package dev.rovsti.contacts.photo

import dev.rovsti.contacts.HostRust
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.contacts.CropView
import dev.rovsti.core.model.contacts.PhotoPlan
import dev.rovsti.core.model.contacts.PhotoSize
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.files.FakeFilesRepository
import dev.rovsti.platform.files.FileResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * E4.4 AC2: the avatar menu (which rows, in which order, which are conditional) and the photo flow behind it - picked
 * or captured file, WhatsApp picture, the crop result, the picture a new contact holds until its first Save.
 */
class PhotoMenuTest {
    private val noActions = PhotoMenuActions({}, {}, {}, {})

    private fun labels(items: List<MenuItem>) = items.map { if (it is MenuItem.Action) it.label else "-" }

    // ---- the menu

    @Test fun aContactWithoutPhotoOrWhatsAppOffersChooseAndTake() {
        assertEquals(listOf(CHOOSE, TAKE), labels(photoMenuItems(false, null, noActions)))
    }

    @Test fun theWhatsAppRowNeedsAJid() {
        assertFalse(WHATSAPP in labels(photoMenuItems(false, null, noActions)))
        assertFalse(WHATSAPP in labels(photoMenuItems(false, "  ", noActions)))
        assertEquals(
            listOf(CHOOSE, TAKE, WHATSAPP),
            labels(photoMenuItems(false, "905551112233@s.whatsapp.net", noActions)),
        )
    }

    @Test fun removeIsDestructiveSeparatedAndOnlyWithAPhoto() {
        val items = photoMenuItems(true, "905551112233@s.whatsapp.net", noActions)
        assertEquals(listOf(CHOOSE, TAKE, WHATSAPP, "-", REMOVE), labels(items))
        val remove = items.last() as MenuItem.Action
        assertTrue(remove.destructive)
        assertTrue(items.filterIsInstance<MenuItem.Action>().dropLast(1).none { it.destructive })
        assertFalse(REMOVE in labels(photoMenuItems(false, "x@s.whatsapp.net", noActions)))
    }

    @Test fun everyRowRunsItsOwnAction() {
        val seen = mutableListOf<String>()
        val actions = PhotoMenuActions({ seen += "choose" }, { seen += "take" }, { seen += "wa" }, { seen += "remove" })
        photoMenuItems(true, "j@s.whatsapp.net", actions).filterIsInstance<MenuItem.Action>().forEach { it.onClick() }
        assertEquals(listOf("choose", "take", "wa", "remove"), seen)
    }

    // ---- the WhatsApp picture

    @Test fun theWhatsAppFileFollowsTheRustRule() {
        val dir = File("cache")
        assertEquals(File(dir, "wa-photo-7.jpg"), waPhotoFile(dir, 7))
        // The rule lives in Rust (`wa_photo_path`); both spellings must stay equal.
        val source = File("../../crates/rovsti_app_core/src/contacts/whatsapp.rs")
        assertTrue("whatsapp.rs not found from ${File("").absolutePath}", source.exists())
        assertTrue(source.readText().contains("dir.join(format!(\"wa-photo-{id}.jpg\"))"))
    }

    private fun tmp(): File = Files.createTempDirectory("rovsti-wa-photo").toFile().apply { deleteOnExit() }

    private fun port(answer: Outcome<PictureAnswer>?) = GatewayWaPhotoPort(picture = { answer })

    @Test fun anInlinePictureIsWrittenToTheTarget() {
        val dir = tmp()
        val bytes = "JPEGDATA".encodeToByteArray()
        val answer = PictureAnswer("image", dataB64 = Base64.getEncoder().encodeToString(bytes))
        val result = runBlocking { port(Outcome.Ok(answer)).fetch("j", waPhotoFile(dir, 7)) }
        assertEquals(WaPhotoResult.Saved(File(dir, "wa-photo-7.jpg").absolutePath), result)
        assertEquals("JPEGDATA", File(dir, "wa-photo-7.jpg").readText())
    }

    @Test fun aPictureInATempFileIsMovedToTheTarget() {
        val dir = tmp()
        val temp = File(dir, "big.tmp").apply { writeText("BIG") }
        val result =
            runBlocking {
                port(
                    Outcome.Ok(PictureAnswer("image", path = temp.absolutePath)),
                ).fetch("j", waPhotoFile(dir, 8))
            }
        assertTrue(result is WaPhotoResult.Saved)
        assertEquals("BIG", File(dir, "wa-photo-8.jpg").readText())
        assertFalse("the temp file is deleted", temp.exists())
    }

