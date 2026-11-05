package dev.rovsti.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The pure JSON and decision helpers of the Messages seam (step 5). */
class MessagesSeamTest {
    // ---- Documents (attachment pick) -------------------------------------------------------

    @Test fun attachmentPickHasItsOwnOp() {
        assertEquals("attachment", Documents.fileOp(Documents.REQUEST_ATTACH_FILE))
        assertEquals("import_messages", Documents.fileOp(Documents.REQUEST_IMPORT_FILE))
        assertEquals("export_messages", Documents.fileOp(Documents.REQUEST_EXPORT_FILE))
        assertTrue(Documents.REQUEST_ATTACH_FILE != Documents.REQUEST_IMPORT_FILE)
    }

    @Test fun documentResultCarriesMimeOnlyWhenGiven() {
        val o = JSONObject(Documents.resultJson("attachment", true, "a.jpg", path = "/c/x.bin", mime = "image/jpeg"))
        assertEquals("attachment", o.getString("op"))
        assertEquals("image/jpeg", o.getString("mime"))
        assertFalse(JSONObject(Documents.resultJson("import_messages", true, path = "/c/m")).has("mime"))
    }

    // ---- SmsJson ----------------------------------------------------------------------------

    @Test fun deliveryFollowsBoxAndStatus() {
        assertEquals("failed", SmsJson.delivery(5, 0))
        assertEquals("failed", SmsJson.delivery(2, 64))
        assertEquals("delivered", SmsJson.delivery(2, 0))
        assertEquals("pending", SmsJson.delivery(4, -1))
        assertEquals("pending", SmsJson.delivery(2, 32))
        assertEquals("sent", SmsJson.delivery(2, -1))
        assertEquals("none", SmsJson.delivery(1, -1))
    }

    @Test fun smsRowOmitsAnAbsentSim() {
        val o = SmsJson.sms(7, 3, "+905", "hi", 1000, 1, false, null, -1)
        assertEquals(7, o.getLong("id"))
        assertEquals("none", o.getString("delivery"))
        assertFalse(o.has("sub_id"))
        assertEquals(2, SmsJson.sms(1, 1, "a", "b", 1, 2, true, 2, -1).getInt("sub_id"))
    }

    @Test fun recipientIdsSplitOnSpaces() {
        assertEquals(listOf("1", "2"), SmsJson.splitIds(" 1  2 "))
        assertTrue(SmsJson.splitIds(null).isEmpty())
    }

    @Test fun envelopeShapes() {
        val ok = JSONObject(SmsJson.ok(JSONObject().put("threads", JSONArray())))
        assertTrue(ok.getBoolean("ok"))
        assertEquals(WIRE_VERSION, ok.getInt("v"))
        val err = JSONObject(SmsJson.error("permission"))
        assertFalse(err.getBoolean("ok"))
        assertEquals("permission", err.getString("error"))
    }

    @Test fun pageKeepsTheNewestAndFlagsOlder() {
        val sms = (1..3).map { SmsJson.sms(it.toLong(), 1, "a", "b", it * 10L, 1, true, null, -1) }
        val mms = listOf(SmsJson.mms(9, 1, listOf("a"), null, 25, false, true, emptyList(), null))
        val page = SmsJson.page(sms, mms, 2)
        // Newest two overall: sms@30 and mms@25.
        assertEquals(1, page.getJSONArray("sms").length())
        assertEquals(1, page.getJSONArray("mms").length())
        assertTrue(page.getBoolean("has_older"))
        assertFalse(SmsJson.page(sms, mms, 10).getBoolean("has_older"))
    }

    @Test fun boxCodeAcceptsCodesAndNames() {
        assertEquals(2, SmsJson.boxCode(2))
        assertEquals(5, SmsJson.boxCode("failed"))
        assertEquals(1, SmsJson.boxCode(null))
        assertEquals(6, SmsJson.boxCode(99))
    }

    // ---- SmsSend ----------------------------------------------------------------------------

    @Test fun smsRequestParsing() {
        val r = SmsRequest.parse("""{"local_id":5,"to":["+905"," "],"body":"hi","sub_id":2}""")!!
        assertEquals(5L, r.localId)
        assertEquals(listOf("+905"), r.to)
        assertEquals(2, r.subId)
        assertNull(SmsRequest.parse("""{"to":[],"body":"hi"}"""))
        assertNull(SmsRequest.parse("""{"to":["1"],"body":""}"""))
        assertNull(SmsRequest.parse("nope"))
        assertNull(SmsRequest.parse("""{"to":["1"],"body":"x","sub_id":null}""")!!.subId)
    }

    @Test fun statusStatesWaitForTheLastPart() {
        assertNull(SmsSend.stateFor(SmsSend.KIND_SENT, true, 0, 3))
        assertEquals("sent", SmsSend.stateFor(SmsSend.KIND_SENT, true, 2, 3))
        assertEquals("delivered", SmsSend.stateFor(SmsSend.KIND_DELIVERY, true, 0, 1))
        assertEquals("failed", SmsSend.stateFor(SmsSend.KIND_SENT, false, 0, 3))
    }

