package dev.rosty.messages.wa.rich

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ContactCardUi
import dev.rosty.messages.conversation.PollOptionUi
import dev.rosty.messages.conversation.RichCardUi
import dev.rosty.messages.theme.RichMetrics

/** What a rich card does when tapped (E6-WA R5); the page wires it to the view model, the system and its sheets. */
@Stable
class RichCardActions(
    /** My new choice in a poll (empty: taken back). */
    val onVote: (messageId: String, options: List<String>) -> Unit = { _, _ -> },
    val onOpenPlace: (RichCardUi.Place) -> Unit = {},
    /** "Message" on a contact card: opens the WhatsApp chat of the number. */
    val onMessage: (jid: String) -> Unit = {},
    /** "Add to contacts": the system's new-contact screen with the card. */
    val onAddContact: (ContactCardUi) -> Unit = {},
    /** The map pictures of place cards; null: a plain map placeholder with the pin. */
    val maps: PlaceMaps? = null,
)

/** Test tags of the rich cards. */
object RichTags {
    const val POLL = "wa_poll"
    const val PLACE = "wa_place"
    const val CONTACT = "wa_contact"

    fun option(name: String) = "wa_poll_option:$name"
}

/** The card of a poll, a place or contact cards inside a bubble ([BubbleUi.rich]). */
@Composable
fun RichCard(
    bubble: BubbleUi,
    ink: Color,
    secondary: Color,
    actions: RichCardActions,
) {
    when (val card = bubble.rich ?: return) {
        is RichCardUi.Poll -> PollCard(card, ink, secondary, actions)
        is RichCardUi.Place -> PlaceCard(card, ink, secondary, actions)
        is RichCardUi.Contacts -> ContactsCard(card, ink, secondary, actions)
    }
}

// ---- poll

