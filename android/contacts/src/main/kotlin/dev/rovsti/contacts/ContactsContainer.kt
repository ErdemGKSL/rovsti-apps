package dev.rovsti.contacts

import android.content.Context
import dev.rovsti.contacts.card.CardWaPort
import dev.rovsti.contacts.card.RepositoryCardWaPort
import dev.rovsti.contacts.data.AndroidReminderPort
import dev.rovsti.contacts.data.BirthdayAlarms
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.data.RecentTracker
import dev.rovsti.contacts.data.ReminderPort
import dev.rovsti.contacts.data.StoreCommonSettings
import dev.rovsti.contacts.data.WaMarks
import dev.rovsti.contacts.people.PeopleStore
import dev.rovsti.contacts.people.PeopleSync
import dev.rovsti.contacts.people.PeopleSyncLog
import dev.rovsti.contacts.photo.GatewayWaPhotoPort
import dev.rovsti.contacts.photo.WaPhotoPort
import dev.rovsti.contacts.trash.ContactDeletion
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.model.shared.InferHomeCcRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.ContactsWaOps
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativeContactsWaOps
import dev.rovsti.core.ops.NativePeopleOps
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeQrOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.core.ops.PeopleOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.QrOps
import dev.rovsti.core.ops.SearchOps
import dev.rovsti.core.ops.SharedOps
import dev.rovsti.platform.calllog.AndroidBlocklistRepository
import dev.rovsti.platform.calllog.AndroidCallLogRepository
import dev.rovsti.platform.calllog.BlocklistRepository
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.contacts.AndroidContactsRepository
import dev.rovsti.platform.contacts.AndroidShortcutsRepository
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.ShortcutsRepository
import dev.rovsti.platform.identity.PhoneNumbers
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.shell.ShellContainer
import dev.rovsti.shell.settings.CommonSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What Rovsti Contacts adds to the shared [ShellContainer] (ARCH 5.3). Every member is lazy and built by a `create*`
 * seam, so a cold path pays for what it touches and tests swap in fakes without a native library.
 */
