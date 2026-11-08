//! The lists and screens built from the contacts: rows, favorites, selection and export, groups, recents, history,
//! frequent people, my card, reminders, per-contact options and the detail search handle.

use super::{Confirm, NOW, NumberDto, Row2, TZ, d, drafts, locale, wires};
use crate::envelope::{ErrorKind, FfiError};
use crate::handles;
use crate::op;
use crate::ops::registry::{Case, OpSpec, PLACEHOLDER_HANDLE as H};
use rovsti_core::birthday_reminder;
use rovsti_core::call_stats::StatCall;
use rovsti_core::contact_dates;
use rovsti_core::contact_edit::{self, ContactDraft, Group};
use rovsti_core::contact_find::{DeepField, DeepIndex};
use rovsti_core::contact_frequent;
use rovsti_core::contact_history;
use rovsti_core::contact_me::{self, EmergencyError};
use rovsti_core::contact_options::{self, ContactOptions, PinShortcut};
use rovsti_core::contact_recent::{self, AddedMark, RecentTab};
use rovsti_core::contact_select::{self, SelectAction};
use rovsti_core::contacts_prefs::MAX_EMERGENCY;
use rovsti_core::contacts_view::{self, ContactVm, NameStyle};
use rovsti_core::group_tools::{self, Kind};
use rovsti_core::recents::{HistoryLine, LocaleTime};
use rovsti_core::settings::SortBy;
use rovsti_core::vcard;
use rovsti_core::wire::{NameFormat, WireContact};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::BTreeSet;

pub(super) const OPS: &[OpSpec] = &[
    op!("contacts.view", ViewReq, ViewAns, view, view_cases),
    op!(
        "contacts.favorites_move",
        MoveReq,
        MoveAns,
        favorites_move,
        move_cases
    ),
    op!(
        "contacts.scrubber",
        ScrubReq,
        ScrubAns,
        scrubber,
        scrub_cases
    ),
    op!("contacts.select_bar", BarReq, BarAns, select_bar, bar_cases),
    op!(
        "contacts.select_export",
        ExportReq,
        ExportAns,
        select_export,
        export_cases
    ),
    op!(
        "contacts.groups_title",
        GroupTitleReq,
        GroupTitleAns,
        groups_title,
        group_title_cases
    ),
    op!(
        "contacts.groups_labels",
        LabelsReq,
        LabelsAns,
        groups_labels,
        labels_cases
    ),
    op!(
        "contacts.groups_change",
        ChangeReq,
        ChangeAns,
        groups_change,
        change_cases
    ),
    op!(
        "contacts.groups_summary",
        SummaryReq,
        super::Notice,
        groups_summary,
        summary_cases
    ),
    op!(
        "contacts.groups_targets",
        TargetsReq,
        TargetsAns,
        groups_targets,
        targets_cases
    ),
    op!(
        "contacts.groups_recipients",
        RecipientsReq,
        RecipientsAns,
        groups_recipients,
        recipients_cases
    ),
    op!(
        "contacts.recent_note_pull",
        PullReq,
        PullAns,
        recent_note_pull,
        pull_cases
    ),
    op!(
        "contacts.recent_note_created",
        CreatedReq,
        CreatedAns,
        recent_note_created,
        created_cases
    ),
    op!(
        "contacts.recent_lists",
        RecentReq,
        RecentAns,
        recent_lists,
        recent_cases
    ),
    op!(
        "contacts.recent_viewed",
        ViewedReq,
        ViewedAns,
        recent_viewed,
        viewed_cases
    ),
    op!(
        "contacts.history",
        HistoryReq,
        HistoryAns,
        history,
        history_cases
    ),
    op!(
        "contacts.frequent",
        FrequentReq,
        FrequentAns,
        frequent,
        frequent_cases
    ),
    op!("contacts.me_state", MeReq, MeAns, me_state, me_cases),
    op!(
        "contacts.me_toggle",
        ToggleReq,
        ToggleAns,
        me_toggle,
        toggle_cases
    ),
    op!(
        "contacts.birthday_reminders",
        ReminderReq,
        ReminderAns,
        birthday_reminders,
        reminder_cases
    ),
    op!(
        "contacts.options",
        OptionsReq,
        OptionsAns,
        options,
        options_cases
    ),
    op!(
        "contacts.pin_shortcut",
        PinReq,
        PinAns,
        pin_shortcut,
        pin_cases
    ),
    op!(
        "contacts.deep_index_new",
        DeepNewReq,
        DeepNewAns,
        deep_new,
        deep_new_cases
    ),
    op!(
        "contacts.deep_find",
        DeepFindReq,
        DeepFindAns,
        deep_find,
        deep_find_cases
    ),
];

fn first(c: Option<char>) -> Option<String> {
    c.map(String::from)
}

// ---- view ----

#[derive(Deserialize)]
struct ViewReq {
    contacts: Vec<WireContact>,
    #[serde(default)]
    query: String,
    #[serde(default)]
    name_format: NameFormat,
    #[serde(default)]
    sort_by: SortBy,
    /// Lookup keys of the favorites in the user's order.
    #[serde(default)]
    favorites_order: Vec<String>,
    /// Contacts hidden for now (selected for delete, just merged).
    #[serde(default)]
    hidden: Vec<i64>,
    /// Width of the favorites grid in dp.
    width_dp: f32,
}

#[derive(Serialize)]
struct ViewAns {
    rows: Vec<RowDto>,
    /// The starred contacts in the user's order.
    favorites: Vec<i64>,
    columns: usize,
    scrubber_letters: Vec<String>,
    show_scrubber: bool,
    /// The query as echoed in "No contact matches ...".
    query_echo: String,
    /// The number a query that looks like one stands for.
    query_number: Option<String>,
}

