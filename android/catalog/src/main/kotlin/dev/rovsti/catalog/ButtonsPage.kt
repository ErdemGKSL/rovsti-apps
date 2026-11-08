package dev.rovsti.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.DestructiveButton
import dev.rovsti.design.controls.GlassIconButton
import dev.rovsti.design.controls.TextButton
import dev.rovsti.design.glass.GlassSource
import dev.rovsti.design.glass.glassSource
import dev.rovsti.design.glass.rememberGlassSource
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.theme.RovstiShapes
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/** Every button kind, on the plain page and over a backdrop (where the glass tier shows). */
@Composable
internal fun ButtonsPage() {
    val c = RovstiTheme.colors
    var taps by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader("Buttons", "44 dp visuals in 48 dp touch targets. Taps: $taps")
        Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            SectionLabel("Glass icon buttons")
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(RovstiIcons.Plus, "Add", { taps++ }, Modifier.testTag("btn-add"))
                GlassIconButton(RovstiIcons.Search, "Search", { taps++ })
                GlassIconButton(RovstiIcons.Ellipsis, "More", { taps++ })
                GlassIconButton(RovstiIcons.Trash, "Delete", { taps++ }, tint = c.destructive)
                GlassIconButton(RovstiIcons.Xmark, "Close, unavailable", { taps++ }, enabled = false)
            }
            SectionLabel("Capsules")
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleButton("Share", { taps++ }, icon = RovstiIcons.Plus)
                CapsuleButton("Call", {
                    taps++
                }, icon = RovstiIcons.Phone, style = CapsuleStyle.Filled, modifier = Modifier.testTag("btn-call"))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleButton("Message", { taps++ }, style = CapsuleStyle.Filled)
                CapsuleButton("Disabled", { taps++ }, style = CapsuleStyle.Filled, enabled = false)
                CapsuleButton("Off", { taps++ }, enabled = false)
            }
            SectionLabel("Text and destructive")
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton("Cancel", { taps++ })
                DestructiveButton("Delete chat", { taps++ }, icon = RovstiIcons.Trash)
                TextButton("Later", { taps++ }, enabled = false)
            }
            SectionLabel("Over a backdrop")
        }
        val source = rememberGlassSource()
        Box(
            Modifier
                .fillMaxWidth()
                .height(
                    150.dp,
                ).padding(horizontal = Spacing.gutter)
                .clip(RovstiShapes.card)
                .clipToBounds(),
        ) {
            Box(Modifier.fillMaxSize().glassSource(source, c.page)) { StripedBackdrop() }
            BackdropButtons(source)
        }
        RovstiText(
            "Glass buttons sample the stripes; filled buttons stay opaque.",
            style = RovstiTheme.type.footnote,
            color = c.labelSecondary,
            modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.s),
        )
    }
}

private fun Modifier.fillMaxSize(): Modifier = this.fillMaxSize()

@Composable
private fun BackdropButtons(source: GlassSource) {
    Column(
        Modifier.fillMaxSize().padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterVertically),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(RovstiIcons.Back, "Back", {}, glass = source)
            GlassIconButton(RovstiIcons.Ellipsis, "More", {}, glass = source)
            CapsuleButton("Glass", {}, glass = source, icon = RovstiIcons.Search)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            CapsuleButton("Filled", {}, style = CapsuleStyle.Filled)
            DestructiveButton("Delete", {}, glass = source)
        }
    }
}
