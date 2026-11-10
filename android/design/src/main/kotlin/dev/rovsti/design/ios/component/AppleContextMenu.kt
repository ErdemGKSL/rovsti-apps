/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/AppleContextMenu.kt
 * Changes for Rovsti: package and imports; the `Popup(...)` of `AppleContextMenuOverlay` is an [OverlayEntry]
 * ([AppleContextMenuEntry]) presented through the [OverlayController], so [AppleContextMenuState] holds the entry
 * instead of a MutableTransitionState (the entry's own `state` is the transition). The springs, the press timing, the
 * lift of the preview, the stretch of the menu, the rubber band, the dismiss rule and the landing crossfade are Convx's.
 * Rovsti additions: tags (`preview-card`, `menu-card`), the dim scrim is tagged `overlay-scrim`, a shadow under the
 * preview, reduce motion, the panel takes focus, and the entry can be driven from outside by a gesture that is not this
 * file's ([PreviewDragTarget], used by `Modifier.longPressMenu`).
 */
package dev.rovsti.design.ios.component

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rovsti.design.R
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
import dev.rovsti.design.overlay.PreviewDragTarget
import dev.rovsti.design.shapes.ContinuousRoundedRectangle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Press-and-hold menu timing from the Apple Music reference (30 fps): press feedback on
 * frames 0-8, haptic and page dim on 9, item expansion on 10-14, and menu expansion on 12-15.
 * Drag the preview down or back to dismiss; tap it to open, or tap outside to close.
 * Keep the source item hidden until the preview finishes closing to avoid showing both.
 * [preview] is laid out at card width and should start with the source item's cover.
 */
@Composable
fun AppleContextMenu(
    sections: List<IosMenuSection>,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    quickActions: List<IosMenuItem> = emptyList(),
    onClick: (() -> Unit)? = null,
    /** External controller for reorderable items; open on hold and cancel when dragging starts. */
    controller: AppleMenuController? = null,
    content: @Composable () -> Unit,
) {
    val haptics = LocalRovstiHaptics.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val menu = controller?.state ?: remember { AppleContextMenuState() }
    // A plain holder, not state: written on every layout (every scrolled frame), read only
    // when the overlay composes on open -- as state it recomposed every tile per frame.
    val anchor = remember { arrayOf(Rect.Zero) }
    val overlay = LocalOverlayOrNull.current
    val latest = rememberUpdatedState(Triple(sections, quickActions, preview))
    val latestClick = rememberUpdatedState(onClick)
    SideEffect {
        menu.overlay = overlay
        menu.factory = { state ->
            AppleContextMenuEntry(
                menu = state,
                anchor = anchor[0],
                sections = latest.value.first,
                quickActions = latest.value.second,
                preview = latest.value.third,
                onPreviewClick = latestClick.value,
            )
        }
    }

    Box(
        modifier =
            modifier
                .onGloballyPositioned { anchor[0] = it.boundsInWindow() }
                .graphicsLayer {
                    val s = 1f - (1f - PressScale) * menu.press.value
                    scaleX = s
                    scaleY = s
                    alpha = menu.itemAlpha()
                }.drawWithContent {
                    drawContent()
                    val p = menu.press.value
                    if (p > 0f) drawRect(Color.Black.copy(alpha = 0.14f * p))
                }.then(
                    if (controller != null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(onClick) {
                            val dismissDown = with(density) { DismissDown.toPx() }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val sink = scope.launch { menu.press.animateTo(1f, tween(300)) }
                                // null = held past the long-press timeout; true = lifted (a tap);
                                // false = the list took the gesture (scrolled), it moved away, or a
                                // child claimed the tap. Read in the Main pass, i.e. after children:
                                // a tap on a button inside the item (a row's "...") is the button's.
                                val lifted =
                                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val change =
                                                awaitPointerEvent().changes.firstOrNull()
                                                    ?: return@withTimeoutOrNull false
                                            if (!change.pressed) return@withTimeoutOrNull !change.isConsumed
                                            if (change.isConsumed ||
                                                (change.position - down.position).getDistance() >
                                                viewConfiguration.touchSlop
                                            ) {
                                                return@withTimeoutOrNull false
                                            }
                                        }
                                        @Suppress("UNREACHABLE_CODE")
                                        false
                                    }
                                sink.cancel()
                                scope.launch { menu.press.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 600f)) }
                                when (lifted) {
                                    true -> {
                                        onClick?.invoke()
                                    }

                                    false -> {
                                        Unit
                                    }

                                    null -> {
                                        haptics.longPress()
                                        scope.launch { menu.drag.snapTo(Offset.Zero) }
                                        menu.open()
                                        // The finger is still down: it now drags the preview.
                                        val start = down.position
                                        while (true) {
                                            val change =
                                                awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()
                                                    ?: break
                                            // Swallowed before children see it -- the lift included, or a
                                            // clickable inside the item would fire on release.
                                            change.consume()
                                            if (!change.pressed) break
                                            val total = change.position - start
                                            scope.launch { menu.drag.snapTo(rubberBand(total)) }
                                        }
                                        menu.release(scope, dismissDown)
                                    }
                                }
                            }
                        }
                    },
                ),
    ) { content() }
}

