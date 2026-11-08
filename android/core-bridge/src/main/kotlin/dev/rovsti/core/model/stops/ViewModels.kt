@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.stops

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// `view.*` (E2.12): the map maths of `rovsti_geo::view` and the slippy tiles. The fixtures are the cross-check data
// for the Kotlin `MapCamera` (E7). See `crates/rovsti_ffi/src/ops/stops/view.rs`.

/** Pixels are relative to the viewport centre, x right and y down. */
@Serializable
data class ProjectRequest(
    val viewport: Viewport,
    @EncodeDefault(NEVER) val points: List<Coord>? = null,
    @EncodeDefault(NEVER) val pixels: List<List<Double>>? = null,
)

@Serializable
data class ProjectAnswer(
    /** The viewport with the scale clamped. */
    val viewport: Viewport,
    @SerialName("display_zoom") val displayZoom: Double,
    @SerialName("fetch_zoom") val fetchZoom: Int,
    val projected: List<List<Double>>,
    val unprojected: List<Coord>,
    @SerialName("min_metres_per_px") val minMetresPerPx: Double,
    @SerialName("max_metres_per_px") val maxMetresPerPx: Double,
    @SerialName("tile_display_px") val tileDisplayPx: Double,
)

/** One camera gesture. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ViewStep {
    @Serializable
    @SerialName("fit")
    data class Fit(
        val points: List<Coord>,
        val width: Double,
        val height: Double,
        val padding: Double,
    ) : ViewStep

    /** Zoom by [factor] (above 1 zooms in) keeping the coordinate under [focal] fixed. */
    @Serializable
    @SerialName("zoom_about")
    data class ZoomAbout(
        val factor: Double,
        val focal: List<Double>,
    ) : ViewStep

    @Serializable
    @SerialName("pan")
    data class Pan(
        val dx: Double,
        val dy: Double,
    ) : ViewStep

    @Serializable
    @SerialName("nearest")
    data class Nearest(
        val points: List<Coord>,
        val at: List<Double>,
        @SerialName("max_px") val maxPx: Double,
    ) : ViewStep
}

@Serializable
data class ViewStepRequest(
    val viewport: Viewport,
    val step: ViewStep,
)

@Serializable
data class ViewStepAnswer(
    val viewport: Viewport,
    @SerialName("display_zoom") val displayZoom: Double,
    val nearest: Int?,
)

@Serializable
data class ViewTilesRequest(
    val viewport: Viewport,
    val width: Double,
    val height: Double,
    @SerialName("max_tiles") val maxTiles: Int,
)

@Serializable
data class TileId(
    val z: Int,
    val x: Long,
    val y: Long,
)

/** A tile with its north-west and south-east corners. */
@Serializable
data class TileRow(
    val z: Int,
    val x: Long,
    val y: Long,
    val nw: Coord,
    val se: Coord,
)

@Serializable
data class ViewTilesAnswer(
    val zoom: Int,
    @SerialName("display_zoom") val displayZoom: Double,
    val count: Int,
    /** Nearest the centre first. */
    val tiles: List<TileRow>,
    @SerialName("centre_tile") val centreTile: TileId,
)

@Serializable
data class FracToCoordRequest(
    val z: Int,
    val fx: Double,
    val fy: Double,
    /** Coordinates to turn into fractional tile positions at [z] (the inverse). */
    @EncodeDefault(NEVER) val coords: List<Coord>? = null,
)

@Serializable
data class FracToCoordAnswer(
    val coord: Coord,
    val fracs: List<List<Double>>,
    @SerialName("max_lat") val maxLat: Double,
)

/** [mode] is `off`, `centred` or `following`; [event] is `me_tap` or `pan`. */
@Serializable
data class FollowRequest(
    val mode: String,
    val event: String,
    @SerialName("has_fix") @EncodeDefault(NEVER) val hasFix: Boolean? = null,
)

@Serializable
data class FollowAnswer(
    val mode: String,
    val watches: Boolean,
)
