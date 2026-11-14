package dev.rosty.core.rust

import dev.rosty.core.Envelope
import dev.rosty.core.MainThreadCheck
import dev.rosty.core.NativeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustEvents
import dev.rosty.core.RustNative
import dev.rosty.core.RustThreading
import java.nio.file.Files

/** One initialised library per JVM (`forkEvery = 0`): the native side keeps process-wide state. */
internal object TestRust {
    val events = RustEvents()
    val core = NativeRustCore()

    init {
        RustNative.load()
        RustThreading.enable(MainThreadCheck { false })
        val dir = Files.createTempDirectory("rosty-bridge").toFile().apply { deleteOnExit() }
        val answer = Envelope.decode(RustNative.init("$dir/data", "$dir/cache", "dev.rosty.test", 34))
        check(answer is Outcome.Ok) { "init failed: $answer" }
        RustNative.setEventSink(events)
    }

    fun ensure() = Unit
}
