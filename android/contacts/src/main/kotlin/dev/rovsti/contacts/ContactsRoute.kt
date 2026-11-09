package dev.rovsti.contacts

import android.content.Intent
import android.os.Bundle
import dev.rovsti.design.chrome.TabItem
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.transition.ChromeSpec
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabBarMode
import dev.rovsti.platform.contacts.Shortcuts
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/** What a new contact starts from (a typed number, an e-mail, a name); the editor (E4.4) builds the draft from it. */
@Serializable
data class DraftSeed(
    val name: String = "",
    val number: String = "",
    val email: String = "",
)

/** Every destination of Rovsti Contacts (ARCH 5.4). The four tab roots come first. */
@Serializable
sealed interface ContactsRoute {
    @Serializable
    data object ContactsList : ContactsRoute

    @Serializable
    data object Favorites : ContactsRoute

    @Serializable
    data object Groups : ContactsRoute

    @Serializable
    data object Settings : ContactsRoute

    @Serializable
    data class ContactCard(
        val id: Long,
    ) : ContactsRoute

    /** [id] null creates a contact; [seed] pre-fills a new one. */
    @Serializable
    data class ContactEditor(
        val id: Long? = null,
        val seed: DraftSeed? = null,
    ) : ContactsRoute

    @Serializable
    data object Trash : ContactsRoute

    @Serializable
    data class GroupPage(
        val id: Long,
    ) : ContactsRoute

    @Serializable
    data object UpcomingBirthdays : ContactsRoute

    @Serializable
    data object WhatsAppSettings : ContactsRoute

    /** A vCard handed to the app by SEND or VIEW; [uri] is the content URI as text. The page itself is E4.10. */
    @Serializable
    data class Intake(
        val uri: String,
    ) : ContactsRoute

    // The common settings pages (shell CommonSettingsPage + the licence detail).
    @Serializable
    data object Appearance : ContactsRoute

    @Serializable
    data object Haptics : ContactsRoute

    @Serializable
    data object About : ContactsRoute

    @Serializable
    data object Licences : ContactsRoute

    @Serializable
    data class LicenceDetail(
        val id: String,
    ) : ContactsRoute
}

internal val ContactsTab = Tab("contacts")
internal val FavoritesTab = Tab("favorites")
internal val GroupsTab = Tab("groups")
internal val SettingsTab = Tab("settings")
internal val ContactsTabs = listOf(ContactsTab, FavoritesTab, GroupsTab, SettingsTab)

internal val ContactsRoots: Map<Tab, ContactsRoute> =
    mapOf(
        ContactsTab to ContactsRoute.ContactsList,
        FavoritesTab to ContactsRoute.Favorites,
        GroupsTab to ContactsRoute.Groups,
        SettingsTab to ContactsRoute.Settings,
    )

/** The tab bar items; the titles are the GPUI app's `TABS` (apps/contacts/src/main.rs). */
internal fun contactsTabItems(
    contacts: String,
    favorites: String,
    groups: String,
    settings: String,
) = persistentListOf(
    TabItem(ContactsTab.id, contacts, RovstiIcons.People),
    TabItem(FavoritesTab.id, favorites, RovstiIcons.Star),
    TabItem(GroupsTab.id, groups, RovstiIcons.Folder),
    TabItem(SettingsTab.id, settings, RovstiIcons.Gear),
)

/**
 * The chrome of each route (pure, tested on the JVM by `ContactsRoutesTest`): the tab roots keep the bar and a large
 * title; the card, the editor, the trash and the other pages that take the whole screen hide it; the settings sub-pages
 * fold it into the circle of the selected tab.
 */
fun chromeFor(route: ContactsRoute): ChromeSpec =
    when (route) {
        ContactsRoute.ContactsList -> ChromeSpec(TabBarMode.Visible, largeTitle = "Contacts")

        ContactsRoute.Favorites -> ChromeSpec(TabBarMode.Visible, largeTitle = "Favorites")

        ContactsRoute.Groups -> ChromeSpec(TabBarMode.Visible, largeTitle = "Groups")

        ContactsRoute.Settings -> ChromeSpec(TabBarMode.Visible, largeTitle = "Settings")

        is ContactsRoute.ContactCard,
        is ContactsRoute.ContactEditor,
        ContactsRoute.Trash,
        is ContactsRoute.GroupPage,
        ContactsRoute.UpcomingBirthdays,
        is ContactsRoute.Intake,
        -> ChromeSpec(TabBarMode.Hidden)

        ContactsRoute.WhatsAppSettings -> ChromeSpec(TabBarMode.Folded, largeTitle = "WhatsApp")

        ContactsRoute.Appearance -> ChromeSpec(TabBarMode.Folded, largeTitle = "Appearance")

        ContactsRoute.Haptics -> ChromeSpec(TabBarMode.Folded, largeTitle = "Haptics")

        ContactsRoute.About -> ChromeSpec(TabBarMode.Folded, largeTitle = "About")

        ContactsRoute.Licences -> ChromeSpec(TabBarMode.Folded, largeTitle = "Licences")

        is ContactsRoute.LicenceDetail -> ChromeSpec(TabBarMode.Folded)
    }

/**
 * The deep link an intent carries (AD-CT8), as a pure function so it is tested without an activity. [open] is the
 * `open_contact_id` extra shared by birthday notifications and pinned shortcuts; [streamUri] is `EXTRA_STREAM` of a
 * SEND and [dataUri] the data of a VIEW. A contact link wins over a vCard. Anything else leaves the stacks alone.
 */
fun contactsRouteFor(
    action: String?,
    dataUri: String?,
    streamUri: String?,
    extras: Bundle?,
): Pair<Tab, List<ContactsRoute>>? {
    val contact = contactIdOf(extras)
    if (contact != null) {
        return ContactsTab to listOf(ContactsRoute.ContactCard(contact))
    }
    val vcard =
        when (action) {
            Intent.ACTION_SEND -> streamUri
            Intent.ACTION_VIEW -> dataUri
            else -> null
        }?.takeIf { it.isNotBlank() }
    return vcard?.let { ContactsTab to listOf(ContactsRoute.Intake(it)) }
}

/** The contact id of the extra; a missing, non-numeric or non-positive value is no link. */
private fun contactIdOf(extras: Bundle?): Long? {
    if (extras == null || !extras.containsKey(Shortcuts.EXTRA_CONTACT_ID)) return null
    @Suppress("DEPRECATION")
    val raw = extras.get(Shortcuts.EXTRA_CONTACT_ID)
    return (raw as? Long ?: (raw as? Int)?.toLong())?.takeIf { it > 0 }
}
