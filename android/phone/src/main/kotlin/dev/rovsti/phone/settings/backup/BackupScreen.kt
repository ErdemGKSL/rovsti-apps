package dev.rovsti.phone.settings.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.rovsti.core.model.phone.BackupPreview
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.R
import dev.rovsti.phone.settings.GroupFooter
import dev.rovsti.phone.settings.NoGutter
import dev.rovsti.phone.settings.SettingsFilesHost
import dev.rovsti.shell.settings.SettingsPage
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Backup page and its preview sheet. */
object BackupTags {
    const val Page = "backup-page"
    const val Export = "backup-export"
    const val Restore = "backup-restore"
    const val Preview = "backup-preview"
    const val Apply = "backup-apply"
    const val Cancel = "backup-cancel"
}

/** Backup and restore (E5.9b): back up now, or restore from a file (which first shows the preview). Stateless. */
@Composable
fun BackupScreen(
    onExport: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    SettingsPage(
        title = stringResource(R.string.backup_title),
        modifier = modifier.testTag(BackupTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        horizontalPadding = NoGutter,
    ) {
        InsetGroupedSection {
            row {
                ListRow(
                    title = stringResource(R.string.backup_export),
                    secondary = stringResource(R.string.backup_export_detail),
                    secondaryMaxLines = 2,
                    leading = {
                        RovstiIcon(
                            RovstiIcons.Download,
                            null,
                            tint = RovstiTheme.colors.accent,
                            size = Spacing.xl,
                        )
                    },
                    onClick = onExport,
                    modifier = Modifier.testTag(BackupTags.Export),
                )
            }
            row {
                ListRow(
                    title = stringResource(R.string.backup_restore),
                    secondary = stringResource(R.string.backup_restore_detail),
                    secondaryMaxLines = 2,
                    leading = {
                        RovstiIcon(
                            RovstiIcons.Folder,
                            null,
                            tint = RovstiTheme.colors.accent,
                            size = Spacing.xl,
                        )
                    },
                    onClick = onRestore,
                    modifier = Modifier.testTag(BackupTags.Restore),
                )
            }
        }
        GroupFooter(stringResource(R.string.backup_footer), Modifier.padding(horizontal = Spacing.gutter))
    }
}

/**
 * The preview sheet of a picked backup: what the file would change, in the core's counts (settings, quick replies, speed
 * dial keys, favorites), the keys whose person moved, a note when values were adjusted, then Restore and Cancel. The
 * preferences stay as they are until Restore.
 */
@Composable
fun ColumnScope.BackupPreviewContent(
    fileName: String,
    preview: BackupPreview,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RovstiTheme.colors
    val resources = LocalContext.current.resources
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .testTag(BackupTags.Preview),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            RovstiText(
                stringResource(R.string.backup_preview_title),
                Modifier.fillMaxWidth().padding(top = Spacing.s).semantics { heading() },
                style = RovstiTheme.type.title2,
                textAlign = TextAlign.Center,
            )
            RovstiText(
                fileName,
                Modifier.fillMaxWidth(),
                style = RovstiTheme.type.subheadline,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            InsetGroupedSection(Modifier.padding(horizontal = Spacing.xs)) {
                row { Count(stringResource(R.string.backup_preview_settings), preview.settingsChanged) }
                row { Count(stringResource(R.string.backup_preview_replies), preview.quickReplies) }
                row { Count(stringResource(R.string.backup_preview_speed_dial), preview.speedDial) }
                row { Count(stringResource(R.string.backup_preview_favorites), preview.favorites) }
            }
            for (moved in preview.moved) {
                RovstiText(
                    resources.getString(R.string.backup_preview_moved, moved.to, moved.from),
                    style = RovstiTheme.type.footnote,
                    color = colors.labelSecondary,
                )
            }
            if (preview.clamped) {
                RovstiText(
                    stringResource(R.string.backup_preview_clamped),
                    style = RovstiTheme.type.footnote,
                    color = colors.labelSecondary,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                CapsuleButton(
                    stringResource(R.string.set_cancel),
                    onCancel,
                    Modifier.weight(1f).testTag(BackupTags.Cancel),
                    fillWidth = true,
                )
                CapsuleButton(
                    stringResource(R.string.backup_apply),
                    onApply,
                    Modifier.weight(1f).testTag(BackupTags.Apply),
                    style = CapsuleStyle.Filled,
                    fillWidth = true,
                )
            }
        }
    }
}

@Composable
private fun Count(
    label: String,
    value: Int,
) {
    ListRow(
        title = label,
        trailing = { RovstiText(value.toString(), color = RovstiTheme.colors.labelSecondary) },
    )
}

/** Presents the preview sheet over the page; Restore and Cancel both close it. */
internal fun presentBackupPreview(
    overlays: OverlayController,
    title: String,
    pending: BackupPending,
    onAction: (BackupAction) -> Unit,
) {
    overlays.present(
        SheetEntry(
            title = title,
            detents = persistentListOf(Detent.Large),
            onDismissed = { onAction(BackupAction.Cancel) },
        ) { sheet: SheetState ->
            BackupPreviewContent(
                pending.fileName,
                pending.preview,
                onApply = {
                    onAction(BackupAction.Apply)
                    sheet.dismiss()
                },
                onCancel = { sheet.dismiss() },
            )
        },
    )
}

/** The Backup route: the screen over its view model, the preview sheet and the pills. */
@Composable
fun BackupEntry(
    model: BackupViewModel,
    host: SettingsFilesHost,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val ui by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val undo = stringResource(R.string.set_undo)
    val applied = stringResource(R.string.backup_applied)
    val undone = stringResource(R.string.backup_undone)
    val invalid = stringResource(R.string.backup_invalid)
    val failed = stringResource(R.string.data_save_failed)
    val previewTitle = stringResource(R.string.backup_preview_title)
    val pending = ui.pending
    LaunchedEffect(pending) {
        if (pending != null) presentBackupPreview(overlays, previewTitle, pending, model::onAction)
    }
    LaunchedEffect(model) {
        model.effect.collect { effect ->
            when (effect) {
                is BackupEffect.Export -> {
                    host.save(effect.file.fileName, effect.file.mime, effect.file.text, effect.file.what)
                }

                is BackupEffect.Invalid -> {
                    overlays.showToast(invalid)
                }

                is BackupEffect.Applied -> {
                    overlays.showUndo(applied, {
                        model.onAction(BackupAction.Undo(effect.previous))
                        overlays.showToast(undone)
                    }, undo)
                }

                BackupEffect.Failed -> {
                    overlays.showToast(failed)
                }
            }
        }
    }
    BackupScreen(
        onExport = { model.onAction(BackupAction.Export) },
        onRestore = host::pickBackup,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
