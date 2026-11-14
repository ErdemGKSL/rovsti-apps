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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rosty.design.backdrop.backdrops.layerBackdrop
import dev.rosty.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rosty.design.controls.SegmentedControl
import dev.rosty.design.ios.component.IosSlider
import dev.rosty.design.ios.component.IosToggle
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRowSwitch
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf

private val Tabs = persistentListOf("Calls", "Messages", "Contacts")

/**
 * E1.17: the switch, the slider and the segmented control, first in a settings section (white surface), then over a busy
 * striped backdrop where the held thumb's lens bends what is behind it. The lines report what each control did.
 */
@Composable
internal fun ControlsPage() {
    var previews by remember { mutableStateOf(true) }
    var receipts by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0.4f) }
    var tab by remember { mutableIntStateOf(0) }
    var busyA by remember { mutableStateOf(true) }
    var busyB by remember { mutableStateOf(false) }
    var busyLevel by remember { mutableFloatStateOf(0.6f) }
    var busyTab by remember { mutableIntStateOf(1) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader("Controls", "Switch, slider and segmented control. Hold a thumb to see the glass.")
        RostyText(
            "Previews ${if (previews) "on" else "off"}, receipts ${if (receipts) "on" else "off"}, " +
                "volume ${(volume * 100).toInt()}, tab ${Tabs[tab]}",
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s).testTag("controls-state"),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
        InsetGroupedSection(header = "Settings", modifier = Modifier.testTag("controls-section")) {
            row {
                ListRowSwitch(
                    "Show previews",
                    previews,
                    { previews = it },
                    secondary = "In the lock screen and in notifications",
                    modifier = Modifier.testTag("row-previews"),
                )
            }
            row {
                ListRowSwitch(
                    "Read receipts",
                    receipts,
                    { receipts = it },
                    modifier = Modifier.testTag("row-receipts"),
                )
            }
            row {
                ListRowSwitch(
                    "Sync contacts",
                    true,
                    {},
                    enabled = false,
                    modifier = Modifier.testTag("row-disabled"),
                )
            }
            row {
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s)) {
                    IosSlider(
                        value = volume,
                        onValueChange = { volume = it },
                        modifier = Modifier.testTag("slider"),
                        minimumValueLabel = { RostyText("0", style = RostyTheme.type.footnote) },
                        maximumValueLabel = { RostyText("100", style = RostyTheme.type.footnote) },
                    )
                }
            }
            row {
                Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s)) {
                    SegmentedControl(Tabs, tab, { tab = it }, Modifier.testTag("segmented-3"))
                }
            }
        }

        val busy = rememberLayerBackdrop()
        RostyText(
            "OVER A BUSY BACKDROP",
            modifier = Modifier.padding(start = Spacing.gutter + Spacing.l, top = Spacing.xl, bottom = Spacing.s),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
        Box(
            Modifier
                .padding(horizontal = Spacing.gutter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)),
        ) {
            Box(Modifier.matchParentSize().layerBackdrop(busy)) { StripedBackdrop() }
            Column(
                Modifier.fillMaxWidth().padding(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosToggle(busyA, { busyA = it }, Modifier.testTag("busy-toggle-on"), backdrop = busy)
                    IosToggle(busyB, { busyB = it }, Modifier.testTag("busy-toggle-off"), backdrop = busy)
                }
                IosSlider(busyLevel, { busyLevel = it }, Modifier.testTag("busy-slider"), backdrop = busy)
                SegmentedControl(Tabs, busyTab, { busyTab = it }, Modifier.testTag("busy-segmented"), backdrop = busy)
            }
        }
        Box(Modifier.height(Spacing.xl))
    }
}

private fun Modifier.matchParentSize(): Modifier = this.fillMaxSize()
