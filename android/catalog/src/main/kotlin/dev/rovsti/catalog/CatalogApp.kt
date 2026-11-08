package dev.rovsti.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val colors = RovstiTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding()) {
        item {
            BasicText(
                text = CatalogPage.Home.title,
                style = TextStyle(color = colors.labelPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            BasicText(
                text = "One page per design-system component. Pages arrive with the E1 stories.",
                style = TextStyle(color = colors.labelSecondary, fontSize = 15.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(bottom = 24.dp),
            )
            SectionLabel("Pages")
        }
        items(CatalogPage.entries.filter { it != CatalogPage.Home }) { target ->
            CatalogRow(title = target.title, detail = null, onClick = { onOpen(target) })
        }
        item {
            SectionLabel("Planned", Modifier.padding(top = 24.dp))
        }
        items(Planned) { item ->
            CatalogRow(title = item.first, detail = item.second, onClick = null)
        }
    }
}

@Composable
private fun ToolchainPage() {
    val colors = RovstiTheme.colors
    val facts = toolchainFacts()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding()) {
        item {
            BasicText(
                text = CatalogPage.Toolchain.title,
                style = TextStyle(color = colors.labelPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
        items(facts) { fact -> CatalogRow(title = fact.first, detail = fact.second, onClick = null) }
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

@Composable
private fun CatalogRow(
    title: String,
    detail: String?,
    onClick: (() -> Unit)?,
) {
    val colors = RovstiTheme.colors
    val clickModifier =
        if (onClick == null) {
            Modifier
        } else {
            // No ripple: pressed feedback arrives with the press nodes in E1.4.
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
        }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = clickModifier.fillMaxWidth().heightIn(min = Sizes.rowMin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = title,
                style = TextStyle(color = colors.labelPrimary, fontSize = 17.sp, lineHeight = 22.sp),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (detail != null) {
                BasicText(
                    text = detail,
                    style = TextStyle(color = colors.labelSecondary, fontSize = 15.sp, lineHeight = 20.sp),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (onClick != null) {
                BasicText(
                    text = "›",
                    style = TextStyle(color = colors.labelSecondary, fontSize = 22.sp),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.separator))
    }
}

private val Planned =
    listOf(
        "Press feedback" to "E1.4",
        "Glass" to "E1.5",
        "Rows and buttons" to "E1.6",
        "Tab bar" to "E1.7",
        "Large-title scaffold" to "E1.8",
    )
