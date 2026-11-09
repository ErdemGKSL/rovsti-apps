package dev.rovsti.contacts.card

import dev.rovsti.contacts.data.Book
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.pinContactToHome
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.AccountLabelRequest
import dev.rovsti.core.model.contacts.AgeRequest
import dev.rovsti.core.model.contacts.CallTarget
import dev.rovsti.core.model.contacts.CardAnswer
import dev.rovsti.core.model.contacts.CardRequest
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.ContactHistoryRequest
import dev.rovsti.core.model.contacts.DraftEdit
import dev.rovsti.core.model.contacts.DraftEditRequest
import dev.rovsti.core.model.contacts.DraftEvent
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.DraftRelation
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.core.model.contacts.EventLineRequest
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.FieldList
import dev.rovsti.core.model.contacts.FieldMenuRequest
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.LocalYmdRequest
import dev.rovsti.core.model.contacts.MapsUrlRequest
import dev.rovsti.core.model.contacts.NumberKeyRequest
import dev.rovsti.core.model.contacts.RelationAction
import dev.rovsti.core.model.contacts.RelationKind
import dev.rovsti.core.model.contacts.RelationTapRequest
import dev.rovsti.core.model.contacts.TextFileAnswer
import dev.rovsti.core.model.contacts.TitleDetail
import dev.rovsti.core.model.contacts.VcardWriteRequest
import dev.rovsti.core.model.contacts.WaCardAnswer
import dev.rovsti.core.model.contacts.WaCardRequest
import dev.rovsti.core.model.contacts.WaLookup
import dev.rovsti.core.model.contacts.WaPresence
import dev.rovsti.core.model.contacts.WaProfileInput
import dev.rovsti.core.model.contacts.WaProfileLine
import dev.rovsti.core.model.contacts.WaProfileLinesRequest
import dev.rovsti.core.model.contacts.WebUrlRequest
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.HistoryRequest
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.TimeFormat
import dev.rovsti.core.model.qr.QrEncodeRequest
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.model.shared.ContactQrRequest
import dev.rovsti.core.model.shared.CopiedNoticeRequest
import dev.rovsti.core.model.shared.CountryLineRequest
import dev.rovsti.core.model.shared.FileNameRequest
import dev.rovsti.core.model.shared.FormsRequest
import dev.rovsti.core.model.shared.InferHomeCcRequest
import dev.rovsti.core.model.shared.QrNumber
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.ContactsWaOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.QrOps
import dev.rovsti.core.ops.SharedOps
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.contacts.ContactResult
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.ShortcutsRepository
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.files.FilesRepository
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone

