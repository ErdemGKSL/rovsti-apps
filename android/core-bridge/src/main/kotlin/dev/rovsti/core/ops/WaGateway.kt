package dev.rovsti.core.ops

import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.RustError
import dev.rovsti.core.RustEvent
import dev.rovsti.core.RustEvents
import dev.rovsti.core.RustHandle
import dev.rovsti.core.RustNative
import dev.rovsti.core.RustThreading
import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.wa.BytesAnswer
import dev.rovsti.core.model.wa.CallStarted
import dev.rovsti.core.model.wa.CallSummary
import dev.rovsti.core.model.wa.ChatAction
import dev.rovsti.core.model.wa.ChatState
import dev.rovsti.core.model.wa.ChatSummary
import dev.rovsti.core.model.wa.CreateInstance
import dev.rovsti.core.model.wa.CreatedInstance
import dev.rovsti.core.model.wa.DeleteForMe
import dev.rovsti.core.model.wa.EditMessage
import dev.rovsti.core.model.wa.GroupInfo
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.IssuedKey
import dev.rovsti.core.model.wa.KeyInfo
import dev.rovsti.core.model.wa.LinkRequest
import dev.rovsti.core.model.wa.MarkRead
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.core.model.wa.PushRegistration
import dev.rovsti.core.model.wa.RevokeMessage
import dev.rovsti.core.model.wa.RichChat
import dev.rovsti.core.model.wa.RichMessage
import dev.rovsti.core.model.wa.ScheduleText
import dev.rovsti.core.model.wa.ScheduledText
import dev.rovsti.core.model.wa.SendMediaBody
import dev.rovsti.core.model.wa.SendMediaWithBody
import dev.rovsti.core.model.wa.SendReaction
import dev.rovsti.core.model.wa.SendText
import dev.rovsti.core.model.wa.Sent
import dev.rovsti.core.model.wa.StarMessage
import dev.rovsti.core.model.wa.StartEventsBody
import dev.rovsti.core.model.wa.StatusList
import dev.rovsti.core.model.wa.WaMessage
import dev.rovsti.core.model.wa.WaOpenRequest
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.core.model.wa.WaUser

/**
 * An open WhatsApp gateway (a native `Gateway` for one URL and connect key). Open it with [open] when the link is
 * known, keep it for the life of the service or screen and [close] it (a closed gateway answers
 * [RustError.NotFound] without a native call). Every method runs on the Rust dispatcher, never on the main thread.
 * Open the long-lived pieces from it: [startEvents] and [openCallAudio].
 */
