package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.core.Empty
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

/**
 * The core half of the Messages app's domain logic (E2.11a): the unified inbox, SMS/MMS payloads and one thread's
 * timeline, MMS PDUs, passcodes, spam rules, notifications, the scheduled-send queue, the encrypted backup, and the
 * preferences, drafts and permission decisions. Stateless except that [merge] and [personForNumber] read a contact
 * index through its handle (`ContactIndexHandle`). The chat tools are `ChatOps` (E2.11b).
 */
interface MessagesOps {
    /** One inbox row per person, folding SMS threads and WhatsApp chats; matches against the contact index [MergeRequest.contacts] (`search.index_new`). */
    suspend fun merge(req: MergeRequest): Outcome<MergeAnswer>

    /** Where a reply goes by default: the channel of the row's newest thread. */
    suspend fun preferredChannel(req: PreferredChannelRequest): Outcome<PreferredChannelAnswer>

    /** The person behind a phone number as the provider reports it; `null` for sender ids ("BANK"). */
    suspend fun personForNumber(req: PersonForNumberRequest): Outcome<PersonAnswer>

    /** The person behind a WhatsApp JID; `@g.us` is a group, `@lid` identities never merge. */
    suspend fun personForJid(req: PersonForJidRequest): Outcome<PersonAnswer>

    /** Which rows pass the selected chip, every chip's count, which chips to show and the empty-state text. */
    suspend fun filter(req: FilterRequest): Outcome<FilterAnswer>

    /** The actions to offer for a multi-selection, the pin caps and the notices that follow. */
    suspend fun bulk(req: BulkRequest): Outcome<BulkAnswer>

    /** Deletes that wait out a five-second Undo window: add, undo, collect the due ones. */
    suspend fun pendingDelete(req: PendingDeleteRequest): Outcome<PendingDeleteAnswer>

    /** What a body costs in SMS parts (GSM-7 160/153, UCS-2 70/67). */
    suspend fun segments(req: SegmentsRequest): Outcome<SegmentInfo>

    /** The unified-model view of a `Telephony.Sms` row. */
    suspend fun toMessage(req: ToMessageRequest): Outcome<ToMessageAnswer>

    /** Converts between a timeline key and the provider id the delete bridge takes (`sms:12`). */
    suspend fun key(req: SmsKeyRequest): Outcome<SmsKeyAnswer>

    /** One SMS/MMS thread as an ascending list: replays pages, optimistic sends and status events. */
    suspend fun timeline(req: TimelineRequest): Outcome<TimelineAnswer>

    /** Decodes an MMS notification or a downloaded message (bytes as base64). */
    suspend fun pduParse(req: PduParseRequest): Outcome<PduParseAnswer>

    /** Encodes an `m-send-req` (bytes as base64). */
    suspend fun pduBuildSendReq(req: SendReqRequest): Outcome<BytesAnswer>

    /** Encodes the `m-notify-resp` that tells the network the message was retrieved or deferred. */
    suspend fun pduBuildNotifyResp(req: NotifyRespRequest): Outcome<BytesAnswer>

    /** Finds the first passcode in a message body (UTF-16 offsets). */
    suspend fun otp(req: OtpRequest): Outcome<OtpAnswer>

    /** Whether a message is spam under the rules, the contacts and the blocklist, and why. */
    suspend fun classify(req: ClassifyRequest): Outcome<ClassifyAnswer>

    /** Adds a rule after trimming it; empty, duplicate and over-the-cap rules are `invalid` with the sentence to show. */
    suspend fun addRule(req: AddRuleRequest): Outcome<RulesAnswer>

    /** Puts a rule back at an index (Undo of a delete). */
    suspend fun insertRule(req: InsertRuleRequest): Outcome<InsertRuleAnswer>

    /** Removes the rule at an index. */
    suspend fun removeRule(req: RemoveRuleRequest): Outcome<RemoveRuleAnswer>

    /** The links in a body, in order, ready to open. */
    suspend fun links(req: BodyRequest): Outcome<LinksAnswer>

    /** The "Open link" pills of a body. */
    suspend fun openLinks(req: OpenLinksRequest): Outcome<OpenLinksAnswer>

