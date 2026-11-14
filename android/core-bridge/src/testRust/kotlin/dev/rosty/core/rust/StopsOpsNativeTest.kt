package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CoordFormatRequest
import dev.rosty.core.model.stops.CrossingValidateRequest
import dev.rosty.core.model.stops.DestParseRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.FixIn
import dev.rosty.core.model.stops.GtfsBbox
import dev.rosty.core.model.stops.GtfsImportRequest
import dev.rosty.core.model.stops.GtfsLinesRequest
import dev.rosty.core.model.stops.GtfsSummaryRequest
import dev.rosty.core.model.stops.GtfsViewRequest
import dev.rosty.core.model.stops.PickEvent
import dev.rosty.core.model.stops.PickReplayRequest
import dev.rosty.core.model.stops.ProjectRequest
import dev.rosty.core.model.stops.Side
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.TripState
import dev.rosty.core.model.stops.Viewport
import dev.rosty.core.ops.ActiveTripHandle
import dev.rosty.core.ops.NativeStopsOps
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
                    .createTempDirectory("rosty-trip")
                    .toFile()
            try {
                open(dir.path).use {
                    it.fix(fix(41.003, 28.999, 10_000)).getOrOk()
                    val stored =
                        ops
                            .storeTripLoad(
                                dev.rosty.core.model.phone
                                    .DirRequest(dir.path),
                            ).getOrOk()
                    assertNotNull(stored.trip)
                    assertEquals("Home – Work", stored.label)
                    val ended =
                        ops
                            .storeTripEnd(
                                dev.rosty.core.model.stops
                                    .StoreTripEndRequest("cancelled", 99_000, dir.path),
                            ).getOrOk()
                    assertEquals(7L, ended.record?.id)
                    assertNull(
                        ops
                            .storeTripLoad(
                                dev.rosty.core.model.phone
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
            val fixtures = System.getProperty("rosty.contract")
            val zip = File(fixtures, "../tests/data/gtfs-tiny.zip").canonicalPath
            val report = ops.gtfsImport(GtfsImportRequest(zip, nowMs = 1_000)).getOrOk().report
            assertEquals(3, report.stops)
            assertEquals(1, report.routes)
            assertEquals(2, report.patterns)
            val missing = ops.gtfsImport(GtfsImportRequest("/nope/feed.zip", nowMs = 1)) as Outcome.Err
            assertTrue(missing.error is RustError.NotFound)
        }

    @Test
    fun theSavedFeedAnswersTheStopsInAViewAndTheLineOfAPattern() =
        runBlocking {
            val fixtures = System.getProperty("rosty.contract")
            val zip = File(fixtures, "../tests/data/gtfs-tiny.zip").canonicalPath
            val dir =
                java.nio.file.Files
                    .createTempDirectory("gtfs-view")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            // Nothing imported yet: no feed, nothing to draw.
            val all = GtfsBbox(south = 40.9, west = 28.9, north = 41.1, east = 29.2)
            val before = ops.gtfsView(GtfsViewRequest(all, cap = 300, pattern = 0, dir = dir)).getOrOk()
            assertFalse(before.hasFeed)
            assertTrue(before.stops.isEmpty())
            assertNull(before.line)

            assertTrue(ops.gtfsImport(GtfsImportRequest(zip, nowMs = 1_000, save = true, dir = dir)).getOrOk().saved)
            val view = ops.gtfsView(GtfsViewRequest(all, cap = 300, pattern = 0, dir = dir)).getOrOk()
            assertTrue(view.hasFeed)
            assertEquals(listOf("A", "B", "C"), view.stops.map { it.id })
            assertEquals(listOf(0, 1, 2), view.stops.map { it.index })
            assertEquals("Moda İskele", view.stops[0].name)
            assertEquals(Coord(40.95, 29.09), view.stops[2].coord)
            assertEquals(3, view.total)
            assertFalse(view.capped)
            // The first pattern is the trip to Kadıköy: A then B.
            assertEquals(listOf(Coord(41.0001, 29.0), Coord(40.99, 29.02)), view.line)
            val second = ops.gtfsView(GtfsViewRequest(all, cap = 300, pattern = 1, dir = dir)).getOrOk()
            assertEquals(listOf(Coord(41.0001, 29.0), Coord(40.95, 29.09)), second.line)

            // A smaller box, a cap, and a pattern the feed does not have.
            val west = GtfsBbox(south = 40.98, west = 28.99, north = 41.01, east = 29.03)
            val two = ops.gtfsView(GtfsViewRequest(west, cap = 300, pattern = 9, dir = dir)).getOrOk()
            assertEquals(listOf("A", "B"), two.stops.map { it.id })
            assertNull(two.line)
            val capped = ops.gtfsView(GtfsViewRequest(all, cap = 1, dir = dir)).getOrOk()
            assertEquals(1, capped.stops.size)
            assertEquals(3, capped.total)
            assertTrue(capped.capped)
            // The stop nearest the middle of the box (41.0, 29.05) is B.
            assertEquals("B", capped.stops.single().id)
            // A box that reaches the feed the long way round, across the antimeridian.
            val around = GtfsBbox(south = 40.0, west = 29.05, north = 42.0, east = 29.01)
            assertEquals(
                listOf(
                    "A",
                    "C",
                ),
                ops.gtfsView(GtfsViewRequest(around, cap = 300, dir = dir)).getOrOk().stops.map {
                    it.id
                },
            )
        }

    @Test
    fun theSavedFeedListsTheLinesAtAStopAndItsSettingsRow() =
        runBlocking {
            val fixtures = System.getProperty("rosty.contract")
            val zip = File(fixtures, "../tests/data/gtfs-tiny.zip").canonicalPath
            val dir =
                java.nio.file.Files
                    .createTempDirectory("gtfs-lines")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            assertFalse(ops.gtfsLines(GtfsLinesRequest(stop = 1, dir = dir)).getOrOk().hasFeed)
            assertNull(ops.gtfsSummary(GtfsSummaryRequest(nowMs = 2_000, dir = dir)).getOrOk().summary)

            // Imported on 1 Jan 1970 at 00:00:01 UTC, with rows the import says it dropped.
            val report = ops.gtfsImport(GtfsImportRequest(zip, nowMs = 1_000, save = true, dir = dir)).getOrOk().report
            assertEquals(0L, report.skippedRows)

            // Kadıköy (B) is the second stop of the first trip, Bostancı (C) of the second: one stop earlier each.
            val kadikoy = ops.gtfsLines(GtfsLinesRequest(stopId = "B", dir = dir)).getOrOk()
            assertTrue(kadikoy.hasFeed)
            assertEquals("Kadıköy", kadikoy.stop!!.name)
            assertEquals(1, kadikoy.stop!!.index)
            assertEquals(20, kadikoy.maxLines)
            val line = kadikoy.lines.single()
            assertEquals(1, line.before)
            assertEquals("Kadıköy", line.headsign)
            assertEquals("Line ${line.name} to Kadıköy", line.title)
            assertEquals("1 stop earlier on this line", line.earlier)
            val bostanci =
                ops
                    .gtfsLines(GtfsLinesRequest(stop = 2, dir = dir))
                    .getOrOk()
                    .lines
                    .single()
            assertEquals("Bostancı", bostanci.headsign)
            // The two trips are two patterns of one route: each stop is offered its own.
            assertTrue(bostanci.pattern != line.pattern)
            assertEquals(line.name, bostanci.name)
            // Moda is the first stop of both: nothing before it, so no line is offered.
            val moda = ops.gtfsLines(GtfsLinesRequest(stopId = "A", dir = dir)).getOrOk()
            assertEquals("Moda İskele", moda.stop!!.name)
            assertTrue(moda.lines.isEmpty())
            // A stop the feed does not have, by id and by index.
            assertNull(ops.gtfsLines(GtfsLinesRequest(stopId = "Z", dir = dir)).getOrOk().stop)
            assertNull(ops.gtfsLines(GtfsLinesRequest(stop = 3, dir = dir)).getOrOk().stop)
            assertTrue(
                ops
                    .gtfsLines(GtfsLinesRequest(stop = 2, limit = 0, dir = dir))
                    .getOrOk()
                    .lines
                    .isEmpty(),
            )

            val day = 86_400_000L
            val row = ops.gtfsSummary(GtfsSummaryRequest(nowMs = 5 * day, dir = dir)).getOrOk()
            assertEquals("3 stops, 1 line", row.summary)
            assertEquals("Imported 1 Jan", row.imported)
            assertEquals(1_000L, row.importedMs)
            assertEquals(listOf(3, 1, 2), listOf(row.stops, row.routes, row.patterns))
            // Another year, a calendar west of UTC (still 31 Dec there), and rows the import dropped.
            val later = GtfsSummaryRequest(nowMs = 400 * day, tzOffsetS = -5 * 3_600, skippedRows = 1_200, dir = dir)
            assertEquals("Imported 31 Dec 1969, 1 200 unusable rows skipped", ops.gtfsSummary(later).getOrOk().imported)
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
