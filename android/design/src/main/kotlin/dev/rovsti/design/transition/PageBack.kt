package dev.rovsti.design.transition

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * The back action of a pushed page (ORIGINAL). [RovstiNavHost] provides it around every entry deeper than its tab root,
 * so a page that does not wire its own back button still gets the standard glass one in the top bar.
 *
 * @param onBack pops the page.
 * @param onLongClick jumps to the tab root (the back button's long press).
 */
@Immutable
data class PageBack(
    val onBack: () -> Unit,
    val onLongClick: () -> Unit,
)

/** The back action of the page being composed; null on a tab root (and outside a [RovstiNavHost]). */
val LocalPageBack = compositionLocalOf<PageBack?> { null }