/** Drag follows the finger downward/sideways; upward it resists, as on iOS. */
internal fun rubberBand(d: Offset) = Offset(d.x * 0.6f, if (d.y < 0f) d.y * 0.3f else d.y)

/**
 * Whether releasing a dragged preview dismisses it: it went down more than [dismissDown] px, or [drag] is more than 40 %
 * of the way [home] (the vector from the card's resting place to the item).
 */
internal fun shouldDismissPreview(
    drag: Offset,
    home: Offset,
    dismissDown: Float,
): Boolean {
    val homeLen = home.getDistance()
    val backHome = homeLen > 1f && (drag.x * home.x + drag.y * home.y) / homeLen > 0.4f * homeLen
    return drag.y > dismissDown || backHome
}

/** Opens and drops an [AppleContextMenu] whose gesture its caller owns. */
@Stable
class AppleMenuController internal constructor() {
    internal val state = AppleContextMenuState()

    /** Whether the menu is up (or still animating back). */
    val isOpen: Boolean get() = state.visible

    fun open() = state.open()

    /** Gone at once, no landing -- the item is being dragged instead. */
    fun cancel() = state.cancel()
}

@Composable
fun rememberAppleMenuController(): AppleMenuController = remember { AppleMenuController() }

@Stable
internal class AppleContextMenuState {
    /** The presented entry (it keeps its transition); replaced on [cancel] and on every [open]. */
    var entry by mutableStateOf<AppleContextMenuEntry?>(null)
    internal var overlay: OverlayController? = null
    internal var factory: ((AppleContextMenuState) -> AppleContextMenuEntry)? = null

    val press = Animatable(0f)
    val drag = Animatable(Offset.Zero, Offset.VectorConverter)

    /** Card target top-left and the card's "home" top-left (on the item), from layout. */
    val placed = FloatArray(4)

    /** The item sat in the right half: card and menu align to its trailing edge. */
    var rightSide = false

    /** The overlay's lift progress, shared so the item can crossfade with it. */
    var progress: State<Float>? = null

    /**
     * The item is hidden while its preview is up. On the way back the last [LandFade]
     * of the travel crossfades -- the item fades in under the landing preview as the
     * preview fades out -- so the two never swap in one frame (their text differs).
     */
    fun itemAlpha(): Float {
        val e = entry
        if (e == null || !visible) return 1f
        if (e.state.targetState) return 0f
        val p = progress?.value ?: return 0f
        return (1f - p / LandFade).coerceIn(0f, 1f)
    }

    /** The preview's own alpha: full, except through the landing crossfade. */
    fun previewAlpha(p: Float): Float = if (entry?.state?.targetState != false) 1f else (p / LandFade).coerceIn(0f, 1f)

    /** Open, or still animating back: the item stays hidden the whole time. */
    val visible: Boolean get() = entry?.let { it.state.currentState || it.state.targetState } == true

    fun open() {
        if (visible) return
        val make = factory ?: return
        val host = overlay ?: return
        val e = make(this)
        entry = e
        host.present(e)
    }

    fun close() {
        entry?.let { overlay?.dismiss(it) }
    }

    /** Gone at once -- the item is being dragged, not put back. */
    fun cancel() {
        entry?.let { overlay?.removeNow(it) }
        entry = null
        progress = null
    }

    /** From the card's resting place back to where the item sits. */
    fun towardHome() = Offset(placed[2] - placed[0], placed[3] - placed[1])

    /** The finger that opened the menu lifted: dismiss on a long drag down or back toward the item, else spring home. */
    fun release(
        scope: kotlinx.coroutines.CoroutineScope,
        dismissDown: Float,
    ) {
        if (shouldDismissPreview(drag.value, towardHome(), dismissDown)) {
            close()
        } else {
            scope.launch { drag.animateTo(Offset.Zero, spring(dampingRatio = 0.75f, stiffness = 500f)) }
        }
    }
}

/**
 * The overlay entry of [AppleContextMenu] (the `Popup` of the original): dim, preview card lifted from the item and the
 * [IosMenu] stretching open under it. Also what `Modifier.longPressMenu` presents for the preview-card style, with the
 * finger that opened it handed over through [PreviewDragTarget].
 */
