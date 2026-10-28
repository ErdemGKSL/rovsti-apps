package dev.rovsti.bridge

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject

/**
 * Platform probes behind the hidden Diagnostics page (AD-36). Each probe returns one
 * `pass`, `fail` or `unknown` line per check with a short reason, and runs off the UI thread.
 * Logcat tag `RovstiSpike`: ids and booleans only, never a number or a name.
 */
object SpikeProbes {
    const val TAG = "RovstiSpike"

    /** Probe groups the page lists; a group is inert ("Not built yet") until its spike registers it. */
    val GROUPS = listOf("audio", "calls", "sim", "backup", "blocking", "reject_text", "silence", "voicemail", "emergency", "contacts", "call_log", "system")

    private val registered = setOf("audio", "calls", "sim", "system", "blocking", "reject_text", "silence", "header", "events")

    fun isRegistered(probe: String) = probe in registered

    fun run(probe: String, context: Context?, calls: CallsApi?): String = when (probe) {
        "header" -> header(context)
        "events" -> (calls?.eventDump() ?: emptyList()).joinToString("\n")
        "audio" -> audio(context, calls)
        "calls" -> calls(calls)
        "sim" -> sim(context, calls)
        "system" -> system(context)
        "blocking" -> context?.let { Epic17Probes.blockingLines(Epic17Probes.readBlocking(it, calls?.blockGuardOn() ?: false, calls?.blockGuardHits() ?: 0)).joinToString("\n") } ?: "unknown: no context"
        "reject_text" -> rejectText(calls)
        "silence" -> silence(calls)
        else -> "unknown: Not built yet"
    }

