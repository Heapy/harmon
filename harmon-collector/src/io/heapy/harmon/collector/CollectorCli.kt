package io.heapy.harmon.collector

sealed interface CollectorCommand {
    data object Help : CollectorCommand

    data object Version : CollectorCommand

    data class Run(
        val socketPath: String,
        val allowedUserId: UInt,
        val socketGroupId: UInt,
        val allowUnprivileged: Boolean,
    ) : CollectorCommand
}

class CollectorCliException(message: String) : IllegalArgumentException(message)

object CollectorCliParser {
    fun parse(arguments: Array<String>): CollectorCommand {
        if (arguments.size == 1) {
            when (arguments.single()) {
                "-h", "--help", "help" -> return CollectorCommand.Help
                "-v", "--version", "version" -> return CollectorCommand.Version
            }
        }

        var socketPath = DEFAULT_COLLECTOR_SOCKET
        var allowedUserId: UInt? = null
        var socketGroupId: UInt? = null
        var allowUnprivileged = false

        var index = 0
        while (index < arguments.size) {
            when (val option = arguments[index]) {
                "--socket" -> {
                    socketPath = arguments.valueAfter(index, option)
                    index += 2
                }
                "--allowed-uid" -> {
                    allowedUserId = arguments.unsignedValueAfter(index, option)
                    index += 2
                }
                "--allowed-gid" -> {
                    socketGroupId = arguments.unsignedValueAfter(index, option)
                    index += 2
                }
                "--allow-unprivileged" -> {
                    allowUnprivileged = true
                    index += 1
                }
                else -> throw CollectorCliException("unknown option '$option'")
            }
        }
        if (!socketPath.startsWith('/') || socketPath.length > MAX_SOCKET_PATH_LENGTH) {
            throw CollectorCliException(
                "--socket must be an absolute path up to $MAX_SOCKET_PATH_LENGTH characters",
            )
        }
        return CollectorCommand.Run(
            socketPath = socketPath,
            allowedUserId = allowedUserId
                ?: throw CollectorCliException("--allowed-uid is required"),
            socketGroupId = socketGroupId
                ?: throw CollectorCliException("--allowed-gid is required"),
            allowUnprivileged = allowUnprivileged,
        )
    }

    fun help(): String = """
        Harmon privileged system collector

        Usage:
          harmon-collector --allowed-uid UID --allowed-gid GID [--socket PATH]
                           [--allow-unprivileged]
          harmon-collector --help
          harmon-collector --version

        --allow-unprivileged is for local development only.
    """.trimIndent()

    private fun Array<String>.valueAfter(index: Int, option: String): String =
        getOrNull(index + 1)?.takeUnless { it.startsWith('-') }
            ?: throw CollectorCliException("$option requires a value")

    private fun Array<String>.unsignedValueAfter(index: Int, option: String): UInt =
        valueAfter(index, option).toUIntOrNull()
            ?: throw CollectorCliException("$option must be an unsigned integer")

    private const val DEFAULT_COLLECTOR_SOCKET = "/var/run/harmon.collector.sock"
    private const val MAX_SOCKET_PATH_LENGTH = 100
}
