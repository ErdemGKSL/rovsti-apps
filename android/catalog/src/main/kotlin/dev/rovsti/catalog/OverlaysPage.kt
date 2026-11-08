package dev.rovsti.catalog

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.OverlayEntry
import dev.rovsti.design.overlay.OverlayScope
import dev.rovsti.design.overlay.OverlayScrim
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing

/**
 * A plain card as a modal overlay: scrim, a card that scales and fades in with the entry's transition, and two
 * buttons. [index] picks its tag and its vertical offset, so a second card stacks visibly over the first.
 */
internal class DemoCardEntry(
    private val index: Int,
    private val onOpenAnother: (() -> Unit)?,
) : OverlayEntry(modal = true, title = "Demo card $index") {
    @Composable
    override fun Content(host: OverlayScope) {
        val transition = rememberTransition(state, label = "demo-card")
        val progress: State<Float> =
            transition.animateFloat(
                transitionSpec = { RovstiMotion.appear(0.001f) },
                label = "demo-card-progress",
            ) { if (it) 1f else 0f }
        val onOpen by rememberUpdatedState(onOpenAnother)
        val colors = RovstiTheme.colors
        OverlayScrim(progress = { progress.value }, onTap = { host.controller.dismiss(this@DemoCardEntry) })
        Box(Modifier.fillMaxSize().padding(horizontal = Spacing.gutter), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier
                    .offset(y = 96.dp * (index - 1))
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val p = progress.value
                        scaleX = 0.92f + 0.08f * p
                        scaleY = 0.92f + 0.08f * p
                        alpha = p.coerceIn(0f, 1f)
                    }.shadow(24.dp, shape)
                    .background(colors.groupedSurface, shape)
                    .border(0.5.dp, colors.separator, shape)
                    .padding(Spacing.l)
                    .testTag("overlay-card-$index"),
                verticalArrangement =
                    androidx.compose.foundation.layout.Arrangement
                        .spacedBy(Spacing.s),
            ) {
                RovstiText("Overlay $index", style = RovstiTheme.type.headline)
                RovstiText(
                    "A plain card in the overlay host. Back, or a tap on the dimmed page, closes only this one.",
                    style = RovstiTheme.type.subheadline,
                    color = colors.labelSecondary,
                )
                Row(
                    horizontalArrangement =
                        androidx.compose.foundation.layout.Arrangement
                            .spacedBy(Spacing.s),
                ) {
                    CapsuleButton(
                        text = "Close",
                        onClick = { host.controller.dismiss(this@DemoCardEntry) },
                        modifier = Modifier.testTag("overlay-close-$index"),
                    )
                    if (onOpen != null) {
                        CapsuleButton(
                            text = "Open another",
                            onClick = { onOpen?.invoke() },
                            modifier = Modifier.testTag("overlay-another-$index"),
                        )
                    }
                }
            }
        }
    }
}

/** E1.9: the overlay host. Two triggers present modal cards, the second stacks above the first. */
@Composable
internal fun OverlaysPage() {
    val controller = LocalOverlay.current

    fun presentSecond() {
        controller.present(DemoCardEntry(index = 2, onOpenAnother = null))
    }
    LazyColumn(
        contentPadding = insetsOnly(),
        modifier = Modifier.fillMaxSize().testTag("overlays-list"),
    ) {
        item {
            PageHeader(
                "Overlay host",
                "Every overlay is an entry in one host: one back owner, one z-order, exits always play.",
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                horizontalArrangement =
                    androidx.compose.foundation.layout.Arrangement
                        .spacedBy(Spacing.s),
            ) {
                CapsuleButton(
                    text = "Present",
                    onClick = { controller.present(DemoCardEntry(index = 1, onOpenAnother = ::presentSecond)) },
                    modifier = Modifier.testTag("present-first"),
                )
                CapsuleButton(
                    text = "Present second",
                    onClick = ::presentSecond,
                    modifier = Modifier.testTag("present-second"),
                )
            }
        }
        items(List(30) { it }, key = { it }) { i ->
            ListRow(
                title = "Row $i",
                secondary = "Under the scrim, it cannot scroll",
                modifier = Modifier.testTag("page-row-$i"),
            )
        }
    }
}
