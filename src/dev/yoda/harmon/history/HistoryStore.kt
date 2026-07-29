package dev.yoda.harmon.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.SynchronousFlag
import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.analysis.isSnapshotFresh
import dev.yoda.harmon.db.HarmonDatabase
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.util.failureDescription
import dev.yoda.harmon.util.printError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.S_IRWXU
import platform.posix.chmod
import platform.posix.errno
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.strerror
import kotlin.time.Clock
import kotlin.time.Instant

/** Where the database lives, next to the HTML reports under the home directory. */
private const val HISTORY_DIRECTORY = "Library/Application Support/Harmon"

private const val HISTORY_DATABASE_NAME = "history.db"

private const val AUTO_VACUUM_PRAGMA = "PRAGMA auto_vacuum = INCREMENTAL"

/**
 * Pages one retention pass may hand back to the file system, at 4 KiB each — about 8 MiB.
 *
 * Bounded on purpose. This runs on the thread that samples the machine, and an unbounded
 * `incremental_vacuum` right after a day of history was deleted would move every freed page at once
 * while the next sample waits. Four times the roughly 2 MiB an hour the window turns over, so the
 * file still shrinks as fast as it grows.
 */
private const val VACUUM_PAGE_LIMIT = 2_048

private const val INCREMENTAL_VACUUM_PRAGMA = "PRAGMA incremental_vacuum($VACUUM_PAGE_LIMIT)"

/**
 * The agent's history: the database file, the connection to it, and the generated queries over it.
 *
 * Built through [openOrNull] rather than through this constructor, because a database that cannot be
 * opened must not stop the agent — history is an addition to monitoring, not a precondition for it.
 * The constructor stays the injection seam: everything that decides how the file behaves lives in
 * [openHistoryDriver], so a test opening a store over a scratch home runs the production
 * configuration instead of an approximation of it.
 */
