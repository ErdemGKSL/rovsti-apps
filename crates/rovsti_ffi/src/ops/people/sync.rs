//! `people.sync_*` (people sync v2, section 8): the planner of `rovsti_app_core::people_sync`, step by step.
//!
//! `people.sync_begin` opens a slice and answers a `handle`; `people.sync_next` says what to do; the caller performs
//! the gateway call and answers with `people.sync_report`, which commits the book (`people.json`) first and the
//! checkpoint (`people-sync.json`) second; `people.sync_stop` ends the slice and releases the handle (without a
//! handle it interrupts the stored run: unlink). `people.sync_status` reads what the settings row shows.
//!
//! The first `people.sync_*` call for a directory in a process turns a stored `Running` run into
//! `Interrupted(process_death)`: a run is only `Running` while the process that runs it lives.

use super::data_dir;
use crate::envelope::FfiError;
use crate::handles;
use crate::ops::registry::{Case, PLACEHOLDER_HANDLE as H};
use rovsti_app_core::people::{self, Book};
use rovsti_app_core::people_sync::{
    self as ps, Checkpoint, Committed, Interrupt, LinkId, Refused, Report, Session, Start, Status,
    Step,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, PoisonError};

const NOW: i64 = 1_790_000_000_000;

/// One open slice: its directory and the planner's in-memory session.
struct Slice {
    dir: PathBuf,
    session: Session,
}

type Shared = Arc<Mutex<Slice>>;

fn lock(slice: &Shared) -> MutexGuard<'_, Slice> {
    slice.lock().unwrap_or_else(PoisonError::into_inner)
}

/// The slice behind `handle`; the registry is not held while the caller works on it.
fn slice(handle: i64) -> Result<Shared, FfiError> {
    handles::global()
        .with::<Shared, _>(handle, |s| Arc::clone(s))
        .ok_or_else(|| FfiError::invalid("no such sync session (stopped or never begun)"))
}

/// The checkpoint, with the process-start rule applied once per directory.
fn load(dir: &Path) -> Result<Checkpoint, FfiError> {
    static SEEN: OnceLock<Mutex<HashSet<PathBuf>>> = OnceLock::new();
    let mut cp = ps::load(dir);
    let first = SEEN
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(PoisonError::into_inner)
        .insert(dir.to_path_buf());
    if first && ps::after_process_start(&mut cp) {
        ps::save(dir, &cp)?;
    }
    Ok(cp)
}

// ---- people.sync_status

#[derive(Deserialize)]
pub(super) struct StatusReq {
    #[serde(default)]
    dir: Option<String>,
    now_ms: i64,
}

pub(super) fn status(req: StatusReq) -> Result<Status, FfiError> {
    let cp = load(&data_dir(&req.dir)?)?;
    Ok(ps::status(&cp, req.now_ms))
}

pub(super) fn status_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "now_ms": NOW})).seeded("people_sync"),
        Case::named("never", json!({"dir": "$DIR", "now_ms": NOW})).seeded("people"),
    ]
}

// ---- people.sync_begin

#[derive(Deserialize)]
pub(super) struct BeginReq {
    #[serde(default)]
    dir: Option<String>,
    now_ms: i64,
    instance_id: String,
    gateway_url: String,
    /// E.164 numbers, recent callers first.
    #[serde(default)]
    numbers: Vec<String>,
    start: Start,
    /// The slice budget (the worker's); none in the foreground.
    #[serde(default)]
    slice_ms: Option<i64>,
}

#[derive(Serialize)]
pub(super) struct BeginAns {
    #[serde(skip_serializing_if = "Option::is_none")]
    handle: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    refused: Option<Refused>,
    status: Status,
}

