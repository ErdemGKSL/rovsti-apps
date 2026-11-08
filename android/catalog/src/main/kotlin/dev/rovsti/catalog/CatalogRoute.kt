package dev.rovsti.catalog

import dev.rovsti.design.chrome.TabItem
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.transition.ChromeSpec
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabBarMode
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/**
 * Every destination of the catalog. "Components" holds the home list and one pushed page per component; "Demo" is a
 * contact list -> detail -> notes stack that exercises push, pop, predictive back and the shared avatar.
 */
@Serializable
sealed interface CatalogRoute {
    @Serializable
    data object Home : CatalogRoute

    @Serializable
    data class Page(
        val page: CatalogPage,
    ) : CatalogRoute

    @Serializable
    data object Contacts : CatalogRoute

    @Serializable
    data class ContactDetail(
        val id: Int,
    ) : CatalogRoute

    @Serializable
    data class ContactNotes(
        val id: Int,
    ) : CatalogRoute
}

internal val ComponentsTab = Tab("components")
internal val DemoTab = Tab("demo")
internal val CatalogTabIds = listOf(ComponentsTab, DemoTab)

internal val AppTabs =
    persistentListOf(
        TabItem(ComponentsTab.id, "Components", RovstiIcons.Gear),
        TabItem(DemoTab.id, "Demo", RovstiIcons.People),
    )

/**
 * The chrome of each page (pure, tested on the JVM by `ChromeSpecTest`): the two tab roots and the contact detail keep
 * the tab bar; component pages and the notes sub-page hide it (component pages such as "Tab bar" draw bars of their own,
 * and a sub-page is a place you came into, not a place you switch from).
 */
fun catalogChromeFor(route: CatalogRoute): ChromeSpec =
    when (route) {
        CatalogRoute.Home -> ChromeSpec(TabBarMode.Visible, largeTitle = CatalogPage.Home.title)
        is CatalogRoute.Page -> ChromeSpec(TabBarMode.Hidden, largeTitle = null)
        CatalogRoute.Contacts -> ChromeSpec(TabBarMode.Visible, largeTitle = "Contacts")
        is CatalogRoute.ContactDetail -> ChromeSpec(TabBarMode.Visible, largeTitle = contactName(route.id))
        is CatalogRoute.ContactNotes -> ChromeSpec(TabBarMode.Hidden, largeTitle = "Notes")
    }
