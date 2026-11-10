package dev.rovsti.phone.recents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.rovsti.core.model.phone.Direction
import dev.rovsti.core.model.phone.RecentsFilter
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.design.haptics.RovstiHaptics
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.phone.PhoneRoots
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneTabs
import dev.rovsti.phone.RecentsTab
import dev.rovsti.phone.RootLinks
import dev.rovsti.phone.chromeFor
import dev.rovsti.phone.phoneTabItems
import dev.rovsti.shell.RovstiShell
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/** Builds the rows the core would make, with the words the core uses. */
internal object RecentsSample {
    fun row(
        key: String,
        title: String,
        subtitle: String = "mobile",
        time: String = "12:30",
        direction: Direction = Direction.Incoming,
        count: String? = null,
        missed: Boolean = false,
        whatsapp: Boolean = false,
        named: Boolean = true,
        country: String? = null,
        sim: SimChipUi? = null,
        callable: Boolean = true,
        deletable: Boolean = true,
    ) = RecentsRowUi(
        key = key,
        title = title,
        count = count,
        subtitle = subtitle,
        country = country,
        time = time,
        direction = direction,
        missed = missed,
        whatsapp = whatsapp,
        callable = callable,
        number = if (named) "+90555" + key.filter { it.isDigit() }.padStart(7, '1') else title,
        hue = null,
        monogram = null,
        named = named,
        sim = sim,
        aria = "$title, $subtitle, $time",
        deletable = deletable,
    )

    /** A full page of calls over three days. */
    fun rows(sim: Boolean = false): List<RecentsItem> {
        val turkcell = if (sim) SimChipUi("Turkcell", 0xFF0A84FF.toInt()) else null
        val vodafone = if (sim) SimChipUi("Vodafone", 0xFFFF453A.toInt()) else null
        return listOf(
            RecentsItem.Header("Today", 0),
            RecentsItem.Row(
                row("1", "Ayse Yilmaz", "missed", "12:41", Direction.Missed, "(2)", missed = true, sim = turkcell),
            ),
            RecentsItem.Row(row("2", "Mert Kaya", "mobile", "11:05", Direction.Outgoing, sim = vodafone)),
            RecentsItem.Row(
                row(
                    "3",
                    "+49 30 1234567",
                    "Incoming",
                    "09:12",
                    Direction.Incoming,
                    named = false,
                    country = "Germany",
                    sim = turkcell,
                ),
            ),
            RecentsItem.Row(row("4", "Deniz Aksoy", "WhatsApp", "08:30", Direction.Incoming, whatsapp = true)),
            RecentsItem.Header("Yesterday", 1),
            RecentsItem.Row(row("5", "Selin Arslan", "mobile", "Yesterday", Direction.Incoming, sim = vodafone)),
            RecentsItem.Row(
                row("6", "Can Demir", "mobile", "Yesterday", Direction.Missed, missed = true, sim = turkcell),
            ),
            RecentsItem.Row(row("7", "Elif Sahin", "home", "Yesterday", Direction.Outgoing, "(3)")),
            RecentsItem.Row(row("8", "Burak Ozturk", "WhatsApp", "Yesterday", Direction.Outgoing, whatsapp = true)),
            RecentsItem.Header("Monday", 2),
            RecentsItem.Row(row("9", "Zeynep Celik", "mobile", "Monday", Direction.Incoming)),
            RecentsItem.Row(
                row(
                    "10",
                    "+1 415 555 0132",
                    "Incoming",
                    "Monday",
                    Direction.Incoming,
                    named = false,
                    country = "United States",
                ),
            ),
            RecentsItem.Row(row("11", "Kerem Aydin", "work", "Monday", Direction.Outgoing)),
            RecentsItem.Row(row("12", "Naz Polat", "mobile", "Monday", Direction.Missed, missed = true)),
            RecentsItem.Row(row("13", "Ozan Tekin", "mobile", "Monday", Direction.Incoming)),
            RecentsItem.Row(row("14", "Pelin Gunes", "mobile", "Monday", Direction.Outgoing)),
        )
    }

    fun ui(
        items: List<RecentsItem> = rows(),
        filter: RecentsFilter = RecentsFilter.All,
        query: String = "",
        select: RecentsSelect? = null,
        dualSim: Boolean = false,
        counts: FilterCounts =
            FilterCounts(all = 14, missed = 3, incoming = 7, outgoing = 5, whatsapp = 2, withNotes = 1),
    ) = RecentsUi(
        ready = true,
        items = items.toImmutableList(),
        rowCount = items.count { it is RecentsItem.Row },
        filter = filter,
        query = query,
        counts = counts,
        select = select,
        dualSim = dualSim,
    )

    fun selected(
        vararg keys: String,
        all: Boolean = false,
    ) = RecentsSelect(keys.toSet().toImmutableSet(), all)
}

/** Holds the page state the way the view model would and records what the screen asks for. */
internal class RecentsHarness(
    initial: RecentsUi = RecentsSample.ui(),
    var missedBadge: Int = 0,
) {
    var ui by mutableStateOf(initial)
    val actions = mutableListOf<RecentsAction>()
    val overlays = OverlayController()
    var settings = 0
    lateinit var listState: LazyListState
    lateinit var searchState: TextFieldState

    fun on(action: RecentsAction) {
        actions += action
    }
}

/** Recents inside the real shell: backdrop, tab bar (with the missed badge), select toolbar, overlay host. */
@Composable
internal fun HostedRecents(
    harness: RecentsHarness,
    strings: RecentsStrings,
    haptics: RovstiHaptics? = null,
    tab: dev.rovsti.design.transition.Tab = RecentsTab,
    initialQuery: String = "",
) {
    val navigator = remember { TabNavigator(PhoneTabs, PhoneRoots).also { it.select(tab) } }
    val tabs = phoneTabItems("Favorites", "Recents", "Contacts", "Keypad", missed = harness.missedBadge)
    harness.listState = rememberLazyListState()
    harness.searchState = rememberTextFieldState(initialQuery)
    val links = remember(harness) { RootLinks(openSettings = { harness.settings++ }, openSetup = {}) }
    val menuFor: (RecentsRowUi) -> kotlinx.collections.immutable.ImmutableList<dev.rovsti.design.overlay.MenuItem> =
        remember(strings) { { row -> recentsMenu(row, strings, harness::on) } }
    val content: @Composable () -> Unit = {
        RovstiShell(
            navigator = navigator,
            tabs = tabs,
            chromeFor = { route -> chromeFor(route, harness.ui.select != null) },
            overlays = harness.overlays,
            modifier = Modifier.fillMaxSize(),
            accessory =
                harness.ui.select?.let { chosen ->
                    { modifier, inline -> RecentsSelectToolbar(chosen, strings, harness::on, modifier, inline) }
                },
        ) {
            entry<PhoneRoute.Recents> {
                dev.rovsti.design.list.SwipeRowGroup {
                    RecentsContent(
                        ui = harness.ui,
                        strings = strings,
                        state = harness.listState,
                        searchState = harness.searchState,
                        onAction = harness::on,
                        links = links,
                        menuFor = menuFor,
                    )
                }
            }
            entry<PhoneRoute.Favorites> {}
            entry<PhoneRoute.Contacts> {}
            entry<PhoneRoute.Keypad> {}
        }
    }
    if (haptics != null) {
        CompositionLocalProvider(LocalRovstiHaptics provides haptics) { content() }
    } else {
        content()
    }
}

internal fun persistentEmpty() = persistentListOf<RecentsItem>()
