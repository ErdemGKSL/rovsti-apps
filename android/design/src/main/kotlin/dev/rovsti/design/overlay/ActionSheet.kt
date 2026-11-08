package dev.rovsti.design.overlay

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassShadow
import dev.rovsti.design.glass.glass
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.press.rowPress
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One row of an [ActionSheetEntry]. With an [armedLabel] the row is a two-tap action: the first tap only arms it (the
 * label becomes [armedLabel] for [ActionSheetMetrics.ArmMs]), the second tap within that time commits.
 */
@Immutable
data class ActionSheetAction(
    val label: String,
    val destructive: Boolean = false,
    val armedLabel: String? = null,
    val onClick: () -> Unit,
)

/** Dimensions and timings of the action sheet (SKILL overlays 6). */
object ActionSheetMetrics {
    val MaxWidth = 500.dp
    val SideMargin = 8.dp
    val RowHeight = 56.dp
    val GroupGap = 8.dp
    val Radius = 26.dp
    const val InMs = 400
    const val OutMs = 150
    const val ArmMs = 3000L
    val InEasing = CubicBezierEasing(0.2833f, 0.99f, 0.31833f, 0.99f)
}

/**
 * A bottom action sheet (ORIGINAL; SKILL overlays 6): a group of [actions] with an optional [title] and [message], and a
 * separate [cancelLabel] group under it. At most 500 dp wide with 8 dp at the sides, 56 dp rows, and the bottom sits 8 dp
 * above the navigation bar. It slides up in 400 ms on (0.2833, 0.99, 0.31833, 0.99) and leaves in 150 ms with an ease-in.
 * Back, the scrim and the cancel row all close it and run [onCancel]. A tall group scrolls above the cancel row.
 */
