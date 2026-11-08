package dev.rovsti.design.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import dev.rovsti.design.chrome.ChromeMetrics
import kotlinx.coroutines.flow.Flow

/**
 * The one place overlays live (ORIGINAL): every popover, menu, sheet, alert and toast is an [OverlayEntry] presented
 * on an [OverlayController] and drawn by [OverlayHost] as the last child of the window-filling root, so there is one
 * back owner, one z-order (list order) and one scrim policy, and exit animations always play.
 */
@Stable
class OverlayController {
    internal val entries = mutableStateListOf<OverlayEntry>()
    private val pushBackSources = mutableStateMapOf<OverlayEntry, StackSource>()

    /**
     * The floating bars of the screen on show, registered by the bar itself (see `RovstiTabBar`) so an entry that
     * lives above the page can keep clear of them. `null` while there is no bar.
     */
    var chromeMetrics: ChromeMetrics? by mutableStateOf(null)
        internal set

    /** The toast on show or leaving (see `showToast`); a new toast replaces it. */
    internal var toast: ToastEntry? = null

    /**
     * How many modal sheets are holding the page back. The page stays pushed back until the last one has left, so
     * stacked sheets never let it spring forward in between.
     */
    val pushBackCount: Int get() = pushBackSources.size

    /**
     * 0..1 for the page behind: the most presented of the registered sheets. Call it in a layer or draw lambda; it
     * follows a dragged sheet frame by frame and falls back to 0 only when the last sheet is gone.
     */
    fun pushBackProgress(): Float {
        var best = 0f
        for (source in pushBackSources.values) best = maxOf(best, source.progress())
        return best
    }

    internal fun registerPushBack(
        entry: OverlayEntry,
        progress: () -> Float,
        top: () -> Float,
    ) {
        pushBackSources[entry] = StackSource(progress, top)
    }

    /**
     * How many modal sheets are presented (and not leaving) above [entry] (ORIGINAL). A sheet with a depth above 0
     * steps back; read it in composition, it follows the entry list.
     */
    internal fun stackDepth(entry: OverlayEntry): Int {
        val index = entries.indexOf(entry)
        if (index < 0) return 0
        var depth = 0
        for (i in index + 1 until entries.size) {
            val other = entries[i]
            if (other.state.targetState && other in pushBackSources) depth++
        }
        return depth
    }

    /** Top edge in px of the nearest modal sheet below [entry] that is still open, or `NaN` when there is none. */
    internal fun topBelow(entry: OverlayEntry): Float {
        val index = entries.indexOf(entry)
        for (i in index - 1 downTo 0) {
            val other = entries[i]
            if (other.state.targetState) pushBackSources[other]?.let { return it.top() }
        }
        return Float.NaN
    }

    internal fun unregisterPushBack(entry: OverlayEntry) {
        pushBackSources.remove(entry)
    }

    /** Shows [entry] on top of the others and starts its enter transition. */
    fun present(entry: OverlayEntry): OverlayEntry {
        entries += entry
        entry.state.targetState = true
        return entry
    }

    /**
     * Hands a finger that is already down to [entry], so one touch can press a trigger, slide and release on a row
     * (no global pointer state: the flow is the only link). [positions] are window pixels; the flow completes when
     * the finger lifts, and ends with `Offset.Unspecified` when the touch was taken away instead. Positions sent
     * before the entry has been composed are kept and replayed. Ignored by entries that are not a [SlideTarget].
     */
    fun beginSlide(
        entry: OverlayEntry,
        positions: Flow<Offset>,
    ) {
        (entry as? SlideTarget)?.onSlide(positions)
    }

    /** Starts the exit transition of [entry]; it is removed once the transition has finished. */
    fun dismiss(entry: OverlayEntry) {
        entry.state.targetState = false
    }

    /**
     * Back: dismisses the topmost entry that is not already leaving. An entry with `dismissOnBack = false` swallows
     * Back instead. Returns whether an entry took the event.
     */
    fun dismissTop(): Boolean {
        val top = entries.lastOrNull { it.state.targetState && !it.passesBack } ?: return false
        if (top.dismissOnBack) top.onBack(this)
        return true
    }

    /** True while at least one entry is present or leaving. */
    val hasEntries: Boolean get() = entries.isNotEmpty()

    internal fun removeFinished() {
        entries.removeAll { it.isRemovable }
    }
}

/** What a modal sheet tells the controller: how far it is presented (0..1) and where its top edge is (px). */
internal class StackSource(
    val progress: () -> Float,
    val top: () -> Float,
)

