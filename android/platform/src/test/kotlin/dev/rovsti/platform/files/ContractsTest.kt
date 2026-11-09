package dev.rovsti.platform.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContractsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun exportIsACreateDocumentOfTheMime() {
        val i = ExportDocument("text/csv").createIntent(context, "calls.csv")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, i.action)
        assertEquals("text/csv", i.type)
        assertEquals("calls.csv", i.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(i.categories.contains(Intent.CATEGORY_OPENABLE))
        assertEquals("application/octet-stream", ExportDocument("").createIntent(context, "x").type)
    }

    @Test fun jsonImportIsAnOpenDocumentWithTheBackupMimes() {
        val i = ImportDocument(kind = ImportKind.Json).createIntent(context, Unit)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, i.action)
        assertEquals("*/*", i.type)
        assertArrayEquals(
            arrayOf("application/json", "text/plain", "application/octet-stream"),
            i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
    }

    @Test fun contactsImportListsTheVcardMimes() {
        val mimes =
            ImportDocument(
                kind = ImportKind.Contacts,
            ).createIntent(context, Unit).getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!
        assertTrue("text/x-vcard" in mimes && "text/csv" in mimes && mimes.size == 7)
    }

    @Test fun fileImportsHaveNoMimeFilter() {
        for (kind in listOf(ImportKind.Attachment, ImportKind.Gtfs)) {
            val i = ImportDocument(5_000, kind).createIntent(context, Unit)
            assertEquals("*/*", i.type)
            assertEquals(null, i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        }
        assertEquals(5_000, ImportDocument(5_000, ImportKind.Gtfs).cap)
    }

    @Test fun photoPickerIsImagesOnly() {
        val i = PickPhoto().createIntent(context, Unit)
        assertTrue(
            i.action == MediaStore.ACTION_PICK_IMAGES || i.action == Intent.ACTION_OPEN_DOCUMENT ||
                i.action == Intent.ACTION_PICK,
        )
        assertTrue(i.type.orEmpty().startsWith("image/"))
    }

    @Test fun captureIsImageCapture() {
        val i = CapturePhoto { 42 }.createIntent(context, Unit)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, i.action)
        assertTrue(i.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java).toString().endsWith("/camera-42.jpg"))
    }

    @Test fun cancelledMapsToCancelled() {
        val intent = Intent().setData(Uri.parse("content://x/y"))
        val all: List<androidx.activity.result.contract.ActivityResultContract<*, FileResult>> =
            listOf(ExportDocument("a/b"), ImportDocument(), PickPhoto(), CapturePhoto())
        for (c in all) {
            assertEquals(
                c.javaClass.simpleName,
                FileResult.Cancelled,
                c.parseResult(Activity.RESULT_CANCELED, intent),
            )
        }
        assertEquals(FileResult.Cancelled, ImportDocument().parseResult(Activity.RESULT_OK, null))
        assertEquals(FileResult.Cancelled, ExportDocument("a/b").parseResult(Activity.RESULT_OK, Intent()))
    }

    @Test fun anOkResultCarriesTheUri() {
        val uri = Uri.parse("content://docs/1")
        assertEquals(FileResult.Ok(uri = uri), ImportDocument().parseResult(Activity.RESULT_OK, Intent().setData(uri)))
    }

    @Test fun anOversizedStreamIsTooLargeAndAFittingOneIsOk() =
        runBlocking {
            val shadow = org.robolectric.Shadows.shadowOf(context.contentResolver)
            val uri = Uri.parse("content://docs/big")
            val repo = AndroidFilesRepository(context, Dispatchers.Unconfined)
            shadow.registerInputStream(uri, java.io.ByteArrayInputStream(ByteArray(101)))
            assertEquals(FileResult.TooLarge, repo.readUri(uri, 100, text = true))
            shadow.registerInputStream(uri, java.io.ByteArrayInputStream(ByteArray(101)))
            assertEquals(FileResult.TooLarge, repo.readUri(uri, 100, text = false))
            assertTrue(
                File(context.cacheDir, "").listFiles().orEmpty().none {
                    it.name.startsWith("import-") &&
                        it.length() > 100
                },
            )
            shadow.registerInputStream(uri, java.io.ByteArrayInputStream("hi".toByteArray()))
            val ok = repo.readUri(uri, 100, text = true) as FileResult.Ok
            assertEquals("hi", ok.text)
            shadow.registerInputStream(uri, java.io.ByteArrayInputStream("hello".toByteArray()))
            val file = repo.readUri(uri, 100, text = false) as FileResult.Ok
            assertEquals("hello", File(file.path!!).readText())
        }

    @Test fun anUnreadableUriFailsWithoutThrowing() =
        runBlocking {
            val repo = AndroidFilesRepository(context, Dispatchers.Unconfined)
            assertTrue(repo.readUri(Uri.parse("content://nobody/x"), 100, text = true) is FileResult.Failed)
        }

    @Test fun writeExportWritesTextAndFiles() =
        runBlocking {
            val repo = AndroidFilesRepository(context, Dispatchers.Unconfined)
            val uri = Uri.parse("content://docs/out")
            val out = java.io.ByteArrayOutputStream()
            org.robolectric.Shadows
                .shadowOf(context.contentResolver)
                .registerOutputStream(uri, out)
            assertTrue(repo.writeExport(uri, text = "{\"a\":1}") is FileResult.Ok)
            assertEquals("{\"a\":1}", out.toString())
            assertTrue(repo.writeExport(uri) is FileResult.Failed)
        }
}
