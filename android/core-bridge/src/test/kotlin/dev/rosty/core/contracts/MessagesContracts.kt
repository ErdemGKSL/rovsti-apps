package dev.rosty.core.contracts

import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.messages.AccessAnswer
import dev.rosty.core.model.messages.AccessRequest
import dev.rosty.core.model.messages.AddRuleRequest
import dev.rosty.core.model.messages.BackupExportAnswer
import dev.rosty.core.model.messages.BackupExportRequest
import dev.rosty.core.model.messages.BackupParseAnswer
import dev.rosty.core.model.messages.BackupParseRequest
import dev.rosty.core.model.messages.BodyRequest
import dev.rosty.core.model.messages.BulkAnswer
import dev.rosty.core.model.messages.BulkRequest
import dev.rosty.core.model.messages.BytesAnswer
import dev.rosty.core.model.messages.ClassifyAnswer
import dev.rosty.core.model.messages.ClassifyRequest
import dev.rosty.core.model.messages.ContainsLinkAnswer
import dev.rosty.core.model.messages.DirRequest
import dev.rosty.core.model.messages.DraftsAnswer
import dev.rosty.core.model.messages.DraftsPutRequest
import dev.rosty.core.model.messages.DraftsRekeyRequest
import dev.rosty.core.model.messages.DraftsSaveRequest
import dev.rosty.core.model.messages.DueSoonAnswer
import dev.rosty.core.model.messages.DueSoonRequest
import dev.rosty.core.model.messages.FilterAnswer
import dev.rosty.core.model.messages.FilterRequest
import dev.rosty.core.model.messages.InsertRuleAnswer
import dev.rosty.core.model.messages.InsertRuleRequest
import dev.rosty.core.model.messages.LinksAnswer
import dev.rosty.core.model.messages.MergeAnswer
import dev.rosty.core.model.messages.MergeRequest
import dev.rosty.core.model.messages.NotifyAnswer
import dev.rosty.core.model.messages.NotifyRequest
import dev.rosty.core.model.messages.NotifyRespRequest
import dev.rosty.core.model.messages.OpenLinksAnswer
import dev.rosty.core.model.messages.OpenLinksRequest
import dev.rosty.core.model.messages.OtpAnswer
import dev.rosty.core.model.messages.OtpRequest
import dev.rosty.core.model.messages.PduParseAnswer
import dev.rosty.core.model.messages.PduParseRequest
import dev.rosty.core.model.messages.PendingDeleteAnswer
import dev.rosty.core.model.messages.PendingDeleteRequest
import dev.rosty.core.model.messages.PersonAnswer
import dev.rosty.core.model.messages.PersonForJidRequest
import dev.rosty.core.model.messages.PersonForNumberRequest
import dev.rosty.core.model.messages.PreferredChannelAnswer
import dev.rosty.core.model.messages.PreferredChannelRequest
import dev.rosty.core.model.messages.PrefsEditAnswer
import dev.rosty.core.model.messages.PrefsEditRequest
import dev.rosty.core.model.messages.PrefsLoadAnswer
import dev.rosty.core.model.messages.PrefsSaveRequest
import dev.rosty.core.model.messages.QueueAnswer
import dev.rosty.core.model.messages.QuickTimesAnswer
import dev.rosty.core.model.messages.QuickTimesRequest
import dev.rosty.core.model.messages.RemoveRuleAnswer
import dev.rosty.core.model.messages.RemoveRuleRequest
import dev.rosty.core.model.messages.RestorePlan
import dev.rosty.core.model.messages.RestorePlanRequest
import dev.rosty.core.model.messages.RulesAnswer
import dev.rosty.core.model.messages.ScheduleAddRequest
import dev.rosty.core.model.messages.ScheduleCancelAnswer
import dev.rosty.core.model.messages.ScheduleCancelRequest
import dev.rosty.core.model.messages.ScheduleLoadRequest
import dev.rosty.core.model.messages.ScheduleReconcileAnswer
import dev.rosty.core.model.messages.ScheduleReconcileRequest
import dev.rosty.core.model.messages.ScheduleValidateAnswer
import dev.rosty.core.model.messages.ScheduleValidateRequest
import dev.rosty.core.model.messages.SegmentInfo
import dev.rosty.core.model.messages.SegmentsRequest
import dev.rosty.core.model.messages.SendReqRequest
import dev.rosty.core.model.messages.SmsKeyAnswer
import dev.rosty.core.model.messages.SmsKeyRequest
import dev.rosty.core.model.messages.StrengthAnswer
import dev.rosty.core.model.messages.StrengthRequest
import dev.rosty.core.model.messages.TagAnswer
import dev.rosty.core.model.messages.TagRequest
import dev.rosty.core.model.messages.TimelineAnswer
import dev.rosty.core.model.messages.TimelineRequest
import dev.rosty.core.model.messages.ToMessageAnswer
import dev.rosty.core.model.messages.ToMessageRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for the core Messages ops (E2.11a). */
object MessagesContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<MergeRequest, MergeAnswer>("inbox.merge", ok = listOf("empty"), err = listOf("stale_handle"))
            op<PreferredChannelRequest, PreferredChannelAnswer>("inbox.preferred_channel")
            op<PersonForNumberRequest, PersonAnswer>(
                "inbox.person_for_number",
                ok = listOf("sender_id", "unknown_number"),
            )
            op<PersonForJidRequest, PersonAnswer>("inbox.person_for_jid", ok = listOf("group", "lid"))
            op<FilterRequest, FilterAnswer>("inbox.filter", ok = listOf("empty", "many"))
            op<BulkRequest, BulkAnswer>("inbox.bulk", ok = listOf("archive", "none", "partial", "pin_cap"))
            op<PendingDeleteRequest, PendingDeleteAnswer>(
                "inbox.pending_delete",
                ok = listOf("add", "drain", "undo", "undo_unknown"),
                err = listOf("bad_action"),
            )
            op<SegmentsRequest, SegmentInfo>("sms.segments", ok = listOf("long", "unicode"))
            op<ToMessageRequest, ToMessageAnswer>("sms.to_message", ok = listOf("outgoing"))
            op<SmsKeyRequest, SmsKeyAnswer>("sms.key", ok = listOf("bad", "local"))
            op<TimelineRequest, TimelineAnswer>(
                "sms_timeline.build",
                ok = listOf("adopt", "empty", "failed_send"),
                err = listOf("bad_event"),
            )
            op<PduParseRequest, PduParseAnswer>(
                "mms_pdu.parse",
                ok = listOf("notification"),
                err = listOf("bad_kind", "not_base64", "truncated", "wrong_type"),
            )
            op<SendReqRequest, BytesAnswer>("mms_pdu.build_send_req", err = listOf("no_recipient"))
            op<NotifyRespRequest, BytesAnswer>("mms_pdu.build_notify_resp", ok = listOf("deferred"))
            op<OtpRequest, OtpAnswer>("otp.find", ok = listOf("after_emoji", "none"))
            op<ClassifyRequest, ClassifyAnswer>(
                "spam.classify",
                ok = listOf("allowed", "blocked", "link_from_stranger", "passcode_is_ham"),
            )
            op<AddRuleRequest, RulesAnswer>(
                "spam.add_rule",
                ok = listOf("short_code"),
                err = listOf("duplicate", "empty"),
            )
            op<InsertRuleRequest, InsertRuleAnswer>("spam.insert_rule", ok = listOf("past_the_end"))
            op<RemoveRuleRequest, RemoveRuleAnswer>("spam.remove_rule", ok = listOf("out_of_range"))
            op<BodyRequest, LinksAnswer>("spam.links", ok = listOf("none"))
            op<OpenLinksRequest, OpenLinksAnswer>("spam.open_links", ok = listOf("single"))
            op<BodyRequest, ContainsLinkAnswer>("spam.contains_link", ok = listOf("plain"))
            op<NotifyRequest, NotifyAnswer>("notify.build", ok = listOf("group", "only_spam", "passcode"))
            op<ScheduleValidateRequest, ScheduleValidateAnswer>(
                "schedule.validate",
                ok = listOf("empty", "too_soon"),
            )
            op<ScheduleAddRequest, QueueAnswer>(
                "schedule.add",
                ok = listOf("first"),
                err = listOf("duplicate_id", "too_soon"),
            )
            op<ScheduleCancelRequest, ScheduleCancelAnswer>("schedule.cancel", ok = listOf("unknown"))
            op<ScheduleReconcileRequest, ScheduleReconcileAnswer>("schedule.reconcile")
            op<DueSoonRequest, DueSoonAnswer>("schedule.due_soon")
            op<ScheduleLoadRequest, QueueAnswer>("schedule.load", ok = listOf("corrupt"))
            op<QuickTimesRequest, QuickTimesAnswer>("schedule.quick_times", ok = listOf("late_evening"))
            op<BackupExportRequest, BackupExportAnswer>(
                "msg_backup.export",
                err = listOf("empty_passphrase", "short_salt", "too_few_rounds"),
            )
            op<BackupParseRequest, BackupParseAnswer>(
                "msg_backup.parse",
                err = listOf("not_a_backup", "wrong_passphrase"),
            )
            op<RestorePlanRequest, RestorePlan>("msg_backup.plan", ok = listOf("nothing_on_device"))
            op<StrengthRequest, StrengthAnswer>("msg_backup.strength", ok = listOf("fair", "weak"))
            op<DirRequest, PrefsLoadAnswer>("messages.prefs_load", ok = listOf("missing"))
            op<PrefsSaveRequest, Empty>("messages.prefs_save")
            op<PrefsEditRequest, PrefsEditAnswer>(
                "messages.prefs_edit",
                ok = listOf("allow", "expire", "mark_spam", "reset", "unmark_spam", "unmute"),
                err = listOf("bad_edit", "empty_sender"),
            )
            op<DirRequest, DraftsAnswer>("messages.drafts_load", ok = listOf("missing"))
            op<DraftsSaveRequest, Empty>("messages.drafts_save")
            op<DraftsPutRequest, DraftsAnswer>(
                "messages.drafts_put",
                ok = listOf("blank_removes", "new_thread_keeps_none"),
                err = listOf("bad_tag"),
            )
            op<DraftsRekeyRequest, DraftsAnswer>("messages.drafts_rekey")
            op<AccessRequest, AccessAnswer>("messages.access", ok = listOf("defaults", "granted"))
            op<TagRequest, TagAnswer>("messages.tag", ok = listOf("bad", "parse"))
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
