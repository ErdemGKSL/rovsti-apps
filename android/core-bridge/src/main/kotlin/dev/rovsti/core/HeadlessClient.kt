package dev.rovsti.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** The raw answer of a headless kind: a non-empty JSON string. Decode it with [decode] or [decodeOrNull]. */
@JvmInline
value class HeadlessAnswer(
    val raw: String,
) {
    /** Strict decode; throws [SerializationException] when the answer does not fit [serializer]. */
    fun <T> decode(serializer: KSerializer<T>): T = Envelope.json.decodeFromString(serializer, raw)

    /** Like [decode], but `null` for an answer of another shape: the caller falls back to the plain notification. */
    fun <T> decodeOrNull(serializer: KSerializer<T>): T? =
        try {
            decode(serializer)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}

/**
 * Headless decisions for receivers and services (SMS, MMS, trip alarm): asks Rust, and answers `null` whenever there
 * is no decision, so the caller posts the plain notification (AD-MSG4). `null` means: the library is missing, the
 * native side panicked or sent `""`, the kind is unknown, or the core raised a [RustError]. Never throws except
 * for coroutine cancellation.
 */
class HeadlessClient(
    private val core: RustCore = NativeRustCore(),
    private val load: () -> Unit = RustNative::load,
) {
    suspend fun ask(
        kind: String,
        json: String,
    ): HeadlessAnswer? =
        try {
            load()
            core.headless(kind, json).takeIf { it.isNotEmpty() }?.let(::HeadlessAnswer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: UnsatisfiedLinkError) {
            null
        } catch (_: LinkageError) {
            null
        } catch (_: RustError) {
            null
        } catch (_: RuntimeException) {
            null
        }
}
