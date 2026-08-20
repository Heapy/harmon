package io.heapy.harmon.setup

/**
 * The launchctl policy setup, stop, and uninstall share: which failures mean "nothing to act on",
 * how a job is unloaded idempotently, and how a persistent override is cleared.
 */

fun isMissingLaunchdJob(result: CommandResult): Boolean {
    if (result.exitCode == LAUNCHCTL_NO_SUCH_PROCESS_EXIT_CODE ||
        result.exitCode == LAUNCHCTL_NO_SUCH_SERVICE_EXIT_CODE
    ) {
        return true
    }
    val output = result.output.lowercase()
    return "no such process" in output ||
        "could not find service" in output ||
        "service not found" in output
}

/** A GUI domain resolves only while its user is logged in; MDM and SSH runs see none. */
fun isMissingLaunchdDomain(result: CommandResult): Boolean {
    if (result.exitCode == LAUNCHCTL_NO_SUCH_DOMAIN_EXIT_CODE) {
        return true
    }
    return "could not find domain" in result.output.lowercase()
}

/** Neither the job nor the domain that would hold it exists, so it cannot be loaded either. */
fun isMissingLaunchdTarget(result: CommandResult): Boolean =
    isMissingLaunchdJob(result) || isMissingLaunchdDomain(result)

fun CommandRunner.bootoutIfLoaded(service: String) {
    val result = run(listOf("/bin/launchctl", "bootout", service))
    if (!result.successful && !isMissingLaunchdTarget(result)) {
        throw CommandExecutionException(result)
    }
}

/**
 * Clears persistent disable overrides, but only when they are actually set: launchd keeps a row
 * for every label it has been told about, so an unconditional enable would add rows uninstall
 * cannot remove afterwards. `print-disabled` returns the whole domain dictionary, so each domain
 * is queried once and that snapshot is reused for all of its labels. An unreadable snapshot leaves
 * its domain untouched rather than creating rows for labels whose state is unknown.
 */
fun CommandRunner.enableDisabledServices(services: List<String>) {
    services.groupBy { it.substringBeforeLast('/') }.forEach { (domain, domainServices) ->
        val disabledResult = run(listOf("/bin/launchctl", "print-disabled", domain))
        domainServices.forEach { service ->
            val label = service.substringAfterLast('/')
            val enablement = parseLaunchctlPrintDisabled(label, disabledResult)
            if (enablement.state == LaunchdEnablement.DISABLED) {
                val result = run(listOf("/bin/launchctl", "enable", service))
                if (!result.successful && !isMissingLaunchdTarget(result)) {
                    throw CommandExecutionException(result)
                }
            }
        }
    }
}

private const val LAUNCHCTL_NO_SUCH_PROCESS_EXIT_CODE = 3

/** `launchctl print` reports an absent service this way; a missing domain is 112 instead. */
private const val LAUNCHCTL_NO_SUCH_SERVICE_EXIT_CODE = 113

private const val LAUNCHCTL_NO_SUCH_DOMAIN_EXIT_CODE = 112
