package dev.yoda.harmon.history

import dev.yoda.harmon.analysis.AlertStateSnapshot
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.MonitoringReport
import kotlin.time.Clock
import kotlin.time.Instant

/** Persistence boundary used by the monitoring loop. */
interface History {
    fun record(
        report: MonitoringReport,
        deliveries: List<DeliveryResult> = emptyList(),
        alertState: AlertStateSnapshot? = null,
    )

    fun restorableAlertState(now: Instant = Clock.System.now()): AlertStateSnapshot?
}
