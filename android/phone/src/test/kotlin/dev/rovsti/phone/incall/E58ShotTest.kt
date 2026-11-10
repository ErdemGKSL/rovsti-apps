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
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import dev.rovsti.core.model.phone.CallUpdate
import dev.rovsti.phone.incoming.HostedIncoming
import dev.rovsti.phone.incoming.IncomingHarness
import dev.rovsti.phone.incoming.IncomingShotHarness
import dev.rovsti.phone.incoming.IncomingUi
import dev.rovsti.phone.incoming.WaCallMeta
import dev.rovsti.phone.incoming.hostDisplay
import dev.rovsti.phone.incoming.ringing
import dev.rovsti.phone.incoming.ringingSnapshot
import dev.rovsti.phone.incoming.waitingSnapshot
import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.8b screenshots: WhatsApp calls on Incoming, In-Call (dimmed controls), the waiting card and the profile photo. */
@Config(sdk = [35])
class E58ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private val jid = "905551112233@s.whatsapp.net"

    override val imageProducer: (Any, Int, Int) -> Bitmap? = { _, w, h -> profilePhoto(w, h) }

    /** A warm, mid-bright portrait-like scene (what a WhatsApp profile picture looks like, not a landscape). */
    private fun profilePhoto(
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

    private fun meta(
        direction: CallDirection,
        phase: CallPhase,
    ) = WaCallMeta(
        "c1",
        jid,
        CallUpdate("c1", jid, "Ada Lovelace", direction, phase, false),
        speaker = false,
        muted = false,
    )

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

    private fun incomingUi(): IncomingUi {
        val snap = ringingSnapshot(ringing(name = "Ada Lovelace", number = "+905551112233", contactId = null))
        val h = IncomingHarness(snap, display = hostDisplay(snap))
        h.source.setWhatsApp(mapOf(1 to meta(CallDirection.In, CallPhase.Ringing)))
        return h.ui
    }

    private fun waitingUi(): IncomingUi {
        val snap = waitingSnapshot()
        val h = IncomingHarness(snap, display = hostDisplay(snap))
        h.source.setWhatsApp(mapOf(3 to meta(CallDirection.In, CallPhase.Ringing)))
        return h.ui
    }

    private fun inCallUi(): InCallUi {
        val call = liveCall(name = "Ada Lovelace", number = "+905551112233", contactId = null, canHold = false)
        val h = InCallHarness(liveSnapshot(call, endpoints = emptyList()))
        h.source.setWhatsApp(mapOf(call.id to meta(CallDirection.Out, CallPhase.Active)))
        return h.ui
    }

    @Test fun incoming() {
        for (dark in listOf(false, true)) {
            val h = IncomingShotHarness(incomingUi())
            shoot("e5.8-wa-incoming", dark) { HostedIncoming(h) }
        }
    }

    @Test fun incomingPhoto() {
        for (dark in listOf(false, true)) {
            val h = IncomingShotHarness(incomingUi())
            shoot("e5.8-wa-incoming-photo", dark) { HostedIncoming(h, photo = "wa-avatar:$jid") }
        }
    }

    @Test fun inCallDimmedControls() {
        for (dark in listOf(false, true)) {
            val h = InCallShotHarness(inCallUi())
            shoot("e5.8-wa-incall-dimmed-controls", dark) { HostedInCall(h) }
        }
    }

    @Test fun waitingDuringCellular() {
        for (dark in listOf(false, true)) {
            val h = IncomingShotHarness(waitingUi())
            shoot("e5.8-wa-waiting-during-cellular", dark) { HostedIncoming(h, inCall = true) }
        }
    }
}