class WaGateway internal constructor(
    id: Long,
    private val ops: WaOps,
    private val audio: AudioPort,
    private val release: (Long) -> Unit,
) : RustHandle(id, release) {
    private suspend fun <T> guarded(call: suspend () -> Outcome<T>): Outcome<T> =
        if (released) Outcome.Err(RustError.NotFound("gateway $id is closed")) else call()

    /** Creates an instance and starts linking; needs no key, the answer carries the first connect key. */
    suspend fun createInstance(req: CreateInstance): Outcome<CreatedInstance> = guarded { ops.createInstance(id, req) }

    /** The instance: its link state and own number. */
    suspend fun status(): Outcome<InstanceStatus> = guarded { ops.status(id) }

    /** Starts linking again after the account logged out. */
    suspend fun relink(link: LinkRequest): Outcome<Empty> = guarded { ops.relink(id, link) }

    /** Deletes the instance on the gateway. */
    suspend fun deleteInstance(): Outcome<Empty> = guarded { ops.deleteInstance(id) }

    /** Mints a connect key for another app; the plain key is only in this answer. */
    suspend fun mintKey(label: String): Outcome<IssuedKey> = guarded { ops.mintKey(id, label) }

    /** The connect keys of the instance. */
    suspend fun keys(): Outcome<List<KeyInfo>> = guarded { ops.keys(id) }

    /** Revokes a connect key. */
    suspend fun revokeKey(keyId: String): Outcome<Empty> = guarded { ops.revokeKey(id, keyId) }

    /** Registers the FCM token the gateway wakes this app with. */
    suspend fun registerPush(req: PushRegistration): Outcome<Empty> = guarded { ops.registerPush(id, req) }

    /** The chats, newest first. */
    suspend fun chats(): Outcome<List<ChatSummary>> = guarded { ops.chats(id) }

    /** The chats with picture id, push name, archive, pin and mute state. */
    suspend fun chatsWithMeta(): Outcome<List<RichChat>> = guarded { ops.chatsWithMeta(id) }

    /** A page of a chat, newest first; [beforeMs] pages back. */
    suspend fun messages(
        chat: String,
        beforeMs: Long? = null,
        limit: Int? = null,
    ): Outcome<List<WaMessage>> = guarded { ops.messages(id, chat, beforeMs, limit) }

    /** Like [messages], with reply, ticks, edit, media details. */
    suspend fun messagesWithMeta(
        chat: String,
        beforeMs: Long? = null,
        limit: Int? = null,
    ): Outcome<List<RichMessage>> = guarded { ops.messagesWithMeta(id, chat, beforeMs, limit) }

    /** Sends a text, optionally as a reply. */
    suspend fun sendText(req: SendText): Outcome<Sent> = guarded { ops.sendText(id, req) }

    /** Has the gateway send a text at a time. */
    suspend fun scheduleText(req: ScheduleText): Outcome<ScheduledText> = guarded { ops.scheduleText(id, req) }

    /** Pending scheduled texts plus those finished in the last 7 days. */
    suspend fun scheduled(): Outcome<List<ScheduledText>> = guarded { ops.scheduled(id) }

    /** Cancels a pending scheduled text. */
    suspend fun cancelScheduled(scheduledId: String): Outcome<Empty> = guarded { ops.cancelScheduled(id, scheduledId) }

    /** Sends a photo, video, voice note, document or sticker. */
    suspend fun sendMedia(body: SendMediaBody): Outcome<Sent> = guarded { ops.sendMedia(id, body) }

    /** Like [sendMedia], as a reply or with a file name, duration or voice-note flag. */
    suspend fun sendMediaWith(body: SendMediaWithBody): Outcome<Sent> = guarded { ops.sendMediaWith(id, body) }

    /** Downloads the media of a message (large ones as a temp file). */
    suspend fun media(messageId: String): Outcome<BytesAnswer> = guarded { ops.media(id, messageId) }

    /** The preview image that came with a message. */
    suspend fun thumbnail(messageId: String): Outcome<BytesAnswer> = guarded { ops.thumbnail(id, messageId) }

    /** Reacts to a message. */
    suspend fun react(req: SendReaction): Outcome<Empty> = guarded { ops.react(id, req) }

    /** Read receipts. */
    suspend fun markRead(req: MarkRead): Outcome<Empty> = guarded { ops.markRead(id, req) }

    /** Played receipts for voice and video notes. */
    suspend fun markPlayed(req: MarkRead): Outcome<Empty> = guarded { ops.markPlayed(id, req) }

    /** Whether this account shows as online. */
    suspend fun setPresence(available: Boolean): Outcome<Empty> = guarded { ops.setPresence(id, available) }

    /** Starts presence updates for a contact. */
    suspend fun subscribePresence(jid: String): Outcome<Empty> = guarded { ops.subscribePresence(id, jid) }

    /** Typing and recording indicators. */
    suspend fun chatState(req: ChatState): Outcome<Empty> = guarded { ops.chatState(id, req) }

    /** The groups. */
    suspend fun groups(): Outcome<List<GroupInfo>> = guarded { ops.groups(id) }

    /** One group with members and description. */
    suspend fun group(jid: String): Outcome<GroupInfo> = guarded { ops.group(id, jid) }

    /** Which of these user JIDs are on WhatsApp (the gateway limits how many). */
    suspend fun lookupUsers(jids: List<String>): Outcome<List<WaUser>> = guarded { ops.lookupUsers(id, jids) }

    /** Names, about text and picture ids from the gateway's store. */
    suspend fun profiles(jids: List<String>): Outcome<List<WaProfile>> = guarded { ops.profiles(id, jids) }

    /** Like [profiles], asking WhatsApp even for fresh entries. */
    suspend fun refreshProfiles(jids: List<String>): Outcome<List<WaProfile>> =
        guarded { ops.refreshProfiles(id, jids) }

    /** A profile picture, unchanged, or none. */
    suspend fun picture(body: PictureBody): Outcome<PictureAnswer> = guarded { ops.picture(id, body) }

    /** The status updates (stories), mine and everyone else's. */
    suspend fun statuses(): Outcome<StatusList> = guarded { ops.statuses(id) }

    /** The photo or video of a status update. */
    suspend fun statusMedia(statusId: String): Outcome<BytesAnswer> = guarded { ops.statusMedia(id, statusId) }

    /** The preview image of a status update. */
    suspend fun statusThumbnail(statusId: String): Outcome<BytesAnswer> = guarded { ops.statusThumbnail(id, statusId) }

    /** Marks status updates seen. */
    suspend fun markStatusViewed(ids: List<String>): Outcome<Empty> = guarded { ops.markStatusViewed(id, ids) }

    /** Edits a message I sent (within 15 minutes). */
    suspend fun editMessage(req: EditMessage): Outcome<Empty> = guarded { ops.editMessage(id, req) }

    /** Deletes a message for everyone. */
    suspend fun revokeMessage(req: RevokeMessage): Outcome<Empty> = guarded { ops.revokeMessage(id, req) }

    /** Deletes a message on this account's devices. */
    suspend fun deleteForMe(req: DeleteForMe): Outcome<Empty> = guarded { ops.deleteForMe(id, req) }

    /** Stars or unstars a message. */
    suspend fun starMessage(req: StarMessage): Outcome<Empty> = guarded { ops.starMessage(id, req) }

    /** Archives, pins, mutes or marks a chat on every device. */
    suspend fun chatAction(req: ChatAction): Outcome<Empty> = guarded { ops.chatAction(id, req) }

    /** Places a WhatsApp call. */
    suspend fun placeCall(to: String): Outcome<CallStarted> = guarded { ops.placeCall(id, to) }

    /** The recent calls, newest first. */
    suspend fun calls(): Outcome<List<CallSummary>> = guarded { ops.calls(id) }

    /** Answers an incoming call. */
    suspend fun acceptCall(callId: String): Outcome<Empty> = guarded { ops.acceptCall(id, callId) }

    /** Declines an incoming call. */
    suspend fun rejectCall(callId: String): Outcome<Empty> = guarded { ops.rejectCall(id, callId) }

    /** Hangs up. */
    suspend fun endCall(callId: String): Outcome<Empty> = guarded { ops.endCall(id, callId) }

    /** Mutes or unmutes the microphone of a call. */
    suspend fun setMuted(
        callId: String,
        muted: Boolean,
    ): Outcome<Empty> = guarded { ops.setMuted(id, callId, muted) }

    /**
     * Opens the event stream. Events arrive as `wa.event` on [Rust.events]; read them with [WaEvents]. [instance] is
     * echoed in each one, [after] resumes after that `seq`. Close the stream when the service ends.
     */
    suspend fun startEvents(
        instance: String,
        after: Long? = null,
    ): Outcome<WaEventStream> =
        guarded {
            when (val started = ops.eventsStart(id, StartEventsBody(instance, after))) {
                is Outcome.Err -> started
                is Outcome.Ok -> Outcome.Ok(WaEventStream(started.value.handle, ops, release))
            }
        }

    /** Opens the audio socket of a call. Close it when the call ends. */
    suspend fun openCallAudio(callId: String): Outcome<CallAudio> =
        guarded {
            when (val opened = ops.callAudioOpen(id, callId)) {
                is Outcome.Err -> opened
                is Outcome.Ok -> Outcome.Ok(CallAudio(opened.value.handle, audio, release))
            }
        }

    companion object {
        /** Opens a gateway; [key] is null only to create an instance. The handle is the caller's to close. */
        suspend fun open(
            url: String,
            key: String? = null,
            cacheDir: String? = null,
            ops: WaOps = NativeWaOps(),
            audio: AudioPort = AudioPort.Native,
            release: (Long) -> Unit = RustNative::release,
        ): Outcome<WaGateway> =
            when (val opened = ops.open(WaOpenRequest(url, key, cacheDir))) {
                is Outcome.Err -> opened
                is Outcome.Ok -> Outcome.Ok(WaGateway(opened.value.handle, ops, audio, release))
            }
    }
}

