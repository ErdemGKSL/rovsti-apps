package dev.rovsti.core.contracts

import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
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
import dev.rovsti.core.model.wa.WithHandleSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `wa.*`. Network requests carry the gateway `handle` flat next to their fields. */
object WaContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<WaOpenRequest, HandleAnswer>(
                "wa.open",
                ok = listOf("no_key"),
                err = listOf("bad_url", "insecure", "bad_key"),
            )
            handleOp<CreateInstance, CreatedInstance>("wa.create_instance", err = listOf("limit"))
            handleOp<Empty, InstanceStatus>(
                "wa.status",
                err = listOf("unauthorized", "forbidden", "api_error", "garbage", "network"),
            )
            handleOp<LinkRequest, Empty>("wa.relink", ok = listOf("qr"))
            handleOp<Empty, Empty>("wa.delete_instance")
            handleOp<LabelBody, IssuedKey>("wa.mint_key")
            handleOp<Empty, List<KeyInfo>>("wa.keys")
            handleOp<KeyIdBody, Empty>("wa.revoke_key")
            handleOp<PushRegistration, Empty>("wa.register_push")
            handleOp<Empty, List<ChatSummary>>("wa.chats")
            handleOp<Empty, List<RichChat>>("wa.chats_with_meta")
            handleOp<MessagesBody, List<WaMessage>>("wa.messages")
            handleOp<MessagesBody, List<RichMessage>>("wa.messages_with_meta")
            handleOp<SendText, Sent>("wa.send_text", ok = listOf("reply"))
            handleOp<ScheduleText, ScheduledText>("wa.schedule_text")
            handleOp<Empty, List<ScheduledText>>("wa.scheduled")
            handleOp<IdBody, Empty>("wa.cancel_scheduled", err = listOf("gone"))
            handleOp<SendMediaBody, Sent>("wa.send_media", err = listOf("both", "bad_base64", "empty"))
            handleOp<SendMediaWithBody, Sent>("wa.send_media_with")
            handleOp<MessageIdBody, BytesAnswer>("wa.media", ok = listOf("large"))
            handleOp<MessageIdBody, BytesAnswer>("wa.thumbnail")
            handleOp<SendReaction, Empty>("wa.react")
            handleOp<MarkRead, Empty>("wa.mark_read")
            handleOp<MarkRead, Empty>("wa.mark_played")
            handleOp<PresenceBody, Empty>("wa.set_presence")
            handleOp<JidBody, Empty>("wa.subscribe_presence")
            handleOp<ChatState, Empty>("wa.chat_state")
            handleOp<Empty, List<GroupInfo>>("wa.groups")
            handleOp<JidBody, GroupInfo>("wa.group")
            handleOp<JidsBody, List<WaUser>>("wa.lookup_users")
            handleOp<JidsBody, List<WaProfile>>("wa.profiles")
            handleOp<JidsBody, List<WaProfile>>("wa.refresh_profiles")
            handleOp<PictureBody, PictureAnswer>(
                "wa.picture",
                ok = listOf("unchanged", "none"),
                err = listOf("rate_limited"),
            )
            handleOp<Empty, StatusList>("wa.statuses")
            handleOp<IdBody, BytesAnswer>("wa.status_media")
            handleOp<IdBody, BytesAnswer>("wa.status_thumbnail")
            handleOp<IdsBody, Empty>("wa.mark_status_viewed")
            handleOp<EditMessage, Empty>("wa.edit_message")
            handleOp<RevokeMessage, Empty>("wa.revoke_message")
            handleOp<DeleteForMe, Empty>("wa.delete_for_me")
            handleOp<StarMessage, Empty>("wa.star_message")
            handleOp<ChatAction, Empty>("wa.chat_action", ok = listOf("mute"))
            handleOp<PlaceCallBody, CallStarted>("wa.place_call", err = listOf("offline"))
            handleOp<Empty, List<CallSummary>>("wa.calls")
            handleOp<CallIdBody, Empty>("wa.accept_call")
            handleOp<CallIdBody, Empty>("wa.reject_call")
            handleOp<CallIdBody, Empty>("wa.end_call")
            handleOp<CallMuteBody, Empty>("wa.set_muted")
            handleOp<StartEventsBody, HandleAnswer>("wa.events_start", ok = listOf("resume"))
            handleOp<Empty, Empty>("wa.events_reconnect_now")
            handleOp<CallIdBody, HandleAnswer>(
                "wa.call_audio_open",
                err = listOf("unauthorized", "no_call"),
            )
            op<PushParseRequest, PushParseAnswer>(
                "wa.push_parse",
                ok = listOf("message", "call_end", "unknown", "garbage"),
            )
            op<LinkReadRequest, LinkReadAnswer>("wa.link_read", ok = listOf("none"))
            op<LinkWriteRequest, Empty>(
                "wa.link_write",
                ok = listOf("remove"),
                err = listOf("bad_key", "bad_url"),
            )
            op<AvatarsPlanRequest, AvatarsPlanAnswer>("wa.avatars_plan", ok = listOf("within_budget"))
            op<WaNamesRequest, WaNamesAnswer>("wa.names", ok = listOf("whatsapp_name", "number", "lid"))
            op<WaTargetRequest, WaTargetAnswer>(
                "wa.target",
                ok = listOf("number", "local_only", "no_number"),
            )
        }

    private inline fun <reified Body, reified Ans> MutableMap<String, KSerializer<*>>.handleOp(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
    ) = stems(op, WithHandleSerializer(serializer<Body>()), serializer<Ans>(), ok, err)

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
    ) = stems(op, serializer<Req>(), serializer<Ans>(), ok, err)

    private fun <Ans> MutableMap<String, KSerializer<*>>.stems(
        op: String,
        request: KSerializer<*>,
        answer: KSerializer<Ans>,
        ok: List<String>,
        err: List<String>,
    ) {
        val okAnswer = OkEnvelope.serializer(answer)
        put("$op.request", request)
        put("$op.answer", okAnswer)
        for (label in ok) {
            put("$op.$label.request", request)
            put("$op.$label.answer", okAnswer)
        }
        for (label in err) {
            put("$op.$label.request", request)
            put("$op.$label.answer", ErrEnvelope.serializer())
        }
    }
}
