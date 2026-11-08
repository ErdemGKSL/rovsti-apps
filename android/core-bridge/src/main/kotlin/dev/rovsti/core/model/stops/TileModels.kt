@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import dev.rovsti.core.Envelope
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

// `tiles.*` (E2.13): the map tile source, its cache and the offline area download. The tile itself is the binary
// buffer of `docs/kotlin-ui/tile-buffer.md` ([TileBuffer]); these are the JSON ops around it. See
// `crates/rovsti_ffi/src/ops/tiles.rs`.

@Serializable
data class TilesOpenRequest(
    /** The tile URL template with `{z}`, `{x}`, `{y}`; null is the public VersaTiles server. */
    @SerialName("server_url") @EncodeDefault(NEVER) val serverUrl: String? = null,
    /** Cap of the per-server cache in bytes; null is 128 MB. */
    @SerialName("cache_cap") @EncodeDefault(NEVER) val cacheCap: Long? = null,
    /** The cache root; null is `<cache dir>/tiles`. */
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class TilesOpenAnswer(
    val handle: Long,
    /** The directory name of this server's cache. */
    val key: String,
    val template: String,
    @SerialName("is_default") val isDefault: Boolean,
    @SerialName("cap_bytes") val capBytes: Long,
    @SerialName("cached_bytes") val cachedBytes: Long,
    @SerialName("cached_tiles") val cachedTiles: Int,
)

@Serializable
data class TilesHandleRequest(
    val handle: Long,
)

@Serializable
data class TilesUsageAnswer(
    /** Bytes of every server under the cache root. */
    @SerialName("total_bytes") val totalBytes: Long,
    /** Bytes and tiles of the open server. */
    @SerialName("source_bytes") val sourceBytes: Long,
    @SerialName("source_tiles") val sourceTiles: Int,
    @SerialName("cap_bytes") val capBytes: Long,
)

@Serializable
data class TileSourceRow(
    val key: String,
    val bytes: Long,
    /** The server the handle is open on. */
    val current: Boolean,
)

@Serializable
data class TilesSourcesAnswer(
    /** Biggest first. */
    val sources: List<TileSourceRow>,
)

@Serializable
data class TilesFreedAnswer(
    @SerialName("freed_bytes") val freedBytes: Long,
)

@Serializable
data class TilesClearExceptRequest(
    val handle: Long,
    /** The server directory to keep; null keeps the open server. */
    @SerialName("keep_key") @EncodeDefault(NEVER) val keepKey: String? = null,
)

@Serializable
data class TilesSetCapRequest(
    val handle: Long,
    @SerialName("cap_bytes") val capBytes: Long,
)

@Serializable
data class TilesTestServerRequest(
    @SerialName("server_url") val serverUrl: String,
)

@Serializable
data class TilesTestServerAnswer(
    /** Layers of tile 0/0/0. */
    val layers: Int,
)

/** A slippy-map tile. */
@Serializable
data class TileRef(
    val z: Int,
    val x: Int,
    val y: Int,
)

@Serializable
data class TilesStandInRequest(
    val want: TileRef,
    val have: List<TileRef>,
)

@Serializable
data class TilesStandInAnswer(
    /** The wanted tile itself, else its nearest loaded ancestor (up to four zooms up), else null. */
    val tile: TileRef?,
)

@Serializable
data class TilesEncodeMvtRequest(
    /** A Mapbox vector tile (gzip is accepted), base64. */
    val mvt: String,
    val z: Int,
    val x: Int,
    val y: Int,
    @EncodeDefault(NEVER) val flags: Int? = null,
)

@Serializable
data class TilesEncodeMvtAnswer(
    /** The tile buffer, base64. */
    val buffer: String,
)

@Serializable
data class TilesBbox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/** The tiles around a crossing, for a trip that must work offline. */
@Serializable
data class TilesCircle(
    val center: Coord,
    @SerialName("radius_m") val radiusM: Double,
    @SerialName("margin_m") @EncodeDefault(NEVER) val marginM: Double? = null,
    @EncodeDefault(NEVER) val budget: Int? = null,
)

@Serializable
data class TilesZooms(
    @EncodeDefault(NEVER) val min: Int? = null,
    @EncodeDefault(NEVER) val max: Int? = null,
)

/** An area is a [bbox] or a [circle], not both. */
@Serializable
data class TilesOfflineRequest(
    val handle: Long,
    @EncodeDefault(NEVER) val bbox: TilesBbox? = null,
    @EncodeDefault(NEVER) val circle: TilesCircle? = null,
    @EncodeDefault(NEVER) val zooms: TilesZooms? = null,
)

@Serializable
data class TilesOfflineAnswer(
    /** The job: cancel it with `tiles.offline_cancel`, release it when done. */
    val handle: Long,
    val total: Int,
    /** The highest zoom the budget allowed. */
    @SerialName("top_zoom") val topZoom: Int,
    /** The area was cut short of the wanted zoom by the tile budget. */
    val capped: Boolean,
    @SerialName("estimate_bytes") val estimateBytes: Long,
)

@Serializable
data class TilesCancelRequest(
    val job: Long,
)

@Serializable
data class TilesCancelAnswer(
    /** Tiles saved so far (they stay in the cache). */
    val saved: Int,
    val total: Int,
)

/** The body of a `tiles.offline` event. */
@Serializable
data class OfflineProgress(
    val job: Long,
    /** Tiles handled, saved or failed. */
    val done: Int,
    val saved: Int,
    val failed: Int,
    val total: Int,
    val fraction: Float,
    /** "Downloading 12 of 120 tiles", or the closing sentence when [finished]. */
    val text: String,
    val finished: Boolean,
    val cancelled: Boolean,
) {
    companion object {
        /** The event body, or null for a body that is not a progress report. */
        fun fromJson(bytes: ByteArray): OfflineProgress? =
            try {
                Envelope.json.decodeFromString(serializer(), bytes.decodeToString())
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
    }
}
