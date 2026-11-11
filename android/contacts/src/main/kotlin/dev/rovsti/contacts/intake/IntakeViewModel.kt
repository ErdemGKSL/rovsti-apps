package dev.rovsti.contacts.intake

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.contacts.R
import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.people.PersonItem
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.Addition
import dev.rovsti.core.model.contacts.CardPhoto
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftAddRequest
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.DraftQueryRequest
import dev.rovsti.core.model.contacts.ImportPhotosRequest
import dev.rovsti.core.model.contacts.ImportPlanRequest
import dev.rovsti.core.model.contacts.IntakeFormat
import dev.rovsti.core.model.contacts.IntakeKind
import dev.rovsti.core.model.contacts.IntakeRequest
import dev.rovsti.core.model.contacts.PhotoPlanRequest
import dev.rovsti.core.model.contacts.ScanKind
import dev.rovsti.core.model.contacts.ScanRequest
import dev.rovsti.core.model.contacts.TextRequest
import dev.rovsti.core.model.qr.QrDecodeRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.QrOps
import dev.rovsti.design.text.UiText
import dev.rovsti.design.text.text
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.files.Documents
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.FilesRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Base64

/**
 * The import page (E4.10 C7, C28, C31, C33, C48, C49). It reads a vCard, CSV or pasted text (`contacts.intake`),
 * parses the cards with their pictures (`contacts.vcard_parse`), leaves out the ones the address book already has
 * (`contacts.import_plan`) and offers the rest in a preview. Add writes them ([ContactsRepository.import]) and puts
 * their pictures on them (`contacts.import_photos`); Undo moves the new contacts to the Trash. A single card can also
 * go into a contact that exists (`contacts.draft_add`, one save, Undo puts the old values back).
 *
 * Every decision is the Rust core's; this class orders the calls and maps the answers to one [IntakeUiState]. [scope]
 * outlives the page, so an import that was started finishes and its Undo works after the page is gone.
 */
