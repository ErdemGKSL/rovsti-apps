package dev.rosty.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.rosty.core.Rust
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.WaSyncAction
import dev.rosty.core.ops.ChatOps
import dev.rosty.core.ops.MessagesOps
import dev.rosty.core.ops.NativeChatOps
import dev.rosty.core.ops.NativeMessagesOps
import dev.rosty.core.ops.NativeQrOps
import dev.rosty.core.ops.NativeSearchOps
import dev.rosty.core.ops.NativeSharedOps
import dev.rosty.core.ops.QrOps
import dev.rosty.core.ops.SearchOps
import dev.rosty.core.ops.SharedOps
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.messages.data.ContactBook
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.inbox.waPreview
import dev.rosty.messages.names.LiveNames
import dev.rosty.messages.names.PacedAsks
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.names.StoredWaNames
import dev.rosty.messages.notify.AnnouncerSources
import dev.rosty.messages.notify.MessageAnnouncer
import dev.rosty.messages.notify.ReplyRouter
import dev.rosty.messages.remind.RemindCenter
import dev.rosty.messages.schedule.FileQueueStore
import dev.rosty.messages.schedule.GatewayWaScheduleRemote
import dev.rosty.messages.schedule.ScheduleCenter
import dev.rosty.messages.spam.CodeCleanup
import dev.rosty.messages.spam.CodeCleanupWorker
import dev.rosty.messages.spam.SpamActions
import dev.rosty.messages.starred.StarCenter
import dev.rosty.messages.starred.realStarCenter
import dev.rosty.messages.wa.GatewayWaRemote
import dev.rosty.messages.wa.StoreWaChats
import dev.rosty.messages.wa.WaChats
import dev.rosty.messages.wa.WaMedia
import dev.rosty.messages.wa.WaOffline
import dev.rosty.messages.wa.WaRecentEmoji
import dev.rosty.messages.wa.WaRemote
import dev.rosty.messages.wa.WaVoiceNotes
import dev.rosty.messages.wa.realWaOffline
import dev.rosty.messages.wa.realWaVoice
import dev.rosty.messages.wa.status.StatusFeed
import dev.rosty.people.AvatarResolver
import dev.rosty.people.FileLiveNameStore
import dev.rosty.people.LiveWaNames
import dev.rosty.people.PeopleIndex
import dev.rosty.people.PeopleSources
import dev.rosty.people.ProviderSource
import dev.rosty.platform.calllog.AndroidBlocklistRepository
import dev.rosty.platform.calllog.BlocklistRepository
import dev.rosty.platform.contacts.AndroidContactsRepository
import dev.rosty.platform.contacts.ContactsRepository
import dev.rosty.platform.identity.PhoneNumbers
import dev.rosty.platform.notify.ReminderRepositories
import dev.rosty.platform.sms.AndroidScheduledSmsRepository
import dev.rosty.platform.sms.AndroidSmsRepository
import dev.rosty.platform.sms.ScheduledSmsRepository
import dev.rosty.platform.sms.SmsQuery
import dev.rosty.platform.sms.SmsRepository
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.telephony.AndroidSimRepository
import dev.rosty.platform.telephony.SimRepository
import dev.rosty.shell.ShellContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * What Rosty Messages adds to the shared [ShellContainer] (ARCH 5.3). Every member is lazy and built by a `create*`
 * seam, so a cold path (a receiver, the FCM service) pays for what it touches and tests swap in fakes without a native
 * library.
 */
