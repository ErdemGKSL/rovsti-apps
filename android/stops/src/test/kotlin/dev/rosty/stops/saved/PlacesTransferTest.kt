package dev.rosty.stops.saved

import dev.rosty.core.Outcome
import dev.rosty.core.ops.NativeStopsOps
import dev.rosty.stops.FakeStopsCore
import dev.rosty.stops.HostCore
import dev.rosty.stops.data.SavedStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S5 on the REAL places ops over the fake's `saved.json` (the two sample stops). */
class PlacesTransferTest {
    private val fake = FakeStopsCore(scriptTransit = false, real = HostCore.real())
    private val ops = NativeStopsOps(fake.rust)
    private val transfer = PlacesTransfer(ops, SavedStore(ops, nowMs = { NOW }), { NOW }, { "2026-09-21" })

    @Test fun bothFormatsExportTheList() =
        runBlocking {
            val gpx = (transfer.export(PlacesTransfer.FORMAT_GPX) as Outcome.Ok).value
            assertTrue(gpx.fileName, gpx.fileName.endsWith(".gpx"))
            assertTrue(gpx.text.contains("Pier"))
            val json = (transfer.export(PlacesTransfer.FORMAT_JSON) as Outcome.Ok).value
            assertTrue(json.fileName, json.fileName.endsWith(".json"))
        }

    @Test fun ourOwnFileAddsNothingAndSaysTheyAreSaved() =
        runBlocking {
            val json = (transfer.export(PlacesTransfer.FORMAT_JSON) as Outcome.Ok).value.text
            val plan = (transfer.plan(json) as Outcome.Ok).value.plan
            assertEquals(0, plan.add.size)
            assertEquals(2, plan.duplicates)
            assertNull(transfer.apply(json))
        }

    @Test fun aNewStopIsAddedAndUndoTakesItAway() =
        runBlocking {
            val plan = (transfer.plan(GPX) as Outcome.Ok).value.plan
            assertEquals(listOf("Bridge"), plan.add.map { it.name })
            val (old, added) = transfer.apply(GPX)!!
            assertEquals(1, added)
            assertEquals(3, fake.saved!!.stops.size)
            transfer.undo(old)
            assertEquals(2, fake.saved!!.stops.size)
        }

    @Test fun somethingElseIsRefused() =
        runBlocking {
            assertTrue(transfer.plan("hello") is Outcome.Err)
        }

    companion object {
        const val NOW = 1_790_066_400_000L
        const val GPX =
            "<?xml version=\"1.0\"?><gpx version=\"1.1\"><rte><name>Bridge</name>" +
                "<rtept lat=\"41.1\" lon=\"29.0\"/><rtept lat=\"41.1\" lon=\"29.01\"/></rte></gpx>"
    }
}
