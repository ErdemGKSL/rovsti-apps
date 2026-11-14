package dev.rosty.stops.map

import dev.rosty.core.model.stops.TileBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64

/** The Rust contract fixtures (`crates/rosty_ffi/fixtures`), the cross-check data of the Kotlin map maths. */
object MapFixtures {
    private val dir: File by lazy {
        val path = System.getProperty("rosty.ffi.fixtures") ?: error("rosty.ffi.fixtures is not set (build.gradle.kts)")
        File(path).also { check(it.isDirectory) { "no fixtures at $it" } }
    }

    /** A request with its answer; [name] is the file name without `.request.json`. */
    class Pair(
        val name: String,
        val request: JsonObject,
        val answer: JsonObject,
    ) {
        /** The `ok` body; fails for an error answer. */
        val ok: JsonObject get() = answer["ok"]?.jsonObject ?: error("$name answers an error")
        val isError: Boolean get() = "err" in answer
    }

    /** Every fixture of one op: `view.project` is `view.project.request.json` and `view.project.x.request.json`. */
    fun pairs(op: String): List<Pair> {
        val found =
            dir
                .listFiles { f -> f.name.startsWith("$op.") && f.name.endsWith(".request.json") }
                .orEmpty()
                .sortedBy { it.name }
                .map { request ->
                    val name = request.name.removeSuffix(".request.json")
                    Pair(name, parse(request), parse(File(dir, "$name.answer.json")))
                }
        check(found.isNotEmpty()) { "no $op fixtures in $dir" }
        return found
    }

    private fun parse(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    /** The real city tile 14/9520/6130 as Rust encoded it (`tiles.encode_mvt` fixtures), light or dark palette. */
    fun cityTile(dark: Boolean = false): TileBuffer {
        val name = if (dark) "tiles.encode_mvt.dark.answer.json" else "tiles.encode_mvt.answer.json"
        val b64 = parse(File(dir, name))["ok"]!!.jsonObject["buffer"]!!.jsonPrimitive.content
        return TileBuffer.decode(Base64.getDecoder().decode(b64))
    }

    const val CITY_Z = 14
    const val CITY_X = 9520
    const val CITY_Y = 6130

    /** The middle of the city tile. */
    val cityLat: Double get() = MapMath.fracToLat(CITY_Z, CITY_Y + 0.5)
    val cityLon: Double get() = MapMath.fracToLon(CITY_Z, CITY_X + 0.5)
}

fun JsonElement.d(key: String): Double = jsonObject[key]!!.jsonPrimitive.double

fun JsonElement.obj(key: String): JsonObject = jsonObject[key]!!.jsonObject

fun JsonElement.arr(key: String): JsonArray = jsonObject[key]?.jsonArray ?: JsonArray(emptyList())

fun JsonElement.at(i: Int): Double = jsonArray[i].jsonPrimitive.double
