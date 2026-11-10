// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code (not ported).
package dev.rovsti.design.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Scale
import dev.rovsti.design.backdrop.isRenderEffectSupported
import dev.rovsti.design.motion.LocalReduceMotion

/** Test tags of the photo background. */
object CallBackdropTags {
    /** The photo image; present only once the photo has loaded. */
    const val Photo = "call-backdrop-photo"
    const val Scrim = "call-backdrop-scrim"
}

/** The darkening laid over a caller photo so white text stays readable (WCAG 4.5:1 for the name on a bright photo). */
object CallScrim {
    const val TopAlpha = 0.25f
    const val BottomAlpha = 0.55f

    /** Opacity of the black scrim at [fraction] (0 = top, 1 = bottom) of the screen: linear between the two ends. */
    fun alphaAt(fraction: Float): Float = TopAlpha + (BottomAlpha - TopAlpha) * fraction.coerceIn(0f, 1f)
}

/** Blur of the photo; the glass discs then blur the already soft photo again. */
val CallPhotoBlur = 36.dp

private const val FadeMillis = 300
private const val BlurScale = 1.12f
private const val PreBlurDivisor = 16

/**
 * What a screen needs to know about the photo background: [alpha] runs 0 -> 1 as the photo fades in over the aura, and
 * [avatarGone] turns true once the fade is over (the avatar circle is then no longer composed).
 */
@Stable
class CallPhotoState {
    val alpha = Animatable(0f)
    val avatarGone: State<Boolean> = derivedStateOf { alpha.value >= 0.999f }
}

@Composable
fun rememberCallPhotoState(): CallPhotoState = remember { CallPhotoState() }

/**
 * The photo of the caller as the call background: full-bleed centre-crop, decoded by Coil at the screen's own pixel size,
 * blurred 36 dp, with a black scrim (25 % at the top, 55 % at the bottom), fading in over 300 ms once it has loaded.
 *
 * The screen never waits for it: [CallAura] is drawn from the first frame and this draws nothing until the request
 * succeeds (a failed or missing photo leaves the aura). Blur is a `RenderEffect` where the platform has one; below that
 * the photo is decoded at 1/16 size and scaled up, which is its own blur. [cacheKey] (the contact id) keys the memory
 * cache so a second call from the same person shows the photo at once.
 */
@Composable
fun CallBackdrop(
    photo: Any?,
    state: CallPhotoState,
    modifier: Modifier = Modifier,
    cacheKey: String? = null,
) {
    val reduce = LocalReduceMotion.current
    if (photo == null) {
        LaunchedEffect(Unit) { state.alpha.snapTo(0f) }
        return
    }
    val context = LocalContext.current
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val native = isRenderEffectSupported()
    val width = window.width.coerceAtLeast(1)
    val height = window.height.coerceAtLeast(1)
    val request =
        remember(photo, cacheKey, width, height, native, context) {
            val divisor = if (native) 1 else PreBlurDivisor
            ImageRequest
                .Builder(context)
                .data(photo)
                .size((width / divisor).coerceAtLeast(1), (height / divisor).coerceAtLeast(1))
                .scale(Scale.FILL)
                .allowHardware(native)
                .crossfade(false)
                .apply { if (cacheKey != null) memoryCacheKey("call-photo:$cacheKey:$width") }
                .build()
        }
    val painter =
        rememberAsyncImagePainter(
            model = request,
            imageLoader = currentRovstiImageLoader(),
            contentScale = ContentScale.Crop,
        )
    val loaded = painter.state.collectAsState().value is AsyncImagePainter.State.Success
    LaunchedEffect(loaded, reduce) {
        if (loaded) state.alpha.animateTo(1f, tween(if (reduce) 0 else FadeMillis)) else state.alpha.snapTo(0f)
    }
    if (!loaded) return
    val blurPx = with(density) { CallPhotoBlur.toPx() }
    Box(modifier.fillMaxSize().graphicsLayer { alpha = state.alpha.value }) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag(CallBackdropTags.Photo)
                    .graphicsLayer {
                        if (native) {
                            // Scaled up a little so the clamped blur edge is never on screen.
                            scaleX = BlurScale
                            scaleY = BlurScale
                            renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                        }
                    },
        )
        Box(
            Modifier
                .fillMaxSize()
                .testTag(CallBackdropTags.Scrim)
                .drawWithCache {
                    val brush =
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = CallScrim.TopAlpha),
                            0.5f to Color.Black.copy(alpha = CallScrim.alphaAt(0.5f)),
                            1f to Color.Black.copy(alpha = CallScrim.BottomAlpha),
                        )
                    onDrawBehind { drawRect(brush) }
                },
        )
    }
}
