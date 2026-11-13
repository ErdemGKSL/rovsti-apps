package dev.rovsti.phone

import android.content.Intent
import android.os.Bundle
import dev.rovsti.design.transition.TabBarMode
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.system.Handoff
import dev.rovsti.platform.telephony.CallNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E5.1: the tabs, the chrome of each route and the deep links an intent carries (pure functions, no activity). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneRoutesTest {
    private fun extras(block: Bundle.() -> Unit) = Bundle().apply(block)

    // ---- tabs and chrome ------------------------------------------------------------------------------------------

    @Test fun theTabsAreInTheGpuiOrder() {
        assertEquals(listOf("favorites", "recents", "contacts", "keypad"), PhoneTabs.map { it.id })
        assertEquals(
            listOf(PhoneRoute.Favorites, PhoneRoute.Recents, PhoneRoute.Contacts, PhoneRoute.Keypad),
            PhoneTabs.map { PhoneRoots.getValue(it) },
        )
        val items = phoneTabItems("Favorites", "Recents", "Contacts", "Keypad")
        assertEquals(listOf("Favorites", "Recents", "Contacts", "Keypad"), items.map { it.label })
        assertEquals(PhoneTabs.map { it.id }, items.map { it.id })
    }

    @Test fun rootsKeepTheBarAndTheKeypadHasNoLargeTitle() {
        for (root in listOf(PhoneRoute.Favorites, PhoneRoute.Recents, PhoneRoute.Contacts)) {
            val spec = chromeFor(root)
            assertEquals(root.toString(), TabBarMode.Visible, spec.tabBar)
            assertNotNull(root.toString(), spec.largeTitle)
        }
        assertEquals(TabBarMode.Visible, chromeFor(PhoneRoute.Keypad).tabBar)
        assertNull(chromeFor(PhoneRoute.Keypad).largeTitle)
    }

    @Test fun callScreensTakeTheWholeScreen() {
        for (route in listOf(PhoneRoute.Incoming(3), PhoneRoute.InCall)) {
            val spec = chromeFor(route)
            assertEquals(route.toString(), TabBarMode.Hidden, spec.tabBar)
            assertNull(route.toString(), spec.largeTitle)
        }
    }

    @Test fun settingsAndItsPagesKeepTheFullBar() {
        for (
        route in
        listOf(
            PhoneRoute.Settings,
            PhoneRoute.Diagnostics,
            PhoneRoute.Stats,
            PhoneRoute.WhatsAppSettings,
            PhoneRoute.Appearance,
            PhoneRoute.Haptics,
            PhoneRoute.About,
            PhoneRoute.Licences,
            PhoneRoute.LicenceDetail("x"),
            PhoneRoute.Blocked,
            PhoneRoute.QuickReplies,
            PhoneRoute.SpeedDialPage,
            PhoneRoute.SimChoices,
            PhoneRoute.Data,
            PhoneRoute.Backup,
            PhoneRoute.Stats,
            PhoneRoute.Notes,
        )
        ) {
            assertEquals(route.toString(), TabBarMode.Visible, chromeFor(route).tabBar)
        }
    }

    @Test fun theSettingsPagesAreTitledAndNeverTabRoots() {
        val pages =
            listOf(
                PhoneRoute.Blocked to "Blocked numbers",
                PhoneRoute.QuickReplies to "Quick replies",
                PhoneRoute.SpeedDialPage to "Speed dial",
                PhoneRoute.SimChoices to "SIM choices",
                PhoneRoute.Data to "Call history",
                PhoneRoute.Backup to "Backup and restore",
                PhoneRoute.Stats to "Call statistics",
                PhoneRoute.Notes to "Notes",
            )
        for ((route, title) in pages) {
            assertEquals(route.toString(), title, chromeFor(route).largeTitle)
            assertFalse(route.toString(), route in PhoneRoots.values)
        }
    }

    @Test fun theNewRoutesSurviveASaveAndRestore() {
        val all =
            listOf(
                PhoneRoute.Blocked,
                PhoneRoute.QuickReplies,
                PhoneRoute.SpeedDialPage,
                PhoneRoute.SimChoices,
                PhoneRoute.Data,
                PhoneRoute.Backup,
                PhoneRoute.Stats,
                PhoneRoute.Notes,
            )
        val json = kotlinx.serialization.json.Json
        for (route in all) {
            val text = json.encodeToString(PhoneRoute.serializer(), route)
            assertEquals(route, json.decodeFromString(PhoneRoute.serializer(), text))
        }
    }

    /** Folded is for tab roots only: no pushed phone route maps to it. */
    @Test fun noNonRootRouteMapsToFolded() {
        val roots = PhoneRoots.values.toSet()
        val pushed =
            listOf(
                PhoneRoute.Settings,
                PhoneRoute.Diagnostics,
                PhoneRoute.Stats,
                PhoneRoute.WhatsAppSettings,
                PhoneRoute.Appearance,
                PhoneRoute.Haptics,
                PhoneRoute.About,
                PhoneRoute.Licences,
                PhoneRoute.LicenceDetail("x"),
                PhoneRoute.Blocked,
                PhoneRoute.QuickReplies,
                PhoneRoute.SpeedDialPage,
                PhoneRoute.SimChoices,
                PhoneRoute.Data,
                PhoneRoute.Backup,
                PhoneRoute.Stats,
                PhoneRoute.Notes,
            )
        for (route in pushed) {
            if (route in roots) continue
            assertNotEquals(route.toString(), TabBarMode.Folded, chromeFor(route).tabBar)
        }
    }

    @Test fun theNavigatorStartsOnRecentsLikeTheFirstTab() {
        val navigator = TabNavigator(PhoneTabs, PhoneRoots)
        assertEquals(PhoneRoute.Favorites, navigator.current)
        navigator.select(KeypadTab)
        assertEquals(PhoneRoute.Keypad, navigator.current)
    }

    // ---- dial links -----------------------------------------------------------------------------------------------

    @Test fun dialWithANumberOpensTheKeypadOnIt() {
        val link = phoneLinkFor(Intent.ACTION_DIAL, "tel:+905551112233", null)
        assertEquals(KeypadTab to emptyList<PhoneRoute>(), link?.route)
        assertEquals("tel:+905551112233", link?.telUri)
    }

    @Test fun dialWithoutDataOpensAnEmptyKeypad() {
        val link = phoneLinkFor(Intent.ACTION_DIAL, null, null)
        assertEquals("tel:", link?.telUri)
        assertEquals(KeypadTab, link?.tab)
    }

    @Test fun viewOfATelLinkKeepsTheEscapedUssdCharactersForTheCore() {
        // `#` arrives as %23 and `,` as %2C; dial.fromTelUri decodes them, the route code must not touch the text.
        assertEquals("tel:*21%23", phoneLinkFor(Intent.ACTION_VIEW, "tel:*21%23", null)?.telUri)
        assertEquals("tel:123%2C45", phoneLinkFor(Intent.ACTION_VIEW, "tel:123%2C45", null)?.telUri)
        assertEquals("TEL:1,2;3", phoneLinkFor(Intent.ACTION_VIEW, "TEL:1,2;3", null)?.telUri)
    }

    @Test fun viewOfAnythingElseLeavesTheStacksAlone() {
        assertNull(phoneLinkFor(Intent.ACTION_VIEW, "https://example.org", null))
        assertNull(phoneLinkFor(Intent.ACTION_VIEW, null, null))
        assertNull(phoneLinkFor(Intent.ACTION_MAIN, null, null))
        assertNull(phoneLinkFor(null, null, null))
    }

    // ---- notification intents -------------------------------------------------------------------------------------

    @Test fun theIncomingNotificationOpensTheIncomingScreen() {
        val link =
            phoneLinkFor(
                CallNotifications.ACTION_SHOW_INCOMING,
                null,
                extras { putInt(CallNotifications.EXTRA_CALL_ID, 7) },
            )
        assertEquals(RecentsTab to listOf<PhoneRoute>(PhoneRoute.Incoming(7)), link?.route)
        assertNull("showing is not answering", link?.answerCallId)
    }

    @Test fun answerCarriesTheIdAndTheTokenAndOpensTheCall() {
        val link =
            phoneLinkFor(
                CallNotifications.ACTION_ANSWER,
                null,
                extras {
                    putInt(CallNotifications.EXTRA_ANSWER_CALL_ID, 4)
                    putLong(CallNotifications.EXTRA_ANSWER_TOKEN, 99L)
                },
            )
        assertEquals(4, link?.answerCallId)
        assertEquals(99L, link?.answerToken)
        assertEquals(listOf<PhoneRoute>(PhoneRoute.Incoming(4)), link?.routes)
    }

    @Test fun messageOpensIncomingWithTheReplySheet() {
        val link =
            phoneLinkFor(
                CallNotifications.ACTION_MESSAGE,
                null,
                extras { putInt(CallNotifications.EXTRA_REPLY_CALL_ID, 5) },
            )
        assertEquals(5, link?.replyCallId)
        assertEquals(listOf<PhoneRoute>(PhoneRoute.Incoming(5)), link?.routes)
        assertNull(link?.answerCallId)
    }

    @Test fun aNegativeOrMissingIdIsNoLink() {
        assertNull(
            phoneLinkFor(
                CallNotifications.ACTION_SHOW_INCOMING,
                null,
                extras { putInt(CallNotifications.EXTRA_CALL_ID, -1) },
            ),
        )
        assertNull(phoneLinkFor(CallNotifications.ACTION_SHOW_INCOMING, null, extras {}))
        assertNull(
            phoneLinkFor(
                CallNotifications.ACTION_ANSWER,
                null,
                extras {
                    putInt(CallNotifications.EXTRA_ANSWER_CALL_ID, -3)
                },
            ),
        )
    }

    @Test fun aPlainLaunchWhileACallIsLiveOpensTheCallScreen() {
        assertEquals(
            listOf<PhoneRoute>(PhoneRoute.InCall),
            phoneLinkFor(Intent.ACTION_MAIN, null, null, liveCall = true)?.routes,
        )
        assertEquals(listOf<PhoneRoute>(PhoneRoute.InCall), phoneLinkFor(null, null, null, liveCall = true)?.routes)
        assertNull("no call, no link", phoneLinkFor(Intent.ACTION_MAIN, null, null, liveCall = false))
    }

    @Test fun aDialLinkBeatsALiveCall() {
        val link = phoneLinkFor(Intent.ACTION_DIAL, "tel:123", null, liveCall = true)
        assertEquals(KeypadTab, link?.tab)
    }

    // ---- the WhatsApp hand-off ------------------------------------------------------------------------------------

    @Test fun theWhatsAppHandoffOpensTheKeypadAndCarriesTheJid() {
        val link =
            phoneLinkFor(
                Handoff.ACTION_WA_CALL,
                null,
                extras { putString(Handoff.EXTRA_JID, "905551234567@s.whatsapp.net") },
            )
        assertEquals(KeypadTab, link?.tab)
        assertEquals("905551234567@s.whatsapp.net", link?.handoffJid)
        assertNull(link?.telUri)
    }

    @Test fun aHandoffWithoutAJidIsNoLink() {
        assertNull(phoneLinkFor(Handoff.ACTION_WA_CALL, null, extras {}))
        assertNull(phoneLinkFor(Handoff.ACTION_WA_CALL, null, extras { putString(Handoff.EXTRA_JID, " ") }))
    }

    // ---- one-shot extras ------------------------------------------------------------------------------------------

    @Test fun theExtrasAreUsedOnce() {
        val intent =
            Intent(CallNotifications.ACTION_ANSWER)
                .putExtra(CallNotifications.EXTRA_ANSWER_CALL_ID, 4)
                .putExtra(CallNotifications.EXTRA_ANSWER_TOKEN, 99L)
                .putExtra(CallNotifications.EXTRA_REPLY_CALL_ID, 4)
                .putExtra(Handoff.EXTRA_JID, "1@s.whatsapp.net")
                .putExtra(Handoff.EXTRA_NAME, "A")
                .putExtra(CallNotifications.EXTRA_CALL_ID, 4)
        assertNotNull(phoneLinkFor(intent.action, intent.dataString, intent.extras))
        intent.consumePhoneExtras()
        val again = phoneLinkFor(intent.action, intent.dataString, intent.extras)
        // The Answer and Message ids are gone: a rotation cannot answer or reply again.
        assertNull(again?.answerCallId)
        assertNull(again?.replyCallId)
        assertFalse(intent.hasExtra(CallNotifications.EXTRA_ANSWER_CALL_ID))
        assertFalse(intent.hasExtra(CallNotifications.EXTRA_ANSWER_TOKEN))
        assertFalse(intent.hasExtra(Handoff.EXTRA_JID))
        assertTrue("the call id of Show stays: it is not an action", intent.hasExtra(CallNotifications.EXTRA_CALL_ID))
    }
}
