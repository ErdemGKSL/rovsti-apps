package dev.rovsti.core.rust

import dev.rovsti.core.Envelope
import dev.rovsti.core.MainThreadCheck
import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustEvents
import dev.rovsti.core.RustNative
import dev.rovsti.core.RustThreading
import java.nio.file.Files

/** One initialised library per JVM (`forkEvery = 0`): the native side keeps process-wide state. */
internal object TestRust {
    val events = RustEvents()
    val core = NativeRustCore()

    init {
        RustNative.load()
        RustThreading.enable(MainThreadCheck { false })
        val dir = Files.createTempDirectory("rovsti-bridge").toFile().apply { deleteOnExit() }
        val answer = Envelope.decode(RustNative.init("$dir/data", "$dir/cache", "dev.rovsti.test", 34))
        check(answer is Outcome.Ok) { "init failed: $answer" }
        RustNative.setEventSink(events)
    }

    fun ensure() = Unit
}
