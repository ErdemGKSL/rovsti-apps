package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
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

/**
 * Number helpers shared by all apps: parsing, display and copy forms, QR cards, country of a number, copy choices.
 * Every method is one Rust op ([ops] lists their names; a test checks each has a contract fixture).
 */
interface SharedOps {
    suspend fun parseNumber(req: ParseNumberRequest): Outcome<ParsedNumber>

    suspend fun sameNumber(req: SameNumberRequest): Outcome<SameNumberAnswer>

    suspend fun render(req: RenderRequest): Outcome<RenderAnswer>

    suspend fun availableForms(req: FormsRequest): Outcome<FormsAnswer>

    suspend fun copyForms(req: FormsRequest): Outcome<FormsAnswer>

    suspend fun copiedNotice(req: CopiedNoticeRequest): Outcome<CopiedNoticeAnswer>

    suspend fun inferHomeCc(req: InferHomeCcRequest): Outcome<InferHomeCcAnswer>

    suspend fun contactQr(req: ContactQrRequest): Outcome<ContactQr>

    suspend fun fileName(req: FileNameRequest): Outcome<FileNameAnswer>

    suspend fun countryOf(req: RawNumberRequest): Outcome<CountryAnswer>

    suspend fun countryOfCc(req: CallingCodeRequest): Outcome<CountryAnswer>

    suspend fun countryLine(req: CountryLineRequest): Outcome<CountryLineAnswer>

    suspend fun countries(): Outcome<CountryListAnswer>

    suspend fun headerChoices(req: HeaderChoicesRequest): Outcome<HeaderChoicesAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "phone_number.parse",
                "phone_number.same",
                "format.render",
                "format.available",
                "format.copy_forms",
                "format.copied_notice",
                "format.infer_home_cc",
                "format.contact_qr",
                "format.file_name",
                "calling_code.country_of",
                "calling_code.of_cc",
                "calling_code.line",
                "calling_code.list",
                "copy.header_choices",
            )
    }
}

/** [SharedOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeSharedOps(
    private val core: RustCore = NativeRustCore(),
) : SharedOps {
    override suspend fun parseNumber(req: ParseNumberRequest) =
        core.call("phone_number.parse", req, ParseNumberRequest.serializer(), ParsedNumber.serializer())

    override suspend fun sameNumber(req: SameNumberRequest) =
        core.call("phone_number.same", req, SameNumberRequest.serializer(), SameNumberAnswer.serializer())

    override suspend fun render(req: RenderRequest) =
        core.call("format.render", req, RenderRequest.serializer(), RenderAnswer.serializer())

    override suspend fun availableForms(req: FormsRequest) =
        core.call("format.available", req, FormsRequest.serializer(), FormsAnswer.serializer())

    override suspend fun copyForms(req: FormsRequest) =
        core.call("format.copy_forms", req, FormsRequest.serializer(), FormsAnswer.serializer())

    override suspend fun copiedNotice(req: CopiedNoticeRequest) =
        core.call("format.copied_notice", req, CopiedNoticeRequest.serializer(), CopiedNoticeAnswer.serializer())

    override suspend fun inferHomeCc(req: InferHomeCcRequest) =
        core.call("format.infer_home_cc", req, InferHomeCcRequest.serializer(), InferHomeCcAnswer.serializer())

    override suspend fun contactQr(req: ContactQrRequest) =
        core.call("format.contact_qr", req, ContactQrRequest.serializer(), ContactQr.serializer())

    override suspend fun fileName(req: FileNameRequest) =
        core.call("format.file_name", req, FileNameRequest.serializer(), FileNameAnswer.serializer())

    override suspend fun countryOf(req: RawNumberRequest) =
        core.call("calling_code.country_of", req, RawNumberRequest.serializer(), CountryAnswer.serializer())

    override suspend fun countryOfCc(req: CallingCodeRequest) =
        core.call("calling_code.of_cc", req, CallingCodeRequest.serializer(), CountryAnswer.serializer())

    override suspend fun countryLine(req: CountryLineRequest) =
        core.call("calling_code.line", req, CountryLineRequest.serializer(), CountryLineAnswer.serializer())

    override suspend fun countries() = core.call("calling_code.list", CountryListAnswer.serializer())

    override suspend fun headerChoices(req: HeaderChoicesRequest) =
        core.call("copy.header_choices", req, HeaderChoicesRequest.serializer(), HeaderChoicesAnswer.serializer())
}
