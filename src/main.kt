import dev.yoda.harmon.cli.HarmonApplication
import dev.yoda.harmon.config.HarmonConfig
import dev.yoda.harmon.history.History
import dev.yoda.harmon.history.HistoryStore
import dev.yoda.harmon.ipc.CollectorClient
import dev.yoda.harmon.notify.NotificationDispatcher
import dev.yoda.harmon.notify.from
import dev.yoda.harmon.runtime.HarmonService
import dev.yoda.harmon.setup.HarmonSetup
import dev.yoda.harmon.setup.HarmonStatus
import dev.yoda.harmon.setup.HarmonUninstall
import dev.yoda.harmon.util.failureDescription
import dev.yoda.harmon.util.printError
import dev.yoda.harmon.web.LiveUiEndpointStore
import dev.yoda.harmon.web.LiveUiLauncher
import dev.yoda.harmon.web.LiveUiRuntime
import dev.yoda.harmon.web.generateLiveUiToken
import kotlin.time.Clock

fun main(arguments: Array<String>) {
    val setup = HarmonSetup()
    val status = HarmonStatus()
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
                        terminalApplications = config.terminalApplications,
                        sampleSeconds = config.webSampleSeconds,
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
        uninstall = uninstall::run,
        openUi = { LiveUiLauncher.open(LiveUiEndpointStore()) },
    )
}
