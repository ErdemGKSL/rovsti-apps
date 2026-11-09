@file:Suppress("ktlint:standard:filename")

package dev.rovsti.contacts.settings

import androidx.compose.ui.unit.dp

/** Dimensions of the Contacts settings pages (convention: raw dp only in `Spacing.kt`). */
internal object SettingsMetrics {
    val CheckSlot = 22.dp
    val Gap = 8.dp
    val ValueMax = 150.dp

    /** Space under a footer, so the next group title does not touch it. */
    val AfterFooter = 16.dp
}