open class ContactsContainer(
    context: Context,
) : ShellContainer(context, AppPermissions.contacts) {
    /** The address book on the contacts provider. */
    val contacts: ContactsRepository by lazy { createContacts() }

    val callLog: CallLogRepository by lazy { createCallLog() }

    /** The system blocklist, for the card's Block This Caller row (E4.13); usable only as the default phone app. */
    val blocklist: BlocklistRepository by lazy { createBlocklist() }

    /** Every contacts decision the Rust core makes (ARCH 4). */
    val ops: ContactsOps by lazy { createOps() }

    val search: SearchOps by lazy { createSearch() }

    /** The WhatsApp decisions of the list and the card (AD-CT6). */
    val waCard: ContactsWaOps by lazy { createWaCard() }

    /** A name the card asked the Contacts list to search for (a relation nobody is called); the list consumes it. */
    val pendingSearch: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Call-log matching for the card's Recent section. */
    val phoneOps: PhoneOps by lazy { createPhoneOps() }

    /** Number formats and countries. */
    val sharedOps: SharedOps by lazy { createSharedOps() }

    /** What the card needs from WhatsApp; the lookups go through the linked instance only. */
    val cardWa: CardWaPort by lazy { createCardWa() }

    /** The preferences and the Trash, files in the data directory. */
    val store: ContactsStore by lazy { ContactsStore(ops) }

    /** Delete and restore with the Trash (E4.5). */
    val trash: ContactTrash by lazy { ContactTrash(contacts, store) }

    /** Deleting one contact from the card or the editor: ask, Trash, delete, Undo pill. */
    val deletion: ContactDeletion by lazy { ContactDeletion(trash, ops, scope, context) }

    /** "Use WhatsApp Photo": the large picture of one JID from the owner's gateway. */
    val waPhotos: WaPhotoPort by lazy { createWaPhotos() }

    /** The address book and its search indexes; follows READ_CONTACTS. */
    val book: BookRepository by lazy {
        BookRepository(
            contacts = contacts,
            permission = permissions.state(PermissionGroup.CONTACTS),
            search = search,
            ops = ops,
            scope = scope,
            nameFormat = { store.prefs.value.nameFormat },
        )
    }

    /** Marks the contacts added since the last pull ("Recently added"); started once, follows the book. */
    val recentTracker: RecentTracker by lazy { RecentTracker(book, store, ops, scope).also { it.start() } }

    /** The list's WhatsApp marks, from the chats the instance already has. */
    val waMarks: WaMarks by lazy {
        WaMarks(wa, book.book, waCard, scope) { contacts ->
            val numbers = contacts.flatMap { c -> c.numbers.flatMap { listOfNotNull(it.raw, it.normalized) } }
            (sharedOps.inferHomeCc(InferHomeCcRequest(numbers.take(HOME_CC_SAMPLE))) as? Outcome.Ok)?.value?.homeCc
        }
    }

    /** The people cache ops (X.1): names, picture ids and files of the people met on WhatsApp. */
    val peopleOps: PeopleOps by lazy { createPeopleOps() }

    /** The cache itself: records in the Rust data directory, pictures in `filesDir/people/`. */
    val peopleStore: PeopleStore by lazy { createPeopleStore() }

    /** Fills [peopleStore] from the gateway while WhatsApp is linked (the worker, "Sync now", live events); started once. */
    val peopleSync: PeopleSync by lazy { createPeopleSync().also { it.start() } }

    /**
     * True once `Rust.init` has finished, waiting for it as long as the caller allows (the provider bounds the wait).
     * The first call starts the init when nothing did before (rule b: nothing calls `RustNative` earlier).
     */
    open suspend fun rustReady(): Boolean {
        rust
        Rust.ready.first { it }
        return true
    }

    val qr: QrOps by lazy { createQr() }

    val shortcuts: ShortcutsRepository by lazy { createShortcuts() }

    /** Appearance and haptics live in `contacts-prefs.json` (E4.15); the activity follows them. */
    override val commonSettings: CommonSettings by lazy { StoreCommonSettings(store, scope) }

    /** Keeps the birthday alarms in line with the book, the switch and the hour (C22); started once. */
    val birthdays: BirthdayAlarms by lazy {
        BirthdayAlarms(book, store, ops, createReminderPort(), scope).also { it.start() }
    }

    protected open fun createReminderPort(): ReminderPort = AndroidReminderPort(context)

    protected open fun createWaPhotos(): WaPhotoPort = GatewayWaPhotoPort.over(wa)

    protected open fun createContacts(): ContactsRepository = AndroidContactsRepository(context, Dispatchers.IO)

    protected open fun createBlocklist(): BlocklistRepository = AndroidBlocklistRepository(context, Dispatchers.IO)

    protected open fun createCallLog(): CallLogRepository = AndroidCallLogRepository(context, Dispatchers.IO)

    protected open fun createOps(): ContactsOps = NativeContactsOps(rust)

    protected open fun createPhoneOps(): PhoneOps = NativePhoneOps(rust)

    protected open fun createSharedOps(): SharedOps = NativeSharedOps(rust)

    protected open fun createCardWa(): CardWaPort = RepositoryCardWaPort(wa)

    protected open fun createWaCard(): ContactsWaOps = NativeContactsWaOps(rust)

    protected open fun createSearch(): SearchOps = NativeSearchOps(rust)

    protected open fun createPeopleOps(): PeopleOps = NativePeopleOps(rust)

    protected open fun createPeopleStore(): PeopleStore =
        PeopleStore(peopleOps, waOps, File(context.filesDir, PeopleStore.DIR_NAME))

    protected open fun createPeopleSync(): PeopleSync =
        PeopleSync(
            store = peopleStore,
            wa = wa,
            numbers = ::peopleNumbers,
            log = PeopleSyncLog(context.getSharedPreferences(PEOPLE_PREFS, Context.MODE_PRIVATE)),
            scope = scope,
            onChanged = { peopleStore.notifyChanged(context.contentResolver) },
        )

    /** E.164 numbers worth asking WhatsApp about: the recent callers first, then the address book. */
    protected open suspend fun peopleNumbers(): List<String> =
        withContext(Dispatchers.IO) {
            val region = PhoneNumbers.homeRegion(context)
            val recent = callLog.entries(RECENT_CALLS).first().map { it.normalized }
            val book =
                contacts.withNumbers().first().flatMap { c ->
                    c.numbers.map { it.normalized ?: PhoneNumbers.e164(it.raw, region).orEmpty() }
                }
            (recent + book).filter { it.startsWith("+") }.distinct().take(PeopleSync.MAX_NUMBERS)
        }

    protected open fun createQr(): QrOps = NativeQrOps(rust)

    protected open fun createShortcuts(): ShortcutsRepository = AndroidShortcutsRepository(context)
}

/** How many contact numbers the home-country guess reads (as Phone's). */
private const val HOME_CC_SAMPLE = 200

/** How many recent calls the people sync reads numbers from. */
private const val RECENT_CALLS = 300

private const val PEOPLE_PREFS = "people-sync"
