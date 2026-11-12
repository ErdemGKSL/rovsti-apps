// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code (not ported); the chrome reuses the FrostedTopBar, TopFadeBlur
// and IosToolbarGroup components (their own headers carry the upstream attribution).
package dev.rovsti.design.media

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.rovsti.design.R
import dev.rovsti.design.chrome.FrostedTopBar
import dev.rovsti.design.chrome.ToolbarButton
import dev.rovsti.design.chrome.ToolbarCapsule
import dev.rovsti.design.chrome.rememberGlassScreenChrome
import dev.rovsti.design.glass.LocalAppBackdrop
import dev.rovsti.design.glass.TopFadeBlur
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.ButtonMenuEntry
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayEntry
import dev.rovsti.design.overlay.OverlayScope
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.transition.Motion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * One button of the viewer's bottom toolbar; [onClick] gets the index of the photo on show. With a [menu] the button
 * opens that menu above itself (a popover that grows out of the button) instead of calling [onClick].
 */
@Immutable
class ViewerAction(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val menu: ImmutableList<MenuItem>? = null,
    val onClick: (page: Int) -> Unit = {},
)

/**
 * A photo whose small [thumb] is already in memory (the list or card avatar) and whose [full] picture still has to
 * load: the viewer draws the thumbnail at once and fades the full photo in over it, so the flight out of the avatar
 * never shows an empty frame.
 */
@Immutable
data class ViewerPhoto(
    val full: Any?,
    val thumb: Any?,
    val source: PhotoSource = PhotoSource.Contact,
    /** The thumbnail it flies out of is a circle (an avatar): the flight turns the circle into the photo's rectangle. */
    val circular: Boolean = false,
)

/**
 * Where a photo on show comes from (E5.13). A [Contact] photo is the contact's own and can be set, replaced or removed
 * from the toolbar; a [WhatsApp] photo is the read-only fallback of a contact without one (X.1): it can be saved and
 * shared but never edited, so the viewer's caller leaves the Edit button out when [editable] is false.
 */
enum class PhotoSource(
    val editable: Boolean,
) {
    Contact(editable = true),
    WhatsApp(editable = false),
}

/**
 * What the viewer publishes for tests and accessibility services: the zoom scale, the dim of the background (0..1) and
 * whether the chrome is shown. Read them with `SemanticsMatcher.expectValue`.
 */
object PhotoViewerSemantics {
    val Zoom = SemanticsPropertyKey<Float>("PhotoViewerZoom")
    val Dim = SemanticsPropertyKey<Float>("PhotoViewerDim")
    val ChromeShown = SemanticsPropertyKey<Boolean>("PhotoViewerChromeShown")
    val Page = SemanticsPropertyKey<Int>("PhotoViewerPage")
    val Settled = SemanticsPropertyKey<Boolean>("PhotoViewerSettled")
}

private var SemanticsPropertyReceiver.viewerZoom by PhotoViewerSemantics.Zoom
private var SemanticsPropertyReceiver.viewerDim by PhotoViewerSemantics.Dim
private var SemanticsPropertyReceiver.viewerChrome by PhotoViewerSemantics.ChromeShown
private var SemanticsPropertyReceiver.viewerPage by PhotoViewerSemantics.Page
private var SemanticsPropertyReceiver.viewerSettled by PhotoViewerSemantics.Settled

/** Test tags of the viewer. */
object PhotoViewerTags {
    const val Root = "photo-viewer"
    const val Toolbar = "photo-viewer-toolbar"

    /** The photo of the current page, at the rectangle it flies through (before the zoom of the finger). */
    const val Photo = "photo-viewer-photo"

    fun action(id: String) = "viewer-action-$id"
}

/** The gap between two photos of the strip. */
private val PageGap = 20.dp
private const val DismissShrink = 0.25f
private const val FadeScaleFrom = 0.92f
private const val ChromeFadeMs = 200

/** The shared-bounds spring of the flight out of and back into the avatar (motion.md): damping 0.85, stiffness 380. */
private const val SharedBoundsDamping = 0.85f
private const val SharedBoundsStiffness = 380f

/**
 * Opens the viewer on [photos] (Coil models) at [initialPage]. [anchorFor] gives the window rectangle in px of the
 * thumbnail of a photo (or null): the photo then flies out of it and back into it on the 500 ms shared-artwork spec.
 */
