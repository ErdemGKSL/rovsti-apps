package dev.rosty.stops.settings

import android.content.Context
import dev.rosty.platform.location.AlarmPreview

/** What the alarm settings play on the phone (S12-S14); a fake in tests. Called on the main thread. */
interface AlarmDevice {
    /** Plays the alarm sound of [planJson] for a few seconds; false while the trip alarm rings. */
    fun playSound(planJson: String): Boolean

    /** Stops a test sound still playing (leaving the page). */
    fun stopSound()

    /** One run of a vibration [pattern]; false without a vibrator. */
    fun vibrate(pattern: List<Long>): Boolean

    /** Rings once as a test with [testRingJson]; false while the trip alarm rings. */
    fun testRing(testRingJson: String): Boolean
}

/** [AlarmDevice] on `:platform`'s [AlarmPreview]. */
class AndroidAlarmDevice(
    private val context: Context,
) : AlarmDevice {
    override fun playSound(planJson: String): Boolean = AlarmPreview.playSound(context, planJson)

    override fun stopSound() = AlarmPreview.stopSound()

    override fun vibrate(pattern: List<Long>): Boolean = AlarmPreview.vibrate(context, pattern)

    override fun testRing(testRingJson: String): Boolean = AlarmPreview.testRing(context, testRingJson)
}
