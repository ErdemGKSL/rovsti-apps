package dev.rovsti.phone.details.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.design.media.ViewerPhoto
import dev.rovsti.design.media.showPhotoViewer
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.platform.files.FakeFilesRepository
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** The contact of the E5.13 tests: a thumbnail URI the fake image loader answers with [portrait]. */
internal val SamplePhoto = PersonPhoto(contactId = 1, thumb = "content://contacts/1/photo")

/** A warm, mid-bright portrait scene (a head and shoulders on a gradient) of any size. */
internal fun portrait(
    w: Int,
    h: Int,
): Bitmap {
    val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader =
        LinearGradient(
            0f,
            0f,
            0f,
            h.toFloat(),
            Color.rgb(250, 214, 170),
            Color.rgb(176, 120, 96),
            Shader.TileMode.CLAMP,
        )
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    p.shader = null
    p.color = Color.rgb(244, 224, 206)
    c.drawCircle(w * 0.5f, h * 0.36f, w * 0.2f, p)
    p.color = Color.rgb(96, 70, 62)
    val body = Path()
    body.moveTo(w * 0.1f, h.toFloat())
    body.cubicTo(w * 0.15f, h * 0.62f, w * 0.85f, h * 0.62f, w * 0.9f, h.toFloat())
    body.close()
    c.drawPath(body, p)
    return bmp
}

/**
 * The opener the Person and Call details sheets use in tests: the real [PersonPhotoActions] toolbar and Edit menu over a
 * fake files repository, opened out of the avatar through the same `showPhotoViewer` the app calls.
 */
internal class TestPhotoOpener(
    private val overlays: OverlayController,
) : PersonPhotoOpener {
    val opened = mutableListOf<PersonPhoto>()
    val calls = mutableListOf<String>()

    override fun open(
        photo: PersonPhoto,
        name: String,
        anchor: () -> androidx.compose.ui.geometry.Rect?,
    ) {
        opened += photo
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions =
            PersonPhotoActions(
                name = name,
                sources = emptyList(),
                files = FakeFilesRepository(),
                scope = CoroutineScope(Dispatchers.Unconfined),
                say = {},
                share = { _, _ -> },
            )
        val menu =
            PersonPhotoMenu(choose = { calls += "choose" }, take = { calls += "take" }, remove = {
                calls +=
                    "remove"
            })
        overlays.showPhotoViewer(
            photos =
                persistentListOf(
                    ViewerPhoto(full = photo.thumb, thumb = photo.thumb, source = photo.source, circular = true),
                ),
            initialPage = 0,
            actions =
                actions.viewerActions(
                    context,
                    photo.source,
                    PersonPhotoActions.editMenu(context, photo.hasPhoto, menu),
                ),
            anchorFor = { anchor() },
        )
    }
}

@Composable
internal fun WithOpener(
    opener: PersonPhotoOpener,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalPersonPhotoOpener provides opener, content = content)
