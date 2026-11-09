package dev.rovsti.contacts.favorites

import dev.rovsti.core.model.contacts.EventKind
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.6 (C9, C50): the Upcoming section and the See All page from `contacts.dates`, Wish as an SMS greeting from
 * `contacts.event_line`, and the .ics request of Add to Calendar - on the real Rust core. The clock is 16 Sep 2026.
 */
class UpcomingTest {
    private var rig: FavRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig() = FavRig().also { rig = it }

    private fun FavRig.upcoming() = stateWhere { it.upcoming.isNotEmpty() }

    @Test fun theSectionShowsTheNextDatesWithWhenAndAge() {
        val r = rig()
        val state = r.upcoming()
        val ids = state.upcoming.map { it.contactId }
        assertEquals(
            "Burak (in 2 days), then Ayşe (in 17 days); Elif's anniversary (65 days) is for See All",
            listOf(2L, 1L),
            ids,
        )
        val burak = state.upcoming.first()
        assertEquals("Burak Demir", burak.name)
        assertEquals(EventKind.Birthday, burak.kind)
        assertTrue(burak.sectionLine, burak.sectionLine.startsWith("In 2 days"))
        assertTrue("the age it reaches: ${burak.sectionLine}", burak.sectionLine.contains("30"))
        assertFalse("the section line has no date", burak.sectionLine.contains("Sep"))
        assertTrue("the page line has the date: ${burak.pageLine}", burak.pageLine.contains("18"))
    }

    @Test fun seeAllCountsEveryDateOfTheNextYearAndGroupsThem() {
        val r = rig()
        val state = r.upcoming()
        assertEquals("four dates in the year", 4, state.upcomingTotal)
        val titles = state.upcomingGroups.map { it.title }
        assertTrue(titles.toString(), titles.first().contains("week", ignoreCase = true))
        assertEquals(4, state.upcomingGroups.sumOf { it.items.size })
    }

    @Test fun wishOpensTheComposerWithTheGreeting() {
        val r = rig()
        r.upcoming()
        r.model.onAction(FavoritesAction.Wish(2, EventKind.Birthday, "Burak Demir"))
        val sms = r.wait { r.model.effects.first() } as FavoritesEffect.Sms
        assertEquals("0532 123 45 67", sms.number)
        assertEquals("Happy 30th birthday, Burak!", sms.body)
    }

    @Test fun wishFromThePageAnswersOnThePageStream() {
        val r = rig()
        r.upcoming()
        r.model.onAction(FavoritesAction.Wish(3, EventKind.Anniversary, "Elif Kaya", onPage = true))
        val sms = r.wait { r.model.pageEffects.first() } as FavoritesEffect.Sms
        assertEquals("+90 212 555 00 11", sms.number)
        assertEquals("Happy 11th anniversary, Elif!", sms.body)
    }

    @Test fun addToCalendarBuildsOneEventPerDate() {
        val r = rig()
        r.upcoming()
        r.model.onAction(FavoritesAction.SaveCalendar)
        val file = r.wait { r.model.pageEffects.first() } as FavoritesEffect.SaveCalendar
        assertEquals(4, file.count)
        assertTrue(file.fileName, file.fileName.endsWith(".ics"))
        assertTrue(file.text.startsWith("BEGIN:VCALENDAR"))
        assertEquals(4, Regex("BEGIN:VEVENT").findAll(file.text).count())
        assertTrue("Burak's birthday is in it: ${file.text}", file.text.contains("Burak"))
        assertTrue(file.text.contains("19960918"))
        assertTrue(file.text.contains("RRULE:FREQ=YEARLY"))
    }

    @Test fun addToCalendarWithoutDatesSaysSo() {
        val r = FavRig(calls = emptyList()).also { rig = it }
        r.wait {
            for (id in 1L..4L) {
                r.fake.save(
                    r.fake
                        .full()
                        .first()
                        .first { it.id == id }
                        .copy(events = emptyList()),
                )
            }
        }
        r.stateWhere { it.upcoming.isEmpty() && it.phase == FavoritesPhase.Ready && it.pickables.isNotEmpty() }
        r.model.onAction(FavoritesAction.SaveCalendar)
        val toast = r.wait { r.model.pageEffects.first() } as FavoritesEffect.Toast
        assertEquals("No birthdays or anniversaries to save.", toast.text)
    }
}
