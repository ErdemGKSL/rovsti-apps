package dev.rovsti.phone.favorites

import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativePhoneOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.contacts.PhoneContactsViewModel
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.PeopleIndex
import dev.rovsti.phone.data.PhoneStore
import dev.rovsti.phone.data.SimState
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.data.eventually
import dev.rovsti.phone.data.tempDir
import dev.rovsti.phone.keypad.Launched
import dev.rovsti.phone.keypad.sim1
import dev.rovsti.platform.calllog.FakeCallLogRepository
import dev.rovsti.platform.contacts.ContactsRepository
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.telephony.FakeSimRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

/** The provider with lookup keys `k-<id>` and sort keys, like `ContactsContract`. */
internal class KeyedContacts(
    val inner: FakeContactsRepository,
) : ContactsRepository by inner {
    override fun all() =
        inner.all().map { list ->
            list.map(::key).sortedWith(compareBy({ !it.displayName.first().isLetter() }, { it.sortKey }))
        }

    override fun withNumbers() = all().map { list -> list.filter { it.numbers.isNotEmpty() } }

    private fun key(c: WireContact): WireContact {
        val alt = c.displayName.substringAfterLast(' ') + " " + c.displayName.substringBeforeLast(' ')
        return c.copy(
            lookupKey = "k-${c.id}",
            sortKey = c.displayName.uppercase(),
            displayNameAlt = alt,
            sortKeyAlt = alt.uppercase(),
        )
    }
}

/** The people of the Favorites and Contacts tests and the call log that makes some of them frequent. */
internal object PeopleSample {
    private fun person(
        id: Long,
        given: String,
        family: String,
        number: String,
        starred: Boolean = false,
    ) = ContactDraft(
        id = id,
        given = given,
        family = family,
        numbers = listOf(DraftField(number)),
        starred = starred,
    )

    fun drafts(): List<ContactDraft> =
        listOf(
            person(1, "Ayse", "Yilmaz", "+90 555 111 22 33", starred = true),
            person(2, "Mert", "Kaya", "+90 555 999 88 77"),
            person(3, "Deniz", "Aksoy", "+90 555 333 44 55", starred = true),
            person(4, "Elif", "Sahin", "+90 555 444 55 66"),
            person(5, "Burak", "Ozturk", "+90 555 555 66 77", starred = true),
            person(6, "Can", "Demir", "+90 555 666 77 88"),
            person(7, "Zeynep", "Celik", "+90 555 777 88 99", starred = true),
            person(8, "Ada", "Lovelace", "+90 555 888 99 00"),
            person(9, "Kerem", "Aydin", "+90 555 123 45 67"),
            person(10, "Naz", "Polat", "+90 555 234 56 78"),
            person(11, "Selin", "Arslan", "+90 555 345 67 89"),
            person(12, "Ozan", "Tekin", "+90 555 456 78 90"),
            person(13, "Aylin", "Bulut", "+90 555 567 89 01"),
            person(14, "Baris", "Cetin", "+90 555 678 90 12"),
            person(15, "Cem", "Yildiz", "+90 555 789 01 23"),
            person(16, "Defne", "Kurt", "+90 555 890 12 34"),
            person(17, "Emre", "Toprak", "+90 555 901 23 45"),
            person(18, "Fatma", "Gunes", "+90 555 012 34 56"),
            person(19, "Gizem", "Aslan", "+90 555 121 21 21"),
            person(20, "Hakan", "Eren", "+90 555 232 32 32"),
            person(21, "Irem", "Kaplan", "+90 555 343 43 43"),
            person(22, "Jale", "Tas", "+90 555 454 54 54"),
            person(23, "Leyla", "Uysal", "+90 555 565 65 65"),
            person(24, "Murat", "Dogan", "+90 555 676 76 76"),
            person(25, "Nehir", "Acar", "+90 555 787 87 87"),
        )

    /** Three calls to Mert (2), two to Can (6) and one to Ayse (1, a favorite); Mert and Can are frequent. */
    fun calls(): List<CallLogEntry> {
        var id = 0L

        fun call(
            number: String,
            hoursAgo: Long,
        ) = CallLogEntry(
            ++id,
            number,
            number.filter { it.isDigit() },
            CallLogKind.Outgoing,
            T0 - hoursAgo * 3_600_000L,
            durationS = 60,
        )
        return listOf(
            call("+90 555 999 88 77", 2),
            call("+90 555 999 88 77", 30),
            call("+90 555 999 88 77", 60),
            call("+90 555 666 77 88", 5),
            call("+90 555 666 77 88", 50),
            call("+90 555 111 22 33", 3),
        )
    }
}

/** The two tab models on the REAL Rust core with the in-memory provider and call log. */
internal class PeopleRig(
    val host: HostRust,
    val scope: CoroutineScope,
    drafts: List<ContactDraft> = PeopleSample.drafts(),
    permission: PermissionState = PermissionState.Granted,
    callPermission: PermissionState = PermissionState.Granted,
    accounts: List<PhoneAccountInfo> = listOf(sim1),
    defaultKey: String? = null,
) {
    val ops = NativePhoneOps(host.core)
    val contactsOps = NativeContactsOps(host.core)
    val fake = FakeContactsRepository(drafts)
    val repo = KeyedContacts(fake)
    val contactsPermission = MutableStateFlow(permission)
    val callLogPermission = MutableStateFlow(callPermission)
    val callLog = FakeCallLogRepository(PeopleSample.calls())
    val launcher = Launched()
    val dir = tempDir("rovsti-people")
    val store = PhoneStore(ops, scope, dir, Dispatchers.IO, debounceMs = 20)
    val simState = SimState(FakeSimRepository(accounts, defaultKey), ops, scope)
    val people =
        PeopleIndex(
            contacts = repo,
            permission = contactsPermission,
            search = NativeSearchOps(host.core),
            ops = ops,
            scope = scope,
        )

    private val sims = accounts.size

    suspend fun ready() {
        store.load()
        eventually("sims built") { simState.sims.value.accounts.accounts.size == sims }
        if (contactsPermission.value ==
            PermissionState.Granted
        ) {
            eventually("index built") { people.index.value != null }
        }
    }

    fun favorites() =
        FavoritesViewModel(
            people = people,
            store = store,
            contactsOps = contactsOps,
            phoneOps = ops,
            contacts = repo,
            callLog = callLog,
            callLogPermission = callLogPermission,
            placer = CallPlacer(ops, simState.sims, launcher),
            clock = { T0 },
        )

    fun contacts() = PhoneContactsViewModel(people, store, contactsOps)

    /** A second store on the same directory: what a restart would read. */
    fun reloaded() = PhoneStore(ops, scope, dir, Dispatchers.IO, debounceMs = 20)
}

internal suspend fun FavoritesViewModel.where(check: (FavoritesUi) -> Boolean): FavoritesUi =
    withTimeout(10_000) { state.first(check) }

internal suspend fun FavoritesViewModel.ready(): FavoritesUi =
    where { it.phase == FavoritesPhase.Ready && it.favorites.isNotEmpty() }
