package dev.rovsti.phone.incall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.phone.incoming.HostedIncoming
import dev.rovsti.phone.incoming.IncomingHarness
import dev.rovsti.phone.incoming.IncomingShotHarness
import dev.rovsti.phone.incoming.hostDisplay
import dev.rovsti.phone.incoming.ringing
import dev.rovsti.phone.incoming.ringingSnapshot
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The caller photo as the call background (Incoming and In-Call). The fake loader draws a BRIGHT photo-like scene (pale
 * sky, a white sun, snowy hills) so the shots prove the scrim keeps the white name readable.
 */
@Config(sdk = [35])
class CallPhotoShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    override val imageProducer: (Any, Int, Int) -> Bitmap? = { _, w, h -> brightScene(w, h) }

    private fun brightScene(
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
                Color.rgb(196, 226, 248),
                Color.rgb(250, 236, 214),
                Shader.TileMode.CLAMP,
            )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.rgb(255, 252, 240)
        c.drawCircle(w * 0.72f, h * 0.22f, w * 0.16f, p)
        for ((i, col) in listOf(
            Color.rgb(236, 240, 245),
            Color.rgb(214, 224, 236),
            Color.rgb(186, 204, 222),
        ).withIndex()) {
            p.color = col
            val path = Path()
            val top = h * (0.50f + 0.14f * i)
            path.moveTo(0f, h.toFloat())
            path.lineTo(0f, top + h * 0.05f)
            path.cubicTo(w * 0.3f, top - h * 0.10f, w * 0.6f, top + h * 0.08f, w.toFloat(), top - h * 0.04f)
            path.lineTo(w.toFloat(), h.toFloat())
            path.close()
            c.drawPath(path, p)
        }
        return bmp
    }

    private fun shoot(
        name: String,
        dark: Boolean,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeFrame(dark = dark) { content() }
        advance(1_900)
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    @Test fun incomingPhoto() {
        val snap = ringingSnapshot(ringing(account = "tel/svc#2"))
        val ui = IncomingHarness(snap, display = hostDisplay(snap)).ui
        for (dark in listOf(false, true)) {
            val h = IncomingShotHarness(ui)
            shoot("e5.6-incoming-photo", dark) { HostedIncoming(h, photo = "content://contacts/1/photo") }
        }
    }

    @Test fun inCallPhoto() {
        for (dark in listOf(false, true)) {
            val h = InCallShotHarness(InCallHarness().ui)
            shoot("e5.7-incall-photo", dark) { HostedInCall(h, photo = "content://contacts/1/photo") }
        }
    }
}
