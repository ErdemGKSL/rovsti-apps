//! `contacts.trash_*` and `contacts.prefs_*`: the Trash and the preferences, files in the data directory.

use super::{Confirm, Empty, NOW, ayse, cem, data_dir};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::contacts::{prefs, trash_store};
use rovsti_core::contact_edit::{Account, ContactDraft, Group};
use rovsti_core::contact_trash::{self, Trash};
use rovsti_core::contacts_prefs::ContactsPrefs;
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "contacts.trash_list",
        ListReq,
        ListAns,
        trash_list,
        list_cases
    ),
    op!(
        "contacts.trash_push",
        PushReq,
        PushAns,
        trash_push,
        push_cases
    ),
    op!(
        "contacts.trash_restore",
        RestoreReq,
        RestoreAns,
        trash_restore,
        restore_cases
    ),
    op!(
        "contacts.trash_delete",
        DeleteReq,
        DeleteAns,
        trash_delete,
        delete_cases
    ),
    op!(
        "contacts.trash_confirm",
        ConfirmReq,
        Confirm,
        trash_confirm,
        confirm_cases
    ),
    op!(
        "contacts.prefs_load",
        DirReq,
        PrefsAns,
        prefs_load,
        prefs_load_cases
    ),
    op!(
        "contacts.prefs_save",
        PrefsSaveReq,
        Empty,
        prefs_save,
        prefs_save_cases
    ),
];

fn io(error: std::io::Error) -> FfiError {
    FfiError::from(error)
}

#[derive(Deserialize)]
struct DirReq {
    #[serde(default)]
    dir: Option<String>,
}

// ---- trash ----

#[derive(Deserialize)]
struct ListReq {
    #[serde(default)]
    dir: Option<String>,
    now_ms: i64,
}

#[derive(Serialize)]
struct ListAns {
    /// Newest first. Entries past the retention period were dropped (and the file rewritten).
    entries: Vec<EntryDto>,
    retention_days: u32,
    max_entries: usize,
    /// The dialog for "Empty Trash".
    empty_confirm: Confirm,
}

#[derive(Serialize)]
struct EntryDto {
    key: u64,
    deleted_ms: i64,
    draft: ContactDraft,
    /// "Deleted today", "12 days left".
    left_text: String,
    days_left: u32,
}

fn trash_list(req: ListReq) -> Result<ListAns, FfiError> {
    let trash = trash_store::trash_load_purged(&data_dir(&req.dir)?, req.now_ms);
    Ok(ListAns {
        empty_confirm: Confirm::of(contact_trash::empty_confirm(trash.entries.len())),
        entries: trash
            .sorted()
            .into_iter()
            .map(|e| EntryDto {
                key: e.key,
                deleted_ms: e.deleted_ms,
                draft: e.draft.clone(),
                left_text: contact_trash::left_text(e.deleted_ms, req.now_ms),
                days_left: contact_trash::days_left(e.deleted_ms, req.now_ms),
            })
            .collect(),
        retention_days: contact_trash::RETENTION_DAYS,
        max_entries: contact_trash::MAX_ENTRIES,
    })
}

fn list_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "now_ms": NOW})).seeded("contacts"),
        Case::named("empty", json!({"dir": "$DIR/nothing-here", "now_ms": NOW})).seeded("contacts"),
    ]
}

#[derive(Deserialize)]
struct PushReq {
    #[serde(default)]
    dir: Option<String>,
    drafts: Vec<ContactDraft>,
    now_ms: i64,
}

#[derive(Serialize)]
struct PushAns {
    keys: Vec<u64>,
    count: usize,
    /// "Moved to Trash" / "Moved 3 contacts to Trash".
    notice: String,
}

fn trash_push(req: PushReq) -> Result<PushAns, FfiError> {
    let dir = data_dir(&req.dir)?;
    let mut trash = trash_store::trash_load_purged(&dir, req.now_ms);
    let n = req.drafts.len();
    let keys = trash.push(req.drafts, req.now_ms);
    trash_store::trash_save(&dir, &trash).map_err(io)?;
    Ok(PushAns {
        keys,
        count: trash.entries.len(),
        notice: contact_trash::moved_notice(n),
    })
}

fn push_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "drafts": [ayse(), cem()], "now_ms": NOW}))
            .seeded("contacts"),
    ]
}

