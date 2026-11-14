package dev.rosty.core.ops

import dev.rosty.core.Outcome
import dev.rosty.core.RustError

/** The designs a share card can have (X.2). New number is a static card only. */
enum class CardTemplate(
    val animatable: Boolean,
) {
    Birthday(true),
    Contact(true),
    NewNumber(false),
}

/** The colour choices of a card; the core owns the gradients, Kotlin only needs the identity and a preview colour. */
enum class CardPalette(
    /** Two ARGB stops the swatch draws; the same family the Rust palettes start from. */
    val swatchTop: Long,
    val swatchBottom: Long,
) {
    Dawn(0xFFFFC6A8, 0xFFFF8FA3),
    Lagoon(0xFF8FE3E8, 0xFF4C9BE8),
    Meadow(0xFFCDEB8B, 0xFF58C98A),
    Dusk(0xFFB9A4F4, 0xFF6C6BDD),
    Ink(0xFF3A3F4B, 0xFF14161C),
}

/** What a card says and shows. Every word comes from the app's string resources (en and tr); the core only lays out. */
data class CardSpec(
    val template: CardTemplate,
    val palette: CardPalette,
    val animated: Boolean,
    val texts: Map<String, String>,
    /** A readable picture of the contact (a content or file URI string), or null for initials. */
    val photo: String?,
    val initials: String,
    /** The text a QR code on the Contact card encodes; null draws no code. */
    val qr: String? = null,
)

/** A render asked for: the [spec] and the file the core writes ([out], under `cacheDir/share/cards/`). */
data class CardRenderRequest(
    val spec: CardSpec,
    val out: String,
)

/** The finished card: the file, its pixel size, the byte count and the frame count (1 for a still). */
data class CardRenderResult(
    val path: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val frames: Int,
) {
    val mime: String get() = if (frames > 1) "image/gif" else "image/png"
}

/**
 * Share cards: `cards.render` draws a [CardSpec] into a file on the Rust dispatcher; [cancel] stops the render whose
 * output file is [out] between two frames. The Rust ops land with X.2 step 3; until then [UnavailableCardsOps] answers.
 */
interface CardsOps {
    suspend fun render(req: CardRenderRequest): Outcome<CardRenderResult>

    suspend fun cancel(out: String)
}

/** Answers `unsupported` to every render; the container uses it until the Rust ops exist. */
object UnavailableCardsOps : CardsOps {
    override suspend fun render(req: CardRenderRequest): Outcome<CardRenderResult> =
        Outcome.Err(RustError.Unsupported("cards.render is not available in this build"))

    override suspend fun cancel(out: String) = Unit
}
