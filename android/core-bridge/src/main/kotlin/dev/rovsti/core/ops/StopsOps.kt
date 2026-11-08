package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.core.Empty
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

/**
 * The Stops app's domain logic (E2.12): the trip in progress behind a handle ([ActiveTripHandle]), the pick state
 * machine, saved stops, history, preferences, GTFS, coordinates, the backup file and `view.*`, the map maths the
 * Kotlin `MapCamera` must agree with. Everything but the trip is stateless: requests carry the data, answers the
 * new data. Files of the data directory go through `store*` / `prefs*` (the trip file under the store lock).
 */
interface StopsOps {
    /**
     * Starts a trip and answers its handle ([ActiveTripHandle] wraps it).
     * A pair that cannot form a gate answers `RustError.Invalid` with the sentence as message.
     */
    suspend fun tripStart(req: TripStartRequest): Outcome<TripView>

    /** Opens a handle on a persisted trip (`trip.json`, or a `TripView.snapshot`). */
    suspend fun tripRestore(req: TripRestoreRequest): Outcome<TripView>

    /** The trip as the card shows it. */
    suspend fun tripState(req: TripHandleRequest): Outcome<TripView>

    /** Feeds one location fix; answers what to do next (ring, next fix, text, arrival message to send). */
    suspend fun tripFix(req: TripFixRequest): Outcome<TripFixAnswer>

    /** Records how sending the arrival message went. */
    suspend fun tripNoticeResult(req: NoticeResultRequest): Outcome<NoticeResultAnswer>

    /** Silences the alarm for some minutes (1..=15); `ring_at_ms` is null after the last snooze. */
    suspend fun tripSnooze(req: SnoozeRequest): Outcome<SnoozeAnswer>

    /** The alarm rang its whole timeout unanswered: ring again later without using a snooze. */
    suspend fun tripTimeout(req: SnoozeRequest): Outcome<TimeoutAnswer>

    /** Dismisses the alarm. */
    suspend fun tripDismiss(req: DismissRequest): Outcome<DismissAnswer>

    /** The shareable status of the running trip. */
    suspend fun tripStatusShare(req: StatusShareRequest): Outcome<TextAnswer>

    /** The history record of the trip; with `dir` it is also moved into `history.json`. */
    suspend fun tripEnd(req: TripEndRequest): Outcome<TripEndAnswer>

    /** Local `HH:MM` of now plus the ETA. */
    suspend fun arrivalClock(req: ArrivalClockRequest): Outcome<ClockAnswer>

    /** The circle on AB, or why A and B cannot form one (`RustError.Invalid`, the sentence as message). */
    suspend fun crossingValidate(req: CrossingValidateRequest): Outcome<CrossingValidateAnswer>

    /** Where a position is against the gate, plus the circle outline for the map. */
    suspend fun crossingRead(req: CrossingReadRequest): Outcome<CrossingReadAnswer>

    /** Reads shared text (maps link, `geo:` URI, coordinates, a shared stop). */
    suspend fun destParse(req: DestParseRequest): Outcome<DestParseAnswer>

    /** Share texts and the QR payload of a point or a stop. */
    suspend fun destShare(req: DestShareRequest): Outcome<DestShareAnswer>

    /** Replays the pick gestures on a fresh state machine; answers the state. */
    suspend fun pickReplay(req: PickReplayRequest): Outcome<PickReplayAnswer>

    /** The texts of the picked ends. */
    suspend fun pickLabels(req: PickLabelsRequest): Outcome<PickLabelsAnswer>

    /** Whether the pair can be saved, and whether it already is. */
    suspend fun pickSaveOffer(req: SaveOfferRequest): Outcome<SaveOfferAnswer>

    /** Whether a released pin snaps to a stop. */
    suspend fun snap(req: SnapRequest): Outcome<SnapAnswer>

    /** Reads the text of `saved.json`. */
    suspend fun savedLoad(req: SavedLoadRequest): Outcome<SavedLoadAnswer>

    /** One edit of the saved stops. */
    suspend fun savedEdit(req: SavedEditRequest): Outcome<SavedEditAnswer>

    /** The Saved list: search, sort, nearest chip. */
    suspend fun savedList(req: SavedListRequest): Outcome<SavedListAnswer>

    /** A name to prefill when saving a gate. */
    suspend fun savedSuggestName(req: SuggestNameRequest): Outcome<NameAnswer>

