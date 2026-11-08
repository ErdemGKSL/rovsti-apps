package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.phone.AssignRequest
import dev.rovsti.core.model.phone.AudioDiffAnswer
import dev.rovsti.core.model.phone.AudioDiffRequest
import dev.rovsti.core.model.phone.AudioRowsAnswer
import dev.rovsti.core.model.phone.AudioRowsRequest
import dev.rovsti.core.model.phone.BadgesAnswer
import dev.rovsti.core.model.phone.BlockCallLineRequest
import dev.rovsti.core.model.phone.BlockCheckAnswer
import dev.rovsti.core.model.phone.BlockCheckRequest
import dev.rovsti.core.model.phone.BlockFileAnswer
import dev.rovsti.core.model.phone.BlockLinesAnswer
import dev.rovsti.core.model.phone.BlockLinesRequest
import dev.rovsti.core.model.phone.BlockManyAnswer
import dev.rovsti.core.model.phone.BlockManyRequest
import dev.rovsti.core.model.phone.BlockParseAnswer
import dev.rovsti.core.model.phone.BlockParseRequest
import dev.rovsti.core.model.phone.BlockedHistoryRequest
import dev.rovsti.core.model.phone.BlockedLine
import dev.rovsti.core.model.phone.BucketAnswer
import dev.rovsti.core.model.phone.BucketRequest
import dev.rovsti.core.model.phone.BuildRowsAnswer
import dev.rovsti.core.model.phone.BuildRowsRequest
import dev.rovsti.core.model.phone.CallCountsAnswer
import dev.rovsti.core.model.phone.CallCountsRequest
import dev.rovsti.core.model.phone.CallDetailsAnswer
import dev.rovsti.core.model.phone.CallDetailsRequest
import dev.rovsti.core.model.phone.CallLogCsvAnswer
import dev.rovsti.core.model.phone.CallLogCsvRequest
import dev.rovsti.core.model.phone.CallLogQueryAnswer
import dev.rovsti.core.model.phone.CallLogQueryRequest
import dev.rovsti.core.model.phone.CanPlaceRequest
import dev.rovsti.core.model.phone.ChecklistAnswer
import dev.rovsti.core.model.phone.ChecklistRequest
import dev.rovsti.core.model.phone.DialTextAnswer
import dev.rovsti.core.model.phone.DialTextRequest
import dev.rovsti.core.model.phone.DigitForAnswer
import dev.rovsti.core.model.phone.DigitForRequest
import dev.rovsti.core.model.phone.DirRequest
import dev.rovsti.core.model.phone.ElapsedAnswer
import dev.rovsti.core.model.phone.ElapsedRequest
import dev.rovsti.core.model.phone.FieldMenuAnswer
import dev.rovsti.core.model.phone.FieldMenuRequest
import dev.rovsti.core.model.phone.FreeDigitsAnswer
import dev.rovsti.core.model.phone.FrequentAnswer
import dev.rovsti.core.model.phone.FrequentRequest
import dev.rovsti.core.model.phone.GroupRequest
import dev.rovsti.core.model.phone.HistoryAnswer
import dev.rovsti.core.model.phone.HistoryRequest
import dev.rovsti.core.model.phone.HistoryTextAnswer
import dev.rovsti.core.model.phone.HistoryTextRequest
import dev.rovsti.core.model.phone.IdsForNumbersAnswer
import dev.rovsti.core.model.phone.IdsForNumbersRequest
import dev.rovsti.core.model.phone.IsEmergencyAnswer
import dev.rovsti.core.model.phone.IsEmergencyRequest
import dev.rovsti.core.model.phone.KeypadTonesAnswer
import dev.rovsti.core.model.phone.KeypadTonesRequest
import dev.rovsti.core.model.phone.MergeExtrasRequest
import dev.rovsti.core.model.phone.MissedExtrasAnswer
import dev.rovsti.core.model.phone.MissedExtrasRequest
import dev.rovsti.core.model.phone.NoteRemoveAnswer
import dev.rovsti.core.model.phone.NoteRemoveRequest
import dev.rovsti.core.model.phone.NoteRestoreRequest
import dev.rovsti.core.model.phone.NoteRowsAnswer
import dev.rovsti.core.model.phone.NoteRowsRequest
import dev.rovsti.core.model.phone.NoteSetAnswer
import dev.rovsti.core.model.phone.NoteSetRequest
import dev.rovsti.core.model.phone.NotesAnswer
import dev.rovsti.core.model.phone.NotesExportAnswer
import dev.rovsti.core.model.phone.NotesExportRequest
import dev.rovsti.core.model.phone.NotesForCallsAnswer
import dev.rovsti.core.model.phone.NotesForCallsRequest
import dev.rovsti.core.model.phone.NotesLoadAnswer
import dev.rovsti.core.model.phone.NotesSaveRequest
import dev.rovsti.core.model.phone.NumbersAnswer
import dev.rovsti.core.model.phone.OnHoldAnswer
import dev.rovsti.core.model.phone.OnHoldRequest
import dev.rovsti.core.model.phone.OverviewAnswer
import dev.rovsti.core.model.phone.OverviewRequest
import dev.rovsti.core.model.phone.PastePlanAnswer
import dev.rovsti.core.model.phone.PasteTonesAnswer
import dev.rovsti.core.model.phone.PasteTonesRequest
import dev.rovsti.core.model.phone.PersonStatsAnswer
import dev.rovsti.core.model.phone.PersonStatsRequest
import dev.rovsti.core.model.phone.PrefsLoadAnswer
import dev.rovsti.core.model.phone.PrefsSaveRequest
import dev.rovsti.core.model.phone.PrunePlanAnswer
import dev.rovsti.core.model.phone.PrunePlanRequest
import dev.rovsti.core.model.phone.RedialAnswer
import dev.rovsti.core.model.phone.RedialRequest
import dev.rovsti.core.model.phone.RefreshDelta
import dev.rovsti.core.model.phone.RefreshDeltaRequest
import dev.rovsti.core.model.phone.ReopenedAnswer
import dev.rovsti.core.model.phone.ReopenedRequest
import dev.rovsti.core.model.phone.ReplyChoicesAnswer
import dev.rovsti.core.model.phone.ReplyChoicesRequest
import dev.rovsti.core.model.phone.RowsAnswer
import dev.rovsti.core.model.phone.SceneAnswer
import dev.rovsti.core.model.phone.SceneDiffAnswer
import dev.rovsti.core.model.phone.SceneDiffRequest
import dev.rovsti.core.model.phone.SceneRequest
import dev.rovsti.core.model.phone.SceneTextsAnswer
import dev.rovsti.core.model.phone.SceneTextsRequest
import dev.rovsti.core.model.phone.SectionStartsAnswer
import dev.rovsti.core.model.phone.SectionStartsRequest
import dev.rovsti.core.model.phone.SetupStepsAnswer
import dev.rovsti.core.model.phone.SimBuildAnswer
import dev.rovsti.core.model.phone.SimBuildRequest
import dev.rovsti.core.model.phone.SimDotAnswer
import dev.rovsti.core.model.phone.SimDotRequest
import dev.rovsti.core.model.phone.SpeedDialMapAnswer
import dev.rovsti.core.model.phone.SpeedDialMapRequest
import dev.rovsti.core.model.phone.TelUriRequest
import dev.rovsti.core.model.phone.TonePlan
import dev.rovsti.core.model.phone.TrackerChangeAnswer
import dev.rovsti.core.model.phone.TrackerHandleAnswer
import dev.rovsti.core.model.phone.TrackerHandleRequest
import dev.rovsti.core.model.phone.TrackerMuteRequest
import dev.rovsti.core.model.phone.TrackerPlacedRequest
import dev.rovsti.core.model.phone.TrackerUpdateRequest
import dev.rovsti.core.model.phone.TrackerViewAnswer
import dev.rovsti.core.model.phone.TrackerViewRequest
import dev.rovsti.core.model.phone.UnassignAnswer
import dev.rovsti.core.model.phone.UnassignRequest
import dev.rovsti.core.model.phone.XiaomiAnswer
import dev.rovsti.core.model.phone.XiaomiRequest