pub(super) fn begin(req: BeginReq) -> Result<BeginAns, FfiError> {
    let dir = data_dir(&req.dir)?;
    let mut cp = load(&dir)?;
    let book = people::load(&dir);
    let link = LinkId::new(&req.instance_id, &req.gateway_url);
    let begun = ps::begin(
        &mut cp,
        &book,
        link,
        &req.numbers,
        req.start,
        req.slice_ms,
        req.now_ms,
    );
    ps::save(&dir, &cp)?;
    let status = ps::status(&cp, req.now_ms);
    Ok(match begun {
        Ok(session) => {
            let shared: Shared = Arc::new(Mutex::new(Slice { dir, session }));
            BeginAns {
                handle: Some(handles::global().insert(shared)),
                refused: None,
                status,
            }
        }
        Err(refused) => BeginAns {
            handle: None,
            refused: Some(refused),
            status,
        },
    })
}

fn begin_req(start: &str) -> serde_json::Value {
    json!({"dir": "$DIR", "now_ms": NOW, "instance_id": "inst-1", "gateway_url": "https://gw.example.test",
        "numbers": ["+905321234567", "+905550000001", "+905550000002"], "start": start})
}

pub(super) fn begin_cases() -> Vec<Case> {
    vec![
        Case::new(begin_req("restart")).seeded("people"),
        Case::named("resume", begin_req("resume")).seeded("people_sync"),
        Case::named("nothing_to_resume", begin_req("resume")).seeded("people"),
        Case::named(
            "not_before",
            json!({"dir": "$DIR", "now_ms": NOW - 60_000, "instance_id": "inst-1",
                "gateway_url": "https://gw.example.test", "numbers": [], "start": "auto"}),
        )
        .seeded("people_sync"),
    ]
}

// ---- people.sync_next

#[derive(Deserialize)]
pub(super) struct NextReq {
    handle: i64,
    now_ms: i64,
    /// Any number: the jitter of the next gap.
    #[serde(default)]
    rand: u32,
}

pub(super) fn next(req: NextReq) -> Result<Step, FfiError> {
    let shared = slice(req.handle)?;
    let mut slice = lock(&shared);
    let Slice { dir, session } = &mut *slice;
    let mut cp = ps::load(dir);
    let book = people::load(dir);
    let step = ps::next(&mut cp, session, &book, req.now_ms, req.rand);
    ps::save(dir, &cp)?;
    Ok(step)
}

pub(super) fn next_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H, "now_ms": NOW, "rand": 20}))
            .seeded("people")
            .after("people.sync_begin", begin_req("restart")),
    ]
}

// ---- people.sync_report

#[derive(Deserialize)]
pub(super) struct ReportReq {
    handle: i64,
    now_ms: i64,
    #[serde(default)]
    rand: u32,
    report: Report,
}

pub(super) fn report(req: ReportReq) -> Result<Committed, FfiError> {
    let shared = slice(req.handle)?;
    let mut slice = lock(&shared);
    let Slice { dir, session } = &mut *slice;
    let mut cp = ps::load(dir);
    let mut book: Book = people::load(dir);
    let committed = ps::report(
        &mut cp, session, &mut book, req.now_ms, req.rand, req.report,
    );
    // Book first, checkpoint second: a crash in between repeats one (idempotent) report.
    if committed.book_dirty {
        people::save(dir, &book)?;
    }
    ps::save(dir, &cp)?;
    Ok(committed)
}

pub(super) fn report_cases() -> Vec<Case> {
    let asked = ["905550000001@s.whatsapp.net", "905550000002@s.whatsapp.net"];
    vec![
        Case::new(json!({"handle": H, "now_ms": NOW + 1_000, "rand": 20, "report": {
            "kind": "lookup", "asked": asked,
            "found": [{"jid": asked[0], "verified_name": "Shop"}],
        }}))
        .seeded("people")
        .after("people.sync_begin", begin_req("restart")),
        Case::named(
            "rate_limited",
            json!({"handle": H, "now_ms": NOW + 1_000, "rand": 0, "report": {
                "kind": "rate_limited", "retry_after_ms": 30_000, "source": "whatsapp",
            }}),
        )
        .seeded("people")
        .after("people.sync_begin", begin_req("restart")),
        Case::named(
            "files",
            json!({"handle": H, "now_ms": NOW + 1_000, "rand": 0, "report": {
                "kind": "files", "max_bytes": 1_500, "files": [
                    {"path": "/cache/people/905321234567_s.whatsapp.net~54.jpg", "bytes": 100, "modified_ms": 1_000},
                ],
            }}),
        )
        .seeded("people")
        .after("people.sync_begin", begin_req("restart")),
    ]
}

