package dev.rosty.catalog

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
import dev.rosty.design.chrome.BackdropScope
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.controls.DestructiveButton
import dev.rosty.design.controls.GlassIconButton
import dev.rosty.design.controls.TextButton
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.theme.RostyShapes
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing

/** Every button kind, on the plain page and over a backdrop (where the glass tier shows). */
@Composable
internal fun ButtonsPage() {
    val c = RostyTheme.colors
    var taps by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader("Buttons", "44 dp visuals in 48 dp touch targets. Taps: $taps")
        Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            SectionLabel("Glass icon buttons")
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(RostyIcons.Plus, "Add", { taps++ }, Modifier.testTag("btn-add"))
                GlassIconButton(RostyIcons.Search, "Search", { taps++ })
                GlassIconButton(RostyIcons.Ellipsis, "More", { taps++ })
                GlassIconButton(RostyIcons.Trash, "Delete", { taps++ }, tint = c.destructive)
                GlassIconButton(RostyIcons.Xmark, "Close, unavailable", { taps++ }, enabled = false)
            }
            SectionLabel("Capsules")
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleButton("Share", { taps++ }, icon = RostyIcons.Plus)
                CapsuleButton("Call", {
                    taps++
                }, icon = RostyIcons.Phone, style = CapsuleStyle.Filled, modifier = Modifier.testTag("btn-call"))
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
                DestructiveButton("Delete chat", { taps++ }, icon = RostyIcons.Trash)
                TextButton("Later", { taps++ }, enabled = false)
            }
            SectionLabel("Over a backdrop")
        }
        BackdropScope(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(
                        150.dp,
                    ).padding(horizontal = Spacing.gutter)
                    .clip(RostyShapes.card)
                    .clipToBounds(),
            content = { StripedBackdrop() },
            chrome = { BackdropButtons() },
        )
        RostyText(
            "Glass buttons sample the stripes; filled buttons stay opaque.",
            style = RostyTheme.type.footnote,
            color = c.labelSecondary,
            modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.s),
        )
    }
}

@Composable
private fun BackdropButtons() {
    Column(
        Modifier.fillMaxSize().padding(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterVertically),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(RostyIcons.Back, "Back", {})
            GlassIconButton(RostyIcons.Ellipsis, "More", {})
            CapsuleButton("Glass", {}, icon = RostyIcons.Search)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            CapsuleButton("Filled", {}, style = CapsuleStyle.Filled)
            DestructiveButton("Delete", {})
        }
    }
}
