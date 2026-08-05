import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.yoda.harmon.db.HarmonDatabase
import dev.yoda.harmon.history.HistoryStore
import dev.yoda.harmon.model.INIT_PID
import dev.yoda.harmon.model.MonitoringReport
import dev.yoda.harmon.model.ReparentedFrom
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val HISTORY_DIRECTORY = "Library/Application Support/Harmon"

private const val HISTORY_DATABASE_NAME = "history.db"

private const val REPARENTED_AT = "reparented_at"

/** The pid the pre-migration row is written under, and the parent it was written with. */
private const val STORED_PID = 44_559L

private const val STORED_PARENT_PID = 44_268L

private const val STORED_NAME = "written-before-the-column-existed"

/** The process the post-migration sample stamps, and the moment that sample is taken at. */
private const val ORPHANED_PID = 44_560L

private const val SAMPLE_AT = "1970-01-01T00:01:40Z"

/**
 * Covers the one thing `Schema.create()` can never be asked about: what happens to a database file
 * an older build already wrote.
 *
 * Every test that migrates goes through [withHistoryStore] over a scratch home, because the
 * migration is part of opening the store and an in-memory driver has no file to have been written
 * before. The one test about the shipped `CREATE TABLE` is the exception and says why.
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
     *
     * Which is exactly why the store is not in the path here. Opening one runs `migrateSchema`, so a
     * `CREATE TABLE` missing the column would be repaired before the assertion could see it and this
     * test could only fail when the DDL and the migration were broken together — a case the tests
     * above already cover. `HarmonDatabase.Schema` straight onto a driver of its own is the only
     * reading of the shipped DDL that nothing else touches.
     */
    @Test
    fun aDatabaseCreatedFromScratchAlreadyHasTheColumn() = withInMemoryDriver { driver ->
        assertTrue(
            REPARENTED_AT in driver.processColumns(),
            "CREATE TABLE process must carry the column, not lean on the migration to add it",
        )
    }

    /**
     * The whole point of migrating: a database an older build wrote goes on being written to.
     *
     * The tests above stop at the shape of the table. This one takes the file the rest of the way a
     * real upgrade takes it — open, migrate, then record a sample through the widened table, with a
     * process that lost its parent so the new column is written rather than merely present.
     *
     * The row written before the migration is not in the answer, and that is not the migration
     * losing it: `record` opens with the start-up retention pass, and a lookup row no sample points
     * at is what `deleteOrphanProcesses` exists to remove. `rowsWrittenBeforeTheMigrationSurviveIt`
     * is where the survival of old data is asserted, on a store that has not written yet.
     */
    @Test
    fun aSampleWrittenAfterTheMigrationLandsInTheWidenedTable() = withScratchHome { home ->
        writePreMigrationDatabase(home)

        withHistoryStore(home) { store ->
            store.record(orphanReport())

            val written = store.database.processesQueries.selectProcesses().executeAsOne()

            assertEquals(ORPHANED_PID, written.pid)
            assertEquals(
                SAMPLE_AT,
                written.reparented_at,
                "a migrated file has to take the write the new column exists for",
            )
        }
    }

    /**
     * The failure the store's `init` KDoc promises, exercised rather than described.
     *
     * A file stamped `user_version = 1` with no `process` table in it is what a corrupted or
     * hand-edited database looks like from sqliter's side: it runs no `create`, the migration finds
     * no column to compare, and its `ALTER TABLE` throws `no such table`. Fail-closed means
     * `openOrNull` answers null and says why once — not that the agent writes into a shape its
     * queries disagree with, and not that it dies.
     */
    @Test
    fun aDatabaseTheMigrationCannotRepairOpensAsNoStoreAndIsReportedOnce() =
        withScratchHome { home ->
            writeDatabaseWithoutTheProcessTable(home)
            val logged = mutableListOf<String>()

            val store = HistoryStore.openOrNull(
                retentionDays = 7,
                intervalSeconds = 300,
                homeDirectory = home,
                logError = { logged += it },
            )

            assertNull(store, "a database the migration cannot repair must not hand back a store")
            assertEquals(1, logged.size, "the reason is reported once: $logged")
            assertTrue(
                logged.single().startsWith("history disabled: "),
                "the failure has to name history rather than the sample: ${logged.single()}",
            )
        }
}

