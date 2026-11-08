/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosMenu.kt
 * Changes for Rovsti: package and imports; `Popup` in [IosMenuAnchor] is replaced by an OverlayEntry presented through
 * the OverlayController (see IosContextMenu.kt); `rememberAppHaptics` is [LocalRovstiHaptics] (a tick per new row, and a
 * confirm when a destructive row is chosen); rows carry a test tag, Button/Checkbox semantics and an OnClick action;
 * the panel takes keyboard focus (arrows with wrap, Enter, Escape/Tab) and can be driven by a finger that went down on
 * the trigger ([slide]); [IosMenuItem.checkable] keeps a check column for rows that are on or off.
 */
package dev.rovsti.design.ios.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.design.backdrop.catalog.utils.InteractiveHighlight
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.ios.foundation.IosMenuColors
import dev.rovsti.design.ios.foundation.IosMenuMaterial
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.ios.foundation.iosMenuSurface
import dev.rovsti.design.ios.foundation.menuVibrancy
import kotlinx.coroutines.flow.Flow

/** One entry of an [IosMenu]. */
@Immutable
data class IosMenuItem(
    val title: String,
    /** The second, dimmer line iOS 26 menus can carry under the title. */
    val description: String? = null,
    val icon: (@Composable (Color) -> Unit)? = null,
    val role: IosButtonRole? = null,
    /** Draws a leading checkmark, the way a Picker inside a Menu marks its selection. */
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit = {},
    /** Rovsti: the row is an on/off row (a checkbox for screen readers) and keeps the check column when it is off. */
    val checkable: Boolean = false,
)

/** A group of items with a separator band above it, and an optional small header. */
@Immutable
data class IosMenuSection(
    val items: List<IosMenuItem>,
    val header: String? = null,
)

/**
 * An iOS-style context menu with optional quick actions.
 *
 * While a finger is down anywhere on the panel, one bright bloom follows it
 * across the whole panel and the panel leans toward it -- no per-row highlight; lifting
 * the finger picks the row under it. So a press can slide
 * across rows and choose on release, exactly as on iOS -- a plain tap is just the
 * zero-length case.
 *
 * [slide] (Rovsti) carries a finger that went down on the trigger and is still down: window positions, the flow
 * completes when it lifts and ends with `Offset.Unspecified` when the touch was taken away instead.
 */
