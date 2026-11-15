package dev.rosty.messages

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.rosty.core.log.RostyLog
import dev.rosty.platform.sms.SmsClock
import dev.rosty.platform.sms.SmsRequest
import dev.rosty.platform.sms.SmsSend

/**
 * `RESPOND_VIA_MESSAGE`: the in-call "Reply with message" (and `mms:`/`mmsto:`) quick reply. The system only binds the
 * default SMS app with `SEND_RESPOND_VIA_MESSAGE`. It sends plain text through [SmsSend] and stops; it never touches the
 * UI or the native library.
 *
 * Ported from apps/messages/platforms/android/app/src/main/kotlin/dev/rosty/messages/HeadlessSmsSendService.kt
 */
class HeadlessSmsSendService : Service() {
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val request = intent?.let(::requestOf)
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread {
            try {
                SmsSend.send(applicationContext, request)
            } catch (e: RuntimeException) {
                RostyLog.e("messages", "The quick reply could not be sent", e)
            } finally {
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** `TelephonyManager.ACTION_RESPOND_VIA_MESSAGE`, spelled out so the service needs no telephony import. */
        const val ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"

        /** Pure: the SMS a `RESPOND_VIA_MESSAGE` intent asks for, or null. */
        fun requestOf(
            action: String?,
            schemeSpecificPart: String?,
            text: String?,
            now: Long,
        ): SmsRequest? {
            if (action != ACTION_RESPOND_VIA_MESSAGE) return null
            val to = ComposeRequest.recipients(schemeSpecificPart)
            val body = text?.takeIf { it.isNotBlank() } ?: return null
            if (to.isEmpty()) return null
            return SmsRequest(now, to, body, null)
        }
    }

    private fun requestOf(intent: Intent): SmsRequest? =
        requestOf(
            intent.action,
            intent.data?.schemeSpecificPart,
            intent.getStringExtra(Intent.EXTRA_TEXT),
            SmsClock.nowMillis(),
        )
}
