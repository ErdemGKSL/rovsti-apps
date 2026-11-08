package dev.rovsti.core.contracts

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
import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `contacts.*`. */
object ContactsContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<DraftRequest, ValidateAnswer>(
                "contacts.draft_validate",
                ok = listOf("bad_date", "bad_email", "no_name"),
            )
            op<DiffRequest, DiffAnswer>(
                "contacts.draft_diff",
                ok = listOf("new_contact", "same"),
            )
            op<DraftQueryRequest, DraftQueryAnswer>(
                "contacts.draft_from_query",
                ok = listOf("blank", "email", "number"),
            )
            op<DraftAddRequest, DraftAddAnswer>(
                "contacts.draft_add",
                ok = listOf("already_there", "empty"),
            )
            op<DraftEditRequest, DraftEditAnswer>(
                "contacts.draft_edit",
                ok = listOf("clean", "clear_default", "retain_groups"),
            )
            op<FieldMenuRequest, FieldMenuAnswer>(
                "contacts.field_menu",
                ok = listOf("clear", "single"),
            )
            op<WebUrlRequest, WebUrlAnswer>(
                "contacts.web_url",
                ok = listOf("other_scheme"),
            )
            op<RelationTapRequest, RelationTapAnswer>(
                "contacts.relation_tap",
                ok = listOf("search"),
            )
            op<AccountLabelRequest, LabelAnswer>(
                "contacts.account_label",
                ok = listOf("phone"),
            )
            op<DatesRequest, DatesAnswer>(
                "contacts.dates",
                ok = listOf("none"),
            )
            op<DateParseRequest, DateParseAnswer>(
                "contacts.date_parse",
                ok = listOf("bad", "no_year", "short"),
            )
            op<AgeRequest, AgeAnswer>(
                "contacts.age",
                ok = listOf("invalid", "no_year"),
            )
            op<EventLineRequest, EventLineAnswer>(
                "contacts.event_line",
                ok = listOf("anniversary"),
            )
            op<MapsUrlRequest, MapsUrlAnswer>(
                "contacts.maps_url",
                ok = listOf("blank"),
            )
            op<LocalYmdRequest, Day>("contacts.local_ymd")
            op<CardRequest, CardAnswer>(
                "contacts.card",
                ok = listOf("last_first", "no_number"),
            )
            op<DraftsRequest, DupesAnswer>(
                "contacts.dupes",
                ok = listOf("none"),
            )
            op<MergeRequest, MergeAnswer>("contacts.merge_plan")
            op<CleanupRequest, CleanupAnswer>(
                "contacts.cleanup",
                ok = listOf("counts_only"),
            )
            op<AvatarRequest, AvatarAnswer>(
                "contacts.avatar",
                ok = listOf("number", "unknown"),
            )
            op<DraftsRequest, StatsAnswer>(
                "contacts.stats",
                ok = listOf("empty"),
            )
            op<QuickRequest, QuickAnswer>(
                "contacts.quick",
                ok = listOf("no_number"),
            )
            op<ExportRequest, TextFileAnswer>("contacts.csv_export")
            op<TextRequest, ParsedAnswer>(
                "contacts.csv_import",
                ok = listOf("empty"),
            )
            op<IcsRequest, IcsAnswer>("contacts.ics")
            op<TextRequest, ParsedAnswer>(
                "contacts.vcard_parse",
                ok = listOf("none"),
            )
            op<VcardWriteRequest, TextFileAnswer>(
                "contacts.vcard_write",
                ok = listOf("one_with_photo"),
                err = listOf("bad_photo"),
            )
            op<ImportPlanRequest, ImportPlanAnswer>(
                "contacts.import_plan",
                ok = listOf("nothing"),
            )
            op<ImportPhotosRequest, ImportPhotosAnswer>("contacts.import_photos")
            op<NumberKeyRequest, NumberKeyAnswer>(
                "contacts.number_key",
                ok = listOf("no_digits"),
            )
            op<IntakeRequest, IntakeAnswer>(
                "contacts.intake",
                ok = listOf("csv", "text", "unreadable"),
                err = listOf("bad_source"),
            )
            op<ScanRequest, ScanAnswer>(
                "contacts.scan",
                ok = listOf("mailto", "nothing", "pasted_line", "tel"),
            )
            op<PhotoPlanRequest, PhotoPlanAnswer>(
                "contacts.photo_plan",
                ok = listOf("cropped", "empty", "too_small"),
            )
            op<PhotoCropRequest, PhotoCropAnswer>(
                "contacts.photo_crop",
                ok = listOf("pinch", "rotate"),
                err = listOf("bad_action"),
            )
            op<PhotoAcceptsRequest, PhotoAcceptsAnswer>(
                "contacts.photo_accepts",
                ok = listOf("not_an_image", "unknown"),
            )
            op<ViewRequest, ViewAnswer>(
                "contacts.view",
                ok = listOf("empty", "last_name", "search_number"),
            )
            op<FavoritesMoveRequest, FavoritesMoveAnswer>(
                "contacts.favorites_move",
                ok = listOf("drag", "off_the_end"),
            )
            op<ScrubberRequest, ScrubberAnswer>(
                "contacts.scrubber",
                ok = listOf("no_rows"),
            )
            op<SelectBarRequest, SelectBarAnswer>(
                "contacts.select_bar",
                ok = listOf("none", "one"),
            )
            op<SelectExportRequest, SelectExportAnswer>(
                "contacts.select_export",
                ok = listOf("text"),
                err = listOf("bad_format"),
            )
            op<GroupTitleRequest, GroupTitleAnswer>(
                "contacts.groups_title",
                ok = listOf("duplicate", "empty", "rename_self"),
            )
            op<GroupLabelsRequest, GroupLabelsAnswer>(
                "contacts.groups_labels",
                ok = listOf("phone"),
            )
            op<GroupChangeRequest, GroupChangeAnswer>(
                "contacts.groups_change",
                ok = listOf("add_only", "unchanged"),
            )
            op<GroupSummaryRequest, Notice>(
                "contacts.groups_summary",
                ok = listOf("nothing"),
            )
            op<GroupTargetsRequest, GroupTargetsAnswer>("contacts.groups_targets")
            op<RecipientsRequest, RecipientsAnswer>(
                "contacts.groups_recipients",
                ok = listOf("email"),
                err = listOf("bad_kind"),
            )
            op<RecentPullRequest, RecentPullAnswer>(
                "contacts.recent_note_pull",
                ok = listOf("first_run"),
            )
            op<RecentCreatedRequest, RecentCreatedAnswer>("contacts.recent_note_created")
            op<RecentListsRequest, RecentListsAnswer>(
                "contacts.recent_lists",
                ok = listOf("searching"),
            )
            op<RecentViewedRequest, RecentViewedAnswer>(
                "contacts.recent_viewed",
                ok = listOf("forget"),
                err = listOf("bad_action"),
            )
            op<ContactHistoryRequest, ContactHistoryAnswer>(
                "contacts.history",
                ok = listOf("none"),
            )
            op<FrequentContactsRequest, FrequentContactsAnswer>(
                "contacts.frequent",
                ok = listOf("none"),
            )
            op<MeStateRequest, MeStateAnswer>(
                "contacts.me_state",
                ok = listOf("unset"),
            )
            op<MeToggleRequest, MeToggleAnswer>(
                "contacts.me_toggle",
                ok = listOf("full", "no_number", "remove"),
            )
            op<ReminderRequest, ReminderAnswer>(
                "contacts.birthday_reminders",
                ok = listOf("none"),
            )
            op<OptionsRequest, OptionsAnswer>(
                "contacts.options",
                ok = listOf("from_json", "silent"),
                err = listOf("bad_json"),
            )
            op<PinShortcutRequest, PinShortcutAnswer>(
                "contacts.pin_shortcut",
                ok = listOf("no_name"),
            )
            op<DeepIndexNewRequest, HandleAnswer>(
                "contacts.deep_index_new",
                ok = listOf("empty"),
            )
            op<DeepFindRequest, DeepFindAnswer>(
                "contacts.deep_find",
                ok = listOf("skip"),
                err = listOf("stale_handle"),
            )
            op<TrashListRequest, TrashListAnswer>(
                "contacts.trash_list",
                ok = listOf("empty"),
            )
            op<TrashPushRequest, TrashPushAnswer>("contacts.trash_push")
            op<TrashRestoreRequest, TrashRestoreAnswer>("contacts.trash_restore")
            op<TrashDeleteRequest, TrashDeleteAnswer>(
                "contacts.trash_delete",
                ok = listOf("all"),
            )
            op<TrashConfirmRequest, Confirm>(
                "contacts.trash_confirm",
                ok = listOf("empty"),
                err = listOf("bad_kind"),
            )
            op<DirRequest, PrefsLoadAnswer>(
                "contacts.prefs_load",
                ok = listOf("missing"),
            )
            op<PrefsSaveRequest, Empty>("contacts.prefs_save")
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
