@file:Suppress("ktlint:standard:filename")

package dev.rovsti.contacts.cleanup

import androidx.compose.ui.unit.dp

/** Sizes of the Clean up page that are not design-system tokens (convention: raw dp only in `Spacing.kt`). */
internal object CleanupMetrics {
    /** No width: the select slot of a row while not selecting. */
    val None = 0.dp
}
