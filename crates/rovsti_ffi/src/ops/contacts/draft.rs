//! `contacts.draft_*`, dates, duplicates, cleanup, the card and the small pure rules around the editor.

use super::{NOW, NumberDto, TZ, ayse, ayse_twin, cem, d, drafts, mehmet, wires};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::avatar::{self, AvatarKey};
use rovsti_core::contact_card::ContactCard;
use rovsti_core::contact_cleanup::{self, Cleanup};
use rovsti_core::contact_dates::{self, EventDate, Upcoming};
use rovsti_core::contact_dupes::{self, DupReason};
use rovsti_core::contact_edit::{
    self, AddError, Addition, ContactDraft, DraftError, DraftEvent, EventKind, Group, RelationTap,
};
use rovsti_core::contact_quick::{self, QuickAction};
use rovsti_core::contact_stats;
use rovsti_core::contacts_view::{self, CallTarget};
use rovsti_core::wire::{NameFormat, WireContact};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashSet;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "contacts.draft_validate",
        DraftReq,
        ValidateAns,
        validate,
        validate_cases
    ),
    op!("contacts.draft_diff", DiffReq, DiffAns, diff, diff_cases),
    op!(
        "contacts.draft_from_query",
        QueryReq,
        FromQueryAns,
        from_query,
        from_query_cases
    ),
    op!("contacts.draft_add", AddReq, AddAns, add, add_cases),
    op!("contacts.draft_edit", EditReq, EditAns, edit, edit_cases),
    op!(
        "contacts.field_menu",
        FieldMenuReq,
        FieldMenuAns,
        field_menu,
        field_menu_cases
    ),
    op!("contacts.web_url", WebReq, WebAns, web_url, web_cases),
    op!(
        "contacts.relation_tap",
        RelationReq,
        RelationAns,
        relation_tap,
        relation_cases
    ),
    op!(
        "contacts.account_label",
        AccountReq,
        LabelAns,
        account_label,
        account_cases
    ),
    op!("contacts.dates", DatesReq, DatesAns, dates, dates_cases),
    op!(
        "contacts.date_parse",
        ParseReq,
        ParseAns,
        date_parse,
        parse_cases
    ),
    op!("contacts.age", AgeReq, AgeAns, age, age_cases),
    op!(
        "contacts.event_line",
        EventLineReq,
        EventLineAns,
        event_line,
        event_line_cases
    ),
    op!("contacts.maps_url", MapsReq, MapsAns, maps_url, maps_cases),
    op!("contacts.local_ymd", YmdReq, YmdAns, local_ymd, ymd_cases),
    op!("contacts.card", CardReq, CardAns, card, card_cases),
    op!("contacts.dupes", DraftsReq, DupesAns, dupes, dupes_cases),
    op!(
        "contacts.merge_plan",
        MergeReq,
        MergeAns,
        merge_plan,
        merge_cases
    ),
    op!(
        "contacts.cleanup",
        CleanupReq,
        CleanupAns,
        cleanup,
        cleanup_cases
    ),
    op!(
        "contacts.avatar",
        AvatarReq,
        AvatarAns,
        avatar_of,
        avatar_cases
    ),
    op!("contacts.stats", DraftsReq, StatsAns, stats, stats_cases),
    op!("contacts.quick", QuickReq, QuickAns, quick, quick_cases),
];

#[derive(Deserialize)]
struct DraftReq {
    draft: ContactDraft,
}

#[derive(Deserialize)]
struct DraftsReq {
    drafts: Vec<ContactDraft>,
}

// ---- draft_validate ----

#[derive(Serialize)]
struct ValidateAns {
    /// The draft cleaned the way a save would.
    draft: ContactDraft,
    error: Option<DraftIssue>,
}

#[derive(Serialize)]
struct DraftIssue {
    /// `no_name`, `bad_number`, `bad_email` or `bad_date`.
    kind: &'static str,
    /// The entry that is wrong (0-based), for the three list kinds.
    index: Option<usize>,
    message: String,
}

