//! `wa.people_sync_*` (people sync v3): the gateway's people job, one op per call of the planner's job steps
//! (`rosty_app_core::people_sync`, `Step::Job*`).
//!
//! * `wa.people_sync_start {handle, mode, entries}` -> `{supported: true, status}`, or `{supported: false}` when the
//!   gateway has no job routes (the router's bare 404: report `job_unsupported`).
//! * `wa.people_sync_status {handle, job_id, rev?, wait_ms?}` -> `{status}` (a long-poll of up to 25 s), or
//!   `{gone: true}` when the gateway no longer has the job (report `job_gone`).
//! * `wa.people_sync_bundle {handle, job_id, after, max_bytes?}` reads one bundle part as a stream; each picture
//!   is written to `<cache_dir>/people-bundle/<job_id>-<seq>.bin` (no bytes over JNI) and named in its record's
//!   `picture_path`; the caller downscales and stores it, deletes the file and reports `bundle`. A part that ends
//!   early is a `network` error; the cursor stays. `{gone: true}` as above.
//! * `wa.people_sync_cancel {handle, job_id}` -> `{}`: stop a working job, or drop a finished one; a job that is
//!   gone is fine.

use super::fixture::KEY;
use super::{Call, Empty};
use crate::envelope::FfiError;
use crate::ops::registry::{Case, OpSpec, PLACEHOLDER_HANDLE};
use rosty_wa_client::ClientError;
use rosty_wa_proto::{PeopleEntry, PeopleJobStatus, PeopleRecord, PeopleSyncMode, PeopleSyncStart};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

pub(super) const OPS: &[OpSpec] = &[
    wa_op!(
        "wa.people_sync_start",
        Call<StartReq>,
        StartAns,
        start,
        || vec![
            main(json!({"mode": "auto", "entries": [
                {"n": "+100000000001", "h": "9a41c0de5b7e1f02", "p": "1716839201"},
                {"n": "+100000000002"},
            ]})),
            named(
                "unsupported",
                json!({"mode": "full", "entries": [{"n": "+100000000001"}]})
            ),
        ]
    ),
    wa_op!(
        "wa.people_sync_status",
        Call<StatusReq>,
        StatusAns,
        status,
        || vec![
            main(json!({"job_id": JOB, "rev": 3, "wait_ms": 25_000})),
            named("gone", json!({"job_id": "0000000000000000"})),
        ]
    ),
    wa_op!(
        "wa.people_sync_bundle",
        Call<BundleReq>,
        BundleAns,
        bundle,
        || vec![
            with_cache(None, json!({"job_id": JOB, "after": 0})),
            with_cache(
                Some("gone"),
                json!({"job_id": "0000000000000000", "after": 0})
            ),
            with_cache(Some("cut"), json!({"job_id": JOB, "after": 2})),
        ]
    ),
    wa_op!(
        "wa.people_sync_cancel",
        Call<JobRef>,
        Empty,
        cancel,
        || vec![main(json!({"job_id": JOB}))]
    ),
];

/// The job id of the fixtures.
const JOB: &str = "5f0c9a1b22d4e7a3";

fn main(body: Value) -> Case {
    super::fixture::case(body)
}

fn named(label: &'static str, body: Value) -> Case {
    super::fixture::named(label, body)
}

/// A bundle case: the gateway opened with its cache in the case's directory, so picture paths read `$DIR`.
fn with_cache(label: Option<&'static str>, mut body: Value) -> Case {
    body["handle"] = json!(PLACEHOLDER_HANDLE);
    let case = match label {
        Some(label) => Case::named(label, body),
        None => Case::new(body),
    };
    case.seeded("wa").after(
        "wa.open",
        json!({"url": "$WA", "key": KEY, "cache_dir": "$DIR/media"}),
    )
}

// ---- wa.people_sync_start

#[derive(Deserialize)]
pub(super) struct StartReq {
    #[serde(default)]
    mode: PeopleSyncMode,
    #[serde(default)]
    entries: Vec<PeopleEntry>,
}

#[derive(Serialize)]
pub(super) struct StartAns {
    /// False: the gateway has no job routes; use the per-contact steps.
    supported: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    status: Option<PeopleJobStatus>,
}

fn start(req: Call<StartReq>) -> Result<StartAns, FfiError> {
    let g = super::ctx(req.handle)?;
    let body = PeopleSyncStart {
        mode: req.body.mode,
        entries: req.body.entries,
    };
    match g.people_sync_start(&body) {
        Ok(status) => Ok(StartAns {
            supported: true,
            status: Some(status),
        }),
        Err(error) if error.is_not_found() => Ok(StartAns {
            supported: false,
            status: None,
        }),
        Err(error) => Err(super::map_error(error)),
    }
}

