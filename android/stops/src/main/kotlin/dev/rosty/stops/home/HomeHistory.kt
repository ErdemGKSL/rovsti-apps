package dev.rosty.stops.home

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.Destination
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.resolve
import dev.rosty.platform.files.ImportKind
import dev.rosty.platform.files.ShareIntents
import dev.rosty.platform.location.TripClock
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.data.HistoryStore
import dev.rosty.stops.files.rememberTextExport
import dev.rosty.stops.files.rememberTextImport
import dev.rosty.stops.history.Backup
import dev.rosty.stops.history.BackupSnapshot
import dev.rosty.stops.history.HistoryAction
import dev.rosty.stops.history.HistoryEffect
import dev.rosty.stops.history.HistorySheetActions
import dev.rosty.stops.history.HistoryViewModel
import dev.rosty.stops.history.presentBackupConfirmSheet
import dev.rosty.stops.history.snapshot
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/** The local date of export file names (`2026-09-21`). */
internal fun localToday(): String =
    Instant
        .ofEpochMilli(TripClock.nowMillis())
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

internal fun tzOffsetNow(): Int = TimeZone.getDefault().getOffset(TripClock.nowMillis()) / MS_PER_SECOND

private const val MS_PER_SECOND = 1000

/** What the History sheet hands back to the map. */
internal interface HistoryHost {
    val tripRunning: Boolean

    fun start(
        a: Destination,
        b: Destination,
    )

    fun showOnMap(
        a: Destination,
        b: Destination,
    )

    fun saveGate(
        a: Destination,
        b: Destination,
    )

    /** The saved stops, the history or the preferences were replaced (a restore or its Undo). */
    fun reloaded()
}

/**
 * The History sheet's view model (owned by the activity like the home's) and its glue: toasts and Undo, share and
 * copy, the CSV / GPX "Save as" (S16), and S32 backup ("Save as") and restore (open a file, confirm, Undo).
 */
@Composable
internal fun rememberHistory(
    container: StopsContainer,
    host: HistoryHost,
): Pair<HistoryViewModel, HistorySheetActions> {
    val context = LocalContext.current
    val resources = LocalResources.current
    val overlays = LocalOverlay.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(host)
    val owner = remember(context) { context.findComponentActivity() }
    val history = remember(container) { HistoryStore(container.ops) }
    val viewModel =
        remember(owner, container) {
            val factory =
                viewModelFactory {
                    initializer {
                        HistoryViewModel(
                            ops = container.ops,
                            store = history,
                            nowMs = TripClock::nowMillis,
                            tzOffsetS = ::tzOffsetNow,
                            today = ::localToday,
                        )
                    }
                }
            ViewModelProvider(owner, factory)[HistoryViewModel::class.java]
        }
    val backup =
        remember(container) {
            Backup(
                container.ops,
                container.saved,
                history,
                container.store,
                TripClock::nowMillis,
                ::tzOffsetNow,
                ::localToday,
            )
        }

    fun toast(id: Int) = overlays.showToast(resources.getString(id))

    val saveHistory =
        rememberTextExport(container.files, HistoryViewModel.MIME_CSV) { ok ->
            toast(if (ok) R.string.history_exported else R.string.history_export_failed)
        }
    val saveGpx =
        rememberTextExport(container.files, HistoryViewModel.MIME_GPX) { ok ->
            toast(if (ok) R.string.history_exported else R.string.history_export_failed)
        }
    val saveBackup =
        rememberTextExport(container.files, BACKUP_MIME) { ok ->
            toast(if (ok) R.string.backup_saved else R.string.backup_failed)
        }

    fun apply(snapshot: BackupSnapshot) {
        scope.launch {
            val old = backup.apply(snapshot)
            latest.reloaded()
            viewModel.onAction(HistoryAction.Refresh)
            if (old == null) {
                toast(R.string.history_not_saved)
                return@launch
            }
            overlays.showUndo(
                resources.getString(R.string.backup_restored),
                {
                    scope.launch {
                        backup.apply(old)
                        latest.reloaded()
                        viewModel.onAction(HistoryAction.Refresh)
                    }
                },
                resources.getString(R.string.saved_undo),
            )
        }
    }

    val openBackup =
        rememberTextImport(container.files, ImportKind.Json) { text ->
            scope.launch {
                val parsed = text?.let { (backup.parse(it) as? Outcome.Ok)?.value }
                if (parsed == null) {
                    toast(R.string.backup_unreadable)
                    return@launch
                }
                overlays.presentBackupConfirmSheet(resources.getString(R.string.backup_confirm_title), parsed.summary) {
                    apply(parsed.snapshot())
                }
            }
        }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HistoryEffect.Toast -> {
                    overlays.showToast(effect.text.resolve(context))
                }

                is HistoryEffect.Undoable -> {
                    overlays.showUndo(
                        effect.text.resolve(context),
                        { viewModel.onAction(HistoryAction.Restore(effect.undo)) },
                        resources.getString(R.string.saved_undo),
                    )
                }

                is HistoryEffect.Start -> {
                    if (latest.tripRunning) toast(R.string.history_trip_running) else latest.start(effect.a, effect.b)
                }

                is HistoryEffect.ShowOnMap -> {
                    latest.showOnMap(effect.a, effect.b)
                }

                is HistoryEffect.SaveGate -> {
                    latest.saveGate(effect.a, effect.b)
                }

                is HistoryEffect.Share -> {
                    try {
                        context.startActivity(ShareIntents.text(effect.text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: android.content.ActivityNotFoundException) {
                        Unit
                    }
                }

                is HistoryEffect.Copy -> {
                    if (container.clipboard.copy(effect.text)) toast(R.string.history_copied)
                }

                is HistoryEffect.Export -> {
                    val save = if (effect.mime == HistoryViewModel.MIME_GPX) saveGpx else saveHistory
                    save(effect.fileName, effect.text)
                }
            }
        }
    }
    val actions =
        remember(backup) {
            object : HistorySheetActions {
                override fun backUp() {
                    scope.launch {
                        when (val answer = backup.export()) {
                            is Outcome.Ok -> saveBackup(answer.value.fileName, answer.value.text)
                            is Outcome.Err -> toast(R.string.backup_failed)
                        }
                    }
                }

                override fun restore() {
                    if (latest.tripRunning) toast(R.string.backup_during_trip) else openBackup()
                }
            }
        }
    return viewModel to actions
}

private const val BACKUP_MIME = "application/json"
