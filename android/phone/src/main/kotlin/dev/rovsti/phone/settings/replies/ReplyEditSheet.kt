package dev.rovsti.phone.settings.replies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.text.GlassTextField
import dev.rovsti.design.text.imeOverlapPadding
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.R

/** Test tags of the reply edit sheet. */
object ReplyEditTags {
    const val Sheet = "reply-edit-sheet"
    const val Field = "reply-edit-field"
    const val Save = "reply-edit-save"
    const val Delete = "reply-edit-delete"
    const val Cancel = "reply-edit-cancel"
    const val Counter = "reply-edit-counter"
}

/**
 * The body of the add / edit sheet of a quick reply: the title, a multi-line field (platform caret, selection and paste
 * menu), the character counter from [counterAt] on (red past [maxChars], when Save is off), Save and Cancel, and, for an
 * existing reply, Delete. The column pads itself by the keyboard's overlap ([imeOverlapPadding], never `imePadding`).
 * A new reply opens with the keyboard.
 */
@Composable
fun ColumnScope.ReplyEditContent(
    initial: String,
    maxChars: Int,
    counterAt: Int,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    field: TextFieldState = rememberTextFieldState(initial),
) {
    val colors = RovstiTheme.colors
    val typed = field.text.toString()
    val length = typed.trim().length
    val valid = length in 1..maxChars.coerceAtLeast(1)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (initial.isEmpty()) runCatching { focus.requestFocus() }
    }
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            modifier
                .fillMaxWidth()
                .weight(1f)
                .imeOverlapPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .testTag(ReplyEditTags.Sheet),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            RovstiText(
                stringResource(if (onDelete == null) R.string.qr_sheet_new else R.string.qr_sheet_edit),
                Modifier.fillMaxWidth().padding(top = Spacing.s).semantics { heading() },
                style = RovstiTheme.type.title2,
                textAlign = TextAlign.Center,
            )
            GlassTextField(
                state = field,
                placeholder = stringResource(R.string.qr_placeholder),
                modifier = Modifier.fillMaxWidth().testTag(ReplyEditTags.Field),
                maxLines = MAX_LINES,
                minLines = MIN_LINES,
                focusRequester = focus,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.End) {
                if (length >= counterAt) {
                    RovstiText(
                        "$length/$maxChars",
                        Modifier.testTag(ReplyEditTags.Counter),
                        style = RovstiTheme.type.footnote,
                        color = if (length > maxChars) colors.destructive else colors.labelSecondary,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                CapsuleButton(
                    stringResource(R.string.set_cancel),
                    onCancel,
                    Modifier.weight(1f).testTag(ReplyEditTags.Cancel),
                    fillWidth = true,
                )
                CapsuleButton(
                    stringResource(R.string.set_save),
                    { onSave(typed) },
                    Modifier.weight(1f).testTag(ReplyEditTags.Save),
                    style = CapsuleStyle.Filled,
                    enabled = valid,
                    fillWidth = true,
                )
            }
            if (onDelete != null) {
                TextButton(
                    stringResource(R.string.set_delete),
                    onDelete,
                    Modifier.align(Alignment.CenterHorizontally).testTag(ReplyEditTags.Delete),
                    tint = colors.destructive,
                )
            }
        }
    }
}

private const val MAX_LINES = 5
private const val MIN_LINES = 3
