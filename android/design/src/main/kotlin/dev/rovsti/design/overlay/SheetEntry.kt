package dev.rovsti.design.overlay

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A bottom sheet with detents (ORIGINAL; SKILL overlays 5, numbers from Convx's behaviour).
 *
 * It rests on one of [detents] (Peek, Medium, Large...), opens on [initialDetent] and is moved by a finger on the
 * grabber, on the sheet or, through [sheetScrollConnection], on a list inside it. The surface is resized live (a
 * `Layout` that reads the offset in the layout phase), so a lazy list always measures at the height it shows.
 * A flick settles on the nearest detent or, past the lowest, closes. A haptic tick marks a new detent.
 *
 * Modal sheets dim the page (0.32), take the page out of the accessibility tree and push it back (0.91, 32 dp,
 * dim 0.22); stacked sheets keep it pushed back until the last one has left. With [modal] = false there is no scrim
 * and no pushback, the page stays interactive around the sheet, and by default it cannot be dismissed: it keeps its
 * lowest detent (the Stops place sheet over the map). Back previews 35 % of the exit and then closes (or, for a
 * sheet that cannot close, drops to its lowest detent); closing clears the focus so the keyboard goes away.
 *
 * [content] fills the space under the grabber; it is given the [SheetState] to dismiss or move the sheet.
 */
class SheetEntry(
    title: String,
    private val detents: ImmutableList<Detent> = persistentListOf(Detent.Medium, Detent.Large),
    private val initialDetent: Detent = detents.first(),
    modal: Boolean = true,
    private val dismissible: Boolean = modal,
    private val onDismissed: () -> Unit = {},
    private val content: @Composable ColumnScope.(SheetState) -> Unit,
) : OverlayEntry(modal = modal, title = title) {
    private var sheetRef by mutableStateOf<SheetState?>(null)

    /** The sheet's position once it has been composed; for tests and for pages that follow its detent. */
    val sheet: SheetState? get() = sheetRef

    override val handlesBack: Boolean get() = true

    override val isRemovable: Boolean
        get() = !state.targetState && (sheetRef?.isGone ?: true)

    @Composable
    override fun Content(host: OverlayScope) {
        val scope = rememberCoroutineScope()
        val haptics by rememberUpdatedState(LocalRovstiHaptics.current)
        val focus = LocalFocusManager.current
        val density = host.density
        val height = host.windowSize.height
        val offsets =
            remember(height, host.safe.top, density.density, detents) {
                detentOffsets(detents, height, host.safe.top, density.density)
            }
        val initialOffset =
            remember(height, host.safe.top, density.density, initialDetent) {
                detentOffsets(listOf(initialDetent), height, host.safe.top, density.density)[0]
            }
        val sheet =
            remember {
                SheetState(
                    scope = scope,
                    dismissible = dismissible,
                    onDetentChanged = { haptics.tick() },
                    onDismissed = {
                        host.controller.dismiss(this)
                        onDismissed()
                    },
                )
            }
        SideEffect {
            sheet.configure(height, offsets)
            if (sheetRef !== sheet) sheetRef = sheet
        }
        LaunchedEffect(sheet) { sheet.present(initialOffset) }
        val open = state.targetState
        LaunchedEffect(open) {
            if (!open) {
                focus.clearFocus()
                sheet.dismiss()
            }
        }
        if (modal) {
            DisposableEffect(sheet) {
                host.controller.registerPushBack(this@SheetEntry, sheet::presentedFraction)
                onDispose { host.controller.unregisterPushBack(this@SheetEntry) }
            }
        }

        val isTop = host.controller.entries.lastOrNull { it.state.targetState } === this
        val canGoBack = isTop && open && (dismissible || sheet.settledOffset < sheet.bottomOffset - 0.5f)
        PredictiveBackHandler(enabled = canGoBack) { events ->
            val start = sheet.offset.value
            val away = if (dismissible) sheet.containerHeight else sheet.bottomOffset
            try {
                events.collect { sheet.preview(backPreviewOffset(start, away, it.progress)) }
                if (dismissible) sheet.dismiss() else sheet.animateToDetent(sheet.detentOffsets.lastIndex)
            } catch (c: CancellationException) {
                sheet.settleBack()
                throw c
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (modal) {
                OverlayScrim(
                    progress = { sheet.presentedFraction() },
                    lightAlpha = SheetMetrics.ScrimAlpha,
                    darkAlpha = SheetMetrics.ScrimAlpha,
                    onTap = if (dismissible) ({ sheet.dismiss() }) else null,
                )
            }
            SheetSurface(sheet, host, modal) { content(sheet) }
        }
    }

    @Composable
    private fun SheetSurface(
        sheet: SheetState,
        host: OverlayScope,
        elevated: Boolean,
        body: @Composable ColumnScope.() -> Unit,
    ) {
        val colors = RovstiTheme.colors
        val shape =
            remember { RoundedCornerShape(topStart = SheetMetrics.CornerRadius, topEnd = SheetMetrics.CornerRadius) }
        val connection = remember(sheet) { sheetScrollConnection(sheet) }
        val drag = rememberDraggableState { sheet.drag(it) }
        val bottomInset = with(host.density) { (host.windowSize.height - host.safe.bottom).coerceAtLeast(0f).toDp() }
        val maxWidthPx = with(host.density) { Sizes.sheetMaxWidth.roundToPx() }
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val w = min(constraints.maxWidth, maxWidthPx)
                    val h = constraints.maxHeight
                    val top =
                        sheet.offset.value
                            .coerceIn(0f, h.toFloat())
                            .roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(w, h - top))
                    layout(constraints.maxWidth, h) { placeable.place((constraints.maxWidth - w) / 2, top) }
                }.then(if (modal) Modifier else Modifier.shadow(16.dp, shape, clip = false))
                .clip(shape)
                .background(colors.groupedSurface)
                .nestedScroll(connection)
                .draggable(drag, Orientation.Vertical, onDragStopped = { sheet.release(it) })
                .testTag("sheet"),
        ) {
            Grabber(sheet)
            Column(Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomInset)) { body() }
        }
    }

    @Composable
    private fun Grabber(sheet: SheetState) {
        val reference =
            with(androidx.compose.ui.platform.LocalDensity.current) { SheetMetrics.GrabberStretchReference.toPx() }
        val color = RovstiTheme.colors.labelTertiary
        Box(
            Modifier
                .fillMaxWidth()
                .height(SheetMetrics.GrabberTop + SheetMetrics.GrabberHeight + SheetMetrics.GrabberBottom)
                .semantics {
                    contentDescription = "Sheet handle"
                    role = Role.Button
                    onClick(label = "Change sheet size") {
                        val offsets = sheet.detentOffsets
                        val here = offsets.indices.minByOrNull { abs(offsets[it] - sheet.settledOffset) } ?: 0
                        sheet.animateToDetent(if (here == 0) offsets.lastIndex else here - 1)
                        true
                    }
                    if (dismissible) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction("Dismiss") {
                                    sheet.dismiss()
                                    true
                                },
                            )
                    }
                }.testTag("sheet-grabber"),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .padding(top = SheetMetrics.GrabberTop)
                    .size(SheetMetrics.GrabberWidth, SheetMetrics.GrabberHeight)
                    .graphicsLayer {
                        val settled = sheet.settledOffset
                        val pull = if (settled.isNaN()) 0f else sheet.offset.value - settled
                        val (sx, sy) = grabberStretch(pull, reference)
                        scaleX = sx
                        scaleY = sy
                    }.background(color, CircleShape),
            )
        }
    }
}
