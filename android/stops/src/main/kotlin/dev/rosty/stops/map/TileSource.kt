package dev.rosty.stops.map

import dev.rosty.core.Outcome
import dev.rosty.core.model.stops.TileBuffer
import dev.rosty.core.ops.TileHandle

/**
 * Where the map gets its tiles (ARCH 8.4 step 2): one decoded, styled tile per call. The real source is
 * [HandleTileSource]; tests and screenshots use a fake that serves fixture buffers.
 *
 * A call may suspend for a download. Cancelling the caller cancels the request (a native call that already started
 * finishes on its own thread and its answer is dropped).
 */
fun interface TileSource {
    /** Tile [z]/[x]/[y] in the colorful palette, or the eclipse one when [dark]. */
    suspend fun tile(
        z: Int,
        x: Int,
        y: Int,
        dark: Boolean,
    ): Outcome<TileBuffer>
}

/** The tiles of an open native source: the disk cache or the user's tile server, decoded and styled by Rust. */
class HandleTileSource(
    private val handle: TileHandle,
    /** Never touch the network: a tile that is not cached is an error and draws as its stand-in. */
    private val cacheOnly: Boolean = false,
) : TileSource {
    override suspend fun tile(
        z: Int,
        x: Int,
        y: Int,
        dark: Boolean,
    ): Outcome<TileBuffer> = handle.tile(z, x, y, dark = dark, cacheOnly = cacheOnly)
}