/**
 * The Phone app's domain logic: call history, notes and stats, speed dial, blocklist, the dial string rules, SIM and
 * setup facts, the audio route list, the call scene and the Phone files. Every method is one Rust op ([ops] lists
 * their names; a test checks each has a contract fixture). Stateless except the `phone.wa_tracker_*` family, whose
 * handle is closed with `RustNative.release`.
 */
interface PhoneOps {
    /** Groups call-log entries into Recents rows: runs of consecutive same-number, same-type calls. */
    suspend fun recentsGroup(req: GroupRequest): Outcome<RowsAnswer>

    /** Ready-to-draw row view-models (times relative to `nowMs`). */
    suspend fun recentsBuild(req: BuildRowsRequest): Outcome<BuildRowsAnswer>

    /** How many rows are new / pushed down after a refresh. */
    suspend fun refreshDelta(req: RefreshDeltaRequest): Outcome<RefreshDelta>

    /** Merges WhatsApp calls into the grouped rows, newest first. */
    suspend fun mergeExtras(req: MergeExtrasRequest): Outcome<RowsAnswer>

    /** Unseen missed calls (log + WhatsApp) and the tab badge text. */
    suspend fun missedExtras(req: MissedExtrasRequest): Outcome<MissedExtrasAnswer>

    /** Calls with any of the numbers, newest first. */
    suspend fun history(req: HistoryRequest): Outcome<HistoryAnswer>

