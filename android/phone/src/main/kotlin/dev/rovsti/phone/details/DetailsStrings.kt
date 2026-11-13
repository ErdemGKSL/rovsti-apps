package dev.rovsti.phone.details

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.rovsti.phone.R
import dev.rovsti.phone.data.DialRefusal

/** Every word of the details sheets that the app (not the core) says, read once per configuration. */
@Immutable
class DetailsStrings(
    private val res: Resources,
) {
    val call: String = res.getString(R.string.details_call)
    val message: String = res.getString(R.string.details_message)
    val more: String = res.getString(R.string.details_more)
    val numbers: String = res.getString(R.string.details_numbers)
    val history: String = res.getString(R.string.details_history)
    val stats: String = res.getString(R.string.details_stats)
    val statCalls: String = res.getString(R.string.details_stat_calls)
    val statIncoming: String = res.getString(R.string.details_stat_incoming)
    val statOutgoing: String = res.getString(R.string.details_stat_outgoing)
    val statMissed: String = res.getString(R.string.details_stat_missed)
    val addContact: String = res.getString(R.string.details_add_contact)
    val deleteHistory: String = res.getString(R.string.details_delete_history)
    val callHint: String = res.getString(R.string.details_call_hint)
    val menuReply: String = res.getString(R.string.details_menu_reply)
    val menuWhatsApp: String = res.getString(R.string.details_menu_whatsapp)
    val menuCopyNumber: String = res.getString(R.string.details_menu_copy_number)
    val menuCopyAll: String = res.getString(R.string.details_menu_copy_all)
    val menuShareDetails: String = res.getString(R.string.details_menu_share_details)
    val menuShareStats: String = res.getString(R.string.details_menu_share_stats)
    val menuQr: String = res.getString(R.string.details_menu_qr)
    val menuExport: String = res.getString(R.string.details_menu_export)
    val menuBlock: String = res.getString(R.string.details_menu_block)
    val sheetPerson: String = res.getString(R.string.details_sheet_person)
    val sheetNumber: String = res.getString(R.string.details_sheet_number)
    val sheetCall: String = res.getString(R.string.details_sheet_call)
    val sheetQr: String = res.getString(R.string.details_sheet_qr)
    val qrCaption: String = res.getString(R.string.details_qr_caption)
    val qrShare: String = res.getString(R.string.details_qr_share)
    val cancel: String = res.getString(R.string.details_cancel)
    val undo: String = res.getString(R.string.details_undo)
    val replyTitle: String = res.getString(R.string.details_reply_title)
    val remindTitle: String = res.getString(R.string.details_remind_title)
    val blockMessage: String = res.getString(R.string.details_block_message)
    val blockAction: String = res.getString(R.string.details_block_action)
    val deleteHistoryTitle: String = res.getString(R.string.details_delete_history_title)
    val historyDeleted: String = res.getString(R.string.details_history_deleted)
    val whenLabel: String = res.getString(R.string.details_when)
    val durationLabel: String = res.getString(R.string.details_duration)
    val channelLabel: String = res.getString(R.string.details_channel)
    val numberLabel: String = res.getString(R.string.details_number)
    val note: String = res.getString(R.string.details_note)
    val notePlaceholder: String = res.getString(R.string.details_note_placeholder)
    val noteSave: String = res.getString(R.string.details_note_save)
    val noteRevert: String = res.getString(R.string.details_note_revert)
    val noteDelete: String = res.getString(R.string.details_note_delete)
    val noteDeleted: String = res.getString(R.string.details_note_deleted)
    val copy: String = res.getString(R.string.details_copy)
    val share: String = res.getString(R.string.details_share)
    val remind: String = res.getString(R.string.details_remind)
    val deleteCall: String = res.getString(R.string.details_delete_call)
    val callDeleted: String = res.getString(R.string.details_call_deleted)

    fun blockTitle(name: String): String = res.getString(R.string.details_block_title, name)

    fun deleteHistoryMessage(
        name: String,
        calls: Int,
    ): String = res.getString(R.string.details_delete_history_message, calls, name)

    fun deleteHistoryAction(calls: Int): String = res.getString(R.string.details_delete_history_action, calls)

    fun saved(fileName: String): String = res.getString(R.string.details_notice_saved, fileName)

    val saveFailed: String = res.getString(R.string.details_notice_save_failed)

    fun undoable(
        kind: PersonUndoKind,
        count: Int,
    ): String =
        when (kind) {
            PersonUndoKind.HistoryDeleted -> {
                historyDeleted
            }

            PersonUndoKind.Blocked -> {
                if (count == 1) {
                    res.getString(R.string.details_blocked)
                } else {
                    res.getString(R.string.details_blocked_many, count)
                }
            }
        }

    fun undoable(kind: CallDetailsUndoKind): String =
        when (kind) {
            CallDetailsUndoKind.NoteDeleted -> noteDeleted
            CallDetailsUndoKind.CallDeleted -> callDeleted
        }

    fun refused(refusal: DialRefusal): String = res.getString(refusal.text)

    fun say(notice: PersonNotice): String =
        res.getString(
            when (notice) {
                PersonNotice.NoNumber -> R.string.details_notice_no_number
                PersonNotice.Copied -> R.string.details_notice_copied
                PersonNotice.CopiedAll -> R.string.details_notice_copied_all
                PersonNotice.CallFailed -> R.string.details_notice_call_failed
                PersonNotice.DialerOpened -> R.string.details_notice_dialer_opened
                PersonNotice.NotDefaultDialer -> R.string.details_notice_not_default
                PersonNotice.BlockRefused -> R.string.details_notice_block_refused
                PersonNotice.AlreadyBlocked -> R.string.details_notice_already_blocked
                PersonNotice.BlockFailed -> R.string.details_notice_block_failed
                PersonNotice.DeleteFailed -> R.string.details_notice_delete_failed
                PersonNotice.DeletePermission -> R.string.details_notice_delete_permission
                PersonNotice.NothingToDelete -> R.string.details_notice_nothing_to_delete
                PersonNotice.NoCallsToExport -> R.string.details_notice_no_calls_to_export
                PersonNotice.QrFailed -> R.string.details_notice_qr_failed
                PersonNotice.UndoFailed -> R.string.details_notice_undo_failed
            },
        )

    fun say(notice: CallDetailsNotice): String =
        res.getString(
            when (notice) {
                CallDetailsNotice.NoteSaved -> R.string.details_note_saved
                CallDetailsNotice.Copied -> R.string.details_copied
                CallDetailsNotice.DeleteFailed -> R.string.details_notice_delete_failed
                CallDetailsNotice.DeletePermission -> R.string.details_notice_delete_permission
                CallDetailsNotice.NothingToDelete -> R.string.details_notice_nothing_to_delete
                CallDetailsNotice.ReminderFailed -> R.string.details_reminder_failed
                CallDetailsNotice.NoteFailed -> R.string.details_note_failed
                CallDetailsNotice.UndoFailed -> R.string.details_notice_undo_failed
            },
        )

    companion object {
        @Composable
        fun current(): DetailsStrings {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            return remember(context, configuration) { DetailsStrings(context.resources) }
        }
    }
}