#[derive(Serialize)]
struct RowDto {
    id: i64,
    name: String,
    subtitle: Option<String>,
    letter: String,
    header: bool,
    monogram: Option<String>,
    hue: Option<usize>,
    numbers: Vec<NumberDto>,
    starred: bool,
    photo_uri: Option<String>,
}

impl From<&ContactVm> for RowDto {
    fn from(v: &ContactVm) -> Self {
        Self {
            id: v.id,
            name: v.name.clone(),
            subtitle: v.subtitle.clone(),
            letter: v.letter.to_string(),
            header: v.header,
            monogram: first(v.monogram),
            hue: v.hue,
            numbers: v.numbers.iter().map(NumberDto::from).collect(),
            starred: v.starred,
            photo_uri: v.photo_uri.clone(),
        }
    }
}

fn view(req: ViewReq) -> Result<ViewAns, FfiError> {
    let shown = contacts_view::without(&req.contacts, &req.hidden);
    let style = NameStyle {
        format: req.name_format,
        sort: req.sort_by,
    };
    let ordered = contacts_view::ordered(&shown, style);
    let rows = contacts_view::build_rows_styled(ordered, &req.query, style);
    let stars = shown.iter().filter(|c| c.starred);
    let favorites = contacts_view::order_favorite_refs(stars.collect(), &req.favorites_order)
        .iter()
        .map(|c| c.id)
        .collect();
    let letters = contacts_view::scrubber_letters(rows.iter().map(|r| r.letter));
    Ok(ViewAns {
        columns: contacts_view::favorite_columns(req.width_dp),
        show_scrubber: contacts_view::show_scrubber(rows.len(), &req.query),
        query_echo: contacts_view::clip_query(&req.query),
        query_number: contacts_view::query_number(&req.query),
        scrubber_letters: letters.iter().map(|c| c.to_string()).collect(),
        rows: rows.iter().map(RowDto::from).collect(),
        favorites,
    })
}

fn view_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"contacts": wires(), "name_format": "first_last", "sort_by": "first_name",
                         "favorites_order": ["k-zeynep"], "width_dp": 411.0}),
        ),
        Case::named(
            "search_number",
            json!({"contacts": wires(), "query": "0555", "width_dp": 600.0}),
        ),
        Case::named(
            "last_name",
            json!({"contacts": wires(), "name_format": "last_first", "sort_by": "last_name",
                                        "hidden": [3], "width_dp": 411.0}),
        ),
        Case::named(
            "empty",
            json!({"contacts": [], "query": "  Ada  ", "width_dp": 411.0}),
        ),
    ]
}

// ---- favorites_move / scrubber ----

#[derive(Deserialize)]
struct MoveReq {
    /// The favorites as shown, in order.
    shown: Vec<WireContact>,
    /// Button mode: move `index` one step.
    #[serde(default)]
    index: Option<usize>,
    #[serde(default)]
    up: bool,
    /// Drag mode: move `from` to `to`.
    #[serde(default)]
    from: Option<usize>,
    #[serde(default)]
    to: Option<usize>,
}

#[derive(Serialize)]
struct MoveAns {
    /// The new order as lookup keys; `None` when the move is not possible.
    keys: Option<Vec<String>>,
}

fn favorites_move(req: MoveReq) -> Result<MoveAns, FfiError> {
    let refs: Vec<&WireContact> = req.shown.iter().collect();
    let keys = match (req.index, req.from, req.to) {
        (Some(index), _, _) => contacts_view::move_favorite(&refs, index, req.up),
        (None, Some(from), Some(to)) => Some(contacts_view::reordered_keys(&refs, from, to)),
        _ => None,
    };
    Ok(MoveAns { keys })
}

fn move_cases() -> Vec<Case> {
    let w = wires();
    let shown = json!([w[0], w[1], w[3]]);
    vec![
        Case::new(json!({"shown": shown, "index": 1, "up": true})),
        Case::named("drag", json!({"shown": shown, "from": 0, "to": 2})),
        Case::named("off_the_end", json!({"shown": shown, "index": 2})),
    ]
}

#[derive(Deserialize)]
struct ScrubReq {
    /// The section letter of every row, in list order.
    rows: Vec<String>,
    /// The touch on the strip and the strip's height.
    y: f32,
    height: f32,
}

#[derive(Serialize)]
struct ScrubAns {
    letters: Vec<String>,
    /// The letter under the touch.
    index: Option<usize>,
    /// The first row of that letter.
    row: Option<usize>,
}

fn scrubber(req: ScrubReq) -> Result<ScrubAns, FfiError> {
    let rows: Vec<char> = req.rows.iter().filter_map(|s| s.chars().next()).collect();
    let letters = contacts_view::scrubber_letters(rows.iter().copied());
    let index = contacts_view::letter_at(req.y, req.height, letters.len());
    let row = index
        .and_then(|i| letters.get(i))
        .and_then(|l| contacts_view::first_row_of(&rows, *l));
    Ok(ScrubAns {
        letters: letters.iter().map(|c| c.to_string()).collect(),
        index,
        row,
    })
}

fn scrub_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"rows": ["A", "A", "B", "C", "#"], "y": 40.0, "height": 120.0})),
        Case::named("no_rows", json!({"rows": [], "y": 0.0, "height": 100.0})),
    ]
}

// ---- selection ----

#[derive(Deserialize)]
struct BarReq {
    count: usize,
    has_groups: bool,
}

