package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.contacts.AccountLabelRequest
import dev.rovsti.core.model.contacts.AgeAnswer
import dev.rovsti.core.model.contacts.AgeRequest
import dev.rovsti.core.model.contacts.AvatarAnswer
import dev.rovsti.core.model.contacts.AvatarRequest
import dev.rovsti.core.model.contacts.CardAnswer
import dev.rovsti.core.model.contacts.CardRequest
import dev.rovsti.core.model.contacts.CleanupAnswer
import dev.rovsti.core.model.contacts.CleanupRequest
import dev.rovsti.core.model.contacts.Confirm
import dev.rovsti.core.model.contacts.ContactHistoryAnswer
import dev.rovsti.core.model.contacts.ContactHistoryRequest
import dev.rovsti.core.model.contacts.DateParseAnswer
import dev.rovsti.core.model.contacts.DateParseRequest
import dev.rovsti.core.model.contacts.DatesAnswer
import dev.rovsti.core.model.contacts.DatesRequest
import dev.rovsti.core.model.contacts.Day
import dev.rovsti.core.model.contacts.DeepFindAnswer
import dev.rovsti.core.model.contacts.DeepFindRequest
import dev.rovsti.core.model.contacts.DeepIndexNewRequest
import dev.rovsti.core.model.contacts.DiffAnswer
import dev.rovsti.core.model.contacts.DiffRequest
import dev.rovsti.core.model.contacts.DirRequest
import dev.rovsti.core.model.contacts.DraftAddAnswer
import dev.rovsti.core.model.contacts.DraftAddRequest
import dev.rovsti.core.model.contacts.DraftEditAnswer
import dev.rovsti.core.model.contacts.DraftEditRequest
import dev.rovsti.core.model.contacts.DraftQueryAnswer
import dev.rovsti.core.model.contacts.DraftQueryRequest
import dev.rovsti.core.model.contacts.DraftRequest
import dev.rovsti.core.model.contacts.DraftsRequest
import dev.rovsti.core.model.contacts.DupesAnswer
import dev.rovsti.core.model.contacts.EventLineAnswer
import dev.rovsti.core.model.contacts.EventLineRequest
import dev.rovsti.core.model.contacts.ExportRequest
import dev.rovsti.core.model.contacts.FavoritesMoveAnswer
import dev.rovsti.core.model.contacts.FavoritesMoveRequest
import dev.rovsti.core.model.contacts.FieldMenuAnswer
import dev.rovsti.core.model.contacts.FieldMenuRequest
import dev.rovsti.core.model.contacts.FrequentContactsAnswer
import dev.rovsti.core.model.contacts.FrequentContactsRequest
import dev.rovsti.core.model.contacts.GroupChangeAnswer
import dev.rovsti.core.model.contacts.GroupChangeRequest
import dev.rovsti.core.model.contacts.GroupLabelsAnswer
import dev.rovsti.core.model.contacts.GroupLabelsRequest
import dev.rovsti.core.model.contacts.GroupSummaryRequest
import dev.rovsti.core.model.contacts.GroupTargetsAnswer
import dev.rovsti.core.model.contacts.GroupTargetsRequest
import dev.rovsti.core.model.contacts.GroupTitleAnswer
import dev.rovsti.core.model.contacts.GroupTitleRequest
import dev.rovsti.core.model.contacts.HandleAnswer
import dev.rovsti.core.model.contacts.IcsAnswer
import dev.rovsti.core.model.contacts.IcsRequest
import dev.rovsti.core.model.contacts.ImportPhotosAnswer
import dev.rovsti.core.model.contacts.ImportPhotosRequest
import dev.rovsti.core.model.contacts.ImportPlanAnswer
import dev.rovsti.core.model.contacts.ImportPlanRequest
import dev.rovsti.core.model.contacts.IntakeAnswer
import dev.rovsti.core.model.contacts.IntakeRequest
import dev.rovsti.core.model.contacts.LabelAnswer
import dev.rovsti.core.model.contacts.LocalYmdRequest
import dev.rovsti.core.model.contacts.MapsUrlAnswer
import dev.rovsti.core.model.contacts.MapsUrlRequest
import dev.rovsti.core.model.contacts.MeStateAnswer
import dev.rovsti.core.model.contacts.MeStateRequest
import dev.rovsti.core.model.contacts.MeToggleAnswer
import dev.rovsti.core.model.contacts.MeToggleRequest
import dev.rovsti.core.model.contacts.MergeAnswer
import dev.rovsti.core.model.contacts.MergeRequest
import dev.rovsti.core.model.contacts.Notice
import dev.rovsti.core.model.contacts.NumberKeyAnswer
import dev.rovsti.core.model.contacts.NumberKeyRequest
import dev.rovsti.core.model.contacts.OptionsAnswer
import dev.rovsti.core.model.contacts.OptionsRequest
import dev.rovsti.core.model.contacts.ParsedAnswer
import dev.rovsti.core.model.contacts.PhotoAcceptsAnswer
import dev.rovsti.core.model.contacts.PhotoAcceptsRequest
import dev.rovsti.core.model.contacts.PhotoCropAnswer
import dev.rovsti.core.model.contacts.PhotoCropRequest
import dev.rovsti.core.model.contacts.PhotoPlanAnswer
import dev.rovsti.core.model.contacts.PhotoPlanRequest
import dev.rovsti.core.model.contacts.PinShortcutAnswer
import dev.rovsti.core.model.contacts.PinShortcutRequest
import dev.rovsti.core.model.contacts.PrefsLoadAnswer
import dev.rovsti.core.model.contacts.PrefsSaveRequest
import dev.rovsti.core.model.contacts.QuickAnswer
import dev.rovsti.core.model.contacts.QuickRequest
import dev.rovsti.core.model.contacts.RecentCreatedAnswer
import dev.rovsti.core.model.contacts.RecentCreatedRequest
import dev.rovsti.core.model.contacts.RecentListsAnswer
import dev.rovsti.core.model.contacts.RecentListsRequest
import dev.rovsti.core.model.contacts.RecentPullAnswer
import dev.rovsti.core.model.contacts.RecentPullRequest
import dev.rovsti.core.model.contacts.RecentViewedAnswer
import dev.rovsti.core.model.contacts.RecentViewedRequest
import dev.rovsti.core.model.contacts.RecipientsAnswer
import dev.rovsti.core.model.contacts.RecipientsRequest
import dev.rovsti.core.model.contacts.RelationTapAnswer
import dev.rovsti.core.model.contacts.RelationTapRequest
import dev.rovsti.core.model.contacts.ReminderAnswer
import dev.rovsti.core.model.contacts.ReminderRequest
import dev.rovsti.core.model.contacts.ScanAnswer
import dev.rovsti.core.model.contacts.ScanRequest
import dev.rovsti.core.model.contacts.ScrubberAnswer
import dev.rovsti.core.model.contacts.ScrubberRequest
import dev.rovsti.core.model.contacts.SelectBarAnswer
import dev.rovsti.core.model.contacts.SelectBarRequest
import dev.rovsti.core.model.contacts.SelectExportAnswer
import dev.rovsti.core.model.contacts.SelectExportRequest
import dev.rovsti.core.model.contacts.StatsAnswer
import dev.rovsti.core.model.contacts.TextFileAnswer
import dev.rovsti.core.model.contacts.TextRequest
import dev.rovsti.core.model.contacts.TrashConfirmRequest
import dev.rovsti.core.model.contacts.TrashDeleteAnswer
import dev.rovsti.core.model.contacts.TrashDeleteRequest
import dev.rovsti.core.model.contacts.TrashListAnswer
import dev.rovsti.core.model.contacts.TrashListRequest
import dev.rovsti.core.model.contacts.TrashPushAnswer
import dev.rovsti.core.model.contacts.TrashPushRequest
import dev.rovsti.core.model.contacts.TrashRestoreAnswer
import dev.rovsti.core.model.contacts.TrashRestoreRequest
import dev.rovsti.core.model.contacts.ValidateAnswer
import dev.rovsti.core.model.contacts.VcardWriteRequest
import dev.rovsti.core.model.contacts.ViewAnswer
import dev.rovsti.core.model.contacts.ViewRequest
import dev.rovsti.core.model.contacts.WebUrlAnswer
import dev.rovsti.core.model.contacts.WebUrlRequest
import dev.rovsti.core.model.core.Empty

