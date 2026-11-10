package dev.rovsti.design.transition

import androidx.compose.runtime.Immutable

/** How the floating tab bar behaves on a page. */
enum class TabBarMode {
    /** The full bar, and the page leaves room for it (`LocalContentInsets.bottom`). */
    Visible,

    /**
     * The bar folded into the circle of the selected tab, and the page leaves room for it. For tab roots only (a list in
     * select mode): a pushed page must keep the full bar, because the folded circle alone reads as a lone gear. The
     * host rejects it on any page deeper than the root.
     */
    Folded,

    /** No bar; the page uses the whole height. */
    Hidden,
}

/**
 * What the app chrome does on one page (ORIGINAL). An app declares a pure `chromeFor(route): ChromeSpec`, unit tests it
 * on the JVM, and `RovstiNavHost` draws the bar and the page insets from it.
 *
 * [largeTitle] is the title the page shows as its large title (null: the page has none); the host does not draw it, the
 * page's scaffold does, and the spec keeps the title next to the rest of the page's chrome decisions so tests can check
 * them together.
 */
@Immutable
data class ChromeSpec(
    val tabBar: TabBarMode = TabBarMode.Visible,
    val largeTitle: String? = null,
)
