import dev.yoda.harmon.BuildInfo
import dev.yoda.harmon.collector.CollectorCliException
import dev.yoda.harmon.collector.CollectorCliParser
import dev.yoda.harmon.collector.CollectorCommand
import dev.yoda.harmon.ipc.CollectorServer
import dev.yoda.harmon.monitor.DarwinSystemCollector
import dev.yoda.harmon.util.printError
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.geteuid
import kotlin.system.exitProcess

@OptIn(ExperimentalForeignApi::class)
fun main(arguments: Array<String>) {
    val command = try {
        CollectorCliParser.parse(arguments)
    } catch (failure: CollectorCliException) {
        printError("error: ${failure.message}")
        printError("")
        printError(CollectorCliParser.help())
        exitProcess(2)
    }

    when (command) {
        CollectorCommand.Help -> println(CollectorCliParser.help())
        CollectorCommand.Version -> println("harmon-collector ${BuildInfo.VERSION}")
        is CollectorCommand.Run -> {
            if (geteuid() != 0u && !command.allowUnprivileged) {
                printError(
                    "error: the collector must run as root; " +
                        "--allow-unprivileged is for local development only",
                )
                exitProcess(77)
            }
            try {
                CollectorServer(
                    socketPath = command.socketPath,
                    allowedUserId = command.allowedUserId,
                    socketGroupId = command.socketGroupId,
                    collector = DarwinSystemCollector(),
                ).runForever()
            } catch (failure: Throwable) {
                printError(
                    "collector error: ${failure.message ?: failure::class.simpleName}",
                )
                exitProcess(1)
            }
        }
    }
}
