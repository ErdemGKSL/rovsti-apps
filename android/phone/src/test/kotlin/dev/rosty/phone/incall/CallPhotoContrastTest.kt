package dev.rosty.phone.incall

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import dev.rosty.phone.incoming.HostedIncoming
import dev.rosty.phone.incoming.IncomingHarness
import dev.rosty.phone.incoming.IncomingShotHarness
import dev.rosty.phone.incoming.IncomingTags
import dev.rosty.phone.incoming.hostDisplay
import dev.rosty.phone.incoming.incomingStrings
import dev.rosty.phone.incoming.ringing
import dev.rosty.phone.incoming.ringingSnapshot
import dev.rosty.testing.ShotTest
import dev.rosty.testing.assertNodeReadable
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The captions of the call screens over the hardest photo: a flat near-white one (#F2F2F2). The scrim has to rise until
 * every caption reads at 4.5:1 in the rendered pixels, in both themes (X.3 step 5).
 */
@Config(sdk = [35])
class CallPhotoContrastTest : ShotTest() {
    override val imageProducer: (Any, Int, Int) -> Bitmap? = { _, w, h ->
        Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.rgb(0xF2, 0xF2, 0xF2))
        }
    }

    @Test fun incomingCaptionsReadOverAWhitePhoto() {
        val snap = ringingSnapshot(ringing(account = "tel/svc#2"))
        val ui = IncomingHarness(snap, display = hostDisplay(snap)).ui
        for (dark in listOf(false, true)) {
            val h = IncomingShotHarness(ui)
            composeFrame(dark = dark) { HostedIncoming(h, photo = "content://contacts/1/white") }
            advance(1_900)
            val s = incomingStrings
            val tag = "dark=$dark"
            assertNodeReadable(hasText(s.title), 4.5, "$tag Incoming call")
            assertNodeReadable(hasTestTag(IncomingTags.Number), 4.5, "$tag number")
            assertNodeReadable(hasText(s.remind), 4.5, "$tag Remind me")
            assertNodeReadable(hasText(s.decline), 4.5, "$tag Decline")
            assertNodeReadable(hasText(s.answer), 4.5, "$tag Answer")
        }
    }

    @Test fun inCallCaptionsReadOverAWhitePhoto() {
        for (dark in listOf(false, true)) {
            val h = InCallShotHarness(InCallHarness().ui)
            composeFrame(dark = dark) { HostedInCall(h, photo = "content://contacts/1/white") }
            advance(1_900)
            val tag = "dark=$dark"
            assertNodeReadable(hasTestTag(InCallTags.Number), 4.5, "$tag number")
            assertNodeReadable(hasTestTag(InCallTags.Timer), 4.5, "$tag timer")
        }
    }
}
