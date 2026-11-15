package dev.rosty.messages.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.messages.R
import dev.rosty.messages.settings.Footnote
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsPage

/** Test tags of the Backup page. */
object BackupTags {
    const val Page = "messages-backup-page"
    const val Export = "messages-backup-export-row"
    const val Import = "messages-backup-import-row"
    const val Status = "messages-backup-status"
}

/**
 * Settings, Backup (AD-MSG7, GPUI `settings_ui::backup_section`): export an encrypted backup of the SMS and MMS text,
 * the spam rules and the scheduled SMS, or restore one. While a step runs both rows wait and the footer says what is
 * happening. Stateless.
 */
@Composable
fun BackupPage(
    ui: BackupUi,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    val idle = !BackupFlow.isBusy(ui.phase)
    SettingsPage(
        title = stringResource(R.string.backup_title),
        modifier = modifier.testTag(BackupTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        IosSettingsGroup(
            items =
                listOf(
                    IosSettingsItem(
                        icon = rememberVectorPainter(RostyIcons.Download),
                        title = {
                            IosText(
                                stringResource(R.string.backup_export_row),
                                Modifier.testTag(BackupTags.Export),
                            )
                        },
                        description = { IosText(stringResource(R.string.backup_export_detail)) },
                        enabled = idle,
                        onClick = onExport,
                    ),
                    IosSettingsItem(
                        icon = rememberVectorPainter(RostyIcons.Folder),
                        title = {
                            IosText(
                                stringResource(R.string.backup_import_row),
                                Modifier.testTag(BackupTags.Import),
                            )
                        },
                        description = { IosText(stringResource(R.string.backup_import_detail)) },
                        enabled = idle,
                        onClick = onImport,
                    ),
                ),
        )
        val busy = (ui.phase as? BackupPhase.Busy)?.what
        if (busy != null) {
            IosText(
                stringResource(busyText(busy)),
                Modifier.testTag(BackupTags.Status),
            )
        }
        Footnote(stringResource(R.string.backup_footer))
    }
}
