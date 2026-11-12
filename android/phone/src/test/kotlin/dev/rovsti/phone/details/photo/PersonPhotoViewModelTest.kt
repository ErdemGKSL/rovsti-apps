package dev.rovsti.phone.details.photo

import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.PhotoPlan
import dev.rovsti.core.model.contacts.PhotoSize
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.design.media.crop.CropFrame
import dev.rovsti.design.media.crop.CropSession
import dev.rovsti.phone.HostRust
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.files.FakeFilesRepository
import dev.rovsti.platform.files.FileResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E5.13 (U10): set, replace and remove reach the contacts repository through the shared crop page's answer; without a
 * write grant (or when the provider refuses) the Contacts editor takes over and nothing is written. The Rust core
 * (`contacts.photo_accepts`, `contacts.photo_plan`) is the real one.
 */
class PersonPhotoViewModelTest {
    private val host by lazy { HostRust("rovsti-person-photo") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    private class Rig(
        host: HostRust,
        scope: CoroutineScope,
        grant: Boolean,
        val repo: FakeContactsRepository =
            FakeContactsRepository(drafts = listOf(ContactDraft(id = 1, given = "Ada"))).also {
                it.photoSizes = mapOf(PICTURE to PhotoSize(4000, 3000))
            },
    ) {
        var granted = grant
        val model =
            PersonPhotoViewModel(
                contactId = 1,
                repo = repo,
                ops = NativeContactsOps(host.core),
                files = FakeFilesRepository(),
                canWrite = { granted },
                scope = scope,
                clock = { 5_000L },
            )

        fun effect(): PersonPhotoEffect = runBlocking { withTimeout(15_000) { model.effects.first() } }

        fun crop(): CropSession = runBlocking { withTimeout(15_000) { model.state.first { it.crop != null }.crop!! } }
    }

    private fun rig(grant: Boolean = true) = Rig(host, scope, grant)

    @Test fun aPickedPictureSetsTheFirstPhotoAfterTheCropPage() {
        val r = rig()
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        assertEquals(CropSession(PICTURE, 4000, 3000, r.crop().maxZoom), r.crop())
        r.model.cropChosen(CropFrame(0, 2.0, 0.5, 0.5))
        assertEquals(PersonPhotoEffect.Changed, r.effect())
        assertEquals(PhotoPlan(x = 1250, y = 750, side = 1500, out = 720, turns = 0), r.repo.photos[1L])
        assertNull(r.model.state.value.crop)
    }

    @Test fun replacingWritesTheNewPlanOverTheOldOne() {
        val r = rig()
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        r.crop()
        r.model.cropChosen(CropFrame(0, 1.0, 0.5, 0.5))
        assertEquals(PersonPhotoEffect.Changed, r.effect())
        val first = r.repo.photos[1L]!!
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        r.crop()
        r.model.cropChosen(CropFrame(1, 2.0, 0.5, 0.5))
        assertEquals(PersonPhotoEffect.Changed, r.effect())
        val second = r.repo.photos[1L]!!
        assertTrue("the second plan replaced the first", second != first)
        assertEquals(1, second.turns)
        assertEquals(1, r.repo.photos.size)
    }

    @Test fun removeReachesTheRepository() {
        val r = rig()
        r.repo.photos[1L] = PhotoPlan(0, 0, 100, 720, 0)
        r.model.remove()
        assertEquals(PersonPhotoEffect.Changed, r.effect())
        assertTrue(r.repo.photos.isEmpty())
    }

    @Test fun cancelOnTheCropPageWritesNothing() {
        val r = rig()
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        r.crop()
        r.model.cropCancelled()
        assertNull(r.model.state.value.crop)
        assertTrue(r.repo.photos.isEmpty())
    }

    @Test fun withoutAWriteGrantEveryEditHandsOffToTheEditor() {
        val r = rig(grant = false)
        assertTrue("the guard says no", !r.model.guard())
        assertEquals(PersonPhotoEffect.HandOff(1), r.effect())
        r.model.remove()
        assertEquals(PersonPhotoEffect.HandOff(1), r.effect())
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        assertEquals(PersonPhotoEffect.HandOff(1), r.effect())
        assertNull("no crop page opened", r.model.state.value.crop)
        assertTrue("nothing was written", r.repo.photos.isEmpty())
    }

    @Test fun aProviderRefusalAfterTheCropHandsOffToo() {
        val r = rig()
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        r.crop()
        r.repo.failWith = ContactResult.Reason.Permission
        r.model.cropChosen(CropFrame(0, 1.0, 0.5, 0.5))
        assertEquals(PersonPhotoEffect.HandOff(1), r.effect())
        assertTrue(r.repo.photos.isEmpty())
    }

    @Test fun otherFailuresSayWhyInsteadOfHandingOff() {
        val r = rig()
        r.repo.failWith = ContactResult.Reason.Unknown
        r.model.remove()
        assertTrue(r.effect() is PersonPhotoEffect.Toast)
        r.model.picked(FileResult.TooLarge)
        assertTrue(r.effect() is PersonPhotoEffect.Toast)
    }

    private companion object {
        const val PICTURE = "/cache/pick-1.jpg"
    }
}
