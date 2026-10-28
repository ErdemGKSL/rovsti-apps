package dev.rovsti.phone

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The pre-approved native fallback for the Incoming Call screen (AD-1, story 5.6):
 * the same layout, labels, order and copy as `incoming.rs`, with solid fills.
 *
 * It is used in two places:
 * - as a **curtain** over the GPUI surface, removed on the first GPUI frame, only when
 *   [CURTAIN_ENABLED] is true (it is not: see the switch below);
 * - as the **error view** when GPUI dies while a call rings (AD-17), always.
 *
 * Both buttons act through [CallRegistry] and never touch Rust.
 */
object NativeIncoming {
    /**
     * THE SWITCH. Off: the GPUI screen is the only incoming surface (the notification is
     * always the safety net). Turn it on, rebuild, and `MainActivity` puts this view over
     * the GPUI surface at launch while a call rings; Rust lifts it on the first frame of
     * its own Incoming Call screen. Decide with the cold-start median in
     * `docs/spikes/s2-incoming.md`: over 3 s, or any blank frame, means set this to true.
     */
    const val CURTAIN_ENABLED = false

    private const val ANSWER = 0xFF248A3D.toInt()
    private const val DECLINE = 0xFFD70015.toInt()

    fun build(
        context: Context,
        call: IncomingCall,
        locked: Boolean,
        onAnswer: () -> Unit,
        onDecline: () -> Unit,
    ): View {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun text(value: String, sp: Float, bold: Boolean, alpha: Float) = TextView(context).apply {
            this.text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(Color.WHITE)
            this.alpha = alpha
            gravity = Gravity.CENTER
            maxLines = 1
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(96), px(24), px(72))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0C3A33.toInt(), 0xFF05100E.toInt(), Color.BLACK),
            )
            // The whole screen is one live region: TalkBack announces the caller (focus order: caller, Answer, Decline).
            contentDescription = "Incoming call from ${call.title}"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true // swallows touches so nothing underneath reacts
        }
        column.addView(text(if (locked) "Incoming call (locked)" else "Incoming call", 13f, false, 0.7f))
        column.addView(text(call.title, 28f, true, 1f).apply {
            setPadding(0, px(24), 0, 0)
        })
        call.subtitle?.let { column.addView(text(it, 15f, false, 0.7f).apply { setPadding(0, px(4), 0, 0) }) }
        column.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        fun button(color: Int, icon: Int, caption: String, description: String, action: () -> Unit): View =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageButton(context).apply {
                    setImageResource(icon)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                    contentDescription = description
                    // Finger-up, like every ringing control: a stray touch must not answer.
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(px(76), px(76)))
                addView(text(caption, 12f, false, 0.9f).apply { setPadding(0, px(8), 0, 0) })
            }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(button(DECLINE, R.drawable.ic_call_decline, "Decline", "Decline", onDecline))
        row.addView(View(context), LinearLayout.LayoutParams(px(96), 1))
        row.addView(button(ANSWER, R.drawable.ic_call_answer, "Answer", "Answer", onAnswer))
        column.addView(row)
        return FrameLayout(context).apply {
            addView(column, FrameLayout.LayoutParams(-1, -1))
        }
    }
}
