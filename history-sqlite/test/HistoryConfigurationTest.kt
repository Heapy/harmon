import dev.yoda.harmon.config.ConfigLoader
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.history.HistoryStore
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryConfigurationTest {
    /**
     * A null retention is a store that is never opened, and only an opened store ever creates the
     * database file. The enabled half is the control that pins the path and lazy connection.
     */
    @Test
    fun aDisabledHistoryLeavesNoDatabaseFileBehind() = withScratchHome { home ->
        assertNull(configuredHistory(historyConfig("historyRetentionDays=0"), home))
        assertFalse(
            NSFileManager.defaultManager.fileExistsAtPath(historyDatabasePath(home)),
            "history off must not so much as create the file",
        )

        val store = assertNotNull(
            configuredHistory(historyConfig("historyRetentionDays=1"), home),
        )
        /* sqliter connects on first use, so the file appears with the first sample, not on open. */
        store.record(rankingReport())
        store.close()

        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(historyDatabasePath(home)))
    }
}

private fun historyConfig(vararg lines: String): HarmonConfig =
    ConfigLoader.parse(lines = lines.asSequence(), environment = emptyMap())

private fun configuredHistory(config: HarmonConfig, home: String): HistoryStore? =
    config.historyRetentionDays?.let { retentionDays ->
        HistoryStore.openOrNull(
            retentionDays = retentionDays,
            intervalSeconds = config.intervalSeconds,
            homeDirectory = home,
        )
    }

private fun historyDatabasePath(home: String): String =
    "$home/Library/Application Support/Harmon/history.db"
