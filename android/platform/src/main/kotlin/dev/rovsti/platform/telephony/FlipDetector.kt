package dev.rovsti.platform.telephony

// Ported from apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/FlipDetector.kt (ARCH 7.3).

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Flip to silence (story 18.4, ProximityController pattern): listens to the gravity sensor (the
 * accelerometer as fallback) at `SENSOR_DELAY_NORMAL` only between [start] and [stop]; the registry
 * calls them from the one place that knows a call rings and is not yet silenced, so the listener
 * exists only while ringing (P-22). The trigger rule is [FlipRule]. [onFlip] fires once per start.
 */
class FlipDetector(
    context: Context,
    private val onFlip: () -> Unit,
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? =
        manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var rule = FlipRule()
    var registered = false
        private set

    /** False when the phone has no such sensor (Settings dims the toggle through `flip_supported`). */
    fun start(): Boolean {
        if (registered) return true
        val s = sensor ?: return false
        rule = FlipRule()
        registered =
            try {
                manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL) == true
            } catch (e: RuntimeException) {
                Log.w("Rovsti", "flip sensor refused", e)
                false
            }
        return registered
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            manager?.unregisterListener(this)
        } catch (_: RuntimeException) {
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.values.size < 3) return
        if (rule.onZ(event.values[2])) {
            stop()
            onFlip()
        }
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {}
}

/**
 * The flip-to-silence trigger (story 18.4): z below -7.0 m/s2 on two consecutive samples, after at
 * least one sample at or above -3.0, so a phone already face down at ring start never silences.
 */
class FlipRule {
    private var armed = false
    private var down = 0

    /** Feeds one z sample; true exactly when the phone has just been turned face down. */
    fun onZ(z: Float): Boolean {
        if (z >= ARM) armed = true
        if (!armed) return false
        down = if (z < FACE_DOWN) down + 1 else 0
        if (down >= 2) {
            armed = false
            down = 0
            return true
        }
        return false
    }

    companion object {
        const val FACE_DOWN = -7.0f
        const val ARM = -3.0f
    }
}