class IntakeViewModel(
    private val source: IntakeSource,
    private val ops: ContactsOps,
    private val repo: ContactsRepository,
    private val book: BookRepository,
    private val files: FilesRepository,
    private val clipboard: ClipboardRepository,
    private val trash: ContactTrash,
    private val scope: CoroutineScope,
    /** A cache directory for the pictures while they are written to the provider. */
    private val stagingDir: File,
    /** `qr.decode` for "Scan QR Code". */
    private val qr: QrOps,
    /** The picked picture as gray values for [qr]. */
    private val luma: LumaReader,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ui = MutableStateFlow(IntakeUiState())
    private val outbox = Channel<IntakeEffect>(Channel.UNLIMITED)

    val state: StateFlow<IntakeUiState> = ui.asStateFlow()

    /** One-shot results. */
    val effects: Flow<IntakeEffect> = outbox.receiveAsFlow()

    // What the preview was made of; only the model touches these, one action at a time.
    private var cards: List<ContactDraft> = emptyList()
    private var photos: List<CardPhoto?> = emptyList()
    private var closed = false

    init {
        scope.launch { load() }
    }

    fun onAction(action: IntakeAction) {
        when (action) {
            IntakeAction.Add -> {
                if (ui.value.canAdd) scope.launch { add() }
            }

            IntakeAction.Cancel -> {
                close()
            }

            is IntakeAction.OpenCard -> {
                scope.launch { openCard(action.index) }
            }

            IntakeAction.CloseCard -> {
                ui.update { it.copy(preview = null) }
            }

            IntakeAction.AddToExisting -> {
                if (ui.value.canAddToExisting && ui.value.phase == IntakePhase.Preview) askExisting()
            }

            is IntakeAction.PickExisting -> {
                if (ui.value.phase == IntakePhase.Preview) scope.launch { addToExisting(action.id) }
            }

            is IntakeAction.SetRole -> {
                setRole(action.column, action.role)
            }

            IntakeAction.ConfirmMapping -> {
                val ready = ui.value
                if (ready.phase == IntakePhase.Mapping && ready.mapping?.canContinue == true) {
                    scope.launch { confirmMapping() }
                }
            }
        }
    }

    // ---- reading ----

    private suspend fun load() {
        when (source) {
            is IntakeSource.Scan -> {
                scan(source.uri)
                return
            }

            is IntakeSource.Csv -> {
                openCsv(source.uri)
                return
            }

            else -> {
                Unit
            }
        }
        val request = request() ?: return
        val answer = (ops.intake(request) as? Outcome.Ok)?.value
        if (answer == null) {
            finish(UNREADABLE)
            return
        }
        when (answer.kind) {
            IntakeKind.Cards -> {
                val parsed = parse(request, answer.format, answer.cards)
                plan(parsed.first, parsed.second)
            }

            IntakeKind.Single -> {
                val scan = answer.single
                val seed =
                    when (scan?.kind) {
                        ScanKind.Number -> DraftSeed(number = scan.value.orEmpty())
                        ScanKind.Email -> DraftSeed(email = scan.value.orEmpty())
                        else -> null
                    }
                if (seed != null) {
                    outbox.trySend(IntakeEffect.OpenEditor(seed))
                    close()
                } else {
                    finish(nothing(answer.notice))
                }
            }

            IntakeKind.NotContacts -> {
                finish(nothing(answer.notice))
            }

            IntakeKind.TooBig -> {
                finish(answer.notice?.let { UiText.Literal(it) } ?: TOO_BIG)
            }

            IntakeKind.Unreadable -> {
                finish(answer.notice?.let { UiText.Literal(it) } ?: UNREADABLE)
            }
        }
    }

    /** The words for a source that holds no contact: a paste is not "a file". */
    private fun nothing(notice: String?): UiText =
        if (source is IntakeSource.Paste) {
            NOTHING_TO_PASTE
        } else {
            notice?.let { UiText.Literal(it) }
                ?: NO_CONTACTS
        }

    /** The request for `contacts.intake`; null when the page is finished already (nothing to read). */
    private suspend fun request(): IntakeRequest? =
        when (source) {
            IntakeSource.Paste -> {
                val text = clipboard.primaryText()
                if (text.isNullOrBlank()) {
                    finish(NOTHING_TO_PASTE)
                    null
                } else {
                    IntakeRequest(source = "text", text = text)
                }
            }

            is IntakeSource.Scan, is IntakeSource.Csv -> {
                null
            }

            is IntakeSource.File -> {
                when (val read = files.readUri(Uri.parse(source.uri), Documents.MAX_CONTACTS_BYTES, text = false)) {
                    is FileResult.Ok -> {
                        val bytes = withContext(io) { read.path?.let(::File)?.let(::takeBytes) }
                        if (bytes == null) {
                            finish(UNREADABLE)
                            null
                        } else {
                            IntakeRequest(
                                source = "file",
                                mime = read.mime,
                                name = read.name.orEmpty(),
                                bytes = Base64.getEncoder().encodeToString(bytes),
                            )
                        }
                    }

                    FileResult.TooLarge -> {
                        finish(TOO_BIG)
                        null
                    }

                    FileResult.Cancelled -> {
                        close()
                        null
                    }

                    is FileResult.Failed -> {
                        finish(UNREADABLE)
                        null
                    }
                }
            }
        }

    /** A vCard is parsed again for the pictures (`intake` keeps none); other formats have none. */
    private suspend fun parse(
        request: IntakeRequest,
        format: IntakeFormat,
        fromIntake: List<ContactDraft>,
    ): Pair<List<ContactDraft>, List<CardPhoto?>> {
        if (format != IntakeFormat.Vcard) return fromIntake to emptyList()
        val text =
            withContext(worker) {
                val raw = Base64.getDecoder().decode(request.bytes)
                String(raw, Charsets.UTF_8).removePrefix("")
            }
        val parsed = (ops.vcardParse(TextRequest(text)) as? Outcome.Ok)?.value
        return if (parsed == null || parsed.contacts.isEmpty()) {
            fromIntake to emptyList()
        } else {
            parsed.contacts to parsed.photos
        }
    }

    private suspend fun plan(
        incoming: List<ContactDraft>,
        incomingPhotos: List<CardPhoto?>,
    ) {
        val existing =
            withTimeoutOrNull(BOOK_WAIT_MS) { book.book.first { it !is Book.Loading } }
                .let { (it as? Book.Ready)?.contacts.orEmpty() }
        val answer =
            (ops.importPlan(ImportPlanRequest(existing, incoming, incomingPhotos)) as? Outcome.Ok)?.value
        if (answer == null) {
            finish(UNREADABLE)
            return
        }
        if (answer.add.isEmpty()) {
            finish(
                if (incoming.size ==
                    1
                ) {
                    UiText.Res(R.string.contacts_already_in_contacts)
                } else {
                    UiText.Res(R.string.contacts_all_already, listOf(incoming.size))
                },
            )
            return
        }
        cards = answer.add
        photos = answer.photos
        val rows =
            withContext(worker) {
                answer.add.mapIndexed { index, draft ->
                    IntakeRow(index, draftTitle(draft), draftSecondary(draft), decode(answer.photos.getOrNull(index)))
                }
            }
        val n = rows.size
        ui.value =
            IntakeUiState(
                phase = IntakePhase.Preview,
                countLine = countLine(n, answer.duplicates),
                rows = rows.toImmutableList(),
                addLabel = UiText.Plural(R.plurals.contacts_add_contacts, n),
                canAddToExisting = incoming.size == 1,
            )
    }

    private fun countLine(
        added: Int,
        duplicates: Int,
    ): UiText {
        val head = UiText.Plural(R.plurals.contacts_new_contacts, added)
        return if (duplicates > 0) {
            UiText.Res(R.string.contacts_new_with_duplicates, listOf(head, duplicates))
        } else {
            head
        }
    }

    // ---- scanning a picture (C19) ----

    /**
     * Picture to cards: the gray values go to `qr.decode`, each code that it reads goes to `contacts.scan`. The first
     * code that holds something wins. A vCard opens the preview, a MECARD, a number or an address opens the editor, and
     * anything else is the alert "No contact in this code".
     */
    private suspend fun scan(uri: String) {
        val gray = luma.read(uri)
        if (gray == null) {
            finish(UiText.Res(R.string.contacts_cant_read_picture))
            return
        }
        val texts =
            (
                qr.decode(QrDecodeRequest(gray.width, gray.height, Base64.getEncoder().encodeToString(gray.bytes)))
                    as? Outcome.Ok
            )?.value?.texts.orEmpty()
        for (text in texts) {
            val found = (ops.scan(ScanRequest(text)) as? Outcome.Ok)?.value ?: continue
            when (found.kind) {
                ScanKind.Cards -> {
                    if (isMecard(text) && found.cards.size == 1) {
                        openEditor(seedOf(found.cards.first()))
                    } else {
                        val parsed = (ops.vcardParse(TextRequest(text)) as? Outcome.Ok)?.value
                        if (parsed != null && parsed.contacts.isNotEmpty()) {
                            plan(parsed.contacts, parsed.photos)
                        } else {
                            plan(found.cards, emptyList())
                        }
                    }
                    return
                }

                ScanKind.Number, ScanKind.Email -> {
                    val value = found.value.orEmpty()
                    val made = (ops.draftFromQuery(DraftQueryRequest(value)) as? Outcome.Ok)?.value?.draft
                    val plain =
                        if (found.kind == ScanKind.Number) DraftSeed(number = value) else DraftSeed(email = value)
                    openEditor(made?.let(::seedOf) ?: plain)
                    return
                }

                ScanKind.Nothing -> {
                    Unit
                }
            }
        }
        outbox.trySend(
            IntakeEffect.Alert(
                NO_CONTACT_IN_CODE,
                if (texts.isEmpty()) {
                    UiText.Res(
                        R.string.contacts_no_qr_in_picture,
                    )
                } else {
                    UiText.Res(R.string.contacts_qr_no_contact)
                },
            ),
        )
        close()
    }

    private fun isMecard(text: String): Boolean = text.trimStart().startsWith("MECARD:", ignoreCase = true)

    private fun openEditor(seed: DraftSeed) {
        outbox.trySend(IntakeEffect.OpenEditor(seed))
        close()
    }

    // ---- CSV (C39) ----

    @Volatile private var mapping: CsvMapping? = null
    private var csvJob: Job? = null
    private var csvCards: List<ContactDraft> = emptyList()

    /** Reads the document, lets the Rust reader count what the file holds as it is, and opens the mapping sheet. */
    private suspend fun openCsv(uri: String) {
        val text =
            when (val read = files.readUri(Uri.parse(uri), Documents.MAX_CONTACTS_BYTES, text = true)) {
                is FileResult.Ok -> {
                    read.text
                }

                FileResult.TooLarge -> {
                    finish(TOO_BIG)
                    return
                }

                FileResult.Cancelled -> {
                    close()
                    return
                }

                is FileResult.Failed -> {
                    null
                }
            }
        val map = text?.takeIf { it.isNotBlank() }?.let { CsvMapping(it) }
        if (map == null || map.isEmpty) {
            finish(UNREADABLE)
            return
        }
        mapping = map
        readCsv(map)
        ui.update { it.copy(phase = IntakePhase.Mapping) }
    }

    private fun setRole(
        column: Int,
        role: CsvRole,
    ) {
        val current = mapping ?: return
        if (ui.value.phase != IntakePhase.Mapping) return
        if (current.columns.getOrNull(column) == null) return
        val next = current.with(column, role)
        mapping = next
        // A newer choice replaces an older read that is still running; the sheet shows the columns at once.
        csvJob?.cancel()
        csvJob = scope.launch { readCsv(next) }
    }

    /** `contacts.csv_import` for the mapping as it stands; the sheet follows the answer. */
    private suspend fun readCsv(map: CsvMapping) {
        val parsed = (ops.csvImport(TextRequest(map.rewritten())) as? Outcome.Ok)?.value
        // A read that was replaced while the core was busy must not overwrite the newer answer, whichever thread
        // finishes last (the cancel above only lands at a suspension point, and the core call may not have one).
        if (mapping !== map) return
        csvCards = parsed?.contacts.orEmpty()
        val n = csvCards.size
        val skipped = parsed?.skipped ?: 0
        val found: UiText =
            when {
                parsed == null -> UiText.Res(R.string.contacts_cant_read_file)
                n == 0 -> UiText.Res(R.string.contacts_no_contacts_columns)
                else -> UiText.Plural(R.plurals.contacts_csv_found, n)
            }
        val line =
            if (n > 0 && skipped > 0) {
                UiText.Res(
                    R.string.contacts_csv_line,
                    listOf(found, UiText.Plural(R.plurals.contacts_csv_skipped, skipped)),
                )
            } else {
                found
            }
        ui.update {
            if (mapping !== map) it else it.copy(mapping = CsvMappingUi(map.columns, line, n > 0))
        }
    }

    private suspend fun confirmMapping() {
        csvJob?.join()
        val found = csvCards
        if (found.isEmpty()) return
        plan(found, emptyList())
    }

    // ---- the read-only card ----

    private suspend fun openCard(index: Int) {
        val draft = cards.getOrNull(index) ?: return
        val row = ui.value.rows.getOrNull(index) ?: return
        val preview = withContext(worker) { previewOf(index, draft, row.photo) }
        ui.update { it.copy(preview = preview) }
    }

    private fun previewOf(
        index: Int,
        draft: ContactDraft,
        photo: ImageBitmap?,
    ): CardPreview = CardPreview(index, draftTitle(draft), draftSubtitle(draft), photo, previewSectionsOf(draft))

    // ---- adding ----

    private suspend fun add() {
        ui.update { it.copy(phase = IntakePhase.Importing) }
        val drafts = cards
        val saved = withContext(io) { repo.import(drafts) }
        val result = (saved as? ContactResult.Ok)?.value
        if (result == null || result.ids.isEmpty()) {
            ui.update { it.copy(phase = IntakePhase.Preview) }
            outbox.trySend(IntakeEffect.Toast(UiText.Res(R.string.contacts_cant_add_contacts)))
            return
        }
        withContext(io) { putPhotos(result.ids) }
        val ids = result.ids.filter { it > 0 }
        val n = result.added
        outbox.trySend(
            IntakeEffect.Undo(UiText.Plural(R.plurals.contacts_added_contacts, n)) {
                val moved = ids.count { trash.delete(it) is dev.rovsti.contacts.trash.MoveResult.Done }
                UiText.Plural(R.plurals.contacts_moved_to_trash, moved)
            },
        )
        close()
    }

    /** Puts the card pictures on the contacts just added (`import_photos` decides which and how many). */
    private suspend fun putPhotos(ids: List<Long>) {
        if (photos.none { it != null }) return
        val targets = (ops.importPhotos(ImportPhotosRequest(ids, photos)) as? Outcome.Ok)?.value?.targets.orEmpty()
        stagingDir.mkdirs()
        for (target in targets) {
            val file = File(stagingDir, "import-photo-${target.contactId}.img")
            try {
                file.writeBytes(Base64.getDecoder().decode(target.photo.base64))
                val size = repo.photoSize(file.absolutePath)
                val plan =
                    (ops.photoPlan(PhotoPlanRequest(size.width, size.height)) as? Outcome.Ok)?.value?.plan ?: continue
                repo.setPhoto(target.contactId, file.absolutePath, plan)
            } catch (_: IllegalArgumentException) {
                // A picture that cannot be read leaves the contact with its initials.
            } finally {
                file.delete()
            }
        }
    }

    // ---- add to an existing contact ----

    private fun askExisting() {
        val ready = book.book.value as? Book.Ready
        val people =
            ready
                ?.contacts
                .orEmpty()
                .map { PersonItem(it.id, it.displayName, it.numbers.firstOrNull()?.raw, it.photoUri) }
                .toImmutableList()
        if (people.isEmpty()) {
            outbox.trySend(IntakeEffect.Toast(UiText.Res(R.string.contacts_no_contact_to_add_to)))
        } else {
            outbox.trySend(IntakeEffect.ChooseExisting(people))
        }
    }

    private suspend fun addToExisting(id: Long) {
        val card = cards.singleOrNull() ?: return
        val target = (book.book.value as? Book.Ready)?.drafts?.firstOrNull { it.id == id } ?: return
        val addition =
            Addition(
                numbers = card.numbers.map { it.value }.filter { it.isNotBlank() },
                emails = card.emails.map { it.value }.filter { it.isNotBlank() },
            )
        val answer = (ops.draftAdd(DraftAddRequest(target, addition)) as? Outcome.Ok)?.value
        val merged = answer?.draft
        if (answer == null || answer.refused || merged == null) {
            outbox.trySend(
                IntakeEffect.Toast(
                    answer?.notice?.let { UiText.Literal(it) } ?: UiText.Res(R.string.contacts_cant_add_to_contact),
                ),
            )
            return
        }
        if (repo.save(merged) is ContactResult.Failed) {
            outbox.trySend(IntakeEffect.Toast(UiText.Res(R.string.contacts_cant_save_contact)))
            return
        }
        outbox.trySend(
            IntakeEffect.Undo(UiText.Literal(answer.notice)) {
                if (repo.save(
                        target,
                    ) is ContactResult.Ok
                ) {
                    UiText.Res(
                        R.string.contacts_put_back,
                    )
                } else {
                    UiText.Res(R.string.contacts_cant_undo_now)
                }
            },
        )
        close()
    }

    // ---- ending ----

    private fun finish(toast: UiText) {
        outbox.trySend(IntakeEffect.Toast(toast))
        close()
    }

    private fun close() {
        if (closed) return
        closed = true
        ui.update { it.copy(phase = IntakePhase.Done) }
        outbox.trySend(IntakeEffect.Close)
    }

    companion object {
        val NOTHING_TO_PASTE: UiText = UiText.Res(R.string.contacts_nothing_to_paste)
        val NO_CONTACT_IN_CODE: UiText = UiText.Res(R.string.contacts_no_contact_in_code)
        private val NO_CONTACTS: UiText = UiText.Res(R.string.contacts_file_no_contacts)
        private val TOO_BIG: UiText = UiText.Res(R.string.contacts_file_too_big)
        private val UNREADABLE: UiText = UiText.Res(R.string.contacts_cant_read_file)
        private const val BOOK_WAIT_MS = 3_000L
        private const val MAX_PHOTO_SIDE = 256
    }

    private fun decode(photo: CardPhoto?): ImageBitmap? {
        photo ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(photo.base64)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_PHOTO_SIDE &&
                bounds.outHeight / (sample * 2) >= MAX_PHOTO_SIDE
            ) {
                sample *= 2
            }
            BitmapFactory
                .decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** What the editor starts from for [draft]: its name and first number and address. */
internal fun seedOf(draft: ContactDraft) =
    DraftSeed(
        name = listOf(draft.given, draft.family).filter { it.isNotBlank() }.joinToString(" "),
        number =
            draft.numbers
                .firstOrNull { it.value.isNotBlank() }
                ?.value
                .orEmpty(),
        email =
            draft.emails
                .firstOrNull { it.value.isNotBlank() }
                ?.value
                .orEmpty(),
    )

/** The name a card is listed under; a card without one is listed by what it has. */
internal fun draftTitle(draft: ContactDraft): String =
    listOf(draft.given, draft.family)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifEmpty {
            draft.org.ifBlank {
                draft.nickname.ifBlank {
                    draft.numbers.firstOrNull { it.value.isNotBlank() }?.value
                        ?: draft.emails.firstOrNull { it.value.isNotBlank() }?.value
                        ?: ""
                }
            }
        }

/** The line under the name: the first number, else the first address, else the company when the name is a person. */
internal fun draftSecondary(draft: ContactDraft): String? {
    val title = draftTitle(draft)
    return listOfNotNull(
        draft.numbers.firstOrNull { it.value.isNotBlank() }?.value,
        draft.emails.firstOrNull { it.value.isNotBlank() }?.value,
        draft.org.takeIf { it.isNotBlank() },
    ).firstOrNull { it != title }
}

/** The bytes of a staged copy of the picked file; the copy is deleted. Null when it cannot be read. */
private fun takeBytes(file: File): ByteArray? = runCatching { file.readBytes() }.getOrNull().also { file.delete() }

/** The read-only sections of one card (Phone, Email, Address, Website, Dates, Notes); empty ones are left out. */
internal fun previewSectionsOf(draft: ContactDraft): ImmutableList<PreviewSection> {
    val sections = ArrayList<PreviewSection>()

    fun fields(
        title: UiText,
        list: List<DraftField>,
    ) {
        val lines =
            list
                .filter {
                    it.value.isNotBlank()
                }.map { PreviewLine(UiText.Literal(it.label ?: it.kind.name), it.value) }
        if (lines.isNotEmpty()) sections += PreviewSection(title, lines.toImmutableList())
    }
    fields(UiText.Res(R.string.contacts_phone), draft.numbers)
    fields(UiText.Res(R.string.contacts_email), draft.emails)
    fields(UiText.Res(R.string.contacts_address), draft.addresses)
    if (draft.websites.isNotEmpty()) {
        sections +=
            PreviewSection(
                UiText.Res(R.string.contacts_website),
                draft.websites
                    .map {
                        PreviewLine(UiText.Res(R.string.contacts_website), it)
                    }.toImmutableList(),
            )
    }
    if (draft.events.isNotEmpty()) {
        sections +=
            PreviewSection(
                UiText.Res(R.string.contacts_dates),
                draft.events.map { PreviewLine(UiText.Literal(it.label ?: it.kind.name), it.date) }.toImmutableList(),
            )
    }
    if (draft.note.isNotBlank()) {
        sections +=
            PreviewSection(
                UiText.Res(R.string.contacts_notes),
                persistentListOf(PreviewLine(UiText.Res(R.string.contacts_notes), draft.note)),
            )
    }
    return sections.toImmutableList()
}

/** The title and the company of a card on one line ("Engineer, Acme"); null when it has neither. */
internal fun draftSubtitle(draft: ContactDraft): String? =
    listOf(draft.title, draft.org).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null }
