package dev.rosty.stops.pick

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which typed text gets a reason when it is no location: links and coordinates, never a name. */
class PickRulesTest {
    @Test fun linksAndCoordinatesLookLikeLinks() {
        for (text in listOf(
            "geo:41.0,29.0",
            "  GEO:1,2",
            "https://maps.example/place",
            "41.0082 28.97",
            "-33.9, 18.4",
        )) {
            assertTrue(text, PickRules.looksLikeLink(text))
            assertTrue(text, PickRules.Core.showRefusal(text))
        }
    }

    @Test fun namesDoNot() {
        for (text in listOf("", "   ", "Pier East", "market 12", "N 41 E 29")) {
            assertFalse(text, PickRules.looksLikeLink(text))
            assertFalse(text, PickRules.Core.showRefusal(text))
        }
    }
}
