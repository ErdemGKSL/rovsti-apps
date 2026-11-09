package dev.rovsti.contacts.intake

import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ExportRequest
import dev.rovsti.core.model.contacts.LocalYmdRequest
import dev.rovsti.core.model.contacts.TextFileAnswer
import dev.rovsti.core.ops.ContactsOps
import java.util.TimeZone

/**
 * "Export as CSV" (E4.10 C39): every contact of the address book as one Google-style CSV (`contacts.csv_export`). The
 * text and the file name are the core's; the page writes them to the document the person chooses.
 */
class CsvExport(
    private val ops: ContactsOps,
    private val book: BookRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The file, or null when the address book is empty or cannot be read. */
    suspend fun build(): TextFileAnswer? {
        val drafts = (book.book.value as? Book.Ready)?.drafts.orEmpty()
        if (drafts.isEmpty()) return null
        val now = clock()
        val day =
            (
                ops.localYmd(LocalYmdRequest(now, TimeZone.getDefault().getOffset(now) / MS_PER_S)) as? Outcome.Ok
            )?.value ?: return null
        return (ops.csvExport(ExportRequest(drafts, day)) as? Outcome.Ok)?.value
    }

    private companion object {
        const val MS_PER_S = 1000
    }
}
