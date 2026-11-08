// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Glass material colours and shine factors, one set per theme. Values are glisse's `--lg-*` custom properties
 * (glisse `src/styles.css:1-89`); the rim and lens maths that use [lensShine] and [rimShine] arrive with E1.5.
 */
@Immutable
data class GlassTokens(
    val foreground: Color,
    val muted: Color,
    val glass: Color,
    val glassActive: Color,
    val edge: Color,
    val drop: Color,
    val selection: Color,
    val lens: Color,
    val lensDrop: Color,
    val segmentTrack: Color,
    val segmentThumb: Color,
    val surface: Color,
    val surfaceEdge: Color,
    val shadow: Color,
    val highlight: Color,
    val separator: Color,
    val destructive: Color,
    val track: Color,
    val rail: Color,
    val on: Color,
    val fill: Color,
    val ring: Color,
    val lensShine: Float,
    val rimShine: Float,
) {
    companion object {
        val Light =
            GlassTokens(
                foreground = Color(0f, 0f, 0f, .88f),
                muted = Color(60, 60, 67).copy(alpha = .6f),
                glass = Color.White.copy(alpha = .6f),
                glassActive = Color(236, 236, 240).copy(alpha = .85f),
                edge = Color.Black.copy(alpha = .08f),
                drop = Color.Black.copy(alpha = .06f),
                selection = Color.Black.copy(alpha = .10f),
                lens = Color.Black.copy(alpha = .03f),
                lensDrop = Color.Black.copy(alpha = .11f),
                segmentTrack = Color(118, 118, 128).copy(alpha = .12f),
                segmentThumb = Color.White,
                surface = Color(250, 250, 252).copy(alpha = .86f),
                surfaceEdge = Color.Black.copy(alpha = .08f),
                shadow = Color(25, 25, 35).copy(alpha = .12f),
                highlight = Color(60, 60, 67).copy(alpha = .09f),
                separator = Color(60, 60, 67).copy(alpha = .15f),
                destructive = Color(0xFFFF383C),
                track = Color(59, 59, 66).copy(alpha = .3f),
                rail = Color.Black.copy(alpha = .1f),
                on = Color(0xFF34C759),
                fill = Color(0xFF0088FF),
                ring = Color.Black.copy(alpha = .4f),
                lensShine = -1.5f,
                rimShine = 0.3f,
            )

        val Dark =
            GlassTokens(
                foreground = Color.White,
                muted = Color(235, 235, 245).copy(alpha = .6f),
                glass = Color(40, 40, 40).copy(alpha = .62f),
                glassActive = Color(70, 70, 70).copy(alpha = .62f),
                edge = Color.White.copy(alpha = .08f),
                drop = Color.Transparent,
                selection = Color.White.copy(alpha = .145f),
                lens = Color.White.copy(alpha = .055f),
                lensDrop = Color.Transparent,
                segmentTrack = Color.Transparent,
                segmentThumb = Color(235, 235, 245).copy(alpha = .3f),
                surface = Color(28, 28, 31).copy(alpha = .86f),
                surfaceEdge = Color.White.copy(alpha = .13f),
                shadow = Color.Black.copy(alpha = .38f),
                highlight = Color(235, 235, 245).copy(alpha = .13f),
                separator = Color(235, 235, 245).copy(alpha = .16f),
                destructive = Color(0xFFFF4245),
                track = Color(235, 235, 245).copy(alpha = .3f),
                rail = Color.White.copy(alpha = .1f),
                on = Color(0xFF30D158),
                fill = Color(0xFF0091FF),
                ring = Color.White.copy(alpha = .5f),
                lensShine = 1f,
                rimShine = 0.13f,
            )
    }
}