    /** Blocked calls with any of the numbers, newest first. */
    suspend fun blockedHistory(req: BlockedHistoryRequest): Outcome<HistoryAnswer>

    /** Title and detail line of one history entry. */
    suspend fun historyText(req: HistoryTextRequest): Outcome<HistoryTextAnswer>

    /** Visible positions where the local day changes. */
    suspend fun sectionStarts(req: SectionStartsRequest): Outcome<SectionStartsAnswer>

    /** Search and filter Recents rows (optionally through call notes). */
    suspend fun callLogQuery(req: CallLogQueryRequest): Outcome<CallLogQueryAnswer>

    /** Who is called most, for the Favorites tab's Frequent row. */
    suspend fun frequent(req: FrequentRequest): Outcome<FrequentAnswer>

    /** Calls per contact id, to feed `search.set_call_counts`. */
    suspend fun callCounts(req: CallCountsRequest): Outcome<CallCountsAnswer>

    /** Log, blocked and WhatsApp call ids of a person's numbers (for deleting). */
    suspend fun idsForNumbers(req: IdsForNumbersRequest): Outcome<IdsForNumbersAnswer>

    /** CSV export of the whole log or one person's calls, with notes. */
    suspend fun callLogCsv(req: CallLogCsvRequest): Outcome<CallLogCsvAnswer>

    /** Which calls are older than an age, with the dialog texts. */
    suspend fun prunePlan(req: PrunePlanRequest): Outcome<PrunePlanAnswer>

    /** Adds, updates or (empty text) removes the note of a call. */
    suspend fun noteSet(req: NoteSetRequest): Outcome<NoteSetAnswer>

    /** Removes a note; the answer holds what Undo restores. */
    suspend fun noteRemove(req: NoteRemoveRequest): Outcome<NoteRemoveAnswer>

    /** Puts a removed note back. */
    suspend fun noteRestore(req: NoteRestoreRequest): Outcome<NotesAnswer>

    /** Rows of the Notes page, newest call first. */
    suspend fun noteRows(req: NoteRowsRequest): Outcome<NoteRowsAnswer>

    /** Which calls have a note (for the glyph on a row). */
    suspend fun notesForCalls(req: NotesForCallsRequest): Outcome<NotesForCallsAnswer>

    /** The details sheet of one call and its copy / share texts. */
    suspend fun callDetails(req: CallDetailsRequest): Outcome<CallDetailsAnswer>

    /** All notes as text and CSV. */
    suspend fun notesExport(req: NotesExportRequest): Outcome<NotesExportAnswer>

    /** Totals, bars and top people for a range. */
    suspend fun statsOverview(req: OverviewRequest): Outcome<OverviewAnswer>

    /** One person's totals and calls. */
    suspend fun statsPerson(req: PersonStatsRequest): Outcome<PersonStatsAnswer>

    /** The calls inside one bar of the chart. */
    suspend fun statsBucket(req: BucketRequest): Outcome<BucketAnswer>

    /** What a long press on a dialpad key does. */
    suspend fun speedDialOnHold(req: OnHoldRequest): Outcome<OnHoldAnswer>

    /** Assigns a slot; errors name the reason (`digit`, `empty_number`, `too_long`). */
    suspend fun speedDialAssign(req: AssignRequest): Outcome<SpeedDialMapAnswer>

    /** Frees a slot. */
    suspend fun speedDialUnassign(req: UnassignRequest): Outcome<UnassignAnswer>

    /** Digits without a speed dial. */
    suspend fun speedDialFreeDigits(req: SpeedDialMapRequest): Outcome<FreeDigitsAnswer>

