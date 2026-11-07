package dev.rovsti.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import org.json.JSONObject

/** Rovsti Stops: the alarm sound picker and the vibration preview. */
internal object AlarmOptions {
    /** Opens the system alarm picker; [current] null is the default alarm. No silent entry. */
    fun pickAlarmSound(activity: Activity, current: String?): Boolean = try {
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, default)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (current.isNullOrEmpty()) default else Uri.parse(current))
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, Documents.REQUEST_ALARM_SOUND)
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }

    /**
     * The picker's answer as the `alarm_sound_picked` event `{uri, title, cancelled}`; the default alarm
     * is a null uri (and title) so the app stores "no choice".
     */
    fun pickedJson(context: Context?, resultCode: Int, picked: Uri?, hasExtra: Boolean): String {
        val o = JSONObject()
        if (resultCode != Activity.RESULT_OK || !hasExtra) {
            return o.put("uri", JSONObject.NULL).put("title", JSONObject.NULL).put("cancelled", true).toString()
        }
        if (picked == null || picked == RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) {
            return o.put("uri", JSONObject.NULL).put("title", JSONObject.NULL).put("cancelled", false).toString()
        }
        val title = try {
            context?.let { RingtoneManager.getRingtone(it, picked)?.getTitle(it) }
        } catch (_: RuntimeException) {
            null
        }
        return o.put("uri", picked.toString()).put("title", title ?: "").put("cancelled", false).toString()
    }

    /** Completes the picker; true when [requestCode] was ours. [emit] gets the `alarm_sound_picked` JSON. */
    fun handle(context: Context?, requestCode: Int, resultCode: Int, data: Intent?, emit: (String) -> Unit): Boolean {
        if (requestCode != Documents.REQUEST_ALARM_SOUND) return false
        val hasExtra = data?.hasExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI) == true
        @Suppress("DEPRECATION")
        val picked: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        emit(pickedJson(context, resultCode, picked, hasExtra))
        return true
    }

    /** One run of [pattern] (pause, on, pause, on, ...) with alarm attributes; false when there is no motor. */
    fun previewVibration(context: Context, pattern: LongArray): Boolean {
        if (pattern.size < 2) return false
        val vibrator = context.applicationContext.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return false
        if (!vibrator.hasVibrator()) return false
        val effect = VibrationEffect.createWaveform(pattern, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AlarmSound.ALARM_ATTRS)
        }
        return true
    }
}