/** A running event stream; closing it stops the socket. */
class WaEventStream internal constructor(
    id: Long,
    private val ops: WaOps,
    release: (Long) -> Unit,
) : RustHandle(id, release) {
    /** Skips the reconnect backoff (a push said the gateway has news). Has no effect while connected. */
    suspend fun reconnectNow(): Outcome<Empty> =
        if (released) Outcome.Err(RustError.NotFound("event stream $id is closed")) else ops.eventsReconnectNow(id)
}

/** Reads and writes PCM for an open call audio handle; the real one is `RustNative.audioPush` / `audioPull`. */
interface AudioPort {
    fun push(
        handle: Long,
        pcm: ByteArray,
    )

    fun pull(
        handle: Long,
        out: ByteArray,
    ): Int

    companion object {
        val Native: AudioPort =
            object : AudioPort {
                override fun push(
                    handle: Long,
                    pcm: ByteArray,
                ) = RustNative.audioPush(handle, pcm)

                override fun pull(
                    handle: Long,
                    out: ByteArray,
                ) = RustNative.audioPull(handle, out)
            }
    }
}

/**
 * The audio of one WhatsApp call: frames of exactly [FRAME_BYTES] (960 little-endian 16-bit samples, 60 ms at
 * 16 kHz, mono) in both directions. Call [push] and [pull] from the audio thread, never the main thread.
 */
