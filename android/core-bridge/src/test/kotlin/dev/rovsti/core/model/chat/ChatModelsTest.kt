package dev.rovsti.core.model.chat

import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.ops.ChatOps
import dev.rovsti.core.ops.NativeChatOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire shapes of the chat models on the exact JSON serde writes, and the facade's op names. */
class ChatModelsTest {
    private val json = Json

    @Test
    fun optionalRequestFieldsAreLeftOutWhenAbsent() {
        assertEquals(
            """{"hits":[1,4],"action":"older"}""",
            json.encodeToString(FindCursorRequest(hits = listOf(1, 4), action = CursorAction.Older)),
        )
        assertEquals(
            """{"action":"add","text":"Thanks"}""",
            json.encodeToString(RepliesRequest(action = RepliesAction.Add, text = "Thanks")),
        )
        assertEquals(
            """{"event":"scrolled","at_end":true}""",
            json.encodeToString(UnreadStateRequest(event = UnreadEvent.Scrolled, atEnd = true)),
        )
    }

    @Test
    fun enumsAreWrittenWithTheRustNames() {
        assertEquals("\"X1_5\"", json.encodeToString(VoiceSpeed.X1_5))
        assertEquals("\"in15_min\"", json.encodeToString(ReminderSlot.In15Min))
        assertEquals("\"copy_number\"", json.encodeToString(DetectActionKind.CopyNumber))
        assertEquals("\"load_older\"", json.encodeToString(DateStepKind.LoadOlder))
        assertEquals(ForwardChannel.WhatsApp, json.decodeFromString<ForwardChannel>("\"whatsapp\""))
    }

    @Test
    fun anIdlePlayerStateIsWhatTheFacadeSendsByDefault() {
        val request = VoiceToggleRequest(key = "n1")
        assertEquals(VoicePlayerState.Idle, request.state)
        assertEquals("""{"key":"n1"}""", json.encodeToString(request))
    }

    @Test
    fun noMarksIsThreeEmptyLists() {
        assertEquals("""{"pinned":[],"archived":[],"unread":[]}""", json.encodeToString(SmsMarks.None))
    }

    @Test
    fun theFacadeSendsEveryCallToItsRustOpName() =
        runBlocking {
            val core = FakeRustCore()
            val ops = NativeChatOps(core)
            ops.find(ChatFindRequest(listOf("a"), "a"))
            ops.marks(MarksRequest(action = MarksAction.Pin, thread = 3))
            ops.textDetect(TextDetectRequest("call 0532"))
            ops.callReminders(CallReminderListRequest("[]"))
            assertEquals(
                listOf("chat_find.find", "thread_marks.edit", "text_detect.detect", "call_reminder.list"),
                core.calls.map { it.op },
            )
            assertTrue(ChatOps.ops.containsAll(core.calls.map { it.op }))
            assertEquals(ChatOps.ops.size, ChatOps.ops.toSet().size)
            assertFalse(ChatOps.ops.any { it.startsWith("core.") })
        }
}