#[derive(Serialize)]
struct BarAns {
    title: String,
    /// `share`, `save_to_file`, `add_to_group`, `star`, `delete`.
    actions: Vec<&'static str>,
    cap: usize,
    cap_notice: String,
    delete_confirm: Confirm,
    starred_notice: String,
    unstarred_notice: String,
    deleted_notice: String,
    formats: Vec<FormatDto>,
}

#[derive(Serialize)]
struct FormatDto {
    id: &'static str,
    label: &'static str,
}

fn select_bar(req: BarReq) -> Result<BarAns, FfiError> {
    Ok(BarAns {
        title: contact_select::title(req.count),
        actions: contact_select::select_bar_actions(req.count, req.has_groups)
            .into_iter()
            .map(|a| match a {
                SelectAction::Share => "share",
                SelectAction::SaveToFile => "save_to_file",
                SelectAction::AddToGroup => "add_to_group",
                SelectAction::Star => "star",
                SelectAction::Delete => "delete",
            })
            .collect(),
        cap: contact_select::MAX_SELECTED,
        cap_notice: contact_select::cap_notice(),
        delete_confirm: Confirm::of(contact_select::delete_confirm(req.count)),
        starred_notice: contact_select::starred_notice(req.count, true),
        unstarred_notice: contact_select::starred_notice(req.count, false),
        deleted_notice: contact_select::deleted_notice(req.count),
        formats: contact_select::SHARE_FORMATS
            .iter()
            .map(|f| FormatDto {
                id: match f {
                    contact_select::ShareFormat::Vcf => "vcf",
                    contact_select::ShareFormat::Text => "text",
                },
                label: f.label(),
            })
            .collect(),
    })
}

fn bar_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"count": 3, "has_groups": true})),
        Case::named("none", json!({"count": 0, "has_groups": false})),
        Case::named("one", json!({"count": 1, "has_groups": false})),
    ]
}

#[derive(Deserialize)]
struct ExportReq {
    drafts: Vec<ContactDraft>,
    selected: Vec<i64>,
    #[serde(default)]
    hidden: Vec<i64>,
    today: super::files::Day,
    /// `vcf` or `text`.
    format: String,
}

#[derive(Serialize)]
struct ExportAns {
    ids: Vec<i64>,
    count: usize,
    file_name: String,
    /// The vCard file or the plain text to share.
    text: String,
    /// "Saved 2 contacts to the file" / "Shared 2 contacts as text".
    notice: String,
    copied_notice: &'static str,
}

fn select_export(req: ExportReq) -> Result<ExportAns, FfiError> {
    let chosen: BTreeSet<i64> = req.selected.iter().copied().collect();
    let ids = contact_select::export_ids(&chosen, &req.hidden);
    let picked = contact_select::pick_drafts(&req.drafts, &ids);
    let (text, notice, copied_notice) = match req.format.as_str() {
        "vcf" => (
            vcard::write(&picked),
            contact_select::exported_notice(picked.len()),
            contact_select::copied_vcard_notice(),
        ),
        "text" => (
            contact_select::share_text(&picked),
            contact_select::shared_text_notice(picked.len()),
            contact_select::copied_contact_notice(),
        ),
        other => return Err(FfiError::invalid(format!("unknown share format {other:?}"))),
    };
    Ok(ExportAns {
        file_name: contact_select::selection_file_name(
            picked.len(),
            (req.today.year, req.today.month, req.today.day),
        ),
        count: picked.len(),
        ids,
        text,
        notice,
        copied_notice,
    })
}

fn export_cases() -> Vec<Case> {
    let today = json!({"year": 2026, "month": 9, "day": 21});
    vec![
        Case::new(
            json!({"drafts": drafts(), "selected": [1, 2, 3], "hidden": [3], "today": today, "format": "vcf"}),
        ),
        Case::named(
            "text",
            json!({"drafts": drafts(), "selected": [1], "today": today, "format": "text"}),
        ),
        Case::named(
            "bad_format",
            json!({"drafts": [], "selected": [], "today": today, "format": "pdf"}),
        ),
    ]
}

// ---- groups ----

#[derive(Deserialize)]
struct GroupTitleReq {
    title: String,
    groups: Vec<Group>,
    #[serde(default)]
    editing: Option<i64>,
}

#[derive(Serialize)]
struct GroupTitleAns {
    title: Option<String>,
    error: Option<String>,
}

fn groups_title(req: GroupTitleReq) -> Result<GroupTitleAns, FfiError> {
    Ok(
        match contact_edit::group_title(&req.title, &req.groups, req.editing) {
            Ok(title) => GroupTitleAns {
                title: Some(title),
                error: None,
            },
            Err(e) => GroupTitleAns {
                title: None,
                error: Some(e.to_string()),
            },
        },
    )
}

fn groups_sample() -> serde_json::Value {
    json!([{"id": 10, "title": "Family", "members": [1, 2], "account": "com.google|ayse@example.org"},
           {"id": 11, "title": "Work", "members": [2]}])
}

fn group_title_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"title": "  Friends ", "groups": groups_sample()})),
        Case::named(
            "duplicate",
            json!({"title": "family", "groups": groups_sample()}),
        ),
        Case::named(
            "rename_self",
            json!({"title": "Family", "groups": groups_sample(), "editing": 10}),
        ),
        Case::named("empty", json!({"title": " ", "groups": groups_sample()})),
    ]
}

#[derive(Deserialize)]
struct LabelsReq {
    members: usize,
    #[serde(default)]
    account: Option<String>,
}

#[derive(Serialize)]
struct LabelsAns {
    subtitle: String,
    /// "Only contacts in <account> can join".
    join_hint: String,
}