/**
 * The Contacts app's domain logic (E2.10): drafts, dates, files, lists, groups, recents, the Trash and the preferences.
 * Every call is a typed round trip through the Rust bridge and never runs on the main thread. Only
 * [deepIndexNew] opens a native object (the detail-search index); every other op is stateless: the request carries
 * the data, the answer the new data. The Trash and preferences ops read and write files in the data directory.
 */
interface ContactsOps {
    /** Cleans a draft the way a save would and names the first problem, if any. */
    suspend fun draftValidate(req: DraftRequest): Outcome<ValidateAnswer>

    /** What a save changed: the summary, the toast text and the draft that undoes it. */
    suspend fun draftDiff(req: DiffRequest): Outcome<DiffAnswer>

    /** A new-contact draft for a search query (a number, an email or a name). */
    suspend fun draftFromQuery(req: DraftQueryRequest): Outcome<DraftQueryAnswer>

    /** Adds numbers and emails to an existing contact, skipping the ones it has. */
    suspend fun draftAdd(req: DraftAddRequest): Outcome<DraftAddAnswer>

    /** Sets the default entry of a list, drops groups of another account, or cleans a draft. */
    suspend fun draftEdit(req: DraftEditRequest): Outcome<DraftEditAnswer>

    /** The default-entry action a field's menu offers. */
    suspend fun fieldMenu(req: FieldMenuRequest): Outcome<FieldMenuAnswer>

