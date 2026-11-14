package dev.rovsti.platform.wa

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import dev.rovsti.core.log.RovstiLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** The proximity wake lock; a fake in tests. */
interface ProximityLock {
    /** False when the device has no proximity sensor that can turn the screen off. */
    val supported: Boolean

    fun acquire()

    /** Releases the lock; the screen comes back once nothing is near the sensor. */
    fun release()
}

/**
 * The screen goes dark at the ear during a WhatsApp call, as the system does for cellular calls: the proximity lock is
 * held while a WhatsApp call is active on the earpiece and its microphone is not muted, and released on a route change
 * (speaker, Bluetooth or a wired headset), on mute, when the call ends and on [close].
 */
class WaProximity(
    private val lock: ProximityLock,
) {
    private var held = false

    /** Whether the lock is held now. */
    val holding: Boolean @Synchronized get() = held

    @Synchronized
    fun update(
        active: Boolean,
        earpiece: Boolean,
        muted: Boolean,
    ) {
        val want = lock.supported && wants(active, earpiece, muted)
        if (want == held) return
        if (want) lock.acquire() else lock.release()
        held = want
    }

    /** Releases the lock for good (the app is going away). */
    @Synchronized
    fun close() {
        if (held) lock.release()
        held = false
    }

    companion object {
        /** Pure: a connected call on the earpiece whose microphone is open. */
        fun wants(
            active: Boolean,
            earpiece: Boolean,
            muted: Boolean,
        ): Boolean = active && earpiece && !muted
    }
}

/** `PROXIMITY_SCREEN_OFF_WAKE_LOCK`, released with `RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY`. */
class AndroidProximityLock(
    context: Context,
) : ProximityLock {
    private val lock: PowerManager.WakeLock? =
        try {
            context.applicationContext
                .getSystemService(PowerManager::class.java)
                ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
                ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "rovsti:wa-proximity")
                ?.apply { setReferenceCounted(false) }
        } catch (_: RuntimeException) {
            null
        }

    override val supported: Boolean get() = lock != null

    override fun acquire() {
        try {
            lock?.acquire(MAX_HOLD_MS)
        } catch (e: RuntimeException) {
            RovstiLog.w("wa", "WA call: no proximity lock", e)
        }
    }

    override fun release() {
        try {
            if (lock?.isHeld == true) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        /** A safety net only: the call's end releases it long before. */
        const val MAX_HOLD_MS = 4 * 60 * 60 * 1000L
    }
}

/** Whether the voice of a call goes to the earpiece now (`AudioManager.getCommunicationDevice`), as it changes. */
fun communicationOnEarpiece(context: Context): Flow<Boolean> =
    callbackFlow {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java)
        if (audio == null) {
            trySend(false)
            awaitClose {}
            return@callbackFlow
        }
        val listener =
            AudioManager.OnCommunicationDeviceChangedListener { device ->
                trySend(device?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            }
        val added =
            try {
                audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
                trySend(audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                true
            } catch (e: RuntimeException) {
                // No route to follow: never hold the proximity lock rather than guess.
                RovstiLog.w("wa", "WA call: the audio route cannot be followed", e)
                trySend(false)
                false
            }
        awaitClose { if (added) runCatching { audio.removeOnCommunicationDeviceChangedListener(listener) } }
    }.distinctUntilChanged()