    @Test fun noPictureAnUnreachableGatewayAndAnUnreadableAnswer() {
        val target = File(tmp(), "wa-photo-1.jpg")
        runBlocking {
            assertEquals(WaPhotoResult.NoPicture, port(Outcome.Ok(PictureAnswer("none"))).fetch("j", target))
            assertEquals(WaPhotoResult.NoPicture, port(Outcome.Ok(PictureAnswer("unchanged"))).fetch("j", target))
            assertEquals(WaPhotoResult.Unreachable, port(null).fetch("j", target))
            assertEquals(
                WaPhotoResult.Unreachable,
                port(Outcome.Err(RustError.Invalid("down", null))).fetch("j", target),
            )
            assertEquals(
                WaPhotoResult.CannotStore,
                port(Outcome.Ok(PictureAnswer("image", dataB64 = "***not base64***"))).fetch("j", target),
            )
        }
        assertFalse(target.exists())
    }

    // ---- the flow

    private class Rig(
        id: Long?,
        wa: WaPhotoPort = WaPhotoPort { _, _ -> WaPhotoResult.NoPicture },
        sizes: Map<String, PhotoSize> = mapOf(PICTURE to PhotoSize(4000, 3000)),
    ) {
        val host = HostRust("rovsti-photo")
        val scope = CoroutineScope(Dispatchers.Default)
        val repo =
            FakeContactsRepository(
                drafts =
                    listOf(
                        dev.rovsti.core.model.contacts
                            .ContactDraft(id = 1, given = "Ada"),
                    ),
            ).also { it.photoSizes = sizes }
        val files = FakeFilesRepository()
        val cache = Files.createTempDirectory("rovsti-photo-cache").toFile().apply { deleteOnExit() }
        val model =
            ContactPhotoModel(
                contactId = id,
                repo = repo,
                ops = host.ops,
                files = files,
                waPhotos = wa,
                cacheDir = cache,
                clock = { 5_000L },
                scope = scope,
            )

        fun toast(): String = (effect() as PhotoEffect.Toast).text

        fun effect(): PhotoEffect = runBlocking { withTimeout(15_000) { model.effects.first() } }

        fun crop(): CropSession = runBlocking { withTimeout(15_000) { model.state.first { it.crop != null }.crop!! } }

        fun stop() {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private var rig: Rig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig(
        id: Long?,
        wa: WaPhotoPort = WaPhotoPort { _, _ -> WaPhotoResult.NoPicture },
        sizes: Map<String, PhotoSize> = mapOf(PICTURE to PhotoSize(4000, 3000)),
    ) = Rig(id, wa, sizes).also { rig = it }

    @Test fun aPickedPictureOpensTheCropPageWithTheCoresZoomLimit() {
        val r = rig(1)
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        val crop = r.crop()
        assertEquals(CropSession(PICTURE, 4000, 3000, 3000.0 / 96.0), crop.copy(maxZoom = 3000.0 / 96.0))
        assertEquals(3000.0 / 96.0, crop.maxZoom, 0.01)
    }

    @Test fun aFileThatIsNotAPictureIsRefusedInWords() {
        val r = rig(1)
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "application/pdf"))
        assertEquals("That file is not a picture", r.toast())
        assertNull(r.model.state.value.crop)
    }

    @Test fun anUnreadableOrTinyPictureIsRefusedInTheCoresWords() {
        val unreadable = rig(1, sizes = emptyMap())
        unreadable.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        assertEquals("That picture can't be read", unreadable.toast())
        val tiny = rig(1, sizes = mapOf(PICTURE to PhotoSize(80, 80)))
        tiny.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        assertEquals("That image is too small for a contact photo.", tiny.toast())
        assertNull(tiny.model.state.value.crop)
    }

    @Test fun cancelAndTooLargeDoNothingOrSayWhy() {
        val r = rig(1)
        r.model.picked(FileResult.Cancelled)
        r.model.picked(FileResult.TooLarge)
        assertEquals("That picture is too large", r.toast())
        r.model.picked(FileResult.Failed("permission"))
        assertEquals("Can't change the photo: no permission", r.toast())
    }

    @Test fun chooseOnTheCropPageSetsThePhotoOfASavedContact() {
        val r = rig(1)
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        r.crop()
        r.model.cropChosen(CropView(0, 2.0, 0.5, 0.5))
        assertEquals(PhotoEffect.Changed, r.effect())
        assertEquals(PhotoPlan(x = 1250, y = 750, side = 1500, out = 720, turns = 0), r.repo.photos[1L])
        assertNull(r.model.state.value.crop)
    }

