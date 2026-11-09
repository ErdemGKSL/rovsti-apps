/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/AlphabetScrollBar.kt
 * Changes for Rovsti: package, imports and theme (RovstiTheme colours and type, ContinuousRoundedRectangle from our shapes
 * package); section keys are `Char` and the sections are an `ImmutableList<Char>` supplied by the caller (Convx's music sort
 * keys and its grid overloads are gone); the haptic goes through `LocalRovstiHaptics`. Added: the letter bubble follows the
 * finger (Convx centres it on the rail); TalkBack adjustable semantics (`setProgress`, `stateDescription` = the letter)
 * instead of `clearAndSetSemantics {}`; `ListScrollRail` hides the rail while the keyboard is up and keeps it above
 * `LocalContentInsets.bottom` (the floating tab bar) instead of Convx's player-aware insets.
 */

package dev.rovsti.design.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.shapes.ContinuousRoundedRectangle
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Digits bucket first, then A-Z, then everything non-Latin under a single tail bucket. */
val AlphabetSections: ImmutableList<Char> = (listOf('0') + ('A'..'Z') + listOf('#')).toImmutableList()

/** The sections a contact list shows: A-Z and the `#` tail (no digit bucket). */
val ContactSections: ImmutableList<Char> = (('A'..'Z') + listOf('#')).toImmutableList()

/**
 * Below this the rail is clutter: a list you can reach the end of in two flings does not
 * need a scrubber, and the rail costs real estate on every row it overlaps.
 */
const val AlphabetScrollBarMinItems = 20

/**
 * Width of the alphabet rail: wide enough to be a comfortable thumb target. Rows under it pass this as
 * `ListRow(endInset = ...)` so their text stops before it.
 */
val ListScrollRailWidth = 28.dp
private val RailWidth = ListScrollRailWidth

/** Space between the bubble and the rail. */
private val BubbleGap = 12.dp

/** Diameter of the letter bubble. */
private val BubbleSize = 64.dp

/** Rail slot that jumps to index 0 rather than to a section. Drawn above the first letter. */
private const val ScrollTopSection = '↑'

/** What a screen reader says for the top slot instead of the arrow glyph. */
private const val ScrollTopSpoken = "Top"

/** Tags for tests. */
object AlphabetRailTags {
    const val Rail = "alphabet-rail"
    const val Bubble = "alphabet-bubble"
}

/**
 * Fast-scrub rail that maps drag position to a list section and shows its label.
 * Empty sections remain in place and resolve to the next populated section.
 *
 * [sectionIndexMap] maps a section to the index of its first item among the [itemCount] scrubbable items.
 */
