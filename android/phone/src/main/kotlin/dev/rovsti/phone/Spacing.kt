@file:Suppress("ktlint:standard:filename")

package dev.rovsti.phone

import androidx.compose.ui.unit.dp

/** Dimensions of the Phone screens (convention: raw dp only in `Spacing.kt`). */
internal object PhoneMetrics {
    /** The status glyph of a Setup row. */
    val StatusIcon = 24.dp

    /** The thin progress bar under the Setup title. */
    val Bar = 5.dp
    val CardRadius = 16.dp
    val CardPad = 14.dp

    /** The notice line's shield tile. */
    val NoticeIcon = 22.dp

    /** The gap under the nav chrome of a root page that has a notice line. */
    val NoticeTop = 8.dp

    /** The tallest value column on the Diagnostics rows. */
    val ValueMax = 160.dp

    /** The log window height of a Diagnostics section with free lines. */
    val LogLine = 18.dp

    /** The gear button of the Keypad placeholder keeps the status bar clear by this margin. */
    val CornerInset = 8.dp
}

/** Font scale above which the Setup buttons stack instead of sitting in one row. */
internal const val STACK_FONT_SCALE = 1.3f

/** Opacity of a done Setup card (it recedes behind the open ones). */
internal const val DONE_CARD_ALPHA = 0.6f

/** Dimensions of the Keypad (E5.2); the sizes that depend on the screen come from `KeypadLayout`. */
internal object KeypadMetrics {
    /** One suggestion row. */
    val SuggestionRow = 56.dp
    val SuggestionAvatar = 40.dp

    /** The line under the field with "Add to Contacts". */
    val AddRow = 36.dp

    /** Space between the lowest key row and the tab bar. */
    val BarGap = 8.dp

    /** The reserved top of the field row, under the status bar. */
    val FieldTop = 8.dp

    /** The field's side margin that keeps the gear clear. */
    val FieldSide = 68.dp

    /** The key's number is this share of its diameter (sp, not scaled by the font size: a key keeps its shape). */
    const val DigitRatio = 0.40f
    const val LettersRatio = 0.14f

    /** The glyph on the call and delete keys. */
    val CallGlyph = 30.dp
    val DeleteGlyph = 28.dp

    /** The dot of a SIM in menus and the chooser. */
    val SimDot = 12.dp
    val SimRowHeight = 56.dp
    val SimSheetBase = 120.dp

    /** The glyph in a menu row. */
    val MenuGlyph = 20.dp
}

/** The delete key repeats after this long, then every [DELETE_REPEAT_MS]; a hold of [DELETE_CLEAR_MS] clears. */
internal const val DELETE_REPEAT_AFTER_MS = 400L
internal const val DELETE_REPEAT_MS = 70L
internal const val DELETE_CLEAR_MS = 2_000L

/** A hold on a key counts after this long (the platform's long-press timeout is 400 ms). */
internal const val KEY_HOLD_MS = 450L

/** The Keypad font does not follow the system font scale past this factor (keys keep their shape). */
internal const val KEYPAD_FONT_SCALE_CAP = 1.3f
