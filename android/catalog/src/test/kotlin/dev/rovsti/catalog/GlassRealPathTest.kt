package dev.rovsti.catalog

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.design.backdrop.BackdropDebug
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Proves the REAL glass path runs: on API 35 (see @Config) with default settings (nothing forced, no fallback) every catalog
 * page with glass and every overlay kind draws through `drawBackdrop` with a RenderEffect over a recorded
 * `LayerBackdrop`. [BackdropDebug] records one entry per glass draw (and one per fallback), so a surface that took the
 * translucent fallback, sampled an empty backdrop, or sampled a layer nobody recorded fails here.
 *
 * Only API 35 runs in this environment: the app's minSdk is 34, so API 33 is below it; Robolectric's API 34 jar was
 * rate-limited (HTTP 429) and its API 36 runtime fails on this JRE ("Failed to interact with raw FileDescriptor
 * internals"). Add 34 and 36 to the list where the jars and the JRE allow it.
 */
@Config(sdk = [35])
class GlassRealPathTest : ShotTest() {
    @Before
    fun enableDebug() {
        BackdropDebug.clear()
        BackdropDebug.enabled = true
    }

    @After
    fun disableDebug() {
        BackdropDebug.enabled = false
        BackdropDebug.clear()
    }

    /** At least one record with a tag starting with [tag], and every record real. */
    private fun assertRealPath(
        scenario: String,
        vararg tags: String,
    ) {
        // Compose draws lazily in this harness: a capture forces the draw phase, and with it the glass nodes.
        compose.onRoot().captureToImage()
        val records = BackdropDebug.snapshot()
        assertTrue("$scenario: no glass drew at all", records.isNotEmpty())
        for (tag in tags) {
            assertTrue(
                "$scenario: nothing tagged '$tag' drew; saw ${records.mapNotNull { it.tag }.distinct()}",
                records.any { it.tag?.startsWith(tag) == true },
            )
        }
        val bad =
            records.filter {
                !it.hasRenderEffect || it.backdropKind != BackdropDebug.Kind.Layer || !it.recorded
            }
        assertEquals(
            "$scenario: surfaces that did not use the backdrop effect: ${bad.distinct()}",
            emptyList<BackdropDebug.Record>(),
            bad,
        )
    }

    private fun open(page: CatalogPage) {
        composeFrame { CatalogApp(initialPage = page) }
        advance(600)
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        advance(1200)
    }

    @Test
    fun glassPage() {
        open(CatalogPage.Glass)
        assertRealPath("glass page", "glass.page.bar", "glass.page.capsule", "glass.page.panel")
    }

    @Test
    fun buttonsOverBackdrop() {
        open(CatalogPage.Buttons)
        assertRealPath("buttons over backdrop", "button.")
    }

    @Test
    fun tabBar() {
        open(CatalogPage.TabBar)
        assertRealPath("tab bar", "tabbar")
    }

    @Test
    fun foldedTabBar() {
        open(CatalogPage.FoldedTabBar)
        assertRealPath("folded tab bar", "tabbar", "button.capsule")
    }

    @Test
    fun largeTitleInlineBarAndToolbar() {
        open(CatalogPage.LargeTitle)
        assertRealPath("large title toolbar", "toolbar.capsule")
    }

    @Test
    fun largeTitleBackButton() {
        composeFrame { LargeTitlePage(initialPushed = 7) }
        advance(600)
        assertRealPath("back button", "button.icon")
    }

    @Test
    fun buttonMenuOpen() {
        open(CatalogPage.ButtonMenu)
        click("menu-trigger-top")
        assertRealPath("button menu", "menu.panel")
    }

    @Test
    fun overflowMenuOpen() {
        open(CatalogPage.LargeTitle)
        click("toolbar-overflow")
        assertRealPath("overflow menu", "menu.panel", "toolbar.capsule")
    }

    @Test
    fun contextMenuOpen() {
        open(CatalogPage.ContextMenu)
        compose.onNodeWithTag("ctx-row-2", useUnmergedTree = true).performTouchInput { down(center) }
        advance(16)
        advance(1000)
        assertRealPath("context menu", "context.panel")
    }

    @Test
    fun popoverOpen() {
        open(CatalogPage.Popover)
        click("pop-date")
        assertRealPath("popover", "popover.panel")
    }

    @Test
    fun sheetMedium() {
        open(CatalogPage.Sheets)
        click("sheet-medium")
        repeat(20) { advance(100) }
        assertRealPath("sheet", "sheet")
    }

    @Test
    fun alertAndActionSheet() {
        open(CatalogPage.Dialogs)
        click("dialog-alert")
        assertRealPath("alert", "alert.panel")
        BackdropDebug.clear()
        open(CatalogPage.Dialogs)
        click("dialog-sheet")
        assertRealPath("action sheet", "actionsheet.group")
    }

    @Test
    fun toast() {
        open(CatalogPage.Toasts)
        click("toast-show")
        assertRealPath("toast", "toast", "tabbar")
    }

    @Test
    fun focusedComposer() {
        open(CatalogPage.TextFields)
        compose.onNodeWithTag("composer-field").performClick()
        advance(600)
        assertRealPath("composer", "composer")
    }
}
