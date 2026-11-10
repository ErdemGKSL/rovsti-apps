/*
 * Rovsti's tab bar: a facade over the ported Convx floating tab bar (package chrome.floatingtabbar). It keeps the signature
 * the apps use and maps it onto Convx's FoldingTabBar (tab group with the draggable lens puck, fold into a circle,
 * accessory slot), exactly the way Convx wires it in ui/component/FloatingNavBar.kt (AppFloatingNavBarChrome):
 * glass from the root backdrop as `tabBarContentModifier`, selected tab in the accent colour, 10 sp labels.
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/FloatingNavBar.kt
 * Changes for Rovsti: no search or player; tabs come from [TabItem]; the scroll connection is driven by [TabBarMinimizer]
 * and the [folded] flag; the accessory is a generic slot.
 */
package dev.rovsti.design.chrome

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.design.chrome.floatingtabbar.FloatingTabBarDefaults
import dev.rovsti.design.chrome.floatingtabbar.FloatingTabBarScrollConnection
import dev.rovsti.design.chrome.floatingtabbar.FoldingTabBar
import dev.rovsti.design.chrome.floatingtabbar.FoldingTabCell
import dev.rovsti.design.chrome.floatingtabbar.FoldingTabGroup
import dev.rovsti.design.controls.Badge
import dev.rovsti.design.glass.GlassEffectConfig
import dev.rovsti.design.glass.GlassPreset
import dev.rovsti.design.glass.LocalGlassEffectConfig
import dev.rovsti.design.glass.glassUsesDarkAppearance
import dev.rovsti.design.glass.liquidGlass
import dev.rovsti.design.glass.rovstiGlass
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.shapes.NavContinuousCapsule
import dev.rovsti.design.theme.GlassTintTokens
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.ImmutableList

/** One tab. [id] is stable and becomes the test tag `tab-<id>`; [label] is also the accessibility name. */
@Immutable
data class TabItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** An unread count drawn on the glyph (0: none). The label announced for the tab then ends with the count. */
    val badge: Int = 0,
)

/** Labels are dropped (icons only) from this system font scale, so they never clip. */
const val LabelsDropAtFontScale = 1.5f

/** Convx FloatingNavBar.kt `NavBarRowHeight`: open tab row height (32 dp glyph, label and the selector's inset). */
internal val TabBarRowHeight = 68.dp

/** Convx FloatingNavBar.kt: `FloatingTabBarDefaults.sizes()` content padding of the tab group is 4 dp on every side. */
internal val TabBarGroupPadding = 4.dp

private val GlyphSlot = 32.dp
private val GlyphSize = 26.dp

/** How far the unread badge sits out of the glyph slot's top-right corner. */
private val BadgeShift = 6.dp
private val LabelStyle = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)

