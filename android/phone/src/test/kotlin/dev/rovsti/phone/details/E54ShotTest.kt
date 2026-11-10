package dev.rovsti.phone.details

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.qr.QrEncodeRequest
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.core.ops.NativeQrOps
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.recents.HostedRecents
import dev.rovsti.phone.recents.RecentsHarness
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.4 screenshots: the sheets over the real Recents page and shell, light and dark. */
@Config(sdk = [35])
class E54ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private val recentsStrings get() =
        dev.rovsti.phone.recents
            .RecentsStrings(compose.activity.resources)

    private fun snap(
        name: String,
        fontScale: Float = 1f,
        after: (RecentsHarness) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            val h = RecentsHarness()
            composeFrame(dark = dark, fontScale = fontScale) { HostedRecents(h, recentsStrings) }
            advance(500)
            after(h)
            advance(1_600)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun contactSheet() =
        snap("e5.4-contact-sheet") { h -> compose.runOnUiThread { h.overlays.presentPerson(DetailsSample.person()) } }

    @Test fun contactSheetLarge() =
        snap("e5.4-contact-sheet-large") { h ->
            compose.runOnUiThread { h.overlays.presentPerson(DetailsSample.person(), detent = Detent.Large) }
        }

    @Test fun contactSheetBigFont() =
        snap("e5.4-contact-sheet-fs2", fontScale = 2f) { h ->
            compose.runOnUiThread { h.overlays.presentPerson(DetailsSample.person(), detent = Detent.Large) }
        }

    @Test fun numberSheet() =
        snap("e5.4-number-sheet") { h ->
            compose.runOnUiThread {
                h.overlays.presentPerson(
                    DetailsSample.person(named = false),
                    detent = Detent.Large,
                )
            }
        }

    @Test fun moreMenu() =
        snap("e5.4-contact-menu") { h ->
            compose.runOnUiThread { h.overlays.presentPerson(DetailsSample.person()) }
            advance(1_200)
            compose.onNodeWithTag(PersonTags.More).performClick()
            advance(900)
        }

    @Test fun personStats() =
        snap("e5.4-person-stats") { h ->
            compose.runOnUiThread { h.overlays.presentPerson(DetailsSample.person(), detent = Detent.Large) }
            advance(1_200)
            compose.onNodeWithTag(PersonTags.Stats).performScrollTo()
            advance(300)
        }

    @Test fun callDetails() =
        snap("e5.4-call-details") { h ->
            val call = CallHarness(DetailsSample.call(note = "Taxi receipt: ask about the invoice"))
            compose.runOnUiThread { h.overlays.presentCall(call, Detent.Large) }
        }

    @Test fun callNoteEditing() =
        snap("e5.4-call-note-editing") { h ->
            val call = CallHarness(DetailsSample.call(note = "Taxi receipt"))
            compose.runOnUiThread { h.overlays.presentCall(call, Detent.Large) }
            advance(1_200)
            compose.onNodeWithTag(CallDetailsTags.Note).performClick()
            compose.onNodeWithTag(CallDetailsTags.Note).performTextInput(" and the date of the meeting with Mert")
            setSimulatedIme(300)
            advance(500)
        }

    @Test fun qr() {
        val host = HostRust("rovsti-e54-qr")
        val matrix =
            runBlocking {
                val answer =
                    NativeQrOps(
                        host.core,
                    ).encode(QrEncodeRequest("BEGIN:VCARD\nVERSION:3.0\nFN:Ayse Yilmaz\nTEL:+905551112233\nEND:VCARD"))
                QrMatrix.fromAnswer((answer as dev.rovsti.core.Outcome.Ok).value)
            }
        val qr = PersonQr("Ayse Yilmaz", "BEGIN:VCARD", matrix)
        snap("e5.4-qr") { h ->
            compose.runOnUiThread {
                h.overlays.present(
                    SheetEntry(title = "QR code", detents = persistentListOf(Detent.Medium)) { _ ->
                        PersonQrContent(qr, strings, onShare = {})
                    },
                )
            }
        }
    }
}
