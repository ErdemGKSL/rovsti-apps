/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/backdrop/internal/RenderEffect.kt
 * Changes for Rovsti: package dev.rovsti.design.backdrop; imports only.
 */
package dev.rovsti.design.backdrop.internal

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.asComposeRenderEffect
import dev.rovsti.design.backdrop.RuntimeShader
import dev.rovsti.design.backdrop.asAndroidRuntimeShader

@RequiresApi(Build.VERSION_CODES.S)
internal fun RenderEffect?.chain(other: RenderEffect): RenderEffect =
    if (this != null) {
        android.graphics.RenderEffect
            .createChainEffect(
                other.asAndroidRenderEffect(),
                this.asAndroidRenderEffect(),
            ).asComposeRenderEffect()
    } else {
        other
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShaderEffect(
    runtimeShader: RuntimeShader,
    uniformShaderName: String,
): RenderEffect =
    android.graphics.RenderEffect
        .createRuntimeShaderEffect(
            runtimeShader.asAndroidRuntimeShader(),
            uniformShaderName,
        ).asComposeRenderEffect()

@RequiresApi(Build.VERSION_CODES.S)
internal fun ColorFilterEffect(
    renderEffect: RenderEffect?,
    colorFilter: ColorFilter,
): RenderEffect =
    if (renderEffect != null) {
        android.graphics.RenderEffect
            .createColorFilterEffect(
                colorFilter.asAndroidColorFilter(),
                renderEffect.asAndroidRenderEffect(),
            ).asComposeRenderEffect()
    } else {
        android.graphics.RenderEffect
            .createColorFilterEffect(
                colorFilter.asAndroidColorFilter(),
            ).asComposeRenderEffect()
    }
