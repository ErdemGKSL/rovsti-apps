package dev.rosty.stops.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.PrefsEdit
import dev.rosty.core.model.stops.TilesPlanRequest
import dev.rosty.core.model.stops.TilesTestServerRequest
import dev.rosty.core.ops.StopsOps
import dev.rosty.core.ops.TileHandle
import dev.rosty.core.ops.TilesOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.R
import dev.rosty.stops.data.StopsStore
import dev.rosty.stops.data.StopsTiles
import dev.rosty.stops.home.tileServerHost
import dev.rosty.stops.offline.MapArea
import dev.rosty.stops.offline.OfflineDownloads
import dev.rosty.stops.offline.OfflineState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The cache of the tile servers (S22): bytes of the server in use and of the others. */
@Immutable
data class CacheUi(
    val sourceBytes: Long,
    val otherBytes: Long,
)

/** The estimate of the area the map showed last (S10), from `tiles.offline_plan`. */
@Immutable
data class AreaUi(
    val area: MapArea,
    val tiles: Int,
    val bytes: Long,
    val topZoom: Int,
    /** The budget stopped short of street detail: zoom in. */
    val capped: Boolean,
)

@Immutable
data class TilesUiState(
    val loading: Boolean = true,
    /** The server in use is the default one (Reset hides). */
    val isDefault: Boolean = true,
    val host: String = tileServerHost(null),
    val defaultUrl: String = "",
    val testing: Boolean = false,
    /** Null while it is measured. */
    val cache: CacheUi? = null,
    val cacheMb: Int = 128,
    val cacheChoices: ImmutableList<Int> = persistentListOf(),
    /** Null before the map has been shown in this run (there is no view to download). */
    val area: AreaUi? = null,
    val offline: OfflineState = OfflineState.Idle,
)

/** What the tiles page shows once. */
sealed interface TilesEffect {
    data class Toast(
        val text: UiText,
    ) : TilesEffect

    data class Undo(
        val text: UiText,
        val undo: () -> Unit,
    ) : TilesEffect

    /** "Freed 12 MB": the page words the size. */
    data class Freed(
        val bytes: Long,
    ) : TilesEffect
}

/**
 * Settings > Map tiles (E7.10): the server URL with its test (`tiles.test_server`, then `prefs_edit set_tile_url`: a
 * server that fails is not saved; the map reopens its handle on the new server), the cache cap (S22, a choice of
 * `prefs_choices`, reached through `next_tile_cache`), the usage per server with Clear, and the offline area (S10):
 * the estimate of the map's last view (`tiles.offline_plan`) and the download through [OfflineDownloads].
 * Failures are worded from the core's `detail.reason` codes in our own strings.
 */