@Composable
private fun PollCard(
    poll: RichCardUi.Poll,
    ink: Color,
    secondary: Color,
    actions: RichCardActions,
) {
    Column(Modifier.width(RichMetrics.CardWidth).testTag(RichTags.POLL)) {
        RostyText(poll.question, style = RostyTheme.type.headline, color = ink)
        RostyText(
            poll.hint,
            style = RostyTheme.type.caption1,
            color = secondary,
            modifier = Modifier.padding(top = Spacing.textGap, bottom = Spacing.xs),
        )
        for (option in poll.options) {
            PollOptionRow(option, ink, secondary, enabled = poll.canVote) {
                val mine = poll.options.filter { it.mine }.map { it.name }
                val next =
                    when {
                        option.mine -> mine - option.name
                        poll.single -> listOf(option.name)
                        else -> mine + option.name
                    }
                actions.onVote(poll.messageId, next)
            }
        }
        RostyText(
            poll.total,
            style = RostyTheme.type.caption1,
            color = secondary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun PollOptionRow(
    option: PollOptionUi,
    ink: Color,
    secondary: Color,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val accent = RostyTheme.colors.accent
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RichMetrics.PollOptionMin)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onTap)
            .padding(vertical = Spacing.xs)
            .testTag(RichTags.option(option.name)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val mark = Modifier.size(RichMetrics.PollMark)
        if (option.mine) {
            RostyIcon(RostyIcons.CircleCheck, null, tint = accent, size = RichMetrics.PollMark, modifier = mark)
        } else {
            Box(mark.border(RichMetrics.PinRing / 2, secondary, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RostyText(
                    option.name,
                    style = RostyTheme.type.subheadline,
                    color = ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RostyText(
                    option.count.toString(),
                    style = RostyTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = if (option.leading) ink else secondary,
                    modifier = Modifier.padding(start = Spacing.s),
                )
            }
            ShareBar(option.share, if (option.mine) accent else secondary)
            if (option.voters.isNotEmpty()) {
                RostyText(
                    option.voters,
                    style = RostyTheme.type.caption2,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ShareBar(
    share: Float,
    tint: Color,
) {
    val shape = RoundedCornerShape(RichMetrics.PollBarCorner)
    Box(
        Modifier
            .padding(vertical = Spacing.textGap)
            .fillMaxWidth()
            .heightIn(min = RichMetrics.PollBar)
            .clip(shape)
            .background(tint.copy(alpha = TRACK_ALPHA)),
    ) {
        if (share > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .heightIn(min = RichMetrics.PollBar)
                    .clip(shape)
                    .background(tint),
            )
        }
    }
}

// ---- place

@Composable
private fun PlaceCard(
    place: RichCardUi.Place,
    ink: Color,
    secondary: Color,
    actions: RichCardActions,
) {
    Column(
        Modifier
            .width(RichMetrics.CardWidth)
            .clip(RoundedCornerShape(RichMetrics.CardCorner))
            .clickable(role = Role.Button) { actions.onOpenPlace(place) }
            .testTag(RichTags.PLACE),
    ) {
        PlaceMap(place.latitude, place.longitude, RichMetrics.CardWidth, RichMetrics.MapHeight, actions.maps)
        Column(Modifier.padding(top = Spacing.xs)) {
            RostyText(
                place.title,
                style = RostyTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            place.subtitle?.let {
                RostyText(it, style = RostyTheme.type.footnote, color = secondary, maxLines = 2)
            }
            place.live?.let {
                RostyText(
                    it,
                    style = RostyTheme.type.caption1.copy(fontWeight = FontWeight.SemiBold),
                    color = RostyTheme.colors.accent,
                )
            }
        }
    }
}

/**
 * A static map of a point with its pin in the middle: painted from the user's tile server through [maps] (Stops'
 * tile painting, tiles in memory only), or a plain tinted area while it loads, offline, and in screenshots.
 */
@Composable
fun PlaceMap(
    latitude: Double,
    longitude: Double,
    width: Dp,
    height: Dp,
    maps: PlaceMaps?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dark = RostyTheme.colors.isDark
    val widthPx = with(density) { width.roundToPx() }
    val heightPx = with(density) { height.roundToPx() }
    val picture by produceState<android.graphics.Bitmap?>(null, maps, latitude, longitude, widthPx, heightPx, dark) {
        value = maps?.picture(latitude, longitude, widthPx, heightPx, density.density, dark)
    }
    Box(
        modifier
            .width(width)
            .heightIn(min = height)
            .clip(RoundedCornerShape(RichMetrics.CardCorner))
            .background(RostyTheme.colors.fillTertiary),
        contentAlignment = Alignment.Center,
    ) {
        picture?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(width).heightIn(min = height).fillMaxHeight(),
            )
        }
        Pin()
    }
}

@Composable
private fun Pin() {
    val accent = RostyTheme.colors.accent
    Canvas(Modifier.size(RichMetrics.Pin + RichMetrics.PinRing * 2)) {
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(Color.White, radius = size.minDimension / 2, center = center)
        drawCircle(accent, radius = size.minDimension / 2 - RichMetrics.PinRing.toPx(), center = center)
    }
}

// ---- contacts

@Composable
private fun ContactsCard(
    card: RichCardUi.Contacts,
    ink: Color,
    secondary: Color,
    actions: RichCardActions,
) {
    Column(
        Modifier.width(RichMetrics.CardWidth).testTag(RichTags.CONTACT),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        for (contact in card.cards) {
            ContactRow(contact, ink, secondary, actions)
        }
    }
}

@Composable
private fun ContactRow(
    contact: ContactCardUi,
    ink: Color,
    secondary: Color,
    actions: RichCardActions,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Box(
                Modifier
                    .size(
                        RichMetrics.ContactAvatar,
                    ).clip(CircleShape)
                    .background(secondary.copy(alpha = TRACK_ALPHA)),
                contentAlignment = Alignment.Center,
            ) {
                RostyIcon(RostyIcons.Person, null, tint = ink, size = RichMetrics.PollMark)
            }
            Column(Modifier.weight(1f)) {
                RostyText(
                    contact.name,
                    style = RostyTheme.type.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (contact.numbers.isEmpty()) {
                    RostyText(
                        stringResource(R.string.wa_contact_no_number),
                        style = RostyTheme.type.footnote,
                        color = secondary,
                    )
                }
                for (number in contact.numbers) {
                    RostyText(number, style = RostyTheme.type.footnote, color = secondary, maxLines = 1)
                }
            }
        }
        Row(
            Modifier.padding(top = Spacing.xs).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            contact.messageJid?.let { jid ->
                CardButton(stringResource(R.string.wa_contact_message), RostyIcons.Message, ink, Modifier.weight(1f)) {
                    actions.onMessage(jid)
                }
            }
            CardButton(stringResource(R.string.wa_contact_add), RostyIcons.PersonAdd, ink, Modifier.weight(1f)) {
                actions.onAddContact(contact)
            }
        }
    }
}

@Composable
private fun CardButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ink: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .heightIn(min = RichMetrics.CardButtonMin)
            .clip(RoundedCornerShape(RichMetrics.CardCorner))
            .background(ink.copy(alpha = BUTTON_ALPHA))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
    ) {
        RostyIcon(icon, null, tint = ink, size = RichMetrics.Pin)
        RostyText(
            text,
            style = RostyTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
        )
    }
}

private const val TRACK_ALPHA = 0.22f
private const val BUTTON_ALPHA = 0.12f
