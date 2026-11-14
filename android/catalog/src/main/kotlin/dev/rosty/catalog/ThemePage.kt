package dev.rosty.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.rosty.design.theme.RostyColors
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.design.theme.contrastRatio
import dev.rosty.design.theme.glassOver

private fun ratio(
    fg: Color,
    bg: Color,
    backdrop: Color = Color.White,
) = "%.1f:1".format(contrastRatio(fg, bg, backdrop))

@Composable
private fun PageTitle(
    text: String,
    detail: String,
) {
    RostyText(text, style = RostyTheme.type.largeTitle, modifier = Modifier.padding(top = Spacing.s))
    RostyText(
        detail,
        style = RostyTheme.type.subheadline,
        color = RostyTheme.colors.labelSecondary,
        modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sectionGap),
    )
}

/** Colour swatches with the contrast the theme measures for each label; numbers are computed, not typed. */
@Composable
internal fun PalettePage() {
    val c = RostyTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding()) {
        item {
            PageTitle("Palette", "Contrast is measured. Labels need 4.5:1, tertiary 3:1.")
            SectionLabel("Labels on surfaces")
            LabelBlock("Page", c.page)
            LabelBlock("Grouped surface", c.groupedSurface, Modifier.padding(top = Spacing.s))
            SectionLabel("Accent and status", Modifier.padding(top = Spacing.sectionGap))
            AccentBlock(c)
            SectionLabel("Glass over backdrops", Modifier.padding(top = Spacing.sectionGap))
            GlassRow()
            SectionLabel("Lines and fills", Modifier.padding(top = Spacing.sectionGap))
            FillRow(c)
        }
    }
}

@Composable
private fun LabelBlock(
    name: String,
    bg: Color,
    modifier: Modifier = Modifier,
) {
    val c = RostyTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RostyShapes.grouped)
            .background(bg)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.textGap),
    ) {
        RostyText(name, style = RostyTheme.type.footnote, color = c.labelSecondary)
        LabelLine("Primary label", c.labelPrimary, bg)
        LabelLine("Secondary label", c.labelSecondary, bg)
        LabelLine("Tertiary label", c.labelTertiary, bg)
    }
}

@Composable
private fun LabelLine(
    text: String,
    fg: Color,
    bg: Color,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RostyText(text, color = fg, modifier = Modifier.weight(1f))
        RostyText(ratio(fg, bg), style = RostyTheme.type.subheadline, color = fg, textAlign = TextAlign.End)
    }
}

@Composable
private fun AccentBlock(c: RostyColors) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RostyShapes.grouped)
            .background(c.groupedSurface)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RostyShapes.capsule)
                    .background(c.accent)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
            ) {
                RostyText("Selected", style = RostyTheme.type.headline, color = c.onAccent)
            }
            RostyText(
                "onAccent on accent ${ratio(c.onAccent, c.accent)}",
                style = RostyTheme.type.subheadline,
                color = c.labelSecondary,
            )
        }
        LabelLine("Accent text", c.accent, c.groupedSurface)
        LabelLine("Destructive text", c.destructive, c.groupedSurface)
        LabelLine("Success text", c.success, c.groupedSurface)
    }
}

@Composable
private fun GlassRow() {
    val c = RostyTheme.colors
    val g = RostyTheme.glass
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        for ((name, backdrop) in listOf("White" to Color.White, "Grey" to Color(0xFF808080), "Black" to Color.Black)) {
            val fill = glassOver(g.glass, c.glassScrim, backdrop)
            Column(
                Modifier
                    .weight(1f)
                    .clip(RostyShapes.grouped)
                    .background(backdrop)
                    .padding(Spacing.xs),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RostyShapes.grouped)
                        .background(fill)
                        .padding(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.textGap),
                ) {
                    RostyText(name, style = RostyTheme.type.footnote, color = c.labelSecondary)
                    RostyText(
                        ratio(c.labelPrimary, fill),
                        style = RostyTheme.type.subheadline,
                        color = c.labelPrimary,
                    )
                    RostyText(
                        ratio(c.labelSecondary, fill),
                        style = RostyTheme.type.subheadline,
                        color = c.labelSecondary,
                    )
                    RostyText(
                        ratio(c.labelTertiary, fill),
                        style = RostyTheme.type.subheadline,
                        color = c.labelTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FillRow(c: RostyColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RostyShapes.grouped)
            .background(c.groupedSurface)
            .padding(Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        for ((name, color) in listOf(
            "Fill 1" to c.fillPrimary,
            "Fill 2" to c.fillSecondary,
            "Fill 3" to c.fillTertiary,
            "Line" to c.separator,
        )) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = Spacing.xs,
                        ).clip(RostyShapes.grouped)
                        .background(color)
                        .padding(vertical = Spacing.l),
                )
                RostyText(name, style = RostyTheme.type.caption1, color = c.labelSecondary)
            }
        }
    }
}

/** Every type style, sample text in the style and its numbers beside the name. Scrolls; wraps at font scale 2. */
@Composable
internal fun TypeScalePage() {
    val c = RostyTheme.colors
    val styles = RostyTheme.type.all
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding())) {
        PageTitle("Type scale", "System font, sp units, explicit line heights.")
        for ((name, style) in styles) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.m)) {
                RostyText(
                    "$name  ${style.fontSize.value.toInt()}/${style.lineHeight.value.toInt()} sp" +
                        if (style.fontWeight ==
                            FontWeight.Bold
                        ) {
                            " bold"
                        } else if (style.fontWeight ==
                            FontWeight.SemiBold
                        ) {
                            " semibold"
                        } else {
                            ""
                        },
                    style = RostyTheme.type.caption1,
                    color = c.labelSecondary,
                )
                RostyText("Rosty sample text, wraps to more lines when the font is large", style = style)
            }
        }
    }
}
