package dev.rovsti.contacts.card

import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftEvent
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.DraftRelation
import dev.rovsti.core.model.contacts.EventKind
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.model.contacts.PinShortcut
import dev.rovsti.core.model.contacts.RelationKind
import dev.rovsti.core.model.contacts.WaLookup
import dev.rovsti.core.model.contacts.WaLookupUser
import dev.rovsti.core.model.contacts.WaPresence
import dev.rovsti.core.model.contacts.WaProfileInput
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativeContactsWaOps
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeQrOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.platform.calllog.FakeCallLogRepository
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.contacts.ShortcutsRepository
import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.files.FakeFilesRepository
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/** The address book of the card tests and shots. Ids 1 to 5, see each person. */
internal object SampleCards {
    /** A fixed clock: 16 Sep 2026, so a birthday on 3 Oct 1990 is 35 years old and 17 days away. */
    const val NOW = 1_789_600_000_000L

    /** Everything a card can hold. The default number is the second in the list; it must be shown first. */
    val ayse =
        ContactDraft(
            id = 1,
            given = "Ayşe",
            family = "Yılmaz",
            org = "Acme Yazılım",
            title = "Engineer",
            nickname = "Ayşo",
            note = "Met at the 2023 mobile conference. Prefers e-mail before noon.",
            numbers =
                listOf(
                    DraftField("+49 30 1234567", FieldKind.Work, label = "Work"),
                    DraftField("+90 532 111 22 33", FieldKind.Mobile, label = "Mobile", primary = true),
                ),
            emails =
                listOf(
                    DraftField("ayse@acme.example", FieldKind.Work, primary = true),
                    DraftField("ayse.yilmaz@home.example", FieldKind.Home),
                ),
            addresses = listOf(DraftField("Bağdat Caddesi 12, Kadıköy, İstanbul", FieldKind.Home)),
            websites = listOf("acme.example/ayse"),
            events = listOf(DraftEvent("1990-10-03", EventKind.Birthday)),
            relations = listOf(DraftRelation("Burak Demir", RelationKind.Brother)),
            groups = listOf(10),
            starred = true,
        )

    /** A name and one number. */
    val burak =
        ContactDraft(
            id = 2,
            given = "Burak",
            family = "Demir",
            numbers = listOf(DraftField("0532 123 45 67", FieldKind.Mobile, label = "Mobile")),
        )

    /** Two numbers, the second one on WhatsApp (chat user 905337654321). */
    val elif =
        ContactDraft(
            id = 3,
            given = "Elif",
            family = "Kaya",
            org = "Kaya Studio",
            numbers =
                listOf(
                    DraftField("+90 212 555 00 11", FieldKind.Work, label = "Work"),
                    DraftField("+90 533 765 43 21", FieldKind.Mobile, label = "Mobile"),
                ),
        )

    /** Two numbers and no default, with a photo. */
    val zeynep =
        ContactDraft(
            id = 5,
            given = "Zeynep",
            family = "Arslan",
            numbers =
                listOf(
                    DraftField("+1 415 555 0132", FieldKind.Mobile, label = "Mobile"),
                    DraftField("+1 415 555 0199", FieldKind.Work, label = "Work"),
                ),
        )

    val all = listOf(ayse, burak, elif, ContactDraft(id = 4, given = "Mehmet", family = "Öz"), zeynep)

    val groups = listOf(Group(id = 10, title = "Family"))

    /** The provider answer with the lookup key and the photo a real provider adds. */
    class Keyed(
        private val inner: FakeContactsRepository,
    ) : ContactsRepository by inner {
        override fun all(): Flow<List<WireContact>> =
            inner.all().map { list ->
                list.map { c ->
                    c.copy(
                        lookupKey = "k-${c.id}",
                        sortKey = c.displayName.lowercase(),
                        photoUri = if (c.id == 5L) "content://photos/5" else null,
                    )
                }
            }
    }
}

/** A scripted WhatsApp side that counts what the card asks of it. */
internal class FakeCardWa(
    linked: Boolean = true,
    chatUsers: List<String> = emptyList(),
    /** What the gateway answers to a lookup. */
    var answer: (List<String>) -> WaLookup = { jids ->
        WaLookup(WaLookup.DONE, jids.map { WaLookupUser(it, onWhatsapp = true) })
    },
    var profile: WaProfileInput? = null,
) : CardWaPort {
    val link = MutableStateFlow(linked)
    val chats = MutableStateFlow(chatUsers)
    val updates = MutableSharedFlow<WaPresence>(replay = 1)
    val lookups = CopyOnWriteArrayList<List<String>>()
    val subscriptions = CopyOnWriteArrayList<String>()

    override val linked: Flow<Boolean> = link

    override fun chatUsers(): Flow<List<String>> = chats

    override suspend fun lookup(jids: List<String>): WaLookup {
        lookups += jids
        return answer(jids)
    }

    override suspend fun subscribePresence(jid: String) {
        subscriptions += jid
    }

    override fun presence(jid: String): Flow<WaPresence> = updates

    override suspend fun profile(jid: String): WaProfileInput? = profile
}

