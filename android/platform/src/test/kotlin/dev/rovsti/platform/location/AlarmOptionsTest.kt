package dev.rovsti.platform.location

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Defaults equal the Rust plan (the moved cargo contract guards the same literals), and the picker answers once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmOptionsTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun theDefaultPlanEqualsRustsAlarmPlan() {
        val plan = AlarmPlan()
        assertEquals(80, plan.volumePct)
        assertEquals(4_000L, plan.rampMs)
        assertEquals(600_000L, plan.ringTimeoutMs)
        assertEquals(
            listOf("user_alarm", "default_alarm", "default_ringtone", "default_notification", "tone"),
            plan.soundOrder,
        )
        assertEquals(listOf(0L, 700L, 500L, 700L, 1500L), plan.vibratePatternMs.toList())
        assertNull(plan.userSoundUri)
        // Missing, blank and broken JSON keep the defaults.
        for (json in listOf(null, "", "  ", "{nope")) assertEquals(plan.volumePct, AlarmPlan.parse(json).volumePct)
    }

    @Test fun parseClampsWhatRustSends() {
        val plan =
            AlarmPlan.parse(
                """{"volume_pct":500,"ramp_ms":99999,"ring_timeout_ms":5,"sound_order":[],"vibrate_pattern_ms":[1],"user_sound_uri":"content://x"}""",
            )
        assertEquals(100, plan.volumePct)
        assertEquals(5_000L, plan.rampMs)
        assertEquals(10_000L, plan.ringTimeoutMs)
        assertEquals(AlarmPlan().soundOrder, plan.soundOrder)
        assertEquals(AlarmPlan().vibratePatternMs.toList(), plan.vibratePatternMs.toList())
        assertEquals("content://x", plan.userSoundUri)
    }

    @Test fun volumeRampAndSoundOrderFollowTheRustHelpers() {
        assertEquals(8, alarmVolumeTarget(current = 3, min = 1, max = 10))
        assertEquals(10, alarmVolumeTarget(current = 10, min = 1, max = 10))
        assertEquals(0, alarmVolumeTarget(current = 0, min = 0, max = 0))
        assertEquals(4, alarmRampVolume(target = 8, min = 1, elapsedMs = 0, rampMs = 4_000))
        assertEquals(8, alarmRampVolume(target = 8, min = 1, elapsedMs = 4_000, rampMs = 4_000))
        assertEquals(
            listOf("default_alarm", "tone"),
            alarmSoundOrder(AlarmPlan(soundOrder = listOf("default_alarm", "bogus", "default_alarm"))),
        )
    }

    @Test fun pickedJsonIsCancelledDefaultOrTheChosenSound() {
        val cancelled = JSONObject(AlarmOptions.pickedJson(app, Activity.RESULT_CANCELED, null, false))
        assertTrue(cancelled.getBoolean("cancelled"))
        assertTrue(cancelled.isNull("uri"))

        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val chosenDefault = JSONObject(AlarmOptions.pickedJson(app, Activity.RESULT_OK, default, true))
        assertFalse(chosenDefault.getBoolean("cancelled"))
        assertTrue(chosenDefault.isNull("uri"))
        assertTrue(chosenDefault.isNull("title"))

        val custom =
            JSONObject(
                AlarmOptions.pickedJson(
                    app,
                    Activity.RESULT_OK,
                    Uri.parse("content://media/internal/audio/media/9"),
                    true,
                ),
            )
        assertEquals("content://media/internal/audio/media/9", custom.getString("uri"))
        assertFalse(custom.getBoolean("cancelled"))
        assertTrue(custom.has("title"))
    }

    @Test fun handleAnswersOnlyItsOwnRequestCode() {
        val seen = mutableListOf<String>()
        assertFalse(AlarmOptions.handle(app, 1, Activity.RESULT_OK, null) { seen += it })
        assertTrue(
            AlarmOptions.handle(app, AlarmOptions.REQUEST_ALARM_SOUND, Activity.RESULT_CANCELED, null) {
                seen +=
                    it
            },
        )
        assertEquals(1, seen.size)
        assertTrue(JSONObject(seen.single()).getBoolean("cancelled"))
    }

    @Test fun thePickerIsAnAlarmPickerWithNoSilentEntry() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertTrue(AlarmOptions.pickAlarmSound(activity, null))
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, started.intent.action)
        assertEquals(RingtoneManager.TYPE_ALARM, started.intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1))
        assertFalse(started.intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
        assertTrue(started.intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false))
        assertEquals(AlarmOptions.REQUEST_ALARM_SOUND, started.requestCode)
        assertEquals(0x4153, AlarmOptions.REQUEST_ALARM_SOUND)
    }

    @Test fun aVibrationPreviewNeedsAPattern() {
        assertFalse(AlarmOptions.previewVibration(app, longArrayOf(0)))
        // Robolectric has a vibrator; the call must never throw either way.
        AlarmOptions.previewVibration(app, longArrayOf(0, 100))
        assertEquals(Intent.ACTION_MAIN, Intent(Intent.ACTION_MAIN).action)
    }
}
