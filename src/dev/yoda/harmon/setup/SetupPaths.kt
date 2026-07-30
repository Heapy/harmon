package dev.yoda.harmon.setup

data class UserSetupPaths(
    val home: String,
    val supportDirectory: String,
    val appBundle: String,
    val appContents: String,
    val appMacOsDirectory: String,
    val appResourcesDirectory: String,
    val installedAgent: String,
    val installedInfoPlist: String,
    val installedIcon: String,
    val configDirectory: String,
    val config: String,
    val logDirectory: String,
    val launchAgentsDirectory: String,
    val agentPlist: String,
    val legacyAgentPlist: String,
    val legacyCommandLink: String,
) {
    companion object {
        fun forHome(home: String): UserSetupPaths {
            require(home.startsWith('/')) { "home must be an absolute path" }
            val support = "$home/Library/Application Support/Harmon"
            val app = "$support/Harmon.app"
            val contents = "$app/Contents"
            return UserSetupPaths(
                home = home,
                supportDirectory = support,
                appBundle = app,
                appContents = contents,
                appMacOsDirectory = "$contents/MacOS",
                appResourcesDirectory = "$contents/Resources",
                installedAgent = "$contents/MacOS/harmon",
                installedInfoPlist = "$contents/Info.plist",
                installedIcon = "$contents/Resources/Harmon.icns",
                configDirectory = "$home/.config/harmon",
                config = "$home/.config/harmon/config",
                logDirectory = "$home/Library/Logs/Harmon",
                launchAgentsDirectory = "$home/Library/LaunchAgents",
                agentPlist = "$home/Library/LaunchAgents/$AGENT_LABEL.plist",
                legacyAgentPlist = "$home/Library/LaunchAgents/$LEGACY_AGENT_LABEL.plist",
                legacyCommandLink = "$home/.local/bin/harmon",
            )
        }
    }
}

object SystemSetupPaths {
    const val helperDirectory = "/Library/PrivilegedHelperTools"
    const val collectorBinary = "$helperDirectory/harmon-collector"
    const val legacyCollectorBinary = "$helperDirectory/dev.yoda.harmon"
    const val launchDaemonsDirectory = "/Library/LaunchDaemons"
    const val collectorPlist = "$launchDaemonsDirectory/$COLLECTOR_LABEL.plist"
    const val logDirectory = "/Library/Logs/Harmon"
    const val socket = DEFAULT_COLLECTOR_SOCKET
}

object InstallModes {
    val USER_DIRECTORY = "0700".toUInt(radix = 8)
    val PUBLIC_DIRECTORY = "0755".toUInt(radix = 8)
    val EXECUTABLE = "0755".toUInt(radix = 8)
    val RESOURCE = "0644".toUInt(radix = 8)
    val USER_SECRET = "0600".toUInt(radix = 8)
    val USER_PLIST = "0600".toUInt(radix = 8)
    val SYSTEM_PLIST = "0644".toUInt(radix = 8)
}
