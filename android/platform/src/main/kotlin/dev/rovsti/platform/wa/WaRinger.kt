package dev.rovsti.platform.wa

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import dev.rovsti.platform.notify.PlatformChannels
import dev.rovsti.platform.telephony.CallRegistry

/** How a WhatsApp ring makes itself known. */
enum class RingStyle {
    /** The default ringtone, looping, plus vibration. */
    Ring,

    /** Vibration only (the ringer is on vibrate). */
    Vibrate,

    /** A quiet call-waiting beep: another call is live. */
    Waiting,

    /** Nothing: the ringer is silent or Do Not Disturb holds calls back. The notification still shows. */
    Silent,
}

/** What a ring does on a device; a fake in tests. Every call is made with the [WaRing] lock held. */
interface RingOutputs {
    /** Starts the looping ringtone. */
    fun ring()

    /** Starts the repeating ring vibration. */
    fun vibrate()

    /** Starts the call-waiting beep. */
    fun waiting()

    /** Stops whatever of the three runs. Never throws. */
    fun stopAll()
}

/** What the phone says about ringing right now; a fake in tests. */
interface RingConditions {
    /** `AudioManager.ringerMode`. */
    val ringerMode: Int

    /** Whether Do Not Disturb lets a call through (see [WaRing.dndAllowsCalls]). */
    val dndAllowsCalls: Boolean

    /** A cellular call or another call (a live WhatsApp call, another VoIP app) is under way. */
    val otherCallLive: Boolean
}

/**
 * The ring of an incoming WhatsApp call (the `wa_calls` channel is silent, so the sound is ours to start and stop).
 * One call rings at a time: [start] for the call that already rings does nothing (the FCM push and the gateway event
 * both report the same ring), a different call takes over. The ring stops on [stop] for that call, on [silence] (a
 * volume or power key while the call screen shows) and by itself after [timeoutMs].
 */
class WaRing(
    private val outputs: RingOutputs,
    private val conditions: RingConditions,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val timeoutMs: Long = WaIncoming.RING_TIMEOUT_MS,
) {
    private var ringing: String? = null
    private var sounding = false
    private var timeout: Runnable? = null

    /** The call that rings now, if any. */
    val current: String? @Synchronized get() = ringing

    /** Starts the ring of [callId]; the style it got, or null when that call already rings. */
    @Synchronized
    fun start(callId: String): RingStyle? {
        if (callId.isEmpty() || callId == ringing) return null
        ringing?.let(::stopLocked)
        val style = styleFor(conditions.ringerMode, conditions.dndAllowsCalls, conditions.otherCallLive)
        ringing = callId
        val stopper = Runnable { stop(callId) }
        timeout = stopper
        postDelayed(stopper, timeoutMs)
        when (style) {
            RingStyle.Ring -> {
                outputs.ring()
                outputs.vibrate()
            }

            RingStyle.Vibrate -> {
                outputs.vibrate()
            }

            RingStyle.Waiting -> {
                outputs.waiting()
            }

            RingStyle.Silent -> {
                Unit
            }
        }
        sounding = style != RingStyle.Silent
        return style
    }

    /** Stops the ring of [callId] (answered, declined, over); false when that call does not ring. */
    @Synchronized
    fun stop(callId: String): Boolean {
        if (callId != ringing) return false
        stopLocked(callId)
        return true
    }

    /** Quiets the ring without ending it (a volume or power key). True when something was sounding. */
    @Synchronized
    fun silence(): Boolean {
        if (ringing == null || !sounding) return false
        outputs.stopAll()
        sounding = false
        return true
    }

    private fun stopLocked(callId: String) {
        timeout?.let(removeCallbacks)
        timeout = null
        outputs.stopAll()
        sounding = false
        if (ringing == callId) ringing = null
    }

    companion object {
        /**
         * Pure: the style for a new ring. Do Not Disturb wins (nothing), then a live call (the waiting beep, as the
         * platform plays it whatever the ringer mode), then the ringer mode.
         */
        fun styleFor(
            ringerMode: Int,
            dndAllowsCalls: Boolean,
            otherCallLive: Boolean,
        ): RingStyle =
            when {
                !dndAllowsCalls -> RingStyle.Silent
                otherCallLive -> RingStyle.Waiting
                ringerMode == AudioManager.RINGER_MODE_SILENT -> RingStyle.Silent
                ringerMode == AudioManager.RINGER_MODE_VIBRATE -> RingStyle.Vibrate
                else -> RingStyle.Ring
            }

        /**
         * Pure: whether Do Not Disturb lets a WhatsApp call ring. Off (`ALL`) lets it; "total silence" and "alarms only"
         * do not; "priority only" lets it when calls are allowed from anyone (the app cannot tell whether a WhatsApp
         * caller is a starred contact, so "contacts" and "starred" hold it back, the notification still shows), or
         * when the user let the `wa_calls` channel override Do Not Disturb.
         */
        fun dndAllowsCalls(
            filter: Int,
            callsAllowed: Boolean,
            callSenders: Int,
            channelBypasses: Boolean,
        ): Boolean =
            when (filter) {
                NotificationManager.INTERRUPTION_FILTER_ALL,
                NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
                -> {
                    true
                }

                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> {
                    channelBypasses ||
                        (callsAllowed && callSenders == NotificationManager.Policy.PRIORITY_SENDERS_ANY)
                }

                else -> {
                    false
                }
            }
    }
}

