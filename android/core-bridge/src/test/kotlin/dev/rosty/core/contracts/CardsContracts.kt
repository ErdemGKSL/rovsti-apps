package dev.rosty.core.contracts

import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.ops.CardCancelAnswer
import dev.rosty.core.ops.CardCancelRequest
import dev.rosty.core.ops.CardRenderRequest
import dev.rosty.core.ops.CardRenderResult
import dev.rosty.core.ops.CardTemplatesAnswer
import dev.rosty.core.ops.CardTemplatesRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `cards.*` (X.2 share cards). */
object CardsContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<CardTemplatesRequest, CardTemplatesAnswer>("cards.templates")
            op<CardRenderRequest, CardRenderResult>(
                "cards.render",
                ok = listOf("number_name", "animated"),
                err = listOf("text_missing", "out_unwritable", "out_invalid"),
            )
            op<CardCancelRequest, CardCancelAnswer>("cards.cancel")
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
    ) {
        val okAnswer = OkEnvelope.serializer(serializer<Ans>())
        put("$op.request", serializer<Req>())
        put("$op.answer", okAnswer)
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
