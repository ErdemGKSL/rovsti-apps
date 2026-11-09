// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code (not ported).
package dev.rovsti.design.media

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size
import okio.Path.Companion.toOkioPath

/** Share of the app's memory class the bitmap cache may use; a low-RAM device gets the smaller one. */
object ImageCacheShare {
    const val Normal = 0.25
    const val LowRam = 0.15
}

/**
 * The app's one image loader: a memory cache of 25 % of the memory class (15 % on low-RAM devices), a disk cache in
 * `filesDir/images`, hardware bitmaps on, crossfade off. There is no network fetcher: apps load `content://` and file
 * URIs, and WhatsApp media arrive as files or bytes from the gateway client.
 *
 * `:shell` should call `imageLoader.memoryCache?.trimMemory(level)` from `onTrimMemory(RUNNING_LOW)`.
 */
fun rovstiImageLoader(
    context: Context,
    lowRam: Boolean,
    /** Extra fetchers, keyers and decoders; `:wa` adds the profile picture fetcher here (`waAvatars(avatars)`). */
    components: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader =
    ImageLoader
        .Builder(context)
        .components(components)
        .memoryCache {
            MemoryCache
                .Builder()
                .maxSizePercent(context, if (lowRam) ImageCacheShare.LowRam else ImageCacheShare.Normal)
                .build()
        }.diskCache {
            DiskCache
                .Builder()
                .directory(context.filesDir.resolve("images").toOkioPath())
                .build()
        }.crossfade(false)
        .allowHardware(true)
        .build()

/**
 * The loader the images of a screen use. Apps provide [rovstiImageLoader] (or leave it unset to get Coil's singleton);
 * tests provide a fake one, so no screenshot depends on a file, the network or a decoder.
 */
val LocalRovstiImageLoader = compositionLocalOf<ImageLoader?> { null }

@Composable
fun currentRovstiImageLoader(): ImageLoader {
    val context = LocalContext.current
    return LocalRovstiImageLoader.current ?: SingletonImageLoader.get(context)
}

/** Pixels of a slot of [size] at [density]; at least 1, so a slot that has not been measured yet never asks for 0. */
fun slotPixels(
    size: Dp,
    density: Density,
): Int = with(density) { size.roundToPx() }.coerceAtLeast(1)

/**
 * The request for a square slot of [size]: decoded at the slot's own pixels (never the full photo for a 44 dp avatar),
 * no crossfade (rows scroll; a fade per row would repaint the list).
 */
@Composable
fun rememberSlotRequest(
    model: Any?,
    size: Dp,
): ImageRequest {
    val context = LocalContext.current
    val px = slotPixels(size, LocalDensity.current)
    return remember(model, px, context) {
        ImageRequest
            .Builder(context)
            .data(model)
            .size(px, px)
            .crossfade(false)
            .build()
    }
}

/**
 * A square image in a row or a grid cell: [size] decides both the layout and the decode size. [contentDescription]
 * `null` marks it decorative (the row's own label names it).
 */
@Composable
fun RowImage(
    model: Any?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val request = rememberSlotRequest(model, size)
    val described =
        if (contentDescription !=
            null
        ) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else {
            Modifier
        }
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = currentRovstiImageLoader(),
        contentScale = contentScale,
        modifier =
            modifier
                .size(size)
                .then(if (shape != null) Modifier.clip(shape) else Modifier)
                .then(described),
    )
}

/**
 * An image decoded at [widthPx] x [heightPx] (for a picture that is zoomed and panned: the crop page decodes at the
 * picture's own size, capped by the caller), drawn into whatever box [modifier] gives it. The layout is the caller's.
 */
@Composable
fun SizedImage(
    model: Any?,
    widthPx: Int,
    heightPx: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillBounds,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val request =
        remember(model, widthPx, heightPx, context) {
            ImageRequest
                .Builder(context)
                .data(model)
                .size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
                .crossfade(false)
                .build()
        }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = currentRovstiImageLoader(),
        contentScale = contentScale,
        modifier = modifier,
    )
}

/**
 * A loader that draws every image itself instead of reading it: [producer] gets the data of the request and the pixel
 * size asked for and returns the bitmap (or `null` for a failure). It serves the catalog's generated photos and the
 * fake loader of the screenshot tests; [onRequest] sees every request with its resolved size.
 */
fun producedImageLoader(
    context: Context,
    onRequest: (ImageRequest, Size) -> Unit = { _, _ -> },
    producer: (data: Any, widthPx: Int, heightPx: Int) -> Bitmap?,
): ImageLoader =
    ImageLoader
        .Builder(context)
        .components {
            add(
                object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        val request = chain.request
                        val size = chain.size
                        onRequest(request, size)
                        val w = (size.width as? Dimension.Pixels)?.px ?: DefaultProducedPx
                        val h = (size.height as? Dimension.Pixels)?.px ?: DefaultProducedPx
                        val bitmap = producer(request.data, w, h) ?: return chain.proceed()
                        return SuccessResult(bitmap.asImage(), request, DataSource.MEMORY)
                    }
                },
            )
        }.crossfade(false)
        .build()

private const val DefaultProducedPx = 512
