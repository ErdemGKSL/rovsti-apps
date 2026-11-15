package dev.rosty.stops.notice

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.platform.location.TripStatus
import dev.rosty.stops.AppFixture
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.SampleStops
import dev.rosty.stops.StopsRoute
import dev.rosty.stops.saved.settleRust
import dev.rosty.stops.trip.TripFixtures
import dev.rosty.stops.trip.TripTags
import dev.rosty.testing.ShotTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File

/** The arriving-soon message on the real app root and the real core: Settings, the picker, and the trip. */
@Config(sdk = [35])
class NoticeAppTest : ShotTest() {
    private fun fixture(
        prefs: StopsPrefs = StopsPrefs(),
        linked: Boolean = true,
    ): AppFixture {
        val core =
            FakeStopsCore(
                prefs = prefs,
                saved = SampleStops.saved,
                draft = Ends(TripFixtures.a, TripFixtures.b),
                real = HostCore.real(),
            )
        return AppFixture(core).also { f ->
            f.container.fakeWa.setChats(NoticeFixtures.listedByName)
            if (linked) f.container.fakeWa.setState(NoticeFixtures.connected)
        }
    }

    private fun click(tag: String) {
        compose
            .onNode(hasClickAction() and hasAnyDescendant(hasTestTag(tag)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        settleRust()
    }

    private fun tripFile(): File = File(HostCore.dataDir, "trip.json")

    /** The map sheet from peek to large, so the trip rows are on screen; then Start with every grant. */
    private fun startTrip(fixture: AppFixture) {
        composeFrame { fixture.Content() }
        settleRust()
        repeat(2) {
            compose.onNodeWithTag("sheet-grabber").performSemanticsAction(SemanticsActions.OnClick)
            settleRust(800)
        }
        TripFixtures.grant(fixture, *TripFixtures.foreground, *TripFixtures.notifications)
        settleRust()
    }

    @Test fun theSettingsRowsLeadToTheirPagesAndAChosenChatTurnsTheMessageOn() {
        val f = fixture()
        f.navigator.push(StopsRoute.Settings)
        composeFrame { f.Content() }
        settleRust()
        compose.onNodeWithText("Off", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Connected", useUnmergedTree = true).assertExists()
        click(NoticeTags.SettingsRow)
        assertEquals(StopsRoute.ArrivingSoon, f.navigator.current)
        compose.onNodeWithTag(NoticeTags.Page).assertExists()
        compose.onNodeWithText("None", useUnmergedTree = true).assertExists()
        // The picker: people by saved contact, then WhatsApp's name, then the number.
        click(NoticeTags.Chat)
        assertEquals(StopsRoute.ArrivingSoonChat, f.navigator.current)
        compose.onNodeWithText("Ayşe Demir", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Corner Bakery", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("+905550005566", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Group", useUnmergedTree = true).assertExists()
        click(NoticeTags.chat(NoticeFixtures.AYSE))
        // Choosing goes back to the page, which now names the chat.
        assertEquals(StopsRoute.ArrivingSoon, f.navigator.current)
        val notice =
            f.container.store.prefs.value.notice!!
        assertEquals(NoticeFixtures.AYSE, notice.chat)
        assertEquals("Ayşe Demir", notice.chatName)
        compose.onNodeWithText("Ayşe Demir", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(NoticeTags.TurnOff, useUnmergedTree = true).assertExists()
        // Ten minutes before the circle.
        click(
            NoticeTags.sendWhen(
                dev.rosty.core.model.stops.NoticeWhen
                    .MinutesBefore(10),
            ),
        )
        assertEquals(
            dev.rosty.core.model.stops.NoticeWhen
                .MinutesBefore(10),
            f.container.store.prefs.value.notice!!
                .sendWhen,
        )
    }

    @Test fun withoutALinkThePageOnlyLeadsToWhatsApp() {
        val f = fixture(StopsPrefs(notice = NoticeFixtures.notice), linked = false)
        f.navigator.push(StopsRoute.Settings)
        f.navigator.push(StopsRoute.ArrivingSoon)
        composeFrame { f.Content() }
        settleRust()
        compose.onNodeWithText("Not connected", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(NoticeTags.Template).assertDoesNotExist()
        click(NoticeTags.WhatsApp)
        assertEquals(StopsRoute.WhatsApp, f.navigator.current)
    }

    @Test fun aTripCarriesTheMessageAndTheCardSaysWhatBecameOfIt() {
        val f = fixture(StopsPrefs(notice = NoticeFixtures.notice))
        startTrip(f)
        compose.onNodeWithTag(TripNoticeTags.Toggle, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Text Ayşe Demir when arriving", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(TripTags.Start, useUnmergedTree = true).performClick()
        settleRust()
        assertEquals(1, f.container.fakeTrips.started.size)
        // The trip file the service reads carries the chat and the message.
        val trip = Json.parseToJsonElement(tripFile().readText()).jsonObject
        val stored = trip.getValue("notice").jsonObject
        assertEquals(NoticeFixtures.AYSE, stored.getValue("chat").jsonPrimitive.content)
        assertEquals(NoticeFixtures.notice.template, stored.getValue("text_template").jsonPrimitive.content)
        assertFalse(
            stored
                .getValue("sent")
                .jsonPrimitive.content
                .toBoolean(),
        )
        compose.onNodeWithText("Will text when arriving", useUnmergedTree = true).assertExists()
        // The service reports the send.
        f.container.fakeTrips.push(TripStatus.Fixed(TripFixtures.fix().copy(noticeSent = true)))
        settleRust()
        compose.onNodeWithText("Arrival message sent", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Will text when arriving", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun switchedOffForOneTripTheTripCarriesNoMessage() {
        val f = fixture(StopsPrefs(notice = NoticeFixtures.notice))
        startTrip(f)
        assertTrue(f.container.arrivingSoon.on.value)
        f.container.arrivingSoon.toggle()
        settleRust(300)
        compose.onNodeWithTag(TripTags.Start, useUnmergedTree = true).performClick()
        settleRust()
        assertEquals(1, f.container.fakeTrips.started.size)
        val trip = Json.parseToJsonElement(tripFile().readText()).jsonObject
        assertTrue(
            "no notice in the trip file",
            trip["notice"] == null || trip["notice"] is kotlinx.serialization.json.JsonNull,
        )
        compose.onNodeWithText("Will text when arriving", useUnmergedTree = true).assertDoesNotExist()
        // The settings still hold the message for the trips after it.
        assertEquals(
            NoticeFixtures.notice,
            f.container.store.prefs.value.notice,
        )
    }

    @Test fun noSwitchWithoutAChatOrALink() {
        val f = fixture(StopsPrefs(notice = NoticeFixtures.notice), linked = false)
        startTrip(f)
        compose.onNodeWithTag(TripNoticeTags.Toggle, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(TripTags.Start, useUnmergedTree = true).performClick()
        settleRust()
        val trip = Json.parseToJsonElement(tripFile().readText()).jsonObject
        assertTrue(trip["notice"] == null || trip["notice"] is kotlinx.serialization.json.JsonNull)
    }
}
