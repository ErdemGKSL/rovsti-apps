package dev.rosty.messages.settings.textsize

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.rosty.core.model.messages.ChatTextSize
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.settings.Footnote
import dev.rosty.messages.settings.radioItem
import dev.rosty.messages.settings.theme.SettingsMetrics
import dev.rosty.messages.theme.MessagesColors
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsPage
import kotlinx.coroutines.launch

/** Test tags of the Text size page. */
object TextSizeTags {
    const val Page = "messages-text-size"
    const val Preview = "messages-text-size-preview"

    fun option(size: ChatTextSize) = "messages-text-size-${size.name.lowercase()}"
}

/**
 * Settings, Text size (M34, GPUI `settings_ui::chats_section`): a live preview of a received and a sent bubble at the
 * chosen size, then Small, Default, Large and Larger as radio rows. Only the text of message bubbles changes.
 */
@Composable
fun TextSizePage(
    selected: ChatTextSize,
    onSelect: (ChatTextSize) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    val scale = ChatTextScale.factor(selected)
    SettingsPage(
        title = stringResource(R.string.text_size_title),
        modifier = modifier.testTag(TextSizeTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        IosSettingsGroup(
            title = stringResource(R.string.text_size_preview),
            items =
                listOf(
                    IosSettingsItem(
                        title = {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.s)
                                    .testTag(TextSizeTags.Preview)
                                    .clearAndSetSemantics {},
                                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                            ) {
                                PreviewBubble(stringResource(R.string.text_size_sample_in), false, scale)
                                PreviewBubble(stringResource(R.string.text_size_sample_out), true, scale)
                            }
                        },
                    ),
                ),
        )
        IosSettingsGroup(
            title = stringResource(R.string.text_size_group),
            items =
                ChatTextScale.all.map { size ->
                    radioItem(
                        title = stringResource(ChatTextScale.label(size)),
                        chosen = size == selected,
                        tag = TextSizeTags.option(size),
                    ) { if (size != selected) onSelect(size) }
                },
        )
        Footnote(stringResource(R.string.text_size_footer))
    }
}

@Composable
private fun PreviewBubble(
    text: String,
    outgoing: Boolean,
    scale: Float,
) {
    val fill = if (outgoing) MessagesColors.OutgoingBubble else RostyTheme.colors.fillSecondary
    val ink = if (outgoing) MessagesColors.OutgoingInk else RostyTheme.colors.labelPrimary
    Box(Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        RostyText(
            text,
            Modifier
                .widthIn(max = SettingsMetrics.PreviewBubbleMax)
                .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
                .background(fill)
                .padding(horizontal = MessagesMetrics.BubblePadH, vertical = MessagesMetrics.BubblePadV),
            style = RostyTheme.type.body.scaledBy(scale),
            color = ink,
        )
    }
}

/** The Text size route over `messages-prefs.json` (`chat_text_size`). */
@Composable
fun TextSizeEntry(
    container: MessagesContainer,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val prefs by container.store.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(container) { container.store.load() }
    TextSizePage(
        selected = prefs?.chatTextSize ?: ChatTextSize.Default,
        onSelect = { size -> scope.launch { container.store.updatePrefs { it.copy(chatTextSize = size) } } },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