fn groups_labels(req: LabelsReq) -> Result<LabelsAns, FfiError> {
    Ok(LabelsAns {
        subtitle: contact_edit::group_subtitle(req.members, req.account.as_deref()),
        join_hint: contact_edit::group_join_hint(req.account.as_deref().unwrap_or("")),
    })
}

fn labels_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"members": 3, "account": "com.google|ayse@example.org"})),
        Case::named("phone", json!({"members": 1})),
    ]
}

#[derive(Deserialize)]
struct ChangeReq {
    /// The group's members now.
    initial: Vec<i64>,
    /// The members after the picker.
    chosen: Vec<i64>,
    /// Only add (the select bar's "Add to group"), never remove.
    #[serde(default)]
    add_only: bool,
}

#[derive(Serialize)]
struct ChangeAns {
    add: Vec<i64>,
    remove: Vec<i64>,
    empty: bool,
}

fn groups_change(req: ChangeReq) -> Result<ChangeAns, FfiError> {
    let change = if req.add_only {
        contact_select::add_to_group_change(&req.initial, &req.chosen)
    } else {
        contact_edit::selection_change(&req.initial, &req.chosen.iter().copied().collect())
    };
    Ok(ChangeAns {
        empty: change.is_empty(),
        add: change.add,
        remove: change.remove,
    })
}

fn change_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"initial": [1, 2], "chosen": [2, 3]})),
        Case::named(
            "add_only",
            json!({"initial": [1, 2], "chosen": [2, 3, 3], "add_only": true}),
        ),
        Case::named("unchanged", json!({"initial": [1], "chosen": [1]})),
    ]
}

#[derive(Deserialize)]
struct SummaryReq {
    title: String,
    added: u32,
    removed: u32,
    skipped: u32,
}

fn groups_summary(req: SummaryReq) -> Result<super::Notice, FfiError> {
    Ok(super::Notice {
        text: contact_edit::members_summary(
            &req.title,
            &contact_edit::MembersPayload {
                added: req.added,
                removed: req.removed,
                skipped: req.skipped,
            },
        ),
    })
}

fn summary_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"title": "Family", "added": 2, "removed": 1, "skipped": 1})),
        Case::named(
            "nothing",
            json!({"title": "Family", "added": 0, "removed": 0, "skipped": 0}),
        ),
    ]
}

#[derive(Deserialize)]
struct SelectedContact {
    id: i64,
    account: String,
}

#[derive(Deserialize)]
struct TargetsReq {
    groups: Vec<Group>,
    selected: Vec<SelectedContact>,
}

#[derive(Serialize)]
struct TargetsAns {
    targets: Vec<TargetDto>,
}

#[derive(Serialize)]
struct TargetDto {
    group_id: i64,
    title: String,
    account: String,
    /// The selected contacts that can join (same account).
    eligible: Vec<i64>,
    skipped: usize,
    /// "Added 2 contacts to Family. 1 skipped: other account".
    notice: String,
}

fn groups_targets(req: TargetsReq) -> Result<TargetsAns, FfiError> {
    let selected: Vec<(i64, String)> = req
        .selected
        .into_iter()
        .map(|c| (c.id, c.account))
        .collect();
    Ok(TargetsAns {
        targets: contact_select::group_targets(&req.groups, &selected)
            .into_iter()
            .map(|t| TargetDto {
                notice: contact_select::added_with_skipped(t.eligible.len(), &t.title, t.skipped),
                group_id: t.group_id,
                title: t.title,
                account: t.account,
                eligible: t.eligible,
                skipped: t.skipped,
            })
            .collect(),
    })
}

fn targets_cases() -> Vec<Case> {
    vec![Case::new(json!({"groups": groups_sample(),
        "selected": [{"id": 1, "account": "com.google|ayse@example.org"}, {"id": 5, "account": ""}]}))]
}

#[derive(Deserialize)]
struct RecipientsReq {
    members: Vec<ContactDraft>,
    /// `sms` or `email`.
    kind: String,
    title: String,
    today: super::files::Day,
}

#[derive(Serialize)]
struct RecipientsAns {
    addresses: Vec<String>,
    /// Members without a usable number or email.
    missing: usize,
    capped: bool,
    cap: usize,
    /// The joined address for the intent (`;` for SMS, `,` for email).
    address: String,
    notice: Option<String>,
    file_name: String,
}

fn groups_recipients(req: RecipientsReq) -> Result<RecipientsAns, FfiError> {
    let (kind, cap) = match req.kind.as_str() {
        "sms" => (Kind::Sms, group_tools::MAX_SMS),
        "email" => (Kind::Email, group_tools::MAX_EMAIL),
        other => {
            return Err(FfiError::invalid(format!(
                "unknown recipient kind {other:?}"
            )));
        }
    };
    let (addresses, missing) = match kind {
        Kind::Sms => group_tools::sms_recipients(&req.members),
        Kind::Email => group_tools::email_recipients(&req.members),
    };
    let used = addresses.len();
    let capped = used >= cap && req.members.len().saturating_sub(missing) > used;
    Ok(RecipientsAns {
        address: match kind {
            Kind::Sms => group_tools::sms_address(&addresses),
            Kind::Email => group_tools::email_address(&addresses),
        },
        notice: group_tools::recipients_notice(kind, used, missing, capped),
        file_name: group_tools::group_file_name(
            &req.title,
            (req.today.year, req.today.month, req.today.day),
        ),
        addresses,
        missing,
        capped,
        cap,
    })
}

fn recipients_cases() -> Vec<Case> {
    let today = json!({"year": 2026, "month": 9, "day": 21});
    vec![
        Case::new(
            json!({"members": drafts(), "kind": "sms", "title": "Family: close/friends", "today": today}),
        ),
        Case::named(
            "email",
            json!({"members": drafts(), "kind": "email", "title": "Work", "today": today}),
        ),
        Case::named(
            "bad_kind",
            json!({"members": [], "kind": "fax", "title": "x", "today": today}),
        ),
    ]
}

