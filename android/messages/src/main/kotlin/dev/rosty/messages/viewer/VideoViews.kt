package dev.rosty.messages.viewer

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rosty.platform.media.MediaVideoPlayers
import dev.rosty.platform.media.VideoPlayer
import dev.rosty.platform.media.VideoPlayers
import java.io.File

/** The video players of the screens below; null: the platform player. Tests provide fakes. */
val LocalVideoPlayers = staticCompositionLocalOf<VideoPlayers?> { null }

/** [LocalVideoPlayers], else the platform player. */
@Composable
fun rememberVideoPlayers(): VideoPlayers {
    val provided = LocalVideoPlayers.current
    val context = LocalContext.current
    return provided ?: remember(context) { MediaVideoPlayers(context) }
}

/**
 * Draws [player] on a [TextureView]: a texture, not a `SurfaceView`, so it scales, fades and flies with the layers
 * above it (the viewer's zoom and dismiss). It stays clear until the first frame, so the thumbnail under it shows.
 */
@Composable
fun VideoSurface(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
) {
    key(player) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = false
                    surfaceTextureListener = SurfaceBinding(player)
                }
            },
            modifier = modifier,
        )
    }
}

/** Hands the texture's surface to the player while it exists. */
private class SurfaceBinding(
    private val player: VideoPlayer,
) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surface = Surface(texture).also(player::setSurface)
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        player.setSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) = Unit

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

/** Pauses [player] when the app goes to the background; [resume] plays it again when it comes back. */
@Composable
fun PauseInBackground(
    player: VideoPlayer,
    resume: Boolean,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player, lifecycle, resume) {
        var wasPlaying = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        wasPlaying = player.state.value.playing
                        player.pause()
                    }

                    Lifecycle.Event.ON_RESUME -> {
                        if (resume && wasPlaying) player.play()
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** A GIF (a video WhatsApp plays as one): muted, over and over, from the start, for as long as it is on screen. */
@Composable
fun LoopingVideo(
    file: File,
    modifier: Modifier = Modifier,
) {
    val players = rememberVideoPlayers()
    val player = remember(file, players) { players.open(file, loop = true, muted = true, startMs = 0, autoplay = true) }
    DisposableEffect(player) { onDispose { player.release() } }
    PauseInBackground(player, resume = true)
    VideoSurface(player, modifier)
}
