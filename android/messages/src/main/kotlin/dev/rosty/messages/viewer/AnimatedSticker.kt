package dev.rosty.messages.viewer

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.rosty.core.log.RostyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * An animated sticker (an animated WebP) that plays where it is, over and over, for as long as it is on screen. The
 * platform decoder does it (`ImageDecoder` and `AnimatedImageDrawable`, API 28+; the app's minimum is 34): the app's
 * image loader (Coil 3 without its GIF module) only gives the first frame. [fallback] is drawn until the file is
 * decoded, and instead of it when the file is not animated or cannot be read.
 */
@Composable
fun AnimatedSticker(
    file: File,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit = {},
) {
    val drawable by produceState<Drawable?>(null, file) {
        value =
            withContext(Dispatchers.IO) {
                try {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                } catch (e: IOException) {
                    RostyLog.w(TAG, "cannot decode a sticker: ${e.message}")
                    null
                } catch (e: IllegalArgumentException) {
                    RostyLog.w(TAG, "cannot decode a sticker: ${e.message}")
                    null
                }
            }
    }
    val animated = drawable as? AnimatedImageDrawable
    if (animated == null) {
        fallback()
        return
    }
    DisposableEffect(animated) {
        animated.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        animated.start()
        onDispose { animated.stop() }
    }
    AndroidView(
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view -> if (view.drawable !== animated) view.setImageDrawable(animated) },
        modifier = modifier,
    )
}

private const val TAG = "wa-sticker"
