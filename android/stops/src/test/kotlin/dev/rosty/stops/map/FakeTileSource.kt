package dev.rosty.stops.map

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.stops.TileBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * A [TileSource] for tests and screenshots: serves decoded buffers from memory, without Rust, a cache or a network.
 * It records what it was asked and can be told to fail, to be slow, to hold every answer, or to miss tiles.
 *
 * [city] serves the real fixture tile 14/9520/6130 (`MapFixtures.cityTile`) in both palettes and nothing else, which
 * is what the Stops screens draw under their chrome in tests.
 */
class FakeTileSource(
    /** The tile for a packed [TileKey] and palette, or null when the source does not have it. */
    private val tiles: (key: Long, dark: Boolean) -> TileBuffer?,
) : TileSource {
    /** Every request in order, as packed [TileKey]s. */
    val requests = ArrayList<Long>()

    /** The palette of each request, in the same order. */
    val requestedDark = ArrayList<Boolean>()

    /** Requests that were cancelled before they answered. */
    val cancelled = ArrayList<Long>()

    /** Requests that answered (a tile or an error). */
    val answered = ArrayList<Long>()

    /** Every request answers a network error. */
    var failing = false

    /** Each answer takes this long (virtual time under a test dispatcher). */
    var delayMs = 0L

    /** Tiles the source pretends not to have, on top of what [tiles] lacks. */
    val missing = HashSet<Long>()

    private var gate: CompletableDeferred<Unit>? = null

    /** From now on requests wait until [release]. */
    fun hold() {
        if (gate == null) gate = CompletableDeferred()
    }

    /** Lets held requests answer, and later ones pass. */
    fun release() {
        gate?.complete(Unit)
        gate = null
    }

    val inFlight: Int get() = requests.size - answered.size - cancelled.size

    override suspend fun tile(
        z: Int,
        x: Int,
        y: Int,
        dark: Boolean,
    ): Outcome<TileBuffer> {
        val key = TileKey.of(z, x, y)
        requests += key
        requestedDark += dark
        try {
            gate?.await()
            if (delayMs > 0) delay(delayMs)
        } catch (e: CancellationException) {
            cancelled += key
            throw e
        }
        answered += key
        if (failing) return Outcome.Err(RustError.Network("the fake tile source is failing"))
        val buffer = if (key in missing) null else tiles(key, dark)
        return if (buffer !=
            null
        ) {
            Outcome.Ok(buffer)
        } else {
            Outcome.Err(RustError.NotFound("no tile ${TileKey.describe(key)}"))
        }
    }

    companion object {
        val CITY: Long = TileKey.of(MapFixtures.CITY_Z, MapFixtures.CITY_X, MapFixtures.CITY_Y)

        /** The fixture city tile, light and dark; every other tile is missing. */
        fun city(): FakeTileSource {
            val light = MapFixtures.cityTile(dark = false)
            val dark = MapFixtures.cityTile(dark = true)
            return FakeTileSource { key, isDark -> if (key == CITY) (if (isDark) dark else light) else null }
        }

        /** No tile anywhere: the map shows its radar background. */
        fun empty(): FakeTileSource = FakeTileSource { _, _ -> null }

        /**
         * A made-up tile for every id: a plain background and one filled square, different per tile, so tests can tell
         * tiles apart without the fixture.
         */
        fun synthetic(): FakeTileSource = FakeTileSource { key, dark -> syntheticTile(key, dark) }

        fun syntheticTile(
            key: Long,
            dark: Boolean,
        ): TileBuffer {
            val square = floatArrayOf(1024f, 1024f, 3072f, 1024f, 3072f, 3072f, 1024f, 3072f)
            val fill = TileBuffer.TileClass(20, false, colourOf(key), 0f, TileBuffer.Kind.Polygon, listOf(square))
            val flags = if (dark) TileBuffer.FLAG_DARK else 0
            val background = if (dark) 0x282725FF else 0xF8F4F0FF.toInt()
            return TileBuffer(flags, TileKey.z(key), TileKey.x(key), TileKey.y(key), 4096, background, listOf(fill))
        }

        /** An opaque `0xRRGGBBAA` colour that depends on the tile. */
        fun colourOf(key: Long): Int {
            val h = (key * -0x61c8864680b583ebL ushr 40).toInt()
            return (h shl 8) or 0xFF
        }
    }
}
