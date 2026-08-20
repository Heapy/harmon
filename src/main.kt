import io.heapy.harmon.cli.HarmonApplication
import io.heapy.harmon.config.HarmonConfig
import io.heapy.harmon.history.History
import io.heapy.harmon.history.HistoryStore
import io.heapy.harmon.ipc.CollectorClient
import io.heapy.harmon.notify.NotificationDispatcher
import io.heapy.harmon.notify.from
import io.heapy.harmon.runtime.HarmonService
import io.heapy.harmon.setup.HarmonSetup
import io.heapy.harmon.setup.HarmonStatus
import io.heapy.harmon.setup.HarmonStop
import io.heapy.harmon.setup.HarmonUninstall
import io.heapy.harmon.util.failureDescription
import io.heapy.harmon.util.printError
import io.heapy.harmon.web.LiveUiEndpointStore
import io.heapy.harmon.web.LiveUiLauncher
import io.heapy.harmon.web.LiveUiRuntime
import io.heapy.harmon.web.generateLiveUiToken
import kotlin.time.Clock

fun main(arguments: Array<String>) {
    val setup = HarmonSetup()
    val status = HarmonStatus()
    val stop = HarmonStop()
    val uninstall = HarmonUninstall()
    val serviceFactory: (HarmonConfig, History?) -> HarmonService = { config, history ->
        HarmonService(
            config = config,
            collector = CollectorClient(config.collectorSocket),
            notifications = lazy { NotificationDispatcher.from(config.notifications) },
            history = history,
        )
    }
    HarmonApplication.run(
        arguments = arguments,
        serviceFactory = serviceFactory,
        runService = { config, history ->
            val liveUi = if (config.webUiEnabled) {
                try {
                    val liveUiEndpointStore = LiveUiEndpointStore()
                    LiveUiRuntime.production(
                        collector = CollectorClient(config.collectorSocket),
                        config = config,
                        endpointStore = liveUiEndpointStore,
                        token = generateLiveUiToken(),
                        logError = ::printError,
                    ).also { runtime ->
                        val endpoint = runtime.start()
                        println(
                            "${Clock.System.now()} live UI listening on " +
                                "http://127.0.0.1:${endpoint.port}; run 'harmon ui' to open it",
                        )
                    }
                } catch (failure: Throwable) {
                    printError(
                        "${Clock.System.now()} live UI unavailable: ${failureDescription(failure)}",
                    )
                    null
                }
            } else {
                try {
                    LiveUiEndpointStore().remove()
                } catch (failure: Throwable) {
                    printError(
                        "${Clock.System.now()} stale live UI endpoint cleanup failed: " +
                            failureDescription(failure),
                    )
                }
                null
            }
            try {
                serviceFactory(config, history).runForever()
            } finally {
                liveUi?.stop()
            }
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
        status = status::run,
        stop = stop::run,
        uninstall = uninstall::run,
        openUi = { LiveUiLauncher.open(LiveUiEndpointStore()) },
    )
}
