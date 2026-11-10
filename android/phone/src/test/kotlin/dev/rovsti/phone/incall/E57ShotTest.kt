package dev.rovsti.phone.incall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.design.overlay.PopoverEntry
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.phone.data.Sims
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.7a screenshots: the In-Call page on the real shell (no tab bar), its keypad, the route popover, the pills. */
@Config(sdk = [35])
class E57ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private val vodafone = SimAccount("tel/svc#2", "Vodafone", "Vodafone", "Vodafone", 0xFFFF453A.toInt(), 1, true)
    private val twoSims = Sims(visible = true, chips = persistentListOf(vodafone))

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

    private val rich = liveSnapshot(endpoints = listOf(earpiece, speaker, headset))

    @Test fun active() = snap("e5.7-incall-active", InCallHarness().ui)

    @Test fun activeBigFont() = snap("e5.7-incall-fs2", InCallHarness().ui, fontScale = 2f)

    @Test fun mutedOnSpeaker() =
        snap(
            "e5.7-incall-muted-speaker",
            InCallHarness(liveSnapshot(muted = true, route = dev.rovsti.core.model.phone.AudioRoute.Speaker)).ui,
        )

    @Test fun onHold() =
        snap(
            "e5.7-incall-hold",
            InCallHarness(liveSnapshot(liveCall(state = dev.rovsti.core.model.phone.WireCallState.Holding))).ui,
        )

    @Test fun calling() =
        snap(
            "e5.7-incall-calling",
            InCallHarness(
                liveSnapshot(liveCall(state = dev.rovsti.core.model.phone.WireCallState.Dialing, connectedAgoS = -1)),
            ).ui,
        )

    @Test fun keypad() =
        snap(
            "e5.7-incall-keypad",
            InCallHarness().ui.copy(
                keypad = ToneKeypad(open = true, digits = "4821", pasteLabel = "Send 4821#", remembered = "7#"),
            ),
        )

    @Test fun simChip() =
        snap("e5.7-incall-sim-chip", InCallHarness(liveSnapshot(liveCall(account = "tel/svc#2")), twoSims).ui)

    @Test fun endedSummary() {
        val h = InCallHarness()
        h.source.set(CallSnapshot(keyguardLocked = false))
        snap("e5.7-incall-ended-summary", h.ui)
    }

    @Test fun routePickerPopover() {
        val ui = InCallHarness(rich).ui
        snap("e5.7-route-picker-popover", ui) { h ->
            val bounds =
                compose
                    .onNodeWithTag(InCallTags.Audio)
                    .fetchSemanticsNode()
                    .boundsInWindow
            h.overlays.present(
                PopoverEntry(
                    Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    title = inCallStrings.routeTitle,
                ) {
                    RoutePickerContent(ui.routeRows, inCallStrings, {})
                },
            )
        }
    }

    @Test fun postCallBlockPill() =
        snap(
            "e5.7-post-call-block-pill",
            InCallHarness(liveSnapshot(liveCall(name = null, contactId = null, incoming = true, connectedAgoS = 8))).ui,
        ) { h ->
            h.overlays.showUndo(
                message = inCallStrings.blockMessage("+905559990000"),
                onUndo = {},
                actionLabel = inCallStrings.postCallBlockAction,
            )
        }

    @Test fun postCallAddNotePill() =
        snap("e5.7-post-call-add-note-pill", InCallHarness().ui) { h ->
            h.overlays.showUndo(
                message = "Call with Mert Kaya, 4:32",
                onUndo = {},
                actionLabel = inCallStrings.postCallAddNote,
            )
        }

    @Test fun miniPill() {
        for (dark in listOf(false, true)) {
            val harness = InCallShotHarness(InCallHarness().ui)
            composeFrame(dark = dark) {
                Box(Modifier.fillMaxSize().background(Color(0xFF8E8E93).copy(alpha = 0.15f))) {
                    MiniCallPill(
                        harness.ui,
                        inCallStrings,
                        harness.timerText,
                        visible = true,
                        onExpand = {},
                        onEnd = {},
                    )
                }
            }
            advance(900)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/e5.7-mini-pill-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }
}
