package dev.rosty.stops.map

import android.os.Looper
import dev.rosty.core.Outcome
import dev.rosty.core.Rust
import dev.rosty.core.ops.NativeStopsOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files

/**
 * The REAL Rust core on the host library that `:core-bridge:cargoBuildHost` builds, for the picking tests: the pick
 * state machine, the snap rule, the labels and the circle are Rust's, so the tests run the real thing. Skipped when
 * the library is absent.
 */
internal class MapHostRust {
    val ops: PickOps

    init {
        val lib = File(System.getProperty("rosty.hostLib").orEmpty())
        assumeTrue(
            "host librosty_ffi not built (run :core-bridge:cargoBuildHost)",
            lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rosty_ffi") },
        )
        val root = Files.createTempDirectory("stops-map").toFile().apply { deleteOnExit() }
        val started =
            runBlocking {
                Rust
                    .init(
                        File(root, "data"),
                        File(root, "cache"),
                        "dev.rosty.stops",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            }
        assertTrue("Rust.init: $started", started is Outcome.Ok)
        ops = StopsPickOps(NativeStopsOps(Rust.core()))
    }

    companion object {
        /**
         * Runs the main looper until [controller] has answered every gesture so far. The controller's scope is on the
         * main thread and the Rust calls come back to it from their own thread.
         */
        fun settle(controller: PickController) {
            val deadline = System.currentTimeMillis() + 10_000
            while (true) {
                shadowOf(Looper.getMainLooper()).idle()
                if (controller.idle) return
                check(System.currentTimeMillis() < deadline) { "the pick controller did not settle" }
                Thread.sleep(2)
            }
        }
    }
}
