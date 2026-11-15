package dev.rosty.people

import dev.rosty.core.log.RostyLog
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** What WhatsApp answered for a number: its [name] (null: not on WhatsApp, or no name) and when it answered. */
data class LiveName(
    val name: String?,
    val atMs: Long,
)

/** Where [LiveWaNames] keeps its answers between runs, by the number's digits in international form. */
interface LiveNameStore {
    /** The stored answers; empty when there are none or they cannot be read. Never throws. */
    fun load(): Map<String, LiveName>

    /** Replaces the stored answers with [answers]. Never throws. */
    fun save(answers: Map<String, LiveName>)
}

/**
 * [LiveNameStore] as one small JSON file in the app's private storage (`{"v":1,"names":{"<digits>":{"name":…,
 * "at":…}}}`), written through a temporary file so a kill mid-write leaves the old answers. At most [MAX_ENTRIES]
 * answers are kept, the newest ones. A file that cannot be read counts as empty.
 */
class FileLiveNameStore(
    private val file: File,
) : LiveNameStore {
    override fun load(): Map<String, LiveName> =
        try {
            if (file.exists()) parse(file.readText()) else emptyMap()
        } catch (e: IOException) {
            RostyLog.w(TAG, "live names: not read", e)
            emptyMap()
        }

    override fun save(answers: Map<String, LiveName>) {
        try {
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            part.writeText(render(answers))
            if (!part.renameTo(file)) {
                part.copyTo(file, overwrite = true)
                part.delete()
            }
        } catch (e: IOException) {
            RostyLog.w(TAG, "live names: not written", e)
        }
    }

    companion object {
        private const val TAG = "people"
        private const val VERSION = 1

        /** How many answers the file keeps. */
        const val MAX_ENTRIES = 2_000

        /** Pure: the file's text for [answers], the newest [MAX_ENTRIES] of them. */
        fun render(answers: Map<String, LiveName>): String {
            val names = JSONObject()
            answers.entries
                .sortedByDescending { it.value.atMs }
                .take(MAX_ENTRIES)
                .forEach { (key, answer) ->
                    names.put(key, JSONObject().put("name", answer.name ?: JSONObject.NULL).put("at", answer.atMs))
                }
            return JSONObject().put("v", VERSION).put("names", names).toString()
        }

        /** Pure: the answers in [text]; empty for anything that is not this file's format. */
        fun parse(text: String): Map<String, LiveName> =
            try {
                val root = JSONObject(text)
                val names = root.optJSONObject("names")
                if (root.optInt("v") != VERSION || names == null) {
                    emptyMap()
                } else {
                    val out = HashMap<String, LiveName>()
                    for (key in names.keys()) {
                        val entry = names.optJSONObject(key) ?: continue
                        val at = entry.optLong("at", -1)
                        if (at < 0) continue
                        val name =
                            if (entry.isNull(
                                    "name",
                                )
                            ) {
                                null
                            } else {
                                entry.optString("name").takeIf { it.isNotBlank() }
                            }
                        out[key] = LiveName(name, at)
                    }
                    out
                }
            } catch (_: JSONException) {
                emptyMap()
            }
    }
}
