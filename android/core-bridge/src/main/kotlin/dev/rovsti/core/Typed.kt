package dev.rovsti.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The generic typed call every `*Ops` facade is built from: encodes [req] with [reqSer], runs [op] and decodes the
 * `ok` value with [ansSer]. An answer that does not fit [ansSer] is [RustError.Parse] naming [op]; Rust failures
 * pass through unchanged. Never throws for either.
 */
suspend fun <Req, Ans> RustCore.call(
    op: String,
    req: Req,
    reqSer: KSerializer<Req>,
    ansSer: KSerializer<Ans>,
): Outcome<Ans> {
    val body =
        try {
            Envelope.json.encodeToJsonElement(reqSer, req)
        } catch (e: SerializationException) {
            return Outcome.Err(RustError.Parse("$op: cannot encode request: ${e.message}"))
        }
    return decodeAnswer(op, call(op, body), ansSer)
}

/** Like [call] for an op without a request body. */
suspend fun <Ans> RustCore.call(
    op: String,
    ansSer: KSerializer<Ans>,
): Outcome<Ans> = decodeAnswer(op, call(op, JsonNull), ansSer)

internal fun <Ans> decodeAnswer(
    op: String,
    outcome: Outcome<JsonElement>,
    ansSer: KSerializer<Ans>,
): Outcome<Ans> =
    when (outcome) {
        is Outcome.Err -> {
            outcome
        }

        is Outcome.Ok -> {
            try {
                Outcome.Ok(Envelope.json.decodeFromJsonElement(ansSer, outcome.value))
            } catch (e: SerializationException) {
                Outcome.Err(RustError.Parse("$op: unexpected answer shape: ${e.message}"))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(RustError.Parse("$op: unexpected answer shape: ${e.message}"))
            }
        }
    }

/**
 * A native object (index, trip, client) owned by Kotlin. [close] releases it once; later calls do nothing. Subclasses
 * pass the id Rust gave them and close it when their screen or service ends. No finalizer: a leaked handle is a bug
 * to find in review, not something to hide.
 */
open class RustHandle(
    val id: Long,
    private val release: (Long) -> Unit = RustNative::release,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val released: Boolean get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) release(id)
    }
}
