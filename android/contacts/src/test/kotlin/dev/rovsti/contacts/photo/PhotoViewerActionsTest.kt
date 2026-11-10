package dev.rovsti.contacts.photo

import android.content.Context
import android.net.Uri
import dev.rovsti.contacts.en
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.files.FakeFilesRepository
import dev.rovsti.platform.files.FileResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** E4.12: the viewer's Save writes the gallery once, Share stages a JPEG, and the Edit menu follows the state. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoViewerActionsTest {
    private val full = Uri.parse("content://com.android.contacts/contacts/5/display_photo")
    private val thumb = Uri.parse("content://com.android.contacts/contacts/5/photo")
    private val files =
        FakeFilesRepository(
            readResult = FileResult.Ok(path = "/cache/p.jpg", mime = "image/jpeg"),
            staged = Uri.parse("content://dev.rovsti.contacts.share/zeynep.jpg"),
        )
    private val context: Context = RuntimeEnvironment.getApplication()
    private val said = mutableListOf<UiText>()
    private val shared = mutableListOf<Pair<Uri, String>>()

    private fun actions(scope: TestScope) =
        PhotoViewerActions(
            contactName = "Zeynep Arslan",
            sources = listOf(full, thumb),
            files = files,
            scope = scope,
            say = { said += it },
            share = { uri, mime -> shared += uri to mime },
        )

    private val noop = PhotoMenuActions({}, {}, {}, {})

    private fun labels(items: List<MenuItem>) = items.filterIsInstance<MenuItem.Action>().map { it.label }

    @Test fun theFullPhotoIsTheDisplayPhotoOfTheContact() {
        assertEquals(full, fullPhotoUri(5))
    }

    @Test fun theFileIsNamedAfterTheContact() =
        runTest {
            assertEquals("Zeynep-Arslan.jpg", actions(this).fileName)
            val blank = PhotoViewerActions("  ", listOf(full), files, this, {}, { _, _ -> })
            assertEquals("contact.jpg", blank.fileName)
        }

    @Test fun saveWritesTheGalleryOnceAndSaysSo() =
        runTest(StandardTestDispatcher()) {
            val a = actions(this)
            a.save()
            a.save() // a second tap while the first runs is ignored
            advanceUntilIdle()
            assertEquals(listOf(Triple("/cache/p.jpg", "Zeynep-Arslan.jpg", "image/jpeg")), files.gallery)
            assertEquals(listOf("Saved to Photos"), said.map { it.en() })
            assertEquals("the full-size photo is read first", full, files.reads.first().first)
        }

    @Test fun aFailedSaveIsToldAndCanBeTriedAgain() =
        runTest(StandardTestDispatcher()) {
            files.galleryOk = false
            val a = actions(this)
            a.save()
            advanceUntilIdle()
            assertEquals(listOf("Can't save the photo"), said.map { it.en() })
            files.galleryOk = true
            a.save()
            advanceUntilIdle()
            assertEquals("Saved to Photos", said.last().en())
        }

    @Test fun anUnreadableFullPhotoFallsBackToTheThumbnail() =
        runTest(StandardTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            files.readResult = FileResult.Failed("unavailable")
            val a = actions(this)
            a.save()
            advanceUntilIdle()
            assertEquals("both sources were tried", listOf(full, thumb), files.reads.map { it.first })
            assertEquals(listOf("Can't save the photo"), said.map { it.en() })
            assertTrue(files.gallery.isEmpty())
            gate.complete(Unit)
        }

    @Test fun shareStagesTheJpegAndHandsItOver() =
        runTest(StandardTestDispatcher()) {
            actions(this).share()
            advanceUntilIdle()
            assertEquals(listOf("/cache/p.jpg" to "Zeynep-Arslan.jpg"), files.stagedPaths)
            assertEquals(listOf(files.staged!! to "image/jpeg"), shared)
        }

    @Test fun aShareThatCannotBeStagedSaysSo() =
        runTest(StandardTestDispatcher()) {
            files.staged = null
            actions(this).share()
            advanceUntilIdle()
            assertTrue(shared.isEmpty())
            assertEquals(listOf("Can't share the photo"), said.map { it.en() })
        }

    @Test fun theToolbarHasSaveShareAndAnEditMenu() =
        runTest {
            val a = actions(this)
            val menu = a.editMenu(context, "x@s.whatsapp.net", noop) { it() }
            val toolbar = a.viewerActions(context, menu)
            assertEquals(listOf("save", "share", "edit"), toolbar.map { it.id })
            assertEquals(listOf("Save", "Share", "Edit"), toolbar.map { it.label })
            assertTrue("only Edit opens a menu", toolbar.map { it.menu != null } == listOf(false, false, true))
        }

    @Test fun theEditMenuFollowsTheWhatsAppState() =
        runTest {
            val a = actions(this)
            assertEquals(listOf(CHOOSE.en(), TAKE.en(), REMOVE.en()), labels(a.editMenu(context, null, noop) { it() }))
            assertEquals(
                listOf(CHOOSE.en(), TAKE.en(), WHATSAPP.en(), REMOVE.en()),
                labels(a.editMenu(context, "905551112233@s.whatsapp.net", noop) { it() }),
            )
        }

    @Test fun removeAsksBeforeItRemoves() =
        runTest {
            var removed = 0
            var asked: (() -> Unit)? = null
            val a = actions(this)
            val menu = a.editMenu(context, null, PhotoMenuActions({}, {}, {}, { removed++ })) { asked = it }
            val remove = menu.filterIsInstance<MenuItem.Action>().first { it.label == REMOVE.en() }
            assertTrue(remove.destructive)
            remove.onClick()
            assertEquals("the menu only asks", 0, removed)
            asked!!.invoke()
            assertEquals(1, removed)
            assertFalse(said.isNotEmpty())
        }
}
