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
import dev.rovsti.platform.telephony.IncomingCall

/**
 * The pre-approved native fallback for the Incoming Call screen (AD-1, story 5.6): the same layout, labels, order and
 * copy as the Compose Incoming screen, with solid fills and plain Views.
 *
 * It is the **error view**: when the Compose UI cannot start while a call rings (AD-17), `MainActivity` shows this so the
 * call can still be answered or declined. The GPUI-era "curtain" over the surface is gone: Compose draws the first
 * frame fast enough and the notification is the safety net.
 *
 * Both buttons act through the callbacks the activity passes (`CallRegistry`) and never touch Rust.
 */
object NativeIncoming {
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

        fun text(
            value: String,
            sp: Float,
            bold: Boolean,
            alpha: Float,
        ) = TextView(context).apply {
            this.text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(Color.WHITE)
            this.alpha = alpha
            gravity = Gravity.CENTER
            maxLines = 1
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(px(24), px(96), px(24), px(72))
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0xFF0C3A33.toInt(), 0xFF05100E.toInt(), Color.BLACK),
                    )
                // The whole screen is one live region: TalkBack announces the caller (focus order: caller, Answer, Decline).
                contentDescription = context.getString(R.string.native_incoming_from, call.title)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isClickable = true // swallows touches so nothing underneath reacts
            }
        column.addView(
            text(
                context.getString(if (locked) R.string.native_incoming_locked else R.string.native_incoming),
                13f,
                false,
                0.7f,
            ),
        )
        column.addView(
            text(call.title, 28f, true, 1f).apply {
                setPadding(0, px(24), 0, 0)
            },
        )
        call.subtitle?.let { column.addView(text(it, 15f, false, 0.7f).apply { setPadding(0, px(4), 0, 0) }) }
        column.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        fun button(
            color: Int,
            icon: Int,
            caption: String,
            action: () -> Unit,
        ): View =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(
                    ImageButton(context).apply {
                        setImageResource(icon)
                        background =
                            GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(color)
                            }
                        contentDescription = caption
                        // Finger-up, like every ringing control: a stray touch must not answer.
                        setOnClickListener { action() }
                    },
                    LinearLayout.LayoutParams(px(76), px(76)),
                )
                addView(text(caption, 12f, false, 0.9f).apply { setPadding(0, px(8), 0, 0) })
            }

        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        row.addView(button(DECLINE, R.drawable.ic_call_decline, context.getString(R.string.native_decline), onDecline))
        row.addView(View(context), LinearLayout.LayoutParams(px(96), 1))
        row.addView(button(ANSWER, R.drawable.ic_call_answer, context.getString(R.string.native_answer), onAnswer))
        column.addView(row)
        return FrameLayout(context).apply {
            addView(column, FrameLayout.LayoutParams(-1, -1))
        }
    }
}