#[derive(Deserialize)]
struct RestoreReq {
    #[serde(default)]
    dir: Option<String>,
    keys: Vec<u64>,
    groups: Vec<Group>,
    accounts: Vec<Account>,
    /// Where a contact whose account is gone goes.
    fallback_account: String,
    now_ms: i64,
}

#[derive(Serialize)]
struct RestoreAns {
    /// New drafts (no id) ready to insert; the entries leave the Trash.
    drafts: Vec<ContactDraft>,
    notice: String,
}

fn trash_restore(req: RestoreReq) -> Result<RestoreAns, FfiError> {
    let dir = data_dir(&req.dir)?;
    let mut trash = trash_store::trash_load_purged(&dir, req.now_ms);
    let taken = trash.take(&req.keys);
    trash_store::trash_save(&dir, &trash).map_err(io)?;
    let n = taken.len();
    Ok(RestoreAns {
        drafts: contact_trash::restore_drafts(
            taken,
            &req.groups,
            &req.accounts,
            &req.fallback_account,
        ),
        notice: contact_trash::restored_notice(n),
    })
}

fn restore_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"dir": "$DIR", "keys": [1], "groups": [], "accounts": [], "fallback_account": "",
                          "now_ms": NOW}),
        )
        .seeded("contacts"),
    ]
}

#[derive(Deserialize)]
struct DeleteReq {
    #[serde(default)]
    dir: Option<String>,
    /// The entries to delete for good; ignored with `all`.
    #[serde(default)]
    keys: Vec<u64>,
    #[serde(default)]
    all: bool,
}

#[derive(Serialize)]
struct DeleteAns {
    removed: usize,
}

fn trash_delete(req: DeleteReq) -> Result<DeleteAns, FfiError> {
    let dir = data_dir(&req.dir)?;
    let mut trash: Trash = trash_store::trash_load(&dir);
    let removed = if req.all {
        trash.clear()
    } else {
        trash.remove(&req.keys)
    };
    trash_store::trash_save(&dir, &trash).map_err(io)?;
    Ok(DeleteAns { removed })
}

fn delete_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "keys": [1]})).seeded("contacts"),
        Case::named("all", json!({"dir": "$DIR", "all": true})).seeded("contacts"),
    ]
}

#[derive(Deserialize)]
struct ConfirmReq {
    /// `forever` (selected entries) or `empty` (the whole Trash).
    kind: String,
    count: usize,
}

fn trash_confirm(req: ConfirmReq) -> Result<Confirm, FfiError> {
    match req.kind.as_str() {
        "forever" => Ok(Confirm::of(contact_trash::forever_confirm(req.count))),
        "empty" => Ok(Confirm::of(contact_trash::empty_confirm(req.count))),
        other => Err(FfiError::invalid(format!("unknown confirmation {other:?}"))),
    }
}

fn confirm_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"kind": "forever", "count": 2})),
        Case::named("empty", json!({"kind": "empty", "count": 1})),
        Case::named("bad_kind", json!({"kind": "maybe", "count": 1})),
    ]
}

// ---- prefs ----

#[derive(Serialize)]
struct PrefsAns {
    prefs: ContactsPrefs,
    /// The favorites order without blanks or repeats, as the list uses it.
    favorites_order: Vec<String>,
    emergency: Vec<String>,
    /// The reminder hour clamped to 0..=23.
    reminder_hour: u8,
}

fn prefs_load(req: DirReq) -> Result<PrefsAns, FfiError> {
    let prefs = prefs::load(&data_dir(&req.dir)?);
    Ok(PrefsAns {
        favorites_order: prefs.order(),
        emergency: prefs.emergency_keys(),
        reminder_hour: prefs.hour(),
        prefs,
    })
}

fn prefs_load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("contacts"),
        Case::named("missing", json!({"dir": "$DIR/nothing-here"})).seeded("contacts"),
    ]
}

#[derive(Deserialize)]
struct PrefsSaveReq {
    #[serde(default)]
    dir: Option<String>,
    prefs: ContactsPrefs,
}

fn prefs_save(req: PrefsSaveReq) -> Result<Empty, FfiError> {
    prefs::save(&data_dir(&req.dir)?, req.prefs).map_err(io)?;
    Ok(Empty {})
}

fn prefs_save_cases() -> Vec<Case> {
    let prefs = ContactsPrefs::default();
    vec![Case::new(json!({"dir": "$DIR", "prefs": prefs})).seeded("contacts")]
}
