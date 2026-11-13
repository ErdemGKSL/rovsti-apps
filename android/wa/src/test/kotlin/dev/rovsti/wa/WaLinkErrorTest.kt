package dev.rovsti.wa

import android.app.Application
import android.content.res.Configuration
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

/** F33: a failed link is a kind the UI words in the phone's language, never an English sentence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaLinkErrorTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private fun words(locale: Locale): Map<WaLinkError, String> {
        val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
        val res = app.createConfigurationContext(config).resources
        return WaLinkError.entries.associateWith { res.getString(it.text) }
    }

    @Test fun rustErrorsMapToKinds() {
        assertEquals(WaLinkError.KeyRefused, WaLinkError.of(RustError.Auth("401")))
        assertEquals(WaLinkError.Unreachable, WaLinkError.of(RustError.Network("no route to host")))
        assertEquals(WaLinkError.Failed, WaLinkError.of(RustError.Io("disk")))
        assertEquals(WaLinkError.Failed, WaLinkError.of(RustError.Unknown("future", "x")))
    }

    @Test fun everyKindSpeaksTurkishOnATurkishPhone() {
        val en = words(Locale.ENGLISH)
        val tr = words(Locale.forLanguageTag("tr"))
        for (kind in WaLinkError.entries) {
            assertTrue("$kind has English words", en.getValue(kind).isNotBlank())
            assertNotEquals("$kind is translated", en.getValue(kind), tr.getValue(kind))
        }
        assertTrue(tr.getValue(WaLinkError.KeyRefused).contains("anahtar"))
    }
}
