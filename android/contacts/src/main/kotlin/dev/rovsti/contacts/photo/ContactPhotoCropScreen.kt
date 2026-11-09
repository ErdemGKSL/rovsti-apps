package dev.rovsti.contacts.photo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.rovsti.contacts.R
import dev.rovsti.core.model.contacts.CropView
import dev.rovsti.design.chrome.rememberGlassScreenChrome
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.GlassIconButton
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.glass.LocalAppBackdrop
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.media.SizedImage
import dev.rovsti.design.media.ZoomState
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayEntry
import dev.rovsti.design.overlay.OverlayScope
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Test tags of the crop page. */
object CropTags {
    const val Root = "photo-crop"
    const val Frame = "photo-crop-frame"
    const val Image = "photo-crop-image"
    const val Cancel = "photo-crop-cancel"
    const val Rotate = "photo-crop-rotate"
    const val Choose = "photo-crop-choose"
}

/**
 * Opens the crop page for [session] over everything: a dark full-screen page (the picture is on show against black, in
 * light and dark, so its chrome is always the dark one). Cancel and Back call [onCancel]; Choose calls [onChosen] with
 * the [CropView] the owner saw. Either way the page leaves.
 */
fun OverlayController.showPhotoCrop(
    session: CropSession,
    onChosen: (CropView) -> Unit,
    onCancel: () -> Unit,
): OverlayEntry = present(PhotoCropEntry(session, onChosen, onCancel))

class PhotoCropEntry(
    private val session: CropSession,
    private val onChosen: (CropView) -> Unit,
    private val onCancel: () -> Unit,
) : OverlayEntry(modal = true, title = "Move and Scale") {
    private var answered = false

    override fun onBack(controller: OverlayController) {
        answer(controller, null)
    }

    private fun answer(
        controller: OverlayController,
        view: CropView?,
    ) {
        if (answered) return
        answered = true
        controller.dismiss(this)
        if (view == null) onCancel() else onChosen(view)
    }

    @Composable
    override fun Content(host: OverlayScope) {
        val reduce = LocalReduceMotion.current
        val transition = rememberTransition(state, label = "photo-crop")
        val appear by transition.animateFloat(
            transitionSpec = { tween(if (reduce) 100 else 260) },
            label = "photo-crop-appear",
        ) { if (it) 1f else 0f }
        RovstiTheme(darkTheme = true) {
            val chrome = rememberGlassScreenChrome(Color.Black)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = appear }
                    .testTag(CropTags.Root),
            ) {
                CropBody(
                    session = session,
                    backdropModifier = chrome.contentModifier(),
                    screenModifier = chrome.screenModifier(),
                    backdrop = chrome.backdrop,
                    onCancel = { answer(host.controller, null) },
                    onChosen = { answer(host.controller, it) },
                )
            }
        }
    }
}

