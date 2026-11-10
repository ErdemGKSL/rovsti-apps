package dev.rovsti.phone.incoming

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.rovsti.phone.R

/** Every word of the Incoming screen and the waiting-call card, read once per configuration. */
@Immutable
class IncomingStrings(
    private val res: Resources,
) {
    val title: String = res.getString(R.string.incoming_title)
    val locked: String = res.getString(R.string.incoming_locked)
    val unknown: String = res.getString(R.string.incoming_unknown)
    val answer: String = res.getString(R.string.incoming_answer)
    val decline: String = res.getString(R.string.incoming_decline)
    val message: String = res.getString(R.string.incoming_message)
    val remind: String = res.getString(R.string.incoming_remind)
    val silence: String = res.getString(R.string.incoming_silence)
    val silenced: String = res.getString(R.string.incoming_silenced)
    val repliesTitle: String = res.getString(R.string.incoming_replies_title)
    val replyCustom: String = res.getString(R.string.incoming_reply_custom)
    val remindTitle: String = res.getString(R.string.incoming_remind_title)
    val endAnswer: String = res.getString(R.string.waiting_end_answer)
    val holdAnswer: String = res.getString(R.string.waiting_hold_answer)
    val endHeldAnswer: String = res.getString(R.string.waiting_end_held_answer)

    fun from(name: String): String = res.getString(R.string.incoming_from, name)

    fun sim(label: String): String = res.getString(R.string.incoming_sim, label)

    fun waiting(name: String): String = res.getString(R.string.waiting_title, name)

    fun say(
        notice: IncomingNotice,
        text: String?,
    ): String =
        text
            ?: res.getString(
                when (notice) {
                    IncomingNotice.Stale -> R.string.incoming_notice_stale
                    IncomingNotice.DeclinedOnly -> R.string.incoming_notice_declined_only
                    IncomingNotice.ReminderSet -> R.string.incoming_notice_reminder_set
                    IncomingNotice.ReminderFailed -> R.string.incoming_notice_reminder_failed
                },
            )

    companion object {
        @Composable
        fun current(): IncomingStrings {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            return remember(context, configuration) { IncomingStrings(context.resources) }
        }
    }
}
