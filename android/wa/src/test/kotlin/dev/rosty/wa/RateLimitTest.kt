package dev.rosty.wa

import dev.rosty.core.RustError
import dev.rosty.wa.avatars.RateLimit
import dev.rosty.wa.avatars.isRateLimited
import dev.rosty.wa.avatars.rateLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gateway's 429 detail, as `rosty_ffi::ops::wa::map_error` sends it. */
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