@Composable
private fun CropBody(
    session: CropSession,
    backdropModifier: Modifier,
    screenModifier: Modifier,
    backdrop: dev.rovsti.design.backdrop.backdrops.LayerBackdrop,
    onCancel: () -> Unit,
    onChosen: (CropView) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val colors = RovstiTheme.colors

    // The gestures are Animatables, not state: a finger takes over a spring in flight, a release hands it its velocity.
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val angle = remember { Animatable(0f) }
    var quarterTurns by remember { mutableIntStateOf(0) }
    val turns = Math.floorMod(quarterTurns, 4)
    val rotated = PhotoCropMath.rotated(session.width, session.height, turns)
    val maxZoom = session.maxZoom.toFloat()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val topRoom = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() } + CropMetrics.Top
            val side =
                min(
                    with(density) { (maxWidth - Spacing.xl * 2).toPx() },
                    with(density) { (maxHeight - topRoom - CropMetrics.Bottom - Spacing.xl).toPx() },
                ).coerceAtLeast(with(density) { CropMetrics.WindowMin.toPx() })
            val sideDp = with(density) { side.toDp() }
            // The window sits in the middle of the room between the title and the buttons.
            val frameTopPx =
                with(density) {
                    topRoom.toPx() + ((maxHeight - topRoom - CropMetrics.Bottom).toPx() - side).coerceAtLeast(0f) / 2f
                }
            val rotatedNow by rememberUpdatedState(rotated)

            // The picture and its mask: this is the layer the bottom glass buttons sample.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(screenModifier)
                    .then(backdropModifier),
            ) {
                // Source orientation, scaled so the short side fills the window; rotation is a layer transform, so the
                // box never changes size and a turn animates smoothly.
                val ratio = side / min(session.width, session.height).toFloat()
                val imageW = session.width * ratio
                val imageH = session.height * ratio
                val decodeCap = CROP_DECODE_CAP_PX
                val decodeScale = min(1f, decodeCap / max(session.width, session.height).toFloat())
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = with(density) { frameTopPx.toDp() })
                        .size(sideDp)
                        .testTag(CropTags.Frame),
                ) {
                    SizedImage(
                        model = File(session.path),
                        widthPx = (session.width * decodeScale).roundToInt(),
                        heightPx = (session.height * decodeScale).roundToInt(),
                        contentScale = ContentScale.FillBounds,
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .requiredSize(with(density) { imageW.toDp() }, with(density) { imageH.toDp() })
                                .testTag(CropTags.Image)
                                .graphicsLayer {
                                    rotationZ = angle.value
                                    scaleX = scale.value
                                    scaleY = scale.value
                                    translationX = offsetX.value
                                    translationY = offsetY.value
                                },
                    )
                }
                // Dim everything outside the circle; the ring marks its edge.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            val center = Offset(size.width / 2f, frameTopPx + side / 2f)
                            drawRect(Color.Black.copy(alpha = CROP_DIM_ALPHA))
                            drawCircle(Color.Black, radius = side / 2f, center = center, blendMode = BlendMode.Clear)
                            drawCircle(
                                Color.White.copy(alpha = CROP_RING_ALPHA),
                                radius = side / 2f,
                                center = center,
                                style = Stroke(CropMetrics.Ring.toPx()),
                            )
                        },
                )
                // The touch surface is the window and a margin around it, not the buttons.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(side, turns, maxZoom) {
                            val tracker = VelocityTracker()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                tracker.resetTracking()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                val windowCentre = Offset(size.width / 2f, frameTopPx + side / 2f)
                                var moved = Offset.Zero
                                var touched = false
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    moved += pan
                                    if (!touched && (moved.getDistance() > slop || zoom != 1f)) touched = true
                                    if (touched) {
                                        val next =
                                            PhotoCropMath.pinch(
                                                state = ZoomState(scale.value, Offset(offsetX.value, offsetY.value)),
                                                zoomChange = zoom,
                                                focus =
                                                    if (centroid.isSpecified) centroid - windowCentre else Offset.Zero,
                                                panChange = pan,
                                                rotated = rotatedNow,
                                                side = side,
                                                maxZoom = maxZoom,
                                            )
                                        scope.launch {
                                            scale.snapTo(next.scale)
                                            offsetX.snapTo(next.offset.x)
                                            offsetY.snapTo(next.offset.y)
                                        }
                                        event.changes.forEach {
                                            tracker.addPosition(it.uptimeMillis, it.position)
                                            if (it.positionChanged()) it.consume()
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                                val v = tracker.calculateVelocity()
                                scope.launch {
                                    val limit = PhotoCropMath.limit(scale.value, rotatedNow, side)
                                    offsetX.updateBounds(-limit.x, limit.x)
                                    offsetY.updateBounds(-limit.y, limit.y)
                                    launch { offsetX.animateDecay(v.x, exponentialDecay()) }
                                    launch { offsetY.animateDecay(v.y, exponentialDecay()) }
                                }
                            }
                        },
                )
            }

            RovstiText(
                stringResource(R.string.crop_title),
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = Spacing.m)
                    .semantics { heading() },
                style = RovstiTheme.type.headline,
                color = Color.White,
            )

            CompositionLocalProvider(LocalAppBackdrop provides backdrop, LocalIosBackdrop provides backdrop) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.l),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        stringResource(R.string.crop_cancel),
                        onClick = onCancel,
                        tint = Color.White,
                        modifier = Modifier.testTag(CropTags.Cancel),
                    )
                    GlassIconButton(
                        icon = RovstiIcons.Rotate,
                        contentDescription = stringResource(R.string.crop_rotate),
                        onClick = {
                            quarterTurns += 1
                            scope.launch {
                                launch { angle.animateTo(90f * quarterTurns, RovstiMotion.sheetSettle(0.5f)) }
                                launch { scale.animateTo(1f, RovstiMotion.sheetSettle(0.5f)) }
                                launch { offsetX.animateTo(0f, RovstiMotion.sheetSettle(0.5f)) }
                                launch { offsetY.animateTo(0f, RovstiMotion.sheetSettle(0.5f)) }
                            }
                        },
                        modifier = Modifier.testTag(CropTags.Rotate),
                    )
                    CapsuleButton(
                        stringResource(R.string.crop_choose),
                        onClick = {
                            onChosen(
                                PhotoCropMath.viewOf(
                                    state = ZoomState(scale.value, Offset(offsetX.value, offsetY.value)),
                                    turns = quarterTurns,
                                    rotated = rotatedNow,
                                    side = side,
                                    maxZoom = maxZoom,
                                ),
                            )
                        },
                        style = CapsuleStyle.Filled,
                        modifier = Modifier.testTag(CropTags.Choose),
                    )
                }
            }
        }
    }
}
