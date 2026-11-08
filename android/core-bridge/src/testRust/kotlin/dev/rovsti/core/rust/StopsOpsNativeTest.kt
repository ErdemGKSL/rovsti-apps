package dev.rovsti.core.rust

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.stops.Coord
import dev.rovsti.core.model.stops.CoordFormatRequest
import dev.rovsti.core.model.stops.CrossingValidateRequest
import dev.rovsti.core.model.stops.DestParseRequest
import dev.rovsti.core.model.stops.Destination
import dev.rovsti.core.model.stops.Endpoint
import dev.rovsti.core.model.stops.FixIn
import dev.rovsti.core.model.stops.GtfsImportRequest
import dev.rovsti.core.model.stops.PickEvent
import dev.rovsti.core.model.stops.PickReplayRequest
import dev.rovsti.core.model.stops.ProjectRequest
import dev.rovsti.core.model.stops.Side
import dev.rovsti.core.model.stops.Slot
import dev.rovsti.core.model.stops.TripState
import dev.rovsti.core.model.stops.Viewport
import dev.rovsti.core.ops.ActiveTripHandle
import dev.rovsti.core.ops.NativeStopsOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The Stops ops through the real library: the `ActiveTrip` handle (create, fix, ring, close) and stateless ops. */
class StopsOpsNativeTest {
    private val ops = NativeStopsOps(TestRust.core)

    private val home = Endpoint("Home", Coord(41.0, 28.994))
    private val work = Endpoint("Work", Coord(41.0, 29.006))

    @Before
    fun load() = TestRust.ensure()

    private fun fix(
        lat: Double,
        lon: Double,
        at: Long,
    ) = FixIn(lat, lon, accuracyM = 5.0, atMs = at, speedMps = 8.0)

    private fun open(dir: String? = null): ActiveTripHandle =
        runBlocking { ActiveTripHandle.start(7, home, work, 1_000, dir = dir, ops = ops) }.getOrOk()

    @Test
    fun createFixAndClose() =
        runBlocking {
            val trip = open()
            trip.use {
                assertTrue(it.id > 0)
                assertEquals("Home – Work", it.opened.label)
                assertEquals(TripState.Tracking, it.opened.state)
                // North of the line, then south: the second fix crosses it and rings once.
                val first = it.fix(fix(41.003, 28.999, 10_000)).getOrOk()
                assertFalse(first.out.ring)
                assertEquals(Side.Left, first.out.side)
                val second = it.fix(fix(40.997, 29.0, 15_000)).getOrOk()
                assertTrue(second.out.ring)
                assertEquals(TripState.Arrived, second.out.state)
                assertEquals("Crossed Home – Work", second.out.title)
                assertFalse(
                    it
                        .fix(fix(40.996, 29.0, 20_000))
                        .getOrOk()
                        .out.ring,
                )

                val snoozed = it.snooze(nowMs = 20_000, minutes = 3).getOrOk()
                assertEquals(4, snoozed.snoozesLeft)
                assertEquals(200_000L, snoozed.ringAtMs)
                assertEquals(TripState.Dismissed, it.dismiss().getOrOk().state)
                val ended = it.end("arrived", nowMs = 60_000).getOrOk()
                assertEquals("Arrived", ended.record.outcome.name)
                assertTrue(
                    it
                        .statusShare(lineM = 120.0, etaMin = 2)
                        .getOrOk()
                        .text
                        .contains("Home"),
                )
            }
            assertTrue(trip.released)
            val closed = trip.state() as Outcome.Err
            assertTrue(closed.error is RustError.NotFound)
            trip.close() // closing twice is harmless
        }

    @Test
    fun aBadGateIsRefusedWithTheSentence() =
        runBlocking {
            val short = ActiveTripHandle.start(8, home, Endpoint("Near", Coord(41.0, 28.9941)), 0, ops = ops)
            val error = (short as Outcome.Err).error
            assertTrue(error is RustError.Invalid)
            assertEquals("A and B must be at least 20 m apart.", error.message)
            val checked = ops.crossingValidate(CrossingValidateRequest(Coord(41.0, 29.0), Coord(41.0, 29.0)))
            assertEquals("A and B are the same place.", (checked as Outcome.Err).error.message)
        }

