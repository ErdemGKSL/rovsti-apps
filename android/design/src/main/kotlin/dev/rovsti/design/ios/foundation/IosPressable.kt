/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/foundation/IosPressable.kt
 * Changes for Rovsti: package dev.rovsti.design.ios.foundation; imports; `composed {` replaced by the node-based
 * `rowPress` (no composed modifiers in Rovsti, see ConventionTest), which draws the same flat grey behind the content
 * in the theme's fillTertiary; the unused onLongClick parameter dropped.
 */
package dev.rovsti.design.ios.foundation

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import dev.rovsti.design.press.rowPress

/**
 * The iOS row press state: the row fills with a flat grey for as long as a finger is on
 * it, and clears the instant it lifts.
 *
 * This is the whole feedback model on iOS: no ripple, no spread, no release animation.
 * Rows built with a bare `clickable` gave no feedback at all, which reads as a dead
 * control rather than a restrained one.
 *
 * The highlight is drawn *behind* the row content, so it has to sit before any padding
 * in the chain and after whatever clips the row's corners.
 */
fun Modifier.iosRowPress(
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
): Modifier =
    this
        .rowPress(enabled = enabled && onClick != null)
        .then(
            if (onClick !=
                null
            ) {
                Modifier.clickable(indication = null, interactionSource = null, enabled = enabled, onClick = onClick)
            } else {
                Modifier
            },
        )
