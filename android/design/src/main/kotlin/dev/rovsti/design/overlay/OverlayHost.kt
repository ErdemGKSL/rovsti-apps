package dev.rovsti.design.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density

/**
 * The one place overlays live (ORIGINAL): every popover, menu, sheet, alert and toast is an [OverlayEntry] presented
 * on an [OverlayController] and drawn by [OverlayHost] as the last child of the window-filling root, so there is one
 * back owner, one z-order (list order) and one scrim policy, and exit animations always play.
 */
@Stable
class OverlayController {
    internal val entries = mutableStateListOf<OverlayEntry>()

    /** Shows [entry] on top of the others and starts its enter transition. */
    fun present(entry: OverlayEntry): OverlayEntry {
        entries += entry
        entry.state.targetState = true
        return entry
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
        val top = entries.lastOrNull { it.state.targetState } ?: return false
        if (top.dismissOnBack) dismiss(top)
        return true
    }

    /** True while at least one entry is present or leaving. */
    val hasEntries: Boolean get() = entries.isNotEmpty()

    internal fun removeFinished() {
        entries.removeAll { !it.state.currentState && !it.state.targetState }
    }
}

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

    @Composable
    abstract fun Content(host: OverlayScope)
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
        Box(modifier.fillMaxSize()) {
            // The page behind a modal entry is not reachable by a screen reader (and not by touch: the scrim eats it).
            Box(if (anyModal) Modifier.clearAndSetSemantics { } else Modifier) { content() }
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
            BackHandler(enabled = controller.entries.any { it.state.targetState }) { controller.dismissTop() }
        }
        LaunchedEffect(controller) {
            snapshotFlow { controller.entries.map { it.state.currentState to it.state.targetState } }
                .collect { controller.removeFinished() }
        }
    }
}
