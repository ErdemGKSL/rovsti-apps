package dev.rovsti.phone.settings.blocked

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.rovsti.phone.R

/** The words of the Blocked numbers pills and notices (the effects run outside composition, so they read resources). */
@Immutable
class BlockedStrings(
    private val res: Resources,
) {
    val undo: String = res.getString(R.string.blocked_undo)
    val unblockAll: String = res.getString(R.string.blocked_tools_unblock_all)
    val cancel: String = res.getString(R.string.blocked_cancel)
    val confirmMessage: String = res.getString(R.string.blocked_confirm_message)
    val pasteTitle: String = res.getString(R.string.blocked_block_numbers)
    val prefixTitle: String = res.getString(R.string.blocked_block_prefix)

    fun confirmTitle(count: Int): String = res.getString(R.string.blocked_confirm_title, count)

    fun pill(
        kind: BlockedUndoKind,
        count: Int,
    ): String =
        when (kind) {
            BlockedUndoKind.Blocked -> {
                if (count == 1) {
                    res.getString(R.string.blocked_pill_blocked_one)
                } else {
                    res.getString(R.string.blocked_pill_blocked_many, count)
                }
            }

            BlockedUndoKind.Unblocked -> {
                if (count == 1) {
                    res.getString(R.string.blocked_pill_unblocked_one)
                } else {
                    res.getString(R.string.blocked_pill_unblocked_many, count)
                }
            }

            BlockedUndoKind.RangeAdded -> {
                res.getString(R.string.blocked_pill_range_added)
            }

            BlockedUndoKind.RangeRemoved -> {
                res.getString(R.string.blocked_pill_range_removed)
            }
        }

    fun notice(notice: BlockedNotice): String =
        res.getString(
            when (notice) {
                BlockedNotice.Unavailable -> R.string.blocked_notice_unavailable
                BlockedNotice.Failed -> R.string.blocked_notice_failed
                BlockedNotice.NoNumbersFound -> R.string.blocked_notice_none_found
                BlockedNotice.EmergencyOnly -> R.string.blocked_notice_emergency
                BlockedNotice.NothingNew -> R.string.blocked_notice_nothing_new
                BlockedNotice.UndoFailed -> R.string.blocked_notice_undo_failed
                BlockedNotice.Exported -> R.string.blocked_notice_exported
                BlockedNotice.ExportFailed -> R.string.blocked_notice_export_failed
                BlockedNotice.ImportFailed -> R.string.blocked_notice_import_failed
                BlockedNotice.TooLarge -> R.string.blocked_notice_too_large
                BlockedNotice.Copied -> R.string.blocked_notice_copied
            },
        )

    companion object {
        @Composable
        fun current(): BlockedStrings {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            return remember(context, configuration) { BlockedStrings(context.resources) }
        }
    }
}