open class MessagesContainer(
    context: Context,
) : ShellContainer(context, AppPermissions.messages) {
    /** The SMS/MMS provider: threads, messages, send, delete. */
    val sms: SmsRepository by lazy { createSms() }

    /** Scheduled SMS alarms (AD-MSG6). */
    val scheduledSms: ScheduledSmsRepository by lazy { createScheduledSms() }

    /** The address book, for names and the New Conversation picker. */
    val contacts: ContactsRepository by lazy { createContacts() }

    /** The SIMs of a dual-SIM phone (the send chooser). */
    val sims: SimRepository by lazy { createSims() }

    /** Every messages decision the Rust core makes: inbox merge, timeline, spam, schedule, drafts, prefs. */
    val ops: MessagesOps by lazy { createOps() }

    /** The conversation tools (find, gallery, export, info, marks, stars, detection). */
    val chat: ChatOps by lazy { createChat() }

    val search: SearchOps by lazy { createSearch() }

    val sharedOps: SharedOps by lazy { createSharedOps() }

    val qr: QrOps by lazy { createQr() }

    /** `messages-prefs.json` and `drafts.json`. */
    val store: MessagesStore by lazy { MessagesStore(ops, scope) }

    /** The home region (SIM, network, locale, else TR) that national numbers are normalized in. */
    val homeRegion: String by lazy { PhoneNumbers.homeRegion(context) }

    /** The people cache in memory (X.1): names and WhatsApp pictures by number, read without blocking. */
    val peopleIndex: PeopleIndex by lazy { PeopleIndex.forRegion(homeRegion) }

    /** Own photo, else the WhatsApp picture, else initials: the one place a Messages avatar is decided. */
    val avatarResolver: AvatarResolver by lazy { AvatarResolver(peopleIndex) }

    /** Whether this app holds the SMS role; [refreshSmsRole] re-reads it (the activity does so on every resume). */
    val smsRole: MutableStateFlow<Boolean> by lazy { MutableStateFlow(readSmsRole()) }

    fun refreshSmsRole() {
        smsRole.value = readSmsRole()
        smsReadable.value = readSmsReadable()
    }

    protected open fun readSmsRole(): Boolean = specialAccess.isRoleHeld(AppPermissions.ROLE_SMS)

    /** Whether `READ_SMS` is granted (the role grants it); re-read with the role on every resume. */
    val smsReadable: MutableStateFlow<Boolean> by lazy { MutableStateFlow(readSmsReadable()) }

    protected open fun readSmsReadable(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** The address book and its Rust index (follows READ_CONTACTS). */
    val book: ContactBook by lazy {
        ContactBook(contacts, permissions.state(PermissionGroup.CONTACTS), search, scope)
    }

    /** WhatsApp names asked on demand for numbers no contact and no stored chat names (shared with Phone's rule). */
    val liveWaNames: LiveWaNames by lazy { createLiveWaNames() }

    /** The one name-order rule of every surface: contact, WhatsApp name (verified, push), number. */
    val names: PersonNames by lazy { createNames() }

    /** Every gateway call of the WhatsApp sync, outbox and Status (E6.4s); tests swap in a fake. */
    val waRemote: WaRemote by lazy { createWaRemote() }

    /** The offline store of the linked WhatsApp instance with its sync and outbox (E6.4s), following the link. */
    val waOffline: WaOffline get() = waOfflineOnce.value

    private val waOfflineOnce = lazy { createWaOffline().also { it.start() } }

    /** The WhatsApp Status updates, for the Status row and the viewer. */
    val statusFeed: StatusFeed by lazy {
        StatusFeed(scope, waOffline, waRemote, java.io.File(context.cacheDir, "wa-status")).also { it.start() }
    }

    /** The system blocklist (shared with Phone); the default SMS app may write it. */
    val blocklist: BlocklistRepository by lazy { createBlocklist() }

    /** Spam rules, allow list and blocklist changes (E6.11, AD-MSG8). */
    val spamActions: SpamActions by lazy { SpamActions(store, ops, blocklist) }

    /** M47: passcode messages deleted 24 h after their code was copied. */
    val codeCleanup: CodeCleanup by lazy { createCodeCleanup() }

    /** The WhatsApp chats of the inbox and their actions, read from the offline store. */
    val waChats: WaChats by lazy { createWaChats() }

    /** Thumbnails, downloads and the files to send of WhatsApp messages (E6.4b), kept through the store's LRU. */
    val waMedia: WaMedia by lazy {
        WaMedia(waOffline, waRemote, java.io.File(context.filesDir, "wa-media"), System::currentTimeMillis)
    }

    /** The voice note player (M17): one note at a time. */
    val waVoice: WaVoiceNotes by lazy { realWaVoice(this) }

    /** The emoji used last for reactions (M49). */
    val waEmoji: WaRecentEmoji by lazy { WaRecentEmoji(java.io.File(context.filesDir, "recent-reactions.json")) }

    /** Where a reply typed into a notification goes while the UI is attached (E6.9). */
    val replies: ReplyRouter by lazy { createReplies() }

    /** The notifications the app makes while its process runs: WhatsApp from the event stream, the switches (E6.9). */
    val announcer: MessageAnnouncer by lazy { createAnnouncer() }

    /** Scheduled send (E6.10, AD-MSG6): the local SMS queue with its alarms and the gateway's WhatsApp items. */
    val schedule: ScheduleCenter by lazy { createSchedule() }

    /** Stars (E6.13): SMS/MMS in the prefs (M23), WhatsApp through the store's outbox (M10), and the Starred list. */
    val stars: StarCenter by lazy { createStars().also { it.start() } }

    /** "Remind me" on a message (M33): Rust's slots and payload, `:platform`'s alarms. */
    val reminders: RemindCenter by lazy { createReminders().also { it.start() } }

    protected open fun createStars(): StarCenter = realStarCenter(this)

    protected open fun createReminders(): RemindCenter =
        RemindCenter(chat, ReminderRepositories.messages(context), scope, System::currentTimeMillis)

    protected open fun createReplies(): ReplyRouter =
        ReplyRouter(
            sms = sms,
            wa = { jid, text -> waOffline.enqueue(jid, WaSyncAction.SendText(text)) },
            recipients = { thread -> withContext(Dispatchers.IO) { SmsQuery.addressesFor(context, thread) } },
            sim = { thread ->
                store.prefs.value
                    ?.simChoices
                    ?.get(thread)
            },
            now = System::currentTimeMillis,
        )

    protected open fun createAnnouncer(): MessageAnnouncer {
        val words = ResourceInboxWords(context.resources)
        return MessageAnnouncer(
            context,
            scope,
            AnnouncerSources(
                ops = ops,
                prefs = store.prefs,
                loadPrefs = { store.load() },
                live = waOffline.live,
                chatOf = { jid ->
                    withTimeoutOrNull(CHAT_LOOKUP_MS) { waChats.chats.first() }?.firstOrNull { it.jid == jid }
                },
                names = names,
                preview = { body -> waPreview(body, MessageMeta(), words) },
                pushWake = context.resources.getBoolean(R.bool.push_wake),
                now = System::currentTimeMillis,
            ),
        )
    }

    protected open fun createSchedule(): ScheduleCenter =
        ScheduleCenter(
            scope = scope,
            ops = ops,
            alarms = scheduledSms,
            remote = GatewayWaScheduleRemote(wa),
            live = waOffline.live,
            queue = FileQueueStore(context.noBackupFilesDir),
            now = System::currentTimeMillis,
        )

    protected open fun createLiveWaNames(): LiveWaNames =
        LiveWaNames(
            gateway = { wa.gateway() },
            e164 = { PhoneNumbers.e164(it, homeRegion) },
            scope = scope,
            // Kept between runs, so a restart does not ask WhatsApp for the same numbers again.
            store = FileLiveNameStore(File(context.noBackupFilesDir, "wa-live-names.json")),
        )

    protected open fun createNames(): PersonNames {
        val stored =
            StoredWaNames(
                // A receiver in a cold process names a sender without starting the WhatsApp sync.
                offline = { if (waOfflineOnce.isInitialized()) waOffline else null },
                key = { number -> PhoneNumbers.e164(number, homeRegion)?.filter(Char::isDigit) },
                scope = scope,
            )
        return PersonNames(
            contactName = { number -> book.owner(number)?.displayName },
            // The book (and the Rust index behind it) opens with the first lookup, not with the resolver.
            contactChanges = flow { emitAll(book.book) },
            live = LiveNames.of(liveWaNames, PacedAsks(scope, liveWaNames::ask)),
            stored = stored::of,
            readable = { number -> withContext(Dispatchers.Default) { PhoneNumbers.forReading(number, homeRegion) } },
        )
    }

    protected open fun createWaRemote(): WaRemote = GatewayWaRemote(wa)

    protected open fun createWaOffline(): WaOffline = realWaOffline(this, waRemote)

    protected open fun createWaChats(): WaChats = StoreWaChats(waOffline, waRemote, names)

    @Volatile private var peopleStarted = false

    /**
     * Follows Contacts' people provider when it is installed (names and WhatsApp pictures it already synced). Without
     * Contacts the avatars fall back to [avatars] (the WhatsApp pictures of the chats). Idempotent.
     */
    fun startPeople() {
        synchronized(this) {
            if (peopleStarted) return
            peopleStarted = true
        }
        if (!PeopleSources.providerInstalled(context.packageManager)) return
        val source = ProviderSource(context.contentResolver)
        scope.launch { peopleIndex.attach(this, source) }
    }

    /** True once `Rust.init` has finished; the first call starts it (rule b). */
    open suspend fun rustReady(): Boolean {
        rust
        Rust.ready.first { it }
        return true
    }

    protected open fun createSms(): SmsRepository = AndroidSmsRepository(context, Dispatchers.IO)

    protected open fun createScheduledSms(): ScheduledSmsRepository =
        AndroidScheduledSmsRepository(context, Dispatchers.IO)

    protected open fun createContacts(): ContactsRepository = AndroidContactsRepository(context, Dispatchers.IO)

    protected open fun createSims(): SimRepository = AndroidSimRepository(context, Dispatchers.IO)

    protected open fun createOps(): MessagesOps = NativeMessagesOps(rust)

    protected open fun createBlocklist(): BlocklistRepository = AndroidBlocklistRepository(context, Dispatchers.IO)

    protected open fun createCodeCleanup(): CodeCleanup =
        CodeCleanup(
            file = java.io.File(context.noBackupFilesDir, CodeCleanup.FILE),
            sms = sms,
            roleHeld = { readSmsRole() },
            wake = { delay -> CodeCleanupWorker.schedule(context, delay) },
            now = SystemRostyClock::nowMillis,
        )

    protected open fun createChat(): ChatOps = NativeChatOps(rust)

    protected open fun createSearch(): SearchOps = NativeSearchOps(rust)

    protected open fun createSharedOps(): SharedOps = NativeSharedOps(rust)

    protected open fun createQr(): QrOps = NativeQrOps(rust)

    private companion object {
        const val CHAT_LOOKUP_MS = 2_000L
    }
}
