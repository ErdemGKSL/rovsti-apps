package dev.rovsti.shell

import android.app.Activity
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RefreshRateTest {
    private val fhd60 = ModeSpec(1, 1080, 2400, 60f)
    private val fhd120 = ModeSpec(2, 1080, 2400, 120f)
    private val fhd90 = ModeSpec(3, 1080, 2400, 90f)
    private val hd144 = ModeSpec(4, 720, 1600, 144f)

    @Test fun picksTheFastestModeAtTheCurrentResolution() {
        assertEquals(2, highestRefreshModeId(fhd60, listOf(fhd60, fhd90, fhd120, hd144)))
    }

    @Test fun neverChangesTheResolutionForARefreshRate() {
        val small60 = hd144.copy(refreshRate = 60f)
        assertNull("the 120 Hz mode is a bigger picture", highestRefreshModeId(small60, listOf(fhd120, small60)))
    }

    @Test fun nothingToDoWhenAlreadyFastest() {
        assertNull(highestRefreshModeId(fhd120, listOf(fhd60, fhd120)))
    }

    @Test fun noModesMeansNoChoice() {
        assertNull(highestRefreshModeId(fhd60, emptyList()))
    }

    @Test fun equalRatesKeepTheLowerIdAndAskForNothing() {
        assertNull(highestRefreshModeId(fhd60, listOf(fhd60, fhd60.copy(id = 9))))
    }

    @Test fun theWindowAsksForThe120HzModeOfTheRealDisplay() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        // Robolectric's default display is 320 x 470 at 60 Hz; the modes below are that size and a bigger one.
        val w = display.mode.physicalWidth
        val h = display.mode.physicalHeight
        installModes(
            display,
            fhd60.copy(width = w, height = h),
            fhd90.copy(width = w, height = h),
            fhd120.copy(width = w, height = h),
            hd144.copy(width = w * 2, height = h * 2),
        )
        val chosen = activity.window.preferHighestRefreshRate(display)
        assertEquals(2, chosen)
        assertEquals(2, activity.window.attributes.preferredDisplayModeId)
    }

    @Test fun aDisplayWithOnlyItsOwnRateLeavesTheWindowAlone() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay
        installModes(display, fhd60.copy(width = display.mode.physicalWidth, height = display.mode.physicalHeight))
        assertNull(activity.window.preferHighestRefreshRate(display))
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
    }

    @Test fun noDisplayIsNotAnError() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertNull(activity.window.preferHighestRefreshRate(null))
    }

    /** Gives the real default [Display] these modes through Robolectric's `ShadowDisplayManager`. */
    private fun installModes(
        display: Display,
        vararg modes: ModeSpec,
    ) {
        val built =
            modes.map {
                ReflectionHelpers.callConstructor(
                    Display.Mode::class.java,
                    ClassParameter.from(Int::class.javaPrimitiveType, it.id),
                    ClassParameter.from(Int::class.javaPrimitiveType, it.width),
                    ClassParameter.from(Int::class.javaPrimitiveType, it.height),
                    ClassParameter.from(Float::class.javaPrimitiveType, it.refreshRate),
                )
            }
        ShadowDisplayManager.setSupportedModes(display.displayId, *built.toTypedArray())
    }
}
