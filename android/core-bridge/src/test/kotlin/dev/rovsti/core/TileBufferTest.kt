package dev.rovsti.core

import dev.rovsti.core.model.stops.TileBuffer
import dev.rovsti.core.model.stops.TileBuffer.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * E2.13 AC3: the Kotlin reader of the `RVT1` buffer, checked on the real tile (z14 x9520 y6130) that Rust encoded
 * into the contract fixtures `tiles.encode_mvt*.answer.json`. Plain JVM test: no native library.
 */
class TileBufferTest {
    private val dir = File(System.getProperty("rovsti.contract") ?: error("rovsti.contract not set"))

    private fun buffer(stem: String): ByteArray {
        val answer = Json.parseToJsonElement(File(dir, "$stem.answer.json").readText())
        return Base64.getDecoder().decode(
            answer.jsonObject
                .getValue("ok")
                .jsonObject
                .getValue("buffer")
                .jsonPrimitive.content,
        )
    }

    private val tile: TileBuffer by lazy { TileBuffer.decode(buffer("tiles.encode_mvt")) }

    @Test
    fun theHeaderMatchesTheRequest() {
        val bytes = buffer("tiles.encode_mvt")
        assertTrue(TileBuffer.isBuffer(bytes))
        assertEquals("RVT1", bytes.copyOf(4).decodeToString())
        assertEquals(0, tile.flags)
        assertEquals(listOf(14, 9520, 6130), listOf(tile.z, tile.x, tile.y))
        assertEquals(4096, tile.extent)
        assertEquals(0xF8F4F0FF.toInt(), tile.background)
        assertEquals(0xFFF8F4F0.toInt(), TileBuffer.rgbaToArgb(tile.background))
        assertFalse(tile.dark)
        assertFalse(tile.cacheOnly)
    }

    @Test
    fun theClassesAreInPaintOrderWithTheirCasings() {
        assertEquals(15, tile.classes.size)
        assertEquals(5, tile.classes.count { it.casing })
        val ids = tile.classes.map { it.paintId }
        assertEquals(ids.sorted(), ids)
        val first = tile.classes.first()
        assertEquals(5, first.paintId)
        assertEquals(Kind.Polygon, first.kind)
        assertEquals(0xF5F1EDFF.toInt(), first.rgba)
        assertEquals(0f, first.width14, 0f)
        assertEquals(1, first.parts.size)
        assertEquals(21, first.pointCount)
        assertEquals(3621f, first.parts[0][0], 0f)
        assertEquals(184f, first.parts[0][1], 0f)
        // Motorway-like line: casing first and wider, then the line.
        val line = tile.classes.last()
        assertEquals(39, line.paintId)
        assertEquals(Kind.Line, line.kind)
        assertEquals(4f, line.width14, 0f)
        val casing = tile.classes[tile.classes.lastIndex - 1]
        assertTrue(casing.casing && casing.paintId == line.paintId && casing.width14 > line.width14)
    }

    @Test
    fun everyVertexIsThere() {
        assertEquals(1083, tile.vertexCount)
        assertTrue(tile.classes.all { c -> c.parts.all { it.size % 2 == 0 } })
    }

    @Test
    fun polygonsAreExteriorThenHolesByOrientation() {
        var polygons = 0
        var holes = 0
        for (c in tile.classes.filter { it.kind == Kind.Polygon }) {
            var open = false
            for (ring in c.parts) {
                val area = TileBuffer.ringArea2(ring)
                if (area > 0) {
                    polygons++
                    open = true
                } else {
                    assertTrue("a hole follows an exterior", area < 0 && open)
                    holes++
                }
            }
        }
        assertTrue(polygons > 100)
        assertTrue(holes >= 0)
    }

    @Test
    fun theDarkFixtureUsesTheEclipsePalette() {
        val dark = TileBuffer.decode(buffer("tiles.encode_mvt.dark"))
        assertTrue(dark.dark)
        assertNotEquals(tile.background, dark.background)
        assertEquals(tile.classes.size, dark.classes.size)
        assertEquals(tile.vertexCount, dark.vertexCount)
        assertNotEquals(tile.classes[0].rgba, dark.classes[0].rgba)
    }

    @Test
    fun anEmptyTileIsJustTheBackground() {
        val empty = TileBuffer.decode(buffer("tiles.encode_mvt.empty"))
        assertEquals(0, empty.classes.size)
        assertEquals(listOf(3, 1, 2), listOf(empty.z, empty.x, empty.y))
    }

    @Test
    fun parseTellsABufferFromAnErrorEnvelope() {
        assertTrue(TileBuffer.parse(buffer("tiles.encode_mvt")) is Outcome.Ok)
        val miss =
            """{"err":{"kind":"not_found","message":"The tile is not cached.","detail":{"reason":"not_cached"}}}"""
        val outcome = TileBuffer.parse(miss.encodeToByteArray())
        assertTrue(outcome is Outcome.Err && outcome.error is RustError.NotFound)
        assertTrue(TileBuffer.parse(null) is Outcome.Err)
        assertTrue(TileBuffer.parse("{\"ok\":1}".encodeToByteArray()) is Outcome.Err)
    }

    @Test
    fun aDamagedBufferIsRefusedNotTrusted() {
        val good = buffer("tiles.encode_mvt")
        for (cut in listOf(0, 3, 10, 29, 31, good.size / 2, good.size - 1)) {
            try {
                TileBuffer.decode(good.copyOf(cut))
                fail("a buffer cut to $cut bytes must not decode")
            } catch (_: IllegalArgumentException) {
            }
        }
        val badVersion = good.copyOf().also { it[4] = 2 }
        assertTrue(TileBuffer.parse(badVersion) is Outcome.Err)
        // A part that claims 2^31 points must not allocate them.
        val huge = good.copyOf()
        val firstPartAt = 30 + 15
        for (i in 0 until 4) huge[firstPartAt + i] = if (i == 3) 0x7F else 0xFF.toByte()
        assertTrue(TileBuffer.parse(huge) is Outcome.Err)
    }
}
