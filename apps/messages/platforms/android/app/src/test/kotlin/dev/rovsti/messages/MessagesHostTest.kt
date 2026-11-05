package dev.rovsti.messages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the Messages host: intent payloads, Rust answers and notification specs. */
class MessagesHostTest {
    @Test fun recipientsSplitDecodeAndDropBlanks() {
        assertEquals(listOf("+905551112233", "05551112244"), ComposeRequest.recipients("+905551112233,05551112244?body=hi"))
        assertEquals(listOf("+905551112233"), ComposeRequest.recipients("%2B905551112233;,"))
        assertEquals(emptyList<String>(), ComposeRequest.recipients(null))
        assertEquals(emptyList<String>(), ComposeRequest.recipients("?body=x"))
    }

    @Test fun plusStaysAPlusAndPercentIsUtf8() {
        assertEquals("+90 5", ComposeRequest.percentDecode("+90%205"))
        assertEquals("Çay", ComposeRequest.percentDecode("%C3%87ay"))
        assertEquals("100%", ComposeRequest.percentDecode("100%"))
        assertEquals("%zz", ComposeRequest.percentDecode("%zz"))
        assertEquals("%-1", ComposeRequest.percentDecode("%-1"))
    }

    @Test fun queryBodyIsFound() {
        assertEquals("hi there", ComposeRequest.queryBody("+90555?subject=x&body=hi%20there"))
        assertNull(ComposeRequest.queryBody("+90555"))
        assertNull(ComposeRequest.queryBody("+90555?body="))
    }

    @Test fun sendtoBecomesANewConversation() {
        val json = ComposeRequest.build("android.intent.action.SENDTO", "+90555?body=yo", null, null, null, null)
        val o = JSONObject(json!!)
        assertEquals("new", o.getString("tag"))
        assertEquals("+90555", o.getJSONArray("to").getString(0))
        assertEquals("yo", o.getString("body"))
    }

    @Test fun sendtoBodyFallsBackToExtras() {
        val o = JSONObject(ComposeRequest.build("android.intent.action.SENDTO", "+90555", "from text", "from sms_body", null, null)!!)
        assertEquals("from sms_body", o.getString("body"))
        val t = JSONObject(ComposeRequest.build("android.intent.action.SENDTO", "+90555", "from text", null, null, null)!!)
        assertEquals("from text", t.getString("body"))
    }

    @Test fun sendtoWithoutRecipientIsIgnored() {
        assertNull(ComposeRequest.build("android.intent.action.SENDTO", "", "x", null, null, null))
    }

    @Test fun sendIsAShareWithNoRecipient() {
        val o = JSONObject(ComposeRequest.build("android.intent.action.SEND", null, "look", null, "content://x/1", "image/png")!!)
        assertEquals("share", o.getString("tag"))
        assertEquals(0, o.getJSONArray("to").length())
        assertEquals("look", o.getString("body"))
        assertEquals("content://x/1", o.getString("stream_uri"))
        assertEquals("image/png", o.getString("mime"))
        assertNull(ComposeRequest.build("android.intent.action.SEND", null, "", null, null, "text/plain"))
        assertNull(ComposeRequest.build("android.intent.action.MAIN", "+90555", "x", null, null, null))
    }

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

    @Test fun announcementNeedsALocation() {
        val ok = MmsAnnouncement.parse("""{"ok":true,"transaction_id":"T1","content_location":"http://mms.example/m1"}""")
        assertEquals(MmsAnnouncement("T1", "http://mms.example/m1"), ok)
        assertNull(MmsAnnouncement.parse("""{"ok":false,"error":"truncated"}"""))
        assertNull(MmsAnnouncement.parse("""{"ok":true}"""))
        assertNull(MmsAnnouncement.parse(""))
        assertNull(MmsAnnouncement.parse(null))
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
