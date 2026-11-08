/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule — Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/core/Point.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly (com.kyant.capsule -> com.convx.music.ui.component.shapes).
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/shapes/core/Point.kt
 * Changes for Rovsti: package dev.rovsti.design.shapes; imports only.
 */
package dev.rovsti.design.shapes.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.rovsti.design.shapes.lerp
import kotlin.math.sqrt

@Immutable
data class Point(
    val x: Double,
    val y: Double,
) {
    @Stable
    operator fun unaryMinus(): Point = Point(-x, -y)

    @Stable
    operator fun minus(other: Point): Point = Point(x - other.x, y - other.y)

    @Stable
    operator fun plus(other: Point): Point = Point(x + other.x, y + other.y)

    @Stable
    operator fun times(operand: Double): Point = Point(x * operand, y * operand)

    @Stable
    operator fun div(operand: Double): Point = Point(x / operand, y / operand)

    @Stable
    fun normalized(): Point {
        val length = sqrt(x * x + y * y)
        return if (length != 0.0) this / length else Zero
    }

    companion object {
        @Stable
        val Zero: Point = Point(0.0, 0.0)
    }
}

@Stable
fun lerp(
    start: Point,
    stop: Point,
    fraction: Double,
): Point =
    Point(
        lerp(start.x, stop.x, fraction),
        lerp(start.y, stop.y, fraction),
    )
