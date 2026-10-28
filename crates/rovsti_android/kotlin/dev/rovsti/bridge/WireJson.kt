package dev.rovsti.bridge

import org.json.JSONArray
import org.json.JSONObject

/*
 * Wire shapes shared with Rust (`rovsti_core::wire`, AD-10). Pure JVM code on
 * purpose: only org.json, no Android classes, so the golden-fixture JVM test
 * (WireFixturesTest) can run them. Rules: snake_case, `v: 1`, defaults are
 * omitted (connect_time_ms 0, can_hold/is_incoming false, null options).
 */

const val WIRE_VERSION = 1

data class AudioInfo(
    val muted: Boolean = false,
    val route: String = "earpiece",
    val routes: List<String> = emptyList(),
)

data class CallInfo(
    val id: Int,
    val state: String,
    val numberRaw: String = "",
    val normalized: String = "",
    val displayName: String? = null,
    val contactId: Long? = null,
    val connectTimeMs: Long = 0,
    val canHold: Boolean = false,
    val isIncoming: Boolean = false,
    val disconnectCause: String? = null,
)

data class SnapshotData(
    val seq: Long,
    val nowMs: Long,
    val keyguardLocked: Boolean,
    val audio: AudioInfo = AudioInfo(),
    val foregroundCallId: Int? = null,
    val waitingCallId: Int? = null,
    val calls: List<CallInfo> = emptyList(),
)

/** Which call is in front and which one is waiting; Rust validates, never re-selects. */
data class Focus(val foreground: Int?, val waiting: Int?) {
    companion object {
        /**
         * Foreground: first active, else dialing, else holding, else ringing,
         * else (a call-ended hold) the first disconnected. Waiting: a ringing
         * call that is not the foreground one.
         */
        fun of(calls: List<CallInfo>): Focus {
            fun first(state: String) = calls.firstOrNull { it.state == state }?.id
            val foreground = first("active") ?: first("dialing") ?: first("holding")
                ?: first("ringing") ?: first("disconnected")
            val waiting = calls.firstOrNull { it.state == "ringing" && it.id != foreground }?.id
            return Focus(foreground, waiting)
        }
    }
}

object SnapshotJson {
    fun empty(seq: Long, nowMs: Long, keyguardLocked: Boolean, audio: AudioInfo = AudioInfo()) =
        SnapshotData(seq, nowMs, keyguardLocked, audio)

    fun build(data: SnapshotData): JSONObject {
        val audio = JSONObject()
            .put("muted", data.audio.muted)
            .put("route", data.audio.route)
            .put("routes", JSONArray(data.audio.routes))
        val calls = JSONArray()
        for (c in data.calls) calls.put(callJson(c))
        val out = JSONObject()
            .put("v", WIRE_VERSION)
            .put("seq", data.seq)
            .put("now_ms", data.nowMs)
            .put("keyguard_locked", data.keyguardLocked)
            .put("audio", audio)
        data.foregroundCallId?.let { out.put("foreground_call_id", it) }
        data.waitingCallId?.let { out.put("waiting_call_id", it) }
        return out.put("calls", calls)
    }

    fun toJson(data: SnapshotData): String = build(data).toString()

    private fun callJson(c: CallInfo): JSONObject {
        val o = JSONObject()
            .put("id", c.id)
            .put("state", c.state)
            .put("number_raw", c.numberRaw)
            .put("normalized", c.normalized)
        c.displayName?.let { o.put("display_name", it) }
        c.contactId?.let { o.put("contact_id", it) }
        if (c.connectTimeMs != 0L) o.put("connect_time_ms", c.connectTimeMs)
        if (c.canHold) o.put("can_hold", true)
        if (c.isIncoming) o.put("is_incoming", true)
        c.disconnectCause?.let { o.put("disconnect_cause", it) }
        return o
    }

    /** Reads a snapshot back (tests, and symmetry with Rust). A missing keyguard flag means locked. */
    fun parse(o: JSONObject): SnapshotData {
        val a = o.optJSONObject("audio")
        val routes = a?.optJSONArray("routes")
        val audio = AudioInfo(
            muted = a?.optBoolean("muted", false) ?: false,
            route = a?.optString("route", "earpiece") ?: "earpiece",
            routes = List(routes?.length() ?: 0) { routes!!.getString(it) },
        )
        val arr = o.optJSONArray("calls")
        val calls = List(arr?.length() ?: 0) { i ->
            val c = arr!!.getJSONObject(i)
            CallInfo(
                id = c.getInt("id"),
                state = c.optString("state", "unknown"),
                numberRaw = c.optString("number_raw", ""),
                normalized = c.optString("normalized", ""),
                displayName = if (c.has("display_name")) c.getString("display_name") else null,
                contactId = if (c.has("contact_id")) c.getLong("contact_id") else null,
                connectTimeMs = c.optLong("connect_time_ms", 0),
                canHold = c.optBoolean("can_hold", false),
                isIncoming = c.optBoolean("is_incoming", false),
                disconnectCause = if (c.has("disconnect_cause")) c.getString("disconnect_cause") else null,
            )
        }
        return SnapshotData(
            seq = o.optLong("seq", 0),
            nowMs = o.optLong("now_ms", 0),
            keyguardLocked = o.optBoolean("keyguard_locked", true),
            audio = audio,
            foregroundCallId = if (o.has("foreground_call_id")) o.getInt("foreground_call_id") else null,
            waitingCallId = if (o.has("waiting_call_id")) o.getInt("waiting_call_id") else null,
            calls = calls,
        )
    }