    /** The launcher shortcut of a saved stop. */
    suspend fun savedShortcut(req: ShortcutRequest): Outcome<ShortcutAnswer>

    /** Saved stops as JSON or GPX text. */
    suspend fun placesExport(req: PlacesExportRequest): Outcome<FileTextAnswer>

    /** Plans (and with `apply` does) importing a GPX or Rovsti stops file. */
    suspend fun placesImport(req: PlacesImportRequest): Outcome<PlacesImportAnswer>

    /** The history list: search, filter, last trip card. */
    suspend fun historyList(req: HistoryListRequest): Outcome<HistoryListAnswer>

    /** One edit of the history. */
    suspend fun historyEdit(req: HistoryEditRequest): Outcome<HistoryEditAnswer>

    /** The delete-older-than choices with counts. */
    suspend fun historyPrunePlan(req: PrunePlanRequest): Outcome<PrunePlanAnswer>

    /** How often a gate was used. */
    suspend fun historyUsage(req: UsageRequest): Outcome<UsageAnswer>

    /** Distinct recent pairs and points. */
    suspend fun historyRecent(req: RecentRequest): Outcome<RecentAnswer>

    /** The history as CSV or GPX. */
    suspend fun historyExport(req: HistoryExportRequest): Outcome<HistoryExportAnswer>

    /** Small texts: share text, duration, prune label. */
    suspend fun historyText(req: HistoryTextRequest): Outcome<HistoryTextAnswer>

    /** Statistics of the history. */
    suspend fun stats(req: StatsRequest): Outcome<StatsAnswer>

    /** The trip in progress from `trip.json` (under the trip lock). */
    suspend fun storeTripLoad(req: DirRequest): Outcome<StoreTripLoadAnswer>

    /** Writes `trip.json` (under the trip lock). */
    suspend fun storeTripSave(req: StoreTripSaveRequest): Outcome<Empty>

    /** Moves the trip in progress into the history (under the trip lock). */
    suspend fun storeTripEnd(req: StoreTripEndRequest): Outcome<StoreTripEndAnswer>

    /** Reads `saved.json`, `history.json` or `draft.json`. */
    suspend fun storeRead(req: StoreReadRequest): Outcome<StoreReadAnswer>

    /** Writes one of those files after checking its shape. */
    suspend fun storeWrite(req: StoreWriteRequest): Outcome<Empty>

    /** Loads `stops-prefs.json`. */
    suspend fun prefsLoad(req: DirRequest): Outcome<PrefsLoadAnswer>

    /** Saves `stops-prefs.json`. */
    suspend fun prefsSave(req: PrefsSaveRequest): Outcome<Empty>

    /** One named edit of the preferences. */
    suspend fun prefsEdit(req: PrefsEditRequest): Outcome<PrefsEditAnswer>

    /** The fixed choices of the settings page. */
    suspend fun prefsChoices(req: Empty): Outcome<PrefsChoicesAnswer>

    /** Fills the arrival message template. */
    suspend fun noticeRender(req: NoticeRenderRequest): Outcome<TextAnswer>

    /** Checks a template (`RustError.Invalid` with the reason). */
    suspend fun noticeValidate(req: NoticeValidateRequest): Outcome<NoticeValidateAnswer>

    /** What the service needs to ring, from the preferences. */
    suspend fun alarmPlan(req: AlarmPlanRequest): Outcome<AlarmPlanAnswer>

    /** When the next fix is wanted. */
    suspend fun poll(req: PollRequest): Outcome<PollAnswer>

    /** Imports a GTFS zip by path. */
    suspend fun gtfsImport(req: GtfsImportRequest): Outcome<GtfsImportAnswer>

    /** Searches the imported feed. */
    suspend fun gtfsSearch(req: GtfsSearchRequest): Outcome<GtfsSearchAnswer>

    /** A coordinate in every format, with the view links. */
    suspend fun coordFormat(req: CoordFormatRequest): Outcome<CoordFormatAnswer>

    /** Reads a coordinate typed or pasted in any format. */
    suspend fun coordParse(req: CoordParseRequest): Outcome<CoordParseAnswer>

    /** The measure tool over a list of points. */
    suspend fun measure(req: MeasureRequest): Outcome<MeasureAnswer>

    /** The backup file text. */
    suspend fun backupExport(req: BackupExportRequest): Outcome<BackupExportAnswer>

    /** Reads and cleans a backup file. */
    suspend fun backupParse(req: BackupParseRequest): Outcome<BackupParseAnswer>

