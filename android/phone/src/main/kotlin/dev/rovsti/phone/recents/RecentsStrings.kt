package dev.rovsti.phone.recents

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.rovsti.core.model.phone.RecentsFilter
import dev.rovsti.core.model.shared.NumberForm
import dev.rovsti.phone.R

/** Every word of Recents that the app (not the core) says, read once per configuration. */
@Immutable
class RecentsStrings(
    private val res: Resources,
) {
    val search: String = res.getString(R.string.recents_search_placeholder)
    val select: String = res.getString(R.string.recents_select)
    val selectAll: String = res.getString(R.string.recents_select_all)
    val deselectAll: String = res.getString(R.string.recents_deselect_all)
    val cancel: String = res.getString(R.string.recents_cancel)
    val selectNone: String = res.getString(R.string.recents_select_none)
    val info: String = res.getString(R.string.recents_info)
    val emptyTitle: String = res.getString(R.string.recents_empty_title)
    val emptyMessage: String = res.getString(R.string.recents_empty_message)
    val callBack: String = res.getString(R.string.recents_menu_call_back)
    val message: String = res.getString(R.string.recents_menu_message)
    val whatsApp: String = res.getString(R.string.recents_menu_whatsapp)
    val quickReply: String = res.getString(R.string.recents_menu_quick_reply)
    val remind: String = res.getString(R.string.recents_menu_remind)
    val copyNumber: String = res.getString(R.string.recents_menu_copy_number)
    val copyCountry: String = res.getString(R.string.recents_menu_copy_country)
    val copyAs: String = res.getString(R.string.recents_menu_copy_as)
    val shareAs: String = res.getString(R.string.recents_menu_share_as)
    val addContact: String = res.getString(R.string.recents_menu_add_contact)
    val block: String = res.getString(R.string.recents_menu_block)
    val delete: String = res.getString(R.string.recents_menu_delete)
    val share: String = res.getString(R.string.recents_share)
    val menuSelect: String = res.getString(R.string.recents_menu_select)
    val swipeCall: String = res.getString(R.string.recents_swipe_call)
    val swipeDelete: String = res.getString(R.string.recents_swipe_delete)
    val replyTitle: String = res.getString(R.string.recents_reply_title)
    val remindTitle: String = res.getString(R.string.recents_remind_title)
    val blockMessage: String = res.getString(R.string.recents_block_message)
    val blockAction: String = res.getString(R.string.recents_block_action)
    val undo: String = res.getString(R.string.recents_undo)

    fun filter(filter: RecentsFilter): String =
        res.getString(
            when (filter) {
                RecentsFilter.All -> R.string.recents_filter_all
                RecentsFilter.Missed -> R.string.recents_filter_missed
                RecentsFilter.Incoming -> R.string.recents_filter_incoming
                RecentsFilter.Outgoing -> R.string.recents_filter_outgoing
                RecentsFilter.WhatsApp -> R.string.recents_filter_whatsapp
                RecentsFilter.WithNotes -> R.string.recents_filter_notes
            },
        )

    fun selected(count: Int): String = res.getString(R.string.recents_selected_title, count)

    fun form(form: NumberForm): String =
        res.getString(
            when (form) {
                NumberForm.Display -> R.string.recents_form_display
                NumberForm.National -> R.string.recents_form_national
                NumberForm.E164 -> R.string.recents_form_e164
                NumberForm.TelLink -> R.string.recents_form_link
                NumberForm.VCard -> R.string.recents_form_vcard
            },
        )

    fun copyAsTitle(share: Boolean): String =
        res.getString(if (share) R.string.recents_share_as_title else R.string.recents_copy_as_title)

    fun blockTitle(
        name: String?,
        count: Int,
    ): String =
        if (name != null) {
            res.getString(R.string.recents_block_title, name)
        } else {
            res.getString(R.string.recents_block_title_many, count)
        }

    fun undoable(
        kind: UndoKind,
        count: Int,
    ): String =
        when (kind) {
            UndoKind.Deleted -> {
                if (count == 1) {
                    res.getString(R.string.recents_deleted_one)
                } else {
                    res.getString(R.string.recents_deleted_many, count)
                }
            }

            UndoKind.Blocked -> {
                if (count == 1) {
                    res.getString(R.string.recents_blocked_one)
                } else {
                    res.getString(R.string.recents_blocked_many, count)
                }
            }
        }

    fun say(notice: RecentsNotice): String =
        res.getString(
            when (notice) {
                RecentsNotice.NoNumber -> R.string.recents_notice_no_number
                RecentsNotice.Copied -> R.string.recents_notice_copied
                RecentsNotice.CopiedWithCountry -> R.string.recents_notice_copied_country
                RecentsNotice.CallFailed -> R.string.recents_notice_call_failed
                RecentsNotice.DialerOpened -> R.string.recents_notice_dialer_opened
                RecentsNotice.NotDefaultDialer -> R.string.recents_notice_not_default
                RecentsNotice.BlockRefused -> R.string.recents_notice_block_refused
                RecentsNotice.AlreadyBlocked -> R.string.recents_notice_already_blocked
                RecentsNotice.BlockFailed -> R.string.recents_notice_block_failed
                RecentsNotice.DeleteFailed -> R.string.recents_notice_delete_failed
                RecentsNotice.DeletePermission -> R.string.recents_notice_delete_permission
                RecentsNotice.WhatsAppSoon -> R.string.recents_notice_whatsapp_soon
                RecentsNotice.ReminderFailed -> R.string.recents_notice_reminder_failed
                RecentsNotice.NothingToDelete -> R.string.recents_notice_nothing_to_delete
                RecentsNotice.UndoFailed -> R.string.recents_notice_undo_failed
            },
        )

    companion object {
        @Composable
        fun current(): RecentsStrings {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            return remember(context, configuration) { RecentsStrings(context.resources) }
        }
    }
}
