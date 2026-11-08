//! `phone.*`: the Phone app's files (preferences, call notes) and the WhatsApp call tracker handle.
//!
//! The file ops read `dir` from the request when given (tests, fixtures) and from `core.init`'s data dir
//! otherwise. Saving is atomic (`rovsti_app_core::storage`).

use super::NOW;
use crate::envelope::{ErrorKind, FfiError};
use crate::handles;
use crate::op;
use crate::ops::registry::{Case, OpSpec, PLACEHOLDER_HANDLE as H};
use crate::state;
use rovsti_app_core::phone::{notes as note_store, wa_call};
use rovsti_app_core::storage::{self, Origin};
use rovsti_core::call_notes::Notes;
use rovsti_core::settings::PrefsV2;
use rovsti_wa_proto::{CallUpdate, WaCallView};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::path::PathBuf;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "phone.prefs_load",
        DirReq,
        PrefsLoadAns,
        prefs_load,
        prefs_load_cases
    ),
    op!(
        "phone.prefs_save",
        PrefsSaveReq,
        Empty,
        prefs_save,
        prefs_save_cases
    ),
    op!(
        "phone.notes_load",
        DirReq,
        NotesLoadAns,
        notes_load,
        notes_load_cases
    ),
    op!(
        "phone.notes_save",
        NotesSaveReq,
        Empty,
        notes_save,
        notes_save_cases
    ),
    op!(
        "phone.reply_choices",
        RepliesReq,
        RepliesAns,
        reply_choices,
        replies_cases
    ),
    op!(
        "phone.keypad_tones",
        TonesReq,
        TonesAns,
        keypad_tones,
        tones_cases
    ),
    op!(
        "phone.wa_tracker_new",
        Value,
        HandleAns,
        tracker_new,
        tracker_new_cases
    ),
    op!(
        "phone.wa_tracker_update",
        UpdateReq,
        UpdateAns,
        tracker_update,
        update_cases
    ),
    op!(
        "phone.wa_tracker_placed",
        PlacedReq,
        UpdateAns,
        tracker_placed,
        placed_cases
    ),
    op!(
        "phone.wa_tracker_view",
        ViewReq,
        ViewAns,
        tracker_view,
        view_cases
    ),
    op!(
        "phone.wa_tracker_mute",
        MuteReq,
        Empty,
        tracker_mute,
        mute_cases
    ),
    op!(
        "phone.wa_tracker_clear",
        HandleReq,
        Empty,
        tracker_clear,
        clear_cases
    ),
    op!(
        "phone.wa_tracker_can_place",
        CanPlaceReq,
        Empty,
        can_place,
        can_place_cases
    ),
    op!(
        "phone.wa_tracker_format_elapsed",
        ElapsedReq,
        ElapsedAns,
        format_elapsed,
        elapsed_cases
    ),
];

#[derive(Serialize)]
struct Empty {}

#[derive(Deserialize)]
struct DirReq {
    #[serde(default)]
    dir: Option<String>,
}

fn data_dir(dir: &Option<String>) -> Result<PathBuf, FfiError> {
    match dir {
        Some(dir) => Ok(dir.into()),
        None => state::dirs()
            .map(|d| d.data_dir.clone())
            .ok_or_else(|| FfiError::invalid("init was not called")),
    }
}

fn io(error: std::io::Error) -> FfiError {
    FfiError::from(error)
}

// ---- prefs ----

#[derive(Serialize)]
struct PrefsLoadAns {
    prefs: PrefsV2,
    /// `missing`, `current`, `upgraded_from_v1`, `newer` or `corrupt`.
    origin: &'static str,
    /// The schema version of a `newer` file.
    newer_version: Option<u32>,
}

fn prefs_load(req: DirReq) -> Result<PrefsLoadAns, FfiError> {
    let loaded = storage::load_prefs(&data_dir(&req.dir)?);
    let (origin, newer_version) = match loaded.origin {
        Origin::Missing => ("missing", None),
        Origin::Current => ("current", None),
        Origin::UpgradedFromV1 => ("upgraded_from_v1", None),
        Origin::Newer(v) => ("newer", Some(v)),
        Origin::Corrupt => ("corrupt", None),
    };
    Ok(PrefsLoadAns {
        prefs: loaded.prefs,
        origin,
        newer_version,
    })
}

fn prefs_load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("phone"),
        Case::named("missing", json!({"dir": "$DIR/nothing-here"})).seeded("phone"),
    ]
}

#[derive(Deserialize)]
struct PrefsSaveReq {
    #[serde(default)]
    dir: Option<String>,
    prefs: PrefsV2,
}

fn prefs_save(mut req: PrefsSaveReq) -> Result<Empty, FfiError> {
    req.prefs.validate();
    storage::save_prefs(&req.prefs, &data_dir(&req.dir)?).map_err(io)?;
    Ok(Empty {})
}

