package dev.rovsti.phone.recents

import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.core.model.phone.TimeFormat
import dev.rovsti.core.ops.NativeChatOps
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.BlockRepo
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.RecentsRepository
import dev.rovsti.phone.data.SimState
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.data.eventually
import dev.rovsti.phone.data.logEntry
import dev.rovsti.phone.data.tempDir
import dev.rovsti.phone.keypad.Launched
import dev.rovsti.phone.keypad.sim1
import dev.rovsti.platform.calllog.FakeBlocklistRepository
import dev.rovsti.platform.calllog.FakeCallLogRepository
import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.notify.ReminderItem
import dev.rovsti.platform.notify.RemindersRepository
import dev.rovsti.platform.telephony.FakeSimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** In-memory call-back reminders: what was armed. */
internal class FakeReminders : RemindersRepository<ReminderItem> {
    val armed = mutableListOf<String>()
    private val items = MutableStateFlow<List<ReminderItem>>(emptyList())

    // `ReminderItem.parse` reads org.json, which plain JVM tests only stub: the payload is kept as text.
    override fun arm(json: String): Boolean {
        armed += json
        return true
    }

    override fun disarm(id: Long): Boolean = true

    override fun armed(): Flow<List<ReminderItem>> = items

    override fun rearmAll(): Boolean = true
}

/** A Recents view model over the REAL Rust core, with fakes for Android. */
internal class RecentsRig(
    private val host: HostRust,
    private val scope: CoroutineScope,
    log: List<CallLogEntry> = sampleLog(),
    private val accounts: List<PhoneAccountInfo> = listOf(sim1),
    defaultKey: String? = null,
) {
    val ops = NativePhoneOps(host.core)
    val callLog = FakeCallLogRepository(log)
    val blocklist = FakeBlocklistRepository()
    val clipboard = FakeClipboardRepository()
    val launcher = Launched()
    val reminders = FakeReminders()

    /** The WhatsApp calls Recents merges in (what `WaCalls` would emit), and what the WhatsApp actions did. */
    val waExtras = MutableStateFlow<List<dev.rovsti.core.model.phone.ExtraCall>>(emptyList())
    val whatsAppPlaced = mutableListOf<String>()
    val whatsAppForgotten = mutableListOf<List<String>>()
    val whatsAppAvailable = MutableStateFlow(true)
    val store = PhoneStore(ops, scope, tempDir("rovsti-recents"), Dispatchers.IO, debounceMs = 20)
    val simState = SimState(FakeSimRepository(accounts, defaultKey), ops, scope)
    private val locale = LocaleTime(0, TimeFormat(hour12 = false, dateOrder = DateOrder.DayMonthYear))
    val repo =
        RecentsRepository(
            callLog = callLog,
            ops = ops,
            scope = scope,
            extras = waExtras,
            locale = { locale },
            clock = { T0 + 60_000 },
            lastSeenMs = { store.prefs.value.ui.missedSeenMs },
            windowMs = 5,
        ).also { it.start() }

    fun model(): RecentsViewModel =
        RecentsViewModel(
            recents = repo,
            callLog = callLog,
            ops = ops,
            shared = NativeSharedOps(host.core),
            chat = NativeChatOps(host.core),
            placer = CallPlacer(ops, simState.sims, launcher),
            block = BlockRepo(blocklist, ops, scope),
            sims = simState.sims,
            store = store,
            clipboard = clipboard,
            reminders = reminders,
            homeCc = { "90" },
            clock = { T0 + 60_000 },
            tzOffsetS = { 0 },
            compute = Dispatchers.Default,
            placeWhatsApp = { whatsAppPlaced += it },
            forgetWhatsApp = { whatsAppForgotten += it },
            whatsAppAvailable = whatsAppAvailable,
        )

    suspend fun ready() {
        store.load()
        eventually("sims built") { simState.sims.value.accounts.accounts.size == accounts.size }
    }

    companion object {
        /** Two missed calls of Ayse (one row), an outgoing call to Mert, an unknown caller and a WhatsApp-free log. */
        fun sampleLog(): List<CallLogEntry> =
            listOf(
                logEntry(1, "+905551112233", CallLogKind.Missed, ago = 5, name = "Ayse Yilmaz", contactId = 1),
                logEntry(2, "+905551112233", CallLogKind.Missed, ago = 20, name = "Ayse Yilmaz", contactId = 1),
                logEntry(3, "+905559998877", CallLogKind.Outgoing, ago = 60, name = "Mert Kaya", contactId = 2),
                logEntry(4, "+4930123456", CallLogKind.Incoming, ago = 300),
                logEntry(5, "+905553334455", CallLogKind.Incoming, ago = 1_500, name = "Deniz Aksoy", contactId = 3),
            )
    }
}

/** The rows the model shows, header items left out. */
internal val RecentsUi.rows: List<RecentsRowUi> get() = items.filterIsInstance<RecentsItem.Row>().map { it.row }

/** Waits for a state that satisfies [check]; the test collects [RecentsViewModel.state] so the flow runs. */
internal suspend fun RecentsViewModel.loaded(
    check: (RecentsUi) -> Boolean = { it.ready && it.rowCount > 0 },
): RecentsUi = withTimeout(10_000) { state.first(check) }

internal suspend inline fun <reified E : RecentsEffect> RecentsViewModel.nextEffect(): E =
    withTimeout(10_000) { effect.first { it is E } as E }
