package dev.rosty.messages.wa

import dev.rosty.core.Outcome
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.notify.MessagesNotifier
import dev.rosty.messages.notify.NotifyPolicy
import dev.rosty.wa.WaRepository

/**
 * What Rosty Messages keeps for a WhatsApp link outside the offline store, and how each piece is dropped when the
 * store says another account was linked, or when the link is forgotten. The inventory is the one of
 * [messagesRekeyMovers] (what follows a merge of two chats is what belongs to the account), plus the names asked
 * through the link:
 *
 * 1. the drafts and the mutes with their ends of every `wa:` thread (`drafts.json`, `messages-prefs.json`); the
 *    notifier's copy of the mutes is refreshed at once;
 * 2. the lines the announcer collected for the chats' notifications;
 * 3. the notifications of the chats;
 * 4. the reminders set on messages of the chats;
 * 5. the scheduled WhatsApp texts in memory (the gateway dropped its rows with the account);
 * 6. the names WhatsApp answered for numbers (`wa-live-names.json`);
 * 7. the thumbnails in memory (their files went with the media directory).
 *
 * [WaOffline] itself deletes the media directory and resets the [WaRekeys] cursor of the instance. Not touched:
 * what is not per chat or per account (the recent reaction emoji, the voice note speed, the quick replies), and
 * everything of SMS threads. The announcer and the schedule center are only asked when the app already made them.
 */
fun messagesLinkCleanup(container: MessagesContainer): WaLinkCleanup {
    val wa = WaRekeys.tagOf("")
    return WaLinkCleanup {
        container.store.forgetThreads(wa)
        container.store.prefs.value
            ?.let { NotifyPolicy.current = NotifyPolicy.of(it) }
        container.announcerIfMade?.forgetWhatsApp()
        MessagesNotifier.dismissAll(container.context, wa)
        container.reminders.forget(wa)
        container.scheduleIfMade?.forgetWhatsApp()
        container.liveWaNames.forget()
        container.waMediaIfMade?.forgetThumbs()
    }
}

/**
 * The app's [WaRepository] as the WhatsApp settings use it: "Unlink" also deletes the offline store of the link, its
 * media directory and what is kept per chat for it ([WaOffline.unlink]). Everything else is the repository's own.
 */
class UnlinkingWaRepository(
    private val real: WaRepository,
    private val offline: () -> WaOffline,
) : WaRepository by real {
    override suspend fun forget(): Outcome<Unit> = offline().unlink { real.forget() }
}
