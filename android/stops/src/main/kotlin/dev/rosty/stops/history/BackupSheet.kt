package dev.rosty.stops.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.home.OnSheet
import kotlinx.collections.immutable.persistentListOf

object BackupTags {
    const val Confirm = "backup-confirm"
    const val Restore = "backup-restore"
    const val Cancel = "backup-cancel"
}

/** S32: "Restore this backup?" with what it holds ([summary], from `stops.backup_parse`); Restore or Cancel. */
fun OverlayController.presentBackupConfirmSheet(
    title: String,
    summary: String,
    onRestore: () -> Unit,
): SheetEntry =
    SheetEntry(title = title, detents = persistentListOf(Detent.Medium)) { sheet ->
        OnSheet {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(BackupTags.Confirm),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                RostyText(title, style = RostyTheme.type.title2)
                RostyText(summary, style = RostyTheme.type.body)
                RostyText(
                    stringResource(R.string.backup_confirm_message),
                    style = RostyTheme.type.subheadline,
                    color = RostyTheme.colors.labelSecondary,
                )
                CapsuleButton(
                    stringResource(R.string.backup_restore_button),
                    {
                        sheet.dismiss()
                        onRestore()
                    },
                    Modifier.fillMaxWidth().testTag(BackupTags.Restore),
                    style = CapsuleStyle.Filled,
                    fillWidth = true,
                )
                CapsuleButton(
                    stringResource(R.string.pick_cancel),
                    { sheet.dismiss() },
                    Modifier.fillMaxWidth().testTag(BackupTags.Cancel),
                    fillWidth = true,
                )
            }
        }
    }.also { present(it) }
