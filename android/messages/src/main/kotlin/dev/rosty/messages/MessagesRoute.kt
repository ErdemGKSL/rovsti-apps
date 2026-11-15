package dev.rosty.messages

import android.content.Intent
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.text.UiText
import dev.rosty.design.transition.ChromeSpec
import dev.rosty.design.transition.Tab
import dev.rosty.design.transition.TabBarMode
import dev.rosty.platform.system.Handoff
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/** Every destination of Rosty Messages (ARCH 5.4). The three tab roots come first, in the GPUI app's tab order. */
@Serializable
sealed interface MessagesRoute {
    /** The unified inbox: one row per person, SMS and WhatsApp merged (AD-MSG3). */
    @Serializable
    data object Chats : MessagesRoute

    /** The WhatsApp chats only, with the Status row. */
    @Serializable
    data object WhatsApp : MessagesRoute

    @Serializable
    data object Settings : MessagesRoute

    /** An SMS/MMS conversation. [threadId] 0 is a conversation not created yet, to [addresses]. */
    @Serializable
    data class SmsThread(
        val threadId: Long,
        val addresses: List<String> = emptyList(),
        val draft: String? = null,
        /** A message to scroll to and light up once loaded (a global search hit), and when it was sent. */
        val reveal: String? = null,
        val revealAtMs: Long? = null,
    ) : MessagesRoute

    /** A WhatsApp conversation. */
    @Serializable
    data class WaThread(
        val jid: String,
        val draft: String? = null,
        /** A message to scroll to and light up once loaded (a global search hit), and when it was sent. */
        val reveal: String? = null,
        val revealAtMs: Long? = null,
    ) : MessagesRoute

    /** New conversation: a number or a contact, by SMS or WhatsApp. [share] carries a share-in to send. */
    @Serializable
    data class NewConversation(
        val share: SharePayload? = null,
    ) : MessagesRoute

    @Serializable
    data object WhatsAppSettings : MessagesRoute

    /** Settings, Spam filter: rules, allow list, blocked numbers, M47 (E6.11). */
    @Serializable
    data object SpamSettings : MessagesRoute

    /** Settings, Notifications: the switches and the muted conversations (E6.12). */
    @Serializable
    data object NotificationSettings : MessagesRoute

    /** Settings, Quick replies (M11, E6.12). */
    @Serializable
    data object QuickReplies : MessagesRoute

    /** Settings, Text size of the bubbles (M34, E6.12). */
    @Serializable
    data object TextSize : MessagesRoute

    /** Settings, Backup: encrypted export and restore (AD-MSG7, E6.12). */
    @Serializable
    data object Backup : MessagesRoute

    // The common settings pages (shell CommonSettingsPage + the licence detail).
    @Serializable
    data object Appearance : MessagesRoute

    @Serializable
    data object Haptics : MessagesRoute

    @Serializable
    data object About : MessagesRoute

    @Serializable
    data object Licences : MessagesRoute

    @Serializable
    data class LicenceDetail(
        val id: String,
    ) : MessagesRoute

    /** Developer mode's Logs page; [tag] preselects a tag. */
    @Serializable
    data class Logs(
        val tag: String? = null,
    ) : MessagesRoute
}

/** A text or an image shared into the app (SEND), waiting for the person to pick a chat. */
@Serializable
data class SharePayload(
    val text: String? = null,
    val streamUri: String? = null,
    val mime: String? = null,
)

internal val ChatsTab = Tab("chats")
internal val WhatsAppTab = Tab("whatsapp")
internal val SettingsTab = Tab("settings")
internal val MessagesTabs = listOf(ChatsTab, WhatsAppTab, SettingsTab)

internal val MessagesRoots: Map<Tab, MessagesRoute> =
    mapOf(
        ChatsTab to MessagesRoute.Chats,
        WhatsAppTab to MessagesRoute.WhatsApp,
        SettingsTab to MessagesRoute.Settings,
    )

/** The tab bar items; the titles are the GPUI app's `TABS` (apps/messages/src/main.rs). */
internal fun messagesTabItems(
    chats: String,
    whatsApp: String,
    settings: String,
) = persistentListOf(
    TabItem(ChatsTab.id, chats, RostyIcons.Message),
    TabItem(WhatsAppTab.id, whatsApp, RostyIcons.WhatsApp),
    TabItem(SettingsTab.id, settings, RostyIcons.Gear),
)

/**
 * The chrome of each route (pure, tested on the JVM by `MessagesRoutesTest`): the tab roots keep the bar and a large
 * title; a conversation and New Conversation take the whole screen (the composer docks to the keyboard); the settings
 * sub-pages keep the full bar.
 */
