package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.stops.TilesCancelAnswer
import dev.rovsti.core.model.stops.TilesCancelRequest
import dev.rovsti.core.model.stops.TilesClearExceptRequest
import dev.rovsti.core.model.stops.TilesEncodeMvtAnswer
import dev.rovsti.core.model.stops.TilesEncodeMvtRequest
import dev.rovsti.core.model.stops.TilesFreedAnswer
import dev.rovsti.core.model.stops.TilesHandleRequest
import dev.rovsti.core.model.stops.TilesOfflineAnswer
import dev.rovsti.core.model.stops.TilesOfflineRequest
import dev.rovsti.core.model.stops.TilesOpenAnswer
import dev.rovsti.core.model.stops.TilesOpenRequest
import dev.rovsti.core.model.stops.TilesSetCapRequest
import dev.rovsti.core.model.stops.TilesSourcesAnswer
import dev.rovsti.core.model.stops.TilesStandInAnswer
import dev.rovsti.core.model.stops.TilesStandInRequest
import dev.rovsti.core.model.stops.TilesTestServerAnswer
import dev.rovsti.core.model.stops.TilesTestServerRequest
import dev.rovsti.core.model.stops.TilesUsageAnswer

/**
 * The JSON ops of the map tile source (E2.13). Most callers use [TileHandle], which wraps an open source; this is
 * the raw facade it is built on (and what tests fake).
 */
interface TilesOps {
    /** Opens a tile source and its disk cache; the answer holds the handle. */
    suspend fun open(req: TilesOpenRequest): Outcome<TilesOpenAnswer>

    suspend fun usage(req: TilesHandleRequest): Outcome<TilesUsageAnswer>

    suspend fun usageBySource(req: TilesHandleRequest): Outcome<TilesSourcesAnswer>

    suspend fun clearAll(req: TilesHandleRequest): Outcome<TilesFreedAnswer>

    suspend fun clearExcept(req: TilesClearExceptRequest): Outcome<TilesFreedAnswer>

    suspend fun setCap(req: TilesSetCapRequest): Outcome<TilesUsageAnswer>

    /** Downloads tile 0/0/0 of a server and decodes it; failures carry the sentence for the user. */
    suspend fun testServer(req: TilesTestServerRequest): Outcome<TilesTestServerAnswer>

    suspend fun standIn(req: TilesStandInRequest): Outcome<TilesStandInAnswer>

    /** A vector tile to a tile buffer without a cache or a network (base64 both ways). */
    suspend fun encodeMvt(req: TilesEncodeMvtRequest): Outcome<TilesEncodeMvtAnswer>

    /** Plans an area and starts downloading it in the background; progress arrives as `tiles.offline` events. */
    suspend fun offlineStart(req: TilesOfflineRequest): Outcome<TilesOfflineAnswer>

    suspend fun offlineCancel(req: TilesCancelRequest): Outcome<TilesCancelAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "tiles.open",
                "tiles.usage",
                "tiles.usage_by_source",
                "tiles.clear_all",
                "tiles.clear_except",
                "tiles.set_cap",
                "tiles.test_server",
                "tiles.stand_in",
                "tiles.encode_mvt",
                "tiles.offline_start",
                "tiles.offline_cancel",
            )
    }
}

/** [TilesOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeTilesOps(
    private val core: RustCore = NativeRustCore(),
) : TilesOps {
    override suspend fun open(req: TilesOpenRequest) =
        core.call("tiles.open", req, TilesOpenRequest.serializer(), TilesOpenAnswer.serializer())

    override suspend fun usage(req: TilesHandleRequest) =
        core.call("tiles.usage", req, TilesHandleRequest.serializer(), TilesUsageAnswer.serializer())

    override suspend fun usageBySource(req: TilesHandleRequest) =
        core.call("tiles.usage_by_source", req, TilesHandleRequest.serializer(), TilesSourcesAnswer.serializer())

    override suspend fun clearAll(req: TilesHandleRequest) =
        core.call("tiles.clear_all", req, TilesHandleRequest.serializer(), TilesFreedAnswer.serializer())

    override suspend fun clearExcept(req: TilesClearExceptRequest) =
        core.call("tiles.clear_except", req, TilesClearExceptRequest.serializer(), TilesFreedAnswer.serializer())

    override suspend fun setCap(req: TilesSetCapRequest) =
        core.call("tiles.set_cap", req, TilesSetCapRequest.serializer(), TilesUsageAnswer.serializer())

    override suspend fun testServer(req: TilesTestServerRequest) =
        core.call("tiles.test_server", req, TilesTestServerRequest.serializer(), TilesTestServerAnswer.serializer())

    override suspend fun standIn(req: TilesStandInRequest) =
        core.call("tiles.stand_in", req, TilesStandInRequest.serializer(), TilesStandInAnswer.serializer())

    override suspend fun encodeMvt(req: TilesEncodeMvtRequest) =
        core.call("tiles.encode_mvt", req, TilesEncodeMvtRequest.serializer(), TilesEncodeMvtAnswer.serializer())

    override suspend fun offlineStart(req: TilesOfflineRequest) =
        core.call("tiles.offline_start", req, TilesOfflineRequest.serializer(), TilesOfflineAnswer.serializer())

    override suspend fun offlineCancel(req: TilesCancelRequest) =
        core.call("tiles.offline_cancel", req, TilesCancelRequest.serializer(), TilesCancelAnswer.serializer())
}