fn validate(req: DraftReq) -> Result<ValidateAns, FfiError> {
    let mut draft = req.draft;
    draft.clean();
    let error = draft.validate().err().map(|e| {
        let (kind, index) = match e {
            DraftError::NoName => ("no_name", None),
            DraftError::BadNumber(i) => ("bad_number", Some(i)),
            DraftError::BadEmail(i) => ("bad_email", Some(i)),
            DraftError::BadDate(i) => ("bad_date", Some(i)),
        };
        DraftIssue {
            kind,
            index,
            message: e.to_string(),
        }
    });
    Ok(ValidateAns { draft, error })
}

fn validate_cases() -> Vec<Case> {
    let messy = d(
        json!({"given": "  Ada ", "numbers": [{"value": " 0555 111 22 33 "}, {"value": "05551112233"}]}),
    );
    vec![
        Case::new(json!({"draft": messy})),
        Case::named("no_name", json!({"draft": d(json!({"note": "x"}))})),
        Case::named(
            "bad_email",
            json!({"draft": d(json!({"given": "Ada", "emails": [{"value": "ada-at-example"}]}))}),
        ),
        Case::named(
            "bad_date",
            json!({"draft": d(json!({"given": "Ada", "events": [{"date": "31-02", "kind": "birthday"}]}))}),
        ),
    ]
}

// ---- draft_diff ----

#[derive(Deserialize)]
struct DiffReq {
    before: ContactDraft,
    after: ContactDraft,
}

#[derive(Serialize)]
struct DiffAns {
    summary: Option<String>,
    /// "Saved · Number added" or "Saved.".
    notice: String,
    /// The draft that puts the old values back (Undo), `None` when nothing changed or the contact is new.
    undo: Option<ContactDraft>,
}

fn diff(req: DiffReq) -> Result<DiffAns, FfiError> {
    let summary = contact_edit::change_summary(&req.before, &req.after);
    Ok(DiffAns {
        notice: contact_edit::saved_notice(summary.clone()),
        undo: contact_edit::undo_target(&req.before, &req.after),
        summary,
    })
}

fn diff_cases() -> Vec<Case> {
    let before = ayse();
    let mut after = before.clone();
    after["numbers"]
        .as_array_mut()
        .expect("numbers")
        .push(json!({"value": "0555 000 11 22", "kind": "home"}));
    vec![
        Case::new(json!({"before": before, "after": after})),
        Case::named("same", json!({"before": ayse(), "after": ayse()})),
        Case::named(
            "new_contact",
            json!({"before": d(json!({"given": "Ada"})), "after": d(json!({"given": "Ada B"}))}),
        ),
    ]
}

// ---- draft_from_query ----

#[derive(Deserialize)]
struct QueryReq {
    query: String,
}

#[derive(Serialize)]
struct FromQueryAns {
    draft: Option<ContactDraft>,
    /// The numbers and emails the query holds, for "Add to an existing contact".
    addition: Option<AdditionDto>,
}

#[derive(Serialize, Deserialize)]
struct AdditionDto {
    numbers: Vec<String>,
    emails: Vec<String>,
}

fn from_query(req: QueryReq) -> Result<FromQueryAns, FfiError> {
    Ok(FromQueryAns {
        draft: contact_edit::draft_from_query(&req.query),
        addition: Addition::from_query(&req.query).map(|a| AdditionDto {
            numbers: a.numbers,
            emails: a.emails,
        }),
    })
}

fn from_query_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"query": "Ada Lovelace"})),
        Case::named("number", json!({"query": "0555 111 22 33"})),
        Case::named("email", json!({"query": "ada@example.org"})),
        Case::named("blank", json!({"query": "  "})),
    ]
}

// ---- draft_add ----

#[derive(Deserialize)]
struct AddReq {
    target: ContactDraft,
    add: AdditionDto,
}

