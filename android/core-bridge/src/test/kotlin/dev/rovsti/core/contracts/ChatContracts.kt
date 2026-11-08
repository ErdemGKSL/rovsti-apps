package dev.rovsti.core.contracts

import dev.rovsti.core.model.chat.CallReminderAnswer
import dev.rovsti.core.model.chat.CallReminderListAnswer
import dev.rovsti.core.model.chat.CallReminderListRequest
import dev.rovsti.core.model.chat.CallReminderRequest
import dev.rovsti.core.model.chat.ChatFindAnswer
import dev.rovsti.core.model.chat.ChatFindRequest
import dev.rovsti.core.model.chat.DateDetectAnswer
import dev.rovsti.core.model.chat.DateDetectRequest
import dev.rovsti.core.model.chat.DatePickAnswer
import dev.rovsti.core.model.chat.DatePickRequest
import dev.rovsti.core.model.chat.DateStepAnswer
import dev.rovsti.core.model.chat.DateStepRequest
import dev.rovsti.core.model.chat.ExportAnswer
import dev.rovsti.core.model.chat.ExportBlockRequest
import dev.rovsti.core.model.chat.ExportNoticeRequest
import dev.rovsti.core.model.chat.ExportRequest
import dev.rovsti.core.model.chat.FindCursorAnswer
import dev.rovsti.core.model.chat.FindCursorRequest
import dev.rovsti.core.model.chat.ForwardPlanAnswer
import dev.rovsti.core.model.chat.ForwardPlanRequest
import dev.rovsti.core.model.chat.ForwardWordsAnswer
import dev.rovsti.core.model.chat.ForwardWordsRequest
import dev.rovsti.core.model.chat.GalleryAnswer
import dev.rovsti.core.model.chat.GalleryRequest
import dev.rovsti.core.model.chat.GalleryTabsAnswer
import dev.rovsti.core.model.chat.GalleryTabsRequest
import dev.rovsti.core.model.chat.GlobalSearchAnswer
import dev.rovsti.core.model.chat.GlobalSearchRequest
import dev.rovsti.core.model.chat.InsertReplyAnswer
import dev.rovsti.core.model.chat.InsertReplyRequest
import dev.rovsti.core.model.chat.LocalStarsAnswer
import dev.rovsti.core.model.chat.LocalStarsRequest
import dev.rovsti.core.model.chat.MakeReminderAnswer
import dev.rovsti.core.model.chat.MakeReminderRequest
import dev.rovsti.core.model.chat.MarksAnswer
import dev.rovsti.core.model.chat.MarksRequest
import dev.rovsti.core.model.chat.MessageInfoAnswer
import dev.rovsti.core.model.chat.MessageInfoRequest
import dev.rovsti.core.model.chat.MimeAnswer
import dev.rovsti.core.model.chat.MimeRequest
import dev.rovsti.core.model.chat.MultiSelectPlan
import dev.rovsti.core.model.chat.MultiSelectRequest
import dev.rovsti.core.model.chat.MuteChoicesAnswer
import dev.rovsti.core.model.chat.MuteChoicesRequest
import dev.rovsti.core.model.chat.MuteStatusAnswer
import dev.rovsti.core.model.chat.MuteStatusRequest
import dev.rovsti.core.model.chat.NextUnreadAnswer
import dev.rovsti.core.model.chat.NextUnreadRequest
import dev.rovsti.core.model.chat.PlacesAnswer
import dev.rovsti.core.model.chat.PlacesRequest
import dev.rovsti.core.model.chat.ReminderListAnswer
import dev.rovsti.core.model.chat.ReminderListRequest
import dev.rovsti.core.model.chat.ReminderSlotsAnswer
import dev.rovsti.core.model.chat.ReminderSlotsRequest
import dev.rovsti.core.model.chat.RepliesAnswer
import dev.rovsti.core.model.chat.RepliesRequest
import dev.rovsti.core.model.chat.SearchFooterRequest
import dev.rovsti.core.model.chat.SearchJumpAnswer
import dev.rovsti.core.model.chat.SearchJumpRequest
import dev.rovsti.core.model.chat.SearchPrepareAnswer
import dev.rovsti.core.model.chat.SearchPrepareRequest
import dev.rovsti.core.model.chat.ShareLocationAnswer
import dev.rovsti.core.model.chat.ShareLocationRequest
import dev.rovsti.core.model.chat.SharedCardAnswer
import dev.rovsti.core.model.chat.SharedCardRequest
import dev.rovsti.core.model.chat.SpeedsAnswer
import dev.rovsti.core.model.chat.SpeedsRequest
import dev.rovsti.core.model.chat.StarredRowsAnswer
import dev.rovsti.core.model.chat.StarredRowsRequest
import dev.rovsti.core.model.chat.TextAnswer
import dev.rovsti.core.model.chat.TextDetectAnswer
import dev.rovsti.core.model.chat.TextDetectRequest
import dev.rovsti.core.model.chat.UnreadDividerAnswer
import dev.rovsti.core.model.chat.UnreadDividerRequest
import dev.rovsti.core.model.chat.UnreadStateAnswer
import dev.rovsti.core.model.chat.UnreadStateRequest
import dev.rovsti.core.model.chat.VoiceToggleAnswer
import dev.rovsti.core.model.chat.VoiceToggleRequest
import dev.rovsti.core.model.chat.VoiceViewAnswer
import dev.rovsti.core.model.chat.VoiceViewRequest
import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for the Messages chat tools (E2.11b). */
object ChatContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<ChatFindRequest, ChatFindAnswer>("chat_find.find", ok = listOf("blank"))
            op<FindCursorRequest, FindCursorAnswer>(
                "chat_find.cursor",
                ok = listOf("none", "older", "resume", "retarget", "wraps_newer"),
            )
            op<GlobalSearchRequest, GlobalSearchAnswer>(
                "global_search.search",
                ok = listOf("partial", "too_short"),
            )
            op<SearchPrepareRequest, SearchPrepareAnswer>("global_search.prepare", ok = listOf("short"))
            op<SearchFooterRequest, TextAnswer>(
                "global_search.footer",
                ok = listOf("failed", "sources_missing"),
            )
            op<SearchJumpRequest, SearchJumpAnswer>(
                "global_search.jump",
                ok = listOf("further_back", "load_older", "loaded"),
            )
            op<GalleryRequest, GalleryAnswer>("chat_gallery.build", ok = listOf("empty", "short_links"))
            op<GalleryTabsRequest, GalleryTabsAnswer>("chat_gallery.tabs", ok = listOf("partial"))
            op<ExportRequest, ExportAnswer>("chat_export.text", ok = listOf("empty"))
            op<ExportBlockRequest, TextAnswer>("chat_export.block")
            op<ExportNoticeRequest, TextAnswer>("chat_export.notice", ok = listOf("failed", "partial"))
            op<MultiSelectRequest, MultiSelectPlan>("multi_select.plan", ok = listOf("none", "one_starred"))
            op<DatePickRequest, DatePickAnswer>(
                "date_jump.pick",
                ok = listOf("never_past_today", "step_day", "step_month"),
            )
            op<DateStepRequest, DateStepAnswer>(
                "date_jump.step",
                ok = listOf("empty", "further_back", "load_older", "newest", "reveals_a_later_day"),
            )
            op<UnreadStateRequest, UnreadStateAnswer>(
                "unread_jump.state",
                ok = listOf("arrived_at_the_end", "arrived_while_away", "back_at_the_end", "capped"),
            )
            op<UnreadDividerRequest, UnreadDividerAnswer>(
                "unread_jump.divider",
                ok = listOf("fewer_loaded", "nothing"),
            )
            op<NextUnreadRequest, NextUnreadAnswer>("unread_jump.next", ok = listOf("no_current", "none"))
            op<SpeedsRequest, SpeedsAnswer>("voice_player.speeds", ok = listOf("from_1_5", "wraps"))
            op<VoiceToggleRequest, VoiceToggleAnswer>(
                "voice_player.toggle",
                ok = listOf("ended", "idle", "other_note", "resume"),
            )
            op<VoiceViewRequest, VoiceViewAnswer>("voice_player.view", ok = listOf("idle", "unknown_duration"))
            op<MarksRequest, MarksAnswer>(
                "thread_marks.edit",
                ok = listOf("archive_unpins", "forget", "mark_read", "mark_unread", "rekey", "too_many_pins"),
                err = listOf("rekey_without_target"),
            )
            op<MuteChoicesRequest, MuteChoicesAnswer>("mute.choices")
            op<MuteStatusRequest, MuteStatusAnswer>(
                "mute.status",
                ok = listOf("always", "another_day", "ended"),
            )
            op<StarredRowsRequest, StarredRowsAnswer>("starred.rows", ok = listOf("empty", "empty_partial"))
            op<LocalStarsRequest, LocalStarsAnswer>(
                "starred.local",
                ok = listOf("forget_thread", "set", "unset"),
                err = listOf("set_without_star"),
            )
            op<RepliesRequest, RepliesAnswer>(
                "quick_replies.edit",
                ok = listOf("add", "move_up", "remove", "restore"),
                err = listOf("duplicate", "empty"),
            )
            op<InsertReplyRequest, InsertReplyAnswer>("quick_replies.insert", ok = listOf("blank_draft"))
            op<ForwardPlanRequest, ForwardPlanAnswer>(
                "forward.plan",
                ok = listOf("blank", "picture", "picture_without_caption", "too_long_for_sms"),
                err = listOf("bad_kind"),
            )
            op<ForwardWordsRequest, ForwardWordsAnswer>("forward.words", ok = listOf("failed", "no_title"))
            op<TextDetectRequest, TextDetectAnswer>(
                "text_detect.detect",
                ok = listOf("not_a_number", "two_numbers"),
            )
            op<DateDetectRequest, DateDetectAnswer>("date_detect.detect", ok = listOf("all_day", "past"))
            op<PlacesRequest, PlacesAnswer>("location_link.find", ok = listOf("geo_with_name", "none"))
            op<ShareLocationRequest, ShareLocationAnswer>("location_link.share", ok = listOf("placeholder"))
            op<SharedCardRequest, SharedCardAnswer>("shared_card.read", ok = listOf("no_name", "unreadable"))
            op<MimeRequest, MimeAnswer>("shared_card.mime", ok = listOf("image"))
            op<MessageInfoRequest, MessageInfoAnswer>(
                "message_info.rows",
                ok = listOf("incoming_whatsapp", "unknown"),
            )
            op<MakeReminderRequest, MakeReminderAnswer>(
                "message_reminder.make",
                ok = listOf("chat", "tonight"),
                err = listOf("blank_tag"),
            )
            op<ReminderSlotsRequest, ReminderSlotsAnswer>("message_reminder.slots", ok = listOf("late_evening"))
            op<ReminderListRequest, ReminderListAnswer>(
                "message_reminder.list",
                ok = listOf("corrupt", "skips_bad_entries"),
            )
            op<CallReminderRequest, CallReminderAnswer>(
                "call_reminder.make",
                ok = listOf("no_name"),
                err = listOf("private_number"),
            )
            op<CallReminderListRequest, CallReminderListAnswer>("call_reminder.list", ok = listOf("corrupt"))
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
    ) {
        val okAnswer = OkEnvelope.serializer(serializer<Ans>())
        put("$op.request", serializer<Req>())
        put("$op.answer", okAnswer)
        for (label in ok) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", okAnswer)
        }
        for (label in err) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", ErrEnvelope.serializer())
        }
    }
}
