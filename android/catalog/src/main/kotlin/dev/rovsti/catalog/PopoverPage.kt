package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.popoverTrigger
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing

/** A small form: the kind of thing a popover is for. */
@Composable
internal fun DatePopoverContent() {
    val colors = RovstiTheme.colors
    Column(Modifier.fillMaxWidth().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        RovstiText("Due date", style = RovstiTheme.type.headline)
        FormRow("Date", "Nov 8, 2026")
        FormRow("Time", "9:41 AM")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
            for ((i, label) in listOf("Today", "Tomorrow", "Next week").withIndex()) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Sizes.chip)
                        .background(if (i == 0) colors.accent else colors.fillSecondary, CircleShape)
                        .padding(horizontal = Spacing.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    RovstiText(
                        label,
                        style = RovstiTheme.type.footnote,
                        color = if (i == 0) colors.onAccent else colors.labelPrimary,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun FormRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = Sizes.touch), verticalAlignment = Alignment.CenterVertically) {
        RovstiText(label, Modifier.weight(1f), style = RovstiTheme.type.body)
        RovstiText(value, style = RovstiTheme.type.body, color = RovstiTheme.colors.labelSecondary)
    }
}

@Composable
internal fun LongPopoverContent() {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
        for (i in 0 until LongRows) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = Sizes.touch,
                    ).padding(horizontal = Spacing.l)
                    .testTag("pop-row-$i"),
                contentAlignment = Alignment.CenterStart,
            ) {
                RovstiText("Option ${i + 1}", style = RovstiTheme.type.body)
            }
        }
    }
}

internal const val LongRows = 30

@Composable
private fun PopoverButton(
    text: String,
    tag: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val controller = LocalOverlay.current
    Box(
        modifier
            .heightIn(min = Sizes.button)
            .background(RovstiTheme.colors.fillSecondary, CircleShape)
            .popoverTrigger(controller, label = text, content = content)
            .padding(horizontal = Spacing.l)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(text, style = RovstiTheme.type.body, color = RovstiTheme.colors.accent, maxLines = 1)
    }
}

/** E1.12: a popover with a form near the top (opens below), a long one that scrolls, and one low on the page (above). */
@Composable
internal fun PopoverPage() {
    Box(Modifier.fillMaxSize().padding(insetsOnly())) {
        Column(Modifier.align(Alignment.TopStart)) {
            PageHeader("Popover", "Arbitrary content grows out of its button. Taller than the screen, it scrolls.")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                PopoverButton("Date", "pop-date", Modifier) { DatePopoverContent() }
                PopoverButton("Long", "pop-long", Modifier) { LongPopoverContent() }
            }
            Column(Modifier.padding(top = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                for (i in 0 until 3) {
                    RovstiText(
                        "Page content ${i + 1} stays where it is",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.gutter)
                                .heightIn(min = Sizes.touch)
                                .testTag("pop-node-$i"),
                        style = RovstiTheme.type.body,
                        color = RovstiTheme.colors.labelSecondary,
                    )
                }
            }
        }
        PopoverButton("Date (low)", "pop-low", Modifier.align(Alignment.BottomEnd).padding(end = Spacing.gutter)) {
            DatePopoverContent()
        }
    }
}
