package dev.rosty.messages.schedule

import dev.rosty.core.model.messages.ScheduleTarget
import dev.rosty.core.model.messages.ScheduledSms
import dev.rosty.core.model.wa.ScheduledState
import dev.rosty.core.model.wa.ScheduledText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The pure rules of scheduled send, with the cases of the GPUI tests (`apps/messages/src/schedule_ui.rs`). */
class ScheduleRulesTest {
    /** 2026-11-10 15:00 UTC, a Tuesday. */
    private val now = Instant.parse("2026-11-10T15:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val labels = ScheduleLabels(ZoneId.of("UTC"), Locale.US, true, ScheduleDayWords("Today", "Tomorrow"))

    private fun sms(
        id: Long,
        atMs: Long,
        to: String = "+905550000001",
    ) = ScheduledSms(id, atMs, ScheduleTarget.Sms(to, null), "text $id", now)

    private fun remote(
        id: String,
        chat: String,
        atMs: Long,
        state: ScheduledState = ScheduledState.Pending,
    ) = ScheduledText(id, chat, "wa $id", atMs, now, state)

    @Test fun labelsNameTheDay() {
        assertEquals("Today 18:00", labels.whenLabel(now + 3 * hour, now))
        assertEquals("Tomorrow 09:00", labels.whenLabel(now + 18 * hour, now))
        assertEquals("12 Nov 09:00", labels.whenLabel(now + 42 * hour, now))
    }

    @Test fun labelsFollowTheLanguageZoneAndClock() {
        val tr =
            ScheduleLabels(
                ZoneId.of("Europe/Istanbul"),
                Locale.forLanguageTag("tr"),
                true,
                ScheduleDayWords("Bugün", "Yarın"),
            )
        // 15:00 UTC is 18:00 in Istanbul.
        assertEquals("Bugün 21:00", tr.whenLabel(now + 3 * hour, now))
        assertEquals("12 Kas 12:00", tr.whenLabel(now + 42 * hour, now))
        val twelve = ScheduleLabels(ZoneId.of("UTC"), Locale.US, false, ScheduleDayWords("Today", "Tomorrow"))
        assertEquals("Today 6:00 PM", twelve.whenLabel(now + 3 * hour, now))
    }

    @Test fun thePickStartsOnTheNextFreeQuarterHour() {
        // 15:00 sharp: the next quarter at least 15 minutes away is 15:15.
        assertEquals(CustomPick(0, 15 * 60 + 15), labels.initialPick(now))
        assertEquals(CustomPick(0, 15 * 60 + 30), labels.initialPick(now + 60_000))
        // 23:50: the pick rolls into tomorrow.
        val late = Instant.parse("2026-11-10T23:50:00Z").toEpochMilli()
        assertEquals(CustomPick(1, 15), labels.initialPick(late))
    }

    @Test fun pickStepsWrapAndClamp() {
        val pick = CustomPick(0, 18 * 60 + 30)
        assertEquals(CustomPick(0, 18 * 60 + 45), pick.stepMinutes(1))
        assertEquals(CustomPick(0, 23 * 60 + 45), CustomPick(0, 0).stepMinutes(-1))
        assertEquals(CustomPick(0, 0), CustomPick(0, 23 * 60 + 45).stepMinutes(1))
        assertEquals(CustomPick(0, 18 * 60 + 30), pick.stepDay(-1))
        assertEquals(CustomPick(CustomPick.MAX_DAY, 18 * 60 + 30), pick.stepDay(CustomPick.MAX_DAY + 5))
    }

    @Test fun aPickIsItsLocalTimeAndThePastIsNotSchedulable() {
        val pick = CustomPick(0, 18 * 60 + 30)
        assertEquals(Instant.parse("2026-11-10T18:30:00Z").toEpochMilli(), labels.atMs(pick, now))
        assertEquals("Tomorrow 18:30", labels.whenLabel(labels.atMs(pick.stepDay(1), now), now))
        assertTrue(labels.isSchedulable(pick, now))
        assertFalse(labels.isSchedulable(CustomPick(0, 15 * 60), now))
        assertFalse(labels.isSchedulable(CustomPick(0, 9 * 60), now))
    }

    @Test fun idsNeverCollideWithTheQueue() {
        assertEquals(now, ScheduleRules.nextId(emptyList(), now))
        assertEquals(now + 6, ScheduleRules.nextId(listOf(sms(now + 5, now)), now))
        assertEquals(now, ScheduleRules.nextId(listOf(sms(3, now)), now))
    }

    @Test fun theStripListsThisConversationsWaitingItemsSoonestFirst() {
        val local =
            listOf(
                sms(1, now + 2 * hour),
                sms(2, now + hour, to = "0555 000 00 01"),
                sms(3, now + hour, to = "+905550000009"),
            )
        val wa =
            listOf(
                remote("a", "ada@s.whatsapp.net", now + 3 * hour),
                remote("b", "ada@s.whatsapp.net", now + hour, ScheduledState.Failed("offline")),
                remote("c", "ada@s.whatsapp.net", now + hour, ScheduledState.Sent("m1")),
                remote("d", "deniz@s.whatsapp.net", now + hour),
            )

        val smsStrip = ScheduleRules.stripItems(ScheduleDest.Sms("+90 555 000 00 01", null), local, wa)
        assertEquals(listOf(StripKey.Local(2), StripKey.Local(1)), smsStrip.map { it.key })

        val waStrip = ScheduleRules.stripItems(ScheduleDest.WhatsApp("ada@s.whatsapp.net"), local, wa)
        assertEquals(listOf(StripKey.Gateway("b"), StripKey.Gateway("a")), waStrip.map { it.key })
        assertEquals(listOf(true, false), waStrip.map { it.failed })
    }

    @Test fun theStripCollapsesToAFewRows() {
        val items = (1..5L).map { StripItem(StripKey.Local(it), now + it * hour, "m$it") }
        assertEquals(ScheduleRules.STRIP_ROWS, ScheduleRules.visible(items, expanded = false).size)
        assertEquals(5, ScheduleRules.visible(items, expanded = true).size)
        val words = EnglishScheduleWords.words
        val ui = ScheduledStripUi.of(items, expanded = false, now, words, labels)
        assertEquals(2, ui.hidden)
        assertEquals("Today 16:00 · m1", ui.rows.first().line)
        assertEquals("Cancel the message scheduled for Today 16:00", ui.rows.first().cancelLabel)
        val failed =
            ScheduledStripUi.of(
                listOf(StripItem(StripKey.Gateway("x"), now, "hello", failed = true)),
                false,
                now,
                words,
                labels,
            )
        assertEquals("Not sent · hello", failed.rows.single().line)
    }

    @Test fun previewCollapsesAndCuts() {
        assertEquals("a b c", ScheduleRules.preview("a\n b\t\tc "))
        val long = "x".repeat(80)
        assertEquals("x".repeat(60) + "…", ScheduleRules.preview(long))
    }

    @Test fun cancelNeverOverwritesTheComposer() {
        assertEquals("see you", ScheduleRules.textAfterCancel("  ", "see you"))
        assertNull(ScheduleRules.textAfterCancel("typing", "see you"))
    }

    @Test fun newlyFailedOnlyOnTheTransition() {
        val pending = remote("a", "c", now)
        val failed = remote("a", "c", now, ScheduledState.Failed("x"))
        assertTrue(ScheduleRules.newlyFailed(pending, failed))
        assertTrue(ScheduleRules.newlyFailed(null, failed))
        assertFalse(ScheduleRules.newlyFailed(failed, failed))
        assertFalse(ScheduleRules.newlyFailed(null, pending))
        assertEquals(listOf(failed), ScheduleRules.upsert(listOf(pending), failed))
        assertEquals(2, ScheduleRules.upsert(listOf(pending), remote("b", "c", now)).size)
    }

    @Test fun firedListParsesAndSurvivesJunk() {
        val fired = ScheduleCenter.parseFired("""[{"id":5,"ok":true,"at_ms":9},{"id":6,"ok":false,"at_ms":10,"x":1}]""")
        assertEquals(listOf(5L to true, 6L to false), fired.map { it.id to it.ok })
        assertTrue(ScheduleCenter.parseFired("not json").isEmpty())
        assertTrue(ScheduleCenter.parseFired("{}").isEmpty())
    }
}
