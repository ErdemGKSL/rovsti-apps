package dev.rovsti.phone.details.photo

import androidx.compose.runtime.Immutable
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.CropView
import dev.rovsti.core.model.contacts.PhotoAcceptsRequest
import dev.rovsti.core.model.contacts.PhotoPlanRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.design.media.crop.CropFrame
import dev.rovsti.design.media.crop.CropSession
import dev.rovsti.design.text.UiText
import dev.rovsti.phone.R
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

@Immutable
data class PersonPhotoUi(
    /** The crop page is open for this picture. */
    val crop: CropSession? = null,
)

sealed interface PersonPhotoEffect {
    data class Toast(
        val text: UiText,
    ) : PersonPhotoEffect

    /** The contact's stored photo changed (the book follows by itself). */
    data object Changed : PersonPhotoEffect

    /**
     * The app may not write contacts: the Contacts editor of [contactId] takes over (the system editor, which holds the
     * write grant itself).
     */
    data class HandOff(
        val contactId: Long,
    ) : PersonPhotoEffect
}

/**
 * Set, replace and remove the photo of one contact from the sheets' photo viewer (E5.13, U10). A picture arrives from
 * the Photo Picker or the camera ([picked]); the Rust core decides whether it is usable (`contacts.photo_accepts`,
 * `contacts.photo_plan`), the shared crop page lets the owner move, scale and rotate it, and the square the core plans
 * from what was shown is cut and stored by [ContactsRepository.setPhoto]. Setting a first photo and replacing one are
 * the same write.
 *
 * Without a write grant ([canWrite] false, or the provider answering [ContactResult.Reason.Permission]) nothing is
 * written: [PersonPhotoEffect.HandOff] asks the host to open the Contacts editor of the contact instead.
 */
class PersonPhotoViewModel(
    val contactId: Long,
    private val repo: ContactsRepository,
    private val ops: ContactsOps,
    private val files: FilesRepository,
    private val canWrite: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ui = MutableStateFlow(PersonPhotoUi())
    val state: StateFlow<PersonPhotoUi> = ui.asStateFlow()

    private val outbox = Channel<PersonPhotoEffect>(Channel.UNLIMITED)
    val effects: Flow<PersonPhotoEffect> = outbox.receiveAsFlow()

    private fun say(text: UiText) {
        outbox.trySend(PersonPhotoEffect.Toast(text))
    }

    private fun handOff() {
        outbox.trySend(PersonPhotoEffect.HandOff(contactId))
    }

    /** True when the app may write the photo; else the hand-off is sent and the caller stops. */
    fun guard(): Boolean {
        if (canWrite()) return true
        handOff()
        return false
    }

    /** The answer of the picker or the camera. */
    fun picked(result: FileResult) {
        scope.launch {
            when (result) {
                FileResult.Cancelled -> Unit
                FileResult.TooLarge -> say(UiText.Res(R.string.photo_too_large))
                is FileResult.Failed -> say(UiText.Res(R.string.photo_cant_change))
                is FileResult.Ok -> open(result)
            }
        }
    }

    private suspend fun open(result: FileResult.Ok) {
        if (!guard()) return
        var path = result.path
        var mime = result.mime
        val uri = result.uri
        if (path == null && uri != null) {
            val limits = (ops.photoAccepts(PhotoAcceptsRequest(null, clock())) as? Outcome.Ok)?.value
            val cap = (limits?.maxPickBytes ?: DEFAULT_CAP).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (val read = files.readUri(uri, cap)) {
                is FileResult.Ok -> {
                    path = read.path
                    mime = read.mime ?: mime
                }

                FileResult.TooLarge -> {
                    return say(UiText.Res(R.string.photo_too_large))
                }

                is FileResult.Failed -> {
                    return say(UiText.Res(R.string.photo_cant_change))
                }

                FileResult.Cancelled -> {
                    return
                }
            }
        }
        if (path == null) return say(UiText.Res(R.string.photo_cant_change))
        begin(path, mime)
    }

    private suspend fun begin(
        path: String,
        mime: String?,
    ) {
        val accepts = (ops.photoAccepts(PhotoAcceptsRequest(mime, clock())) as? Outcome.Ok)?.value?.accepts ?: false
        if (!accepts) {
            discard(path)
            return say(UiText.Res(R.string.photo_not_picture))
        }
        val size = repo.photoSize(path)
        if (size.width <= 0 || size.height <= 0) {
            discard(path)
            return say(UiText.Res(R.string.photo_unreadable))
        }
        val plan = (ops.photoPlan(PhotoPlanRequest(size.width, size.height)) as? Outcome.Ok)?.value
        if (plan == null || plan.plan == null) {
            discard(path)
            return say(plan?.error?.let { UiText.Literal(it) } ?: UiText.Res(R.string.photo_unusable))
        }
        ui.update { it.copy(crop = CropSession(path, size.width, size.height, plan.maxZoom)) }
    }

    /** Choose on the crop page: [frame] is what the owner saw. */
    fun cropChosen(frame: CropFrame) {
        val session = ui.value.crop ?: return
        scope.launch {
            ui.update { it.copy(crop = null) }
            val view = CropView(frame.turns, frame.zoom, frame.cx, frame.cy)
            val answer = (ops.photoPlan(PhotoPlanRequest(session.width, session.height, view)) as? Outcome.Ok)?.value
            val plan = answer?.plan
            if (plan == null) {
                discard(session.path)
                return@launch say(answer?.error?.let { UiText.Literal(it) } ?: UiText.Res(R.string.photo_unusable))
            }
            when (val written = repo.setPhoto(contactId, session.path, plan)) {
                is ContactResult.Ok -> {
                    outbox.trySend(PersonPhotoEffect.Changed)
                }

                is ContactResult.Failed -> {
                    if (written.reason == ContactResult.Reason.Permission) {
                        handOff()
                    } else {
                        say(UiText.Res(R.string.photo_cant_change))
                    }
                }
            }
        }
    }

    /** Cancel (or Back) on the crop page. */
    fun cropCancelled() {
        val session = ui.value.crop ?: return
        ui.update { it.copy(crop = null) }
        scope.launch { discard(session.path) }
    }

    /** "Remove Photo", after the question: the contact loses its stored photo. */
    fun remove() {
        if (!guard()) return
        scope.launch {
            when (val removed = repo.removePhoto(contactId)) {
                is ContactResult.Ok -> {
                    outbox.trySend(PersonPhotoEffect.Changed)
                }

                is ContactResult.Failed -> {
                    if (removed.reason == ContactResult.Reason.Permission) {
                        handOff()
                    } else {
                        say(UiText.Res(R.string.photo_cant_remove))
                    }
                }
            }
        }
    }

    private suspend fun discard(path: String) {
        withContext(io) { File(path).delete() }
    }

    private companion object {
        const val DEFAULT_CAP = 20L * 1024 * 1024
    }
}
