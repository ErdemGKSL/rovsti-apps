package dev.rovsti.design.controls

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes

/** Where a conversation or contact comes from. Shown once per screen area, never as a pill on every row. */
enum class MessageSource(
    val label: String,
) {
    Sms("SMS"),
    WhatsApp("WhatsApp"),
}

/**
 * A 14 dp glyph for [source], drawn by us: a filled bubble in the accent colour for SMS, a round bubble with a handset in
 * the success colour for WhatsApp. The shapes differ, so colour is not the only cue. Announced by its label unless
 * [contentDescription] says otherwise (pass an empty string inside a row that already says it).
 */
@Composable
fun SourceGlyph(
    source: MessageSource,
    modifier: Modifier = Modifier,
    contentDescription: String? = source.label,
    /** Overrides the source colour: on a coloured call background the green glyph would vanish, so it is drawn white. */
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = RovstiTheme.colors
    when (source) {
        MessageSource.Sms -> {
            RovstiIcon(RovstiIcons.Sms, contentDescription, modifier, tint ?: colors.accent, Sizes.sourceGlyph)
        }

        MessageSource.WhatsApp -> {
            RovstiIcon(
                RovstiIcons.WhatsApp,
                contentDescription,
                modifier,
                tint ?: colors.success,
                Sizes.sourceGlyph,
            )
        }
    }
}
