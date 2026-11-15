package dev.rosty.messages.schedule.theme

import androidx.compose.ui.unit.dp

/** The sizes of scheduled send that are not design tokens (convention: no raw dp at call sites). */
object ScheduleMetrics {
    /** The Send button of `ComposerField`: a 32 dp disc inset 6 dp from the pill's end and bottom. */
    val SendButton = 32.dp
    val SendInset = 6.dp

    /** Extra reach around the Send button for the long press. */
    val SendSlop = 8.dp

    /** The small glyphs of the strip header and the late note. */
    val NoteGlyph = 14.dp

    /** The rounded panel of the Scheduled strip. */
    val StripCorner = 14.dp

    /** The tallest the strip grows before its rows scroll. */
    val StripMaxHeight = 260.dp
}
