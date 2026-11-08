package dev.rovsti.catalog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.glass.GlassSpec
import dev.rovsti.design.glass.GlassTier
import dev.rovsti.design.glass.LocalGlassConditions
import dev.rovsti.design.glass.glass
import dev.rovsti.design.glass.glassSource
import dev.rovsti.design.glass.glassTier
import dev.rovsti.design.glass.rememberGlassSource
import dev.rovsti.design.glass.rimImage
import dev.rovsti.design.theme.RovstiShapes
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing

/**
 * Glass over a striped backdrop: three bar capsules and a popover-sized card, all drawn as siblings AFTER the
 * recorded source. The tier comes from [LocalGlassConditions], so tests pick Flat, Frost or Liquid by providing
 * conditions. The rim light the Liquid tier paints is shown separately by [RimTwinPanel].
 */
@Composable
internal fun GlassPage() {
    val c = RovstiTheme.colors
    val tokens = RovstiTheme.glass
    val source = rememberGlassSource()
    val tier = glassTier(LocalGlassConditions.current)
    val bar = GlassSpec.bar(tokens, c, tier)
    val popover = GlassSpec.popover(tokens, c, tier)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassSource(source, c.page)) { StripedBackdrop() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding()),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Box(
                Modifier
                    .testTag("glass-tier")
                    .glass(source, bar)
                    .heightIn(min = Sizes.button)
                    .padding(horizontal = Spacing.l),
                contentAlignment = Alignment.CenterStart,
            ) {
                RovstiText("Glass: ${tier.name} tier", style = RovstiTheme.type.headline)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                for (label in listOf("Messages", "Calls", "Stops")) {
                    Box(
                        Modifier
                            .weight(1f)
                            .testTag("glass-capsule-$label")
                            .glass(source, bar)
                            .heightIn(min = Sizes.button),
                        contentAlignment = Alignment.Center,
                    ) {
                        RovstiText(label, style = RovstiTheme.type.body)
                    }
                }
            }
            Column(
                Modifier
                    .testTag("glass-popover")
                    .fillMaxWidth()
                    .glass(source, popover)
                    .padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                RovstiText("Popover surface", style = RovstiTheme.type.headline)
                RovstiText(
                    "Blur 8 dp, saturation 1.8, a weak lens and a hairline. The label stays readable over any stripe.",
                    style = RovstiTheme.type.subheadline,
                    color = c.labelSecondary,
                )
            }
            if (tier == GlassTier.Flat) {
                RovstiText(
                    "Flat: fill at 85 %, hairline, shadow. No capture.",
                    style = RovstiTheme.type.footnote,
                    modifier = Modifier.glass(source, bar.copy(cornerDp = 12f)).padding(Spacing.s),
                )
            }
        }
    }
}

/**
 * The rim light as the CPU twin draws it, four pixels per px on a 50 % grey panel (where glisse measured iOS): bright
 * along the top and left edges and the bottom right corner, fading over the other two corners.
 */
@Composable
internal fun RimTwinPanel() {
    val tokens = RovstiTheme.glass
    val colors = RovstiTheme.colors
    val shine = tokens.rimShine
    val edge = tokens.edge
    // glisse measured iOS at a rim alpha of 0.65 over 50 % grey; the last row uses the theme's own (dimmer) shine.
    val shapes =
        listOf(
            Rim("Capsule 160 x 56, shine 0.65", 160, 56, 28f, 0.65f),
            Rim("Card 160 x 96, corner 24, shine 0.65", 160, 96, 24f, 0.65f),
            Rim("Circle 56, shine 0.65", 56, 56, 28f, 0.65f),
            Rim("Capsule at the theme shine ${"%.2f".format(shine)}", 160, 56, 28f, shine),
        )
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.page)
            .verticalScroll(rememberScrollState())
            .padding(pagePadding()),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        RovstiText("Rim light, CPU twin", style = RovstiTheme.type.largeTitle)
        RovstiText(
            "Lit from the top left, reflected on the bottom right. The Liquid tier paints the same shape with AGSL.",
            style = RovstiTheme.type.subheadline,
            color = colors.labelSecondary,
        )
        Column(
            Modifier
                .testTag("rim-panel")
                .fillMaxWidth()
                .clip(RovstiShapes.card)
                .background(Color(0xFF808080))
                .padding(Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            for (rim in shapes) {
                val (name, w, h, corner, rimShine) = rim
                val bitmap = remember(rim, edge) { rimImage(w, h, corner, rimShine, edge, density = 4) }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    RovstiText(name, style = RovstiTheme.type.footnote, color = Color.White)
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.width((w * 1.5f).dp).size((w * 1.5f).dp, (h * 1.5f).dp),
                    )
                }
            }
        }
    }
}

private data class Rim(
    val name: String,
    val w: Int,
    val h: Int,
    val corner: Float,
    val shine: Float,
)
