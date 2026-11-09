package dev.rovsti.contacts

import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.ops.NativeContactsOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files

/**
 * The REAL Rust core on the host library that `:core-bridge:cargoBuildHost` builds, with a data directory of its own.
 * The test is skipped when the library is absent (as `EditorRig` does).
 */
internal class HostRust(
    prefix: String,
) {
    val root: File
    val ops: NativeContactsOps

    init {
        val lib = File(System.getProperty("rovsti.hostLib").orEmpty())
        assumeTrue(
            "host librovsti_ffi not built (run :core-bridge:cargoBuildHost)",
            lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rovsti_ffi") },
        )
        root = Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }
        val started =
            runBlocking {
                Rust
                    .init(
                        File(root, "data"),
                        File(root, "cache"),
                        "dev.rovsti.contacts",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            }
        assertTrue("Rust.init: $started", started is Outcome.Ok)
        ops = NativeContactsOps(Rust.core())
    }

    fun storeDir(): String = File(root, "store").apply { mkdirs() }.absolutePath
}