@Composable
fun AlphabetScrollBar(
    sectionIndexMap: Map<Char, Int>,
    itemCount: Int,
    isAtTarget: (Int) -> Boolean,
    scrollToItem: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
    sections: ImmutableList<Char> = AlphabetSections,
    showScrollTop: Boolean = true,
    labelOf: (Char) -> String = { it.toString() },
    showIndicator: Boolean = true,
) {
    val haptics = LocalRovstiHaptics.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()

    // The rail draws one extra slot above the letters. It is part of the same scrub
    // surface -- dragging up past the first letter reaches the top of the list -- so it has to share
    // the cell geometry rather than sit outside the Column as a separate button.
    val railItems =
        remember(sections, showScrollTop) {
            if (showScrollTop) (persistentListOf(ScrollTopSection) + sections) else sections
        }

    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var selectedSection by remember { mutableStateOf<Char?>(null) }
    var indicatorSection by remember { mutableStateOf<Char?>(null) }
    var indicatorIndex by remember { mutableIntStateOf(0) }
    var indicatorVisible by remember { mutableStateOf(false) }
    var lastSelectedIndex by remember { mutableIntStateOf(-1) }

    // What TalkBack last put the rail on (the rail has no other resting state).
    var spokenIndex by remember { mutableIntStateOf(0) }

    val currentItemCount by rememberUpdatedState(itemCount)
    val currentSectionIndexMap by rememberUpdatedState(sectionIndexMap)
    val currentSections by rememberUpdatedState(sections)
    val currentRailItems by rememberUpdatedState(railItems)
    val currentIsAtTarget by rememberUpdatedState(isAtTarget)
    val currentScrollToItem by rememberUpdatedState(scrollToItem)

    fun updateSelection(index: Int) {
        if (index !in currentRailItems.indices || index == lastSelectedIndex) return
        lastSelectedIndex = index
        val section = currentRailItems[index]
        selectedSection = section
        indicatorSection = section
        indicatorIndex = index
        spokenIndex = index
        indicatorVisible = true

        val maxIndex = currentItemCount - 1
        if (maxIndex < 0) return
        val target =
            if (section == ScrollTopSection) {
                0
            } else {
                findAlphabetTargetIndex(
                    section = section,
                    sectionIndexMap = currentSectionIndexMap,
                    sections = currentSections,
                ).coerceIn(0, maxIndex)
            }

        if (!currentIsAtTarget(target)) {
            scrollJob?.cancel()
            scrollJob =
                scope.launch {
                    try {
                        val latestMax = currentItemCount - 1
                        if (latestMax >= 0) currentScrollToItem(target.coerceAtMost(latestMax))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: IndexOutOfBoundsException) {
                        // A re-sort can replace the lazy layout between validation and scrolling.
                    } catch (_: IllegalArgumentException) {
                        // Lazy layouts reject an index from a concurrently replaced item provider.
                    }
                }
        }
        haptics.tick()
    }

    fun clearSelection() {
        selectedSection = null
        indicatorVisible = false
        lastSelectedIndex = -1
    }

    BoxWithConstraints(modifier = modifier) {
        if (railItems.isEmpty() || maxHeight <= 0.dp) return@BoxWithConstraints

        // Captured into a local: BoxWithConstraintsScope and RowScope both carry
        // @LayoutScopeMarker, so inside the Row below the outer scope is shadowed and
        // maxHeight stops resolving.
        val railHeight = maxHeight
        val cellSize = railHeight / railItems.size.toFloat()
        val cellHeightPx = with(LocalDensity.current) { cellSize.toPx() }
        val railHeightPx = with(LocalDensity.current) { railHeight.toPx() }
        val trackAlpha by animateFloatAsState(
            targetValue = if (indicatorVisible) 0.9f else 0f,
            label = "railTrackAlpha",
        )
        val lastIndex = railItems.lastIndex

        Row(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .height(railHeight),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlphabetIndicator(
                section = indicatorSection?.let(labelOf),
                visible = indicatorVisible && showIndicator,
                // Rovsti: the bubble sits level with the finger. The Row centres it, so the offset is the distance
                // from the rail's centre to the centre of the selected cell, kept inside the rail.
                offsetY = {
                    val centre = (indicatorIndex + 0.5f) * cellHeightPx
                    val half = BubbleSize.toPx() / 2f
                    val clamped = centre.coerceIn(half, (railHeightPx - half).coerceAtLeast(half))
                    (clamped - railHeightPx / 2f).roundToInt()
                },
            )
            Spacer(modifier = Modifier.width(BubbleGap))
            Column(
                modifier =
                    Modifier
                        .width(RailWidth)
                        .height(railHeight)
                        .testTag(AlphabetRailTags.Rail)
                        // A track, not bare floating letters: it reads as a control, and it
                        // only appears while a scrub is in progress so a resting list stays
                        // clean.
                        .background(
                            color = RovstiTheme.colors.fillSecondary.copy(alpha = trackAlpha),
                            shape = ContinuousRoundedRectangle(RailWidth / 2),
                        )
                        // Rovsti: one adjustable control instead of Convx's empty semantics. Announcing every letter
                        // would bury the list in the accessibility tree; swiping up/down changes the letter.
                        .semantics {
                            contentDescription = "Index"
                            val spoken = railItems[spokenIndex.coerceIn(0, lastIndex)]
                            stateDescription = if (spoken == ScrollTopSection) ScrollTopSpoken else labelOf(spoken)
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(
                                    current = spokenIndex.toFloat().coerceIn(0f, lastIndex.toFloat()),
                                    range = 0f..lastIndex.toFloat().coerceAtLeast(1f),
                                    steps = (lastIndex - 1).coerceAtLeast(0),
                                )
                            setProgress { value ->
                                updateSelection(value.roundToInt().coerceIn(0, lastIndex))
                                // No finger is down, so the bubble does not linger.
                                clearSelection()
                                true
                            }
                        }.pointerInput(railItems, cellHeightPx, sectionIndexMap) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var dragged = false
                                updateSelection((down.position.y / cellHeightPx).toInt())
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change =
                                            event.changes.firstOrNull { it.id == down.id }
                                                ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        if (!dragged &&
                                            (change.position - down.position).getDistance() >= touchSlop
                                        ) {
                                            dragged = true
                                        }
                                        if (dragged) {
                                            updateSelection(
                                                (change.position.y / cellHeightPx)
                                                    .toInt()
                                                    .coerceIn(0, railItems.lastIndex),
                                            )
                                        }
                                    }
                                } finally {
                                    clearSelection()
                                }
                            }
                        },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                railItems.forEach { section ->
                    AlphabetCell(
                        section = labelOf(section),
                        selected = selectedSection == section,
                        size = cellSize,
                    )
                }
            }
        }
    }
}

