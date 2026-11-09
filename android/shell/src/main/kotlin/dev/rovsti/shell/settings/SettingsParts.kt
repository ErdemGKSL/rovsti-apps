package dev.rovsti.shell.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme

/** Space between two groups of a settings page. */
@Composable
internal fun GroupGap() {
    Spacer(Modifier.height(SettingsMetrics.S27))
}

/** The sentence under a group that explains it, in the same left inset as the group header. */
@Composable
fun SettingsFooter(text: String) {
    IosText(
        text = text,
        style = IosTheme.typography.footnote,
        color = IosTheme.colors.secondaryLabel,
        modifier =
            Modifier.fillMaxWidth().padding(
                start = SettingsMetrics.S20,
                end = SettingsMetrics.S20,
                top = SettingsMetrics.S8,
            ),
    )
}

@Composable
internal fun tilePainter(icon: ImageVector): Painter = rememberVectorPainter(icon)
