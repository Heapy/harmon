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

        assertTrue(
            NSFileManager.defaultManager.fileExistsAtPath(historyDatabasePath(home)),
            "openOrNull must have created the database, not deferred it to the first write",
        )

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
