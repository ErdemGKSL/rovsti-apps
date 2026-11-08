package dev.rovsti.catalog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.rovsti.design.chrome.BackdropScope
import dev.rovsti.design.chrome.BottomScrim
import dev.rovsti.design.chrome.ChromeMetrics
import dev.rovsti.design.chrome.LocalChromeMetrics
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.RovstiTabBar
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.ContactSections
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListScrollRail
import dev.rovsti.design.list.ListScrollRailWidth
import dev.rovsti.design.list.SectionHeader
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.list.SwipeRowGroup
import dev.rovsti.design.list.alphabetSectionKey
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.overlay.showUndo
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.collections.immutable.persistentListOf

/** One of the 120 contacts of the lists page. */
internal data class ListContact(
    val id: Int,
    val name: String,
)

private val FirstNames =
    listOf(
        "Ada Aiko Alma Anton Amara",
        "Bea Bruno Beatrix Boris Bianca",
        "Cleo Carlos Chiara Cem Camille",
        "Dara Diego Dilek Dmitri Daria",
        "Elif Emil Esra Eliot Elena",
        "Fatma Felix Farah Finn Freya",
        "Grace Gustav Gizem Greta Goran",
        "Hana Hugo Hakan Helga Hiro",
        "Ines Ivan Ilke Isla Idris",
        "Jonas Jana Jamal Joon Julia",
        "Kemal Kaya Klara Koji Kofi",
        "Lena Leo Laila Luca Lars",
        "Mira Mehmet Maya Marek Mona",
        "Nora Nils Nadia Nuri Noor",
        "Omar Olga Otto Orla Ozan",
        "Paula Pavel Petra Pinar Pedro",
        "Qadir Quinn Qiana Quentin",
        "Rosa Ravi Rana Rafael",
        "Sara Selim Sven Sofia",
        "Tara Tomas Tilda Tariq",
        "Uma Ugo Ulla Umut",
        "Vera Viktor Vivian Volkan",
        "Wanda Willem Wen Wilma",
        "Xavier Xena Ximena Xander",
        "Yara Yusuf Yuki Yvonne",
        "Zoe Zeki Zara Zane",
    )

private val Surnames =
    listOf("Lovelace", "Hopper", "Turing", "Okafor", "Silva", "Novak", "Haddad", "Lindqvist", "Moreau", "Tanaka")

/** 120 contacts, 4 or 5 per letter A-Z, sorted by name (a pure function, so screenshots never differ). */
internal fun listContacts(): List<ListContact> =
    FirstNames
        .flatMap { it.split(' ') }
        .mapIndexed { i, first -> ListContact(i, "$first ${Surnames[i % Surnames.size]}") }
        .sortedBy { it.name }

private fun phoneOf(contact: ListContact) = "Mobile, +49 170 555 01${(contact.id % 100).toString().padStart(2, '0')}"

/** The flat lazy list: a header item per letter followed by that letter's rows. */
internal sealed interface ListEntry {
    data class Header(
        val letter: Char,
    ) : ListEntry

    data class Row(
        val contact: ListContact,
    ) : ListEntry
}

internal fun entriesOf(contacts: List<ListContact>): List<ListEntry> =
    buildList {
        var current: Char? = null
        for (c in contacts) {
            val letter = alphabetSectionKey(c.name)
            if (letter != current) {
                add(ListEntry.Header(letter))
                current = letter
            }
            add(ListEntry.Row(c))
        }
    }

internal fun sectionIndexOf(entries: List<ListEntry>): Map<Char, Int> =
    buildMap { entries.forEachIndexed { i, e -> if (e is ListEntry.Header) put(e.letter, i) } }

