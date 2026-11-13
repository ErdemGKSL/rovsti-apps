package dev.rovsti.platform.calllog

import android.content.Context
import dev.rovsti.platform.PlatformPrefs
import dev.rovsti.platform.identity.PhoneNumbers

/**
 * Prefix ranges of the blocklist (U55): "+90 850 *" blocks every number that starts with those digits (a call-centre
 * block, a spam range). The system blocklist (`BlockedNumberContract`) only knows whole numbers, so the ranges live in
 * a small preference file of this app and are checked next to the system list when a call rings (`CallRegistry`) and
 * listed with it on the Blocked numbers page.
 *
 * A range is stored as the owner typed it ("+90 850"); [key] is its digits with a leading `+`, which is what is compared.
 */
object BlockPrefix {
    /** Fewest digits of a range: shorter ranges would swallow whole countries (and the emergency numbers). */
    const val MIN_DIGITS = 3

    /** Most digits of a range (a whole E.164 number is 15 digits). */
    const val MAX_DIGITS = 14

    /** What [parse] could make of the typed text. */
    sealed interface Parsed {
        /** A usable range: [display] is what the list shows, [key] what is compared. */
        data class Valid(
            val display: String,
            val key: String,
        ) : Parsed

        /** Letters, dial codes or nothing. */
        data object Invalid : Parsed

        /** Fewer than [MIN_DIGITS] digits. */
        data object TooShort : Parsed
    }

    /** Digits (with a leading `+` kept) of [typed]; separators are dropped. */
    fun key(typed: String): String {
        val t = typed.trim().removeSuffix("*").trim()
        val plus = t.startsWith("+")
        return (if (plus) "+" else "") + t.filter { it.isDigit() }
    }

    fun parse(typed: String): Parsed {
        val t = typed.trim().removeSuffix("*").trim()
        if (t.isEmpty()) return Parsed.Invalid
        if (t.any { !(it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' || it == '.') }) {
            return Parsed.Invalid
        }
        if (t.lastIndexOf('+') > 0) return Parsed.Invalid
        val key = key(t)
        val digits = key.count { it.isDigit() }
        if (digits < MIN_DIGITS) return Parsed.TooShort
        if (digits > MAX_DIGITS) return Parsed.Invalid
        // One space between groups, nothing else.
        val shown = t.replace(Regex("[()\\-.]"), " ").replace(Regex("\\s+"), " ").trim()
        return Parsed.Valid(shown, key)
    }

    /** Whether [number] (as dialled or as stored) starts with the range [key]. */
    fun matches(
        key: String,
        number: String,
    ): Boolean {
        // One normaliser: the platform's (withheld callers match nothing; an extension never counts as a digit).
        if (key.isEmpty() || PhoneNumbers.isWithheld(number)) return false
        val n = PhoneNumbers.withoutExtension(number).trim()
        val digits = n.filter { it.isDigit() }
        return if (key.startsWith("+")) {
            // International range: compare with the international form; "00" is the same as "+".
            val international =
                when {
                    n.startsWith("+") -> "+$digits"
                    n.startsWith("00") -> "+" + digits.removePrefix("00")
                    else -> return false
                }
            international.startsWith(key)
        } else {
            digits.startsWith(key)
        }
    }
}

/** Where the ranges are kept; a seam so the JVM tests need no Android. */
interface PrefixStore {
    /** The ranges as typed, oldest first. */
    fun all(): List<String>

    /** Adds [display] (no second range with the same key); true when it was new. */
    fun add(display: String): Boolean

    /** Removes the range with the same key as [display]; true when one was there. */
    fun remove(display: String): Boolean
}

/** [PrefixStore] in `SharedPreferences`, one string set per range text, in insertion order. */
class PrefsPrefixStore(
    context: Context,
) : PrefixStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): List<String> =
        prefs
            .getString(KEY, "")
            .orEmpty()
            .split(SEP)
            .filter { it.isNotBlank() }

    @Synchronized
    override fun add(display: String): Boolean {
        val now = all()
        val key = BlockPrefix.key(display)
        if (now.any { BlockPrefix.key(it) == key }) return false
        prefs.edit().putString(KEY, (now + display).joinToString(SEP)).apply()
        return true
    }

    @Synchronized
    override fun remove(display: String): Boolean {
        val now = all()
        val key = BlockPrefix.key(display)
        val kept = now.filterNot { BlockPrefix.key(it) == key }
        if (kept.size == now.size) return false
        prefs.edit().putString(KEY, kept.joinToString(SEP)).apply()
        return true
    }

    private companion object {
        const val FILE = PlatformPrefs.PHONE_SETTINGS
        const val KEY = "block_prefix_ranges"
        const val SEP = "\n"
    }
}

/** In memory, for tests and previews. */
class MemoryPrefixStore(
    initial: List<String> = emptyList(),
) : PrefixStore {
    private val ranges = initial.toMutableList()

    @Synchronized
    override fun all(): List<String> = ranges.toList()

    @Synchronized
    override fun add(display: String): Boolean {
        if (ranges.any { BlockPrefix.key(it) == BlockPrefix.key(display) }) return false
        ranges += display
        return true
    }

    @Synchronized
    override fun remove(display: String): Boolean = ranges.removeAll { BlockPrefix.key(it) == BlockPrefix.key(display) }
}

/** Whether any range of [store] covers [number]. */
fun PrefixStore.covers(number: String): Boolean = all().any { BlockPrefix.matches(BlockPrefix.key(it), number) }
