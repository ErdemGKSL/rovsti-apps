package dev.rosty.stops.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.rosty.design.theme.RostyTheme
import dev.rosty.stops.R

/** The distance units of the scale bar (S43). */
enum class MapUnits { Metric, Imperial }

/**
 * The scale bar maths (S28), the twin of `apps/stops/src/map.rs::scale_bar` plus imperial lengths (S43). Pure.
 *
 * The bar shows the longest "nice" length that is at most [MAX_DP] wide at the current scale; when even the shortest
 * one is longer (it cannot be within the camera's zoom limits) the shortest is shown at its true length.
 */
object ScaleBars {
    /** The bar is never wider than this (`MAX_PX`). */
    const val MAX_DP = 110.0

    const val METRES_PER_FOOT = 0.3048
    const val FEET_PER_MILE = 5280

    /** Metres: 10 m to 100 km, the GPUI steps. */
    private val METRIC = intArrayOf(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000)

    /** Feet: 20 ft to 2000 ft, then 1 to 100 miles. */
    private val IMPERIAL_FEET =
        intArrayOf(
            20,
            50,
            100,
            200,
            500,
            1_000,
            2_000,
            1 * FEET_PER_MILE,
            2 * FEET_PER_MILE,
            5 * FEET_PER_MILE,
            10 * FEET_PER_MILE,
            20 * FEET_PER_MILE,
            50 * FEET_PER_MILE,
            100 * FEET_PER_MILE,
        )

    fun stepCount(units: MapUnits): Int = steps(units).size

    /** Which step the bar shows at [metresPerDp]. */
    fun stepIndex(
        metresPerDp: Double,
        units: MapUnits,
    ): Int {
        if (!metresPerDp.isFinite() || metresPerDp <= 0.0) return 0
        val steps = steps(units)
        for (i in steps.indices.reversed()) {
            if (stepMetres(i, units) / metresPerDp <= MAX_DP) return i
        }
        return 0
    }

    /** Ground length of a step in metres. */
    fun stepMetres(
        index: Int,
        units: MapUnits,
    ): Double {
        val raw = steps(units)[index]
        return if (units == MapUnits.Metric) raw.toDouble() else raw * METRES_PER_FOOT
    }

    /** Width of the bar in dp for a step at [metresPerDp]. */
    fun lengthDp(
        index: Int,
        metresPerDp: Double,
        units: MapUnits,
    ): Float =
        if (metresPerDp.isFinite() &&
            metresPerDp > 0.0
        ) {
            (stepMetres(index, units) / metresPerDp).toFloat()
        } else {
            0f
        }

    /** The number on the label of a step: metres or kilometres, feet or miles ([isLargeUnit]). */
    fun labelValue(
        index: Int,
        units: MapUnits,
    ): Int {
        val raw = steps(units)[index]
        return if (isLargeUnit(index, units)) raw / largeUnit(units) else raw
    }

    /** The label is in kilometres or miles rather than metres or feet. */
    fun isLargeUnit(
        index: Int,
        units: MapUnits,
    ): Boolean = steps(units)[index] >= largeUnit(units)

    private fun largeUnit(units: MapUnits): Int = if (units == MapUnits.Metric) 1_000 else FEET_PER_MILE

    private fun steps(units: MapUnits): IntArray = if (units == MapUnits.Metric) METRIC else IMPERIAL_FEET
}

/**
 * The scale bar (S28): the label over a thin rounded bar, like the GPUI map. It follows the camera in the draw phase
 * only (the label layouts are measured once per step and cached), so zooming recomposes nothing.
 *
 * Put it over the map, above the sheet and beside the attribution; it is as wide as the longest bar.
 */
@Composable
fun MapScaleBar(
    camera: MapCamera,
    modifier: Modifier = Modifier,
    units: MapUnits = MapUnits.Metric,
) {
    val resources = LocalResources.current
    val labels =
        remember(units, resources) {
            Array(ScaleBars.stepCount(units)) { i ->
                val id =
                    when {
                        units == MapUnits.Metric && ScaleBars.isLargeUnit(i, units) -> R.string.map_scale_kilometres
                        units == MapUnits.Metric -> R.string.map_scale_metres
                        ScaleBars.isLargeUnit(i, units) -> R.string.map_scale_miles
                        else -> R.string.map_scale_feet
                    }
                resources.getString(id, ScaleBars.labelValue(i, units))
            }
        }
    val colors = RostyTheme.colors
    val ink = if (colors.isDark) Color(0xFFEBEBF5) else Color(0xFF3C3C43)
    val halo = if (colors.isDark) Color(0xCC000000) else Color(0xE6FFFFFF)
    val style = RostyTheme.type.caption1.copy(color = ink, shadow = Shadow(halo, Offset.Zero, HALO_BLUR))
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val layouts = remember(labels, style, measurer) { arrayOfNulls<TextLayoutResult>(labels.size) }
    Box(
        modifier
            .size(width = (ScaleBars.MAX_DP + 2 * EDGE_DP).dp, height = BOX_HEIGHT_DP.dp)
            // The numbers change with every zoom step; a screen reader gets nothing useful from them.
            .clearAndSetSemantics {}
            .drawBehind {
                camera.observe()
                val metresPerDp = camera.metresPerDp
                val index = ScaleBars.stepIndex(metresPerDp, units)
                val layout =
                    layouts[index] ?: measurer.measure(labels[index], style, maxLines = 1).also { layouts[index] = it }
                val edge = EDGE_DP.dp.toPx()
                val barHeight = BAR_HEIGHT_DP.dp.toPx()
                val barWidth = ScaleBars.lengthDp(index, metresPerDp, units).dp.toPx()
                val barTop = size.height - barHeight - edge
                drawText(layout, topLeft = Offset(edge, barTop - GAP_DP.dp.toPx() - layout.size.height))
                // A light rim under the bar keeps it readable on dark water and on pale land alike.
                val rim = RIM_DP.dp.toPx()
                drawRoundRect(
                    color = halo,
                    topLeft = Offset(edge - rim, barTop - rim),
                    size = Size(barWidth + 2 * rim, barHeight + 2 * rim),
                    cornerRadius = CornerRadius(barHeight / 2 + rim),
                )
                drawRoundRect(
                    color = ink,
                    topLeft = Offset(edge, barTop),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barHeight / 2),
                )
            },
    )
}

private const val BAR_HEIGHT_DP = 2f
private const val GAP_DP = 2f
private const val EDGE_DP = 2f
private const val RIM_DP = 1f
private const val BOX_HEIGHT_DP = 26f
private const val HALO_BLUR = 6f
