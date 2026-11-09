package dev.rovsti.contacts.intake

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.core.model.contacts.TextFileAnswer
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.files.ExportDocument
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.ImportDocument
import dev.rovsti.platform.files.ImportKind
import dev.rovsti.platform.files.PickPhoto
import kotlinx.coroutines.launch

/**
 * The ways in that Settings and the "+" menu of the list share (E4.10): each is one function that starts the system
 * picker (or reads the clipboard) and pushes the import page when the person has chosen something.
 */
@Immutable
class ImportActions(
    val importFile: () -> Unit,
    val pasteContact: () -> Unit,
    val scanQr: () -> Unit,
    val importCsv: () -> Unit,
    val exportCsv: () -> Unit,
    val exportVcf: () -> Unit = {},
)

/** Builds the [ImportActions] on the launchers of the calling page. */
@Composable
fun rememberImportActions(
    container: ContactsContainer,
    navigator: TabNavigator<ContactsRoute>,
    overlays: OverlayController,
): ImportActions {
    val scope = rememberCoroutineScope()
    val file =
        rememberLauncherForActivityResult(ImportDocument(kind = ImportKind.Contacts)) { result ->
            (result as? FileResult.Ok)?.uri?.let { navigator.push(ContactsRoute.Intake(it.toString())) }
        }
    // The Photo Picker needs no permission; the page decodes the picture off the main thread.
    val picture =
        rememberLauncherForActivityResult(remember { PickPhoto() }) { result ->
            (result as? FileResult.Ok)?.uri?.let { navigator.push(ContactsRoute.Intake(it.toString(), scan = true)) }
        }
    val csv =
        rememberLauncherForActivityResult(ImportDocument(kind = ImportKind.ContactsCsv)) { result ->
            (result as? FileResult.Ok)?.uri?.let { navigator.push(ContactsRoute.Intake(it.toString(), csv = true)) }
        }
    var pending by remember { mutableStateOf<TextFileAnswer?>(null) }
    val saved: (FileResult) -> Unit = { result ->
        val file = pending
        pending = null
        val uri = (result as? FileResult.Ok)?.uri
        if (file != null && uri != null) {
            scope.launch {
                val written = container.files.writeExport(uri, text = file.text)
                overlays.showToast(if (written is FileResult.Ok) "Saved ${file.fileName}" else "Can't save the file.")
            }
        }
    }
    val save = rememberLauncherForActivityResult(ExportDocument("text/csv"), saved)
    val saveVcf = rememberLauncherForActivityResult(ExportDocument("text/x-vcard"), saved)
    return remember(container, navigator, overlays) {
        ImportActions(
            importFile = { file.launch(Unit) },
            // The clipboard is read on this tap and nowhere else; an empty one is answered here, without a page.
            pasteContact = {
                if (container.clipboard.primaryText().isNullOrBlank()) {
                    overlays.showToast(IntakeViewModel.NOTHING_TO_PASTE)
                } else {
                    navigator.push(ContactsRoute.Intake(paste = true))
                }
            },
            scanQr = { picture.launch(Unit) },
            importCsv = { csv.launch(Unit) },
            exportVcf = {
                scope.launch {
                    val built = VcfExport(container.ops, container.book).build()
                    if (built == null) {
                        overlays.showToast("No contacts to export")
                    } else {
                        pending = built
                        saveVcf.launch(built.fileName)
                    }
                }
            },
            exportCsv = {
                scope.launch {
                    val built = CsvExport(container.ops, container.book).build()
                    if (built == null) {
                        overlays.showToast("No contacts to export")
                    } else {
                        pending = built
                        save.launch(built.fileName)
                    }
                }
            },
        )
    }
}
