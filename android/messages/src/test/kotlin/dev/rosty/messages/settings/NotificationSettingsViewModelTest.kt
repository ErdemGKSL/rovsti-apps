package dev.rosty.messages.settings

import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.PrefsEditAnswer
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.settings.notify.MutedRow
import dev.rosty.messages.settings.notify.NotificationSettingsAction
import dev.rosty.messages.settings.notify.NotificationSettingsViewModel
import dev.rosty.messages.spam.SpamCore
import dev.rosty.messages.spam.prefsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** E6.12: the Notifications page drives the switches E6.9's notifier reads and lists the mutes still running. */
class NotificationSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

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
        val names = mapOf("sms:7" to "Ayşe")
        val model = NotificationSettingsViewModel(store, core.ops, { names[it] }, now = { 5_000L })
        return model to core
    }

    @Test fun expiredMutesAreNotListedAndNamesAreLookedUp() =
        scope.runTest {
            val (model, _) = rig()
            advanceUntilIdle()
            val ui = model.state.value
            assertEquals(
                listOf(MutedRow("sms:7", "Ayşe"), MutedRow("wa:905550000001@s.whatsapp.net", null)),
                ui.muted,
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
                listOf("wa:905550000001@s.whatsapp.net"),
                model.state.value.muted
                    .map { it.tag },
            )
        }
}
