package dev.rovsti.core.model.messages

import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.ops.MessagesOps
import dev.rovsti.core.ops.NativeMessagesOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-written serializers of the Messages models, on the exact JSON serde writes. */
class MessagesModelsTest {
    private val json = Json

    private inline fun <reified T> roundTrip(
        text: String,
        value: T,
    ) {
        assertEquals(value, json.decodeFromString<T>(text))
        assertEquals(text, json.encodeToString(value))
    }

    @Test
    fun threadIdsAreExternallyTagged() {
        roundTrip<ThreadId>("""{"Sms":5}""", ThreadId.Sms(5))
        roundTrip<ThreadId>("""{"WhatsApp":"1@g.us"}""", ThreadId.WhatsApp("1@g.us"))
        assertEquals(Channel.WhatsApp, ThreadId.WhatsApp("x").channel)
    }

    @Test
    fun personKeysNestNumbersAndThreads() {
        roundTrip<PersonKey>("""{"contact":1}""", PersonKey.Contact(1))
        roundTrip<PersonKey>("""{"number":{"canonical":"+49151"}}""", PersonKey.Number(PhoneNumber("+49151")))
        roundTrip<PersonKey>("""{"group":{"Sms":6}}""", PersonKey.Group(ThreadId.Sms(6)))
    }

    @Test
    fun spamRulesMixBareStringsAndTaggedObjects() {
        roundTrip<SpamRule>("\"unknown_with_link\"", SpamRule.UnknownWithLink)
        roundTrip<SpamRule>("""{"keyword":{"text":"win"}}""", SpamRule.Keyword("win"))
        roundTrip<SpamRule>("""{"sender":{"pattern":"PROMO*"}}""", SpamRule.Sender("PROMO*"))
        roundTrip<SpamRule>("""{"short_code_only":true}""", SpamRule.ShortCodeOnly(true))
    }

    @Test
    fun timelineKeysAndScheduleTargetsRoundTrip() {
        roundTrip<SmsKey>("""{"local":3}""", SmsKey.Local(3))
        roundTrip<SmsKey>("""{"mms":7}""", SmsKey.Mms(7))
        roundTrip<ScheduleTarget>("""{"Sms":{"to":"+90","sub_id":null}}""", ScheduleTarget.Sms("+90", null))
        roundTrip<ScheduleTarget>(
            """{"WhatsApp":{"chat":"1@s.whatsapp.net"}}""",
            ScheduleTarget.WhatsApp("1@s.whatsapp.net"),
        )
    }

    @Test
    fun anUnknownVariantIsRefusedWithItsName() {
        val error = runCatching { json.decodeFromString<SpamRule>("\"nope\"") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("nope"))
    }

    @Test
    fun eventFactoriesWriteOnlyTheirOwnFields() {
        assertEquals("""{"kind":"retry","local_id":4}""", json.encodeToString(TimelineEvent.retry(4)))
        assertEquals(
            """{"kind":"pending","local_id":9,"body":"On my way","sub_id":1,"now_ms":100}""",
            json.encodeToString(TimelineEvent.pending(9, "On my way", 100, subId = 1)),
        )
    }

    @Test
    fun theFacadeSendsEveryCallToItsRustOpName() =
        runBlocking {
            val core = FakeRustCore()
            val ops = NativeMessagesOps(core)
            ops.segments(SegmentsRequest("hi"))
            ops.scheduleLoad(ScheduleLoadRequest())
            ops.tag(TagRequest(tag = "sms:1"))
            assertEquals(listOf("sms.segments", "schedule.load", "messages.tag"), core.calls.map { it.op })
            assertTrue(MessagesOps.ops.containsAll(core.calls.map { it.op }))
        }
}
