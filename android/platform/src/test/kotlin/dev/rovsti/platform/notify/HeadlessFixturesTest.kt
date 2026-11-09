package dev.rovsti.platform.notify

import dev.rovsti.core.Envelope
import dev.rovsti.core.model.headless.MsgRemindRequest
import dev.rovsti.core.model.headless.WaReplyRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The requests the notification code builds decode (strictly, unknown keys fail) into the models of the headless
 * goldens Rust and `:core-bridge` share (`crates/rovsti_ffi/fixtures/headless.*.request.json`).
 */
class HeadlessFixturesTest {
    private val dir = File(checkNotNull(System.getProperty("rovsti.ffi.fixtures")) { "rovsti.ffi.fixtures not set" })

    private fun golden(name: String) = File(dir, name).readText()

    @Test fun msgRemindRequestDecodesAndMatchesTheGoldenShape() {
        val built =
            ReplyActions.remindRequest(
                "sms:7",
                "Ayse",
                "Call me",
                nowMs = 1_700_000_000_000,
                tzOffsetS = 10_800,
            )
        val model = Envelope.json.decodeFromString(MsgRemindRequest.serializer(), built)
        val golden =
            Envelope.json.decodeFromString(
                MsgRemindRequest.serializer(),
                golden("headless.msg_remind.request.json"),
            )
        // Same tag, title, body, clock and zone; the golden asks for 30 minutes, the notification action for 60.
        assertEquals(golden.copy(minutes = 60), model)
        assertEquals(
            setOf("tag", "title", "body", "minutes", "now_ms", "tz_offset_s"),
            org.json
                .JSONObject(built)
                .keys()
                .asSequence()
                .toSet(),
        )
    }

    @Test fun waReplyRequestDecodesAndMatchesTheGolden() {
        val built = ReplyActions.waReplyRequest("\$DIR", "905551112233@s.whatsapp.net", "ok")
        val model = Envelope.json.decodeFromString(WaReplyRequest.serializer(), built)
        assertEquals(
            Envelope.json.decodeFromString(WaReplyRequest.serializer(), golden("headless.wa_reply.request.json")),
            model,
        )
    }
}
