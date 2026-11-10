package dev.rovsti.phone.details

import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.PhoneAccountInfo
import dev.rovsti.core.model.phone.TimeFormat
import dev.rovsti.core.ops.NativeChatOps
import dev.rovsti.core.ops.NativeQrOps
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.BlockRepo
import dev.rovsti.phone.data.CallPlacer
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.recents.RecentsRig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal val Ayse =
    PersonTarget(
        name = "Ayse Yilmaz",
        numbers =
            kotlinx.collections.immutable.persistentListOf(
                TargetNumber("+905551112233", "mobile"),
                TargetNumber("+902125550101", "work"),
            ),
        contactId = 1,
    )

internal val Stranger =
    PersonTarget(
        name = null,
        numbers = kotlinx.collections.immutable.persistentListOf(TargetNumber("+4930123456")),
    )

internal val Nobody =
    PersonTarget(
        name = "Nobody",
        numbers = kotlinx.collections.immutable.persistentListOf(TargetNumber("+900000000000")),
    )

/** The two details models over the REAL Rust core and the Recents rig's fakes. */
internal class DetailsRig(
    host: HostRust,
    private val scope: CoroutineScope,
    log: List<CallLogEntry> = RecentsRig.sampleLog(),
    accounts: List<PhoneAccountInfo> = listOf(dev.rovsti.phone.keypad.sim1),
) {
    val base = RecentsRig(host, scope, log, accounts)
    private val shared = NativeSharedOps(host.core)
    private val qr = NativeQrOps(host.core)
    private val chat = NativeChatOps(host.core)
    private val locale = LocaleTime(0, TimeFormat(hour12 = false, dateOrder = DateOrder.DayMonthYear))
    val block = BlockRepo(base.blocklist, base.ops, scope)

    suspend fun ready() = base.ready()

    fun person(target: PersonTarget = Ayse): PersonSheetViewModel =
        PersonSheetViewModel(
            target = target,
            recents = base.repo,
            callLog = base.callLog,
            ops = base.ops,
            shared = shared,
            qr = qr,
            placer = CallPlacer(base.ops, base.simState.sims, base.launcher),
            block = block,
            sims = base.simState.sims,
            store = base.store,
            clipboard = base.clipboard,
            homeCc = { "90" },
            locale = { locale },
            scope = scope,
            clock = { T0 + 60_000 },
            tzOffsetS = { 0 },
            compute = Dispatchers.Default,
        )

    fun call(target: CallDetailsTarget): CallDetailsViewModel =
        CallDetailsViewModel(
            target = target,
            recents = base.repo,
            callLog = base.callLog,
            ops = base.ops,
            chat = chat,
            store = base.store,
            clipboard = base.clipboard,
            reminders = base.reminders,
            sims = base.simState.sims,
            locale = { locale },
            scope = scope,
            clock = { T0 + 60_000 },
            tzOffsetS = { 0 },
            compute = Dispatchers.Default,
        )
}

internal suspend fun PersonSheetViewModel.loaded(
    check: (PersonUi) -> Boolean = {
        it.ready && it.history.isNotEmpty()
    },
): PersonUi = withTimeout(10_000) { state.first(check) }

internal suspend inline fun <reified E : PersonEffect> PersonSheetViewModel.nextEffect(): E =
    withTimeout(10_000) { effect.first { it is E } as E }

internal suspend fun CallDetailsViewModel.loaded(check: (CallDetailsUi) -> Boolean = { it.ready }): CallDetailsUi =
    withTimeout(10_000) { state.first(check) }

internal suspend inline fun <reified E : CallDetailsEffect> CallDetailsViewModel.nextEffect(): E =
    withTimeout(10_000) { effect.first { it is E } as E }
