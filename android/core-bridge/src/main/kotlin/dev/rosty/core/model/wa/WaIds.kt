package dev.rosty.core.model.wa

/**
 * The one place Kotlin takes a WhatsApp id (a JID) apart: what it names ([kind]), its id without device ([bare]),
 * the phone number it carries ([number]). Every screen, receiver and repository asks here instead of cutting the
 * text at `@` itself.
 *
 * The rules are those of `rosty_core::wa_identity::parse` (the op `wa_identity.parse`). They are written out here
 * in Kotlin and not called through the bridge, because the callers are synchronous (a `remember`, a pure row
 * mapper, a broadcast receiver before the native library is loaded, plain JVM tests): the op is a suspend call on
 * the Rust dispatcher. Two tests keep the two in step: `WaIdsTest` reads the contract fixtures of `wa_identity.parse`
 * and the strings of the Rust unit tests, `WaIdsNativeTest` asks the real library for the same strings.
 *
 * Parsing says what an id is, never who: a LID whose number the store knows is still a LID here. Who it is (the
 * chat it belongs to, the numbers of the person) is `WaSyncStore.resolve` and `WaSyncStore.numbers`.
 */
object WaIds {
    private const val PN_SERVER = "s.whatsapp.net"
    private const val LID_SERVER = "lid"
    private const val GROUP_SERVER = "g.us"
    private const val BROADCAST_SERVER = "broadcast"
    private const val NEWSLETTER_SERVER = "newsletter"
    private const val BOT_SERVER = "bot"
    private const val STATUS_USER = "status"

    /** The announcements account (`0@s.whatsapp.net`): a service, not a person. */
    private const val PSA_USER = "0"
    private const val MAX_DEVICE = 65_535
    private const val MAX_AGENT = 255
    private const val CACHE_SIZE = 256

    /** Shortest number a phone JID is built from ([pnJid]). */
    private const val MIN_NUMBER_DIGITS = 6

