package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.WaIdSamples
import dev.rosty.core.model.wa.WaIdParseRequest
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.ops.NativeWaSyncOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [WaIds] against the real host library: for every string of the Rust unit tests, and for what Rust rejects, the
 * Kotlin parser answers exactly what the op `wa_identity.parse` answers.
 */
class WaIdsNativeTest {
    private val ops = NativeWaSyncOps(TestRust.core)

    @Before
    fun load() = TestRust.ensure()

    @Test
    fun theKotlinParserAnswersWhatTheLibraryAnswers() =
        runBlocking {
            val texts = WaIdSamples.parsed.map { it.first } + WaIdSamples.rejected + EXTRA
            for (text in texts) {
                val native =
                    when (val answer = ops.parseId(WaIdParseRequest(text))) {
                        is Outcome.Ok -> answer.value
                        is Outcome.Err -> error("wa_identity.parse failed for '$text': ${answer.error}")
                    }
                assertEquals("'$text'", native, WaIds.parse(text))
            }
        }

    private companion object {
        /** Spellings no Rust test names, where the two parsers could drift: signs, zeros, limits, odd separators. */
        val EXTRA =
            listOf(
                "90555:+7@s.whatsapp.net",
                "90555:007@s.whatsapp.net",
                "90555:65535@s.whatsapp.net",
                "90555:65536@s.whatsapp.net",
                "90555:-1@s.whatsapp.net",
                "90555.65536@s.whatsapp.net",
                "90555.255:1@s.whatsapp.net",
                "90555.256:1@s.whatsapp.net",
                "90555_7@s.whatsapp.net",
                "90555_999@s.whatsapp.net",
                "1.2.3@g.us",
                "1_2@g.us",
                "a:b:7@lid",
                "status:1@broadcast",
                "status.1@broadcast",
                "0:5@s.whatsapp.net",
                "0.5@s.whatsapp.net",
                "x.0255@newsletter",
                "x.000000000001@newsletter",
                "\t90555@s.whatsapp.net ",
                "905550000001@S.WHATSAPP.NET",
                "@",
                "@@",
                "a@b@",
            )
    }
}
