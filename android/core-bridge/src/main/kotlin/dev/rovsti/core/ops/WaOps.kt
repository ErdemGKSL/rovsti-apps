package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.wa.AvatarsPlanAnswer
import dev.rovsti.core.model.wa.AvatarsPlanRequest
import dev.rovsti.core.model.wa.BytesAnswer
import dev.rovsti.core.model.wa.CallIdBody
import dev.rovsti.core.model.wa.CallMuteBody
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
import dev.rovsti.core.model.wa.HandleAnswer
import dev.rovsti.core.model.wa.IdBody
import dev.rovsti.core.model.wa.IdsBody
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.IssuedKey
import dev.rovsti.core.model.wa.JidBody
import dev.rovsti.core.model.wa.JidsBody
import dev.rovsti.core.model.wa.KeyIdBody
import dev.rovsti.core.model.wa.KeyInfo
import dev.rovsti.core.model.wa.LabelBody
import dev.rovsti.core.model.wa.LinkReadAnswer
import dev.rovsti.core.model.wa.LinkReadRequest
import dev.rovsti.core.model.wa.LinkRequest
import dev.rovsti.core.model.wa.LinkWriteRequest
import dev.rovsti.core.model.wa.MarkRead
import dev.rovsti.core.model.wa.MessageIdBody
import dev.rovsti.core.model.wa.MessagesBody
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import dev.rovsti.core.model.wa.PlaceCallBody
import dev.rovsti.core.model.wa.PresenceBody
import dev.rovsti.core.model.wa.PushParseAnswer
import dev.rovsti.core.model.wa.PushParseRequest
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
import dev.rovsti.core.model.wa.SettingsApplyAnswer
import dev.rovsti.core.model.wa.SettingsApplyRequest
import dev.rovsti.core.model.wa.StarMessage
import dev.rovsti.core.model.wa.StartEventsBody
import dev.rovsti.core.model.wa.StatusList
import dev.rovsti.core.model.wa.WaMessage
import dev.rovsti.core.model.wa.WaNamesAnswer
import dev.rovsti.core.model.wa.WaNamesRequest
import dev.rovsti.core.model.wa.WaOpenRequest
import dev.rovsti.core.model.wa.WaProfile
import dev.rovsti.core.model.wa.WaTargetAnswer
import dev.rovsti.core.model.wa.WaTargetRequest
import dev.rovsti.core.model.wa.WaUser
import dev.rovsti.core.model.wa.WithHandle
import dev.rovsti.core.model.wa.WithHandleSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * The JSON ops of the WhatsApp gateway (E2.14). Every method but the pure ones at the end takes the `handle` of a
 * gateway opened with [open]; most callers use [WaGateway] instead of this raw facade. Failures: `RustError.Network`,
 * `Auth` (the key is wrong or revoked), `Invalid` (the gateway's own sentence, `detail.code` its error code) and
 * `Parse`.
 */
interface WaOps {
    /** Creates an instance and starts linking; needs no key, the answer carries the first connect key. */
    suspend fun createInstance(
        handle: Long,
        req: CreateInstance,
    ): Outcome<CreatedInstance>

    /** The instance: its link state and own number. */
    suspend fun status(handle: Long): Outcome<InstanceStatus>

    /** Starts linking again after the account logged out. */
    suspend fun relink(
        handle: Long,
        link: LinkRequest,
    ): Outcome<Empty>

    /** Deletes the instance on the gateway. */
    suspend fun deleteInstance(handle: Long): Outcome<Empty>

    /** Mints a connect key for another app; the plain key is only in this answer. */
    suspend fun mintKey(
        handle: Long,
        label: String,
    ): Outcome<IssuedKey>

    /** The connect keys of the instance. */
    suspend fun keys(handle: Long): Outcome<List<KeyInfo>>

    /** Revokes a connect key. */
    suspend fun revokeKey(
        handle: Long,
        keyId: String,
    ): Outcome<Empty>

    /** Registers the FCM token the gateway wakes this app with. */
    suspend fun registerPush(
        handle: Long,
        req: PushRegistration,
    ): Outcome<Empty>

    /** The chats, newest first. */
    suspend fun chats(handle: Long): Outcome<List<ChatSummary>>

    /** The chats with picture id, push name, archive, pin and mute state. */
    suspend fun chatsWithMeta(handle: Long): Outcome<List<RichChat>>

    /** A page of a chat, newest first; [beforeMs] pages back. */
    suspend fun messages(
        handle: Long,
        chat: String,
        beforeMs: Long? = null,
        limit: Int? = null,
    ): Outcome<List<WaMessage>>

    /** Like [messages], with reply, ticks, edit, media details. */
    suspend fun messagesWithMeta(
        handle: Long,
        chat: String,
        beforeMs: Long? = null,
        limit: Int? = null,
    ): Outcome<List<RichMessage>>

    /** Sends a text, optionally as a reply. */
    suspend fun sendText(
        handle: Long,
        req: SendText,
    ): Outcome<Sent>

    /** Has the gateway send a text at a time. */
    suspend fun scheduleText(
        handle: Long,
        req: ScheduleText,
    ): Outcome<ScheduledText>

    /** Pending scheduled texts plus those finished in the last 7 days. */
    suspend fun scheduled(handle: Long): Outcome<List<ScheduledText>>

    /** Cancels a pending scheduled text. */
    suspend fun cancelScheduled(
        handle: Long,
        scheduledId: String,
    ): Outcome<Empty>

    /** Sends a photo, video, voice note, document or sticker. */
    suspend fun sendMedia(
        handle: Long,
        body: SendMediaBody,
    ): Outcome<Sent>

    /** Like [sendMedia], as a reply or with a file name, duration or voice-note flag. */
    suspend fun sendMediaWith(
        handle: Long,
        body: SendMediaWithBody,
    ): Outcome<Sent>

    /** Downloads the media of a message (large ones as a temp file). */
    suspend fun media(
        handle: Long,
        messageId: String,
    ): Outcome<BytesAnswer>

    /** The preview image that came with a message. */
    suspend fun thumbnail(
        handle: Long,
        messageId: String,
    ): Outcome<BytesAnswer>

    /** Reacts to a message. */
    suspend fun react(
        handle: Long,
        req: SendReaction,
    ): Outcome<Empty>

    /** Read receipts. */
    suspend fun markRead(
        handle: Long,
        req: MarkRead,
    ): Outcome<Empty>

    /** Played receipts for voice and video notes. */
    suspend fun markPlayed(
        handle: Long,
        req: MarkRead,
    ): Outcome<Empty>

    /** Whether this account shows as online. */
    suspend fun setPresence(
        handle: Long,
        available: Boolean,
    ): Outcome<Empty>

    /** Starts presence updates for a contact. */
    suspend fun subscribePresence(
        handle: Long,
        jid: String,
    ): Outcome<Empty>

    /** Typing and recording indicators. */
    suspend fun chatState(
        handle: Long,
        req: ChatState,
    ): Outcome<Empty>

    /** The groups. */
    suspend fun groups(handle: Long): Outcome<List<GroupInfo>>

    /** One group with members and description. */
    suspend fun group(
        handle: Long,
        jid: String,
    ): Outcome<GroupInfo>

    /** Which of these user JIDs are on WhatsApp (the gateway limits how many). */
    suspend fun lookupUsers(
        handle: Long,
        jids: List<String>,
    ): Outcome<List<WaUser>>

    /** Names, about text and picture ids from the gateway's store. */
    suspend fun profiles(
        handle: Long,
        jids: List<String>,
    ): Outcome<List<WaProfile>>

    /** Like [profiles], asking WhatsApp even for fresh entries. */
    suspend fun refreshProfiles(
        handle: Long,
        jids: List<String>,
    ): Outcome<List<WaProfile>>

    /** A profile picture, unchanged, or none. */
    suspend fun picture(
        handle: Long,
        body: PictureBody,
    ): Outcome<PictureAnswer>

    /** The status updates (stories), mine and everyone else's. */
    suspend fun statuses(handle: Long): Outcome<StatusList>

    /** The photo or video of a status update. */
    suspend fun statusMedia(
        handle: Long,
        statusId: String,
    ): Outcome<BytesAnswer>

    /** The preview image of a status update. */
    suspend fun statusThumbnail(
        handle: Long,
        statusId: String,
    ): Outcome<BytesAnswer>

    /** Marks status updates seen. */
    suspend fun markStatusViewed(
        handle: Long,
        ids: List<String>,
    ): Outcome<Empty>

    /** Edits a message I sent (within 15 minutes). */
    suspend fun editMessage(
        handle: Long,
        req: EditMessage,
    ): Outcome<Empty>

    /** Deletes a message for everyone. */
    suspend fun revokeMessage(
        handle: Long,
        req: RevokeMessage,
    ): Outcome<Empty>

    /** Deletes a message on this account's devices. */
    suspend fun deleteForMe(
        handle: Long,
        req: DeleteForMe,
    ): Outcome<Empty>

    /** Stars or unstars a message. */
    suspend fun starMessage(
        handle: Long,
        req: StarMessage,
    ): Outcome<Empty>

    /** Archives, pins, mutes or marks a chat on every device. */
    suspend fun chatAction(
        handle: Long,
        req: ChatAction,
    ): Outcome<Empty>

    /** Places a WhatsApp call. */
    suspend fun placeCall(
        handle: Long,
        to: String,
    ): Outcome<CallStarted>

    /** The recent calls, newest first. */
    suspend fun calls(handle: Long): Outcome<List<CallSummary>>

    /** Answers an incoming call. */
    suspend fun acceptCall(
        handle: Long,
        callId: String,
    ): Outcome<Empty>

    /** Declines an incoming call. */
    suspend fun rejectCall(
        handle: Long,
        callId: String,
    ): Outcome<Empty>

    /** Hangs up. */
    suspend fun endCall(
        handle: Long,
        callId: String,
    ): Outcome<Empty>

    /** Mutes or unmutes the microphone of a call. */
    suspend fun setMuted(
        handle: Long,
        callId: String,
        muted: Boolean,
    ): Outcome<Empty>

    /** Opens the event stream; events arrive as `wa.event`. The answer is the stream handle. */
    suspend fun eventsStart(
        handle: Long,
        body: StartEventsBody,
    ): Outcome<HandleAnswer>

    /** Opens the audio socket of a call; the answer is the audio handle. */
    suspend fun callAudioOpen(
        handle: Long,
        callId: String,
    ): Outcome<HandleAnswer>

    /** Reconnects an event stream now instead of waiting out its backoff (a push says there is news). */
    suspend fun eventsReconnectNow(stream: Long): Outcome<Empty>

    /** Opens a gateway; the answer is its handle. */
    suspend fun open(req: WaOpenRequest): Outcome<HandleAnswer>

    /** Decodes an FCM wake-up payload. */
    suspend fun pushParse(req: PushParseRequest): Outcome<PushParseAnswer>

    /** Reads `whatsapp-link.json` of the app. */
    suspend fun linkRead(req: LinkReadRequest = LinkReadRequest()): Outcome<LinkReadAnswer>

    /** Saves the link, or removes it (`link` null). */
    suspend fun linkWrite(req: LinkWriteRequest): Outcome<Empty>

    /** Picture file names and the files to delete over the disk budget. */
    suspend fun avatarsPlan(req: AvatarsPlanRequest): Outcome<AvatarsPlanAnswer>

    /** The name to show for a JID: contact, WhatsApp name, number. */
    suspend fun names(req: WaNamesRequest): Outcome<WaNamesAnswer>

    /** Where a contact's WhatsApp actions go, and the JIDs to look up. */
    suspend fun target(req: WaTargetRequest): Outcome<WaTargetAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "wa.create_instance",
                "wa.status",
                "wa.relink",
                "wa.delete_instance",
                "wa.mint_key",
                "wa.keys",
                "wa.revoke_key",
                "wa.register_push",
                "wa.chats",
                "wa.chats_with_meta",
                "wa.messages",
                "wa.messages_with_meta",
                "wa.send_text",
                "wa.schedule_text",
                "wa.scheduled",
                "wa.cancel_scheduled",
                "wa.send_media",
                "wa.send_media_with",
                "wa.media",
                "wa.thumbnail",
                "wa.react",
                "wa.mark_read",
                "wa.mark_played",
                "wa.set_presence",
                "wa.subscribe_presence",
                "wa.chat_state",
                "wa.groups",
                "wa.group",
                "wa.lookup_users",
                "wa.profiles",
                "wa.refresh_profiles",
                "wa.picture",
                "wa.statuses",
                "wa.status_media",
                "wa.status_thumbnail",
                "wa.mark_status_viewed",
                "wa.edit_message",
                "wa.revoke_message",
                "wa.delete_for_me",
                "wa.star_message",
                "wa.chat_action",
                "wa.place_call",
                "wa.calls",
                "wa.accept_call",
                "wa.reject_call",
                "wa.end_call",
                "wa.set_muted",
                "wa.events_start",
                "wa.call_audio_open",
                "wa.events_reconnect_now",
                "wa.open",
                "wa.push_parse",
                "wa.link_read",
                "wa.link_write",
                "wa.avatars_plan",
                "wa.names",
                "wa.target",
            )
    }
}

