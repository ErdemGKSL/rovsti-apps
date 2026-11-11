package dev.rovsti.phone

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.TelephonyManager
import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.getOrNull
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.TelUriRequest
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.core.model.shared.InferHomeCcRequest
import dev.rovsti.core.model.shared.NumberForm
import dev.rovsti.core.model.shared.RenderRequest
import dev.rovsti.core.ops.ChatOps
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.NativeChatOps
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeQrOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.core.ops.QrOps
import dev.rovsti.core.ops.SearchOps
import dev.rovsti.core.ops.SharedOps
import dev.rovsti.phone.data.AndroidCallLauncher
import dev.rovsti.phone.data.BlockRepo
import dev.rovsti.phone.data.CallLauncher
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.DeviceLocaleTime
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.People
import dev.rovsti.phone.data.PeopleIndex
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.RecentsRepository
import dev.rovsti.phone.data.SimState
import dev.rovsti.phone.data.SpeedDial
import dev.rovsti.phone.details.CallDetailsTarget
import dev.rovsti.phone.details.CallDetailsViewModel
import dev.rovsti.phone.details.PersonSheetViewModel
import dev.rovsti.phone.details.PersonTarget
import dev.rovsti.phone.incall.CoreInCallBackend
import dev.rovsti.phone.incall.InCallBackend
import dev.rovsti.phone.incall.InCallViewModel
import dev.rovsti.phone.incall.RepoInCallBlocker
import dev.rovsti.phone.incall.StoreInCallNotes
import dev.rovsti.phone.incall.ToneMemory
import dev.rovsti.phone.incoming.CallSource
import dev.rovsti.phone.incoming.CoreIncomingBackend
import dev.rovsti.phone.incoming.IncomingBackend
import dev.rovsti.phone.incoming.IncomingViewModel
import dev.rovsti.phone.incoming.RegistryCallSource
import dev.rovsti.phone.setup.AndroidSetupFacts
import dev.rovsti.phone.setup.InMemorySetupMemory
import dev.rovsti.phone.setup.SetupFactsSource
import dev.rovsti.phone.setup.SetupMemory
import dev.rovsti.phone.wa.CoreWaArbiter
import dev.rovsti.phone.wa.PrefsForgottenCalls
import dev.rovsti.phone.wa.WaCallLauncher
import dev.rovsti.phone.wa.WaCallsRouter
import dev.rovsti.phone.wa.WaIdBook
import dev.rovsti.phone.wa.WaMergedCallSource
import dev.rovsti.phone.wa.WaNotices
import dev.rovsti.phone.wa.WaRingNotifier
import dev.rovsti.platform.calllog.AndroidBlocklistRepository
import dev.rovsti.platform.calllog.AndroidCallLogRepository
import dev.rovsti.platform.calllog.BlocklistRepository
import dev.rovsti.platform.calllog.CallLogRepository
import dev.rovsti.platform.contacts.AndroidContactsRepository
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.notify.ReminderItem
import dev.rovsti.platform.notify.ReminderRepositories
import dev.rovsti.platform.notify.RemindersRepository
import dev.rovsti.platform.system.SettingsMirror
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.telephony.AndroidSimRepository
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.platform.telephony.CallsApi
import dev.rovsti.platform.telephony.SimRepository
import dev.rovsti.platform.wa.WaIncoming
import dev.rovsti.shell.ShellContainer
import dev.rovsti.wa.avatars.WaAvatar
import dev.rovsti.wa.calls.AndroidCallAudioPump
import dev.rovsti.wa.calls.CallAudioPump
import dev.rovsti.wa.calls.ForgottenCalls
import dev.rovsti.wa.calls.WaCallActions
import dev.rovsti.wa.calls.WaCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A number the Keypad is asked to show: a `tel:` link decoded by `dial.fromTelUri`, or a WhatsApp hand-off. */
data class DialRequest(
    val text: String,
    /** The number came from a sibling app's WhatsApp hand-off. */
    val fromHandoff: Boolean = false,
    /** Makes two requests for the same number two values, so the Keypad takes the second one too. */
    val serial: Long = 0,
)