fun OverlayController.showPhotoViewer(
    photos: ImmutableList<Any?>,
    initialPage: Int,
    actions: ImmutableList<ViewerAction>,
    anchorFor: (page: Int) -> Rect? = { null },
    onPageChanged: (page: Int) -> Unit = {},
): OverlayEntry = present(PhotoViewerEntry(photos, initialPage, actions, anchorFor, onPageChanged))

/**
 * The full-screen photo viewer, an [OverlayEntry] (modal, pane title "Photo") with a pager of its own: pager libraries
 * animate across pages, while this strip is one offset that a finger drags and a release settles with the fling
 * projection. The background is black at `appear * (1 - dismissProgress)`, so it clears as the photo is dragged away.
 *
 * Shared bounds: an overlay lives outside the `NavDisplay`, so the `SharedTransitionScope` cannot carry the flight.
 * The viewer runs the shared-bounds spring (damping 0.85, stiffness 380) itself, between the thumbnail rectangle the caller
 * passes ([anchorFor], which a grid reads from its thumbnail with `onGloballyPositioned`) and the letterboxed photo.
 */
class PhotoViewerEntry(
    private val photos: ImmutableList<Any?>,
    private val initialPage: Int,
    private val actions: ImmutableList<ViewerAction>,
    private val anchorFor: (page: Int) -> Rect?,
    private val onPageChanged: (page: Int) -> Unit,
) : OverlayEntry(modal = true, title = "", titleRes = R.string.design_photo) {
    @Composable
    override fun Content(host: OverlayScope) {
        val scope = rememberCoroutineScope()
        val reduce = LocalReduceMotion.current
        val density = host.density.density
        val viewer =
            remember {
                ViewerState(
                    count = photos.size,
                    initial = initialPage.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
                    scope = scope,
                    gapPx = PageGap.value * density,
                    viewport = host.windowSize,
                    onClose = { host.controller.dismiss(this) },
                    onPage = onPageChanged,
                    hasAnchor = { anchorFor(it) != null },
                )
            }
        SideEffect { viewer.viewport = host.windowSize }

        val transition = rememberTransition(state, label = "photo-viewer")
        val appear by transition.animateFloat(
            transitionSpec = {
                if (reduce) {
                    tween(
                        150,
                    )
                } else {
                    spring(dampingRatio = SharedBoundsDamping, stiffness = SharedBoundsStiffness)
                }
            },
            label = "photo-viewer-appear",
        ) { if (it) 1f else 0f }

        val chromeAlpha by animateFloatAsState(
            targetValue = if (viewer.chromeOn) 1f else 0f,
            animationSpec = tween(ChromeFadeMs),
            label = "photo-viewer-chrome",
        )
        val chromeLive by remember { derivedStateOf { viewer.chromeOn || chromeAlpha > 0.01f } }

        // The photo is on show against black, in light and dark: the chrome is always the dark one.
        RovstiTheme(darkTheme = true) {
            val chrome = rememberGlassScreenChrome(Color.Black)
            val photoPosition = stringResource(R.string.design_photo_position, viewer.page + 1, viewer.count)
            val pageOf = stringResource(R.string.design_page_of, viewer.page + 1, viewer.count)
            val nextPhoto = stringResource(R.string.design_photo_next)
            val previousPhoto = stringResource(R.string.design_photo_previous)
            val zoomIn = stringResource(R.string.design_zoom_in)
            val zoomOut = stringResource(R.string.design_zoom_out)
            val closeLabel = stringResource(R.string.design_close)
            val dim: () -> Float = { appear * (1f - viewer.dimProgress) }
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(PhotoViewerTags.Root)
                    .drawBehind { drawRect(Color.Black, alpha = dim().coerceIn(0f, 1f)) }
                    .semantics {
                        contentDescription = photoPosition
                        stateDescription = pageOf
                        viewerZoom = viewer.zoom.scale
                        viewerDim = dim()
                        viewerChrome = chromeLive
                        viewerPage = viewer.page
                        viewerSettled = viewer.isSettled
                        customActions =
                            listOf(
                                CustomAccessibilityAction(nextPhoto) { viewer.goTo(viewer.page + 1) },
                                CustomAccessibilityAction(previousPhoto) { viewer.goTo(viewer.page - 1) },
                                CustomAccessibilityAction(zoomIn) { viewer.zoomStep(in_ = true) },
                                CustomAccessibilityAction(zoomOut) { viewer.zoomStep(in_ = false) },
                                CustomAccessibilityAction(closeLabel) {
                                    viewer.onClose()
                                    true
                                },
                            )
                    },
            ) {
                Box(Modifier.fillMaxSize().then(chrome.screenModifier())) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(chrome.contentModifier())
                            .viewerGestures(viewer)
                            .graphicsLayer {
                                val p = viewer.dismissProgress
                                var s = 1f - DismissShrink * p
                                var a = 1f
                                if (anchorFor(viewer.page) == null) {
                                    s *= FadeScaleFrom + (1f - FadeScaleFrom) * appear
                                    a = appear
                                }
                                translationX = viewer.dismissX
                                translationY = viewer.dismissY
                                scaleX = s
                                scaleY = s
                                alpha = a
                            },
                    ) {
                        val first = (viewer.page - 1).coerceAtLeast(0)
                        val last = (viewer.page + 1).coerceAtMost(viewer.count - 1)
                        for (i in first..last) {
                            ViewerPage(viewer, i, photos[i], anchorFor, appear = { appear })
                        }
                    }
                }
                CompositionLocalProvider(
                    LocalAppBackdrop provides chrome.backdrop,
                    LocalIosBackdrop provides chrome.backdrop,
                ) {
                    if (chromeLive) {
                        Box(
                            Modifier.fillMaxSize().graphicsLayer {
                                alpha = chromeAlpha * (1f - viewer.dimProgress) * appear.coerceIn(0f, 1f)
                            },
                        ) {
                            TopFadeBlur(
                                hazeState = chrome.hazeState,
                                pageColor = Color.Black,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                            FrostedTopBar(
                                onBack = viewer.onClose,
                                backLabel = closeLabel,
                                title = pageOf,
                                titleAlpha = 1f,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                            if (actions.isNotEmpty()) {
                                ToolbarCapsule(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .navigationBarsPadding()
                                        .padding(bottom = 16.dp)
                                        .testTag(PhotoViewerTags.Toolbar),
                                ) {
                                    for (action in actions) {
                                        ViewerActionButton(action, host.controller) { viewer.page }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A toolbar button; the one with a menu opens it above the button, anchored to the button's window rectangle. */
@Composable
private fun ViewerActionButton(
    action: ViewerAction,
    controller: OverlayController,
    page: () -> Int,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    ToolbarButton(
        icon = action.icon,
        label = action.label,
        onClick = {
            val menu = action.menu
            if (menu == null) action.onClick(page()) else controller.present(ButtonMenuEntry(bounds, menu))
        },
        modifier =
            Modifier
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .testTag(PhotoViewerTags.action(action.id)),
    )
}

/** One photo of the strip: its box is the letterboxed photo, flying out of the thumbnail while the viewer opens. */
@Composable
private fun ViewerPage(
    viewer: ViewerState,
    index: Int,
    model: Any?,
    anchorFor: (Int) -> Rect?,
    appear: () -> Float,
) {
    val context = LocalContext.current
    val loader = currentRovstiImageLoader()
    val vw =
        viewer.viewport.width
            .roundToInt()
            .coerceAtLeast(1)
    val vh =
        viewer.viewport.height
            .roundToInt()
            .coerceAtLeast(1)
    val full = (model as? ViewerPhoto)?.full ?: model
    val thumb = (model as? ViewerPhoto)?.thumb
    val circular = (model as? ViewerPhoto)?.circular == true
    val request =
        remember(full, vw, vh, context) {
            ImageRequest
                .Builder(context)
                .data(full)
                .size(vw, vh)
                .crossfade(thumb != null)
                .build()
        }
    val thumbRequest =
        remember(thumb, vw, vh, context) {
            thumb?.let {
                ImageRequest
                    .Builder(context)
                    .data(it)
                    .size(vw, vh)
                    .crossfade(false)
                    .build()
            }
        }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = (index - viewer.page) * viewer.stride + viewer.stripX },
    ) {
        Box(
            modifier =
                Modifier
                    .layout { measurable, constraints ->
                        val fitted = viewer.fitted(index)
                        val centred =
                            Rect(
                                (constraints.maxWidth - fitted.width) / 2f,
                                (constraints.maxHeight - fitted.height) / 2f,
                                (constraints.maxWidth + fitted.width) / 2f,
                                (constraints.maxHeight + fitted.height) / 2f,
                            )
                        val t = appear().coerceIn(0f, 1f)
                        val anchor = if (index == viewer.page && t < 1f) anchorFor(index) else null
                        val rect =
                            if (anchor == null) {
                                centred
                            } else {
                                Rect(
                                    lerp(anchor.left, centred.left, t),
                                    lerp(anchor.top, centred.top, t),
                                    lerp(anchor.right, centred.right, t),
                                    lerp(anchor.bottom, centred.bottom, t),
                                )
                            }
                        val w = rect.width.roundToInt().coerceAtLeast(0)
                        val h = rect.height.roundToInt().coerceAtLeast(0)
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                        }
                    }.testTag(PhotoViewerTags.Photo)
                    .graphicsLayer {
                        // An avatar is a circle: the flight starts as that circle and opens into the photo's rectangle.
                        val t = appear().coerceIn(0f, 1f)
                        if (circular && index == viewer.page && t < 1f && anchorFor(index) != null) {
                            val radius = minOf(size.width, size.height) / 2f * (1f - t)
                            clip = true
                            shape = CircleToRect(radius)
                        }
                        if (index == viewer.page) {
                            val z = viewer.zoom
                            scaleX = z.scale
                            scaleY = z.scale
                            translationX = z.offset.x
                            translationY = z.offset.y
                        }
                    },
        ) {
            if (thumbRequest != null) {
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = null,
                    imageLoader = loader,
                    contentScale = ContentScale.Crop,
                    onSuccess = { success ->
                        // The thumbnail sets the box until the full picture says its own size.
                        val size = success.painter.intrinsicSize
                        if (size.isSpecified && size.width > 0f && size.height > 0f && index !in viewer.natural) {
                            viewer.natural[index] = size
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                imageLoader = loader,
                contentScale = ContentScale.Crop,
                onSuccess = { success ->
                    val size = success.painter.intrinsicSize
                    if (size.isSpecified && size.width > 0f && size.height > 0f) viewer.natural[index] = size
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun lerp(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

/**
 * Everything the gestures change, in one place (ORIGINAL). Plain state values written every frame while a finger is
 * down; [running] animates them after the release and is cancelled by the next touch, so every motion can be
 * interrupted by a finger and picks up from where it is.
 */
@Stable
internal class ViewerState(
    val count: Int,
    initial: Int,
    private val scope: CoroutineScope,
    private val gapPx: Float,
    viewport: Size,
    val onClose: () -> Unit,
    private val onPage: (Int) -> Unit,
    /** The photo on show has a thumbnail to fly back into: a dismissing drag then carries on to it. */
    private val hasAnchor: (page: Int) -> Boolean = { false },
) {
    var page by mutableIntStateOf(initial)
        private set
    var viewport by mutableStateOf(viewport)
    var zoom by mutableStateOf(ZoomState.Fit)
        private set
    var stripX by mutableFloatStateOf(0f)
        private set
    var dismissX by mutableFloatStateOf(0f)
        private set
    var dismissY by mutableFloatStateOf(0f)
        private set
    var chromeOn by mutableStateOf(true)
        private set
    val natural = mutableStateMapOf<Int, Size>()

    private var rawDrag = 0f
    private var running: Job? = null

    val stride: Float get() = viewport.width + gapPx
    val dismissProgress: Float get() = ZoomMath.dismissProgress(dismissY, viewport.height)

    /** True from the release that closes the viewer: the dim then follows the exit flight alone. */
    private var closing by mutableStateOf(false)
    private var releasedProgress = 0f

    /** How far the background has cleared: the drag while it lasts, the value at the release once the viewer closes. */
    val dimProgress: Float get() = if (closing) releasedProgress else dismissProgress
    val isSettled: Boolean get() = running?.isActive != true

    fun fitted(index: Int): Size = ZoomMath.fit(natural[index] ?: viewport, viewport)

    private fun center() = Offset(viewport.width / 2f, viewport.height / 2f)

    fun touchDown() {
        running?.cancel()
        running = null
        rawDrag = stripX
    }

    fun toggleChrome() {
        chromeOn = !chromeOn
    }

    // ---- zoom -------------------------------------------------------------------------------------------------

    fun pinch(
        zoomChange: Float,
        centroid: Offset,
        panChange: Offset,
    ) {
        zoom = ZoomMath.pinch(zoom, zoomChange, centroid - center(), panChange, fitted(page), viewport)
    }

    fun panBy(delta: Offset) {
        var dx = delta.x
        if (rawDrag != 0f) {
            // A strip that is off its page unwinds first, then the photo pans.
            val next = rawDrag + dx
            if (sign(next) != sign(rawDrag) || next == 0f) {
                dx = next
                rawDrag = 0f
            } else {
                rawDrag = next
                dx = 0f
            }
            stripX = ZoomMath.pageDrag(rawDrag, stride, page, count)
        }
        val result = ZoomMath.pan(zoom, Offset(dx, delta.y), fitted(page), viewport)
        zoom = result.state
        if (result.overflow.x != 0f) {
            rawDrag += result.overflow.x
            stripX = ZoomMath.pageDrag(rawDrag, stride, page, count)
        }
    }

    fun settleZoom() {
        val target = ZoomMath.settled(zoom, fitted(page), viewport)
        if (target != zoom) animateZoom(target)
    }

    fun doubleTap(position: Offset) {
        animateZoom(ZoomMath.doubleTap(zoom, position - center(), fitted(page), viewport))
    }

    fun zoomStep(in_: Boolean): Boolean {
        animateZoom(if (in_) ZoomMath.doubleTap(ZoomState.Fit, Offset.Zero, fitted(page), viewport) else ZoomState.Fit)
        return true
    }

    private fun animateZoom(target: ZoomState) {
        val from = zoom
        running?.cancel()
        running =
            scope.launch {
                animate(0f, 1f, animationSpec = RovstiMotion.appear(0.0005f)) { t, _ ->
                    zoom =
                        ZoomState(
                            lerp(from.scale, target.scale, t),
                            Offset(lerp(from.offset.x, target.offset.x, t), lerp(from.offset.y, target.offset.y, t)),
                        )
                }
                zoom = target
            }
    }

    // ---- paging -----------------------------------------------------------------------------------------------

    fun dragPage(dx: Float) {
        rawDrag += dx
        stripX = ZoomMath.pageDrag(rawDrag, stride, page, count)
    }

    fun releasePage(velocityX: Float) {
        val target = ZoomMath.settlePage(rawDrag, velocityX, stride, page, count)
        moveStripTo(target, velocityX)
    }

    /** Accessibility and tests: slide to [target] (clamped) without a finger. */
    fun goTo(target: Int): Boolean {
        val clamped = target.coerceIn(0, count - 1)
        if (clamped == page) return false
        moveStripTo(clamped, 0f)
        return true
    }

    private fun moveStripTo(
        target: Int,
        velocityX: Float,
    ) {
        val end = -(target - page) * stride
        val from = stripX
        running?.cancel()
        running =
            scope.launch {
                animate(from, end, velocityX, RovstiMotion.appear(0.5f)) { v, _ -> stripX = v }
                // One snapshot: the photo that slid into the middle becomes the page, the offset returns to zero.
                if (target != page) {
                    page = target
                    zoom = ZoomState.Fit
                    onPage(target)
                }
                stripX = 0f
                rawDrag = 0f
            }
    }

    // ---- dismissing -------------------------------------------------------------------------------------------

    fun dragDismiss(delta: Offset) {
        dismissX += delta.x
        dismissY += delta.y
    }

    fun releaseDismiss(velocityY: Float) {
        if (ZoomMath.shouldDismiss(dismissY, velocityY, viewport.height)) {
            running?.cancel()
            releasedProgress = dismissProgress
            closing = true
            onClose()
            if (hasAnchor(page)) {
                // The photo does not carry on down the screen: it keeps the speed of the finger and the exit flight
                // brings it, on the same spring, into the thumbnail's rectangle.
                val fromX = dismissX
                val fromY = dismissY
                running =
                    scope.launch {
                        launch {
                            animate(fromX, 0f, 0f, spring(SharedBoundsDamping, SharedBoundsStiffness)) { v, _ ->
                                dismissX = v
                            }
                        }
                        animate(fromY, 0f, velocityY, spring(SharedBoundsDamping, SharedBoundsStiffness)) { v, _ ->
                            dismissY = v
                        }
                    }
            }
            return
        }
        val fromX = dismissX
        val fromY = dismissY
        running?.cancel()
        running =
            scope.launch {
                animate(1f, 0f, animationSpec = RovstiMotion.press(0.001f)) { t, _ ->
                    dismissX = fromX * t
                    dismissY = fromY * t
                }
                dismissX = 0f
                dismissY = 0f
            }
    }
}

private enum class Mode { Undecided, Pinch, Pan, Page, Dismiss }

/**
 * The one gesture loop of the viewer: two fingers pinch and pan with the focus staying under them, one finger pans a
 * zoomed photo, pages a photo at fit when it moves sideways and dismisses it when it moves up or down, a tap toggles the
 * chrome and a double tap zooms. The vertical dismiss is only ever claimed at scale 1.
 */
private fun Modifier.viewerGestures(viewer: ViewerState): Modifier =
    pointerInput(viewer) {
        val slop = viewConfiguration.touchSlop
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            viewer.touchDown()
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            var mode = Mode.Undecided
            var travelled = Offset.Zero
            var event = awaitPointerEvent()
            while (true) {
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                pressed.forEach { tracker.addPointerInputChange(it) }
                if (mode == Mode.Undecided && pressed.size >= 2) mode = Mode.Pinch
                when (mode) {
                    Mode.Pinch -> {
                        viewer.pinch(
                            event.calculateZoom(),
                            event.calculateCentroid(useCurrent = true),
                            event.calculatePan(),
                        )
                    }

                    Mode.Undecided -> {
                        travelled += event.calculatePan()
                        if (travelled.getDistance() > slop) {
                            mode =
                                when {
                                    viewer.zoom.isZoomed -> Mode.Pan
                                    abs(travelled.y) > abs(travelled.x) -> Mode.Dismiss
                                    else -> Mode.Page
                                }
                            applySingle(viewer, mode, travelled)
                        }
                    }

                    else -> {
                        applySingle(viewer, mode, event.calculatePan())
                    }
                }
                if (mode != Mode.Undecided) event.changes.forEach { if (it.positionChanged()) it.consume() }
                event = awaitPointerEvent()
            }
            val velocity = tracker.calculateVelocity()
            when (mode) {
                Mode.Pinch -> {
                    viewer.settleZoom()
                }

                Mode.Pan -> {
                    viewer.releasePage(velocity.x).also { viewer.settleZoom() }
                }

                Mode.Page -> {
                    viewer.releasePage(velocity.x)
                }

                Mode.Dismiss -> {
                    viewer.releaseDismiss(velocity.y)
                }

                Mode.Undecided -> {
                    // A tap. A second one inside the double-tap window zooms; otherwise the first toggles the chrome.
                    val second = withTimeoutOrNull(doubleTapTimeout) { awaitFirstDown(requireUnconsumed = false) }
                    if (second == null) {
                        viewer.toggleChrome()
                    } else {
                        viewer.touchDown()
                        var moved = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: break
                            if ((c.position - second.position).getDistance() > slop) moved = true
                            if (!c.pressed) break
                        }
                        if (!moved) viewer.doubleTap(second.position)
                    }
                }
            }
        }
    }

private fun applySingle(
    viewer: ViewerState,
    mode: Mode,
    delta: Offset,
) {
    when (mode) {
        Mode.Pan -> viewer.panBy(delta)
        Mode.Page -> viewer.dragPage(delta.x)
        Mode.Dismiss -> viewer.dragDismiss(delta)
        else -> Unit
    }
}

/** A rectangle with all four corners [radius] px round: half the short side is a circle, 0 the plain rectangle. */
private class CircleToRect(
    private val radius: Float,
) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ) = androidx.compose.ui.graphics.Outline.Rounded(
        androidx.compose.ui.geometry.RoundRect(
            0f,
            0f,
            size.width,
            size.height,
            androidx.compose.ui.geometry
                .CornerRadius(radius),
        ),
    )
}
