package dev.rovsti.phone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dev.rovsti.bridge.FlipRule

/**
 * Flip to silence (story 18.4, ProximityController pattern): listens to the gravity sensor (the
 * accelerometer as fallback) at `SENSOR_DELAY_NORMAL` only between [start] and [stop]; the registry
 * calls them from the one place that knows a call rings and is not yet silenced, so the listener
 * exists only while ringing (P-22). The trigger rule is [FlipRule]. [onFlip] fires once per start.
 */
class FlipDetector(context: Context, private val onFlip: () -> Unit) : SensorEventListener {
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
        registered = try {
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