/**
 * One contact's page (E4.3). The contact is read from the provider ([ContactsRepository.detail]) and the book (photo,
 * lookup key); the rows, labels, dates, ages, the maps and web addresses, the default markers and the field menus are
 * answers of the Rust ops (`contacts.card`, `contacts.event_line`, `contacts.age`, `contacts.maps_url`,
 * `contacts.web_url`, `contacts.field_menu`, `format.copy_forms`, `contacts.relation_tap`, `contacts.account_label`).
 * This class orders the calls and maps their answers to one [ContactCardUiState].
 *
 * Recent calls (C21/C37) are read only while READ_CALL_LOG is granted; until then the section is a permission row.
 * WhatsApp (AD-CT5, AD-CT6): once per open card, `contacts.wa_card` names the JIDs to look up and [CardWaPort.lookup]
 * asks the gateway; the chips, the presence line and the profile lines follow its answers. Nothing leaves the phone when
 * the instance is not linked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactCardViewModel(
    private val id: Long,
    private val repo: ContactsRepository,
    private val book: BookRepository,
    private val ops: ContactsOps,
    private val shared: SharedOps,
    private val phoneOps: PhoneOps,
    private val waOps: ContactsWaOps,
    private val wa: CardWaPort,
    private val callLog: CallLogRepository,
    private val callLogPermission: Flow<PermissionState>,
    private val store: ContactsStore,
    private val clipboard: ClipboardRepository,
    private val files: FilesRepository,
    private val qr: QrOps,
    private val shortcuts: ShortcutsRepository,
    /** A cache directory the vCard is written to before it is staged for sharing. */
    private val stagingDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val locale: (Long) -> LocaleTime = ::defaultLocale,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val outbox = Channel<ContactCardEffect>(Channel.UNLIMITED)

    /** One-shot results. */
    val effects: Flow<ContactCardEffect> = outbox.receiveAsFlow()

    private class Base(
        val draft: ContactDraft,
        val wire: WireContact,
        val card: CardAnswer,
        val homeCc: String?,
        val myCard: Boolean,
    ) {
        val numbers: List<String> get() = card.numbers.map { it.raw }
    }

    private class WaInputs(
        val linked: Boolean = false,
        val chatUsers: List<String> = emptyList(),
        val lookup: WaLookup? = null,
        val presence: WaPresence? = null,
        val profile: WaProfileInput? = null,
    )

    private class WaView(
        val answer: WaCardAnswer? = null,
        val lines: List<WaProfileLine> = emptyList(),
    )

    private class Parts(
        val base: Result<Base?>?,
        val wa: WaView,
        val groups: List<Group>,
        val recent: CardRecent,
    )

    private val waInputs = MutableStateFlow(WaInputs())
    private val lookupMutex = Any()
    private var lookupAsked = false
    private var homeCcCache: String? = null
    private var homeCcFor: Int = -1

    /** null while loading; a [Gone] marker is the second state. */
    private val base: StateFlow<Result<Base?>?> =
        combine(repo.detail(id), book.book, store.prefs) { draft, b, prefs -> Triple(draft, b, prefs) }
            .mapLatest { (draft, b, prefs) ->
                when {
                    b is Book.Loading -> null
                    draft == null -> Result.success(null)
                    b !is Book.Ready -> null
                    else -> Result.success(baseOf(draft, b, prefs.nameFormat, prefs.myCard))
                }
            }.flowOn(worker)
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val numbers: Flow<List<String>> =
        base.map { it?.getOrNull()?.numbers.orEmpty() }.distinctUntilChanged()

    private val waView: Flow<WaView> =
        combine(waInputs, base) { inputs, b -> inputs to b?.getOrNull() }
            .mapLatest { (inputs, b) -> waViewOf(inputs, b) }
            .flowOn(worker)

    private val recent: Flow<CardRecent> =
        combine(callLogPermission, numbers) { permission, nums -> permission to nums }
            .mapLatest { (permission, nums) -> recentOf(permission, nums) }
            .flowOn(worker)

    /** The page. */
    val state: StateFlow<ContactCardUiState> =
        combine(base, waView, repo.groups(), recent) { b, w, groups, r -> Parts(b, w, groups, r) }
            .mapLatest { build(it.base, it.wa, it.groups, it.recent) }
            .flowOn(worker)
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ContactCardUiState())

    init {
        scope.launch { store.load() }
        scope.launch { driveLookup() }
        scope.launch { drivePresence() }
        scope.launch {
            // A contact that disappears (deleted elsewhere) closes the page.
            base.first { it != null && it.getOrNull() == null }
            outbox.trySend(ContactCardEffect.Close)
        }
    }

    fun onAction(action: ContactCardAction) {
        when (action) {
            ContactCardAction.Call -> {
                scope.launch { call() }
            }

            ContactCardAction.Message -> {
                scope.launch { primaryNumber()?.let { send(ContactCardEffect.Sms(it)) } }
            }

            ContactCardAction.WhatsApp -> {
                scope.launch { whatsapp() }
            }

            ContactCardAction.Email -> {
                scope.launch { firstEmail()?.let { send(ContactCardEffect.Email(it)) } }
            }

            ContactCardAction.Delete -> {
                send(ContactCardEffect.AskDelete(id))
            }

            ContactCardAction.Edit -> {
                send(ContactCardEffect.OpenEditor(id))
            }

            ContactCardAction.ToggleStar -> {
                scope.launch { toggleStar() }
            }

            ContactCardAction.OpenInContacts -> {
                send(
                    ContactCardEffect.OpenAndroidContact(
                        id,
                        base.value
                            ?.getOrNull()
                            ?.wire
                            ?.lookupKey,
                    ),
                )
            }

            is ContactCardAction.Tap -> {
                scope.launch { run(action.op) }
            }

            is ContactCardAction.Field -> {
                scope.launch { run(action.op) }
            }

            is ContactCardAction.Relation -> {
                scope.launch { relation(action.name) }
            }

            is ContactCardAction.WaChip -> {
                scope.launch { chat(action.jid) }
            }

            is ContactCardAction.Wish -> {
                send(ContactCardEffect.Sms(action.number, action.text))
            }

            ContactCardAction.ShareText -> {
                base()?.let { send(ContactCardEffect.Share(it.card.shareText)) }
            }

            ContactCardAction.ShareFile -> {
                scope.launch { shareFile() }
            }

            ContactCardAction.ShowQr -> {
                scope.launch { showQr() }
            }

            ContactCardAction.ExportVcf -> {
                scope.launch { vcard()?.let { send(ContactCardEffect.ExportVcf(it.fileName, it.text)) } }
            }

            ContactCardAction.CopyContact -> {
                base()?.let { copyText(it.card.shareText, "Contact copied") }
            }

            ContactCardAction.CopyVcard -> {
                scope.launch { vcard()?.let { copyText(it.text, "vCard copied") } }
            }

            ContactCardAction.AddToHome -> {
                scope.launch { pinToHome() }
            }
        }
    }

    private fun send(effect: ContactCardEffect) {
        outbox.trySend(effect)
    }

    // ---- actions

    private suspend fun call() {
        val b = base.value?.getOrNull() ?: return
        when (b.card.callTarget) {
            CallTarget.Direct -> {
                b.card.callIndex
                    ?.let { b.card.numbers.getOrNull(it) }
                    ?.let { send(ContactCardEffect.Dial(it.raw)) }
            }

            CallTarget.Choose -> {
                send(
                    ContactCardEffect.PickNumber(
                        b.card.numbers
                            .map { CallChoice(it.raw, it.display, it.label) }
                            .toImmutableList(),
                    ),
                )
            }

            CallTarget.Nothing -> {
                Unit
            }
        }
    }

    private fun primaryNumber(): String? {
        val b = base.value?.getOrNull() ?: return null
        return (b.card.numbers.firstOrNull { it.default } ?: b.card.numbers.firstOrNull())?.raw
    }

    private fun firstEmail(): String? =
        base.value
            ?.getOrNull()
            ?.draft
            ?.let(::emailOf)

    private suspend fun whatsapp() {
        val jid = waView.first().answer?.jid ?: return
        chat(jid)
    }

    private fun chat(jid: String) {
        send(
            ContactCardEffect.WhatsApp(
                jid,
                base.value
                    ?.getOrNull()
                    ?.card
                    ?.name
                    .orEmpty(),
            ),
        )
    }

    private suspend fun toggleStar() {
        val b = base.value?.getOrNull() ?: return
        if (repo.setStarred(id, !b.draft.starred) is ContactResult.Failed) {
            send(ContactCardEffect.Toast("Can't change the favorite."))
        }
    }

    private fun base(): Base? = base.value?.getOrNull()

    private fun copyText(
        text: String,
        notice: String,
    ) {
        if (clipboard.copy(text)) send(ContactCardEffect.Toast(notice))
    }

    /** `contacts.vcard_write` for this one contact; the file name is the op's own (`<Name>.vcf`). */
    private suspend fun vcard(): TextFileAnswer? {
        val b = base() ?: return null
        val now = clock()
        val day =
            ok(ops.localYmd(LocalYmdRequest(now, TimeZone.getDefault().getOffset(now) / MS_PER_S))) ?: return null
        val file = ok(ops.vcardWrite(VcardWriteRequest(listOf(b.draft), day)))
        if (file == null) send(ContactCardEffect.Toast("Can't make the file."))
        return file
    }

    /** Writes the vCard under its own name, stages it for the share provider and hands the URI to the entry. */
    private suspend fun shareFile() {
        val file = vcard() ?: return
        val uri =
            withContext(worker) {
                val source = File(stagingDir, "vcard-source.tmp")
                source.writeText(file.text)
                files.stageForShare(source.absolutePath, file.fileName)
            }
        if (uri == null) {
            send(ContactCardEffect.Toast("Can't share the file."))
        } else {
            send(ContactCardEffect.ShareFile(uri, file.fileName))
        }
    }

    /** The QR sheet: `format.contact_qr` fits the vCard into a code, `qr.encode` makes the modules. */
    private suspend fun showQr() {
        val b = base() ?: return
        val name = b.card.name
        val numbers =
            b.draft.numbers
                .filter { it.value.isNotBlank() }
                .map { QrNumber(it.value, it.label) }
        val payload = ok(shared.contactQr(ContactQrRequest(name, numbers, b.homeCc)))?.payload
        val matrix = payload?.let { ok(qr.encode(QrEncodeRequest(it))) }?.let(QrMatrix::fromAnswer)
        if (matrix == null) {
            send(ContactCardEffect.Toast("This contact is too big for a QR code."))
            return
        }
        val fileName = ok(shared.fileName(FileNameRequest(name)))?.qrFileName ?: "contact-qr.png"
        send(ContactCardEffect.ShowQr(QrSheetModel(name, matrix, fileName, files, stagingDir)))
    }

    private suspend fun pinToHome() {
        val b = base() ?: return
        val toast = pinContactToHome(ops, shortcuts, id, b.wire.lookupKey, b.card.name) ?: return
        send(ContactCardEffect.Toast(toast))
    }

    private suspend fun relation(name: String) {
        val all = (book.book.value as? Book.Ready)?.contacts.orEmpty()
        val answer = ok(ops.relationTap(RelationTapRequest(name, all))) ?: return
        when (answer.action) {
            RelationAction.Open -> answer.contactId?.let { send(ContactCardEffect.OpenCard(it)) }
            RelationAction.Search -> send(ContactCardEffect.SearchFor(answer.query ?: name))
        }
    }

    private suspend fun run(op: FieldOp) {
        when (op) {
            is FieldOp.Copy -> {
                if (clipboard.copy(op.text)) send(ContactCardEffect.Toast(op.notice))
            }

            is FieldOp.ShareText -> {
                send(ContactCardEffect.Share(op.text))
            }

            is FieldOp.Dial -> {
                send(ContactCardEffect.Dial(op.number))
            }

            is FieldOp.Sms -> {
                send(ContactCardEffect.Sms(op.number))
            }

            is FieldOp.Email -> {
                send(ContactCardEffect.Email(op.address))
            }

            is FieldOp.OpenUrl -> {
                send(ContactCardEffect.OpenUrl(op.url))
            }

            is FieldOp.SetDefault -> {
                setDefault(op)
            }
        }
    }

    private suspend fun setDefault(op: FieldOp.SetDefault) {
        val draft = base.value?.getOrNull()?.draft ?: return
        val list = if (op.emails) draft.emails else draft.numbers
        val index =
            if (op.emails) {
                list.indexOfFirst {
                    it.value.equals(op.value, ignoreCase = true)
                }
            } else {
                indexOfNumber(list, op.value)
            }
        if (index < 0) {
            send(ContactCardEffect.Toast("That entry changed. Open the card again."))
            return
        }
        val edited =
            ok(
                ops.draftEdit(
                    DraftEditRequest(
                        draft = draft,
                        edit = DraftEdit.SetPrimary,
                        list = if (op.emails) FieldList.Emails else FieldList.Numbers,
                        index = if (op.clear) null else index,
                    ),
                ),
            )?.draft ?: return
        when (repo.save(edited)) {
            is ContactResult.Ok -> {
                send(
                    ContactCardEffect.Toast(
                        when {
                            op.clear -> "Default cleared"
                            op.emails -> "Default email set"
                            else -> "Default number set"
                        },
                    ),
                )
            }

            is ContactResult.Failed -> {
                send(ContactCardEffect.Toast("Can't change the default."))
            }
        }
    }

    private suspend fun indexOfNumber(
        list: List<DraftField>,
        raw: String,
    ): Int {
        val key = numberKey(raw)
        return list.indexOfFirst { numberKey(it.value) == key }
    }

    private suspend fun numberKey(raw: String): String = ok(ops.numberKey(NumberKeyRequest(raw)))?.key ?: raw.trim()

    // ---- the contact

    private suspend fun baseOf(
        draft: ContactDraft,
        b: Book.Ready,
        format: dev.rovsti.core.model.search.NameFormat,
        myCard: String?,
    ): Base? {
        val wire = b.contacts.firstOrNull { it.id == id } ?: return null
        val card = ok(ops.card(CardRequest(wire, format))) ?: return null
        if (homeCcFor != b.contacts.size) {
            val numbers = b.contacts.flatMap { c -> c.numbers.map { it.raw } }
            homeCcCache = ok(shared.inferHomeCc(InferHomeCcRequest(numbers)))?.homeCc
            homeCcFor = b.contacts.size
        }
        return Base(draft, wire, card, homeCcCache, myCard != null && myCard == wire.lookupKey)
    }

    // ---- WhatsApp

    private suspend fun driveLookup() {
        wa.linked
            .flatMapLatest { linked ->
                if (linked) {
                    combine(wa.chatUsers(), numbers) { users, nums -> users to nums }
                } else {
                    waInputs.value = WaInputs()
                    flowOf<Pair<List<String>, List<String>>>()
                }
            }.collect { (users, nums) ->
                waInputs.update { WaInputs(true, users, it.lookup, it.presence, it.profile) }
                if (nums.isEmpty() || !firstAsk()) return@collect
                val request = ok(waOps.card(WaCardRequest(id, nums, users, nowMs = clock()))) ?: return@collect
                if (request.lookupRequest.isEmpty()) {
                    synchronized(lookupMutex) { lookupAsked = false }
                    return@collect
                }
                waInputs.update {
                    WaInputs(
                        it.linked,
                        it.chatUsers,
                        WaLookup(WaLookup.CHECKING),
                        it.presence,
                        it.profile,
                    )
                }
                val result = wa.lookup(request.lookupRequest)
                waInputs.update { WaInputs(it.linked, it.chatUsers, result, it.presence, it.profile) }
            }
    }

    /** True for the one caller that may ask the gateway in this open card. */
    private fun firstAsk(): Boolean =
        synchronized(lookupMutex) {
            if (lookupAsked) return false
            lookupAsked = true
            true
        }

    private suspend fun drivePresence() {
        waView
            .map { it.answer?.jid }
            .distinctUntilChanged()
            .collectLatestJid()
    }

    private suspend fun Flow<String?>.collectLatestJid() {
        collectLatest { jid ->
            if (jid == null) return@collectLatest
            coroutineScope {
                launch {
                    wa.presence(jid).collect { p ->
                        waInputs.update { WaInputs(it.linked, it.chatUsers, it.lookup, p, it.profile) }
                    }
                }
                wa.subscribePresence(jid)
                val profile = wa.profile(jid)
                waInputs.update { WaInputs(it.linked, it.chatUsers, it.lookup, it.presence, profile) }
                awaitCancellation()
            }
        }
    }

    private suspend fun waViewOf(
        inputs: WaInputs,
        b: Base?,
    ): WaView {
        if (!inputs.linked || b == null || b.numbers.isEmpty()) return WaView()
        val request =
            WaCardRequest(
                id = id,
                numbers = b.numbers,
                chatUsers = inputs.chatUsers,
                lookup = inputs.lookup,
                presence = inputs.presence,
                nowMs = clock(),
                offsetS = locale(clock()).tzOffsetS,
            )
        val answer = ok(waOps.card(request)) ?: return WaView()
        val profile = inputs.profile
        val lines =
            if (profile != null && answer.jid != null) {
                ok(waOps.profileLines(WaProfileLinesRequest(profile, b.card.name)))?.lines.orEmpty()
            } else {
                emptyList()
            }
        return WaView(answer, lines)
    }

    // ---- recent calls

    private suspend fun recentOf(
        permission: PermissionState,
        numbers: List<String>,
    ): CardRecent {
        val now = clock()
        val time = locale(now)
        if (permission != PermissionState.Granted) {
            val text =
                ok(ops.history(ContactHistoryRequest(emptyList(), now, time)))?.permission ?: return CardRecent.Hidden
            return CardRecent.NeedsPermission(text)
        }
        if (numbers.isEmpty()) return CardRecent.Hidden
        val entries = callLog.entries().first()
        val lines =
            ok(
                phoneOps.history(HistoryRequest(entries = entries, numbers = numbers, limit = PULL_LIMIT)),
            )?.lines.orEmpty()
        if (lines.isEmpty()) return CardRecent.Hidden
        val answer = ok(ops.history(ContactHistoryRequest(lines, now, time))) ?: return CardRecent.Hidden
        return CardRecent.Calls(answer.summary, answer.rows.map { TitleDetail(it.title, it.detail) }.toImmutableList())
    }

    // ---- building the page

    private suspend fun build(
        result: Result<Base?>?,
        w: WaView,
        groups: List<Group>,
        recent: CardRecent,
    ): ContactCardUiState {
        val b = result?.getOrNull()
        if (result == null) return ContactCardUiState(CardPhase.Loading)
        if (b == null) return ContactCardUiState(CardPhase.Gone)
        val draft = b.draft
        val card = b.card
        val now = clock()
        val jid = w.answer?.jid
        val chips = w.answer?.chips.orEmpty()
        val jids = w.answer?.numberJids.orEmpty()
        val sections = mutableListOf<CardSection>()

        fun add(
            kind: RowKind,
            title: String?,
            rows: List<CardRow>,
        ) {
            if (rows.isNotEmpty()) sections += CardSection(kind, title, rows.toImmutableList())
        }

        add(RowKind.Number, "Phone", numberRows(b, chips, jids))
        add(RowKind.Email, "Email", emailRows(draft))
        add(RowKind.Date, "Dates", dateRows(b, now))
        add(RowKind.Address, "Address", addressRows(draft))
        add(RowKind.Website, "Websites", websiteRows(draft))
        add(RowKind.Relation, "Related people", relationRows(draft))
        add(
            RowKind.Group,
            "Groups",
            draft.groups.mapNotNull { gid -> groups.firstOrNull { it.id == gid } }.map {
                CardRow(key = "group-${it.id}", kind = RowKind.Group, title = it.title)
            },
        )
        val extras = mutableListOf<CardRow>()
        if (draft.nickname.isNotBlank()) {
            extras +=
                textRow("nickname", RowKind.Nickname, "Nickname", draft.nickname.trim(), "Nickname copied")
        }
        phonetic(draft)?.let {
            extras +=
                textRow("phonetic", RowKind.Phonetic, "Phonetic name", it, "Phonetic name copied")
        }
        if (draft.note.isNotBlank()) {
            extras +=
                textRow("note", RowKind.Note, "Note", draft.note.trim(), "Note copied", "Copy Note")
        }
        add(RowKind.Note, null, extras)
        if (draft.account.isNotBlank()) {
            val label = ok(ops.accountLabel(AccountLabelRequest(draft.account)))?.label ?: draft.account
            add(RowKind.Account, null, listOf(CardRow("account", RowKind.Account, label, "Account")))
        }
        val waRows = mutableListOf<CardRow>()
        if (jid == null) w.answer?.statusLine?.let { waRows += CardRow("wa-status", RowKind.Whatsapp, it, "Status") }
        w.lines.forEachIndexed { i, line ->
            waRows += textRow("wa-$i", RowKind.Whatsapp, line.label, line.value, "${line.label} copied")
        }
        add(RowKind.Whatsapp, "WhatsApp", waRows)

        val email = emailOf(draft)
        val hasNumber = card.numbers.isNotEmpty()
        val capsules =
            buildList {
                add(CardCapsule(CapsuleKind.Call, "Call", card.callTarget != CallTarget.Nothing))
                add(CardCapsule(CapsuleKind.Message, "Message", hasNumber))
                if (jid != null) add(CardCapsule(CapsuleKind.WhatsApp, "WhatsApp"))
                if (email != null) add(CardCapsule(CapsuleKind.Email, "Email"))
            }
        return ContactCardUiState(
            phase = CardPhase.Ready,
            header =
                CardHeader(
                    id = id,
                    name = card.name,
                    orgLine = orgLine(draft.title, draft.org),
                    photoUri = b.wire.photoUri,
                    presence = if (jid != null) w.answer?.statusLine?.replaceFirstChar { it.uppercase() } else null,
                    starred = draft.starred,
                    isMyCard = b.myCard,
                ),
            capsules = capsules.toImmutableList(),
            sections = sections.toImmutableList(),
            recent = recent,
            lookupKey = b.wire.lookupKey,
            waJid = jid,
            callChoices =
                if (card.callTarget == CallTarget.Choose) {
                    card.numbers.map { CallChoice(it.raw, it.display, it.label) }.toImmutableList()
                } else {
                    persistentListOf()
                },
        )
    }

    private suspend fun numberRows(
        b: Base,
        chips: List<Boolean>,
        jids: List<String?>,
    ): List<CardRow> {
        val count = b.card.numbers.size
        return b.card.numbers.mapIndexed { i, n ->
            val country = ok(shared.countryLine(CountryLineRequest(n.raw, b.homeCc)))?.line
            val label = if (country != null) "${n.label} · $country" else n.label
            val action = ok(ops.fieldMenu(FieldMenuRequest(n.default, count)))?.action
            val forms = ok(shared.copyForms(FormsRequest(n.raw, b.homeCc)))?.forms.orEmpty()
            val menu = mutableListOf<FieldMenuItem>()
            menu += FieldMenuItem("Call", FieldOp.Dial(n.raw))
            menu += FieldMenuItem("Message", FieldOp.Sms(n.raw))
            if (forms.size >= 2) {
                for (form in forms) {
                    val notice = ok(shared.copiedNotice(CopiedNoticeRequest(form.form)))?.notice ?: "Copied"
                    menu += FieldMenuItem("Copy ${form.label}", FieldOp.Copy(form.text, notice))
                }
            } else {
                menu += FieldMenuItem("Copy Number", FieldOp.Copy(n.raw, "Number copied"))
            }
            menu += FieldMenuItem("Share Number", FieldOp.ShareText(n.raw))
            if (action != null) menu += FieldMenuItem(action.label, FieldOp.SetDefault(false, n.raw, action.clears))
            CardRow(
                key = "number-$i",
                kind = RowKind.Number,
                title = n.display,
                label = label,
                isDefault = n.default && count > 1,
                waJid = if (chips.getOrNull(i) == true) jids.getOrNull(i) else null,
                tap = FieldOp.Dial(n.raw),
                menu = menu.toImmutableList(),
            )
        }
    }

    private suspend fun emailRows(draft: ContactDraft): List<CardRow> {
        val shown = draft.emails.filter { it.value.isNotBlank() }
        return shown.mapIndexed { i, email ->
            val value = email.value.trim()
            val action = ok(ops.fieldMenu(FieldMenuRequest(email.primary, shown.size)))?.action
            val menu =
                mutableListOf(
                    FieldMenuItem("Send Email", FieldOp.Email(value)),
                    FieldMenuItem("Copy Email", FieldOp.Copy(value, "Email copied")),
                )
            if (action != null) menu += FieldMenuItem(action.label, FieldOp.SetDefault(true, value, action.clears))
            CardRow(
                key = "email-$i",
                kind = RowKind.Email,
                title = value,
                label = fieldTitle(email.kind, email.label),
                isDefault = email.primary && shown.size > 1,
                tap = FieldOp.Email(value),
                menu = menu.toImmutableList(),
            )
        }
    }

    private suspend fun dateRows(
        b: Base,
        now: Long,
    ): List<CardRow> {
        val tz = locale(now).tzOffsetS
        val primary = (b.card.numbers.firstOrNull { it.default } ?: b.card.numbers.firstOrNull())?.raw
        return b.draft.events.mapIndexedNotNull { i, event: DraftEvent ->
            val line = ok(ops.eventLine(EventLineRequest(event, b.card.name, now, tz))) ?: return@mapIndexedNotNull null
            val text = line.line ?: return@mapIndexedNotNull null
            val parts = text.split(" · ")
            val caption = parts.first()
            val value = parts.drop(1).joinToString(" · ")
            val age = ok(ops.age(AgeRequest(event.date, now, tz)))
            val years = age?.takeIf { it.valid && event.kind == EventKind.Birthday }?.age
            val label =
                if (years != null &&
                    years >= 0
                ) {
                    "$caption · ${if (years == 1L) "1 year old" else "$years years old"}"
                } else {
                    caption
                }
            CardRow(
                key = "date-$i",
                kind = RowKind.Date,
                title = value,
                label = label,
                wish = if (event.kind == EventKind.Birthday && primary != null) line.wish else null,
                wishNumber = primary,
                menu =
                    persistentListOf(
                        FieldMenuItem(
                            "Copy Date",
                            FieldOp.Copy("$caption · ${parts.getOrElse(1) { value }}", "Date copied"),
                        ),
                    ),
            )
        }
    }

    private suspend fun addressRows(draft: ContactDraft): List<CardRow> =
        draft.addresses.filter { it.value.isNotBlank() }.mapIndexed { i, address ->
            val text = address.value.trim()
            val url = ok(ops.mapsUrl(MapsUrlRequest(text)))?.url
            val menu =
                mutableListOf(
                    FieldMenuItem("Copy Address", FieldOp.Copy(text, "Address copied")),
                    FieldMenuItem("Share Address", FieldOp.ShareText(text)),
                )
            if (url != null) menu += FieldMenuItem("Open in Maps", FieldOp.OpenUrl(url))
            CardRow(
                key = "address-$i",
                kind = RowKind.Address,
                title = text,
                label = fieldTitle(address.kind, address.label, "Address"),
                tap = url?.let { FieldOp.OpenUrl(it) },
                menu = menu.toImmutableList(),
            )
        }

    private suspend fun websiteRows(draft: ContactDraft): List<CardRow> =
        draft.websites.filter { it.isNotBlank() }.mapIndexed { i, site ->
            val text = site.trim()
            val url = ok(ops.webUrl(WebUrlRequest(text)))?.url
            val menu = mutableListOf<FieldMenuItem>()
            if (url != null) menu += FieldMenuItem("Open Link", FieldOp.OpenUrl(url))
            menu += FieldMenuItem("Copy Link", FieldOp.Copy(text, "Link copied"))
            menu += FieldMenuItem("Share Link", FieldOp.ShareText(text))
            CardRow(
                key = "website-$i",
                kind = RowKind.Website,
                title = text,
                tap = url?.let { FieldOp.OpenUrl(it) },
                menu = menu.toImmutableList(),
            )
        }

    private fun relationRows(draft: ContactDraft): List<CardRow> =
        draft.relations.filter { it.name.isNotBlank() }.mapIndexed { i, relation: DraftRelation ->
            CardRow(
                key = "relation-$i",
                kind = RowKind.Relation,
                title = relation.name.trim(),
                label = relationTitle(relation),
                relationName = relation.name.trim(),
                menu = persistentListOf(FieldMenuItem("Copy Name", FieldOp.Copy(relation.name.trim(), "Name copied"))),
            )
        }

    private fun textRow(
        key: String,
        kind: RowKind,
        label: String,
        value: String,
        notice: String,
        copyLabel: String = "Copy",
    ) = CardRow(
        key = key,
        kind = kind,
        title = value,
        label = label,
        menu = persistentListOf(FieldMenuItem(copyLabel, FieldOp.Copy(value, notice))),
    )

    private fun <T> ok(outcome: Outcome<T>): T? = (outcome as? Outcome.Ok)?.value

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Newest call-log rows read to find the calls with this contact (`contact_history::PULL_LIMIT`). */
        const val PULL_LIMIT = 500

        /** The device's offset from UTC and the common 12-hour, day-first formats; the app passes the real ones. */
        fun defaultLocale(now: Long): LocaleTime =
            LocaleTime(
                tzOffsetS = TimeZone.getDefault().getOffset(now) / MS_PER_S,
                format = TimeFormat(hour12 = true, dateOrder = DateOrder.DayMonthYear),
            )

        private const val MS_PER_S = 1000
    }
}

internal fun orgLine(
    title: String,
    org: String,
): String? = listOf(title.trim(), org.trim()).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }

internal fun phonetic(draft: ContactDraft): String? =
    listOf(draft.phoneticGiven.trim(), draft.phoneticFamily.trim())
        .filter {
            it.isNotEmpty()
        }.joinToString(" ")
        .ifEmpty { null }

internal fun emailOf(draft: ContactDraft): String? =
    (
        draft.emails.firstOrNull { it.primary && it.value.isNotBlank() }
            ?: draft.emails.firstOrNull { it.value.isNotBlank() }
    )?.value
        ?.trim()

internal fun fieldTitle(
    kind: FieldKind,
    label: String?,
    fallback: String = "Other",
): String =
    when (kind) {
        FieldKind.Mobile -> "Mobile"
        FieldKind.Home -> "Home"
        FieldKind.Work -> "Work"
        FieldKind.Custom -> label?.takeIf { it.isNotBlank() } ?: fallback
        FieldKind.Other -> fallback
    }

internal fun relationTitle(relation: DraftRelation): String =
    when (relation.kind) {
        RelationKind.Custom -> relation.label.ifBlank { "Related" }
        else -> relation.kind.name
    }
