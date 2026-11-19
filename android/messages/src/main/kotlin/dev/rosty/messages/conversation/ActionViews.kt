package dev.rosty.messages.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.SizedImage
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MediaMetrics
import dev.rosty.messages.theme.MessagesMetrics
import kotlinx.collections.immutable.ImmutableList

/** Test tags of the message actions (E6-WA R4). */
object ActionTags {
    const val Editing = "wa-editing-bar"
    const val LinkChip = "wa-link-chip"
    const val LinkChipRemove = "wa-link-chip-remove"
    const val LinkCard = "wa-link-card"
    const val Mentions = "wa-mentions"
    const val Notice = "wa-notice"

    fun mention(jid: String) = "wa-mention-$jid"
}

/** A line of the conversation ("Disappearing messages turned on: 7 days"): a centred pill, no bubble. */
@Composable
internal fun NoticeLine(bubble: BubbleUi) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
            .testTag(ActionTags.Notice),
        contentAlignment = Alignment.Center,
    ) {
        RostyText(
            bubble.text,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
                    .background(RostyTheme.colors.fillSecondary)
                    .padding(horizontal = Spacing.m, vertical = Spacing.xs),
            style = RostyTheme.type.caption1,
            color = RostyTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** A bubble's text with its mentions drawn as names: semibold in the accent tone. */
@Composable
internal fun MarkedText(
    bubble: BubbleUi,
    style: TextStyle,
    ink: Color,
) {
    val accent = if (bubble.outgoing) ink else RostyTheme.colors.accent
    val text =
        remember(bubble.text, bubble.marks, accent) {
            buildAnnotatedString {
                append(bubble.text)
                for (mark in bubble.marks) {
                    val start = mark.start.coerceIn(0, bubble.text.length)
                    val end = mark.end.coerceIn(start, bubble.text.length)
                    addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), start, end)
                }
            }
        }
    BasicText(text, style = style.merge(TextStyle(color = ink)))
}

/** The card of a link under a bubble's text: picture, title, description and host; a tap opens the link. */
@Composable
internal fun LinkCard(
    card: LinkCardUi,
    ink: Color,
    secondary: Color,
    onOpen: (String) -> Unit,
) {
    Row(
        Modifier
            .padding(top = Spacing.xs)
            .clip(RoundedCornerShape(MessagesMetrics.FolderTileCorner))
            .background(secondary.copy(alpha = CARD_ALPHA))
            .clickable(role = Role.Button) { onOpen(card.url) }
            .padding(Spacing.s)
            .testTag(ActionTags.LinkCard),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        card.picture?.let { LinkPicture(it) }
        Column(Modifier.widthIn(max = MessagesMetrics.LinkCardTextMax)) {
            card.title?.let {
                RostyText(
                    it,
                    style = RostyTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            card.description?.let {
                RostyText(
                    it,
                    style = RostyTheme.type.footnote,
                    color = secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            card.host?.let {
                RostyText(it, style = RostyTheme.type.caption2, color = secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LinkPicture(model: Any) {
    val px = with(LocalDensity.current) { MessagesMetrics.LinkPicture.roundToPx() }
    SizedImage(
        model = model,
        widthPx = px,
        heightPx = px,
        contentScale = ContentScale.Crop,
        modifier =
            Modifier
                .size(MessagesMetrics.LinkPicture)
                .clip(RoundedCornerShape(MessagesMetrics.FolderTileCorner)),
    )
}

/** The link card of the next Send above the composer; × sends the message without it. */
@Composable
internal fun LinkChip(
    chip: LinkChipUi,
    onRemove: () -> Unit,
) {
    val colors = RostyTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.s)
            .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
            .background(colors.fillSecondary)
            .padding(start = Spacing.s)
            .testTag(ActionTags.LinkChip),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (chip.picture != null) {
            LinkPicture(chip.picture)
        } else {
            RostyIcon(RostyIcons.Globe, null, tint = colors.labelSecondary, size = MessagesMetrics.RowAccessory)
        }
        Column(Modifier.weight(1f).padding(vertical = Spacing.s)) {
            RostyText(
                if (chip.loading) stringResource(R.string.wa_link_loading) else chip.title ?: chip.url,
                style = RostyTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RostyText(
                chip.host ?: chip.url,
                style = RostyTheme.type.caption1,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(MediaMetrics.FileAction)
                .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.wa_link_remove),
                    onClick = onRemove,
                ).testTag(ActionTags.LinkChipRemove),
            contentAlignment = Alignment.Center,
        ) {
            RostyIcon(
                RostyIcons.Xmark,
                stringResource(R.string.wa_link_remove),
                tint = colors.labelSecondary,
                size = MessagesMetrics.RowAccessory,
            )
        }
    }
}

/** The members the `@` being typed may name, above the composer; a tap inserts the name. */
@Composable
internal fun MentionList(
    options: ImmutableList<MentionOptionUi>,
    onPick: (MentionOptionUi) -> Unit,
) {
    val colors = RostyTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.s)
            .clip(RoundedCornerShape(MessagesMetrics.BubbleCorner))
            .background(colors.fillSecondary)
            .heightIn(max = MessagesMetrics.MentionListMax)
            .verticalScroll(rememberScrollState())
            .testTag(ActionTags.Mentions),
    ) {
        for (option in options) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(option) }
                    .padding(horizontal = Spacing.m, vertical = Spacing.s)
                    .testTag(ActionTags.mention(option.jid)),
            ) {
                RostyText(option.name, style = RostyTheme.type.body, color = colors.labelPrimary, maxLines = 1)
                option.detail?.let {
                    RostyText(it, style = RostyTheme.type.caption1, color = colors.labelSecondary, maxLines = 1)
                }
            }
        }
    }
}

private const val CARD_ALPHA = 0.12f
