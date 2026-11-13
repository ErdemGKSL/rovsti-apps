package dev.rovsti.platform.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneNumbersTest {
    @Test fun theRegionIsTheSimThenTheNetworkThenTheLocaleThenTurkey() {
        assertEquals("TR", PhoneNumbers.regionOf("tr", "de", "US"))
        assertEquals("DE", PhoneNumbers.regionOf("", "de", "US"))
        assertEquals("US", PhoneNumbers.regionOf(null, " ", "US"))
        assertEquals("TR", PhoneNumbers.regionOf(null, null, ""))
        assertEquals("TR", PhoneNumbers.regionOf("x", "123", null))
    }

    @Test fun everyTurkishFormIsOneE164() {
        for (raw in listOf(
            "+905321234567",
            "05321234567",
            "5321234567",
            "0090 532 123 45 67",
            "+90 (532) 123-45-67",
            "0 (532) 123 45 67",
        )) {
            assertEquals(raw, "+905321234567", PhoneNumbers.e164(raw, "TR"))
        }
        assertEquals("+905321234567", PhoneNumbers.e164("+90 532 123 45 67 ext. 12", "TR"))
        assertEquals("+15551234567".length, PhoneNumbers.e164("+1 650 253 0000", "TR")?.length)
    }

    @Test fun shortCodesAndWithheldNumbersHaveNoE164() {
        for (raw in listOf("112", "4440123", "*#06#", "", "-1", "-2", "-3", "Private", "Unknown")) {
            assertNull(raw, PhoneNumbers.e164(raw, "TR"))
        }
    }

    @Test fun withheldIsTheCoresRule() {
        for (raw in listOf("", " ", "-1", "-2", "-3", "UNKNOWN", "private", "Anonymous", "Payphone")) {
            assertTrue(raw, PhoneNumbers.isWithheld(raw))
        }
        for (raw in listOf("112", "+905321234567", "-12")) assertFalse(raw, PhoneNumbers.isWithheld(raw))
    }

    @Test fun extensionsAreCutLikeTheCore() {
        assertEquals("+90 212 555 00 00", PhoneNumbers.withoutExtension("+90 212 555 00 00 ext. 12"))
        assertEquals("02125550000", PhoneNumbers.withoutExtension("02125550000;ext=12"))
        assertEquals("02125550000", PhoneNumbers.withoutExtension("02125550000,,12"))
        assertEquals("+902125550000", PhoneNumbers.withoutExtension("+902125550000x12"))
        assertEquals("*#06#", PhoneNumbers.withoutExtension("*#06#"))
    }
}
