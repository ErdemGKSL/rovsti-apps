package dev.rovsti.phone

import android.content.Intent
import android.os.Bundle
import dev.rovsti.design.chrome.TabItem
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.transition.ChromeSpec
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabBarMode
import dev.rovsti.platform.system.Handoff
import dev.rovsti.platform.telephony.CallNotifications
import dev.rovsti.platform.telephony.DialIntents
import dev.rovsti.platform.wa.WaCallService
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/** Every destination of Rovsti Phone (ARCH 5.4). The four tab roots come first, in the GPUI app's tab order. */
@Serializable
sealed interface PhoneRoute {
    @Serializable
    data object Favorites : PhoneRoute

    @Serializable
    data object Recents : PhoneRoute

    @Serializable
    data object Contacts : PhoneRoute

    @Serializable
    data object Keypad : PhoneRoute

    /** Pushed from the gear on each tab root. */
    @Serializable
    data object Settings : PhoneRoute

    /** Hidden: seven taps on the version row of Settings open it (nothing links to it). */
    @Serializable
    data object Diagnostics : PhoneRoute

    /** The full-screen Incoming Call screen of the ringing call [callId] (E5.6). */
    @Serializable
    data class Incoming(
        val callId: Int,
    ) : PhoneRoute

    /** The full-screen In-Call screen (E5.7a). */
    @Serializable
    data object InCall : PhoneRoute

    /** Call statistics (E5.10), pushed from Settings. */
    @Serializable
    data object Stats : PhoneRoute

    @Serializable
    data object WhatsAppSettings : PhoneRoute

    // The common settings pages (shell CommonSettingsPage + the licence detail).
    @Serializable
    data object Appearance : PhoneRoute

    @Serializable
    data object Haptics : PhoneRoute

    @Serializable
    data object About : PhoneRoute

    @Serializable
    data object Licences : PhoneRoute

    @Serializable
    data class LicenceDetail(
        val id: String,
    ) : PhoneRoute

    // The pages Settings opens (E5.9): each is pushed with the glass back button and the full tab bar.
    @Serializable
    data object Blocked : PhoneRoute

    @Serializable
    data object QuickReplies : PhoneRoute

    @Serializable
    data object SpeedDialPage : PhoneRoute

    @Serializable
    data object SimChoices : PhoneRoute

    /** Call history: export, delete older than, delete all. */
    @Serializable
    data object Data : PhoneRoute

    @Serializable
    data object Backup : PhoneRoute

    @Serializable
    data object Notes : PhoneRoute
}

internal val FavoritesTab = Tab("favorites")
internal val RecentsTab = Tab("recents")
internal val ContactsTab = Tab("contacts")
internal val KeypadTab = Tab("keypad")

/** The tab order of `apps/phone/src/main.rs` (`TABS`). */
internal val PhoneTabs = listOf(FavoritesTab, RecentsTab, ContactsTab, KeypadTab)

internal val PhoneRoots: Map<Tab, PhoneRoute> =
    mapOf(
        FavoritesTab to PhoneRoute.Favorites,
        RecentsTab to PhoneRoute.Recents,
        ContactsTab to PhoneRoute.Contacts,
        KeypadTab to PhoneRoute.Keypad,
    )

/** The tab bar items; icons are the GPUI app's `TAB_ICONS` (star, clock, person.2, keypad grid). */
internal fun phoneTabItems(
    favorites: String,
    recents: String,
    contacts: String,
    keypad: String,
    /** Unseen missed calls: the badge on the Recents tab. */
    missed: Int = 0,
) = persistentListOf(
    TabItem(FavoritesTab.id, favorites, RovstiIcons.Star),
    TabItem(RecentsTab.id, recents, RovstiIcons.Clock, badge = missed),
    TabItem(ContactsTab.id, contacts, RovstiIcons.People),
    TabItem(KeypadTab.id, keypad, RovstiIcons.Keypad),
)

/**
 * The chrome of each route (pure, tested on the JVM by `PhoneRoutesTest`): the four roots keep the bar (the Keypad has
 * no large title: its number field is the title); Incoming and InCall take the whole screen with no bar and no navigation
 * chrome; Settings and its sub-pages fold the bar into the circle of the selected tab.
 */
