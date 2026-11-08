package dev.rovsti.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A failed Rust call: one subclass per `ErrorKind` of the envelope. [detail] is structured data when Rust sent any. */
sealed class RustError(
    message: String,
    val detail: JsonElement? = null,
) : Exception(message) {
    class Invalid(
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    class NotFound(
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    class Io(
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    class Parse(
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    class Network(
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    class Auth(
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    class Unsupported(
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    class Panic(
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    /** A kind this build does not know (a newer library): handled like any failure, never a crash. */
    class Unknown(
        val kind: String,
        message: String,
        detail: JsonElement? = null,
    ) : RustError(message, detail)

    companion object {
        /** Maps a wire name (`"not_found"`) to its subclass; an unknown name is [Unknown]. */
        fun of(
            kind: String,
            message: String,
            detail: JsonElement? = null,
        ): RustError =
            when (kind) {
                "invalid" -> Invalid(message, detail)
                "not_found" -> NotFound(message, detail)
                "io" -> Io(message, detail)
                "parse" -> Parse(message, detail)
                "network" -> Network(message, detail)
                "auth" -> Auth(message, detail)
                "unsupported" -> Unsupported(message, detail)
                "panic" -> Panic(message, detail)
                else -> Unknown(kind, "unknown error kind \"$kind\": $message", detail)
            }
    }
}

/** The result of a call: a value or a [RustError]. Calls never throw for Rust failures. */
sealed interface Outcome<out T> {
    data class Ok<out T>(
        val value: T,
    ) : Outcome<T>

    data class Err(
        val error: RustError,
    ) : Outcome<Nothing>
}

fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Ok)?.value

fun <T> Outcome<T>.getOrThrow(): T =
    when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> throw error
    }

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> =
    when (this) {
        is Outcome.Ok -> Outcome.Ok(transform(value))
        is Outcome.Err -> this
    }

/**
 * The answer of every call: `{"ok": value}` or `{"err": {"kind", "message", "detail"?}}`. Decoding is strict:
 * exactly one of the two keys, no unknown keys anywhere, a `kind` and a `message` in an error.
 */
object Envelope {
    /** Strict: an unknown field is a contract break, not something to skip. */
    val json: Json = Json { ignoreUnknownKeys = false }

    /** Decodes the envelope; a malformed one is [RustError.Parse]. The `ok` value stays a [JsonElement]. */
    fun decode(bytes: ByteArray?): Outcome<JsonElement> {
        if (bytes == null) return Outcome.Err(RustError.Panic("native call returned no answer"))
        return try {
            decodeObject(json.parseToJsonElement(bytes.decodeToString()))
        } catch (e: SerializationException) {
            Outcome.Err(RustError.Parse("malformed envelope: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            Outcome.Err(RustError.Parse("malformed envelope: ${e.message}"))
        }
    }

    /** Decodes the envelope and then the `ok` value with [serializer]. */
    fun <T> decode(
        bytes: ByteArray?,
        serializer: KSerializer<T>,
    ): Outcome<T> =
        when (val outcome = decode(bytes)) {
            is Outcome.Err -> {
                outcome
            }

            is Outcome.Ok -> {
                try {
                    Outcome.Ok(json.decodeFromJsonElement(serializer, outcome.value))
                } catch (e: SerializationException) {
                    Outcome.Err(RustError.Parse("unexpected answer shape: ${e.message}"))
                } catch (e: IllegalArgumentException) {
                    Outcome.Err(RustError.Parse("unexpected answer shape: ${e.message}"))
                }
            }
        }

    private fun decodeObject(root: JsonElement): Outcome<JsonElement> {
        val obj = root as? JsonObject ?: return Outcome.Err(RustError.Parse("envelope is not an object"))
        val unknown = obj.keys - setOf("ok", "err")
        if (unknown.isNotEmpty()) return Outcome.Err(RustError.Parse("unknown envelope keys $unknown"))
        val ok = obj["ok"]
        val err = obj["err"]
        return when {
            ok != null && err == null -> Outcome.Ok(ok)
            err != null && ok == null -> Outcome.Err(decodeError(err))
            else -> Outcome.Err(RustError.Parse("envelope needs exactly one of ok and err"))
        }
    }

    private fun decodeError(err: JsonElement): RustError {
        val body = err as? JsonObject ?: return RustError.Parse("err is not an object")
        val unknown = body.keys - setOf("kind", "message", "detail")
        if (unknown.isNotEmpty()) return RustError.Parse("unknown err keys $unknown")
        val kind = body["kind"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val message = body["message"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (kind == null || message == null) return RustError.Parse("err needs kind and message")
        val detail = body["detail"]?.takeUnless { it is JsonNull }
        return RustError.of(kind, message, detail)
    }
}

internal fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

internal fun JsonElement.asStringOrNull(): String? = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
