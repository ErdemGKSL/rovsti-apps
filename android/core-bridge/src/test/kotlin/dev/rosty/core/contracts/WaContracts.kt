package dev.rosty.core.contracts

import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.people.PeopleSyncBundleAnswer
import dev.rosty.core.model.people.PeopleSyncBundleBody
import dev.rosty.core.model.people.PeopleSyncJobBody
import dev.rosty.core.model.people.PeopleSyncStartAnswer
import dev.rosty.core.model.people.PeopleSyncStartBody
import dev.rosty.core.model.people.PeopleSyncStatusAnswer
import dev.rosty.core.model.people.PeopleSyncStatusBody
import dev.rosty.core.model.wa.AvatarsPlanAnswer
import dev.rosty.core.model.wa.AvatarsPlanRequest
import dev.rosty.core.model.wa.BytesAnswer
import dev.rosty.core.model.wa.CallIdBody
import dev.rosty.core.model.wa.CallMuteBody
import dev.rosty.core.model.wa.CallStarted
import dev.rosty.core.model.wa.CallSummary
import dev.rosty.core.model.wa.ChatAction
import dev.rosty.core.model.wa.ChatState
import dev.rosty.core.model.wa.ChatSummary
import dev.rosty.core.model.wa.CreateInstance
import dev.rosty.core.model.wa.CreatedInstance
import dev.rosty.core.model.wa.DeleteForMe
import dev.rosty.core.model.wa.EditMessage
import dev.rosty.core.model.wa.ForwardMessage
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.HandleAnswer
import dev.rosty.core.model.wa.IdBody
import dev.rosty.core.model.wa.IdentityMappingsAnswer
import dev.rosty.core.model.wa.IdentityMappingsBody
import dev.rosty.core.model.wa.IdsBody
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.IssuedKey
import dev.rosty.core.model.wa.JidBody
import dev.rosty.core.model.wa.JidsBody
import dev.rosty.core.model.wa.KeyIdBody
import dev.rosty.core.model.wa.KeyInfo
import dev.rosty.core.model.wa.LabelBody
import dev.rosty.core.model.wa.LinkFindAnswer
import dev.rosty.core.model.wa.LinkFindRequest
import dev.rosty.core.model.wa.LinkMeta
import dev.rosty.core.model.wa.LinkParseRequest
import dev.rosty.core.model.wa.LinkReadAnswer
import dev.rosty.core.model.wa.LinkReadRequest
import dev.rosty.core.model.wa.LinkRequest
import dev.rosty.core.model.wa.LinkWriteRequest
import dev.rosty.core.model.wa.MarkRead
import dev.rosty.core.model.wa.MentionEncodeRequest
import dev.rosty.core.model.wa.MentionEncoded
import dev.rosty.core.model.wa.MentionQueryAnswer
import dev.rosty.core.model.wa.MentionQueryRequest
import dev.rosty.core.model.wa.MessageIdBody
import dev.rosty.core.model.wa.MessageReceipts
import dev.rosty.core.model.wa.MessageReceiptsRequest
import dev.rosty.core.model.wa.MessagesBody
import dev.rosty.core.model.wa.PictureAnswer
import dev.rosty.core.model.wa.PictureBody
import dev.rosty.core.model.wa.PlaceCallBody
import dev.rosty.core.model.wa.PresenceBody
import dev.rosty.core.model.wa.PushParseAnswer
import dev.rosty.core.model.wa.PushParseRequest
import dev.rosty.core.model.wa.PushRegistration
import dev.rosty.core.model.wa.ReceiptSectionsAnswer
import dev.rosty.core.model.wa.ReceiptSectionsRequest
import dev.rosty.core.model.wa.RevokeMessage
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.model.wa.RichMessage
import dev.rosty.core.model.wa.ScheduleText
import dev.rosty.core.model.wa.ScheduledText
import dev.rosty.core.model.wa.SendMediaBody
import dev.rosty.core.model.wa.SendMediaWithBody
import dev.rosty.core.model.wa.SendReaction
import dev.rosty.core.model.wa.SendText
import dev.rosty.core.model.wa.Sent
import dev.rosty.core.model.wa.SettingsApplyAnswer
import dev.rosty.core.model.wa.SettingsApplyRequest
import dev.rosty.core.model.wa.StarMessage
import dev.rosty.core.model.wa.StartEventsBody
import dev.rosty.core.model.wa.StatusList
import dev.rosty.core.model.wa.WaMessage
import dev.rosty.core.model.wa.WaNamesAnswer
import dev.rosty.core.model.wa.WaNamesRequest
import dev.rosty.core.model.wa.WaOpenRequest
import dev.rosty.core.model.wa.WaProfile
import dev.rosty.core.model.wa.WaTargetAnswer
import dev.rosty.core.model.wa.WaTargetRequest
import dev.rosty.core.model.wa.WaUser
import dev.rosty.core.model.wa.WithHandleSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `wa.*`. Network requests carry the gateway `handle` flat next to their fields. */
object WaContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<WaOpenRequest, HandleAnswer>(
                "wa.open",
                ok = listOf("no_key", "vault", "vault_same_url"),
                err = listOf("bad_url", "insecure", "bad_key", "no_url", "vault_key_refused", "vault_other_url"),
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
            handleOp<PushRegistration, Empty>("wa.register_push", ok = listOf("keyed"))
            handleOp<Empty, List<ChatSummary>>("wa.chats")
            handleOp<Empty, List<RichChat>>("wa.chats_with_meta")
            handleOp<MessagesBody, List<WaMessage>>("wa.messages")
            handleOp<MessagesBody, List<RichMessage>>("wa.messages_with_meta")
            handleOp<SendText, Sent>("wa.send_text", ok = listOf("reply"))
            handleOp<ScheduleText, ScheduledText>("wa.schedule_text")
            handleOp<Empty, List<ScheduledText>>("wa.scheduled")
            handleOp<IdBody, Empty>("wa.cancel_scheduled", err = listOf("gone"))
            handleOp<SendMediaBody, Sent>("wa.send_media", err = listOf("both", "bad_base64", "empty"))
            handleOp<SendMediaWithBody, Sent>(
                "wa.send_media_with",
                ok = listOf("options", "waveform"),
                err = listOf("bad_waveform", "view_once_document"),
            )
            handleOp<MessageIdBody, BytesAnswer>("wa.media", ok = listOf("large"))
            handleOp<MessageIdBody, BytesAnswer>("wa.thumbnail")
            handleOp<SendReaction, Empty>("wa.react")
            handleOp<MarkRead, Empty>("wa.mark_read")
            handleOp<MarkRead, Empty>("wa.mark_played")
            handleOp<PresenceBody, Empty>("wa.set_presence")
            handleOp<JidBody, Empty>("wa.subscribe_presence")
            handleOp<ChatState, Empty>("wa.chat_state", ok = listOf("recording"))
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
            handleOp<PeopleSyncStartBody, PeopleSyncStartAnswer>("wa.people_sync_start", ok = listOf("unsupported"))
            handleOp<PeopleSyncStatusBody, PeopleSyncStatusAnswer>("wa.people_sync_status", ok = listOf("gone"))
            handleOp<PeopleSyncBundleBody, PeopleSyncBundleAnswer>(
                "wa.people_sync_bundle",
                ok = listOf("gone"),
                err = listOf("cut"),
            )
            handleOp<PeopleSyncJobBody, Empty>("wa.people_sync_cancel")
            handleOp<IdentityMappingsBody, IdentityMappingsAnswer>(
                "wa.identity_mappings",
                ok = listOf("since", "old_gateway"),
                err = listOf("unauthorized"),
            )
            handleOp<Empty, StatusList>("wa.statuses")
            handleOp<IdBody, BytesAnswer>("wa.status_media")
            handleOp<IdBody, BytesAnswer>("wa.status_thumbnail")
            handleOp<IdsBody, Empty>("wa.mark_status_viewed")
            handleOp<EditMessage, Empty>("wa.edit_message")
            handleOp<RevokeMessage, Empty>("wa.revoke_message")
            handleOp<DeleteForMe, Empty>("wa.delete_for_me")
            handleOp<ForwardMessage, Sent>("wa.forward", err = listOf("old_gateway"))
            handleOp<MessageReceiptsRequest, MessageReceipts>("wa.message_receipts", err = listOf("old_gateway"))
            op<MentionQueryRequest, MentionQueryAnswer>("wa.mention_query", ok = listOf("email"))
            op<MentionEncodeRequest, MentionEncoded>("wa.mention_encode", ok = listOf("edited"))
            op<LinkFindRequest, LinkFindAnswer>("wa.link_find", ok = listOf("none"))
            op<LinkParseRequest, LinkMeta>("wa.link_parse", ok = listOf("bare"), err = listOf("not_http"))
            op<ReceiptSectionsRequest, ReceiptSectionsAnswer>("wa.receipt_sections", ok = listOf("voice"))
            // E6-WA R5: rich content and presence.
            handleOp<dev.rosty.core.model.wa.SendRich, Sent>(
                "wa.send_rich",
                ok = listOf("location"),
                err = listOf("old_gateway"),
            )
            handleOp<dev.rosty.core.model.wa.PollVote, Empty>("wa.poll_vote", ok = listOf("take_back"))
            handleOp<JidBody, Empty>("wa.unsubscribe_presence")
            op<dev.rosty.core.model.wa.PollCheckRequest, dev.rosty.core.model.wa.PollCheckAnswer>(
                "wa.poll_check",
                ok = listOf("problems"),
            )
            op<dev.rosty.core.model.wa.PollViewRequest, dev.rosty.core.model.wa.PollView>("wa.poll_view")
            op<dev.rosty.core.model.wa.VcardParseRequest, dev.rosty.core.model.wa.VcardParseAnswer>(
                "wa.vcard_parse",
                ok = listOf("no_number"),
            )
            op<dev.rosty.core.model.wa.VcardBuildRequest, dev.rosty.core.model.wa.VcardBuildAnswer>(
                "wa.vcard_build",
                err = listOf("no_name"),
            )
            op<dev.rosty.core.model.wa.PlaceViewRequest, dev.rosty.core.model.wa.PlaceView>(
                "wa.place_view",
                ok = listOf("no_name"),
                err = listOf("nowhere"),
            )
            op<dev.rosty.core.model.wa.StaticMapRequest, dev.rosty.core.model.wa.StaticMap>(
                "wa.static_map",
                err = listOf("empty"),
            )
            op<dev.rosty.core.model.wa.PresenceViewRequest, dev.rosty.core.model.wa.HeaderPresence>(
                "wa.presence_view",
                ok = listOf("typing", "group", "hidden"),
            )
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
            op<SettingsApplyRequest, SettingsApplyAnswer>(
                "wa.settings_apply",
                ok =
                    listOf(
                        "linked_start",
                        "enter_url",
                        "bad_url",
                        "link_new_qr",
                        "link_new_code",
                        "paste_key",
                        "bad_key",
                        "connecting",
                        "show_qr",
                        "show_code",
                        "connected",
                        "share_key",
                        "keys",
                        "keys_loaded",
                        "confirm_revoke",
                        "logged_out",
                        "confirm_delete",
                        "confirm_unlink",
                        "confirm_unlink_back",
                        "failed",
                    ),
                err = listOf("bad_model"),
            )
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