/** [WaOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeWaOps(
    private val core: RustCore = NativeRustCore(),
) : WaOps {
    private suspend fun <B, A> gateway(
        op: String,
        handle: Long,
        body: B,
        bodySer: KSerializer<B>,
        ans: KSerializer<A>,
    ) = core.call(op, WithHandle(handle, body), WithHandleSerializer(bodySer), ans)

    override suspend fun createInstance(
        handle: Long,
        req: CreateInstance,
    ) = gateway("wa.create_instance", handle, req, CreateInstance.serializer(), CreatedInstance.serializer())

    override suspend fun status(handle: Long) =
        gateway("wa.status", handle, Empty, Empty.serializer(), InstanceStatus.serializer())

    override suspend fun relink(
        handle: Long,
        link: LinkRequest,
    ) = gateway("wa.relink", handle, link, LinkRequest.serializer(), Empty.serializer())

    override suspend fun deleteInstance(handle: Long) =
        gateway("wa.delete_instance", handle, Empty, Empty.serializer(), Empty.serializer())

    override suspend fun mintKey(
        handle: Long,
        label: String,
    ) = gateway("wa.mint_key", handle, LabelBody(label), LabelBody.serializer(), IssuedKey.serializer())

    override suspend fun keys(handle: Long) =
        gateway("wa.keys", handle, Empty, Empty.serializer(), ListSerializer(KeyInfo.serializer()))

    override suspend fun revokeKey(
        handle: Long,
        keyId: String,
    ) = gateway("wa.revoke_key", handle, KeyIdBody(keyId), KeyIdBody.serializer(), Empty.serializer())

    override suspend fun registerPush(
        handle: Long,
        req: PushRegistration,
    ) = gateway("wa.register_push", handle, req, PushRegistration.serializer(), Empty.serializer())

    override suspend fun chats(handle: Long) =
        gateway("wa.chats", handle, Empty, Empty.serializer(), ListSerializer(ChatSummary.serializer()))

    override suspend fun chatsWithMeta(handle: Long) =
        gateway("wa.chats_with_meta", handle, Empty, Empty.serializer(), ListSerializer(RichChat.serializer()))

    override suspend fun messages(
        handle: Long,
        chat: String,
        beforeMs: Long?,
        limit: Int?,
    ) = gateway(
        "wa.messages",
        handle,
        MessagesBody(chat, beforeMs, limit),
        MessagesBody.serializer(),
        ListSerializer(WaMessage.serializer()),
    )

    override suspend fun messagesWithMeta(
        handle: Long,
        chat: String,
        beforeMs: Long?,
        limit: Int?,
    ) = gateway(
        "wa.messages_with_meta",
        handle,
        MessagesBody(chat, beforeMs, limit),
        MessagesBody.serializer(),
        ListSerializer(RichMessage.serializer()),
    )

    override suspend fun sendText(
        handle: Long,
        req: SendText,
    ) = gateway("wa.send_text", handle, req, SendText.serializer(), Sent.serializer())

    override suspend fun scheduleText(
        handle: Long,
        req: ScheduleText,
    ) = gateway("wa.schedule_text", handle, req, ScheduleText.serializer(), ScheduledText.serializer())

    override suspend fun scheduled(handle: Long) =
        gateway("wa.scheduled", handle, Empty, Empty.serializer(), ListSerializer(ScheduledText.serializer()))

    override suspend fun cancelScheduled(
        handle: Long,
        scheduledId: String,
    ) = gateway("wa.cancel_scheduled", handle, IdBody(scheduledId), IdBody.serializer(), Empty.serializer())

    override suspend fun sendMedia(
        handle: Long,
        body: SendMediaBody,
    ) = gateway("wa.send_media", handle, body, SendMediaBody.serializer(), Sent.serializer())

    override suspend fun sendMediaWith(
        handle: Long,
        body: SendMediaWithBody,
    ) = gateway("wa.send_media_with", handle, body, SendMediaWithBody.serializer(), Sent.serializer())

    override suspend fun media(
        handle: Long,
        messageId: String,
    ) = gateway("wa.media", handle, MessageIdBody(messageId), MessageIdBody.serializer(), BytesAnswer.serializer())

    override suspend fun thumbnail(
        handle: Long,
        messageId: String,
    ) = gateway("wa.thumbnail", handle, MessageIdBody(messageId), MessageIdBody.serializer(), BytesAnswer.serializer())

    override suspend fun react(
        handle: Long,
        req: SendReaction,
    ) = gateway("wa.react", handle, req, SendReaction.serializer(), Empty.serializer())

    override suspend fun markRead(
        handle: Long,
        req: MarkRead,
    ) = gateway("wa.mark_read", handle, req, MarkRead.serializer(), Empty.serializer())

    override suspend fun markPlayed(
        handle: Long,
        req: MarkRead,
    ) = gateway("wa.mark_played", handle, req, MarkRead.serializer(), Empty.serializer())

    override suspend fun setPresence(
        handle: Long,
        available: Boolean,
    ) = gateway("wa.set_presence", handle, PresenceBody(available), PresenceBody.serializer(), Empty.serializer())

    override suspend fun subscribePresence(
        handle: Long,
        jid: String,
    ) = gateway("wa.subscribe_presence", handle, JidBody(jid), JidBody.serializer(), Empty.serializer())

    override suspend fun chatState(
        handle: Long,
        req: ChatState,
    ) = gateway("wa.chat_state", handle, req, ChatState.serializer(), Empty.serializer())

    override suspend fun groups(handle: Long) =
        gateway("wa.groups", handle, Empty, Empty.serializer(), ListSerializer(GroupInfo.serializer()))

    override suspend fun group(
        handle: Long,
        jid: String,
    ) = gateway("wa.group", handle, JidBody(jid), JidBody.serializer(), GroupInfo.serializer())

    override suspend fun lookupUsers(
        handle: Long,
        jids: List<String>,
    ) = gateway("wa.lookup_users", handle, JidsBody(jids), JidsBody.serializer(), ListSerializer(WaUser.serializer()))

    override suspend fun profiles(
        handle: Long,
        jids: List<String>,
    ) = gateway("wa.profiles", handle, JidsBody(jids), JidsBody.serializer(), ListSerializer(WaProfile.serializer()))

    override suspend fun refreshProfiles(
        handle: Long,
        jids: List<String>,
    ) = gateway(
        "wa.refresh_profiles",
        handle,
        JidsBody(jids),
        JidsBody.serializer(),
        ListSerializer(WaProfile.serializer()),
    )

    override suspend fun picture(
        handle: Long,
        body: PictureBody,
    ) = gateway("wa.picture", handle, body, PictureBody.serializer(), PictureAnswer.serializer())

    override suspend fun statuses(handle: Long) =
        gateway("wa.statuses", handle, Empty, Empty.serializer(), StatusList.serializer())

    override suspend fun statusMedia(
        handle: Long,
        statusId: String,
    ) = gateway("wa.status_media", handle, IdBody(statusId), IdBody.serializer(), BytesAnswer.serializer())

    override suspend fun statusThumbnail(
        handle: Long,
        statusId: String,
    ) = gateway("wa.status_thumbnail", handle, IdBody(statusId), IdBody.serializer(), BytesAnswer.serializer())

    override suspend fun markStatusViewed(
        handle: Long,
        ids: List<String>,
    ) = gateway("wa.mark_status_viewed", handle, IdsBody(ids), IdsBody.serializer(), Empty.serializer())

    override suspend fun editMessage(
        handle: Long,
        req: EditMessage,
    ) = gateway("wa.edit_message", handle, req, EditMessage.serializer(), Empty.serializer())

    override suspend fun revokeMessage(
        handle: Long,
        req: RevokeMessage,
    ) = gateway("wa.revoke_message", handle, req, RevokeMessage.serializer(), Empty.serializer())

    override suspend fun deleteForMe(
        handle: Long,
        req: DeleteForMe,
    ) = gateway("wa.delete_for_me", handle, req, DeleteForMe.serializer(), Empty.serializer())

    override suspend fun starMessage(
        handle: Long,
        req: StarMessage,
    ) = gateway("wa.star_message", handle, req, StarMessage.serializer(), Empty.serializer())

    override suspend fun chatAction(
        handle: Long,
        req: ChatAction,
    ) = gateway("wa.chat_action", handle, req, ChatAction.serializer(), Empty.serializer())

    override suspend fun placeCall(
        handle: Long,
        to: String,
    ) = gateway("wa.place_call", handle, PlaceCallBody(to), PlaceCallBody.serializer(), CallStarted.serializer())

    override suspend fun calls(handle: Long) =
        gateway("wa.calls", handle, Empty, Empty.serializer(), ListSerializer(CallSummary.serializer()))

    override suspend fun acceptCall(
        handle: Long,
        callId: String,
    ) = gateway("wa.accept_call", handle, CallIdBody(callId), CallIdBody.serializer(), Empty.serializer())

    override suspend fun rejectCall(
        handle: Long,
        callId: String,
    ) = gateway("wa.reject_call", handle, CallIdBody(callId), CallIdBody.serializer(), Empty.serializer())

    override suspend fun endCall(
        handle: Long,
        callId: String,
    ) = gateway("wa.end_call", handle, CallIdBody(callId), CallIdBody.serializer(), Empty.serializer())

    override suspend fun setMuted(
        handle: Long,
        callId: String,
        muted: Boolean,
    ) = gateway("wa.set_muted", handle, CallMuteBody(callId, muted), CallMuteBody.serializer(), Empty.serializer())

    override suspend fun eventsStart(
        handle: Long,
        body: StartEventsBody,
    ) = gateway("wa.events_start", handle, body, StartEventsBody.serializer(), HandleAnswer.serializer())

    override suspend fun callAudioOpen(
        handle: Long,
        callId: String,
    ) = gateway("wa.call_audio_open", handle, CallIdBody(callId), CallIdBody.serializer(), HandleAnswer.serializer())

    override suspend fun eventsReconnectNow(stream: Long) =
        gateway("wa.events_reconnect_now", stream, Empty, Empty.serializer(), Empty.serializer())

    override suspend fun open(req: WaOpenRequest) =
        core.call("wa.open", req, WaOpenRequest.serializer(), HandleAnswer.serializer())

    override suspend fun pushParse(req: PushParseRequest) =
        core.call("wa.push_parse", req, PushParseRequest.serializer(), PushParseAnswer.serializer())

    override suspend fun linkRead(req: LinkReadRequest) =
        core.call("wa.link_read", req, LinkReadRequest.serializer(), LinkReadAnswer.serializer())

    override suspend fun linkWrite(req: LinkWriteRequest) =
        core.call("wa.link_write", req, LinkWriteRequest.serializer(), Empty.serializer())

    override suspend fun avatarsPlan(req: AvatarsPlanRequest) =
        core.call("wa.avatars_plan", req, AvatarsPlanRequest.serializer(), AvatarsPlanAnswer.serializer())

    override suspend fun names(req: WaNamesRequest) =
        core.call("wa.names", req, WaNamesRequest.serializer(), WaNamesAnswer.serializer())

    override suspend fun target(req: WaTargetRequest) =
        core.call("wa.target", req, WaTargetRequest.serializer(), WaTargetAnswer.serializer())
}

/**
 * `wa.settings_apply` (R9-3): the WhatsApp settings state machine, pure and stateless. The caller keeps the
 * answer's `model` and sends it back with the next input; the `view` is what the page draws and the `effects` are
 * what the screen must run (gateway calls, save, clipboard). Failures: `Invalid` for a model or input that does not
 * decode.
 */
interface WaSettingsOps {
    suspend fun apply(req: SettingsApplyRequest): Outcome<SettingsApplyAnswer>

    companion object {
        val ops: List<String> = listOf("wa.settings_apply")
    }
}

class NativeWaSettingsOps(
    private val core: RustCore = NativeRustCore(),
) : WaSettingsOps {
    override suspend fun apply(req: SettingsApplyRequest) =
        core.call(
            "wa.settings_apply",
            req,
            SettingsApplyRequest.serializer(),
            SettingsApplyAnswer.serializer(),
        )
}