#[derive(Serialize)]
struct AddAns {
    draft: Option<ContactDraft>,
    added: usize,
    /// "Number added to Ayşe Yılmaz", or the reason nothing was added.
    notice: String,
    refused: bool,
}

fn add(req: AddReq) -> Result<AddAns, FfiError> {
    let addition = Addition {
        numbers: req.add.numbers,
        emails: req.add.emails,
    };
    Ok(
        match contact_edit::add_to_existing(&req.target, &addition) {
            Ok((draft, added)) => AddAns {
                notice: contact_edit::added_notice(&draft.display_name(), &addition, added),
                draft: Some(draft),
                added,
                refused: false,
            },
            Err(e @ (AddError::AlreadyThere(_) | AddError::Empty)) => AddAns {
                draft: None,
                added: 0,
                notice: e.to_string(),
                refused: true,
            },
        },
    )
}

fn add_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"target": ayse(), "add": {"numbers": ["0555 000 11 22"], "emails": []}})),
        Case::named(
            "already_there",
            json!({"target": ayse(), "add": {"numbers": ["+90 532 123 45 67"], "emails": []}}),
        ),
        Case::named(
            "empty",
            json!({"target": ayse(), "add": {"numbers": [], "emails": []}}),
        ),
    ]
}

// ---- draft_edit ----

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Edit {
    SetPrimary,
    RetainAccountGroups,
    Clean,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
enum List {
    Numbers,
    Emails,
    Addresses,
}

#[derive(Deserialize)]
struct EditReq {
    draft: ContactDraft,
    edit: Edit,
    /// `set_primary`: the list; the index is the entry that becomes the default (absent clears the default).
    #[serde(default)]
    list: Option<List>,
    #[serde(default)]
    index: Option<usize>,
    /// `retain_account_groups`: the groups of the account.
    #[serde(default)]
    groups: Vec<Group>,
}

#[derive(Serialize)]
struct EditAns {
    draft: ContactDraft,
    /// The default entry of each list after the edit.
    primary_numbers: Option<usize>,
    primary_emails: Option<usize>,
}

fn edit(req: EditReq) -> Result<EditAns, FfiError> {
    let mut draft = req.draft;
    match req.edit {
        Edit::SetPrimary => {
            let list = match req.list.unwrap_or(List::Numbers) {
                List::Numbers => &mut draft.numbers,
                List::Emails => &mut draft.emails,
                List::Addresses => &mut draft.addresses,
            };
            contact_edit::set_primary(list, req.index);
        }
        Edit::RetainAccountGroups => contact_edit::retain_account_groups(&mut draft, &req.groups),
        Edit::Clean => draft.clean(),
    }
    Ok(EditAns {
        primary_numbers: contact_edit::primary_of(&draft.numbers),
        primary_emails: contact_edit::primary_of(&draft.emails),
        draft,
    })
}

fn edit_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"draft": ayse(), "edit": "set_primary", "list": "numbers", "index": 1})),
        Case::named(
            "clear_default",
            json!({"draft": ayse(), "edit": "set_primary", "list": "numbers"}),
        ),
        Case::named(
            "retain_groups",
            json!({"draft": mehmet(), "edit": "retain_account_groups",
                   "groups": [{"id": 10, "title": "Family", "members": [1, 2], "account": "com.google|ayse@example.org"},
                              {"id": 11, "title": "Work", "members": [2], "account": "other"}]}),
        ),
        Case::named(
            "clean",
            json!({"draft": d(json!({"given": " Ada ", "websites": ["a.org", "A.org"]})), "edit": "clean"}),
        ),
    ]
}

// ---- field_menu / web_url / relation_tap / account_label ----

#[derive(Deserialize)]
struct FieldMenuReq {
    is_primary: bool,
    count: usize,
}

#[derive(Serialize)]
struct FieldMenuAns {
    /// "Set as default" / "Clear default"; `None` when the list has a single entry.
    action: Option<FieldAction>,
}

