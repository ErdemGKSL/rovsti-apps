package dev.rosty.messages.starred

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.inbox.EnglishInboxWords
import dev.rosty.messages.wa.WaStoreLink
import dev.rosty.messages.wa.WaTestKit
import dev.rosty.messages.wa.settle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E6.13: local SMS stars through Rust into the prefs (M23); WhatsApp stars queued in the store's outbox, shown at once
 * and listed by the store's own query (M10).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StarCenterTest {
    private val now = 1_790_000_000_000L

    private fun TestScope.kit(): MarkKit {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return MarkKit(CoroutineScope(backgroundScope.coroutineContext + dispatcher), dispatcher, { now })
            .also { advanceUntilIdle() }
    }

    private val sms = MarkTarget("sms:7", "sms:12", 1_789_999_000_000L, "Mert Kaya", "Altıda\ngarda", "Mert Kaya")
    private val wa =
        MarkTarget("wa:905550000002@s.whatsapp.net", "3EB0AA", 1_789_999_500_000L, "Me", "Biletler bende", "Elif")
    private val jid = "905550000002@s.whatsapp.net"
    private val waRow = WaStarRow(jid, "3EB0AA", wa.atMs, "Me", "Biletler bende", "Elif")

    @Test fun anSmsStarGoesThroughRustIntoThePrefs() =
        runTest {
            val kit = kit()
            assertTrue(kit.stars.set(sms, true))
            advanceUntilIdle()
            val stored =
                kit.store.prefs.value!!
                    .smsStars
                    .getValue("sms:12")
            assertEquals("7", stored.threadId)
            assertEquals("Mert Kaya", stored.author)
            assertEquals(
                setOf("sms:12"),
                kit.stars
                    .marks("sms:7")
                    .first()
                    .on,
            )
            assertTrue(
                kit.stars
                    .marks("sms:8")
                    .first()
                    .on
                    .isEmpty(),
            )
            // The list shows Rust's one-line preview and opens the thread on the message.
            val item =
                kit.stars.items.value
                    .single()
            assertEquals("Altıda garda", item.preview)
            assertEquals(MessagesRoute.SmsThread(7, reveal = "sms:12", revealAtMs = sms.atMs), starredRoute(item))
            // The prefs file was written.
            kit.store.flush()
            assertTrue(
                "sms:12" in
                    kit.core.spam.saved!!
                        .smsStars,
            )

            assertTrue(kit.stars.set(sms, false))
            advanceUntilIdle()
            assertTrue(
                kit.stars.items.value
                    .isEmpty(),
            )
            assertTrue(
                kit.stars
                    .marks("sms:7")
                    .first()
                    .on
                    .isEmpty(),
            )
        }

    @Test fun aWhatsAppStarIsQueuedInTheOutboxAndListedAtOnce() =
        runTest {
            val kit = kit()
            kit.wa.knows["3EB0AA"] = waRow
            assertTrue(kit.stars.set(wa, true))
            // Queued once, with nothing else asked of the gateway; the list has it before any debounce ran.
            assertEquals(listOf(Triple(jid, "3EB0AA", true)), kit.wa.queued)
            val item =
                kit.stars.items.value
                    .single()
            assertEquals("Elif", item.title)
            assertEquals("Me", item.author)
            assertEquals(MessagesRoute.WaThread(jid, reveal = "3EB0AA", revealAtMs = wa.atMs), starredRoute(item))
            // The bubble shows the store's own flag: nothing is laid over a WhatsApp thread.
            assertEquals(StarMarks(), kit.stars.marks(wa.tag).first())
        }

    @Test fun anUnstarLeavesTheListAtOnceAndUndoBringsItBack() =
        runTest {
            val kit = kit()
            kit.wa.knows["3EB0AA"] = waRow
            kit.wa.flagged += "3EB0AA"
            kit.stars.rescan()
            advanceUntilIdle()
            assertEquals(
                listOf("3EB0AA"),
                kit.stars.items.value
                    .map { it.id },
            )

            assertTrue(kit.stars.set(wa, false))
            assertTrue(
                kit.stars.items.value
                    .isEmpty(),
            )
            // Undo is the star again, queued behind (the store replaces an unstar that has not gone out).
            assertTrue(kit.stars.set(wa, true))
            assertEquals(
                listOf("3EB0AA"),
                kit.stars.items.value
                    .map { it.id },
            )
            assertEquals(listOf(Triple(jid, "3EB0AA", false), Triple(jid, "3EB0AA", true)), kit.wa.queued)
        }

    @Test fun whatTheStoreChangesReachesTheList() =
        runTest {
            val kit = kit()
            kit.wa.knows["3EB0AA"] = waRow
            // Starred on another phone: the gateway's event set the flag in the store.
            kit.wa.flagged += "3EB0AA"
            kit.wa.changes.tryEmit(Unit)
            // The rows are read again once the changes settle (the debounce).
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(
                listOf("3EB0AA"),
                kit.stars.items.value
                    .map { it.id },
            )
            // Unstarred there, or a star of this phone the gateway refused: the store took the flag back.
            kit.wa.flagged -= "3EB0AA"
            kit.wa.changes.tryEmit(Unit)
            // The rows are read again once the changes settle (the debounce).
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(
                kit.stars.items.value
                    .isEmpty(),
            )
            assertTrue(kit.wa.queued.isEmpty())
        }

    @Test fun smsAndWhatsAppStarsAreOneListNewestFirst() =
        runTest {
            val kit = kit()
            kit.wa.knows["3EB0AA"] = waRow
            kit.stars.set(sms, true)
            kit.stars.set(wa, true)
            advanceUntilIdle()
            assertEquals(
                listOf("3EB0AA", "sms:12"),
                kit.stars.items.value
                    .map { it.id },
            )
            // An SMS star never touches the WhatsApp outbox.
            assertEquals(1, kit.wa.queued.size)
        }

    @Test fun withoutALinkAWhatsAppStarIsNotKept() =
        runTest {
            val kit = kit()
            kit.wa.linked = false
            assertFalse(kit.stars.set(wa, true))
            assertTrue(kit.wa.queued.isEmpty())
            assertTrue(
                kit.stars.items.value
                    .isEmpty(),
            )
        }

    // ---- the real seams: the offline store's outbox and its starred query

    @Test fun theStarIsAnOutboxActionThatGoesOutWhenTheConnectionIsBack() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(jid, name = "Elif")
            kit.ops.put(jid, "3EB0AA", wa.atMs, "Biletler bende", fromMe = true)
            kit.offline.start()
            settle()
            val seams = waStarSeams(kit.offline, EnglishInboxWords, "Me")

            // Offline: queued in the store, starred there at once, and a wake asked for the outbox job.
            assertTrue(seams.star(jid, "3EB0AA", true))
            assertEquals(listOf(WaSyncAction.Star("3EB0AA", true)), kit.ops.queue.map { it.action })
            assertTrue(kit.remote.performed.isEmpty())
            assertEquals(listOf(0L), kit.wakes)
            assertEquals(listOf(waRow), seams.starred())

            kit.connect()
            settle()
            assertEquals(listOf(jid to WaSyncAction.Star("3EB0AA", true)), kit.remote.performed)
            assertTrue(kit.ops.queue.isEmpty())
            assertEquals(listOf(waRow), seams.starred())
        }

    @Test fun aStarTheGatewayRefusesIsTakenBack() =
        runTest {
            val kit = WaTestKit(backgroundScope)
            kit.chat(jid, name = "Elif")
            kit.ops.put(jid, "3EB0AA", wa.atMs, "Biletler bende", fromMe = true)
            kit.remote.answer = { _, _ -> Outcome.Err(RustError.Invalid("no such message")) }
            kit.offline.start()
            settle()
            kit.connect()
            settle()
            val seams = waStarSeams(kit.offline, EnglishInboxWords, "Me")
            val heard = mutableListOf<Set<String>?>()
            backgroundScope.launch { kit.offline.changes.collect { heard += it } }
            settle()

            assertTrue(seams.star(jid, "3EB0AA", true))
            settle()
            assertEquals(1, kit.remote.performed.size)
            assertTrue(kit.ops.queue.isEmpty())
            assertEquals(emptyList<WaStarRow>(), seams.starred())
            // The readers were told: once for the star, once for the answer.
            assertEquals(listOf<Set<String>?>(setOf(jid), setOf(jid)), heard)
        }

    @Test fun theStarredQueryNamesTheChatAndWhoWrote() =
        runTest {
            val kit = WaTestKit(backgroundScope, linked = false)
            kit.offline.start()
            settle()
            val seams = waStarSeams(kit.offline, EnglishInboxWords, "Me")
            // Not linked: no store to ask or to queue in.
            assertNull(seams.starred())
            assertFalse(seams.star(jid, "3EB0AA", true))

            kit.chat(WaTestKit.ADA, pushName = "Ada")
            kit.chat(WaTestKit.GROUP, name = "Flatmates", group = true)
            kit.message(WaTestKit.text(WaTestKit.ADA, "a1", 1_000, "Door code 4821").copy(starred = true))
            kit.message(WaTestKit.text(WaTestKit.ADA, "a2", 2_000, "not starred"))
            kit.message(
                WaTestKit
                    .text(WaTestKit.GROUP, "g1", 3_000, "Rent is due", sender = WaTestKit.MERT, senderName = "Deniz")
                    .copy(starred = true),
            )
            kit.message(
                WaTestKit.text(WaTestKit.GROUP, "g2", 4_000, "", sender = WaTestKit.MERT).copy(starred = true),
            )
            kit.message(WaTestKit.text(WaTestKit.GROUP, "g3", 5_000, "gone").copy(starred = true, deleted = true))
            kit.message(
                WaTestKit.text(WaTestKit.MERT, "c1", 6_000, "I have the tickets", fromMe = true).copy(starred = true),
            )
            kit.link.value = WaStoreLink(WaTestKit.INSTANCE)
            settle()

            val rows = seams.starred()!!
            assertEquals(listOf("c1", "g2", "g1", "a1"), rows.map { it.id })
            // A chat the list does not name yet falls back to its number; a group names the sender.
            assertEquals(listOf("+905550000002", "Flatmates", "Flatmates", "Ada"), rows.map { it.title })
            assertEquals(listOf("Me", "+905550000002", "Deniz", "Ada"), rows.map { it.author })
            assertEquals("Rent is due", rows[2].preview)
        }

    @Test fun onlyMessagesWithAnIdOfTheirOwnCanBeMarked() {
        val sent = BubbleUi("sms:5", false, "hi", "14:05", atMs = 5)
        assertEquals(MarkTarget("sms:7", "sms:5", 5, "Mert", "hi", "Mert"), sent.markTarget("sms:7", "Mert", "Me"))
        assertNull(sent.copy(key = "local:9").markTarget("sms:7", "Mert", "Me"))
        // A WhatsApp message I sent keeps its client id as the key; stars and reminders use the WhatsApp id.
        val mine = BubbleUi("client-1", true, "ok", "14:06", BubbleState.Read, atMs = 6, markId = "3EB0BB")
        assertEquals("3EB0BB", mine.markTarget("wa:$jid", "Elif", "Me")?.id)
        assertEquals("Me", mine.markTarget("wa:$jid", "Elif", "Me")?.author)
        assertNull(mine.copy(state = BubbleState.Waiting).markTarget("wa:$jid", "Elif", "Me"))
        assertNull(mine.copy(deleted = true).markTarget("wa:$jid", "Elif", "Me"))
    }

    @Test fun marksAreLaidOverTheBubbles() {
        val a = BubbleUi("a", false, "one", "14:00", atMs = 1, starred = true)
        val b = BubbleUi("b-client", true, "two", "14:01", atMs = 2, markId = "b")
        val state =
            ConversationUi(
                items =
                    persistentListOf(
                        ConversationItem.Bubble(b),
                        ConversationItem.Bubble(a),
                        ConversationItem.Day("d", "Today"),
                    ),
            )
        assertSame(state, state.withMarks(StarMarks(), emptyMap()))
        val marked = state.withMarks(StarMarks(on = setOf("b"), off = setOf("a")), mapOf("b" to "18:05"))
        val bubbles = marked.items.mapNotNull { (it as? ConversationItem.Bubble)?.bubble }
        assertTrue(bubbles[0].starred)
        assertEquals("18:05", bubbles[0].reminder)
        assertFalse(bubbles[1].starred)
        assertNull(bubbles[1].reminder)
        assertEquals(3, marked.items.size)
    }
}
