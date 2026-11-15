package dev.rosty.messages.backup.theme

import androidx.compose.ui.unit.dp

/** The sizes of the backup sheets that are not design tokens (convention: no raw dp at call sites). */
object BackupMetrics {
    /** A passphrase field: as tall as the glass text fields, with their rounded ends. */
    val FieldMinHeight = 44.dp
    val FieldCorner = 22.dp
    val FieldPadH = 14.dp
}
