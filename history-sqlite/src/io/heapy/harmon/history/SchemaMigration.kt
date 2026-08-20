package io.heapy.harmon.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.interop.SQLiteExceptionErrorCode
import co.touchlab.sqliter.interop.SqliteErrorType
import io.heapy.harmon.util.failureDescription

/** cid, name, type, notnull, default, primary-key. */
private const val TABLE_INFO_NAME_COLUMN = 1

private const val PROCESS_TABLE = "process"

private const val REPARENTED_AT_COLUMN = "reparented_at"

private const val ADD_REPARENTED_AT =
    "ALTER TABLE $PROCESS_TABLE ADD COLUMN $REPARENTED_AT_COLUMN TEXT"

/** One open-time attempt plus two write-time retries; bounds noise and sqliter connection leaks. */
internal const val MIGRATION_ATTEMPTS = 3

/**
 * Hand-written because generated migrations are disabled. The guard avoids a failing ALTER on
 * every start; a second read resolves another writer adding the column between read and write.
 */
internal fun migrateSchema(driver: SqlDriver) {
    if (REPARENTED_AT_COLUMN in columnNamesOf(driver, PROCESS_TABLE)) return

    try {
        driver.execute(identifier = null, sql = ADD_REPARENTED_AT, parameters = 0).value
    } catch (failure: Throwable) {
        if (!isSchemaVerdict(failure)) throw failure
        if (schemaAlreadyMigrated(driver)) return
        throw HistorySchemaMismatch(failure)
    }
}

/** `duplicate column` and a missing table share SQLITE_ERROR, so inspect the shape again. */
private fun schemaAlreadyMigrated(driver: SqlDriver): Boolean =
    runCatching { REPARENTED_AT_COLUMN in columnNamesOf(driver, PROCESS_TABLE) }.getOrDefault(false)

/** Only SQLITE_ERROR indicates schema shape; busy, full, read-only, and I/O failures remain retryable. */
private fun isSchemaVerdict(failure: Throwable): Boolean {
    if (failure !is SQLiteExceptionErrorCode) return false

    val type = try {
        failure.errorType
    } catch (unknownCode: IllegalArgumentException) {
        // Preserve the original failure when sqliter cannot map a future SQLite code.
        return false
    }
    return type == SqliteErrorType.SQLITE_ERROR
}

/** A reachable database whose shape this build cannot repair. */
internal class HistorySchemaMismatch(cause: Throwable) : IllegalStateException(
    "the stored schema cannot be migrated: ${failureDescription(cause)}",
    cause,
)

/** Missing tables yield an empty set; table_info returns rows and therefore requires executeQuery. */
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
