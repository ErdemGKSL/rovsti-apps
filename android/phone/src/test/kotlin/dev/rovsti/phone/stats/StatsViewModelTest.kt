package dev.rovsti.phone.stats

import dev.rovsti.core.model.phone.StatRange
import dev.rovsti.phone.HostRust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.10 decisions on the REAL Rust core with a call-log fixture (Tue 14 Nov 2023, UTC). */
class StatsViewModelTest {
    private val host by lazy { HostRust("rovsti-stats-vm") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun mainDispatcher() =
        dev.rovsti.phone.UnconfinedMain
            .install()

    @After fun stop() = scope.cancel()

    private suspend fun rig(log: List<dev.rovsti.core.model.phone.CallLogEntry> = statsLog()) =
        StatsRig(host, scope, log).also { it.ready() }

    @Test fun thisWeekCountsSinceMondayAndDrawsSevenDays() =
        runBlocking {
            val ui = rig().model.loaded()
            assertEquals(StatRange.Week, ui.range)
            val cells = ui.cells!!
            assertEquals("Mon and Tue only", 5, cells.calls)
            assertEquals(2, cells.incoming)
            assertEquals(2, cells.outgoing)
            assertEquals(1, cells.missed)
            assertTrue("the core worded the talk time", cells.talk.isNotBlank())
            assertEquals(7, ui.bars.size)
            assertFalse(ui.weekly)
            assertEquals("Tue", ui.bars.last().label)
            assertEquals("Tuesday", ui.bars.last().name)
            assertEquals("today holds the most calls", 3, ui.bars.last().calls)
            assertEquals("the tallest bar is 1", 1f, ui.bars.last().fraction, 0f)
            assertEquals("Monday has two of three", 2f / 3f, ui.bars[5].fraction, 0.001f)
            assertEquals(0f, ui.bars.first().fraction, 0f)
            assertEquals(6, ui.bars.last().index)
        }

    @Test fun theRangesChangeTheTotalsAndTheBars() =
        runBlocking {
            val model = rig().model
            model.loaded()
            model.onAction(StatsAction.SelectRange(1))
            val days = model.loaded { it.range == StatRange.Days7 && (it.cells?.calls ?: 0) > 0 }
            assertEquals("Sunday joins", 6, days.cells!!.calls)
            assertEquals(7, days.bars.size)
            assertEquals(1, days.bars.count { it.calls == 1 })
            model.onAction(StatsAction.SelectRange(2))
            val weeks = model.loaded { it.range == StatRange.Weeks8 && it.bars.size == 8 }
            assertEquals("three weeks ago joins", 7, weeks.cells!!.calls)
            assertTrue(weeks.weekly)
            assertNull("a week bar has no weekday name", weeks.bars.first().name)
            assertEquals("this week is last", 5, weeks.bars.last().calls)
            assertEquals("Sunday's call and Deniz's", 2, weeks.bars.count { it.calls == 1 && it.fraction < 1f })
            assertEquals(2, weeks.rangeIndex)
        }

    @Test fun recordsAreTheBusiestDayAndTheLongestCall() =
        runBlocking {
            val model = rig().model
            model.onAction(StatsAction.SelectRange(1))
            val ui = model.loaded { it.range == StatRange.Days7 && it.records.longest != null }
            val busiest = ui.records.busiest!!
            assertTrue(
                "the core titled the day: ${busiest.title}",
                busiest.title.contains("Tue") && busiest.title.contains("14 Nov"),
            )
            assertTrue(busiest.detail.startsWith("3 calls"))
            val longest = ui.records.longest!!
            assertTrue("30:00 of the stranger on Sunday: ${longest.detail}", longest.detail.contains("30:00"))
            assertTrue(longest.title.isNotBlank())
        }

    @Test fun thisWeeksLongestCallIsAyses() =
        runBlocking {
            val ui = rig().model.loaded { it.records.longest != null }
            assertTrue(
                ui.records.longest!!
                    .title
                    .contains("Ayse"),
            )
            assertTrue(
                ui.records.longest!!
                    .detail
                    .contains("10:00"),
            )
        }

    @Test fun theTopPeopleAreRankedByTalkTimeWithTheCoresWords() =
        runBlocking {
            val ui = rig().model.loaded()
            assertEquals(listOf("Ayse Yilmaz", "Mert Kaya"), ui.people.map { it.title })
            assertEquals(3, ui.people.first().calls)
            assertTrue(
                ui.people
                    .first()
                    .summary
                    .startsWith("3 calls"),
            )
            assertEquals(1, ui.people.last().missed)
        }

    @Test fun tappingABarOpensItsBucketAndMarksItUntilItCloses() =
        runBlocking {
            val model = rig().model
            model.loaded()
            model.onAction(StatsAction.TapBar(6))
            val bucket = model.nextEffect<StatsEffect.OpenBucket>().bucket
            assertTrue(bucket.title.contains("14 Nov"))
            assertTrue(bucket.summary.startsWith("3 calls"))
            assertEquals(3, bucket.lines.size)
            assertEquals("newest first", "+905551112233", bucket.lines.first().number)
            model.loaded { it.selected == 6 }
            model.onAction(StatsAction.BucketClosed)
            assertNull(model.loaded { it.selected == null }.selected)
        }

    @Test fun aWeekBarOpensTheCallsOfThatWeek() =
        runBlocking {
            val model = rig().model
            model.onAction(StatsAction.SelectRange(2))
            model.loaded { it.range == StatRange.Weeks8 && it.bars.size == 8 && (it.cells?.calls ?: 0) > 0 }
            model.onAction(StatsAction.TapBar(7))
            val bucket = model.nextEffect<StatsEffect.OpenBucket>().bucket
            assertTrue("the core titled it: ${bucket.title}", bucket.title.startsWith("Week of"))
            assertEquals(5, bucket.lines.size)
        }

    @Test fun aPersonRowOpensTheNumberSheetAndTheExportsCarryTheCoresText() =
        runBlocking {
            val model = rig().model
            val ui = model.loaded()
            model.onAction(StatsAction.OpenPerson(ui.people.first().key))
            val open = model.nextEffect<StatsEffect.OpenNumber>()
            assertEquals("Ayse Yilmaz", open.name)
            model.onAction(StatsAction.ExportCsv)
            val csv = model.nextEffect<StatsEffect.SaveCsv>()
            assertTrue(csv.fileName.startsWith("rovsti-call-stats-this-week-2023-11-14"))
            assertTrue(csv.csv.startsWith("name,number,calls"))
            assertTrue(csv.csv.contains("Ayse Yilmaz"))
            model.onAction(StatsAction.Share)
            val text = model.nextEffect<StatsEffect.ShareText>().text
            assertTrue(text.startsWith("Call statistics, this week"))
        }

    @Test fun noCallsIsTheEmptyState() =
        runBlocking {
            val model = rig(emptyList()).model
            val ui = model.loaded { it.ready }
            assertTrue(ui.empty)
            assertNull(ui.records.busiest)
            assertNull(ui.records.longest)
            assertNotNull(ui.cells)
            assertTrue(ui.people.isEmpty())
        }
}