#[derive(Serialize)]
struct FieldAction {
    label: &'static str,
    clears: bool,
}

fn field_menu(req: FieldMenuReq) -> Result<FieldMenuAns, FfiError> {
    Ok(FieldMenuAns {
        action: contact_edit::default_action(req.is_primary, req.count)
            .map(|(label, clears)| FieldAction { label, clears }),
    })
}

fn field_menu_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"is_primary": false, "count": 3})),
        Case::named("clear", json!({"is_primary": true, "count": 2})),
        Case::named("single", json!({"is_primary": false, "count": 1})),
    ]
}

#[derive(Deserialize)]
struct WebReq {
    text: String,
}

#[derive(Serialize)]
struct WebAns {
    url: Option<String>,
}

fn web_url(req: WebReq) -> Result<WebAns, FfiError> {
    Ok(WebAns {
        url: contact_edit::web_url(&req.text),
    })
}

fn web_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "example.org/about"})),
        Case::named("other_scheme", json!({"text": "ftp://example.org"})),
    ]
}

#[derive(Deserialize)]
struct RelationReq {
    name: String,
    all: Vec<WireContact>,
}

#[derive(Serialize)]
struct RelationAns {
    /// `open` (a contact has the name) or `search`.
    action: &'static str,
    contact_id: Option<i64>,
    query: Option<String>,
}

fn relation_tap(req: RelationReq) -> Result<RelationAns, FfiError> {
    Ok(match contact_edit::relation_tap(&req.name, &req.all) {
        RelationTap::Open(id) => RelationAns {
            action: "open",
            contact_id: Some(id),
            query: None,
        },
        RelationTap::Search(query) => RelationAns {
            action: "search",
            contact_id: None,
            query: Some(query),
        },
    })
}

fn relation_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"name": "Mehmet Demir", "all": wires()})),
        Case::named("search", json!({"name": "Nobody", "all": wires()})),
    ]
}

#[derive(Deserialize)]
struct AccountReq {
    key: String,
}

#[derive(Serialize)]
struct LabelAns {
    label: String,
}

fn account_label(req: AccountReq) -> Result<LabelAns, FfiError> {
    Ok(LabelAns {
        label: contact_edit::account_label(&req.key),
    })
}

fn account_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"key": "com.google|ayse@example.org"})),
        Case::named("phone", json!({"key": ""})),
    ]
}

// ---- dates ----

#[derive(Serialize)]
struct UpcomingDto {
    contact_id: i64,
    name: String,
    kind: EventKind,
    in_days: i64,
    date_text: String,
    turning: Option<i64>,
    /// "Turns 36" / "11 years".
    turning_text: Option<String>,
    /// "Today", "Tomorrow", "In 5 days".
    when_text: String,
}

impl From<&Upcoming> for UpcomingDto {
    fn from(u: &Upcoming) -> Self {
        Self {
            contact_id: u.contact_id,
            name: u.name.clone(),
            kind: u.kind,
            in_days: u.in_days,
            date_text: u.date_text.clone(),
            turning: u.turning,
            turning_text: contact_dates::turning_text(u),
            when_text: contact_dates::when_text(u.in_days),
        }
    }
}

#[derive(Deserialize)]
struct DatesReq {
    drafts: Vec<ContactDraft>,
    now_ms: i64,
    tz_offset_s: i32,
    within_days: i64,
    /// The most entries of the Favorites tab strip.
    favorites_limit: usize,
}

#[derive(Serialize)]
struct DatesAns {
    items: Vec<UpcomingDto>,
    favorites: Vec<UpcomingDto>,
    groups: Vec<DateGroup>,
}

#[derive(Serialize)]
struct DateGroup {
    title: &'static str,
    items: Vec<UpcomingDto>,
}

