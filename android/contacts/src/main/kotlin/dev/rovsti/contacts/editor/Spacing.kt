package dev.rovsti.contacts.editor

import androidx.compose.ui.unit.dp

/** The editor's own dimensions; layout code reads them from here (convention: raw dp only in `Spacing`/`Metrics` files). */
internal object EditorMetrics {
    val HeaderHeight = 52.dp
    val Avatar = 88.dp
    val RowVertical = 6.dp
    val Minus = 24.dp
    val Slot = 32.dp
    val MinusBar = 12.dp
    val MinusBarThickness = 2.dp
    val LabelButtonMin = 76.dp
    val LabelButtonMax = 112.dp
    val PopoverWidth = 224.dp
    val PopoverRow = 44.dp
    val IssueIndent = 4.dp
    val HintIndent = 14.dp
    val Zero = 0.dp

    /** A focused field stays this far above the keyboard (`main.rs:115`, `REVEAL_MARGIN`). */
    val RevealMargin = 16.dp
    val NoteLines = 4
}

/** The notes field starts two lines tall. */
internal const val NOTE_MIN_LINES = 2