internal class AppleContextMenuEntry(
    private val menu: AppleContextMenuState,
    private val anchor: Rect,
    private val sections: List<IosMenuSection>,
    private val quickActions: List<IosMenuItem>,
    private val preview: @Composable () -> Unit,
    private val onPreviewClick: (() -> Unit)?,
    /** The real element, hidden while the preview is up (the facade; [AppleContextMenu] hides its item itself). */
    private val target: ContextMenuTarget? = null,
    private val openHaptic: Boolean = false,
    private val panelTag: String = "menu-card",
    private val debugTag: String = "menu.panel",
) : OverlayEntry(modal = true, title = "", titleRes = R.string.design_preview_and_actions),
    PreviewDragTarget {
    private var scope: kotlinx.coroutines.CoroutineScope? = null
    private var dismissDownPx = 0f

    init {
        menu.entry = this
    }

    override fun drag(offset: Offset) {
        scope?.launch { menu.drag.snapTo(rubberBand(offset)) }
    }

    override fun release() {
        scope?.let { menu.release(it, dismissDownPx) }
    }

    @Composable
    override fun Content(host: OverlayScope) {
        val reduceMotion = LocalReduceMotion.current
        val haptics = LocalRovstiHaptics.current
        val density = host.density
        val coroutines = rememberCoroutineScope()
        val close = { host.controller.dismiss(this) }
        scope = coroutines
        dismissDownPx = with(density) { DismissDown.toPx() }
        menu.overlay = host.controller

        val transition = rememberTransition(state, label = "appleContextMenu")
        // Lift: one spring with a touch of overshoot. Landing: critically damped, so it
        // settles onto the item instead of bouncing and snapping.
        val p =
            transition.animateFloat(
                transitionSpec = {
                    when {
                        reduceMotion -> tween(ReducedFadeMs)
                        targetState -> spring(dampingRatio = 0.8f, stiffness = 420f)
                        else -> spring(dampingRatio = 1f, stiffness = 520f)
                    }
                },
                label = "lift",
            ) { if (it) 1f else 0f }
        menu.progress = p
        // Let the menu grow wider before it grows taller, with a soft spring on both axes.
        val mx = transition.menuAxis(dampingRatio = 0.62f, stiffness = 560f, "menuX")
        val my = transition.menuAxis(dampingRatio = 0.78f, stiffness = 330f, "menuY")

        val colors = IosTheme.colors
        val dark = colors.isDark
        val cardColor = if (dark) Color(0xFF2C2C2E) else Color.White
        val dim = Color.Black.copy(alpha = if (dark) 0.28f else 0.14f)
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val padPx = with(density) { CardPadding.toPx() }
        val menuFadePx = with(density) { 120.dp.toPx() }
        val focus = remember { FocusRequester() }
        val cardShape = remember { ContinuousRoundedRectangle(22.dp) }

        LaunchedEffect(Unit) { if (openHaptic) haptics.longPress() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        // The facade's real element follows the landing crossfade the way AppleContextMenu's own item does.
        LaunchedEffect(target) {
            if (target != null) snapshotFlow { 1f - menu.itemAlpha() }.collect { target.cover.floatValue = it }
        }
        DisposableEffect(Unit) { onDispose { target?.cover?.floatValue = 0f } }

        Box(Modifier.fillMaxSize()) {
            // The page behind: a slight dim only (no blur).
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("overlay-scrim")
                    .drawBehind { drawRect(dim, alpha = p.value.coerceIn(0f, 1f)) }
                    .pointerInput(Unit) { detectTapGestures { close() } },
            )

            CompositionLocalProvider(
                LocalIosTextStyle provides IosTheme.typography.body,
                LocalIosContentColor provides colors.label,
            ) {
                Layout(
                    modifier = Modifier.fillMaxSize(),
                    content = {
                        // Preview card: at p = 0 its *content* sits exactly on the item.
                        Box(
                            Modifier
                                .graphicsLayer {
                                    val v = p.value
                                    val inner = size.width - 2 * padPx
                                    val s0 = (anchor.width / inner).coerceIn(0.05f, 1f)
                                    val s = if (reduceMotion) 1f else s0 + (1f - s0) * v
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = s
                                    scaleY = s
                                    val d = menu.drag.value
                                    val homeX = (anchor.left - padPx * s0 - menu.placed[0]) * (1f - v)
                                    val homeY = (anchor.top - padPx * s0 - menu.placed[1]) * (1f - v)
                                    translationX = (if (reduceMotion) 0f else homeX) + d.x * v
                                    translationY = (if (reduceMotion) 0f else homeY) + d.y * v
                                    alpha = menu.previewAlpha(v)
                                    shadowElevation = CardShadow.toPx() * v.coerceIn(0f, 1f)
                                    shape = cardShape
                                    clip = false
                                }.clip(cardShape)
                                .drawBehind { drawRect(cardColor, alpha = p.value.coerceIn(0f, 1f)) }
                                .pointerInput(onPreviewClick) {
                                    detectTapGestures {
                                        onPreviewClick?.invoke()
                                        close()
                                    }
                                }.padding(CardPadding),
                        ) { Box(Modifier.testTag("preview-card")) { Column { preview() } } }

                        // Menu: stretches open from its corner under the card.
                        Box(
                            Modifier.graphicsLayer {
                                val x = mx.value
                                val y = my.value
                                val dragFade = 1f - (menu.drag.value.getDistance() / menuFadePx).coerceIn(0f, 1f)
                                alpha = (p.value * 3f).coerceIn(0f, 1f) * dragFade
                                if (reduceMotion) return@graphicsLayer
                                scaleX = 0.25f + 0.75f * x
                                scaleY = 0.08f + 0.92f * y
                                transformOrigin = TransformOrigin(if (menu.rightSide) 1f else 0f, 0f)
                                val r =
                                    if (transition.targetState) {
                                        20.dp.toPx() *
                                            (
                                                1f -
                                                    minOf(
                                                        x,
                                                        y,
                                                    )
                                            ).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                renderEffect =
                                    if (r > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        BlurEffect(r, r, TileMode.Decal)
                                    } else {
                                        null
                                    }
                            },
                        ) {
                            IosMenu(
                                sections = sections,
                                quickActions = quickActions,
                                onDismissRequest = close,
                                debugTag = debugTag,
                                modifier = Modifier.testTag(panelTag).focusRequester(focus),
                            )
                        }
                    },
                ) { measurables, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val margin = 16.dp.roundToPx()
                    val gap = 8.dp.roundToPx()
                    val top = statusTop.roundToPx() + 8.dp.roundToPx()
                    val bottom = h - navBottom.roundToPx() - 16.dp.roundToPx()

                    val menuPlaceable = measurables[1].measure(Constraints())
                    // Card ~78% of the width (reference), shrunk if card + menu can't fit.
                    val textBlock = 76.dp.roundToPx()
                    val ideal = minOf((w * 0.78f).roundToInt(), 340.dp.roundToPx())
                    val fit = bottom - top - gap - menuPlaceable.height - textBlock
                    val cardW = ideal.coerceAtMost(fit).coerceAtLeast(140.dp.roundToPx())
                    val card = measurables[0].measure(Constraints.fixedWidth(cardW))

                    // Keeps the item's side: leading edge unless it sat in the right half.
                    val rightSide = anchor.center.x > w / 2f
                    val cardX =
                        (if (rightSide) anchor.right.roundToInt() - card.width else anchor.left.roundToInt())
                            .coerceIn(margin, (w - margin - card.width).coerceAtLeast(margin))
                    val cardY =
                        anchor.top
                            .roundToInt()
                            .coerceAtMost(bottom - card.height - gap - menuPlaceable.height)
                            .coerceAtLeast(top)
                    val menuX =
                        (if (rightSide) cardX + card.width - menuPlaceable.width else cardX)
                            .coerceIn(margin, (w - margin - menuPlaceable.width).coerceAtLeast(margin))
                    val menuY = cardY + card.height + gap

                    menu.placed[0] = cardX.toFloat()
                    menu.placed[1] = cardY.toFloat()
                    menu.placed[2] = anchor.left - padPx
                    menu.placed[3] = anchor.top - padPx
                    menu.rightSide = rightSide
                    layout(w, h) {
                        card.place(cardX, cardY)
                        menuPlaceable.place(menuX, menuY)
                    }
                }
            }
        }
    }
}

@Composable
private fun Transition<Boolean>.menuAxis(
    dampingRatio: Float,
    stiffness: Float,
    label: String,
): State<Float> =
    animateFloat(
        transitionSpec = {
            if (targetState) spring(dampingRatio = dampingRatio, stiffness = stiffness) else tween(140)
        },
        label = label,
    ) { if (it) 1f else 0f }

/** The share of the landing travel over which the preview and the item crossfade. */
private const val LandFade = 0.3f

/** Scale used while the menu item is pressed. */
private const val PressScale = 0.96f
private val CardPadding = 10.dp
private val CardShadow = 18.dp
private val DismissDown = 64.dp
private const val ReducedFadeMs = 100
