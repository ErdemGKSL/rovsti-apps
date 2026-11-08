/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/backdrop/internal/Paint.kt
 * Changes for Rovsti: package dev.rovsti.design.backdrop; imports only.
 */
package dev.rovsti.design.backdrop.internal

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Paint
import dev.rovsti.design.backdrop.RuntimeShader
import dev.rovsti.design.backdrop.asAndroidRuntimeShader

internal fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) {
            BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
}

internal fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.asFrameworkPaint().shader = runtimeShader?.asAndroidRuntimeShader()
}