class HistoryStore(
    val directory: String,
    val driver: SqlDriver,
    private val retentionDays: Long,
    private val intervalSeconds: Long,
) {
    val database: HarmonDatabase = HarmonDatabase(driver)

    /** Samples handed to [record] in this run, which is the only clock the retention pass has. */
    private var recordedSamples = 0L

    /**
     * Writes one whole sample — the system row, every process, the applications that have a bundle,
     * the alerts and what each channel did with them — or writes nothing at all.
     *
     * The single transaction is not only about that all-or-nothing. `last_insert_rowid()` is per
     * connection and the driver keeps a writer apart from a pool of readers, so the id of the sample
     * has to be read inside the transaction that wrote it: read outside, the query lands on a reader
     * where the counter is 0, and every child row then fails the `sample_id` foreign key. The lookups
     * take their ids from a `SELECT` instead, because their inserts are `ON CONFLICT DO NOTHING` and
     * a suppressed insert leaves the counter pointing at whatever row preceded it.
     *
     * Applications are resolved before the processes: `process_sample.application_id` references the
     * lookup, so the row it names has to exist first. Groups without a bundle resolve to no id at all
     * and their processes to `application_id = NULL`; see [upsertApplication].
     *
     * [alertState] is the agent's own state rather than part of the sample, and it rides along in the
     * same transaction so that the two can never disagree about which sample they belong to. It is
     * taken after `AlertState.commit`, so what lands is the state the next sample starts from. Left
     * out, the tables holding it keep whatever a previous run wrote — the callers with no alert state
     * to speak of, `once` and `diagnose`, are also the ones that must not overwrite it.
     *
     * This is also where retention runs from, on roughly every twelfth sample; see `pruneIfDue`.
     * Hanging it off the write path is deliberate — a retention nothing calls is a database that
     * grows forever behind a green test suite.
     */
    fun record(
        report: MonitoringReport,
        deliveries: List<DeliveryResult> = emptyList(),
        alertState: AlertStateSnapshot? = null,
    ) {
        pruneIfDue()

        val usage = report.usage
        val samples = database.samplesQueries
        val processes = database.processesQueries
        val applications = database.applicationsQueries
        val alerts = database.alertsQueries

        database.transaction {
            samples.insertSample(usage)
            val sampleId = samples.lastInsertedId().executeAsOne()

            val applicationIds = buildMap {
                for (application in usage.applications) {
                    val applicationId = applications.upsertApplication(application) ?: continue
                    put(application.id, applicationId)
                }
            }
            val applicationIdByPid = applicationIdsByPid(usage.applications, applicationIds)

            for (process in usage.processes) {
                processes.insertProcessUsage(
                    sampleId = sampleId,
                    processId = processes.upsertProcess(process),
                    applicationId = applicationIdByPid[process.identity.pid],
                    usage = process,
                )
            }
            for (application in usage.applications) {
                val applicationId = applicationIds[application.id] ?: continue
                applications.insertApplicationUsage(sampleId, applicationId, application)
            }

            report.alerts.forEach { alerts.insertReportedAlert(sampleId, it) }
            report.suppressedAlertKeys.forEach { alerts.insertSuppressedAlert(sampleId, it) }
            deliveries.forEach { alerts.insertDeliveryResult(sampleId, it) }

            alertState?.let { snapshot ->
                alerts.replaceAlertState(snapshot)
                samples.upsertAgentState(
                    sample_counter = snapshot.sampleCounter,
                    last_sample_at = usage.capturedAt.toSqlTimestamp(),
                )
            }
        }
    }

    /**
     * The alert state a previous run left behind, or null when there is none or it is too old to
     * apply — see [isSnapshotFresh], which this measures against the sample it was written with.
     *
     * The age is judged here rather than by the caller because the sampling interval it is judged in
     * is already this store's. A stale snapshot is dropped whole rather than trimmed: the sample
     * counter and the keys are only meaningful together, and half of yesterday's state is not a
     * smaller restore but a wrong one.
     */
    fun restorableAlertState(now: Instant = Clock.System.now()): AlertStateSnapshot? {
        val agent = database.samplesQueries.selectAgentState().executeAsOneOrNull() ?: return null
        if (!isSnapshotFresh(Instant.parse(agent.last_sample_at), now, intervalSeconds)) {
            return null
        }

        return AlertStateSnapshot(
            sampleCounter = agent.sample_counter,
            keys = database.alertsQueries.selectAlertKeyStates(),
        )
    }

    /**
     * Drops every sample captured before [cutoff], everything hanging off those samples, and the
     * lookup rows nothing points at any more.
     *
     * The samples go out on their own: `sample_id` cascades, so `process_sample`,
     * `application_sample`, `alert` and `alert_delivery` follow without being named. `alert_state` and
     * `agent_state` are the agent's own state rather than history and hang off no sample, which is why
     * they are absent here — restarting must not cost the backoff a failing channel earned.
     *
     * The lookups are cleaned last, because a process is orphaned only once the last sample naming it
     * is gone. Their `NOT IN` form is load-bearing; see the queries themselves.
     *
     * The vacuum runs in a transaction of its own and returns [VACUUM_PAGE_LIMIT] pages at most.
     * Deleting rows in WAL mode frees pages inside the file without shrinking it, and `auto_vacuum` is
     * INCREMENTAL precisely so that this call, and only this call, decides when the space goes back.
     * Separate from the delete so that a vacuum that fails cannot take the retention with it — the
     * space is worth a retry, the samples are already gone either way.
     */
    fun prune(cutoff: String = retentionCutoff(Clock.System.now(), retentionDays)) {
        database.transaction {
            database.samplesQueries.deleteOlderThan(cutoff)
            database.processesQueries.deleteOrphanProcesses()
            database.applicationsQueries.deleteOrphanApplications()
        }
        returnFreedPages()
    }

    fun close() {
        driver.close()
    }

    /**
     * Hands up to [VACUUM_PAGE_LIMIT] freed pages back to the file system.
     *
     * `PRAGMA incremental_vacuum` emits one row — empty, no columns — for every page it returns, and
     * both halves of that fact decide how it has to be run. It cannot go through `driver.execute`,
     * whose non-query path throws on the first `SQLITE_ROW` rather than vacuuming; and it cannot go
     * through a bare `driver.executeQuery` either, because the driver sends reads to a pool of
     * read-only connections and a vacuum there fails with `SQLITE_READONLY`. Inside a transaction the
     * driver serves everything from the writing connection, which is what makes this work. Nothing is
     * read from the cursor — stepping it is the work.
     *
     * Both failures are quiet in a way that matters: the first only appears once there is a page to
     * reclaim, so a database small enough that a delete frees nothing at all never shows it.
     */
    private fun returnFreedPages() {
        database.transaction {
            driver.executeQuery(
                identifier = null,
                sql = INCREMENTAL_VACUUM_PRAGMA,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        /* One step, one page. */
                    }
                    QueryResult.Unit
                },
                parameters = 0,
            ).value
        }
    }

    /**
     * Runs the retention pass on the samples [shouldPrune] picks, before the sample that triggered it
     * is written.
     *
     * Before rather than after so that the pass on sample zero is a true start-up pass — an agent that
     * was down for a week clears the whole stale window before it grows it further. The counter
     * advances even when the pass throws: a retention that fails on a full disk costs this one sample,
     * not every sample after it.
     */
    private fun pruneIfDue() {
        if (shouldPrune(recordedSamples++, intervalSeconds)) {
            prune()
        }
    }

    companion object {
        /**
         * The store under [homeDirectory], keeping [retentionDays] of samples taken every
         * [intervalSeconds], or null when the database cannot be opened.
         *
         * Never throws. A directory the user has locked down, a full disk or an unset `HOME` costs
         * the run its history and nothing else, and the reason is logged here because nothing
         * downstream will ever hold this store to ask about it.
         *
         * Neither of the two numbers has a default, because both belong to the caller's
         * configuration and a wrong retention is invisible: too short silently deletes history the
         * user asked to keep. A configured retention of zero means no history at all, and is the
         * caller's reason not to open a store rather than a value to pass here.
         */
        fun openOrNull(
            retentionDays: Long,
            intervalSeconds: Long,
            homeDirectory: String? = currentHomeDirectory(),
            logError: (String) -> Unit = ::printError,
        ): HistoryStore? = try {
            val home = homeDirectory ?: error("HOME is not set")
            val directory = "$home/$HISTORY_DIRECTORY"
            createPrivateDirectory(directory)
            HistoryStore(
                directory = directory,
                driver = openHistoryDriver(directory),
                retentionDays = retentionDays,
                intervalSeconds = intervalSeconds,
            )
        } catch (failure: Throwable) {
            logError("history disabled: ${failureDescription(failure)}")
            null
        }
    }
}