fn dates(req: DatesReq) -> Result<DatesAns, FfiError> {
    let items = contact_dates::upcoming(&req.drafts, req.now_ms, req.tz_offset_s, req.within_days);
    Ok(DatesAns {
        favorites: contact_dates::favorites_upcoming(&items, req.favorites_limit)
            .iter()
            .map(UpcomingDto::from)
            .collect(),
        groups: contact_dates::upcoming_groups(&items)
            .into_iter()
            .map(|(title, rows)| DateGroup {
                title,
                items: rows.iter().map(UpcomingDto::from).collect(),
            })
            .collect(),
        items: items.iter().map(UpcomingDto::from).collect(),
    })
}

fn dates_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"drafts": drafts(), "now_ms": NOW, "tz_offset_s": TZ, "within_days": 365, "favorites_limit": 3}),
        ),
        Case::named(
            "none",
            json!({"drafts": [cem()], "now_ms": NOW, "tz_offset_s": TZ, "within_days": 30, "favorites_limit": 3}),
        ),
    ]
}

#[derive(Serialize)]
struct DateDto {
    year: Option<i32>,
    month: u32,
    day: u32,
    /// `YYYY-MM-DD` or `--MM-DD`, as the editor stores it.
    android: String,
    /// "7 Mar 1990".
    text: String,
}

impl From<EventDate> for DateDto {
    fn from(date: EventDate) -> Self {
        Self {
            year: date.year,
            month: date.md.month,
            day: date.md.day,
            android: date.to_android(),
            text: date.text(),
        }
    }
}

#[derive(Deserialize)]
struct ParseReq {
    text: String,
}

#[derive(Serialize)]
struct ParseAns {
    /// What the strict reader takes (`YYYY-MM-DD`, `--MM-DD`, ...).
    strict: Option<DateDto>,
    /// What the editor's date box takes (also `MM-DD`, `M/D`).
    input: Option<DateDto>,
    /// The text the box shows after normalising.
    normalized: String,
    /// The text the box shows for a stored date.
    input_text: String,
}

fn date_parse(req: ParseReq) -> Result<ParseAns, FfiError> {
    Ok(ParseAns {
        strict: contact_dates::parse(&req.text).map(DateDto::from),
        input: contact_dates::parse_input(&req.text).map(DateDto::from),
        normalized: contact_dates::normalize_input(&req.text),
        input_text: contact_dates::input_text(&req.text),
    })
}

fn parse_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "1990-10-03"})),
        Case::named("short", json!({"text": "3/10"})),
        Case::named("no_year", json!({"text": "--02-29"})),
        Case::named("bad", json!({"text": "31-02"})),
    ]
}

#[derive(Deserialize)]
struct AgeReq {
    date: String,
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct AgeAns {
    valid: bool,
    /// Completed years today.
    age: Option<i64>,
    days_until: Option<i64>,
    /// The age on the next occurrence (today counts).
    turning: Option<i64>,
    when_text: Option<String>,
}

fn age(req: AgeReq) -> Result<AgeAns, FfiError> {
    let today = contact_dates::local_day(req.now_ms, req.tz_offset_s);
    let Some(date) = contact_dates::parse(&req.date) else {
        return Ok(AgeAns {
            valid: false,
            age: None,
            days_until: None,
            turning: None,
            when_text: None,
        });
    };
    let days = contact_dates::days_until(date.md, today);
    Ok(AgeAns {
        valid: true,
        age: contact_dates::age_on(date, today),
        days_until: Some(days),
        turning: contact_dates::age_on(date, today + days),
        when_text: Some(contact_dates::when_text(days)),
    })
}

fn age_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"date": "1990-10-03", "now_ms": NOW, "tz_offset_s": TZ})),
        Case::named(
            "no_year",
            json!({"date": "--10-03", "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "invalid",
            json!({"date": "soon", "now_ms": NOW, "tz_offset_s": TZ}),
        ),
    ]
}

