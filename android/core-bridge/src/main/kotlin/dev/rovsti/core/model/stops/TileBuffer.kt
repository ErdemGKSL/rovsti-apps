package dev.rovsti.core.model.stops

import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError

/**
 * A decoded map tile, the Kotlin reader of the `RVT1` buffer (`docs/kotlin-ui/tile-buffer.md`). Points are kept as
 * flat `FloatArray`s (`x0, y0, x1, y1, ...`, tile units) so a 20 000-vertex tile costs a handful of allocations.
 */
class TileBuffer(
    val flags: Int,
    val z: Int,
    val x: Int,
    val y: Int,
    /** Tile units per side. */
    val extent: Int,
    /** The colour under the tile as `0xRRGGBBAA`. */
    val background: Int,
    /** Records in paint order, bottom first: draw them in this order. */
    val classes: List<TileClass>,
) {
    val dark: Boolean get() = flags and FLAG_DARK != 0
    val cacheOnly: Boolean get() = flags and FLAG_CACHE_ONLY != 0

    /** Points of the non-casing records, the same number as `TileGeometry::vertices` in Rust. */
    val vertexCount: Int get() = classes.filterNot { it.casing }.sumOf { it.pointCount }

    enum class Kind { Polygon, Line }

    /** One record: a fill or a stroke of one paint class. */
    class TileClass(
        /** Stable class number without the casing bit. */
        val paintId: Int,
        /** The record is the casing (outline) pass of [paintId]. */
        val casing: Boolean,
        /** `0xRRGGBBAA`. */
        val rgba: Int,
        /** Line width in px at display zoom 14; 0 for fills. */
        val width14: Float,
        val kind: Kind,
        /** Polygon: each polygon is an exterior ring (positive [ringArea2]) followed by its holes (negative). */
        val parts: List<FloatArray>,
    ) {
        val pointCount: Int get() = parts.sumOf { it.size / 2 }

        /** The colour as an Android `ARGB_8888` int. */
        val argb: Int get() = rgbaToArgb(rgba)
    }

    companion object {
        const val FLAG_DARK = 1
        const val FLAG_CACHE_ONLY = 2
        private const val CASING_BIT = 0x8000
        private const val VERSION = 1
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())

        /** `0xRRGGBBAA` to `0xAARRGGBB`. */
        fun rgbaToArgb(rgba: Int): Int = (rgba shl 24) or (rgba ushr 8)

        /** Doubled signed area of a ring (y down): positive is an exterior ring, negative a hole. */
        fun ringArea2(ring: FloatArray): Double {
            val n = ring.size / 2
            if (n < 3) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val j = (i + 1) % n
                sum += ring[2 * i].toDouble() * ring[2 * j + 1] - ring[2 * j].toDouble() * ring[2 * i + 1]
            }
            return sum
        }

        /** Whether [bytes] is a buffer (an error envelope is JSON and starts with `{`). */
        fun isBuffer(bytes: ByteArray): Boolean = bytes.size >= 4 && (0 until 4).all { bytes[it] == MAGIC[it] }

        /**
         * Decodes the answer of the `tile` export: a buffer, or the error envelope Rust answered instead. A damaged
         * buffer is [RustError.Parse].
         */
        fun parse(bytes: ByteArray?): Outcome<TileBuffer> =
            when {
                bytes == null -> {
                    Outcome.Err(RustError.Panic("native call returned no tile"))
                }

                isBuffer(bytes) -> {
                    decodeOrError(bytes)
                }

                else -> {
                    Envelope.decode(bytes).let {
                        it as? Outcome.Err
                            ?: Outcome.Err(RustError.Parse("tile is not a buffer"))
                    }
                }
            }

        private fun decodeOrError(bytes: ByteArray): Outcome<TileBuffer> =
            try {
                Outcome.Ok(decode(bytes))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(RustError.Parse("tile buffer: ${e.message}"))
            }

        /** Decodes a buffer. Throws [IllegalArgumentException] for anything that is not a well-formed v1 buffer. */
        fun decode(bytes: ByteArray): TileBuffer {
            require(isBuffer(bytes)) { "not a tile buffer (magic is not RVT1)" }
            val r = Reader(bytes, 4)
            val version = r.u16()
            require(version == VERSION) { "unsupported tile buffer version $version" }
            val flags = r.u16()
            val z = r.u32()
            val x = r.u32()
            val y = r.u32()
            val extent = r.u32()
            val background = r.u32()
            val count = r.u16()
            val classes = ArrayList<TileClass>(count)
            repeat(count) {
                val id = r.u16()
                val rgba = r.u32()
                val width = Float.fromBits(r.u32())
                val kind =
                    when (val k = r.u8()) {
                        0 -> Kind.Polygon
                        1 -> Kind.Line
                        else -> throw IllegalArgumentException("unknown record kind $k")
                    }
                val partCount = r.u32()
                require(partCount >= 0 && partCount <= r.remaining() / 4) { "the tile buffer is cut short" }
                val parts = ArrayList<FloatArray>(partCount)
                repeat(partCount) {
                    val n = r.u32()
                    require(n >= 0 && n <= r.remaining() / 8) { "the tile buffer is cut short" }
                    parts += FloatArray(2 * n) { Float.fromBits(r.u32()) }
                }
                classes += TileClass(id and CASING_BIT.inv(), id and CASING_BIT != 0, rgba, width, kind, parts)
            }
            return TileBuffer(flags, z, x, y, extent, background, classes)
        }
    }

    private class Reader(
        private val bytes: ByteArray,
        private var at: Int,
    ) {
        fun remaining() = bytes.size - at

        private fun need(n: Int) = require(n <= remaining()) { "the tile buffer is cut short" }

        fun u8(): Int {
            need(1)
            return bytes[at++].toInt() and 0xFF
        }

        fun u16(): Int {
            need(2)
            val v = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
            at += 2
            return v
        }

        /** An unsigned 32-bit number as an Int; colours keep their bits, counts must fit in 31. */
        fun u32(): Int {
            need(4)
            val v =
                (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return v
        }
    }
}
