package dev.rosty.stops.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.rosty.platform.files.ExportDocument
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.files.FilesRepository
import dev.rosty.platform.files.ImportDocument
import dev.rosty.platform.files.ImportKind
import kotlinx.coroutines.launch

/**
 * "Save as" for a text file through the system document picker (`:platform` files, E3.8a): call the answer with a
 * suggested file name and the text; [onDone] hears whether the text was written (false: refused or failed), nothing
 * when the user cancelled the picker.
 */
@Composable
fun rememberTextExport(
    files: FilesRepository,
    mime: String,
    onDone: (Boolean) -> Unit,
): (name: String, text: String) -> Unit {
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<String>(1) }
    val launcher =
        rememberLauncherForActivityResult(ExportDocument(mime)) { result ->
            val text = pending[0] ?: return@rememberLauncherForActivityResult
            pending[0] = null
            when (result) {
                is FileResult.Ok -> {
                    val uri = result.uri ?: return@rememberLauncherForActivityResult onDone(false)
                    scope.launch { onDone(files.writeExport(uri, text = text) is FileResult.Ok) }
                }

                FileResult.Cancelled -> {
                    Unit
                }

                else -> {
                    onDone(false)
                }
            }
        }
    return remember(launcher) {
        { name, text ->
            pending[0] = text
            launcher.launch(name)
        }
    }
}

/**
 * "Open" for a small text file (JSON, GPX, CSV): the system document picker of [kind], then the UTF-8 text read off
 * the main thread. [onText] gets the text, or null when it could not be read or was too large; nothing on cancel.
 */
@Composable
fun rememberTextImport(
    files: FilesRepository,
    kind: ImportKind = ImportKind.Json,
    onText: (String?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val contract = remember(kind) { ImportDocument(kind = kind) }
    val launcher =
        rememberLauncherForActivityResult(contract) { result ->
            when (result) {
                is FileResult.Ok -> {
                    val uri = result.uri ?: return@rememberLauncherForActivityResult onText(null)
                    scope.launch {
                        val read = files.readUri(uri, contract.cap, text = true)
                        onText((read as? FileResult.Ok)?.text)
                    }
                }

                FileResult.Cancelled -> {
                    Unit
                }

                else -> {
                    onText(null)
                }
            }
        }
    return remember(launcher) { { launcher.launch(Unit) } }
}
