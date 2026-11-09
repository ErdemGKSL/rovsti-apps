package dev.rovsti.wa

import dev.rovsti.core.Envelope
import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustEvents
import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.HandleAnswer
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.LinkReadAnswer
import dev.rovsti.core.model.wa.LinkWriteRequest
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.model.wa.RichMessage
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.model.wa.WaEventPayload
import dev.rovsti.core.model.wa.WaStreamEvent
import dev.rovsti.core.ops.NativeWaOps
import dev.rovsti.core.ops.WaEvents
import dev.rovsti.core.ops.WaGateway
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted gateway for repository and view-model tests, built on [FakeRustCore]: the real [WaGateway], [WaEvents]
 * and [dev.rovsti.wa.WaLinkStore] run against it, so only the Rust side is faked.
 *
 * It answers the ops the repository uses (`wa.open`, `wa.status`, `wa.chats_with_meta`, `wa.messages_with_meta`,
 * `wa.events_start`, `wa.events_reconnect_now`, `wa.link_read`, `wa.link_write`). Every other op is unscripted and
 * answers `Unsupported` unless a test calls `core.on(...)`. [link] is the content of `whatsapp-link.json`.
 */
class FakeWaGateway(
    val core: FakeRustCore = FakeRustCore(),
    link: StoredLink? = null,
) {
    /** The event feed the repository under test listens to; feed it with [emit] and [emitEvent]. */
    val rustEvents = RustEvents()
    val waEvents = WaEvents(rustEvents)
    val ops = NativeWaOps(core)

    /** The stored link; `wa.link_read` answers it, `wa.link_write` replaces it. */
    @Volatile var link: StoredLink? = link

    @Volatile var status: Outcome<InstanceStatus> = Outcome.Err(RustError.Network("no status scripted"))

    @Volatile var chats: List<RichChat> = emptyList()

    @Volatile var messages: Map<String, List<RichMessage>> = emptyMap()

    /** Handles the fake gave out and the repository later released. */
    val released = CopyOnWriteArrayList<Long>()

    /** Makes `wa.open` fail with this error when set. */
    @Volatile var openError: RustError? = null

    val opener =
        WaGatewayOpener { WaGateway.open(it.gatewayUrl, it.key, null, ops, release = { id -> released += id }) }

    init {
        ok("wa.open", HandleAnswer.serializer()) {
            openError?.let { return@ok Outcome.Err(it) }
                ?: Outcome.Ok(HandleAnswer(GATEWAY))
        }
        ok("wa.events_start", HandleAnswer.serializer()) { Outcome.Ok(HandleAnswer(STREAM)) }
        ok("wa.events_reconnect_now", Empty.serializer()) { Outcome.Ok(Empty) }
        ok("wa.status", InstanceStatus.serializer()) { status }
        ok("wa.chats_with_meta", ListSerializer(RichChat.serializer())) { Outcome.Ok(chats) }
        core.on("wa.messages_with_meta") { request ->
            val chat =
                (request as? JsonObject)
                    ?.get("chat")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            Outcome.Ok(
                Envelope.json.encodeToJsonElement(ListSerializer(RichMessage.serializer()), messages[chat].orEmpty()),
            )
        }
        ok("wa.link_read", LinkReadAnswer.serializer()) { Outcome.Ok(LinkReadAnswer(this.link)) }
        core.on("wa.link_write") { request ->
            val req = Envelope.json.decodeFromJsonElement(LinkWriteRequest.serializer(), request)
            this.link = req.link
            Outcome.Ok(Envelope.json.encodeToJsonElement(Empty.serializer(), Empty))
        }
    }

    /** Delivers a `wa.event` of [instance], as the native sink would. */
    fun emit(
        instance: String,
        event: WaStreamEvent,
    ) {
        val json = Envelope.json.encodeToString(WaEventPayload.serializer(), WaEventPayload(instance, event))
        rustEvents.onEvent(WaEvents.EVENT, json.encodeToByteArray())
    }

    /** Delivers one gateway [event] with sequence number [seq]. */
    fun emitEvent(
        instance: String,
        seq: Long,
        event: GatewayEvent,
    ) = emit(instance, WaStreamEvent.Event(seq, seq * 1_000, event))

    /** How often [op] was called. */
    fun count(op: String): Int = core.calls.count { it.op == op }

    private fun <T> ok(
        op: String,
        ser: KSerializer<T>,
        answer: () -> Outcome<T>,
    ) = core.on(op) {
        when (val out = answer()) {
            is Outcome.Ok -> Outcome.Ok(Envelope.json.encodeToJsonElement(ser, out.value))
            is Outcome.Err -> out
        }
    }

    companion object {
        const val GATEWAY = 7L
        const val STREAM = 8L
    }
}
