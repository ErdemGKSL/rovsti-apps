package dev.rovsti.contacts.photo

import androidx.compose.ui.unit.dp

/** The crop page's own dimensions; layout code reads them from here (convention: raw dp only in `Spacing.kt`). */
internal object CropMetrics {
    /** Room above the window for the title (below the status inset). */
    val Top = 56.dp

    /** Room under the window for the buttons. */
    val Bottom = 96.dp

    /** The smallest window, whatever the screen. */
    val WindowMin = 120.dp
    val Ring = 1.5.dp
}

/** How dark the picture is outside the circle, and how bright the ring is. */
internal const val CROP_DIM_ALPHA = 0.62f
internal const val CROP_RING_ALPHA = 0.85f

/** The picture is decoded at no more than this many px on its long side. */
internal const val CROP_DECODE_CAP_PX = 2048f
