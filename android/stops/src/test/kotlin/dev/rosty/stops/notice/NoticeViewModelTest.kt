package dev.rosty.stops.notice

import dev.rosty.core.model.stops.NoticeWhen
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.core.ops.NativeWaOps
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.data.StopsStore
import dev.rosty.wa.FakeWaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The arriving-soon settings on the real core (`prefs_edit`, `prefs_choices`, `notice_render`, `notice_validate`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wa = FakeWaRepository(NoticeFixtures.connected).apply { setChats(NoticeFixtures.chats) }

    @After fun tearDown() = scope.cancel()

    private fun model(
        prefs: StopsPrefs = StopsPrefs(),
        locale: String? = null,
    ): Pair<NoticeViewModel, FakeStopsCore> {
        val fake = FakeStopsCore(prefs = prefs, real = HostCore.real())
        val ops = NativeStopsOps(fake.rust) { locale }
        val waOps = NativeWaOps(HostCore.real())
        val model =
            NoticeViewModel(
                store = StopsStore(ops),
                ops = ops,
                wa = wa,
                names = { chats -> namedChats(chats, NoticeFixtures.profiles::get, waOps::names) },
                sampleStop = "Kadıköy Pier",
                scope = scope,
            )
        return model to fake
    }

    private fun NoticeViewModel.settled(check: (NoticeUiState) -> Boolean): NoticeUiState =
        runBlocking { withTimeout(TIMEOUT) { state.first { !it.loading && check(it) } } }

    @Test fun aNewUserSeesTheDefaultMessageItsPreviewAndTheChoices() {
        val (model, fake) = model()
        val state = model.settled { it.preview.isNotEmpty() && it.linkSettled }
        assertTrue(state.connected)
        assertFalse(state.on)
        assertEquals("Arriving at {stop} in about {min} min.", state.defaultTemplate)
        assertEquals(state.defaultTemplate, model.templateField.text.toString())
        assertEquals("Arriving at Kadıköy Pier in about 8 min.", state.preview)
        assertNull(state.problem)
        assertFalse(state.dirty)
        assertEquals(NoticeWhen.WithAlarm, state.sendWhen)
        assertEquals(
            listOf(
                NoticeWhen.WithAlarm,
                NoticeWhen.MinutesBefore(5),
                NoticeWhen.MinutesBefore(10),
                NoticeWhen.MinutesBefore(15),
            ),
            state.whenChoices.map { it.id },
        )
        assertEquals("When you cross the line", state.whenChoices[0].label)
        // Looking at the page writes nothing.
        assertTrue(fake.savedPrefs.isEmpty())
    }

    @Test fun inTurkishTheDefaultMessageAndTheChoicesAreTurkish() {
        val (model, _) = model(locale = "tr-TR")
        val state = model.settled { it.preview.isNotEmpty() }
        assertEquals("Yaklaşık {min} dk sonra varıyorum: {stop}.", state.defaultTemplate)
        assertEquals("Yaklaşık 8 dk sonra varıyorum: Kadıköy Pier.", state.preview)
        assertEquals(listOf("Çizgiyi geçtiğinizde", "Çembere 5 dk kala"), state.whenChoices.take(2).map { it.label })
    }

    @Test fun aMessageNeverRewrittenReadsInThePhonesLanguage() {
        // Saved while the phone was English, untouched since: in Turkish it reads (and goes out) as the Turkish default.
        val (model, fake) = model(StopsPrefs(notice = NoticeFixtures.notice), locale = "tr-TR")
        val state = model.settled { it.preview.isNotEmpty() }
        assertEquals("Yaklaşık {min} dk sonra varıyorum: {stop}.", state.saved)
        assertEquals(state.saved, model.templateField.text.toString())
        assertFalse(state.dirty)
        assertTrue(fake.savedPrefs.isEmpty())
        // A message the user wrote is theirs in every language.
        val own = NoticeFixtures.notice.copy(template = "On my way to {stop}")
        val (other, _) = model(StopsPrefs(notice = own), locale = "tr-TR")
        assertEquals("On my way to {stop}", other.settled { it.preview.isNotEmpty() }.saved)
    }

    @Test fun choosingAChatTurnsTheMessageOnWithTheNameThePickerShowed() {
        val (model, fake) = model()
        model.settled { it.linkSettled }
        model.openChats()
        val chats = model.settled { it.chats is NoticeChats.Loaded }.chats as NoticeChats.Loaded
        assertEquals(4, chats.all.size)
        model.chooseChat(chats.all.first { it.jid == NoticeFixtures.AYSE })
        val state = model.settled { it.on }
        assertEquals(NoticeFixtures.AYSE, state.chat)
        assertEquals("Ayşe Demir", state.chatName)
        val saved = fake.savedPrefs.last().notice!!
        assertEquals(NoticeFixtures.AYSE, saved.chat)
        assertEquals("Ayşe Demir", saved.chatName)
        assertEquals(NoticeWhen.WithAlarm, saved.sendWhen)
    }

    @Test fun theSearchNarrowsThePicker() {
        val (model, _) = model()
        model.openChats()
        model.settled { it.chats is NoticeChats.Loaded }
        model.searchField.edit { append("bak") }
        model.searchChanged()
        val shown = model.settled { (it.chats as? NoticeChats.Loaded)?.shown?.size == 1 }.chats as NoticeChats.Loaded
        assertEquals(NoticeFixtures.SHOP, shown.shown.single().jid)
        assertEquals(4, shown.all.size)
        model.closeChats()
    }

    @Test fun aMessageIsCheckedPreviewedAndSavedTrimmed() {
        val (model, fake) = model(StopsPrefs(notice = NoticeFixtures.notice))
        model.settled { it.preview.isNotEmpty() }
        model.templateField.edit { replace(0, length, "  Almost at {stop}!  ") }
        model.templateChanged()
        val edited = model.settled { it.dirty && it.preview == "Almost at Kadıköy Pier!" }
        assertNull(edited.problem)
        assertTrue(fake.savedPrefs.isEmpty())
        model.saveTemplate()
        val saved = model.settled { !it.dirty }
        assertEquals("Almost at {stop}!", saved.saved)
        assertEquals("Almost at {stop}!", model.templateField.text.toString())
        assertEquals(
            "Almost at {stop}!",
            fake.savedPrefs
                .last()
                .notice!!
                .template,
        )
        // The chat and the time are untouched.
        assertEquals(
            NoticeFixtures.AYSE,
            fake.savedPrefs
                .last()
                .notice!!
                .chat,
        )
        assertEquals(
            NoticeWhen.MinutesBefore(10),
            fake.savedPrefs
                .last()
                .notice!!
                .sendWhen,
        )
    }

    @Test fun anEmptyOrOverLongMessageIsRefusedWithItsReason() {
        val (model, fake) = model(StopsPrefs(notice = NoticeFixtures.notice))
        model.settled { it.preview.isNotEmpty() }
        model.templateField.edit { replace(0, length, "   ") }
        model.templateChanged()
        assertEquals(NoticeProblem.Empty, model.settled { it.problem != null }.problem)
        model.saveTemplate()
        model.templateField.edit { replace(0, length, "x".repeat(501)) }
        model.templateChanged()
        val long = model.settled { it.problem == NoticeProblem.TooLong }
        assertEquals(500, long.maxChars)
        model.saveTemplate()
        // Exactly the limit is fine.
        model.templateField.edit { replace(0, length, "y".repeat(500)) }
        model.templateChanged()
        model.settled { it.problem == null && it.dirty }
        assertTrue("nothing refused was written", fake.savedPrefs.isEmpty())
    }

    @Test fun theDefaultMessageComesBackWithOneTap() {
        val custom = NoticeFixtures.notice.copy(template = "On my way to {stop}")
        val (model, fake) = model(StopsPrefs(notice = custom))
        model.settled { it.saved == "On my way to {stop}" && it.defaultTemplate.isNotEmpty() }
        model.useDefault()
        val state = model.settled { it.saved == it.defaultTemplate }
        assertEquals("Arriving at {stop} in about {min} min.", model.templateField.text.toString())
        assertEquals(
            state.defaultTemplate,
            fake.savedPrefs
                .last()
                .notice!!
                .template,
        )
    }

    @Test fun whenItGoesOutIsOneEdit() {
        val (model, fake) = model(StopsPrefs(notice = NoticeFixtures.notice))
        model.settled { it.sendWhen == NoticeWhen.MinutesBefore(10) }
        model.setWhen(NoticeWhen.MinutesBefore(10))
        model.setWhen(NoticeWhen.WithAlarm)
        model.settled { it.sendWhen == NoticeWhen.WithAlarm }
        assertEquals(1, fake.savedPrefs.size)
        assertEquals(
            NoticeWhen.WithAlarm,
            fake.savedPrefs
                .last()
                .notice!!
                .sendWhen,
        )
    }

    @Test fun turningOffClearsTheMessageAndUndoPutsEverythingBack() {
        val custom = NoticeFixtures.notice.copy(template = "On my way to {stop}")
        val (model, fake) = model(StopsPrefs(notice = custom))
        model.settled { it.on }
        model.turnOff()
        val off = model.settled { !it.on }
        assertEquals("", off.chatName)
        assertNull(fake.savedPrefs.last().notice)
        val effect = runBlocking { withTimeout(TIMEOUT) { model.effects.first { it is NoticeEffect.Undo } } }
        (effect as NoticeEffect.Undo).undo()
        model.settled { it.on && it.saved == "On my way to {stop}" && it.sendWhen == NoticeWhen.MinutesBefore(10) }
        assertEquals(custom, fake.savedPrefs.last().notice)
    }

    @Test fun withoutALinkThePageSaysSo() {
        wa.setState(dev.rosty.wa.WaLinkState.None)
        val (model, _) = model(StopsPrefs(notice = NoticeFixtures.notice))
        val state = model.settled { it.linkSettled }
        assertFalse(state.connected)
        // The saved message is kept and shows again once connected.
        assertTrue(state.on)
        wa.setState(NoticeFixtures.connected)
        assertTrue(model.settled { it.connected }.on)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
