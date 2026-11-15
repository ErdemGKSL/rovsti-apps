package dev.rosty.messages.spam

import dev.rosty.core.model.messages.Channel
import dev.rosty.core.model.messages.InboxRow
import dev.rosty.core.model.messages.InboxThread
import dev.rosty.core.model.messages.Message
import dev.rosty.core.model.messages.MessageDirection
import dev.rosty.core.model.messages.PersonKey
import dev.rosty.core.model.messages.PhoneNumber
import dev.rosty.core.model.messages.SmsThreadRow
import dev.rosty.core.model.messages.SpamRule
import dev.rosty.core.model.messages.SpamRules
import dev.rosty.core.model.messages.ThreadId
import dev.rosty.core.model.phone.BlockedNumber
import dev.rosty.design.overlay.MenuItem
import dev.rosty.messages.inbox.BulkKind
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.inbox.InboxAction
import dev.rosty.messages.inbox.InboxBuilder
import dev.rosty.messages.inbox.InboxFolder
import dev.rosty.messages.inbox.InboxInputs
import dev.rosty.messages.inbox.InboxRowUi
import dev.rosty.messages.inbox.InboxStrings
import dev.rosty.messages.inbox.InboxViewModel
import dev.rosty.messages.inbox.RowMenuActions
import dev.rosty.messages.inbox.RowTime
import dev.rosty.messages.inbox.rowMenu
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** E6.11: which inbox rows are spam (GPUI `inbox::is_spam`), the Spam folder's menu and bulk actions. */
class SpamInboxTest {
    private val now = 1_790_000_000_000L
    private val builder = InboxBuilder(EnglishInboxWords, RowTime(ZoneId.of("UTC"), Locale.US, true, "Yesterday"))

    private fun thread(
        id: Long,
        body: String,
        group: Boolean = false,
    ) = InboxThread(
        ThreadId.Sms(id),
        Message(ThreadId.Sms(id), MessageDirection.Incoming, null, body, now - id, false),
        1,
        null,
        group,
    )

    private fun wa(jid: String) =
        InboxThread(
            ThreadId.WhatsApp(jid),
            Message(ThreadId.WhatsApp(jid), MessageDirection.Incoming, null, "x", now, true),
            0,
            null,
            false,
        )

    private fun row(
        key: PersonKey,
        vararg threads: InboxThread,
    ) = InboxRow(key, "", threads.toList(), threads.first().last, 1, threads.map { it.id.channel }.distinct())

    private fun number(n: String) = PersonKey.Number(PhoneNumber(n))

    private val stranger = "+905550000001"
    private val friend = "+905550000002"
    private val promo = "PROMO-SHOP"

    private val smsRows =
        mapOf(
            1L to SmsThreadRow(1, listOf(stranger), "", now, 1),
            2L to SmsThreadRow(2, listOf(friend), "", now, 1),
            3L to SmsThreadRow(3, listOf(promo), "", now, 1),
            4L to SmsThreadRow(4, listOf(stranger, friend), "", now, 1),
            5L to SmsThreadRow(5, listOf("+905550000005"), "", now, 1),
        )

    private val rows =
        listOf(
            row(number(stranger), thread(1, "Claim at http://x.example/win")),
            row(PersonKey.Contact(7), thread(2, "http://family.example/photos")),
            row(number(promo), thread(3, "Big sale")),
            row(PersonKey.Group(ThreadId.Sms(4)), thread(4, "http://group.example", group = true)),
            row(number("+905550000005"), thread(5, "http://x.example"), wa("905550000005@s.whatsapp.net")),
        )

    private val rules = SpamRules(rules = listOf(SpamRule.UnknownWithLink, SpamRule.Sender("PROMO*")))

    @Test fun onlyOneToOneSmsRowsOfStrangersAreSpam() =
        runBlocking {
            val core = SpamCore()
            val facts = SpamVerdicts(core.ops).judge(rows, smsRows, rules, emptyList())
            // The stranger's link and the promo sender; never the contact, the group or someone also on WhatsApp.
            assertEquals(setOf(1L, 3L), facts.spam)
            assertTrue(facts.blocked.isEmpty())
        }

    @Test fun theAllowListAndTheSwitchWin() =
        runBlocking {
            val core = SpamCore()
            val verdicts = SpamVerdicts(core.ops)
            assertEquals(setOf(1L), verdicts.judge(rows, smsRows, rules.copy(allow = listOf(promo)), emptyList()).spam)
            assertTrue(verdicts.judge(rows, smsRows, rules.copy(enabled = false), emptyList()).spam.isEmpty())
        }

    @Test fun aBlockedSenderIsSpamAndMarkedBlocked() =
        runBlocking {
            val core = SpamCore()
            val blocked = listOf(BlockedNumber(1, friend, friend))
            val facts = SpamVerdicts(core.ops).judge(rows, smsRows, SpamRules(), blocked)
            // A blocked contact stays in the inbox (contacts are never spam) but its menu offers Unblock.
            assertTrue(2L in facts.blocked)
            assertFalse(2L in facts.spam)
        }

