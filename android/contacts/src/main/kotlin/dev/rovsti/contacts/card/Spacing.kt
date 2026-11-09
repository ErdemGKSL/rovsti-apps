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

/** The QR sheet's own dimensions. */
internal object QrSheetMetrics {
    /** The white card with the code; a square. */
    val Code = 216.dp
    val TopGap = 8.dp
}

/** Test tags of the QR sheet. */
internal object QrSheetTags {
    const val Sheet = "qr-sheet"
    const val Code = "qr-sheet-code"
    const val Save = "qr-sheet-save"
    const val Share = "qr-sheet-share"
}