    /** Whether a body holds something that looks like a link. */
    suspend fun containsLink(req: BodyRequest): Outcome<ContainsLinkAnswer>

    /** The notification digest of a conversation, without its spam. */
    suspend fun notify(req: NotifyRequest): Outcome<NotifyAnswer>

    /** Whether a message may be scheduled for a time, and why not. */
    suspend fun scheduleValidate(req: ScheduleValidateRequest): Outcome<ScheduleValidateAnswer>

    /** Adds an item to the queue string; `invalid` for a too-soon time, an empty body, a full queue or a repeated id. */
    suspend fun scheduleAdd(req: ScheduleAddRequest): Outcome<QueueAnswer>

    /** Removes an item by id. */
    suspend fun scheduleCancel(req: ScheduleCancelRequest): Outcome<ScheduleCancelAnswer>

    /** Removes what the platform reports as fired and returns the items that failed. */
    suspend fun scheduleReconcile(req: ScheduleReconcileRequest): Outcome<ScheduleReconcileAnswer>

    /** The items due within a window, to (re)register with the alarm service. */
    suspend fun scheduleDueSoon(req: DueSoonRequest): Outcome<DueSoonAnswer>

    /** Reads the queue string tolerantly: a corrupt one is empty, an oversized one is cut to the cap. */
    suspend fun scheduleLoad(req: ScheduleLoadRequest): Outcome<QueueAnswer>

    /** The three quick picks of the schedule sheet in the viewer's zone. */
    suspend fun quickTimes(req: QuickTimesRequest): Outcome<QuickTimesAnswer>

    /** Seals messages, rules and the schedule into an encrypted `RMB1` file (bytes as base64). */
    suspend fun backupExport(req: BackupExportRequest): Outcome<BackupExportAnswer>

    /** Opens a backup; a wrong passphrase is `auth`, anything else unreadable is `parse`. */
    suspend fun backupParse(req: BackupParseRequest): Outcome<BackupParseAnswer>

    /** Which backed-up SMS rows are missing from the device. */
    suspend fun backupPlan(req: RestorePlanRequest): Outcome<RestorePlan>

    /** A rough guide for the passphrase field; it never blocks a backup. */
    suspend fun passphraseStrength(req: StrengthRequest): Outcome<StrengthAnswer>

    /** Reads `messages-prefs.json`; a missing or corrupt file gives the defaults. */
    suspend fun prefsLoad(req: DirRequest): Outcome<PrefsLoadAnswer>

    /** Saves the preferences atomically. */
    suspend fun prefsSave(req: PrefsSaveRequest): Outcome<Empty>

    /** Applies one named edit (mute, mark spam, allow a sender, reset, ...) so the rules stay in Rust. */
    suspend fun prefsEdit(req: PrefsEditRequest): Outcome<PrefsEditAnswer>

    /** Reads the unsent drafts by conversation tag. */
    suspend fun draftsLoad(req: DirRequest): Outcome<DraftsAnswer>

    /** Saves the drafts atomically; bad tags and blank texts are dropped. */
    suspend fun draftsSave(req: DraftsSaveRequest): Outcome<Empty>

    /** Sets or clears one draft in a drafts map. */
    suspend fun draftsPut(req: DraftsPutRequest): Outcome<DraftsAnswer>

    /** Moves the draft of a conversation that merged into another. */
    suspend fun draftsRekey(req: DraftsRekeyRequest): Outcome<DraftsAnswer>

    /** What to ask for a permission, which banners to show and what to pull again on return. */
    suspend fun access(req: AccessRequest): Outcome<AccessAnswer>

    /** Converts between a thread and its notification tag (`sms:7`, `wa:<jid>`). */
    suspend fun tag(req: TagRequest): Outcome<TagAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "inbox.merge",
                "inbox.preferred_channel",
                "inbox.person_for_number",
                "inbox.person_for_jid",
                "inbox.filter",
                "inbox.bulk",
                "inbox.pending_delete",
                "sms.segments",
                "sms.to_message",
                "sms.key",
                "sms_timeline.build",
                "mms_pdu.parse",
                "mms_pdu.build_send_req",
                "mms_pdu.build_notify_resp",
                "otp.find",
                "spam.classify",
                "spam.add_rule",
                "spam.insert_rule",
                "spam.remove_rule",
                "spam.links",
                "spam.open_links",
                "spam.contains_link",
                "notify.build",
                "schedule.validate",
                "schedule.add",
                "schedule.cancel",
                "schedule.reconcile",
                "schedule.due_soon",
                "schedule.load",
                "schedule.quick_times",
                "msg_backup.export",
                "msg_backup.parse",
                "msg_backup.plan",
                "msg_backup.strength",
                "messages.prefs_load",
                "messages.prefs_save",
                "messages.prefs_edit",
                "messages.drafts_load",
                "messages.drafts_save",
                "messages.drafts_put",
                "messages.drafts_rekey",
                "messages.access",
                "messages.tag",
            )
    }
}