/** Number of scrub stops the proportional rail offers. Matches the alphabet rail's height. */
private const val ProportionalRailStops = 27

/** Every stop draws the same tick; the rail is a position, not a label. */
private const val ProportionalRailTick = "·"

/** Proportional stops are keyed by private-use characters, so they never collide with a letter. */
private const val ProportionalBase = ''

/**
 * The one call site every long list uses.
 *
 * Pass [sectionIndexMap] when the list is sorted alphabetically and the rail shows
 * letters. Pass null for any other sort — the rail stays, but becomes a proportional
 * fast-scroll thumb instead of vanishing, which is the whole point: a scrubber that
 * disappears when you change the sort order is a scrubber nobody can rely on.
 *
 * The proportional mode is the same rail with synthetic sections spaced evenly through
 * the list, so both modes share one gesture, one geometry and one haptic.
 */
@Composable
fun ListScrollRail(
    sectionIndexMap: Map<Char, Int>?,
    itemCount: Int,
    isAtTarget: (Int) -> Boolean,
    scrollToItem: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
    sections: ImmutableList<Char> = AlphabetSections,
) {
    if (itemCount < AlphabetScrollBarMinItems) return

    if (sectionIndexMap != null) {
        // A descending sort runs Z->A down the list, so the rail has to run Z->A down the
        // screen too or the thumb travels opposite to the content. Detected from the map
        // rather than passed in, so no call site can get the two out of step.
        val ordered =
            remember(sectionIndexMap, sections) {
                if (sectionIndexIsDescending(sectionIndexMap, sections)) {
                    sections.asReversed().toImmutableList()
                } else {
                    sections
                }
            }
        AlphabetScrollBar(
            sectionIndexMap = sectionIndexMap,
            itemCount = itemCount,
            isAtTarget = isAtTarget,
            scrollToItem = scrollToItem,
            modifier = modifier,
            sections = ordered,
        )
        return
    }

    val stops = remember { List(ProportionalRailStops) { ProportionalBase + it }.toImmutableList() }
    val proportionalIndex =
        remember(itemCount) {
            buildProportionalSectionIndex(itemCount, ProportionalRailStops)
        }
    AlphabetScrollBar(
        sectionIndexMap = proportionalIndex,
        itemCount = itemCount,
        isAtTarget = isAtTarget,
        scrollToItem = scrollToItem,
        modifier = modifier,
        sections = stops,
        labelOf = { section -> if (section == ScrollTopSection) section.toString() else ProportionalRailTick },
        showIndicator = false,
    )
}

/**
 * The rail, positioned and offset for a [LazyListState] whose scrubbable items are the
 * last ones in the layout.
 *
 * Screens put headers, chips and sort buttons above their list, so rail index 0 is not
 * lazy index 0. Whatever the headers add up to on this pass is exactly the difference
 * between the layout's item count and [itemCount]. It is read inside the lambdas rather
 * than in composition on purpose: `layoutInfo` is snapshot state, and reading it during
 * composition would recompose the whole screen on every scrolled pixel.
 *
 * Rovsti: the rail is inset by [LocalContentInsets] (top bar, and the floating tab bar at the bottom) and is not drawn at
 * all while the keyboard is up ([imeVisible], a parameter so tests can force it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.ListScrollRail(
    lazyListState: LazyListState,
    itemCount: Int,
    sectionIndexMap: Map<Char, Int>?,
    modifier: Modifier = Modifier,
    sections: ImmutableList<Char> = AlphabetSections,
    imeVisible: @Composable () -> Boolean = { WindowInsets.isImeVisible },
) {
    if (imeVisible()) return
    val insets = LocalContentInsets.current
    val direction = LocalLayoutDirection.current
    val headerOffset = {
        (lazyListState.layoutInfo.totalItemsCount - itemCount).coerceAtLeast(0)
    }
    // The list pads its top by the status inset only; the frosted bar covers the next stretch. A jump lands the section
    // below the bar (Rovsti), not under it.
    val density = LocalDensity.current
    val barExtraPx =
        with(density) {
            (insets.calculateTopPadding() - WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding())
                .coerceAtLeast(0.dp)
                .roundToPx()
        }
    ListScrollRail(
        sectionIndexMap = sectionIndexMap,
        itemCount = itemCount,
        // updateSelection already skips an unchanged rail stop; a scroll to the same place costs nothing.
        isAtTarget = { false },
        // Not animateScrollToItem: a scrub samples many sections per second and each
        // animation would be cancelled by the next, so the list would crawl behind the
        // thumb instead of tracking it.
        scrollToItem = {
            val index = it + headerOffset()
            lazyListState.scrollToItem(index, if (index == 0) 0 else -barExtraPx)
        },
        modifier =
            modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(
                    top = insets.calculateTopPadding(),
                    bottom = insets.calculateBottomPadding() + Spacing.s,
                    end =
                        if (direction == LayoutDirection.Ltr) {
                            insets.calculateRightPadding(direction)
                        } else {
                            insets.calculateLeftPadding(direction)
                        },
                ),
        sections = sections,
    )
}

/**
 * True when the sections appear in reverse alphabetical order down the list.
 *
 * Compares the first and last populated sections in alphabet order: if "A" starts later
 * in the list than "Z" does, the list is sorted descending.
 */
