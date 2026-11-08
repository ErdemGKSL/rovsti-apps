package dev.rovsti.catalog

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing

/** The catalog shell: one current page, saved across recreation, Back returns to Home. */
@Composable
fun CatalogApp(initialPage: CatalogPage = CatalogPage.Home) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    BackHandler(enabled = page != CatalogPage.Home) { page = CatalogPage.Home }

    Box(
        modifier = Modifier.fillMaxSize().background(RovstiTheme.colors.page),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.fillMaxHeight().widthIn(max = Sizes.contentMaxWidth).fillMaxWidth()) {
            when (page) {
                CatalogPage.Home -> HomePage(onOpen = { page = it })
                CatalogPage.Toolchain -> ToolchainPage()
                CatalogPage.ThemePalette -> PalettePage()
                CatalogPage.ThemeType -> TypeScalePage()
                CatalogPage.Motion -> MotionPage()
                CatalogPage.Press -> PressPage()
                CatalogPage.Glass -> GlassPage()
                CatalogPage.Buttons -> ButtonsPage()
                CatalogPage.Rows -> RowsPage()
                CatalogPage.GroupedSection -> GroupedSectionPage()
                CatalogPage.Avatars -> AvatarsPage()
                CatalogPage.Chips -> ChipsPage()
                CatalogPage.EmptyState -> EmptyStatePage()
                CatalogPage.TabBar -> TabBarPage()
                CatalogPage.FoldedTabBar -> FoldedTabBarPage()
                CatalogPage.LargeTitle -> LargeTitlePage()
            }
        }
    }
}

@Composable
internal fun pagePadding(): PaddingValues =
    WindowInsets.safeDrawing
        .add(WindowInsets(left = Spacing.gutter, top = Spacing.gutter, right = Spacing.gutter, bottom = Spacing.gutter))
        .asPaddingValues()

@Composable
private fun HomePage(onOpen: (CatalogPage) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(insetsOnly())) {
        PageHeader(CatalogPage.Home.title, "One page per design-system component. Pages arrive with the E1 stories.")
        InsetGroupedSection(header = "Pages", modifier = Modifier.testTag("home-pages")) {
            for (target in CatalogPage.entries.filter { it != CatalogPage.Home }) {
                row { ListRow(title = target.title, trailing = { ListRowChevron() }, onClick = { onOpen(target) }) }
            }
        }
        InsetGroupedSection(header = "Planned", modifier = Modifier.padding(top = Spacing.sectionGap - Spacing.s)) {
            for (item in Planned) {
                row { ListRow(title = item.first, meta = item.second) }
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

private val Planned =
    listOf(
        "Overlay host and placement" to "E1.9",
    )
