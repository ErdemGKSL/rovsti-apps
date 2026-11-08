package dev.rovsti.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rovsti.design.backdrop.catalog.components.LiquidButton
import dev.rovsti.design.backdrop.catalog.components.LiquidSlider
import dev.rovsti.design.backdrop.catalog.components.LiquidToggle
import dev.rovsti.design.backdrop.drawBackdrop
import dev.rovsti.design.backdrop.effects.blur
import dev.rovsti.design.backdrop.effects.lens
import dev.rovsti.design.backdrop.effects.vibrancy
import dev.rovsti.design.shapes.ContinuousCapsule
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/**
 * E1.24: the ported Convx / Kyant0 backdrop engine over stripes. Three of its own catalog pieces
 * (button, toggle on and off, slider) and one plain capsule built from `drawBackdrop` with a
 * continuous-corner shape. Every piece samples the same [layerBackdrop] source.
 */
@Composable
internal fun BackdropEnginePage() {
    val backdrop = rememberLayerBackdrop()
    var on by remember { mutableStateOf(true) }
    var off by remember { mutableStateOf(false) }
    var slider by remember { mutableFloatStateOf(40f) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { StripedBackdrop() }
        Column(
            Modifier.fillMaxSize().padding(pagePadding()),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            RovstiText("Backdrop engine", style = RovstiTheme.type.largeTitle)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiquidButton(onClick = {}, backdrop = backdrop, modifier = Modifier.testTag("backdrop-button")) {
                    RovstiText("Liquid button", style = RovstiTheme.type.headline)
                }
                LiquidToggle(
                    selected = { on },
                    onSelect = { on = it },
                    backdrop = backdrop,
                    modifier = Modifier.testTag("backdrop-toggle-on"),
                )
                LiquidToggle(
                    selected = { off },
                    onSelect = { off = it },
                    backdrop = backdrop,
                    modifier = Modifier.testTag("backdrop-toggle-off"),
                )
            }
            LiquidSlider(
                value = { slider },
                onValueChange = { slider = it },
                valueRange = 0f..100f,
                visibilityThreshold = 0.1f,
                backdrop = backdrop,
                modifier = Modifier.testTag("backdrop-slider").fillMaxWidth(),
            )
            Box(
                Modifier
                    .testTag("backdrop-capsule")
                    .width(240.dp)
                    .height(72.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { ContinuousCapsule },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            lens(16.dp.toPx(), 32.dp.toPx())
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                RovstiText("Capsule", style = RovstiTheme.type.headline)
            }
        }
    }
}
