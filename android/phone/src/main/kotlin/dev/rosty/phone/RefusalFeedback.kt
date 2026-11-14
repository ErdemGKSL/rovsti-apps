package dev.rosty.phone

import dev.rosty.design.haptics.RostyHaptics

/** F31: a refusal from the core is a failure, so every screen plays the reject haptic for it and never the confirm one. */
fun playRefusalHaptic(haptics: RostyHaptics) = haptics.reject()
