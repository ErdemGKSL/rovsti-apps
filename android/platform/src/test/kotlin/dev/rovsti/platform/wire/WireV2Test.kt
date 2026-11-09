package dev.rovsti.platform.wire

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The v0.2 goldens (stories 14.0b, 14.0c): Kotlin rebuilds the same JSON from structured inputs. */
class WireV2Test {
    private val dir: File =
        run {
            var d: File? = File(System.getProperty("user.dir")).absoluteFile
            while (d != null && !File(d, "crates/rovsti_core/tests/fixtures").isDirectory) d = d.parentFile
            File(requireNotNull(d) { "repo root not found" }, "crates/rovsti_core/tests/fixtures")
        }

    private fun fixture(name: String) = JSONObject(File(dir, name).readText())

    private fun same(
        a: Any?,
        b: Any?,
    ): Boolean =
        when {
            a is JSONObject && b is JSONObject -> {
                a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
                    a.keys().asSequence().all { same(a.get(it), b.get(it)) }
            }

            a is JSONArray && b is JSONArray -> {
                a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
            }

            a is Number && b is Number -> {
                a.toDouble() == b.toDouble()
            }

            else -> {
                a == b
            }
        }

    private fun assertSimilar(
        expected: JSONObject,
        actual: JSONObject,
    ) = assertTrue("expected $expected\n but was $actual", same(expected, actual))

    private val all =
        listOf("audio_endpoints.json", "two_calls.json", "conference.json", "selecting_account.json")

    @Test fun v2FixturesRoundTripThroughTheModel() {
        for (name in all) {
            val expected = fixture(name)
            assertSimilar(expected, SnapshotJson.buildSnapshot(SnapshotJson.parse(expected)))
        }
    }

    @Test fun audioEndpointsBuiltFromStructuredInput() {
        fun ep(n: Int) = "7c1d0c3e-0000-4000-8000-00000000000$n"
        val endpoints =
            listOf(
                EndpointInfo(ep(1), "earpiece"),
                EndpointInfo(ep(2), "bluetooth", "Galaxy Buds", selected = true),
                EndpointInfo(ep(3), "bluetooth", "Phonak Audeo", hint = "hearing_aid"),
                EndpointInfo(ep(4), "wired_headset", "USB-C headset"),
                EndpointInfo(ep(5), "speaker"),
            )
        val data =
            SnapshotData(
                seq = 50,
                nowMs = 1790000400000,
                keyguardLocked = false,
                audio = AudioInfo.derived(false, endpoints),
                foregroundCallId = 1,
                calls =
                    listOf(
                        CallInfo(
                            id = 1,
                            state = "active",
                            numberRaw = "05551112233",
                            normalized = "05551112233",
                            connectTimeMs = 1790000340000,
                            canHold = true,
                            caps =
                                Wire.Caps.HOLD or Wire.Caps.SUPPORT_HOLD or
                                    Wire.Caps.MUTE or Wire.Caps.ADD_PARTICIPANT,
                        ),
                    ),
            )
        assertSimilar(fixture("audio_endpoints.json"), SnapshotJson.buildSnapshot(data))
        assertEquals(listOf("earpiece", "bluetooth", "wired_headset", "speaker"), data.audio.routes)
        assertEquals("bluetooth", data.audio.route)
    }

    @Test fun nothingSelectedFallsBackToTheGivenRoute() {
        val endpoints = listOf(EndpointInfo("a", "earpiece"), EndpointInfo("b", "speaker"))
        val audio = AudioInfo.derived(true, endpoints, "speaker")
        assertEquals("speaker", audio.route)
        assertEquals(listOf("earpiece", "speaker"), audio.routes)
    }

    @Test fun selectingAccountBuiltByHand() {
        val data =
            SnapshotData(
                seq = 80,
                nowMs = 1790000700000,
                keyguardLocked = false,
                audio = AudioInfo(false, "earpiece", listOf("earpiece", "speaker")),
                foregroundCallId = 1,
                endedCallId = 9,
                pendingFailed = "merge",
                ringerSilenced = true,
                silencedBy = "volume",
                selectingAccount = true,
                accounts =
                    listOf(
                        AccountRef("com.android.phone/.PstnConn#8901", "SIM 1"),
                        AccountRef("com.android.phone/.PstnConn#8902", "SIM 2"),
                    ),
                calls = listOf(CallInfo(1, "dialing", "05551112233", "05551112233")),
            )
        assertSimilar(fixture("selecting_account.json"), SnapshotJson.buildSnapshot(data))
    }

    @Test fun bitConstantsArePinnedAndDisjoint() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512), Wire.Caps.ALL.toList())
        assertEquals(listOf(1, 2, 4, 8), Wire.Props.ALL.toList())
        assertEquals(listOf(1, 2, 4, 8), Wire.Features.ALL.toList())
        for (mask in listOf(Wire.Caps.ALL, Wire.Props.ALL, Wire.Features.ALL)) {
            var seen = 0
            for (bit in mask) {
                assertEquals(1, Integer.bitCount(bit))
                assertEquals(0, seen and bit)
                seen = seen or bit
            }
        }
    }
}
