package dev.rosty.messages.theme

import androidx.compose.ui.unit.dp

/** The few sizes of Messages that are not design tokens (convention: no raw dp at call sites). */
object MessagesMetrics {
    val None = 0.dp
    val RowAccessory = 14.dp
    val SwipeGlyph = 20.dp
    val UnreadBadgeMin = 20.dp
    val UnreadBadgePad = 6.dp
    val OtpChipVertical = 4.dp
    val FolderTile = 32.dp
    val FolderTileCorner = 8.dp
    val FolderGlyph = 18.dp
    val BubbleMaxFraction = 0.78f
    val BubbleCorner = 18.dp
    val BubbleTail = 6.dp
    val BubblePadH = 12.dp
    val BubblePadV = 7.dp
    val BubbleGap = 2.dp
    val BubbleGroupGap = 8.dp
    val TickGlyph = 14.dp
    val ComposerButton = 36.dp
    val JumpButton = 40.dp
    val EmptyShare = 0.7f
    val GlowPad = 2.dp

    /** The gallery grid: hairline gaps, slightly rounded thumbnails. */
    val GalleryGap = 2.dp
    val GalleryCorner = 4.dp
}
