package dev.rosty.stops.settings

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.TilesTestServerAnswer
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.core.ops.NativeTilesOps
import dev.rosty.design.text.UiText
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.R
import dev.rosty.stops.data.StopsStore
import dev.rosty.stops.data.StopsTiles
import dev.rosty.stops.offline.MapArea
import dev.rosty.stops.offline.OfflineDownloads
import dev.rosty.stops.offline.OfflineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * E7.10: the Map tiles page. The server test, the estimate and the prefs run on the REAL core; the cache and the
 * download are scripted ([FakeTilesOps], progress through its own event bus as Rust sends it).
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
class TilesViewModelTest {
    private lateinit var main: ExecutorCoroutineDispatcher
    private val fake = FakeStopsCore(real = HostCore.real())
    private val ops = NativeStopsOps(fake.rust)
    private val store = StopsStore(ops)
    private val tilesOps = FakeTilesOps(NativeTilesOps(HostCore.real()))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tiles =
        StopsTiles({ store.prefs.value }, Files.createTempDirectory("tiles").toString()) { tilesOps.openHandle(it) }
    private val scheduled = mutableListOf<MapArea>()
    private lateinit var offline: OfflineDownloads
    private val mapView = MutableStateFlow<MapArea?>(null)
    private lateinit var model: TilesViewModel

    @Before fun setUp() {
        main = newSingleThreadContext("main")
        Dispatchers.setMain(main)
        offline =
            OfflineDownloads(tiles, scope, schedule = { area ->
                scheduled += area
                // The worker's part, in this process.
                scope.launch { offline.run(area) }
            }, unschedule = {})
        model = TilesViewModel(store, ops, tilesOps, tiles, offline, mapView)
        settled { it.cache != null }
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    private fun settled(check: (TilesUiState) -> Boolean): TilesUiState =
        runBlocking { withTimeout(TIMEOUT) { model.state.first { !it.loading && check(it) } } }

    private fun nextEffect(): TilesEffect = runBlocking { withTimeout(TIMEOUT) { model.effects.first() } }

    private fun type(text: String) = runBlocking(main) { model.urlField.setTextAndPlaceCursorAtEnd(text) }

    @Test fun theFieldShowsTheDefaultServerAndTheCacheIsMeasured() {
        val state = settled { it.defaultUrl.isNotEmpty() }
        assertTrue(state.isDefault)
        assertEquals(state.defaultUrl, model.urlField.text.toString())
        assertEquals(CacheUi(40L * FakeTilesOps.MB, 12L * FakeTilesOps.MB), state.cache)
        assertEquals(listOf(128, 256, 512, 1024), state.cacheChoices)
    }

    @Test fun aServerThatFailsIsNotSaved() {
        type("ftp://tiles.example.org/{z}/{x}/{y}")
        model.testAndSave()
        assertEquals(TilesEffect.Toast(UiText.Res(R.string.tiles_error_scheme)), nextEffect())
        type("http://127.0.0.1:1/{z}/{x}/{y}")
        model.testAndSave()
        assertEquals(TilesEffect.Toast(UiText.Res(R.string.tiles_error_unreachable)), nextEffect())
        assertNull(fake.prefs.tileUrl)
        assertTrue(fake.savedPrefs.isEmpty())
    }

    @Test fun aWorkingServerIsSavedAndResetAndUndoMoveBetweenThem() {
        tilesOps.testAnswer = Outcome.Ok(TilesTestServerAnswer(layers = 3))
        type("https://tiles.example.org/{z}/{x}/{y}.pbf")
        model.testAndSave()
        val saved = nextEffect() as TilesEffect.Undo
        assertEquals(UiText.Plural(R.plurals.tiles_server_works, 3), saved.text)
        assertEquals("https://tiles.example.org/{z}/{x}/{y}.pbf", fake.prefs.tileUrl)
        assertEquals("tiles.example.org", settled { !it.isDefault }.host)
        model.resetServer()
        val reset = nextEffect() as TilesEffect.Undo
        assertNull(fake.prefs.tileUrl)
        settled { it.isDefault }
        reset.undo()
        settled { !it.isDefault }
        assertEquals("https://tiles.example.org/{z}/{x}/{y}.pbf", fake.prefs.tileUrl)
    }

    @Test fun theCacheCapIsReachedThroughRustAndAShrinkBelowTheUsageAsks() {
        model.setCache(512)
        assertEquals(TilesEffect.Toast(UiText.Res(R.string.tiles_cache_limit, listOf(512))), nextEffect())
        assertEquals(512, fake.prefs.tileCacheMb)
        assertEquals(512L * FakeTilesOps.MB, tilesOps.caps.last())
        settled { it.cacheMb == 512 }
        assertFalse("40 MB fit in 128 MB", model.shrinks(128))
        tilesOps.sources =
            listOf(
                dev.rosty.core.model.stops
                    .TileSourceRow("versatiles", 300L * FakeTilesOps.MB, true),
            )
        model.measure()
        settled { it.cache?.sourceBytes == 300L * FakeTilesOps.MB }
        assertTrue(model.shrinks(128))
        assertFalse("growing never asks", model.shrinks(1024))
    }

    @Test fun clearingFreesTheOtherServersOrEverything() {
        model.clearOthers()
        assertEquals(TilesEffect.Freed(12L * FakeTilesOps.MB), nextEffect())
        assertEquals(0L, settled { it.cache?.otherBytes == 0L }.cache!!.otherBytes)
        model.clearAll()
        assertEquals(TilesEffect.Toast(UiText.Res(R.string.tiles_cleared)), nextEffect())
        assertEquals(1, tilesOps.clears)
    }

    @Test fun theMapsViewIsEstimatedThenDownloadedWithProgress() {
        assertNull(settled { true }.area)
        mapView.value = FakeTilesOps.AREA
        val area = settled { it.area != null }.area!!
        assertTrue(area.tiles > 0)
        assertTrue(area.bytes > 0)
        model.download()
        val seen = mutableListOf<OfflineState>()
        runBlocking {
            withTimeout(TIMEOUT) {
                model.state.first { s ->
                    seen += s.offline
                    s.offline is OfflineState.Finished
                }
            }
        }
        assertEquals(listOf(FakeTilesOps.AREA), scheduled)
        assertEquals(FakeTilesOps.AREA.bbox, tilesOps.starts.single().bbox)
        assertTrue(seen.toString(), seen.any { it is OfflineState.Running })
        assertEquals(OfflineState.Finished(saved = 4, failed = 0, total = 4, cancelled = false), seen.last())
    }

    @Test fun aRunningDownloadIsCancelledAndASecondStartRefused() {
        tilesOps.hold = true
        mapView.value = FakeTilesOps.AREA
        settled { it.area != null }
        model.download()
        settled { it.offline is OfflineState.Running }
        model.download()
        assertEquals(TilesEffect.Toast(UiText.Res(R.string.offline_already_running)), nextEffect())
        model.cancelDownload()
        val end = settled { it.offline is OfflineState.Finished }.offline as OfflineState.Finished
        assertTrue(end.cancelled)
        assertEquals(1, tilesOps.cancels)
        offline.dismiss()
        assertEquals(OfflineState.Idle, settled { it.offline == OfflineState.Idle }.offline)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
