package dev.rovsti.phone

import dev.rovsti.design.haptics.RovstiHaptics

/** F31: a refusal from the core is a failure, so every screen plays the reject haptic for it and never the confirm one. */
fun playRefusalHaptic(haptics: RovstiHaptics) = haptics.reject()