/**
 * What Rovsti Phone adds to the shared [ShellContainer] (ARCH 5.3). Every member is lazy and built by a `create*`
 * seam, so a cold path (the `InCallService`, a notification receiver) pays for what it touches and tests swap in fakes
 * without a native library.
 */
open class PhoneContainer(
    context: Context,
) : ShellContainer(context, AppPermissions.phone) {
    /**
     * The commands of the call screens: the registry's (`CallRegistry`, the process-wide singleton the `InCallService`
     * feeds), with the WhatsApp call's commands routed to [waCalls] (AD-WA5) once the calls are merged ([callSource]).
     */
    val calls: CallsApi by lazy {
        val registry = registryCalls
        (callSource as? WaMergedCallSource)?.let { WaCallsRouter(registry, waCalls, it, waNotices, scope) } ?: registry
    }

    /** The cellular calls alone. */
    val registryCalls: CallsApi by lazy { createCalls() }

    /** Where WhatsApp calls tell the user something (microphone missing, the gateway refused). */
    val waNotices: WaNotices = WaNotices()

    /** The ids of WhatsApp calls inside the merged call snapshot. */
    val waIds: WaIdBook = WaIdBook()

    /** Places WhatsApp calls: the Recents row menu, the person sheets, the keypad's call menu, a sibling app's hand-off. */
    val waLauncher: WaCallLauncher by lazy {
        WaCallLauncher(
            waCalls = waCalls,
            link = wa.linkState,
            ops = ops,
            busy = {
                callSource.snapshot.value.calls
                    .any { it.state != WireCallState.Unknown }
            },
            e164 = ::e164,
            notices = waNotices,
            scope = scope,
        )
    }

    /** Answer and Decline tapped on the WhatsApp call notification: once per call, after the core is ready (rule b). */
    val waActions: WaCallActions by lazy { WaCallActions(waCalls, ready = ::awaitRustReady, scope = scope) }

    val callLog: CallLogRepository by lazy { createCallLog() }

    val blocklist: BlocklistRepository by lazy { createBlocklist() }

    val contacts: ContactsRepository by lazy { createContacts() }

    /** The call-capable SIMs and the system default. */
    val sims: SimRepository by lazy { createSims() }

    /** Every phone decision the Rust core makes (ARCH 4). */
    val ops: PhoneOps by lazy { createOps() }

    /** The contact index and T9 search. */
    val search: SearchOps by lazy { createSearch() }

    val qr: QrOps by lazy { createQr() }

    /** The contact list view (sections, favorites order and moves) of the Favorites and Contacts tabs. */
    val contactsOps: ContactsOps by lazy { createContactsOps() }

    /** The photo of a caller for the call screens' background: the contacts photo, or a WhatsApp profile picture. */
    val callPhotos: dev.rovsti.phone.incoming.CallPhotos by lazy {
        object : dev.rovsti.phone.incoming.CallPhotos {
            override fun photoOf(contactId: Long) =
                contacts.all().map { list -> list.firstOrNull { it.id == contactId }?.photoUri }

            override fun photoOfJid(jid: String) =
                kotlinx.coroutines.flow.flow {
                    avatars.start()
                    avatars.want(jid)
                    emitAll(
                        avatars.revision
                            .map { if (avatars.file(jid) != null) WaAvatar(jid) else null }
                            .distinctUntilChanged(),
                    )
                }
        }
    }

    /** The call-back reminders (`call_reminder.*`, `message_reminder.slots`). */
    val chat: ChatOps by lazy { createChat() }

    /** The armed call-back reminders and their alarms. */
    val callReminders: RemindersRepository<ReminderItem> by lazy { createCallReminders() }

    /** The number formats (`format.render`, `format.infer_home_cc`) for "Copy as +E.164". */
    val sharedOps: SharedOps by lazy { createSharedOps() }

    /** The WhatsApp calls of the linked instance; started with the container (the merged call source and Recents read it). */
    val waCalls: WaCalls by lazy { createWaCalls() }

    /** The preferences (`prefs.json`) and the call notes; loaded by the first screen that needs them. */
    val store: PhoneStore by lazy { createStore() }

    /** The call log and the WhatsApp calls as Recents rows; started by the Recents screen. */
    val recents: RecentsRepository by lazy { createRecents() }

    /** The contacts with a number, their search index and T9; follows READ_CONTACTS. */
    val people: PeopleIndex by lazy { createPeople() }

    /** The SIMs as chips and the "ask every time" state. */
    val simState: SimState by lazy { SimState(sims, ops, scope) }

    /** The system blocklist with the core's rules and Undo snapshots. */
    val block: BlockRepo by lazy { BlockRepo(blocklist, ops, scope) }

    val speedDial: SpeedDial by lazy { SpeedDial(store, ops) }

    /** Validation, the SIM choice and the call itself (`dial.validate`, `TelecomManager.placeCall`). */
    val placer: CallPlacer by lazy { CallPlacer(ops, simState.sims, createCallLauncher()) }

    /** What Android says about the Setup checklist rows. */
    val setupFacts: SetupFactsSource by lazy { createSetupFacts() }

    /** The Xiaomi confirmations and the "already offered" flag. In memory until `PhoneStore` (Step 2) persists them. */
    val setupMemory: SetupMemory by lazy { createSetupMemory() }

    /** A number for the Keypad from a `tel:` link or a hand-off; the Keypad consumes it (sets it back to null). */
    val pendingDial: MutableStateFlow<DialRequest?> = MutableStateFlow(null)

    /** The JID of a WhatsApp call handed over by a sibling app; the app places the call and clears it. */
    val pendingHandoff: MutableStateFlow<String?> = MutableStateFlow(null)

    /** The payload of an Answer or Decline tapped on a WhatsApp call notification, until [waActions] has taken it. */
    val pendingWaCallAction: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Whether the keyguard is up, as the activity last read it (resume, unlock, screen off, a call). The merged call
     * snapshot uses it for WhatsApp calls: the registry's snapshot only refreshes the keyguard while a cellular call runs.
     */
    val keyguardLocked: MutableStateFlow<Boolean> by lazy { MutableStateFlow(readKeyguardLocked()) }

    private var dialSerial = 0L

    /** The wall clock in ms; one seam for the data layer. */
    protected open val clock: () -> Long = System::currentTimeMillis

    /** Sets [text] as the next [pendingDial]. */
    fun offerDial(
        text: String,
        fromHandoff: Boolean = false,
    ) {
        pendingDial.value = DialRequest(text, fromHandoff, ++dialSerial)
    }

    /** Decodes a `tel:` link with `dial.fromTelUri` and offers the number to the Keypad (an empty link is an empty field). */
    fun openTelUri(uri: String) {
        scope.launch {
            ops
                .fromTelUri(TelUriRequest(uri))
                .getOrNull()
                ?.let { offerDial(it.text) }
        }
    }

    /** What the device says about local time now (the time zone and 12/24 h can change while the app runs). */
    fun localeNow(): LocaleTime = DeviceLocaleTime.now(context, clock())

    /** The calls as the screens read them (the registry's last snapshot); lazy so a cold path pays nothing. */
    val callSource: CallSource by lazy { createCallSource() }

    /** The reply choices and the reminder slots of the Incoming screen (the Rust core). */
    val incomingBackend: IncomingBackend by lazy { createIncomingBackend() }

    /** Numbers as people read them (`+90 555 111 22 33`), shared by the call screens. */
    val numberDisplay: NumberDisplay by lazy { NumberDisplay(sharedOps, ::homeCc) }

    /** Set by the app while the Incoming model lives: a power or volume key press; true when it was consumed. */
    @Volatile
    var ringKey: (() -> Boolean)? = null

    /** The model of the Incoming screen and the waiting-call card (E5.6); [scope] is the app's, [unknown] its word. */
    open fun incomingModel(
        scope: CoroutineScope,
        unknown: String,
    ): IncomingViewModel =
        IncomingViewModel(
            source = callSource,
            calls = calls,
            backend = incomingBackend,
            reminders = callReminders,
            display = numberDisplay,
            sims = simState.sims,
            savedReplies = { store.prefs.value.quickReplies },
            unknownLabel = unknown,
            scope = scope,
            clock = clock,
        )

    /** The reasons the In-Call screen asks the core (words, route rows, tone plans). */
    val inCallBackend: InCallBackend by lazy { createInCallBackend() }

    /** The tones pasted per number, offered again when that number next connects (U51); lives as long as the process. */
    val toneMemory: ToneMemory = ToneMemory()

    /** The model of the single-call In-Call screen (E5.7a); [scope] is the app's, [unknown] its word. */
    open fun inCallModel(
        scope: CoroutineScope,
        unknown: String,
        conference: String = "Conference call",
    ): InCallViewModel =
        InCallViewModel(
            source = callSource,
            calls = calls,
            backend = inCallBackend,
            notes = StoreInCallNotes(store, ops, clock),
            blocker = RepoInCallBlocker(block),
            clipboard = clipboard,
            tones = toneMemory,
            display = numberDisplay,
            sims = simState.sims,
            bluetoothGranted =
                permissions
                    .state(PermissionGroup.BLUETOOTH)
                    .map { it is PermissionState.Granted }
                    .stateIn(
                        scope,
                        SharingStarted.Eagerly,
                        permissions.state(PermissionGroup.BLUETOOTH).value is PermissionState.Granted,
                    ),
            unknownLabel = unknown,
            scope = scope,
            clock = clock,
            placer = placer,
            conferenceLabel = conference,
        )

    /** The model of one Contact / Number sheet (E5.4); [scope] belongs to the sheet and ends with it. */
    open fun personModel(
        target: PersonTarget,
        scope: CoroutineScope,
    ): PersonSheetViewModel =
        PersonSheetViewModel(
            target = target,
            recents = recents,
            callLog = callLog,
            ops = ops,
            shared = sharedOps,
            qr = qr,
            placer = placer,
            block = block,
            sims = simState.sims,
            store = store,
            clipboard = clipboard,
            homeCc = ::homeCc,
            locale = ::localeNow,
            placeWhatsApp = waLauncher::place,
            whatsAppAvailable = waLauncher.available,
            scope = scope,
            clock = clock,
        )

    /** The model of one Call details sheet (E5.4). */
    open fun callModel(
        target: CallDetailsTarget,
        scope: CoroutineScope,
    ): CallDetailsViewModel =
        CallDetailsViewModel(
            target = target,
            recents = recents,
            callLog = callLog,
            ops = ops,
            chat = chat,
            store = store,
            clipboard = clipboard,
            reminders = callReminders,
            sims = simState.sims,
            locale = ::localeNow,
            scope = scope,
            clock = clock,
        )

    protected open fun createCalls(): CallsApi = CallRegistry

    protected open fun createCallSource(): CallSource =
        WaMergedCallSource(
            base = RegistryCallSource(registryCalls, CallRegistry.state, scope),
            waCalls = waCalls.calls,
            muted = waCalls.mutedCalls,
            speaker = waCalls.speakerOn,
            arbiter = CoreWaArbiter(ops),
            ids = waIds,
            // A cellular call rings: the WhatsApp offer is declined, once per call (the core's RejectWaBusy).
            onBusy = { id -> scope.launch { waCalls.reject(id) } },
            scope = scope,
            clock = clock,
            keyguardLocked = keyguardLocked,
        )

    /** `KeyguardManager.isKeyguardLocked`; locked when it cannot be read (slide to answer is the safe side). */
    protected open fun readKeyguardLocked(): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false

    /** Waits until the Rust core is initialised: nothing WhatsApp-related may touch it before (start-up crash, rule b). */
    protected open suspend fun awaitRustReady() {
        rust
        Rust.ready.first { it }
    }

    protected open fun createIncomingBackend(): IncomingBackend = CoreIncomingBackend(ops, chat)

    protected open fun createInCallBackend(): InCallBackend = CoreInCallBackend(ops)

    protected open fun createCallLog(): CallLogRepository = AndroidCallLogRepository(context, Dispatchers.IO)

    protected open fun createBlocklist(): BlocklistRepository = AndroidBlocklistRepository(context, Dispatchers.IO)

    protected open fun createContacts(): ContactsRepository = AndroidContactsRepository(context, Dispatchers.IO)

    protected open fun createSims(): SimRepository = AndroidSimRepository(context, Dispatchers.IO)

    protected open fun createOps(): PhoneOps = NativePhoneOps(rust)

    protected open fun createSearch(): SearchOps = NativeSearchOps(rust)

    protected open fun createQr(): QrOps = NativeQrOps(rust)

    protected open fun createContactsOps(): ContactsOps = NativeContactsOps(rust)

    protected open fun createChat(): ChatOps = NativeChatOps(rust)

    protected open fun createCallReminders(): RemindersRepository<ReminderItem> = ReminderRepositories.calls(context)

    protected open fun createSharedOps(): SharedOps = NativeSharedOps(rust)

    /** The voicemail number the SIM reports, null when it has none or may not be read. */
    @SuppressLint("MissingPermission")
    open fun voicemailNumber(): String? =
        try {
            context.getSystemService(TelephonyManager::class.java)?.voiceMailNumber
        } catch (_: SecurityException) {
            null
        }

    /** The system's "dial pad tones" setting (default on). */
    open fun systemKeypadTones(): Boolean = SettingsMirror.systemKeypadTones(context)

    /** The calling code of home ("90"), guessed from the contacts' own numbers; null when there is nothing to guess from. */
    open suspend fun homeCc(): String? {
        val numbers =
            (people.people.value as? People.Ready)?.contacts?.flatMap { c -> c.numbers.map { it.raw } }.orEmpty()
        return (sharedOps.inferHomeCc(InferHomeCcRequest(numbers.take(HOME_CC_SAMPLE))) as? Outcome.Ok)?.value?.homeCc
    }

    /** [typed] in `+` form, null when it has none. The home country comes from the contacts' own numbers. */
    open suspend fun e164(typed: String): String? {
        val numbers =
            (people.people.value as? People.Ready)?.contacts?.flatMap { c -> c.numbers.map { it.raw } }.orEmpty()
        val home =
            (
                sharedOps.inferHomeCc(
                    InferHomeCcRequest(numbers.take(HOME_CC_SAMPLE)),
                ) as? Outcome.Ok
            )?.value?.homeCc
        return (sharedOps.render(RenderRequest(typed, NumberForm.E164, home)) as? Outcome.Ok)?.value?.text
    }

    protected open fun createCallPump(): CallAudioPump = AndroidCallAudioPump(context)

    protected open fun createWaCalls(): WaCalls =
        WaCalls(
            wa,
            permissions.state(PermissionGroup.MICROPHONE),
            createCallPump(),
            scope,
            forgotten = createForgottenCalls(),
            // Answered, declined or over: the ringing notification goes, and a late push cannot post it again.
            ringOver = { id -> WaIncoming.cancel(context, id) },
        ).also {
            it.start()
            WaRingNotifier(it.calls, scope, clock) { id, title -> WaIncoming.show(context, id, title) }.start()
        }

    /** The deleted WhatsApp calls, kept across restarts. */
    protected open fun createForgottenCalls(): ForgottenCalls = PrefsForgottenCalls(context)

    protected open fun createStore(): PhoneStore = PhoneStore(ops, scope)

    protected open fun createRecents(): RecentsRepository =
        RecentsRepository(
            callLog = callLog,
            ops = ops,
            scope = scope,
            clock = clock,
            extras = waCalls.calls.map { RecentsRepository.extraCalls(it) },
            locale = { DeviceLocaleTime.now(context, clock()) },
            lastSeenMs = { store.prefs.value.ui.missedSeenMs },
        ).also { it.start() }

    protected open fun createPeople(): PeopleIndex =
        PeopleIndex(
            contacts = contacts,
            permission = permissions.state(PermissionGroup.CONTACTS),
            search = search,
            ops = ops,
            scope = scope,
            nameFormat = { store.prefs.value.settings.nameFormat },
        )

    protected open fun createCallLauncher(): CallLauncher = AndroidCallLauncher(context)

    protected open fun createSetupFacts(): SetupFactsSource = AndroidSetupFacts(context, permissions, specialAccess)

    protected open fun createSetupMemory(): SetupMemory = InMemorySetupMemory()
}

/** How many contact numbers the home-country guess reads. */
private const val HOME_CC_SAMPLE = 200
