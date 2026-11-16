package dev.rosty.core.ops

import dev.rosty.core.NativeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.RustError
import dev.rosty.core.call
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The designs a share card can have (X.2). New number is a static card only. */
@Serializable
enum class CardTemplate(
    val animatable: Boolean,
) {
    @SerialName("birthday")
    Birthday(true),

    @SerialName("contact")
    Contact(true),

    @SerialName("new_number")
    NewNumber(false),
}

/** The colour choices of a card; the core owns the gradients, Kotlin only needs the identity and a preview colour. */
@Serializable
enum class CardPalette(
    /** Two ARGB stops the swatch draws; the same family the Rust palettes start from. */
    val swatchTop: Long,
    val swatchBottom: Long,
) {
    @SerialName("dawn")
    Dawn(0xFFFFC6A8, 0xFFFF8FA3),

    @SerialName("lagoon")
    Lagoon(0xFF8FE3E8, 0xFF4C9BE8),

    @SerialName("meadow")
    Meadow(0xFFCDEB8B, 0xFF58C98A),

    @SerialName("dusk")
    Dusk(0xFFB9A4F4, 0xFF6C6BDD),

    @SerialName("ink")
    Ink(0xFF3A3F4B, 0xFF14161C),
}

/** What a card says and shows. Every word comes from the app's string resources (en and tr); the core only lays out. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CardSpec(
    val template: CardTemplate,
    val palette: CardPalette,
    @EncodeDefault(NEVER) val animated: Boolean = false,
    /** The words by key (`title`, `age_line`, `date_line`, `subtitle`, `numbers`; numbers newline-separated). */
    @EncodeDefault(NEVER) val texts: Map<String, String> = emptyMap(),
    /**
     * A picture of the contact: a content or file URI string, or an absolute path. [NativeCardsOps] hands Rust a
     * readable file path; null, a missing or an unreadable picture draws [initials].
     */
    @EncodeDefault(NEVER) val photo: String? = null,
    @EncodeDefault(NEVER) val initials: String = "",
    /** The text a QR code on the Contact card encodes; null draws no code. */
    @EncodeDefault(NEVER) val qr: String? = null,
)

/** A render asked for: the [spec] and the file the core writes ([out], an absolute path under `cacheDir/share/cards/`). */
@Serializable
data class CardRenderRequest(
    val spec: CardSpec,
    val out: String,
)

/** The finished card: the file, its pixel size, the byte count and the frame count (1 for a still). */
@Serializable
data class CardRenderResult(
    val path: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val frames: Int,
) {
    val mime: String get() = if (frames > 1) "image/gif" else "image/png"
}

/** `cards.templates` takes no fields. */
@Serializable
class CardTemplatesRequest {
    override fun equals(other: Any?): Boolean = other is CardTemplatesRequest

    override fun hashCode(): Int = 0
}

@Serializable
data class CardSize(
    val width: Int,
    val height: Int,
)

/** One template as the core draws it: the text keys it prints, the ones it needs, and its sizes. */
@Serializable
data class CardTemplateInfo(
    val id: CardTemplate,
    val animatable: Boolean,
    val texts: List<String>,
    val required: List<String>,
    val still: CardSize,
    val animated: CardSize?,
)

@Serializable
data class CardTemplatesAnswer(
    val templates: List<CardTemplateInfo>,
    val palettes: List<CardPalette>,
)

@Serializable
data class CardCancelRequest(
    val out: String,
)

@Serializable
data class CardCancelAnswer(
    val cancelled: Boolean,
)

/**
 * Share cards: `cards.render` draws a [CardSpec] into a file on the Rust dispatcher; [cancel] stops the render whose
 * output file is [out] between two frames. A failure is an error code (`text_missing`, `out_unwritable`, `cancelled`,
 * `render_failed`, ...), never a sentence.
 */
interface CardsOps {
    suspend fun render(req: CardRenderRequest): Outcome<CardRenderResult>

    suspend fun cancel(out: String)

    /** The templates, their text keys and sizes, and the palettes. */
    suspend fun templates(): Outcome<CardTemplatesAnswer> = Outcome.Err(RustError.Unsupported("cards.templates"))

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "cards.templates",
                "cards.render",
                "cards.cancel",
            )
    }
}

/**
 * [CardsOps] over a [RustCore]; the real core runs every call on `RustDispatcher` after `Rust.init` has started the
 * library. [photos] turns a photo that is not a plain file (a `content://` URI) into a readable file path, or null;
 * the app's container copies (and downsizes) it into the cache. A photo it cannot read draws initials.
 */
class NativeCardsOps(
    private val core: RustCore = NativeRustCore(),
    private val photos: suspend (String) -> String? = { null },
) : CardsOps {
    override suspend fun render(req: CardRenderRequest): Outcome<CardRenderResult> {
        val photo = req.spec.photo?.let { localPhoto(it) }
        val wire = req.copy(spec = req.spec.copy(photo = photo))
        return core.call("cards.render", wire, CardRenderRequest.serializer(), CardRenderResult.serializer())
    }

    override suspend fun cancel(out: String) {
        core.call("cards.cancel", CardCancelRequest(out), CardCancelRequest.serializer(), CardCancelAnswer.serializer())
    }

    override suspend fun templates(): Outcome<CardTemplatesAnswer> =
        core.call(
            "cards.templates",
            CardTemplatesRequest(),
            CardTemplatesRequest.serializer(),
            CardTemplatesAnswer.serializer(),
        )

    private suspend fun localPhoto(photo: String): String? =
        when {
            photo.isBlank() -> null
            photo.startsWith("/") -> photo
            photo.startsWith(FILE_SCHEME) -> photo.removePrefix(FILE_SCHEME).takeIf { it.startsWith("/") }
            else -> runCatching { photos(photo) }.getOrNull()
        }

    private companion object {
        const val FILE_SCHEME = "file://"
    }
}

/** Answers `unsupported` to every render. Test-only stand-in: no production container may return it (X.2 guard). */
object UnavailableCardsOps : CardsOps {
    override suspend fun render(req: CardRenderRequest): Outcome<CardRenderResult> =
        Outcome.Err(RustError.Unsupported("cards.render is not available in this build"))

    override suspend fun cancel(out: String) = Unit
}
