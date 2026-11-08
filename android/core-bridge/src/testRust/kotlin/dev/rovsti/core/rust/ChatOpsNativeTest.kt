package dev.rovsti.core.rust

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.chat.CursorAction
import dev.rovsti.core.model.chat.DateDetectRequest
import dev.rovsti.core.model.chat.DateStepKind
import dev.rovsti.core.model.chat.DateStepRequest
import dev.rovsti.core.model.chat.DetectActionKind
import dev.rovsti.core.model.chat.FindCursorRequest
import dev.rovsti.core.model.chat.ForwardChannel
import dev.rovsti.core.model.chat.ForwardKind
import dev.rovsti.core.model.chat.ForwardPlanRequest
import dev.rovsti.core.model.chat.GlobalSearchRequest
import dev.rovsti.core.model.chat.LocalStar
import dev.rovsti.core.model.chat.LocalStarAction
import dev.rovsti.core.model.chat.LocalStarsRequest
import dev.rovsti.core.model.chat.MakeReminderRequest
import dev.rovsti.core.model.chat.MarksAction
import dev.rovsti.core.model.chat.MarksRequest
import dev.rovsti.core.model.chat.ReminderKind
import dev.rovsti.core.model.chat.RepliesAction
import dev.rovsti.core.model.chat.RepliesRequest
import dev.rovsti.core.model.chat.SearchCandidate
import dev.rovsti.core.model.chat.SmsMarks
import dev.rovsti.core.model.chat.TextDetectRequest
import dev.rovsti.core.model.chat.UnreadEvent
import dev.rovsti.core.model.chat.UnreadStateRequest
import dev.rovsti.core.model.chat.VoiceToggleKind
import dev.rovsti.core.model.chat.VoiceToggleRequest
import dev.rovsti.core.ops.NativeChatOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The chat tools against the real host library, with Kotlin-built values. */
class ChatOpsNativeTest {
    private val ops = NativeChatOps(TestRust.core)
    private val now = 1_790_000_000_000L
    private val tz = 10_800

    @Before
    fun load() = TestRust.ensure()

    @Test
    fun theFindCursorStartsOnTheNewestHitAndWrapsBothWays() =
        runBlocking {
            val hits = listOf(1, 4, 9)
            val start = ops.findCursor(FindCursorRequest(hits, action = CursorAction.Start)).getOrOk()
            assertEquals(9, start.current)
            assertEquals("3 of 3", start.label)
            val wrapped = ops.findCursor(FindCursorRequest(hits, at = 2, action = CursorAction.Newer)).getOrOk()
            assertEquals(1, wrapped.current)
            assertTrue(wrapped.oldest)
            val empty = ops.findCursor(FindCursorRequest(emptyList(), action = CursorAction.Older)).getOrOk()
            assertTrue(empty.empty)
            assertEquals("No matches", empty.label)
        }

    @Test
    fun aGlobalSearchHighlightIsInUtf16UnitsOfTheSnippet() =
        runBlocking {
            val text = "😊 Kahve içelim mi"
            val answer =
                ops
                    .globalSearch(
                        GlobalSearchRequest(
                            "KAHVE",
                            items = listOf(SearchCandidate("sms:1", "Ayşe", "sms:9", null, text, now)),
                        ),
                    ).getOrOk()
            val hit = answer.hits.single()
            assertEquals("Kahve", hit.snippet.substring(hit.highlight.start, hit.highlight.end))
            assertEquals("1 result", answer.summary)
        }

    @Test
    fun aDateAfterAnEmojiIsLocatedInUtf16UnitsOfTheBody() =
        runBlocking {
            val body = "😊 görüşelim 2026-12-01 at 10:30 tamam"
            val date =
                ops
                    .dateDetect(DateDetectRequest(body, now, tz, chat = "Ayşe"))
                    .getOrOk()
                    .dates
                    .single()
            assertEquals(date.text, body.substring(date.start, date.end))
            assertEquals(date.startMs + 3_600_000, date.endMs)
            assertFalse(date.allDay)
        }

    @Test
    fun theDayPickerRevealsTheFirstMessageOnOrAfterTheDay() =
        runBlocking {
            val day = 86_400_000L
            val start = now - 7 * day
            val step =
                ops
                    .dateStep(
                        DateStepRequest(listOf(now - 9 * day, now - 5 * day, now), false, 1, start, "Mon 14 Sep", tz),
                    ).getOrOk()
            assertEquals(DateStepKind.Reveal, step.kind)
            assertEquals(1, step.index)
            assertEquals("No messages on Mon 14 Sep: showing the next day with messages", step.laterNotice)
        }

