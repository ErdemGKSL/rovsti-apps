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
    val GROUPS = listOf("audio", "calls", "sim", "blocking", "reject_text", "silence", "voicemail", "emergency", "contacts", "call_log", "system")

    private val registered = setOf("audio", "header", "events")

    fun isRegistered(probe: String) = probe in registered

    fun run(probe: String, context: Context?, calls: CallsApi?): String = when (probe) {
        "header" -> header(context)
        "events" -> (calls?.eventDump() ?: emptyList()).joinToString("\n")
        "audio" -> audio(context, calls)
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