    /** The slot a person's numbers already hold. */
    suspend fun speedDialDigitFor(req: DigitForRequest): Outcome<DigitForAnswer>

    /** What each assigned dialpad key shows. */
    suspend fun speedDialBadges(req: SpeedDialMapRequest): Outcome<BadgesAnswer>

    /** May this number be blocked, and is it already? */
    suspend fun blockCheck(req: BlockCheckRequest): Outcome<BlockCheckAnswer>

    /** Typed number to block, in dial form. */
    suspend fun blockParse(req: BlockParseRequest): Outcome<BlockParseAnswer>

    /** Numbers found in pasted text. */
    suspend fun blockParseMany(req: BlockManyRequest): Outcome<BlockManyAnswer>

    /** What importing a blocked-numbers file would do. */
    suspend fun blockParseFile(req: BlockManyRequest): Outcome<BlockFileAnswer>

    /** Rows of the Blocked numbers page plus its CSV and share text. */
    suspend fun blockLines(req: BlockLinesRequest): Outcome<BlockLinesAnswer>

    /** One blocked call in a history list. */
    suspend fun blockCallLine(req: BlockCallLineRequest): Outcome<BlockedLine>

    /** Pasted text to dial characters. */
    suspend fun normalisePaste(req: DialTextRequest): Outcome<DialTextAnswer>

    /** The dial string a `tel:` link stands for. */
    suspend fun fromTelUri(req: TelUriRequest): Outcome<DialTextAnswer>

    /** Checks a dial string; `invalid` names `empty` or `no_dialable`. */
    suspend fun validate(req: DialTextRequest): Outcome<DialTextAnswer>

    /** Copy / Paste / Clear entries of the number field menu. */
    suspend fun fieldMenu(req: FieldMenuRequest): Outcome<FieldMenuAnswer>

    /** Phone numbers found in text. */
    suspend fun extractNumbers(req: DialTextRequest): Outcome<NumbersAnswer>

    /** What pasting text does: nothing, one number, a choice. */
    suspend fun pastePlan(req: DialTextRequest): Outcome<PastePlanAnswer>

    /** The number the call button redials. */
    suspend fun redialTarget(req: RedialRequest): Outcome<RedialAnswer>

    /** DTMF tones and pauses of a sequence. */
    suspend fun tonePlan(req: DialTextRequest): Outcome<TonePlan>

    /** The sequence as the owner reads it. */
    suspend fun tonesText(req: DialTextRequest): Outcome<DialTextAnswer>

    /** Short preview of a sequence. */
    suspend fun tonePreview(req: DialTextRequest): Outcome<DialTextAnswer>

    /** Label of the "send tones" paste action, if the clipboard fits. */
    suspend fun pasteTonesLabel(req: PasteTonesRequest): Outcome<PasteTonesAnswer>

    /** Whether typed digits are an emergency number here. */
    suspend fun isEmergency(req: IsEmergencyRequest): Outcome<IsEmergencyAnswer>

    /** SIM chips and picker data from the phone accounts. */
    suspend fun simBuild(req: SimBuildRequest): Outcome<SimBuildAnswer>

    /** A SIM colour moved until it reads against the fill. */
    suspend fun simDot(req: SimDotRequest): Outcome<SimDotAnswer>

    /** The setup items with their texts. */
    suspend fun setupSteps(): Outcome<SetupStepsAnswer>

    /** The checklist state and progress from what Android reports. */
    suspend fun setupChecklist(req: ChecklistRequest): Outcome<ChecklistAnswer>

    /** Manual Xiaomi confirmations: confirm, unconfirm, reconcile, query. */
    suspend fun setupXiaomi(req: XiaomiRequest): Outcome<XiaomiAnswer>

    /** Items that were done and are not any more. */
    suspend fun setupReopened(req: ReopenedRequest): Outcome<ReopenedAnswer>

    /** The route list rows, decision and diff. */
    suspend fun audioRows(req: AudioRowsRequest): Outcome<AudioRowsAnswer>

    /** Arrival / loss between two endpoint lists. */
    suspend fun audioDiff(req: AudioDiffRequest): Outcome<AudioDiffAnswer>

    /** The call screen's layout, roles and dimmed controls. */
    suspend fun sceneOf(req: SceneRequest): Outcome<SceneAnswer>

    /** Which parts of the scene a new snapshot changed. */
    suspend fun sceneDiff(req: SceneDiffRequest): Outcome<SceneDiffAnswer>

