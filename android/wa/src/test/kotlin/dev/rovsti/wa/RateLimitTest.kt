package dev.rovsti.wa

import dev.rovsti.core.RustError
import dev.rovsti.wa.avatars.RateLimit
import dev.rovsti.wa.avatars.isRateLimited
import dev.rovsti.wa.avatars.rateLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gateway's 429 detail, as `rovsti_ffi::ops::wa::map_error` sends it. */
class RateLimitTest {
    private fun invalid(detail: String): RustError =
        RustError.Invalid("x", Json.parseToJsonElement(detail) as JsonElement)

    @Test
    fun aNewGatewaySaysWhenAndWho() {
        val error = invalid("""{"code":"rate_limited","retry_after_ms":61000,"source":"whatsapp"}""")
        assertEquals(RateLimit(61_000, "whatsapp"), error.rateLimit())
        assertTrue(error.isRateLimited())
    }

    @Test
    fun anOldGatewaySaysOnlyThatItIsLimited() {
        assertEquals(RateLimit(null, null), invalid("""{"code":"rate_limited"}""").rateLimit())
    }

    @Test
    fun otherErrorsAreNotRateLimits() {
        assertNull(invalid("""{"code":"internal"}""").rateLimit())
        assertNull(RustError.Network("down").rateLimit())
        assertFalse(invalid("""{"code":{"nested":1}}""").isRateLimited())
    }
}
