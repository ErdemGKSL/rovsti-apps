package dev.rosty.messages.wa

import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.notify.MessagesNotifier
import dev.rosty.messages.notify.NotifyPolicy
import dev.rosty.messages.notify.OpenConversation

/**
 * What Rosty Messages keeps per WhatsApp chat outside the offline store, and how each piece follows a merge
 * (`wa:<from>` to `wa:<into>`), in the order they run:
 *
 * 1. the draft (`drafts.json`; the draft of the surviving chat is kept when both have one) and the mute with its end
 *    (`messages-prefs.json`: `muted`, `mute_until`), through Rust's `drafts_rekey` and the prefs' `rekey`; the
 *    notifier's copy of the mutes is refreshed at once, so the notification below is judged by the moved mute;
 * 2. the lines the announcer collected for the chat's notification;
 * 3. the conversation on screen (no notification is posted for the chat the user is looking at);
 * 4. the notification: the one of `wa:<from>` is cancelled and, when it was showing, posted again under
 *    `wa:<into>`;
 * 5. the reminders set on messages of the chat (their badges and their tap);
 * 6. the scheduled WhatsApp texts in memory (the gateway keeps them; the strip above the composer and the Scheduled
 *    page list them by chat).
 *
 * The announcer and the schedule center are only asked when the app already made them: a process that never did (the
 * outbox job, a receiver) holds no lines and no list to move, and making them here would start the WhatsApp side
 * from inside its own start.
 *
 * Kept in the store and moved by the store itself: messages, stars, reactions, pin, archive, unread, the outbox and
 * its queued sends, cached media. Not per chat: the recent reaction emoji, the voice note speed, the quick replies.
 */
fun messagesRekeyMovers(container: MessagesContainer): List<RekeyMover> {
    val context = container.context
    return listOf(
        RekeyMover { step ->
            container.store.rekeyThread(WaRekeys.tagOf(step.from), WaRekeys.tagOf(step.into))
            container.store.prefs.value
                ?.let { NotifyPolicy.current = NotifyPolicy.of(it) }
        },
        RekeyMover { step ->
            container.announcerIfMade?.rekey(WaRekeys.tagOf(step.from), WaRekeys.tagOf(step.into))
        },
        RekeyMover { step -> OpenConversation.rekey(WaRekeys.tagOf(step.from), WaRekeys.tagOf(step.into)) },
        RekeyMover { step ->
            MessagesNotifier.retag(context, WaRekeys.tagOf(step.from), WaRekeys.tagOf(step.into))
        },
        RekeyMover { step -> container.reminders.rekey(WaRekeys.tagOf(step.from), WaRekeys.tagOf(step.into)) },
        RekeyMover { step -> container.scheduleIfMade?.rekey(step.from, step.into) },
        RekeyMover { step -> container.waDrafts.rekey(WaRekeys.tagOf(step.from), WaRekeys.tagOf(step.into)) },
    )
}

/** The [WaRekeys] of the app: its cursor in `wa-rekeys.json`, its movers those of [messagesRekeyMovers]. */
fun realWaRekeys(container: MessagesContainer): WaRekeys =
    WaRekeys(
        // In memory since the vault (`plans/wa-vault.md` 13.1): the store it follows lives in memory too.
        cursor = MemoryRekeyCursor(),
        movers = messagesRekeyMovers(container),
    )