fun chromeFor(route: PhoneRoute): ChromeSpec =
    when (route) {
        PhoneRoute.Favorites -> ChromeSpec(TabBarMode.Visible, largeTitle = "Favorites")
        PhoneRoute.Recents -> ChromeSpec(TabBarMode.Visible, largeTitle = "Recents")
        PhoneRoute.Contacts -> ChromeSpec(TabBarMode.Visible, largeTitle = "Contacts")
        PhoneRoute.Keypad -> ChromeSpec(TabBarMode.Visible)
        is PhoneRoute.Incoming, PhoneRoute.InCall -> ChromeSpec(TabBarMode.Hidden)
        PhoneRoute.Settings -> ChromeSpec(TabBarMode.Visible, largeTitle = "Settings")
        PhoneRoute.Diagnostics -> ChromeSpec(TabBarMode.Visible, largeTitle = "Diagnostics")
        PhoneRoute.Stats -> ChromeSpec(TabBarMode.Visible, largeTitle = "Call statistics")
        PhoneRoute.WhatsAppSettings -> ChromeSpec(TabBarMode.Visible, largeTitle = "WhatsApp")
        PhoneRoute.Appearance -> ChromeSpec(TabBarMode.Visible, largeTitle = "Appearance")
        PhoneRoute.Haptics -> ChromeSpec(TabBarMode.Visible, largeTitle = "Haptics")
        PhoneRoute.About -> ChromeSpec(TabBarMode.Visible, largeTitle = "About")
        PhoneRoute.Licences -> ChromeSpec(TabBarMode.Visible, largeTitle = "Licences")
        is PhoneRoute.LicenceDetail -> ChromeSpec(TabBarMode.Visible)
        PhoneRoute.Blocked -> ChromeSpec(TabBarMode.Visible, largeTitle = "Blocked numbers")
        PhoneRoute.QuickReplies -> ChromeSpec(TabBarMode.Visible, largeTitle = "Quick replies")
        PhoneRoute.SpeedDialPage -> ChromeSpec(TabBarMode.Visible, largeTitle = "Speed dial")
        PhoneRoute.SimChoices -> ChromeSpec(TabBarMode.Visible, largeTitle = "SIM choices")
        PhoneRoute.Data -> ChromeSpec(TabBarMode.Visible, largeTitle = "Call history")
        PhoneRoute.Backup -> ChromeSpec(TabBarMode.Visible, largeTitle = "Backup and restore")
        PhoneRoute.Notes -> ChromeSpec(TabBarMode.Visible, largeTitle = "Notes")
    }

/** [chromeFor] while Recents is in select mode: the tab bar folds beside the select toolbar (tab roots only). */
fun chromeFor(
    route: PhoneRoute,
    selecting: Boolean,
): ChromeSpec =
    chromeFor(route).let {
        if (selecting && route == PhoneRoute.Recents) it.copy(tabBar = TabBarMode.Folded) else it
    }

/** What an intent asks of the app, as plain data (so [phoneLinkFor] is tested without an activity). */
data class PhoneLink(
    val tab: Tab,
    val routes: List<PhoneRoute>,
    /** The raw `tel:` URI to decode with `dial.fromTelUri` and show in the Keypad ("tel:" is an empty field). */
    val telUri: String? = null,
    /** The JID of a WhatsApp call hand-off from a sibling app. */
    val handoffJid: String? = null,
    /** A ringing call the notification's Answer asked to answer, honoured once (story 5.3). */
    val answerCallId: Int? = null,
    val answerToken: Long = 0L,
    /** The call whose Message action was tapped: the Incoming screen opens with the reply sheet (story 18.7). */
    val replyCallId: Int? = null,
) {
    val route: Pair<Tab, List<PhoneRoute>> get() = tab to routes
}

