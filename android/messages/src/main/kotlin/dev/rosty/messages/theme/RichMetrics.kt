package dev.rosty.messages.theme

import androidx.compose.ui.unit.dp

/** The sizes of WhatsApp's rich bubbles and sheets (E6-WA R5): polls, place cards, contact cards. */
object RichMetrics {
    /** The width of a poll, place or contact bubble's card. */
    val CardWidth = 240.dp

    /** A place card's map. */
    val MapHeight = 120.dp

    /** The map of the place page and of the "send my place" preview. */
    val MapPageHeight = 320.dp

    /** The pin on a map: its dot and its white ring. */
    val Pin = 14.dp
    val PinRing = 3.dp

    /** A poll option's tick circle and its share bar. */
    val PollMark = 20.dp
    val PollBar = 6.dp
    val PollBarCorner = 3.dp

    /** The smallest height of a tappable poll option and of a card button. */
    val PollOptionMin = 44.dp
    val CardButtonMin = 36.dp

    /** A contact card's initial. */
    val ContactAvatar = 36.dp

    /** Corner of a card's map and of its buttons. */
    val CardCorner = 10.dp

    /** A row of the poll sheet's options. */
    val OptionFieldMin = 44.dp
}
