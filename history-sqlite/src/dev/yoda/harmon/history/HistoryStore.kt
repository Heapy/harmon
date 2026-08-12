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

private const val HISTORY_DIRECTORY = "Library/Application Support/Harmon"

private const val HISTORY_DATABASE_NAME = "history.db"

private const val AUTO_VACUUM_PRAGMA = "PRAGMA auto_vacuum = INCREMENTAL"

/** Caps each synchronous retention pass to about 8 MiB of 4 KiB pages. */
private const val VACUUM_PAGE_LIMIT = 2_048

private const val INCREMENTAL_VACUUM_PRAGMA = "PRAGMA incremental_vacuum($VACUUM_PAGE_LIMIT)"

/** Optional history store; open, migration, retention, and write failures never stop monitoring. */
class HistoryStore(
    val directory: String,
    val driver: SqlDriver,
    private val retentionDays: Long,
    private val intervalSeconds: Long,
    private val logError: (String) -> Unit = ::printError,
) : History {
    val database: HarmonDatabase = HarmonDatabase(driver)

    private var schemaMigrated = false

    private var migrationAttemptsLeft = MIGRATION_ATTEMPTS

    /** Separate from the retry count so a changed budget cannot silently disable a fresh store. */
    private var historyAbandoned = false

    private var recordedSamples = 0L

    private fun migrateSchemaOnce() {
        if (schemaMigrated) return

        migrationAttemptsLeft--
        migrateSchema(driver)
        schemaMigrated = true
    }

    /** Retries transient migration failures, but permanently abandons a bad shape or spent budget. */
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

    /** Sets the no-op state before logging and closing so either operation may fail safely. */
    private fun abandonHistory(failure: Throwable) {
        historyAbandoned = true
        logError("history disabled: ${failureDescription(failure)}")
        driver.close()
    }

    /**
     * Writes the sample, children, deliveries, and optional alert state atomically. The sample id
     * must be read inside this writer transaction because `last_insert_rowid()` is per connection.
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

            // Build group ids before process rows so every stored membership is available.
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

    /** Restores the counter and keys together; a stale snapshot is discarded whole. */
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

    /** Cascades sample deletion, then removes unused lookups; alert state is not sample history. */
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
     * incremental_vacuum returns rows and mutates the file, so it needs executeQuery on the writer
     * connection. A bare query would use the read-only pool; execute would reject SQLITE_ROW.
     */
    private fun returnFreedPages() {
        database.transaction {
            driver.executeQuery(
                identifier = null,
                sql = INCREMENTAL_VACUUM_PRAGMA,
                mapper = { cursor ->
                    while (cursor.next().value) {
                        // Stepping the cursor performs the vacuum.
                    }
                    QueryResult.Unit
                },
                parameters = 0,
            ).value
        }
    }

    /** A failed pass is logged separately and still advances the schedule, preserving this sample. */
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
         * Never throws. Permanent schema mismatches return null; transient open-time failures keep
         * the store alive for bounded retries at subsequent writes.
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
                // No store will be returned to own this connection.
                driver.close()
                throw mismatch
            } catch (failure: Throwable) {
                // Keep the pool so its next borrow can establish a fresh connection.
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
 * Enables foreign keys on every pooled connection and sets incremental auto-vacuum before WAL can
 * freeze a new file's header. NORMAL synchronous mode may lose only the latest sample on panic.
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

/** Protects the directory, not just the database, because WAL and SHM files carry the same data. */
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
