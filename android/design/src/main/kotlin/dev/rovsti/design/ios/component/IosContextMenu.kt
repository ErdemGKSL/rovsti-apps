/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosContextMenu.kt
 * Changes for Rovsti: package and imports; the `Popup(...)` of `IosContextMenuOverlay` is an [OverlayEntry]
 * ([IosContextMenuEntry]) presented through the [OverlayController] (same z-order, back owner and backdrop as every
 * other overlay), and the composable presents or dismisses it as `anchor` changes. The timings, easings, scrim,
 * 1.05 lift, scale-from-anchor with a blur that clears, and the above/below rule are Convx's. Rovsti additions: the lifted
 * copy is measured at the anchor's exact size and carries a shadow, the menu is kept inside the safe area, reduce
 * motion fades instead of scaling, the panel takes focus and a finger that went down on the trigger can slide over it
 * ([SlideTarget]).
 */
package dev.rovsti.design.ios.component

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.ios.foundation.LocalIosContentColor
import dev.rovsti.design.ios.foundation.LocalIosTextStyle
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.overlay.ContextMenuTarget
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayEntry
import dev.rovsti.design.overlay.OverlayScope
import dev.rovsti.design.overlay.SlideTarget
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The iOS "hold to open" context menu: press and hold anything, the pressed thing lifts out
 * of a dimmed screen and a glass menu materialises beside it.
 *
 * Timing read off the reference comparison clip (AQPov..., 30fps): the panel is up in about
 * four frames, arriving as a small, heavily blurred, half-transparent shape that resolves
 * into a crisp full-size card -- a blur that clears, not just an alpha ramp, which is what
 * makes it read as glass condensing rather than a popup appearing. Dismissal is faster and
 * plain (no blur on the way out), as it is on iOS.
 *
 * The panel itself is [IosMenu]; this adds the gesture, the lift, the scrim and the motion.
 */
@Composable
fun IosContextMenu(
    sections: List<IosMenuSection>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Gap between the anchor and the menu, matching the reference's tight offset. */
    anchorGap: Dp = 8.dp,
    /** Kept off the screen edges by this much, as iOS clamps its menus. */
    screenMargin: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    /** Apple Music's Add / Favourite / Share row across the top of the panel. */
    quickActions: List<IosMenuItem> = emptyList(),
    content: @Composable () -> Unit,
) {
    val haptics = LocalRovstiHaptics.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var open by remember { mutableStateOf(false) }

    Box(
        modifier =
            modifier
                .onGloballyPositioned { anchor = it.boundsInWindow() }
                .pointerInput(enabled, onClick) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onLongPress = {
                            haptics.longPress()
                            open = true
                        },
                        onTap = { onClick?.invoke() },
                    )
                },
    ) {
        content()
    }

    IosContextMenuOverlay(
        sections = sections,
        anchor = anchor.takeIf { open },
        onDismissRequest = { open = false },
        anchorGap = anchorGap,
        screenMargin = screenMargin,
        liftedContent = content,
        quickActions = quickActions,
    )
}

/**
 * The same presentation, for callers that already know where the anchor is and run their
 * own long-press (the floating nav bar's tabs, which cannot hand their gesture over --
 * their cells are the tab bar's own click targets).
 *
 * A non-null [anchor] opens it; null closes it, and the overlay stays composed until the
 * exit animation has actually finished (the [OverlayController] drops the entry then).
 */
@Composable
fun IosContextMenuOverlay(
    sections: List<IosMenuSection>,
    anchor: Rect?,
    onDismissRequest: () -> Unit,
    anchorGap: Dp = 8.dp,
    screenMargin: Dp = 12.dp,
    liftedContent: (@Composable () -> Unit)? = null,
    quickActions: List<IosMenuItem> = emptyList(),
) {
    val controller = LocalOverlayOrNull.current ?: return
    val live = remember { arrayOfNulls<IosContextMenuEntry>(1) }
    val latest = rememberUpdatedState(Triple(sections, quickActions, liftedContent))
    val dismissRequest = rememberUpdatedState(onDismissRequest)
    val rect = rememberUpdatedState(anchor)
    LaunchedEffect(anchor != null) {
        val at = rect.value
        val current = live[0]
        if (at != null) {
            if (current == null || !current.state.targetState) {
                val entry =
                    IosContextMenuEntry(
                        anchor = at,
                        sections = latest.value.first,
                        quickActions = latest.value.second,
                        anchorGap = anchorGap,
                        screenMargin = screenMargin,
                        lifted = latest.value.third,
                        onDismissRequest = { dismissRequest.value() },
                    )
                live[0] = entry
                controller.present(entry)
            }
        } else if (current != null && current.state.targetState) {
            controller.dismiss(current)
        }
    }
    DisposableEffect(controller) {
        onDispose { live[0]?.takeIf { it.state.targetState }?.let(controller::dismiss) }
    }
}

