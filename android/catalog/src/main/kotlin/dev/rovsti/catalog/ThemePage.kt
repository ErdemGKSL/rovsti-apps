package dev.rovsti.catalog

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
import dev.rovsti.design.theme.RovstiColors
import dev.rovsti.design.theme.RovstiShapes
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.theme.contrastRatio
import dev.rovsti.design.theme.glassOver

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
    RovstiText(text, style = RovstiTheme.type.largeTitle, modifier = Modifier.padding(top = Spacing.s))
    RovstiText(
        detail,
        style = RovstiTheme.type.subheadline,
        color = RovstiTheme.colors.labelSecondary,
        modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sectionGap),
    )
}

/** Colour swatches with the contrast the theme measures for each label; numbers are computed, not typed. */
@Composable
internal fun PalettePage() {
    val c = RovstiTheme.colors
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
    val c = RovstiTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RovstiShapes.grouped)
            .background(bg)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.textGap),
    ) {
        RovstiText(name, style = RovstiTheme.type.footnote, color = c.labelSecondary)
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
        RovstiText(text, color = fg, modifier = Modifier.weight(1f))
        RovstiText(ratio(fg, bg), style = RovstiTheme.type.subheadline, color = fg, textAlign = TextAlign.End)
    }
}

@Composable
private fun AccentBlock(c: RovstiColors) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RovstiShapes.grouped)
            .background(c.groupedSurface)
            .padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RovstiShapes.capsule)
                    .background(c.accent)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
            ) {
                RovstiText("Selected", style = RovstiTheme.type.headline, color = c.onAccent)
            }
            RovstiText(
                "onAccent on accent ${ratio(c.onAccent, c.accent)}",
                style = RovstiTheme.type.subheadline,
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
    val c = RovstiTheme.colors
    val g = RovstiTheme.glass
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        for ((name, backdrop) in listOf("White" to Color.White, "Grey" to Color(0xFF808080), "Black" to Color.Black)) {
            val fill = glassOver(g.glass, c.glassScrim, backdrop)
            Column(
                Modifier
                    .weight(1f)
                    .clip(RovstiShapes.grouped)
                    .background(backdrop)
                    .padding(Spacing.xs),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RovstiShapes.grouped)
                        .background(fill)
                        .padding(Spacing.m),
                    verticalArrangement = Arrangement.spacedBy(Spacing.textGap),
                ) {
                    RovstiText(name, style = RovstiTheme.type.footnote, color = c.labelSecondary)
                    RovstiText(
                        ratio(c.labelPrimary, fill),
                        style = RovstiTheme.type.subheadline,
                        color = c.labelPrimary,
                    )
                    RovstiText(
                        ratio(c.labelSecondary, fill),
                        style = RovstiTheme.type.subheadline,
                        color = c.labelSecondary,
                    )
                    RovstiText(
                        ratio(c.labelTertiary, fill),
                        style = RovstiTheme.type.subheadline,
                        color = c.labelTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FillRow(c: RovstiColors) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RovstiShapes.grouped)
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
                        ).clip(RovstiShapes.grouped)
                        .background(color)
                        .padding(vertical = Spacing.l),
                )
                RovstiText(name, style = RovstiTheme.type.caption1, color = c.labelSecondary)
            }
        }
    }
}

/** Every type style, sample text in the style and its numbers beside the name. Scrolls; wraps at font scale 2. */
@Composable
internal fun TypeScalePage() {
    val c = RovstiTheme.colors
    val styles = RovstiTheme.type.all
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding())) {
        PageTitle("Type scale", "System font, sp units, explicit line heights.")
        for ((name, style) in styles) {
            Column(Modifier.fillMaxWidth().padding(bottom = Spacing.m)) {
                RovstiText(
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
                    style = RovstiTheme.type.caption1,
                    color = c.labelSecondary,
                )
                RovstiText("Rovsti sample text, wraps to more lines when the font is large", style = style)
            }
        }
    }
}