    /** The address to open for a typed website. */
    suspend fun webUrl(req: WebUrlRequest): Outcome<WebUrlAnswer>

    /** Open the related contact, or search for the name. */
    suspend fun relationTap(req: RelationTapRequest): Outcome<RelationTapAnswer>

    /** The owner-facing name of an account key. */
    suspend fun accountLabel(req: AccountLabelRequest): Outcome<LabelAnswer>

    /** Upcoming birthdays and anniversaries: all, the Favorites strip and the grouped list. */
    suspend fun dates(req: DatesRequest): Outcome<DatesAnswer>

    /** Reads the text of the editor's date box. */
    suspend fun dateParse(req: DateParseRequest): Outcome<DateParseAnswer>

    /** Age, days until and the age reached next time for a stored date. */
    suspend fun age(req: AgeRequest): Outcome<AgeAnswer>

    /** The card line of an event, the age it reaches and the greeting to send. */
    suspend fun eventLine(req: EventLineRequest): Outcome<EventLineAnswer>

    /** A geo: link that opens an address in any maps app. */
    suspend fun mapsUrl(req: MapsUrlRequest): Outcome<MapsUrlAnswer>

    /** The local calendar date of a timestamp. */
    suspend fun localYmd(req: LocalYmdRequest): Outcome<Day>

    /** One contact's page: numbers (default first), call target and share text. */
    suspend fun card(req: CardRequest): Outcome<CardAnswer>

    /** Groups of likely duplicates. */
    suspend fun dupes(req: DraftsRequest): Outcome<DupesAnswer>

    /** The one contact made of a keeper and the others. */
    suspend fun mergePlan(req: MergeRequest): Outcome<MergeAnswer>

    /** Counts per clean-up filter and the ids under one filter. */
    suspend fun cleanup(req: CleanupRequest): Outcome<CleanupAnswer>

    /** Monogram, initials and hue pair of a name. */
    suspend fun avatar(req: AvatarRequest): Outcome<AvatarAnswer>

    /** Counts over the whole address book. */
    suspend fun stats(req: DraftsRequest): Outcome<StatsAnswer>

    /** The quick actions of a contact row. */
    suspend fun quick(req: QuickRequest): Outcome<QuickAnswer>

    /** Writes contacts as a Google-style CSV. */
    suspend fun csvExport(req: ExportRequest): Outcome<TextFileAnswer>

