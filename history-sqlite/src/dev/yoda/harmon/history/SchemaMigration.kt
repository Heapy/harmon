package dev.yoda.harmon.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.sqliter.interop.SQLiteExceptionErrorCode
import co.touchlab.sqliter.interop.SqliteErrorType
import dev.yoda.harmon.util.failureDescription

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
 * — and it is bounded because retrying forever leaks a connection per attempt. sqliter's
 * `NativeDatabaseManager.createConnection` closes a connection whose `migrateIfNeeded` threw and
 * nothing else; one whose `onCreateConnection` pragma threw — which is what a file that is not a
 * database does to the `PRAGMA auto_vacuum` `openHistoryDriver` sets — is dropped still open. An
 * attempt per sample would be a leaked descriptor every interval for the life of a daemon that never
 * returns.
 *
 * The stack trace sqliter prints beside it is noise rather than a second reason, and it is noise
 * this code could switch off: `DatabaseConfiguration.Logging` is a public data class over a public
 * `Logger`, the default `WarningLogger` prints only because its `eActive` is true, and
 * `openHistoryDriver` already owns the `onConfiguration` hook that would replace it. It is left in
 * place on purpose. Bounded at three attempts it is three traces a run, it is the only thing that
 * says which call of ours the failure came out of, and switching it off would also silence every
 * write failure later in the run — those are not bounded by anything.
 *
 * Three is one attempt while [HistoryStore.openOrNull] is still opening and two samples after it —
 * ten minutes at the shipped interval, which outlasts any restart overlap and is nowhere near a
 * night of noise.
 */
internal const val MIGRATION_ATTEMPTS = 3

/**
 * Adds `reparented_at` to a `process` table that does not have it yet, and does nothing at all to a
 * file that already matches — which is every file this build itself created.
 *
 * A file of its own, and the file the next migration is written into. One column, named here rather
 * than diffed out of a table of column-to-DDL pairs, because one is how many the project has. The
 * next migration either adds its own guarded statement beside this one or turns the pair into that
 * table; what it must not do is assume this function already generalises.
 *
 * What lives here is the migration itself — the statement, the classification of its failures, and
 * the reads both depend on. How often it may be retried and what happens once it is out of attempts
 * belong to whoever owns the connection and the log, which is [HistoryStore].
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
 * the error code SQLite answered with, and then by [schemaAlreadyMigrated], from the table itself.
 * It cannot be decided by where the statement sits, because the read above proves nothing about the
 * write below it: sqldelight sends a `PRAGMA` to its reader pool and an `ALTER` to its transaction
 * pool, so the two run on two connections and the second of them is opened for the first time right
 * there. A file the previous agent still holds locked across a launchd restart answers the read — in
 * WAL a reader is never blocked — and then fails the write with `SQLITE_BUSY`, which is a fact about
 * the moment and not about the schema.
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

/**
 * Whether the column is on the table after all, asked once before a failed `ALTER TABLE` is called a
 * verdict about the file.
 *
 * `duplicate column name: reparented_at` and `no such table: process` reach [isSchemaVerdict] as one
 * error code and are opposite events. The first says the column this migration exists to add is
 * already there — the shape the queries want, which makes it a migration that succeeded rather than
 * one that failed. So the question the code asks is the one it actually cares about, read off the
 * table, rather than the wording of a message.
 *
 * The race that produces it is the same two connections the migration is built around. The guard
 * read goes to the reader pool and the `ALTER` to the transaction pool, so any other writer — a
 * second agent, an overlapping launchd restart — that widens the table between the two leaves this
 * one holding a stale answer. Without this second read, losing that race would disable history for
 * the whole run over a database that is already correct.
 *
 * A read that throws answers false. The file has stopped answering by then, and the failure that
 * came out of the `ALTER` is the better one to report.
 */
private fun schemaAlreadyMigrated(driver: SqlDriver): Boolean =
    runCatching { REPARENTED_AT_COLUMN in columnNamesOf(driver, PROCESS_TABLE) }.getOrDefault(false)

/**
 * Whether [failure] is SQLite refusing the `ALTER TABLE` over the shape of the database rather than
 * over the state of the machine.
 *
 * `no such table: process` and `duplicate column name: reparented_at` both fail while the statement
 * is being compiled, under the generic `SQLITE_ERROR`, and the code alone cannot tell the two apart
 * — which is why this answers "the schema decides" rather than "history is over", and
 * [schemaAlreadyMigrated] asks the table which of the two it was. Everything else the write can
 * raise says nothing about the stored schema and is worth another try: `SQLITE_BUSY` from a lock
 * that outlived the busy timeout, `SQLITE_FULL`, `SQLITE_READONLY`, `SQLITE_IOERR`, or a
 * `SQLITE_CANTOPEN` from the connection this statement is the first to need.
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
 * `process` table that is missing, or one an `ALTER TABLE` cannot widen and that still lacks the
 * column afterwards, will be missing and refused again on the next sample, so retrying it would
 * write nothing and report the same thing forever: this is the failure that disables history at the
 * first attempt, whether it arrives at [HistoryStore.openOrNull] or later at a write. Every other
 * one buys the retry [MIGRATION_ATTEMPTS] bounds.
 *
 * Internal rather than public because nothing outside this module has ever caught it. It is a
 * classification the store makes for itself, between two answers it gives on its own — a caller of
 * `openOrNull` sees a store or a null either way.
 */
internal class HistorySchemaMismatch(cause: Throwable) : IllegalStateException(
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
