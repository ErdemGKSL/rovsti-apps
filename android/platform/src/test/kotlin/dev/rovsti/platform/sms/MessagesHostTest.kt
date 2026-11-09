package dev.rovsti.platform.sms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moved from `apps/messages/.../MessagesHostTest`: the pure parts of the incoming-message path (Rust answers and
 * notification specs). The compose-intent and WAP-push cases stay with the Messages app (E6).
 */
class MessagesHostTest {
    @Test fun verdictFallsBackToHam() {
        assertEquals(Verdict.HAM, Verdict.parse(null))
        assertEquals(Verdict.HAM, Verdict.parse(""))
        assertEquals(Verdict.HAM, Verdict.parse("{oops"))
        val v = Verdict.parse("""{"spam":true,"reason":"keyword_rule","otp":null}""")
        assertTrue(v.spam)
        assertNull(v.otp)
        assertEquals("482913", Verdict.parse("""{"spam":false,"otp":"482913"}""").otp)
    }

    @Test fun classifyRequestCarriesEverythingRustReads() {
        val o = JSONObject(IncomingNotice.classifyRequest("/data", "BANK", "code 1", known = true, blocked = false))
        assertEquals("/data", o.getString("dir"))
        assertEquals("BANK", o.getString("sender"))
        assertEquals("code 1", o.getString("body"))
        assertTrue(o.getBoolean("known"))
        assertFalse(o.getBoolean("blocked"))
    }

    @Test fun specOffersReplyUnlessThereIsACode() {
        val plain = JSONObject(IncomingNotice.spec(7, "Ali", listOf("a", "b"), null))
        assertEquals("sms:7", plain.getString("tag"))
        assertTrue(plain.getBoolean("reply"))
        assertFalse(plain.has("otp"))
        val code = JSONObject(IncomingNotice.spec(7, "Bank", listOf("123456"), "123456"))
        assertFalse(code.getBoolean("reply"))
        assertEquals("123456", code.getString("otp"))
    }

    @Test fun linesKeepTheNewestAndFallBack() {
        assertEquals(listOf("3", "4", "5"), IncomingNotice.lastLines(listOf("1", "2", "3", "4", "5"), "x", 3))
        assertEquals(listOf("fallback"), IncomingNotice.lastLines(emptyList(), "fallback"))
        assertEquals("Photo", IncomingNotice.attachmentLine(listOf("text/plain", "image/jpeg")))
        assertEquals("Attachment", IncomingNotice.attachmentLine(listOf("application/pdf")))
    }

    @Test fun retrievedMessageParsesPartsAndBytes() {
        val answer = """{"ok":true,"transaction_id":"R7","from":"+4915112345678","to":["+15550001111"],"subject":"Hi",
            "date_s":1700000000,"text":"Hello","spam":false,"otp":null,
            "parts":[{"content_type":"text/plain","name":null,"text":"Hello"},
                     {"content_type":"image/jpeg","name":"a.jpg","data_b64":"/9j/2Q=="}]}"""
        val r = Retrieved.parse(answer)!!
        assertEquals("+4915112345678", r.from)
        assertEquals(listOf("+15550001111"), r.to)
        assertEquals("Hi", r.subject)
        assertEquals(1700000000L, r.dateSeconds)
        assertEquals("Hello", r.parts[0].text)
        assertNull(r.parts[0].data)
        assertEquals(4, r.parts[1].data!!.size)
        assertEquals("a.jpg", r.parts[1].name)
        assertFalse(r.verdict.spam)
    }

    @Test fun retrievedFailureIsNull() {
        assertNull(Retrieved.parse("""{"ok":false,"error":"wrong_type"}"""))
        assertNull(Retrieved.parse("not json"))
        assertNull(Retrieved.parse(null))
    }
}
