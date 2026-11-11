package dev.rovsti.phone.incall

import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.7b screenshots: two calls (the Held card), a conference, the Add call sheet and the conference panel. */
@Config(sdk = [35])
class E57bShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        ui: InCallUi,
        fontScale: Float = 1f,
        after: (InCallShotHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val harness = InCallShotHarness(ui)
            composeFrame(dark = dark, fontScale = fontScale) { HostedInCall(harness) }
            advance(500)
            compose.runOnUiThread { after(harness) }
            advance(1_400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun holdTwoCalls() = snap("e5.7-incall-hold-two-calls", InCallHarness(twoCalls()).ui)

    @Test fun holdTwoCallsBigFont() =
        snap(
            "e5.7-incall-hold-two-calls-fs2",
            InCallHarness(twoCalls()).ui,
            fontScale = 2f,
        )

    @Test fun conference() = snap("e5.7-incall-conference", InCallHarness(conferenceCall()).ui)

    @Test fun addCallSheet() =
        snap("e5.7-add-call-sheet", InCallHarness().ui) { h ->
            h.overlays.present(
                SheetEntry(title = inCallStrings.addTitle, detents = persistentListOf(Detent.Large)) { sheet ->
                    AddCallSheetBody(MutableStateFlow(addCallPeople), inCallStrings, sheet, onDial = {})
                },
            )
        }

    @Test fun addCallSheetFavorites() =
        snap("e5.7-add-call-sheet-favorites", InCallHarness().ui) { h ->
            h.overlays.present(
                SheetEntry(title = inCallStrings.addTitle, detents = persistentListOf(Detent.Large)) { sheet ->
                    AddCallSheetBody(
                        MutableStateFlow(addCallPeople),
                        inCallStrings,
                        sheet,
                        onDial = {},
                        initialTab = 1,
                    )
                },
            )
        }

    @Test fun conferencePanel() {
        val model = InCallHarness(conferenceCall())
        snap("e5.7-conference-panel", model.ui) { h ->
            h.overlays.present(
                SheetEntry(title = inCallStrings.confTitle, detents = persistentListOf(Detent.Medium, Detent.Large)) {
                    ConferenceSheetBody(model.model.state, inCallStrings, {}, onGone = {})
                },
            )
        }
    }
}
