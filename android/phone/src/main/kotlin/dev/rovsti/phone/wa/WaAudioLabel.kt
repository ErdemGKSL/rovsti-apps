package dev.rovsti.phone.wa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import dev.rovsti.design.controls.MessageSource
import dev.rovsti.design.controls.SourceGlyph
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/**
 * The status line of a WhatsApp call, "WhatsApp audio", with the 14 dp WhatsApp glyph in front. The glyph says it once
 * for the whole screen (never a pill on a row); the words are read by a screen reader, the glyph is silent.
 */
@Composable
fun WaAudioLabel(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = RovstiTheme.type.subheadline,
) {
    Row(
        modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        SourceGlyph(MessageSource.WhatsApp, contentDescription = null, tint = color)
        RovstiText(text, style = style, color = color, maxLines = 1)
    }
}
