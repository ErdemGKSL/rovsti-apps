package dev.rovsti.phone

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.chat.ReminderSlotsRequest
import dev.rovsti.core.ops.NativeChatOps
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.phone.recents.HostedRecents
import dev.rovsti.phone.recents.RecentsHarness
import dev.rovsti.phone.recents.RecentsStrings
import dev.rovsti.phone.remind.RemindStrings
import dev.rovsti.phone.remind.menuAnchor
import dev.rovsti.phone.remind.presentRemindMenu
import dev.rovsti.phone.remind.remindMenuItems
import dev.rovsti.phone.setup.FlowFacts
import dev.rovsti.phone.setup.SetupAction
import dev.rovsti.phone.setup.SetupFlowRig
import dev.rovsti.phone.setup.SetupSettingsTags
import dev.rovsti.phone.setup.SetupSheetScreen
import dev.rovsti.phone.setup.SetupUiState
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * E5.12 screenshots on the real core: the remind-later menu over Recents, the Xiaomi setup rows, the revoked
 * full-screen-intent row, the Setup section of Settings and the Turkish sheet. Rows, words and states are the core's.
 */
@Config(sdk = [35])
class E512ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val host by lazy { HostRust("rovsti-e512") }

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    private fun sheet(
        name: String,
        state: SetupUiState,
        fontScale: Float = 1f,
    ) {
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark, fontScale = fontScale) { HostedPhone(h, state) }
            advance(500)
            compose.runOnUiThread {
                h.overlays.present(
                    SheetEntry(
                        "Setup",
                        persistentListOf(Detent.Medium, Detent.Large),
                        initialDetent = Detent.Large,
                    ) { _ -> SetupSheetScreen(state, onAction = {}) },
                )
            }
            advance(1_400)
            capture(name, dark)
        }
    }

    @Test fun remindLaterMenu() {
        val slots =
            runBlocking {
                (
                    NativeChatOps(host.core).reminderSlots(
                        ReminderSlotsRequest(NOW_MS, 0),
                    ) as dev.rovsti.core.Outcome.Ok
                ).value.slots
            }
        assertEquals(4, slots.size)
        for (dark in listOf(false, true)) {
            val h = RecentsHarness()
            val strings = RecentsStrings(RuntimeEnvironment.getApplication().resources)
            composeFrame(dark = dark) { HostedRecents(h, strings) }
            advance(500)
            val remind = RemindStrings(RuntimeEnvironment.getApplication().resources)
            compose.runOnUiThread {
                h.overlays.presentRemindMenu(
                    menuAnchor(
                        androidx.compose.ui.unit
                            .IntSize(1_080, 2_400),
                        0.42f,
                    ),
                    remindMenuItems(slots, remind.pickTime, onSlot = {}, onPickTime = {}),
                )
            }
            advance(1_400)
            capture("e5.12-remind-later-menu", dark)
        }
    }

    @Test fun xiaomiSetupRows() {
        val state = SetupFlowRig.state(host, FlowFacts(SetupFlowRig.xiaomiFacts, xiaomi = true))
        assertEquals(9, state.rows.size)
        sheet("e5.12-xiaomi-setup-rows", state)
    }

    @Test fun fullScreenIntentRevokedRow() {
        val state =
            SetupFlowRig.state(
                host,
                FlowFacts(SetupFlowRig.allGranted.copy(fullScreenAllowed = false), exact = false),
            )
        assertTrue(state.exactAlarmMissing)
        sheet("e5.12-full-screen-intent-revoked-row", state)
    }

    @Test fun settingsSetupSection() {
        val state =
            SetupFlowRig.state(
                host,
                FlowFacts(
                    SetupFlowRig.xiaomiFacts.copy(roleHeld = true, fullScreenAllowed = false),
                    xiaomi = true,
                    exact = false,
                ),
            )
        for (dark in listOf(false, true)) {
            val h = PhoneHarness()
            composeFrame(dark = dark) { HostedPhone(h, state, push = PhoneRoute.Settings) }
            advance(1_200)
            capture("e5.12-settings-setup-section", dark)
        }
    }

    @Test fun settingsRowsPressTheirButtons() {
        val state = SetupFlowRig.state(host, FlowFacts(SetupFlowRig.allGranted.copy(fullScreenAllowed = false)))
        val seen = mutableListOf<SetupAction>()
        composeFrame {
            PhoneSettingsPage(
                setup = state,
                settings = PhoneHarness().common,
                versionName = "0.1.0",
                links = PhoneHarness().settingsLinks(),
                onSetupAction = { seen += it },
            )
        }
        compose
            .onNode(
                hasTestTag(SetupSettingsTags.row(dev.rovsti.core.model.phone.SetupItem.FullScreenNotifications)),
                useUnmergedTree = true,
            ).performClick()
        val press = seen.single() as SetupAction.Press
        assertEquals(dev.rovsti.core.model.phone.SetupItem.FullScreenNotifications, press.item)
    }

    @Test fun setupTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        val state = SetupFlowRig.state(host, FlowFacts(SetupFlowRig.xiaomiFacts, xiaomi = true, exact = false))
        sheet("e5.12-setup-tr", state)
    }

    private companion object {
        const val NOW_MS = 1_792_000_000_000L
    }
}
