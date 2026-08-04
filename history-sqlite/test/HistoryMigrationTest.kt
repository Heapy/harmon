import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HISTORY_DIRECTORY = "Library/Application Support/Harmon"

private const val HISTORY_DATABASE_NAME = "history.db"

private const val REPARENTED_AT = "reparented_at"

/** The pid the pre-migration row is written under, and the parent it was written with. */
private const val STORED_PID = 44_559L

private const val STORED_PARENT_PID = 44_268L

private const val STORED_NAME = "written-before-the-column-existed"

/**
 * Covers the one thing `Schema.create()` can never be asked about: what happens to a database file
 * an older build already wrote.
 *
 * Every test here goes through [withHistoryStore] over a scratch home, because the migration is part
 * of opening the store and an in-memory driver has no file to have been written before.
 */
class HistoryMigrationTest {

    @Test
    fun openingAnOlderDatabaseAddsTheColumn() = withScratchHome { home ->
        writePreMigrationDatabase(home)

        withHistoryStore(home) { store ->
            assertTrue(
                REPARENTED_AT in store.driver.processColumns(),
                "opening a database written before the column must add it, got: " +
                    "${store.driver.processColumns()}",
            )
        }
    }

    /**
     * The migration must be an addition and nothing else. `ALTER TABLE … ADD COLUMN` cannot lose a
     * row, but a migration written as "create the new shape and copy into it" could, and this is the
     * assertion that would catch such a rewrite the day someone reaches for one.
     */
    @Test
    fun rowsWrittenBeforeTheMigrationSurviveIt() = withScratchHome { home ->
        writePreMigrationDatabase(home)

        withHistoryStore(home) { store ->
            val stored = store.database.processesQueries.selectProcesses().executeAsOne()

            assertEquals(STORED_PID, stored.pid)
            assertEquals(STORED_NAME, stored.name)
            assertEquals(STORED_PARENT_PID, stored.parent_pid)
            assertNull(
                stored.reparented_at,
                "a row that predates the column has nothing to say about a reparenting",
            )
        }
    }

    /**
     * The second open is the one that matters. `ALTER TABLE … ADD COLUMN` fails on a column that is
     * already there, so a migration whose guard does not hold would throw here — and be swallowed by
     * `openOrNull` into a null store, which is history silently gone from every start after the
     * first.
     */
    @Test
    fun openingAMigratedDatabaseAgainAddsNothingAndThrowsNothing() = withScratchHome { home ->
        writePreMigrationDatabase(home)

        withHistoryStore(home) { }

        withHistoryStore(home) { store ->
            assertEquals(
                1,
                store.driver.processColumns().count { it == REPARENTED_AT },
                "the second open must find the column and leave it alone",
            )
            assertEquals(
                1,
                store.database.processesQueries.selectProcesses().executeAsList().size,
                "and must not have rebuilt the table under the row",
            )
        }
    }

    /**
     * The other half of the same contract: a fresh machine never migrates anything, because
     * `Schema.create()` builds the current shape. Without this, a schema whose `CREATE TABLE` had
     * lost the column would still pass every test above — the migration would quietly add it back.
     */
    @Test
    fun aDatabaseCreatedFromScratchAlreadyHasTheColumn() = withScratchHome { home ->
        withHistoryStore(home) { store ->
            assertTrue(
                REPARENTED_AT in store.driver.processColumns(),
                "CREATE TABLE process must carry the column, not lean on the migration to add it",
            )
        }
    }
}

/**
 * The `process` table as it stood before `reparented_at`, as a schema the driver can create.
 *
 * The DDL is written out rather than derived from the current one by dropping a column. It is the
 * only record left of the pre-migration shape, and a shape produced by editing today's schema would
 * only prove that today's schema can be edited.
 *
 * The version is 1 — the version the shipped schema carries — and that is the whole trick. sqliter
 * runs `create` only when it reads `user_version = 0` and stamps its own schema's version
 * afterwards, so a file left at 0 would send the next open through `HarmonDatabase.Schema.create()`,
 * which throws on a `process` table that already exists. `openOrNull` catches that and hands back
 * null, and the test would fail on a missing store having never reached the migration at all.
 *
 * Only `process` is built. The rest of the schema is not what the migration reads, and a database
 * carrying one table is a sharper fixture than a copy of the whole file: a migration that reached
 * for `sample` would fail here rather than pass unnoticed.
 */
private object PreMigrationSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE process (
                  id              INTEGER PRIMARY KEY AUTOINCREMENT,
                  pid             INTEGER NOT NULL,
                  started_at      INTEGER NOT NULL,
                  name            TEXT NOT NULL,
                  executable_path TEXT,
                  uid             INTEGER,
                  parent_pid      INTEGER NOT NULL,
                  UNIQUE (pid, started_at)
                )
            """.trimIndent(),
            parameters = 0,
        )
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

/**
 * Puts a database of the old shape, holding one process row, where a store opened over [home] finds
 * it.
 *
 * The driver is this file's own rather than the store's: the store's is what is under test, and it
 * would create the current shape. Nothing else about the file is arranged to match production —
 * `auto_vacuum` and the foreign-key flag decide what happens to a database, and this one exists to
 * be looked at, not written to.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writePreMigrationDatabase(home: String) {
    val directory = "$home/$HISTORY_DIRECTORY"
    NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)

    val driver = NativeSqliteDriver(
        schema = PreMigrationSchema,
        name = HISTORY_DATABASE_NAME,
        onConfiguration = { configuration ->
            configuration.copy(
                extendedConfig = configuration.extendedConfig.copy(basePath = directory),
            )
        },
    )
    try {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO process(pid, started_at, name, executable_path, uid, parent_pid) " +
                "VALUES ($STORED_PID, 100, '$STORED_NAME', NULL, 501, $STORED_PARENT_PID)",
            parameters = 0,
        )
        /*
         * Asserted here rather than left to be discovered from a null store later: a fixture stuck
         * at user_version 0 fails every test in this file with "the store must open under /tmp/…",
         * which points at nothing.
         */
        assertEquals(
            1L,
            driver.scalar("PRAGMA user_version") { it.getLong(0) },
            "the fixture must look migrated to sqliter, or the next open runs Schema.create() over " +
                "a process table that already exists and openOrNull swallows the failure",
        )
    } finally {
        driver.close()
    }
}

/**
 * The column names of `process`, read the way the migration itself reads them.
 *
 * A list rather than a set, so that "the column is there" and "the column is there once" are two
 * different assertions.
 */
private fun SqlDriver.processColumns(): List<String> = executeQuery(
    identifier = null,
    sql = "PRAGMA table_info(process)",
    mapper = { cursor ->
        val names = mutableListOf<String>()
        while (cursor.next().value) {
            cursor.getString(1)?.let(names::add)
        }
        QueryResult.Value(names)
    },
    parameters = 0,
).value
