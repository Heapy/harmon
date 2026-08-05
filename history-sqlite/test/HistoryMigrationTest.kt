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
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.chmod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
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

    /**
     * The other half of that verdict, and the reason it is two verdicts rather than one.
     *
     * A database that cannot be opened at all has told the migration nothing about the schema in it,
     * so answering it the way a mismatch is answered would cost `harmon run` every sample of the
     * rest of its life — `runForever` never returns, and a store `openOrNull` declined to build is
     * never asked for again. A locked file, a stale `-shm` or a disk that had no room for the
     * journal all arrive here, and all of them are gone by the next sample.
     *
     * So the store comes back unmigrated and the first write finishes the job. Asserted through a
     * pre-migration file rather than a fresh one because a fresh file would be created by
     * `Schema.create()` at the first connection and prove only that a write can happen — the old
     * shape is what makes "the migration ran, late" visible.
     */
    @Test
    fun aDatabaseUnreachableAtOpenKeepsTheStoreAndMigratesAtTheFirstWrite() =
        withScratchHome { home ->
            writePreMigrationDatabase(home)
            val logged = mutableListOf<String>()

            val store = withUnopenableDatabase(home) {
                HistoryStore.openOrNull(
                    retentionDays = 7,
                    intervalSeconds = 300,
                    homeDirectory = home,
                    logError = { logged += it },
                )
            }

            assertNotNull(store, "a database that is only unreachable must not cost the run history")
            try {
                assertEquals(1, logged.size, "the reason is reported once: $logged")
                assertFalse(
                    logged.single().startsWith("history disabled: "),
                    "an unreachable database is not a disabled one: ${logged.single()}",
                )
                assertFalse(
                    REPARENTED_AT in store.driver.processColumns(),
                    "the file must still be pre-migration here, or the retry proves nothing",
                )

                store.record(orphanReport())

                assertTrue(
                    REPARENTED_AT in store.driver.processColumns(),
                    "the write has to run the migration the open could not: " +
                        "${store.driver.processColumns()}",
                )
                assertEquals(
                    SAMPLE_AT,
                    store.database.processesQueries.selectProcesses().executeAsOne().reparented_at,
                    "and then take the sample into the widened table",
                )
            } finally {
                store.close()
            }
        }

    /**
     * The failure the open-time classification cannot see, and the one the whole retry exists for.
     *
     * The `PRAGMA table_info` guard and the `ALTER TABLE` do not run on the same connection —
     * sqldelight serves a pragma from its reader pool and a write from its transaction pool — so a
     * database that answers the read can still refuse the write. In production that is the previous
     * agent holding the write lock across a launchd restart, or a disk with no room left; here it is
     * the mode taken off the file after the reader is already connected, which fails the transaction
     * pool's first connection instead. Whichever it is, it says nothing about the stored schema, and
     * a store that treated it as a schema verdict would disable history for the run at exactly the
     * start that the one migration this project has is due to run.
     */
    @Test
    fun anAlterThatFailsOnTheMomentIsRetriedRatherThanDisablingHistory() = withScratchHome { home ->
        writePreMigrationDatabase(home)
        val logged = mutableListOf<String>()

        withPreMigrationDriver(home) { driver ->
            withStoreOver(driver, home, logged) { store ->
                withUnopenableDatabase(home) {
                    assertFails("the write the migration could not run for has to fail") {
                        store.record(orphanReport())
                    }
                }

                assertEquals(
                    emptyList(),
                    logged,
                    "a lock or a full disk under the ALTER is not a verdict about the file",
                )

                store.record(orphanReport())

                assertTrue(
                    REPARENTED_AT in driver.processColumns(),
                    "the next sample has to run the migration the last one could not: " +
                        "${driver.processColumns()}",
                )
                assertEquals(
                    SAMPLE_AT,
                    store.database.processesQueries.selectProcesses().executeAsOne().reparented_at,
                    "and then take the sample into the widened table",
                )
            }
        }
    }

    /**
     * The bound on that retry, which is the other half of it being survivable.
     *
     * A file that is reachable and permanently broken — corrupt, not a database at all — fails the
     * migration the same way a locked one does and never stops. Retried on every sample it would
     * cost a stack trace sqliter prints for itself and a connection its factory drops without
     * closing, every interval, for the life of a daemon that never returns. So the store gives up:
     * it says `history disabled: …` once, closes the driver, and records nothing more.
     *
     * [ATTEMPT_CEILING] rather than the store's own number, so that this asserts the retry is
     * bounded rather than restating what it is bounded to.
     */
    @Test
    fun aMigrationThatKeepsFailingGivesUpInsteadOfRetryingForever() = withScratchHome { home ->
        writePreMigrationDatabase(home)
        val logged = mutableListOf<String>()

        withPreMigrationDriver(home) { driver ->
            withStoreOver(driver, home, logged) { store ->
                withUnopenableDatabase(home) {
                    var attempts = 0
                    while (
                        attempts < ATTEMPT_CEILING &&
                        runCatching { store.record(orphanReport()) }.isFailure
                    ) {
                        attempts++
                    }

                    assertTrue(
                        attempts < ATTEMPT_CEILING,
                        "the store has to stop trying a migration that keeps failing, not carry " +
                            "sqliter's stack trace and a leaked connection into every sample",
                    )
                    assertEquals(1, logged.size, "and say so once: $logged")
                    assertTrue(
                        logged.single().startsWith("history disabled: "),
                        "the reason has to name history rather than the sample: ${logged.single()}",
                    )
                    assertFails("giving up has to hand the connection back") {
                        driver.processColumns()
                    }
                }
            }
        }
    }
}

