package dev.rovsti.core.contracts

import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
import dev.rovsti.core.model.phone.DirRequest
import dev.rovsti.core.model.stops.AlarmPlanAnswer
import dev.rovsti.core.model.stops.AlarmPlanRequest
import dev.rovsti.core.model.stops.ArrivalClockRequest
import dev.rovsti.core.model.stops.BackupExportAnswer
import dev.rovsti.core.model.stops.BackupExportRequest
import dev.rovsti.core.model.stops.BackupParseAnswer
import dev.rovsti.core.model.stops.BackupParseRequest
import dev.rovsti.core.model.stops.ClockAnswer
import dev.rovsti.core.model.stops.CoordFormatAnswer
import dev.rovsti.core.model.stops.CoordFormatRequest
import dev.rovsti.core.model.stops.CoordParseAnswer
import dev.rovsti.core.model.stops.CoordParseRequest
import dev.rovsti.core.model.stops.CrossingReadAnswer
import dev.rovsti.core.model.stops.CrossingReadRequest
import dev.rovsti.core.model.stops.CrossingValidateAnswer
import dev.rovsti.core.model.stops.CrossingValidateRequest
import dev.rovsti.core.model.stops.DestParseAnswer
import dev.rovsti.core.model.stops.DestParseRequest
import dev.rovsti.core.model.stops.DestShareAnswer
import dev.rovsti.core.model.stops.DestShareRequest
import dev.rovsti.core.model.stops.DismissAnswer
import dev.rovsti.core.model.stops.DismissRequest
import dev.rovsti.core.model.stops.FileTextAnswer
import dev.rovsti.core.model.stops.FollowAnswer
import dev.rovsti.core.model.stops.FollowRequest
import dev.rovsti.core.model.stops.FormatDistanceRequest
import dev.rovsti.core.model.stops.FracToCoordAnswer
import dev.rovsti.core.model.stops.FracToCoordRequest
import dev.rovsti.core.model.stops.GtfsImportAnswer
import dev.rovsti.core.model.stops.GtfsImportRequest
import dev.rovsti.core.model.stops.GtfsSearchAnswer
import dev.rovsti.core.model.stops.GtfsSearchRequest
import dev.rovsti.core.model.stops.HistoryEditAnswer
import dev.rovsti.core.model.stops.HistoryEditRequest
import dev.rovsti.core.model.stops.HistoryExportAnswer
import dev.rovsti.core.model.stops.HistoryExportRequest
import dev.rovsti.core.model.stops.HistoryListAnswer
import dev.rovsti.core.model.stops.HistoryListRequest
import dev.rovsti.core.model.stops.HistoryTextAnswer
import dev.rovsti.core.model.stops.HistoryTextRequest
import dev.rovsti.core.model.stops.MeasureAnswer
import dev.rovsti.core.model.stops.MeasureRequest
import dev.rovsti.core.model.stops.NameAnswer
import dev.rovsti.core.model.stops.NoticeRenderRequest
import dev.rovsti.core.model.stops.NoticeResultAnswer
import dev.rovsti.core.model.stops.NoticeResultRequest
import dev.rovsti.core.model.stops.NoticeValidateAnswer
import dev.rovsti.core.model.stops.NoticeValidateRequest
import dev.rovsti.core.model.stops.PickLabelsAnswer
import dev.rovsti.core.model.stops.PickLabelsRequest
import dev.rovsti.core.model.stops.PickReplayAnswer
import dev.rovsti.core.model.stops.PickReplayRequest
import dev.rovsti.core.model.stops.PlacesExportRequest
import dev.rovsti.core.model.stops.PlacesImportAnswer
import dev.rovsti.core.model.stops.PlacesImportRequest
import dev.rovsti.core.model.stops.PollAnswer
import dev.rovsti.core.model.stops.PollRequest
import dev.rovsti.core.model.stops.PrefsChoicesAnswer
import dev.rovsti.core.model.stops.PrefsEditAnswer
import dev.rovsti.core.model.stops.PrefsEditRequest
import dev.rovsti.core.model.stops.PrefsLoadAnswer
import dev.rovsti.core.model.stops.PrefsSaveRequest
import dev.rovsti.core.model.stops.ProjectAnswer
import dev.rovsti.core.model.stops.ProjectRequest
import dev.rovsti.core.model.stops.PrunePlanAnswer
import dev.rovsti.core.model.stops.PrunePlanRequest
import dev.rovsti.core.model.stops.RecentAnswer
import dev.rovsti.core.model.stops.RecentRequest
import dev.rovsti.core.model.stops.SaveOfferAnswer
import dev.rovsti.core.model.stops.SaveOfferRequest
import dev.rovsti.core.model.stops.SavedEditAnswer
import dev.rovsti.core.model.stops.SavedEditRequest
import dev.rovsti.core.model.stops.SavedListAnswer
import dev.rovsti.core.model.stops.SavedListRequest
import dev.rovsti.core.model.stops.SavedLoadAnswer
import dev.rovsti.core.model.stops.SavedLoadRequest
import dev.rovsti.core.model.stops.ShortcutAnswer
import dev.rovsti.core.model.stops.ShortcutRequest
import dev.rovsti.core.model.stops.SnapAnswer
import dev.rovsti.core.model.stops.SnapRequest
import dev.rovsti.core.model.stops.SnoozeAnswer
import dev.rovsti.core.model.stops.SnoozeRequest
import dev.rovsti.core.model.stops.StatsAnswer
import dev.rovsti.core.model.stops.StatsRequest
import dev.rovsti.core.model.stops.StatusShareRequest
import dev.rovsti.core.model.stops.StoreReadAnswer
import dev.rovsti.core.model.stops.StoreReadRequest
import dev.rovsti.core.model.stops.StoreTripEndAnswer
import dev.rovsti.core.model.stops.StoreTripEndRequest
import dev.rovsti.core.model.stops.StoreTripLoadAnswer
import dev.rovsti.core.model.stops.StoreTripSaveRequest
import dev.rovsti.core.model.stops.StoreWriteRequest
import dev.rovsti.core.model.stops.SuggestNameRequest
import dev.rovsti.core.model.stops.TextAnswer
import dev.rovsti.core.model.stops.TimeoutAnswer
import dev.rovsti.core.model.stops.TripEndAnswer
import dev.rovsti.core.model.stops.TripEndRequest
import dev.rovsti.core.model.stops.TripFixAnswer
import dev.rovsti.core.model.stops.TripFixRequest
import dev.rovsti.core.model.stops.TripHandleRequest
import dev.rovsti.core.model.stops.TripRestoreRequest
import dev.rovsti.core.model.stops.TripStartRequest
import dev.rovsti.core.model.stops.TripView
import dev.rovsti.core.model.stops.UsageAnswer
import dev.rovsti.core.model.stops.UsageRequest
import dev.rovsti.core.model.stops.ViewStepAnswer
import dev.rovsti.core.model.stops.ViewStepRequest
import dev.rovsti.core.model.stops.ViewTilesAnswer
import dev.rovsti.core.model.stops.ViewTilesRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `stops.*` and `view.*` (E2.12). */
object StopsContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<TripStartRequest, TripView>(
                "stops.trip_start",
                ok = listOf("notice"),
                err = listOf("same_point", "too_short"),
            )
            op<TripRestoreRequest, TripView>(
                "stops.trip_restore",
            )
            op<TripHandleRequest, TripView>(
                "stops.trip_state",
                err = listOf("closed"),
            )
            op<TripFixRequest, TripFixAnswer>(
                "stops.trip_fix",
                ok = listOf("far", "inaccurate"),
                err = listOf("closed"),
            )
            op<NoticeResultRequest, NoticeResultAnswer>(
                "stops.trip_notice_result",
                ok = listOf("give_up", "retry"),
                err = listOf("bad_result"),
            )
            op<SnoozeRequest, SnoozeAnswer>(
                "stops.trip_snooze",
                ok = listOf("clamped"),
            )
            op<SnoozeRequest, TimeoutAnswer>(
                "stops.trip_timeout",
            )
            op<DismissRequest, DismissAnswer>(
                "stops.trip_dismiss",
            )
            op<StatusShareRequest, TextAnswer>(
                "stops.trip_status_share",
                ok = listOf("crossed"),
            )
            op<TripEndRequest, TripEndAnswer>(
                "stops.trip_end",
                ok = listOf("cancelled"),
                err = listOf("bad_outcome"),
            )
            op<ArrivalClockRequest, ClockAnswer>(
                "stops.arrival_clock",
                ok = listOf("zero_eta"),
            )
            op<CrossingValidateRequest, CrossingValidateAnswer>(
                "stops.crossing_validate",
                err = listOf("invalid_coord", "too_long", "too_short"),
            )
            op<CrossingReadRequest, CrossingReadAnswer>(
                "stops.crossing_read",
                ok = listOf("outside"),
                err = listOf("too_short"),
            )
            op<DestParseRequest, DestParseAnswer>(
                "stops.dest_parse",
                ok = listOf("bare", "geo_uri", "stop_share"),
                err = listOf("nothing", "short_link"),
            )
            op<DestShareRequest, DestShareAnswer>(
                "stops.dest_share",
                ok = listOf("unnamed"),
            )
            op<PickReplayRequest, PickReplayAnswer>(
                "stops.pick_replay",
                ok = listOf("drag", "empty", "full_and_menu", "moving", "too_close"),
            )
            op<PickLabelsRequest, PickLabelsAnswer>(
                "stops.pick_labels",
                ok = listOf("empty"),
            )
            op<SaveOfferRequest, SaveOfferAnswer>(
                "stops.pick_save_offer",
                ok = listOf("incomplete", "new"),
            )
            op<SnapRequest, SnapAnswer>(
                "stops.snap",
                ok = listOf("held", "no_candidate", "slow"),
            )
            op<SavedLoadRequest, SavedLoadAnswer>(
                "stops.saved_load",
                ok = listOf("missing", "old_format"),
            )
            op<SavedEditRequest, SavedEditAnswer>(
                "stops.saved_edit",
                ok = listOf("cycle_colour", "favourite", "move", "remove", "rename", "restore", "set_colour"),
                err = listOf("bad_name", "duplicate", "remove_missing", "short_gate"),
            )
            op<SavedListRequest, SavedListAnswer>(
                "stops.saved_list",
                ok = listOf("by_name", "empty", "search"),
                err = listOf("bad_sort"),
            )
            op<SuggestNameRequest, NameAnswer>(
                "stops.saved_suggest_name",
                ok = listOf("no_feed"),
            )
            op<ShortcutRequest, ShortcutAnswer>(
                "stops.saved_shortcut",
                ok = listOf("long_name"),
            )
            op<PlacesExportRequest, FileTextAnswer>(
                "stops.places_export",
                ok = listOf("json"),
                err = listOf("bad_format"),
            )
            op<PlacesImportRequest, PlacesImportAnswer>(
                "stops.places_import",
                ok = listOf("apply"),
                err = listOf("empty", "garbage"),
            )
            op<HistoryListRequest, HistoryListAnswer>(
                "stops.history_list",
                ok = listOf("arrived_work", "none"),
                err = listOf("bad_filter"),
            )
            op<HistoryEditRequest, HistoryEditAnswer>(
                "stops.history_edit",
                ok = listOf("clear", "prune", "push", "put_back", "take_missing"),
            )
            op<PrunePlanRequest, PrunePlanAnswer>(
                "stops.history_prune_plan",
            )
            op<UsageRequest, UsageAnswer>(
                "stops.history_usage",
                ok = listOf("unused"),
            )
            op<RecentRequest, RecentAnswer>(
                "stops.history_recent",
            )
            op<HistoryExportRequest, HistoryExportAnswer>(
                "stops.history_export",
                ok = listOf("gpx_two"),
                err = listOf("bad_format"),
            )
            op<HistoryTextRequest, HistoryTextAnswer>(
                "stops.history_text",
                ok = listOf("empty"),
            )
            op<StatsRequest, StatsAnswer>(
                "stops.stats",
                ok = listOf("empty"),
            )
            op<DirRequest, StoreTripLoadAnswer>(
                "stops.store_trip_load",
                ok = listOf("none"),
            )
            op<StoreTripSaveRequest, Empty>(
                "stops.store_trip_save",
            )
            op<StoreTripEndRequest, StoreTripEndAnswer>(
                "stops.store_trip_end",
                ok = listOf("no_trip"),
                err = listOf("bad_outcome"),
            )
            op<StoreReadRequest, StoreReadAnswer>(
                "stops.store_read",
                ok = listOf("draft", "history", "missing"),
                err = listOf("unknown"),
            )
            op<StoreWriteRequest, Empty>(
                "stops.store_write",
                ok = listOf("history"),
                err = listOf("bad_value"),
            )
            op<DirRequest, PrefsLoadAnswer>(
                "stops.prefs_load",
                ok = listOf("missing"),
            )
            op<PrefsSaveRequest, Empty>(
                "stops.prefs_save",
            )
            op<PrefsEditRequest, PrefsEditAnswer>(
                "stops.prefs_edit",
                ok =
                    listOf(
                        "alarm_defaults",
                        "next_appearance",
                        "next_snooze",
                        "next_tile_cache",
                        "next_vibration",
                        "next_volume",
                        "notice",
                        "notice_when",
                        "show_saved",
                        "sound",
                        "sound_blocked",
                        "template",
                        "tile_url",
                    ),
                err = listOf("template_empty", "tile_url_bad"),
            )
            op<Empty, PrefsChoicesAnswer>(
                "stops.prefs_choices",
            )
            op<NoticeRenderRequest, TextAnswer>(
                "stops.notice_render",
                ok = listOf("no_eta"),
            )
            op<NoticeValidateRequest, NoticeValidateAnswer>(
                "stops.notice_validate",
                err = listOf("empty", "too_long"),
            )
            op<AlarmPlanRequest, AlarmPlanAnswer>(
                "stops.alarm_plan",
                ok = listOf("custom"),
            )
            op<PollRequest, PollAnswer>(
                "stops.poll",
                ok = listOf("close", "unknown"),
            )
            op<GtfsImportRequest, GtfsImportAnswer>(
                "stops.gtfs_import",
                ok = listOf("saved"),
                err = listOf("missing_file", "not_a_zip"),
            )
            op<GtfsSearchRequest, GtfsSearchAnswer>(
                "stops.gtfs_search",
                ok = listOf("no_feed"),
            )
            op<CoordFormatRequest, CoordFormatAnswer>(
                "stops.coord_format",
                ok = listOf("plain"),
            )
            op<CoordParseRequest, CoordParseAnswer>(
                "stops.coord_parse",
                ok = listOf("decimal", "nothing"),
            )
            op<MeasureRequest, MeasureAnswer>(
                "stops.measure",
                ok = listOf("empty", "one_point"),
            )
            op<BackupExportRequest, BackupExportAnswer>(
                "stops.backup_export",
            )
            op<BackupParseRequest, BackupParseAnswer>(
                "stops.backup_parse",
                err = listOf("newer", "not_json", "other_app"),
            )
            op<FormatDistanceRequest, TextAnswer>(
                "stops.format_distance",
                ok = listOf("km"),
            )
            op<ProjectRequest, ProjectAnswer>(
                "view.project",
                ok = listOf("clamped_scale", "high_latitude"),
            )
            op<ViewStepRequest, ViewStepAnswer>(
                "view.step",
                ok = listOf("fit_nothing", "nearest", "pan", "zoom_about", "zoom_nan"),
            )
            op<ViewTilesRequest, ViewTilesAnswer>(
                "view.tiles",
                ok = listOf("over_budget", "world"),
            )
            op<FracToCoordRequest, FracToCoordAnswer>(
                "view.frac_to_coord",
                ok = listOf("polar", "world"),
            )
            op<FollowRequest, FollowAnswer>(
                "view.follow",
                ok = listOf("following_off", "no_fix", "pan"),
                err = listOf("bad_mode"),
            )
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
