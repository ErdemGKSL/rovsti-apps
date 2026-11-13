package dev.rovsti.platform.identity

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * The platform half of number normalization; the matching rules are the Rust core's (`rovsti_core::caller_id`), which
 * every app shares. Android normalizes with its own libphonenumber ([PhoneNumberUtils.formatNumberToE164]) in the home
 * region: the SIM's country, else the network's, else the locale's, else Turkey.
 */
object PhoneNumbers {
    /** The region when nothing else says one. */
    const val FALLBACK_REGION = "TR"

    /** The longest number text worth normalizing; anything longer is no phone number. */
    private const val MAX_TEXT = 40

    /** The home region (ISO 3166 alpha-2, upper case), read once per call; never throws. */
    fun homeRegion(context: Context): String {
        val phone =
            try {
                context.getSystemService(TelephonyManager::class.java)
            } catch (_: RuntimeException) {
                null
            }
        return regionOf(
            sim = safe { phone?.simCountryIso },
            network = safe { phone?.networkCountryIso },
            locale = Locale.getDefault().country,
        )
    }

    /** Pure: the first of [sim], [network] and [locale] that is a two-letter region, else [FALLBACK_REGION]. */
    fun regionOf(
        sim: String?,
        network: String?,
        locale: String?,
    ): String =
        listOf(sim, network, locale)
            .map { it?.trim()?.uppercase(Locale.ROOT).orEmpty() }
            .firstOrNull { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            ?: FALLBACK_REGION

    /**
     * Pure (Android's metadata): [raw] in E.164 for [region], null for a withheld number, a short code, a service
     * code or a number Android does not take as valid. An extension or dial string after the number is ignored.
     */
    fun e164(
        raw: String,
        region: String,
    ): String? {
        if (isWithheld(raw) || raw.length > MAX_TEXT) return null
        val number = withoutExtension(raw)
        if (number.count { it.isDigit() } < MIN_DIGITS) return null
        return try {
            PhoneNumberUtils.formatNumberToE164(number, region)?.takeIf { it.startsWith("+") }
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * [raw] as people read it (`+905321234567` -> `+90 532 123 45 67`, `05321234567` -> `0532 123 45 67`) by Android's
     * libphonenumber in [region]; the trimmed text itself for a withheld number, a short code or anything Android
     * cannot format. Loads Android's metadata on first use: not the main thread.
     */
    fun forReading(
        raw: String,
        region: String,
    ): String {
        val text = raw.trim()
        if (isWithheld(text) || text.length > MAX_TEXT || text.count { it.isDigit() } < MIN_DIGITS) return text
        return try {
            PhoneNumberUtils.formatNumber(text, region)?.takeIf { it.isNotBlank() } ?: text
        } catch (_: RuntimeException) {
            text
        }
    }

    /** Pure, the core's `is_withheld`: what a call log writes for a caller nobody can name. */
    fun isWithheld(raw: String): Boolean {
        val text = raw.trim { it.isWhitespace() || it in FORMAT_MARKS }
        if (text.isEmpty() || text in PLACEHOLDERS) return true
        return text.lowercase(Locale.ROOT) in WITHHELD_WORDS
    }

    /** Pure, the core's `strip_extension`: the number without `ext. 12`, `x12`, `;ext=12`, `,12` or `;12`. */
    fun withoutExtension(raw: String): String {
        if ((raw.contains('*') || raw.contains('#')) && !raw.trimStart().startsWith("+")) return raw
        val lower = raw.lowercase(Locale.ROOT)
        var cut = raw.length
        for (marker in EXTENSION_MARKERS) {
            val at = lower.indexOf(marker)
            if (at > 0 && raw.substring(0, at).any { it in '0'..'9' }) cut = minOf(cut, at)
        }
        return raw.substring(0, cut).trimEnd { it.isWhitespace() || it == '.' || it == ':' }
    }

    private inline fun safe(read: () -> String?): String? =
        try {
            read()
        } catch (_: RuntimeException) {
            null
        }

    /** Short codes and service numbers have fewer national digits than this (the core's `MIN_E164_NSN`). */
    private const val MIN_DIGITS = 8
    private val PLACEHOLDERS = setOf("-1", "-2", "-3", "-4")
    private val WITHHELD_WORDS = setOf("unknown", "private", "anonymous", "payphone", "restricted", "withheld")
    private val EXTENSION_MARKERS = listOf(",", ";", "#", "ext", "x", "p", "w")
    private val FORMAT_MARKS =
        setOf('\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E') +
            setOf('\u2060', '\u2066', '\u2067', '\u2068', '\u2069', '\u061C', '\uFEFF')
}
