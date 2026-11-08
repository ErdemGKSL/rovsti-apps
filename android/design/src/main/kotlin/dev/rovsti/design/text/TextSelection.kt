package dev.rovsti.design.text

import androidx.compose.foundation.text.selection.TextSelectionColors
import dev.rovsti.design.theme.RovstiColors

/** Share of the accent used for the selection highlight; the text on it keeps its normal colour. */
internal const val SELECTION_BACKGROUND_ALPHA = 0.3f

/**
 * The text-selection colours of the app: both drag handles and the caret-adjacent teardrop in the accent, the highlight
 * behind selected text the accent at 30 %. `RovstiTheme` provides this through `LocalTextSelectionColors`, so every
 * `BasicTextField` under the theme (fields, composer, a future dialer number) picks it up without wiring.
 */
fun rovstiSelectionColors(colors: RovstiColors): TextSelectionColors =
    TextSelectionColors(
        handleColor = colors.accent,
        backgroundColor = colors.accent.copy(alpha = SELECTION_BACKGROUND_ALPHA),
    )
