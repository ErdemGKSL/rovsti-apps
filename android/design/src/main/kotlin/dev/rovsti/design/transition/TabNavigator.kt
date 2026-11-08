package dev.rovsti.design.transition

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One root destination of the app (a tab of the tab bar). [id] is stable and equals `TabItem.id`. */
@Immutable
data class Tab(
    val id: String,
)

/** Emitted when the selected tab is picked again while its stack is at the root: the page scrolls to its top. */
@Immutable
data class ScrollToTop(
    val tab: Tab,
)

/**
 * One entry of the stack that `NavDisplay` shows. The tab and the depth make the key unique across tabs and across
 * repeated routes, so the same route pushed twice (or in two tabs) is two pages.
 */
@Immutable
data class StackKey<R : Any>(
    val tab: Tab,
    val depth: Int,
    val route: R,
)

/**
 * Per-tab back stacks (ORIGINAL; SKILL navigation-layout 3). Each tab keeps its own stack of routes: switching tabs
 * never loses what the other tab showed. All state lives here and is plain: a [SnapshotStateList] per tab, so the host
 * recomposes on changes and a unit test can drive it without a UI.
 *
 * - [select] switches tabs. Picking the current tab again pops its stack to the root; at the root it emits
 *   [ScrollToTop] on [scrollToTop] (a hot flow with one buffered event, never a sticky flag).
 * - [push] / [pop] / [replaceTop] change the current tab's stack. [pop] returns false at the root, so the caller lets
 *   the activity finish.
 * - [resetTo] replaces a tab's stack with the routes of a deep link and selects it.
 * - [lastNavWasPop] is true after [pop] and a reselect that popped, false after everything else. The backdrop reads it
 *   so a predictive-back pop does not freeze the glass (Convx `lastNavWasPop`).
 * - [saver] saves the stacks as JSON through the routes' serializer.
 */
@Stable
class TabNavigator<R : Any>(
    val tabs: List<Tab>,
    private val roots: Map<Tab, R>,
    initialTab: Tab = tabs.first(),
) {
    init {
        require(tabs.isNotEmpty()) { "A navigator needs at least one tab" }
        require(tabs.all { it in roots }) { "Every tab needs a root route" }
        require(initialTab in tabs) { "Unknown initial tab $initialTab" }
    }

    private val stacks: Map<Tab, SnapshotStateList<R>> =
        tabs.associateWith { tab -> mutableStateListOf(roots.getValue(tab)) }

    private val events = MutableSharedFlow<ScrollToTop>(extraBufferCapacity = 1)

    var currentTab: Tab by mutableStateOf(initialTab)
        private set

    /** True when the last change went back (pop or reselect-to-root). Read by the backdrop freeze. */
    var lastNavWasPop: Boolean = false
        private set

    val scrollToTop: SharedFlow<ScrollToTop> = events.asSharedFlow()

    /** The visible route of the current tab. */
    val current: R get() = stacks.getValue(currentTab).last()

    /** The stack of [tab], root first. */
    fun stack(tab: Tab): List<R> = stacks.getValue(tab).toList()

    /** The current tab's stack as the keys `NavDisplay` shows. Reads snapshot state: call it in composition. */
    fun keys(): List<StackKey<R>> {
        val tab = currentTab
        return stacks.getValue(tab).mapIndexed { depth, route -> StackKey(tab, depth, route) }
    }

    fun select(tab: Tab) {
        require(tab in tabs) { "Unknown tab $tab" }
        if (tab != currentTab) {
            lastNavWasPop = false
            currentTab = tab
            return
        }
        val stack = stacks.getValue(tab)
        if (stack.size > 1) {
            lastNavWasPop = true
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
        } else {
            events.tryEmit(ScrollToTop(tab))
        }
    }

    fun push(route: R) {
        lastNavWasPop = false
        stacks.getValue(currentTab).add(route)
    }

    /** Pops the current tab; false (and no change) at its root. */
    fun pop(): Boolean {
        val stack = stacks.getValue(currentTab)
        if (stack.size <= 1) return false
        lastNavWasPop = true
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceTop(route: R) {
        lastNavWasPop = false
        val stack = stacks.getValue(currentTab)
        stack[stack.lastIndex] = route
    }

    /** Deep link: the stack of [tab] becomes its root followed by [routes], and [tab] is selected. */
    fun resetTo(
        tab: Tab,
        routes: List<R>,
    ) {
        require(tab in tabs) { "Unknown tab $tab" }
        lastNavWasPop = false
        val stack = stacks.getValue(tab)
        stack.clear()
        stack.add(roots.getValue(tab))
        stack.addAll(routes)
        currentTab = tab
    }

    companion object {
        /** Saves the selected tab and every stack as one JSON string; routes go through [serializer]. */
        fun <R : Any> saver(
            tabs: List<Tab>,
            roots: Map<Tab, R>,
            serializer: KSerializer<R>,
        ): Saver<TabNavigator<R>, String> {
            val list = ListSerializer(serializer)
            return Saver(
                save = { nav ->
                    buildJsonObject {
                        put("tab", nav.currentTab.id)
                        put(
                            "stacks",
                            buildJsonObject {
                                for (tab in nav.tabs) put(tab.id, Json.encodeToJsonElement(list, nav.stack(tab)))
                            },
                        )
                    }.toString()
                },
                restore = { text ->
                    val obj = Json.parseToJsonElement(text).jsonObject
                    val selected =
                        tabs.firstOrNull { it.id == obj.getValue("tab").jsonPrimitive.content } ?: tabs.first()
                    val nav = TabNavigator(tabs, roots, selected)
                    val saved: JsonObject = obj.getValue("stacks").jsonObject
                    for (tab in tabs) {
                        val element = saved[tab.id] ?: continue
                        val routes = Json.decodeFromJsonElement(list, element)
                        if (routes.isNotEmpty()) nav.restoreStack(tab, routes)
                    }
                    nav
                },
            )
        }
    }

    private fun restoreStack(
        tab: Tab,
        routes: List<R>,
    ) {
        val stack = stacks.getValue(tab)
        stack.clear()
        stack.addAll(routes)
    }
}
