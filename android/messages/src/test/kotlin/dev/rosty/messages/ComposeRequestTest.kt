package dev.rosty.messages

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The `sms:`/`smsto:` parsing (ported from the GPUI app's ComposeRequestTest) and the quick-reply request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeRequestTest {
    @Test fun recipientsSplitAndDecodeButKeepThePlus() {
        assertEquals(
            listOf("+905550000001", "+905550000002"),
            ComposeRequest.recipients("+905550000001,%2B905550000002"),
        )
        assertEquals(listOf("+905550000001"), ComposeRequest.recipients("+905550000001?body=x"))
        assertEquals(emptyList<String>(), ComposeRequest.recipients(" , ;"))
    }

    @Test fun theBodyComesFromTheQueryThenTheExtras() {
        assertEquals("a b", ComposeRequest.queryBody("1?Body=a%20b"))
        assertEquals(
            ComposeRequest.New(listOf("1"), "extra"),
            ComposeRequest.build(Intent.ACTION_SENDTO, "1", null, "extra", null, null),
        )
        assertEquals(
            ComposeRequest.New(listOf("1"), "ç"),
            ComposeRequest.build(Intent.ACTION_VIEW, "1?body=%C3%A7", "x", "y", null, null),
        )
        assertNull(ComposeRequest.build(Intent.ACTION_SENDTO, "", "x", null, null, null))
    }

    @Test fun aShareNeedsTextOrAStream() {
        assertEquals(
            ComposeRequest.Share(null, "content://x/1", "image/png"),
            ComposeRequest.build(Intent.ACTION_SEND, null, null, null, "content://x/1", "image/png"),
        )
        assertNull(ComposeRequest.build(Intent.ACTION_SEND, null, "", null, "", null))
    }

    @Test fun theQuickReplyNeedsAnActionARecipientAndText() {
        val ok =
            HeadlessSmsSendService.requestOf(
                HeadlessSmsSendService.ACTION_RESPOND_VIA_MESSAGE,
                "+905550000001",
                "late",
                5,
            )
        assertEquals(listOf("+905550000001"), ok?.to)
        assertEquals("late", ok?.body)
        assertNull(HeadlessSmsSendService.requestOf("other", "1", "late", 5))
        assertNull(HeadlessSmsSendService.requestOf(HeadlessSmsSendService.ACTION_RESPOND_VIA_MESSAGE, "1", " ", 5))
        assertNull(HeadlessSmsSendService.requestOf(HeadlessSmsSendService.ACTION_RESPOND_VIA_MESSAGE, "", "late", 5))
    }

    @Test fun anMmsAnnouncementNeedsALocation() {
        assertEquals(
            MmsAnnouncement("t1", "http://mmsc/x"),
            MmsAnnouncement.parse("""{"ok":true,"transaction_id":"t1","content_location":"http://mmsc/x"}"""),
        )
        assertNull(MmsAnnouncement.parse("""{"ok":false,"content_location":"http://mmsc/x"}"""))
        assertNull(MmsAnnouncement.parse("not json"))
        assertNull(MmsAnnouncement.parse(null))
    }
}
