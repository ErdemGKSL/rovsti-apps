package dev.rovsti.phone.details

import android.content.res.Resources
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import dev.rovsti.core.model.phone.RecentsRow
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Who a Recents row is about: the contact with all of their numbers when the row has one in the book, else the one
 * number
 * (named from the call log when the log knew a name). A number that only differs by formatting from one of the
 * contact's
 * is not added twice; a row number the book does not have is put first, so the row's own number is always callable.
 */
fun personTarget(
    row: RecentsRow,
    contacts: List<WireContact>?,
): PersonTarget {
    val contact = row.contactId?.let { id -> contacts?.firstOrNull { it.id == id } }
    if (contact == null) {
        return PersonTarget(
            name = row.name,
            numbers = persistentListOf(TargetNumber(row.numberRaw, row.label)),
            contactId = row.contactId,
        )
    }
    val book = contact.numbers.map { TargetNumber(it.raw, it.label) }
    val key = tail(row.numberRaw)
    val all = if (book.any { tail(it.raw) == key }) book else listOf(TargetNumber(row.numberRaw, row.label)) + book
    return PersonTarget(
        name = row.name ?: contact.displayName.ifBlank { null },
        numbers = all.toImmutableList(),
        contactId = contact.id,
    )
}

/** The sheet of a person from the book (the Contacts tab and the Favorites): every number the contact has. */
fun personTarget(contact: WireContact): PersonTarget =
    PersonTarget(
        name = contact.displayName.ifBlank { null },
        numbers = contact.numbers.map { TargetNumber(it.raw, it.label) }.toImmutableList(),
        contactId = contact.id,
    )

/** The last digits of a number: enough to tell "0532 123 45 67" from "+90 532 123 45 67" as one line. */
private fun tail(raw: String): String = raw.filter { it.isDigit() }.takeLast(TAIL_DIGITS)

private const val TAIL_DIGITS = 9

/**
 * Presents the details sheets over the page: the Contact / Number sheet (medium, then large by a drag), the Call
 * details
 * sheet and the QR sheet stacked above it. Each sheet gets its own model and scope; the model closes its scope when the
 * sheet's content leaves the composition. The sheets move with the design system's `sheetSettle` spring and close
 * by drag
 * past 12 % of the height, by a flick, by the scrim and by Back.
 */
class DetailsPresenter(
    private val overlays: OverlayController,
    private val host: DetailsHost,
    private val resources: () -> Resources,
    private val person: (PersonTarget, CoroutineScope) -> PersonSheetViewModel,
    private val call: (CallDetailsTarget, CoroutineScope) -> CallDetailsViewModel,
    private val scope: () -> CoroutineScope = { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) },
) {
    fun presentPerson(target: PersonTarget): SheetEntry {
        val strings = DetailsStrings(resources())
        val model = person(target, scope())
        val title = if (target.name != null) strings.sheetPerson else strings.sheetNumber
        val entry =
            SheetEntry(title = title, detents = PersonDetents, initialDetent = Detent.Medium) { _ ->
                PersonSheet(model, host, onOpenCall = ::presentCall, onShowQr = ::presentQr)
            }
        overlays.present(entry)
        return entry
    }

    fun presentCall(target: CallDetailsTarget): SheetEntry {
        val strings = DetailsStrings(resources())
        val model = call(target, scope())
        lateinit var entry: SheetEntry
        entry =
            SheetEntry(title = strings.sheetCall, detents = PersonDetents, initialDetent = Detent.Medium) { sheet ->
                CallDetailsSheet(model, sheet, host, onClose = { entry.sheet?.dismiss() })
            }
        overlays.present(entry)
        return entry
    }

    fun presentQr(qr: PersonQr): SheetEntry {
        val strings = DetailsStrings(resources())
        val entry =
            SheetEntry(title = strings.sheetQr, detents = persistentListOf(Detent.Medium)) { _ ->
                QrBody(qr, host)
            }
        overlays.present(entry)
        return entry
    }
}

@Composable
private fun ColumnScope.QrBody(
    qr: PersonQr,
    host: DetailsHost,
) {
    val strings = DetailsStrings.current()
    PersonQrContent(qr, strings, onShare = { host.share(qr.payload) })
}