/**
 * E1.19: 120 contacts A-Z with sticky letter headers, swipe actions (Pin; Mute and Delete with an undo toast; one row
 * open at a time) and the alphabet rail over a page that has the floating tab bar.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun ListsPage(imeVisible: @Composable () -> Boolean = { WindowInsets.isImeVisible }) {
    val colors = RovstiTheme.colors
    val ios = IosTheme.colors
    val controller = LocalOverlay.current
    val chrome = remember { ChromeMetrics() }
    var selected by remember { mutableIntStateOf(2) }
    val all = remember { listContacts() }
    val deleted = remember { mutableListOf<Int>().toMutableStateList() }
    val pinned = remember { mutableListOf<Int>().toMutableStateList() }
    val muted = remember { mutableListOf<Int>().toMutableStateList() }
    val visible = all.filter { it.id !in deleted }
    val entries = remember(visible) { entriesOf(visible) }
    val sectionIndex = remember(entries) { sectionIndexOf(entries) }
    val state = rememberLazyListState()
    var log by remember { mutableStateOf("") }

    BackdropScope(
        modifier = Modifier.fillMaxSize().testTag("lists-page"),
        baseBg = colors.page,
        content = {
            CompositionLocalProvider(LocalContentInsets provides chrome.contentInsets()) {
                SwipeRowGroup {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize().testTag("lists-list"),
                        contentPadding = LocalContentInsets.current,
                    ) {
                        item(key = "page-header") { PageHeader("Lists", "Sticky headers, swipe actions and a rail.") }
                        for (entry in entries) {
                            when (entry) {
                                is ListEntry.Header -> {
                                    stickyHeader(key = "h-${entry.letter}", contentType = "header") {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .background(colors.page)
                                                .testTag("header-${entry.letter}"),
                                        ) {
                                            SectionHeader(entry.letter.toString())
                                        }
                                    }
                                }

                                is ListEntry.Row -> {
                                    item(key = "c-${entry.contact.id}", contentType = "row") {
                                        val contact = entry.contact
                                        val isPinned = contact.id in pinned
                                        val isMuted = contact.id in muted
                                        SwipeRow(
                                            modifier = Modifier.testTag("contact-${contact.name}"),
                                            leading =
                                                persistentListOf(
                                                    SwipeAction(if (isPinned) "Unpin" else "Pin", ios.systemOrange) {
                                                        if (isPinned) {
                                                            pinned.remove(contact.id)
                                                        } else {
                                                            pinned.add(contact.id)
                                                        }
                                                        log = "Pin ${contact.name}"
                                                    },
                                                ),
                                            trailing =
                                                persistentListOf(
                                                    SwipeAction(if (isMuted) "Unmute" else "Mute", ios.systemIndigo) {
                                                        if (isMuted) muted.remove(contact.id) else muted.add(contact.id)
                                                        log = "Mute ${contact.name}"
                                                        controller.showToast(if (isMuted) "Unmuted" else "Muted")
                                                    },
                                                    SwipeAction("Delete", ios.systemRed) {
                                                        deleted.add(contact.id)
                                                        log = "Delete ${contact.name}"
                                                        controller.showUndo(
                                                            "${contact.name} deleted",
                                                            onUndo = { deleted.remove(contact.id) },
                                                        )
                                                    },
                                                ),
                                        ) {
                                            ListRow(
                                                title = contact.name,
                                                secondary = phoneOf(contact),
                                                leading = { Avatar(contact.name) },
                                                meta =
                                                    if (isPinned) {
                                                        "Pinned"
                                                    } else if (isMuted) {
                                                        "Muted"
                                                    } else {
                                                        null
                                                    },
                                                onClick = { log = "Open ${contact.name}" },
                                                endInset = ListScrollRailWidth,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        chrome = {
            CompositionLocalProvider(
                LocalChromeMetrics provides chrome,
                LocalContentInsets provides chrome.contentInsets(),
            ) {
                BottomScrim(chrome, Modifier.align(Alignment.BottomCenter))
                ListScrollRail(
                    lazyListState = state,
                    itemCount = entries.size,
                    sectionIndexMap = sectionIndex,
                    sections = ContactSections,
                    imeVisible = imeVisible,
                )
                RovstiTabBar(
                    tabs = CatalogTabs,
                    selected = selected,
                    onSelect = { selected = it },
                    onReselect = {},
                    modifier = Modifier.align(Alignment.BottomCenter).testTag("tab-bar"),
                )
            }
        },
    )
}
