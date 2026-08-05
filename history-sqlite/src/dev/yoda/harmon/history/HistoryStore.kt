package dev.yoda.harmon.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.SynchronousFlag
import co.touchlab.sqliter.interop.SQLiteExceptionErrorCode
import co.touchlab.sqliter.interop.SqliteErrorType
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
 * How many times one store tries to bring the stored schema to this build's shape before it stops
 * trying.
 *
 * The retry is there for the failure that is about the moment rather than about the file — a lock
 * the previous agent still holds a second after launchd started this one, a journal that had no room
 * — and it is bounded because retrying forever costs more than the failure it survives. sqliter
 * prints the whole stack trace of a statement that throws, which no log level of ours can suppress;
 * and its connection factory drops a connection whose first pragma failed without closing it, which
 * is what a file that is not a database does. An attempt per sample would be a stack trace and a
 * leaked descriptor every interval for the life of a daemon that never returns.
 *
 * Three is one attempt while [HistoryStore.openOrNull] is still opening and two samples after it —
 * ten minutes at the shipped interval, which outlasts any restart overlap and is nowhere near a
 * night of noise.
 */
private const val MIGRATION_ATTEMPTS = 3

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
     * Runs the migration the open could not, and answers whether this build may write to the file.
     *
     * Three outcomes rather than two, and the third is what this exists for:
     *
     * - the stored schema is this build's, either because it always was or because the attempt just
     *   made it so. True, and [record] writes;
     * - the attempt failed and the store has attempts left. The failure escapes as itself and costs
     *   this sample its write, which `HarmonService.recordSafely` reports once and stays quiet
     *   about afterwards; the next sample tries again;
     * - the attempt was the last one, or it was a [HistorySchemaMismatch] — a verdict about the
     *   file, which no number of retries improves. [abandonHistory] says so once, and every sample
     *   after this one answers false here and records nothing.
     *
     * Never a row written into a shape the queries disagree with, in all three: the migration goes
     * first and the write only happens behind a true.
     */
    private fun migrateBeforeWriting(): Boolean {
        if (schemaMigrated) return true
        if (historyAbandoned) return false

        try {
            migrateSchemaOnce()
        } catch (failure: Throwable) {
            if (failure is HistorySchemaMismatch || migrationAttemptsLeft <= 0) {
                abandonHistory(failure)
            }
            throw failure
        }
        return true
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
     * The migration goes first and is a no-op on every sample but the ones that reach a store whose
     * open-time attempt at it could not reach the database. A sample that arrives after the store
     * gave up on the file writes nothing at all and says nothing either; [migrateBeforeWriting]
     * carries which failures buy a retry, and how many.
     */
    override fun record(
        report: MonitoringReport,
        deliveries: List<DeliveryResult>,
        alertState: AlertStateSnapshot?,
    ) {
        if (!migrateBeforeWriting()) return
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
 *
 * Which failure of that statement is a verdict about the file is decided by [isSchemaVerdict], from
 * the error code SQLite answered with. It cannot be decided by where the statement sits, because the
 * read above proves nothing about the write below it: sqldelight sends a `PRAGMA` to its reader pool
 * and an `ALTER` to its transaction pool, so the two run on two connections and the second of them
 * is opened for the first time right there. A file the previous agent still holds locked across a
 * launchd restart answers the read — in WAL a reader is never blocked — and then fails the write
 * with `SQLITE_BUSY`, which is a fact about the moment and not about the schema.
 */
private fun migrateSchema(driver: SqlDriver) {
    if (REPARENTED_AT_COLUMN in columnNamesOf(driver, PROCESS_TABLE)) return

    try {
        driver.execute(identifier = null, sql = ADD_REPARENTED_AT, parameters = 0).value
    } catch (failure: Throwable) {
        if (!isSchemaVerdict(failure)) throw failure
        throw HistorySchemaMismatch(failure)
    }
}

/**
 * Whether [failure] is SQLite refusing the `ALTER TABLE` over the shape of the database rather than
 * over the state of the machine.
 *
 * `no such table: process` and `duplicate column name: reparented_at` both fail while the statement
 * is being compiled, under the generic `SQLITE_ERROR`, and both will fail the same way on the next
 * sample and every sample after it. Everything else the write can raise says nothing about the
 * stored schema and is worth another try: `SQLITE_BUSY` from a lock that outlived the busy timeout,
 * `SQLITE_FULL`, `SQLITE_READONLY`, `SQLITE_IOERR`, or a `SQLITE_CANTOPEN` from the connection this
 * statement is the first to need.
 *
 * Narrow on purpose rather than fail-closed. A permanent failure this reads as transient still ends
 * in history disabled, [MIGRATION_ATTEMPTS] attempts later and by a different route; a transient one
 * read as permanent costs the whole run its history for a lock that was gone five minutes later.
 */
private fun isSchemaVerdict(failure: Throwable): Boolean {
    if (failure !is SQLiteExceptionErrorCode) return false

    /*
     * `errorType` maps the code through an enum and throws on one it does not know. Answered as
     * "not a verdict" rather than allowed to replace the failure being classified with a stranger.
     */
    val type = try {
        failure.errorType
    } catch (unknownCode: IllegalArgumentException) {
        return false
    }
    return type == SqliteErrorType.SQLITE_ERROR
}

/**
 * A database this build reached and cannot bring to the shape its queries expect.
 *
 * The one failure of the migration that is a verdict about the file rather than about the moment. A
 * `process` table that is missing, or one an `ALTER TABLE` cannot widen, will be missing and refused
 * again on the next sample, so retrying it would write nothing and report the same thing forever:
 * this is the failure that disables history at the first attempt, whether it arrives at
 * [HistoryStore.openOrNull] or later at a write. Every other one buys the retry
 * [MIGRATION_ATTEMPTS] bounds.
 *
 * Private because nothing outside this file has ever caught it. It is a classification the store
 * makes for itself, between two answers it gives on its own — a caller of `openOrNull` sees a store
 * or a null either way.
 */
private class HistorySchemaMismatch(cause: Throwable) : IllegalStateException(
    "the stored schema cannot be migrated: ${failureDescription(cause)}",
    cause,
)

/**
 * The names of [table]'s columns as the open database has them.
 *
 * A table that is not there answers with no rows and is therefore indistinguishable from one with no
 * columns — the empty set. [migrateSchema] then runs its `ALTER TABLE` against a table that does not
 * exist and throws `no such table`, which is the intended outcome rather than an oversight: a file
 * carrying `user_version = 1` and no `process` table is not a database this build can write into,
 * and the [HistorySchemaMismatch] that becomes a logged `history disabled: …` is the whole of the
 * fail-closed contract.
 *
 * This read is the first use of the connection, so it is also where a database nothing can open
 * fails. That failure is not a verdict about the schema and is not classified as one — see
 * [isSchemaVerdict], which reads the error code rather than the position of the statement.
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
