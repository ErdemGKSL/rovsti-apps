package dev.rovsti.phone.incoming

import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.phone.data.Sims
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.6 screenshots: the Incoming screen on the real shell (no tab bar), its sheet, the lock-screen variant, the card. */
@Config(sdk = [35])
class E56ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private val vodafone = SimAccount("tel/svc#2", "Vodafone", "Vodafone", "Vodafone", 0xFFFF453A.toInt(), 1, true)
    private val twoSims = Sims(visible = true, chips = persistentListOf(vodafone))

    private fun snap(
        name: String,
        ui: IncomingUi,
        fontScale: Float = 1f,
        inCall: Boolean = false,
        after: (IncomingShotHarness) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val harness = IncomingShotHarness(ui)
            composeFrame(dark = dark, fontScale = fontScale) { HostedIncoming(harness, inCall = inCall) }
            advance(500)
            compose.runOnUiThread { after(harness) }
            advance(1_400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private fun ringingUi(
        call: dev.rovsti.core.model.phone.WireCall = ringing(account = "tel/svc#2"),
        locked: Boolean = false,
    ) = IncomingHarness(ringingSnapshot(call, locked = locked), twoSims).ui

    @Test fun incoming() = snap("e5.6-incoming", ringingUi())

    @Test fun incomingUnknownNumberBigFont() =
        snap(
            "e5.6-incoming-fs2",
            ringingUi(ringing(name = null, number = "+49 30 1234567", account = null, contactId = null)),
            fontScale = 2f,
        )

    @Test fun lockScreenVariant() =
        snap(
            "e5.6-incoming-lockscreen-variant",
            ringingUi(ringing(name = null, number = "+905550001122", contactId = null, account = null), locked = true),
        )

    @Test fun declineWithMessageSheet() =
        snap("e5.6-decline-with-message-sheet", ringingUi()) { h ->
            IncomingSheets(h.overlays, incomingStrings) {}
                .replies(
                    IncomingEffect.ShowReplies(
                        1,
                        persistentListOf(
                            "Can't talk now. What's up?",
                            "I'll call you back.",
                            "I'm on my way.",
                            "Can I call you later?",
                        ),
                        160,
                    ),
                )
        }

    @Test fun remindSheet() =
        snap("e5.6-remind-sheet", ringingUi()) { h ->
            IncomingSheets(h.overlays, incomingStrings) {}
                .reminders(
                    IncomingEffect.ShowReminders(
                        FakeBackend().slots.let { s ->
                            persistentListOf(*s.toTypedArray())
                        },
                    ),
                )
        }

    @Test fun waitingCall() = snap("e5.6-waiting-call-card", IncomingHarness(waitingSnapshot()).ui, inCall = true)

    @Test fun waitingCallWithHeldCall() =
        snap("e5.6-waiting-call-card-held", IncomingHarness(waitingSnapshot(held = true)).ui, inCall = true)
}
