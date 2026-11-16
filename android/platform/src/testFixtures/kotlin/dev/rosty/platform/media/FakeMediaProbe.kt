package dev.rosty.platform.media

import java.io.File

/**
 * A [MediaProbe] a test fills by file name: [answers] says what each file measures (a file without an entry measures
 * as nothing), and every call is logged in [measured].
 */
class FakeMediaProbe(
    val answers: MutableMap<String, MediaMeasure> = mutableMapOf(),
) : MediaProbe {
    /** The names of the files measured, oldest first. */
    val measured = mutableListOf<String>()

    override suspend fun measure(
        file: File,
        mime: String,
    ): MediaMeasure? {
        measured += file.name
        return answers[file.name]
    }
}