// ---- recents ----

#[derive(Clone, Deserialize, Serialize)]
struct MarkDto {
    id: i64,
    ms: i64,
}

fn marks_in(marks: Vec<MarkDto>) -> Vec<AddedMark> {
    marks
        .into_iter()
        .map(|m| AddedMark { id: m.id, ms: m.ms })
        .collect()
}

fn marks_out(marks: Vec<AddedMark>) -> Vec<MarkDto> {
    marks
        .into_iter()
        .map(|m| MarkDto { id: m.id, ms: m.ms })
        .collect()
}

#[derive(Deserialize)]
struct PullReq {
    #[serde(default)]
    known_max_id: Option<i64>,
    marks: Vec<MarkDto>,
    /// The ids of every contact in the last pull.
    ids: Vec<i64>,
    now_ms: i64,
}

#[derive(Serialize)]
struct PullAns {
    known_max_id: i64,
    marks: Vec<MarkDto>,
}

fn recent_note_pull(req: PullReq) -> Result<PullAns, FfiError> {
    let (known_max_id, marks) =
        contact_recent::note_pull(req.known_max_id, &marks_in(req.marks), &req.ids, req.now_ms);
    Ok(PullAns {
        known_max_id,
        marks: marks_out(marks),
    })
}

fn pull_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"known_max_id": 3, "marks": [{"id": 2, "ms": NOW - 86_400_000}], "ids": [1, 2, 3, 4, 5], "now_ms": NOW}),
        ),
        Case::named(
            "first_run",
            json!({"marks": [], "ids": [1, 2], "now_ms": NOW}),
        ),
    ]
}

#[derive(Deserialize)]
struct CreatedReq {
    marks: Vec<MarkDto>,
    id: i64,
    now_ms: i64,
}

#[derive(Serialize)]
struct CreatedAns {
    marks: Vec<MarkDto>,
}

fn recent_note_created(req: CreatedReq) -> Result<CreatedAns, FfiError> {
    Ok(CreatedAns {
        marks: marks_out(contact_recent::note_created(
            &marks_in(req.marks),
            req.id,
            req.now_ms,
        )),
    })
}

fn created_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"marks": [{"id": 2, "ms": NOW - 1000}], "id": 9, "now_ms": NOW}),
    )]
}

#[derive(Deserialize)]
struct RecentReq {
    contacts: Vec<WireContact>,
    marks: Vec<MarkDto>,
    /// Lookup keys of the recently viewed contacts, newest first.
    viewed_keys: Vec<String>,
    hidden: Vec<i64>,
    now_ms: i64,
    tz_offset_s: i32,
    #[serde(default)]
    query: String,
}

#[derive(Serialize)]
struct RecentAns {
    added: Vec<RecentRow>,
    edited: Vec<RecentRow>,
    viewed: Vec<i64>,
    /// The row at the top of the list: "Recent" / "2 added · 1 edited".
    entry_title: String,
    entry_detail: String,
    show_entry: bool,
    tabs: Vec<TabDto>,
    clear_viewed: Confirm,
    window_days: u16,
}

#[derive(Serialize)]
struct RecentRow {
    id: i64,
    name: String,
    /// "Today", "Yesterday", "3 days ago", "12 May".
    when: String,
}

#[derive(Serialize)]
struct TabDto {
    id: &'static str,
    label: &'static str,
    empty_title: &'static str,
    empty_body: &'static str,
}

fn recent_lists(req: RecentReq) -> Result<RecentAns, FfiError> {
    let shown = contacts_view::without(&req.contacts, &req.hidden);
    let marks = marks_in(req.marks);
    let added = contact_recent::added(&shown, &marks);
    let edited = contact_recent::edited(
        &shown,
        &marks,
        req.now_ms,
        contact_recent::WINDOW_DAYS,
        contact_recent::ADDED_KEEP,
    );
    let (entry_title, entry_detail) = contact_recent::entry_title(added.len(), edited.len());
    let row = |c: &WireContact, ms: i64| RecentRow {
        id: c.id,
        name: c.display_name.clone(),
        when: contacts_view::recent_when(ms, req.now_ms, req.tz_offset_s),
    };
    Ok(RecentAns {
        show_entry: contact_recent::show_entry(&req.query, added.len(), edited.len()),
        added: added.iter().map(|(c, ms)| row(c, *ms)).collect(),
        edited: edited
            .iter()
            .map(|c| row(c, c.updated_ms.unwrap_or(0)))
            .collect(),
        viewed: contact_recent::viewed(&req.viewed_keys, &shown, &[])
            .iter()
            .map(|c| c.id)
            .collect(),
        entry_title: entry_title.to_owned(),
        entry_detail,
        tabs: RecentTab::ALL
            .iter()
            .map(|t| {
                let (empty_title, empty_body) = t.empty();
                TabDto {
                    id: match t {
                        RecentTab::Added => "added",
                        RecentTab::Edited => "edited",
                    },
                    label: t.label(),
                    empty_title,
                    empty_body,
                }
            })
            .collect(),
        clear_viewed: Confirm::of_static(contact_recent::CLEAR_VIEWED),
        window_days: contact_recent::WINDOW_DAYS,
    })
}

fn recent_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"contacts": wires(), "marks": [{"id": 4, "ms": NOW - 7_200_000}], "viewed_keys": ["k-cem", "k-ayse", "gone"],
                         "hidden": [], "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "searching",
            json!({"contacts": wires(), "marks": [], "viewed_keys": [], "hidden": [1],
                         "now_ms": NOW, "tz_offset_s": TZ, "query": "ay"}),
        ),
    ]
}