#[derive(Deserialize)]
struct EventLineReq {
    event: DraftEvent,
    name: String,
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct EventLineAns {
    /// "Birthday · 3 Oct 1990 · In 12 days"; `None` for an unreadable date.
    line: Option<String>,
    turning: Option<i64>,
    /// "Happy 36th birthday, Ayşe!".
    wish: String,
    /// The kind the editor's chip shows after a tap.
    next_kind: EventKind,
}

fn event_line(req: EventLineReq) -> Result<EventLineAns, FfiError> {
    let today = contact_dates::local_day(req.now_ms, req.tz_offset_s);
    let turning = contact_dates::turning(&req.event, today);
    Ok(EventLineAns {
        line: contact_dates::card_line(&req.event, req.now_ms, req.tz_offset_s),
        wish: contact_dates::wish_text(&req.name, req.event.kind, turning),
        next_kind: contact_dates::next_event_kind(req.event.kind),
        turning,
    })
}

fn event_line_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"event": {"date": "1990-10-03", "kind": "birthday"}, "name": "Ayşe Yılmaz",
                         "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "anniversary",
            json!({"event": {"date": "--09-25", "kind": "anniversary"}, "name": "", "now_ms": NOW, "tz_offset_s": TZ}),
        ),
    ]
}

#[derive(Deserialize)]
struct MapsReq {
    address: String,
}

#[derive(Serialize)]
struct MapsAns {
    url: Option<String>,
}

fn maps_url(req: MapsReq) -> Result<MapsAns, FfiError> {
    Ok(MapsAns {
        url: contact_dates::maps_url(&req.address),
    })
}

fn maps_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"address": "Istiklal Cd. 1\nBeyoğlu, İstanbul"})),
        Case::named("blank", json!({"address": " \n "})),
    ]
}

#[derive(Deserialize)]
struct YmdReq {
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct YmdAns {
    year: i32,
    month: u8,
    day: u8,
}

fn local_ymd(req: YmdReq) -> Result<YmdAns, FfiError> {
    let (year, month, day) = contact_dates::local_ymd(req.now_ms, req.tz_offset_s);
    Ok(YmdAns { year, month, day })
}

fn ymd_cases() -> Vec<Case> {
    vec![Case::new(json!({"now_ms": NOW, "tz_offset_s": TZ}))]
}

// ---- card ----

#[derive(Deserialize)]
struct CardReq {
    contact: WireContact,
    #[serde(default)]
    format: NameFormat,
}

#[derive(Serialize)]
struct CardAns {
    id: i64,
    name: String,
    monogram: Option<String>,
    hue: Option<usize>,
    /// Default number first.
    numbers: Vec<NumberDto>,
    starred: bool,
    /// What the Call button does: `direct` (`index`), `choose` or `nothing`.
    call_target: &'static str,
    call_index: Option<usize>,
    share_text: String,
}

pub(super) fn call_target(numbers: &[contacts_view::NumberVm]) -> (&'static str, Option<usize>) {
    match contacts_view::call_target(numbers) {
        CallTarget::Direct(i) => ("direct", Some(i)),
        CallTarget::Choose => ("choose", None),
        CallTarget::Nothing => ("nothing", None),
    }
}

fn card(req: CardReq) -> Result<CardAns, FfiError> {
    let card = ContactCard::new(&req.contact, req.format);
    let (call_target, call_index) = call_target(&card.numbers);
    Ok(CardAns {
        share_text: card.share_text(),
        id: card.id,
        monogram: card.monogram.map(String::from),
        hue: card.hue,
        numbers: card.numbers.iter().map(NumberDto::from).collect(),
        starred: card.starred,
        call_target,
        call_index,
        name: card.name,
    })
}

fn card_cases() -> Vec<Case> {
    let wire = wires();
    vec![
        Case::new(json!({"contact": wire[0], "format": "first_last"})),
        Case::named(
            "last_first",
            json!({"contact": wire[1], "format": "last_first"}),
        ),
        Case::named("no_number", json!({"contact": wire[2]})),
    ]
}

// ---- duplicates ----

#[derive(Serialize)]
struct DupesAns {
    groups: Vec<DupGroupDto>,
    summary: String,
}

#[derive(Serialize)]
struct DupGroupDto {
    /// The keeper is `ids[0]`.
    ids: Vec<i64>,
    /// `same_number`, `same_email` or `same_name`.
    reason: &'static str,
    label: &'static str,
}

fn dupes(req: DraftsReq) -> Result<DupesAns, FfiError> {
    let groups = contact_dupes::find(&req.drafts);
    Ok(DupesAns {
        summary: contact_dupes::summary(&groups),
        groups: groups
            .iter()
            .map(|g| DupGroupDto {
                ids: g.ids.clone(),
                reason: match g.reason {
                    DupReason::SameNumber => "same_number",
                    DupReason::SameEmail => "same_email",
                    DupReason::SameName => "same_name",
                },
                label: g.reason.label(),
            })
            .collect(),
    })
}

fn dupes_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"drafts": drafts()})),
        Case::named("none", json!({"drafts": [ayse(), cem()]})),
    ]
}