fn prefs_save_cases() -> Vec<Case> {
    let prefs = PrefsV2 {
        quick_replies: vec!["Later".into()],
        ..PrefsV2::default()
    };
    vec![Case::new(json!({"dir": "$DIR", "prefs": prefs})).seeded("phone")]
}

// ---- notes ----

#[derive(Serialize)]
struct NotesLoadAns {
    /// Empty when the file does not exist yet.
    notes: Notes,
}

fn notes_load(req: DirReq) -> Result<NotesLoadAns, FfiError> {
    Ok(NotesLoadAns {
        notes: note_store::load(&data_dir(&req.dir)?).unwrap_or_default(),
    })
}

fn notes_load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("phone"),
        Case::named("missing", json!({"dir": "$DIR/nothing-here"})).seeded("phone"),
    ]
}

#[derive(Deserialize)]
struct NotesSaveReq {
    #[serde(default)]
    dir: Option<String>,
    notes: Notes,
}

fn notes_save(req: NotesSaveReq) -> Result<Empty, FfiError> {
    note_store::save(&data_dir(&req.dir)?, &req.notes).map_err(io)?;
    Ok(Empty {})
}

fn notes_save_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "notes": {"schema": 1, "notes": [
        {"number": "+905321234567", "at_ms": NOW, "text": "Geri ara", "edited_ms": NOW}]}}))
        .seeded("phone"),
    ]
}

// ---- WhatsApp call tracker ----

fn with_tracker<R>(handle: i64, f: impl FnOnce(&mut wa_call::Tracker) -> R) -> Result<R, FfiError> {
    handles::global().with(handle, f).ok_or_else(|| {
        FfiError::new(
            ErrorKind::NotFound,
            format!("WhatsApp call tracker handle {handle} is not open"),
        )
    })
}

#[derive(Serialize)]
struct HandleAns {
    handle: i64,
}

fn tracker_new(_: Value) -> Result<HandleAns, FfiError> {
    Ok(HandleAns {
        handle: handles::global().insert(wa_call::Tracker::default()),
    })
}

fn tracker_new_cases() -> Vec<Case> {
    vec![Case::new(Value::Null)]
}

fn new_tracker() -> (&'static str, Value) {
    ("phone.wa_tracker_new", Value::Null)
}

#[derive(Deserialize)]
struct HandleReq {
    handle: i64,
}

#[derive(Serialize)]
struct UpdateAns {
    /// `ignored`, `shown` or `ended`.
    change: &'static str,
    view: Option<WaCallView>,
    status_line: Option<String>,
}

fn change_name(change: wa_call::Change) -> &'static str {
    match change {
        wa_call::Change::Ignored => "ignored",
        wa_call::Change::Shown => "shown",
        wa_call::Change::Ended => "ended",
    }
}

fn snapshot(t: &wa_call::Tracker, change: wa_call::Change, now_ms: i64) -> UpdateAns {
    let view = t.view(now_ms);
    UpdateAns {
        change: change_name(change),
        status_line: view.as_ref().map(wa_call::status_line),
        view,
    }
}

#[derive(Deserialize)]
struct UpdateReq {
    handle: i64,
    update: CallUpdate,
    now_ms: i64,
}

fn tracker_update(req: UpdateReq) -> Result<UpdateAns, FfiError> {
    with_tracker(req.handle, |t| {
        let change = t.on_update(req.update, req.now_ms);
        snapshot(t, change, req.now_ms)
    })
}

fn update_json(phase: Value) -> Value {
    json!({"call_id": "wa-1", "peer": "905321234567@s.whatsapp.net", "peer_name": "Ayşe Yılmaz",
        "direction": "in", "phase": phase, "is_video": false})
}

fn update_cases() -> Vec<Case> {
    let (op, req) = new_tracker();
    vec![
        Case::new(json!({"handle": H, "update": update_json(json!("ringing")), "now_ms": NOW}))
            .after(op, req.clone()),
        Case::named(
            "ended_unknown_call",
            json!({"handle": H, "update": update_json(json!({"ended": "missed"})), "now_ms": NOW}),
        )
        .after(op, req),
    ]
}

#[derive(Deserialize)]
struct PlacedReq {
    handle: i64,
    call_id: String,
    peer: String,
    now_ms: i64,
}

fn tracker_placed(req: PlacedReq) -> Result<UpdateAns, FfiError> {
    with_tracker(req.handle, |t| {
        let change = t.on_placed(&req.call_id, &req.peer, req.now_ms);
        snapshot(t, change, req.now_ms)
    })
}

