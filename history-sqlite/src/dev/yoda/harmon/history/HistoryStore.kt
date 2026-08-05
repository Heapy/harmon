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
     * Whether [migrateSchema] has run through against this database, which is the whole of what
     * makes an unreachable database survivable — see [migrateSchemaOnce].
     */
    private var schemaMigrated = false

    /** Attempts at that migration this store has left, out of [MIGRATION_ATTEMPTS]. */
    private var migrationAttemptsLeft = MIGRATION_ATTEMPTS

    /**
     * Whether the store has given up on the file, which is the whole of what makes [record] write
     * nothing at all.
     *
     * A flag of its own rather than a spent [migrationAttemptsLeft], because the two would only
     * agree while the budget is what it is today: lowered to one, a store handed back by
     * [openOrNull] would arrive with the count already spent and go quiet without ever saying why.
     * Only [abandonHistory] sets this, and it says why first.
     */
    private var historyAbandoned = false

    /** Samples handed to [record] in this run, which is the only clock the retention pass has. */
    private var recordedSamples = 0L

    /**
     * Brings a file an older build wrote up to the schema this build queries, once per run and
     * before anything of this build writes into it — see [migrateSchema] for why a migration is the
     * store's own work here, and which of its failures is a verdict about the file rather than
     * about the moment.
     *
     * [openOrNull] calls this while it is still opening, which is what stops opening from being
     * lazy: sqliter connects on first use, the `PRAGMA table_info` this reads is a use, and the
     * database is therefore created and connected to before [openOrNull] returns, where a store
     * nothing ever queried used to leave no file behind. That is the point rather than a side
     * effect — a migration deferred to the first sample would run inside that sample's transaction,
     * and its failure would be reported as a failed write.
     *
     * [record] calls it again through [migrateBeforeWriting], because the open-time attempt is
     * allowed to fail without costing the run its history: a file another process holds locked
     * across a launchd restart, a stale `-shm`, a disk with no room for the journal are all gone by
     * the next sample. Every attempt is counted, whoever makes it — a store gets
     * [MIGRATION_ATTEMPTS] and no more.
     */
    private fun migrateSchemaOnce() {
        if (schemaMigrated) return

        migrationAttemptsLeft--
        migrateSchema(driver)
        schemaMigrated = true
    }

    /**
     * Runs the migration the open could not, and returns only once the stored schema is one this
     * build may write into.
     *
     * One outcome and one failure, rather than a boolean that also throws. It returns when the
     * stored schema is this build's — either because it always was, or because the attempt just
     * made it so — and the caller writes behind it; anything else leaves as the failure itself,
     * which costs this sample its write. `HarmonService.recordSafely` reports that once and stays
     * quiet about it afterwards, and the next sample tries again.
     *
     * What the failure additionally decides is whether there will be a next attempt at all. A
     * [HistorySchemaMismatch] is a verdict about the file, which no number of retries improves, and
     * a spent budget is the retry itself running out; either way [abandonHistory] says so once
     * before the failure is rethrown, and [historyAbandoned] keeps every later sample out of here.
     *
     * Never a row written into a shape the queries disagree with, in any of them: the migration
     * goes first, and a write is only ever reached by returning from this.
     */
    private fun migrateBeforeWriting() {
        try {
            migrateSchemaOnce()
        } catch (failure: Throwable) {
            if (failure is HistorySchemaMismatch || migrationAttemptsLeft <= 0) {
                abandonHistory(failure)
            }
            throw failure
        }
    }

    /**
     * Gives up on the file: says why once, closes the connection, and leaves [record] a no-op.
     *
     * The close is what makes this more than a flag. `runForever` never returns, so a driver left
     * open over a database nothing will write to again would carry its descriptors and an
     * un-checkpointed `-wal`/`-shm` pair for the life of the daemon — the same leak [openOrNull]
     * closes the driver to avoid when it hands back no store at all.
     *
     * The flag is set before either, so that a close which throws still leaves history off, and the
     * reason is logged before the close, so that a close which throws cannot take the one line the
     * user needs down with it.
     */
    private fun abandonHistory(failure: Throwable) {
        historyAbandoned = true
        logError("history disabled: ${failureDescription(failure)}")
        driver.close()
    }

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
     *
     * A sample that arrives after the store gave up on the file is where this ends: it writes
     * nothing at all and says nothing either, because the one line that explains it was logged at
     * the moment history was given up on. Everything before that goes through the migration first,
     * which is a no-op on every sample but the ones that reach a store whose open-time attempt at it
     * could not reach the database; [migrateBeforeWriting] carries which failures buy a retry, and
     * how many.
     */
    override fun record(
        report: MonitoringReport,
        deliveries: List<DeliveryResult>,
        alertState: AlertStateSnapshot?,
    ) {
        if (historyAbandoned) return
        migrateBeforeWriting()
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
         * Never throws. A directory the user has locked down or an unset `HOME` costs the run its
         * history and nothing else, and the reason is logged here because nothing downstream will
         * ever hold this store to ask about it.
         *
         * A database that merely could not be reached at this moment does not cost the run its
         * history: the store comes back unmigrated, the reason is logged once, and the next writes
         * try again — [MIGRATION_ATTEMPTS] times over the run, after which the store says
         * `history disabled: …` for itself and stops. Only a schema this build cannot repair —
         * [HistorySchemaMismatch] — hands back no store at all, at the first attempt.
         * [migrateBeforeWriting] carries which failure is which and why the two are answered
         * differently.
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
            val driver = openHistoryDriver(directory)
            val store = HistoryStore(
                directory = directory,
                driver = driver,
                retentionDays = retentionDays,
                intervalSeconds = intervalSeconds,
                logError = logError,
            )
            try {
                store.migrateSchemaOnce()
            } catch (mismatch: HistorySchemaMismatch) {
                /*
                 * Closed here because the caller is about to be handed null and will have nothing
                 * left to close it with. The migration reached the file, so by now the driver holds
                 * a connection: leaked, `harmon run` would carry that file descriptor and an
                 * un-checkpointed -wal/-shm pair for the life of the daemon.
                 */
                driver.close()
                throw mismatch
            } catch (failure: Throwable) {
                /*
                 * Kept open rather than closed. A connection that was never established leaves no
                 * descriptor to leak, and the pool builds a fresh one on the next borrow — which is
                 * the retry the store's first write depends on.
                 */
                logError(
                    "history unreachable: ${failureDescription(failure)}; " +
                        "retrying at the first write",
                )
            }
            store
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