    /** Missed / incoming / after-call sentences and the call timer. */
    suspend fun sceneTexts(req: SceneTextsRequest): Outcome<SceneTextsAnswer>

    /** Reads `prefs.json` (defaults when missing or corrupt). */
    suspend fun prefsLoad(req: DirRequest): Outcome<PrefsLoadAnswer>

    /** Saves `prefs.json` atomically. */
    suspend fun prefsSave(req: PrefsSaveRequest): Outcome<Empty>

    /** Reads `call-notes.json`. */
    suspend fun notesLoad(req: DirRequest): Outcome<NotesLoadAnswer>

    /** Saves `call-notes.json` atomically. */
    suspend fun notesSave(req: NotesSaveRequest): Outcome<Empty>

    /** Decline-with-message choices and their limits. */
    suspend fun replyChoices(req: ReplyChoicesRequest): Outcome<ReplyChoicesAnswer>

    /** Whether the keypad plays tones. */
    suspend fun keypadTones(req: KeypadTonesRequest): Outcome<KeypadTonesAnswer>

    /** Opens a WhatsApp call tracker; close it with `RustNative.release`. */
    suspend fun trackerNew(): Outcome<TrackerHandleAnswer>

    /** Folds a gateway update into the tracked call. */
    suspend fun trackerUpdate(req: TrackerUpdateRequest): Outcome<TrackerChangeAnswer>

    /** Starts tracking an outgoing call. */
    suspend fun trackerPlaced(req: TrackerPlacedRequest): Outcome<TrackerChangeAnswer>

    /** The tracked call as the screen shows it. */
    suspend fun trackerView(req: TrackerViewRequest): Outcome<TrackerViewAnswer>

    /** Remembers the mute state. */
    suspend fun trackerMute(req: TrackerMuteRequest): Outcome<Empty>

    /** Forgets the tracked call. */
    suspend fun trackerClear(req: TrackerHandleRequest): Outcome<Empty>

    /** May a WhatsApp call start now? Errors carry the sentence to show. */
    suspend fun canPlace(req: CanPlaceRequest): Outcome<Empty>

    /** `m:ss` or `h:mm:ss`. */
    suspend fun formatElapsed(req: ElapsedRequest): Outcome<ElapsedAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "recents.group",
                "recents.build",
                "recents.refresh_delta",
                "recents.merge_extras",
                "recents.missed_extras",
                "recents.history",
                "recents.blocked_history",
                "recents.history_text",
                "recents.section_starts",
                "call_log.query",
                "call_log.frequent",
                "call_log.counts",
                "call_log.ids_for_numbers",
                "call_log.csv",
                "call_log.prune_plan",
                "call_notes.set",
                "call_notes.remove",
                "call_notes.restore",
                "call_notes.rows",
                "call_notes.for_calls",
                "call_notes.details",
                "call_notes.export",
                "call_stats.overview",
                "call_stats.person",
                "call_stats.bucket",
                "speed_dial.on_hold",
                "speed_dial.assign",
                "speed_dial.unassign",
                "speed_dial.free_digits",
                "speed_dial.digit_for",
                "speed_dial.badges",
                "blocklist.check",
                "blocklist.parse_input",
                "blocklist.parse_many",
                "blocklist.parse_file",
                "blocklist.lines",
                "blocklist.call_line",
                "dial.normalise_paste",
                "dial.from_tel_uri",
                "dial.validate",
                "dial.field_menu",
                "dial.extract_numbers",
                "dial.paste_plan",
                "dial.redial_target",
                "dial.tone_plan",
                "dial.tones_text",
                "dial.tone_preview",
                "dial.paste_tones_label",
                "dial.is_emergency",
                "sim.build",
                "sim.dot",
                "setup.steps",
                "setup.checklist",
                "setup.xiaomi",
                "setup.reopened",
                "audio.rows",
                "audio.diff",
                "scene.of",
                "scene.diff",
                "scene.texts",
                "phone.prefs_load",
                "phone.prefs_save",
                "phone.notes_load",
                "phone.notes_save",
                "phone.reply_choices",
                "phone.keypad_tones",
                "phone.wa_tracker_new",
                "phone.wa_tracker_update",
                "phone.wa_tracker_placed",
                "phone.wa_tracker_view",
                "phone.wa_tracker_mute",
                "phone.wa_tracker_clear",
                "phone.wa_tracker_can_place",
                "phone.wa_tracker_format_elapsed",
            )
    }
}

