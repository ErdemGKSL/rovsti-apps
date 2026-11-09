//! `phone.backup_export`, `phone.backup_import` and `phone.display_name`: the Phone backup file and the one name
//! helper the lists call (the last items of the E2.14 inventory sweep).
//!
//! The backup ops are pure: Kotlin reads and writes the picked file (the Storage Access Framework), Rust builds and
//! validates the text. An import answers the preview of what it would change together with the preferences after
//! it, so the confirmation sheet and the undo both come from one answer.

use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::storage::{self, BackupRefusal};
use rovsti_core::names;
use rovsti_core::settings::{self, PrefsV2};
use rovsti_core::wire::{NameFormat, WireContact};
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "phone.backup_export",
        ExportReq,
        ExportAns,
        export,
        export_cases
    ),
    op!(
        "phone.backup_import",
        ImportReq,
        ImportAns,
        import,
        import_cases
    ),
    op!(
        "phone.display_name",
        NameReq,
        NameAns,
        display_name,
        name_cases
    ),
];

#[derive(Deserialize)]
struct ExportReq {
    prefs: PrefsV2,
    created_ms: i64,
    app_version: String,
}

#[derive(Serialize)]
struct ExportAns {
    /// `rovsti-phone-backup-yyyy-mm-dd.json`, for the UTC day of `created_ms`.
    file_name: String,
    /// The file's text (pretty JSON).
    text: String,
}

fn export(req: ExportReq) -> Result<ExportAns, FfiError> {
    let mut prefs = req.prefs;
    prefs.validate();
    Ok(ExportAns {
        file_name: settings::backup_file_name(req.created_ms),
        text: storage::backup_json(&prefs, req.created_ms, &req.app_version),
    })
}

fn export_cases() -> Vec<Case> {
    let prefs = PrefsV2 {
        quick_replies: vec!["Later".into(), "Can't talk".into()],
        ..PrefsV2::default()
    };
    vec![Case::new(
        json!({"prefs": prefs, "created_ms": super::NOW, "app_version": "0.1.0"}),
    )]
}

#[derive(Deserialize)]
struct ImportReq {
    /// The picked file's text.
    text: String,
    /// The preferences now; the import replaces each section of the file.
    current: PrefsV2,
}

#[derive(Serialize)]
struct Moved {
    to: u8,
    from: u8,
}

#[derive(Serialize)]
struct Preview {
    settings_changed: usize,
    quick_replies: usize,
    speed_dial: usize,
    favorites: usize,
    /// A contact on two digits keeps the later digit.
    moved: Vec<Moved>,
    /// Some value was out of bounds and clamped.
    clamped: bool,
}

#[derive(Serialize)]
struct ImportAns {
    preview: Preview,
    /// The preferences once the import is confirmed (save them with `phone.prefs_save`).
    prefs: PrefsV2,
}

fn import(req: ImportReq) -> Result<ImportAns, FfiError> {
    let preview = storage::parse_backup(&req.text, &req.current).map_err(|refusal| {
        let reason = match refusal {
            BackupRefusal::NotABackup => "not_a_backup",
            BackupRefusal::Newer => "newer",
        };
        FfiError::new(ErrorKind::Invalid, refusal.message())
            .with_detail(json!({ "reason": reason }))
    })?;
    let shown = Preview {
        settings_changed: preview.settings_changed,
        quick_replies: preview.quick_replies,
        speed_dial: preview.speed_dial,
        favorites: preview.favorites,
        moved: preview
            .moved
            .iter()
            .map(|m| Moved {
                to: m.to,
                from: m.from,
            })
            .collect(),
        clamped: preview.clamped,
    };
    let mut prefs = req.current;
    preview.apply_to(&mut prefs);
    Ok(ImportAns {
        preview: shown,
        prefs,
    })
}

fn import_cases() -> Vec<Case> {
    let mine = PrefsV2 {
        quick_replies: vec!["Later".into(), "Can't talk".into()],
        ..PrefsV2::default()
    };
    let text = storage::backup_json(&mine, super::NOW, "0.1.0");
    vec![
        Case::new(json!({"text": text, "current": PrefsV2::default()})),
        Case::named(
            "not_a_backup",
            json!({"text": "{\"v\": 1, \"kind\": \"other-file\"}", "current": PrefsV2::default()}),
        ),
        Case::named(
            "newer",
            json!({"text": text.replace("\"v\": 1", "\"v\": 9"), "current": PrefsV2::default()}),
        ),
        Case::named("empty", json!({"text": "", "current": PrefsV2::default()})),
    ]
}

#[derive(Deserialize)]
struct NameReq {
    contact: WireContact,
    format: NameFormat,
}

#[derive(Serialize)]
struct NameAns {
    /// The name to show: the chosen form, the other one, else the first number; empty for nothing at all.
    name: String,
}

fn display_name(req: NameReq) -> Result<NameAns, FfiError> {
    Ok(NameAns {
        name: names::display(&req.contact, req.format).into_owned(),
    })
}

fn name_cases() -> Vec<Case> {
    let contact = json!({"id": 1, "display_name": "Ayse Yilmaz", "display_name_alt": "Yilmaz, Ayse",
                         "numbers": [{"raw": "+905321234567", "label": "mobile"}]});
    let nameless = json!({"id": 2, "display_name": "", "display_name_alt": "",
                          "numbers": [{"raw": "+905321234567", "label": "mobile"}]});
    vec![
        Case::new(json!({"contact": contact, "format": "last_first"})),
        Case::named(
            "first_last",
            json!({"contact": contact, "format": "first_last"}),
        ),
        Case::named(
            "nameless",
            json!({"contact": nameless, "format": "first_last"}),
        ),
    ]
}
