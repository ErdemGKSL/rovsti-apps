package dev.rosty.stops.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.chrome.BackdropScope
import dev.rosty.design.chrome.ChromeMetrics
import dev.rosty.design.chrome.RostyTabBar
import dev.rosty.design.chrome.TabItem
import dev.rosty.design.controls.ChipModel
import dev.rosty.design.controls.ChipRow
import dev.rosty.design.controls.GlassCircleButton
import dev.rosty.design.controls.GlassIconButton
import dev.rosty.design.glass.GlassPreset
import dev.rosty.design.glass.LocalAppBackdropFrozen
import dev.rosty.design.glass.rostyGlass
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.foundation.LocalIosContentColor
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.press.scalePress
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.HomeTab
import dev.rosty.stops.R
import dev.rosty.stops.StopsSizes
import dev.rosty.stops.sideRoomDp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.roundToInt

/** Test tags of the home's floating chrome. */
object HomeTags {
    const val Root = "home"
    const val Top = "home-top"
    const val Search = "home-search"
    const val Chips = "home-chips"
    const val Buttons = "home-buttons"
    const val Locate = "home-locate"
    const val Layers = "home-layers"
    const val Options = "home-map-options"
    const val Bottom = "home-bottom"
    const val TabBar = "home-tab-bar"
    const val Hint = "home-hint"
    const val MapFooter = "home-map-footer"
    const val ScaleBar = "home-scale-bar"
}

/** What the home's chrome asks for; every action of a later story is a no-op until it is wired. */
class HomeActions(
    val onTab: (HomeTab) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onOpenSaved: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onLocate: () -> Unit = {},
    /** The locate button held (S25 "My location"). */
    val onLocateLong: () -> Unit = {},
    /** The layers and map options buttons open popovers at their window bounds. */
    val onLayers: (Rect) -> Unit = {},
    val onMapOptions: (Rect) -> Unit = {},
)

/**
 * The map home (ARCH 8.4, SKILL navigation-layout 9). Layers, bottom to top:
 *
 * 1. [map] (`StopsMap`), the full window under both system bars, recorded by a [BackdropScope] so the chrome's glass samples it;
 * 2. floating chrome: the search pill and the one-line chip row at the top, and at the bottom one block holding the
 *    button column (locate, layers, map options) and the home's tab bar folded to a circle. The block rides the sheet by
 *    a `graphicsLayer` translation only (no relayout), up to the medium detent, and fades as the sheet grows past it;
 * 3. the map sheet, an overlay entry (non-modal: the map keeps panning and taking taps at peek and medium).
 *
 * [camera] follows the glass-covered area: the top chrome, the button column and the sheet's **settled** detent.
 */