// ---- wa.people_sync_status

#[derive(Deserialize)]
pub(super) struct StatusReq {
    job_id: String,
    /// The revision the caller holds; with `wait_ms` the gateway answers when the job is past it.
    #[serde(default)]
    rev: Option<u64>,
    #[serde(default)]
    wait_ms: Option<u64>,
}

#[derive(Serialize)]
pub(super) struct StatusAns {
    /// The gateway no longer has the job.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    gone: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    status: Option<PeopleJobStatus>,
}

fn status(req: Call<StatusReq>) -> Result<StatusAns, FfiError> {
    let g = super::ctx(req.handle)?;
    let b = &req.body;
    match g.people_sync_status(&b.job_id, b.rev, b.wait_ms) {
        Ok(status) => Ok(StatusAns {
            gone: false,
            status: Some(status),
        }),
        Err(error) if error.is_not_found() => Ok(StatusAns {
            gone: true,
            status: None,
        }),
        Err(error) => Err(super::map_error(error)),
    }
}

// ---- wa.people_sync_bundle

#[derive(Deserialize)]
pub(super) struct BundleReq {
    job_id: String,
    #[serde(default)]
    after: u32,
    #[serde(default)]
    max_bytes: Option<u64>,
}

/// A record of the part: the gateway's fields, and where its picture bytes were written.
#[derive(Serialize)]
pub(super) struct BundleRecordAns {
    #[serde(flatten)]
    record: PeopleRecord,
    #[serde(skip_serializing_if = "Option::is_none")]
    picture_path: Option<String>,
}

#[derive(Serialize)]
pub(super) struct BundleAns {
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    gone: bool,
    records: Vec<BundleRecordAns>,
    /// The next `after`.
    last_seq: u32,
    /// Another part follows.
    more: bool,
    /// About how many bytes the part had.
    bytes: u64,
}

/// `<cache_dir>/people-bundle/<job_id>-<seq>.bin`.
fn picture_file(cache_dir: &Path, job_id: &str, seq: u32) -> PathBuf {
    let safe: String = job_id
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect();
    cache_dir
        .join("people-bundle")
        .join(format!("{safe}-{seq}.bin"))
}

fn bundle(req: Call<BundleReq>) -> Result<BundleAns, FfiError> {
    let g = super::ctx(req.handle)?;
    let b = &req.body;
    let mut records = Vec::new();
    let mut bytes = 0u64;
    let read = g.people_sync_bundle(&b.job_id, b.after, b.max_bytes, |mut record, picture| {
        let line = serde_json::to_vec(&record).map_or(0, |l| l.len() as u64);
        bytes = bytes.saturating_add(line + 1);
        record.picture_bytes = None;
        let picture_path = match picture {
            Some(picture) => {
                bytes = bytes.saturating_add(picture.len() as u64 + 1);
                let path = picture_file(&g.cache_dir, &b.job_id, record.seq);
                if let Some(parent) = path.parent() {
                    std::fs::create_dir_all(parent)
                        .map_err(|e| ClientError::Http(format!("people bundle: {e}")))?;
                }
                std::fs::write(&path, &picture)
                    .map_err(|e| ClientError::Http(format!("people bundle: {e}")))?;
                Some(path.to_string_lossy().into_owned())
            }
            None => None,
        };
        records.push(BundleRecordAns {
            record,
            picture_path,
        });
        Ok(())
    });
    match read {
        Ok(end) => Ok(BundleAns {
            gone: false,
            records,
            last_seq: end.last_seq,
            more: end.more,
            bytes,
        }),
        Err(error) if error.is_not_found() => Ok(BundleAns {
            gone: true,
            records: Vec::new(),
            last_seq: b.after,
            more: false,
            bytes: 0,
        }),
        Err(error) => {
            // The pictures of a part that did not complete are not reported: delete them.
            for record in &records {
                if let Some(path) = &record.picture_path {
                    let _ = std::fs::remove_file(path);
                }
            }
            Err(super::map_error(error))
        }
    }
}

// ---- wa.people_sync_cancel

#[derive(Deserialize)]
pub(super) struct JobRef {
    job_id: String,
}

fn cancel(req: Call<JobRef>) -> Result<Empty, FfiError> {
    let g = super::ctx(req.handle)?;
    super::done(g.people_sync_cancel(&req.body.job_id)).map_err(super::map_error)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bundle_pictures_get_a_safe_name_in_the_cache() {
        let path = picture_file(Path::new("/cache"), "5f0c/../x", 17);
        assert_eq!(path, Path::new("/cache/people-bundle/5f0c____x-17.bin"));
    }
}
