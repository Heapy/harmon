import dev.yoda.harmon.cli.HarmonApplication
import dev.yoda.harmon.history.HistoryStore
import dev.yoda.harmon.ipc.CollectorClient
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.notify.from
import dev.yoda.harmon.runtime.HarmonService
import dev.yoda.harmon.setup.HarmonSetup

fun main(arguments: Array<String>) {
    val setup = HarmonSetup()
    HarmonApplication.run(
        arguments = arguments,
        serviceFactory = { config, history ->
            HarmonService(
                config = config,
                collector = CollectorClient(config.collectorSocket),
                notifications = lazy { NotificationDispatcher.from(config.notifications) },
                history = history,
            )
        },
        historyFactory = { config ->
            config.historyRetentionDays?.let { retentionDays ->
                HistoryStore.openOrNull(
                    retentionDays = retentionDays,
                    intervalSeconds = config.intervalSeconds,
                )
            }
        },
        setup = setup::run,
    )
}
