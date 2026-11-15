package dev.rosty.messages.backup

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.model.messages.Strength
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The file side of a backup: the documents the system pickers chose. */
interface BackupFiles {
    /** Writes [bytes] to the document [target] chosen in "save as"; false when it refused. */
    suspend fun write(
        target: Uri,
        bytes: ByteArray,
    ): Boolean

    /** The picked document [source]; null when it cannot be read or is over the cap. */
    suspend fun read(source: Uri): PickedFile?
}

/** A picked backup file: its bytes and its display name, when the provider has one. */
class PickedFile(
    val bytes: ByteArray,
    val name: String?,
)

@Immutable
data class BackupUi(
    val phase: BackupPhase = BackupPhase.Idle,
    /** The core's guess at the typed passphrase (null while nothing is typed). */
    val strength: Strength? = null,
    /** The picked file's name, shown on the passphrase and preview sheets. */
    val fileName: String? = null,
    /** Whether messages can be written now (the SMS role). */
    val roleHeld: Boolean = true,
)

sealed interface BackupAction {
    data object BeginExport : BackupAction

    /** A field of the export form changed; [passphrase] is the first field. */
    data class Edited(
        val passphrase: String,
    ) : BackupAction

    data class SubmitExport(
        val passphrase: String,
        val confirm: String,
    ) : BackupAction

    /** "Save as" answered: the chosen document, or null when the person backed out. */
    data class SaveTo(
        val target: Uri?,
    ) : BackupAction

    /** The file picker opens. */
    data object PickStarted : BackupAction

    /** The picker answered: the document, or null when the person backed out. */
    data class Picked(
        val source: Uri?,
    ) : BackupAction

    data class Open(
        val passphrase: String,
    ) : BackupAction

    data object Restore : BackupAction

    data object Cancel : BackupAction
}

sealed interface BackupEffect {
    /** Open the system "save as" with [fileName]. */
    data class SaveAs(
        val fileName: String,
    ) : BackupEffect

    /** The flow ended: say how in a pill. */
    data class Finished(
        val phase: BackupPhase,
    ) : BackupEffect
}

/**
 * Settings, Backup (AD-MSG7): export with a passphrase typed twice to a place the person picks, import from a picked
 * file with its passphrase, a preview with counts, then the restore. The phases are [BackupFlow]; the work is
 * [BackupEngine]; the file pickers belong to the page, which hands their answers back as [BackupAction]s.
 */
class BackupViewModel(
    private val engine: BackupEngine,
    private val files: BackupFiles,
    private val roleHeld: () -> Boolean,
    private val today: () -> LocalDate,
) : ViewModel() {
    private val ui = MutableStateFlow(BackupUi(roleHeld = roleHeld()))
    val state: StateFlow<BackupUi> = ui.asStateFlow()

    private val effects = Channel<BackupEffect>(Channel.BUFFERED)
    val effect: Flow<BackupEffect> = effects.receiveAsFlow()

    private var sealed: ByteArray? = null
    private var picked: ByteArray? = null
    private var opened: OpenedBackup? = null
    private var strengthJob: Job? = null

    private val phase get() = ui.value.phase

    private fun go(next: BackupPhase) {
        when (next) {
            is BackupPhase.Done, is BackupPhase.Failed -> {
                // The pill says how it ended; the page is ready for the next one.
                sealed = null
                picked = null
                opened = null
                ui.update { it.copy(phase = BackupPhase.Idle, strength = null, fileName = null) }
                effects.trySend(BackupEffect.Finished(next))
            }

            else -> {
                ui.update { it.copy(phase = next) }
            }
        }
    }

    fun onAction(action: BackupAction) {
        when (action) {
            BackupAction.BeginExport -> {
                ui.update { it.copy(strength = null) }
                go(BackupFlow.beginExport(phase))
            }

            is BackupAction.Edited -> {
                edited(action.passphrase)
            }

            is BackupAction.SubmitExport -> {
                viewModelScope.launch { submit(action.passphrase, action.confirm) }
            }

            is BackupAction.SaveTo -> {
                viewModelScope.launch { save(action.target) }
            }

            BackupAction.PickStarted -> {
                if (!BackupFlow.isBusy(phase)) go(BackupPhase.Busy(BackupBusy.Picking))
            }

            is BackupAction.Picked -> {
                viewModelScope.launch { picked(action.source) }
            }

            is BackupAction.Open -> {
                viewModelScope.launch { open(action.passphrase) }
            }

            BackupAction.Restore -> {
                viewModelScope.launch { restore() }
            }

            BackupAction.Cancel -> {
                if (!BackupFlow.isBusy(phase)) {
                    picked = null
                    opened = null
                    ui.update { it.copy(phase = BackupFlow.cancel(phase), strength = null, fileName = null) }
                }
            }
        }
    }

    private fun edited(passphrase: String) {
        go(BackupFlow.edited(phase))
        strengthJob?.cancel()
        strengthJob =
            viewModelScope.launch {
                val strength = if (passphrase.isEmpty()) null else engine.strength(passphrase)
                ui.update { it.copy(strength = strength) }
            }
    }

    private suspend fun submit(
        passphrase: String,
        confirm: String,
    ) {
        if (phase !is BackupPhase.Export) return
        val strength = if (passphrase.isEmpty()) null else engine.strength(passphrase)
        val next = BackupFlow.submitExport(phase, passphrase, confirm, strength)
        go(next)
        if (next != BackupPhase.Busy(BackupBusy.Sealing)) return
        val bytes = engine.seal(passphrase) ?: return go(BackupPhase.Failed(BackupFailure.Failed))
        sealed = bytes
        go(BackupPhase.Busy(BackupBusy.Saving))
        effects.trySend(BackupEffect.SaveAs(BackupEngine.fileName(today())))
    }

    private suspend fun save(target: Uri?) {
        val bytes = sealed ?: return
        if (target == null) {
            sealed = null
            ui.update { it.copy(phase = BackupPhase.Idle) }
            return
        }
        val written = files.write(target, bytes)
        go(if (written) BackupPhase.Done(BackupDone.Saved) else BackupPhase.Failed(BackupFailure.NotSaved))
    }

    private suspend fun picked(source: Uri?) {
        if (source == null) {
            ui.update { it.copy(phase = BackupPhase.Idle) }
            return
        }
        go(BackupPhase.Busy(BackupBusy.Picking))
        val file = files.read(source)
        picked = file?.bytes
        ui.update { it.copy(fileName = file?.name, roleHeld = roleHeld()) }
        go(BackupFlow.picked(file != null))
    }

    private suspend fun open(passphrase: String) {
        val bytes = picked ?: return
        if (phase !is BackupPhase.ImportAsk || passphrase.isEmpty()) return
        go(BackupPhase.Busy(BackupBusy.Opening))
        when (val result = engine.open(bytes, passphrase)) {
            is OpenResult.Opened -> {
                opened = result.backup
                ui.update { it.copy(roleHeld = roleHeld()) }
                go(BackupPhase.Preview(result.backup.preview))
            }

            OpenResult.WrongPassphrase -> {
                go(BackupPhase.ImportAsk(wrong = true))
            }

            is OpenResult.Unusable -> {
                go(BackupPhase.Failed(result.failure))
            }
        }
    }

    private suspend fun restore() {
        val backup = opened ?: return
        if (phase !is BackupPhase.Preview) return
        go(BackupPhase.Busy(BackupBusy.Restoring))
        go(engine.restore(backup))
    }
}
