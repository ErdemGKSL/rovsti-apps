package dev.rosty.testing

import dev.rosty.design.haptics.HapticEvent
import dev.rosty.design.haptics.RostyHaptics

/** A haptics fake that remembers what was played, in order. */
class RecordingHaptics : RostyHaptics {
    val events = mutableListOf<HapticEvent>()

    override fun play(event: HapticEvent) {
        events += event
    }
}
