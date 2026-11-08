package dev.rovsti.design.overlay

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import dev.rovsti.design.glass.panelGlass
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.ImmutableList

/**
 * The finger handling of a context menu's action panel (ORIGINAL; the rows, the plate and the card press are the ones
 * of the button-anchored menu, see MenuPanel.kt). [onCommit] runs after the row was chosen.
 */
@Composable
internal fun rememberContextPanelState(
    items: ImmutableList<MenuItem>,
    geometry: CardGeometry,
    host: OverlayScope,
    onCommit: (MenuItem.Action) -> Unit,
): MenuPanelState {
    val reduceMotion = LocalReduceMotion.current
    val haptics by rememberUpdatedState(LocalRovstiHaptics.current)
    val reduceNow by rememberUpdatedState(reduceMotion)
    val commit by rememberUpdatedState(onCommit)
    val scope = rememberCoroutineScope()
    val panel =
        remember {
            MenuPanelState(
                items = items,
                scope = scope,
                geometry = geometry,
                haptics = { haptics },
                reduceMotion = { reduceNow },
                pxPerDp = host.density.density,
                onCommit = { commit(it) },
            )
        }
    LaunchedEffect(panel) { panel.runPlate() }
    return panel
}

/**
 * The glass card with the action rows. It has its final size from the first frame (the caller animates its layer from
 * outside), grabs keyboard focus-ability (arrows, Enter, Escape) and scrolls when taller than the room it was given.
 */
@Composable
internal fun ContextPanelCard(
    items: ImmutableList<MenuItem>,
    panel: MenuPanelState,
    focus: FocusRequester,
    dismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelShape = remember { RoundedCornerShape(MenuMetrics.CardRadius) }
    val scrollState = rememberScrollState()
    panel.scrollPx = { scrollState.value }
    Box(
        modifier
            .testTag("context-panel")
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape, Key.Tab -> {
                        dismiss()
                        true
                    }

                    else -> {
                        panel.onKey(event.key)
                    }
                }
            }.menuPanelPointer(panel)
            .graphicsLayer {
                scaleX = panel.scale.value
                scaleY = panel.scale.value
                translationX = panel.dx.value
                translationY = panel.dy.value
            },
    ) {
        Box(Modifier.matchParentSize().panelGlass(panelShape, "context.panel"))
        Box(
            Modifier
                .padding(MenuMetrics.CardPadding)
                .verticalScroll(scrollState, enabled = scrollState.maxValue > 0),
        ) {
            MenuRows(items, panel)
        }
        Box(Modifier.matchParentSize().drawBehind { panel.drawSheen(this) })
    }
}