/**
 * The database as an older build left it: today's schema everywhere except `process`, which is put
 * back the way it stood before `reparented_at`.
 *
 * The `process` DDL is written out rather than derived from the current one by dropping a column. It
 * is the only record left of the pre-migration shape, and a shape produced by editing today's schema
 * would only prove that today's schema can be edited.
 *
 * The rest of the schema comes from `HarmonDatabase.Schema` and is not repeated here, because none
 * of it changed. It has to be present at all so that a migrated file can be written to — a database
 * carrying `process` alone would make `record` fail on the missing `sample` table, and the upgrade
 * path this fixture exists to exercise ends at the first write, not at the `ALTER TABLE`. Dropping
 * and rebuilding `process` afterwards is safe because `process_sample.process_id` deliberately
 * carries no `REFERENCES` clause; see `Processes.sq`.
 *
 * The version is 1 — the version the shipped schema carries — and that is the whole trick. sqliter
 * runs `create` only when it reads `user_version = 0` and stamps its own schema's version
 * afterwards, so a file left at 0 would send the next open through `HarmonDatabase.Schema.create()`,
 * which throws on a `process` table that already exists. `openOrNull` catches that and hands back
 * null, and the test would fail on a missing store having never reached the migration at all.
 */
private object PreMigrationSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        HarmonDatabase.Schema.create(driver).value
        driver.execute(identifier = null, sql = "DROP TABLE process", parameters = 0).value
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
        ).value
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
 * A database sqliter considers current and this build cannot write to: `user_version = 1` and no
 * `process` table at all.
 *
 * The one table it does carry is there because sqlite creates no file for a schema that creates
 * nothing, and `user_version` has nowhere to be stamped on a file that does not exist.
 */
private object SchemaWithoutTheProcessTable : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            identifier = null,
            sql = "CREATE TABLE something_else (id INTEGER PRIMARY KEY AUTOINCREMENT)",
            parameters = 0,
        ).value
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
 * would create the current shape. It is nonetheless configured the way `openHistoryDriver`
 * configures production, because two of those settings can only ever be applied to a file that does
 * not exist yet — `auto_vacuum` freezes at 0 once WAL has written the header, and a fixture frozen
 * there would quietly return no page to any retention test later written over it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writePreMigrationDatabase(home: String) {
    val driver = productionShapedDriver(home, PreMigrationSchema)
    try {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO process(pid, started_at, name, executable_path, uid, parent_pid) " +
                "VALUES ($STORED_PID, 100, '$STORED_NAME', NULL, 501, $STORED_PARENT_PID)",
            parameters = 0,
        ).value
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
 * The same, for the file no migration can repair.
 *
 * The `user_version` read is not only an assertion. `NativeSqliteDriver` connects lazily, so a
 * driver that is built and closed again creates no file at all and the next open would find an empty
 * directory and build a perfectly good database in it — the test would then pass for the wrong
 * reason, having never produced the file it is about.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeDatabaseWithoutTheProcessTable(home: String) {
    val driver = productionShapedDriver(home, SchemaWithoutTheProcessTable)
    try {
        assertEquals(
            1L,
            driver.scalar("PRAGMA user_version") { it.getLong(0) },
            "the fixture has to look current to sqliter, or the next open simply creates the schema",
        )
    } finally {
        driver.close()
    }
}

/**
 * A driver over the same file `HistoryStore` opens, configured the way `openHistoryDriver`
 * configures its own — the settings that only take on a file that does not exist yet.
 */
@OptIn(ExperimentalForeignApi::class)
private fun productionShapedDriver(home: String, schema: SqlSchema<QueryResult.Value<Unit>>) =
    NativeSqliteDriver(
        schema = schema,
        name = HISTORY_DATABASE_NAME,
        onConfiguration = { configuration ->
            configuration.copy(
                extendedConfig = configuration.extendedConfig.copy(
                    foreignKeyConstraints = true,
                    basePath = "$home/$HISTORY_DIRECTORY".also {
                        NSFileManager.defaultManager
                            .createDirectoryAtPath(it, true, null, null)
                    },
                ),
                lifecycleConfig = configuration.lifecycleConfig.copy(
                    onCreateConnection = { connection ->
                        connection.rawExecSql("PRAGMA auto_vacuum = INCREMENTAL")
                    },
                ),
            )
        },
    )

/** One sample carrying a process that lost its parent, taken after the migration has run. */
private fun orphanReport(): MonitoringReport = MonitoringReport(
    usage = systemUsage(
        processes = listOf(
            processUsage(
                pid = ORPHANED_PID.toInt(),
                name = "abandoned",
                parentPid = INIT_PID,
                reparentedFrom = ReparentedFrom(pid = 44_268, name = "supervisor"),
            ),
        ),
    ).copy(capturedAt = Instant.fromEpochSeconds(100)),
    alerts = emptyList(),
    topProcessCount = 1,
)

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