    @Test
    fun aRestoredTripKeepsItsState() =
        runBlocking {
            open().use { first ->
                first.fix(fix(41.003, 28.999, 10_000)).getOrOk()
                val snapshot = first.state().getOrOk().snapshot
                ActiveTripHandle.restore(snapshot, ops = ops).getOrOk().use { second ->
                    assertEquals(Side.Left, second.opened.side)
                    assertEquals(1, second.opened.fixes)
                    assertTrue(
                        second
                            .fix(fix(40.997, 29.0, 15_000))
                            .getOrOk()
                            .out.ring,
                    )
                }
            }
        }

    @Test
    fun withADirTheServiceFileFollowsTheHandle() =
        runBlocking {
            val dir =
                kotlin.io.path
                    .createTempDirectory("rovsti-trip")
                    .toFile()
            try {
                open(dir.path).use {
                    it.fix(fix(41.003, 28.999, 10_000)).getOrOk()
                    val stored =
                        ops
                            .storeTripLoad(
                                dev.rovsti.core.model.phone
                                    .DirRequest(dir.path),
                            ).getOrOk()
                    assertNotNull(stored.trip)
                    assertEquals("Home – Work", stored.label)
                    val ended =
                        ops
                            .storeTripEnd(
                                dev.rovsti.core.model.stops
                                    .StoreTripEndRequest("cancelled", 99_000, dir.path),
                            ).getOrOk()
                    assertEquals(7L, ended.record?.id)
                    assertNull(
                        ops
                            .storeTripLoad(
                                dev.rovsti.core.model.phone
                                    .DirRequest(dir.path),
                            ).getOrOk()
                            .trip,
                    )
                }
            } finally {
                dir.deleteRecursively()
            }
        }

    @Test
    fun statelessOpsAnswer() =
        runBlocking {
            val replay =
                ops
                    .pickReplay(
                        PickReplayRequest(
                            listOf(
                                PickEvent.Tap(Destination("", home.coord)),
                                PickEvent.Tap(Destination("Work", work.coord)),
                            ),
                        ),
                    ).getOrOk()
            assertTrue(replay.canStart)
            assertEquals(Slot.B, replay.sheetSlot)
            assertEquals("A-B 1.0 km", replay.hint.text)

            val place =
                ops
                    .destParse(
                        DestParseRequest("geo:41.0082,28.9784"),
                    ).getOrOk()
            assertEquals(41.0082, place.place?.coord?.lat ?: 0.0, 1e-9)

            val formats = ops.coordFormat(CoordFormatRequest(Coord(41.0082, 28.9784))).getOrOk()
            assertEquals("41.008200, 28.978400", formats.formats.first().text)

            val projected =
                ops
                    .viewProject(
                        ProjectRequest(Viewport(Coord(41.0, 29.0), 4.0), points = listOf(Coord(41.0, 29.0))),
                    ).getOrOk()
            val (x, y) = projected.projected.single()
            assertEquals(0.0, x, 1e-6)
            assertEquals(0.0, y, 1e-6)
        }

    @Test
    fun theTinyGtfsZipImports() =
        runBlocking {
            val fixtures = System.getProperty("rovsti.contract")
            val zip = File(fixtures, "../tests/data/gtfs-tiny.zip").canonicalPath
            val report = ops.gtfsImport(GtfsImportRequest(zip, nowMs = 1_000)).getOrOk().report
            assertEquals(3, report.stops)
            assertEquals(1, report.routes)
            assertEquals(2, report.patterns)
            val missing = ops.gtfsImport(GtfsImportRequest("/nope/feed.zip", nowMs = 1)) as Outcome.Err
            assertTrue(missing.error is RustError.NotFound)
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
