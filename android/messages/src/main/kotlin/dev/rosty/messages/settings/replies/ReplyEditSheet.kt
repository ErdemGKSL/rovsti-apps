package dev.rosty.messages.settings.replies

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.TextButton
import dev.rosty.design.ios.foundation.LocalIosBackdrop
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.text.imeOverlapPadding
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MessagesColors

/** Test tags of the reply edit sheet. */
object ReplyEditTags {
    const val Sheet = "messages-reply-edit"
    const val Field = "messages-reply-edit-field"
    const val Save = "messages-reply-edit-save"
    const val Delete = "messages-reply-edit-delete"
    const val Problem = "messages-reply-edit-problem"
}

/** The sentence for a refused reply. */
fun replyProblemText(problem: ReplyProblem): Int =
    when (problem) {
        ReplyProblem.Empty -> R.string.quick_replies_problem_empty
        ReplyProblem.TooLong -> R.string.quick_replies_problem_long
        ReplyProblem.Duplicate -> R.string.quick_replies_problem_duplicate
        ReplyProblem.Full -> R.string.quick_replies_problem_full
        ReplyProblem.Failed -> R.string.quick_replies_problem_failed
    }

/**
 * The add / edit sheet of a quick reply: the title, a multi-line field, the character count near the limit, the core's
 * refusal when Save was refused, Save and Cancel, and Delete for an existing reply. A new reply opens with the keyboard.
 * [onSave] answers through its callback: null closes the sheet, a problem stays on it.
 */
@Composable
fun ColumnScope.ReplyEditContent(
    initial: String,
    maxChars: Int,
    onSave: (String, (ReplyProblem?) -> Unit) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    field: TextFieldState = rememberTextFieldState(initial),
) {
    val colors = RostyTheme.colors
    val typed = field.text.toString()
    val length = typed.trim().length
    var problem by remember { mutableStateOf<ReplyProblem?>(null) }
    LaunchedEffect(typed) { problem = null }
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
            RostyText(
                stringResource(if (onDelete == null) R.string.quick_replies_new else R.string.quick_replies_edit),
                Modifier.fillMaxWidth().padding(top = Spacing.s).semantics { heading() },
                style = RostyTheme.type.title2,
                textAlign = TextAlign.Center,
            )
            GlassTextField(
                state = field,
                placeholder = stringResource(R.string.quick_replies_placeholder),
                modifier = Modifier.fillMaxWidth().testTag(ReplyEditTags.Field),
                maxLines = MAX_LINES,
                minLines = MIN_LINES,
                focusRequester = focus,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), horizontalArrangement = Arrangement.End) {
                problem?.let {
                    RostyText(
                        stringResource(replyProblemText(it)),
                        Modifier.weight(1f).testTag(ReplyEditTags.Problem),
                        style = RostyTheme.type.footnote,
                        color = colors.destructive,
                    )
                }
                if (maxChars > 0 && length >= maxChars * COUNTER_FROM) {
                    RostyText(
                        "$length/$maxChars",
                        style = RostyTheme.type.footnote,
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
                    stringResource(R.string.inbox_cancel),
                    onCancel,
                    Modifier.weight(1f),
                    fillWidth = true,
                )
                CapsuleButton(
                    stringResource(R.string.quick_replies_save),
                    { onSave(typed) { problem = it } },
                    Modifier.weight(1f).testTag(ReplyEditTags.Save),
                    style = CapsuleStyle.Filled,
                    enabled = length > 0 && (maxChars <= 0 || length <= maxChars),
                    tint = MessagesColors.OutgoingBubble,
                    fillWidth = true,
                )
            }
            if (onDelete != null) {
                TextButton(
                    stringResource(R.string.quick_replies_delete),
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

/** The counter shows from 90 % of the limit on. */
private const val COUNTER_FROM = 0.9f
