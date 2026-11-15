package dev.rosty.platform.location

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Stops E7.10: the picker answers as one of three picks, and the volume test stops by itself or on leave. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmPreviewTest {
    private val app = RuntimeEnvironment.getApplication()

    @After fun quiet() = AlarmPreview.stopSound()

    @Test fun thePickerOpensOnTheCurrentSoundOrTheDefault() {
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        @Suppress("DEPRECATION")
        val existing = { i: Intent -> i.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI) }
        assertEquals(default, existing(AlarmPreview.pickerIntent(null)))
        assertEquals(default, existing(AlarmPreview.pickerIntent(" ")))
        assertEquals(Uri.parse("content://media/7"), existing(AlarmPreview.pickerIntent("content://media/7")))
        assertFalse(AlarmPreview.pickerIntent(null).getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
    }

    @Test fun answersAreCancelledDefaultOrChosen() {
        assertEquals(AlarmPick.Cancelled, AlarmPreview.picked(app, Activity.RESULT_CANCELED, null))
        assertEquals(AlarmPick.Cancelled, AlarmPreview.picked(app, Activity.RESULT_OK, Intent()))
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val pickedDefault = Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, default)
        assertEquals(AlarmPick.Default, AlarmPreview.picked(app, Activity.RESULT_OK, pickedDefault))
        val chosen = AlarmPreview.pickOf("""{"uri":"content://media/9","title":"Birds","cancelled":false}""")
        assertEquals(AlarmPick.Chosen("content://media/9", "Birds"), chosen)
        assertEquals(AlarmPick.Cancelled, AlarmPreview.pickOf("{nope"))
    }

    @Test fun theVolumeTestStopsAfterItsTimeAndOnLeave() {
        assertTrue(AlarmPreview.playSound(app, "{}", 1_000))
        assertTrue(AlarmPreview.isPlaying)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_100))
        assertFalse("stopped by itself", AlarmPreview.isPlaying)
        assertTrue(AlarmPreview.playSound(app, "{}"))
        AlarmPreview.stopSound()
        assertFalse("stopped on leave", AlarmPreview.isPlaying)
    }
}
