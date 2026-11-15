package dev.rosty.messages.starred

import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.conversation.BubbleState
import dev.rosty.messages.conversation.BubbleUi
import dev.rosty.messages.conversation.ConversationItem
import dev.rosty.messages.conversation.ConversationUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** E6.13: local SMS stars through Rust into the prefs (M23), WhatsApp stars shown at once and sent when online (M10). */
@OptIn(ExperimentalCoroutinesApi::class)
class StarCenterTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_790_000_000_000L

    private fun TestScope.kit(
        file: File? = null,
        graceMs: Long = 10_000,
    ): MarkKit {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return MarkKit(CoroutineScope(backgroundScope.coroutineContext + dispatcher), dispatcher, {
            now
        }, file, graceMs = graceMs)
            .also { advanceUntilIdle() }
    }

    private val sms = MarkTarget("sms:7", "sms:12", 1_789_999_000_000L, "Mert Kaya", "Altıda\ngarda", "Mert Kaya")
    private val wa =
        MarkTarget("wa:905550000002@s.whatsapp.net", "3EB0AA", 1_789_999_500_000L, "Me", "Biletler bende", "Elif")
    private val jid = "905550000002@s.whatsapp.net"

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

    @Test fun aWhatsAppStarShowsAtOnceAndGoesOutWhenTheConnectionIsBack() =
        runTest {
            val kit = kit()
            assertTrue(kit.stars.set(wa, true))
            advanceUntilIdle()
            // Offline: nothing was sent, the bubble and the list show it already.
            assertTrue(kit.wa.sent.isEmpty())
            assertEquals(
                setOf("3EB0AA"),
                kit.stars
                    .marks(wa.tag)
                    .first()
                    .on,
            )
            val item =
                kit.stars.items.value
                    .single()
            assertEquals("Elif", item.title)
            assertEquals(MessagesRoute.WaThread(jid, reveal = "3EB0AA", revealAtMs = wa.atMs), starredRoute(item))

            kit.wa.online.value = true
            advanceUntilIdle()
            assertEquals(listOf(Triple(jid, "3EB0AA", true)), kit.wa.sent)
            // Sent once: another reconnect does not send it again.
            kit.wa.online.value = false
            kit.wa.online.value = true
            advanceUntilIdle()
            assertEquals(1, kit.wa.sent.size)
        }

    @Test fun anUnstarHidesAStoredStarUntilTheStoreFollows() =
        runTest {
            val kit = kit()
            kit.wa.stored = listOf(WaStarRow(jid, "3EB0AA", wa.atMs, "Me", "Biletler bende", "Elif"))
            kit.wa.online.value = true
            kit.stars.rescan()
            advanceUntilIdle()
            assertEquals(
                listOf("3EB0AA"),
                kit.stars.items.value
                    .map { it.id },
            )

            kit.stars.set(wa, false)
            advanceUntilIdle()
            assertEquals(listOf(Triple(jid, "3EB0AA", false)), kit.wa.sent)
            assertTrue(
                kit.stars.items.value
                    .isEmpty(),
            )
            assertEquals(
                setOf("3EB0AA"),
                kit.stars
                    .marks(wa.tag)
                    .first()
                    .off,
            )

            // The gateway's event reached the store; after the grace time the store alone decides.
            kit.wa.stored = emptyList()
            now += 11_000
            kit.stars.seen(jid, mapOf("3EB0AA" to false))
            kit.stars.rescan()
            advanceUntilIdle()
            assertEquals(StarMarks(), kit.stars.marks(wa.tag).first())
            assertTrue(
                kit.stars.items.value
                    .isEmpty(),
            )
        }

    @Test fun aStarUnstarredOnAnotherPhoneLeavesTheList() =
        runTest {
            val kit = kit()
            kit.wa.online.value = true
            kit.stars.set(wa, true)
            advanceUntilIdle()
            now += 11_000
            // Still starred in the store: the own copy stays (the message may be older than what the scan reads).
            kit.stars.seen(jid, mapOf("3EB0AA" to true))
            advanceUntilIdle()
            assertEquals(1, kit.stars.items.value.size)
            kit.stars.seen(jid, mapOf("3EB0AA" to false))
            advanceUntilIdle()
            assertTrue(
                kit.stars.items.value
                    .isEmpty(),
            )
        }

    @Test fun pendingStarsSurviveARestart() =
        runTest {
            val file = File(tmp.root, StarCenter.FILE)
            val first = kit(file)
            first.stars.set(wa, true)
            advanceUntilIdle()
            assertTrue(file.readText().contains("3EB0AA"))

            val second = kit(file)
            assertEquals(
                listOf("3EB0AA"),
                second.stars.items.value
                    .map { it.id },
            )
            second.wa.online.value = true
            advanceUntilIdle()
            assertEquals(listOf(Triple(jid, "3EB0AA", true)), second.wa.sent)
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
