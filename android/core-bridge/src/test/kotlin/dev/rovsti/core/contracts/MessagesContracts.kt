package dev.rovsti.core.contracts

import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
import dev.rovsti.core.model.messages.AccessAnswer
import dev.rovsti.core.model.messages.AccessRequest
import dev.rovsti.core.model.messages.AddRuleRequest
import dev.rovsti.core.model.messages.BackupExportAnswer
import dev.rovsti.core.model.messages.BackupExportRequest
import dev.rovsti.core.model.messages.BackupParseAnswer
import dev.rovsti.core.model.messages.BackupParseRequest
import dev.rovsti.core.model.messages.BodyRequest
import dev.rovsti.core.model.messages.BulkAnswer
import dev.rovsti.core.model.messages.BulkRequest
import dev.rovsti.core.model.messages.BytesAnswer
import dev.rovsti.core.model.messages.ClassifyAnswer
import dev.rovsti.core.model.messages.ClassifyRequest
import dev.rovsti.core.model.messages.ContainsLinkAnswer
import dev.rovsti.core.model.messages.DirRequest
import dev.rovsti.core.model.messages.DraftsAnswer
import dev.rovsti.core.model.messages.DraftsPutRequest
import dev.rovsti.core.model.messages.DraftsRekeyRequest
import dev.rovsti.core.model.messages.DraftsSaveRequest
import dev.rovsti.core.model.messages.DueSoonAnswer
import dev.rovsti.core.model.messages.DueSoonRequest
import dev.rovsti.core.model.messages.FilterAnswer
import dev.rovsti.core.model.messages.FilterRequest
import dev.rovsti.core.model.messages.InsertRuleAnswer
import dev.rovsti.core.model.messages.InsertRuleRequest
import dev.rovsti.core.model.messages.LinksAnswer
import dev.rovsti.core.model.messages.MergeAnswer
import dev.rovsti.core.model.messages.MergeRequest
import dev.rovsti.core.model.messages.NotifyAnswer
import dev.rovsti.core.model.messages.NotifyRequest
import dev.rovsti.core.model.messages.NotifyRespRequest
import dev.rovsti.core.model.messages.OpenLinksAnswer
import dev.rovsti.core.model.messages.OpenLinksRequest
import dev.rovsti.core.model.messages.OtpAnswer
import dev.rovsti.core.model.messages.OtpRequest
import dev.rovsti.core.model.messages.PduParseAnswer
import dev.rovsti.core.model.messages.PduParseRequest
import dev.rovsti.core.model.messages.PendingDeleteAnswer
import dev.rovsti.core.model.messages.PendingDeleteRequest
import dev.rovsti.core.model.messages.PersonAnswer
import dev.rovsti.core.model.messages.PersonForJidRequest
import dev.rovsti.core.model.messages.PersonForNumberRequest
import dev.rovsti.core.model.messages.PreferredChannelAnswer
import dev.rovsti.core.model.messages.PreferredChannelRequest
import dev.rovsti.core.model.messages.PrefsEditAnswer
import dev.rovsti.core.model.messages.PrefsEditRequest
import dev.rovsti.core.model.messages.PrefsLoadAnswer
import dev.rovsti.core.model.messages.PrefsSaveRequest
import dev.rovsti.core.model.messages.QueueAnswer
import dev.rovsti.core.model.messages.QuickTimesAnswer
import dev.rovsti.core.model.messages.QuickTimesRequest
import dev.rovsti.core.model.messages.RemoveRuleAnswer
import dev.rovsti.core.model.messages.RemoveRuleRequest
import dev.rovsti.core.model.messages.RestorePlan
import dev.rovsti.core.model.messages.RestorePlanRequest
import dev.rovsti.core.model.messages.RulesAnswer
import dev.rovsti.core.model.messages.ScheduleAddRequest
import dev.rovsti.core.model.messages.ScheduleCancelAnswer
import dev.rovsti.core.model.messages.ScheduleCancelRequest
import dev.rovsti.core.model.messages.ScheduleLoadRequest
import dev.rovsti.core.model.messages.ScheduleReconcileAnswer
import dev.rovsti.core.model.messages.ScheduleReconcileRequest
import dev.rovsti.core.model.messages.ScheduleValidateAnswer
import dev.rovsti.core.model.messages.ScheduleValidateRequest
import dev.rovsti.core.model.messages.SegmentInfo
import dev.rovsti.core.model.messages.SegmentsRequest
import dev.rovsti.core.model.messages.SendReqRequest
import dev.rovsti.core.model.messages.SmsKeyAnswer
import dev.rovsti.core.model.messages.SmsKeyRequest
import dev.rovsti.core.model.messages.StrengthAnswer
import dev.rovsti.core.model.messages.StrengthRequest
import dev.rovsti.core.model.messages.TagAnswer
import dev.rovsti.core.model.messages.TagRequest
import dev.rovsti.core.model.messages.TimelineAnswer
import dev.rovsti.core.model.messages.TimelineRequest
import dev.rovsti.core.model.messages.ToMessageAnswer
import dev.rovsti.core.model.messages.ToMessageRequest
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
