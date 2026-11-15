package dev.rosty.messages.starred.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The sizes and the one colour of the Starred list that are not design tokens. */
internal object StarredMetrics {
    /** The star at the end of a row (its touch target is the full 48 dp). */
    val StarGlyph = 20.dp

    /** The system yellow of a filled star; it reads on the light and the dark page (a glyph, not text). */
    val StarTint = Color(0xFFFFB800)
}