/**
 * The floating tab bar: Convx's glass capsule with icon over a 10 sp label per tab, and the lens puck under the
 * selected tab that follows a press-drag and snaps to the nearest tab on release (`FoldingTabGroup`).
 *
 * - Width: the window less [Spacing.gutter] on both sides, at most [Sizes.tabBarMaxWidth]. Bottom margin: system inset
 *   plus [ChromeMetrics.BarMargin]. The bar's measured height (tab row plus [accessory], if any) goes to [chrome]; it
 *   writes 0 while hidden, so `LocalContentInsets` always leaves room for both.
 * - [minimizer]: scrolling down folds the bar into the circle of the selected tab (Convx's inline state); scrolling up
 *   unfolds it. [folded] holds it folded; tapping the circle calls [onFoldedClick] and expands the minimizer.
 * - [accessory]: a pill above the row (centred between the circle and the screen edge while folded). It receives the
 *   modifier that carries the bar's fill, shadow and glass, and whether the bar is currently folded.
 * - [imeVisible] hides the bar while the keyboard is up.
 *
 * [onSelect] runs when the user picks another tab; [onReselect] when the selected tab is picked again. [selected] is the
 * single source of truth: if the parent ignores [onSelect], the puck returns to [selected].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun RovstiTabBar(
    tabs: ImmutableList<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onReselect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minimizer: TabBarMinimizer? = null,
    folded: Boolean = false,
    onFoldedClick: () -> Unit = {},
    chrome: ChromeMetrics? = LocalChromeMetrics.current,
    imeVisible: @Composable () -> Boolean = { WindowInsets.isImeVisible },
    selectedDescription: String = "Selected",
    accessory: (@Composable (modifier: Modifier, isInline: Boolean) -> Unit)? = null,
) {
    val hidden = imeVisible()
    val overlays = LocalOverlayOrNull.current
    // Entries above the page (toasts) read the bar from the controller: they are composed outside the bar's locals.
    DisposableEffect(overlays, chrome) {
        if (chrome != null) overlays?.chromeMetrics = chrome
        onDispose { if (overlays?.chromeMetrics === chrome) overlays?.chromeMetrics = null }
    }
    LaunchedEffect(hidden, chrome) { if (hidden) chrome?.bottomBarPx = 0 }
    if (hidden || tabs.isEmpty()) return

    val haptics = LocalRovstiHaptics.current
    val colors = RovstiTheme.colors
    val tokens = RovstiTheme.glass
    val selectedNow by rememberUpdatedState(selected.coerceIn(0, tabs.lastIndex))
    val showLabels = LocalDensity.current.fontScale < LabelsDropAtFontScale

    // Convx drives the fold from a nested-scroll connection; here the minimizer and the folded flag own the state and
    // the connection mirrors it (a tap on the folded bar goes back through onExpandRequest).
    val inlineWanted = folded || (minimizer?.minimized == true)
    val onFoldedClickNow by rememberUpdatedState(onFoldedClick)
    val connection =
        remember(minimizer) {
            FloatingTabBarScrollConnection(initialIsInline = inlineWanted, scrollThresholdPx = 0f).also {
                it.onExpandRequest = {
                    minimizer?.expand()
                    onFoldedClickNow()
                }
            }
        }
    SideEffect { if (connection.isInline != inlineWanted) connection.mirrorInline(inlineWanted) }

    fun pick(index: Int) {
        if (index == selectedNow) {
            haptics.tick()
            onReselect(index)
        } else {
            haptics.selection()
            onSelect(index)
        }
    }

    // Convx FloatingNavBar.kt: the bar's glass is the nav-bar liquidGlass over the root backdrop; the highlight comes
    // from GlassTintTokens. A surface without a backdrop (content level) paints the grouped fill, as rovstiGlass does.
    val glassConfig = LocalGlassEffectConfig.current
    val isDark = glassUsesDarkAppearance()
    val hasBackdrop = LocalIosBackdrop.current != null
    val barGlass =
        if (hasBackdrop) {
            Modifier.liquidGlass(
                config = glassConfig,
                shape = NavContinuousCapsule,
                highlightAlpha = GlassTintTokens.pluckOpacity(isDark, false),
                rimTintColor = GlassTintTokens.pluckColor(isDark, Color.Unspecified, glassConfig.highlightColor),
                outerShadow = false,
                debugTag = "tabbar",
            )
        } else {
            Modifier.rovstiGlass(GlassPreset.Bar, NavContinuousCapsule, "tabbar", outerShadow = false)
        }
    val glassAccessory: (@Composable (Modifier, Boolean) -> Unit)? =
        accessory?.let { slot -> { m, inline -> slot(m.then(barGlass), inline) } }

    val selectedInk = colors.accent
    val unselectedInk = tokens.foreground.copy(alpha = 0.82f)
    val appBackdrop = LocalIosBackdrop.current

    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = Spacing.gutter, end = Spacing.gutter, bottom = ChromeMetrics.BarMargin),
        contentAlignment = Alignment.BottomCenter,
    ) {
        FoldingTabBar(
            selectedTabKey = tabs[selectedNow].id,
            scrollConnection = connection,
            rowHeight = TabBarRowHeight,
            tabBarContentModifier = barGlass,
            colors = FloatingTabBarDefaults.colors(Color.Transparent, Color.Transparent),
            shapes =
                FloatingTabBarDefaults.shapes(
                    tabBarShape = NavContinuousCapsule,
                    accessoryShape = NavContinuousCapsule,
                ),
            sizes = FloatingTabBarDefaults.sizes(),
            elevations = FloatingTabBarDefaults.elevations(),
            modifier =
                Modifier
                    .widthIn(max = Sizes.tabBarMaxWidth)
                    .onSizeChanged { chrome?.bottomBarPx = it.height },
            accessory = glassAccessory,
            detachLast = false,
            expandedTabs = { foldingTabs, surfaceBackdrop ->
                FoldingTabGroup(
                    selectedTabIndex = { selectedNow },
                    onTabSelected = { index -> foldingTabs.getOrNull(index)?.onClick?.invoke() },
                    backdrop = appBackdrop ?: surfaceBackdrop,
                    surfaceBackdrop = surfaceBackdrop,
                    tabsCount = foldingTabs.size,
                    accentColor = selectedInk,
                    containerColor = Color.Transparent,
                    height = TabBarRowHeight,
                    modifier = Modifier.testTag("tab-surface").selectableGroup(),
                ) {
                    foldingTabs.forEachIndexed { index, tab ->
                        val isSelected = index == selectedNow
                        FoldingTabCell(
                            onClick = tab.onClick,
                            onLongClick = tab.onLongClick,
                            modifier =
                                Modifier.testTag("tab-${tabs[index].id}").semantics {
                                    this.selected = isSelected
                                    if (isSelected) stateDescription = selectedDescription
                                },
                        ) {
                            tab.icon()
                            tab.title()
                        }
                    }
                }
            },
        ) {
            tabs.forEachIndexed { index, item ->
                val isSelected = index == selectedNow
                val ink = if (isSelected) selectedInk else unselectedInk
                tab(
                    key = item.id,
                    title = {
                        if (showLabels) {
                            BasicText(
                                text = item.label,
                                style =
                                    LabelStyle.copy(
                                        color = ink,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // The glyph carries the name; the drawn label must not be read a second time.
                                modifier = Modifier.semantics { invisibleToUser() },
                            )
                        }
                    },
                    icon = {
                        // Convx FloatingNavBar.kt: the glyph is drawn at 80 % of its slot, the rest is headroom for the bounce.
                        Box(Modifier.size(GlyphSlot), contentAlignment = Alignment.Center) {
                            // The glyph names the tab for the cell and for the folded circle; the drawn label is hidden from services.
                            RovstiIcon(item.icon, item.label, tint = ink, size = GlyphSize)
                            if (item.badge > 0) {
                                Badge(
                                    item.badge,
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = BadgeShift, y = -BadgeShift)
                                        .testTag("tab-badge-${item.id}"),
                                )
                            }
                        }
                    },
                    onClick = { pick(index) },
                )
            }
        }
    }
}
