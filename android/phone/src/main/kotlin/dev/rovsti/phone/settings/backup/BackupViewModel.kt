package dev.rovsti.phone.settings.backup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.phone.BackupExportRequest
import dev.rovsti.core.model.phone.BackupImportAnswer
import dev.rovsti.core.model.phone.BackupImportRequest
import dev.rovsti.core.model.phone.BackupPreview
import dev.rovsti.core.model.phone.PhonePrefs
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.settings.ExportFile
import dev.rovsti.phone.settings.MIME_JSON
import dev.rovsti.phone.settings.SavedWhat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A backup file the user picked, understood by the core and waiting for Apply. */
@Immutable
data class BackupPending(
    val fileName: String,
    val preview: BackupPreview,
    internal val prefs: PhonePrefs,
)

@Immutable
data class BackupUi(
    val pending: BackupPending? = null,
)

sealed interface BackupAction {
    data object Export : BackupAction

    /** A file was picked: [text] is its content, [name] its display name. */
    data class Picked(
        val name: String,
        val text: String,
    ) : BackupAction

    /** Applies the previewed file. */
    data object Apply : BackupAction

    data object Cancel : BackupAction

    data class Undo(
        val previous: PhonePrefs,
    ) : BackupAction
}

sealed interface BackupEffect {
    data class Export(
        val file: ExportFile,
    ) : BackupEffect

    /** The file is no Rovsti Phone backup, or it is from a newer app; [reason] is the core's sentence. */
    data class Invalid(
        val reason: String,
    ) : BackupEffect

    /** The file was applied; [previous] is the preferences before, for the Undo pill. */
    data class Applied(
        val previous: PhonePrefs,
    ) : BackupEffect

    data object Failed : BackupEffect
}

/**
 * Settings, Backup and restore (E5.9b). Export is `phone.backup_export` over the preferences now. A picked file goes
 * through `phone.backup_import` first and only its preview (what would change) is shown; Apply saves the preferences
 * the core made of it, Cancel throws it away. Nothing changes before Apply.
 */
class BackupViewModel(
    private val store: PhoneStore,
    private val ops: PhoneOps,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val ui = MutableStateFlow(BackupUi())
    private val effects = Channel<BackupEffect>(Channel.BUFFERED)

    val state: StateFlow<BackupUi> = ui.asStateFlow()
    val effect: Flow<BackupEffect> = effects.receiveAsFlow()

    init {
        viewModelScope.launch { store.load() }
    }

    fun onAction(action: BackupAction) {
        viewModelScope.launch {
            when (action) {
                BackupAction.Export -> export()
                is BackupAction.Picked -> picked(action.name, action.text)
                BackupAction.Apply -> apply()
                BackupAction.Cancel -> ui.value = BackupUi()
                is BackupAction.Undo -> store.updatePrefs { action.previous }
            }
        }
    }

    private suspend fun export() {
        store.load()
        val answer =
            (ops.backupExport(BackupExportRequest(store.prefs.value, clock(), appVersion)) as? Outcome.Ok)?.value
        if (answer == null) {
            effects.trySend(BackupEffect.Failed)
        } else {
            effects.trySend(BackupEffect.Export(ExportFile(answer.fileName, MIME_JSON, answer.text, SavedWhat.Backup)))
        }
    }

    private suspend fun picked(
        name: String,
        text: String,
    ) {
        store.load()
        when (val out = ops.backupImport(BackupImportRequest(text, store.prefs.value))) {
            is Outcome.Ok -> {
                val answer: BackupImportAnswer = out.value
                ui.value = BackupUi(BackupPending(name, answer.preview, answer.prefs))
            }

            is Outcome.Err -> {
                ui.value = BackupUi()
                effects.trySend(BackupEffect.Invalid(reasonOf(out.error)))
            }
        }
    }

    private suspend fun apply() {
        val pending = ui.value.pending ?: return
        val previous = store.prefs.value
        store.updatePrefs { pending.prefs }
        store.flush()
        ui.value = BackupUi()
        effects.trySend(BackupEffect.Applied(previous))
    }

    private fun reasonOf(error: RustError): String = error.message.orEmpty()
}
