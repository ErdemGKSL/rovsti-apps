package dev.rovsti.contacts.list

import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.QuickAction
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import java.text.Normalizer

/**
 * A 40-contact address book for the list tests and shots (names from `apps/contacts/src/sample.rs` plus extras):
 * 5 starred, 4 on WhatsApp, 3 with a photo, a few with two numbers (those get the "Work and 1 more" line).
 */
internal object SampleBook {
    class Person(
        val id: Long,
        val given: String,
        val family: String,
        val number: String,
        val second: String? = null,
        val org: String = "",
    ) {
        val name get() = "$given $family"
    }

    val people: List<Person> =
        listOf(
            Person(1, "Ayşe", "Yılmaz", "+90 555 111 22 33"),
            Person(2, "Burak", "Demir", "0532 123 45 67"),
            Person(3, "Elif", "Kaya", "+90 212 555 00 11", "0533 765 43 21"),
            Person(4, "Mehmet", "Öz", "+49 151 234 567 89", "0216 555 01 02"),
            Person(5, "Özgür", "Şahin", "0544 987 65 43"),
            Person(6, "Zeynep", "Arslan", "+1 415 555 0132", org = "Acme Yazılım"),
            Person(7, "Ahmet", "Çelik", "0505 222 33 44"),
            Person(8, "Can", "Aydın", "0506 333 44 55"),
            Person(9, "Deniz", "Korkmaz", "0507 444 55 66"),
            Person(10, "İpek", "Güneş", "0538 555 66 77"),
            Person(11, "Ada", "Lovelace", "+44 20 7946 0958"),
            Person(12, "Alper", "Tekin", "0548 120 30 40"),
            Person(13, "Berrin", "Sezer", "0549 130 40 50"),
            Person(14, "Cem", "Karaca", "0551 140 50 60", "0552 141 51 61"),
            Person(15, "Dilara", "Eren", "0552 150 60 70"),
            Person(16, "Emre", "Polat", "0553 160 70 80"),
            Person(17, "Fatma Nur", "Çakır", "0554 170 80 90"),
            Person(18, "Gökhan", "Işık", "0555 180 90 10"),
            Person(19, "Hülya", "Avcı", "0556 190 10 20"),
            Person(20, "Kemal", "Aksoy", "0557 200 11 22"),
            Person(21, "Leyla", "Polat", "0558 210 12 23"),
            Person(22, "Mert", "Koç", "0559 220 13 24"),
            Person(23, "Melike", "Duman", "0561 230 14 25"),
            Person(24, "Nur", "Güneş", "0562 240 15 26"),
            Person(25, "Oğuz", "Bayrak", "0563 250 16 27"),
            Person(26, "Pelin", "Yurt", "0564 260 17 28"),
            Person(27, "Rıza", "Tan", "0565 270 18 29"),
            Person(28, "Selin", "Aktaş", "0566 280 19 30"),
            Person(29, "Tolga", "Uçar", "0567 290 20 31"),
            Person(30, "Ulaş", "Erdem", "0568 300 21 32", "0569 301 22 33"),
            Person(31, "Vedat", "Özer", "0571 310 22 33"),
            Person(32, "Yasemin", "Kurt", "0572 320 23 34"),
            Person(33, "Yusuf", "Çetin", "0573 330 24 35"),
            Person(34, "Zafer", "Bulut", "0574 340 25 36"),
            Person(35, "Zehra", "Aslan", "0575 350 26 37"),
            Person(36, "Barış", "Soylu", "0576 360 27 38"),
            Person(37, "Ceren", "Akın", "0577 370 28 39"),
            Person(38, "Doruk", "Ay", "0578 380 29 40"),
            Person(39, "Ezgi", "Taş", "0579 390 30 41"),
            Person(40, "112", "Emergency", "112"),
        )

    val starred = setOf(1L, 3L, 5L, 11L, 22L)
    val whatsapp = setOf(2L, 11L, 14L, 22L)
    val photos = setOf(1L, 11L, 22L)

    fun drafts(): List<ContactDraft> =
        people.map { p ->
            ContactDraft(
                id = p.id,
                given = p.given,
                family = p.family,
                org = p.org,
                numbers =
                    listOfNotNull(
                        DraftField(p.number, FieldKind.Mobile),
                        p.second?.let { DraftField(it, FieldKind.Work) },
                    ),
                starred = p.id in starred,
            )
        }

    fun sortKeyOf(name: String): String = fold(name)

    private fun fold(s: String): String =
        Normalizer
            .normalize(s, Normalizer.Form.NFD)
            .filter { it.code < 0x300 || it.code > 0x36f }
            .replace('ı', 'i')
            .lowercase()

    private fun letterOf(p: Person): String {
        val first = fold(p.given).firstOrNull { it.isLetterOrDigit() }
        return if (first != null && first in 'a'..'z') first.uppercase() else "#"
    }

    /** The rows the way `contacts.view` builds them: sorted by first name, a header on the first row of each letter. */
    fun items(
        photoSet: Set<Long> = photos,
        marked: Set<Long> = whatsapp,
        exclude: Set<Long> = emptySet(),
    ): List<ContactItem> {
        val sorted =
            people
                .filter { it.id !in exclude }
                .sortedWith(compareBy<Person>({ letterOf(it) == "#" }, { fold(it.name) }))
        var last = ""
        return sorted.map { p ->
            val letter = letterOf(p)
            val header = letter != last
            last = letter
            ContactItem(
                id = p.id,
                name = p.name,
                secondary = p.second?.let { "Work and 1 more" },
                letter = letter,
                header = header,
                starred = p.id in starred,
                whatsapp = p.id in marked,
                hasNumber = true,
                photoUri = if (p.id in photoSet) "content://contacts/photo/${p.id}" else null,
            )
        }
    }

    val quick =
        persistentListOf(
            QuickAction("call", "Call"),
            QuickAction("message", "Message"),
            QuickAction("copy_number", "Copy number"),
            QuickAction("share", "Share"),
            QuickAction("star", "Add to Favorites"),
            QuickAction("add_to_home", "Add to Home screen"),
        )

    private val quickStarred = quick.map { if (it.id == "star") QuickAction("unstar", "Remove from Favorites") else it }
    private val quickWa =
        quick.toMutableList().apply { add(2, QuickAction("whatsapp", "WhatsApp")) }

    fun quickFor(items: List<ContactItem>) =
        items
            .map { it.menuKey }
            .toSet()
            .associateWith { key ->
                when {
                    key.starred -> quickStarred
                    key.onWhatsapp -> quickWa
                    else -> quick
                }.toImmutableList()
            }.toImmutableMap()

    /** The state of the page over [items] (what the view model builds), with the rail and the long-press menus. */
    fun state(
        items: List<ContactItem> = items(),
        query: String = "",
        viewed: List<ContactItem> = emptyList(),
        deep: List<ContactItem> = emptyList(),
    ): ContactsListUiState {
        val searching = query.isNotBlank()
        val rows = items.toImmutableList()
        val (index, count) = sectionIndexOf(rows)
        return ContactsListUiState(
            phase = ListPhase.Ready,
            rows = rows,
            deepRows = deep.toImmutableList(),
            scrubberLetters = rows.filter { it.header }.map { it.letter.single() }.toImmutableList(),
            showScrubber = !searching && rows.size > 20,
            sectionIndex = index,
            scrubItems = count,
            query = query,
            queryEcho = query.trim(),
            emptyKind = if (rows.isEmpty() && deep.isEmpty()) EmptyKind.NoResults else EmptyKind.None,
            viewed = viewed.toImmutableList(),
            quick = quickFor(rows + deep + viewed),
        )
    }
}