internal fun sectionIndexIsDescending(
    sectionIndexMap: Map<Char, Int>,
    sections: List<Char> = AlphabetSections,
): Boolean {
    val populated =
        sections.mapNotNull { section ->
            sectionIndexMap[section]
        }
    if (populated.size < 2) return false
    return populated.first() > populated.last()
}

/** Spread [stops] evenly over [itemCount], keyed the way the rail keys its sections. */
internal fun buildProportionalSectionIndex(
    itemCount: Int,
    stops: Int,
): Map<Char, Int> {
    if (itemCount <= 0 || stops <= 0) return emptyMap()
    val lastItem = itemCount - 1
    return (0 until stops).associate { stop ->
        val fraction = if (stops == 1) 0f else stop.toFloat() / (stops - 1)
        (ProportionalBase + stop) to (fraction * lastItem).toInt().coerceIn(0, lastItem)
    }
}

@Composable
private fun AlphabetIndicator(
    section: String?,
    visible: Boolean,
    offsetY: Density.() -> Int,
) {
    AnimatedVisibility(
        visible = visible && section != null,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier.offset { IntOffset(0, offsetY()) },
    ) {
        Box(
            modifier =
                Modifier
                    .size(BubbleSize)
                    .testTag(AlphabetRailTags.Bubble)
                    .background(
                        color = RovstiTheme.colors.accent.copy(alpha = 0.92f),
                        shape = ContinuousRoundedRectangle(BubbleSize / 2),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            RovstiText(
                text = section.orEmpty(),
                style = RovstiTheme.type.title2,
                color = RovstiTheme.colors.onAccent,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AlphabetCell(
    section: String,
    selected: Boolean,
    size: Dp,
) {
    // The rail always spans the full available height, so on a short window the cells
    // get small rather than the rail getting shorter. Type steps down to match.
    val fontSize =
        when {
            size < 8.dp -> 6.sp
            size < 12.dp -> 8.sp
            size < 16.dp -> 10.sp
            else -> 12.sp
        }
    Box(
        // Height from the rail's own division, width from the rail: a tall window would
        // otherwise make each cell wider than the track it sits in.
        modifier =
            Modifier
                .height(size)
                .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        RovstiText(
            text = section,
            style =
                RovstiTheme.type.caption2.copy(
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
            color = if (selected) RovstiTheme.colors.labelPrimary else RovstiTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Resolve a rail section to a list index.
 *
 * An empty section falls forward to the next populated one, so tapping "Q" in a library
 * with no Q artists lands on R rather than doing nothing. Past the end it falls back to
 * the last populated section instead of index 0, which is what makes a scrub to the
 * bottom of the rail reach the bottom of the list.
 */
fun findAlphabetTargetIndex(
    section: Char,
    sectionIndexMap: Map<Char, Int>,
    sections: List<Char> = AlphabetSections,
): Int {
    if (sectionIndexMap.isEmpty()) return 0
    sectionIndexMap[section]?.let { return it }

    val requestedIndex = sections.indexOf(section)
    if (requestedIndex < 0) return 0

    sections
        .drop(requestedIndex + 1)
        .firstNotNullOfOrNull(sectionIndexMap::get)
        ?.let { return it }

    return sections.asReversed().firstNotNullOfOrNull(sectionIndexMap::get) ?: 0
}

/** Bucket a display title into one of [AlphabetSections]. */
fun alphabetSectionKey(title: String): Char {
    val first = title.trimStart().firstOrNull() ?: return '#'
    return when {
        first.isDigit() -> '0'
        first in 'A'..'Z' -> first
        first in 'a'..'z' -> first.uppercaseChar()
        else -> '#'
    }
}

/** Build the section -> first-index map a rail needs from an already-sorted list. */
fun <T> buildAlphabetSectionIndex(
    items: List<T>,
    titleOf: (T) -> String,
): Map<Char, Int> {
    val map = LinkedHashMap<Char, Int>()
    items.forEachIndexed { index, item ->
        map.putIfAbsent(alphabetSectionKey(titleOf(item)), index)
    }
    return map
}
