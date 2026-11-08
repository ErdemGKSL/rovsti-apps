package dev.rovsti.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.BackdropScope
import dev.rovsti.design.text.ComposerField
import dev.rovsti.design.text.GlassTextField
import dev.rovsti.design.text.SearchField
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/**
 * E1.16: the text fields. A plain field, a field with text, a search field with a clear button, and the message composer
 * docked at the bottom edge. The lines in the middle report what each field did, so a tester sees every callback.
 */
@Composable
internal fun TextFieldsPage() {
    val name = rememberTextFieldState()
    val note = rememberTextFieldState("Call back after the meeting")
    val search = rememberTextFieldState("Ayla")
    val emptySearch = rememberTextFieldState()
    val composer = rememberTextFieldState()
    var submits by remember { mutableIntStateOf(0) }
    var sent by remember { mutableStateOf("Nothing sent yet") }
    BackdropScope(
        modifier = Modifier.fillMaxSize(),
        content = {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(insetsOnly())
                    .padding(bottom = ComposerClearance),
            ) {
                PageHeader("Text fields", "Tap to place the caret, hold to select, paste from the clipboard.")
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    RovstiText(
                        "Submitted $submits times",
                        modifier = Modifier.padding(top = Spacing.s).testTag("field-submits"),
                        style = RovstiTheme.type.footnote,
                        color = RovstiTheme.colors.labelSecondary,
                    )
                    RovstiText(
                        sent,
                        modifier = Modifier.testTag("composer-sent"),
                        style = RovstiTheme.type.footnote,
                        color = RovstiTheme.colors.labelSecondary,
                    )
                    SectionLabel("Field", Modifier.padding(top = Spacing.l))
                    GlassTextField(
                        name,
                        "Name",
                        Modifier.fillMaxWidth().testTag("field-empty"),
                        onSubmit = { submits++ },
                    )
                    GlassTextField(
                        note,
                        "Note",
                        Modifier.fillMaxWidth().testTag("field-note"),
                        onSubmit = { submits++ },
                    )
                    SectionLabel("Search", Modifier.padding(top = Spacing.l))
                    SearchField(
                        search,
                        Modifier.fillMaxWidth().testTag("field-search"),
                        onSubmit = { submits++ },
                    )
                    SearchField(emptySearch, Modifier.fillMaxWidth().testTag("field-search-empty"))
                }
            }
        },
        chrome = {
            ComposerField(
                state = composer,
                onSend = { sent = "Sent: $it" },
                modifier = Modifier.align(Alignment.BottomCenter),
                fieldModifier = Modifier.testTag("composer-field"),
            )
        },
    )
}

/** Room under the last item so the docked composer, one line tall, never covers it. */
private val ComposerClearance = 80.dp