#[derive(Deserialize)]
struct MergeReq {
    keeper: ContactDraft,
    others: Vec<ContactDraft>,
}

#[derive(Serialize)]
struct MergeAns {
    draft: ContactDraft,
    notice: String,
}

fn merge_plan(req: MergeReq) -> Result<MergeAns, FfiError> {
    Ok(MergeAns {
        notice: contact_dupes::merged_notice(req.others.len() + 1),
        draft: contact_dupes::merge(&req.keeper, &req.others),
    })
}

fn merge_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"keeper": ayse(), "others": [ayse_twin()]}),
    )]
}

// ---- cleanup ----

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
#[allow(clippy::enum_variant_names)]
enum CleanupId {
    NoNumber,
    NoName,
    NoPhoto,
    NoGroup,
}

impl CleanupId {
    fn of(self) -> Cleanup {
        match self {
            Self::NoNumber => Cleanup::NoNumber,
            Self::NoName => Cleanup::NoName,
            Self::NoPhoto => Cleanup::NoPhoto,
            Self::NoGroup => Cleanup::NoGroup,
        }
    }

    const ALL: [Self; 4] = [Self::NoNumber, Self::NoName, Self::NoPhoto, Self::NoGroup];
}

#[derive(Deserialize)]
struct CleanupReq {
    drafts: Vec<ContactDraft>,
    /// Ids of the contacts that have a photo.
    photo_ids: Vec<i64>,
    /// The filter whose contact ids to list.
    #[serde(default)]
    filter: Option<CleanupId>,
}

#[derive(Serialize)]
struct CleanupAns {
    filters: Vec<CleanupFilter>,
    /// The ids under `filter`, in list order.
    ids: Vec<i64>,
}

#[derive(Serialize)]
struct CleanupFilter {
    id: CleanupId,
    label: &'static str,
    count: usize,
    empty_text: String,
}

fn cleanup(req: CleanupReq) -> Result<CleanupAns, FfiError> {
    let photos: HashSet<i64> = req.photo_ids.into_iter().collect();
    let counts = contact_cleanup::counts(&req.drafts, &photos);
    Ok(CleanupAns {
        filters: CleanupId::ALL
            .iter()
            .zip(counts)
            .map(|(id, count)| CleanupFilter {
                id: *id,
                label: id.of().label(),
                count,
                empty_text: contact_cleanup::empty_text(id.of()),
            })
            .collect(),
        ids: req
            .filter
            .map(|f| contact_cleanup::ids(&req.drafts, &photos, f.of()))
            .unwrap_or_default(),
    })
}

fn cleanup_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"drafts": drafts(), "photo_ids": [1], "filter": "no_photo"})),
        Case::named("counts_only", json!({"drafts": drafts(), "photo_ids": []})),
    ]
}