/** The ringtone, vibrator and tone generator of the device. */
internal class AndroidRingOutputs(
    private val context: Context,
) : RingOutputs {
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null

    private val vibrator: Vibrator?
        get() = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    override fun ring() {
        try {
            val r = RingtoneManager.getRingtone(context, Settings.System.DEFAULT_RINGTONE_URI) ?: return
            r.audioAttributes = RING_ATTRIBUTES
            r.isLooping = true
            r.play()
            ringtone = r
        } catch (e: RuntimeException) {
            Log.w(TAG, "WA ring: the ringtone could not play", e)
        }
    }

    override fun vibrate() {
        try {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(VIBRATION, 0),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "WA ring: no vibration", e)
        }
    }

    override fun waiting() {
        try {
            tone =
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, WAITING_VOLUME).also {
                    it.startTone(ToneGenerator.TONE_SUP_CALL_WAITING)
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "WA ring: no call-waiting tone", e)
        }
    }

    override fun stopAll() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching {
            tone?.stopTone()
            tone?.release()
        }
        tone = null
        runCatching { vibrator?.cancel() }
    }

    private companion object {
        const val TAG = "Rovsti"
        const val WAITING_VOLUME = 60

        /** On 1 s, off 1 s, repeated from index 0. */
        val VIBRATION = longArrayOf(0, 1_000, 1_000)

        val RING_ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
    }
}

/** The ringer mode, Do Not Disturb and live calls as the device reports them. */
internal class AndroidRingConditions(
    private val context: Context,
) : RingConditions {
    private val audio get() = context.getSystemService(AudioManager::class.java)

    override val ringerMode: Int
        get() = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

    override val dndAllowsCalls: Boolean
        get() {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return true
            return try {
                val policy = manager.notificationPolicy
                val calls = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
                val channel = manager.getNotificationChannel(PlatformChannels.WA_CALLS)
                WaRing.dndAllowsCalls(
                    filter = manager.currentInterruptionFilter,
                    callsAllowed = policy.priorityCategories and calls != 0,
                    callSenders = policy.priorityCallSenders,
                    channelBypasses = channel?.canBypassDnd() == true,
                )
            } catch (_: RuntimeException) {
                true
            }
        }

    override val otherCallLive: Boolean
        get() {
            val mode = audio?.mode
            return CallRegistry.hasLiveCall() ||
                mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION
        }
}

/**
 * The process-wide ring of WhatsApp calls. `WaIncoming.show` starts it (both the FCM push of a cold process and the
 * gateway event of a live one go through there, so a call rings once), `WaIncoming.cancel` stops it (answer, decline,
 * the ring is over, a `call_end` push), and the call screen's volume and power keys [silence] it.
 */
object WaRinger {
    private var ring: WaRing? = null

    private fun ring(context: Context): WaRing =
        synchronized(this) {
            ring ?: run {
                val app = context.applicationContext
                val main = Handler(Looper.getMainLooper())
                WaRing(AndroidRingOutputs(app), AndroidRingConditions(app), main::postDelayed, main::removeCallbacks)
                    .also { ring = it }
            }
        }

    private fun existing(): WaRing? = synchronized(this) { ring }

    /** Starts the ring of [callId]; never throws (an FCM push and JNI call this). */
    fun start(
        context: Context,
        callId: String,
    ): RingStyle? =
        try {
            ring(context).start(callId)
        } catch (e: RuntimeException) {
            Log.w("Rovsti", "WA ring could not start", e)
            null
        }

    /** Stops the ring of [callId]; false when it does not ring. */
    fun stop(callId: String): Boolean = existing()?.stop(callId) == true

    /** Quiets the ring (volume or power key); true when something was sounding. */
    fun silence(): Boolean = existing()?.silence() == true
}
