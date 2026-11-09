package dev.rovsti.contacts

import android.content.Intent
import android.os.Bundle
import dev.rovsti.design.transition.TabBarMode
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.contacts.Shortcuts
import dev.rovsti.platform.notify.BirthdayReminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactsRoutesTest {
    private fun extras(block: Bundle.() -> Unit) = Bundle().apply(block)

    @Test fun theBirthdayExtraOpensTheCard() {
        val route = contactsRouteFor(null, null, null, extras { putLong(BirthdayReminders.EXTRA_CONTACT_ID, 42L) })
        assertEquals(ContactsTab to listOf<ContactsRoute>(ContactsRoute.ContactCard(42L)), route)
    }

    @Test fun theShortcutExtraOpensTheCard() {
        val route = contactsRouteFor(null, null, null, extras { putLong(Shortcuts.EXTRA_CONTACT_ID, 7L) })
        assertEquals(ContactsTab to listOf<ContactsRoute>(ContactsRoute.ContactCard(7L)), route)
    }

    @Test fun aVcardSendOrViewOpensTheImportPage() {
        assertEquals(
            ContactsTab to listOf<ContactsRoute>(ContactsRoute.Intake("content://x/a.vcf")),
            contactsRouteFor(Intent.ACTION_SEND, null, "content://x/a.vcf", null),
        )
        assertEquals(
            ContactsTab to listOf<ContactsRoute>(ContactsRoute.Intake("content://x/b.vcf")),
            contactsRouteFor(Intent.ACTION_VIEW, "content://x/b.vcf", null, null),
        )
    }

    @Test fun aViewedVcardIsAFileSourceNotAPaste() {
        val (tab, routes) = contactsRouteFor(Intent.ACTION_VIEW, "content://x/c.vcf", null, null)!!
        val route = routes.single() as ContactsRoute.Intake
        assertEquals(ContactsTab, tab)
        assertEquals("content://x/c.vcf", route.uri)
        assertEquals("a link is never the paste source", false, route.paste)
        val nav = TabNavigator(ContactsTabs, ContactsRoots)
        nav.resetTo(tab, routes)
        assertEquals(listOf(ContactsRoute.ContactsList, route), nav.stack(ContactsTab))
        assertEquals(TabBarMode.Hidden, chromeFor(route).tabBar)
        assertEquals(TabBarMode.Hidden, chromeFor(ContactsRoute.Intake(paste = true)).tabBar)
    }

    @Test fun aContactLinkBeatsAVcard() {
        val route =
            contactsRouteFor(
                Intent.ACTION_VIEW,
                "content://x/b.vcf",
                null,
                extras { putLong(Shortcuts.EXTRA_CONTACT_ID, 3L) },
            )
        assertEquals(ContactsTab to listOf<ContactsRoute>(ContactsRoute.ContactCard(3L)), route)
    }

    @Test fun nothingOrJunkLeavesTheStacksAlone() {
        assertNull(contactsRouteFor(null, null, null, null))
        assertNull(contactsRouteFor(Intent.ACTION_MAIN, null, null, Bundle()))
        assertNull(contactsRouteFor(Intent.ACTION_SEND, null, null, null))
        assertNull(contactsRouteFor(Intent.ACTION_VIEW, "  ", null, null))
        assertNull(contactsRouteFor(null, null, null, extras { putString(Shortcuts.EXTRA_CONTACT_ID, "x") }))
        assertNull(contactsRouteFor(null, null, null, extras { putLong(Shortcuts.EXTRA_CONTACT_ID, 0L) }))
    }

    @Test fun theLinkResetsOnlyTheContactsTab() {
        val nav = TabNavigator(ContactsTabs, ContactsRoots)
        val (tab, routes) = contactsRouteFor(null, null, null, extras { putLong(Shortcuts.EXTRA_CONTACT_ID, 9L) })!!
        nav.resetTo(tab, routes)
        assertEquals(ContactsTab, nav.currentTab)
        assertEquals(listOf(ContactsRoute.ContactsList, ContactsRoute.ContactCard(9L)), nav.stack(ContactsTab))
        assertEquals(listOf<ContactsRoute>(ContactsRoute.Settings), nav.stack(SettingsTab))
    }

    @Test fun chromeOfEveryRoute() {
        fun mode(route: ContactsRoute) = chromeFor(route).tabBar
        for (root in ContactsRoots.values) {
            assertEquals("$root", TabBarMode.Visible, mode(root))
            assertNotNull("$root has a large title", chromeFor(root).largeTitle)
        }
        val hidden =
            listOf(
                ContactsRoute.ContactCard(1),
                ContactsRoute.ContactEditor(),
                ContactsRoute.ContactEditor(5, DraftSeed(number = "123")),
                ContactsRoute.Trash,
                ContactsRoute.GroupPage(2),
                ContactsRoute.UpcomingBirthdays,
                ContactsRoute.Duplicates,
                ContactsRoute.Cleanup,
                ContactsRoute.Intake("content://x"),
            )
        for (route in hidden) assertEquals("$route", TabBarMode.Hidden, mode(route))
        val folded =
            listOf(
                ContactsRoute.WhatsAppSettings,
                ContactsRoute.Appearance,
                ContactsRoute.Haptics,
                ContactsRoute.About,
                ContactsRoute.Licences,
                ContactsRoute.LicenceDetail("rovsti"),
            )
        for (route in folded) assertEquals("$route", TabBarMode.Folded, mode(route))
    }

    @Test fun everyRouteSurvivesSaveAndRestore() {
        val nav = TabNavigator(ContactsTabs, ContactsRoots)
        nav.push(ContactsRoute.ContactEditor(4, DraftSeed(name = "Ada", email = "a@b.c")))
        nav.select(SettingsTab)
        nav.push(ContactsRoute.LicenceDetail("convx"))
        val saver = TabNavigator.saver(ContactsTabs, ContactsRoots, ContactsRoute.serializer())
        val text =
            with(saver) {
                androidx.compose.runtime.saveable
                    .SaverScope { true }
                    .save(nav)
            }!!
        val back = saver.restore(text)!!
        assertEquals(SettingsTab, back.currentTab)
        assertEquals(nav.stack(ContactsTab), back.stack(ContactsTab))
        assertEquals(nav.stack(SettingsTab), back.stack(SettingsTab))
    }

    @Test fun tabsAreTheGpuiOnesInOrder() {
        assertEquals(listOf("contacts", "favorites", "groups", "settings"), ContactsTabs.map { it.id })
        assertEquals(
            listOf("Contacts", "Favorites", "Groups", "Settings"),
            contactsTabItems("Contacts", "Favorites", "Groups", "Settings").map { it.label },
        )
    }
}
