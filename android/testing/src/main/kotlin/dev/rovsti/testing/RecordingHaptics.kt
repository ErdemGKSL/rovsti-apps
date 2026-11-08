package dev.rovsti.testing

import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.RovstiHaptics

/** A haptics fake that remembers what was played, in order. */
class RecordingHaptics : RovstiHaptics {
    val events = mutableListOf<HapticEvent>()

    override fun play(event: HapticEvent) {
        events += event
    }
}
