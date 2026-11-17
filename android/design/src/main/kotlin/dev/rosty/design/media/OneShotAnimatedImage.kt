package dev.rosty.design.media

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import dev.rosty.design.motion.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A decoded picture the composable can play: a drawable plus the three things it does to it. */
interface OneShotImage {
    val drawable: Drawable

    /** Plays from the first frame; calling it again replays. A still ignores it. */
    fun start()

    /** Frees the decoder; called once when the composable leaves the composition. */
    fun release()
}

/** Where pictures come from; the seam lets tests inject a fake. [animate] is false under reduce motion. */
fun interface AnimatedImageSource {
    suspend fun open(
        path: String,
        animate: Boolean,
    ): OneShotImage?
}

/** The platform source: [ImageDecoder] into an [AnimatedImageDrawable] that plays once and holds its last frame. */
object PlatformAnimatedImageSource : AnimatedImageSource {
    override suspend fun open(
        path: String,
        animate: Boolean,
    ): OneShotImage? =
        withContext(Dispatchers.IO) {
            runCatching { decode(path, animate) }.getOrNull() ?: runCatching { bitmap(path) }.getOrNull()
        }

    private fun decode(
        path: String,
        animate: Boolean,
    ): OneShotImage {
        val source = ImageDecoder.createSource(File(path))
        if (!animate) return StillImage(BitmapDrawable(null, ImageDecoder.decodeBitmap(source)))
        val drawable = ImageDecoder.decodeDrawable(source)
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = 0
            return PlatformAnimated(drawable)
        }
        return StillImage(drawable)
    }

    private fun bitmap(path: String): OneShotImage? =
        BitmapFactory.decodeFile(path)?.let { StillImage(BitmapDrawable(null, it)) }
}

private class PlatformAnimated(
    private val animated: AnimatedImageDrawable,
) : OneShotImage {
    override val drawable: Drawable get() = animated

    override fun start() {
        animated.stop()
        animated.start()
    }

    override fun release() {
        animated.stop()
        animated.callback = null
    }
}

private class StillImage(
    override val drawable: Drawable,
) : OneShotImage {
    override fun start() = Unit

    override fun release() = Unit
}

/** The source [OneShotAnimatedImage] reads; tests provide a fake. */
val LocalAnimatedImageSource = staticCompositionLocalOf<AnimatedImageSource> { PlatformAnimatedImageSource }

/**
 * An animated picture (a GIF) that plays once and rests on its last frame.
 *
 * It decodes off the main thread, draws the drawable through a [Drawable.Callback] that invalidates only the draw
 * phase, and calls start once per [playKey] (a new key replays). Under [LocalReduceMotion] it never starts and shows
 * a still. A file that is not animated falls back to a bitmap.
 */
@Composable
fun OneShotAnimatedImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    playKey: Any = path,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val source = LocalAnimatedImageSource.current
    val reduceMotion = LocalReduceMotion.current
    val image by produceState<OneShotImage?>(null, path, source, reduceMotion) {
        val opened = source.open(path, animate = !reduceMotion)
        value = opened
        awaitDispose { opened?.release() }
    }
    var frame by remember { mutableIntStateOf(0) }
    val current = image
    if (current != null) {
        DisposableEffect(current) {
            val handler = Handler(Looper.getMainLooper())
            val callback =
                object : Drawable.Callback {
                    override fun invalidateDrawable(who: Drawable) {
                        frame++
                    }

                    override fun scheduleDrawable(
                        who: Drawable,
                        what: Runnable,
                        time: Long,
                    ) {
                        handler.postAtTime(what, who, time)
                    }

                    override fun unscheduleDrawable(
                        who: Drawable,
                        what: Runnable,
                    ) {
                        handler.removeCallbacks(what, who)
                    }
                }
            current.drawable.callback = callback
            frame++
            onDispose {
                handler.removeCallbacksAndMessages(null)
                current.drawable.callback = null
            }
        }
        LaunchedEffect(current, playKey, reduceMotion) {
            if (!reduceMotion) current.start()
        }
    }
    Box(
        modifier
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .drawWithContent {
                frame // read: a new frame redraws, nothing else recomposes
                current?.drawable?.let { drawDrawable(it, contentScale, size) }
            },
        contentAlignment = Alignment.Center,
    ) {}
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDrawable(
    drawable: Drawable,
    contentScale: ContentScale,
    size: Size,
) {
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0 || size.width <= 0f || size.height <= 0f) return
    val scale = contentScale.computeScaleFactor(Size(w.toFloat(), h.toFloat()), size)
    val fitted = IntSize((w * scale.scaleX).toInt(), (h * scale.scaleY).toInt())
    val offset = Alignment.Center.align(fitted, IntSize(size.width.toInt(), size.height.toInt()), layoutDirection)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.clipRect(0f, 0f, size.width, size.height)
        native.translate(offset.x.toFloat(), offset.y.toFloat())
        native.scale(scale.scaleX, scale.scaleY)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(native)
        native.restore()
    }
}
