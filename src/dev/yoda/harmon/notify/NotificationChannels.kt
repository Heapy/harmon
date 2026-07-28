package dev.yoda.harmon.notify

import dev.yoda.harmon.config.NotificationConfig
import dev.yoda.harmon.model.DeliveryResult
import dev.yoda.harmon.model.NotificationPayload
import dev.yoda.harmon.nativebridge.HMHttpResult
import dev.yoda.harmon.nativebridge.hm_http_global_init
import dev.yoda.harmon.nativebridge.hm_http_post_json
import dev.yoda.harmon.report.ReportJson
import dev.yoda.harmon.util.failureDescription
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSWorkspace
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSUserNotification
import platform.Foundation.NSUserNotificationCenter
import platform.Foundation.NSUserNotificationCenterDelegateProtocol
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.darwin.NSObject

private const val REPORT_PATH_USER_INFO_KEY = "harmonReportPath"

/**
 * How long the delivering thread keeps its run loop alive after handing a notification to
 * Notification Center. `deliverNotification` only enqueues the request, and a process that
 * returns straight into `exit` — every CLI command does — ends before the connection carrying it
 * completes: `usernoted` then logs `Denying message 3 from connection <LegacyConnection
 * identifier: dev.yoda.harmon>` and shows nothing. Measured on macOS 26 by sending three times
 * each way: without the spin all three were denied, with it all three were accepted and
 * delivered. 0.2 s already sufficed; the value carries headroom over that.
 */
private const val DELIVERY_FLUSH_SECONDS = 0.5

/**
 * Whether the macOS Notification Center channel is best-effort. It is: `deliverNotification`
 * queues a notification and never reports back whether it was shown, so the channel cannot decide
 * that a sample was delivered. Named rather than inlined because `SystemNotificationChannel`
 * cannot be constructed outside a running app.
 */
const val SYSTEM_CHANNEL_BEST_EFFORT = true

interface NotificationChannel {
    val name: String

    /**
     * A channel that cannot confirm delivery synchronously. Its result never decides whether the
     * sample was delivered, so a failure elsewhere is not masked by its optimistic success.
     */
    val bestEffort: Boolean get() = false

    fun deliver(payload: NotificationPayload): DeliveryResult
}

/** What one dispatch achieved: what each channel reported, and whether the sample was delivered. */
data class DeliverySummary(
    val results: List<DeliveryResult>,
    val decisiveSuccess: Boolean,
)