/**
 * The overlay entry of [IosContextMenuOverlay] (the `Popup` of the original): scrim, lifted copy and menu panel. Also
 * the entry behind `ButtonMenu` and `Modifier.longPressMenu`'s lifted style (their facades build one with their own
 * tags). [onDismissRequest] runs whenever the entry closes itself (scrim, row, Back, Escape).
 */
open class IosContextMenuEntry(
    private val anchor: Rect,
    private val sections: List<IosMenuSection>,
    private val quickActions: List<IosMenuItem> = emptyList(),
    private val anchorGap: Dp = 8.dp,
    private val screenMargin: Dp = 12.dp,
    private val lifted: (@Composable () -> Unit)? = null,
    private val onDismissRequest: () -> Unit = {},
    /** The real element hidden while [lifted] stands in for it. */
    private val target: ContextMenuTarget? = null,
    private val openHaptic: Boolean = false,
    private val panelTag: String = "menu-card",
    private val debugTag: String = "menu.panel",
    title: String = "Menu",
) : OverlayEntry(modal = true, title = title),
    SlideTarget {
    private var slide by mutableStateOf<Flow<Offset>?>(null)

    override fun onSlide(positions: Flow<Offset>) {
        slide = positions
    }

    override fun onBack(controller: OverlayController) {
        controller.dismiss(this)
        onDismissRequest()
    }

    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val haptics = LocalRovstiHaptics.current
        val density = host.density
        val dismiss = {
            host.controller.dismiss(this)
            onDismissRequest()
        }
        // The popup has to stay composed through the exit animation, so the transition -- not
        // the caller's anchor -- decides when it is really over (the controller drops the entry then).
        val transition = rememberTransition(state, label = "iosContextMenu")
        val anchorRect = anchor

        // Which side the menu landed on: written during layout, read during the draw of the
        // same frame by the panel's transform origin. A plain array, not snapshot state --
        // an observable write from layout would invalidate the layout that just made it.
        val goesAbove = remember { booleanArrayOf(true) }

        // Read animation state in draw and layer blocks to avoid recomposing the panel per frame.
        // One spring drives the menu size, blur, and fade.
        val progress =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(ReducedFadeMs)
                        targetState -> spring(dampingRatio = 0.78f, stiffness = 700f)
                        else -> tween(150, easing = MenuExitEasing)
                    }
                },
                label = "morph",
            ) { open -> if (open) 1f else 0f }
        val enterBlurPx = with(density) { MenuEnterBlur.toPx() }
        // Menu's placed top-left, written by layout, read by the layer's pivot.
        val menuOrigin = remember { intArrayOf(0, 0) }

        // Rovsti: 0.14 on a light page (Convx's 0.28 greyed a white page), 0.28 on a dark one.
        val scrimTarget = if (IosTheme.colors.isDark) ScrimAlpha else ScrimAlpha / 2f
        val scrimAlpha =
            transition.animateFloat(
                transitionSpec = {
                    if (targetState) tween(180, easing = MenuEnterEasing) else tween(140, easing = MenuExitEasing)
                },
                label = "scrim",
            ) { open -> if (open) ScrimAlpha else 0f }

        // Anchor lift: iOS raises the pressed view a few percent as it leaves the page.
        val liftScale =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(1)
                        targetState -> tween(150, easing = MenuEnterEasing)
                        else -> tween(120, easing = MenuExitEasing)
                    }
                },
                label = "lift",
            ) { open -> if (open) AnchorLift else 1f }

        val focus = remember { FocusRequester() }
        val liftShape = remember { RoundedCornerShape(LiftRadius) }
        LaunchedEffect(Unit) { if (openHaptic) haptics.longPress() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        DisposableEffect(Unit) {
            target?.cover?.floatValue = 1f
            onDispose { target?.cover?.floatValue = 0f }
        }

        val gapPx = with(density) { anchorGap.roundToPx() }
        val marginPx = with(density) { screenMargin.roundToPx() }

        Box(
            Modifier
                .fillMaxSize()
                .testTag("overlay-scrim")
                .drawBehind { drawRect(Color.Black, alpha = scrimAlpha.value) }
                .pointerInput(Unit) {
                    detectTapGestures { dismiss() }
                },
        ) {
            CompositionLocalProvider(
                LocalIosTextStyle provides IosTheme.typography.body,
                LocalIosContentColor provides IosTheme.colors.label,
            ) {
                Layout(
                    modifier = Modifier.fillMaxSize(),
                    content = {
                        // The lifted copy of whatever was pressed, drawn above the scrim
                        // at the place it already occupies, so nothing appears to move.
                        // Without a lifted copy (the button menu) this layer must draw nothing: its shadow
                        // outline would otherwise leave a ghost the size of the anchor under the open menu.
                        Box(
                            Modifier.graphicsLayer {
                                if (lifted == null) return@graphicsLayer
                                scaleX = liftScale.value
                                scaleY = liftScale.value
                                val l = ((liftScale.value - 1f) / (AnchorLift - 1f)).coerceIn(0f, 1f)
                                shadowElevation = LiftShadow.toPx() * l
                                shape = liftShape
                                clip = false
                            },
                        ) { lifted?.invoke() }

                        Box(
                            Modifier
                                .graphicsLayer {
                                    val p = progress.value
                                    // Visible from the first frame as a faint blob (clip f9).
                                    this.alpha = (p / 0.35f).coerceIn(0f, 1f)
                                    if (reduceMotion) return@graphicsLayer
                                    // Starts the size of the pressed thing, per axis.
                                    val startX = (anchorRect.width / size.width).coerceIn(0.08f, 1f)
                                    val startY = (anchorRect.height / size.height).coerceIn(0.08f, 1f)
                                    scaleX = startX + (1f - startX) * p
                                    scaleY = startY + (1f - startY) * p
                                    // Grows out of the anchor itself, wherever it sits
                                    // relative to the panel.
                                    transformOrigin =
                                        TransformOrigin(
                                            pivotFractionX =
                                                ((anchorRect.center.x - menuOrigin[0]) / size.width)
                                                    .coerceIn(
                                                        0f,
                                                        1f,
                                                    ),
                                            pivotFractionY =
                                                ((anchorRect.center.y - menuOrigin[1]) / size.height)
                                                    .coerceIn(
                                                        0f,
                                                        1f,
                                                    ),
                                        )
                                    // Heavy blur that clears as it arrives; none on exit
                                    // (the reference's dismissal is a straight fade).
                                    // Below API 31 the panel arrives without it.
                                    val radius =
                                        if (transition.targetState) {
                                            enterBlurPx *
                                                (1f - p).coerceIn(
                                                    0f,
                                                    1f,
                                                )
                                        } else {
                                            0f
                                        }
                                    renderEffect =
                                        if (radius > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            BlurEffect(radius, radius, TileMode.Decal)
                                        } else {
                                            null
                                        }
                                },
                        ) {
                            IosMenu(
                                sections = sections,
                                quickActions = quickActions,
                                onDismissRequest = dismiss,
                                slide = slide,
                                debugTag = debugTag,
                                modifier = Modifier.testTag(panelTag).focusRequester(focus),
                            )
                        }
                    },
                ) { measurables, constraints ->
                    val loose = constraints.copy(minWidth = 0, minHeight = 0)
                    // Rovsti: the lifted copy is exactly as big as the thing it replaces.
                    val liftedP =
                        measurables[0].measure(
                            Constraints.fixed(
                                anchorRect.width.roundToInt().coerceAtLeast(1),
                                anchorRect.height.roundToInt().coerceAtLeast(1),
                            ),
                        )
                    val menu = measurables[1].measure(loose)

                    val width = constraints.maxWidth
                    val height = constraints.maxHeight
                    val safe = host.safe
                    val minY = max(marginPx, safe.top.roundToInt() + marginPx)
                    val maxY = min(height - marginPx, safe.bottom.roundToInt() - marginPx)
                    val minX = max(marginPx, safe.left.roundToInt() + marginPx)
                    val maxX = min(width - marginPx, safe.right.roundToInt() - marginPx)

                    // Above when there isn't room below -- which is always the case for a
                    // bottom bar, and matches the reference's menu sitting over the list.
                    val spaceBelow = maxY - anchorRect.bottom.roundToInt()
                    val above = spaceBelow < menu.height + gapPx
                    goesAbove[0] = above

                    val menuY =
                        if (above) {
                            (anchorRect.top.roundToInt() - gapPx - menu.height)
                        } else {
                            anchorRect.bottom.roundToInt() + gapPx
                        }.coerceIn(minY, (maxY - menu.height).coerceAtLeast(minY))

                    val menuX =
                        (anchorRect.center.x.roundToInt() - menu.width / 2)
                            .coerceIn(minX, (maxX - menu.width).coerceAtLeast(minX))

                    menuOrigin[0] = menuX
                    menuOrigin[1] = menuY
                    layout(width, height) {
                        liftedP.place(anchorRect.left.roundToInt(), anchorRect.top.roundToInt())
                        menu.place(menuX, menuY)
                    }
                }
            }
        }
    }
}

/** Cubic fits of the reference's in/out curves; the enter one carries a hint of overshoot. */
private val MenuEnterEasing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1.03f)
private val MenuExitEasing = CubicBezierEasing(0.4f, 0f, 0.9f, 0.5f)

/** How blurred the panel is on its first frame (clip f10: content unreadable). */
private val MenuEnterBlur = 20.dp
private const val ScrimAlpha = 0.28f
private const val AnchorLift = 1.05f
private const val ReducedFadeMs = 100
private val LiftRadius = 16.dp
private val LiftShadow = 14.dp
