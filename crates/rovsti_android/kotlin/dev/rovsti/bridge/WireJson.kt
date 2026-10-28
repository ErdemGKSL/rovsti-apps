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

/** Bit constants shared with `rovsti_core::wire::{caps, props, features}` (AD-24); pinned by tests. */
object Wire {
    object Caps {
        const val HOLD = 1
        const val SUPPORT_HOLD = 2
        const val MERGE = 4
        const val SWAP = 8
        const val MANAGE = 16
        const val SPLIT = 32
        const val DISCONNECT_PARTICIPANT = 64
        const val RESPOND_VIA_TEXT = 128
        const val MUTE = 256
        const val ADD_PARTICIPANT = 512
        val ALL = intArrayOf(HOLD, SUPPORT_HOLD, MERGE, SWAP, MANAGE, SPLIT, DISCONNECT_PARTICIPANT, RESPOND_VIA_TEXT, MUTE, ADD_PARTICIPANT)
    }

    object Props {
        const val CONFERENCE = 1
        const val HD = 2
        const val WIFI = 4
        const val EMERGENCY = 8
        val ALL = intArrayOf(CONFERENCE, HD, WIFI, EMERGENCY)
    }

    object Features {
        const val HD = 1
        const val WIFI = 2
        const val VOLTE = 4
        const val VIDEO = 8
        val ALL = intArrayOf(HD, WIFI, VOLTE, VIDEO)
    }
}

/** One audio output (`audio.endpoints[]`, AD-23). [name] is sent for bluetooth, wired and unknown only. */
data class EndpointInfo(
    val id: String,
    val type: String,
    val name: String? = null,
    val selected: Boolean = false,
    val hint: String? = null,
)

data class AudioInfo(
    val muted: Boolean = false,
    val route: String = "earpiece",
    val routes: List<String> = emptyList(),
    val endpoints: List<EndpointInfo> = emptyList(),
) {
    companion object {
        /** `route` and `routes` derived from [endpoints] (AD-23); [fallback] when none is selected. */
        fun derived(muted: Boolean, endpoints: List<EndpointInfo>, fallback: String = "earpiece"): AudioInfo {
            val route = endpoints.firstOrNull { it.selected }?.type ?: fallback
            return AudioInfo(muted, route, endpoints.map { it.type }.distinct(), endpoints)
        }
    }
}

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
    val parentId: Int? = null,
    val children: List<Int> = emptyList(),
    val caps: Int = 0,
    val props: Int = 0,
    val accountKey: String? = null,
    val displayNameAlt: String? = null,
    val holdSinceMs: Long? = null,
    /** Not on the wire: this call can be conferenced with another one (`Call.getConferenceableCalls()` is not empty). */
    val conferenceable: Boolean = false,
)

data class AccountRef(val key: String, val label: String = "")

data class SnapshotData(
    val seq: Long,
    val nowMs: Long,
    val keyguardLocked: Boolean,
    val audio: AudioInfo = AudioInfo(),
    val foregroundCallId: Int? = null,
    val waitingCallId: Int? = null,
    val backgroundCallId: Int? = null,
    val endedCallId: Int? = null,
    val canAddCall: Boolean = false,
    val canMerge: Boolean = false,
    val canSwap: Boolean = false,
    val pending: String? = null,
    val pendingFailed: String? = null,
    val ringerSilenced: Boolean = false,
    val silencedBy: String? = null,
    val selectingAccount: Boolean = false,
    val accounts: List<AccountRef> = emptyList(),
    val calls: List<CallInfo> = emptyList(),
)

/**
 * Which call is in front, which one is held beside it, which one waits and which one just ended
 * (AD-24). Rust validates these ids against `calls` and never re-selects.
 */
