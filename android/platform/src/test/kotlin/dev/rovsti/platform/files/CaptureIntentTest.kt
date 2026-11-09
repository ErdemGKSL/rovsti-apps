package dev.rovsti.platform.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureIntentTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun theOutputUriIsOurProviderAndTheWriteGrantIsSet() {
        val intent = CapturePhoto { 7 }.createIntent(context, Unit)
        val uri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, android.net.Uri::class.java)!!
        assertEquals("${context.packageName}.rovstishare", uri.authority)
        assertEquals("content", uri.scheme)
        assertEquals("camera-7.jpg", uri.lastPathSegment)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(File(MediaShare.dir(context), "camera-7.jpg").isFile)
        assertTrue(MediaShare.writable(uri.lastPathSegment!!))
    }

    @Test fun aTakenPhotoIsAJpegPath() {
        val contract = CapturePhoto { 8 }
        contract.createIntent(context, Unit)
        val file = File(MediaShare.dir(context), "camera-8.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val ok = contract.parseResult(Activity.RESULT_OK, null) as FileResult.Ok
        assertEquals(file.absolutePath, ok.path)
        assertEquals("image/jpeg", ok.mime)
    }

    @Test fun anEmptyFileOrACancelIsCancelledAndTheFileIsDeleted() {
        val contract = CapturePhoto { 9 }
        contract.createIntent(context, Unit)
        assertEquals(FileResult.Cancelled, contract.parseResult(Activity.RESULT_OK, null))
        assertFalse(File(MediaShare.dir(context), "camera-9.jpg").exists())
        contract.createIntent(context, Unit)
        assertEquals(FileResult.Cancelled, contract.parseResult(Activity.RESULT_CANCELED, null))
        assertFalse(File(MediaShare.dir(context), "camera-9.jpg").exists())
    }

    @Test fun shareIntentsKeepTheGrantAndChooser() {
        val uri = MediaShare.uriFor(context, "a.png")
        val chooser = ShareIntents.image(uri, "", "Pic")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/*", send.type)
        assertEquals("Pic", send.getStringExtra(Intent.EXTRA_TITLE))
        assertNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java))
        assertEquals(
            "*/*",
            ShareIntents.file(uri, "", "").getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!.type,
        )
        val text = ShareIntents.text("hello").getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("text/plain", text.type)
        assertEquals("hello", text.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun composerIntents() {
        val sms = ShareIntents.sendSmsTo("+905551112233", "hi")
        assertEquals(Intent.ACTION_SENDTO, sms.action)
        assertEquals("smsto", sms.scheme)
        assertEquals("+905551112233", sms.data!!.schemeSpecificPart)
        assertEquals("hi", sms.getStringExtra("sms_body"))
        assertEquals(null, ShareIntents.sendSmsTo("1").getStringExtra("sms_body"))
        assertEquals("a@b.c", ShareIntents.sendEmailTo("a@b.c").data!!.schemeSpecificPart)
    }
}
