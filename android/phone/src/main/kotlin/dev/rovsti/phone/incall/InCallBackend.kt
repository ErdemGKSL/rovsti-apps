package dev.rovsti.phone.incall

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.AudioRowsAnswer
import dev.rovsti.core.model.phone.AudioRowsRequest
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.CallUpdate
import dev.rovsti.core.model.phone.DialTextRequest
import dev.rovsti.core.model.phone.ElapsedRequest
import dev.rovsti.core.model.phone.Endpoint
import dev.rovsti.core.model.phone.NoteRemoveRequest
import dev.rovsti.core.model.phone.NoteSetRequest
import dev.rovsti.core.model.phone.PasteTonesRequest
import dev.rovsti.core.model.phone.SceneAnswer
import dev.rovsti.core.model.phone.SceneRequest
import dev.rovsti.core.model.phone.ToneStep
import dev.rovsti.core.model.phone.WaScreenRequest
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.data.BlockRepo
import dev.rovsti.phone.data.BlockResult
import dev.rovsti.phone.data.BlockUndo
import dev.rovsti.phone.data.PhoneStore

/** What the In-Call screen asks of the Rust core: the words and the rules. A seam, so the model's tests need no core. */
interface InCallBackend {
    /** `4:32` / `1:02:03` for [seconds]. */
    suspend fun elapsed(seconds: Long): String

    /** The route list rows; [previous] keeps vanished devices as ghosts. Null when the core failed. */
    suspend fun audioRows(
        endpoints: List<Endpoint>,
        previous: List<Endpoint>?,
    ): AudioRowsAnswer?

    /** The label of the "send tones" paste action, null when the clipboard does not fit. */
    suspend fun pasteLabel(clip: String?): String?

    /** The tones and pauses of [text]. */
    suspend fun tonePlan(text: String): List<ToneStep>

    /** The call scene of [snapshot] (`scene.of`): layout, held call, conference rows and dimmed controls; null on a failure. */
    suspend fun scene(snapshot: CallSnapshot): SceneAnswer? = null

    /** Which controls a WhatsApp call has, and the core's sentence for the ones it has not; null when the core failed. */
    suspend fun waControls(
        update: CallUpdate,
        speaker: Boolean,
        muted: Boolean,
    ): WaControls? = null
}

class CoreInCallBackend(
    private val ops: PhoneOps,
) : InCallBackend {
    override suspend fun elapsed(seconds: Long): String =
        (ops.formatElapsed(ElapsedRequest(seconds)) as? Outcome.Ok)?.value?.text.orEmpty()

    override suspend fun audioRows(
        endpoints: List<Endpoint>,
        previous: List<Endpoint>?,
    ): AudioRowsAnswer? = (ops.audioRows(AudioRowsRequest(endpoints, previous)) as? Outcome.Ok)?.value

    override suspend fun pasteLabel(clip: String?): String? =
        (ops.pasteTonesLabel(PasteTonesRequest(clip)) as? Outcome.Ok)?.value?.label

    override suspend fun tonePlan(text: String): List<ToneStep> =
        (ops.tonePlan(DialTextRequest(text)) as? Outcome.Ok)?.value?.steps.orEmpty()

    override suspend fun scene(snapshot: CallSnapshot): SceneAnswer? =
        (ops.sceneOf(SceneRequest(snapshot)) as? Outcome.Ok)?.value

    override suspend fun waControls(
        update: CallUpdate,
        speaker: Boolean,
        muted: Boolean,
    ): WaControls? =
        (ops.waScreen(WaScreenRequest(update, speaker, muted)) as? Outcome.Ok)?.value?.let {
            WaControls(
                mute = it.enabled.mute,
                speaker = it.enabled.speaker,
                keypad = it.enabled.keypad,
                hold = it.enabled.hold,
                addCall = it.enabled.addCall,
                end = it.enabled.end,
                note = it.note,
            )
        }
}

/** The call notes: stored under the number and the time the call started, so the log finds them afterwards (U17). */
interface InCallNotes {
    fun current(
        number: String,
        startedMs: Long,
    ): String

    suspend fun save(
        number: String,
        startedMs: Long,
        text: String,
    ): Boolean
}

class StoreInCallNotes(
    private val store: PhoneStore,
    private val ops: PhoneOps,
    private val clock: () -> Long,
) : InCallNotes {
    override fun current(
        number: String,
        startedMs: Long,
    ): String =
        store.notes.value.notes
            .firstOrNull { it.number == number && it.atMs == startedMs }
            ?.text
            .orEmpty()

    override suspend fun save(
        number: String,
        startedMs: Long,
        text: String,
    ): Boolean {
        if (text.isBlank()) {
            val out = (ops.noteRemove(NoteRemoveRequest(store.notes.value, number, startedMs)) as? Outcome.Ok)?.value
            if (out != null) store.updateNotes { out.notes }
            return out != null
        }
        val set =
            (
                ops.noteSet(
                    NoteSetRequest(store.notes.value, number, startedMs, text, clock()),
                ) as? Outcome.Ok
            )?.value
        if (set != null) store.updateNotes { set.notes }
        return set != null
    }
}

/** Blocking the caller after the call (the pill's Block / Undo). */
interface InCallBlocker {
    /** True when the number may be blocked now (not an emergency number, not blocked yet, this app may write). */
    suspend fun canBlock(number: String): Boolean

    suspend fun block(number: String): BlockUndo?

    suspend fun undo(undo: BlockUndo): Boolean
}

class RepoInCallBlocker(
    private val repo: BlockRepo,
) : InCallBlocker {
    override suspend fun canBlock(number: String): Boolean = repo.canBlock() && repo.check(number) == null

    override suspend fun block(number: String): BlockUndo? = (repo.block(number) as? BlockResult.Done)?.undo

    override suspend fun undo(undo: BlockUndo): Boolean = repo.undo(undo)
}

/** The tones the user pasted on a number, offered again the next time that number connects (U51). */
class ToneMemory {
    private val byNumber = HashMap<String, String>()

    @Synchronized
    fun get(number: String): String? = byNumber[number]

    @Synchronized
    fun put(
        number: String,
        tones: String,
    ) {
        if (number.isNotBlank() && tones.isNotBlank()) byNumber[number] = tones
    }
}