class CallAudio internal constructor(
    id: Long,
    private val port: AudioPort,
    release: (Long) -> Unit,
) : RustHandle(id, release) {
    /** Sends one microphone frame. Any other size is dropped by Rust; so is a frame while the queue is full. */
    fun push(pcm: ByteArray) {
        if (released) return
        RustThreading.assertNotMain()
        port.push(id, pcm)
    }

    /**
     * Fills [out] (at least [FRAME_BYTES]) with the next frame of the peer's voice. Answers [FRAME_BYTES], [NO_FRAME]
     * when none is ready yet (play silence) or [ENDED] when the call is over.
     */
    fun pull(out: ByteArray): Int {
        if (released) return ENDED
        RustThreading.assertNotMain()
        return port.pull(id, out)
    }

    companion object {
        const val FRAME_BYTES = 1920
        const val NO_FRAME = 0
        const val ENDED = -1
    }
}

/** What a [WaEvents] feed delivers. */
sealed interface WaFeed {
    /** One thing the stream of [instance] reported. */
    data class Stream(
        val instance: String,
        val event: dev.rovsti.core.model.wa.WaStreamEvent,
    ) : WaFeed

    /**
     * Events were dropped because this app fell behind (the sink's buffer overflowed). Reload what you derived from
     * events: chats, the open chat, calls. A gateway `resync` event arrives as a normal [Stream] item.
     */
    data object Resync : WaFeed

    /** A `wa.event` that does not decode (a newer gateway); [message] says why. */
    data class Undecodable(
        val message: String,
    ) : WaFeed
}

/** The `wa.event` feed over the process-wide [RustEvents]. */
class WaEvents(
    private val source: RustEvents = Rust.events,
) {
    /** The events of [instance] (all instances when null), in the order the socket delivered them. */
    fun feed(instance: String? = null): kotlinx.coroutines.flow.Flow<WaFeed> =
        kotlinx.coroutines.flow.flow {
            source.events.collect { event ->
                when (event) {
                    RustEvent.Resync -> {
                        emit(WaFeed.Resync)
                    }

                    is RustEvent.Message -> {
                        if (event.kind == EVENT) decode(event, instance)?.let { emit(it) }
                    }
                }
            }
        }

    private fun decode(
        event: RustEvent.Message,
        instance: String?,
    ): WaFeed? =
        try {
            val payload =
                dev.rovsti.core.Envelope.json.decodeFromString(
                    dev.rovsti.core.model.wa.WaEventPayload
                        .serializer(),
                    event.json.decodeToString(),
                )
            if (instance == null || payload.instance == instance) {
                WaFeed.Stream(payload.instance, payload.event)
            } else {
                null
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            WaFeed.Undecodable(e.message.orEmpty())
        }

    companion object {
        const val EVENT = "wa.event"
    }
}
