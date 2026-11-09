package dev.rovsti.platform.system

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPagesTest {
    @Test fun everyPageHasAnActionAndEndsInAppDetails() {
        assertEquals(10, SystemPages.ACTIONS.size)
        for (page in SystemPages.ACTIONS.keys) {
            val chain = SystemPages.chain(page)
            assertEquals(SystemPages.ACTION_DETAILS, chain.last())
        }
        assertEquals(listOf(SystemPages.ACTION_DETAILS), SystemPages.chain("app_details"))
        assertTrue(SystemPages.chain("nonsense").isEmpty())
    }

    @Test fun literalActionsAreExact() {
        assertEquals("android.settings.HEARING_DEVICES_SETTINGS", SystemPages.ACTIONS["hearing_devices"])
        assertEquals("android.telephony.action.EMERGENCY_ASSISTANCE", SystemPages.ACTIONS["emergency_info"])
        assertEquals("android.settings.BLUETOOTH_SETTINGS", SystemPages.ACTIONS["bluetooth"])
    }

    @Test fun theFirstActionWinsWithoutFallback() {
        val tried = ArrayList<String>()
        val out =
            JSONObject(
                SystemPages.open("bluetooth") {
                    tried.add(it)
                    true
                },
            )
        assertEquals(listOf("android.settings.BLUETOOTH_SETTINGS"), tried)
        assertTrue(out.getBoolean("ok"))
        assertFalse(out.getJSONObject("data").getBoolean("fallback"))
    }

    @Test fun aMissingActivityFallsBackToAppDetailsAndSaysSo() {
        val out = JSONObject(SystemPages.open("hearing_devices") { it == SystemPages.ACTION_DETAILS })
        assertTrue(out.getBoolean("ok"))
        assertTrue(out.getJSONObject("data").getBoolean("fallback"))
    }

    @Test fun nothingOpeningIsAFailureEnvelope() {
        val out = JSONObject(SystemPages.open("sound") { false })
        assertFalse(out.getBoolean("ok"))
        assertEquals("unavailable", out.getString("error"))
        assertFalse(JSONObject(SystemPages.open("nonsense") { true }).getBoolean("ok"))
    }
}
