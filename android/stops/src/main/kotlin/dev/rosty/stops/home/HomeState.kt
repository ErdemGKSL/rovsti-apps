package dev.rosty.stops.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.model.stops.Ends
import dev.rosty.core.model.stops.SavedStop
import dev.rosty.core.model.stops.SavedStops
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The first screen's state (ARCH 5.2): what the home sheet and, from E7.3 on, the map layers draw. Built from a
 * [HomeSnapshot] by [homeStateOf]; per-frame values (camera, sheet position) never live here.
 */
@Immutable
data class HomeState(
    val loading: Boolean = true,
    val trip: TripSummary? = null,
    val saved: ImmutableList<SavedStop> = persistentListOf(),
    val historyCount: Int = 0,
    val transitImportedMs: Long? = null,
    /** The host of the tile server the map uses (the default VersaTiles one when none is set). */
    val tileServer: String = DEFAULT_TILE_HOST,
    /** S40: saved stops drawn on the map. */
    val savedOnMap: Boolean = true,
    /** `saved.json` as read (the pin menu's "Save as stop" offer and the map markers need the whole file). */
    val savedFile: SavedStops? = null,
    /** The draft ends of the last session. */
    val draft: Ends? = null,
)

/** The public VersaTiles server, the default when `tile_url` is unset (`rosty_tiles`). */
const val DEFAULT_TILE_HOST = "tiles.versatiles.org"

/** The first screen's state of a [snapshot] (pure). */
fun homeStateOf(snapshot: HomeSnapshot): HomeState =
    HomeState(
        loading = false,
        trip = snapshot.trip,
        saved =
            snapshot.saved
                ?.stops
                .orEmpty()
                .toImmutableList(),
        historyCount = snapshot.history?.records?.size ?: 0,
        transitImportedMs = snapshot.transitImportedMs,
        tileServer = tileServerHost(snapshot.prefs.tileUrl),
        savedOnMap = snapshot.prefs.showSavedOnMap,
        savedFile = snapshot.saved,
        draft = snapshot.draft,
    )

/**
 * The host of a tile URL template for display (pure): `https://tiles.example.org/{z}/{x}/{y}.pbf` reads
 * `tiles.example.org`; null or blank is the default server; something without a scheme shows as typed, up to its first
 * slash.
 */
fun tileServerHost(url: String?): String {
    val text = url?.trim().orEmpty()
    if (text.isEmpty()) return DEFAULT_TILE_HOST
    val rest = text.substringAfter("://", text)
    return rest.substringBefore('/').substringBefore('?').ifEmpty { text }
}

/**
 * The home's view model: loads the [HomeSnapshot] once and on [refresh] (a trip ended, a stop was saved). The load runs
 * on the core's dispatcher (`RustDispatcher`), never on the main thread.
 */
class HomeViewModel(
    private val data: HomeData,
) : ViewModel() {
    private val mutable = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = mutable.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { mutable.value = homeStateOf(data.load()) }
    }
}