class NotificationDispatcher(
    private val channels: List<NotificationChannel>,
) {
    /**
     * Delivers [payload] through every channel, deciding on the way whether the sample counts as
     * delivered — each result is judged next to the channel that produced it.
     *
     * Only the *optimistic* success of a best-effort channel is discounted. One that reported an
     * outright failure — the system channel cannot write its HTML report on a full disk —
     * observed something, and with Notification Center as the only channel that failure has to
     * keep the alert pushable. With every channel silent the delivery counts as successful,
     * because nothing contradicts it.
     */
    fun deliver(payload: NotificationPayload): DeliverySummary {
        val delivered = channels.map { channel -> channel to channel.resultFor(payload) }
        val observed = delivered
            .filter { (channel, result) -> !channel.bestEffort || !result.successful }
            .map { (_, result) -> result }
        return DeliverySummary(
            results = delivered.map { (_, result) -> result },
            decisiveSuccess = observed.isEmpty() || observed.any { it.successful },
        )
    }

    private fun NotificationChannel.resultFor(payload: NotificationPayload): DeliveryResult =
        try {
            deliver(payload)
        } catch (failure: Throwable) {
            DeliveryResult(
                channel = name,
                successful = false,
                detail = failureDescription(failure),
            )
        }

    val isEmpty: Boolean
        get() = channels.isEmpty()

    companion object {
        fun from(config: NotificationConfig): NotificationDispatcher {
            val channels = buildList {
                if (config.systemEnabled) {
                    add(SystemNotificationChannel())
                }
                config.webhookUrl?.let { url ->
                    add(
                        WebhookNotificationChannel(
                            url = url,
                            bearerToken = config.webhookBearerToken,
                            timeoutSeconds = config.timeoutSeconds,
                        ),
                    )
                }
                val telegramToken = config.telegramBotToken
                val telegramChatId = config.telegramChatId
                if (telegramToken != null && telegramChatId != null) {
                    add(
                        TelegramNotificationChannel(
                            botToken = telegramToken,
                            chatId = telegramChatId,
                            timeoutSeconds = config.timeoutSeconds,
                        ),
                    )
                }
            }
            return NotificationDispatcher(channels)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class SystemNotificationChannel(
    private val reportStore: HtmlReportStore = HtmlReportStore(),
) : NotificationChannel {
    override val name: String = "system"
    override val bestEffort: Boolean = SYSTEM_CHANNEL_BEST_EFFORT
    private val center = NSApplication.sharedApplication.let { application ->
        application.setActivationPolicy(
            NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory,
        )
        application.finishLaunching()
        NSUserNotificationCenter.defaultUserNotificationCenter
    }
    private val delegate = ReportNotificationDelegate()

    init {
        center.delegate = delegate
    }

    @Suppress("DEPRECATION")
    override fun deliver(payload: NotificationPayload): DeliveryResult {
        val reportPath = reportStore.write(payload.html)
        val notification = NSUserNotification().apply {
            setIdentifier(payload.identifier)
            setTitle(payload.title)
            setSubtitle(payload.subtitle)
            setInformativeText(payload.text)
            setHasActionButton(true)
            setActionButtonTitle("Open report")
            setUserInfo(mapOf(REPORT_PATH_USER_INFO_KEY to reportPath))
        }
        center.deliverNotification(notification)
        NSRunLoop.mainRunLoop.runUntilDate(
            NSDate.dateWithTimeIntervalSinceNow(DELIVERY_FLUSH_SECONDS),
        )
        return DeliveryResult(
            channel = name,
            successful = true,
            detail = "queued in Notification Center (no delivery confirmation); " +
                "click opens $reportPath",
        )
    }
}

@Suppress("DEPRECATION")
private class ReportNotificationDelegate :
    NSObject(),
    NSUserNotificationCenterDelegateProtocol {
    @ObjCSignatureOverride
    override fun userNotificationCenter(
        center: NSUserNotificationCenter,
        didActivateNotification: NSUserNotification,
    ) {
        val reportPath = didActivateNotification
            .userInfo
            ?.get(REPORT_PATH_USER_INFO_KEY) as? String
        if (reportPath != null) {
            NSWorkspace.sharedWorkspace.openURL(
                NSURL.fileURLWithPath(reportPath),
            )
        }
        center.removeDeliveredNotification(didActivateNotification)
    }

    @ObjCSignatureOverride
    override fun userNotificationCenter(
        center: NSUserNotificationCenter,
        shouldPresentNotification: NSUserNotification,
    ): Boolean = true
}

@OptIn(ExperimentalForeignApi::class)
private object NativeHttpClient {
    init {
        check(hm_http_global_init() == 0) { "Unable to initialize libcurl" }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun post(
        channel: String,
        url: String,
        authorizationHeader: String?,
        body: String,
        timeoutSeconds: Long,
    ): DeliveryResult = memScoped {
        val nativeResult = alloc<HMHttpResult>()
        val result = hm_http_post_json(
            url,
            authorizationHeader.orEmpty(),
            body,
            timeoutSeconds,
            nativeResult.ptr,
        )
        val statusCode = nativeResult.status_code
        val successful = result == 0 && statusCode in 200..299
        val error = nativeResult.error.toKString()
        DeliveryResult(
            channel = channel,
            successful = successful,
            detail = when {
                successful -> "HTTP $statusCode"
                error.isNotBlank() -> "HTTP $statusCode: $error"
                else -> "HTTP $statusCode, curl result $result"
            },
        )
    }
}

class WebhookNotificationChannel(
    private val url: String,
    bearerToken: String?,
    timeoutSeconds: Long,
) : NotificationChannel {
    override val name: String = "webhook"
    private val authorizationHeader = bearerToken?.let { "Authorization: Bearer $it" }
    private val timeoutSeconds = timeoutSeconds

    override fun deliver(payload: NotificationPayload): DeliveryResult =
        NativeHttpClient.post(
            channel = name,
            url = url,
            authorizationHeader = authorizationHeader,
            body = payload.json,
            timeoutSeconds = timeoutSeconds,
        )
}

class TelegramNotificationChannel(
    botToken: String,
    private val chatId: String,
    timeoutSeconds: Long,
) : NotificationChannel {
    override val name: String = "telegram"
    private val url = "https://api.telegram.org/bot$botToken/sendMessage"
    private val timeoutSeconds = timeoutSeconds

    override fun deliver(payload: NotificationPayload): DeliveryResult {
        val text = buildString {
            append(payload.title)
            if (payload.subtitle.isNotBlank()) {
                append(" — ")
                append(payload.subtitle)
            }
            appendLine()
            append(payload.text)
        }.take(TELEGRAM_TEXT_LIMIT)
        val body = ReportJson.telegramRequest(chatId, text)
        return NativeHttpClient.post(
            channel = name,
            url = url,
            authorizationHeader = null,
            body = body,
            timeoutSeconds = timeoutSeconds,
        )
    }

    private companion object {
        const val TELEGRAM_TEXT_LIMIT = 4_000
    }
}
