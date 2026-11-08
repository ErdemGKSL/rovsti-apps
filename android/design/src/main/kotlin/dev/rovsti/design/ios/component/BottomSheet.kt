/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/BottomSheet.kt
 * (itself adapted by Convx from ViMusic, https://github.com/vfsfitvnm/ViMusic, GPL-3.0).
 * Changes for Rovsti: package and imports; everything under ui.player.* is gone (PlayerMorph, recordPlayerLayer, the
 * screen-corner expansion, onCollapseLanded/onCollapseArmed, keepContentComposed, overlayContent) because the player is
 * music-specific; the corner radius is a plain lerp of collapsed and expanded radius; NavigationBarAnimationSpec is
 * [RovstiMotion.bottomSheetMount] (a cited token). The two-anchor drag, projected-snap release, nested-scroll
 * connection, predictive-back preview (35 %, eased) and the spring numbers are as Convx wrote them.
 */
package dev.rovsti.design.ios.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.lerp
import dev.rovsti.design.ios.foundation.nearestProjectedSnap
import dev.rovsti.design.motion.RovstiMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.pow

/** Progress where the collapsed and the expanded layers hand off (Convx: PLAYER_LAYER_HANDOFF_PROGRESS). */
internal const val SHEET_LAYER_HANDOFF_PROGRESS = 0.2f

/** Spring stiffness for opening and closing the sheet (Convx: PlayerSheetStiffness). */
private const val SheetStiffness = 360f

private const val BackdropFreezeSafetyNs = 900_000_000L

/** How far (of the collapse travel) a full predictive-back swipe previews. */
private const val PREDICTIVE_BACK_TRAVEL = 0.35f

/**
 * Two-anchor draggable sheet (collapsed bar that expands to full height), as Convx's mini player and
 * queue pane use it, minus the player morph.
 */
@Composable
fun BottomSheet(
    state: BottomSheetState,
    modifier: Modifier = Modifier,
    background: @Composable (BoxScope.() -> Unit) = { },
    onDismiss: (() -> Unit)? = null,
    collapsedContent: @Composable BoxScope.() -> Unit,
    isExpandable: Boolean = true,
    /** Caps the expanded content to this width, centered. [background] stays full-bleed regardless. */
    contentMaxWidth: Dp = Dp.Unspecified,
    /** Radius the sheet rounds to at full size. */
    expandedCornerRadius: Dp = 0.dp,
    /** Corner radius while collapsed. */
    collapsedCornerRadius: Dp = 16.dp,
    /** False when the host drives back itself. */
    handleBack: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    alpha = (1.4f * (state.progress.coerceAtLeast(0.1f) - 0.1f).pow(0.5f)).coerceIn(0f, 1f)
                }.fillMaxSize(),
    ) {
        // Always full-bleed: see contentMaxWidth.
        Box(modifier = Modifier.fillMaxSize(), content = background)
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer { translationY = (state.expandedBound - state.value).toPx() }
                .pointerInput(state, isExpandable) {
                    if (!isExpandable) return@pointerInput
                    val velocityTracker = VelocityTracker()

                    detectVerticalDragGestures(
                        onDragStart = { state.onDragStart() },
                        onVerticalDrag = { change, dragAmount ->
                            state.dragClockNs[0] = System.nanoTime()
                            velocityTracker.addPointerInputChange(change)
                            state.dispatchRawDelta(dragAmount)
                        },
                        onDragCancel = {
                            state.dragClockNs[0] = 0L
                            velocityTracker.resetTracking()
                            // Settle to the nearest anchor: snapping shut here slammed the sheet closed in one frame
                            // whenever the system took the pointer.
                            state.performFling(0f, onDismiss = null)
                        },
                        onDragEnd = {
                            state.dragClockNs[0] = 0L
                            val velocity = -velocityTracker.calculateVelocity().y
                            velocityTracker.resetTracking()
                            state.performFling(velocity, onDismiss)
                        },
                    )
                }.graphicsLayer {
                    // Interpolate the corner radius during dragging instead of switching abruptly.
                    val cornerRadius =
                        lerp(collapsedCornerRadius.toPx(), expandedCornerRadius.toPx(), state.progress)
                    shape = RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
                    clip = true
                },
    ) {
        if (handleBack && !state.isCollapsed && !state.isDismissed) {
            // Predictive back previews collapse; release commits and cancellation restores the sheet.
            PredictiveBackHandler { events ->
                try {
                    events.collect { event -> state.previewBack(event.progress) }
                    state.collapseSoft()
                } catch (e: CancellationException) {
                    state.expandSoft()
                    throw e
                }
            }
        }

        if (!state.isCollapsed) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Start the expanded content after the collapsed content has faded out.
                            alpha = ((state.progress - SHEET_LAYER_HANDOFF_PROGRESS) / 0.2f).coerceIn(0f, 1f)
                        },
            ) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxHeight()
                            .then(
                                if (contentMaxWidth.isSpecified) {
                                    Modifier.widthIn(max = contentMaxWidth)
                                } else {
                                    Modifier.fillMaxWidth()
                                },
                            ),
                    content = content,
                )
            }
        }

        if (!state.isExpanded && (onDismiss == null || !state.isDismissed)) {
            Box(
                modifier =
                    Modifier
                        .graphicsLayer {
                            // Fully gone exactly where the expanded content starts to arrive.
                            alpha = 1f - (state.progress / SHEET_LAYER_HANDOFF_PROGRESS).coerceIn(0f, 1f)
                        }.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { if (isExpandable) state.expandSoft() },
                        ).fillMaxWidth()
                        .height(state.collapsedBound),
                content = collapsedContent,
            )
        }
    }
}

