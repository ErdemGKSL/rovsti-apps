package dev.rovsti.contacts.photo

import androidx.compose.runtime.Immutable
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.CropView
import dev.rovsti.core.model.contacts.PhotoAcceptsRequest
import dev.rovsti.core.model.contacts.PhotoPlan
import dev.rovsti.core.model.contacts.PhotoPlanRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.FilesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A picture on its way to being a contact photo: the file, its size after EXIF rotation and how far it may be zoomed. */
@Immutable
data class CropSession(
    val path: String,
    val width: Int,
    val height: Int,
    val maxZoom: Double,
)

@Immutable
data class PhotoUiState(
    /** The crop page is open for this picture. */
    val crop: CropSession? = null,
    /** A WhatsApp picture is being fetched. */
    val busy: Boolean = false,
    /** A new contact holds a cropped picture until its first Save (the file; null when there is none). */
    val pendingPath: String? = null,
)

sealed interface PhotoEffect {
    data class Toast(
        val text: String,
    ) : PhotoEffect

    /** The contact's stored photo changed (the book follows by itself). */
    data object Changed : PhotoEffect
}

/**
 * The photo of one contact in the editor (E4.4 AC2). A picture arrives from the Photo Picker (no media permission), the
 * camera ([FileResult] with a path) or WhatsApp; the Rust core decides whether it is usable (`contacts.photo_accepts`,
 * `contacts.photo_plan`), the crop page lets the owner move and scale it, and `contacts.photo_plan` turns what was
 * shown into the square to cut, which the provider cuts and stores ([ContactsRepository.setPhoto]). A saved contact
 * gets the photo at once; a new one ([contactId] null) holds it until the first Save returns the id ([applyPending]).
 */
