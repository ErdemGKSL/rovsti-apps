package dev.rosty.messages.remind.theme

import androidx.compose.ui.unit.dp

/** The sizes of the "Remind me" popover that are not design tokens. */
internal object RemindMetrics {
    val CardWidth = 264.dp
    val Hairline = 0.5.dp
    val RowGlyph = 18.dp

    /** The room the card needs below a bubble (its header, four slots, "Cancel reminder" and the bars' margin). */
    val CardRoom = 360.dp
}