class ActionSheetEntry(
    title: String? = null,
    private val message: String? = null,
    private val actions: ImmutableList<ActionSheetAction>,
    private val cancelLabel: String = "Cancel",
    private val onCancel: () -> Unit = {},
) : OverlayEntry(modal = true, title = title ?: "Actions") {
    private val header = title

    override fun onBack(controller: OverlayController) {
        controller.dismiss(this)
        onCancel()
    }

    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val transition = rememberTransition(state, label = "action-sheet")
        val progress =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(100)
                        targetState -> tween(ActionSheetMetrics.InMs, easing = ActionSheetMetrics.InEasing)
                        else -> tween(ActionSheetMetrics.OutMs, easing = EaseIn)
                    }
                },
                label = "action-sheet-p",
            ) { if (it) 1f else 0f }
        val haptics by rememberUpdatedState(LocalRovstiHaptics.current)
        val focus = remember { FocusRequester() }
        var armed by remember { mutableIntStateOf(-1) }
        val cancel = {
            host.controller.dismiss(this)
            onCancel()
        }
        LaunchedEffect(armed) {
            if (armed >= 0) {
                delay(ActionSheetMetrics.ArmMs)
                armed = -1
                haptics.reject()
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        OverlayScrim(progress = { progress.value }, onTap = { cancel() })
        Box(
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val side = ActionSheetMetrics.SideMargin.roundToPx()
                    val width = min(ActionSheetMetrics.MaxWidth.roundToPx(), (host.safe.width - 2 * side).roundToInt())
                    // The navigation bar is outside host.safe already; the sheet floats 8 dp above it.
                    val bottomGap = (host.windowSize.height - host.safe.bottom).roundToInt() + side
                    val maxHeight = (host.safe.bottom - host.safe.top).roundToInt() - 2 * side
                    val p = measurable.measure(Constraints(width, width, 0, maxHeight.coerceAtLeast(0)))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val x = ((constraints.maxWidth - p.width) / 2f).roundToInt()
                        val y = constraints.maxHeight - bottomGap - p.height
                        val travel = (p.height + bottomGap).toFloat()
                        p.placeWithLayer(x, y) {
                            val v = progress.value
                            if (reduceMotion) {
                                alpha = v.coerceIn(0f, 1f)
                            } else {
                                translationY = travel * (1f - v)
                            }
                        }
                    }
                },
        ) {
            // One shadow for both groups, drawn behind them as a single layer: a shadow per group let the Cancel
            // group's shadow fall over the last action row (F3).
            val shadow = rememberPanelGlass(ActionSheetMetrics.Radius.value, null).second.shadow
            var cancelHeightPx by remember { mutableIntStateOf(0) }
            val gapPx = with(LocalDensity.current) { ActionSheetMetrics.GroupGap.roundToPx() }
            val radiusPx = with(LocalDensity.current) { ActionSheetMetrics.Radius.toPx() }
            val groupsShape =
                remember(cancelHeightPx, gapPx, radiusPx) {
                    GenericShape { size, _ ->
                        val split = (size.height - cancelHeightPx - gapPx).coerceAtLeast(0f)
                        val r = CornerRadius(radiusPx, radiusPx)
                        addRoundRect(RoundRect(0f, 0f, size.width, split, r))
                        addRoundRect(RoundRect(0f, split + gapPx, size.width, size.height, r))
                    }
                }
            Column(
                Modifier
                    .fillMaxWidth()
                    .dropShadow(
                        groupsShape,
                        Shadow(
                            radius = shadow.radiusDp.dp,
                            color = shadow.color,
                            offset = DpOffset(0.dp, shadow.yDp.dp),
                        ),
                    ).testTag("action-sheet")
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            cancel()
                            true
                        } else {
                            false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(ActionSheetMetrics.GroupGap),
            ) {
                SheetGroup(Modifier.weight(1f, fill = false).testTag("action-group")) {
                    val scroll = rememberScrollState()
                    Column(Modifier.verticalScroll(scroll)) {
                        if (header != null || message != null) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.m),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (header != null) {
                                    RovstiText(
                                        header,
                                        style = RovstiTheme.type.footnote.copy(fontWeight = FontWeight.SemiBold),
                                        color = RovstiTheme.colors.labelSecondary,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                                if (message != null) {
                                    RovstiText(
                                        message,
                                        style = RovstiTheme.type.footnote,
                                        color = RovstiTheme.colors.labelSecondary,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        actions.forEachIndexed { index, action ->
                            val isArmed = armed == index
                            SheetRow(
                                label = if (isArmed) action.armedLabel ?: action.label else action.label,
                                destructive = action.destructive,
                                bold = false,
                                divider = header != null || message != null || index > 0,
                                armed = isArmed,
                                tag = "sheet-action-$index",
                            ) {
                                if (action.armedLabel != null && !isArmed) {
                                    armed = index
                                    haptics.tick()
                                } else {
                                    if (action.destructive || action.armedLabel != null) haptics.confirm()
                                    host.controller.dismiss(this@ActionSheetEntry)
                                    action.onClick()
                                }
                            }
                        }
                    }
                }
                SheetGroup(Modifier.onSizeChanged { cancelHeightPx = it.height }.testTag("action-cancel-group")) {
                    SheetRow(
                        cancelLabel,
                        destructive = false,
                        bold = true,
                        divider = false,
                        armed = false,
                        tag = "sheet-cancel",
                    ) {
                        cancel()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetGroup(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val shape = remember { RoundedCornerShape(ActionSheetMetrics.Radius) }
    val (source, panel) = rememberPanelGlass(ActionSheetMetrics.Radius.value, null)
    val spec = remember(panel) { panel.copy(shadow = GlassShadow.None) }
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().glass(source, spec))
        Box(Modifier.clip(shape)) { content() }
    }
}

@Composable
private fun SheetRow(
    label: String,
    destructive: Boolean,
    bold: Boolean,
    divider: Boolean,
    armed: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = RovstiTheme.colors
    val source = remember { MutableInteractionSource() }
    val hairline = colors.separator
    val thickness = with(LocalDensity.current) { 0.5.dp.toPx() }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ActionSheetMetrics.RowHeight)
            .drawBehind {
                if (divider) drawRect(hairline, Offset.Zero, Size(size.width, thickness))
            }.rowPress(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                if (armed) {
                    stateDescription = "Tap again to confirm"
                    liveRegion = LiveRegionMode.Polite
                }
            }.padding(horizontal = Spacing.gutter, vertical = Spacing.s)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(
            label,
            style =
                RovstiTheme.type.title3.copy(
                    fontWeight =
                        if (bold ||
                            armed
                        ) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                ),
            color = if (destructive) colors.destructive else colors.accent,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Asks before something that cannot be undone: an action sheet with one red action. With [twoTap] the first tap only
 * turns the row into [armedLabel] for three seconds (a `Reject` haptic when that runs out unanswered) and the second
 * tap commits with a `Confirm` haptic; the row's accessibility state says it is armed.
 */
fun OverlayController.confirmDestructive(
    destructiveLabel: String,
    onConfirm: () -> Unit,
    title: String? = null,
    message: String? = null,
    twoTap: Boolean = false,
    armedLabel: String = "Tap again to confirm",
    cancelLabel: String = "Cancel",
    onCancel: () -> Unit = {},
): ActionSheetEntry =
    present(
        ActionSheetEntry(
            title = title,
            message = message,
            actions =
                persistentListOf(
                    ActionSheetAction(
                        label = destructiveLabel,
                        destructive = true,
                        armedLabel = if (twoTap) armedLabel else null,
                        onClick = onConfirm,
                    ),
                ),
            cancelLabel = cancelLabel,
            onCancel = onCancel,
        ),
    ) as ActionSheetEntry

/** [confirmDestructive] with the two-tap guard. */
fun OverlayController.confirmDestructiveTwoTap(
    destructiveLabel: String,
    armedLabel: String,
    onConfirm: () -> Unit,
    title: String? = null,
    message: String? = null,
): ActionSheetEntry =
    confirmDestructive(
        destructiveLabel = destructiveLabel,
        onConfirm = onConfirm,
        title = title,
        message = message,
        twoTap = true,
        armedLabel = armedLabel,
    )
