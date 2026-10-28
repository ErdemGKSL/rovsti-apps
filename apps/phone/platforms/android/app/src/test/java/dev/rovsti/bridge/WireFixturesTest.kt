package dev.rovsti.bridge

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the golden fixtures shared with Rust (`crates/rovsti_core/tests/fixtures`)
 * and checks that SnapshotJson / ProviderJson produce the same JSON (AD-10).
 * Comparison is on parsed objects (`similar`), not raw text.
 */
class WireFixturesTest {
    private val dir: File = run {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "crates/rovsti_core/tests/fixtures").isDirectory) d = d.parentFile
        File(requireNotNull(d) { "repo root not found" }, "crates/rovsti_core/tests/fixtures")
    }

    private fun fixture(name: String) = JSONObject(File(dir, name).readText())

    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject ->
            keys(a) == keys(b) && keys(a).all { same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray ->
            a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun assertSimilar(expected: JSONObject, actual: JSONObject) =
        assertTrue("expected $expected\n but was $actual", same(expected, actual))

    @Test fun snapshotsRoundTrip() {
        for (name in listOf("call_snapshot_active.json", "call_snapshot_waiting.json", "call_snapshot_empty.json")) {
            val expected = fixture(name)
            assertSimilar(expected, SnapshotJson.build(SnapshotJson.parse(expected)))
        }
    }

    @Test fun activeSnapshotBuiltByHand() {
        val data = SnapshotData(
            seq = 42, nowMs = 1790000100000, keyguardLocked = false,
            audio = AudioInfo(true, "speaker", listOf("earpiece", "speaker", "bluetooth")),
            foregroundCallId = 1,
            calls = listOf(
                CallInfo(
                    id = 1, state = "active", numberRaw = "+90 555 111 22 33",
                    normalized = ProviderJson.normalize("+90 555 111 22 33"),
                    displayName = "Ayşe Yılmaz", contactId = 17,
                    connectTimeMs = 1790000040000, canHold = true,
                ),
            ),
        )
        assertSimilar(fixture("call_snapshot_active.json"), SnapshotJson.build(data))
    }

    @Test fun waitingSnapshotFocus() {
        val snap = SnapshotJson.parse(fixture("call_snapshot_waiting.json"))
        val focus = Focus.of(snap.calls)
        assertEquals(snap.foregroundCallId, focus.foreground)
        assertEquals(snap.waitingCallId, focus.waiting)
    }

    @Test fun emptySnapshotKeepsKeyguardFlag() {
        val empty = SnapshotJson.empty(44, 1790000300000, true)
        val built = SnapshotJson.build(empty.copy(audio = AudioInfo(false, "earpiece", emptyList())))
        assertSimilar(fixture("call_snapshot_empty.json"), built)
    }

    @Test fun missingKeyguardMeansLocked() {
        assertTrue(SnapshotJson.parse(fixture("call_snapshot_no_keyguard.json")).keyguardLocked)
    }

    @Test fun unknownStatesSurviveParsing() {
        val snap = SnapshotJson.parse(fixture("call_snapshot_unknown_state.json"))
        assertTrue(snap.calls.isNotEmpty())
    }

    @Test fun focusPrefersActiveThenRinging() {
        fun c(id: Int, state: String) = CallInfo(id, state)
        assertEquals(Focus(2, 3), Focus.of(listOf(c(1, "holding"), c(2, "active"), c(3, "ringing"))))
        assertEquals(Focus(3, null), Focus.of(listOf(c(3, "ringing"))))
        assertEquals(Focus(4, null), Focus.of(listOf(c(4, "disconnected"))))
        assertEquals(Focus(null, null), Focus.of(emptyList()))
    }

    @Test fun callLogMatchesFixture() {
        val rows = listOf(
            ProviderJson.LogRow(104, "+90 555 111 22 33", 3, 1790000000000, 0, "Ayşe Yılmaz", "Mobile", 17),
            ProviderJson.LogRow(103, "05551112233", 1, 1789990000000, 61),
            ProviderJson.LogRow(102, "+4915123456789", 5, 1789980000000),
            ProviderJson.LogRow(101, "", 0, 1789970000000),
        )
        assertSimilar(fixture("call_log_ok.json"), JSONObject(ProviderJson.callLog(rows)))
    }

    @Test fun contactsMatchFixture() {
        val photo = "content://com.android.contacts/contacts/17/photo"
        val rows = listOf(
            ProviderJson.PhoneRow(17, "Ayşe Yılmaz", "+90 555 111 22 33", "Mobile", starred = true, photoUri = photo),
            ProviderJson.PhoneRow(17, "Ayşe Yılmaz", "0212 555 00 11", starred = true, photoUri = photo),
            ProviderJson.PhoneRow(18, "Özgür Kaya", "0532 123 45 67"),
        )
        assertSimilar(fixture("contacts_ok.json"), JSONObject(ProviderJson.contacts(rows)))
    }

    @Test fun favoritesMatchFixture() {
        val rows = listOf(ProviderJson.PhoneRow(17, "Ayşe Yılmaz", "+90 555 111 22 33", "Mobile", starred = true))
        assertSimilar(fixture("favorites_ok.json"), JSONObject(ProviderJson.contacts(rows)))
    }

    @Test fun permissionErrorMatchesFixture() {
        assertSimilar(fixture("call_log_permission.json"), JSONObject(ProviderJson.error("permission")))
    }

    @Test fun superPrimaryNumberComesFirstAndDuplicatesCollapse() {
        val rows = listOf(
            ProviderJson.PhoneRow(1, "A", "0555 111 22 33"),
            ProviderJson.PhoneRow(1, "A", "0212 555 00 11", superPrimary = true),
            ProviderJson.PhoneRow(1, "A", "05551112233"),
        )
        val numbers = JSONObject(ProviderJson.contacts(rows)).getJSONObject("data")
            .getJSONArray("contacts").getJSONObject(0).getJSONArray("numbers")
        assertEquals(2, numbers.length())
        assertEquals("0212 555 00 11", numbers.getJSONObject(0).getString("raw"))
        // Only the super-primary number carries the "default" flag (the Contact sheet's caption).
        assertEquals(true, numbers.getJSONObject(0).getBoolean("default"))
        assertEquals(false, numbers.getJSONObject(1).has("default"))
    }

    @Test fun mappings() {
        assertEquals("+905551112233", ProviderJson.normalize(" +90 (555) 111-22 33"))
        assertEquals("0", ProviderJson.normalize("0+"))
        assertEquals("06", ProviderJson.normalize("*#06#"))
        assertEquals("missed", ProviderJson.kindName(3))
        assertEquals("incoming", ProviderJson.kindName(7))
        assertEquals("unknown", ProviderJson.kindName(99))
        assertEquals("local", SnapshotJson.causeName(2))
        assertEquals("unknown", SnapshotJson.causeName(0))
        assertEquals("speaker", SnapshotJson.routeName(4))
        assertNull(SnapshotJson.routeName(5))
        assertFalse(SnapshotJson.toJson(SnapshotJson.empty(1, 1, false)).contains("foreground_call_id"))
    }
}