    private fun header(context: Context?): String {
        val version = try {
            context?.packageManager?.getPackageInfo(context.packageName, 0)?.versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val hyper = systemProperty("ro.mi.os.version.name")
        return "app=${version ?: "?"} model=${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE}" +
            " sdk=${android.os.Build.VERSION.SDK_INT} hyperos=${hyper.ifEmpty { "n/a" }}" +
            " date=${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())}"
    }

    private fun systemProperty(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
    } catch (_: ReflectiveOperationException) {
        ""
    } catch (_: RuntimeException) {
        ""
    }

    // ---- S5: SIM accounts ------------------------------------------------------------------------

    /** The account list, and the last placed call's key against the key built from its call-log row. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun sim(context: Context?, calls: CallsApi?): String {
        if (context == null) return "unknown: no context"
        val parsed = try {
            JSONObject(SimAccounts.collect(context))
        } catch (_: org.json.JSONException) {
            return "unknown: accounts unreadable"
        }
        if (!parsed.optBoolean("ok")) return "unknown: accounts ${parsed.optString("error", "unavailable")} (grant Phone access first)"
        val data = parsed.getJSONObject("data")
        val list = data.getJSONArray("accounts")
        val accounts = (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            SimAccounts.Account(
                key = o.getString("key"),
                label = o.optString("label"),
                slotIndex = if (o.has("slot_index")) o.getInt("slot_index") else null,
                colorArgb = o.optInt("color_argb"),
                sim = o.optBoolean("sim"),
                enabled = o.optBoolean("enabled", true),
            )
        }
        val defaultKey = if (data.has("default_key")) data.getString("default_key") else null
        val placed = lastPlacedKey(calls)
        return SimProbe.lines(accounts, defaultKey, placed, newestLogKey(context)).joinToString("\n")
    }

    /** The account key of the foreground (or newest) call in the last snapshot. */
    private fun lastPlacedKey(calls: CallsApi?): String? = try {
        val snap = SnapshotJson.parse(JSONObject(calls?.snapshot() ?: return null))
        snap.calls.firstOrNull { it.id == snap.foregroundCallId }?.accountKey ?: snap.calls.lastOrNull()?.accountKey
    } catch (_: org.json.JSONException) {
        null
    }

    /** The key built from the newest outgoing call-log row's account columns. */
    private fun newestLogKey(context: Context): String? = try {
        val args = android.os.Bundle().apply {
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${android.provider.CallLog.Calls.DATE} DESC")
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${android.provider.CallLog.Calls.TYPE} = ${android.provider.CallLog.Calls.OUTGOING_TYPE}")
        }
        context.contentResolver.query(
            android.provider.CallLog.Calls.CONTENT_URI,
            arrayOf(android.provider.CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, android.provider.CallLog.Calls.PHONE_ACCOUNT_ID),
            args, null,
        )?.use { if (it.moveToFirst()) SimAccounts.keyOf(it.getString(0), it.getString(1)) else null }
    } catch (_: SecurityException) {
        null
    }

    // ---- S14: vibration (system part) ----------------------------------------------------------------

    /** Counts down 5 s so the phone can go to the ear with the screen off, then vibrates once. */
    private fun system(context: Context?): String {
        if (context == null) return "unknown: no context"
        val vibrator = context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        val values = SettingsMirror.values
        val touch = try {
            context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        } catch (_: RuntimeException) {
            false
        }
        Thread.sleep(5000)
        val vibrated = Haptics.click(context)
        val tones = SettingsMirror.systemKeypadTones(context)
        return (SimProbe.vibrateLines(vibrator?.hasVibrator() == true, values.vibrateConnect, values.vibrateEnd, touch, vibrated) +
            "info talkback_touch_exploration=$touch dtmf_tone_when_dialing=$tones" +
            "unknown: announcements and custom actions fire (turn TalkBack on and run the route, conference, reply and reorder flows; note it in docs/spikes/s14-system.md)").joinToString("\n")
    }

    // ---- S7: reject text, S8: silence ------------------------------------------------------------------

    /** Waits up to 40 s for a ringing call, then declines it with "test" through the Telecom path once. */
    private fun rejectText(calls: CallsApi?): String {
        if (calls == null) return "unknown: no call registry"
        return Epic17Probes.rejectLines(calls.probeRejectText(RejectProbeWindowMs)).joinToString("\n")
    }

    /** Watches up to 75 s and records, per ring, whether and how the ringer was silenced. */
    private fun silence(calls: CallsApi?): String {
        if (calls == null) return "unknown: no call registry"
        val attempts = ArrayList<Epic17Probes.Attempt>()
        var ringing = false
        var silenced = false
        var by: String? = null
        val end = System.currentTimeMillis() + 75_000
        while (System.currentTimeMillis() < end && attempts.size < 3) {
            val snap = try { SnapshotJson.parse(JSONObject(calls.snapshot())) } catch (_: org.json.JSONException) { null }
            val now = snap?.calls?.any { it.state == "ringing" } == true
            if (now) {
                ringing = true
                if (snap!!.ringerSilenced) { silenced = true; by = snap.silencedBy }
            } else if (ringing) {
                attempts.add(Epic17Probes.Attempt(silenced, by))
                ringing = false; silenced = false; by = null
            }
            Thread.sleep(150)
        }
        if (ringing) attempts.add(Epic17Probes.Attempt(silenced, by))
        return Epic17Probes.silenceLines(attempts, calls.silenceCallbacks()).joinToString("\n")
    }

    private const val RejectProbeWindowMs = 40_000L

    // ---- S4: multi-call ------------------------------------------------------------------------

    /** One snapshot read: caps masks, props, conferenceable and child counts, then the four S4 criteria. */
    private fun calls(calls: CallsApi?): String {
        if (calls == null) return "unknown: no call registry"
        val snap = try {
            SnapshotJson.parse(JSONObject(calls.snapshot()))
        } catch (_: org.json.JSONException) {
            return "unknown: no snapshot yet"
        }
        val out = ArrayList<String>()
        if (snap.calls.isEmpty()) out.add("unknown: no calls (start a call first, then run the probe)")
        val facts = calls.multiCallFacts()
        out.addAll(MultiCallProbe.masks(snap.calls, facts))
        out.add("flags can_add=${snap.canAddCall} can_merge=${snap.canMerge} can_swap=${snap.canSwap} pending=${snap.pending ?: "none"} failed=${snap.pendingFailed ?: "none"}")
        out.addAll(MultiCallProbe.verdicts(snap.calls, facts))
        return out.joinToString("\n")
    }

    // ---- S3: audio endpoints -----------------------------------------------------------

    /** One second of the endpoint list as the snapshot carried it. */
    data class Sample(val endpoints: List<EndpointInfo>)

    /** 20 seconds, one sample a second (spike S3). */
    private fun audio(context: Context?, calls: CallsApi?): String {
        if (calls == null) return "unknown: no call registry"
        val samples = ArrayList<Sample>()
        val out = ArrayList<String>()
        repeat(AUDIO_SECONDS) { second ->
            val sample = sampleOf(calls.snapshot())
            samples.add(sample)
            out.add(sampleLine(second, sample))
            android.util.Log.d(TAG, out.last())
            if (second < AUDIO_SECONDS - 1) Thread.sleep(1000)
        }
        val denied = context?.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        out.addAll(verdicts(samples, denied, calls.routeLatencyMs()))
        return out.joinToString("\n")
    }

    fun sampleOf(snapshotJson: String): Sample {
        val audio = try {
            JSONObject(snapshotJson).optJSONObject("audio")
        } catch (_: org.json.JSONException) {
            null
        }
        return Sample(SnapshotJson.parse(JSONObject().put("audio", audio ?: JSONObject())).audio.endpoints)
    }

    /** `t=3 earpiece len=0 tail=0001 sel=false; bluetooth len=11 ...` (no names, only lengths). */
    fun sampleLine(second: Int, sample: Sample): String {
        if (sample.endpoints.isEmpty()) return "t=$second no endpoints"
        return "t=$second " + sample.endpoints.joinToString("; ") {
            "${it.type} len=${it.name?.length ?: 0} tail=${it.id.takeLast(4)} sel=${it.selected}"
        }
    }

    fun verdicts(samples: List<Sample>, permissionDenied: Boolean, latencyMs: Long): List<String> {
        val out = ArrayList<String>()
        val last = samples.lastOrNull()?.endpoints ?: emptyList()
        if (last.isEmpty()) {
            out.add("unknown: no endpoints seen (start a call first, then run the probe)")
        }
        val named = last.filter { it.type == "bluetooth" || it.type == "wired_headset" }
        out.add(
            when {
                named.isEmpty() -> "unknown: names present with BLUETOOTH_CONNECT denied (connect earbuds or a wired headset)"
                !permissionDenied -> "unknown: names present with BLUETOOTH_CONNECT denied (permission is granted; deny it in App info and rerun)"
                named.all { !it.name.isNullOrEmpty() } -> "pass: names present with BLUETOOTH_CONNECT denied"
                else -> "fail: names present with BLUETOOTH_CONNECT denied"
            },
        )
        out.add(idStability(samples))
        out.add(
            when {
                latencyMs < 0 -> "unknown: selected moved within 500 ms after a route request (tap a device in the picker, then rerun)"
                latencyMs <= 500 -> "pass: selected moved within 500 ms after a route request (${latencyMs} ms)"
                else -> "fail: selected moved within 500 ms after a route request (${latencyMs} ms)"
            },
        )
        return out
    }

    /** An endpoint present in the first and the last sample (same type and name length) must keep its id. */
    fun idStability(samples: List<Sample>): String {
        val first = samples.firstOrNull()?.endpoints?.filter { it.type == "bluetooth" || it.type == "wired_headset" } ?: emptyList()
        val last = samples.lastOrNull()?.endpoints ?: emptyList()
        if (first.isEmpty()) return "unknown: id stable while connected (no device was connected at the start)"
        val stable = samples.all { s -> first.all { f -> s.endpoints.any { it.id == f.id } || s.endpoints.none { it.type == f.type && it.name == f.name } } }
        val survivors = first.count { f -> last.any { it.type == f.type && it.name == f.name } }
        return when {
            survivors == 0 -> "unknown: id stable while connected (the device left during the run)"
            stable -> "pass: id stable while connected"
            else -> "fail: id stable while connected"
        }
    }

    const val AUDIO_SECONDS = 20
}
