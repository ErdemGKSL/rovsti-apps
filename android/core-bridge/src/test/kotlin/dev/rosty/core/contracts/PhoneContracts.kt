package dev.rosty.core.contracts

import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.phone.AssignRequest
import dev.rosty.core.model.phone.AudioDiffAnswer
import dev.rosty.core.model.phone.AudioDiffRequest
import dev.rosty.core.model.phone.AudioRowsAnswer
import dev.rosty.core.model.phone.AudioRowsRequest
import dev.rosty.core.model.phone.BackupExportAnswer
import dev.rosty.core.model.phone.BackupExportRequest
import dev.rosty.core.model.phone.BackupImportAnswer
import dev.rosty.core.model.phone.BackupImportRequest
import dev.rosty.core.model.phone.BadgesAnswer
import dev.rosty.core.model.phone.BlockCallLineRequest
import dev.rosty.core.model.phone.BlockCheckAnswer
import dev.rosty.core.model.phone.BlockCheckRequest
import dev.rosty.core.model.phone.BlockFileAnswer
import dev.rosty.core.model.phone.BlockLinesAnswer
import dev.rosty.core.model.phone.BlockLinesRequest
import dev.rosty.core.model.phone.BlockManyAnswer
import dev.rosty.core.model.phone.BlockManyRequest
import dev.rosty.core.model.phone.BlockParseAnswer
import dev.rosty.core.model.phone.BlockParseRequest
import dev.rosty.core.model.phone.BlockedHistoryRequest
import dev.rosty.core.model.phone.BlockedLine
import dev.rosty.core.model.phone.BucketAnswer
import dev.rosty.core.model.phone.BucketRequest
import dev.rosty.core.model.phone.BuildRowsAnswer
import dev.rosty.core.model.phone.BuildRowsRequest
import dev.rosty.core.model.phone.CallCountsAnswer
import dev.rosty.core.model.phone.CallCountsRequest
import dev.rosty.core.model.phone.CallDetailsAnswer
import dev.rosty.core.model.phone.CallDetailsRequest
import dev.rosty.core.model.phone.CallLogCsvAnswer
import dev.rosty.core.model.phone.CallLogCsvRequest
import dev.rosty.core.model.phone.CallLogQueryAnswer
import dev.rosty.core.model.phone.CallLogQueryRequest
import dev.rosty.core.model.phone.CanPlaceRequest
import dev.rosty.core.model.phone.ChecklistAnswer
import dev.rosty.core.model.phone.ChecklistRequest
import dev.rosty.core.model.phone.DialTextAnswer
import dev.rosty.core.model.phone.DialTextRequest
import dev.rosty.core.model.phone.DigitForAnswer
import dev.rosty.core.model.phone.DigitForRequest
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.phone.DisplayNameAnswer
import dev.rosty.core.model.phone.DisplayNameRequest
import dev.rosty.core.model.phone.ElapsedAnswer
import dev.rosty.core.model.phone.ElapsedRequest
import dev.rosty.core.model.phone.FieldMenuAnswer
import dev.rosty.core.model.phone.FieldMenuRequest
import dev.rosty.core.model.phone.FreeDigitsAnswer
import dev.rosty.core.model.phone.FrequentAnswer
import dev.rosty.core.model.phone.FrequentRequest
import dev.rosty.core.model.phone.GroupRequest
import dev.rosty.core.model.phone.HistoryAnswer
import dev.rosty.core.model.phone.HistoryRequest
import dev.rosty.core.model.phone.HistoryTextAnswer
import dev.rosty.core.model.phone.HistoryTextRequest
import dev.rosty.core.model.phone.IdsForNumbersAnswer
import dev.rosty.core.model.phone.IdsForNumbersRequest
import dev.rosty.core.model.phone.IsEmergencyAnswer
import dev.rosty.core.model.phone.IsEmergencyRequest
import dev.rosty.core.model.phone.KeypadTonesAnswer
import dev.rosty.core.model.phone.KeypadTonesRequest
import dev.rosty.core.model.phone.MergeExtrasRequest
import dev.rosty.core.model.phone.MissedExtrasAnswer
import dev.rosty.core.model.phone.MissedExtrasRequest
import dev.rosty.core.model.phone.NoteRemoveAnswer
import dev.rosty.core.model.phone.NoteRemoveRequest
import dev.rosty.core.model.phone.NoteRestoreRequest
import dev.rosty.core.model.phone.NoteRowsAnswer
import dev.rosty.core.model.phone.NoteRowsRequest
import dev.rosty.core.model.phone.NoteSetAnswer
import dev.rosty.core.model.phone.NoteSetRequest
import dev.rosty.core.model.phone.NotesAnswer
import dev.rosty.core.model.phone.NotesExportAnswer
import dev.rosty.core.model.phone.NotesExportRequest
import dev.rosty.core.model.phone.NotesForCallsAnswer
import dev.rosty.core.model.phone.NotesForCallsRequest
import dev.rosty.core.model.phone.NotesLoadAnswer
import dev.rosty.core.model.phone.NotesSaveRequest
import dev.rosty.core.model.phone.NumbersAnswer
import dev.rosty.core.model.phone.OnHoldAnswer
import dev.rosty.core.model.phone.OnHoldRequest
import dev.rosty.core.model.phone.OverviewAnswer
import dev.rosty.core.model.phone.OverviewRequest
import dev.rosty.core.model.phone.PastePlanAnswer
import dev.rosty.core.model.phone.PasteTonesAnswer
import dev.rosty.core.model.phone.PasteTonesRequest
import dev.rosty.core.model.phone.PersonStatsAnswer
import dev.rosty.core.model.phone.PersonStatsRequest
import dev.rosty.core.model.phone.PrefsLoadAnswer
import dev.rosty.core.model.phone.PrefsSaveRequest
import dev.rosty.core.model.phone.PrunePlanAnswer
import dev.rosty.core.model.phone.PrunePlanRequest
import dev.rosty.core.model.phone.RedialAnswer
import dev.rosty.core.model.phone.RedialRequest
import dev.rosty.core.model.phone.RefreshDelta
import dev.rosty.core.model.phone.RefreshDeltaRequest
import dev.rosty.core.model.phone.ReopenedAnswer
import dev.rosty.core.model.phone.ReopenedRequest
import dev.rosty.core.model.phone.ReplyChoicesAnswer
import dev.rosty.core.model.phone.ReplyChoicesRequest
import dev.rosty.core.model.phone.RowsAnswer
import dev.rosty.core.model.phone.SceneAnswer
import dev.rosty.core.model.phone.SceneDiffAnswer
import dev.rosty.core.model.phone.SceneDiffRequest
import dev.rosty.core.model.phone.SceneRequest
import dev.rosty.core.model.phone.SceneTextsAnswer
import dev.rosty.core.model.phone.SceneTextsRequest
import dev.rosty.core.model.phone.SectionStartsAnswer
import dev.rosty.core.model.phone.SectionStartsRequest
import dev.rosty.core.model.phone.SetupStepsAnswer
import dev.rosty.core.model.phone.SimBuildAnswer
import dev.rosty.core.model.phone.SimBuildRequest
import dev.rosty.core.model.phone.SimDotAnswer
import dev.rosty.core.model.phone.SimDotRequest
import dev.rosty.core.model.phone.SpeedDialMapAnswer
import dev.rosty.core.model.phone.SpeedDialMapRequest
import dev.rosty.core.model.phone.TelUriRequest
import dev.rosty.core.model.phone.TonePlan
import dev.rosty.core.model.phone.TrackerChangeAnswer
import dev.rosty.core.model.phone.TrackerHandleAnswer
import dev.rosty.core.model.phone.TrackerHandleRequest
import dev.rosty.core.model.phone.TrackerMuteRequest
import dev.rosty.core.model.phone.TrackerPlacedRequest
import dev.rosty.core.model.phone.TrackerUpdateRequest
import dev.rosty.core.model.phone.TrackerViewAnswer
import dev.rosty.core.model.phone.TrackerViewRequest
import dev.rosty.core.model.phone.UnassignAnswer
import dev.rosty.core.model.phone.UnassignRequest
import dev.rosty.core.model.phone.WaArbitrateAnswer
import dev.rosty.core.model.phone.WaArbitrateRequest
import dev.rosty.core.model.phone.WaHandoffAnswer
import dev.rosty.core.model.phone.WaHandoffRequest
import dev.rosty.core.model.phone.WaScreenAnswer
import dev.rosty.core.model.phone.WaScreenRequest
import dev.rosty.core.model.phone.XiaomiAnswer
import dev.rosty.core.model.phone.XiaomiRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.serializer

