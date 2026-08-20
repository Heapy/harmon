package io.heapy.harmon.history

import io.heapy.harmon.analysis.AlertStateSnapshot
import io.heapy.harmon.model.DeliveryResult
import io.heapy.harmon.model.MonitoringReport
import kotlin.time.Clock
import kotlin.time.Instant

interface History {
    fun record(
        report: MonitoringReport,
        deliveries: List<DeliveryResult> = emptyList(),
        alertState: AlertStateSnapshot? = null,
    )

    fun restorableAlertState(now: Instant = Clock.System.now()): AlertStateSnapshot?
}
