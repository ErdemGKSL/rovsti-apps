package dev.rovsti.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.rovsti.design.motion.GlisseSpring
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.motion.SpringState
import dev.rovsti.design.theme.RovstiShapes
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** A spring to plot. [damping] is the glisse damping coefficient (`2 * ratio * sqrt(stiffness)`). */
internal class Curve(
    val name: String,
    val stiffness: Float,
    val damping: Float,
    val color: Color,
    val dashed: Boolean = false,
) {
    val ratio: Float get() = damping / (2f * sqrt(stiffness))

    /** Step response 0 -> 1 sampled every millisecond for [DurationMs] + 1 points. */
    val samples: FloatArray by lazy {
        val s = SpringState(0f, 1f)
        FloatArray(DurationMs + 1) { i ->
            if (i > 0) s.advance(stiffness, damping, 0.001f)
            s.value
        }
    }

    /** Highest value reached, as a fraction of the travel above 1 (0 for a spring that never overshoots). */
    val overshoot: Float get() = max(0f, samples.max() - 1f)

    companion object {
        const val DurationMs = 600

        fun of(
            name: String,
            spring: GlisseSpring,
            color: Color,
            dashed: Boolean = false,
        ) = Curve(name, spring.stiffness, spring.damping, color, dashed)

        fun of(
            name: String,
            ratio: Float,
            stiffness: Float,
            color: Color,
            dashed: Boolean = false,
        ) = Curve(name, stiffness, 2f * ratio * sqrt(stiffness), color, dashed)
    }
}

private val Orange = Color(0xFFFF9500)
private val Purple = Color(0xFFAF52DE)
private val Teal = Color(0xFF30B0C7)

/** Step responses of the motion tokens, computed by the ported solver, with axes and a legend. */
@Composable
internal fun MotionPage() {
    val c = RovstiTheme.colors
    val glass =
        listOf(
            Curve.of("GlassPressDown", RovstiMotion.GlassPressDown, c.accent),
            Curve.of("GlassPressUp", RovstiMotion.GlassPressUp, c.destructive),
            Curve.of("SelectRest", RovstiMotion.SelectRest, Orange),
            Curve.of("Toggle", RovstiMotion.Toggle, c.success, dashed = true),
        )
    val surfaces =
        listOf(
            Curve.of("SheetSettle", 0.86f, 380f, c.accent),
            Curve.of("MenuGrowX", 0.62f, 560f, c.destructive),
            Curve.of("MenuGrowY", 0.78f, 330f, Purple),
            Curve.of("Morph", 1f, 950f, Teal, dashed = true),
        )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding())) {
        RovstiText("Spring curves", style = RovstiTheme.type.largeTitle, modifier = Modifier.padding(top = Spacing.s))
        RovstiText(
            "Step response from 0 to 1 over 600 ms, stepped by the glisse solver at 1 kHz. Above the dashed " +
                "line is overshoot.",
            style = RovstiTheme.type.subheadline,
            color = c.labelSecondary,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sectionGap),
        )
        SectionLabel("Glass and controls")
        ChartCard(glass)
        SectionLabel("Sheets and menus", Modifier.padding(top = Spacing.sectionGap))
        ChartCard(surfaces)
    }
}

@Composable
private fun ChartCard(curves: List<Curve>) {
    val c = RovstiTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RovstiShapes.grouped)
            .background(c.groupedSurface)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        SpringChart(curves, Modifier.fillMaxWidth().height(220.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            for (curve in curves) LegendRow(curve)
        }
    }
}

@Composable
private fun LegendRow(curve: Curve) {
    val c = RovstiTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Canvas(Modifier.width(28.dp).height(8.dp)) {
            drawLine(
                curve.color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = 3.dp.toPx(),
                pathEffect =
                    if (curve.dashed) {
                        PathEffect.dashPathEffect(
                            floatArrayOf(8.dp.toPx(), 5.dp.toPx()),
                        )
                    } else {
                        null
                    },
            )
        }
        Column(Modifier.weight(1f)) {
            RovstiText(curve.name, style = RovstiTheme.type.body)
            RovstiText(
                "ratio %.2f, stiffness %.0f".format(curve.ratio, curve.stiffness),
                style = RovstiTheme.type.footnote,
                color = c.labelSecondary,
            )
        }
        val peak = curve.overshoot
        RovstiText(
            when {
                peak < 0.0005f -> "no overshoot"
                peak < 0.095f -> "+%.1f%%".format(peak * 100f)
                else -> "+%.0f%%".format(peak * 100f)
            },
            style = RovstiTheme.type.subheadline,
            color = c.labelSecondary,
        )
    }
}