    /** Short distance text. */
    suspend fun formatDistance(req: FormatDistanceRequest): Outcome<TextAnswer>

    /** Coordinates to pixels and back, and the zoom of a scale. */
    suspend fun viewProject(req: ProjectRequest): Outcome<ProjectAnswer>

    /** One camera gesture: fit, zoom about a pixel, pan, nearest. */
    suspend fun viewStep(req: ViewStepRequest): Outcome<ViewStepAnswer>

    /** The tiles to fetch for a widget. */
    suspend fun viewTiles(req: ViewTilesRequest): Outcome<ViewTilesAnswer>

    /** Fractional tile position to coordinate and back. */
    suspend fun viewFracToCoord(req: FracToCoordRequest): Outcome<FracToCoordAnswer>

    /** The follow-mode transitions of the Me button. */
    suspend fun viewFollow(req: FollowRequest): Outcome<FollowAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "stops.trip_start",
                "stops.trip_restore",
                "stops.trip_state",
                "stops.trip_fix",
                "stops.trip_notice_result",
                "stops.trip_snooze",
                "stops.trip_timeout",
                "stops.trip_dismiss",
                "stops.trip_status_share",
                "stops.trip_end",
                "stops.arrival_clock",
                "stops.crossing_validate",
                "stops.crossing_read",
                "stops.dest_parse",
                "stops.dest_share",
                "stops.pick_replay",
                "stops.pick_labels",
                "stops.pick_save_offer",
                "stops.snap",
                "stops.saved_load",
                "stops.saved_edit",
                "stops.saved_list",
                "stops.saved_suggest_name",
                "stops.saved_shortcut",
                "stops.places_export",
                "stops.places_import",
                "stops.history_list",
                "stops.history_edit",
                "stops.history_prune_plan",
                "stops.history_usage",
                "stops.history_recent",
                "stops.history_export",
                "stops.history_text",
                "stops.stats",
                "stops.store_trip_load",
                "stops.store_trip_save",
                "stops.store_trip_end",
                "stops.store_read",
                "stops.store_write",
                "stops.prefs_load",
                "stops.prefs_save",
                "stops.prefs_edit",
                "stops.prefs_choices",
                "stops.notice_render",
                "stops.notice_validate",
                "stops.alarm_plan",
                "stops.poll",
                "stops.gtfs_import",
                "stops.gtfs_search",
                "stops.coord_format",
                "stops.coord_parse",
                "stops.measure",
                "stops.backup_export",
                "stops.backup_parse",
                "stops.format_distance",
                "view.project",
                "view.step",
                "view.tiles",
                "view.frac_to_coord",
                "view.follow",
            )
    }
}

