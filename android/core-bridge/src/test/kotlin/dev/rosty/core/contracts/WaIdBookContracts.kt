package dev.rosty.core.contracts

import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.wa.WaIdBookAccount
import dev.rosty.core.model.wa.WaIdBookAccountRequest
import dev.rosty.core.model.wa.WaIdBookCatchUpRequest
import dev.rosty.core.model.wa.WaIdBookCaughtUp
import dev.rosty.core.model.wa.WaIdBookLearnRequest
import dev.rosty.core.model.wa.WaIdBookLearned
import dev.rosty.core.model.wa.WaIdBookSnapshot
import dev.rosty.core.model.wa.WaIdBookSnapshotRequest
import dev.rosty.core.model.wa.WaIdBookWipeRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `wa_idbook.*` (Phone's identity table). */
object WaIdBookContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<WaIdBookSnapshotRequest, WaIdBookSnapshot>(
                "wa_idbook.snapshot",
                ok = listOf("other_instance", "missing"),
            )
            op<WaIdBookCatchUpRequest, WaIdBookCaughtUp>("wa_idbook.catch_up", ok = listOf("done", "odd_pairs"))
            op<WaIdBookLearnRequest, WaIdBookLearned>(
                "wa_idbook.learn",
                ok = listOf("known", "number_change", "not_a_pair"),
            )
            op<WaIdBookAccountRequest, WaIdBookAccount>(
                "wa_idbook.account",
                ok = listOf("other_number", "other_epoch", "event", "nothing_known"),
            )
            op<WaIdBookWipeRequest, Empty>("wa_idbook.wipe", ok = listOf("missing"))
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
    ) {
        val okAnswer = OkEnvelope.serializer(serializer<Ans>())
        put("$op.request", serializer<Req>())
        put("$op.answer", okAnswer)
        for (label in ok) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", okAnswer)
        }
    }
}