    @Test fun statusJsonMatchesTheRustShape() {
        val o = JSONObject(SmsSend.statusJson(4, "content://sms/9", "failed", 1))
        assertEquals(4L, o.getLong("local_id"))
        assertEquals("failed", o.getString("state"))
        assertEquals("content://sms/9", o.getString("uri"))
        assertEquals(1, o.getInt("error_code"))
        assertFalse(JSONObject(SmsSend.statusJson(4, null, "sent")).has("uri"))
    }

    // ---- MMS plumbing -----------------------------------------------------------------------

    @Test fun mmsFileNamesAreServedOnlyWhenSafe() {
        assertEquals("send-12.pdu", MmsCodec.fileName("send", 12))
        assertTrue(MmsCodec.isSafeName("recv-12.pdu"))
        assertFalse(MmsCodec.isSafeName("../x"))
        assertFalse(MmsCodec.isSafeName("a/b"))
        assertFalse(MmsCodec.isSafeName("a..b"))
        assertFalse(MmsCodec.isSafeName(""))
    }

    // ---- NotificationSpec -------------------------------------------------------------------

    @Test fun notificationSpecParsing() {
        val spec = NotificationSpec.parse(
            """{"tag":"sms:7","title":"Ali","lines":["a","b"],"otp":"123456","reply":false,"silent":true}""",
        )!!
        assertEquals("sms:7", spec.tag)
        assertEquals(listOf("a", "b"), spec.lines)
        assertEquals("123456", spec.otp)
        assertFalse(spec.reply)
        assertTrue(spec.silent)
        assertNull(NotificationSpec.parse("""{"tag":"x","lines":["a"],"otp":null}""")!!.otp)
        assertNull(NotificationSpec.parse("{}"))
        assertNull(NotificationSpec.parse("""{"tag":"x","lines":[]}"""))
        assertNull(NotificationSpec.parse("not json"))
    }

    @Test fun tagsIdentifyTheConversation() {
        assertEquals(7L, NotificationSpec.smsThreadId("sms:7"))
        assertNull(NotificationSpec.smsThreadId("wa:1@s.whatsapp.net"))
        assertNull(NotificationSpec.smsThreadId("sms:x"))
        assertEquals("1@s.whatsapp.net", NotificationSpec.waChat("wa:1@s.whatsapp.net"))
        assertNull(NotificationSpec.waChat("wa:"))
        assertNull(NotificationSpec.waChat("sms:1"))
    }

    // ---- Scheduled SMS ----------------------------------------------------------------------

    private fun memoryStore(): ScheduledStore {
        val map = HashMap<String, String>()
        return ScheduledStore({ map[it] }, { k, v -> map[k] = v })
    }

    @Test fun scheduledItemParsing() {
        val item = ScheduledItem.parse("""{"id":3,"at_ms":99,"to":"+905","body":"hi","sub_id":1}""")!!
        assertEquals(ScheduledItem(3, 99, "+905", "hi", 1), item)
        assertNull(ScheduledItem.parse("""{"id":0,"to":"+905"}"""))
        assertNull(ScheduledItem.parse("""{"id":1,"to":""}"""))
        assertNull(ScheduledItem.parse("nope"))
        assertEquals(item, ScheduledItem.parse(item.toJson().toString()))
    }

    @Test fun storeArmsReplacesAndDisarms() {
        val store = memoryStore()
        store.arm(ScheduledItem(1, 10, "a", "x", null))
        store.arm(ScheduledItem(2, 20, "b", "y", null))
        store.arm(ScheduledItem(1, 30, "a", "z", null))
        assertEquals(2, store.all().size)
        assertEquals("z", store.get(1)!!.body)
        assertTrue(store.disarm(1))
        assertFalse(store.disarm(1))
        assertNull(store.get(1))
    }

    @Test fun firedListDrainsOnce() {
        val store = memoryStore()
        store.recordFired(1, true, 100)
        store.recordFired(2, false, 200)
        val first = JSONArray(store.drainFired())
        assertEquals(2, first.length())
        assertEquals(false, first.getJSONObject(1).getBoolean("ok"))
        assertEquals(0, JSONArray(store.drainFired()).length())
    }

    // ---- Documents, Headless ----------------------------------------------------------------

    @Test fun fileCapIsLimitedTo64Mb() {
        assertEquals(Documents.MAX_FILE_BYTES, Documents.fileCap(0))
        assertEquals(Documents.MAX_FILE_BYTES, Documents.fileCap(Int.MAX_VALUE))
        assertEquals(1000, Documents.fileCap(1000))
    }

    @Test fun copyLimitedStopsAtTheCap() {
        val out = ByteArrayOutputStream()
        assertTrue(Documents.copyLimited(ByteArrayInputStream(ByteArray(100)), out, 100))
        assertEquals(100, out.size())
        assertFalse(Documents.copyLimited(ByteArrayInputStream(ByteArray(101)), ByteArrayOutputStream(), 100))
    }

    @Test fun documentResultCarriesAPath() {
        val o = JSONObject(Documents.resultJson("import_messages", true, "m.rmb", path = "/c/import.bin"))
        assertEquals("/c/import.bin", o.getString("path"))
        assertFalse(JSONObject(Documents.resultJson("export_messages", true)).has("path"))
    }

    @Test fun anEmptyHeadlessAnswerIsNoDecision() {
        assertNull(Headless.answer(null))
        assertNull(Headless.answer("  "))
        assertEquals("{}", Headless.answer("{}"))
    }
}
