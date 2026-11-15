package dev.rosty.messages.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.TextButton
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.ios.foundation.LocalIosBackdrop
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.text.imeOverlapPadding
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem

/** Test tags of the rule and sender editors. */
object RuleEditorTags {
    const val Sheet = "spam-editor"
    const val Field = "spam-editor-field"
    const val Save = "spam-editor-save"
    const val Cancel = "spam-editor-cancel"
    const val Delete = "spam-editor-delete"
    const val Problem = "spam-editor-problem"

    fun kind(kind: RuleKind) = "spam-editor-kind-${kind.name}"
}

private fun RuleKind.label(): Int =
    when (this) {
        RuleKind.Sender -> R.string.spam_kind_sender
        RuleKind.Keyword -> R.string.spam_kind_keyword
        RuleKind.UnknownLinks -> R.string.spam_rule_links
        RuleKind.ShortCodes -> R.string.spam_rule_short_codes
    }

private fun RuleKind.hint(): Int =
    when (this) {
        RuleKind.Sender -> R.string.spam_hint_sender
        RuleKind.Keyword -> R.string.spam_hint_keyword
        RuleKind.UnknownLinks -> R.string.spam_hint_links
        RuleKind.ShortCodes -> R.string.spam_hint_short
    }

private fun RuleProblem.text(): Int =
    when (this) {
        RuleProblem.Empty -> R.string.spam_err_empty
        RuleProblem.Duplicate -> R.string.spam_err_duplicate
        RuleProblem.TooMany -> R.string.spam_err_full
        RuleProblem.Failed -> R.string.inbox_spam_failed
    }

/**
 * The body of the rule sheet (GPUI's rule form, as a sheet): the title, the four kinds as a checked list, the field for
 * a sender pattern or the words (platform caret, selection and paste), a line that says how the rule matches, the
 * reason Rust refused it, then Cancel and Save and, for an existing rule, Delete. Save hands the text to
 * [onSave], which answers the refusal (or null, and the sheet closes). A new rule opens with the keyboard.
 */
@Composable
fun ColumnScope.RuleEditorContent(
    initial: RuleRow?,
    onSave: (kind: RuleKind, text: String, done: (RuleProblem?) -> Unit) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    field: TextFieldState = rememberTextFieldState(initial?.text.orEmpty()),
    startKind: RuleKind = initial?.kind ?: RuleKind.Sender,
) {
    var kind by rememberSaveable { mutableStateOf(startKind) }
    var problem by remember { mutableStateOf<RuleProblem?>(null) }
    val typed = field.text.toString()
    val valid = !kind.needsText || typed.replace("*", "").isNotBlank()
    EditorColumn(
        title = stringResource(if (initial == null) R.string.spam_rule_new else R.string.spam_rule_edit),
        modifier = modifier,
        focusOnOpen = initial == null,
    ) { focus ->
        IosSettingsGroup(
            items =
                RuleKind.entries.map { option ->
                    IosSettingsItem(
                        title = {
                            Box(
                                Modifier.testTag(RuleEditorTags.kind(option)).semantics {
                                    role = Role.RadioButton
                                    selected = option == kind
                                },
                            ) { IosText(stringResource(option.label())) }
                        },
                        trailingContent = {
                            Box(Modifier.size(Sizes.chevron), contentAlignment = Alignment.Center) {
                                if (option == kind) RostyIcon(RostyIcons.Checkmark, null, tint = IosTheme.tint)
                            }
                        },
                        trailingShrinks = true,
                        onClick = {
                            kind = option
                            problem = null
                        },
                    )
                },
        )
        if (kind.needsText) {
            GlassTextField(
                state = field,
                placeholder =
                    stringResource(
                        if (kind ==
                            RuleKind.Sender
                        ) {
                            R.string.spam_field_sender
                        } else {
                            R.string.spam_field_keyword
                        },
                    ),
                modifier = Modifier.fillMaxWidth().testTag(RuleEditorTags.Field),
                focusRequester = focus,
                onSubmit = { if (valid) onSave(kind, typed) { problem = it } },
            )
        }
        Hint(stringResource(kind.hint()))
        problem?.let { Problem(stringResource(it.text())) }
        Buttons(valid, onCancel) { onSave(kind, typed) { problem = it } }
        if (onDelete != null) {
            TextButton(
                stringResource(R.string.spam_delete_rule),
                onDelete,
                Modifier.align(Alignment.CenterHorizontally).testTag(RuleEditorTags.Delete),
                tint = RostyTheme.colors.destructive,
            )
        }
    }
}

/**
 * The body of the one-field sheets of the page: allow a sender, block a number. [onSave] answers the sentence to show
 * under the field when it was refused, or null (and the sheet closes).
 */
@Composable
fun ColumnScope.SenderEditorContent(
    title: String,
    placeholder: String,
    onSave: (text: String, done: (String?) -> Unit) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    phone: Boolean = false,
    field: TextFieldState = rememberTextFieldState(),
) {
    var problem by remember { mutableStateOf<String?>(null) }
    val typed = field.text.toString()
    val valid = typed.isNotBlank()
    EditorColumn(title, modifier, focusOnOpen = true) { focus ->
        GlassTextField(
            state = field,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth().testTag(RuleEditorTags.Field),
            keyboardOptions =
                if (phone) {
                    KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                    )
                } else {
                    KeyboardOptions.Default
                },
            focusRequester = focus,
            onSubmit = { if (valid) onSave(typed) { problem = it } },
        )
        problem?.let { Problem(it) }
        Buttons(valid, onCancel) { onSave(typed) { problem = it } }
    }
}

@Composable
private fun ColumnScope.EditorColumn(
    title: String,
    modifier: Modifier,
    focusOnOpen: Boolean,
    content: @Composable ColumnScope.(FocusRequester) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (focusOnOpen) runCatching { focus.requestFocus() }
    }
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            modifier
                .fillMaxWidth()
                .weight(1f)
                .imeOverlapPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .testTag(RuleEditorTags.Sheet),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            RostyText(
                title,
                Modifier.fillMaxWidth().padding(top = Spacing.s).semantics { heading() },
                style = RostyTheme.type.title2,
                textAlign = TextAlign.Center,
            )
            content(focus)
        }
    }
}

@Composable
private fun Hint(text: String) {
    RostyText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s),
        style = RostyTheme.type.footnote,
        color = RostyTheme.colors.labelSecondary,
    )
}

@Composable
private fun Problem(text: String) {
    RostyText(
        text,
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s).testTag(RuleEditorTags.Problem),
        style = RostyTheme.type.footnote,
        color = RostyTheme.colors.destructive,
    )
}

@Composable
private fun Buttons(
    valid: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        CapsuleButton(
            stringResource(R.string.inbox_cancel),
            onCancel,
            Modifier.weight(1f).testTag(RuleEditorTags.Cancel),
            fillWidth = true,
        )
        CapsuleButton(
            stringResource(R.string.spam_save),
            onSave,
            Modifier.weight(1f).testTag(RuleEditorTags.Save),
            style = CapsuleStyle.Filled,
            enabled = valid,
            fillWidth = true,
        )
    }
}
