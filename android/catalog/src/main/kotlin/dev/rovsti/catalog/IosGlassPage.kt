package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rovsti.design.glass.GlassEffectConfig
import dev.rovsti.design.glass.GlassSettings
import dev.rovsti.design.glass.LocalAppBackdrop
import dev.rovsti.design.glass.LocalGlassSettings
import dev.rovsti.design.glass.TopBarBlurStyle
import dev.rovsti.design.glass.TopFadeBlur
import dev.rovsti.design.glass.liquidGlass
import dev.rovsti.design.glass.resolvedTextColor
import dev.rovsti.design.ios.foundation.IosGlassStyle
import dev.rovsti.design.ios.foundation.IosMenuMaterial
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.ios.foundation.iosGlass
import dev.rovsti.design.ios.foundation.iosGlassContentColor
import dev.rovsti.design.ios.foundation.iosMenuSurface
import dev.rovsti.design.shapes.ContinuousCapsule
import dev.rovsti.design.theme.GlassTintTokens
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/**
 * E1.25: the ported Convx glass materials over stripes: iosGlass Regular and Prominent, the iosMenuSurface
 * panel, three liquidGlass presets (bar, button, popover) and the TopFadeBlur strip. Everything samples one
 * root [layerBackdrop]; the glass sits outside the recorded subtree, as Convx's chrome does.
 */
@Composable
internal fun IosGlassPage() {
    val backdrop = rememberLayerBackdrop()
    val haze = remember { HazeState() }
    CompositionLocalProvider(
        LocalAppBackdrop provides backdrop,
        LocalIosBackdrop provides backdrop,
        LocalGlassSettings provides GlassSettings(topBarBlurStyle = TopBarBlurStyle.SinglePass),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).hazeSource(haze)) {
                StripedBackdrop()
                // Dark pages are dark: dim the stripes so the dark glass (a white lift, light labels) is judged
                // over content it is meant for, not over full-brightness yellow.
                if (RovstiTheme.colors.isDark) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            }
            TopFadeBlur(
                hazeState = haze,
                pageColor = RovstiTheme.colors.page,
                heightOverride = 120.dp,
                backdrop = backdrop,
                modifier = Modifier.testTag("top-fade"),
            )
            Column(
                Modifier.fillMaxSize().padding(pagePadding()),
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                RovstiText("iOS glass", style = RovstiTheme.type.largeTitle)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    IosPill("Regular", IosGlassStyle.Regular, Modifier.testTag("glass-regular"))
                    IosPill("Prominent", IosGlassStyle.Prominent, Modifier.testTag("glass-prominent"))
                }
                MenuPanel()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiquidPreset("Bar", GlassEffectConfig(), Modifier.width(120.dp).testTag("preset-bar"))
                    LiquidPreset(
                        "Button",
                        GlassEffectConfig(
                            blurRadius = 1f,
                            lensHeight = GlassTintTokens.OtherLensHeight,
                            lensAmount = GlassTintTokens.OtherLensAmount,
                        ),
                        Modifier.width(104.dp).testTag("preset-button"),
                    )
                    LiquidPreset(
                        "Popover",
                        GlassEffectConfig(blurRadius = GlassTintTokens.SheetBlurLight.value, vibrancy = 1.2f),
                        Modifier.width(112.dp).testTag("preset-popover"),
                    )
                }
            }
        }
    }
}

@Composable
private fun IosPill(
    label: String,
    style: IosGlassStyle,
    modifier: Modifier,
) {
    Box(
        modifier.width(140.dp).height(52.dp).iosGlass(shape = ContinuousCapsule, style = style),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(label, style = RovstiTheme.type.headline, color = iosGlassContentColor(style))
    }
}

@Composable
private fun MenuPanel() {
    val colors = IosMenuMaterial.colors
    Column(
        Modifier
            .testTag("menu-panel")
            .width(IosMenuMaterial.Width)
            .iosMenuSurface(IosMenuMaterial.PanelShape),
    ) {
        listOf("Share", "Copy link", "Delete").forEachIndexed { i, label ->
            if (i > 0) {
                Box(
                    Modifier
                        .padding(horizontal = IosMenuMaterial.SeparatorInset)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.separator),
                )
            }
            Box(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                RovstiText(label, style = RovstiTheme.type.body, color = colors.label)
            }
        }
    }
}

@Composable
private fun LiquidPreset(
    label: String,
    config: GlassEffectConfig,
    modifier: Modifier,
) {
    Box(
        modifier.height(56.dp).liquidGlass(config, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(label, style = RovstiTheme.type.headline, color = config.resolvedTextColor())
    }
}
