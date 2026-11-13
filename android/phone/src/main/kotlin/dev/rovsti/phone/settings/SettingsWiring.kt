package dev.rovsti.phone.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.core.model.phone.NamesByNumber
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.showToast
import dev.rovsti.phone.PhoneContainer
import dev.rovsti.phone.R
import dev.rovsti.phone.data.People
import dev.rovsti.phone.findComponentActivity
import dev.rovsti.phone.settings.backup.BackupAction
import dev.rovsti.phone.settings.backup.BackupViewModel
import dev.rovsti.phone.settings.data.DataViewModel
import dev.rovsti.phone.settings.replies.QuickRepliesViewModel
import dev.rovsti.phone.settings.sim.SimChoicesViewModel
import dev.rovsti.phone.settings.speeddial.SpeedDialViewModel
import dev.rovsti.platform.files.ExportDocument
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.ImportDocument
import dev.rovsti.platform.files.ImportKind
import dev.rovsti.platform.files.ShareIntents
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A view model kept for the life of the activity, like the other Phone pages. */
@Composable
private inline fun <reified T : ViewModel> rememberSettingsModel(
    container: PhoneContainer,
    crossinline create: () -> T,
): T {
    val owner = LocalContext.current.findComponentActivity()
    return remember(owner, container) {
        val factory = viewModelFactory { initializer { create() } }
        ViewModelProvider(owner, factory)[T::class.java]
    }
}

/** The contacts with a number, or null while they are not readable. */
private fun PhoneContainer.contactList(): StateFlow<List<WireContact>?> =
    people.people
        .map { (it as? People.Ready)?.contacts }
        .stateIn(scope, SharingStarted.Eagerly, (people.people.value as? People.Ready)?.contacts)

private fun digitsTail(number: String): String = number.filter { it.isDigit() }.takeLast(TAIL)

private const val TAIL = 9

@Composable
fun rememberQuickRepliesModel(container: PhoneContainer): QuickRepliesViewModel =
    rememberSettingsModel(container) { QuickRepliesViewModel(container.store, container.ops) }

@Composable
fun rememberSpeedDialModel(container: PhoneContainer): SpeedDialViewModel {
    val contacts = remember(container) { container.contactList() }
    return rememberSettingsModel(container) { SpeedDialViewModel(container.store, container.speedDial, contacts) }
}

@Composable
fun rememberSimChoicesModel(container: PhoneContainer): SimChoicesViewModel {
    val contacts = remember(container) { container.contactList() }
    return rememberSettingsModel(container) {
        SimChoicesViewModel(container.simChoices, container.simState.sims) { number ->
            val tail = digitsTail(number)
            contacts.value
                .orEmpty()
                .firstOrNull { c -> c.numbers.any { digitsTail(it.raw) == tail } }
                ?.displayName
        }
    }
}

@Composable
fun rememberDataModel(container: PhoneContainer): DataViewModel {
    val contacts = remember(container) { container.contactList() }
    return rememberSettingsModel(container) {
        DataViewModel(
            ops = container.ops,
            contactsOps = container.contactsOps,
            store = container.store,
            callLog = container.callLog,
            entries = { container.recents.callEntries },
            extras = { container.recents.extraCalls },
            contacts = { contacts.value.orEmpty() },
            names = {
                val names: NamesByNumber =
                    contacts.value
                        .orEmpty()
                        .flatMap { c -> c.numbers.map { it.raw to c.displayName } }
                        .toMap()
                names
            },
            forgetWhatsApp = container.waCalls::forget,
            refresh = { container.recents.refreshNow() },
        )
    }
}

@Composable
fun rememberBackupModel(container: PhoneContainer): BackupViewModel {
    val context = LocalContext.current
    val version = remember(context) { container.setupFacts.appVersion }
    return rememberSettingsModel(container) { BackupViewModel(container.store, container.ops, version) }
}

/**
 * The Android side of the Data and Backup pages: the "save as" document pickers (one per mime type), the share sheet and
 * the backup file picker. A saved file shows the pill that names it (U41); a picked backup goes to [backup] as text.
 */
@Composable
fun rememberSettingsHost(
    container: PhoneContainer,
    backup: BackupViewModel,
): SettingsFilesHost {
    val context = LocalContext.current
    val overlays = LocalOverlay.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ExportFile?>(null) }
    val savedText = SavedWhat.entries.associateWith { savedMessageText(it) }
    val failed = stringResource(R.string.data_save_failed)

    fun finish(result: FileResult) {
        val file = pending ?: return
        pending = null
        val uri = (result as? FileResult.Ok)?.uri ?: return
        scope.launch {
            val written = container.files.writeExport(uri, text = file.text)
            overlays.showToast(if (written is FileResult.Ok) savedText.getValue(file.what) else failed)
        }
    }
    val csv = rememberLauncherForActivityResult(ExportDocument(MIME_CSV)) { finish(it) }
    val vcard = rememberLauncherForActivityResult(ExportDocument(MIME_VCARD)) { finish(it) }
    val json = rememberLauncherForActivityResult(ExportDocument(MIME_JSON)) { finish(it) }
    val picker =
        rememberLauncherForActivityResult(ImportDocument(kind = ImportKind.Json)) { result ->
            val uri = (result as? FileResult.Ok)?.uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val read = container.files.readUri(uri, ImportKind.Json.cap(0), text = true)
                val text = (read as? FileResult.Ok)?.text
                backup.onAction(BackupAction.Picked((read as? FileResult.Ok)?.name ?: "", text.orEmpty()))
            }
        }
    return remember(context, csv, vcard, json, picker) {
        object : SettingsFilesHost {
            override fun save(
                fileName: String,
                mime: String,
                text: String,
                what: SavedWhat,
            ) {
                pending = ExportFile(fileName, mime, text, what)
                when (mime) {
                    MIME_VCARD -> vcard
                    MIME_JSON -> json
                    else -> csv
                }.launch(fileName)
            }

            override fun share(text: String) {
                try {
                    context.startActivity(
                        Intent.createChooser(ShareIntents.text(text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: ActivityNotFoundException) {
                    // No app handles it: nothing to show.
                }
            }

            override fun pickBackup() = picker.launch(Unit)
        }
    }
}

private fun Context.savedMessageTextOf(what: SavedWhat): String =
    getString(
        when (what) {
            SavedWhat.History -> R.string.data_saved_history
            SavedWhat.Notes -> R.string.data_saved_notes
            SavedWhat.Contacts -> R.string.data_saved_contacts
            SavedWhat.Backup -> R.string.data_saved_backup
        },
    )

@Composable
private fun savedMessageText(what: SavedWhat): String = LocalContext.current.savedMessageTextOf(what)