fun chromeFor(
    route: MessagesRoute,
    text: (UiText) -> String,
): ChromeSpec =
    when (route) {
        MessagesRoute.Chats -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.tab_chats)))
        }

        MessagesRoute.WhatsApp -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.tab_whatsapp)))
        }

        MessagesRoute.Settings -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.tab_settings)))
        }

        is MessagesRoute.SmsThread, is MessagesRoute.WaThread, is MessagesRoute.NewConversation -> {
            ChromeSpec(TabBarMode.Hidden)
        }

        MessagesRoute.WhatsAppSettings -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = "WhatsApp")
        }

        MessagesRoute.SpamSettings -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.settings_spam_row)))
        }

        MessagesRoute.NotificationSettings -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.notify_settings_title)))
        }

        MessagesRoute.QuickReplies -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.quick_replies_title)))
        }

        MessagesRoute.TextSize -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.text_size_title)))
        }

        MessagesRoute.Backup -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.backup_title)))
        }

        MessagesRoute.Appearance -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.settings_appearance_title)))
        }

        MessagesRoute.Haptics -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.settings_haptics_title)))
        }

        MessagesRoute.About -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.settings_about_title)))
        }

        MessagesRoute.Licences -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.settings_licences_title)))
        }

        is MessagesRoute.LicenceDetail -> {
            ChromeSpec(TabBarMode.Visible)
        }

        is MessagesRoute.Logs -> {
            ChromeSpec(TabBarMode.Visible, largeTitle = text(UiText.Res(R.string.settings_logs_title)))
        }
    }

/** The action of a sibling app's WhatsApp hand-off (AD-CT5, `Handoff.start`) and its extras. */
const val ACTION_WA_CHAT = Handoff.ACTION_WA_CHAT
const val EXTRA_WA_JID = Handoff.EXTRA_JID

/** A number instead of a JID (an older sender); turned into the JID here. */
const val EXTRA_WA_NUMBER = "dev.rosty.extra.NUMBER"

/** The extra of a message notification's tap (`sms:<thread>` or `wa:<jid>`), `MessageNotifications.EXTRA_TAG`. */
const val EXTRA_THREAD_TAG = "open_thread_tag"

/**
 * The parts of an intent [messagesRouteFor] reads; built from a real intent in `MainActivity`, by hand in tests.
 * [compose] is what [ComposeRequest] made of a SENDTO, VIEW or SEND.
 */
data class IntentFacts(
    val action: String? = null,
    val threadTag: String? = null,
    val waJid: String? = null,
    val waNumber: String? = null,
    val compose: ComposeRequest? = null,
) {
    companion object
}

/**
 * The deep link an intent carries, as a pure function: a notification's thread tag opens that conversation; a WhatsApp
 * hand-off opens the chat (a JID, or a number turned into its JID); `sms:`/`smsto:` opens a conversation to the
 * recipients with the body as a draft; a share opens New Conversation with the payload. Anything else leaves the stacks
 * alone.
 */
fun messagesRouteFor(facts: IntentFacts): Pair<Tab, List<MessagesRoute>>? {
    facts.threadTag?.let { tag ->
        smsThreadOf(tag)?.let { return ChatsTab to listOf(MessagesRoute.SmsThread(it)) }
        waChatOf(tag)?.let { return ChatsTab to listOf(MessagesRoute.WaThread(it)) }
    }
    if (facts.action == ACTION_WA_CHAT) {
        val jid = facts.waJid?.takeIf { it.contains('@') } ?: facts.waNumber?.let(::jidOfNumber)
        return jid?.let { ChatsTab to listOf(MessagesRoute.WaThread(it)) }
    }
    return when (val compose = facts.compose) {
        is ComposeRequest.New -> {
            ChatsTab to listOf(MessagesRoute.SmsThread(0, compose.to, compose.body))
        }

        is ComposeRequest.Share -> {
            ChatsTab to
                listOf(MessagesRoute.NewConversation(SharePayload(compose.text, compose.streamUri, compose.mime)))
        }

        null -> {
            null
        }
    }
}

/** Reads the [IntentFacts] of [intent]. */
fun IntentFacts.Companion.of(intent: Intent): IntentFacts =
    IntentFacts(
        action = intent.action,
        threadTag = intent.getStringExtra(EXTRA_THREAD_TAG),
        waJid = intent.getStringExtra(EXTRA_WA_JID),
        waNumber = intent.getStringExtra(EXTRA_WA_NUMBER),
        compose = ComposeRequest.fromIntent(intent),
    )

/** `sms:<id>` with a positive id. */
internal fun smsThreadOf(tag: String): Long? =
    tag
        .removePrefix("sms:")
        .takeIf { tag.startsWith("sms:") }
        ?.toLongOrNull()
        ?.takeIf { it > 0 }

/** `wa:<jid>` with a JID. */
internal fun waChatOf(tag: String): String? =
    tag.removePrefix("wa:").takeIf { tag.startsWith("wa:") && it.contains('@') }

/** A WhatsApp user JID from a dialable number: its digits at `s.whatsapp.net`; null when it has too few digits. */
internal fun jidOfNumber(number: String): String? {
    val digits = number.filter { it.isDigit() }
    return digits.takeIf { it.length >= MIN_JID_DIGITS }?.let { "$it@s.whatsapp.net" }
}

private const val MIN_JID_DIGITS = 6
