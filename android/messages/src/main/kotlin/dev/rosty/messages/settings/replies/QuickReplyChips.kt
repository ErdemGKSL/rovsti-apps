package dev.rosty.messages.settings.replies

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.core.Outcome
import dev.rosty.core.model.chat.InsertReplyRequest
import dev.rosty.core.model.chat.RepliesAction
import dev.rosty.core.model.chat.RepliesRequest
import dev.rosty.design.press.scalePress
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.settings.textsize.ChatTextScale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/** One quick reply above a composer: the one-line [label] the core cut, and the whole [text] it inserts. */
@Immutable
data class ReplyChip(
    val label: String,
    val text: String,
)

/** Test tags of the chip row. */
object ReplyChipTags {
    const val Row = "conversation-quick-replies"

    fun chip(index: Int) = "conversation-quick-reply-$index"
}

/** What both thread pages take from Settings: the bubble text size (M34) and the reply chips (M11). */
@Immutable
class ComposerExtras(
    val textScale: Float,
    val chips: ImmutableList<ReplyChip>,
    val insert: (ReplyChip) -> Unit,
)

/**
 * The settings a conversation page follows, read from `messages-prefs.json`: the text size factor and the quick
 * replies as chips (`quick_replies.edit` "show" cuts the labels). A chip tap puts the reply into [composer] through
 * `quick_replies.insert` (after the draft, with a space).
 */
@Composable
fun rememberComposerExtras(
    container: MessagesContainer,
    composer: TextFieldState,
): ComposerExtras {
    val prefs by container.store.prefs.collectAsState()
    val size = prefs?.chatTextSize
    val replies = prefs?.quickReplies
    val chips by produceState<ImmutableList<ReplyChip>>(persistentListOf(), replies) {
        val list = replies ?: return@produceState
        val answer = (container.chat.replies(RepliesRequest(list, RepliesAction.Show)) as? Outcome.Ok)?.value
        value =
            answer
                ?.let { a -> a.list.zip(a.chips) { text, label -> ReplyChip(label, text) } }
                .orEmpty()
                .toImmutableList()
    }
    val scope = rememberCoroutineScope()
    val insert: (ReplyChip) -> Unit =
        remember(container, composer, scope) {
            { chip ->
                scope.launch {
                    val draft = composer.text.toString()
                    val answer = container.chat.insertReply(InsertReplyRequest(draft, chip.text)) as? Outcome.Ok
                    answer?.let { composer.setTextAndPlaceCursorAtEnd(it.value.draft) }
                }
            }
        }
    val scale = size?.let(ChatTextScale::factor) ?: 1f
    return remember(scale, chips, insert) { ComposerExtras(scale, chips, insert) }
}

/** The horizontally scrolling row of reply chips above a composer (GPUI `quick_reply_row`); one line, never wraps. */
@Composable
fun QuickReplyRow(
    chips: ImmutableList<ReplyChip>,
    onPick: (ReplyChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag(ReplyChipTags.Row),
        contentPadding = PaddingValues(horizontal = Spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(chips, key = { _, chip -> chip.text }, contentType = { _, _ -> "reply" }) { index, chip ->
            Box(
                Modifier
                    .testTag(ReplyChipTags.chip(index))
                    .heightIn(min = Sizes.touch)
                    .scalePress()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) { onPick(chip) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .heightIn(min = Sizes.chip)
                        .drawBehind {
                            drawRoundRect(colors.fillTertiary, cornerRadius = CornerRadius(size.height / 2f))
                        }.padding(horizontal = Spacing.l, vertical = Spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    RostyText(
                        chip.label,
                        style = RostyTheme.type.subheadline,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}
