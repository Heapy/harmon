import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import dev.yoda.harmon.db.HarmonDatabase
import dev.yoda.harmon.history.HistoryStore
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

private const val REPARENTED_AT = "reparented_at"

private const val TABLE_INFO_NAME_COLUMN = 1

private const val STORED_PID = 44_559L

private const val STORED_PARENT_PID = 44_268L

private const val STORED_NAME = "written-before-the-column-existed"

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

    @Test
    fun aDatabaseCreatedFromScratchAlreadyHasTheColumn() = withInMemoryDriver { driver ->
        assertTrue(
            REPARENTED_AT in driver.processColumns(),
            "CREATE TABLE process must carry the column, not lean on the migration to add it",
        )
    }

    @Test
    fun aSampleWrittenAfterTheMigrationLandsInTheWidenedTable() = withScratchHome { home ->
        writePreMigrationDatabase(home)

        withHistoryStore(home) { store ->
            store.record(orphanReport())

            val written = store.database.processesQueries.selectProcesses().executeAsOne()

            assertEquals(ORPHANED_PID.toLong(), written.pid)
            assertEquals(
                FIRST_SAMPLE_AT,
                written.reparented_at,
                "a migrated file has to take the write the new column exists for",
            )
        }
    }

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
                    FIRST_SAMPLE_AT,
                    store.database.processesQueries.selectProcesses().executeAsOne().reparented_at,
                    "and then take the sample into the widened table",
                )
            } finally {
                store.close()
            }
        }

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
                    FIRST_SAMPLE_AT,
                    store.database.processesQueries.selectProcesses().executeAsOne().reparented_at,
                    "and then take the sample into the widened table",
                )
            }
        }
    }

    @Test
    fun aColumnAnotherWriterAddedFirstIsAMigrationThatSucceeded() = withScratchHome { home ->
        writePreMigrationDatabase(home)
        val logged = mutableListOf<String>()

        withPreMigrationDriver(home) { driver ->
            val racing = RacedMigrationDriver(driver)
            withStoreOver(racing, home, logged) { store ->
                store.record(orphanReport())

                assertTrue(racing.raced, "the fixture has to have widened the table under the ALTER")
                assertEquals(
                    emptyList(),
                    logged,
                    "a database another writer already migrated is not a broken one: $logged",
                )
                assertEquals(
                    1,
                    driver.processColumns().count { it == REPARENTED_AT },
                    "and the column stays the one column it is: ${driver.processColumns()}",
                )
                assertEquals(
                    FIRST_SAMPLE_AT,
                    store.database.processesQueries.selectProcesses().executeAsOne().reparented_at,
                    "the sample has to land in the column the other writer added",
                )
            }
        }
    }

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

    @Test
    fun theMigrationBudgetIsOneAttemptAtOpenAndTwoWrites() = withScratchHome { home ->
        writePreMigrationDatabase(home)
        val logged = mutableListOf<String>()

        withUnopenableDatabase(home) {
            val store = HistoryStore.openOrNull(
                retentionDays = 7,
                intervalSeconds = 300,
                homeDirectory = home,
                logError = { logged += it },
            ) ?: fail("a database that is only unreachable must not cost the run history")

            assertEquals(1, logged.size, "opening spends the first attempt and says so: $logged")
            assertFalse(
                logged.single().startsWith("history disabled: "),
                "an unreachable database is not a disabled one: ${logged.single()}",
            )

            assertFails("the first write spends the second attempt") { store.record(orphanReport()) }
            assertEquals(1, logged.size, "which is not the end of the budget: $logged")

            assertFails("the second write spends the last one") { store.record(orphanReport()) }
            assertEquals(2, logged.size, "and that one ends it, once: $logged")
            assertTrue(
                logged.last().startsWith("history disabled: "),
                "the reason has to name history rather than the sample: ${logged.last()}",
            )

            store.record(orphanReport())

            assertEquals(2, logged.size, "a store that gave up neither tries nor says so again")
        }
    }
}

private class RacedMigrationDriver(private val delegate: SqlDriver) : SqlDriver by delegate {

    var raced = false
        private set

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (!raced && sql.startsWith("ALTER TABLE process ADD COLUMN")) {
            raced = true
            delegate.execute(identifier = null, sql = sql, parameters = parameters).value
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }
}

private const val ATTEMPT_CEILING = 10

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

private fun withStoreOver(
    driver: SqlDriver,
    home: String,
    logged: MutableList<String>,
    body: (HistoryStore) -> Unit,
) = body(
    HistoryStore(
        directory = historyDirectory(home),
        driver = driver,
        retentionDays = 7,
        intervalSeconds = 300,
        logError = { logged += it },
    ),
)

@OptIn(ExperimentalForeignApi::class)
private fun <T> withUnopenableDatabase(home: String, body: () -> T): T {
    val path = historyDatabasePath(home)
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

@OptIn(ExperimentalForeignApi::class)
private fun productionShapedDriver(home: String, schema: SqlSchema<QueryResult.Value<Unit>>) =
    NativeSqliteDriver(
        schema = schema,
        name = HISTORY_DATABASE_NAME,
        onConfiguration = { configuration ->
            configuration.copy(
                extendedConfig = configuration.extendedConfig.copy(
                    foreignKeyConstraints = true,
                    basePath = historyDirectory(home).also {
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

private fun SqlDriver.processColumns(): List<String> = executeQuery(
    identifier = null,
    sql = "PRAGMA table_info(process)",
    mapper = { cursor ->
        val names = mutableListOf<String>()
        while (cursor.next().value) {
            cursor.getString(TABLE_INFO_NAME_COLUMN)?.let(names::add)
        }
        QueryResult.Value(names)
    },
    parameters = 0,
).value
