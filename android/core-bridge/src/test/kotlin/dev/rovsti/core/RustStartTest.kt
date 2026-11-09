package dev.rovsti.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Start-up crash on device: the first `RustNative.call` of a view model ran on [RustDispatcher] next to `Rust.init`
 * and could beat `System.loadLibrary`, throwing `UnsatisfiedLinkError`. Calls now wait for [Rust.init]. This JVM has
 * no `librovsti_ffi`, so init fails: every call made right after it must get that error as an [Outcome], not throw.
 */
class RustStartTest {
    @Test
    fun callsRightAfterInitWaitForItInsteadOfThrowing() =
        runBlocking {
            val dir = Files.createTempDirectory("rovsti-start").toFile().apply { deleteOnExit() }
            Rust.init(dir.resolve("data"), dir.resolve("cache"), "dev.rovsti.test", 34, CoroutineScope(Dispatchers.IO))
            val answers =
                (1..16)
                    .map { async(Dispatchers.IO) { Rust.core().call("core.version") } }
                    .awaitAll()
            for (answer in answers) {
                assertTrue("$answer", answer is Outcome.Err && answer.error is RustError.Unsupported)
            }
            assertEquals("", Rust.core().headless("sms.received", "{}"))
        }
}