@Composable
fun StopsHome(
    state: HomeState,
    map: @Composable (Modifier) -> Unit,
    tabs: ImmutableList<TabItem>,
    selectedTab: HomeTab,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    camera: MapCameraPadding = remember { MapCameraPadding() },
    chips: ImmutableList<ChipModel> = persistentListOf(),
    onChip: (String) -> Unit = {},
    hint: String? = null,
    /** Shown under the search pill in place of [hint] (the measure card, E7.9). */
    topCard: (@Composable () -> Unit)? = null,
    /** The locate button is tinted while the view follows the position (S17). */
    following: Boolean = false,
    /** The scale bar and the attribution; [stacked] (large text) puts them one under the other. */
    mapFooter: @Composable (stacked: Boolean) -> Unit = {},
    motionFrozen: () -> Boolean = { false },
    sheetContent: @Composable () -> Unit = { MapSheetContent(state, actions.onOpenSaved, actions.onOpenHistory) },
) {
    var detentFromTallest by rememberSaveable { mutableIntStateOf(MapSheetDetents.lastIndex) }
    // Peek reaches down to the content's buttons (the plan's Start and Save, the trip's Stop): measured, never guessed.
    val peek = remember { MapSheetPeek() }
    val entry =
        rememberMapSheet(detentFromTallest) { _ ->
            CompositionLocalProvider(LocalMapSheetPeek provides peek) { sheetContent() }
        }
    val density = LocalDensity.current
    // Large text: the attribution goes under the scale bar instead of off the edge.
    val stackedFooter = density.fontScale >= STACKED_FOOTER_SCALE
    val bottomInsetPx = WindowInsets.safeDrawing.getBottom(density).toFloat()
    val gapPx = with(density) { Spacing.s.toPx() }
    val fadePx = with(density) { StopsSizes.rideFade.toPx() }
    // Measured on layout changes only (never per frame); the layer lambda below reads them.
    var windowPx by remember { mutableFloatStateOf(0f) }
    var windowWidthPx by remember { mutableFloatStateOf(0f) }
    var topChromeBottomPx by remember { mutableFloatStateOf(0f) }
    var blockPx by remember { mutableFloatStateOf(0f) }
    var buttonsWidthPx by remember { mutableFloatStateOf(0f) }
    var unfolded by remember { mutableStateOf(false) }
    val overlays = LocalOverlay.current
    // A hidden top chrome takes no touch and says nothing (it reads in layout, not per frame of the sheet's drag).
    val topHidden by remember(entry) {
        derivedStateOf {
            val sheet = entry.sheet
            val top = if (sheet == null) windowPx else sheet.offset.value
            topChromeAlpha(top, topChromeBottomPx, fadePx, overlays.pushBackProgress()) < HIDDEN_ALPHA
        }
    }
    // Toasts (the third-tap hint, "Copied") rise above the riding block and the settled sheet, not over the scale bar:
    // the bar hands these metrics to the overlay host, and the settle below writes the clearance into them (the bar's
    // own height write on a fold is read back and replaced).
    val toastMetrics = remember { ChromeMetrics() }
    val barMarginPx = with(density) { ChromeMetrics.BarMargin.toPx() }
    // A wide window (landscape, tablets) has room beside the centred sheet: the chrome stays put there and the folded
    // bar keeps to the start edge, so the sheet never covers it.
    val sideRoom = with(density) { sideRoomDp(windowWidthPx.toDp(), Sizes.sheetMaxWidth) }
    val wide = sideRoom >= StopsSizes.sideChromeMin

    // The peek detent follows the measured content (a layout change, never a frame of the drag); the sheet keeps the
    // detent it rests on, and the camera padding below follows the new settled height.
    LaunchedEffect(entry, peek, density, bottomInsetPx) {
        val grabberPx = with(density) { MapSheetGrabber.toPx() }
        val peekGapPx = with(density) { Spacing.m.toPx() }
        val minPx = with(density) { StopsSizes.sheetPeek.toPx() }
        val belowMediumPx = with(density) { StopsSizes.peekBelowMedium.toPx() }
        snapshotFlow {
            mapSheetPeekPx(peek.contentBottomPx, grabberPx, peekGapPx, bottomInsetPx, windowPx, minPx, belowMediumPx)
                .roundToInt()
        }.collect { px -> entry.updateDetents(mapSheetDetents(with(density) { px.toDp() })) }
    }

    // The settled detent: remembered for the next visit, and the camera padding (on settle, never per frame).
    LaunchedEffect(entry, camera) {
        snapshotFlow {
            val sheet = entry.sheet
            CameraInputs(
                settledVisiblePx =
                    if (sheet == null || sheet.settledOffset.isNaN()) {
                        0f
                    } else {
                        sheetVisiblePx(sheet.settledOffset, sheet.containerHeight)
                    },
                fromTallest = sheet?.let { s -> s.detentOffsets.indexOfFirst { it == s.settledOffset } } ?: -1,
                topPx = topChromeBottomPx,
                endPx = buttonsWidthPx,
                blockPx = blockPx,
                windowPx = windowPx,
                wide = wide,
                barWrite = toastMetrics.bottomBarPx,
            )
        }.collect { inputs ->
            if (inputs.fromTallest >= 0) detentFromTallest = inputs.fromTallest
            val lift =
                if (inputs.wide) {
                    0f
                } else {
                    val maxVisible =
                        rideMaxVisiblePx(
                            inputs.windowPx * MEDIUM_SHARE,
                            inputs.windowPx,
                            inputs.topPx,
                            inputs.blockPx,
                            bottomInsetPx,
                            gapPx,
                        )
                    -sheetRideTranslation(inputs.settledVisiblePx, bottomInsetPx, maxVisible)
                }
            toastMetrics.bottomBarPx =
                toastBarPx(inputs.blockPx + lift, inputs.settledVisiblePx, bottomInsetPx, barMarginPx, inputs.windowPx)
            with(density) {
                camera.update(
                    PaddingValues(
                        top = inputs.topPx.toDp(),
                        bottom = inputs.settledVisiblePx.toDp(),
                        end = inputs.endPx.toDp(),
                    ),
                )
            }
        }
    }

    BackdropScope(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged {
                    windowPx = it.height.toFloat()
                    windowWidthPx = it.width.toFloat()
                }.testTag(HomeTags.Root),
        baseBg = RostyTheme.colors.page,
        chrome = {
            // While the map moves the chrome's glass keeps its last capture (MapMotionEffect feeds the gate).
            val outer = LocalAppBackdropFrozen.current
            CompositionLocalProvider(LocalAppBackdropFrozen provides { outer() || motionFrozen() }) {
                Box(Modifier.fillMaxSize()) {
                    // Top: the search / pick pill and the chip row, inside the safe area.
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            ).padding(top = Spacing.s)
                            .widthIn(max = Sizes.contentMaxWidth)
                            .fillMaxWidth()
                            .graphicsLayer {
                                // Hidden under a large map sheet and under a modal sheet's push-back (read in draw).
                                val sheet = entry.sheet
                                val top = if (sheet == null) windowPx else sheet.offset.value
                                alpha = topChromeAlpha(top, topChromeBottomPx, fadePx, overlays.pushBackProgress())
                            }.then(if (topHidden) Modifier.clearAndSetSemantics {}.blockTouches() else Modifier)
                            .testTag(HomeTags.Top),
                    ) {
                        // The camera clears the search pill only: the hint and the chips below it come and go with
                        // answers from the core, and the first view must not depend on which came first.
                        SearchPill(
                            actions.onSearch,
                            Modifier
                                .padding(horizontal = Spacing.gutter)
                                .onGloballyPositioned { topChromeBottomPx = it.boundsInRoot().bottom },
                        )
                        if (topCard != null) {
                            Spacer(Modifier.height(Spacing.s))
                            Box(Modifier.padding(horizontal = Spacing.gutter)) { topCard() }
                        } else if (!hint.isNullOrBlank()) {
                            Spacer(Modifier.height(Spacing.s))
                            HintPill(hint, Modifier.align(Alignment.CenterHorizontally))
                        }
                        if (chips.isNotEmpty()) {
                            Spacer(Modifier.height(Spacing.s))
                            ChipRow(
                                chips = chips,
                                selected = "",
                                onSelect = onChip,
                                modifier = Modifier.height(StopsSizes.chipRow).testTag(HomeTags.Chips),
                            )
                        }
                    }
                    // Bottom: the button column over the folded tab bar, one block riding the sheet.
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onSizeChanged { blockPx = it.height.toFloat() }
                            .graphicsLayer {
                                val sheet = entry.sheet
                                if (wide) return@graphicsLayer
                                val container = sheet?.containerHeight ?: windowPx
                                val visible = if (sheet == null) 0f else sheetVisiblePx(sheet.offset.value, container)
                                val maxVisible =
                                    rideMaxVisiblePx(
                                        mediumVisiblePx = container * MEDIUM_SHARE,
                                        windowHeightPx = windowPx,
                                        topChromeBottomPx = topChromeBottomPx,
                                        blockHeightPx = blockPx,
                                        bottomInsetPx = bottomInsetPx,
                                        gapPx = gapPx,
                                    )
                                translationY = sheetRideTranslation(visible, bottomInsetPx, maxVisible)
                                alpha = sheetRideAlpha(visible, maxVisible, fadePx)
                            }.testTag(HomeTags.Bottom)
                            // The bottom inset is taken here (the bar inside then adds only its 8 dp margin), so the block
                            // rests above the gesture area with or without the bar.
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                        horizontalAlignment = Alignment.End,
                    ) {
                        // A wide window keeps the footer above the buttons, clear of the centred sheet.
                        if (wide) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                    .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
                                    .testTag(HomeTags.MapFooter),
                            ) { mapFooter(stackedFooter) }
                        }
                        MapButtons(
                            actions,
                            following,
                            Modifier
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .padding(end = StopsSizes.buttonColumnEnd)
                                .onSizeChanged { buttonsWidthPx = it.width.toFloat() + gapPx },
                        )
                        if (!wide) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                    .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
                                    .testTag(HomeTags.MapFooter),
                            ) { mapFooter(stackedFooter) }
                        }
                        RostyTabBar(
                            tabs = tabs,
                            selected = tabs.indexOfFirst { it.id == selectedTab.id }.coerceAtLeast(0),
                            onSelect = { index ->
                                unfolded = false
                                HomeTab.entries.firstOrNull { it.id == tabs[index].id }?.let(actions.onTab)
                            },
                            onReselect = { unfolded = false },
                            modifier =
                                (if (wide) Modifier.align(Alignment.Start).width(sideRoom) else Modifier)
                                    .testTag(HomeTags.TabBar),
                            folded = !unfolded,
                            onFoldedClick = { unfolded = true },
                            chrome = toastMetrics,
                        )
                    }
                }
            }
        },
    ) {
        map(Modifier.fillMaxSize())
    }
}