// ---- people.sync_stop

#[derive(Deserialize)]
pub(super) struct StopReq {
    #[serde(default)]
    dir: Option<String>,
    /// The slice to end; none: interrupt the stored run (unlink).
    #[serde(default)]
    handle: Option<i64>,
    now_ms: i64,
    #[serde(default = "cancelled")]
    reason: Interrupt,
}

fn cancelled() -> Interrupt {
    Interrupt::Cancelled
}

pub(super) fn stop(req: StopReq) -> Result<Status, FfiError> {
    match req.handle {
        Some(handle) => {
            let shared = slice(handle)?;
            let status = {
                let mut slice = lock(&shared);
                let Slice { dir, session } = &mut *slice;
                let mut cp = ps::load(dir);
                ps::stop(&mut cp, Some(session), req.reason);
                ps::save(dir, &cp)?;
                ps::status(&cp, req.now_ms)
            };
            handles::global().release(handle);
            Ok(status)
        }
        None => {
            let dir = data_dir(&req.dir)?;
            let mut cp = load(&dir)?;
            ps::stop(&mut cp, None, req.reason);
            ps::save(&dir, &cp)?;
            Ok(ps::status(&cp, req.now_ms))
        }
    }
}

pub(super) fn stop_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H, "now_ms": NOW + 1_000, "reason": "cancelled"}))
            .seeded("people")
            .after("people.sync_begin", begin_req("restart")),
        Case::named(
            "unlink",
            json!({"dir": "$DIR", "now_ms": NOW, "reason": "wa_disconnected"}),
        )
        .seeded("people_sync"),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("ffi-people-sync-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    fn begin_in(dir: &Path, start: Start) -> BeginAns {
        begin(BeginReq {
            dir: Some(dir.to_string_lossy().into_owned()),
            now_ms: NOW,
            instance_id: "i".into(),
            gateway_url: "https://gw.example.test".into(),
            numbers: vec!["+905550000001".into()],
            start,
            slice_ms: None,
        })
        .unwrap()
    }

    #[test]
    fn a_slice_walks_and_a_stopped_handle_is_gone() {
        let dir = temp("walk");
        let handle = begin_in(&dir, Start::Restart).handle.unwrap();
        let step = next(NextReq {
            handle,
            now_ms: NOW,
            rand: 20,
        })
        .unwrap();
        assert!(matches!(step, Step::Lookup { .. }));
        let committed = report(ReportReq {
            handle,
            now_ms: NOW + 1,
            rand: 20,
            report: Report::Lookup {
                asked: vec!["905550000001@s.whatsapp.net".into()],
                found: vec![people::Update {
                    jid: "905550000001@s.whatsapp.net".into(),
                    ..Default::default()
                }],
            },
        })
        .unwrap();
        assert_eq!(committed.changed, 1);
        assert_eq!(people::load(&dir).people.len(), 1, "the book was saved");
        let status = stop(StopReq {
            dir: None,
            handle: Some(handle),
            now_ms: NOW + 2,
            reason: Interrupt::Cancelled,
        })
        .unwrap();
        assert!(status.run.unwrap().resumable);
        assert!(
            next(NextReq {
                handle,
                now_ms: NOW,
                rand: 0
            })
            .is_err()
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_first_call_in_a_process_interrupts_a_running_run() {
        let dir = temp("death");
        let mut cp = Checkpoint::default();
        let book = Book::default();
        let _ = ps::begin(
            &mut cp,
            &book,
            LinkId::new("i", "g"),
            &[],
            Start::Restart,
            None,
            NOW,
        )
        .unwrap();
        ps::save(&dir, &cp).unwrap();
        let st = status(StatusReq {
            dir: Some(dir.to_string_lossy().into_owned()),
            now_ms: NOW,
        })
        .unwrap();
        assert!(st.run.unwrap().resumable, "process death made it resumable");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
