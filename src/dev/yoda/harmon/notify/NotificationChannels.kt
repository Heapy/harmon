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
import platform.Foundation.NSUserNotification
import platform.Foundation.NSUserNotificationCenter
import platform.Foundation.NSUserNotificationCenterDelegateProtocol
import platform.Foundation.NSURL
import platform.darwin.NSObject

private const val REPORT_PATH_USER_INFO_KEY = "harmonReportPath"

/**
 * Whether the macOS Notification Center channel is best-effort. It is: `deliverNotification`
 * queues a notification and never reports back whether it was shown, so the channel cannot decide
 * that a sample was delivered.
 *
 * Public because `SystemNotificationChannel` is internal and constructing it boots AppKit, which
 * a test suite cannot do — without this constant the override below would have no observable
 * form at all and could be deleted with a green suite.
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

class NotificationDispatcher(
    private val channels: List<NotificationChannel>,
) {
    fun deliver(payload: NotificationPayload): List<DeliveryResult> =
        channels.map { channel ->
            try {
                channel.deliver(payload)
            } catch (failure: Throwable) {
                DeliveryResult(
                    channel = channel.name,
                    successful = false,
                    detail = failureDescription(failure),
                )
            }
        }

    /**
     * Results come from [deliver], so their order matches [channels] and best-effort channels can
     * be recognised by index.
     *
     * Only the *optimistic* success of a best-effort channel is discounted. A best-effort channel
     * that reported an outright failure — the system channel cannot write its HTML report on a
     * full disk or a read-only home — observed something, and that observation counts: with
     * Notification Center as the only configured channel, such a failure has to keep the alert
     * pushable instead of settling it as delivered. With every configured channel silent about the
     * outcome the delivery counts as successful, because nothing contradicts it.
     *
     * A result without a matching channel — a list this dispatcher did not produce — is treated
     * as decisive rather than indexed blindly, so a caller's mistake cannot be read as a silent
     * success.
     */
    fun decisiveSuccess(results: List<DeliveryResult>): Boolean {
        val observed = results.filterIndexed { index, result ->
            channels.getOrNull(index)?.bestEffort != true || !result.successful
        }
        return observed.isEmpty() || observed.any { it.successful }
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