/**
 * The deep link an intent carries, as a pure function. [action] and [dataUri] are the intent's, [extras] its extras and
 * [liveCall] whether `CallRegistry` holds a call.
 *
 * - DIAL and VIEW of `tel:` (the `#` and `,` of USSD codes arrive escaped, `dial.fromTelUri` decodes them) open the
 *   Keypad on that number; a DIAL without data opens it empty (`DialIntents.forwardedUri`);
 * - the call notifications' intents open Incoming (Show, Answer, Message) with the call id;
 * - the WhatsApp hand-off (`dev.rovsti.action.WA_CALL` through `.WaHandoff`) opens the Keypad; the number is dialled over WhatsApp
 *   (`phone.wa_handoff_number` decides the number);
 * - a plain launch while a call is live (the ongoing notification's tap) opens the In-Call screen, and so does the
 *   ongoing WhatsApp call notification's tap (`WaCallService.EXTRA_OPEN_CALL`).
 *
 * Calls open on the Recents tab (they belong to its history), numbers on the Keypad. Anything else leaves the stacks
 * alone (null).
 */
fun phoneLinkFor(
    action: String?,
    dataUri: String?,
    extras: Bundle?,
    liveCall: Boolean = false,
): PhoneLink? {
    val showId = extras?.intIdOrNull(CallNotifications.EXTRA_CALL_ID)
    val answerId = extras?.intIdOrNull(CallNotifications.EXTRA_ANSWER_CALL_ID)
    val replyId = extras?.intIdOrNull(CallNotifications.EXTRA_REPLY_CALL_ID)
    val token = extras?.getLong(CallNotifications.EXTRA_ANSWER_TOKEN, 0L) ?: 0L
    return when {
        answerId != null -> {
            PhoneLink(
                RecentsTab,
                listOf(PhoneRoute.Incoming(answerId)),
                answerCallId = answerId,
                answerToken = token,
            )
        }

        replyId != null -> {
            PhoneLink(RecentsTab, listOf(PhoneRoute.Incoming(replyId)), replyCallId = replyId)
        }

        action == CallNotifications.ACTION_SHOW_INCOMING && showId != null -> {
            PhoneLink(RecentsTab, listOf(PhoneRoute.Incoming(showId)))
        }

        action == Handoff.ACTION_WA_CALL -> {
            val jid = extras?.getString(Handoff.EXTRA_JID)?.takeIf { it.isNotBlank() }
            jid?.let { PhoneLink(KeypadTab, emptyList(), handoffJid = it) }
        }

        action == Intent.ACTION_DIAL || (action == Intent.ACTION_VIEW && dataUri.isTel()) -> {
            val uri = DialIntents.forwardedUri(Intent.ACTION_DIAL, dataUri.takeIf { it.isTel() })
            PhoneLink(KeypadTab, emptyList(), telUri = uri)
        }

        // The ongoing WhatsApp call notification's body: back to that call.
        !extras?.getString(WaCallService.EXTRA_OPEN_CALL).isNullOrEmpty() -> {
            PhoneLink(RecentsTab, listOf(PhoneRoute.InCall))
        }

        liveCall && (action == null || action == Intent.ACTION_MAIN) -> {
            PhoneLink(RecentsTab, listOf(PhoneRoute.InCall))
        }

        else -> {
            null
        }
    }
}

private fun String?.isTel(): Boolean = this != null && length >= 4 && substring(0, 4).equals("tel:", ignoreCase = true)

/** A call-id extra; a missing or negative value is no id. */
private fun Bundle.intIdOrNull(key: String): Int? = if (containsKey(key)) getInt(key, -1).takeIf { it >= 0 } else null

/** The extras a link uses once: a rotation or a restart must not answer, reply or open the same call again. */
fun Intent.consumePhoneExtras() {
    removeExtra(CallNotifications.EXTRA_ANSWER_CALL_ID)
    removeExtra(CallNotifications.EXTRA_ANSWER_TOKEN)
    removeExtra(CallNotifications.EXTRA_REPLY_CALL_ID)
    removeExtra(Handoff.EXTRA_JID)
    removeExtra(Handoff.EXTRA_NAME)
    removeExtra(WaCallService.EXTRA_OPEN_CALL)
}