@Stable
class BottomSheetState(
    draggableState: DraggableState,
    private val coroutineScope: CoroutineScope,
    private val animatable: Animatable<Dp, AnimationVector1D>,
    private val onAnchorChanged: (Int) -> Unit,
    val collapsedBound: Dp,
    val expandedBound: Dp,
    val dismissedBound: Dp,
    private val density: Density,
) : DraggableState by draggableState {
    // A plain array lets draw-phase glass reads avoid snapshot invalidations.
    internal val dragClockNs = longArrayOf(0L)

    /** True from drag start until the sheet settles. */
    var isDragging by mutableStateOf(false)
        private set

    /** Anchor the sheet is moving toward. */
    var targetOpen by mutableStateOf(false)
        private set

    /** Marks a gesture start. */
    fun onDragStart() {
        isDragging = true
    }

    private fun endDragState() {
        isDragging = false
    }

    private fun publishTarget(open: Boolean) {
        targetOpen = open
    }

    /** Pass to a `background` slot's layer backdrop to skip re-recording it while this sheet is being dragged. */
    val backdropFrozen: () -> Boolean = {
        val started = dragClockNs[0]
        started != 0L && System.nanoTime() - started < BackdropFreezeSafetyNs
    }

    val value by animatable.asState()

    val isDismissed by derivedStateOf { value <= dismissedBound }

    val isCollapsed by derivedStateOf { value <= collapsedBound }

    val isExpanded by derivedStateOf { value >= expandedBound }

    val progress by derivedStateOf {
        val totalRange = (expandedBound - collapsedBound).value
        if (totalRange <= 0f) 0f else ((animatable.value - collapsedBound).value / totalRange).coerceIn(0f, 1f)
    }

    fun collapse(
        animationSpec: AnimationSpec<Dp>,
        initialVelocity: Dp = 0.dp,
    ) {
        onAnchorChanged(collapsedAnchor)
        publishTarget(false)
        coroutineScope.launch { animatable.animateTo(collapsedBound, animationSpec, initialVelocity) }
    }

    fun expand(
        animationSpec: AnimationSpec<Dp>,
        initialVelocity: Dp = 0.dp,
    ) {
        onAnchorChanged(expandedAnchor)
        publishTarget(true)
        coroutineScope.launch { animatable.animateTo(expandedBound, animationSpec, initialVelocity) }
    }

    private fun sheetSpring() = spring<Dp>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = SheetStiffness)

    /** Settles toward the nearest anchor using the release velocity. */
    private fun collapse(velocityPxPerSec: Float = 0f) {
        collapse(sheetSpring(), initialVelocity = with(density) { velocityPxPerSec.toDp() })
    }

    private fun expand(velocityPxPerSec: Float = 0f) {
        expand(sheetSpring(), initialVelocity = with(density) { velocityPxPerSec.toDp() })
    }

    fun collapseSoft() {
        // Use the same spring for tap and drag transitions.
        endDragState()
        collapse(sheetSpring())
    }

    fun expandSoft() {
        endDragState()
        expand(sheetSpring())
    }

    fun dismiss() {
        onAnchorChanged(dismissedAnchor)
        endDragState()
        publishTarget(false)
        coroutineScope.launch { animatable.animateTo(dismissedBound) }
    }

    suspend fun dismissAndWait() {
        onAnchorChanged(dismissedAnchor)
        endDragState()
        publishTarget(false)
        animatable.animateTo(dismissedBound)
    }

    /**
     * Predictive-back preview: [fraction] 0..1 of the gesture moves the sheet up to [PREDICTIVE_BACK_TRAVEL] of the
     * way down. Eased so the first pixels are gentle.
     */
    fun previewBack(fraction: Float) {
        val eased = 1f - (1f - fraction.coerceIn(0f, 1f)).pow(2)
        val target = expandedBound - (expandedBound - collapsedBound) * (PREDICTIVE_BACK_TRAVEL * eased)
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { animatable.snapTo(target) }
    }

    fun snapTo(value: Dp) {
        endDragState()
        publishTarget(value >= expandedBound)
        coroutineScope.launch { animatable.snapTo(value) }
    }

    fun performFling(
        velocity: Float,
        onDismiss: (() -> Unit)?,
    ) {
        endDragState()
        if (velocity > 250) {
            expand(velocity)
        } else if (velocity < -250) {
            if (value < collapsedBound && onDismiss != null) {
                dismiss()
                onDismiss.invoke()
            } else {
                collapse(velocity)
            }
        } else {
            // Slow release: snap nearest the *projected* endpoint, not the release point, so a gentle toss toward an
            // anchor still lands there. Work in px since velocity is px/s; value/anchors are Dp.
            val currentPx = with(density) { value.toPx() }
            val snapsPx =
                buildList {
                    if (onDismiss != null) add(with(density) { dismissedBound.toPx() })
                    add(with(density) { collapsedBound.toPx() })
                    add(with(density) { expandedBound.toPx() })
                }
            val pickedPx = nearestProjectedSnap(currentPx, velocity, snapsPx)
            val collapsedPx = with(density) { collapsedBound.toPx() }
            val expandedPx = with(density) { expandedBound.toPx() }
            when {
                onDismiss != null && pickedPx != null && pickedPx < collapsedPx && value < collapsedBound -> {
                    dismiss()
                    onDismiss.invoke()
                }

                pickedPx != null && pickedPx >= expandedPx -> {
                    expand()
                }

                else -> {
                    collapse()
                }
            }
        }
    }

    // Keep this connection stable across recompositions so drag state survives until onPreFling.
    val preUpPostDownNestedScrollConnection =
        object : NestedScrollConnection {
            var isTopReached = false

            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (isExpanded && available.y < 0) {
                    isTopReached = false
                }

                return if (isExpanded && available.y > 0 && isTopReached) {
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (isExpanded && available.y < 0) {
                    isTopReached = true
                }

                return if (isExpanded && available.y > 0) {
                    isTopReached = true
                    dispatchRawDelta(available.y)
                    available
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (isExpanded && available.y < 0 && isTopReached) {
                    coroutineScope.launch { performFling(available.y, null) }
                    available
                } else {
                    Velocity.Zero
                }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                isTopReached = false
                dragClockNs[0] = 0L
                return Velocity.Zero
            }
        }
}

