package dev.rosty.stops

import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.Rust
import dev.rosty.core.RustCore
import dev.rosty.core.RustError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files

/**
 * The REAL Rust core on the host library that `:core-bridge:cargoBuildHost` builds, for the ops that read no file:
 * the saved-stop edits, the list, the distances, the share texts, the QR code. Started once per test JVM in a temp
 * data directory; a test using it is skipped when the library is absent.
 */
object HostCore {
    private val started: Boolean by lazy {
        val lib = File(System.getProperty("rosty.hostLib").orEmpty())
        val present = lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rosty_ffi") }
        if (!present) return@lazy false
        val root = Files.createTempDirectory("stops-host").toFile().apply { deleteOnExit() }
        val outcome =
            runBlocking {
                Rust
                    .init(
                        File(root, "data"),
                        File(root, "cache"),
                        "dev.rosty.stops",
                        SDK,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            }
        outcome is Outcome.Ok
    }

    /** The real core; skips the calling test when the host library is not built. */
    fun real(): RustCore {
        assumeTrue("host librosty_ffi not built (run :core-bridge:cargoBuildHost)", started)
        return Rust.core()
    }

    private const val SDK = 35
}

/**
 * [fake]'s scripted answers first (the files, the prefs, the trip), the [real] core for every op the fake does not
 * script.
 */
class HybridCore(
    private val fake: FakeRustCore,
    private val real: RustCore,
) : RustCore {
    override suspend fun version(): Outcome<String> = real.version()

    override suspend fun call(
        op: String,
        request: JsonElement,
    ): Outcome<JsonElement> {
        val answer = fake.call(op, request)
        val unscripted =
            answer is Outcome.Err && answer.error is RustError.Unsupported &&
                answer.error.message
                    .orEmpty()
                    .startsWith(UNSCRIPTED)
        return if (unscripted) real.call(op, request) else answer
    }

    override suspend fun headless(
        kind: String,
        json: String,
    ): String = fake.headless(kind, json).ifEmpty { real.headless(kind, json) }

    private companion object {
        /** How [FakeRustCore] answers an op it has no script for. */
        const val UNSCRIPTED = "fake has no op"
    }
}