fn placed_cases() -> Vec<Case> {
    let (op, req) = new_tracker();
    vec![Case::new(json!({"handle": H, "call_id": "wa-2", "peer": "905321234567@s.whatsapp.net", "now_ms": NOW}))
        .after(op, req)]
}

#[derive(Deserialize)]
struct ViewReq {
    handle: i64,
    now_ms: i64,
}

#[derive(Serialize)]
struct ViewAns {
    view: Option<WaCallView>,
    status_line: Option<String>,
    started_ms: Option<i64>,
    empty: bool,
}

fn tracker_view(req: ViewReq) -> Result<ViewAns, FfiError> {
    with_tracker(req.handle, |t| {
        let view = t.view(req.now_ms);
        ViewAns {
            status_line: view.as_ref().map(wa_call::status_line),
            started_ms: t.started_ms(),
            empty: t.is_empty(),
            view,
        }
    })
}

fn view_cases() -> Vec<Case> {
    let (op, req) = new_tracker();
    vec![
        Case::new(json!({"handle": H, "now_ms": NOW})).after(op, req),
        Case::named("stale_handle", json!({"handle": H, "now_ms": NOW})),
    ]
}

#[derive(Deserialize)]
struct MuteReq {
    handle: i64,
    muted: bool,
}

fn tracker_mute(req: MuteReq) -> Result<Empty, FfiError> {
    with_tracker(req.handle, |t| t.set_muted(req.muted))?;
    Ok(Empty {})
}

fn mute_cases() -> Vec<Case> {
    let (op, req) = new_tracker();
    vec![Case::new(json!({"handle": H, "muted": true})).after(op, req)]
}

fn tracker_clear(req: HandleReq) -> Result<Empty, FfiError> {
    with_tracker(req.handle, |t| t.clear())?;
    Ok(Empty {})
}

fn clear_cases() -> Vec<Case> {
    let (op, req) = new_tracker();
    vec![Case::new(json!({"handle": H})).after(op, req)]
}

#[derive(Deserialize)]
struct CanPlaceReq {
    connected: bool,
    busy: bool,
    number: String,
}

fn can_place(req: CanPlaceReq) -> Result<Empty, FfiError> {
    wa_call::can_place(req.connected, req.busy, &req.number)
        .map(|()| Empty {})
        .map_err(FfiError::invalid)
}

fn can_place_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"connected": true, "busy": false, "number": "+905321234567"})),
        Case::named(
            "not_connected",
            json!({"connected": false, "busy": false, "number": "+905321234567"}),
        ),
        Case::named(
            "busy",
            json!({"connected": true, "busy": true, "number": "+905321234567"}),
        ),
        Case::named(
            "no_number",
            json!({"connected": true, "busy": false, "number": " "}),
        ),
    ]
}

#[derive(Deserialize)]
struct ElapsedReq {
    seconds: u64,
}

#[derive(Serialize)]
struct ElapsedAns {
    text: String,
}

fn format_elapsed(req: ElapsedReq) -> Result<ElapsedAns, FfiError> {
    Ok(ElapsedAns {
        text: wa_call::format_elapsed(req.seconds),
    })
}

fn elapsed_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"seconds": 65})),
        Case::named("hour", json!({"seconds": 3725})),
    ]
}

// ---- small preference rules ----

#[derive(Deserialize)]
struct RepliesReq {
    #[serde(default)]
    replies: Vec<String>,
}

#[derive(Serialize)]
struct RepliesAns {
    /// The decline-with-message choices: the saved ones, else the defaults.
    choices: Vec<String>,
    max: usize,
    max_chars: usize,
    /// The character counter shows from this many characters.
    counter_at: usize,
    can_add: bool,
}

fn reply_choices(req: RepliesReq) -> Result<RepliesAns, FfiError> {
    use rovsti_core::settings as s;
    Ok(RepliesAns {
        choices: s::reply_choices(&req.replies),
        max: s::MAX_REPLIES,
        max_chars: s::MAX_REPLY_CHARS,
        counter_at: s::REPLY_COUNTER_AT,
        can_add: s::can_add_reply(req.replies.len()),
    })
}

fn replies_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"replies": ["Later", "In a meeting"]})),
        Case::named("defaults", json!({})),
    ]
}

#[derive(Deserialize)]
struct TonesReq {
    /// The setting: absent follows the system.
    #[serde(default)]
    setting: Option<bool>,
    system: bool,
}

#[derive(Serialize)]
struct TonesAns {
    on: bool,
}

fn keypad_tones(req: TonesReq) -> Result<TonesAns, FfiError> {
    Ok(TonesAns {
        on: rovsti_core::settings::effective_keypad_tones(req.setting, req.system),
    })
}

fn tones_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"setting": null, "system": true})),
        Case::named("forced_off", json!({"setting": false, "system": true})),
    ]
}