    @Test
    fun threadMarksRefuseASixthPinWithTheSentenceToShow() =
        runBlocking {
            val full = SmsMarks(pinned = listOf(1, 2, 3, 4, 5), archived = emptyList(), unread = emptyList())
            val refused = ops.marks(MarksRequest(full, MarksAction.Pin, 8)).getOrOk()
            assertEquals("too_many_pins", refused.refused)
            assertFalse(refused.changed)
            assertEquals(full, refused.marks)
            val archived = ops.marks(MarksRequest(full, MarksAction.Archive, 1)).getOrOk()
            assertEquals(listOf(2L, 3L, 4L, 5L), archived.marks.pinned)
            assertEquals(listOf(1L), archived.marks.archived)
            val rekey = ops.marks(MarksRequest(full, MarksAction.Rekey, 1))
            assertTrue("rekey needs a target", rekey is Outcome.Err && rekey.error is RustError.Invalid)
        }

    @Test
    fun theQuickReplyListRefusesADuplicateWithItsReason() =
        runBlocking {
            val list = ops.replies(RepliesRequest(action = RepliesAction.Show)).getOrOk()
            assertTrue(list.list.isNotEmpty())
            val duplicate =
                ops.replies(
                    RepliesRequest(list.list, RepliesAction.Add, text = list.list.first().uppercase()),
                )
            val error = (duplicate as Outcome.Err).error
            assertTrue(error is RustError.Invalid)
            assertEquals("That reply is already in the list", error.message)
        }

    @Test
    fun localStarsAreKeptPerThread() =
        runBlocking {
            val star = LocalStar("7", now, "Me", "Selam")
            val set =
                ops
                    .localStars(
                        LocalStarsRequest(action = LocalStarAction.Set, id = "sms:1", star = star, threadId = "7"),
                    ).getOrOk()
            assertTrue(set.starred)
            assertEquals(1, set.threadCount)
            val gone =
                ops
                    .localStars(
                        LocalStarsRequest(set.stars, LocalStarAction.ForgetThread, threadId = "7"),
                    ).getOrOk()
            assertTrue(gone.stars.isEmpty())
        }

    @Test
    fun aForwardOfBlankTextSendsNothing() =
        runBlocking {
            val plan =
                ops
                    .forwardPlan(
                        ForwardPlanRequest(ForwardKind.Text, text = "  ", to = ForwardChannel.Sms),
                    ).getOrOk()
            assertEquals(ForwardKind.Nothing, plan.kind)
            assertNull(plan.text)
        }

    @Test
    fun theUnreadBadgeCapsAtNinetyNinePlus() =
        runBlocking {
            val answer =
                ops
                    .unreadState(
                        UnreadStateRequest(away = true, unseen = 98, event = UnreadEvent.Arrived, incoming = 5),
                    ).getOrOk()
            assertEquals("99+", answer.badge)
            assertEquals(103, answer.unseen)
        }

    @Test
    fun aPausedNoteResumesWhereItStopped() =
        runBlocking {
            val state =
                dev.rovsti.core.model.chat
                    .VoicePlayerState("n1", false, 12_000, 42_000, false, null)
            val plan = ops.voiceToggle(VoiceToggleRequest(state, "n1")).getOrOk()
            assertEquals(VoiceToggleKind.Resume, plan.plan)
            assertEquals(12_000L, plan.fromMs)
        }

    @Test
    fun numbersAndAddressesInABodyGetTheirTapPills() =
        runBlocking {
            val answer =
                ops
                    .textDetect(
                        TextDetectRequest(
                            "Ara 0532 123 45 67 ya da ada@example.com",
                            own = listOf("+90 532 123 45 67"),
                        ),
                    ).getOrOk()
            assertEquals(
                listOf(
                    DetectActionKind.Message,
                    DetectActionKind.CopyNumber,
                    DetectActionKind.Email,
                    DetectActionKind.CopyEmail,
                ),
                answer.actions.map { it.kind },
            )
            assertEquals(listOf("05321234567"), answer.dialled)
        }

    @Test
    fun aBlankReminderIsInvalid() =
        runBlocking {
            val bad =
                ops.makeReminder(
                    MakeReminderRequest(
                        ReminderKind.Message,
                        " ",
                        title = "",
                        preview = "",
                        nowMs = now,
                        tzOffsetS = tz,
                    ),
                )
            assertTrue(bad is Outcome.Err && bad.error is RustError.Invalid)
            val ok =
                ops
                    .makeReminder(
                        MakeReminderRequest(
                            ReminderKind.Chat,
                            "sms:1",
                            title = "Ayşe",
                            preview = "Selam",
                            minutes = 30,
                            nowMs = now,
                            tzOffsetS = tz,
                        ),
                    ).getOrOk()
            assertEquals(now + 30 * 60_000, ok.reminder.atMs)
            assertEquals("Reminder: Ayşe", ok.notifTitle)
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
