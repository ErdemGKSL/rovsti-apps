package dev.rosty.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.rosty.design.press.glassPress
import dev.rosty.design.press.rowPress
import dev.rosty.design.press.scalePress
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing

/**
 * The three press effects, each above a line of neighbouring text. Pressing must never move that text: the effects are
 * layer transforms (glass, scale) or a flat fill (row). Test tags: `press-target-*` is the layout box of the control
 * (outside the transform), `press-visual-*` the content inside it (which does grow), `press-neighbour-*` the text.
 */
@Composable
internal fun PressPage() {
    val c = RostyTheme.colors
    var taps by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding())) {
        RostyText("Press feedback", style = RostyTheme.type.largeTitle, modifier = Modifier.padding(top = Spacing.s))
        RostyText(
            "Controls change by transform or fill only. Layout never moves, so the text under each control stays put.",
            style = RostyTheme.type.subheadline,
            color = c.labelSecondary,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sectionGap),
        )

        SectionLabel("Glass press")
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(
                Modifier
                    .testTag("press-target")
                    .width(240.dp)
                    .heightIn(min = Sizes.touch)
                    .glassPress(glow = true, glowShape = RostyShapes.capsule),
            ) {
                Box(
                    Modifier
                        .testTag("press-visual")
                        .fillMaxWidth()
                        .heightIn(min = Sizes.touch)
                        .clip(RostyShapes.capsule)
                        .background(c.groupedSurface)
                        .border(0.5.dp, c.opaqueSeparator, RostyShapes.capsule)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { taps++ },
                    contentAlignment = Alignment.Center,
                ) {
                    RostyText("Press me", color = c.accent)
                }
            }
            RostyText(
                "Text below the button. Taps: $taps",
                style = RostyTheme.type.footnote,
                color = c.labelSecondary,
                modifier = Modifier.testTag("press-neighbour"),
            )
        }

        SectionLabel("Scale press", Modifier.padding(top = Spacing.sectionGap))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Box(
                Modifier
                    .testTag("press-target-scale")
                    .fillMaxWidth()
                    .scalePress(),
            ) {
                Row(
                    Modifier
                        .testTag("press-visual-scale")
                        .fillMaxWidth()
                        .clip(RostyShapes.card)
                        .background(c.groupedSurface)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) {}
                        .padding(Spacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(Sizes.avatar).clip(RostyShapes.capsule).background(c.accent))
                    Column(Modifier.padding(start = Spacing.m)) {
                        RostyText("A card")
                        RostyText("Shrinks to 96 percent", style = RostyTheme.type.footnote, color = c.labelSecondary)
                    }
                }
            }
            RostyText(
                "Text below the card.",
                style = RostyTheme.type.footnote,
                color = c.labelSecondary,
                modifier = Modifier.testTag("press-neighbour-scale"),
            )
        }

        SectionLabel("Row press", Modifier.padding(top = Spacing.sectionGap))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RostyShapes.grouped)
                    .background(c.groupedSurface),
            ) {
                Row(
                    Modifier
                        .testTag("press-target-row")
                        .fillMaxWidth()
                        .heightIn(min = Sizes.rowMin)
                        .rowPress()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) {}
                        .padding(horizontal = Spacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RostyText("Flat highlight", modifier = Modifier.weight(1f))
                    RostyText("›", color = c.labelSecondary)
                }
            }
            RostyText(
                "Text below the row.",
                style = RostyTheme.type.footnote,
                color = c.labelSecondary,
                modifier = Modifier.testTag("press-neighbour-row"),
            )
        }
    }
}
