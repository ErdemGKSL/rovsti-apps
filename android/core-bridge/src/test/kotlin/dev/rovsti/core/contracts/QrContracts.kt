package dev.rovsti.core.contracts

import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
import dev.rovsti.core.model.qr.QrDecodeAnswer
import dev.rovsti.core.model.qr.QrDecodeRequest
import dev.rovsti.core.model.qr.QrEncodeAnswer
import dev.rovsti.core.model.qr.QrEncodeRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `qr.*`. */
object QrContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<QrEncodeRequest, QrEncodeAnswer>(
                "qr.encode",
                ok = listOf("high"),
                err = listOf("empty", "too_long"),
            )
            op<QrDecodeRequest, QrDecodeAnswer>(
                "qr.decode",
                ok = listOf("blank"),
                err = listOf("bad_size"),
            )
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