/** [StopsOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeStopsOps(
    private val core: RustCore = NativeRustCore(),
) : StopsOps {
    override suspend fun tripStart(req: TripStartRequest) =
        core.call("stops.trip_start", req, TripStartRequest.serializer(), TripView.serializer())

    override suspend fun tripRestore(req: TripRestoreRequest) =
        core.call("stops.trip_restore", req, TripRestoreRequest.serializer(), TripView.serializer())

    override suspend fun tripState(req: TripHandleRequest) =
        core.call("stops.trip_state", req, TripHandleRequest.serializer(), TripView.serializer())

    override suspend fun tripFix(req: TripFixRequest) =
        core.call("stops.trip_fix", req, TripFixRequest.serializer(), TripFixAnswer.serializer())

    override suspend fun tripNoticeResult(req: NoticeResultRequest) =
        core.call("stops.trip_notice_result", req, NoticeResultRequest.serializer(), NoticeResultAnswer.serializer())

    override suspend fun tripSnooze(req: SnoozeRequest) =
        core.call("stops.trip_snooze", req, SnoozeRequest.serializer(), SnoozeAnswer.serializer())

    override suspend fun tripTimeout(req: SnoozeRequest) =
        core.call("stops.trip_timeout", req, SnoozeRequest.serializer(), TimeoutAnswer.serializer())

    override suspend fun tripDismiss(req: DismissRequest) =
        core.call("stops.trip_dismiss", req, DismissRequest.serializer(), DismissAnswer.serializer())

    override suspend fun tripStatusShare(req: StatusShareRequest) =
        core.call("stops.trip_status_share", req, StatusShareRequest.serializer(), TextAnswer.serializer())

    override suspend fun tripEnd(req: TripEndRequest) =
        core.call("stops.trip_end", req, TripEndRequest.serializer(), TripEndAnswer.serializer())

    override suspend fun arrivalClock(req: ArrivalClockRequest) =
        core.call("stops.arrival_clock", req, ArrivalClockRequest.serializer(), ClockAnswer.serializer())

    override suspend fun crossingValidate(req: CrossingValidateRequest) =
        core.call(
            "stops.crossing_validate",
            req,
            CrossingValidateRequest.serializer(),
            CrossingValidateAnswer.serializer(),
        )

    override suspend fun crossingRead(req: CrossingReadRequest) =
        core.call("stops.crossing_read", req, CrossingReadRequest.serializer(), CrossingReadAnswer.serializer())

    override suspend fun destParse(req: DestParseRequest) =
        core.call("stops.dest_parse", req, DestParseRequest.serializer(), DestParseAnswer.serializer())

    override suspend fun destShare(req: DestShareRequest) =
        core.call("stops.dest_share", req, DestShareRequest.serializer(), DestShareAnswer.serializer())

    override suspend fun pickReplay(req: PickReplayRequest) =
        core.call("stops.pick_replay", req, PickReplayRequest.serializer(), PickReplayAnswer.serializer())

    override suspend fun pickLabels(req: PickLabelsRequest) =
        core.call("stops.pick_labels", req, PickLabelsRequest.serializer(), PickLabelsAnswer.serializer())

    override suspend fun pickSaveOffer(req: SaveOfferRequest) =
        core.call("stops.pick_save_offer", req, SaveOfferRequest.serializer(), SaveOfferAnswer.serializer())

    override suspend fun snap(req: SnapRequest) =
        core.call("stops.snap", req, SnapRequest.serializer(), SnapAnswer.serializer())

    override suspend fun savedLoad(req: SavedLoadRequest) =
        core.call("stops.saved_load", req, SavedLoadRequest.serializer(), SavedLoadAnswer.serializer())

    override suspend fun savedEdit(req: SavedEditRequest) =
        core.call("stops.saved_edit", req, SavedEditRequest.serializer(), SavedEditAnswer.serializer())

    override suspend fun savedList(req: SavedListRequest) =
        core.call("stops.saved_list", req, SavedListRequest.serializer(), SavedListAnswer.serializer())

    override suspend fun savedSuggestName(req: SuggestNameRequest) =
        core.call("stops.saved_suggest_name", req, SuggestNameRequest.serializer(), NameAnswer.serializer())

    override suspend fun savedShortcut(req: ShortcutRequest) =
        core.call("stops.saved_shortcut", req, ShortcutRequest.serializer(), ShortcutAnswer.serializer())

    override suspend fun placesExport(req: PlacesExportRequest) =
        core.call("stops.places_export", req, PlacesExportRequest.serializer(), FileTextAnswer.serializer())

    override suspend fun placesImport(req: PlacesImportRequest) =
        core.call("stops.places_import", req, PlacesImportRequest.serializer(), PlacesImportAnswer.serializer())

    override suspend fun historyList(req: HistoryListRequest) =
        core.call("stops.history_list", req, HistoryListRequest.serializer(), HistoryListAnswer.serializer())

    override suspend fun historyEdit(req: HistoryEditRequest) =
        core.call("stops.history_edit", req, HistoryEditRequest.serializer(), HistoryEditAnswer.serializer())

    override suspend fun historyPrunePlan(req: PrunePlanRequest) =
        core.call("stops.history_prune_plan", req, PrunePlanRequest.serializer(), PrunePlanAnswer.serializer())

    override suspend fun historyUsage(req: UsageRequest) =
        core.call("stops.history_usage", req, UsageRequest.serializer(), UsageAnswer.serializer())

    override suspend fun historyRecent(req: RecentRequest) =
        core.call("stops.history_recent", req, RecentRequest.serializer(), RecentAnswer.serializer())

    override suspend fun historyExport(req: HistoryExportRequest) =
        core.call("stops.history_export", req, HistoryExportRequest.serializer(), HistoryExportAnswer.serializer())

    override suspend fun historyText(req: HistoryTextRequest) =
        core.call("stops.history_text", req, HistoryTextRequest.serializer(), HistoryTextAnswer.serializer())

    override suspend fun stats(req: StatsRequest) =
        core.call("stops.stats", req, StatsRequest.serializer(), StatsAnswer.serializer())

    override suspend fun storeTripLoad(req: DirRequest) =
        core.call("stops.store_trip_load", req, DirRequest.serializer(), StoreTripLoadAnswer.serializer())

    override suspend fun storeTripSave(req: StoreTripSaveRequest) =
        core.call("stops.store_trip_save", req, StoreTripSaveRequest.serializer(), Empty.serializer())

    override suspend fun storeTripEnd(req: StoreTripEndRequest) =
        core.call("stops.store_trip_end", req, StoreTripEndRequest.serializer(), StoreTripEndAnswer.serializer())

    override suspend fun storeRead(req: StoreReadRequest) =
        core.call("stops.store_read", req, StoreReadRequest.serializer(), StoreReadAnswer.serializer())

    override suspend fun storeWrite(req: StoreWriteRequest) =
        core.call("stops.store_write", req, StoreWriteRequest.serializer(), Empty.serializer())

    override suspend fun prefsLoad(req: DirRequest) =
        core.call("stops.prefs_load", req, DirRequest.serializer(), PrefsLoadAnswer.serializer())

    override suspend fun prefsSave(req: PrefsSaveRequest) =
        core.call("stops.prefs_save", req, PrefsSaveRequest.serializer(), Empty.serializer())

    override suspend fun prefsEdit(req: PrefsEditRequest) =
        core.call("stops.prefs_edit", req, PrefsEditRequest.serializer(), PrefsEditAnswer.serializer())

    override suspend fun prefsChoices(req: Empty) =
        core.call("stops.prefs_choices", req, Empty.serializer(), PrefsChoicesAnswer.serializer())

    override suspend fun noticeRender(req: NoticeRenderRequest) =
        core.call("stops.notice_render", req, NoticeRenderRequest.serializer(), TextAnswer.serializer())

    override suspend fun noticeValidate(req: NoticeValidateRequest) =
        core.call("stops.notice_validate", req, NoticeValidateRequest.serializer(), NoticeValidateAnswer.serializer())

    override suspend fun alarmPlan(req: AlarmPlanRequest) =
        core.call("stops.alarm_plan", req, AlarmPlanRequest.serializer(), AlarmPlanAnswer.serializer())

    override suspend fun poll(req: PollRequest) =
        core.call("stops.poll", req, PollRequest.serializer(), PollAnswer.serializer())

    override suspend fun gtfsImport(req: GtfsImportRequest) =
        core.call("stops.gtfs_import", req, GtfsImportRequest.serializer(), GtfsImportAnswer.serializer())

    override suspend fun gtfsSearch(req: GtfsSearchRequest) =
        core.call("stops.gtfs_search", req, GtfsSearchRequest.serializer(), GtfsSearchAnswer.serializer())

    override suspend fun coordFormat(req: CoordFormatRequest) =
        core.call("stops.coord_format", req, CoordFormatRequest.serializer(), CoordFormatAnswer.serializer())

    override suspend fun coordParse(req: CoordParseRequest) =
        core.call("stops.coord_parse", req, CoordParseRequest.serializer(), CoordParseAnswer.serializer())

    override suspend fun measure(req: MeasureRequest) =
        core.call("stops.measure", req, MeasureRequest.serializer(), MeasureAnswer.serializer())

    override suspend fun backupExport(req: BackupExportRequest) =
        core.call("stops.backup_export", req, BackupExportRequest.serializer(), BackupExportAnswer.serializer())

    override suspend fun backupParse(req: BackupParseRequest) =
        core.call("stops.backup_parse", req, BackupParseRequest.serializer(), BackupParseAnswer.serializer())

    override suspend fun formatDistance(req: FormatDistanceRequest) =
        core.call("stops.format_distance", req, FormatDistanceRequest.serializer(), TextAnswer.serializer())

    override suspend fun viewProject(req: ProjectRequest) =
        core.call("view.project", req, ProjectRequest.serializer(), ProjectAnswer.serializer())

    override suspend fun viewStep(req: ViewStepRequest) =
        core.call("view.step", req, ViewStepRequest.serializer(), ViewStepAnswer.serializer())

    override suspend fun viewTiles(req: ViewTilesRequest) =
        core.call("view.tiles", req, ViewTilesRequest.serializer(), ViewTilesAnswer.serializer())

    override suspend fun viewFracToCoord(req: FracToCoordRequest) =
        core.call("view.frac_to_coord", req, FracToCoordRequest.serializer(), FracToCoordAnswer.serializer())

    override suspend fun viewFollow(req: FollowRequest) =
        core.call("view.follow", req, FollowRequest.serializer(), FollowAnswer.serializer())
}
