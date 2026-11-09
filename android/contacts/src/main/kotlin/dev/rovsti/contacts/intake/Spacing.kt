package dev.rovsti.contacts.intake

import androidx.compose.ui.unit.dp

/** The preview sheet (convention: raw dp only in `Spacing.kt`). */
internal object IntakeMetrics {
    val HeaderTop = 4.dp
    val BarButtonHeight = 44.dp
}

/** The read-only card inside the sheet. */
internal object IntakeCardMetrics {
    val Avatar = 88.dp
    val Top = 8.dp
    val LineGap = 2.dp
    val LineVertical = 10.dp
}

/** The column-mapping sheet (C39). */
internal object MappingMetrics {
    /** The widest the role label of a row may grow, so a long header keeps its room. */
    val RoleMaxWidth = 128.dp

    /** The chevron next to the role label. */
    val Chevron = 14.dp
}
