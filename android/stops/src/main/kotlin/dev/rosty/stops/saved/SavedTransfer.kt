package dev.rosty.stops.saved

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.FileTextAnswer
import dev.rosty.core.model.stops.MergePlan
import dev.rosty.core.model.stops.PlacesExportRequest
import dev.rosty.core.model.stops.PlacesImportAnswer
import dev.rosty.core.model.stops.PlacesImportRequest
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.ops.StopsOps
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.platform.files.FilesRepository
import dev.rosty.platform.files.ImportKind
import dev.rosty.stops.R
import dev.rosty.stops.data.SavedStore
import dev.rosty.stops.files.rememberTextExport
import dev.rosty.stops.files.rememberTextImport
import kotlinx.coroutines.launch

/**
 * S5: the saved stops as a GPX or JSON file and back. The file text and name are `stops.places_export`; an import is
 * `stops.places_import`, first as a plan (what would be added, duplicates and unusable entries skipped, the cap), then
 * applied when the user confirms; [apply] answers the list it replaced, for Undo.
 */
class PlacesTransfer(
    private val ops: StopsOps,
    private val store: SavedStore,
    private val nowMs: () -> Long,
    private val today: () -> String,
) {
    suspend fun export(format: String): Outcome<FileTextAnswer> =
        ops.placesExport(PlacesExportRequest(store.load(), format, today()))

    suspend fun plan(text: String): Outcome<PlacesImportAnswer> =
        ops.placesImport(PlacesImportRequest(store.load(), text, nowMs()))

    /** Adds what [text] plans; answers the old list and how many were added, or null when nothing was written. */
    suspend fun apply(text: String): Pair<SavedStops, Int>? {
        val old = store.load()
        val answer = (ops.placesImport(PlacesImportRequest(old, text, nowMs(), apply = true)) as? Outcome.Ok)?.value
        if (answer == null || answer.added == 0) return null
        if (store.replace(answer.saved) is Outcome.Err) return null
        return old to answer.added
    }

    suspend fun undo(old: SavedStops) = store.replace(old)

    companion object {
        const val FORMAT_GPX = "gpx"
        const val FORMAT_JSON = "json"
    }
}

/** The import summary in the app's words (GPUI `places_io::summary`): "Add 3 stops. 1 already saved." */
fun importSummary(
    resources: Resources,
    plan: MergePlan,
): String =
    buildList {
        add(
            if (plan.add.isEmpty()) {
                resources.getString(R.string.places_nothing_new)
            } else {
                resources.getQuantityString(R.plurals.places_add, plan.add.size, plan.add.size)
            },
        )
        if (plan.duplicates > 0) {
            add(resources.getQuantityString(R.plurals.places_duplicates, plan.duplicates, plan.duplicates))
        }
        if (plan.invalid > 0) add(resources.getQuantityString(R.plurals.places_invalid, plan.invalid, plan.invalid))
        if (plan.overCap > 0) add(resources.getQuantityString(R.plurals.places_over_cap, plan.overCap, plan.overCap))
    }.joinToString(" ")

/** What the Saved sheet's menu offers for files. */
interface SavedFileActions {
    fun export(format: String)

    fun import()
}

/** The S5 glue: "Save as" for an export, the document picker, the confirm sheet and the Undo pill. */
@Composable
fun rememberSavedFiles(
    files: FilesRepository,
    transfer: PlacesTransfer,
    onChanged: () -> Unit,
): SavedFileActions {
    val overlays = LocalOverlay.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onChanged)

    fun toast(id: Int) = overlays.showToast(resources.getString(id))

    val saveGpx =
        rememberTextExport(
            files,
            MIME_GPX,
        ) { ok -> toast(if (ok) R.string.places_exported else R.string.places_export_failed) }
    val saveJson =
        rememberTextExport(
            files,
            MIME_JSON,
        ) { ok -> toast(if (ok) R.string.places_exported else R.string.places_export_failed) }
    val open =
        rememberTextImport(files, ImportKind.Json) { text ->
            scope.launch {
                val plan = text?.let { (transfer.plan(it) as? Outcome.Ok)?.value }
                if (text == null || plan == null) {
                    toast(R.string.places_unreadable)
                    return@launch
                }
                overlays.presentImportSheet(
                    resources.getString(R.string.places_import_title),
                    importSummary(resources, plan.plan),
                    plan.plan.add.map { it.name },
                ) {
                    scope.launch {
                        val done = transfer.apply(text)
                        if (done == null) {
                            toast(R.string.places_nothing_added)
                            return@launch
                        }
                        changed()
                        val (old, added) = done
                        overlays.showUndo(
                            resources.getQuantityString(R.plurals.places_imported, added, added),
                            {
                                scope.launch {
                                    transfer.undo(old)
                                    changed()
                                }
                            },
                            resources.getString(R.string.saved_undo),
                        )
                    }
                }
            }
        }
    return remember(transfer) {
        object : SavedFileActions {
            override fun export(format: String) {
                scope.launch {
                    when (val answer = transfer.export(format)) {
                        is Outcome.Err -> {
                            toast(R.string.places_export_failed)
                        }

                        is Outcome.Ok -> {
                            val save = if (format == PlacesTransfer.FORMAT_GPX) saveGpx else saveJson
                            save(answer.value.fileName, answer.value.text)
                        }
                    }
                }
            }

            override fun import() = open()
        }
    }
}

private const val MIME_GPX = "application/gpx+xml"
private const val MIME_JSON = "application/json"
