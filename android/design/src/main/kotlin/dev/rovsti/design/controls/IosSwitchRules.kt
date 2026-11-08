// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.controls

import kotlin.math.abs

/**
 * The decisions of a switch gesture as pure functions, so every threshold has a JVM test. The ported liquid toggle has none of
 * these rules (its own gesture toggles on any release, including the one that ends a page scroll); they come from
 * glisse `src/switch.tsx:107-179`:
 * - a drag starts after 4 dp of sideways travel (`Math.abs(dx) < 4` returns);
 * - a tap only counts when it ends within 24 dp of the switch (`box.top - 24` / `box.bottom + 24`);
 * - a drag lands on the side the thumb was let go on (`value > 0.5`);
 * - the first [dev.rovsti.design.press.HOLD_MS] belong to the page (the hold rule lives in `press/Hold.kt`).
 */
internal object IosSwitchRules {
    /** Sideways travel that turns a press into a drag (glisse: `Math.abs(dx) < 4`). */
    const val DRAG_START_DP = 4f

    /** A tap still counts when it ends this far above or below the switch (glisse: `box.top - 24`). */
    const val NEAR_DP = 24f

    /** True once the finger has moved far enough sideways to be dragging the thumb. */
    fun startsDrag(dxDp: Float): Boolean = abs(dxDp) >= DRAG_START_DP

    /** True when a touch ending at [yPx] (in the hit box of [boxHeightPx]) is within [nearPx] of the box. */
    fun nearBox(
        yPx: Float,
        boxHeightPx: Float,
        nearPx: Float,
    ): Boolean = yPx >= -nearPx && yPx <= boxHeightPx + nearPx

    /**
     * The state after the finger lifts. A drag lands where it was let go ([value] above half); a tap toggles only when
     * it ends [near] the switch, otherwise nothing changes.
     */
    fun onRelease(
        checked: Boolean,
        moved: Boolean,
        value: Float,
        near: Boolean,
    ): Boolean =
        when {
            moved -> value > 0.5f
            near -> !checked
            else -> checked
        }
}