    /** The ids parsed last: lists ask for the same few ids on every pass. */
    private val cache =
        object : LinkedHashMap<String, Parsed>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?): Boolean =
                size > CACHE_SIZE
        }

    /** A cached answer; [id] null: the text is not a JID. */
    private class Parsed(
        val id: WaIdParsed?,
    )

    /** [jid] taken apart; null when it is not a JID (no `@`, an empty part, a phone-number server without digits). */
    fun parse(jid: String?): WaIdParsed? {
        if (jid == null) return null
        synchronized(cache) { cache[jid] }?.let { return it.id }
        val parsed = Parsed(parseNow(jid))
        synchronized(cache) { cache[jid] = parsed }
        return parsed.id
    }

    /** What [jid] names; null when it is not a JID. */
    fun kind(jid: String?): WaIdKind? = parse(jid)?.kind

    /** `user@server` without device or agent, the server in its usual spelling; null when [jid] is not a JID. */
    fun bare(jid: String?): String? = parse(jid)?.bare

    /** The digits of a phone number, the LID user, the group id; null when [jid] is not a JID. */
    fun user(jid: String?): String? = parse(jid)?.user

    /** `+<digits>` when [jid] is a phone-number id; a LID has none, whatever its digits. */
    fun number(jid: String?): String? = parse(jid)?.number

    /**
     * The number of a person the gateway names by [jid] and, when it knows it, by the other form [alt] (a call's
     * `peer` and `peer_alt`, a message's `sender` and `sender_alt`): the one of the two that is a phone number. A
     * LID next to its number is that number; a LID alone has none.
     */
    fun number(
        jid: String?,
        alt: String?,
    ): String? = number(jid) ?: number(alt)

    /** The digits of the phone number [jid] is (`905550000001`); null for every other kind. */
    fun pnUser(jid: String?): String? = parse(jid)?.takeIf { it.kind == WaIdKind.Pn }?.user

    fun isGroup(jid: String?): Boolean = kind(jid) == WaIdKind.Group

    fun isLid(jid: String?): Boolean = kind(jid) == WaIdKind.Lid

    /** A phone-number id: the only kind that can be called or saved as a contact. */
    fun isPn(jid: String?): Boolean = kind(jid) == WaIdKind.Pn

    /** A person (a phone number or a LID), not a group, a channel or a service. */
    fun isPerson(jid: String?): Boolean = kind(jid).let { it == WaIdKind.Pn || it == WaIdKind.Lid }

    /** A chat people talk in: not the status feed, a channel or a broadcast list. Text that is no JID is not one. */
    fun isConversation(jid: String?): Boolean =
        when (kind(jid)) {
            null, WaIdKind.Status, WaIdKind.Newsletter, WaIdKind.Broadcast -> false
            else -> true
        }

    /** Whether [a] and [b] are one id, whatever device either was written with. Null and text that is no JID: no. */
    fun same(
        a: String?,
        b: String?,
    ): Boolean = bare(a)?.let { it == bare(b) } == true

    /**
     * The phone-number id of a dialable [number] (`<digits>@s.whatsapp.net`); null when it has fewer than six
     * digits. The number must be international: the digits are taken as they are.
     */
    fun pnJid(number: String): String? {
        val digits = number.filter { it in '0'..'9' }
        return digits.takeIf { it.length >= MIN_NUMBER_DIGITS }?.let { "$it@$PN_SERVER" }
    }

    /** `rosty_core::wa_identity::parse`, line for line. */
    private fun parseNow(text: String): WaIdParsed? {
        val trimmed = text.trim()
        val at = trimmed.lastIndexOf('@')
        if (at <= 0 || at == trimmed.lastIndex) return null
        val local = trimmed.substring(0, at)
        val server = trimmed.substring(at + 1)
        val kind =
            when (server) {
                PN_SERVER, "c.us", "hosted" -> WaIdKind.Pn
                LID_SERVER, "hosted.lid" -> WaIdKind.Lid
                GROUP_SERVER -> WaIdKind.Group
                BROADCAST_SERVER -> WaIdKind.Broadcast
                NEWSLETTER_SERVER -> WaIdKind.Newsletter
                BOT_SERVER -> WaIdKind.Bot
                else -> WaIdKind.Other
            }
        val colon = local.lastIndexOf(':')
        var user = if (colon >= 0) local.substring(0, colon) else local
        var device = if (colon >= 0) unsigned(local.substring(colon + 1), MAX_DEVICE) else null
        if (kind != WaIdKind.Lid) {
            val cut = user.lastIndexOfAny(charArrayOf('.', '_'))
            val tail = if (cut >= 0) user.substring(cut + 1) else ""
            if (tail.isNotEmpty() && tail.all { it in '0'..'9' }) {
                if (kind == WaIdKind.Pn && colon < 0 && user[cut] == '.') {
                    // `digits.device@s.whatsapp.net`: the legacy device spelling.
                    unsigned(tail, MAX_DEVICE)?.let {
                        user = user.substring(0, cut)
                        device = it
                    }
                } else if (unsigned(tail, MAX_AGENT) != null) {
                    user = user.substring(0, cut)
                }
            }
        }
        if (user.isEmpty()) return null
        return when {
            kind == WaIdKind.Pn && user == PSA_USER -> {
                WaIdParsed(WaIdKind.Other, user, "$user@$PN_SERVER", device = device)
            }

            kind == WaIdKind.Pn -> {
                if (user.all { it in '0'..'9' }) {
                    WaIdParsed(kind, user, "$user@$PN_SERVER", number = "+$user", device = device)
                } else {
                    null
                }
            }

            kind == WaIdKind.Broadcast && user == STATUS_USER -> {
                WaIdParsed(WaIdKind.Status, user, "$user@$BROADCAST_SERVER", device = device)
            }

            kind == WaIdKind.Lid -> {
                WaIdParsed(kind, user, "$user@$LID_SERVER", device = device)
            }

            // Every other kind is written with its own server; an unknown server is kept as written.
            else -> {
                WaIdParsed(kind, user, "$user@$server", device = device)
            }
        }
    }

    /** Rust's `str::parse` for an unsigned integer up to [max]: digits with an optional `+`; null otherwise. */
    private fun unsigned(
        text: String,
        max: Int,
    ): Int? {
        val digits = text.removePrefix("+")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val trimmed = digits.trimStart('0')
        // Longer than any value up to 65 535: out of range (and it would not fit an Int either).
        if (trimmed.length > MAX_DIGITS) return null
        val value = if (trimmed.isEmpty()) 0 else trimmed.toInt()
        return value.takeIf { it <= max }
    }

    private const val MAX_DIGITS = 5
}
