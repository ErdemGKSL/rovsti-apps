package dev.rovsti.wa

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.model.wa.RichMessage
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.ops.WaGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [WaRepository] for view-model tests: no gateway, no Rust. Everything the tests drive goes through the
 * `set*`, `emit` and `log` members; the rest is the interface, and `WaRepositoryShapeTest` keeps the two in step.
 */
class FakeWaRepository(
    initial: WaLinkState = WaLinkState.None,
) : WaRepository {
    private val state = MutableStateFlow(initial)
    private val bus = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    private val chatList = MutableStateFlow<List<RichChat>>(emptyList())
    private val messageLists = MutableStateFlow<Map<String, List<RichMessage>>>(emptyMap())

    /** The calls made, in order: `start`, `wake`, `relinkTo`, `forget`. */
    val log = mutableListOf<String>()

    /** The link given to the last [relinkTo]. */
    var linked: StoredLink? = null
        private set

    override val linkState: StateFlow<WaLinkState> = state.asStateFlow()

    private val settledState = MutableStateFlow(true)
    override val settled: StateFlow<Boolean> = settledState.asStateFlow()

    /** Whether the stored link has been read ([WaRepository.settled]); true by default. */
    fun setSettled(value: Boolean) {
        settledState.value = value
    }

    override val events: SharedFlow<GatewayEvent> = bus.asSharedFlow()

    override fun start() {
        log += "start"
    }

    /** Runs after each [relinkTo], so a demo can emit the QR or code the gateway would send. */
    var onRelinkTo: (StoredLink) -> Unit = {}

    /** What [gateway] opens; tests give it the fake gateway of the link they script. */
    var gatewayOpener: suspend () -> WaGateway? = { null }

    override suspend fun gateway(): WaGateway? = gatewayOpener()

    override suspend fun wake(): Outcome<Unit> {
        log += "wake"
        return Outcome.Ok(Unit)
    }

    override fun chats(): Flow<List<RichChat>> = chatList

    override fun messages(jid: String): Flow<List<RichMessage>> = messageLists.map { it[jid].orEmpty() }

    override suspend fun relinkTo(link: StoredLink): Outcome<Unit> {
        log += "relinkTo"
        linked = link
        onRelinkTo(link)
        return Outcome.Ok(Unit)
    }

    override suspend fun forget(): Outcome<Unit> {
        log += "forget"
        linked = null
        state.value = WaLinkState.None
        return Outcome.Ok(Unit)
    }

    fun setState(value: WaLinkState) {
        state.value = value
    }

    fun setChats(value: List<RichChat>) {
        chatList.value = value
    }

    fun setMessages(
        jid: String,
        value: List<RichMessage>,
    ) {
        messageLists.value = messageLists.value + (jid to value)
    }

    fun emit(event: GatewayEvent) {
        bus.tryEmit(event)
    }
}