data class Focus(
    val foreground: Int?,
    val waiting: Int?,
    val background: Int? = null,
    val ended: Int? = null,
) {
    companion object {
        private val LIVE = setOf("active", "dialing", "holding")

        /**
         * Over top-level calls only (a conference is one call). Foreground: first active, else
         * dialing, else holding (lowest id), else ringing, else a disconnected call (the ended hold,
         * only when nothing live and not ringing exists). A disconnected call beside a live one is
         * [ended]; [background] is the other non-ringing, non-disconnected call; [waiting] is the
         * lowest-id ringing call that is not the foreground. While an operation runs, [keep] (the
         * previous foreground) stays in front if it is still live.
         */
        fun of(calls: List<CallInfo>, keep: Int? = null): Focus {
            val top = calls.filter { it.parentId == null }
            fun first(state: String) = top.firstOrNull { it.state == state }?.id
            val live = top.filter { it.state in LIVE }
            val kept = keep?.takeIf { k -> live.any { it.id == k } }
            val foreground = kept ?: first("active") ?: first("dialing") ?: first("holding")
                ?: first("ringing") ?: first("disconnected")
            val waiting = top.firstOrNull { it.state == "ringing" && it.id != foreground }?.id
            val background = live.firstOrNull { it.id != foreground }?.id
            val anyLive = top.any { it.state in LIVE || it.state == "ringing" }
            val ended = if (anyLive) top.firstOrNull { it.state == "disconnected" && it.id != foreground }?.id else null
            return Focus(foreground, waiting, background, ended)
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
        if (data.audio.endpoints.isNotEmpty()) {
            val endpoints = JSONArray()
            for (e in data.audio.endpoints) endpoints.put(endpointJson(e))
            audio.put("endpoints", endpoints)
        }
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
        data.backgroundCallId?.let { out.put("background_call_id", it) }
        data.endedCallId?.let { out.put("ended_call_id", it) }
        if (data.canAddCall) out.put("can_add_call", true)
        if (data.canMerge) out.put("can_merge", true)
        if (data.canSwap) out.put("can_swap", true)
        data.pending?.let { out.put("pending", it) }
        data.pendingFailed?.let { out.put("pending_failed", it) }
        if (data.ringerSilenced) out.put("ringer_silenced", true)
        data.silencedBy?.let { out.put("silenced_by", it) }
        if (data.selectingAccount) out.put("selecting_account", true)
        if (data.accounts.isNotEmpty()) {
            val accounts = JSONArray()
            for (a in data.accounts) {
                accounts.put(JSONObject().put("key", a.key).apply { if (a.label.isNotEmpty()) put("label", a.label) })
            }
            out.put("accounts", accounts)
        }
        return out.put("calls", calls)
    }

    fun toJson(data: SnapshotData): String = build(data).toString()

    /** Test-visible alias: the snapshot a structured input produces (story 14.0c). */
    fun buildSnapshot(data: SnapshotData): JSONObject = build(data)

    private fun endpointJson(e: EndpointInfo): JSONObject {
        val o = JSONObject().put("id", e.id).put("type", e.type)
        e.name?.let { o.put("name", it) }
        if (e.selected) o.put("selected", true)
        e.hint?.let { o.put("hint", it) }
        return o
    }

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
        c.parentId?.let { o.put("parent_id", it) }
        if (c.children.isNotEmpty()) o.put("children", JSONArray(c.children))
        if (c.caps != 0) o.put("caps", c.caps)
        if (c.props != 0) o.put("props", c.props)
        c.accountKey?.let { o.put("account_key", it) }
        c.displayNameAlt?.let { o.put("display_name_alt", it) }
        c.holdSinceMs?.let { o.put("hold_since_ms", it) }
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
            endpoints = a?.optJSONArray("endpoints")?.let { arr ->
                List(arr.length()) { i ->
                    val e = arr.getJSONObject(i)
                    EndpointInfo(
                        id = e.getString("id"),
                        type = e.optString("type", "unknown"),
                        name = if (e.has("name")) e.getString("name") else null,
                        selected = e.optBoolean("selected", false),
                        hint = if (e.has("hint")) e.getString("hint") else null,
                    )
                }
            } ?: emptyList(),
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
                parentId = if (c.has("parent_id")) c.getInt("parent_id") else null,
                children = c.optJSONArray("children")?.let { ch -> List(ch.length()) { ch.getInt(it) } } ?: emptyList(),
                caps = c.optInt("caps", 0),
                props = c.optInt("props", 0),
                accountKey = if (c.has("account_key")) c.getString("account_key") else null,
                displayNameAlt = if (c.has("display_name_alt")) c.getString("display_name_alt") else null,
                holdSinceMs = if (c.has("hold_since_ms")) c.getLong("hold_since_ms") else null,
            )
        }
        return SnapshotData(
            seq = o.optLong("seq", 0),
            nowMs = o.optLong("now_ms", 0),
            keyguardLocked = o.optBoolean("keyguard_locked", true),
            audio = audio,
            foregroundCallId = if (o.has("foreground_call_id")) o.getInt("foreground_call_id") else null,
            waitingCallId = if (o.has("waiting_call_id")) o.getInt("waiting_call_id") else null,
            backgroundCallId = if (o.has("background_call_id")) o.getInt("background_call_id") else null,
            endedCallId = if (o.has("ended_call_id")) o.getInt("ended_call_id") else null,
            canAddCall = o.optBoolean("can_add_call", false),
            canMerge = o.optBoolean("can_merge", false),
            canSwap = o.optBoolean("can_swap", false),
            pending = if (o.has("pending")) o.getString("pending") else null,
            pendingFailed = if (o.has("pending_failed")) o.getString("pending_failed") else null,
            ringerSilenced = o.optBoolean("ringer_silenced", false),
            silencedBy = if (o.has("silenced_by")) o.getString("silenced_by") else null,
            selectingAccount = o.optBoolean("selecting_account", false),
            accounts = o.optJSONArray("accounts")?.let { arr ->
                List(arr.length()) { i -> arr.getJSONObject(i).let { AccountRef(it.getString("key"), it.optString("label", "")) } }
            } ?: emptyList(),
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
        /** Opaque `"{component}#{handle id}"` of the account the call used (AD-26); null when unknown. */
        val accountKey: String? = null,
        /** `user`, `system` or `unknown` for a blocked row (story 17.2); null for any other. */
        val blockReason: String? = null,
    )

    data class PhoneRow(
        val contactId: Long,
        val displayName: String,
        val numberRaw: String,
        val label: String? = null,
        val superPrimary: Boolean = false,
        val starred: Boolean = false,
        val photoUri: String? = null,
        val lookupKey: String? = null,
        val displayNameAlt: String? = null,
        val sortKey: String? = null,
        val sortKeyAlt: String? = null,
    )

    fun error(code: String): String =
        JSONObject().put("v", WIRE_VERSION).put("ok", false).put("error", code).toString()

    fun ok(data: JSONObject): String =
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
            r.accountKey?.let { o.put("account_key", it) }
            r.blockReason?.let { o.put("block_reason", it) }
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
            first.lookupKey?.let { o.put("lookup_key", it) }
            first.displayNameAlt?.takeIf { it.isNotEmpty() }?.let { o.put("display_name_alt", it) }
            first.sortKey?.takeIf { it.isNotEmpty() }?.let { o.put("sort_key", it) }
            first.sortKeyAlt?.takeIf { it.isNotEmpty() }?.let { o.put("sort_key_alt", it) }
            contacts.put(o)
        }
        return ok(JSONObject().put("contacts", contacts))
    }
}