class TilesViewModel(
    private val store: StopsStore,
    private val ops: StopsOps,
    private val tilesOps: TilesOps,
    private val tiles: StopsTiles,
    private val offline: OfflineDownloads,
    private val mapView: StateFlow<MapArea?>,
) : ViewModel() {
    private val mutable = MutableStateFlow(TilesUiState())
    val state: StateFlow<TilesUiState> = mutable.asStateFlow()

    /** The URL field (ARCH 4.6: one state for the field's life, never recreated per keystroke). */
    val urlField = TextFieldState()

    private val effectChannel = Channel<TilesEffect>(Channel.BUFFERED)
    val effects: Flow<TilesEffect> = effectChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val choices = (ops.prefsChoices(Empty) as? Outcome.Ok)?.value
            mutable.update {
                it.copy(
                    defaultUrl = choices?.defaultTileUrl.orEmpty(),
                    cacheChoices = choices?.tileCacheMb.orEmpty().toImmutableList(),
                )
            }
            store.load()
            fillField()
            mutable.update { it.copy(loading = false) }
            measure()
        }
        viewModelScope.launch {
            store.prefs.collect { p ->
                mutable.update {
                    it.copy(
                        isDefault = p.tileUrl.isNullOrBlank(),
                        host = tileServerHost(p.tileUrl),
                        cacheMb = p.tileCacheMb,
                    )
                }
            }
        }
        viewModelScope.launch { offline.state.collect { s -> onOffline(s) } }
        viewModelScope.launch { mapView.collect { area -> estimate(area) } }
    }

    private fun toast(
        @StringRes id: Int,
        vararg args: Any,
    ) {
        effectChannel.trySend(TilesEffect.Toast(UiText.Res(id, args.toList())))
    }

    /** The field shows the server in use (the default template when none is chosen). */
    private fun fillField() {
        val url =
            store.prefs.value.tileUrl
                ?.takeIf { it.isNotBlank() } ?: state.value.defaultUrl
        urlField.setTextAndPlaceCursorAtEnd(url)
    }

    private suspend fun handle(): TileHandle? = (tiles.handle() as? Outcome.Ok)?.value

    /** Measures the cache of every server off the main thread. */
    fun measure() {
        viewModelScope.launch {
            val h = handle() ?: return@launch
            val sources = (h.usageBySource() as? Outcome.Ok)?.value?.sources ?: return@launch
            mutable.update {
                it.copy(
                    cache =
                        CacheUi(
                            sourceBytes = sources.filter { s -> s.current }.sumOf { s -> s.bytes },
                            otherBytes = sources.filterNot { s -> s.current }.sumOf { s -> s.bytes },
                        ),
                )
            }
        }
    }

    // ---- server ----

    /** Downloads one tile of the typed server first; only a server that works is saved. */
    fun testAndSave() {
        if (state.value.testing) return
        val typed =
            urlField.text
                .toString()
                .trim()
        mutable.update { it.copy(testing = true) }
        viewModelScope.launch {
            val tested = tilesOps.testServer(TilesTestServerRequest(typed))
            when (tested) {
                is Outcome.Err -> {
                    effectChannel.trySend(TilesEffect.Toast(serverError(tested.error)))
                }

                is Outcome.Ok -> {
                    val before = store.prefs.value.tileUrl
                    when (val saved = store.edit(PrefsEdit.SetTileUrl(typed))) {
                        is Outcome.Err -> {
                            effectChannel.trySend(TilesEffect.Toast(serverError(saved.error)))
                        }

                        is Outcome.Ok -> {
                            effectChannel.trySend(
                                TilesEffect.Undo(
                                    UiText.Plural(R.plurals.tiles_server_works, tested.value.layers),
                                ) { restoreServer(before) },
                            )
                            measure()
                        }
                    }
                }
            }
            mutable.update { it.copy(testing = false) }
        }
    }

    /** Back to the public VersaTiles server; Undo puts the custom one back. */
    fun resetServer() {
        val before = store.prefs.value.tileUrl
        viewModelScope.launch {
            if (store.edit(PrefsEdit.SetTileUrl(null)) is Outcome.Ok) {
                fillField()
                measure()
                effectChannel.trySend(
                    TilesEffect.Undo(UiText.Res(R.string.tiles_server_reset)) { restoreServer(before) },
                )
            }
        }
    }

    private fun restoreServer(url: String?) {
        viewModelScope.launch {
            store.edit(PrefsEdit.SetTileUrl(url))
            fillField()
            measure()
        }
    }

    // ---- cache ----

    /** True when choosing [mb] deletes tiles now (the cache holds more than it allows): the page asks first. */
    fun shrinks(mb: Int): Boolean {
        val held = state.value.cache?.sourceBytes ?: return false
        return mb < state.value.cacheMb && held > mb.toLong() * BYTES_PER_MB
    }

    fun setCache(mb: Int) {
        val steps = state.value.cacheChoices.size + 1
        viewModelScope.launch {
            val done = store.edit(PrefsEdit.NextTileCache, steps, { it.tileCacheMb == mb })
            if (done !is Outcome.Ok) {
                toast(R.string.settings_save_failed)
                return@launch
            }
            // The handle reopens with the new cap; setting it again evicts the oldest tiles at once.
            handle()?.setCap(done.value.tileCacheBytes)
            toast(R.string.tiles_cache_limit, mb)
            measure()
        }
    }

    /** After the page's confirmation: every cached tile of every server; refused while a trip runs. */
    fun clearAll() {
        viewModelScope.launch {
            if (tripRunning()) {
                toast(R.string.tiles_not_during_trip)
                return@launch
            }
            when (handle()?.clearAll()) {
                is Outcome.Ok -> toast(R.string.tiles_cleared)
                else -> toast(R.string.tiles_clear_failed)
            }
            measure()
        }
    }

    /** After the page's confirmation: the caches of the servers not in use. */
    fun clearOthers() {
        viewModelScope.launch {
            if (tripRunning()) {
                toast(R.string.tiles_not_during_trip)
                return@launch
            }
            when (val freed = handle()?.clearExcept()) {
                is Outcome.Ok -> effectChannel.trySend(TilesEffect.Freed(freed.value.freedBytes))
                else -> toast(R.string.tiles_clear_failed)
            }
            measure()
        }
    }

    private suspend fun tripRunning(): Boolean = (ops.storeTripLoad(DirRequest()) as? Outcome.Ok)?.value?.trip != null

    // ---- offline area ----

    private suspend fun estimate(area: MapArea?) {
        if (area == null) {
            mutable.update { it.copy(area = null) }
            return
        }
        val plan = (tilesOps.offlinePlan(TilesPlanRequest(bbox = area.bbox)) as? Outcome.Ok)?.value
        mutable.update {
            it.copy(area = plan?.let { p -> AreaUi(area, p.total, p.estimateBytes, p.topZoom, p.capped) })
        }
    }

    /** After the page's confirmation: starts the download of the estimated area. */
    fun download() {
        val area = state.value.area ?: return
        if (!offline.start(area.area)) toast(R.string.offline_already_running)
    }

    fun cancelDownload() = offline.cancel()

    private fun onOffline(s: OfflineState) {
        val before = state.value.offline
        mutable.update { it.copy(offline = s) }
        // The download ended: the cache grew.
        if (before.active && !s.active) measure()
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}

/** The words for a failed server test or save, from the core's `detail.reason` (pure). */
fun serverError(error: RustError): UiText {
    val status = ((error.detail as? JsonObject)?.get("status") as? JsonPrimitive)?.intOrNull
    val id =
        when (error.reason) {
            "empty" -> R.string.tiles_error_empty
            "scheme" -> R.string.tiles_error_scheme
            "placeholder" -> R.string.tiles_error_placeholder
            "whitespace" -> R.string.tiles_error_whitespace
            "too_long" -> R.string.tiles_error_too_long
            "unreachable" -> R.string.tiles_error_unreachable
            "refused" -> R.string.tiles_error_refused
            "no_tile" -> R.string.tiles_error_no_tile
            "status" -> return UiText.Res(R.string.tiles_error_status, listOf(status ?: 0))
            "too_large" -> R.string.tiles_error_too_large
            "not_vector" -> R.string.tiles_error_not_vector
            "storage" -> R.string.tiles_error_storage
            else -> R.string.tiles_error_other
        }
    return UiText.Res(id)
}