    @Test fun answersAreReusedUntilTheRulesChange() =
        runBlocking {
            val core = SpamCore()
            val verdicts = SpamVerdicts(core.ops)
            verdicts.judge(rows, smsRows, rules, emptyList())
            val first = core.core.calls.count { it.op == "spam.classify" }
            verdicts.judge(rows, smsRows, rules, emptyList())
            assertEquals(first, core.core.calls.count { it.op == "spam.classify" })
            verdicts.judge(rows, smsRows, rules.copy(rules = listOf(SpamRule.UnknownWithLink)), emptyList())
            assertTrue(core.core.calls.count { it.op == "spam.classify" } > first)
        }

    private fun built(
        spam: Set<Long>,
        blocked: Set<Long> = emptySet(),
    ) = builder.build(rows, InboxInputs(sms = smsRows, spamThreads = spam, blockedThreads = blocked, nowMs = now))

    @Test fun spamRowsLeaveTheInboxForTheFolder() {
        val view = built(setOf(1L, 3L), blocked = setOf(1L))
        assertEquals(2, view.spam.size)
        assertTrue(view.spam.all { it.inSpam })
        assertTrue(view.rows.none { it.inSpam || it.smsThread == 1L || it.smsThread == 3L })
        assertTrue(view.spam.first { it.smsThread == 1L }.blocked)
    }

    private val strings =
        InboxStrings(
            title = "",
            archivedTitle = "",
            spamTitle = "",
            search = "",
            newMessage = "",
            select = "Select",
            selectAll = "",
            deselectAll = "",
            cancel = "",
            selectNone = "",
            selectedFormat = { "" },
            chip = { "" },
            empty = { "" },
            emptyTitle = "",
            emptyDetail = "",
            loading = "",
            accessOffTitle = "",
            accessOffDetail = "",
            archivedRow = "",
            spamRow = "",
            chatsCount = { "" },
            inboxBack = "",
            spamNote = "",
            noResults = "",
            all = "",
            pin = "Pin",
            unpin = "Unpin",
            markRead = "Read",
            markUnread = "Unread",
            mute = "Mute",
            unmute = "Unmute",
            archive = "Archive",
            unarchive = "Unarchive",
            delete = "Delete",
            bulk = { it.name },
            copyCode = { it },
            muted = "",
            pinned = "",
            unreadCount = { "" },
            notSpam = "Not spam",
            moveToSpam = "Move to Spam",
            block = "Block",
            unblock = "Unblock",
        )

    private fun menu(row: InboxRowUi): List<String> {
        val actions = RowMenuActions({}, {}, {}, {})
        return rowMenu(row, strings, actions).filterIsInstance<MenuItem.Action>().map { it.label }
    }

    @Test fun theMenuOfASpamRowOffersNotSpamAndNoPinOrArchive() {
        val view = built(setOf(1L, 3L))
        assertEquals(
            listOf("Select", "Read", "Mute", "Not spam", "Block", "Delete"),
            menu(view.spam.first { it.smsThread == 1L }),
        )
        // An alphanumeric sender cannot be blocked.
        assertFalse("Block" in menu(view.spam.first { it.smsThread == 3L }))
    }

    @Test fun aStrangersRowOffersMoveToSpamAndAContactsDoesNot() {
        val view = built(emptySet(), blocked = setOf(2L))
        val stranger = view.rows.first { it.smsThread == 1L }
        assertTrue(stranger.canMoveToSpam)
        assertEquals(
            listOf("Select", "Pin", "Read", "Mute", "Archive", "Move to Spam", "Block", "Delete"),
            menu(stranger),
        )
        val contact = view.rows.first { it.smsThread == 2L }
        assertFalse("Move to Spam" in menu(contact))
        assertTrue("Unblock" in menu(contact))
        // Someone also on WhatsApp is never moved into Spam.
        assertFalse(view.rows.first { it.waChat != null }.canMoveToSpam)
    }

    @Test fun theMenuActionsNameTheRow() {
        val sent = ArrayList<InboxAction>()
        val row = built(setOf(1L)).spam.single { it.smsThread == 1L }
        rowMenu(row, strings, RowMenuActions({ sent += it }, {}, {}, {}))
            .filterIsInstance<MenuItem.Action>()
            .filter { it.label == "Not spam" || it.label == "Block" }
            .forEach { it.onClick() }
        assertEquals(listOf(InboxAction.NotSpam(row.key), InboxAction.Block(row.key, true)), sent)
    }

    @Test fun bulkActionsInTheSpamFolder() {
        val view = built(setOf(1L, 3L))
        assertEquals(
            listOf(BulkKind.MarkRead, BulkKind.Mute, BulkKind.NotSpam, BulkKind.Delete),
            InboxViewModel.bulkActions(view.spam, InboxFolder.Spam),
        )
        assertFalse(BulkKind.NotSpam in InboxViewModel.bulkActions(view.rows, InboxFolder.Inbox))
    }

    @Test fun onlyNumbersCanBeBlocked() {
        val view = built(emptySet())
        assertTrue(view.rows.first { it.smsThread == 1L }.senderIsNumber)
        assertFalse(view.rows.first { it.smsThread == 3L }.senderIsNumber)
        assertEquals(listOf(Channel.Sms), view.rows.first { it.smsThread == 1L }.channels)
    }
}