    /** Reads a Google or Outlook contacts CSV. */
    suspend fun csvImport(req: TextRequest): Outcome<ParsedAnswer>

    /** Writes birthdays and anniversaries as a calendar file. */
    suspend fun ics(req: IcsRequest): Outcome<IcsAnswer>

    /** Reads every card of a vCard text, with the embedded photos. */
    suspend fun vcardParse(req: TextRequest): Outcome<ParsedAnswer>

    /** Writes contacts as vCard 3.0, optionally with photos. */
    suspend fun vcardWrite(req: VcardWriteRequest): Outcome<TextFileAnswer>

    /** Splits incoming cards into those to add and duplicates. */
    suspend fun importPlan(req: ImportPlanRequest): Outcome<ImportPlanAnswer>

    /** The (contact id, photo) pairs to write after an import. */
    suspend fun importPhotos(req: ImportPhotosRequest): Outcome<ImportPhotosAnswer>

    /** The key that identifies a number (its last ten digits). */
    suspend fun numberKey(req: NumberKeyRequest): Outcome<NumberKeyAnswer>

    /** Reads a shared or opened file, or shared text. */
    suspend fun intake(req: IntakeRequest): Outcome<IntakeAnswer>

    /** Reads a QR payload or pasted text into cards, a number or an email. */
    suspend fun scan(req: ScanRequest): Outcome<ScanAnswer>

    /** The square to cut from a picked photo. */
    suspend fun photoPlan(req: PhotoPlanRequest): Outcome<PhotoPlanAnswer>

    /** One step of the crop gesture: rotate, pan or pinch. */
    suspend fun photoCrop(req: PhotoCropRequest): Outcome<PhotoCropAnswer>

    /** Whether a MIME type is a usable photo, and the camera file name. */
    suspend fun photoAccepts(req: PhotoAcceptsRequest): Outcome<PhotoAcceptsAnswer>

    /** The contact list: rows with sections, favorites, scrubber letters and grid columns. */
    suspend fun view(req: ViewRequest): Outcome<ViewAnswer>

    /** The new favorites order after a move. */
    suspend fun favoritesMove(req: FavoritesMoveRequest): Outcome<FavoritesMoveAnswer>

    /** The letter and first row under a touch on the scrubber. */
    suspend fun scrubber(req: ScrubberRequest): Outcome<ScrubberAnswer>

    /** The select-mode bar: title, actions, notices and the share formats. */
    suspend fun selectBar(req: SelectBarRequest): Outcome<SelectBarAnswer>

    /** The vCard or text of the selection, with its file name and notice. */
    suspend fun selectExport(req: SelectExportRequest): Outcome<SelectExportAnswer>

    /** Checks a group title against the other groups. */
    suspend fun groupsTitle(req: GroupTitleRequest): Outcome<GroupTitleAnswer>

    /** The subtitle and join hint of a group. */
    suspend fun groupsLabels(req: GroupLabelsRequest): Outcome<GroupLabelsAnswer>

    /** The members to add and remove after the picker. */
    suspend fun groupsChange(req: GroupChangeRequest): Outcome<GroupChangeAnswer>

    /** The sentence after a membership change. */
    suspend fun groupsSummary(req: GroupSummaryRequest): Outcome<Notice>

    /** The groups the selection can join, with the eligible contacts. */
    suspend fun groupsTargets(req: GroupTargetsRequest): Outcome<GroupTargetsAnswer>

    /** The numbers or emails of a group's members, capped, with the notice. */
    suspend fun groupsRecipients(req: RecipientsRequest): Outcome<RecipientsAnswer>

    /** Marks the contacts that appeared since the last pull as added. */
    suspend fun recentNotePull(req: RecentPullRequest): Outcome<RecentPullAnswer>

    /** Marks one contact as just added. */
    suspend fun recentNoteCreated(req: RecentCreatedRequest): Outcome<RecentCreatedAnswer>

    /** The Recents screen: added, edited and recently viewed contacts. */
    suspend fun recentLists(req: RecentListsRequest): Outcome<RecentListsAnswer>

