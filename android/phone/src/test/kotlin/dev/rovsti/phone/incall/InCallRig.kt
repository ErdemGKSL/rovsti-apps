package dev.rovsti.phone.incall

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.core.model.phone.AudioRoute
import dev.rovsti.core.model.phone.AudioRow
import dev.rovsti.core.model.phone.AudioRowsAnswer
import dev.rovsti.core.model.phone.AudioState
import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.Endpoint
import dev.rovsti.core.model.phone.EndpointType
import dev.rovsti.core.model.phone.RouteDecision
import dev.rovsti.core.model.phone.RouteDiff
import dev.rovsti.core.model.phone.RowKind
import dev.rovsti.core.model.phone.ToneStep
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.phone.PhoneRoots
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.PhoneTabs
import dev.rovsti.phone.RecentsTab
import dev.rovsti.phone.chromeFor
import dev.rovsti.phone.data.BlockUndo
import dev.rovsti.phone.data.NumberDisplay
import dev.rovsti.phone.data.Sims
import dev.rovsti.phone.incoming.AuraMode
import dev.rovsti.phone.incoming.FakeSource
import dev.rovsti.phone.phoneTabItems
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.telephony.CallsApi
import dev.rovsti.shell.RovstiShell
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

internal const val NOW = 1_700_000_000_000L
internal const val NUMBER = "+905559990000"

internal val inCallStrings get() = InCallStrings(ApplicationProvider.getApplicationContext<Context>().resources)

internal fun liveCall(
    id: Int = 2,
    state: WireCallState = WireCallState.Active,
    name: String? = "Mert Kaya",
    number: String = NUMBER,
    contactId: Long? = 9,
    connectedAgoS: Long = 272,
    incoming: Boolean = false,
    account: String? = null,
    canHold: Boolean = true,
) = WireCall(
    id = id,
    state = state,
    numberRaw = number,
    displayName = name,
    contactId = contactId,
    connectTimeMs = if (connectedAgoS >= 0) NOW - connectedAgoS * 1000 else 0,
    startedMs = NOW - (connectedAgoS.coerceAtLeast(0) + 8) * 1000,
    canHold = canHold,
    isIncoming = incoming,
    accountKey = account,
)

internal val earpiece = Endpoint("e1", EndpointType.Earpiece, selected = true)
internal val speaker = Endpoint("s1", EndpointType.Speaker)
internal val headset = Endpoint("b1", EndpointType.Bluetooth, name = "Pixel Buds Pro")

internal fun liveSnapshot(
    call: WireCall = liveCall(),
    muted: Boolean = false,
    route: AudioRoute = AudioRoute.Earpiece,
    endpoints: List<Endpoint> = listOf(earpiece, speaker),
) = CallSnapshot(
    calls = listOf(call),
    foregroundCallId = call.id,
    keyguardLocked = false,
    audio = AudioState(muted = muted, route = route, endpoints = endpoints),
)

