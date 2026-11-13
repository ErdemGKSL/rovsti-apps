package dev.rovsti.design.text

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** F36: numbers and durations keep their glyph order inside a right-to-left layout. */
class LtrIsolateTest : ShotTest() {
    private val phone = "+49 30 123456"

    @Test
    fun isolationWrapsInLriAndPdi() {
        assertEquals("⁦$phone⁩", phone.ltrIsolated())
        assertEquals("", "".ltrIsolated())
    }

    @Test
    fun numberRunsInsideArabicTextAreIsolatedAndTheRestIsLeftAlone() {
        val arabic = "محمد $phone"
        assertEquals("محمد ⁦$phone⁩", arabic.isolateNumbers())
        val noDigits = "محمد"
        assertSame(noDigits, noDigits.isolateNumbers())
    }

    @Test
    fun rtlLettersAreDetected() {
        assertTrue(LtrIsolate.hasRtl("محمد"))
        assertTrue(LtrIsolate.hasRtl("שלום"))
        assertFalse(LtrIsolate.hasRtl("1 h 52 min"))
        assertFalse(LtrIsolate.hasRtl(phone))
    }

    @Test
    fun anLtrParagraphIsForcedOnlyForPlainTextInAnRtlLayout() {
        val base = TextStyle()
        assertEquals(TextDirection.Ltr, ltrStyleFor(base, phone, LayoutDirection.Rtl).textDirection)
        assertEquals(TextDirection.Unspecified, ltrStyleFor(base, phone, LayoutDirection.Ltr).textDirection)
        assertEquals(TextDirection.Unspecified, ltrStyleFor(base, "محمد", LayoutDirection.Rtl).textDirection)
        val own = base.copy(textDirection = TextDirection.Rtl)
        assertEquals(TextDirection.Rtl, ltrStyleFor(own, phone, LayoutDirection.Rtl).textDirection)
    }

    @Test
    fun rtlSafeIsolatesWholeInRtlAndIsTheTextItselfInLtr() {
        composeFrame(rtl = true) {
            RovstiText("1 h 52 min".rtlSafe(), Modifier.testTag("t"))
        }
        compose.onNodeWithTag("t").assertTextEquals("⁦1 h 52 min⁩")
        composeFrame(rtl = false) {
            RovstiText("1 h 52 min".rtlSafe(), Modifier.testTag("t"))
        }
        compose.onNodeWithTag("t").assertTextEquals("1 h 52 min")
    }
}