#[derive(Deserialize)]
struct ViewedReq {
    keys: Vec<String>,
    key: String,
    /// `note` (opened a card) or `forget` (swiped away).
    action: String,
}

#[derive(Serialize)]
struct ViewedAns {
    keys: Vec<String>,
}

fn recent_viewed(req: ViewedReq) -> Result<ViewedAns, FfiError> {
    let keys = match req.action.as_str() {
        "note" => contact_recent::note_viewed(&req.keys, &req.key),
        "forget" => contact_recent::forget_viewed(&req.keys, &req.key),
        other => {
            return Err(FfiError::invalid(format!(
                "unknown viewed action {other:?}"
            )));
        }
    };
    Ok(ViewedAns { keys })
}

fn viewed_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"keys": ["a", "b"], "key": "b", "action": "note"})),
        Case::named(
            "forget",
            json!({"keys": ["a", "b"], "key": "a", "action": "forget"}),
        ),
        Case::named(
            "bad_action",
            json!({"keys": [], "key": "a", "action": "burn"}),
        ),
    ]
}

// ---- history / frequent ----

#[derive(Deserialize)]
struct HistoryReq {
    lines: Vec<HistoryLine>,
    now_ms: i64,
    locale: LocaleTime,
}

#[derive(Serialize)]
struct HistoryAns {
    /// "3 calls, last call 2 days ago".
    summary: Option<String>,
    rows: Vec<Row2>,
    card_limit: usize,
    pull_limit: usize,
    permission: Row2,
}

fn history(req: HistoryReq) -> Result<HistoryAns, FfiError> {
    let (title, detail) = contact_history::permission_text();
    Ok(HistoryAns {
        summary: contact_history::summary_at(&req.lines, req.now_ms, req.locale.tz_offset_s),
        rows: contact_history::history_rows(&req.lines, req.now_ms, req.locale)
            .into_iter()
            .map(|(title, detail)| Row2 { title, detail })
            .collect(),
        card_limit: contact_history::CARD_LIMIT,
        pull_limit: contact_history::PULL_LIMIT,
        permission: Row2 {
            title: title.to_owned(),
            detail: detail.to_owned(),
        },
    })
}

fn history_cases() -> Vec<Case> {
    let lines = json!([
        {"entry": {"log": 3}, "blocked": false, "call_type": "outgoing", "whatsapp": false,
         "date_ms": NOW - 3_600_000, "duration_s": 125, "number": "+90 212 555 01 02"},
        {"entry": {"whatsapp": "wa-1"}, "blocked": false, "call_type": "missed", "whatsapp": true,
         "date_ms": NOW - 2 * 86_400_000, "duration_s": 0, "number": "+905321234567"}
    ]);
    vec![
        Case::new(json!({"lines": lines, "now_ms": NOW, "locale": locale()})),
        Case::named(
            "none",
            json!({"lines": [], "now_ms": NOW, "locale": locale()}),
        ),
    ]
}

#[derive(Deserialize)]
struct FrequentReq {
    calls: Vec<StatCall>,
    contacts: Vec<WireContact>,
    now_ms: i64,
    #[serde(default)]
    limit: Option<usize>,
}

#[derive(Serialize)]
struct FrequentAns {
    people: Vec<FrequentDto>,
    window_days: i64,
}

#[derive(Serialize)]
struct FrequentDto {
    contact_id: i64,
    calls: u32,
    last_ms: i64,
    /// "5 calls in the last 30 days".
    subtitle: String,
}

fn frequent(req: FrequentReq) -> Result<FrequentAns, FfiError> {
    let limit = req.limit.unwrap_or(contact_frequent::MAX_SHOWN);
    Ok(FrequentAns {
        people: contact_frequent::rank(&req.calls, &req.contacts, req.now_ms, limit)
            .iter()
            .map(|f| FrequentDto {
                subtitle: contact_frequent::subtitle(f),
                contact_id: f.contact_id,
                calls: f.calls,
                last_ms: f.last_ms,
            })
            .collect(),
        window_days: contact_frequent::WINDOW_MS / 86_400_000,
    })
}

fn frequent_cases() -> Vec<Case> {
    let call = |ms_ago: i64, dir: &str| json!({"number": "+90 216 555 03 04", "date_ms": NOW - ms_ago, "name": null, "duration_s": 30, "dir": dir, "whatsapp": false});
    vec![
        Case::new(
            json!({"calls": [call(3_600_000, "incoming"), call(86_400_000, "outgoing"), call(10 * 86_400_000, "missed")],
                         "contacts": wires(), "now_ms": NOW, "limit": 6}),
        ),
        Case::named(
            "none",
            json!({"calls": [], "contacts": wires(), "now_ms": NOW}),
        ),
    ]
}

// ---- my card / emergency ----

#[derive(Deserialize)]
struct MeReq {
    /// The lookup key of "my card".
    #[serde(default)]
    key: Option<String>,
    all: Vec<WireContact>,
    emergency: Vec<String>,
}

#[derive(Serialize)]
struct MeAns {
    my_card: Row2,
    my_card_contact: Option<i64>,
    emergency: Vec<EmergencyDto>,
    /// The text shared from the emergency list.
    emergency_text: String,
    max: usize,
    my_card_set_notice: &'static str,
    my_card_removed_notice: &'static str,
}

#[derive(Serialize)]
struct EmergencyDto {
    contact_id: i64,
    name: String,
    number: String,
}

