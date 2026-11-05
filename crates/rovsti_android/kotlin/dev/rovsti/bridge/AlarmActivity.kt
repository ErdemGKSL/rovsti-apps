package dev.rovsti.bridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The full-screen alarm (AD-ST3): plain Views, no GPUI, so it shows over the lock screen even when the
 * app process was just started. Back means Snooze. Declare it in the host manifest with
 * `showWhenLocked`, `turnScreenOn`, `launchMode="singleInstance"`, `excludeFromRecents` and `exported="false"`.
 */
class AlarmActivity : Activity() {
    private val closer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!TripAlarm.ringing) {
            // A stale tap on an old notification: nothing is ringing.
            finish()
            return
        }
        val (title, text) = TripAlarm.message()
        setContentView(content(title, text))
        registerReceiver(closer, IntentFilter(TripAlarm.ACTION_CLOSE), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(closer)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    @Deprecated("Back is Snooze on this screen")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = snooze()

    private fun snooze() {
        TripAlarm.snooze(this)
        finish()
    }

    private fun dismiss() {
        TripAlarm.dismiss(this)
        finish()
    }

    private fun content(title: String, text: String): LinearLayout {
        val pad = (24 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
            addView(label(title, 34f, Color.WHITE))
            addView(label(text, 18f, Color.LTGRAY))
            addView(button("Dismiss") { dismiss() })
            addView(button("Snooze") { snooze() })
        }
    }

    private fun label(text: String, sp: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 20f
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
        setOnClickListener { onClick() }
    }
}
