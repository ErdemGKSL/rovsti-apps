package dev.rosty.platform.audio

import java.io.File

/**
 * A [VoiceRecorder] a test drives by hand: [level] is what the next [amplitude] answers, [interrupt] ends the
 * recording the way the system would, and every start and stop is logged. A started recording writes [bytes] into its
 * file so the take has a size.
 */
class FakeVoiceRecorder(
    /** False: the microphone cannot be opened. */
    var available: Boolean = true,
    private val bytes: Int = 64,
) : VoiceRecorder {
    /** The files recordings were started into, oldest first. */
    val started = mutableListOf<File>()

    /** How many times [stop] was called. */
    var stops = 0
        private set

    /** The amplitude the next [amplitude] call answers (0 to [VoiceRecorder.MAX_AMPLITUDE]). */
    var level = 0

    /** What [stop] answers. */
    var playable = true

    /** The length cap of the recording that runs. */
    var maxMs = 0L
        private set

    private var onInterrupted: ((RecorderStop) -> Unit)? = null

    val recording: Boolean get() = onInterrupted != null

    override fun start(
        file: File,
        maxMs: Long,
        onInterrupted: (RecorderStop) -> Unit,
    ): Boolean {
        if (!available) return false
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
        started += file
        this.maxMs = maxMs
        this.onInterrupted = onInterrupted
        return true
    }

    override fun amplitude(): Int = if (recording) level else 0

    override fun stop(): Boolean {
        stops++
        val was = recording
        onInterrupted = null
        return was && playable
    }

    /** The system ends the recording ([RecorderStop.FocusLost]: a call came in). */
    fun interrupt(why: RecorderStop) {
        onInterrupted?.invoke(why)
    }
}