// ---- avatar / stats / quick ----

#[derive(Deserialize)]
struct AvatarReq {
    name: String,
    #[serde(default)]
    contact_id: Option<i64>,
    /// The digits of an unsaved number, for a stable colour.
    #[serde(default)]
    number_digits: Option<String>,
}

#[derive(Serialize)]
struct AvatarAns {
    monogram: Option<String>,
    initials: String,
    /// Index of the hue pair (0..6); `None` draws the neutral grey.
    hue: Option<usize>,
}

fn avatar_of(req: AvatarReq) -> Result<AvatarAns, FfiError> {
    let key = match (&req.contact_id, &req.number_digits) {
        (Some(id), _) => AvatarKey::Contact(*id),
        (None, Some(digits)) => AvatarKey::Number(digits),
        (None, None) => AvatarKey::Unknown,
    };
    Ok(AvatarAns {
        monogram: avatar::monogram(&req.name).map(String::from),
        initials: avatar::initials(&req.name),
        hue: avatar::avatar_hue(key),
    })
}

fn avatar_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"name": "İlker Çelik", "contact_id": 7})),
        Case::named(
            "number",
            json!({"name": "", "number_digits": "905321234567"}),
        ),
        Case::named("unknown", json!({"name": "+90 532"})),
    ]
}

#[derive(Serialize)]
struct StatsAns {
    total: usize,
    with_number: usize,
    with_email: usize,
    with_birthday: usize,
    starred: usize,
    accounts: Vec<AccountCount>,
    total_text: String,
    rows: Vec<StatRow>,
}

#[derive(Serialize)]
struct AccountCount {
    key: String,
    label: String,
    count: usize,
}

#[derive(Serialize)]
struct StatRow {
    label: String,
    value: String,
}

fn stats(req: DraftsReq) -> Result<StatsAns, FfiError> {
    let s = contact_stats::summarize(&req.drafts);
    Ok(StatsAns {
        total_text: contact_stats::total_text(s.total),
        rows: contact_stats::rows(&s)
            .into_iter()
            .map(|(label, value)| StatRow { label, value })
            .collect(),
        accounts: s
            .accounts
            .iter()
            .map(|(key, count)| AccountCount {
                key: key.clone(),
                label: contact_edit::account_label(key),
                count: *count,
            })
            .collect(),
        total: s.total,
        with_number: s.with_number,
        with_email: s.with_email,
        with_birthday: s.with_birthday,
        starred: s.starred,
    })
}

fn stats_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"drafts": drafts()})),
        Case::named("empty", json!({"drafts": []})),
    ]
}

#[derive(Deserialize)]
struct QuickReq {
    has_number: bool,
    on_whatsapp: bool,
    starred: bool,
}

#[derive(Serialize)]
struct QuickAns {
    actions: Vec<QuickDto>,
}

#[derive(Serialize)]
struct QuickDto {
    id: &'static str,
    label: &'static str,
}

fn quick(req: QuickReq) -> Result<QuickAns, FfiError> {
    Ok(QuickAns {
        actions: contact_quick::actions(req.has_number, req.on_whatsapp, req.starred)
            .into_iter()
            .map(|a| QuickDto {
                id: match a {
                    QuickAction::Call => "call",
                    QuickAction::Message => "message",
                    QuickAction::WhatsApp => "whatsapp",
                    QuickAction::CopyNumber => "copy_number",
                    QuickAction::Share => "share",
                    QuickAction::Star => "star",
                    QuickAction::Unstar => "unstar",
                    QuickAction::AddToHome => "add_to_home",
                },
                label: a.label(),
            })
            .collect(),
    })
}

fn quick_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"has_number": true, "on_whatsapp": true, "starred": false})),
        Case::named(
            "no_number",
            json!({"has_number": false, "on_whatsapp": false, "starred": true}),
        ),
    ]
}
