package dev.yoda.harmon.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.SynchronousFlag
import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.analysis.isSnapshotFresh
import dev.yoda.harmon.db.HarmonDatabase
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.INIT_PID
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.util.failureDescription
import dev.yoda.harmon.util.printError
import dev.yoda.harmon.util.systemErrorText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.EEXIST
import platform.posix.S_IRWXG
import platform.posix.S_IRWXO
import platform.posix.S_IRWXU
import platform.posix.chmod
import platform.posix.errno
import platform.posix.getenv
import platform.posix.mkdir
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
 * `incremental_vacuum` right after a day of history was deleted would move every freed page at
 * once while the next sample waits. Four times the roughly 2 MiB an hour the window turns over, so
 * the file still shrinks as fast as it grows.
 *
 * It shrinks only that fast, which is the cost of the bound: a user who lowers
 * `historyRetentionDays` from seven days to one frees far more than 8 MiB at the first pass and
 * gets it back over the following day, an hourly slice at a time, rather than at once.
 */
private const val VACUUM_PAGE_LIMIT = 2_048

private const val INCREMENTAL_VACUUM_PRAGMA = "PRAGMA incremental_vacuum($VACUUM_PAGE_LIMIT)"

/** The column `PRAGMA table_info` answers a column's name in: cid, **name**, type, … */
private const val TABLE_INFO_NAME_COLUMN = 1

private const val PROCESS_TABLE = "process"

private const val REPARENTED_AT_COLUMN = "reparented_at"

private const val ADD_REPARENTED_AT =
    "ALTER TABLE $PROCESS_TABLE ADD COLUMN $REPARENTED_AT_COLUMN TEXT"

/**
 * The agent's history: the database file, the connection to it, and the generated queries over it.
 *
 * Built through [openOrNull] rather than through this constructor, because a database that cannot
 * be opened must not stop the agent — history is an addition to monitoring, not a precondition for
 * it. Everything that decides how the file behaves is configured there as well, which is why the
 * tests open through it over a scratch home: what they run is then the production configuration
 * rather than an approximation of it.
 *
 * [logError] carries the failures that are the store's own and nobody else's — a retention pass
 * that threw, which the sample it interrupted must not be blamed for; see `pruneIfDue`.
 */
