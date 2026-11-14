package dev.rovsti.contacts

import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.chrome.TabItem
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.text.UiText
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

    /** Settings "Find Duplicates" (E4.11): groups of contacts that look alike, with the merge review. */
    @Serializable
    data object Duplicates : ContactsRoute

    /** Settings "Clean Up" (E4.11): contacts without a number, a name, a photo or a group. */
    @Serializable
    data object Cleanup : ContactsRoute

    /** The Emergency Contacts page (E4.14, C43): up to five people in the user's order, each with a Call button. */
    @Serializable
    data object Emergency : ContactsRoute

    /** The Recently Added / Edited page (E4.14, C15, C45). */
    @Serializable
    data object Recent : ContactsRoute

    @Serializable
    data object WhatsAppSettings : ContactsRoute

    /** The Settings choice pages and the summary (E4.15). */
    @Serializable
    data object SettingsNameFormat : ContactsRoute

    @Serializable
    data object SettingsSortBy : ContactsRoute

    @Serializable
    data object SettingsSaveTo : ContactsRoute

    @Serializable
    data object SettingsSummary : ContactsRoute

    @Serializable
    data object SettingsReminderTime : ContactsRoute

    /**
     * The import page (E4.10): a vCard handed to the app by SEND or VIEW or picked in Settings ([uri] is the content
     * URI as text), or [paste] for "Paste Contact", which reads the clipboard when the page opens. With [scan] the
     * [uri] is a picture that may show a QR code; with [csv] it is a CSV document.
     */
    @Serializable
    data class Intake(
        val uri: String = "",
        val paste: Boolean = false,
        val scan: Boolean = false,
        val csv: Boolean = false,
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

    /** Developer mode's Logs page; [tag] preselects a tag (`sync` for "Sync Details"). */
    @Serializable
    data class Logs(
        val tag: String? = null,
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
fun chromeFor(
    route: ContactsRoute,
    text: (UiText) -> String,
): ChromeSpec =
    when (route) {
        ContactsRoute.ContactsList -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.tab_contacts)))
        }

        ContactsRoute.Favorites -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.tab_favorites)))
        }

        ContactsRoute.Groups -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.tab_groups)))
        }

        ContactsRoute.Settings -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.tab_settings)))
        }

        is ContactsRoute.ContactCard,
        is ContactsRoute.ContactEditor,
        ContactsRoute.Trash,
        is ContactsRoute.GroupPage,
        ContactsRoute.UpcomingBirthdays,
        ContactsRoute.Duplicates,
        ContactsRoute.Cleanup,
        ContactsRoute.Emergency,
        ContactsRoute.Recent,
        is ContactsRoute.Intake,
        -> {
            ChromeSpec(TabBarMode.Hidden)
        }

        ContactsRoute.WhatsAppSettings -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = "WhatsApp")
        }

        ContactsRoute.SettingsNameFormat -> {
            ChromeSpec(
                TabBarMode.Visible,
                largeTitle = text(UiText.Res(R.string.contacts_name_format)),
            )
        }

        ContactsRoute.SettingsSortBy -> {
            ChromeSpec(
                TabBarMode.Visible,
                largeTitle = text(UiText.Res(R.string.contacts_sort_by)),
            )
        }

        ContactsRoute.SettingsSaveTo -> {
            ChromeSpec(
                TabBarMode.Visible,
                largeTitle = text(UiText.Res(R.string.settings_save_to)),
            )
        }

        ContactsRoute.SettingsSummary -> {
            ChromeSpec(
                TabBarMode.Visible,
                largeTitle = text(UiText.Res(R.string.contacts_summary)),
            )
        }

        ContactsRoute.SettingsReminderTime -> {
            ChromeSpec(
                TabBarMode.Visible,
                largeTitle = text(UiText.Res(R.string.contacts_time)),
            )
        }

        ContactsRoute.Appearance -> {
            ChromeSpec(
                TabBarMode.Visible,
                largeTitle = text(UiText.Res(R.string.contacts_appearance)),
            )
        }

        ContactsRoute.Haptics -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.contacts_haptics)))
        }

        ContactsRoute.About -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.contacts_about)))
        }

        ContactsRoute.Licences -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.contacts_licences)))
        }

        is ContactsRoute.LicenceDetail -> {
            ChromeSpec(TabBarMode.Visible)
        }

        is ContactsRoute.Logs -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.contacts_logs)))
        }
    }

/**
 * [chromeFor] while the list may be in select mode: the Contacts tab folds the bar into its circle (the select toolbar
 * rides beside it), every other page is unchanged.
 */
fun chromeFor(
    route: ContactsRoute,
    selecting: Boolean,
    text: (UiText) -> String,
): ChromeSpec =
    chromeFor(route, text).let {
        if (selecting && route == ContactsRoute.ContactsList) it.copy(tabBar = TabBarMode.Folded) else it
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
