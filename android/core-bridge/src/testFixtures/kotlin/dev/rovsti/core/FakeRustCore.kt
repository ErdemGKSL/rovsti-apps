package dev.rovsti.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * In-memory [RustCore] for view-model and screen tests: scripted answers per op, a log of the calls, no native
 * library. Unscripted ops answer [RustError.Unsupported], like the real core does.
 */
class FakeRustCore(
    private val versionString: String = "0.0.0-fake",
) : RustCore {
    /** One logged call. */
    data class Call(
        val op: String,
        val request: JsonElement,
    )

    private val answers = mutableMapOf<String, (JsonElement) -> Outcome<JsonElement>>()
    private val headlessAnswers = mutableMapOf<String, (String) -> String>()
    private val log = mutableListOf<Call>()

    val calls: List<Call> get() = synchronized(log) { log.toList() }

    fun on(
        op: String,
        answer: (JsonElement) -> Outcome<JsonElement>,
    ) {
        answers[op] = answer
    }

    /**
     * Answers [op] from a typed lambda: the request is decoded with [reqSer], the result encoded with [ansSer]. A
     * request that does not decode is [RustError.Parse], like the real core's.
     */
    fun <Req, Ans> onTyped(
        op: String,
        reqSer: KSerializer<Req>,
        ansSer: KSerializer<Ans>,
        answer: (Req) -> Outcome<Ans>,
    ) {
        on(op) { request ->
            val req =
                try {
                    Envelope.json.decodeFromJsonElement(reqSer, request)
                } catch (e: IllegalArgumentException) {
                    return@on Outcome.Err(RustError.Parse("$op: bad request: ${e.message}"))
                }
            when (val out = answer(req)) {
                is Outcome.Ok -> Outcome.Ok(Envelope.json.encodeToJsonElement(ansSer, out.value))
                is Outcome.Err -> out
            }
        }
    }

    fun onHeadless(
        kind: String,
        answer: (String) -> String,
    ) {
        headlessAnswers[kind] = answer
    }

    override suspend fun version(): Outcome<String> = Outcome.Ok(versionString)

    override suspend fun call(
        op: String,
        request: JsonElement,
    ): Outcome<JsonElement> {
        synchronized(log) { log += Call(op, request) }
        return answers[op]?.invoke(request) ?: Outcome.Err(RustError.Unsupported("fake has no op $op"))
    }

    override suspend fun headless(
        kind: String,
        json: String,
    ): String = headlessAnswers[kind]?.invoke(json).orEmpty()

    companion object {
        val none: JsonElement = JsonNull
    }
}
