//! `contacts.*`: the Contacts app's domain logic over `rovsti_core::contact_*`, `vcard`, `import_plan`,
//! `group_tools`, `contacts_view`, `contact_select`, `birthday_reminder` and `rovsti_app_core::contacts`.
//!
//! Every op is stateless except `contacts.deep_index_new` / `contacts.deep_find` (a handle over the detail-search
//! index, like the contact index of `search.*`). The trash and the preferences are files in the data directory: a
//! request may name `dir` (tests, fixtures), else `core.init`'s data dir is used; saves are atomic
//! (`rovsti_app_core::contacts`). Per-keystroke work (the editor's fields, the sheet state of a screen) stays in
//! Kotlin (OPS.md); everything here is per event.

mod draft;
mod files;
mod lists;
mod store;
mod wa;

use super::registry::OpSpec;
use crate::envelope::FfiError;
use crate::state;
use rovsti_core::contact_edit::ContactDraft;
use rovsti_core::contacts_view::NumberVm;
use serde::Serialize;
use serde_json::{Value, json};
use std::path::PathBuf;

/// The op tables of the submodules, in the order the registry lists them.
pub(super) const TABLES: &[&[OpSpec]] = &[draft::OPS, files::OPS, lists::OPS, store::OPS, wa::OPS];

/// "now" of every fixture: 2026-09-21T17:33:20Z.
pub(super) const NOW: i64 = 1_790_000_000_000;
/// Istanbul in summer.
pub(super) const TZ: i32 = 10_800;

#[derive(Serialize)]
pub(super) struct Empty {}

/// `{ "text": ... }`: the sentence a toast or a row shows.
#[derive(Serialize)]
pub(super) struct Notice {
    pub text: String,
}

/// `{ "title", "body" }`: a confirmation dialog.
#[derive(Serialize)]
pub(super) struct Confirm {
    pub title: String,
    pub body: String,
}

impl Confirm {
    pub fn of((title, body): (String, String)) -> Self {
        Self { title, body }
    }

    pub fn of_static((title, body): (&str, &str)) -> Self {
        Self {
            title: title.to_owned(),
            body: body.to_owned(),
        }
    }
}

/// `{ "title", "detail" }`: a two-line row.
#[derive(Serialize)]
pub(super) struct Row2 {
    pub title: String,
    pub detail: String,
}

/// One number of a contact, ready to draw and to dial.
#[derive(Serialize)]
pub(super) struct NumberDto {
    pub raw: String,
    pub display: String,
    pub label: String,
    pub default: bool,
}

impl From<&NumberVm> for NumberDto {
    fn from(n: &NumberVm) -> Self {
        Self {
            raw: n.raw.clone(),
            display: n.display.clone(),
            label: n.label.clone(),
            default: n.default,
        }
    }
}

pub(super) fn data_dir(dir: &Option<String>) -> Result<PathBuf, FfiError> {
    match dir {
        Some(dir) => Ok(dir.into()),
        None => state::dirs()
            .map(|d| d.data_dir.clone())
            .ok_or_else(|| FfiError::invalid("init was not called")),
    }
}

/// A draft with every field in the form the wire carries (empty optional lists left out), from a partial literal.
pub(super) fn d(partial: Value) -> Value {
    let draft: ContactDraft = serde_json::from_value(partial).expect("fixture draft");
    serde_json::to_value(draft).expect("draft json")
}

/// Ayse: two numbers, an email, a birthday in twelve days, one group.
pub(super) fn ayse() -> Value {
    d(
        json!({"id": 1, "given": "Ayşe", "family": "Yılmaz", "org": "Acme", "title": "Engineer",
        "numbers": [{"value": "0532 123 45 67", "kind": "mobile", "primary": true},
                    {"value": "+90 212 555 01 02", "kind": "work"}],
        "emails": [{"value": "ayse@example.org", "kind": "home"}],
        "events": [{"date": "1990-10-03", "kind": "birthday"}],
        "groups": [10], "starred": true, "account": "com.google|ayse@example.org"}),
    )
}

/// Mehmet: one number, an anniversary in four days.
pub(super) fn mehmet() -> Value {
    d(
        json!({"id": 2, "given": "Mehmet", "family": "Demir", "org": "",
        "numbers": [{"value": "+90 212 555 01 02", "kind": "work"}],
        "events": [{"date": "2015-09-25", "kind": "anniversary"}],
        "groups": [10, 11], "account": "com.google|ayse@example.org"}),
    )
}

/// Cem: a name and nothing else.
pub(super) fn cem() -> Value {
    d(json!({"id": 3, "given": "Cem", "family": "Aksoy"}))
}

/// Ayse again, as a second entry with the same number.
pub(super) fn ayse_twin() -> Value {
    d(json!({"id": 4, "given": "Ayse", "family": "Yilmaz",
        "numbers": [{"value": "05321234567", "kind": "mobile"}]}))
}

pub(super) fn drafts() -> Value {
    json!([ayse(), mehmet(), cem(), ayse_twin()])
}

/// The same people as the provider lists them.
pub(super) fn wires() -> Value {
    json!([
        {"id": 1, "display_name": "Ayşe Yılmaz", "display_name_alt": "Yılmaz, Ayşe", "lookup_key": "k-ayse",
         "numbers": [{"raw": "0532 123 45 67", "label": "mobile", "default": true}, {"raw": "+90 212 555 01 02", "label": "work"}],
         "starred": true, "email": "ayse@example.org", "updated_ms": NOW - 3_600_000},
        {"id": 2, "display_name": "Mehmet Demir", "display_name_alt": "Demir, Mehmet", "lookup_key": "k-mehmet",
         "numbers": [{"raw": "+90 216 555 03 04", "label": "work"}], "updated_ms": NOW - 3 * 86_400_000},
        {"id": 3, "display_name": "Cem Aksoy", "display_name_alt": "Aksoy, Cem", "lookup_key": "k-cem",
         "numbers": [], "updated_ms": NOW - 40 * 86_400_000},
        {"id": 4, "display_name": "Zeynep Kaya", "display_name_alt": "Kaya, Zeynep", "lookup_key": "k-zeynep",
         "numbers": [{"raw": "0555 000 11 22"}], "starred": true, "email": "zeynep@example.org"}
    ])
}

pub(super) fn locale() -> Value {
    json!({"tz_offset_s": TZ, "format": {"hour12": false, "date_order": "day_month_year"}})
}