/** Attempts past which a retry counts as unbounded, for the test that asserts it is not. */
private const val ATTEMPT_CEILING = 10

/**
 * Runs [body] against a driver over the pre-migration file, configured the way production is, with
 * its reader connection already established.
 *
 * That last part is the point. `HistoryStore.openOrNull` would migrate the file before handing the
 * store over, and this file's tests about a failing `ALTER TABLE` need the connection the pragma
 * reads through to be alive while the one the `ALTER` writes through cannot be made — which is the
 * arrangement production is in on the first start after an upgrade, and is not reachable through
 * `openOrNull`.
 */
private fun withPreMigrationDriver(home: String, body: (SqlDriver) -> Unit) {
    val driver = productionShapedDriver(home, PreMigrationSchema)
    try {
        assertFalse(
            REPARENTED_AT in driver.processColumns(),
            "the fixture must be pre-migration, and this read is what connects the reader pool",
        )
        body(driver)
    } finally {
        driver.close()
    }
}

/**
 * A store over an already-built [driver], the way `openOrNull` would have built one had its own
 * migration attempt not been part of opening.
 *
 * The store is not closed afterwards because [withPreMigrationDriver] closes the driver, and a
 * store that gave up on the file has closed it already.
 */
private fun withStoreOver(
    driver: SqlDriver,
    home: String,
    logged: MutableList<String>,
    body: (HistoryStore) -> Unit,
) = body(
    HistoryStore(
        directory = "$home/$HISTORY_DIRECTORY",
        driver = driver,
        retentionDays = 7,
        intervalSeconds = 300,
        logError = { logged += it },
    ),
)

/**
 * Runs [body] with the database file in place and impossible to open, and puts its mode back
 * afterwards.
 *
 * `chmod 0` rather than a real lock or a real full disk, because it is the only one of them a test
 * can undo exactly. What it stands in for is every reason a connection cannot be made at this
 * moment, and the store cannot tell those apart anyway: each of them reaches it as a throw from the
 * first read of the connection.
 *
 * The mode goes on the file rather than on the directory it sits in, so that `createPrivateDirectory`
 * — which chmods that directory on every open — cannot undo it while `openOrNull` is running.
 */
@OptIn(ExperimentalForeignApi::class)
private fun <T> withUnopenableDatabase(home: String, body: () -> T): T {
    val path = "$home/$HISTORY_DIRECTORY/$HISTORY_DATABASE_NAME"
    if (chmod(path, 0u) != 0) {
        fail("cannot take the mode off $path")
    }
    return try {
        body()
    } finally {
        if (chmod(path, (S_IRUSR or S_IWUSR).toUShort()) != 0) {
            fail("cannot give $path its mode back")
        }
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
