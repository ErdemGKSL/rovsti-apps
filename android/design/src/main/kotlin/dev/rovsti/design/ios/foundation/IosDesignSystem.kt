/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/foundation/IosDesignSystem.kt
 * Changes for Rovsti: package dev.rovsti.design.ios.foundation; imports.
 */
package dev.rovsti.design.ios.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import dev.rovsti.design.theme.RovstiTheme

/**
 * Semantic iOS-style colors for content and Liquid Glass surfaces.
 *
 * Use these roles instead of hard-coded colors so light/dark themes stay
 * consistent. User-selected background and text colors override the defaults
 * when set; clearing them restores the system palette.
 */
object IosDesignSystem {
    /** Screen canvas. */
    val IosColors.canvas: Color get() = systemGroupedBackground

    /** Card/group on the canvas. */
    val IosColors.card: Color get() = secondarySystemGroupedBackground

    /** Nested group inside a card. */
    val IosColors.nested: Color get() = tertiarySystemGroupedBackground

    /** Plain-screen background (non-grouped screens). */
    val IosColors.plainBackground: Color get() = systemBackground

    /** Card on a plain screen. */
    val IosColors.plainCard: Color get() = secondarySystemBackground

    /** Primary text — safety/critical copy always uses this. */
    val IosColors.primaryText: Color get() = label

    /** Secondary text — metadata, subtitles. */
    val IosColors.secondaryText: Color get() = secondaryLabel

    /** Tertiary text — captions, footnotes. */
    val IosColors.tertiaryText: Color get() = tertiaryLabel

    /** Hairline dividers. */
    val IosColors.hairline: Color get() = separator
}

/**
 * Standard material levels, thin→thick. Thicker = more opaque = fine text;
 * thinner = more context. Choose by semantic need, not apparent color.
 */
enum class IosMaterial { UltraThin, Thin, Regular, Thick }

/** Fill alpha each material level paints over the backdrop. */
fun IosMaterial.fillAlpha(): Float =
    when (this) {
        IosMaterial.UltraThin -> 0.28f
        IosMaterial.Thin -> 0.45f
        IosMaterial.Regular -> 0.65f
        IosMaterial.Thick -> 0.85f
    }

/**
 * Vibrant label for [material]. Quaternary never passes on thin/ultraThin
 * (contrast too low) — falls back to tertiary. Tier 0..3 =
 * label → secondary → tertiary → quaternary.
 */
fun IosColors.vibrantLabel(
    tier: Int,
    material: IosMaterial = IosMaterial.Regular,
): Color {
    val safeTier =
        if (tier >= 3 &&
            (material == IosMaterial.Thin || material == IosMaterial.UltraThin)
        ) {
            2
        } else {
            tier.coerceIn(0, 3)
        }
    return when (safeTier) {
        0 -> label
        1 -> secondaryLabel
        2 -> tertiaryLabel
        else -> quaternaryLabel
    }
}

/**
 * WCAG contrast floor from the skill: minimum 4.5:1, strive 7:1 for custom
 * small text. Returns true when [fg] on [bg] meets [ratio].
 */
fun meetsContrast(
    fg: Color,
    bg: Color,
    ratio: Float = 4.5f,
): Boolean {
    fun lum(c: Color): Double {
        val l = c.luminance().toDouble()
        return l
    }
    val a = lum(fg) + 0.05
    val b = lum(bg) + 0.05
    return (maxOf(a, b) / minOf(a, b)) >= ratio
}

/**
 * Default background: [fallback] when given, else the Rovsti page colour. (Convx also honoured a user colour
 * override from preferences; Rovsti has none.)
 */
@Composable
fun rememberDesignBackground(fallback: Color? = null): Color =
    if (fallback != null && fallback.isSpecified) fallback else RovstiTheme.colors.page

/** Default text: the semantic label. (Convx also honoured a user text colour override; Rovsti has none.) */
@Composable
fun rememberDesignText(): Color = IosTheme.colors.label

/**
 * Chrome content color: monochromatic label by default (dark over light
 * content, light over dark). Accent only when [emphasize] (single primary
 * action / status) — never tint multiple chrome controls.
 */
@Composable
@ReadOnlyComposable
fun chromeContent(emphasize: Boolean = false): Color = if (emphasize) IosTheme.colors.accent else IosTheme.colors.label

/** Label color on a prominent (accent-filled) chrome control — always white. */
val chromeOnProminent: Color get() = Color.White
