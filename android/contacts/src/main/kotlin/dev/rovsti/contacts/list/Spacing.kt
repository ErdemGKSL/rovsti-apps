@file:Suppress("ktlint:standard:filename")

package dev.rovsti.contacts.list

import androidx.compose.ui.unit.dp

/** Sizes of select mode (E4.8) that are not design-system tokens. */
internal object SelectMetrics {
    /** The toolbar beside the folded tab-bar circle (the accessory's inline height) and in the expanded bar. */
    val ToolbarInline = 48.dp
    val ToolbarExpanded = 54.dp
    val ToolbarSide = 4.dp
    val MoreIcon = 20.dp

    /** No width: the select slot of a row while not selecting. */
    val None = 0.dp
}

/** Sizes of the list's top rows (E4.14). */
internal object ListMetrics {
    /** The glyph in the round tile of the Emergency Contacts and Recent rows. */
    val TileGlyph = 20.dp
}
