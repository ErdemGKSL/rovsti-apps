package dev.rosty.messages.theme

import androidx.compose.ui.graphics.Color

/** The colours of Messages that are not theme tokens. */
object MessagesColors {
    /**
     * My bubble in both themes: the light theme's deep accent with white text (6.6:1). The dark theme's accent is a light
     * blue for links and buttons on black; as a bubble fill it would need dark text, which iOS never does.
     */
    val OutgoingBubble = Color(0xFF0051D5)
    val OutgoingInk = Color.White
}