@Composable
private fun SpringChart(
    curves: List<Curve>,
    modifier: Modifier = Modifier,
) {
    val c = RovstiTheme.colors
    val measurer = rememberTextMeasurer()
    val tick = RovstiTheme.type.caption1.copy(color = c.labelSecondary)
    val axisTitle = RovstiTheme.type.caption1.copy(color = c.labelSecondary)
    val yMax = remember(curves) { ceil((curves.maxOf { it.samples.max() } + 0.05f) * 10f) / 10f }
    Canvas(
        modifier.semantics {
            contentDescription =
                "Step response chart. Horizontal axis: time in milliseconds. " +
                "Vertical axis: progress toward the target. " +
                curves.joinToString(". ") { it.name }
        },
    ) {
        val yTicks = listOf(0f, 0.5f, 1f)
        val yLabels = yTicks.map { measurer.measure("%.1f".format(it), tick) } + measurer.measure("target", tick)
        val xTicks = listOf(0, 200, 400, 600)
        val xLabels = xTicks.map { measurer.measure("$it", tick) }
        val xTitle = measurer.measure("time (ms)", axisTitle)
        val yTitle = measurer.measure("progress", axisTitle)
        val gap = 6.dp.toPx()
        val left = max(yLabels.maxOf { it.size.width }, yTitle.size.width) + gap
        val bottom = xLabels.maxOf { it.size.height } + xTitle.size.height + 2 * gap
        val top = yTitle.size.height + gap
        val right = xLabels.last().size.width / 2f
        val w = size.width - left - right
        val h = size.height - top - bottom

        fun px(ms: Float) = left + w * ms / Curve.DurationMs

        fun py(v: Float) = top + h * (1f - v / yMax)

        // Grid and the dashed target line.
        for (t in yTicks) {
            if (t == 1f) continue
            drawLine(c.separator, Offset(left, py(t)), Offset(left + w, py(t)), strokeWidth = 1.dp.toPx())
        }
        drawLine(c.labelTertiary, Offset(left, top + h), Offset(left + w, top + h), strokeWidth = 1.dp.toPx())
        drawLine(c.labelTertiary, Offset(left, top), Offset(left, top + h), strokeWidth = 1.dp.toPx())
        drawLine(
            c.labelSecondary,
            Offset(left, py(1f)),
            Offset(left + w, py(1f)),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
        )

        // Curves.
        for (curve in curves) {
            val path = Path()
            for (i in curve.samples.indices step 4) {
                val x = px(i.toFloat())
                val y = py(curve.samples[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                curve.color,
                style =
                    Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect =
                            if (curve.dashed) {
                                PathEffect.dashPathEffect(
                                    floatArrayOf(10.dp.toPx(), 6.dp.toPx()),
                                )
                            } else {
                                null
                            },
                    ),
            )
        }

        // Labels. Y: right-aligned beside the ticks; "target" sits on the dashed line at 1.
        fun label(
            layout: TextLayoutResult,
            at: Offset,
        ) = drawText(layout, topLeft = at)
        yTicks.forEachIndexed { i, t ->
            if (t == 1f) return@forEachIndexed
            val l = yLabels[i]
            label(l, Offset(left - gap - l.size.width, py(t) - l.size.height / 2f))
        }
        val target = yLabels.last()
        label(target, Offset(left - gap - target.size.width, py(1f) - target.size.height / 2f))
        label(yTitle, Offset(0f, 0f))
        xTicks.forEachIndexed { i, ms ->
            val l = xLabels[i]
            val x = (px(ms.toFloat()) - l.size.width / 2f).coerceIn(left - l.size.width / 2f, size.width - l.size.width)
            label(l, Offset(x, top + h + gap))
        }
        label(xTitle, Offset(left + (w - xTitle.size.width) / 2f, size.height - xTitle.size.height))
    }
}
