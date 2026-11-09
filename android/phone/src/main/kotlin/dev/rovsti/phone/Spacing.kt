@file:Suppress("ktlint:standard:filename")

package dev.rovsti.phone

import androidx.compose.ui.unit.dp

/** Dimensions of the Phone screens (convention: raw dp only in `Spacing.kt`). */
internal object PhoneMetrics {
    /** The status glyph of a Setup row. */
    val StatusIcon = 24.dp

    /** The thin progress bar under the Setup title. */
    val Bar = 5.dp
    val CardRadius = 16.dp
    val CardPad = 14.dp

    /** The notice line's shield tile. */
    val NoticeIcon = 22.dp

    /** The gap under the nav chrome of a root page that has a notice line. */
    val NoticeTop = 8.dp

    /** The tallest value column on the Diagnostics rows. */
    val ValueMax = 160.dp

    /** The log window height of a Diagnostics section with free lines. */
    val LogLine = 18.dp

    /** The gear button of the Keypad placeholder keeps the status bar clear by this margin. */
    val CornerInset = 8.dp
}

/** Font scale above which the Setup buttons stack instead of sitting in one row. */
internal const val STACK_FONT_SCALE = 1.3f

/** Opacity of a done Setup card (it recedes behind the open ones). */
internal const val DONE_CARD_ALPHA = 0.6f
