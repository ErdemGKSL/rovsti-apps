/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/backdrop/backdrops/CanvasBackdrop.kt
 * Changes for Rovsti: package dev.rovsti.design.backdrop; imports only.
 */
package dev.rovsti.design.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import dev.rovsti.design.backdrop.Backdrop

@Composable
fun rememberCanvasBackdrop(onDraw: DrawScope.() -> Unit): Backdrop =
    remember(onDraw) {
        CanvasBackdrop(onDraw)
    }

@Immutable
private class CanvasBackdrop(
    val onDraw: DrawScope.() -> Unit,
) : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        onDraw()
    }
}
