package dev.rovsti.design.overlay

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.panelGlass
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.press.rowPress
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.min
import kotlin.math.roundToInt

/** How an alert button looks and what Back does: only a [Cancel] button makes Back close the alert. */
enum class AlertRole {
    Default,

    /** Bold; Back and Escape press it. */
    Cancel,

    /** Red. */
    Destructive,
}

/** One button of an [AlertEntry]. Tapping it closes the alert, then runs [onClick]. */
@Immutable
data class AlertAction(
    val label: String,
    val role: AlertRole = AlertRole.Default,
    val onClick: () -> Unit = {},
)

/** Dimensions and timings of the alert (SKILL overlays 6). */
object AlertMetrics {
    val Width = 270.dp
    val MinHeight = 110.dp
    val Radius = 26.dp
    val ButtonMinHeight = 44.dp
    val Padding = 20.dp
    const val EnterScale = 1.2f
    const val ExitMs = 150
    const val ScrimLight = 0.2f
    const val ScrimDark = 0.4f
}

/**
 * A centred alert (ORIGINAL; SKILL overlays 6): 270 dp wide, at least 110 dp tall, the title and the message centred,
 * the buttons side by side when there are two and each label fits on one line in half the width, otherwise stacked
 * (decided by measuring, so a large font or a long label switches to a column).
 *
 * It enters scaled 1.2 -> 1 and fading in on the `appear` spring and leaves with a 150 ms fade. The scrim (0.4 dark,
 * 0.2 light) swallows touches, but a tap on it does nothing: an alert must be answered. Back closes it only when an
 * action has [AlertRole.Cancel], and then runs that action. Focus moves to the alert when it opens, the page behind is
 * hidden from accessibility by the host, and [title] is the pane title announced.
 */
