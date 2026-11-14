package dev.rosty.stops

import dev.rosty.core.Outcome
import dev.rosty.core.Rust
import dev.rosty.core.model.stops.Appearance
import dev.rosty.core.model.stops.VibrationPattern
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.stops.data.StopsStore
import dev.rosty.stops.home.CoreHomeData
import dev.rosty.stops.home.DEFAULT_TILE_HOST
import dev.rosty.stops.home.homeStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E7.1 AC3 migration: the files the GPUI Stops app wrote are read by the new app's Rust core unchanged, and the home's
 * first screen state shows them: `stops-prefs.json`, `trip.json`, `saved.json` (v2), `history.json` and `gtfs.json`; an
 * old single-destination `trip.json` is moved aside to `trip.legacy.json` and the home opens without a trip (AD-SX8).
 *
 * The fixtures in `src/test/resources/migration` are copies of `crates/rosty_app_core/tests/fixtures` (`dirs/stops`,
 * `dirs/stops_files`, `dirs/stops_gtfs` and `trip.legacy.json`); the first test fails when a copy goes stale. The rest
 * runs on the host `librosty_ffi` that `:core-bridge:cargoBuildHost` builds (skipped, with a message, when it is absent),
 * through the same store and home loader the container wires.
 */
class StopsMigrationTest {
    private val files =
        mapOf(
            "stops_files/stops-prefs.json" to "stops-prefs.json",
            "stops/trip.json" to "trip.json",
            "stops_files/saved.json" to "saved.json",
            "stops_files/history.json" to "history.json",
            "stops_gtfs/gtfs.json" to "gtfs.json",
        )

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/migration/$path")) { "missing $path" }.readBytes()

    @Test fun theCopiedFixturesEqualTheRustOnes() {
        val dirs = File(System.getProperty("rosty.rustFixtureDirs")!!)
        for (path in files.keys) {
            assertTrue("$path is stale: copy it from $dirs", File(dirs, path).readBytes().contentEquals(resource(path)))
        }
        val legacy = File(dirs.parentFile, "trip.legacy.json")
        assertTrue(
            "trip.legacy.json is stale: copy it from $legacy",
            legacy.readBytes().contentEquals(resource("trip.legacy.json")),
        )
    }

    @Test fun theFirstScreenReadsPrefsTripSavedHistoryAndTransit() =
        runBlocking {
            val root = startRust()
            val data = File(root, "no_backup").apply { mkdirs() }
            for ((from, to) in files) File(data, to).writeBytes(resource(from))
            val dir = data.absolutePath
            val ops = NativeStopsOps(Rust.core())
            val store = StopsStore(ops, dir)
            val snapshot = CoreHomeData(ops, store, dir).load()

            // stops-prefs.json: every field of the old file survives.
            val prefs = snapshot.prefs
            assertEquals(Appearance.Dark, prefs.appearance)
            assertFalse(prefs.vibrate)
            assertEquals(10, prefs.snoozeMin)
            assertEquals(VibrationPattern.Pulse, prefs.vibration)
            assertEquals(90, prefs.volumePct)
            assertEquals(256, prefs.tileCacheMb)
            assertNull(prefs.tileUrl)
            assertEquals(prefs, store.prefs.value)

            // trip.json: the trip in progress, Home to Work.
            val trip = snapshot.trip!!
            assertEquals("Home", trip.a?.label)
            assertEquals("Work", trip.b?.label)
            assertTrue(trip.label.isNotBlank())

            // saved.json (v2), history.json, gtfs.json.
            assertEquals(2, snapshot.saved!!.version)
            assertEquals(listOf("Pier"), snapshot.saved!!.stops.map { it.name })
            assertTrue(
                snapshot.saved!!
                    .stops
                    .single()
                    .favourite,
            )
            assertEquals(listOf(4L), snapshot.history!!.records.map { it.id })
            assertEquals(1_790_000_000_000L, snapshot.transitImportedMs)

            // The first screen's state.
            val state = homeStateOf(snapshot)
            assertFalse(state.loading)
            assertEquals(trip, state.trip)
            assertEquals(1, state.saved.size)
            assertEquals(1, state.historyCount)
            assertEquals(1_790_000_000_000L, state.transitImportedMs)
            assertEquals(DEFAULT_TILE_HOST, state.tileServer)
            assertTrue(state.savedOnMap)

            // A write by the new app is read back by a fresh store (the next start).
            (store.update { it.copy(appearance = Appearance.Light) } as Outcome.Ok)
            assertEquals(Appearance.Light, (StopsStore(ops, dir).load() as Outcome.Ok).value.appearance)
            assertEquals(256, (StopsStore(ops, dir).load() as Outcome.Ok).value.tileCacheMb)
        }

    @Test fun anOldTripFileIsMovedAsideAndTheHomeOpensWithoutATrip() =
        runBlocking {
            val root = startRust()
            val data = File(root, "legacy").apply { mkdirs() }
            val old = resource("trip.legacy.json")
            File(data, "trip.json").writeBytes(old)
            val dir = data.absolutePath
            val ops = NativeStopsOps(Rust.core())
            val snapshot = CoreHomeData(ops, StopsStore(ops, dir), dir).load()
            assertNull(snapshot.trip)
            assertFalse("trip.json is gone", File(data, "trip.json").exists())
            assertArrayEquals("kept byte for byte", old, File(data, "trip.legacy.json").readBytes())
            // No other file: an empty home with the default preferences.
            val state = homeStateOf(snapshot)
            assertNull(state.trip)
            assertEquals(0, state.saved.size)
            assertEquals(0, state.historyCount)
            assertNull(state.transitImportedMs)
            assertEquals(Appearance.Auto, snapshot.prefs.appearance)
        }

    private suspend fun startRust(): File {
        val lib = File(System.getProperty("rosty.hostLib").orEmpty())
        assumeTrue(
            "host librosty_ffi not built (run :core-bridge:cargoBuildHost)",
            lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rosty_ffi") },
        )
        val root = Files.createTempDirectory("rosty-stops-migration").toFile().apply { deleteOnExit() }
        val started =
            Rust
                .init(
                    File(root, "rust-data"),
                    File(root, "cache"),
                    "dev.rosty.stops",
                    35,
                    CoroutineScope(Dispatchers.Default),
                ).await()
        assertTrue("Rust.init: $started", started is Outcome.Ok)
        return root
    }
}