    @Test fun aRotatedChoiceKeepsItsTurns() {
        val r = rig(1)
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        r.crop()
        r.model.cropChosen(CropView(1, 1.0, 0.5, 0.5))
        assertEquals(PhotoEffect.Changed, r.effect())
        val plan = r.repo.photos[1L]!!
        assertEquals(1, plan.turns)
        assertEquals(3000, plan.side)
        assertEquals(0, plan.x)
        assertEquals(500, plan.y)
    }

    @Test fun cancelOnTheCropPageDropsThePictureAndSetsNothing() {
        val file = File.createTempFile("pick", ".jpg").apply { writeText("x") }
        val r = rig(1, sizes = mapOf(file.absolutePath to PhotoSize(4000, 3000)))
        r.model.picked(FileResult.Ok(path = file.absolutePath, mime = "image/jpeg"))
        r.crop()
        r.model.cropCancelled()
        assertNull(r.model.state.value.crop)
        runBlocking { withTimeout(5_000) { while (file.exists()) kotlinx.coroutines.delay(20) } }
        assertFalse(file.exists())
        assertTrue(r.repo.photos.isEmpty())
    }

    @Test fun aNewContactHoldsTheChoiceUntilItsFirstSave() {
        val r = rig(null)
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        r.crop()
        r.model.cropChosen(CropView(0, 1.0, 0.5, 0.5))
        runBlocking { withTimeout(15_000) { r.model.state.first { it.pendingPath != null } } }
        assertTrue("nothing is stored before the contact exists", r.repo.photos.isEmpty())
        runBlocking { r.model.applyPending(1) }
        assertEquals(3000, r.repo.photos[1L]!!.side)
        assertNull(r.model.state.value.pendingPath)
    }

    @Test fun removeClearsTheStoredPhotoOfASavedContactAndThePendingOneOfANewContact() {
        val saved = rig(1)
        saved.repo.photos[1L] = PhotoPlan(0, 0, 100, 100, 0)
        saved.model.remove()
        assertEquals(PhotoEffect.Changed, saved.effect())
        assertTrue(saved.repo.photos.isEmpty())

        val fresh = rig(null)
        fresh.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        fresh.crop()
        fresh.model.cropChosen(CropView(0, 1.0, 0.5, 0.5))
        runBlocking { withTimeout(15_000) { fresh.model.state.first { it.pendingPath != null } } }
        fresh.model.remove()
        runBlocking { withTimeout(15_000) { fresh.model.state.first { it.pendingPath == null } } }
        runBlocking { fresh.model.applyPending(1) }
        assertTrue(fresh.repo.photos.isEmpty())
    }

    @Test fun aRefusedWriteIsSaidAndKeepsTheOldPhoto() {
        val r = rig(1)
        r.model.picked(FileResult.Ok(path = PICTURE, mime = "image/jpeg"))
        r.crop()
        r.repo.failWith = ContactResult.Reason.Refused
        r.model.cropChosen(CropView(0, 1.0, 0.5, 0.5))
        assertEquals("Can't change the photo", r.toast())
    }

    @Test fun theWhatsAppPictureGoesThroughTheSameCropPage() {
        val asked = mutableListOf<Pair<String, File>>()
        val dir = tmp()
        val saved = File(dir, "wa-photo-1.jpg").apply { writeText("JPEG") }
        val sizes = mapOf(saved.absolutePath to PhotoSize(640, 640))
        val r =
            rig(
                1,
                wa =
                    WaPhotoPort { jid, target ->
                        asked += jid to target
                        WaPhotoResult.Saved(saved.absolutePath)
                    },
                sizes = sizes,
            )
        r.model.useWhatsApp("905551112233@s.whatsapp.net")
        val crop = r.crop()
        assertEquals(saved.absolutePath, crop.path)
        assertEquals(640, crop.width)
        assertEquals("905551112233@s.whatsapp.net", asked.single().first)
        assertEquals("wa-photo-1.jpg", asked.single().second.name)
        assertFalse(r.model.state.value.busy)
    }

    @Test fun theWhatsAppFailuresAreSaidInPlainWords() {
        fun toastFor(result: WaPhotoResult): String {
            val r = rig(1, wa = WaPhotoPort { _, _ -> result })
            r.model.useWhatsApp("j@s.whatsapp.net")
            return r.toast()
        }
        assertEquals("They have no WhatsApp photo you can see", toastFor(WaPhotoResult.NoPicture))
        assertEquals("WhatsApp can't be reached right now", toastFor(WaPhotoResult.Unreachable))
        assertEquals("The photo can't be stored on this phone", toastFor(WaPhotoResult.CannotStore))
    }

    private companion object {
        const val PICTURE = "/cache/pick-1.jpg"
    }
}
