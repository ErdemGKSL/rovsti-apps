package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.Coord
import dev.rosty.core.model.stops.CoordFormatRequest
import dev.rosty.core.model.stops.CrossingValidateRequest
import dev.rosty.core.model.stops.DestParseRequest
import dev.rosty.core.model.stops.DestShareRequest
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.Endpoint
import dev.rosty.core.model.stops.FixIn
import dev.rosty.core.model.stops.GtfsBbox
import dev.rosty.core.model.stops.GtfsImportRequest
import dev.rosty.core.model.stops.GtfsLinesRequest
import dev.rosty.core.model.stops.GtfsSummaryRequest
import dev.rosty.core.model.stops.GtfsViewRequest
import dev.rosty.core.model.stops.NoticeChatInput
import dev.rosty.core.model.stops.NoticeChatsRequest
import dev.rosty.core.model.stops.PickEvent
import dev.rosty.core.model.stops.PickReplayRequest
import dev.rosty.core.model.stops.PlacesExportRequest
import dev.rosty.core.model.stops.PlacesImportRequest
import dev.rosty.core.model.stops.ProjectRequest
import dev.rosty.core.model.stops.SavedEdit
import dev.rosty.core.model.stops.SavedEditRequest
import dev.rosty.core.model.stops.SavedStops
import dev.rosty.core.model.stops.Side
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.TripFixRequest
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

    @Test
    fun theSentencesFollowTheLocaleTheOpsWereMadeWith() =
        runBlocking {
            var tag: String? = "tr-TR"
            val localized = NativeStopsOps(TestRust.core) { tag }
            val far =
                dev.rosty.core.model.stops
                    .FormatDistanceRequest(1_240.0)
            assertEquals("1,2 km", localized.formatDistance(far).getOrOk().text)
            // A request that names its own language keeps it; no locale at all is English.
            assertEquals("1.2 km", localized.formatDistance(far.copy(locale = "en")).getOrOk().text)
            assertEquals("1.2 km", ops.formatDistance(far).getOrOk().text)
            // The choices have no request of their own: the provider is their language.
            val choices = localized.prefsChoices(dev.rosty.core.model.core.Empty).getOrOk()
            assertEquals(listOf("Otomatik", "Açık", "Koyu"), choices.appearance.map { it.label })
            assertEquals("Yaklaşık {min} dk sonra varıyorum: {stop}.", choices.defaultTemplate)
            assertEquals(
                "Auto",
                ops
                    .prefsChoices(dev.rosty.core.model.core.Empty)
                    .getOrOk()
                    .appearance[0]
                    .label,
            )
            // An error is a sentence too.
            val tooClose =
                dev.rosty.core.model.stops
                    .CrossingValidateRequest(Coord(41.0, 29.0), Coord(41.0, 29.0001))
            val refused = localized.crossingValidate(tooClose) as Outcome.Err
            assertEquals("A ile B arasında en az 20 m olmalı.", refused.error.message)

            // A trip started in Turkish writes its status in Turkish, also for ops that name no language ...
            val trip = ActiveTripHandle.start(9, home, work, 1_000, ops = localized).getOrOk()
            trip.use {
                val out = ops.tripFix(TripFixRequest(it.id, fix(41.2, 29.4, 2_000))).getOrOk().out
                assertTrue(out.text, out.text.startsWith("Çemberin dışında, "))
                // ... and follows the phone when its language changes during the trip.
                tag = "en-GB"
                val english = it.fix(fix(41.2, 29.4, 3_000)).getOrOk().out
                assertTrue(english.text, english.text.startsWith("Outside the circle, "))
                tag = null
                val kept = it.fix(fix(41.2, 29.4, 4_000)).getOrOk().out
                assertTrue(kept.text, kept.text.startsWith("Outside the circle, "))
            }
        }

    @Test
    fun theArrivingSoonChatsAreNamedByContactThenWhatsAppThenNumber() =
        runBlocking {
            val chats =
                listOf(
                    NoticeChatInput("905550001122@s.whatsapp.net", contact = "Ayşe Demir", push = "ayse"),
                    NoticeChatInput("905550003344@s.whatsapp.net", verified = "Corner Bakery", push = "shop"),
                    NoticeChatInput("905550005566@s.whatsapp.net"),
                    NoticeChatInput("120363000000000001@g.us", group = true, chatName = "Family"),
                    NoticeChatInput("98765432101234@lid"),
                )
            val named = ops.noticeChats(NoticeChatsRequest(chats)).getOrOk().chats
            assertEquals(
                listOf("Ayşe Demir", "Corner Bakery", "+905550005566", "Family", "WhatsApp user"),
                named.map { it.name },
            )
            assertEquals(listOf("contact", "verified", "number", "stored_chat", "unknown"), named.map { it.tier })
            // The number is a second line only under a name; a group and a hidden id have none.
            assertEquals(listOf("+905550001122", "+905550003344", null, null, null), named.map { it.number })
            val turkish = NativeStopsOps(TestRust.core) { "tr" }.noticeChats(NoticeChatsRequest(chats)).getOrOk()
            assertEquals("WhatsApp kullanıcısı", turkish.chats.last().name)
        }

    @Test
    fun aRefusedStartIsClearedWithoutAHistoryRecord() =
        runBlocking {
            val dir =
                kotlin.io.path
                    .createTempDirectory("rosty-clear")
                    .toFile()
            val request =
                dev.rosty.core.model.phone
                    .DirRequest(dir.path)
            try {
                // Nothing to clear yet: not an error.
                assertFalse(ops.storeTripClear(request).getOrOk().removed)
                open(dir.path).use {
                    assertNotNull(ops.storeTripLoad(request).getOrOk().trip)
                    assertTrue(ops.storeTripClear(request).getOrOk().removed)
                    assertNull(ops.storeTripLoad(request).getOrOk().trip)
                    assertFalse(ops.storeTripClear(request).getOrOk().removed)
                    // Nothing was recorded: ending now finds no trip either.
                    val ended =
                        ops.storeTripEnd(
                            dev.rosty.core.model.stops
                                .StoreTripEndRequest("cancelled", 99_000, dir.path),
                        )
                    assertNull(ended.getOrOk().record)
                }
            } finally {
                dir.deleteRecursively()
            }
        }

    @Test
    fun theStatusOfAMovingTripCarriesItsEtaMinutes() =
        runBlocking {
            open().use { trip ->
                // 8 m/s towards the line, 330 m away: under a minute, said as one.
                val answer = trip.fix(fix(41.003, 28.999, 10_000)).getOrOk()
                assertEquals(1, answer.out.etaMin)
                assertEquals(answer.etaMin, answer.out.etaMin)
                assertTrue(answer.out.text, answer.out.text.endsWith("about 1 min"))
                val clock =
                    ops
                        .arrivalClock(
                            dev.rosty.core.model.stops
                                .ArrivalClockRequest(nowMs = 0, etaMin = answer.out.etaMin!!, tzOffsetS = 0),
                        ).getOrOk()
                assertEquals("00:01", clock.clock)
            }
        }

    @Test
    fun aSavedStopKeepsItsNoteThroughEditShareExportAndImport() =
        runBlocking {
            var saved = SavedStops(version = 2, nextId = 1, stops = emptyList())
            val add = SavedEdit.Add("Pier", home, work)
            saved = ops.savedEdit(SavedEditRequest(saved, add, nowMs = 5)).getOrOk().saved
            val id = saved.stops.single().id
            assertEquals("", saved.stops.single().note)

            val noted = SavedEdit.SetNote(id, "  Back door,\n platform 2 ")
            saved = ops.savedEdit(SavedEditRequest(saved, noted)).getOrOk().saved
            assertEquals("Back door, platform 2", saved.stops.single().note)

            val tooLong = ops.savedEdit(SavedEditRequest(saved, SavedEdit.SetNote(id, "x".repeat(201))))
            assertEquals("note_too_long", (tooLong as Outcome.Err).error.reason)
            val missing = ops.savedEdit(SavedEditRequest(saved, SavedEdit.SetNote(99, "x")))
            assertEquals("not_found", (missing as Outcome.Err).error.reason)

            // The note is the last line of the stop's share text.
            val share =
                ops
                    .destShare(
                        DestShareRequest(
                            at = home.coord,
                            b = work.coord,
                            name = "Pier",
                            note = saved.stops.single().note,
                        ),
                    ).getOrOk()
            assertTrue(share.stopText!!.endsWith("\nBack door, platform 2"))
            // It survives the JSON export and a fresh import.
            val exported = ops.placesExport(PlacesExportRequest(saved, "json", "2026-11-15")).getOrOk()
            val empty = SavedStops(version = 2, nextId = 1, stops = emptyList())
            val imported =
                ops
                    .placesImport(
                        PlacesImportRequest(empty, exported.text, nowMs = 9, apply = true),
                    ).getOrOk()
            assertEquals(
                "Back door, platform 2",
                imported.saved!!
                    .stops
                    .single()
                    .note,
            )

            // A blank note clears it.
            saved = ops.savedEdit(SavedEditRequest(saved, SavedEdit.SetNote(id, "  "))).getOrOk().saved
            assertEquals("", saved.stops.single().note)
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
