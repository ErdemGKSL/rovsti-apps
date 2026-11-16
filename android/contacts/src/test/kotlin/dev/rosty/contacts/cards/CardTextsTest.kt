package dev.rosty.contacts.cards

import dev.rosty.core.ops.CardTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The words a card prints, in the app's locale; the date follows the locale too ("3 Ekim", not "October 3"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardTextsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun englishBirthday() {
        val texts = cardTexts(context, StudioSamples.birthday, CardTemplate.Birthday)
        assertEquals("Happy birthday, Ayşe Yılmaz!", texts["title"])
        assertEquals("Turning 36", texts["age_line"])
        assertEquals("October 3", texts["date_line"])
    }

    @Config(qualifiers = "tr")
    @Test
    fun turkishBirthdayPrintsTheDateInTurkish() {
        val texts = cardTexts(context, StudioSamples.birthday, CardTemplate.Birthday)
        assertEquals("Doğum günün kutlu olsun, Ayşe Yılmaz!", texts["title"])
        assertEquals("36 yaşına giriyorsun", texts["age_line"])
        assertEquals("3 Ekim", texts["date_line"])
    }

    @Test
    fun eventDatesReadAsMonthAndDay() {
        assertEquals(10 to 3, eventMonthDay("1990-10-03"))
        assertEquals(2 to 29, eventMonthDay("--02-29"))
        assertNull(eventMonthDay(""))
        assertNull(eventMonthDay("1990-13-03"))
        assertNull(eventMonthDay("03.10.1990"))
    }

    /** The owner's device report: a contact named with its number and no birthday opens on Contact. */
    @Config(qualifiers = "tr")
    @Test
    fun aNumberNamedContactWithoutABirthdayOpensOnContact() {
        val who = StudioSamples.numberNamed
        assertEquals(CardTemplate.Contact, who.defaultTemplate)
        assertTrue(CardTemplate.Birthday in who.templates)
        val texts = cardTexts(context, who, CardTemplate.Contact)
        assertEquals("+90 555 000 12 34", texts["title"])
        assertEquals("+90 555 000 12 34", texts["numbers"])
        assertNull(texts["date_line"])
    }

    /** The Birthday tab of a contact without a birthday: a greeting by name, no age line and no date pill. */
    @Test
    fun theBirthdayCardWithoutABirthdayHasNoDateAndNoAge() {
        val texts = cardTexts(context, StudioSamples.numberNamed, CardTemplate.Birthday)
        assertEquals("Happy birthday, +90 555 000 12 34!", texts["title"])
        assertFalse("age_line" in texts)
        assertFalse("date_line" in texts)
    }

    @Test
    fun cardDateFollowsTheLocale() {
        assertEquals("October 3", cardDate(context, 10, 3))
        assertNull(cardDate(context, null, 3))
        assertNull(cardDate(context, 2, 30))
    }

    @Config(qualifiers = "tr")
    @Test
    fun cardDateInTurkish() {
        assertEquals("3 Ekim", cardDate(context, 10, 3))
        assertEquals("29 Şubat", cardDate(context, 2, 29))
    }
}