/** Below this the top chrome counts as hidden (no touch, no semantics). */
private const val HIDDEN_ALPHA = 0.05f

/** Swallows every touch of a hidden element so the map under it does not take a tap the user did not aim at it. */
private fun Modifier.blockTouches(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    }

/** What the camera padding is computed from; a data class so `snapshotFlow` emits on a real change only. */
private data class CameraInputs(
    val settledVisiblePx: Float,
    val fromTallest: Int,
    val topPx: Float,
    val endPx: Float,
    val blockPx: Float,
    val windowPx: Float,
    val wide: Boolean,
    /** What the metrics hold now: a write by the tab bar brings the clearance back. */
    val barWrite: Int,
)

/**
 * The toast's clearance above the window's bottom inset (pure), as `ChromeMetrics.bottomBarPx`: the higher of the
 * riding block's top ([blockTopPx], from the window bottom, insets included) and the settled sheet's top, less the
 * inset and the bar margin the toast maths adds back. A sheet settled above the middle of the window ([windowPx]) is not
 * cleared (nor the block riding it): the toast would leave the screen, so it keeps its own place near the bottom, over
 * the sheet.
 */
fun toastBarPx(
    blockTopPx: Float,
    sheetTopPx: Float,
    bottomInsetPx: Float,
    barMarginPx: Float,
    windowPx: Float = Float.MAX_VALUE,
): Int {
    if (sheetTopPx > windowPx * TOAST_MAX_RISE) return 0
    return (maxOf(blockTopPx, sheetTopPx) - bottomInsetPx - barMarginPx).coerceAtLeast(0f).toInt()
}

