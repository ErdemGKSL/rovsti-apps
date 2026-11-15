package dev.rosty.messages.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.core.model.messages.Strength
import dev.rosty.design.controls.ActivityIndicator
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.ios.foundation.LocalIosBackdrop
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.text.GlassSecureTextField
import dev.rosty.design.text.imeOverlapPadding
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MessagesColors

/** Test tags of the backup sheet. */
object BackupSheetTags {
    const val Sheet = "messages-backup-sheet"
    const val Passphrase = "messages-backup-passphrase"
    const val Confirm = "messages-backup-confirm"
    const val Issue = "messages-backup-issue"
    const val Strength = "messages-backup-strength"
    const val Export = "messages-backup-export"
    const val Open = "messages-backup-open"
    const val Preview = "messages-backup-preview"
    const val Restore = "messages-backup-restore"
    const val Cancel = "messages-backup-cancel"
    const val Busy = "messages-backup-busy"
}

/** The fields of the backup sheet, owned by the page so they survive the phase changes. */
class BackupFields(
    val passphrase: TextFieldState = TextFieldState(),
    val confirm: TextFieldState = TextFieldState(),
    val open: TextFieldState = TextFieldState(),
) {
    fun clear() {
        passphrase.edit { replace(0, length, "") }
        confirm.edit { replace(0, length, "") }
        open.edit { replace(0, length, "") }
    }
}

/**
 * The body of the one sheet the backup flow uses, following the phase: the export form (passphrase twice, the core's
 * strength, the complaint), the passphrase of a picked file (again after a wrong one), what the app is busy with, and the
 * restore preview with its counts. Nothing is written before Restore.
 */