    /** Notes or forgets a recently viewed contact. */
    suspend fun recentViewed(req: RecentViewedRequest): Outcome<RecentViewedAnswer>

    /** The calls with one contact shown on its card. */
    suspend fun history(req: ContactHistoryRequest): Outcome<ContactHistoryAnswer>

    /** The most called unstarred contacts of the last 30 days. */
    suspend fun frequent(req: FrequentContactsRequest): Outcome<FrequentContactsAnswer>

    /** My card and the emergency list as the settings rows show them. */
    suspend fun meState(req: MeStateRequest): Outcome<MeStateAnswer>

    /** Adds or removes a contact from the emergency list. */
    suspend fun meToggle(req: MeToggleRequest): Outcome<MeToggleAnswer>

    /** The alarms for the next 60 days and what to arm and disarm. */
    suspend fun birthdayReminders(req: ReminderRequest): Outcome<ReminderAnswer>

    /** The ringtone and voicemail texts of a contact. */
    suspend fun options(req: OptionsRequest): Outcome<OptionsAnswer>

    /** The spec of a Home-screen shortcut. */
    suspend fun pinShortcut(req: PinShortcutRequest): Outcome<PinShortcutAnswer>

    /** Builds the detail-search index; close the handle with `RustNative.release`. */
    suspend fun deepIndexNew(req: DeepIndexNewRequest): Outcome<HandleAnswer>

    /** Contacts whose company, title, note, address, email or website match. */
    suspend fun deepFind(req: DeepFindRequest): Outcome<DeepFindAnswer>

    /** The Trash, newest first, with expired entries dropped. */
    suspend fun trashList(req: TrashListRequest): Outcome<TrashListAnswer>

    /** Moves deleted contacts to the Trash. */
    suspend fun trashPush(req: TrashPushRequest): Outcome<TrashPushAnswer>

    /** Takes entries out of the Trash as new drafts. */
    suspend fun trashRestore(req: TrashRestoreRequest): Outcome<TrashRestoreAnswer>

    /** Deletes Trash entries for good. */
    suspend fun trashDelete(req: TrashDeleteRequest): Outcome<TrashDeleteAnswer>

    /** The dialog before deleting for good. */
    suspend fun trashConfirm(req: TrashConfirmRequest): Outcome<Confirm>

    /** The saved preferences, or the defaults. */
    suspend fun prefsLoad(req: DirRequest): Outcome<PrefsLoadAnswer>

    /** Saves the preferences atomically. */
    suspend fun prefsSave(req: PrefsSaveRequest): Outcome<Empty>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "contacts.draft_validate",
                "contacts.draft_diff",
                "contacts.draft_from_query",
                "contacts.draft_add",
                "contacts.draft_edit",
                "contacts.field_menu",
                "contacts.web_url",
                "contacts.relation_tap",
                "contacts.account_label",
                "contacts.dates",
                "contacts.date_parse",
                "contacts.age",
                "contacts.event_line",
                "contacts.maps_url",
                "contacts.local_ymd",
                "contacts.card",
                "contacts.dupes",
                "contacts.merge_plan",
                "contacts.cleanup",
                "contacts.avatar",
                "contacts.stats",
                "contacts.quick",
                "contacts.csv_export",
                "contacts.csv_import",
                "contacts.ics",
                "contacts.vcard_parse",
                "contacts.vcard_write",
                "contacts.import_plan",
                "contacts.import_photos",
                "contacts.number_key",
                "contacts.intake",
                "contacts.scan",
                "contacts.photo_plan",
                "contacts.photo_crop",
                "contacts.photo_accepts",
                "contacts.view",
                "contacts.favorites_move",
                "contacts.scrubber",
                "contacts.select_bar",
                "contacts.select_export",
                "contacts.groups_title",
                "contacts.groups_labels",
                "contacts.groups_change",
                "contacts.groups_summary",
                "contacts.groups_targets",
                "contacts.groups_recipients",
                "contacts.recent_note_pull",
                "contacts.recent_note_created",
                "contacts.recent_lists",
                "contacts.recent_viewed",
                "contacts.history",
                "contacts.frequent",
                "contacts.me_state",
                "contacts.me_toggle",
                "contacts.birthday_reminders",
                "contacts.options",
                "contacts.pin_shortcut",
                "contacts.deep_index_new",
                "contacts.deep_find",
                "contacts.trash_list",
                "contacts.trash_push",
                "contacts.trash_restore",
                "contacts.trash_delete",
                "contacts.trash_confirm",
                "contacts.prefs_load",
                "contacts.prefs_save",
            )
    }
}

