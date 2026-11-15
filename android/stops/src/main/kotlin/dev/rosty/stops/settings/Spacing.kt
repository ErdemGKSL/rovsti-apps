@file:Suppress("ktlint:standard:filename")

package dev.rosty.stops.settings

import androidx.compose.ui.unit.dp

/** Dimensions of the Stops settings pages (convention: raw dp only in `Spacing.kt`). */
internal object SettingsMetrics {
    /** The empty trailing slot of an action row (no chevron), as wide as the tick of a radio row. */
    val CheckSlot = 22.dp
    val Gap = 8.dp
    val ValueMax = 160.dp

    /** Space under a footer, so the next group title does not touch it. */
    val AfterFooter = 16.dp

    /** The offline download's progress bar under its line. */
    val ProgressHeight = 4.dp
    val ProgressGap = 6.dp

    /** The URL field's inset from the page edge (the group titles' inset). */
    val FieldInset = 4.dp
    val FieldGap = 8.dp
}
