package dev.rovsti.wa

import dev.rovsti.core.Envelope
import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustEvents
import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.wa.AvatarsPlanAnswer
import dev.rovsti.core.model.wa.AvatarsPlanRequest
import dev.rovsti.core.model.wa.CallIdBody
import dev.rovsti.core.model.wa.CallMuteBody
import dev.rovsti.core.model.wa.CallStarted
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.HandleAnswer
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.JidsBody
import dev.rovsti.core.model.wa.LinkReadAnswer
import dev.rovsti.core.model.wa.LinkWriteRequest
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.model.wa.RichMessage
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.model.wa.WaEventPayload
import dev.rovsti.core.model.wa.WaNamesAnswer
import dev.rovsti.core.model.wa.WaNamesRequest
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.core.model.wa.WaStreamEvent
import dev.rovsti.core.ops.AudioPort
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
    /** Where the PCM of `openCallAudio` handles goes; the native port would need the library. */
    val audio: AudioPort = SilentAudioPort,
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

    /** The recent calls `wa.calls` answers. */
    @Volatile var calls: List<CallSummary> = emptyList()

    /** The call id `wa.place_call` answers, or an error. */
    @Volatile var placed: Outcome<CallStarted> = Outcome.Ok(CallStarted("call-1"))

    /** Answers of the call ops by name (`wa.accept_call`, ...); default `Ok`. */
    val callErrors = java.util.concurrent.ConcurrentHashMap<String, RustError>()

    /** What `wa.picture` answers for a request; the default is `none`. */
    @Volatile var picture: (PictureBody) -> Outcome<PictureAnswer> = { Outcome.Ok(PictureAnswer("none")) }

    /** What `wa.profiles` answers for the asked jids. */
    @Volatile var profiles: (List<String>) -> Outcome<List<WaProfile>> = { Outcome.Ok(emptyList()) }

    val opener =
        WaGatewayOpener { WaGateway.open(it.gatewayUrl, it.key, null, ops, audio, release = { id -> released += id }) }

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
        ok("wa.place_call", CallStarted.serializer()) { placed }
        ok("wa.calls", ListSerializer(CallSummary.serializer())) { Outcome.Ok(calls) }
        for (op in listOf("wa.accept_call", "wa.reject_call", "wa.end_call")) {
            core.on(op) { callAnswer(op) }
        }
        core.on("wa.set_muted") { request ->
            // The body must decode: a wrong shape is the test's bug, not a silent pass.
            Envelope.json.decodeFromJsonElement(CallMuteBody.serializer(), request.withoutHandle())
            callAnswer("wa.set_muted")
        }
        ok("wa.call_audio_open", HandleAnswer.serializer()) { Outcome.Ok(HandleAnswer(AUDIO)) }
        handled("wa.picture", PictureBody.serializer(), PictureAnswer.serializer()) { picture(it) }
        handled("wa.profiles", JidsBody.serializer(), ListSerializer(WaProfile.serializer())) { profiles(it.jids) }
        core.onTyped("wa.avatars_plan", AvatarsPlanRequest.serializer(), AvatarsPlanAnswer.serializer()) {
            Outcome.Ok(FakeAvatarsPlan.answer(it))
        }
        core.onTyped("wa.names", WaNamesRequest.serializer(), WaNamesAnswer.serializer()) {
            Outcome.Ok(
                WaNamesAnswer(
                    it.contact ?: it.waName ?: it.jid.substringBefore('@'),
                    "+" + it.jid.substringBefore('@'),
                ),
            )
        }
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

    /** A gateway op: the request carries `handle` next to the body fields; the body is decoded without it. */
    private fun <Req, Ans> handled(
        op: String,
        reqSer: KSerializer<Req>,
        ansSer: KSerializer<Ans>,
        answer: (Req) -> Outcome<Ans>,
    ) = core.on(op) { request ->
        val req = Envelope.json.decodeFromJsonElement(reqSer, request.withoutHandle())
        when (val out = answer(req)) {
            is Outcome.Ok -> Outcome.Ok(Envelope.json.encodeToJsonElement(ansSer, out.value))
            is Outcome.Err -> out
        }
    }

    private fun callAnswer(op: String): Outcome<kotlinx.serialization.json.JsonElement> =
        callErrors[op]?.let { Outcome.Err(it) }
            ?: Outcome.Ok(Envelope.json.encodeToJsonElement(Empty.serializer(), Empty))

    private fun kotlinx.serialization.json.JsonElement.withoutHandle() =
        JsonObject((this as JsonObject).filterKeys { it != "handle" })

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
        const val AUDIO = 9L
    }
}

/** Swallows the PCM: the pump threads of a test run against [FakeCallFrames] instead. */
object SilentAudioPort : AudioPort {
    override fun push(
        handle: Long,
        pcm: ByteArray,
    ) = Unit

    override fun pull(
        handle: Long,
        out: ByteArray,
    ): Int = 0
}