    /** `android.telecom.DisconnectCause.getCode()` to the wire name. */
    fun causeName(code: Int): String = when (code) {
        2 -> "local"
        3, 11 -> "remote"
        4 -> "canceled"
        5 -> "missed"
        6 -> "rejected"
        7 -> "busy"
        1 -> "error"
        8, 9 -> "unreachable"
        else -> "unknown"
    }

    /** `CallEndpoint.getEndpointType()` to the wire route; null for types we do not show. */
    fun routeName(endpointType: Int): String? = when (endpointType) {
        1 -> "earpiece"
        2 -> "bluetooth"
        3 -> "wired_headset"
        4 -> "speaker"
        else -> null
    }
}

/** `{v, ok, data|error}` envelopes for content-provider pulls (AD-9, AD-10). */
object ProviderJson {
    data class LogRow(
        val id: Long,
        val numberRaw: String,
        val type: Int,
        val dateMs: Long,
        val durationS: Long = 0,
        val cachedName: String? = null,
        val label: String? = null,
        val contactId: Long? = null,
    )

    data class PhoneRow(
        val contactId: Long,
        val displayName: String,
        val numberRaw: String,
        val label: String? = null,
        val superPrimary: Boolean = false,
        val starred: Boolean = false,
        val photoUri: String? = null,
    )

    fun error(code: String): String =
        JSONObject().put("v", WIRE_VERSION).put("ok", false).put("error", code).toString()

    private fun ok(data: JSONObject): String =
        JSONObject().put("v", WIRE_VERSION).put("ok", true).put("data", data).toString()

    /** `CallLog.Calls.TYPE` to the wire kind. */
    fun kindName(type: Int): String = when (type) {
        1, 7 -> "incoming"
        2 -> "outgoing"
        3 -> "missed"
        4 -> "voicemail"
        5 -> "rejected"
        6 -> "blocked"
        else -> "unknown"
    }

    /**
     * Digits with an optional leading `+`, like `rovsti_core` reads a number: direction and other
     * format marks are dropped, a `;ext=` style URI parameter is cut, and the `(0)` after a
     * country code is not part of the number. A service code (it holds `*` or `#`, e.g. `*#06#`)
     * keeps those characters so it never collapses into a plain number; Rust leaves it unmatched.
     */
    fun normalize(raw: String): String {
        var text = raw.filterNot { it in '\u200b'..'\u200f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' || it == '\u2060' || it == '\u061c' || it == '\ufeff' }
            .trimStart()
        val param = Regex(";[A-Za-z]").find(text)
        if (param != null) text = text.substring(0, param.range.first)
        if (text.startsWith("+")) text = text.replaceFirst("(0)", "")
        val service = text.any { it == '*' || it == '#' }
        val out = StringBuilder(text.length)
        for ((i, ch) in text.withIndex()) {
            if (ch in '0'..'9' || (i == 0 && ch == '+') || (service && (ch == '*' || ch == '#'))) out.append(ch)
        }
        return out.toString()
    }

    fun callLog(rows: List<LogRow>): String {
        val entries = JSONArray()
        for (r in rows) {
            val o = JSONObject()
                .put("id", r.id)
                .put("number_raw", r.numberRaw)
                .put("normalized", normalize(r.numberRaw))
                .put("kind", kindName(r.type))
                .put("date_ms", r.dateMs)
            if (r.durationS != 0L) o.put("duration_s", r.durationS)
            r.cachedName?.let { o.put("cached_name", it) }
            r.label?.let { o.put("number_label", it) }
            r.contactId?.let { o.put("contact_id", it) }
            entries.put(o)
        }
        return ok(JSONObject().put("entries", entries))
    }

    /** Groups phone rows by contact (first appearance order); super-primary numbers come first. */
    fun contacts(rows: List<PhoneRow>): String {
        val byContact = LinkedHashMap<Long, MutableList<PhoneRow>>()
        for (r in rows) byContact.getOrPut(r.contactId) { ArrayList() }.add(r)
        val contacts = JSONArray()
        for ((id, group) in byContact) {
            val numbers = JSONArray()
            val seen = HashSet<String>()
            for (n in group.sortedByDescending { it.superPrimary }) {
                if (!seen.add(normalize(n.numberRaw).ifEmpty { n.numberRaw })) continue
                val no = JSONObject().put("raw", n.numberRaw)
                n.label?.let { no.put("label", it) }
                if (n.superPrimary) no.put("default", true)
                numbers.put(no)
            }
            val first = group[0]
            val o = JSONObject()
                .put("id", id)
                .put("display_name", first.displayName)
                .put("numbers", numbers)
            if (group.any { it.starred }) o.put("starred", true)
            group.firstNotNullOfOrNull { it.photoUri }?.let { o.put("photo_uri", it) }
            contacts.put(o)
        }
        return ok(JSONObject().put("contacts", contacts))
    }
}
