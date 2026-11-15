package dev.rosty.messages.theme

import androidx.compose.ui.unit.dp

/** The sizes of the Status views that are not design tokens. */
internal object StatusMetrics {
    val Ring = 2.dp
    val RingGap = 2.dp

    /** The avatar inside the ring, so ring and avatar together fill the row's 40 dp leading slot. */
    val RowAvatar = 32.dp
    val ViewerAvatar = 36.dp
    val Bar = 3.dp
    val BarGap = 4.dp
    val TextMaxWidth = 360.dp
    val Play = 64.dp
    val PlayGlyph = 28.dp
    val MessageLift = 96.dp
}
