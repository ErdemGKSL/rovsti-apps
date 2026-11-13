package dev.rovsti.phone.data

import android.app.Application
import android.content.res.Configuration
import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** F30: a dial refusal is worded from the core's stable code, in the phone's language, never in the core's English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DialRefusalTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private fun error(answer: String): RustError = (Envelope.decode(answer.encodeToByteArray()) as Outcome.Err).error

    private fun words(locale: Locale): Map<DialRefusal, String> {
        val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
        val res = app.createConfigurationContext(config).resources
        return DialRefusal.entries.associateWith { res.getString(it.text) }
    }

    @Test fun theCoreCodesMapToTheirRefusals() {
        // The answers of crates/rovsti_ffi/fixtures/dial.validate.{empty,no_dialable}.answer.json.
        val empty = error("""{"err":{"kind":"invalid","message":"nothing to dial","detail":{"reason":"empty"}}}""")
        val none =
            error(
                """{"err":{"kind":"invalid","message":"no digit, '*' or '#' in the number",""" +
                    """"detail":{"reason":"no_dialable"}}}""",
            )
        assertEquals(DialRefusal.Empty, DialRefusal.of(empty))
        assertEquals(DialRefusal.NoDialable, DialRefusal.of(none))
    }

    @Test fun anUnknownOrMissingCodeIsTheGenericRefusal() {
        assertEquals(DialRefusal.Other, DialRefusal.of("from_a_newer_core"))
        assertEquals(DialRefusal.Other, DialRefusal.of(null as String?))
        assertEquals(DialRefusal.Other, DialRefusal.of(RustError.Invalid("nothing to dial")))
        assertEquals(DialRefusal.Other, DialRefusal.of(RustError.Panic("boom")))
    }

    @Test fun everyRefusalSpeaksTurkishOnATurkishPhone() {
        val en = words(Locale.ENGLISH)
        val tr = words(Locale.forLanguageTag("tr"))
        for (refusal in DialRefusal.entries) {
            assertTrue("$refusal has English words", en.getValue(refusal).isNotBlank())
            assertNotEquals("$refusal is translated", en.getValue(refusal), tr.getValue(refusal))
        }
        assertEquals("Aranacak numara yok", tr.getValue(DialRefusal.Empty))
        assertTrue("never the core's sentence", "nothing to dial" !in tr.values && "nothing to dial" !in en.values)
    }
}
