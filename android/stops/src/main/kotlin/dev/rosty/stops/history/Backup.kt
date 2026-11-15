package dev.rosty.stops.history

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.BackupExportAnswer
import dev.rosty.core.model.stops.BackupExportRequest
import dev.rosty.core.model.stops.BackupParseAnswer
import dev.rosty.core.model.stops.BackupParseRequest
import dev.rosty.core.model.stops.History
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.ops.StopsOps
import dev.rosty.stops.data.HistoryStore
import dev.rosty.stops.data.SavedStore
import dev.rosty.stops.data.StopsStore

/** What a restore replaced, for its Undo. */
class BackupSnapshot(
    val saved: SavedStops,
    val history: History,
    val prefs: StopsPrefs,
)

/**
 * S32: one JSON file with the saved stops, the trip history and the settings. The file's text, name and summary come
 * from `stops.backup_export`; reading one back (and refusing what is not a backup) is `stops.backup_parse`. [apply]
 * writes the three parts through their stores and answers what was there, so Undo can put it back with [apply] again.
 */
class Backup(
    private val ops: StopsOps,
    private val saved: SavedStore,
    private val history: HistoryStore,
    private val prefs: StopsStore,
    private val nowMs: () -> Long,
    private val tzOffsetS: () -> Int,
    private val today: () -> String,
) {
    suspend fun export(): Outcome<BackupExportAnswer> {
        val request =
            BackupExportRequest(
                saved = saved.load(),
                history = history.reload(),
                prefs = (prefs.load() as? Outcome.Ok)?.value ?: prefs.prefs.value,
                nowMs = nowMs(),
                tzOffsetS = tzOffsetS(),
                today = today(),
            )
        return ops.backupExport(request)
    }

    suspend fun parse(text: String): Outcome<BackupParseAnswer> = ops.backupParse(BackupParseRequest(text, tzOffsetS()))

    /** Writes [backup]'s three parts; answers what they replaced, or null when a write failed. */
    suspend fun apply(backup: BackupSnapshot): BackupSnapshot? {
        val old =
            BackupSnapshot(
                saved.load(),
                history.reload(),
                (prefs.load() as? Outcome.Ok)?.value ?: prefs.prefs.value,
            )
        if (saved.replace(backup.saved) is Outcome.Err) return null
        if (history.replace(backup.history) is Outcome.Err) return null
        if (prefs.update { backup.prefs } is Outcome.Err) return null
        return old
    }
}

fun BackupParseAnswer.snapshot(): BackupSnapshot = BackupSnapshot(saved, history, prefs)