/**
 * The card's model on the REAL Rust core (the host `librovsti_ffi` that `:core-bridge:cargoBuildHost` builds), with the
 * in-memory provider and call log. [assumeTrue] skips the test when the host library is absent.
 */
internal class CardRig(
    val id: Long,
    drafts: List<ContactDraft> = SampleCards.all,
    val permission: MutableStateFlow<PermissionState> = MutableStateFlow(PermissionState.Granted),
    calls: List<CallLogEntry> = emptyList(),
    val wa: FakeCardWa = FakeCardWa(linked = false),
) {
    val scope = CoroutineScope(Dispatchers.Default)
    val repo = FakeContactsRepository(drafts, SampleCards.groups)
    val callLog = FakeCallLogRepository(calls)
    val clipboard = FakeClipboardRepository()
    val files = FakeFilesRepository()
    val pins = mutableListOf<PinShortcut>()
    val staging: File = Files.createTempDirectory("rovsti-card-staging").toFile().apply { deleteOnExit() }
    val model: ContactCardViewModel

    init {
        val lib = File(System.getProperty("rovsti.hostLib").orEmpty())
        assumeTrue(
            "host librovsti_ffi not built (run :core-bridge:cargoBuildHost)",
            lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rovsti_ffi") },
        )
        val root = Files.createTempDirectory("rovsti-card").toFile().apply { deleteOnExit() }
        val started =
            runBlocking {
                Rust
                    .init(
                        File(root, "data"),
                        File(root, "cache"),
                        "dev.rovsti.contacts",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            }
        assertTrue("Rust.init: $started", started is Outcome.Ok)
        val ops = NativeContactsOps(Rust.core())
        val store = ContactsStore(ops, File(root, "store").apply { mkdirs() }.absolutePath) { SampleCards.NOW }
        val provider = SampleCards.Keyed(repo)
        val book =
            BookRepository(
                provider,
                MutableStateFlow(PermissionState.Granted),
                NativeSearchOps(Rust.core()),
                ops,
                scope,
            )
        model =
            ContactCardViewModel(
                id = id,
                repo = provider,
                book = book,
                ops = ops,
                shared = NativeSharedOps(Rust.core()),
                phoneOps = NativePhoneOps(Rust.core()),
                waOps = NativeContactsWaOps(Rust.core()),
                wa = wa,
                callLog = callLog,
                callLogPermission = permission,
                store = store,
                clipboard = clipboard,
                files = files,
                qr = NativeQrOps(Rust.core()),
                shortcuts =
                    object : ShortcutsRepository {
                        override fun canPin() = true

                        override fun pinContact(spec: PinShortcut): Boolean = pins.add(spec)

                        override fun pinStop(spec: dev.rovsti.core.model.stops.ShortcutAnswer) = false
                    },
                stagingDir = staging,
                clock = { SampleCards.NOW },
                scope = scope,
            )
    }

    /** The first state that satisfies [predicate]; fails after [TIMEOUT_MS]. */
    fun stateWhere(predicate: (ContactCardUiState) -> Boolean): ContactCardUiState =
        runBlocking { withTimeout(TIMEOUT_MS) { model.state.first(predicate) } }

    fun ready(): ContactCardUiState = stateWhere { it.phase == CardPhase.Ready }

    fun effect(): ContactCardEffect = runBlocking { withTimeout(TIMEOUT_MS) { model.effects.first() } }

    fun row(
        state: ContactCardUiState,
        key: String,
    ): CardRow = state.sections.flatMap { it.rows }.first { it.key == key }

    companion object {
        const val TIMEOUT_MS = 15_000L

        /** A call-log row of [number]. */
        fun call(
            id: Long,
            number: String,
            kind: CallLogKind,
            agoMs: Long,
            durationS: Long = 0,
        ) = CallLogEntry(
            id = id,
            numberRaw = number,
            normalized = number.filter { it.isDigit() },
            kind = kind,
            dateMs = SampleCards.NOW - agoMs,
            durationS = durationS,
        )
    }
}
