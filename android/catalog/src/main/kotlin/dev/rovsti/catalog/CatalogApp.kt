package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.transition.RovstiNavHost
import dev.rovsti.design.transition.TabNavigator

/** The catalog shell: [RovstiNavHost] with two tabs; Back pops, a tab keeps its own stack (see [CatalogRoute]). */
@Composable
fun CatalogApp(initialPage: CatalogPage = CatalogPage.Home) {
    val roots = remember { mapOf(ComponentsTab to CatalogRoute.Home, DemoTab to CatalogRoute.Contacts) }
    val navigator =
        rememberSaveable(saver = TabNavigator.saver(CatalogTabIds, roots, CatalogRoute.serializer())) {
            TabNavigator(CatalogTabIds, roots).also { nav ->
                if (initialPage != CatalogPage.Home) nav.resetTo(ComponentsTab, listOf(CatalogRoute.Page(initialPage)))
            }
        }
    val overlays = remember { OverlayController() }
    RovstiNavHost(
        navigator = navigator,
        tabs = AppTabs,
        chromeFor = ::catalogChromeFor,
        overlays = overlays,
    ) {
        entry<CatalogRoute.Home> { HomePage(navigator) }
        entry<CatalogRoute.Page> { route -> CatalogPageFrame { PageContent(route.page) } }
        entry<CatalogRoute.Contacts> { ContactsPage(navigator) }
        entry<CatalogRoute.ContactDetail> { route -> ContactDetailPage(route.id, navigator) }
        entry<CatalogRoute.ContactNotes> { route -> ContactNotesPage(route.id, navigator) }
    }
}

/** A page capped at the content width, centred, as the catalog always showed them. */
@Composable
internal fun CatalogPageFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxHeight().widthIn(max = Sizes.contentMaxWidth).fillMaxWidth()) { content() }
    }
}

@Composable
private fun PageContent(page: CatalogPage) {
    when (page) {
        CatalogPage.Home -> Unit
        CatalogPage.Toolchain -> ToolchainPage()
        CatalogPage.ThemePalette -> PalettePage()
        CatalogPage.ThemeType -> TypeScalePage()
        CatalogPage.Motion -> MotionPage()
        CatalogPage.Press -> PressPage()
        CatalogPage.Glass -> GlassPage()
        CatalogPage.BackdropEngine -> BackdropEnginePage()
        CatalogPage.IosGlass -> IosGlassPage()
        CatalogPage.Buttons -> ButtonsPage()
        CatalogPage.Controls -> ControlsPage()
        CatalogPage.Rows -> RowsPage()
        CatalogPage.Swipe -> SwipePage()
        CatalogPage.Lists -> ListsPage()
        CatalogPage.GroupedSection -> GroupedSectionPage()
        CatalogPage.Avatars -> AvatarsPage()
        CatalogPage.Photos -> PhotosPage()
        CatalogPage.Chips -> ChipsPage()
        CatalogPage.EmptyState -> EmptyStatePage()
        CatalogPage.TabBar -> TabBarPage()
        CatalogPage.FoldedTabBar -> FoldedTabBarPage()
        CatalogPage.LargeTitle -> LargeTitlePage()
        CatalogPage.Overlays -> OverlaysPage()
        CatalogPage.ButtonMenu -> ButtonMenuPage()
        CatalogPage.ContextMenu -> ContextMenuPage()
        CatalogPage.Popover -> PopoverPage()
        CatalogPage.Dialogs -> DialogsPage()
        CatalogPage.Sheets -> SheetsPage()
        CatalogPage.TextFields -> TextFieldsPage()
        CatalogPage.Toasts -> ToastsPage()
    }
}

@Composable
internal fun pagePadding(): PaddingValues =
    WindowInsets.safeDrawing
        .add(WindowInsets(left = Spacing.gutter, top = Spacing.gutter, right = Spacing.gutter, bottom = Spacing.gutter))
        .asPaddingValues()

@Composable
private fun HomePage(navigator: TabNavigator<CatalogRoute>) {
    val scroll = rememberScrollState()
    LaunchedEffect(navigator, scroll) {
        navigator.scrollToTop.collect { if (it.tab == ComponentsTab) scroll.animateScrollTo(0) }
    }
    val top = insetsOnly().calculateTopPadding()
    val bottom = LocalContentInsets.current.calculateBottomPadding()
    CatalogPageFrame {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = top, bottom = bottom)) {
            PageHeader(
                CatalogPage.Home.title,
                "One page per design-system component. Pages arrive with the E1 stories.",
            )
            InsetGroupedSection(header = "Pages", modifier = Modifier.testTag("home-pages")) {
                for (target in CatalogPage.entries.filter { it != CatalogPage.Home }) {
                    row {
                        ListRow(
                            title = target.title,
                            trailing = { ListRowChevron() },
                            onClick = { navigator.push(CatalogRoute.Page(target)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolchainPage() {
    val facts = toolchainFacts()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader(CatalogPage.Toolchain.title, "What this build runs on.")
        InsetGroupedSection {
            for (fact in facts) {
                row { ListRow(title = fact.first, meta = fact.second) }
            }
        }
    }
}

@Composable
internal fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text.uppercase(),
        style =
            TextStyle(
                color = RovstiTheme.colors.labelSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            ),
        modifier = modifier.padding(bottom = 8.dp),
    )
}