@Composable
fun IosMenu(
    sections: List<IosMenuSection>,
    modifier: Modifier = Modifier,
    quickActions: List<IosMenuItem> = emptyList(),
    onDismissRequest: () -> Unit = {},
    slide: Flow<Offset>? = null,
    debugTag: String = "menu.panel",
) {
    val menuColors = IosMenuMaterial.colors
    // Window px through the current density: the V2 player rescales dp, where
    // screenHeightDp overshot and the panel ran off the bottom in landscape.
    val maximumHeight =
        with(LocalDensity.current) {
            (
                LocalWindowInfo.current.containerSize.height
                    .toDp() - 24.dp
            ).coerceAtLeast(48.dp)
        }
    val haptics = LocalRovstiHaptics.current
    val scope = rememberCoroutineScope()
    // Bloom radius ~ 0.6 of the panel width, far brighter than a button's sheen.
    val bloom =
        remember(scope) {
            InteractiveHighlight(
                animationScope = scope,
                radiusScale = 0.6f,
                sheenAlpha = 0.06f,
                spotAlpha = 0.32f,
                releasePulse = true,
            )
        }

    // Every pickable entry, in hit-test order: quick actions first, then rows.
    val entries = remember(sections, quickActions) { quickActions + sections.flatMap { it.items } }
    // Coordinates, not rects: resolved against the panel at hit time, so nesting
    // (quick actions sit inside a Row) and the panel's own padding need no bookkeeping.
    val entryCoords = remember(entries) { arrayOfNulls<LayoutCoordinates>(entries.size) }
    val panelCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var hovered by remember(entries) { mutableIntStateOf(-1) }
    var keyboard by remember(entries) { mutableIntStateOf(-1) }
    val dismiss by rememberUpdatedState(onDismissRequest)

    fun hit(position: Offset): Int {
        val panel = panelCoords[0]?.takeIf { it.isAttached } ?: return -1
        return entryCoords
            .indexOfFirst { c ->
                c != null && c.isAttached && panel.localBoundingBoxOf(c, clipBounds = false).contains(position)
            }.takeIf { it >= 0 && entries[it].enabled } ?: -1
    }

    fun commit(index: Int) {
        val item = entries[index]
        if (!item.enabled) return
        if (item.role == IosButtonRole.Destructive) haptics.confirm()
        item.onClick()
        dismiss()
    }

    fun hover(position: Offset) {
        val now = hit(position)
        if (now != hovered) {
            if (now >= 0) haptics.textHandle()
            hovered = now
        }
    }

    fun release(scrolled: Boolean) {
        val picked = hovered
        hovered = -1
        if (picked >= 0 && !scrolled) commit(picked)
    }

    if (slide != null) {
        LaunchedEffect(slide, entries) {
            while (panelCoords[0]?.isAttached != true || entryCoords.any { it?.isAttached != true }) withFrameNanos { }
            var cancelled = false
            var last = Offset.Unspecified
            var first = true
            slide.collect { at ->
                if (at.isUnspecified) {
                    cancelled = true
                    return@collect
                }
                last = at
                val panel = panelCoords[0]?.takeIf { it.isAttached } ?: return@collect
                val local = panel.windowToLocal(at)
                if (first) {
                    first = false
                    hovered = hit(local)
                } else {
                    hover(local)
                }
            }
            if (cancelled || last.isUnspecified) hovered = -1 else release(scrolled = false)
        }
    }

    Column(
        modifier =
            modifier
                .width(IosMenuMaterial.Width)
                .heightIn(max = maximumHeight)
                .iosMenuSurface(IosMenuMaterial.PanelShape, bloom, debugTag = debugTag)
                .menuVibrancy()
                .onGloballyPositioned { panelCoords[0] = it }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape, Key.Tab -> {
                            dismiss()
                            true
                        }

                        Key.DirectionDown -> {
                            keyboard = iosMenuStep(entries, keyboard, +1)
                            true
                        }

                        Key.DirectionUp -> {
                            keyboard = iosMenuStep(entries, keyboard, -1)
                            true
                        }

                        Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                            val at = if (keyboard >= 0) keyboard else iosMenuStep(entries, -1, +1)
                            if (at >= 0) commit(at)
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }.pointerInput(entries) {
                    // Observe only: the bloom/stretch watches the same stream.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var scrolled = false
                        hovered = hit(down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (change.isConsumed && change.position != change.previousPosition) scrolled = true
                            if (!change.pressed) break
                            hover(change.position)
                        }
                        release(scrolled)
                    }
                }.verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
    ) {
        var index = 0
        if (quickActions.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 10.dp),
            ) {
                quickActions.forEach { action ->
                    val i = index++
                    IosMenuQuickAction(
                        item = action,
                        menuColors = menuColors,
                        modifier =
                            Modifier
                                .weight(1f)
                                .rowSemantics(i, action, ::commit)
                                .onGloballyPositioned { entryCoords[i] = it },
                    )
                }
            }
        }
        sections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0 || quickActions.isNotEmpty()) IosMenuSeparator(menuColors)

            if (section.header != null) {
                Box(Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 4.dp)) {
                    IosText(
                        text = section.header,
                        color = menuColors.secondaryLabel,
                        style = IosTheme.typography.footnote,
                    )
                }
            }

            // A picker section (any row selectable) keeps a leading checkmark column.
            val checkColumn = section.items.any { it.selected || it.checkable }
            section.items.forEach { item ->
                val i = index++
                IosMenuRow(
                    item = item,
                    checkColumn = checkColumn,
                    menuColors = menuColors,
                    keyboardFocused = keyboard == i,
                    modifier =
                        Modifier
                            .rowSemantics(i, item, ::commit)
                            .onGloballyPositioned { entryCoords[i] = it },
                )
            }
        }
    }
}

/**
 * The entry the arrow keys land on from [current], moving [step] (+1 or -1) through the entries that can be chosen and
 * wrapping around the ends; `-1` when none can. Pure (Rovsti).
 */