/** The highest a toast follows the sheet, as a share of the window height. */
private const val TOAST_MAX_RISE = 0.5f

/**
 * The top pill: a glass capsule that reads like a search field; a tap opens the pick sheet (E7.8: search, paste a
 * link, recent trips). One 48 dp row.
 */
@Composable
private fun SearchPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    val label = stringResource(R.string.home_search)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = StopsSizes.searchPill)
            .scalePress()
            .rostyGlass(GlassPreset.Bar, CircleShape, "stops.search")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ).semantics { role = Role.Button }
            .padding(horizontal = Spacing.l)
            .testTag(HomeTags.Search),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RostyIcon(RostyIcons.Search, null, tint = colors.labelSecondary)
        RostyText(
            label,
            style = RostyTheme.type.body,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The pick's next step ("Tap the map to set point A") or the pair's label ("A-B 850 m"), under the search pill. */
@Composable
private fun HintPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    RostyText(
        text,
        modifier
            .rostyGlass(GlassPreset.Bar, CircleShape, "stops.hint")
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .testTag(HomeTags.Hint),
        style = RostyTheme.type.subheadline,
        color = RostyTheme.colors.labelPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Locate / follow, layers and map options: 44 dp glass circles in 48 dp hit boxes. */
@Composable
private fun MapButtons(
    actions: HomeActions,
    following: Boolean,
    modifier: Modifier = Modifier,
) {
    // Written on placement, read on the tap: the popovers hang off the buttons.
    val bounds = remember { arrayOf(Rect.Zero, Rect.Zero) }
    val locate = stringResource(R.string.home_locate)
    Column(modifier.testTag(HomeTags.Buttons)) {
        GlassCircleButton(
            onClick = actions.onLocate,
            modifier = Modifier.semantics { contentDescription = locate }.testTag(HomeTags.Locate),
            onLongClick = actions.onLocateLong,
            // The dark glass adds white over its content: off while following, so the accent tint reads.
            applyAdditiveChrome = !following,
            size = Sizes.button,
            touchTarget = Sizes.touch,
        ) {
            RostyIcon(
                StopsIcons.Locate,
                null,
                tint = if (following) RostyTheme.colors.accent else LocalIosContentColor.current,
                size = StopsSizes.mapIcon,
            )
        }
        GlassIconButton(
            StopsIcons.Layers,
            stringResource(R.string.home_layers),
            { actions.onLayers(bounds[0]) },
            Modifier.onGloballyPositioned { bounds[0] = it.boundsInWindow() }.testTag(HomeTags.Layers),
        )
        GlassIconButton(
            RostyIcons.Map,
            stringResource(R.string.home_map_options),
            { actions.onMapOptions(bounds[1]) },
            Modifier.onGloballyPositioned { bounds[1] = it.boundsInWindow() }.testTag(HomeTags.Options),
        )
    }
}

/** From this font scale the map footer stacks. */
private const val STACKED_FOOTER_SCALE = 1.5f
