package dev.rovsti.core.contracts

import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
import dev.rovsti.core.model.shared.CallingCodeRequest
import dev.rovsti.core.model.shared.ContactQr
import dev.rovsti.core.model.shared.ContactQrRequest
import dev.rovsti.core.model.shared.CopiedNoticeAnswer
import dev.rovsti.core.model.shared.CopiedNoticeRequest
import dev.rovsti.core.model.shared.CountryAnswer
import dev.rovsti.core.model.shared.CountryLineAnswer
import dev.rovsti.core.model.shared.CountryLineRequest
import dev.rovsti.core.model.shared.CountryListAnswer
import dev.rovsti.core.model.shared.FileNameAnswer
import dev.rovsti.core.model.shared.FileNameRequest
import dev.rovsti.core.model.shared.FormsAnswer
import dev.rovsti.core.model.shared.FormsRequest
import dev.rovsti.core.model.shared.HeaderChoicesAnswer
import dev.rovsti.core.model.shared.HeaderChoicesRequest
import dev.rovsti.core.model.shared.InferHomeCcAnswer
import dev.rovsti.core.model.shared.InferHomeCcRequest
import dev.rovsti.core.model.shared.ParseNumberRequest
import dev.rovsti.core.model.shared.ParsedNumber
import dev.rovsti.core.model.shared.RawNumberRequest
import dev.rovsti.core.model.shared.RenderAnswer
import dev.rovsti.core.model.shared.RenderRequest
import dev.rovsti.core.model.shared.SameNumberAnswer
import dev.rovsti.core.model.shared.SameNumberRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer

/** Fixture stem to model for `phone_number.*`, `format.*`, `calling_code.*` and `copy.*`. */
object SharedContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<ParseNumberRequest, ParsedNumber>(
                "phone_number.parse",
                ok = listOf("international"),
                err = listOf("empty", "letters", "too_long"),
            )
            op<SameNumberRequest, SameNumberAnswer>(
                "phone_number.same",
                ok = listOf("different"),
                err = listOf("invalid"),
            )
            op<RenderRequest, RenderAnswer>(
                "format.render",
                ok = listOf("no_form", "empty"),
                err = listOf("bad_form"),
                rawRequest = setOf("bad_form"),
            )
            op<FormsRequest, FormsAnswer>("format.available")
            op<FormsRequest, FormsAnswer>("format.copy_forms", ok = listOf("short_code"))
            op<CopiedNoticeRequest, CopiedNoticeAnswer>("format.copied_notice")
            op<InferHomeCcRequest, InferHomeCcAnswer>("format.infer_home_cc", ok = listOf("no_evidence"))
            op<ContactQrRequest, ContactQr>("format.contact_qr", err = listOf("too_big"))
            op<FileNameRequest, FileNameAnswer>("format.file_name", ok = listOf("nothing_usable"))
            op<RawNumberRequest, CountryAnswer>("calling_code.country_of", ok = listOf("national"))
            op<CallingCodeRequest, CountryAnswer>("calling_code.of_cc", ok = listOf("unknown"))
            op<CountryLineRequest, CountryLineAnswer>("calling_code.line", ok = listOf("home"))
            op<JsonNull, CountryListAnswer>("calling_code.list")
            op<HeaderChoicesRequest, HeaderChoicesAnswer>("copy.header_choices", ok = listOf("group", "name_is_number"))
        }

    /**
     * Registers the main case and the labelled ones: [ok] cases answer `{"ok": Ans}`, [err] cases `{"err": ..}`.
     * A case in [rawRequest] sends a request that is deliberately not a valid [Req].
     */
    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
        rawRequest: Set<String> = emptySet(),
    ) {
        val okAnswer = OkEnvelope.serializer(serializer<Ans>())
        put("$op.request", requestSerializer<Req>())
        put("$op.answer", okAnswer)
        for (label in ok) {
            put("$op.$label.request", if (label in rawRequest) JsonElement.serializer() else requestSerializer<Req>())
            put("$op.$label.answer", okAnswer)
        }
        for (label in err) {
            put("$op.$label.request", if (label in rawRequest) JsonElement.serializer() else requestSerializer<Req>())
            put("$op.$label.answer", ErrEnvelope.serializer())
        }
    }

    private inline fun <reified Req> requestSerializer(): KSerializer<*> =
        if (Req::class == JsonNull::class) JsonNull.serializer() else serializer<Req>()
}