class HistoryStore(
    val directory: String,
    val driver: SqlDriver,
    private val retentionDays: Long,
    private val intervalSeconds: Long,
    private val logError: (String) -> Unit = ::printError,
) : History {
    val database: HarmonDatabase = HarmonDatabase(driver)

    /**
     * Brings a file an older build wrote up to the schema this build queries, before anything reads
     * it — see [migrateSchema] for why a migration is the store's own work here.
     *
     * Opening stops being lazy because of this. sqliter connects on first use, and a store nothing
     * ever queried used to leave no file behind; the `PRAGMA table_info` below is a use, so the
     * database is now created and connected to while [openOrNull] is still running. That is the
     * point rather than a side effect: a migration that ran at the first write would run inside the
     * transaction of the first sample, and its failure would be reported as a failed write.
     *
     * A migration that throws therefore leaves [openOrNull] to catch it, log it and hand back no
     * store — the agent keeps monitoring without history rather than writing into a shape its
     * queries disagree with.
     *
     * That verdict is fail-closed and does not distinguish a genuinely mismatched schema from a
     * momentary one: a database another process holds locked fails the same way, and costs the
     * whole run its history where a failure at the first write used to cost one sample and be
     * retried. The trade is accepted rather than overlooked. A retry loop here would run on agent
     * start, where nothing is waiting to observe it, and the only writer this file is designed for
     * is the single `harmon run` launchd installs — two agents over one file would also race on the
     * `ALTER TABLE` itself, which no amount of retrying makes safe.
     */
    init {
        migrateSchema(driver)
    }

    /** Samples handed to [record] in this run, which is the only clock the retention pass has. */
    private var recordedSamples = 0L

    /**
     * Writes one whole sample — the system row, every process, the applications that have a bundle,
     * the alerts and what each channel did with them — or writes nothing at all.
     *
     * The single transaction is not only about that all-or-nothing. `last_insert_rowid()` is per
     * connection and the driver keeps a writer apart from a pool of readers, so the id of the
     * sample has to be read inside the transaction that wrote it: read outside, the query lands on
     * a reader where the counter is 0, and every child row then fails the `sample_id` foreign key.
     * The lookups take their ids from a `SELECT` instead, because their inserts are
     * `ON CONFLICT DO NOTHING` and a suppressed insert leaves the counter pointing at whatever row
     * preceded it; see `selectProcessId` in `Processes.sq` for the whole of that reasoning.
     *
     * The applications are walked first and the processes second, because the pid → application map
     * the process rows read has to be complete before the first of them is written. Groups without
     * a bundle resolve to no id at all and their processes to `application_id = NULL`; see
     * [upsertApplication].
     *
     * [alertState] is the agent's own state rather than part of the sample, and it rides along in
     * the same transaction so that the two can never disagree about which sample they belong to. It
     * is taken after `AlertState.commit`, so what lands is the state the next sample starts from.
     * Left out, the tables holding it keep whatever a previous run wrote — the callers with no
     * alert state to speak of, `once` and `diagnose`, are also the ones that must not overwrite it.
     *
     * A process the calculator saw change parent to [INIT_PID] is stamped in the same pass, through
     * `markReparented` rather than through another column of the lookup upsert; `Processes.sq`
     * carries why that is a statement of its own. The `parentPid == INIT_PID` half of the condition
     * is repeated here rather than shared with the alert rule that also applies it.
     * `ProcessUsage.reparentedFrom` reports a change of parent and says nothing about whether it was
     * a loss; each of its two consumers decides that for itself, and a store that trusted the
     * calculator to have filtered already would record whatever a future rule decided to report.
     * `orphanAlerts=false` is one consumer switching itself off and does not reach this one: the
     * stamp is history rather than a notification, and a user who silenced the alert has not asked
     * to stop recording what happened.
     *
     * This is also where retention runs from, about once an hour — every twelfth sample at the
     * default interval, and a different count at any other; see `pruneIfDue` and [shouldPrune].
     * Hanging it off the write path is deliberate — a retention nothing calls is a database that
     * grows forever behind a green test suite.
     */
    override fun record(
        report: MonitoringReport,
        deliveries: List<DeliveryResult>,
        alertState: AlertStateSnapshot?,
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

            /*
             * One pass writes the lookup row, the aggregate row and the membership of every stored
             * group. `ApplicationUsage.processIds` is the only link between a process and its
             * group, so inverting it here puts that membership in `process_sample` — "which Chrome
             * helpers were busy at 3am" is then one join, and the list itself is never stored.
             */
            val applicationIdByPid = buildMap<Int, Long> {
                for (application in usage.applications) {
                    val applicationId = applications.upsertApplication(application) ?: continue
                    applications.insertApplicationUsage(sampleId, applicationId, application)
                    for (pid in application.processIds) {
                        put(pid, applicationId)
                    }
                }
            }

            for (process in usage.processes) {
                val processId = processes.upsertProcess(process)
                processes.insertProcessUsage(
                    sampleId = sampleId,
                    processId = processId,
                    applicationId = applicationIdByPid[process.identity.pid],
                    usage = process,
                )
                if (process.reparentedFrom != null && process.parentPid == INIT_PID) {
                    processes.markReparented(
                        reparented_at = usage.capturedAt.toSqlTimestamp(),
                        id = processId,
                    )
                }
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
     * The age is judged here rather than by the caller because the sampling interval it is judged
     * in is already this store's. A stale snapshot is dropped whole rather than trimmed: the sample
     * counter and the keys are only meaningful together, and half of yesterday's state is not a
     * smaller restore but a wrong one.
     */
    override fun restorableAlertState(now: Instant): AlertStateSnapshot? {
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
     * `application_sample`, `alert` and `alert_delivery` follow without being named. `alert_state`
     * and `agent_state` are the agent's own state rather than history and hang off no sample, which
     * is why they are absent here — restarting must not cost the backoff a failing channel earned.
     *
     * The lookups are cleaned last, because a process is orphaned only once the last sample naming
     * it is gone. Their `NOT IN` form is load-bearing; see the queries themselves.
     *
     * The vacuum runs in a transaction of its own and returns [VACUUM_PAGE_LIMIT] pages at most.
     * Deleting rows in WAL mode frees pages inside the file without shrinking it, and `auto_vacuum`
     * is INCREMENTAL precisely so that this call, and only this call, decides when the space goes
     * back. Separate from the delete so that a vacuum that fails cannot take the retention with it
     * — the space is worth a retry, the samples are already gone either way. Neither half may cost
     * the sample that triggered the pass; that is `pruneIfDue`'s job.
     */
    fun prune(cutoff: String = retentionCutoff(Clock.System.now(), retentionDays)) {
        database.transaction {
            database.samplesQueries.deleteOlderThan(cutoff)
            database.processesQueries.deleteOrphanProcesses()
            database.applicationsQueries.deleteOrphanApplications()
        }
        returnFreedPages()
    }

    /**
     * Closes the connection pool, which is also what checkpoints and removes the `-wal` beside the
     * database.
     *
     * The agent never reaches it: `runForever` does not return, so a daemon always exits with an
     * un-checkpointed WAL that the next open replays. This is for the callers that do end — the
     * tests, and any future reader that opens the file for a query rather than for a run.
     */
    fun close() {
        driver.close()
    }

    /**
     * Hands up to [VACUUM_PAGE_LIMIT] freed pages back to the file system.
     *
     * `PRAGMA incremental_vacuum` emits one row — empty, no columns — for every page it returns,
     * and both halves of that fact decide how it has to be run. It cannot go through
     * `driver.execute`, whose non-query path throws on the first `SQLITE_ROW` rather than
     * vacuuming; and it cannot go through a bare `driver.executeQuery` either, because the driver
     * sends reads to a pool of read-only connections and a vacuum there fails with
     * `SQLITE_READONLY`. Inside a transaction the driver serves everything from the writing
     * connection, which is what makes this work. Nothing is read from the cursor — stepping it is
     * the work.
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
     * Runs the retention pass on the samples [shouldPrune] picks, before the sample that triggered
     * it is written — and never lets the pass cost that sample.
     *
     * Before rather than after so that the pass on sample zero is a true start-up pass: an agent
     * that was down for a week clears the whole stale window before it grows it further.
     *
     * The failure is caught here rather than left to [record] because the two are not the same
     * event. Retention frees space; the write is the monitoring the agent exists for. A pass that
     * throws — a full disk, a vacuum that refuses on a page it cannot move — would otherwise
     * propagate out of [record], be swallowed as a write failure, and punch an hourly hole in the
     * series while telling the user `history write failed`, which points at the wrong thing.
     *
     * The counter advances even when the pass throws: a retention that keeps failing then costs an
     * hour's worth of unreclaimed space per pass, not a pass — and a failure — on every sample
     * from here on.
     */
    private fun pruneIfDue() {
        if (!shouldPrune(recordedSamples++, intervalSeconds)) return

        try {
            prune()
        } catch (failure: Throwable) {
            logError("history retention failed: ${failureDescription(failure)}")
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
            /*
             * The driver is built into a local first so that it can be closed if the constructor
             * throws. Its `init` block migrates, which is a use of the connection, so by then the
             * file is open: passed straight as a constructor argument, a failing migration would
             * be caught below with nothing left holding the driver to close it, and `harmon run`
             * would carry the file descriptor and an un-checkpointed -wal/-shm pair for the life
             * of the daemon.
             */
            val driver = openHistoryDriver(directory)
            try {
                HistoryStore(
                    directory = directory,
                    driver = driver,
                    retentionDays = retentionDays,
                    intervalSeconds = intervalSeconds,
                    logError = logError,
                )
            } catch (failure: Throwable) {
                driver.close()
                throw failure
            }
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
 * afford. `user_version` is deliberately left alone: sqliter maintains it from `Schema.version`,
 * and a value of our own would make the driver run the (empty) generated `migrate()` on every
 * start.
 *
 * Private because `HistoryStore.openOrNull` is the only way a store is ever built, and a caller
 * that could pass its own driver could pass one this configuration does not apply to.
 */
private fun openHistoryDriver(directory: String): SqlDriver = NativeSqliteDriver(
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
 * Adds `reparented_at` to a `process` table that does not have it yet, and does nothing at all to a
 * file that already matches — which is every file this build itself created.
 *
 * One column, named here rather than diffed out of a table of column-to-DDL pairs, because one is
 * how many the project has. The next migration either adds its own guarded statement beside this one
 * or turns the pair into that table; what it must not do is assume this function already generalises.
 *
 * This is the first migration the project has, and it is hand-written because the generated one
 * cannot run. `plugins/sqldelight-gen` drives the SQLDelight compiler with
 * `deriveSchemaFromMigrations = false` and `verifyMigrations = false`, so a `.sqm` file contributes
 * nothing to generation and `Schema.migrate()` is a body that returns `QueryResult.Unit`. The driver
 * would not call it anyway: sqliter maintains `user_version` from `Schema.version`, that version is
 * still 1, and every file an older build wrote already carries 1 — so as far as sqliter is
 * concerned, a database missing `reparented_at` is up to date. Schema evolution belongs to whoever
 * opens the store, and this is it. `docs/history.md` carries the same in prose.
 *
 * The guard is a read of `PRAGMA table_info` rather than a `runCatching` around an `ALTER TABLE`
 * that would fail on every start after the first. sqliter prints the whole stack trace of a failing
 * statement before it throws, so a swallowed exception is still a wall of red in the launchd log on
 * every agent start for the life of the machine — the exception would be caught, the noise would
 * not.
 *
 * `ALTER TABLE … ADD COLUMN` is the cheap half of SQLite's ALTER: it rewrites the table header and
 * no row, so this costs the same on the 25 000-row `process` lookup of a year-old database as on an
 * empty one. Column order does not matter to anything reading it — SQLDelight expands `SELECT *`
 * into an explicit list of names at generation time, so a column appended at the end is read by
 * name like every other.
 */
private fun migrateSchema(driver: SqlDriver) {
    if (REPARENTED_AT_COLUMN in columnNamesOf(driver, PROCESS_TABLE)) return

    driver.execute(identifier = null, sql = ADD_REPARENTED_AT, parameters = 0).value
}

/**
 * The names of [table]'s columns as the open database has them.
 *
 * A table that is not there answers with no rows and is therefore indistinguishable from one with no
 * columns — the empty set. [migrateSchema] then runs its `ALTER TABLE` against a table that does not
 * exist and throws `no such table`, which is the intended outcome rather than an oversight: a file
 * carrying `user_version = 1` and no `process` table is not a database this build can write into,
 * and [HistoryStore.openOrNull] turning that into a logged `history disabled: …` is the whole of the
 * fail-closed contract.
 *
 * `PRAGMA table_info` answers with a row per column, so it has to go through `executeQuery` like
 * `PRAGMA incremental_vacuum` does and for the same reason — sqliter's `execute` throws on the first
 * row a statement returns. Unlike that one it is a read, so the reader pool serves it happily.
 */
private fun columnNamesOf(driver: SqlDriver, table: String): Set<String> = driver.executeQuery(
    identifier = null,
    sql = "PRAGMA table_info($table)",
    mapper = { cursor ->
        val names = mutableSetOf<String>()
        while (cursor.next().value) {
            cursor.getString(TABLE_INFO_NAME_COLUMN)?.let(names::add)
        }
        QueryResult.Value(names)
    },
    parameters = 0,
).value

/**
 * Creates [path] and every missing directory above it, the last one readable by its owner alone.
 *
 * The mode goes on the directory rather than on the database file because sqlite creates
 * `history.db-wal` and `history.db-shm` beside it on every open. Those two carry the same telemetry
 * as the database, and any mode set on them would be gone the next time they were recreated.
 *
 * Only the last component gets that mode. The directories above it are `~/Library` and
 * `~/Library/Application Support`, which every macOS account already has; creating one of them
 * owner-only would be this agent deciding something about the account rather than about its own
 * data, so they are created at the ordinary 0777-minus-umask like any other `mkdir`.
 */
@OptIn(ExperimentalForeignApi::class)
private fun createPrivateDirectory(path: String) {
    val ownerOnly = S_IRWXU.toUShort()
    val inherited = (S_IRWXU or S_IRWXG or S_IRWXO).toUShort()
    val components = path.split('/').filter { it.isNotEmpty() }
    var current = if (path.startsWith('/')) "" else "."

    components.forEachIndexed { index, component ->
        current = "$current/$component"
        val mode = if (index == components.lastIndex) ownerOnly else inherited
        if (mkdir(current, mode) != 0 && errno != EEXIST) {
            throw IllegalStateException("cannot create $current: ${systemErrorText()}")
        }
    }
    if (chmod(path, ownerOnly) != 0) {
        throw IllegalStateException("cannot protect $path: ${systemErrorText()}")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun currentHomeDirectory(): String? = getenv("HOME")?.toKString()