/** Fixture stem to model for the Phone ops (`recents.*` ... `phone.*`). */
object PhoneContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<GroupRequest, RowsAnswer>("recents.group", ok = listOf("empty"))
            op<BuildRowsRequest, BuildRowsAnswer>("recents.build")
            op<RefreshDeltaRequest, RefreshDelta>("recents.refresh_delta", ok = listOf("same"))
            op<MergeExtrasRequest, RowsAnswer>("recents.merge_extras")
            op<MissedExtrasRequest, MissedExtrasAnswer>("recents.missed_extras", ok = listOf("none"))
            op<HistoryRequest, HistoryAnswer>("recents.history", ok = listOf("no_numbers"))
            op<BlockedHistoryRequest, HistoryAnswer>("recents.blocked_history")
            op<HistoryTextRequest, HistoryTextAnswer>("recents.history_text", ok = listOf("whatsapp_yesterday"))
            op<SectionStartsRequest, SectionStartsAnswer>("recents.section_starts")
            op<CallLogQueryRequest, CallLogQueryAnswer>("call_log.query", ok = listOf("text", "with_notes"))
            op<FrequentRequest, FrequentAnswer>("call_log.frequent", ok = listOf("starred_left_out"))
            op<CallCountsRequest, CallCountsAnswer>("call_log.counts")
            op<IdsForNumbersRequest, IdsForNumbersAnswer>("call_log.ids_for_numbers")
            op<CallLogCsvRequest, CallLogCsvAnswer>("call_log.csv", ok = listOf("nobody", "person"))
            op<PrunePlanRequest, PrunePlanAnswer>("call_log.prune_plan", ok = listOf("nothing"))
            op<NoteSetRequest, NoteSetAnswer>("call_notes.set", ok = listOf("remove_by_empty_text"))
            op<NoteRemoveRequest, NoteRemoveAnswer>("call_notes.remove", ok = listOf("no_match"))
            op<NoteRestoreRequest, NotesAnswer>("call_notes.restore")
            op<NoteRowsRequest, NoteRowsAnswer>("call_notes.rows", ok = listOf("no_match"))
            op<NotesForCallsRequest, NotesForCallsAnswer>("call_notes.for_calls")
            op<CallDetailsRequest, CallDetailsAnswer>("call_notes.details", ok = listOf("bare"))
            op<NotesExportRequest, NotesExportAnswer>("call_notes.export")
            op<OverviewRequest, OverviewAnswer>("call_stats.overview", ok = listOf("week"))
            op<PersonStatsRequest, PersonStatsAnswer>("call_stats.person")
            op<BucketRequest, BucketAnswer>("call_stats.bucket")
            op<OnHoldRequest, OnHoldAnswer>(
                "speed_dial.on_hold",
                ok = listOf("field_not_empty", "unassigned", "voicemail", "zero"),
            )
            op<AssignRequest, SpeedDialMapAnswer>("speed_dial.assign", err = listOf("bad_digit", "empty_number"))
            op<UnassignRequest, UnassignAnswer>("speed_dial.unassign", ok = listOf("empty_slot"))
            op<SpeedDialMapRequest, FreeDigitsAnswer>("speed_dial.free_digits")
            op<DigitForRequest, DigitForAnswer>("speed_dial.digit_for", ok = listOf("none"))
            op<SpeedDialMapRequest, BadgesAnswer>("speed_dial.badges")
            op<BlockCheckRequest, BlockCheckAnswer>(
                "blocklist.check",
                ok = listOf("already_blocked", "custom_emergency", "emergency", "no_number", "not_a_number"),
            )
            op<BlockParseRequest, BlockParseAnswer>("blocklist.parse_input", err = listOf("emergency", "invalid"))
            op<BlockManyRequest, BlockManyAnswer>("blocklist.parse_many")
            op<BlockManyRequest, BlockFileAnswer>("blocklist.parse_file")
            op<BlockLinesRequest, BlockLinesAnswer>("blocklist.lines")
            op<BlockCallLineRequest, BlockedLine>("blocklist.call_line")
            op<DialTextRequest, DialTextAnswer>("dial.normalise_paste", ok = listOf("encoded"))
            op<TelUriRequest, DialTextAnswer>("dial.from_tel_uri", ok = listOf("empty", "ussd"))
            op<DialTextRequest, DialTextAnswer>("dial.validate", err = listOf("empty", "no_dialable"))
            op<FieldMenuRequest, FieldMenuAnswer>("dial.field_menu", ok = listOf("empty_field", "nothing"))
            op<DialTextRequest, NumbersAnswer>("dial.extract_numbers", ok = listOf("none"))
            op<DialTextRequest, PastePlanAnswer>("dial.paste_plan", ok = listOf("choose", "nothing"))
            op<RedialRequest, RedialAnswer>("dial.redial_target", ok = listOf("empty"))
            op<DialTextRequest, TonePlan>("dial.tone_plan", ok = listOf("empty"))
            op<DialTextRequest, DialTextAnswer>("dial.tones_text")
            op<DialTextRequest, DialTextAnswer>("dial.tone_preview")
            op<PasteTonesRequest, PasteTonesAnswer>("dial.paste_tones_label", ok = listOf("none"))
            op<IsEmergencyRequest, IsEmergencyAnswer>("dial.is_emergency", ok = listOf("local", "ordinary"))
            op<SimBuildRequest, SimBuildAnswer>("sim.build", ok = listOf("clash", "single"))
            op<SimDotRequest, SimDotAnswer>("sim.dot", ok = listOf("readable"))
            op<JsonNull, SetupStepsAnswer>("setup.steps")
            op<ChecklistRequest, ChecklistAnswer>("setup.checklist", ok = listOf("xiaomi"))
            op<XiaomiRequest, XiaomiAnswer>(
                "setup.xiaomi",
                ok = listOf("not_opened", "query", "reconcile"),
                err = listOf("missing_item"),
            )
            op<ReopenedRequest, ReopenedAnswer>("setup.reopened")
            op<AudioRowsRequest, AudioRowsAnswer>("audio.rows", ok = listOf("empty", "ghost"))
            op<AudioDiffRequest, AudioDiffAnswer>("audio.diff", ok = listOf("same"))
            op<SceneRequest, SceneAnswer>("scene.of", ok = listOf("conference", "empty", "incoming_waiting"))
            op<SceneDiffRequest, SceneDiffAnswer>("scene.diff", ok = listOf("same"))
            op<SceneTextsRequest, SceneTextsAnswer>("scene.texts")
            op<DirRequest, PrefsLoadAnswer>("phone.prefs_load", ok = listOf("missing"))
            op<PrefsSaveRequest, Empty>("phone.prefs_save")
            op<DirRequest, NotesLoadAnswer>("phone.notes_load", ok = listOf("missing"))
            op<NotesSaveRequest, Empty>("phone.notes_save")
            op<ReplyChoicesRequest, ReplyChoicesAnswer>("phone.reply_choices", ok = listOf("defaults"))
            op<KeypadTonesRequest, KeypadTonesAnswer>("phone.keypad_tones", ok = listOf("forced_off"))
            op<JsonNull, TrackerHandleAnswer>("phone.wa_tracker_new")
            op<TrackerUpdateRequest, TrackerChangeAnswer>("phone.wa_tracker_update", ok = listOf("ended_unknown_call"))
            op<TrackerPlacedRequest, TrackerChangeAnswer>("phone.wa_tracker_placed")
            op<TrackerViewRequest, TrackerViewAnswer>("phone.wa_tracker_view", err = listOf("stale_handle"))
            op<TrackerMuteRequest, Empty>("phone.wa_tracker_mute")
            op<TrackerHandleRequest, Empty>("phone.wa_tracker_clear")
            op<CanPlaceRequest, Empty>("phone.wa_tracker_can_place", err = listOf("busy", "no_number", "not_connected"))
            op<ElapsedRequest, ElapsedAnswer>("phone.wa_tracker_format_elapsed", ok = listOf("hour"))
            op<WaArbitrateRequest, WaArbitrateAnswer>(
                "phone.wa_arbitrate",
                ok = listOf("waiting", "busy", "in_progress", "no_wa"),
            )
            op<WaScreenRequest, WaScreenAnswer>("phone.wa_screen", ok = listOf("ringing"))
            op<WaHandoffRequest, WaHandoffAnswer>("phone.wa_handoff_number", ok = listOf("chat", "lid"))
            op<BackupExportRequest, BackupExportAnswer>("phone.backup_export")
            op<BackupImportRequest, BackupImportAnswer>(
                "phone.backup_import",
                err = listOf("not_a_backup", "newer", "empty"),
            )
            op<DisplayNameRequest, DisplayNameAnswer>(
                "phone.display_name",
                ok = listOf("first_last", "nameless"),
            )
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
    ) {
        val okAnswer = OkEnvelope.serializer(serializer<Ans>())
        val request: KSerializer<*> = if (Req::class == JsonNull::class) JsonNull.serializer() else serializer<Req>()
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