/**
 * One overlay. [modal] entries take the page out of the accessibility tree; [title] becomes the entry's pane title
 * (what a screen reader announces when it opens). [state] drives the enter and exit transitions: read it with
 * `rememberTransition(state)` inside [Content].
 */
abstract class OverlayEntry(
    val modal: Boolean,
    val title: String,
    val dismissOnBack: Boolean = true,
) {
    val state = MutableTransitionState(false)

    /**
     * True when the entry runs Back itself (a sheet previews and settles the gesture): the host then leaves the system
     * Back to it while it is topmost.
     */
    open val handlesBack: Boolean get() = false

    /** True for entries that never take Back (a toast): the system Back goes on to the page or the next entry. */
    open val passesBack: Boolean get() = false

    /** Whether the host may drop the entry: its exit has finished. Entries that animate without a transition override it. */
    open val isRemovable: Boolean get() = !state.currentState && !state.targetState

    /** Back was pressed while this is the topmost entry and [dismissOnBack] is set: close by default. */
    open fun onBack(controller: OverlayController) {
        controller.dismiss(this)
    }

    @Composable
    abstract fun Content(host: OverlayScope)
}

/** An entry that can take over a finger that went down elsewhere: see [OverlayController.beginSlide]. */
interface SlideTarget {
    fun onSlide(positions: Flow<Offset>)
}

/**
 * What an entry knows about the window. [safe] is the window minus the system bars, the display cutout and the IME,
 * in pixels of the host.
 */
@Stable
class OverlayScope(
    val windowSize: Size,
    val safe: Rect,
    val density: Density,
    val controller: OverlayController,
)

val LocalOverlay =
    staticCompositionLocalOf<OverlayController> { error("No OverlayHost: wrap the app root in OverlayHost") }

/** The nearest host's controller, or `null` where there is none (previews, tests of a single page). */
val LocalOverlayOrNull = staticCompositionLocalOf<OverlayController?> { null }

/** Draws [content], then every entry of [controller] above it. */
@Composable
fun OverlayHost(
    controller: OverlayController,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val anyModal by remember(controller) {
        derivedStateOf { controller.entries.any { it.modal && it.state.targetState } }
    }
    CompositionLocalProvider(LocalOverlay provides controller, LocalOverlayOrNull provides controller) {
        val density = LocalDensity.current
        val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
        val cornerPx = with(density) { SheetMetrics.PushBackCorner.toPx() }
        Box(
            modifier.fillMaxSize().drawBehind {
                // The black the shrunk page leaves visible around itself.
                if (controller.pushBackProgress() > 0f) drawRect(Color.Black)
            },
        ) {
            // The page behind a modal entry is not reachable by a screen reader (and not by touch: the scrim eats it).
            Box(
                (if (anyModal) Modifier.clearAndSetSemantics { } else Modifier)
                    .fillMaxSize()
                    .sheetPushBack(controller::pushBackProgress, statusBarPx, cornerPx),
            ) { content() }
            for (entry in controller.entries) {
                key(entry) {
                    BoxWithConstraints(
                        Modifier.fillMaxSize().semantics {
                            paneTitle = entry.title
                            isTraversalGroup = true
                        },
                    ) {
                        val density = LocalDensity.current
                        val direction = LocalLayoutDirection.current
                        val insets = WindowInsets.safeDrawing
                        val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                        val left = insets.getLeft(density, direction)
                        val top = insets.getTop(density)
                        val right = insets.getRight(density, direction)
                        val bottom = insets.getBottom(density)
                        val scope =
                            remember(size, left, top, right, bottom, density, controller) {
                                OverlayScope(
                                    windowSize = size,
                                    safe =
                                        Rect(
                                            left.toFloat(),
                                            top.toFloat(),
                                            (size.width - right).coerceAtLeast(left.toFloat()),
                                            (size.height - bottom).coerceAtLeast(top.toFloat()),
                                        ),
                                    density = density,
                                    controller = controller,
                                )
                            }
                        entry.Content(scope)
                    }
                }
            }
        }
        // Registered after the page's own handlers, and re-registered whenever the number of entries changes, so the
        // host is always asked first while an overlay is open.
        key(controller.entries.size) {
            val top = controller.entries.lastOrNull { it.state.targetState && !it.passesBack }
            BackHandler(enabled = top?.handlesBack == false) {
                controller.dismissTop()
            }
        }
        LaunchedEffect(controller) {
            snapshotFlow { controller.entries.map { it.isRemovable } }
                .collect { controller.removeFinished() }
        }
    }
}
