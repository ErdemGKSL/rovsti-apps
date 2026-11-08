/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule — Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/core/CubicBezier.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly (com.kyant.capsule -> com.convx.music.ui.component.shapes).
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/shapes/core/CubicBezier.kt
 * Changes for Rovsti: package dev.rovsti.design.shapes; imports only.
 */
package dev.rovsti.design.shapes.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

@Immutable
data class CubicBezier(
    val p0: Point,
    val p1: Point,
    val p2: Point,
    val p3: Point,
) {
    @Stable
    operator fun times(operand: Double): CubicBezier =
        CubicBezier(
            p0 * operand,
            p1 * operand,
            p2 * operand,
            p3 * operand,
        )
}
