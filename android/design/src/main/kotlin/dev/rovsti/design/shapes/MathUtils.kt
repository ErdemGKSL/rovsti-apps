/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule — Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/MathUtils.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly (com.kyant.capsule -> com.convx.music.ui.component.shapes).
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/shapes/MathUtils.kt
 * Changes for Rovsti: package dev.rovsti.design.shapes; imports only.
 */
package dev.rovsti.design.shapes

@Suppress("NOTHING_TO_INLINE")
internal inline fun lerp(
    start: Double,
    stop: Double,
    fraction: Double,
): Double = start + (stop - start) * fraction
