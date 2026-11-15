package dev.rosty.stops.saved

import dev.rosty.core.RustError
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.SavedStop
import dev.rosty.design.text.UiText
import dev.rosty.stops.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedLogicTest {
    private fun stop(
        id: Long,
        favourite: Boolean = false,
    ) = SavedStop(id, "S$id", Endpoint("a", Coord(41.0, 29.0)), Endpoint("b", Coord(41.001, 29.0)), 0L, favourite)

    /** Favourites first, as Rust keeps the file: 1* 2* 3 4 5. */
    private val stops = listOf(stop(1, true), stop(2, true), stop(3), stop(4), stop(5))

    @Test fun moveStaysInsideTheGroup() {
        assertFalse(canMove(stops, 1, -1))
        assertTrue(canMove(stops, 1, 1))
        assertFalse(canMove(stops, 2, 1))
        assertFalse(canMove(stops, 3, -1))
        assertTrue(canMove(stops, 3, 1))
        assertFalse(canMove(stops, 5, 1))
        assertFalse(canMove(stops, 9, 1))
        assertFalse(canMove(stops, 3, 0))
    }

    @Test fun dragDeltaCountsTheOwnGroupOnly() {
        assertEquals(2, groupDelta(stops, 2, 4))
        assertEquals(-1, groupDelta(stops, 4, 3))
        assertEquals(1, groupDelta(stops, 0, 1))
        // Dropped among the other group: only the passed stops of its own group count (Rust clamps at the edge).
        assertEquals(1, groupDelta(stops, 0, 3))
        assertEquals(-1, groupDelta(stops, 3, 0))
        assertEquals(0, groupDelta(stops, 1, 1))
        assertEquals(0, groupDelta(stops, 7, 1))
    }

    @Test fun aFavouriteHasAStar() {
        assertEquals("★ S1", rowTitle(stops[0]))
        assertEquals("S3", rowTitle(stops[2]))
    }

    @Test fun searchAndSortShowFromTheirCounts() {
        assertFalse(showSearch(5))
        assertTrue(showSearch(6))
        assertFalse(showSort(1))
        assertTrue(showSort(2))
    }

    @Test fun refusalsAreWordedFromTheirReason() {
        fun reason(code: String?) =
            RustError.Invalid("x", code?.let { JsonObject(mapOf("reason" to JsonPrimitive(it))) })
        assertEquals(UiText.Res(R.string.saved_error_name), SavedViewModel.refusal(reason("invalid_name")))
        assertEquals(UiText.Res(R.string.saved_error_duplicate), SavedViewModel.refusal(reason("duplicate")))
        assertEquals(UiText.Res(R.string.saved_error_full), SavedViewModel.refusal(reason("full")))
        assertEquals(UiText.Res(R.string.saved_error_gate), SavedViewModel.refusal(reason("invalid_gate")))
        assertEquals(UiText.Res(R.string.saved_error_gone), SavedViewModel.refusal(reason("not_found")))
        assertEquals(UiText.Res(R.string.saved_error_other), SavedViewModel.refusal(reason(null)))
    }

    @Test fun openInMapsAimsAtTheMiddleOfTheGate() {
        val uri = SavedViewModel.geoUriOf(stops[0])
        val (lat, lon) =
            uri
                .removePrefix("geo:")
                .substringBefore('?')
                .split(',')
                .map(String::toDouble)
        assertEquals(41.0005, lat, 1e-9)
        assertEquals(29.0, lon, 1e-9)
        assertTrue(uri.contains("?q="))
    }
}
