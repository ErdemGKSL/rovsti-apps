package dev.rovsti.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.BackdropScope
import dev.rovsti.design.glass.GlassPreset
import dev.rovsti.design.glass.panelGlass
import dev.rovsti.design.glass.rovstiGlass
import dev.rovsti.design.theme.RovstiShapes
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing

/**
 * Liquid glass over a striped backdrop: three bar capsules and a menu-material card, all drawn as siblings AFTER
 * the recorded stripes ([BackdropScope]). The surfaces are the Convx stack (`liquidGlass` through `rovstiGlass`,
 * `iosMenuSurface` through `panelGlass`); there is no fallback tier to pick.
 */
@Composable
internal fun GlassPage() {
    val c = RovstiTheme.colors
    BackdropScope(
        modifier = Modifier.fillMaxSize(),
        content = { StripedBackdrop() },
        chrome = {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding()),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                Box(
                    Modifier
                        .testTag("glass-bar")
                        .rovstiGlass(GlassPreset.Bar, RovstiShapes.capsule, "glass.page.bar")
                        .heightIn(min = Sizes.button)
                        .padding(horizontal = Spacing.l),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    RovstiText("Liquid glass", style = RovstiTheme.type.headline)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    for (label in listOf("Messages", "Calls", "Stops")) {
                        Box(
                            Modifier
                                .weight(1f)
                                .testTag("glass-capsule-$label")
                                .rovstiGlass(GlassPreset.Bar, RovstiShapes.capsule, "glass.page.capsule")
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
                        .panelGlass(RoundedCornerShape(24.dp), "glass.page.panel")
                        .padding(Spacing.l),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    RovstiText("Menu material", style = RovstiTheme.type.headline)
                    RovstiText(
                        "Blur 8 dp, saturation 1.8, a weak lens and the menu fill. The label stays readable over any stripe.",
                        style = RovstiTheme.type.subheadline,
                        color = c.labelSecondary,
                    )
                }
            }
        },
    )
}
