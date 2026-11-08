package dev.rovsti.core.contracts

import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `tiles.*`. */
object TilesContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<TilesOpenRequest, TilesOpenAnswer>(
                "tiles.open",
                ok = listOf("custom"),
                err = listOf("bad_url", "no_scheme"),
            )
            op<TilesHandleRequest, TilesUsageAnswer>("tiles.usage")
            op<TilesHandleRequest, TilesSourcesAnswer>("tiles.usage_by_source")
            op<TilesHandleRequest, TilesFreedAnswer>("tiles.clear_all")
            op<TilesClearExceptRequest, TilesFreedAnswer>("tiles.clear_except")
            op<TilesSetCapRequest, TilesUsageAnswer>("tiles.set_cap")
            // The main case points at a port nothing listens on, so even offline it answers `network`.
            op<TilesTestServerRequest, TilesTestServerAnswer>(
                "tiles.test_server",
                mainIsError = true,
                err = listOf("bad_url", "empty"),
            )
            op<TilesStandInRequest, TilesStandInAnswer>(
                "tiles.stand_in",
                ok = listOf("none", "itself"),
            )
            op<TilesEncodeMvtRequest, TilesEncodeMvtAnswer>(
                "tiles.encode_mvt",
                ok = listOf("dark", "empty"),
                err = listOf("garbage", "outside"),
            )
            op<TilesOfflineRequest, TilesOfflineAnswer>(
                "tiles.offline_start",
                ok = listOf("circle"),
                err = listOf("no_area", "bad_bbox", "bad_zooms"),
            )
            op<TilesCancelRequest, TilesCancelAnswer>(
                "tiles.offline_cancel",
                err = listOf("unknown"),
            )
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
        mainIsError: Boolean = false,
    ) {
        val okAnswer = OkEnvelope.serializer(serializer<Ans>())
        put("$op.request", serializer<Req>())
        put("$op.answer", if (mainIsError) ErrEnvelope.serializer() else okAnswer)
        for (label in ok) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", okAnswer)
        }
        for (label in err) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", ErrEnvelope.serializer())
        }
    }
}
