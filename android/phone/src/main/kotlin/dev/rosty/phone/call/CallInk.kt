// Rosty Project (C) 2026, GPL-3.0-or-later. Original code (not ported).
package dev.rosty.phone.call

import androidx.compose.ui.graphics.Color
import dev.rosty.design.media.CallScrim

/**
 * The ink of the call screens (Incoming, In-Call, the multi-call views, the tone keypad), laid over the aura or over
 * the caller's photo. The photo's scrim ([CallScrim]) is solved against [OnAuraSecondary], so the two stay in step.
 */
internal val OnAura: Color = Color.White

/** The faint captions: "Incoming call", the number, the timer, the held label. White 85 %. */
internal val OnAuraSecondary: Color = CallScrim.SecondaryInk
