package dev.rosty.core.model

import dev.rosty.core.Envelope
import dev.rosty.core.WaIdSamples
import dev.rosty.core.model.wa.WaIdKind
import dev.rosty.core.model.wa.WaIdParseRequest
import dev.rosty.core.model.wa.WaIdParsed
import dev.rosty.core.model.wa.WaIds
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [WaIds] is the Kotlin copy of `rosty_core::wa_identity::parse`: it must answer what the Rust parser answers. The
 * strings are those of the Rust unit tests ([WaIdSamples]) and the contract fixtures of the op `wa_identity.parse`
 * (written by Rust). Plain JVM, no native library; `WaIdsNativeTest` asks the real library for the same strings.
 */
class WaIdsTest {
    @Test
    fun everyIdOfTheRustTestsParsesAsInRust() {
        for ((text, expected) in WaIdSamples.parsed) assertEquals(text, expected, WaIds.parse(text))
    }

    @Test
    fun whatRustRejectsIsNotAnId() {
        for (text in WaIdSamples.rejected) assertNull(text, WaIds.parse(text))
        assertNull(WaIds.parse(null))
    }

    /** Every `wa_identity.parse*.request.json` of the contract fixtures against its answer. */
    @Test
    fun theContractFixturesOfTheOpParseTheSame() {
        val dir = File(System.getProperty("rosty.contract") ?: error("rosty.contract not set"))
        val requests = dir.listFiles { f -> f.name.matches(Regex("""wa_identity\.parse(\..+)?\.request\.json""")) }
        assertTrue("no wa_identity.parse fixtures in $dir", !requests.isNullOrEmpty())
        for (request in requests.orEmpty()) {
            val answer = File(dir, request.name.replace(".request.json", ".answer.json"))
            val id = Envelope.json.decodeFromString(WaIdParseRequest.serializer(), request.readText()).id
            val ok =
                Envelope.json
                    .parseToJsonElement(answer.readText())
                    .jsonObject
                    .getValue("ok")
            val expected = Envelope.json.decodeFromJsonElement(WaIdParsed.serializer().nullable, ok)
            assertEquals(request.name, expected, WaIds.parse(id))
        }
    }

    @Test
    fun theBareFormParsesToItself() {
        for ((text, expected) in WaIdSamples.parsed) {
            assertEquals(text, expected.copy(device = null), WaIds.parse(expected.bare))
        }
    }

    @Test
    fun everyDeviceOfAPersonIsOneId() {
        val pn = "905550000001@s.whatsapp.net"
        for (text in listOf(
            "905550000001:0@s.whatsapp.net",
            "905550000001:12@s.whatsapp.net",
            "905550000001.0:12@s.whatsapp.net",
            "905550000001:99@hosted",
            "905550000001@c.us",
        )) {
            assertEquals(text, pn, WaIds.bare(text))
            assertTrue(text, WaIds.same(text, pn))
        }
        for (text in listOf(
            "123:0@lid",
            "123:7@lid",
            "123:99@hosted.lid",
        )) {
            assertEquals(text, "123@lid", WaIds.bare(text))
        }
    }

    @Test
    fun aNumberAndALidWithTheSameDigitsAreTwoIds() {
        assertFalse(WaIds.same("905550000001@s.whatsapp.net", "905550000001@lid"))
        assertEquals("+905550000001", WaIds.number("905550000001:3@s.whatsapp.net"))
        // A LID is never read as a number, whatever its digits; nor is a group, a service or a bot.
        for (text in listOf("905550000001@lid", "1-2@g.us", "0@s.whatsapp.net", "1@bot")) {
            assertNull(text, WaIds.number(text))
        }
    }

    @Test
    fun theKindAnswersWhatAScreenAsks() {
        assertTrue(WaIds.isGroup("120000000000000001@g.us"))
        assertFalse(WaIds.isGroup("905550000001@s.whatsapp.net"))
        assertTrue(WaIds.isLid("777000000000001:2@lid"))
        assertTrue(WaIds.isPn("905550000001:2@s.whatsapp.net"))
        assertTrue(WaIds.isPerson("777000000000001@lid") && WaIds.isPerson("905550000001@c.us"))
        assertFalse(WaIds.isPerson("0@s.whatsapp.net") || WaIds.isPerson("1@bot") || WaIds.isPerson("merhaba"))
        for (chat in listOf("905550000001@s.whatsapp.net", "777000000000001@lid", "1-2@g.us", "1@bot")) {
            assertTrue(chat, WaIds.isConversation(chat))
        }
        for (feed in listOf("status@broadcast", "1700@broadcast", "x@newsletter", "merhaba", null)) {
            assertFalse("$feed", WaIds.isConversation(feed))
        }
        assertEquals(WaIdKind.Status, WaIds.kind("status@broadcast"))
        assertEquals("777000000000001", WaIds.user("777000000000001:2@lid"))
    }

    @Test
    fun aDialableNumberBecomesItsPhoneId() {
        assertEquals("905550000001@s.whatsapp.net", WaIds.pnJid("+90 555 000 00 01"))
        assertEquals("905550000001@s.whatsapp.net", WaIds.pnJid("905550000001"))
        assertNull(WaIds.pnJid("12345"))
        assertNull(WaIds.pnJid("abc"))
    }

    @Test
    fun theCacheAnswersTheSameAndStaysSmall() {
        repeat(2_000) { n -> assertEquals("$n@lid", WaIds.bare("$n:1@lid")) }
        assertEquals("0@lid", WaIds.bare("0:1@lid"))
        assertNull(WaIds.parse("merhaba"))
        assertNull(WaIds.parse("merhaba"))
    }
}
