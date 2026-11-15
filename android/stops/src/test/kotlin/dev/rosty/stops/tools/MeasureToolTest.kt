package dev.rosty.stops.tools

import dev.rosty.core.model.stops.Coord
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The measure tool over the REAL `stops.measure` and `stops.format_distance`. */
class MeasureToolTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ops = NativeStopsOps(FakeStopsCore(scriptTransit = false, real = HostCore.real()).rust)
    private val tool = MeasureTool(ops, scope, gpxName = { "Walk $it" }, today = { "2026-09-21" })

    @After fun tearDown() = scope.cancel()

    private fun settled(check: (MeasureUiState) -> Boolean): MeasureUiState =
        runBlocking { withTimeout(TIMEOUT) { tool.state.first(check) } }

    private fun point(i: Int) = Coord(41.0 + i * 0.01, 29.0)

    @Test fun offTheToolTakesNoTaps() {
        assertEquals(MeasureTap.Off, tool.tap(point(0)))
        assertFalse(tool.state.value.on)
    }

    @Test fun twoPointsMakeOneLegAndAGate() {
        tool.start()
        tool.tap(point(0))
        assertNull("one point has no result", tool.state.value.result)
        tool.tap(point(1))
        val result = settled { it.result != null }.result!!
        assertEquals(1, result.legs.size)
        assertTrue(result.total, result.total.endsWith("km"))
        assertNotNull(result.gate)
        assertEquals("rosty-stops-measure-2026-09-21.gpx", result.fileName)
        assertTrue(result.gpx.contains("Walk 2026-09-21"))
    }

    @Test fun undoAndClearTakePointsAwayAndRestoreGivesThemBack() {
        tool.start(from = point(0))
        tool.tap(point(1))
        tool.tap(point(2))
        assertEquals(2, settled { it.result?.points?.size == 3 }.result!!.legs.size)
        tool.undo()
        assertEquals(1, settled { it.result?.points?.size == 2 }.result!!.legs.size)
        val old = tool.clear()
        assertEquals(0, tool.state.value.points.size)
        assertNull(tool.state.value.result)
        tool.restore(old)
        assertEquals(2, settled { it.result != null }.points.size)
        tool.done()
        assertFalse(tool.state.value.on)
        assertTrue(
            tool.state.value.points
                .isEmpty(),
        )
    }

    @Test fun theCoreCapsThePoints() {
        tool.start()
        tool.tap(point(0))
        tool.tap(point(1))
        val cap = settled { it.maxPoints != null }.maxPoints!!
        for (i in 2 until cap) tool.tap(point(i))
        assertEquals(MeasureTap.Full, tool.tap(point(cap)))
        assertEquals(cap, tool.state.value.points.size)
    }

    @Test fun theCopyTextHasTheSummaryThenOneLinePerLeg() {
        val text = measureCopyText("Total 2 km, 2 legs", listOf("1 km", "1 km")) { n, leg -> "$n. $leg" }
        assertEquals("Total 2 km, 2 legs\n1. 1 km\n2. 1 km", text)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
