package dev.rosty.contacts

import dev.rosty.core.Outcome
import dev.rosty.core.Rust
import dev.rosty.core.ops.NativeContactsOps
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
        val lib = File(System.getProperty("rosty.hostLib").orEmpty())
        assumeTrue(
            "host librosty_ffi not built (run :core-bridge:cargoBuildHost)",
            lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rosty_ffi") },
        )
        root = Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }
        val started =
            runBlocking {
                Rust
                    .init(
                        File(root, "data"),
                        File(root, "cache"),
                        "dev.rosty.contacts",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            }
        assertTrue("Rust.init: $started", started is Outcome.Ok)
        ops = NativeContactsOps(Rust.core())
    }

    fun storeDir(): String = File(root, "store").apply { mkdirs() }.absolutePath
}