/** [PhoneOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativePhoneOps(
    private val core: RustCore = NativeRustCore(),
) : PhoneOps {
    override suspend fun recentsGroup(req: GroupRequest) =
        core.call("recents.group", req, GroupRequest.serializer(), RowsAnswer.serializer())

    override suspend fun recentsBuild(req: BuildRowsRequest) =
        core.call("recents.build", req, BuildRowsRequest.serializer(), BuildRowsAnswer.serializer())

    override suspend fun refreshDelta(req: RefreshDeltaRequest) =
        core.call("recents.refresh_delta", req, RefreshDeltaRequest.serializer(), RefreshDelta.serializer())

    override suspend fun mergeExtras(req: MergeExtrasRequest) =
        core.call("recents.merge_extras", req, MergeExtrasRequest.serializer(), RowsAnswer.serializer())

    override suspend fun missedExtras(req: MissedExtrasRequest) =
        core.call("recents.missed_extras", req, MissedExtrasRequest.serializer(), MissedExtrasAnswer.serializer())

    override suspend fun history(req: HistoryRequest) =
        core.call("recents.history", req, HistoryRequest.serializer(), HistoryAnswer.serializer())

    override suspend fun blockedHistory(req: BlockedHistoryRequest) =
        core.call("recents.blocked_history", req, BlockedHistoryRequest.serializer(), HistoryAnswer.serializer())

    override suspend fun historyText(req: HistoryTextRequest) =
        core.call("recents.history_text", req, HistoryTextRequest.serializer(), HistoryTextAnswer.serializer())

    override suspend fun sectionStarts(req: SectionStartsRequest) =
        core.call("recents.section_starts", req, SectionStartsRequest.serializer(), SectionStartsAnswer.serializer())

    override suspend fun callLogQuery(req: CallLogQueryRequest) =
        core.call("call_log.query", req, CallLogQueryRequest.serializer(), CallLogQueryAnswer.serializer())

    override suspend fun frequent(req: FrequentRequest) =
        core.call("call_log.frequent", req, FrequentRequest.serializer(), FrequentAnswer.serializer())

    override suspend fun callCounts(req: CallCountsRequest) =
        core.call("call_log.counts", req, CallCountsRequest.serializer(), CallCountsAnswer.serializer())

    override suspend fun idsForNumbers(req: IdsForNumbersRequest) =
        core.call("call_log.ids_for_numbers", req, IdsForNumbersRequest.serializer(), IdsForNumbersAnswer.serializer())

    override suspend fun callLogCsv(req: CallLogCsvRequest) =
        core.call("call_log.csv", req, CallLogCsvRequest.serializer(), CallLogCsvAnswer.serializer())

    override suspend fun prunePlan(req: PrunePlanRequest) =
        core.call("call_log.prune_plan", req, PrunePlanRequest.serializer(), PrunePlanAnswer.serializer())

    override suspend fun noteSet(req: NoteSetRequest) =
        core.call("call_notes.set", req, NoteSetRequest.serializer(), NoteSetAnswer.serializer())

    override suspend fun noteRemove(req: NoteRemoveRequest) =
        core.call("call_notes.remove", req, NoteRemoveRequest.serializer(), NoteRemoveAnswer.serializer())

    override suspend fun noteRestore(req: NoteRestoreRequest) =
        core.call("call_notes.restore", req, NoteRestoreRequest.serializer(), NotesAnswer.serializer())

    override suspend fun noteRows(req: NoteRowsRequest) =
        core.call("call_notes.rows", req, NoteRowsRequest.serializer(), NoteRowsAnswer.serializer())

    override suspend fun notesForCalls(req: NotesForCallsRequest) =
        core.call("call_notes.for_calls", req, NotesForCallsRequest.serializer(), NotesForCallsAnswer.serializer())

    override suspend fun callDetails(req: CallDetailsRequest) =
        core.call("call_notes.details", req, CallDetailsRequest.serializer(), CallDetailsAnswer.serializer())

    override suspend fun notesExport(req: NotesExportRequest) =
        core.call("call_notes.export", req, NotesExportRequest.serializer(), NotesExportAnswer.serializer())

    override suspend fun statsOverview(req: OverviewRequest) =
        core.call("call_stats.overview", req, OverviewRequest.serializer(), OverviewAnswer.serializer())

    override suspend fun statsPerson(req: PersonStatsRequest) =
        core.call("call_stats.person", req, PersonStatsRequest.serializer(), PersonStatsAnswer.serializer())

    override suspend fun statsBucket(req: BucketRequest) =
        core.call("call_stats.bucket", req, BucketRequest.serializer(), BucketAnswer.serializer())

    override suspend fun speedDialOnHold(req: OnHoldRequest) =
        core.call("speed_dial.on_hold", req, OnHoldRequest.serializer(), OnHoldAnswer.serializer())

    override suspend fun speedDialAssign(req: AssignRequest) =
        core.call("speed_dial.assign", req, AssignRequest.serializer(), SpeedDialMapAnswer.serializer())

    override suspend fun speedDialUnassign(req: UnassignRequest) =
        core.call("speed_dial.unassign", req, UnassignRequest.serializer(), UnassignAnswer.serializer())

    override suspend fun speedDialFreeDigits(req: SpeedDialMapRequest) =
        core.call("speed_dial.free_digits", req, SpeedDialMapRequest.serializer(), FreeDigitsAnswer.serializer())

    override suspend fun speedDialDigitFor(req: DigitForRequest) =
        core.call("speed_dial.digit_for", req, DigitForRequest.serializer(), DigitForAnswer.serializer())

    override suspend fun speedDialBadges(req: SpeedDialMapRequest) =
        core.call("speed_dial.badges", req, SpeedDialMapRequest.serializer(), BadgesAnswer.serializer())

    override suspend fun blockCheck(req: BlockCheckRequest) =
        core.call("blocklist.check", req, BlockCheckRequest.serializer(), BlockCheckAnswer.serializer())

    override suspend fun blockParse(req: BlockParseRequest) =
        core.call("blocklist.parse_input", req, BlockParseRequest.serializer(), BlockParseAnswer.serializer())

    override suspend fun blockParseMany(req: BlockManyRequest) =
        core.call("blocklist.parse_many", req, BlockManyRequest.serializer(), BlockManyAnswer.serializer())

    override suspend fun blockParseFile(req: BlockManyRequest) =
        core.call("blocklist.parse_file", req, BlockManyRequest.serializer(), BlockFileAnswer.serializer())

    override suspend fun blockLines(req: BlockLinesRequest) =
        core.call("blocklist.lines", req, BlockLinesRequest.serializer(), BlockLinesAnswer.serializer())

    override suspend fun blockCallLine(req: BlockCallLineRequest) =
        core.call("blocklist.call_line", req, BlockCallLineRequest.serializer(), BlockedLine.serializer())

    override suspend fun normalisePaste(req: DialTextRequest) =
        core.call("dial.normalise_paste", req, DialTextRequest.serializer(), DialTextAnswer.serializer())

    override suspend fun fromTelUri(req: TelUriRequest) =
        core.call("dial.from_tel_uri", req, TelUriRequest.serializer(), DialTextAnswer.serializer())

    override suspend fun validate(req: DialTextRequest) =
        core.call("dial.validate", req, DialTextRequest.serializer(), DialTextAnswer.serializer())

    override suspend fun fieldMenu(req: FieldMenuRequest) =
        core.call("dial.field_menu", req, FieldMenuRequest.serializer(), FieldMenuAnswer.serializer())

    override suspend fun extractNumbers(req: DialTextRequest) =
        core.call("dial.extract_numbers", req, DialTextRequest.serializer(), NumbersAnswer.serializer())

    override suspend fun pastePlan(req: DialTextRequest) =
        core.call("dial.paste_plan", req, DialTextRequest.serializer(), PastePlanAnswer.serializer())

    override suspend fun redialTarget(req: RedialRequest) =
        core.call("dial.redial_target", req, RedialRequest.serializer(), RedialAnswer.serializer())

    override suspend fun tonePlan(req: DialTextRequest) =
        core.call("dial.tone_plan", req, DialTextRequest.serializer(), TonePlan.serializer())

    override suspend fun tonesText(req: DialTextRequest) =
        core.call("dial.tones_text", req, DialTextRequest.serializer(), DialTextAnswer.serializer())

    override suspend fun tonePreview(req: DialTextRequest) =
        core.call("dial.tone_preview", req, DialTextRequest.serializer(), DialTextAnswer.serializer())

    override suspend fun pasteTonesLabel(req: PasteTonesRequest) =
        core.call("dial.paste_tones_label", req, PasteTonesRequest.serializer(), PasteTonesAnswer.serializer())

    override suspend fun isEmergency(req: IsEmergencyRequest) =
        core.call("dial.is_emergency", req, IsEmergencyRequest.serializer(), IsEmergencyAnswer.serializer())

    override suspend fun simBuild(req: SimBuildRequest) =
        core.call("sim.build", req, SimBuildRequest.serializer(), SimBuildAnswer.serializer())

    override suspend fun simDot(req: SimDotRequest) =
        core.call("sim.dot", req, SimDotRequest.serializer(), SimDotAnswer.serializer())

    override suspend fun setupSteps() = core.call("setup.steps", SetupStepsAnswer.serializer())

    override suspend fun setupChecklist(req: ChecklistRequest) =
        core.call("setup.checklist", req, ChecklistRequest.serializer(), ChecklistAnswer.serializer())

    override suspend fun setupXiaomi(req: XiaomiRequest) =
        core.call("setup.xiaomi", req, XiaomiRequest.serializer(), XiaomiAnswer.serializer())

    override suspend fun setupReopened(req: ReopenedRequest) =
        core.call("setup.reopened", req, ReopenedRequest.serializer(), ReopenedAnswer.serializer())

    override suspend fun audioRows(req: AudioRowsRequest) =
        core.call("audio.rows", req, AudioRowsRequest.serializer(), AudioRowsAnswer.serializer())

    override suspend fun audioDiff(req: AudioDiffRequest) =
        core.call("audio.diff", req, AudioDiffRequest.serializer(), AudioDiffAnswer.serializer())

    override suspend fun sceneOf(req: SceneRequest) =
        core.call("scene.of", req, SceneRequest.serializer(), SceneAnswer.serializer())

    override suspend fun sceneDiff(req: SceneDiffRequest) =
        core.call("scene.diff", req, SceneDiffRequest.serializer(), SceneDiffAnswer.serializer())

    override suspend fun sceneTexts(req: SceneTextsRequest) =
        core.call("scene.texts", req, SceneTextsRequest.serializer(), SceneTextsAnswer.serializer())

    override suspend fun prefsLoad(req: DirRequest) =
        core.call("phone.prefs_load", req, DirRequest.serializer(), PrefsLoadAnswer.serializer())

    override suspend fun prefsSave(req: PrefsSaveRequest) =
        core.call("phone.prefs_save", req, PrefsSaveRequest.serializer(), Empty.serializer())

    override suspend fun notesLoad(req: DirRequest) =
        core.call("phone.notes_load", req, DirRequest.serializer(), NotesLoadAnswer.serializer())

    override suspend fun notesSave(req: NotesSaveRequest) =
        core.call("phone.notes_save", req, NotesSaveRequest.serializer(), Empty.serializer())

    override suspend fun replyChoices(req: ReplyChoicesRequest) =
        core.call("phone.reply_choices", req, ReplyChoicesRequest.serializer(), ReplyChoicesAnswer.serializer())

    override suspend fun keypadTones(req: KeypadTonesRequest) =
        core.call("phone.keypad_tones", req, KeypadTonesRequest.serializer(), KeypadTonesAnswer.serializer())

    override suspend fun trackerNew() = core.call("phone.wa_tracker_new", TrackerHandleAnswer.serializer())

    override suspend fun trackerUpdate(req: TrackerUpdateRequest) =
        core.call("phone.wa_tracker_update", req, TrackerUpdateRequest.serializer(), TrackerChangeAnswer.serializer())

    override suspend fun trackerPlaced(req: TrackerPlacedRequest) =
        core.call("phone.wa_tracker_placed", req, TrackerPlacedRequest.serializer(), TrackerChangeAnswer.serializer())

    override suspend fun trackerView(req: TrackerViewRequest) =
        core.call("phone.wa_tracker_view", req, TrackerViewRequest.serializer(), TrackerViewAnswer.serializer())

    override suspend fun trackerMute(req: TrackerMuteRequest) =
        core.call("phone.wa_tracker_mute", req, TrackerMuteRequest.serializer(), Empty.serializer())

    override suspend fun trackerClear(req: TrackerHandleRequest) =
        core.call("phone.wa_tracker_clear", req, TrackerHandleRequest.serializer(), Empty.serializer())

    override suspend fun canPlace(req: CanPlaceRequest) =
        core.call("phone.wa_tracker_can_place", req, CanPlaceRequest.serializer(), Empty.serializer())

    override suspend fun formatElapsed(req: ElapsedRequest) =
        core.call("phone.wa_tracker_format_elapsed", req, ElapsedRequest.serializer(), ElapsedAnswer.serializer())
}