fn me_state(req: MeReq) -> Result<MeAns, FfiError> {
    let found = contact_me::resolve(req.key.as_deref(), &req.all);
    let (title, detail) = contact_me::my_card_row(found);
    let list = contact_me::emergency_list(&req.emergency, &req.all);
    let entries: Vec<EmergencyDto> = list
        .iter()
        .map(|c| EmergencyDto {
            contact_id: c.id,
            name: c.display_name.clone(),
            number: c
                .numbers
                .iter()
                .find(|n| n.default)
                .or_else(|| c.numbers.first())
                .map(|n| n.raw.clone())
                .unwrap_or_default(),
        })
        .collect();
    let pairs: Vec<(&str, &str)> = entries
        .iter()
        .map(|e| (e.name.as_str(), e.number.as_str()))
        .collect();
    Ok(MeAns {
        emergency_text: contact_me::emergency_text(found.map(|c| c.display_name.as_str()), &pairs),
        my_card: Row2 { title, detail },
        my_card_contact: found.map(|c| c.id),
        emergency: entries,
        max: MAX_EMERGENCY,
        my_card_set_notice: contact_me::my_card_notice(true),
        my_card_removed_notice: contact_me::my_card_notice(false),
    })
}

fn me_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"key": "k-ayse", "all": wires(), "emergency": ["k-mehmet", "gone"]})),
        Case::named("unset", json!({"all": wires(), "emergency": []})),
    ]
}

#[derive(Deserialize)]
struct ToggleReq {
    keys: Vec<String>,
    contact: WireContact,
}

#[derive(Serialize)]
struct ToggleAns {
    keys: Vec<String>,
    /// `true` added, `false` removed, `None` refused.
    added: Option<bool>,
    notice: String,
}

fn me_toggle(req: ToggleReq) -> Result<ToggleAns, FfiError> {
    Ok(
        match contact_me::toggle_emergency(&req.keys, &req.contact) {
            Ok((keys, added)) => ToggleAns {
                keys,
                added: Some(added),
                notice: contact_me::emergency_notice(added).to_owned(),
            },
            Err(e @ (EmergencyError::Full | EmergencyError::NoNumber | EmergencyError::NoKey)) => {
                ToggleAns {
                    keys: req.keys,
                    added: None,
                    notice: e.to_string(),
                }
            }
        },
    )
}

fn toggle_cases() -> Vec<Case> {
    let w = wires();
    vec![
        Case::new(json!({"keys": ["k-ayse"], "contact": w[1]})),
        Case::named(
            "remove",
            json!({"keys": ["k-ayse", "k-mehmet"], "contact": w[1]}),
        ),
        Case::named("no_number", json!({"keys": [], "contact": w[2]})),
        Case::named(
            "full",
            json!({"keys": ["a", "b", "c", "d", "e"], "contact": w[0]}),
        ),
    ]
}

// ---- birthday reminders ----

#[derive(Deserialize)]
struct ReminderReq {
    drafts: Vec<ContactDraft>,
    now_ms: i64,
    tz_offset_s: i32,
    hour: u8,
    hour12: bool,
    /// Alarm ids already armed.
    armed: Vec<i64>,
}

#[derive(Serialize)]
struct ReminderAns {
    alarms: Vec<AlarmDto>,
    /// Armed alarms to cancel.
    disarm: Vec<i64>,
    /// Alarms to set (ids of `alarms`).
    arm: Vec<i64>,
    hours: Vec<HourDto>,
    hour_label: String,
    default_hour: u8,
}

#[derive(Serialize)]
struct AlarmDto {
    id: i64,
    contact_id: i64,
    at_ms: i64,
    title: String,
    body: String,
}

#[derive(Serialize)]
struct HourDto {
    hour: u8,
    label: String,
}

fn birthday_reminders(req: ReminderReq) -> Result<ReminderAns, FfiError> {
    let upcoming = contact_dates::upcoming(
        &req.drafts,
        req.now_ms,
        req.tz_offset_s,
        i64::from(birthday_reminder::HORIZON_DAYS),
    );
    let alarms = birthday_reminder::plan(&upcoming, req.now_ms, req.tz_offset_s, req.hour);
    let (disarm, arm) = birthday_reminder::diff(&req.armed, &alarms);
    Ok(ReminderAns {
        arm: arm.iter().map(|a| a.id).collect(),
        disarm,
        hours: birthday_reminder::HOUR_CHOICES
            .iter()
            .map(|h| HourDto {
                hour: *h,
                label: birthday_reminder::hour_label(*h, req.hour12),
            })
            .collect(),
        hour_label: birthday_reminder::hour_label(req.hour, req.hour12),
        default_hour: birthday_reminder::DEFAULT_HOUR,
        alarms: alarms
            .into_iter()
            .map(|a| AlarmDto {
                id: a.id,
                contact_id: a.contact_id,
                at_ms: a.at_ms,
                title: a.title,
                body: a.body,
            })
            .collect(),
    })
}

fn reminder_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"drafts": drafts(), "now_ms": NOW, "tz_offset_s": TZ, "hour": 9, "hour12": false, "armed": [42]}),
        ),
        Case::named(
            "none",
            json!({"drafts": [], "now_ms": NOW, "tz_offset_s": TZ, "hour": 21, "hour12": true, "armed": []}),
        ),
    ]
}

// ---- options / pin ----

#[derive(Deserialize)]
struct OptionsReq {
    #[serde(default)]
    options: Option<ContactOptions>,
    /// The stored JSON text instead of `options`.
    #[serde(default)]
    json: Option<String>,
    name: String,
}