/** [ContactsOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeContactsOps(
    private val core: RustCore = NativeRustCore(),
) : ContactsOps {
    override suspend fun draftValidate(req: DraftRequest) =
        core.call("contacts.draft_validate", req, DraftRequest.serializer(), ValidateAnswer.serializer())

    override suspend fun draftDiff(req: DiffRequest) =
        core.call("contacts.draft_diff", req, DiffRequest.serializer(), DiffAnswer.serializer())

    override suspend fun draftFromQuery(req: DraftQueryRequest) =
        core.call("contacts.draft_from_query", req, DraftQueryRequest.serializer(), DraftQueryAnswer.serializer())

    override suspend fun draftAdd(req: DraftAddRequest) =
        core.call("contacts.draft_add", req, DraftAddRequest.serializer(), DraftAddAnswer.serializer())

    override suspend fun draftEdit(req: DraftEditRequest) =
        core.call("contacts.draft_edit", req, DraftEditRequest.serializer(), DraftEditAnswer.serializer())

    override suspend fun fieldMenu(req: FieldMenuRequest) =
        core.call("contacts.field_menu", req, FieldMenuRequest.serializer(), FieldMenuAnswer.serializer())

    override suspend fun webUrl(req: WebUrlRequest) =
        core.call("contacts.web_url", req, WebUrlRequest.serializer(), WebUrlAnswer.serializer())

    override suspend fun relationTap(req: RelationTapRequest) =
        core.call("contacts.relation_tap", req, RelationTapRequest.serializer(), RelationTapAnswer.serializer())

    override suspend fun accountLabel(req: AccountLabelRequest) =
        core.call("contacts.account_label", req, AccountLabelRequest.serializer(), LabelAnswer.serializer())

    override suspend fun dates(req: DatesRequest) =
        core.call("contacts.dates", req, DatesRequest.serializer(), DatesAnswer.serializer())

    override suspend fun dateParse(req: DateParseRequest) =
        core.call("contacts.date_parse", req, DateParseRequest.serializer(), DateParseAnswer.serializer())

    override suspend fun age(req: AgeRequest) =
        core.call("contacts.age", req, AgeRequest.serializer(), AgeAnswer.serializer())

    override suspend fun eventLine(req: EventLineRequest) =
        core.call("contacts.event_line", req, EventLineRequest.serializer(), EventLineAnswer.serializer())

    override suspend fun mapsUrl(req: MapsUrlRequest) =
        core.call("contacts.maps_url", req, MapsUrlRequest.serializer(), MapsUrlAnswer.serializer())

    override suspend fun localYmd(req: LocalYmdRequest) =
        core.call("contacts.local_ymd", req, LocalYmdRequest.serializer(), Day.serializer())

    override suspend fun card(req: CardRequest) =
        core.call("contacts.card", req, CardRequest.serializer(), CardAnswer.serializer())

    override suspend fun dupes(req: DraftsRequest) =
        core.call("contacts.dupes", req, DraftsRequest.serializer(), DupesAnswer.serializer())

    override suspend fun mergePlan(req: MergeRequest) =
        core.call("contacts.merge_plan", req, MergeRequest.serializer(), MergeAnswer.serializer())

    override suspend fun cleanup(req: CleanupRequest) =
        core.call("contacts.cleanup", req, CleanupRequest.serializer(), CleanupAnswer.serializer())

    override suspend fun avatar(req: AvatarRequest) =
        core.call("contacts.avatar", req, AvatarRequest.serializer(), AvatarAnswer.serializer())

    override suspend fun stats(req: DraftsRequest) =
        core.call("contacts.stats", req, DraftsRequest.serializer(), StatsAnswer.serializer())

    override suspend fun quick(req: QuickRequest) =
        core.call("contacts.quick", req, QuickRequest.serializer(), QuickAnswer.serializer())

    override suspend fun csvExport(req: ExportRequest) =
        core.call("contacts.csv_export", req, ExportRequest.serializer(), TextFileAnswer.serializer())

    override suspend fun csvImport(req: TextRequest) =
        core.call("contacts.csv_import", req, TextRequest.serializer(), ParsedAnswer.serializer())

    override suspend fun ics(req: IcsRequest) =
        core.call("contacts.ics", req, IcsRequest.serializer(), IcsAnswer.serializer())

    override suspend fun vcardParse(req: TextRequest) =
        core.call("contacts.vcard_parse", req, TextRequest.serializer(), ParsedAnswer.serializer())

    override suspend fun vcardWrite(req: VcardWriteRequest) =
        core.call("contacts.vcard_write", req, VcardWriteRequest.serializer(), TextFileAnswer.serializer())

    override suspend fun importPlan(req: ImportPlanRequest) =
        core.call("contacts.import_plan", req, ImportPlanRequest.serializer(), ImportPlanAnswer.serializer())

    override suspend fun importPhotos(req: ImportPhotosRequest) =
        core.call("contacts.import_photos", req, ImportPhotosRequest.serializer(), ImportPhotosAnswer.serializer())

    override suspend fun numberKey(req: NumberKeyRequest) =
        core.call("contacts.number_key", req, NumberKeyRequest.serializer(), NumberKeyAnswer.serializer())

    override suspend fun intake(req: IntakeRequest) =
        core.call("contacts.intake", req, IntakeRequest.serializer(), IntakeAnswer.serializer())

    override suspend fun scan(req: ScanRequest) =
        core.call("contacts.scan", req, ScanRequest.serializer(), ScanAnswer.serializer())

    override suspend fun photoPlan(req: PhotoPlanRequest) =
        core.call("contacts.photo_plan", req, PhotoPlanRequest.serializer(), PhotoPlanAnswer.serializer())

    override suspend fun photoCrop(req: PhotoCropRequest) =
        core.call("contacts.photo_crop", req, PhotoCropRequest.serializer(), PhotoCropAnswer.serializer())

    override suspend fun photoAccepts(req: PhotoAcceptsRequest) =
        core.call("contacts.photo_accepts", req, PhotoAcceptsRequest.serializer(), PhotoAcceptsAnswer.serializer())

    override suspend fun view(req: ViewRequest) =
        core.call("contacts.view", req, ViewRequest.serializer(), ViewAnswer.serializer())

    override suspend fun favoritesMove(req: FavoritesMoveRequest) =
        core.call("contacts.favorites_move", req, FavoritesMoveRequest.serializer(), FavoritesMoveAnswer.serializer())

    override suspend fun scrubber(req: ScrubberRequest) =
        core.call("contacts.scrubber", req, ScrubberRequest.serializer(), ScrubberAnswer.serializer())

    override suspend fun selectBar(req: SelectBarRequest) =
        core.call("contacts.select_bar", req, SelectBarRequest.serializer(), SelectBarAnswer.serializer())

    override suspend fun selectExport(req: SelectExportRequest) =
        core.call("contacts.select_export", req, SelectExportRequest.serializer(), SelectExportAnswer.serializer())

    override suspend fun groupsTitle(req: GroupTitleRequest) =
        core.call("contacts.groups_title", req, GroupTitleRequest.serializer(), GroupTitleAnswer.serializer())

    override suspend fun groupsLabels(req: GroupLabelsRequest) =
        core.call("contacts.groups_labels", req, GroupLabelsRequest.serializer(), GroupLabelsAnswer.serializer())

    override suspend fun groupsChange(req: GroupChangeRequest) =
        core.call("contacts.groups_change", req, GroupChangeRequest.serializer(), GroupChangeAnswer.serializer())

    override suspend fun groupsSummary(req: GroupSummaryRequest) =
        core.call("contacts.groups_summary", req, GroupSummaryRequest.serializer(), Notice.serializer())

    override suspend fun groupsTargets(req: GroupTargetsRequest) =
        core.call("contacts.groups_targets", req, GroupTargetsRequest.serializer(), GroupTargetsAnswer.serializer())

    override suspend fun groupsRecipients(req: RecipientsRequest) =
        core.call("contacts.groups_recipients", req, RecipientsRequest.serializer(), RecipientsAnswer.serializer())

    override suspend fun recentNotePull(req: RecentPullRequest) =
        core.call("contacts.recent_note_pull", req, RecentPullRequest.serializer(), RecentPullAnswer.serializer())

    override suspend fun recentNoteCreated(req: RecentCreatedRequest) =
        core.call(
            "contacts.recent_note_created",
            req,
            RecentCreatedRequest.serializer(),
            RecentCreatedAnswer.serializer(),
        )

    override suspend fun recentLists(req: RecentListsRequest) =
        core.call("contacts.recent_lists", req, RecentListsRequest.serializer(), RecentListsAnswer.serializer())

    override suspend fun recentViewed(req: RecentViewedRequest) =
        core.call("contacts.recent_viewed", req, RecentViewedRequest.serializer(), RecentViewedAnswer.serializer())

    override suspend fun history(req: ContactHistoryRequest) =
        core.call("contacts.history", req, ContactHistoryRequest.serializer(), ContactHistoryAnswer.serializer())

    override suspend fun frequent(req: FrequentContactsRequest) =
        core.call("contacts.frequent", req, FrequentContactsRequest.serializer(), FrequentContactsAnswer.serializer())

    override suspend fun meState(req: MeStateRequest) =
        core.call("contacts.me_state", req, MeStateRequest.serializer(), MeStateAnswer.serializer())

    override suspend fun meToggle(req: MeToggleRequest) =
        core.call("contacts.me_toggle", req, MeToggleRequest.serializer(), MeToggleAnswer.serializer())

    override suspend fun birthdayReminders(req: ReminderRequest) =
        core.call("contacts.birthday_reminders", req, ReminderRequest.serializer(), ReminderAnswer.serializer())

    override suspend fun options(req: OptionsRequest) =
        core.call("contacts.options", req, OptionsRequest.serializer(), OptionsAnswer.serializer())

    override suspend fun pinShortcut(req: PinShortcutRequest) =
        core.call("contacts.pin_shortcut", req, PinShortcutRequest.serializer(), PinShortcutAnswer.serializer())

    override suspend fun deepIndexNew(req: DeepIndexNewRequest) =
        core.call("contacts.deep_index_new", req, DeepIndexNewRequest.serializer(), HandleAnswer.serializer())

    override suspend fun deepFind(req: DeepFindRequest) =
        core.call("contacts.deep_find", req, DeepFindRequest.serializer(), DeepFindAnswer.serializer())

    override suspend fun trashList(req: TrashListRequest) =
        core.call("contacts.trash_list", req, TrashListRequest.serializer(), TrashListAnswer.serializer())

    override suspend fun trashPush(req: TrashPushRequest) =
        core.call("contacts.trash_push", req, TrashPushRequest.serializer(), TrashPushAnswer.serializer())

    override suspend fun trashRestore(req: TrashRestoreRequest) =
        core.call("contacts.trash_restore", req, TrashRestoreRequest.serializer(), TrashRestoreAnswer.serializer())

    override suspend fun trashDelete(req: TrashDeleteRequest) =
        core.call("contacts.trash_delete", req, TrashDeleteRequest.serializer(), TrashDeleteAnswer.serializer())

    override suspend fun trashConfirm(req: TrashConfirmRequest) =
        core.call("contacts.trash_confirm", req, TrashConfirmRequest.serializer(), Confirm.serializer())

    override suspend fun prefsLoad(req: DirRequest) =
        core.call("contacts.prefs_load", req, DirRequest.serializer(), PrefsLoadAnswer.serializer())

    override suspend fun prefsSave(req: PrefsSaveRequest) =
        core.call("contacts.prefs_save", req, PrefsSaveRequest.serializer(), Empty.serializer())
}
