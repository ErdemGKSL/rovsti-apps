package dev.rovsti.contacts.people

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.core.model.people.PersonUpdate
import dev.rovsti.people.PeopleContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/** X.1b: what the sibling apps see through `content://dev.rovsti.contacts.people`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PeopleTestApp::class)
class PeopleProviderTest {
    private val app get() = ApplicationProvider.getApplicationContext<PeopleTestApp>()
    private val container get() = app.container
    private val resolver: ContentResolver get() = app.contentResolver

    private val ayse = "905321234567@s.whatsapp.net"
    private val can = "905559876543@s.whatsapp.net"

    private fun fill() =
        runBlocking {
            container.peopleStore.upsert(
                listOf(
                    PersonUpdate(ayse, number = "+905321234567", onWhatsapp = true, pushName = "Ayse"),
                    PersonUpdate(can, number = "+905559876543", onWhatsapp = true, pushName = "Can", pictureId = "7"),
                ),
            )
        }

    private fun names(): List<String?> =
        resolver.query(PeopleContract.peopleUri, PeopleContract.Columns.all, null, null, null)!!.use { c ->
            buildList {
                while (c.moveToNext()) add(PeopleContract.rowOf(c)?.name)
            }
        }

    @Test fun queryAnswersOneRowPerPersonWithTheContractColumns() {
        fill()
        resolver.query(PeopleContract.peopleUri, null, null, null, null)!!.use { c ->
            assertEquals(PeopleContract.Columns.all.toList(), c.columnNames.toList())
            assertEquals(2, c.count)
            c.moveToFirst()
            val first = PeopleContract.rowOf(c)!!
            assertEquals("Ayse", first.name)
            assertEquals(listOf("+905321234567"), first.numbers)
            assertTrue(first.onWhatsapp)
            c.moveToNext()
            val second = PeopleContract.rowOf(c)!!
            assertEquals("Can", second.name)
            assertEquals("7", second.pictureId)
            assertTrue("a new picture id is stale until the file is in", second.pictureStale)
        }
    }

    @Test fun anUnknownPathIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            resolver.query(
                PeopleContract.peopleUri
                    .buildUpon()
                    .appendPath("nope")
                    .build(),
                null,
                null,
                null,
                null,
            )
        }
    }

    @Test fun openFileServesTheStoredPictureReadOnly() {
        fill()
        runBlocking {
            val name = container.peopleStore.store(can, "7", "jpeg-bytes".encodeToByteArray())!!
            container.peopleStore.upsert(listOf(PersonUpdate(can, pictureId = "7", pictureFile = name)))
        }
        val uri = PeopleContract.pictureUri(can, "7")
        resolver.openFileDescriptor(uri, "r")!!.use { fd ->
            java.io
                .FileInputStream(
                    fd.fileDescriptor,
                ).use { assertEquals("jpeg-bytes", it.readBytes().decodeToString()) }
        }
        // Read-only: no write mode, no stream for another picture id (a replaced picture has a new URI).
        assertThrows(FileNotFoundException::class.java) { resolver.openFileDescriptor(uri, "w") }
        assertThrows(FileNotFoundException::class.java) { resolver.openFileDescriptor(uri, "rw") }
        assertThrows(FileNotFoundException::class.java) {
            resolver.openFileDescriptor(PeopleContract.pictureUri(can, "8"), "r")
        }
        assertThrows(FileNotFoundException::class.java) {
            resolver.openFileDescriptor(PeopleContract.pictureUri(ayse, "7"), "r")
        }
    }

    @Test fun openFileNeverLeavesThePictureFolder() {
        fill()
        val outside = File(container.dir.parentFile, "outside.jpg").apply { writeText("secret") }
        runBlocking {
            container.peopleStore.upsert(
                listOf(PersonUpdate(can, pictureId = "7", pictureFile = "../outside.jpg")),
            )
        }
        assertThrows(FileNotFoundException::class.java) {
            resolver.openFileDescriptor(PeopleContract.pictureUri(can, "7"), "r")
        }
        assertTrue(outside.delete())
    }

    @Test fun writesAreRefused() {
        assertThrows(UnsupportedOperationException::class.java) {
            resolver.insert(PeopleContract.peopleUri, android.content.ContentValues())
        }
        assertThrows(
            UnsupportedOperationException::class.java,
        ) { resolver.delete(PeopleContract.peopleUri, null, null) }
        assertThrows(UnsupportedOperationException::class.java) {
            resolver.update(PeopleContract.peopleUri, android.content.ContentValues(), null, null)
        }
    }

    @Test fun theManifestGuardsItWithTheSignaturePermission() {
        val info = app.packageManager.resolveContentProvider(PeopleContract.AUTHORITY, 0)
        assertNotNull("the provider is declared", info)
        assertEquals("dev.rovsti.contacts.people.PeopleProvider", info!!.name)
        assertTrue(info.exported)
        assertEquals(PeopleContract.PERMISSION, info.readPermission)
        assertEquals(PeopleContract.PERMISSION, info.writePermission)
        assertFalse("no URI grants: only the permission opens it", info.grantUriPermissions)
    }

    @Test fun readersAreToldWhenTheCacheChanges() {
        var told = 0
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    told++
                }
            }
        resolver.registerContentObserver(PeopleContract.peopleUri, true, observer)
        container.peopleStore.notifyChanged(resolver)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, told)
        resolver.unregisterContentObserver(observer)
        // The cursor is registered for it too, so a reader's cursor goes stale.
        resolver.query(PeopleContract.peopleUri, null, null, null, null)!!.use {
            assertEquals(PeopleContract.peopleUri, it.notificationUri)
        }
    }

    @Test fun beforeRustInitTheAnswerIsEmptyAndNothingIsCalled() {
        fill()
        container.ready = false
        assertEquals(emptyList<String?>(), names())
        assertEquals(
            "no Rust op ran for the query",
            0,
            container.people.core.calls
                .count { it.op == "people.snapshot" },
        )
        assertNull(runCatching { resolver.openFileDescriptor(PeopleContract.pictureUri(can, "7"), "r") }.getOrNull())
        container.ready = true
        assertEquals(listOf<String?>("Ayse", "Can"), names())
    }

    @Test fun aRustThatNeverGetsReadyIsWaitedForOnlyAShortWhile() {
        fill()
        container.ready = null
        val start = System.nanoTime()
        assertEquals(emptyList<String?>(), names())
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("the wait is bounded, took $ms ms", ms < 10_000)
    }

    @Test fun creatingTheProviderStartsNoRust() {
        // The provider was created with the application (Robolectric builds the manifest providers): no container member
        // was touched by it.
        assertEquals(0, container.people.core.calls.size)
    }
}
