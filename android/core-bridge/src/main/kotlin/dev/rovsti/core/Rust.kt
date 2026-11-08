package dev.rovsti.core

import dev.rovsti.core.model.core.VersionInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.io.File

/**
 * The typed face of the native library. Apps and view models depend on this interface; tests use `FakeRustCore`
 * (testFixtures). No method takes a `Context`: the native side must never see an Android object (ARCH 6.7).
 */
interface RustCore {
    suspend fun version(): Outcome<String>

    /** Runs `"<domain>.<verb>"` with a JSON body ([JsonNull] for none). */
    suspend fun call(
        op: String,
        request: JsonElement = JsonNull,
    ): Outcome<JsonElement>

    /** Headless work (SMS receivers, services): JSON string in and out; `""` for an unknown kind. */
    suspend fun headless(
        kind: String,
        json: String,
    ): String
}

/** The real implementation: every call runs on [RustDispatcher]. */
class NativeRustCore : RustCore {
    override suspend fun version(): Outcome<String> =
        call("core.version").let { outcome ->
            when (outcome) {
                is Outcome.Err -> {
                    outcome
                }

                is Outcome.Ok -> {
                    outcome.value
                        .asObjectOrNull()
                        ?.get("version")
                        ?.asStringOrNull()
                        ?.let { Outcome.Ok(it) }
                        ?: Outcome.Err(RustError.Parse("core.version answer has no version"))
                }
            }
        }

    override suspend fun call(
        op: String,
        request: JsonElement,
    ): Outcome<JsonElement> =
        withContext(RustDispatcher) {
            RustThreading.assertNotMain()
            val body = if (request is JsonNull) ByteArray(0) else request.toString().encodeToByteArray()
            Envelope.decode(RustNative.call(op, body))
        }

    override suspend fun headless(
        kind: String,
        json: String,
    ): String =
        withContext(RustDispatcher) {
            RustThreading.assertNotMain()
            RustNative.headless(kind, json).orEmpty()
        }
}

/**
 * Process-wide entry. `Application.onCreate` calls [init] once; it loads the library and initialises Rust off the
 * main thread, so start-up is not blocked. Later calls return the same [Deferred].
 */
object Rust {
    val events: RustEvents = RustEvents()

    private val core: RustCore = NativeRustCore()
    private val readyFlow = MutableStateFlow(false)
    private var started: Deferred<Outcome<VersionInfo>>? = null

    /** True once [init] succeeded. */
    val ready: StateFlow<Boolean> = readyFlow.asStateFlow()

    /** The core to call; usable after [ready] is true. */
    fun core(): RustCore = core

    @Synchronized
    fun init(
        dataDir: File,
        cacheDir: File,
        appId: String,
        sdk: Int,
        scope: CoroutineScope,
        debug: Boolean = false,
    ): Deferred<Outcome<VersionInfo>> {
        started?.let { return it }
        val result = CompletableDeferred<Outcome<VersionInfo>>()
        started = result
        if (debug) RustThreading.enable()
        scope.launch(RustDispatcher) {
            val outcome =
                try {
                    RustNative.load()
                    dataDir.mkdirs()
                    cacheDir.mkdirs()
                    val answer = RustNative.init(dataDir.absolutePath, cacheDir.absolutePath, appId, sdk)
                    Envelope.decode(answer).let { decoded ->
                        when (decoded) {
                            is Outcome.Err -> decoded
                            is Outcome.Ok -> parseVersion(decoded.value)
                        }
                    }
                } catch (e: UnsatisfiedLinkError) {
                    Outcome.Err(RustError.Unsupported("native library missing: ${e.message}"))
                }
            if (outcome is Outcome.Ok) {
                RustNative.setEventSink(events)
                readyFlow.value = true
            }
            result.complete(outcome)
        }
        return result
    }

    private fun parseVersion(value: JsonElement): Outcome<VersionInfo> {
        val obj = value.asObjectOrNull()
        val version = obj?.get("version")?.asStringOrNull()
        val abi = obj?.get("abi")?.asStringOrNull()?.toIntOrNull()
        return if (version != null && abi != null) {
            Outcome.Ok(VersionInfo(version, abi))
        } else {
            Outcome.Err(RustError.Parse("init answer has no version and abi"))
        }
    }
}
