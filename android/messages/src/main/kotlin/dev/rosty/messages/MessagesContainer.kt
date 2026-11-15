package dev.rosty.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.rosty.core.Rust
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
import dev.rosty.messages.data.ContactBook
import dev.rosty.messages.data.MessagesStore
import dev.rosty.messages.wa.GatewayWaRemote
import dev.rosty.messages.wa.StoreWaChats
import dev.rosty.messages.wa.WaChats
import dev.rosty.messages.wa.WaOffline
import dev.rosty.messages.wa.WaRemote
import dev.rosty.messages.wa.realWaOffline
import dev.rosty.messages.wa.status.StatusFeed
import dev.rosty.people.AvatarResolver
import dev.rosty.people.PeopleIndex
import dev.rosty.people.PeopleSources
import dev.rosty.people.ProviderSource
import dev.rosty.platform.contacts.AndroidContactsRepository
import dev.rosty.platform.contacts.ContactsRepository
import dev.rosty.platform.identity.PhoneNumbers
import dev.rosty.platform.sms.AndroidScheduledSmsRepository
import dev.rosty.platform.sms.AndroidSmsRepository
import dev.rosty.platform.sms.ScheduledSmsRepository
import dev.rosty.platform.sms.SmsRepository
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.platform.telephony.AndroidSimRepository
import dev.rosty.platform.telephony.SimRepository
import dev.rosty.shell.ShellContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    /** Every gateway call of the WhatsApp sync, outbox and Status (E6.4s); tests swap in a fake. */
    val waRemote: WaRemote by lazy { createWaRemote() }

    /** The offline store of the linked WhatsApp instance with its sync and outbox (E6.4s), following the link. */
    val waOffline: WaOffline by lazy { createWaOffline().also { it.start() } }

    /** The WhatsApp Status updates, for the Status row and the viewer. */
    val statusFeed: StatusFeed by lazy {
        StatusFeed(scope, waOffline, waRemote, java.io.File(context.cacheDir, "wa-status")).also { it.start() }
    }

    /** The WhatsApp chats of the inbox and their actions, read from the offline store. */
    val waChats: WaChats by lazy { createWaChats() }

    protected open fun createWaRemote(): WaRemote = GatewayWaRemote(wa)

    protected open fun createWaOffline(): WaOffline = realWaOffline(this, waRemote)

    protected open fun createWaChats(): WaChats = StoreWaChats(waOffline, waRemote)

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

    protected open fun createChat(): ChatOps = NativeChatOps(rust)

    protected open fun createSearch(): SearchOps = NativeSearchOps(rust)

    protected open fun createSharedOps(): SharedOps = NativeSharedOps(rust)

    protected open fun createQr(): QrOps = NativeQrOps(rust)
}
