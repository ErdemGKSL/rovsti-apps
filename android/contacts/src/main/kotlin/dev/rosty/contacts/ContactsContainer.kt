package dev.rosty.contacts

import android.content.Context
import android.net.Uri
import dev.rosty.contacts.card.CardWaPort
import dev.rosty.contacts.card.RepositoryCardWaPort
import dev.rosty.contacts.cards.CardPhotos
import dev.rosty.contacts.data.AndroidReminderPort
import dev.rosty.contacts.data.BirthdayAlarms
import dev.rosty.contacts.data.BookRepository
import dev.rosty.contacts.data.ContactsStore
import dev.rosty.contacts.data.RecentTracker
import dev.rosty.contacts.data.ReminderPort
import dev.rosty.contacts.data.StoreCommonSettings
import dev.rosty.contacts.data.WaMarks
import dev.rosty.contacts.people.PeopleSync
import dev.rosty.contacts.people.PeopleSyncWorker
import dev.rosty.contacts.photo.GatewayWaPhotoPort
import dev.rosty.contacts.photo.WaPhotoPort
import dev.rosty.contacts.trash.ContactDeletion
import dev.rosty.contacts.trash.ContactTrash
import dev.rosty.core.Outcome
import dev.rosty.core.Rust
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.shared.InferHomeCcRequest
import dev.rosty.core.ops.CardsOps
import dev.rosty.core.ops.ContactsOps
import dev.rosty.core.ops.ContactsWaOps
import dev.rosty.core.ops.NativeCardsOps
import dev.rosty.core.ops.NativeContactsOps
import dev.rosty.core.ops.NativeContactsWaOps
import dev.rosty.core.ops.NativePeopleOps
import dev.rosty.core.ops.NativePhoneOps
import dev.rosty.core.ops.NativeQrOps
import dev.rosty.core.ops.NativeSearchOps
import dev.rosty.core.ops.NativeSharedOps
import dev.rosty.core.ops.PeopleOps
import dev.rosty.core.ops.PhoneOps
import dev.rosty.core.ops.QrOps
import dev.rosty.core.ops.SearchOps
import dev.rosty.core.ops.SharedOps
import dev.rosty.people.AvatarResolver
import dev.rosty.people.PeopleIndex
import dev.rosty.people.WaFullPictures
import dev.rosty.people.sync.PeopleStore
import dev.rosty.people.sync.PeopleSyncRunner
import dev.rosty.people.sync.PeopleSyncWork
import dev.rosty.platform.calllog.AndroidBlocklistRepository
import dev.rosty.platform.calllog.AndroidCallLogRepository
import dev.rosty.platform.calllog.BlocklistRepository
import dev.rosty.platform.calllog.CallLogRepository
import dev.rosty.platform.contacts.AndroidContactsRepository
import dev.rosty.platform.contacts.AndroidShortcutsRepository
import dev.rosty.platform.contacts.ContactsRepository
import dev.rosty.platform.contacts.ShortcutsRepository
import dev.rosty.platform.identity.PhoneNumbers
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.shell.ShellContainer
import dev.rosty.shell.settings.CommonSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What Rosty Contacts adds to the shared [ShellContainer] (ARCH 5.3). Every member is lazy and built by a `create*`
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

    /** The full-size WhatsApp picture the card's photo viewer swaps in (S12); a small cache folder, never the book. */
    val waFullPictures: WaFullPictures by lazy {
        WaFullPictures(File(context.cacheDir, WaFullPictures.DIR_NAME), picture = { body ->
            wa.gateway()?.picture(body)
        })
    }

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

    /** The people cache in memory (lookups never block): what every avatar asks, see [avatarResolver]. */
    val peopleIndex: PeopleIndex by lazy { PeopleIndex.forRegion(PhoneNumbers.homeRegion(context)) }

    /** Own photo, else the WhatsApp picture, else initials: the one place a Contacts avatar is decided (X.1c). */
    val avatarResolver: AvatarResolver by lazy { AvatarResolver(peopleIndex) }

    /**
     * Loads the people cache into [peopleIndex] once `Rust.init` is done, so the first screens already draw cached
     * pictures. Safe to call early: it waits for the init (rule b) and does nothing when the core cannot answer.
     */
    suspend fun warmPeopleIndex() {
        if (!rustReady()) return
        refreshPeopleIndex()
    }

    /** Re-reads the cache into [peopleIndex] (after a sync); an unchanged cache recomposes nothing. */
    suspend fun refreshPeopleIndex() {
        val rows = peopleStore.snapshot()
        val present =
            withContext(Dispatchers.IO) {
                rows.mapNotNull { row -> row.pictureFile?.let { File(peopleStore.dir, it) }?.takeIf { it.isFile } }
            }.mapTo(HashSet()) { it.name }
        peopleIndex.replace(rows) { row ->
            row.pictureFile?.takeIf { it in present }?.let { Uri.fromFile(File(peopleStore.dir, it)).toString() }
        }
    }

    /** Fills [peopleStore] from the gateway while WhatsApp is linked (the worker, "Sync now", live events); started once. */
    val peopleSync: PeopleSync by lazy { createPeopleSync().also { it.start() } }

    /**
     * [peopleSync] while Contacts' WhatsApp vault is open, else null (`plans/wa-vault.md` 13.3): a run of the worker
     * (the 12-hourly one, its continuation, a start from the app) that finds the vault locked makes no gateway request
     * and ends at once. It is not lost: the next unlock runs the foreground trigger ([unlockedWhatsApp]), which syncs
     * when the last run is older than the period, so the deferred run costs the gateway nothing extra.
     */
    suspend fun peopleSyncWhenUnlocked(): PeopleSync? {
        val vault = waVault
        vault.refresh()
        if (vault.unlocked) return peopleSync
        RostyLog.event(PeopleSyncRunner.TAG, "worker_skipped", "reason" to "vault_locked")
        return null
    }

    /** The vault opened: a people sync the lock held back runs now when it is due (the foreground trigger). */
    override suspend fun unlockedWhatsApp() {
        super.unlockedWhatsApp()
        peopleSync.syncIfStale()
    }

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

    /** Share cards for Card Studio (X.2). */
    val cards: CardsOps by lazy { createCards() }

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
            ops = peopleOps,
            wa = wa,
            numbers = ::peopleNumbers,
            scope = scope,
            legacy = context.getSharedPreferences(PEOPLE_PREFS, Context.MODE_PRIVATE),
            // The vault's link: never `whatsapp-link.json` (`plans/wa-vault.md` 13.3).
            gatewayUrl = { (waLinkSource.read() as? Outcome.Ok)?.value?.gatewayUrl.orEmpty() },
            lockFile = File(context.noBackupFilesDir, PEOPLE_LOCK),
            onChanged = {
                peopleStore.notifyChanged(context.contentResolver)
                refreshPeopleIndex()
            },
            // Sync Now, Continue and the foreground trigger run in the foreground worker (people sync v2 S11).
            request = { start -> PeopleSyncWorker.startNow(context, start) },
            dropContinuation = { PeopleSyncWork.dropContinuation(context) },
            bundleDir = File(context.cacheDir, PeopleSyncRunner.BUNDLE_DIR),
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

    /** The Rust card renderer (`cards.*`); a contact photo URI is copied to a cache file it can read. */
    protected open fun createCards(): CardsOps = NativeCardsOps(rust, CardPhotos(context)::file)

    protected open fun createShortcuts(): ShortcutsRepository = AndroidShortcutsRepository(context)
}

/** How many contact numbers the home-country guess reads (as Phone's). */
private const val HOME_CC_SAMPLE = 200

/** How many recent calls the people sync reads numbers from. */
private const val RECENT_CALLS = 300

/** The v1 sync's prefs: read once by [PeopleSync] for the last sync, then cleared; the name stays reserved. */
private const val PEOPLE_PREFS = "people-sync"

/** The people sync's cross-process guard, in `noBackupFilesDir`. */
private const val PEOPLE_LOCK = "people-sync.lock"
