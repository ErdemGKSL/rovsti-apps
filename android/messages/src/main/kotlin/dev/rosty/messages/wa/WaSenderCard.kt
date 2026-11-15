package dev.rosty.messages.wa

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.text.rtlSafe
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.theme.MediaMetrics
import dev.rosty.messages.theme.MessagesMetrics
import dev.rosty.messages.ui.MessagesAvatar

/** Test tags of the sender card. */
object SenderCardTags {
    const val Root = "sender-card"
    const val Message = "sender-card-message"
    const val Call = "sender-card-call"
    const val Contact = "sender-card-contact"
}

/** What the rows of the sender card do. */
class SenderCardActions(
    val onMessage: (SenderCardUi) -> Unit,
    val onCall: (SenderCardUi) -> Unit,
    val onContact: (SenderCardUi) -> Unit,
)

/**
 * The card a tap on a sender's name in a group opens (M50): who it is (picture, name, number) and what can be done with
 * them: message them privately, call them, open their contact (or add them when they are not in the address book).
 * It is the content of a popover anchored to the name; a sender WhatsApp shows without a number can only be messaged.
 */
@Composable
fun SenderMiniCard(
    card: SenderCardUi,
    actions: SenderCardActions,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    Column(modifier.width(MediaMetrics.CardWidth).testTag(SenderCardTags.Root)) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            MessagesAvatar(card.name, card.avatar, size = MediaMetrics.CardAvatar)
            Column(Modifier.weight(1f)) {
                RostyText(
                    card.name.rtlSafe(),
                    style = RostyTheme.type.headline,
                    color = RostyTheme.glass.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The number is the name already when nobody named them.
                card.numberLabel?.takeIf { it != card.name && card.number != card.name }?.let {
                    RostyText(
                        it.rtlSafe(),
                        modifier = Modifier.padding(top = Spacing.textGap),
                        style = RostyTheme.type.subheadline,
                        color = colors.labelSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        CardRow(RostyIcons.Message, stringResource(R.string.wa_sender_message), SenderCardTags.Message) {
            actions.onMessage(card)
        }
        if (card.number != null) {
            CardRow(RostyIcons.Phone, stringResource(R.string.wa_sender_call), SenderCardTags.Call) {
                actions.onCall(card)
            }
            CardRow(
                if (card.saved) RostyIcons.Person else RostyIcons.PersonAdd,
                stringResource(if (card.saved) R.string.wa_sender_open_contact else R.string.wa_sender_add_contact),
                SenderCardTags.Contact,
            ) { actions.onContact(card) }
        }
    }
}

@Composable
private fun CardRow(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(MediaMetrics.Hairline).background(RostyTheme.colors.separator))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touch)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        RostyText(
            label,
            modifier = Modifier.weight(1f),
            style = RostyTheme.type.body,
            color = RostyTheme.glass.foreground,
            maxLines = 2,
        )
        RostyIcon(icon, null, tint = RostyTheme.glass.foreground, size = MessagesMetrics.FolderGlyph)
    }
}
