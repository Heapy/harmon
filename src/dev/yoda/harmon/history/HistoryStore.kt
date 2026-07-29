package dev.yoda.harmon.history

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.SynchronousFlag
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

/** Where the database lives, next to the HTML reports under the home directory. */
private const val HISTORY_DIRECTORY = "Library/Application Support/Harmon"

private const val HISTORY_DATABASE_NAME = "history.db"

private const val AUTO_VACUUM_PRAGMA = "PRAGMA auto_vacuum = INCREMENTAL"

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
) {
    val database: HarmonDatabase = HarmonDatabase(driver)

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
     */
    fun record(report: MonitoringReport, deliveries: List<DeliveryResult> = emptyList()) {
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
        }
    }

    fun close() {
        driver.close()
    }

    companion object {
        /**
         * The store under [homeDirectory], or null when it cannot be opened.
         *
         * Never throws. A directory the user has locked down, a full disk or an unset `HOME` costs
         * the run its history and nothing else, and the reason is logged here because nothing
         * downstream will ever hold this store to ask about it.
         */
        fun openOrNull(
            homeDirectory: String? = currentHomeDirectory(),
            logError: (String) -> Unit = ::printError,
        ): HistoryStore? = try {
            val home = homeDirectory ?: error("HOME is not set")
            val directory = "$home/$HISTORY_DIRECTORY"
            createPrivateDirectory(directory)
            HistoryStore(directory, openHistoryDriver(directory))
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