/**
 * A driver over `<directory>/history.db`, configured the one way the schema depends on.
 *
 * Everything goes through `onConfiguration` rather than through pragmas issued after opening,
 * because the driver keeps a transaction pool and a reader pool and a one-shot pragma would land on
 * one connection of the two. Two of these settings are invisible until they are wrong:
 *
 * - foreign keys are **off** on a fresh sqlite connection, and the retention cascade from `sample`
 *   to its child rows is built on them. Without this flag the cascade silently does nothing;
 * - `auto_vacuum` can only be changed while the file is still empty, and sqliter applies
 *   `journal_mode` — WAL, its default — before it creates the schema. `onCreateConnection` is the
 *   only hook that runs before that: open → onCreateConnection → synchronous → foreign keys →
 *   journal_mode → migrateIfNeeded. Set after WAL has written the file header the pragma is a no-op
 *   forever, curable only by a full `VACUUM`, and retention would then delete rows without ever
 *   returning a page to the file system.
 *
 * `synchronousFlag` gives up the last sample to a kernel panic, which a 300-second interval can
 * afford. `user_version` is deliberately left alone: sqliter maintains it from `Schema.version`, and
 * a value of our own would make the driver run the (empty) generated `migrate()` on every start.
 */
fun openHistoryDriver(directory: String): SqlDriver = NativeSqliteDriver(
    schema = HarmonDatabase.Schema,
    name = HISTORY_DATABASE_NAME,
    onConfiguration = { configuration ->
        configuration.copy(
            extendedConfig = configuration.extendedConfig.copy(
                foreignKeyConstraints = true,
                basePath = directory,
                synchronousFlag = SynchronousFlag.NORMAL,
            ),
            lifecycleConfig = configuration.lifecycleConfig.copy(
                onCreateConnection = { connection -> connection.rawExecSql(AUTO_VACUUM_PRAGMA) },
            ),
        )
    },
)

/**
 * Creates [path] and every missing directory above it, the last one readable by its owner alone.
 *
 * The mode goes on the directory rather than on the database file because sqlite creates
 * `history.db-wal` and `history.db-shm` beside it on every open. Those two carry the same telemetry
 * as the database, and any mode set on them would be gone the next time they were recreated.
 */
@OptIn(ExperimentalForeignApi::class)
private fun createPrivateDirectory(path: String) {
    val mode = S_IRWXU.toUShort()
    var current = if (path.startsWith('/')) "" else "."

    for (component in path.split('/')) {
        if (component.isEmpty()) continue
        current = "$current/$component"
        if (mkdir(current, mode) != 0 && errno != EEXIST) {
            throw IllegalStateException("cannot create $current: ${systemErrorText()}")
        }
    }
    if (chmod(path, mode) != 0) {
        throw IllegalStateException("cannot protect $path: ${systemErrorText()}")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun systemErrorText(): String {
    val code = errno
    return strerror(code)?.toKString() ?: "error $code"
}

@OptIn(ExperimentalForeignApi::class)
private fun currentHomeDirectory(): String? = getenv("HOME")?.toKString()
