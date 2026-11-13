//! `people.*` (X.1): the people cache of `rovsti_app_core::people`, a file (`people.json`) in the data directory.
//!
//! A request may name `dir` (tests, fixtures), else `core.init`'s data dir is used. `people.upsert` writes; the
//! others only read, so they work offline and never fail on a missing or damaged file.

use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use crate::state;
use rovsti_app_core::people::{self, FileInfo, Plan, Row, Update, Upserted};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::PathBuf;

pub(super) const OPS: &[OpSpec] = &[
    op!("people.upsert", UpsertReq, Upserted, upsert, upsert_cases),
    op!("people.lookup", LookupReq, LookupAns, lookup, lookup_cases),
    op!("people.plan", PlanReq, Plan, plan, plan_cases),
    op!(
        "people.snapshot",
        SnapshotReq,
        SnapshotAns,
        snapshot,
        snapshot_cases
    ),
];

const NOW: i64 = 1_790_000_000_000;

fn data_dir(dir: &Option<String>) -> Result<PathBuf, FfiError> {
    match dir {
        Some(dir) => Ok(dir.into()),
        None => state::dirs()
            .map(|d| d.data_dir.clone())
            .ok_or_else(|| FfiError::invalid("init was not called")),
    }
}

// ---- people.upsert

#[derive(Deserialize)]
struct UpsertReq {
    #[serde(default)]
    dir: Option<String>,
    now_ms: i64,
    people: Vec<Update>,
}

fn upsert(req: UpsertReq) -> Result<Upserted, FfiError> {
    let dir = data_dir(&req.dir)?;
    let mut book = people::load(&dir);
    let out = book.upsert(&req.people, req.now_ms);
    if out.changed > 0 || !out.drop_files.is_empty() {
        people::save(&dir, &book)?;
    }
    Ok(out)
}

fn upsert_cases() -> Vec<Case> {
    let jid = "905321234567@s.whatsapp.net";
    vec![
        Case::new(json!({"dir": "$DIR", "now_ms": NOW, "people": [
            {"jid": jid, "push_name": "Ayse", "on_whatsapp": true, "picture_id": "56"},
            {"jid": "4477@lid", "push_name": "Cem"},
        ]}))
        .seeded("people"),
        Case::named(
            "lid_join",
            json!({"dir": "$DIR", "now_ms": NOW, "people": [
                {"jid": "4477@lid", "lid": "4477@lid", "number": "+44 7700 900123"},
            ]}),
        )
        .seeded("people"),
        Case::named(
            "picture_removed",
            json!({"dir": "$DIR", "now_ms": NOW, "people": [
                {"jid": jid, "picture_removed": true},
            ]}),
        )
        .seeded("people"),
    ]
}

// ---- people.lookup

#[derive(Deserialize)]
struct LookupReq {
    #[serde(default)]
    dir: Option<String>,
    /// An E.164 number in any spelling, a phone JID, a LID JID or a bare LID.
    key: String,
}

#[derive(Serialize)]
struct LookupAns {
    person: Option<Row>,
}

fn lookup(req: LookupReq) -> Result<LookupAns, FfiError> {
    let book = people::load(&data_dir(&req.dir)?);
    let person = book.lookup(&req.key).map(people::Person::row);
    Ok(LookupAns { person })
}

fn lookup_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "key": "+90 532 123 45 67"})).seeded("people"),
        Case::named("by_lid", json!({"dir": "$DIR", "key": "4477@lid"})).seeded("people"),
        Case::named("unknown", json!({"dir": "$DIR", "key": "+1 202 555 0100"})).seeded("people"),
    ]
}

// ---- people.plan

#[derive(Deserialize)]
struct PlanReq {
    #[serde(default)]
    dir: Option<String>,
    now_ms: i64,
    /// The picture files on disk, with size and age.
    #[serde(default)]
    files: Vec<FileInfo>,
    max_bytes: u64,
}

fn plan(req: PlanReq) -> Result<Plan, FfiError> {
    let book = people::load(&data_dir(&req.dir)?);
    Ok(book.plan(req.now_ms, &req.files, req.max_bytes))
}

fn plan_cases() -> Vec<Case> {
    let file = |name: &str, bytes: u64, modified_ms: u64| json!({"path": format!("/cache/people/{name}"), "bytes": bytes, "modified_ms": modified_ms});
    vec![
        Case::new(
            json!({"dir": "$DIR", "now_ms": NOW, "max_bytes": 1500, "files": [
                file("905321234567_s.whatsapp.net~55.jpg", 1000, 1_000),
                file("905321234567_s.whatsapp.net~56.jpg", 1000, 2_000),
            ]}),
        )
        .seeded("people"),
        Case::named(
            "nothing_to_do",
            json!({"dir": "$DIR/nothing-here", "now_ms": NOW, "max_bytes": 1500}),
        )
        .seeded("people"),
    ]
}

// ---- people.snapshot

#[derive(Deserialize)]
struct SnapshotReq {
    #[serde(default)]
    dir: Option<String>,
}

#[derive(Serialize)]
struct SnapshotAns {
    people: Vec<Row>,
}

fn snapshot(req: SnapshotReq) -> Result<SnapshotAns, FfiError> {
    Ok(SnapshotAns {
        people: people::load(&data_dir(&req.dir)?).snapshot(),
    })
}

fn snapshot_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("people"),
        Case::named("missing", json!({"dir": "$DIR/nothing-here"})).seeded("people"),
    ]
}