const val expandedAnchor = 2
const val collapsedAnchor = 1
const val dismissedAnchor = 0

@Composable
fun rememberBottomSheetState(
    dismissedBound: Dp,
    expandedBound: Dp,
    collapsedBound: Dp = dismissedBound,
    initialAnchor: Int = dismissedAnchor,
    // Pane-sized sheets start at rest; screen-edge sheets can reveal on mount.
    animateOnMount: Boolean = true,
): BottomSheetState {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var previousAnchor by rememberSaveable { mutableIntStateOf(initialAnchor) }

    return remember(dismissedBound, expandedBound, collapsedBound, coroutineScope) {
        val initialValue =
            when (previousAnchor) {
                expandedAnchor -> expandedBound
                collapsedAnchor -> collapsedBound
                dismissedAnchor -> dismissedBound
                else -> error("Unknown BottomSheet anchor")
            }
        val animatable = Animatable(if (animateOnMount) 0.dp else initialValue, Dp.VectorConverter)

        animatable.updateBounds(dismissedBound.coerceAtMost(expandedBound), null)
        if (animateOnMount) {
            coroutineScope.launch { animatable.animateTo(initialValue, RovstiMotion.bottomSheetMount()) }
        }

        BottomSheetState(
            draggableState =
                DraggableState { delta ->
                    // UNDISPATCHED: the snap lands in this input event, not a dispatch later: the card was trailing
                    // the finger by a frame.
                    coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        // Keep direct drags within the expanded bound. The upper animation bound stays open so settle
                        // springs can overshoot.
                        val next = animatable.value - with(density) { delta.toDp() }
                        animatable.snapTo(next.coerceAtMost(expandedBound))
                    }
                },
            onAnchorChanged = { previousAnchor = it },
            coroutineScope = coroutineScope,
            animatable = animatable,
            collapsedBound = collapsedBound,
            expandedBound = expandedBound,
            dismissedBound = dismissedBound,
            density = density,
        )
    }
}
