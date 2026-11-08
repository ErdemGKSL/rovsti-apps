@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.shared

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of the shared number ops (`phone_number.*`, `format.*`, `calling_code.*`, `copy.*`), mirroring
// `crates/rovsti_ffi/fixtures/<op>[.<label>].{request,answer}.json`. Optional request fields are left out of the JSON
// when null (`@EncodeDefault(NEVER)`), as the fixtures are written. Stability: `compose_stability.conf`.

// ---- phone_number.*

/** `phone_number.parse` request. */
@Serializable
data class ParseNumberRequest(
    val input: String,
)

/** `phone_number.parse` answer: the canonical form, plus the WhatsApp user part for international numbers. */
@Serializable
data class ParsedNumber(
    val canonical: String,
    val international: Boolean,
    @SerialName("whatsapp_user") val whatsappUser: String?,
)

/** `phone_number.same` request. */
@Serializable
data class SameNumberRequest(
    val a: String,
    val b: String,
)

@Serializable
data class SameNumberAnswer(
    val same: Boolean,
)

// ---- format.*

/** The forms a number can be copied in. Wire names are the `NumberForm` serde names of `rovsti_core`. */
@Serializable
enum class NumberForm {
    @SerialName("display")
    Display,

    @SerialName("e164")
    E164,

    @SerialName("national")
    National,

    @SerialName("tel_link")
    TelLink,

    @SerialName("vcard")
    VCard,
}

/** `format.render` request. */
@Serializable
data class RenderRequest(
    val raw: String,
    val form: NumberForm,
    @SerialName("home_cc") @EncodeDefault(EncodeDefault.Mode.NEVER) val homeCc: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val name: String? = null,
)

/** `format.render` answer: `null` when the number has no such form. */
@Serializable
data class RenderAnswer(
    val text: String?,
)

/** `format.available` and `format.copy_forms` request. */
@Serializable
data class FormsRequest(
    val raw: String,
    @SerialName("home_cc") @EncodeDefault(EncodeDefault.Mode.NEVER) val homeCc: String? = null,
)

@Serializable
data class FormRow(
    val form: NumberForm,
    val label: String,
    val text: String,
)

@Serializable
data class FormsAnswer(
    val forms: List<FormRow>,
)

@Serializable
data class CopiedNoticeRequest(
    val form: NumberForm,
)

@Serializable
data class CopiedNoticeAnswer(
    val notice: String,
)

@Serializable
data class InferHomeCcRequest(
    val numbers: List<String>,
)

@Serializable
data class InferHomeCcAnswer(
    @SerialName("home_cc") val homeCc: String?,
)

@Serializable
data class QrNumber(
    val raw: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val label: String? = null,
)

/** `format.contact_qr` request. A card too big for a QR code answers `invalid` with detail `{"reason":"too_big"}`. */
@Serializable
data class ContactQrRequest(
    val name: String,
    val numbers: List<QrNumber>,
    @SerialName("home_cc") @EncodeDefault(EncodeDefault.Mode.NEVER) val homeCc: String? = null,
)

/** `format.contact_qr` answer: the vCard text to draw and how many numbers fit. */
@Serializable
data class ContactQr(
    val payload: String,
    val kept: Int,
    val dropped: Int,
)

@Serializable
data class FileNameRequest(
    val name: String,
)

@Serializable
data class FileNameAnswer(
    val slug: String,
    @SerialName("qr_file_name") val qrFileName: String,
)

// ---- calling_code.*

@Serializable
data class RawNumberRequest(
    val raw: String,
)

@Serializable
data class CallingCodeRequest(
    val cc: String,
)

@Serializable
data class Country(
    val cc: String,
    val name: String,
)

@Serializable
data class CountryAnswer(
    val country: Country?,
)

@Serializable
data class CountryLineRequest(
    val raw: String,
    @SerialName("home_cc") @EncodeDefault(EncodeDefault.Mode.NEVER) val homeCc: String? = null,
)

@Serializable
data class CountryLineAnswer(
    val line: String?,
)

@Serializable
data class CountryListAnswer(
    val countries: List<Country>,
)

// ---- copy.*

@Serializable
data class HeaderChoicesRequest(
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val number: String? = null,
    @SerialName("wa_id") @EncodeDefault(EncodeDefault.Mode.NEVER) val waId: String? = null,
)

@Serializable
data class CopyChoice(
    val label: String,
    val text: String,
    val notice: String,
)

@Serializable
data class HeaderChoicesAnswer(
    val choices: List<CopyChoice>,
)
