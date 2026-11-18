package dev.rosty.messages.settings

import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.PrefsEditAnswer
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.settings.notify.MutedRow
import dev.rosty.messages.settings.notify.NotificationSettingsAction
import dev.rosty.messages.settings.notify.NotificationSettingsViewModel
import dev.rosty.messages.settings.notify.WaChatMute
import dev.rosty.messages.settings.notify.WaMutes
import dev.rosty.messages.spam.SpamCore
import dev.rosty.messages.spam.prefsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E6.12: the Notifications page drives the switches E6.9's notifier reads and lists the mutes still running. Round 7a:
 * the WhatsApp chats muted in WhatsApp are listed while the vault is open, one unlock row stands for them while it is
 * locked, and Unmute of a WhatsApp chat goes through the chat action.
 */
class NotificationSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private val waMutes = MutableStateFlow<WaMutes>(WaMutes.Hidden)
    private val unmuted = mutableListOf<String>()
    private var unlocks = 0

    private fun TestScope.rig(): Pair<NotificationSettingsViewModel, SpamCore> {
        val prefs =
            prefsWith().copy(
                muted = listOf("sms:7", "wa:905550000001@s.whatsapp.net", "sms:9"),
                muteUntil = mapOf("sms:9" to 1_000L, "sms:7" to 9_000L),
            )
        val core = SpamCore(prefs)
        core.core.onTyped("messages.prefs_edit", PrefsEditRequest.serializer(), PrefsEditAnswer.serializer()) { req ->
            val next = req.prefs.copy(muted = req.prefs.muted - req.tag, muteUntil = req.prefs.muteUntil - req.tag)
            Outcome.Ok(PrefsEditAnswer(next, next != req.prefs, null))
        }
        val store = MessagesStore(core.ops, backgroundScope, io = dispatcher, debounceMs = 0)
        val names = mapOf("sms:7" to "Ayşe", "wa:$ADA" to "Ada")
        val model =
            NotificationSettingsViewModel(
                store,
                core.ops,
                { names[it] },
                now = { 5_000L },
                waMutes = waMutes,
                unmuteWa = { jid ->
                    unmuted += jid
                    true
                },
                unlockWa = { unlocks++ },
            )
        return model to core
    }

    @Test fun expiredMutesAreNotListedAndNamesAreLookedUp() =
        scope.runTest {
            val (model, _) = rig()
            advanceUntilIdle()
            val ui = model.state.value
            // A `wa:` entry left in the preferences by an earlier build is no WhatsApp mute (round 6).
            assertEquals(listOf(MutedRow("sms:7", "Ayşe")), ui.muted)
            assertFalse(ui.waLocked)
        }

    @Test fun whileUnlockedTheChatsMutedInWhatsAppAreListedAndUnmutedThere() =
        scope.runTest {
            val (model, core) = rig()
            waMutes.value =
                WaMutes.Open(
                    listOf(
                        WaChatMute(ADA, -1L),
                        WaChatMute(BOB, 9_000L),
                        WaChatMute(CEM, 4_000L),
                        WaChatMute(DOGA, null),
                    ),
                )
            advanceUntilIdle()
            assertEquals(
                listOf(MutedRow("sms:7", "Ayşe"), MutedRow("wa:$ADA", "Ada"), MutedRow("wa:$BOB", null)),
                model.state.value.muted,
            )
            val before = core.saved
            model.onAction(NotificationSettingsAction.Unmute("wa:$ADA"))
            advanceUntilIdle()
            assertEquals(listOf(ADA), unmuted)
            assertEquals("the SMS preferences are not touched", before, core.saved)
        }

    @Test fun whileLockedOneRowOffersTheUnlock() =
        scope.runTest {
            val (model, _) = rig()
            waMutes.value = WaMutes.Locked
            advanceUntilIdle()
            assertTrue(model.state.value.waLocked)
            assertEquals(listOf(MutedRow("sms:7", "Ayşe")), model.state.value.muted)
            model.onAction(NotificationSettingsAction.UnlockWhatsApp)
            advanceUntilIdle()
            assertEquals(1, unlocks)
            waMutes.value = WaMutes.Open(listOf(WaChatMute(ADA, -1L)))
            advanceUntilIdle()
            assertFalse(model.state.value.waLocked)
            assertEquals(
                MutedRow("wa:$ADA", "Ada"),
                model.state.value.muted
                    .last(),
            )
        }

    @Test fun theSwitchesAndUnmuteAreSaved() =
        scope.runTest {
            val (model, core) = rig()
            advanceUntilIdle()
            model.onAction(NotificationSettingsAction.SetNotifications(false))
            model.onAction(NotificationSettingsAction.SetOtpCopy(false))
            advanceUntilIdle()
            assertFalse(core.saved!!.notifications)
            assertFalse(core.saved!!.otpCopy)
            model.onAction(NotificationSettingsAction.Unmute("sms:7"))
            advanceUntilIdle()
            assertEquals(listOf("wa:905550000001@s.whatsapp.net", "sms:9"), core.saved!!.muted)
            assertEquals(
                emptyList<String>(),
                model.state.value.muted
                    .map { it.tag },
            )
        }

    private companion object {
        const val ADA = "100000000001@s.whatsapp.net"
        const val BOB = "100000000002@s.whatsapp.net"
        const val CEM = "100000000003@s.whatsapp.net"
        const val DOGA = "100000000004@s.whatsapp.net"
    }
}
