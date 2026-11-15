package dev.rosty.messages.wa

import dev.rosty.core.FakeRustCore
import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.getOrThrow
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.messages.DirRequest
import dev.rosty.core.model.messages.DraftsAnswer
import dev.rosty.core.model.messages.DraftsRekeyRequest
import dev.rosty.core.model.messages.DraftsSaveRequest
import dev.rosty.core.model.messages.Incoming
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.PrefsEditAnswer
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.core.model.messages.PrefsLoadAnswer
import dev.rosty.core.model.messages.PrefsSaveRequest
import dev.rosty.core.ops.NativeMessagesOps
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.notify.Digests
import dev.rosty.messages.notify.OpenConversation
import dev.rosty.messages.spam.prefsWith
import dev.rosty.messages.wa.WaTestKit.Companion.ADA
import dev.rosty.messages.wa.WaTestKit.Companion.MERT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Following a merge of two WhatsApp chats (E6-WA R1): the store's log is followed once and in order, also after a
 * restart, and each kind of state the app keeps per chat moves from the old key to the new one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WaRekeysTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ops = FakeWaSyncOps(INSTANCE)
    private val moved = ArrayList<Rekey>()
    private val recorder = RekeyMover { moved += it }

    @After fun tearDown() {
        OpenConversation.tag?.let(OpenConversation::leave)
    }

    private suspend fun store(instance: String = INSTANCE): WaSyncStore =
        WaSyncStore.open(instance, ops = ops, release = {}).getOrThrow()

    // ---- the log is followed once, in order, whoever reported it

    @Test fun aMergeIsFollowedByEveryMoverThenTold() =
        runTest(UnconfinedTestDispatcher()) {
            val store = store()
            val order = ArrayList<String>()
            val rekeys =
                WaRekeys(movers = listOf(RekeyMover { order += "draft ${it.from}" }, RekeyMover { order += "notice" }))
            val told = ArrayList<Rekey>()
            backgroundScope.launch { rekeys.rekeys.collect { told += it } }

            val step = ops.merge(LID, ADA)!!
            rekeys.follow(store, listOf(step))

            assertEquals(listOf("draft $LID", "notice"), order)
            assertEquals(listOf(Rekey(LID, ADA)), told)
        }

    @Test fun stepsAreFollowedInTheOrderOfTheLogWhicheverAnswerNamedThem() =
        runTest {
            val store = store()
            val rekeys = WaRekeys(movers = listOf(recorder))
            val first = ops.merge(LID, ADA)!!
            val second = ops.merge(LID2, MERT)!!

            // The later step is reported first (another call's answer came back sooner): the log decides.
            rekeys.follow(store, listOf(second))
            assertEquals(listOf(Rekey(LID, ADA), Rekey(LID2, MERT)), moved)
            rekeys.follow(store, listOf(first))
            rekeys.follow(store, listOf(first, second))
            assertEquals("a step is followed once", 2, moved.size)
        }

    @Test fun anAnswerWithoutStepsAsksNothing() =
        runTest {
            val store = store()
            val rekeys = WaRekeys(movers = listOf(recorder))
            ops.merge(LID, ADA)
            rekeys.follow(store, emptyList())
            assertTrue(moved.isEmpty())
        }

    @Test fun aMergeLoggedWhileNothingRanIsFollowedAtTheNextStart() =
        runTest {
            val file = File(tmp.root, "state/${WaRekeys.FILE}")
            val store = store()
            WaRekeys(FileRekeyCursor(file), listOf(recorder)).let { first ->
                ops.merge(LID, ADA)
                assertEquals(listOf(Rekey(LID, ADA)), first.replay(store))
            }
            // The app is gone; the outbox job's process learns another pair and merges.
            ops.merge(LID2, MERT)

            val restarted = WaRekeys(FileRekeyCursor(file), listOf(recorder))
            assertEquals(listOf(Rekey(LID2, MERT)), restarted.replay(store))
            assertEquals(listOf(Rekey(LID, ADA), Rekey(LID2, MERT)), moved)
            // And a third start has nothing to do.
            assertTrue(WaRekeys(FileRekeyCursor(file), listOf(recorder)).replay(store).isEmpty())
            assertEquals(2, moved.size)
        }

    @Test fun aCursorFileThatCannotBeReadStartsOverAndTheStepsAreFollowedAgain() =
        runTest {
            val file = tmp.newFile(WaRekeys.FILE).apply { writeText("{ not json") }
            val store = store()
            ops.merge(LID, ADA)
            assertEquals(listOf(Rekey(LID, ADA)), WaRekeys(FileRekeyCursor(file), listOf(recorder)).replay(store))
            assertEquals(1L, FileRekeyCursor(file).read(INSTANCE))
        }

    @Test fun aMoverThatFailsKeepsTheStepForTheNextRun() =
        runTest {
            val store = store()
            val cursor = MemoryRekeyCursor()
            var broken = true
            val flaky = RekeyMover { if (broken) throw IllegalStateException("the prefs file is locked") }
            val rekeys = WaRekeys(cursor, listOf(flaky, recorder))
            val told = ArrayList<Rekey>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rekeys.rekeys.collect { told += it } }
            ops.merge(LID, ADA)
            ops.merge(LID2, MERT)

            assertTrue(rekeys.replay(store).isEmpty())
            assertEquals(0L, cursor.read(INSTANCE))
            assertTrue(moved.isEmpty() && told.isEmpty())

            broken = false
            assertEquals(listOf(Rekey(LID, ADA), Rekey(LID2, MERT)), rekeys.replay(store))
            assertEquals(2L, cursor.read(INSTANCE))
            assertEquals(moved, told)
        }

    @Test fun theCursorIsKeptPerInstance() =
        runTest {
            val file = File(tmp.root, WaRekeys.FILE)
            val cursor = FileRekeyCursor(file)
            cursor.write("inst1", 7)
            cursor.write("inst2", 2)
            val read = FileRekeyCursor(file)
            assertEquals(7L, read.read("inst1"))
            assertEquals(2L, read.read("inst2"))
            assertEquals(0L, read.read("inst3"))
        }

    // ---- what moves

    private fun TestScope.messagesStore(core: RekeyPrefsCore): MessagesStore =
        MessagesStore(
            NativeMessagesOps(core.core),
            backgroundScope,
            io = StandardTestDispatcher(testScheduler),
            debounceMs = 0,
        )

    @Test fun theDraftMovesToTheMergedChat() =
        runTest {
            val core = RekeyPrefsCore(drafts = mapOf(tag(LID) to "see you at six", "sms:7" to "ok"))
            val store = messagesStore(core)

            store.rekeyThread(tag(LID), tag(ADA))
            store.flush()

            assertEquals(mapOf(tag(ADA) to "see you at six", "sms:7" to "ok"), store.drafts.value)
            assertEquals("written to drafts.json", store.drafts.value, core.drafts)
        }

    @Test fun whenBothChatsHaveADraftTheOneOfTheSurvivingChatIsKept() =
        runTest {
            val core =
                RekeyPrefsCore(drafts = mapOf(tag(LID) to "typed under the LID", tag(ADA) to "typed under the number"))
            val store = messagesStore(core)

            store.rekeyThread(tag(LID), tag(ADA))
            store.flush()

            assertEquals(mapOf(tag(ADA) to "typed under the number"), store.drafts.value)
            assertEquals(store.drafts.value, core.drafts)
        }

    @Test fun theMuteAndItsEndMoveAndOtherTagsStay() =
        runTest {
            val prefs =
                prefsWith().copy(
                    muted = listOf(tag(LID), "sms:7", tag(MERT)),
                    muteUntil = mapOf(tag(LID) to 1_900_000_000_000L, "sms:7" to 5L),
                )
            val core = RekeyPrefsCore(prefs)
            val store = messagesStore(core)

            store.rekeyThread(tag(LID), tag(ADA))
            store.flush()

            val now = store.prefs.value!!
            assertEquals(setOf(tag(ADA), "sms:7", tag(MERT)), now.muted.toSet())
            assertEquals(mapOf(tag(ADA) to 1_900_000_000_000L, "sms:7" to 5L), now.muteUntil)
            assertEquals("written to messages-prefs.json", now, core.prefs)
        }

    @Test fun movingTwiceOrMovingNothingWritesNothingMore() =
        runTest {
            val core = RekeyPrefsCore(prefsWith().copy(muted = listOf(tag(LID))), mapOf(tag(LID) to "draft"))
            val store = messagesStore(core)
            store.rekeyThread(tag(LID), tag(ADA))
            store.flush()
            val (prefsSaves, draftsSaves) = core.prefsSaves to core.draftsSaves
            assertEquals(1 to 1, prefsSaves to draftsSaves)

            // The same step again (a replay after a crash before the cursor moved), a chat with no state, a no-op.
            store.rekeyThread(tag(LID), tag(ADA))
            store.rekeyThread(tag(LID2), tag(MERT))
            store.rekeyThread(tag(ADA), tag(ADA))
            store.flush()

            assertEquals(mapOf(tag(ADA) to "draft"), store.drafts.value)
            assertEquals(listOf(tag(ADA)), store.prefs.value!!.muted)
            assertEquals(prefsSaves to draftsSaves, core.prefsSaves to core.draftsSaves)
        }

    @Test fun theLinesCollectedForANotificationJoinThoseOfTheMergedChatInOrder() {
        val digests = Digests(maxLines = 3)

        fun line(
            chat: String,
            body: String,
            at: Long,
        ) = Incoming(tag(chat), "", body, at, verdictSpam = false)
        digests.push(line(LID, "one", 1))
        digests.push(line(ADA, "two", 2))
        digests.push(line(LID, "three", 3))
        digests.push(line(MERT, "other", 4))

        digests.rekey(tag(LID), tag(ADA))

        assertTrue(digests.lines(tag(LID)).isEmpty())
        assertEquals(listOf("one", "two", "three"), digests.lines(tag(ADA)).map { it.body })
        assertTrue("every line names the merged chat", digests.lines(tag(ADA)).all { it.thread == tag(ADA) })
        assertEquals(listOf("other"), digests.lines(tag(MERT)).map { it.body })
        // The next message of the person continues the one digest, capped as ever.
        assertEquals(listOf("two", "three", "four"), digests.push(line(ADA, "four", 5)).map { it.body })
        // Again, or for a chat with no lines: nothing changes.
        digests.rekey(tag(LID), tag(ADA))
        digests.rekey(tag(ADA), tag(ADA))
        assertEquals(listOf("two", "three", "four"), digests.lines(tag(ADA)).map { it.body })
    }

    @Test fun theConversationOnScreenFollowsOnlyWhenItIsTheMergedOne() {
        OpenConversation.enter(tag(LID))
        OpenConversation.rekey(tag(LID2), tag(MERT))
        assertEquals(tag(LID), OpenConversation.tag)
        OpenConversation.rekey(tag(LID), tag(ADA))
        assertEquals(tag(ADA), OpenConversation.tag)
        OpenConversation.leave(tag(ADA))
        OpenConversation.rekey(tag(LID), tag(ADA))
        assertNull(OpenConversation.tag)
        assertFalse(OpenConversation.isShowing(tag(ADA)))
    }

    private fun tag(jid: String) = WaRekeys.tagOf(jid)

    private companion object {
        const val INSTANCE = "inst1"
        const val LID = "777000000000001@lid"
        const val LID2 = "777000000000002@lid"
    }
}
