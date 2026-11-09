package dev.rovsti.contacts

import android.content.Context
import dev.rovsti.contacts.card.CardWaPort
import dev.rovsti.contacts.card.RepositoryCardWaPort
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.data.WaMarks
import dev.rovsti.contacts.photo.GatewayWaPhotoPort
import dev.rovsti.contacts.photo.WaPhotoPort
import dev.rovsti.contacts.trash.ContactDeletion
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.ContactsWaOps
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativeContactsWaOps
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeQrOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.QrOps
import dev.rovsti.core.ops.SearchOps
import dev.rovsti.core.ops.SharedOps
import dev.rovsti.platform.calllog.AndroidCallLogRepository
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.contacts.AndroidContactsRepository
import dev.rovsti.platform.contacts.AndroidShortcutsRepository
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.ShortcutsRepository
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.shell.ShellContainer
import dev.rovsti.shell.settings.CommonSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val deletion: ContactDeletion by lazy { ContactDeletion(trash, ops, scope) }

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

    /** The list's WhatsApp marks, from the chats the instance already has. */
    val waMarks: WaMarks by lazy { WaMarks(wa, book.book, waCard, scope) }

    val qr: QrOps by lazy { createQr() }

    val shortcuts: ShortcutsRepository by lazy { createShortcuts() }

    /** Contacts keeps no appearance and no haptics preference yet (E4.15), so both rows are hidden. */
    val commonSettings: CommonSettings by lazy { NoCommonPreferences }

    protected open fun createWaPhotos(): WaPhotoPort = GatewayWaPhotoPort.over(wa)

    protected open fun createContacts(): ContactsRepository = AndroidContactsRepository(context, Dispatchers.IO)

    protected open fun createCallLog(): CallLogRepository = AndroidCallLogRepository(context, Dispatchers.IO)

    protected open fun createOps(): ContactsOps = NativeContactsOps(rust)

    protected open fun createPhoneOps(): PhoneOps = NativePhoneOps(rust)

    protected open fun createSharedOps(): SharedOps = NativeSharedOps(rust)

    protected open fun createCardWa(): CardWaPort = RepositoryCardWaPort(wa)

    protected open fun createWaCard(): ContactsWaOps = NativeContactsWaOps(rust)

    protected open fun createSearch(): SearchOps = NativeSearchOps(rust)

    protected open fun createQr(): QrOps = NativeQrOps(rust)

    protected open fun createShortcuts(): ShortcutsRepository = AndroidShortcutsRepository(context)
}

/**
 * [CommonSettings] of an app without either preference: both flows hold null (the rows and pages are hidden, see
 * `CommonSettings`) and the setters have nothing to store.
 */
internal object NoCommonPreferences : CommonSettings {
    override val appearance: StateFlow<Appearance?> = MutableStateFlow(null)
    override val haptics: StateFlow<HapticsLevel?> = MutableStateFlow(null)

    override fun setAppearance(value: Appearance) = Unit

    override fun setHaptics(value: HapticsLevel) = Unit
}