#[derive(Serialize)]
struct OptionsAns {
    options: ContactOptions,
    ringtone_text: String,
    ringtone_notice: String,
    voicemail_confirm: Confirm,
    voicemail_on_notice: &'static str,
    voicemail_off_notice: &'static str,
    pin_ok_notice: &'static str,
    pin_failed_notice: &'static str,
}

fn options(req: OptionsReq) -> Result<OptionsAns, FfiError> {
    let options = match (req.options, req.json) {
        (Some(o), _) => o,
        (None, Some(text)) => contact_options::parse(&text)?,
        (None, None) => ContactOptions::default(),
    };
    Ok(OptionsAns {
        ringtone_text: contact_options::ringtone_text(&options),
        ringtone_notice: contact_options::ringtone_notice(&options),
        voicemail_confirm: Confirm::of(contact_options::voicemail_confirm(&req.name)),
        voicemail_on_notice: contact_options::voicemail_notice(true),
        voicemail_off_notice: contact_options::voicemail_notice(false),
        pin_ok_notice: contact_options::pin_notice(true),
        pin_failed_notice: contact_options::pin_notice(false),
        options,
    })
}

fn options_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"options": {"ringtone_uri": "content://media/internal/audio/media/9", "ringtone_title": "Beacon", "to_voicemail": false},
                         "name": "Ayşe Yılmaz"}),
        ),
        Case::named(
            "silent",
            json!({"options": {"ringtone_uri": "", "ringtone_title": null, "to_voicemail": true}, "name": ""}),
        ),
        Case::named(
            "from_json",
            json!({"json": "{\"to_voicemail\":true}", "name": "Cem"}),
        ),
        Case::named("bad_json", json!({"json": "{oops", "name": "Cem"})),
    ]
}

#[derive(Deserialize)]
struct PinReq {
    contact_id: i64,
    #[serde(default)]
    lookup_key: Option<String>,
    name: String,
    #[serde(default)]
    icon_path: Option<String>,
}

#[derive(Serialize)]
struct PinAns {
    shortcut: PinShortcut,
}

fn pin_shortcut(req: PinReq) -> Result<PinAns, FfiError> {
    Ok(PinAns {
        shortcut: contact_options::shortcut(
            req.contact_id,
            req.lookup_key.as_deref(),
            &req.name,
            req.icon_path.as_deref(),
        ),
    })
}

fn pin_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"contact_id": 1, "lookup_key": "k-ayse", "name": "Ayşe Yılmaz Demirci Uzunsoyad",
                         "icon_path": "/cache/shortcut-1.png"}),
        ),
        Case::named("no_name", json!({"contact_id": 7, "name": " "})),
    ]
}

// ---- deep find (handle) ----

fn with_deep<R>(handle: i64, f: impl FnOnce(&mut DeepIndex) -> R) -> Result<R, FfiError> {
    handles::global().with(handle, f).ok_or_else(|| {
        FfiError::new(
            ErrorKind::NotFound,
            format!("detail index handle {handle} is not open"),
        )
    })
}

#[derive(Deserialize)]
struct DeepNewReq {
    drafts: Vec<ContactDraft>,
}

#[derive(Serialize)]
struct DeepNewAns {
    handle: i64,
    count: usize,
}

fn deep_new(req: DeepNewReq) -> Result<DeepNewAns, FfiError> {
    let count = req.drafts.len();
    Ok(DeepNewAns {
        handle: handles::global().insert(DeepIndex::new(&req.drafts)),
        count,
    })
}

fn deep_new_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"drafts": drafts()})),
        Case::named("empty", json!({"drafts": []})),
    ]
}

#[derive(Deserialize)]
struct DeepFindReq {
    handle: i64,
    query: String,
    #[serde(default)]
    skip: Vec<i64>,
    limit: usize,
}

#[derive(Serialize)]
struct DeepFindAns {
    hits: Vec<HitDto>,
}

#[derive(Serialize)]
struct HitDto {
    id: i64,
    /// `company`, `job_title`, `nickname`, `phonetic_name`, `relation`, `note`, `address`, `email`, `website`.
    field: &'static str,
    label: &'static str,
    snippet: String,
}

fn deep_find(req: DeepFindReq) -> Result<DeepFindAns, FfiError> {
    let hits = with_deep(req.handle, |index| {
        index.search(&req.query, &req.skip, req.limit)
    })?;
    Ok(DeepFindAns {
        hits: hits
            .into_iter()
            .map(|h| HitDto {
                id: h.id,
                field: match h.field {
                    DeepField::Company => "company",
                    DeepField::JobTitle => "job_title",
                    DeepField::Nickname => "nickname",
                    DeepField::PhoneticName => "phonetic_name",
                    DeepField::Relation => "relation",
                    DeepField::Note => "note",
                    DeepField::Address => "address",
                    DeepField::Email => "email",
                    DeepField::Website => "website",
                },
                label: h.field.label(),
                snippet: h.snippet,
            })
            .collect(),
    })
}

fn deep_find_cases() -> Vec<Case> {
    let setup = (
        "contacts.deep_index_new",
        json!({"drafts": [
            d(json!({"id": 1, "given": "Ada", "org": "Analytical Engines Ltd", "title": "Countess"})),
            d(json!({"id": 2, "given": "Bob", "note": "met at the engine works", "emails": [{"value": "bob@engines.example"}]})),
        ]}),
    );
    vec![
        Case::new(json!({"handle": H, "query": "engine", "limit": 10}))
            .after(setup.0, setup.1.clone()),
        Case::named(
            "skip",
            json!({"handle": H, "query": "engine", "skip": [1], "limit": 10}),
        )
        .after(setup.0, setup.1),
        Case::named(
            "stale_handle",
            json!({"handle": H, "query": "engine", "limit": 10}),
        ),
    ]
}
