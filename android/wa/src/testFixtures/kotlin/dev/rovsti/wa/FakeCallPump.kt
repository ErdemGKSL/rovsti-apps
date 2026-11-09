package dev.rovsti.wa

import dev.rovsti.platform.wa.CallFrames
import dev.rovsti.wa.calls.CallAudioPump
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [CallAudioPump] that does what [dev.rovsti.platform.wa.WaCallAudio] does with real threads and no hardware: a
 * microphone thread pushes silent frames and a speaker thread pulls until it is interrupted or the call ends.
 * [starts] and [stops] count the calls, [threads] are the two threads of the running pump.
 */
class FakeCallPump : CallAudioPump {
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    val speakerRequests = mutableListOf<Boolean>()
    var failToStart = false

    @Volatile var threads: List<Thread> = emptyList()
        private set

    @Volatile var pushed = 0
        private set

    override fun start(frames: CallFrames): Boolean {
        starts.incrementAndGet()
        if (failToStart) return false
        val mic =
            Thread({
                val frame = ByteArray(FRAME_BYTES)
                while (!Thread.currentThread().isInterrupted) {
                    frames.push(frame)
                    pushed++
                    if (!pause()) return@Thread
                }
            }, "fake-wa-mic")
        val speaker =
            Thread({
                val out = ByteArray(FRAME_BYTES)
                while (!Thread.currentThread().isInterrupted) {
                    if (frames.pull(out) < 0) return@Thread
                    if (!pause()) return@Thread
                }
            }, "fake-wa-speaker")
        threads =
            listOf(mic, speaker).onEach {
                it.isDaemon = true
                it.start()
            }
        return true
    }

    override fun stop(): Boolean {
        stops.incrementAndGet()
        val running = threads
        running.forEach { it.interrupt() }
        return running.isNotEmpty()
    }

    override fun setSpeaker(on: Boolean): Boolean {
        speakerRequests += on
        return true
    }

    private fun pause(): Boolean =
        try {
            Thread.sleep(2)
            true
        } catch (_: InterruptedException) {
            false
        }

    private companion object {
        const val FRAME_BYTES = 1920
    }
}
