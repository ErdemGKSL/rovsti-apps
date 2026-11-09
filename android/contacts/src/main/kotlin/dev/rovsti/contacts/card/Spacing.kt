package dev.rovsti.contacts.card

import androidx.compose.ui.unit.dp

/** The card's own dimensions; layout code reads them from here (convention: raw dp only in `Spacing.kt`). */
internal object CardMetrics {
    val CapsuleHeight = 56.dp
    val HeroAvatar = 88.dp
    val CapsuleIcon = 22.dp
    val CapsuleLabelGap = 2.dp
    val NoGap = 0.dp
    val TagRadius = 6.dp
}

/** Below this font scale a capsule shows its label; from here on only the icon (the label is still spoken). */
internal const val ICON_ONLY_FONT_SCALE = 1.5f