/** [MessagesOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeMessagesOps(
    private val core: RustCore = NativeRustCore(),
) : MessagesOps {
    override suspend fun merge(req: MergeRequest) =
        core.call("inbox.merge", req, MergeRequest.serializer(), MergeAnswer.serializer())

    override suspend fun preferredChannel(req: PreferredChannelRequest) =
        core.call(
            "inbox.preferred_channel",
            req,
            PreferredChannelRequest.serializer(),
            PreferredChannelAnswer.serializer(),
        )

    override suspend fun personForNumber(req: PersonForNumberRequest) =
        core.call("inbox.person_for_number", req, PersonForNumberRequest.serializer(), PersonAnswer.serializer())

    override suspend fun personForJid(req: PersonForJidRequest) =
        core.call("inbox.person_for_jid", req, PersonForJidRequest.serializer(), PersonAnswer.serializer())

    override suspend fun filter(req: FilterRequest) =
        core.call("inbox.filter", req, FilterRequest.serializer(), FilterAnswer.serializer())

    override suspend fun bulk(req: BulkRequest) =
        core.call("inbox.bulk", req, BulkRequest.serializer(), BulkAnswer.serializer())

    override suspend fun pendingDelete(req: PendingDeleteRequest) =
        core.call("inbox.pending_delete", req, PendingDeleteRequest.serializer(), PendingDeleteAnswer.serializer())

    override suspend fun segments(req: SegmentsRequest) =
        core.call("sms.segments", req, SegmentsRequest.serializer(), SegmentInfo.serializer())

    override suspend fun toMessage(req: ToMessageRequest) =
        core.call("sms.to_message", req, ToMessageRequest.serializer(), ToMessageAnswer.serializer())

    override suspend fun key(req: SmsKeyRequest) =
        core.call("sms.key", req, SmsKeyRequest.serializer(), SmsKeyAnswer.serializer())

    override suspend fun timeline(req: TimelineRequest) =
        core.call("sms_timeline.build", req, TimelineRequest.serializer(), TimelineAnswer.serializer())

    override suspend fun pduParse(req: PduParseRequest) =
        core.call("mms_pdu.parse", req, PduParseRequest.serializer(), PduParseAnswer.serializer())

    override suspend fun pduBuildSendReq(req: SendReqRequest) =
        core.call("mms_pdu.build_send_req", req, SendReqRequest.serializer(), BytesAnswer.serializer())

    override suspend fun pduBuildNotifyResp(req: NotifyRespRequest) =
        core.call("mms_pdu.build_notify_resp", req, NotifyRespRequest.serializer(), BytesAnswer.serializer())

    override suspend fun otp(req: OtpRequest) =
        core.call("otp.find", req, OtpRequest.serializer(), OtpAnswer.serializer())

    override suspend fun classify(req: ClassifyRequest) =
        core.call("spam.classify", req, ClassifyRequest.serializer(), ClassifyAnswer.serializer())

    override suspend fun addRule(req: AddRuleRequest) =
        core.call("spam.add_rule", req, AddRuleRequest.serializer(), RulesAnswer.serializer())

    override suspend fun insertRule(req: InsertRuleRequest) =
        core.call("spam.insert_rule", req, InsertRuleRequest.serializer(), InsertRuleAnswer.serializer())

    override suspend fun removeRule(req: RemoveRuleRequest) =
        core.call("spam.remove_rule", req, RemoveRuleRequest.serializer(), RemoveRuleAnswer.serializer())

    override suspend fun links(req: BodyRequest) =
        core.call("spam.links", req, BodyRequest.serializer(), LinksAnswer.serializer())

    override suspend fun openLinks(req: OpenLinksRequest) =
        core.call("spam.open_links", req, OpenLinksRequest.serializer(), OpenLinksAnswer.serializer())

    override suspend fun containsLink(req: BodyRequest) =
        core.call("spam.contains_link", req, BodyRequest.serializer(), ContainsLinkAnswer.serializer())

    override suspend fun notify(req: NotifyRequest) =
        core.call("notify.build", req, NotifyRequest.serializer(), NotifyAnswer.serializer())

    override suspend fun scheduleValidate(req: ScheduleValidateRequest) =
        core.call("schedule.validate", req, ScheduleValidateRequest.serializer(), ScheduleValidateAnswer.serializer())

    override suspend fun scheduleAdd(req: ScheduleAddRequest) =
        core.call("schedule.add", req, ScheduleAddRequest.serializer(), QueueAnswer.serializer())

    override suspend fun scheduleCancel(req: ScheduleCancelRequest) =
        core.call("schedule.cancel", req, ScheduleCancelRequest.serializer(), ScheduleCancelAnswer.serializer())

    override suspend fun scheduleReconcile(req: ScheduleReconcileRequest) =
        core.call(
            "schedule.reconcile",
            req,
            ScheduleReconcileRequest.serializer(),
            ScheduleReconcileAnswer.serializer(),
        )

    override suspend fun scheduleDueSoon(req: DueSoonRequest) =
        core.call("schedule.due_soon", req, DueSoonRequest.serializer(), DueSoonAnswer.serializer())

    override suspend fun scheduleLoad(req: ScheduleLoadRequest) =
        core.call("schedule.load", req, ScheduleLoadRequest.serializer(), QueueAnswer.serializer())

    override suspend fun quickTimes(req: QuickTimesRequest) =
        core.call("schedule.quick_times", req, QuickTimesRequest.serializer(), QuickTimesAnswer.serializer())

    override suspend fun backupExport(req: BackupExportRequest) =
        core.call("msg_backup.export", req, BackupExportRequest.serializer(), BackupExportAnswer.serializer())

    override suspend fun backupParse(req: BackupParseRequest) =
        core.call("msg_backup.parse", req, BackupParseRequest.serializer(), BackupParseAnswer.serializer())

    override suspend fun backupPlan(req: RestorePlanRequest) =
        core.call("msg_backup.plan", req, RestorePlanRequest.serializer(), RestorePlan.serializer())

    override suspend fun passphraseStrength(req: StrengthRequest) =
        core.call("msg_backup.strength", req, StrengthRequest.serializer(), StrengthAnswer.serializer())

    override suspend fun prefsLoad(req: DirRequest) =
        core.call("messages.prefs_load", req, DirRequest.serializer(), PrefsLoadAnswer.serializer())

    override suspend fun prefsSave(req: PrefsSaveRequest) =
        core.call("messages.prefs_save", req, PrefsSaveRequest.serializer(), Empty.serializer())

    override suspend fun prefsEdit(req: PrefsEditRequest) =
        core.call("messages.prefs_edit", req, PrefsEditRequest.serializer(), PrefsEditAnswer.serializer())

    override suspend fun draftsLoad(req: DirRequest) =
        core.call("messages.drafts_load", req, DirRequest.serializer(), DraftsAnswer.serializer())

    override suspend fun draftsSave(req: DraftsSaveRequest) =
        core.call("messages.drafts_save", req, DraftsSaveRequest.serializer(), Empty.serializer())

    override suspend fun draftsPut(req: DraftsPutRequest) =
        core.call("messages.drafts_put", req, DraftsPutRequest.serializer(), DraftsAnswer.serializer())

    override suspend fun draftsRekey(req: DraftsRekeyRequest) =
        core.call("messages.drafts_rekey", req, DraftsRekeyRequest.serializer(), DraftsAnswer.serializer())

    override suspend fun access(req: AccessRequest) =
        core.call("messages.access", req, AccessRequest.serializer(), AccessAnswer.serializer())

    override suspend fun tag(req: TagRequest) =
        core.call("messages.tag", req, TagRequest.serializer(), TagAnswer.serializer())
}