class AlertEntry(
    private val heading: String,
    private val message: String? = null,
    private val actions: ImmutableList<AlertAction>,
) : OverlayEntry(
        modal = true,
        title = heading,
        dismissOnBack = actions.any { it.role == AlertRole.Cancel },
    ) {
    private val cancel get() = actions.firstOrNull { it.role == AlertRole.Cancel }

    override fun onBack(controller: OverlayController) {
        controller.dismiss(this)
        cancel?.onClick?.invoke()
    }

    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val transition = rememberTransition(state, label = "alert")
        val progress =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(AlertMetrics.ExitMs)
                        targetState -> RovstiMotion.appear(0.001f)
                        else -> tween(AlertMetrics.ExitMs)
                    }
                },
                label = "alert-p",
            ) { if (it) 1f else 0f }
        val scrim =
            transition.animateFloat(
                transitionSpec = { if (targetState) RovstiMotion.scrimIn() else tween(AlertMetrics.ExitMs) },
                label = "alert-scrim",
            ) { if (it) 1f else 0f }
        val focus = remember { FocusRequester() }
        val shape = remember { RoundedCornerShape(AlertMetrics.Radius) }
        val close: (AlertAction) -> Unit = { action ->
            host.controller.dismiss(this)
            action.onClick()
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        // No tap handler: touching the scrim does nothing.
        OverlayScrim(
            progress = { scrim.value },
            lightAlpha = AlertMetrics.ScrimLight,
            darkAlpha = AlertMetrics.ScrimDark,
        )
        Box(Modifier.fillMaxSize().padding(horizontal = Spacing.gutter), contentAlignment = Alignment.Center) {
            val width =
                with(LocalDensity.current) { min(AlertMetrics.Width.roundToPx(), host.safe.width.roundToInt()).toDp() }
            Box(
                Modifier
                    .width(width)
                    .heightIn(min = AlertMetrics.MinHeight)
                    .graphicsLayer {
                        val p = progress.value
                        alpha = p.coerceIn(0f, 1f)
                        val s =
                            if (reduceMotion ||
                                !state.targetState
                            ) {
                                1f
                            } else {
                                AlertMetrics.EnterScale - (AlertMetrics.EnterScale - 1f) * p
                            }
                        scaleX = s
                        scaleY = s
                    }.testTag("alert")
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val c = cancel
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && c != null) {
                            close(c)
                            true
                        } else {
                            false
                        }
                    },
            ) {
                Box(Modifier.matchParentSize().panelGlass(shape, "alert.panel"))
                Column(Modifier.clip(shape)) {
                    Column(
                        Modifier
                            .padding(
                                horizontal = AlertMetrics.Padding,
                            ).padding(top = AlertMetrics.Padding, bottom = AlertMetrics.Padding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RovstiText(heading, style = RovstiTheme.type.headline, textAlign = TextAlign.Center)
                        if (message != null) {
                            RovstiText(
                                message,
                                modifier = Modifier.padding(top = Spacing.xs),
                                style = RovstiTheme.type.footnote,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    AlertButtons(actions, close)
                }
            }
        }
    }
}

/**
 * The buttons: one row when there are exactly two and both labels fit on one line in half the width, else a column.
 * Hairlines are drawn from the decision made during measurement (a plain field read in the draw phase).
 */
@Composable
private fun AlertButtons(
    actions: ImmutableList<AlertAction>,
    onAction: (AlertAction) -> Unit,
) {
    val hairline = RovstiTheme.colors.separator
    val state = remember { ButtonsLayoutState() }
    val thickness = with(LocalDensity.current) { 0.5.dp.toPx() }
    Layout(
        content = {
            for (action in actions) {
                AlertButton(action) { onAction(action) }
            }
        },
        modifier =
            Modifier
                .testTag("alert-buttons")
                .drawBehind {
                    drawRect(hairline, topLeft = Offset.Zero, size = Size(size.width, thickness))
                    val edges = state.edges
                    for (i in 0 until state.count) {
                        if (state.row) {
                            drawRect(hairline, Offset(edges[i], 0f), Size(thickness, size.height))
                        } else {
                            drawRect(hairline, Offset(0f, edges[i]), Size(size.width, thickness))
                        }
                    }
                },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val row = measurables.size == 2 && measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= width / 2 }
        state.row = row
        state.count = if (measurables.isEmpty()) 0 else measurables.size - 1
        val placeables: List<Placeable>
        val height: Int
        if (row) {
            val half = width / 2
            val h = measurables.maxOf { it.minIntrinsicHeight(half) }
            placeables = measurables.map { it.measure(Constraints.fixed(half, h)) }
            height = h
            state.edges = floatArrayOf(half.toFloat())
        } else {
            val hs = measurables.map { it.minIntrinsicHeight(width) }
            placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(width, hs[i])) }
            height = hs.sum()
            val edges = FloatArray(maxOf(0, measurables.size - 1))
            var y = 0
            for (i in edges.indices) {
                y += hs[i]
                edges[i] = y.toFloat()
            }
            state.edges = edges
        }
        layout(width, height) {
            var x = 0
            var y = 0
            placeables.forEach { p ->
                p.place(x, y)
                if (row) x += p.width else y += p.height
            }
        }
    }
}

private class ButtonsLayoutState {
    var row = false
    var count = 0
    var edges = FloatArray(0)
}

@Composable
private fun AlertButton(
    action: AlertAction,
    onClick: () -> Unit,
) {
    val colors = RovstiTheme.colors
    val source = remember { MutableInteractionSource() }
    val color = if (action.role == AlertRole.Destructive) colors.destructive else colors.accent
    Box(
        Modifier
            .heightIn(min = AlertMetrics.ButtonMinHeight)
            .rowPress(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s)
            .testTag("alert-button-${action.label}"),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(
            action.label,
            style =
                RovstiTheme.type.body.copy(
                    fontWeight =
                        if (action.role ==
                            AlertRole.Cancel
                        ) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                ),
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}
