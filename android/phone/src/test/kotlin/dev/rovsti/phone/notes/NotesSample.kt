package dev.rovsti.phone.notes

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** What the core would answer for the screenshots, with its words (24 hour clock, day first). */
internal object NotesSample {
    private fun row(
        number: String,
        atMs: Long,
        name: String?,
        whenText: String,
        preview: String,
    ) = NoteRowUi(
        key = NotesViewModel.rowKey(number, atMs),
        number = number,
        atMs = atMs,
        title = name ?: DISPLAYED[number] ?: number,
        subtitle = "$whenText · $preview",
        name = name,
    )

    /** What `NumberDisplay` makes of a stranger's number (F38): the golden never shows the raw digits. */
    private val DISPLAYED = mapOf("+905557771122" to "+90 555 777 11 22")

    private val rows =
        listOf(
            row(
                "+905551112233",
                1_000_007L,
                "Ayse Yilmaz",
                "Tue 14 Nov 2023, 21:13",
                "Fatura icin geri ara, cuma gunu",
            ),
            row("+905559998877", 1_000_006L, "Mert Kaya", "Mon 13 Nov 2023, 21:13", "Bring the invoice…"),
            row("+905557771122", 1_000_005L, null, "Sun 12 Nov 2023, 21:13", "Meeting moved to Friday, same room"),
            row("+905553334455", 1_000_004L, "Deniz Aksoy", "Sat 11 Nov 2023, 10:02", "Ask about the new office keys"),
            row("+905554445566", 1_000_003L, "Elif Sahin", "Fri 10 Nov 2023, 18:40", "Kargo adresi degisti"),
            row("+905555556677", 1_000_002L, "Burak Ozturk", "Thu 9 Nov 2023, 09:15", "Lunch on Thursday, his treat"),
            row("+905556667788", 1_000_001L, "Can Demir", "Wed 8 Nov 2023, 16:55", "Wants the quote by Monday"),
        )

    fun all() = NotesUi(ready = true, total = rows.size, rows = rows.toImmutableList())

    fun search() = NotesUi(ready = true, query = "invoice", total = rows.size, rows = persistentListOf(rows[1]))

    fun empty() = NotesUi(ready = true)

    fun noMatch() = NotesUi(ready = true, query = "zzz", total = rows.size)
}
