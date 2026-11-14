package dev.rosty.phone.keypad

import dev.rosty.core.model.contacts.ContactDraft
import dev.rosty.core.model.contacts.DraftField
import dev.rosty.core.model.phone.CallLogEntry
import dev.rosty.core.model.phone.CallLogKind
import dev.rosty.core.model.phone.PhoneAccountInfo
import dev.rosty.core.ops.NativePhoneOps
import dev.rosty.core.ops.NativeSearchOps
import dev.rosty.phone.HostRust
import dev.rosty.phone.data.CallLauncher
import dev.rosty.phone.data.CallPlacer
import dev.rosty.phone.data.PeopleIndex
import dev.rosty.phone.data.PhoneStore
import dev.rosty.phone.data.SimState
import dev.rosty.phone.data.SpeedDial
import dev.rosty.phone.data.eventually
import dev.rosty.phone.data.logEntry
import dev.rosty.phone.data.tempDir
import dev.rosty.platform.contacts.FakeContactsRepository
import dev.rosty.platform.files.FakeClipboardRepository
import dev.rosty.platform.system.permissions.PermissionState
import dev.rosty.platform.telephony.FakeSimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Records the calls the Keypad places. */
internal class Launched : CallLauncher {
    val placed = mutableListOf<Pair<String, String?>>()

    override fun place(
        number: String,
        accountKey: String?,
    ): CallLauncher.Launch {
        placed += number to accountKey
        return CallLauncher.Launch.PLACED
    }
}

internal val sim1 = PhoneAccountInfo("tel/svc#1", "Turkcell", slotIndex = 0, sim = true)
internal val sim2 = PhoneAccountInfo("tel/svc#2", "Vodafone", slotIndex = 1, sim = true)

internal fun person(
    id: Long,
    given: String,
    family: String,
    number: String,
) = ContactDraft(id = id, given = given, family = family, numbers = listOf(DraftField(number)))

internal val book =
    listOf(
        person(1, "Ayse", "Yilmaz", "+90 555 111 22 33"),
        person(2, "Mert", "Kaya", "+90 555 999 88 77"),
        person(3, "Deniz", "Aksoy", "+90 555 333 44 55"),
    )

/** A Keypad view model over the REAL Rust core, with fakes for Android. */
internal class KeypadRig(
    host: HostRust,
    scope: CoroutineScope,
    accounts: List<PhoneAccountInfo> = listOf(sim1),
    defaultKey: String? = null,
    contacts: List<ContactDraft> = book,
) {
    val ops = NativePhoneOps(host.core)
    val launcher = Launched()
    val clipboard = FakeClipboardRepository()
    val log = mutableListOf<CallLogEntry>()
    var voicemail: String? = null
    val store = PhoneStore(ops, scope, tempDir("rosty-keypad"), Dispatchers.IO, debounceMs = 20)
    val speedDial = SpeedDial(store, ops)
    val people =
        PeopleIndex(
            contacts = FakeContactsRepository(contacts),
            permission = MutableStateFlow<PermissionState>(PermissionState.Granted),
            search = NativeSearchOps(host.core),
            ops = ops,
            scope = scope,
        )
    private val simState = SimState(FakeSimRepository(accounts, defaultKey), ops, scope)

    fun model(): KeypadViewModel =
        KeypadViewModel(
            ops = ops,
            people = people,
            placer = CallPlacer(ops, simState.sims, launcher),
            speedDial = speedDial,
            sims = simState.sims,
            clipboard = clipboard,
            redialEntries = { log },
            voicemailNumber = { voicemail },
            toneSetting = { null },
            systemTones = { true },
            e164 = { typed -> if (typed.startsWith("+")) typed else "+90" + typed.trimStart('0') },
            debounceMs = 0,
        )

    suspend fun ready() {
        store.load()
        eventually("index built") { people.index.value != null }
        eventually("sims built") { simState.sims.value.accounts.accounts.size == accounts.size }
    }

    private val accounts = accounts

    fun outgoing(number: String) {
        log += logEntry(log.size + 1L, number, CallLogKind.Outgoing, ago = log.size.toLong())
    }

    suspend inline fun <reified E : KeypadEffect> KeypadViewModel.next(): E =
        withTimeout(10_000) { effect.first { it is E } as E }
}

internal fun KeypadViewModel.text(): String = field.text.toString()

internal fun KeypadViewModel.type(digits: String) = digits.forEach { onAction(KeypadAction.Digit(it)) }
