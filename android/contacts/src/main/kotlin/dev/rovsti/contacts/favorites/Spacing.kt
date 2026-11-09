package dev.rovsti.contacts.favorites

import androidx.compose.ui.unit.dp

/** Dimensions of the Favorites tab (convention: raw dp only in `Spacing.kt`). */
internal object FavoritesMetrics {
    val HandleGlyph = 20.dp
    val LiftShadow = 12.dp
    val WishHeight = 32.dp
    val StarGlyph = 20.dp
}

/** The row being dragged grows a little (iOS lifts the picked row). */
internal const val LIFT_SCALE = 1.03f
