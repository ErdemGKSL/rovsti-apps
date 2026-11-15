package dev.rosty.stops.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Slot
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.showToast
import dev.rosty.stops.R
import dev.rosty.stops.StopsContainer
import dev.rosty.stops.files.rememberTextExport
import dev.rosty.stops.pick.Picker
import dev.rosty.stops.saved.SavedAction
import dev.rosty.stops.saved.SavedViewModel
import dev.rosty.stops.tools.MapToolsHost
import dev.rosty.stops.trip.TripViewModel
import kotlinx.coroutines.launch

/**
 * What the home lends the map tools (E7.9): the trip and the saved stops, the "Saved stops on map" choice (shown at
 * once through [showSavedOnMap], written to `stops-prefs.json`) and the GPX "Save as".
 */
@Composable
internal fun mapToolsHost(
    container: StopsContainer,
    trip: TripViewModel,
    state: HomeState,
    savedOnMapNow: Boolean?,
    showSavedOnMap: (Boolean) -> Unit,
    saved: SavedViewModel,
    picker: Picker?,
): MapToolsHost {
    val overlays = LocalOverlay.current
    val resources = LocalResources.current
    val saveGpx =
        rememberTextExport(container.files, GPX_MIME) { ok ->
            overlays.showToast(
                resources.getString(if (ok) R.string.measure_exported else R.string.measure_export_failed),
            )
        }
    return object : MapToolsHost {
        override val tripRunning: Boolean get() = trip.state.value.trip != null

        override val hasSaved: Boolean get() = state.saved.isNotEmpty()

        override val savedOnMap: Boolean get() = savedOnMapNow ?: state.savedOnMap

        override fun setSavedOnMap(on: Boolean) {
            showSavedOnMap(on)
            container.scope.launch { container.store.update { it.copy(showSavedOnMap = on) } }
        }

        override fun saveGate(
            a: Destination,
            b: Destination,
        ) = saved.onAction(SavedAction.SaveGate(a, b))

        override fun use(
            slot: Slot,
            dest: Destination,
        ) {
            picker?.place(slot, dest)
        }

        override fun exportGpx(
            fileName: String,
            text: String,
        ) = saveGpx(fileName, text)
    }
}

private const val GPX_MIME = "application/gpx+xml"
