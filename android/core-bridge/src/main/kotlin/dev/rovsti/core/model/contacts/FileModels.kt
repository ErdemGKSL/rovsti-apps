@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.contacts

import dev.rovsti.core.model.search.WireContact
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Files and pictures: vCard, CSV and calendar files, the import plan, intake, scan and the photo crop
// (`crates/rovsti_ffi/src/ops/contacts/files.rs`).

@Serializable
data class TextRequest(
    val text: String,
)

/** A file ready to share or save. */
@Serializable
data class TextFileAnswer(
    val text: String,
    @SerialName("file_name") val fileName: String,
    val count: Int,
)

/** Cards read from a file; [photos] is aligned with [contacts] (vCard only, empty for CSV). */
@Serializable
data class ParsedAnswer(
    val contacts: List<ContactDraft>,
    val skipped: Int,
    val photos: List<CardPhoto?>,
)

@Serializable
data class ExportRequest(
    val drafts: List<ContactDraft>,
    val today: Day,
)

@Serializable
data class IcsRequest(
    val drafts: List<ContactDraft>,
    val today: Day,
    @SerialName("stamp_ms") val stampMs: Long,
)

@Serializable
data class IcsAnswer(
    val text: String,
    @SerialName("file_name") val fileName: String,
    val count: Int,
    val notice: String,
)

@Serializable
data class VcardWriteRequest(
    val drafts: List<ContactDraft>,
    val today: Day,
    /** Optional photos aligned with [drafts]. */
    @EncodeDefault(NEVER) val photos: List<CardPhoto?> = emptyList(),
)

@Serializable
data class ImportPlanRequest(
    val existing: List<WireContact>,
    val incoming: List<ContactDraft>,
    @EncodeDefault(NEVER) val photos: List<CardPhoto?> = emptyList(),
)

/** Cards to add, how many were left out as duplicates, and the photos of the kept cards (aligned with [add]). */
@Serializable
data class ImportPlanAnswer(
    val add: List<ContactDraft>,
    val duplicates: Int,
    val photos: List<CardPhoto?>,
)

@Serializable
data class ImportPhotosRequest(
    val ids: List<Long>,
    val photos: List<CardPhoto?>,
)

@Serializable
data class PhotoTarget(
    @SerialName("contact_id") val contactId: Long,
    val photo: CardPhoto,
)

@Serializable
data class ImportPhotosAnswer(
    val targets: List<PhotoTarget>,
    val max: Int,
)

@Serializable
data class NumberKeyRequest(
    val raw: String,
)

/** The last ten digits of a number; null without digits. */
@Serializable
data class NumberKeyAnswer(
    val key: String?,
)

// ---- intake / scan ----

@Serializable
enum class ScanKind {
    @SerialName("cards")
    Cards,

    @SerialName("number")
    Number,

    @SerialName("email")
    Email,

    @SerialName("nothing")
    Nothing,
}

@Serializable
data class ScanRequest(
    val text: String,
    /** Text from the clipboard (a name and a number on a line are read too) instead of a QR payload. */
    @EncodeDefault(NEVER) val pasted: Boolean = false,
)

/** What a scanned or pasted text holds; [value] is the number or address of `number` / `email`. */
@Serializable
data class ScanAnswer(
    val kind: ScanKind,
    val cards: List<ContactDraft>,
    val value: String?,
    val notice: String,
)

/** [source] is `file` (a shared or opened file: [bytes] base64, [mime], [name]) or `text` ([text]). */
@Serializable
data class IntakeRequest(
    val source: String,
    @EncodeDefault(NEVER) val mime: String? = null,
    @EncodeDefault(NEVER) val name: String = "",
    @EncodeDefault(NEVER) val bytes: String = "",
    @EncodeDefault(NEVER) val text: String = "",
)

@Serializable
enum class IntakeKind {
    @SerialName("cards")
    Cards,

    @SerialName("single")
    Single,

    @SerialName("not_contacts")
    NotContacts,

    @SerialName("too_big")
    TooBig,

    @SerialName("unreadable")
    Unreadable,
}

@Serializable
enum class IntakeFormat {
    @SerialName("vcard")
    Vcard,

    @SerialName("csv")
    Csv,

    @SerialName("text")
    Text,
}

@Serializable
data class IntakeAnswer(
    val kind: IntakeKind,
    val cards: List<ContactDraft>,
    val single: ScanAnswer?,
    /** The pill for a file that cannot be used. */
    val notice: String?,
    val format: IntakeFormat,
)

// ---- photo ----

/** The user's crop of a photo: quarter [turns], [zoom] (1 fits the short side) and the centre (0..1 of the rotated image). */
@Serializable
data class CropView(
    val turns: Int,
    val zoom: Double,
    val cx: Double,
    val cy: Double,
)

@Serializable
data class PhotoPlanRequest(
    val width: Int,
    val height: Int,
    /** The crop; absent plans the centred square. */
    @EncodeDefault(NEVER) val view: CropView? = null,
)

/** The square to cut (x, y, side) and the output size. */
@Serializable
data class PhotoPlan(
    val x: Int,
    val y: Int,
    val side: Int,
    val out: Int,
    val turns: Int,
)

@Serializable
data class PhotoPlanAnswer(
    val plan: PhotoPlan?,
    val error: String?,
    @SerialName("rotated_width") val rotatedWidth: Int,
    @SerialName("rotated_height") val rotatedHeight: Int,
    @SerialName("max_zoom") val maxZoom: Double,
    @SerialName("max_side") val maxSide: Int,
)

/** [action] is `rotate`, `pan` ([dx], [dy] in px over a window of [sidePx]) or `pinch` ([factor]). */
@Serializable
data class PhotoCropRequest(
    val width: Int,
    val height: Int,
    val view: CropView,
    val action: String,
    @EncodeDefault(NEVER) val dx: Double = 0.0,
    @EncodeDefault(NEVER) val dy: Double = 0.0,
    @SerialName("side_px") @EncodeDefault(NEVER) val sidePx: Double = 0.0,
    @EncodeDefault(NEVER) val factor: Double = 1.0,
)

@Serializable
data class PhotoCropAnswer(
    val view: CropView,
)

@Serializable
data class PhotoAcceptsRequest(
    @EncodeDefault(NEVER) val mime: String? = null,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class PhotoAcceptsAnswer(
    val accepts: Boolean,
    @SerialName("camera_file_name") val cameraFileName: String,
    @SerialName("max_pick_bytes") val maxPickBytes: Long,
    @SerialName("min_side") val minSide: Int,
)
