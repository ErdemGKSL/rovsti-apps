package dev.rovsti.phone.incall

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.rovsti.core.model.phone.RowKind
import dev.rovsti.phone.R

/** Every word of the In-Call screen, the route list, the mini pill and the post-call pills, read once per configuration. */
@Immutable
class InCallStrings(
    private val res: Resources,
) {
    val calling: String = res.getString(R.string.incall_status_calling)
    val held: String = res.getString(R.string.incall_status_held)
    val ended: String = res.getString(R.string.incall_status_ended)
    val collapse: String = res.getString(R.string.incall_collapse)
    val expand: String = res.getString(R.string.incall_expand)
    val mute: String = res.getString(R.string.incall_mute)
    val unmute: String = res.getString(R.string.incall_unmute)
    val keypad: String = res.getString(R.string.incall_keypad)
    val audio: String = res.getString(R.string.incall_audio)
    val addCall: String = res.getString(R.string.incall_add_call)
    val addCallLater: String = res.getString(R.string.incall_add_call_later)
    val hold: String = res.getString(R.string.incall_hold)
    val resume: String = res.getString(R.string.incall_resume)
    val note: String = res.getString(R.string.incall_note)
    val noteSaved: String = res.getString(R.string.incall_note_saved)
    val copyNumber: String = res.getString(R.string.incall_copy_number)
    val end: String = res.getString(R.string.incall_end)
    val hideKeypad: String = res.getString(R.string.incall_hide_keypad)
    val stopTones: String = res.getString(R.string.incall_tones_stop)
    val digitsHint: String = res.getString(R.string.incall_digits_hint)
    val routeTitle: String = res.getString(R.string.incall_route_title)
    val routeSelected: String = res.getString(R.string.incall_route_selected)
    val routeUnavailable: String = res.getString(R.string.incall_route_unavailable)
    val timerCopy: String = res.getString(R.string.incall_timer_copy)
    val timerShare: String = res.getString(R.string.incall_timer_share)
    val noteTitle: String = res.getString(R.string.incall_note_title)
    val notePlaceholder: String = res.getString(R.string.incall_note_placeholder)
    val noteSave: String = res.getString(R.string.incall_note_save)
    val postCallBlockAction: String = res.getString(R.string.postcall_block_action)
    val postCallUndo: String = res.getString(R.string.postcall_undo)
    val postCallBlockFailed: String = res.getString(R.string.postcall_block_failed)
    val postCallAddNote: String = res.getString(R.string.postcall_add_note)

    fun sendTones(tones: String): String = res.getString(R.string.incall_tones_send, tones)

    fun blockMessage(who: String): String = res.getString(R.string.postcall_block_message, who)

    fun blocked(who: String): String = res.getString(R.string.postcall_blocked, who)

    fun mini(
        name: String,
        status: String,
    ): String = res.getString(R.string.mini_call_title, name, status)

    /** "Call with Anna, 4:32" / "Call ended, 4:32" / "Call with Anna ended" for a call that never connected. */
    fun summary(
        title: String,
        ended: EndedUi,
        named: Boolean,
    ): String =
        when {
            !ended.connected -> res.getString(R.string.incall_summary_not_connected, title)
            named -> res.getString(R.string.incall_summary_with, title, ended.elapsed)
            else -> res.getString(R.string.incall_summary_ended, ended.elapsed)
        }

    fun route(row: RouteRowUi): String =
        row.label.ifBlank {
            when (row.kind) {
                RowKind.Speaker -> res.getString(R.string.incall_route_speaker)
                RowKind.Phone -> res.getString(R.string.incall_route_phone)
                else -> row.kind.name
            }
        }

    fun say(notice: InCallNotice): String =
        res.getString(
            when (notice) {
                InCallNotice.NumberCopied -> R.string.incall_notice_number_copied
                InCallNotice.NoNumber -> R.string.incall_notice_no_number
                InCallNotice.TimerCopied -> R.string.incall_notice_timer_copied
                InCallNotice.TonesCopied -> R.string.incall_notice_tones_copied
                InCallNotice.TonesSent -> R.string.incall_notice_tones_sent
                InCallNotice.NoteSaved -> R.string.incall_notice_note_saved
                InCallNotice.NoteFailed -> R.string.incall_notice_note_failed
                InCallNotice.RouteFailed -> R.string.incall_notice_route_failed
                InCallNotice.CommandFailed -> R.string.incall_notice_command_failed
            },
        )

    companion object {
        @Composable
        fun current(): InCallStrings {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            return remember(context, configuration) { InCallStrings(context.resources) }
        }
    }
}