internal fun iosMenuStep(
    entries: List<IosMenuItem>,
    current: Int,
    step: Int,
): Int {
    val enabled = entries.indices.filter { entries[it].enabled }
    if (enabled.isEmpty()) return -1
    val at = enabled.indexOf(current)
    val next = if (at < 0) (if (step > 0) 0 else enabled.lastIndex) else (at + step + enabled.size) % enabled.size
    return enabled[next]
}

/** Rovsti: the tag, role and click action of an entry; the finger handling stays on the panel. */
private fun Modifier.rowSemantics(
    index: Int,
    item: IosMenuItem,
    commit: (Int) -> Unit,
): Modifier =
    testTag("menu-row-$index").semantics(mergeDescendants = true) {
        role = if (item.checkable) Role.Checkbox else Role.Button
        if (item.checkable) selected = item.selected
        if (!item.enabled) disabled()
        onClick(label = null) {
            if (!item.enabled) return@onClick false
            commit(index)
            true
        }
    }

@Composable
private fun IosMenuSeparator(menuColors: IosMenuColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(21.dp)
            .padding(horizontal = IosMenuMaterial.SeparatorInset),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(menuColors.separator),
        )
    }
}

@Composable
private fun IosMenuQuickAction(
    item: IosMenuItem,
    menuColors: IosMenuColors,
    modifier: Modifier = Modifier,
) {
    val color = if (item.enabled) menuColors.label else menuColors.secondaryLabel
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            modifier
                .fillMaxHeight()
                .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            item.icon?.invoke(color)
        }
        IosText(
            text = item.title,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style =
                IosTheme.typography.caption.copy(
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp,
                ),
        )
    }
}

@Composable
private fun IosMenuRow(
    item: IosMenuItem,
    checkColumn: Boolean,
    menuColors: IosMenuColors,
    keyboardFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = IosTheme.colors
    val contentColor =
        when {
            !item.enabled -> menuColors.secondaryLabel
            item.role == IosButtonRole.Destructive -> colors.systemRed
            else -> menuColors.label
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                // Rovsti: the row the arrow keys are on gets the menu's highlight fill.
                .then(
                    if (keyboardFocused) {
                        Modifier.background(menuColors.highlight, RoundedCornerShape(22.dp))
                    } else {
                        Modifier
                    },
                ).defaultMinSize(minHeight = 44.dp)
                .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checkColumn) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (item.selected) IosCheckmark(color = contentColor)
            }
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            IosText(
                text = item.title,
                color = contentColor,
                style =
                    IosTheme.typography.body.copy(
                        fontSize = 17.sp,
                        lineHeight = 20.sp,
                        letterSpacing = (-0.43).sp,
                    ),
            )
            if (item.description != null) {
                IosText(
                    text = item.description,
                    color = menuColors.secondaryLabel,
                    style = IosTheme.typography.footnote,
                )
            }
        }
        // Rovsti: icons sit at the trailing edge, as on iOS (Convx's original puts them first).
        if (item.icon != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                item.icon.invoke(contentColor)
            }
        }
    }
}

/**
 * Anchors a menu to [content], opening it below on tap -- SwiftUI's Menu. The menu is an overlay entry
 * ([IosContextMenuOverlay]) positioned off [content]'s bounds.
 */
@Composable
fun IosMenuAnchor(
    sections: List<IosMenuSection>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val bounds = remember { arrayOf(Rect.Zero) }
    Box(modifier) {
        Box(
            Modifier
                .onGloballyPositioned { bounds[0] = it.boundsInWindow() }
                .clickable { onExpandedChange(true) },
        ) { content() }
    }
    IosContextMenuOverlay(
        sections = sections,
        anchor = if (expanded) bounds[0] else null,
        onDismissRequest = { onExpandedChange(false) },
    )
}

/** The checkmark iOS draws next to a selected menu row. */
@Composable
fun IosCheckmark(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val stroke = if (color == Color.Unspecified) IosTheme.tint else color
    androidx.compose.foundation.Canvas(modifier.size(width = 16.dp, height = 12.dp)) {
        drawPath(
            path =
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, size.height * 0.55f)
                    lineTo(size.width * 0.35f, size.height)
                    lineTo(size.width, 0f)
                },
            color = stroke,
            style =
                androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
        )
    }
}