@Composable
fun ColumnScope.BackupSheetContent(
    ui: BackupUi,
    fields: BackupFields,
    onAction: (BackupAction) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            modifier
                .fillMaxWidth()
                .weight(1f)
                .imeOverlapPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .testTag(BackupSheetTags.Sheet),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            when (val phase = ui.phase) {
                is BackupPhase.Export -> ExportForm(phase, ui.strength, fields, onAction, onClose)
                is BackupPhase.ImportAsk -> OpenForm(phase, ui.fileName, fields, onAction, onClose)
                is BackupPhase.Preview -> PreviewBody(phase.preview, ui, onAction, onClose)
                is BackupPhase.Busy -> BusyLine(phase.what)
                else -> Unit
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    RostyText(
        text,
        Modifier.fillMaxWidth().padding(top = Spacing.s).semantics { heading() },
        style = RostyTheme.type.title2,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Note(
    text: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    RostyText(
        text,
        modifier.fillMaxWidth(),
        style = RostyTheme.type.footnote,
        color = if (warn) RostyTheme.colors.destructive else RostyTheme.colors.labelSecondary,
    )
}

@Composable
private fun ExportForm(
    phase: BackupPhase.Export,
    strength: Strength?,
    fields: BackupFields,
    onAction: (BackupAction) -> Unit,
    onClose: () -> Unit,
) {
    val submit = {
        onAction(
            BackupAction.SubmitExport(fields.passphrase.text.toString(), fields.confirm.text.toString()),
        )
    }
    SheetTitle(stringResource(R.string.backup_export_title))
    Note(stringResource(R.string.backup_export_explain))
    PassphraseField(
        fields.passphrase,
        stringResource(R.string.backup_passphrase),
        BackupSheetTags.Passphrase,
        ImeAction.Next,
    ) { onAction(BackupAction.Edited(it)) }
    PassphraseField(
        fields.confirm,
        stringResource(R.string.backup_passphrase_again),
        BackupSheetTags.Confirm,
        ImeAction.Done,
        onSubmit = submit,
    ) { onAction(BackupAction.Edited(fields.passphrase.text.toString())) }
    strength?.let {
        Note(
            stringResource(R.string.backup_strength, stringResource(strengthLabel(it))),
            Modifier.testTag(BackupSheetTags.Strength),
        )
    }
    phase.issue?.let { Note(stringResource(issueText(it)), Modifier.testTag(BackupSheetTags.Issue), warn = true) }
    Buttons(
        cancel = onClose,
        primary = stringResource(R.string.backup_export_action),
        primaryTag = BackupSheetTags.Export,
        onPrimary = submit,
    )
}

@Composable
private fun OpenForm(
    phase: BackupPhase.ImportAsk,
    fileName: String?,
    fields: BackupFields,
    onAction: (BackupAction) -> Unit,
    onClose: () -> Unit,
) {
    val open = { onAction(BackupAction.Open(fields.open.text.toString())) }
    SheetTitle(stringResource(R.string.backup_open_title))
    fileName?.let { FileName(it) }
    Note(stringResource(R.string.backup_open_explain))
    PassphraseField(
        fields.open,
        stringResource(R.string.backup_passphrase),
        BackupSheetTags.Passphrase,
        ImeAction.Done,
        onSubmit = open,
    ) {}
    if (phase.wrong) {
        Note(
            stringResource(R.string.backup_wrong_passphrase),
            Modifier.testTag(BackupSheetTags.Issue),
            warn = true,
        )
    }
    Buttons(
        cancel = onClose,
        primary = stringResource(R.string.backup_open_action),
        primaryTag = BackupSheetTags.Open,
        onPrimary = open,
    )
}

@Composable
private fun FileName(name: String) {
    RostyText(
        name,
        Modifier.fillMaxWidth(),
        style = RostyTheme.type.subheadline,
        color = RostyTheme.colors.labelSecondary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PreviewBody(
    preview: RestorePreview,
    ui: BackupUi,
    onAction: (BackupAction) -> Unit,
    onClose: () -> Unit,
) {
    SheetTitle(stringResource(R.string.backup_preview_title))
    ui.fileName?.let { FileName(it) }
    InsetGroupedSection(Modifier.padding(horizontal = Spacing.xs).testTag(BackupSheetTags.Preview)) {
        row { Count(stringResource(R.string.backup_preview_insert), preview.insert) }
        row { Count(stringResource(R.string.backup_preview_skipped), preview.skipped) }
        row { Count(stringResource(R.string.backup_preview_scheduled), preview.scheduled) }
        row { Count(stringResource(R.string.backup_preview_spam), preview.spamRules) }
    }
    Note(stringResource(R.string.backup_preview_explain))
    val blocked = !ui.roleHeld && preview.insert > 0
    if (blocked) Note(stringResource(R.string.backup_needs_role), warn = true)
    Buttons(
        cancel = onClose,
        primary = stringResource(R.string.backup_restore_action),
        primaryTag = BackupSheetTags.Restore,
        enabled = !blocked,
        onPrimary = { onAction(BackupAction.Restore) },
    )
}

@Composable
private fun Count(
    label: String,
    value: Int,
) {
    ListRow(
        title = label,
        trailing = { RostyText(value.toString(), color = RostyTheme.colors.labelSecondary) },
    )
}

@Composable
private fun BusyLine(what: BackupBusy) {
    val text = stringResource(busyText(what))
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        // The text names the work; the spinner says it is still going.
        ActivityIndicator(text, Modifier.clearAndSetSemantics { })
        RostyText(
            text,
            Modifier.testTag(BackupSheetTags.Busy),
            style = RostyTheme.type.body,
            color = RostyTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Buttons(
    cancel: () -> Unit,
    primary: String,
    primaryTag: String,
    onPrimary: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        CapsuleButton(
            stringResource(R.string.inbox_cancel),
            cancel,
            Modifier.weight(1f).testTag(BackupSheetTags.Cancel),
            fillWidth = true,
        )
        CapsuleButton(
            primary,
            onPrimary,
            Modifier.weight(1f).testTag(primaryTag),
            style = CapsuleStyle.Filled,
            enabled = enabled,
            tint = MessagesColors.OutgoingBubble,
            fillWidth = true,
        )
    }
}

/**
 * A passphrase field: the design system's secure glass field, on an opaque base so the page behind the glass sheet
 * never shows through it.
 */
@Composable
private fun PassphraseField(
    state: TextFieldState,
    placeholder: String,
    tag: String,
    ime: ImeAction,
    onSubmit: () -> Unit = {},
    onEdited: (String) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime
            .snapshotFlow { state.text.toString() }
            .collect { onEdited(it) }
    }
    GlassSecureTextField(
        state = state,
        placeholder = placeholder,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RostyShapes.capsule)
                .background(RostyTheme.colors.groupedSurface)
                .testTag(tag),
        imeAction = ime,
        onSubmit = onSubmit,
    )
}

private fun strengthLabel(strength: Strength): Int =
    when (strength) {
        Strength.Weak -> R.string.backup_strength_weak
        Strength.Fair -> R.string.backup_strength_fair
        Strength.Strong -> R.string.backup_strength_strong
    }

private fun issueText(issue: ExportIssue): Int =
    when (issue) {
        ExportIssue.Empty -> R.string.backup_issue_empty
        ExportIssue.Mismatch -> R.string.backup_issue_mismatch
        ExportIssue.Weak -> R.string.backup_issue_weak
    }

internal fun busyText(what: BackupBusy): Int =
    when (what) {
        BackupBusy.Sealing -> R.string.backup_busy_sealing
        BackupBusy.Saving -> R.string.backup_busy_saving
        BackupBusy.Picking -> R.string.backup_busy_picking
        BackupBusy.Opening -> R.string.backup_busy_opening
        BackupBusy.Restoring -> R.string.backup_busy_restoring
    }

/** The pill a finished flow shows. */
internal fun finishedText(
    phase: BackupPhase,
    res: android.content.res.Resources,
): String =
    when (phase) {
        is BackupPhase.Done -> {
            when (val done = phase.done) {
                BackupDone.Saved -> {
                    res.getString(R.string.backup_saved)
                }

                is BackupDone.Restored -> {
                    when {
                        done.inserted == 0 -> {
                            res.getString(R.string.backup_nothing_restored)
                        }

                        done.skipped == 0 -> {
                            res.getQuantityString(
                                R.plurals.backup_restored,
                                done.inserted,
                                done.inserted,
                            )
                        }

                        else -> {
                            res.getQuantityString(
                                R.plurals.backup_restored_skipped,
                                done.inserted,
                                done.inserted,
                                done.skipped,
                            )
                        }
                    }
                }
            }
        }

        is BackupPhase.Failed -> {
            res.getString(
                when (phase.failure) {
                    BackupFailure.Unreadable -> R.string.backup_fail_unreadable
                    BackupFailure.NotABackup -> R.string.backup_fail_not_backup
                    BackupFailure.NewerVersion -> R.string.backup_fail_newer
                    BackupFailure.Corrupt -> R.string.backup_fail_corrupt
                    BackupFailure.NeedsRole -> R.string.backup_needs_role
                    BackupFailure.Failed -> R.string.backup_fail_failed
                    BackupFailure.NotSaved -> R.string.backup_fail_not_saved
                },
            )
        }

        else -> {
            ""
        }
    }
