package dev.rosty.stops.home

import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.stops.SampleStops
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStateTest {
    @Test fun theTileHostIsReadFromTheTemplate() {
        assertEquals("tiles.versatiles.org", tileServerHost(null))
        assertEquals("tiles.versatiles.org", tileServerHost("  "))
        assertEquals("tiles.example.org", tileServerHost("https://tiles.example.org/{z}/{x}/{y}.pbf"))
        assertEquals("tiles.example.org:8080", tileServerHost("http://tiles.example.org:8080/t/{z}/{x}/{y}"))
        assertEquals("tiles.example.org", tileServerHost("tiles.example.org/{z}/{x}/{y}"))
        assertEquals("a.example", tileServerHost("https://a.example?key=1"))
    }

    @Test fun theStateCarriesWhatTheSnapshotRead() {
        val snapshot =
            HomeSnapshot(
                prefs = StopsPrefs(tileUrl = "https://tiles.example.org/{z}/{x}/{y}.pbf", showSavedOnMap = false),
                trip = TripSummary("Pier West – Pier East", SampleStops.pierW, SampleStops.pierE),
                saved = SampleStops.saved,
                history = SampleStops.history,
                transitImportedMs = 1_790_000_000_000L,
            )
        val state = homeStateOf(snapshot)
        assertFalse(state.loading)
        assertEquals("Pier West – Pier East", state.trip?.label)
        assertEquals(listOf(1L, 2L), state.saved.map { it.id })
        assertEquals(1, state.historyCount)
        assertEquals(1_790_000_000_000L, state.transitImportedMs)
        assertEquals("tiles.example.org", state.tileServer)
        assertFalse(state.savedOnMap)
    }

    @Test fun missingFilesAreAnEmptyHome() {
        val state = homeStateOf(HomeSnapshot(StopsPrefs(), null, null, null, null))
        assertNull(state.trip)
        assertTrue(state.saved.isEmpty())
        assertEquals(0, state.historyCount)
        assertNull(state.transitImportedMs)
        assertEquals(DEFAULT_TILE_HOST, state.tileServer)
    }

    @Test fun aTripSummaryReadsItsEnds() {
        val json =
            Json.parseToJsonElement(
                """{"id":7,"a":{"label":"Home","coord":{"lat":41.0,"lon":28.994}},
                   "b":{"label":"Work","coord":{"lat":41.0,"lon":29.006}},"started_ms":1000}""",
            )
        val core = tripSummary(json, "From the core")
        assertEquals("From the core", core.label)
        assertEquals("Home", core.a?.label)
        assertEquals(29.006, core.b!!.coord.lon, 0.0)
        assertEquals("Home – Work", tripSummary(json, null).label)
        val bare = tripSummary(Json.parseToJsonElement("""{"id":1,"a":{"oops":1}}"""), " ")
        assertNull(bare.a)
        assertEquals("", bare.label)
    }
}