class ContactPhotoModel(
    private val contactId: Long?,
    private val repo: ContactsRepository,
    private val ops: ContactsOps,
    private val files: FilesRepository,
    private val waPhotos: WaPhotoPort,
    private val cacheDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ui = MutableStateFlow(PhotoUiState())
    val state: StateFlow<PhotoUiState> = ui.asStateFlow()

    private val outbox = Channel<PhotoEffect>(Channel.UNLIMITED)
    val effects: Flow<PhotoEffect> = outbox.receiveAsFlow()

    private var pendingPlan: PhotoPlan? = null

    private fun say(text: String) {
        outbox.trySend(PhotoEffect.Toast(text))
    }

    /** The answer of the picker or the camera. */
    fun picked(result: FileResult) {
        scope.launch {
            when (result) {
                FileResult.Cancelled -> Unit
                FileResult.TooLarge -> say("That picture is too large")
                is FileResult.Failed -> say("Can't change the photo: ${words(result.reason)}")
                is FileResult.Ok -> open(result)
            }
        }
    }

    private suspend fun open(result: FileResult.Ok) {
        val limits = (ops.photoAccepts(PhotoAcceptsRequest(null, clock())) as? Outcome.Ok)?.value
        var path = result.path
        var mime = result.mime
        val uri = result.uri
        if (path == null && uri != null) {
            val cap = (limits?.maxPickBytes ?: DEFAULT_CAP).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (val read = files.readUri(uri, cap)) {
                is FileResult.Ok -> {
                    path = read.path
                    mime = read.mime ?: mime
                }

                FileResult.TooLarge -> {
                    return say("That picture is too large")
                }

                is FileResult.Failed -> {
                    return say("Can't change the photo: ${words(read.reason)}")
                }

                FileResult.Cancelled -> {
                    return
                }
            }
        }
        if (path == null) return say("Can't change the photo: the picture is not available")
        begin(path, mime)
    }

    /** Checks the picture at [path] and opens the crop page for it. */
    private suspend fun begin(
        path: String,
        mime: String?,
    ) {
        val accepts = (ops.photoAccepts(PhotoAcceptsRequest(mime, clock())) as? Outcome.Ok)?.value?.accepts ?: false
        if (!accepts) {
            discard(path)
            return say("That file is not a picture")
        }
        val size = repo.photoSize(path)
        if (size.width <= 0 || size.height <= 0) {
            discard(path)
            return say("That picture can't be read")
        }
        val plan = (ops.photoPlan(PhotoPlanRequest(size.width, size.height)) as? Outcome.Ok)?.value
        if (plan == null || plan.plan == null) {
            discard(path)
            return say(plan?.error ?: "That picture can't be used")
        }
        ui.update { it.copy(crop = CropSession(path, size.width, size.height, plan.maxZoom)) }
    }

    /** "Use WhatsApp Photo": fetches the large picture of [jid] and opens the crop page for it. */
    fun useWhatsApp(jid: String) {
        if (ui.value.busy) return
        scope.launch {
            ui.update { it.copy(busy = true) }
            val target = waPhotoFile(cacheDir, contactId ?: 0L)
            val fetched = waPhotos.fetch(jid, target)
            ui.update { it.copy(busy = false) }
            when (fetched) {
                is WaPhotoResult.Saved -> begin(fetched.path, "image/jpeg")
                WaPhotoResult.NoPicture -> say("They have no WhatsApp photo you can see")
                WaPhotoResult.Unreachable -> say("WhatsApp can't be reached right now")
                WaPhotoResult.CannotStore -> say("The photo can't be stored on this phone")
            }
        }
    }

    /** Choose on the crop page: [view] is what the owner saw. */
    fun cropChosen(view: CropView) {
        val session = ui.value.crop ?: return
        scope.launch {
            ui.update { it.copy(crop = null) }
            val answer =
                (ops.photoPlan(PhotoPlanRequest(session.width, session.height, view)) as? Outcome.Ok)?.value
            val plan = answer?.plan
            if (plan == null) {
                discard(session.path)
                return@launch say(answer?.error ?: "That picture can't be used")
            }
            val id = contactId
            if (id == null) {
                pendingPlan = plan
                ui.update { it.copy(pendingPath = session.path) }
                return@launch
            }
            when (repo.setPhoto(id, session.path, plan)) {
                is ContactResult.Ok -> outbox.trySend(PhotoEffect.Changed)
                is ContactResult.Failed -> say("Can't change the photo")
            }
        }
    }

    /** Cancel (or Back) on the crop page. */
    fun cropCancelled() {
        val session = ui.value.crop ?: return
        ui.update { it.copy(crop = null) }
        scope.launch { discard(session.path) }
    }

    /** "Remove Photo": a saved contact loses its stored photo, a new one drops the picture it was holding. */
    fun remove() {
        scope.launch {
            val id = contactId
            if (id == null) {
                ui.value.pendingPath?.let { discard(it) }
                pendingPlan = null
                ui.update { it.copy(pendingPath = null) }
                return@launch
            }
            when (repo.removePhoto(id)) {
                is ContactResult.Ok -> outbox.trySend(PhotoEffect.Changed)
                is ContactResult.Failed -> say("Can't remove the photo")
            }
        }
    }

    /** The first Save of a new contact returned [id]: the picture it was holding becomes its photo. */
    suspend fun applyPending(id: Long) {
        val path = ui.value.pendingPath ?: return
        val plan = pendingPlan ?: return
        pendingPlan = null
        ui.update { it.copy(pendingPath = null) }
        when (repo.setPhoto(id, path, plan)) {
            is ContactResult.Ok -> outbox.trySend(PhotoEffect.Changed)
            is ContactResult.Failed -> say("The contact was saved, but its photo was not")
        }
    }

    private suspend fun discard(path: String) {
        withContext(io) { File(path).delete() }
    }

    private fun words(reason: String): String =
        when (reason) {
            "permission" -> "no permission"
            "unavailable" -> "the picture is not available"
            else -> "something went wrong"
        }

    private companion object {
        const val DEFAULT_CAP = 20L * 1024 * 1024
    }
}
