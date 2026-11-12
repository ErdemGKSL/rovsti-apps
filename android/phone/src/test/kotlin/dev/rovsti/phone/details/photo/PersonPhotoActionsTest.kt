package dev.rovsti.phone.details.photo

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.design.media.PhotoSource
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.platform.files.FakeFilesRepository
import dev.rovsti.platform.files.FileResult
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E5.13 (U2): Save writes to MediaStore through the files repository, Share stages the picture; read-only hides Edit. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonPhotoActionsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun actions(
        files: FakeFilesRepository,
        say: MutableList<Any> = mutableListOf(),
        shared: MutableList<Pair<Uri, String>> = mutableListOf(),
    ) = PersonPhotoActions(
        name = "Ayse Yilmaz",
        sources = listOf(Uri.parse("content://contacts/1/display_photo")),
        files = files,
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        say = { say += it },
        share = { uri, mime -> shared += uri to mime },
    )

    @Test fun theFileIsNamedAfterThePerson() {
        assertEquals("Ayse-Yilmaz.jpg", actions(FakeFilesRepository()).fileName)
    }

    @Test fun saveWritesTheFullPhotoToTheGalleryOnceAndSaysSo() {
        val files = FakeFilesRepository(readResult = FileResult.Ok(path = "/cache/a.jpg", mime = "image/jpeg"))
        val said = mutableListOf<Any>()
        val a = actions(files, said)
        a.save()
        assertEquals(listOf(Triple("/cache/a.jpg", "Ayse-Yilmaz.jpg", "image/jpeg")), files.gallery)
        assertEquals(1, said.size)
    }

    @Test fun anUnreadablePhotoSavesNothing() {
        val files = FakeFilesRepository(readResult = FileResult.Failed("unavailable"))
        val said = mutableListOf<Any>()
        actions(files, said).save()
        assertTrue(files.gallery.isEmpty())
        assertEquals(1, said.size)
    }

    @Test fun shareStagesThePictureAndHandsItToTheHost() {
        val staged = Uri.parse("content://dev.rovsti.phone.share/a.jpg")
        val files =
            FakeFilesRepository(readResult = FileResult.Ok(path = "/cache/a.jpg", mime = "image/jpeg"), staged = staged)
        val shared = mutableListOf<Pair<Uri, String>>()
        actions(files, shared = shared).share()
        assertEquals(listOf(staged to "image/jpeg"), shared)
        assertEquals(listOf("/cache/a.jpg" to "Ayse-Yilmaz.jpg"), files.stagedPaths)
    }

    @Test fun readOnlySourcesHaveNoEditButton() {
        val a = actions(FakeFilesRepository())
        val contact = a.viewerActions(context, PhotoSource.Contact, kotlinx.collections.immutable.persistentListOf())
        assertEquals(listOf("save", "share", "edit"), contact.map { it.id })
        val wa = a.viewerActions(context, PhotoSource.WhatsApp, kotlinx.collections.immutable.persistentListOf())
        assertEquals(listOf("save", "share"), wa.map { it.id })
    }

    @Test fun theEditMenuOffersRemoveOnlyWithAPhoto() {
        val menu = PersonPhotoMenu({}, {}, {})
        val with = PersonPhotoActions.editMenu(context, hasPhoto = true, menu = menu)
        val without = PersonPhotoActions.editMenu(context, hasPhoto = false, menu = menu)
        assertEquals(4, with.size)
        assertTrue((with.last() as MenuItem.Action).destructive)
        assertEquals(2, without.size)
    }
}
