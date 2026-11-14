package dev.rosty.core.contracts

import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.stops.TilesCancelAnswer
import dev.rosty.core.model.stops.TilesCancelRequest
import dev.rosty.core.model.stops.TilesClearExceptRequest
import dev.rosty.core.model.stops.TilesEncodeMvtAnswer
import dev.rosty.core.model.stops.TilesEncodeMvtRequest
import dev.rosty.core.model.stops.TilesFreedAnswer
import dev.rosty.core.model.stops.TilesHandleRequest
import dev.rosty.core.model.stops.TilesOfflineAnswer
import dev.rosty.core.model.stops.TilesOfflineRequest
import dev.rosty.core.model.stops.TilesOpenAnswer
import dev.rosty.core.model.stops.TilesOpenRequest
import dev.rosty.core.model.stops.TilesSetCapRequest
import dev.rosty.core.model.stops.TilesSourcesAnswer
import dev.rosty.core.model.stops.TilesStandInAnswer
import dev.rosty.core.model.stops.TilesStandInRequest
import dev.rosty.core.model.stops.TilesTestServerAnswer
import dev.rosty.core.model.stops.TilesTestServerRequest
import dev.rosty.core.model.stops.TilesUsageAnswer
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