/** The core's route rows, written by hand: phone and speaker, and a Bluetooth headset when [bluetooth]. */
internal open class FakeInCallBackend(
    var bluetooth: Boolean = true,
    var pasteLabel: String? = null,
    var plan: List<ToneStep> = emptyList(),
) : InCallBackend {
    override suspend fun elapsed(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    override suspend fun audioRows(
        endpoints: List<Endpoint>,
        previous: List<Endpoint>?,
    ): AudioRowsAnswer? {
        val rows =
            endpoints.map {
                AudioRow(
                    id = it.id,
                    kind =
                        when (it.kind) {
                            EndpointType.Bluetooth -> RowKind.Bluetooth
                            EndpointType.Speaker -> RowKind.Speaker
                            else -> RowKind.Phone
                        },
                    label = it.name ?: if (it.kind == EndpointType.Speaker) "Speaker" else "Phone",
                    subtitle = "",
                    selected = it.selected,
                    unavailable = false,
                    ghost = false,
                    hearingAid = false,
                    nameless = it.name == null,
                )
            }
        return AudioRowsAnswer(
            rows = rows,
            decision = if (rows.size > 2) RouteDecision.OpensList else RouteDecision.Toggle,
            anyNameless = false,
            diff = RouteDiff(emptyList(), emptyList(), null),
        )
    }

    override suspend fun pasteLabel(clip: String?): String? = pasteLabel

    override suspend fun tonePlan(text: String): List<ToneStep> = plan
}

internal class FakeNotes : InCallNotes {
    val saved = mutableMapOf<Pair<String, Long>, String>()
    var accept = true

    override fun current(
        number: String,
        startedMs: Long,
    ) = saved[number to startedMs].orEmpty()

    override suspend fun save(
        number: String,
        startedMs: Long,
        text: String,
    ): Boolean {
        if (!accept) return false
        if (text.isBlank()) saved.remove(number to startedMs) else saved[number to startedMs] = text
        return true
    }
}

internal class FakeBlocker(
    var can: Boolean = true,
) : InCallBlocker {
    val blocked = mutableListOf<String>()
    val undone = mutableListOf<BlockUndo>()

    override suspend fun canBlock(number: String) = can

    override suspend fun block(number: String): BlockUndo? {
        blocked += number
        return BlockUndo(unblock = listOf(number))
    }

    override suspend fun undo(undo: BlockUndo): Boolean {
        undone += undo
        return true
    }
}

internal class FakeClipboard(
    var clip: String? = null,
) : ClipboardRepository {
    val copies = mutableListOf<Pair<String, Boolean>>()

    override fun copy(
        text: String,
        sensitive: Boolean,
    ): Boolean {
        copies += text to sensitive
        return true
    }

    override fun primaryText(): String? = clip

    override fun changes(): Flow<Unit> = emptyFlow()
}

/** Remembers every `CallsApi` command the screen sends. */
internal class InCallCalls : CallsApi {
    val log = mutableListOf<String>()
    var accept = true

    override fun snapshot() = "{}"

    override fun answer(callId: Int) = record("answer $callId")

    override fun reject(callId: Int) = record("reject $callId")

    override fun disconnect(callId: Int) = record("disconnect $callId")

    override fun hold(
        callId: Int,
        on: Boolean,
    ) = record("hold $callId $on")

    override fun mute(on: Boolean) = record("mute $on")

    override fun route(name: String) = record("route $name")

    override fun routeEndpoint(id: String) = record("endpoint $id")

    override fun dtmf(digit: Char) = record("dtmf $digit")

    override fun dtmfStop() = record("dtmfStop")

    override fun answerReplacing(callId: Int) = record("answerReplacing $callId")

    private fun record(line: String): Boolean {
        log += line
        return accept
    }
}

/** A model over fakes on a test scope; effects are collected as they happen. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class InCallHarness(
    first: CallSnapshot = liveSnapshot(),
    sims: Sims = Sims(),
    bluetooth: Boolean = true,
    val backend: FakeInCallBackend = FakeInCallBackend(),
    val tones: ToneMemory = ToneMemory(),
    display: NumberDisplay = NumberDisplay.AsIs,
) {
    val testScope = TestScope(UnconfinedTestDispatcher())
    val source = FakeSource(first)
    val calls = InCallCalls()
    val notes = FakeNotes()
    val blocker = FakeBlocker()
    val clipboard = FakeClipboard()
    val grant = MutableStateFlow(bluetooth)
    val effects = mutableListOf<InCallEffect>()
    val model: InCallViewModel =
        InCallViewModel(
            source = source,
            calls = calls,
            backend = backend,
            notes = notes,
            blocker = blocker,
            clipboard = clipboard,
            tones = tones,
            display = display,
            sims = MutableStateFlow(sims),
            bluetoothGranted = grant,
            unknownLabel = "Unknown",
            scope = testScope.backgroundScope,
            clock = { NOW },
        )

    init {
        testScope.backgroundScope.launch { model.effect.collect { effects += it } }
    }

    val ui get() = model.state.value

    /** Lets virtual time pass (tones, the ended dwell). */
    fun advance(ms: Long) {
        testScope.testScheduler.advanceTimeBy(ms)
        testScope.testScheduler.runCurrent()
    }
}

/** What the harness of a screenshot or a UI test records. */
internal class InCallShotHarness(
    ui: InCallUi,
    timer: String = "4:32",
) {
    val overlays = OverlayController()
    val actions = mutableListOf<InCallAction>()
    val state = mutableStateOf(ui)
    val timerText = mutableStateOf(timer)
    var ui: InCallUi
        get() = state.value
        set(value) {
            state.value = value
        }
}

/** The In-Call page on the real shell, the way the app routes it: Recents below, In-Call pushed, no tab bar. */
@Composable
internal fun HostedInCall(
    harness: InCallShotHarness,
    aura: AuraMode = AuraMode.Shot,
    onAudio: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onTimerMenu: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    photo: Any? = null,
) {
    val navigator =
        remember {
            TabNavigator(PhoneTabs, PhoneRoots).also {
                it.select(RecentsTab)
                it.push(PhoneRoute.InCall)
            }
        }
    val strings = inCallStrings
    val timer: State<String> = harness.timerText
    RovstiShell(
        navigator = navigator,
        tabs = phoneTabItems("Favorites", "Recents", "Contacts", "Keypad"),
        chromeFor = { route -> chromeFor(route) },
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<PhoneRoute.Recents> {}
        entry<PhoneRoute.Favorites> {}
        entry<PhoneRoute.Contacts> {}
        entry<PhoneRoute.Keypad> {}
        entry<PhoneRoute.InCall> {
            Box(Modifier.fillMaxSize()) {
                InCallContent(
                    ui = harness.state.value,
                    strings = strings,
                    timer = timer,
                    onAction = { harness.actions += it },
                    onAudio = onAudio,
                    onTimerMenu = onTimerMenu,
                    onCollapse = {},
                    aura = aura,
                    photo = photo,
                )
            }
        }
    }
}
