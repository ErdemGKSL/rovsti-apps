//! People sync v3, schema v9: the people job of an instance (one at most), its numbers with what is done for
//! each, and the lookup answers WhatsApp gave.
//!
//! Every write of a running job names its id and only applies while the job is `running` or `waiting`: a write
//! after a cancel, an expiry, a replacing job or the account wipe changes nothing, and the job's task ends on it.

use std::collections::HashMap;

use rosty_wa_proto::people::{
    AcceptedEntry, JOB_KEEP_MS, JOB_MAX_AGE_MS, LOOKUP_KEEP_MS, job_error,
};
use rosty_wa_proto::{
    PeopleJobError, PeopleJobPhase, PeopleJobResult, PeopleJobState, PeopleJobStatus,
    PeopleSyncMode, PeopleWait, PeopleWaitSource, WaUser,
};
use rusqlite::{Connection, OptionalExtension, Transaction, params};

use super::{InstanceId, Store, StoreError, from_tag, to_tag};

/// `pictured`: still to do.
pub const PICTURED_TODO: u8 = 0;
/// `pictured`: done, or nothing to fetch.
pub const PICTURED_OK: u8 = 1;
/// `pictured`: could not be fetched (or the spool is full); the record says `picture_missing`.
pub const PICTURED_FAILED: u8 = 2;

const JOB_COLUMNS: &str = "instance_id, job_id, digest, mode, state, phase, rev, done, total, numbers, eta_ms, \
     wait_until_ms, wait_source, error_code, error_message, result_records, result_pictures, result_bytes, \
     account_epoch, created_ms, updated_ms, finished_ms";

/// A people job as stored.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JobRow {
    pub instance: InstanceId,
    pub job_id: String,
    pub digest: String,
    pub mode: PeopleSyncMode,
    pub state: PeopleJobState,
    pub phase: PeopleJobPhase,
    pub rev: u64,
    pub done: u32,
    pub total: u32,
    pub numbers: u32,
    pub eta_ms: Option<u64>,
    pub wait: Option<PeopleWait>,
    pub error: Option<PeopleJobError>,
    pub result: Option<PeopleJobResult>,
    pub account_epoch: u32,
    pub created_ms: i64,
    pub updated_ms: i64,
    pub finished_ms: Option<i64>,
}

impl JobRow {
    /// When the gateway forgets the job: a finished one [`JOB_KEEP_MS`] after it finished; a working one fails
    /// [`JOB_MAX_AGE_MS`] after it started.
    pub fn expires_ms(&self) -> i64 {
        match self.finished_ms {
            Some(at) => at.saturating_add(JOB_KEEP_MS),
            None => self.created_ms.saturating_add(JOB_MAX_AGE_MS),
        }
    }

    /// The wire status; `mine` is false only on the start answer for another device's job.
    pub fn status(&self, mine: bool) -> PeopleJobStatus {
        PeopleJobStatus {
            job_id: self.job_id.clone(),
            rev: self.rev,
            mine,
            state: self.state,
            phase: self.phase,
            done: self.done,
            total: self.total,
            numbers: self.numbers,
            wait: self.wait.filter(|_| self.state == PeopleJobState::Waiting),
            eta_ms: self.eta_ms.filter(|_| self.state.is_active()),
            result: self.result.filter(|_| self.state == PeopleJobState::Ready),
            error: self
                .error
                .clone()
                .filter(|_| self.state == PeopleJobState::Failed),
            account_epoch: self.account_epoch,
            created_ms: self.created_ms,
            updated_ms: self.updated_ms,
            expires_ms: Some(self.expires_ms()),
        }
    }
}

/// A job to start: its id, the digest of its request and the accepted numbers.
#[derive(Clone, Debug)]
pub struct NewJob {
    pub job_id: String,
    pub digest: String,
    pub mode: PeopleSyncMode,
    pub entries: Vec<AcceptedEntry>,
}

/// What a start request found.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BeginOutcome {
    /// A new job; `replaced` is the finished job it took the place of.
    Started { replaced: Option<String> },
    /// The same request while its job works: that job.
    Joined,
    /// Another device's job works on the instance; nothing was changed.
    Other,
    /// The same request's job is ready (a device that lost its checkpoint downloads it).
    Ready,
}

/// What a cancel found.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CancelOutcome {
    /// A working job is now `cancelled`.
    Cancelled(Box<JobRow>),
    /// A finished job was deleted.
    Dropped,
    /// No such job.
    Gone,
}

/// One number of a job.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NumberRow {
    pub seq: u32,
    /// `<digits>@s.whatsapp.net`.
    pub jid: String,
    pub have_hash: Option<String>,
    pub have_picture: Option<String>,
    pub looked: bool,
    pub profiled: bool,
    /// [`PICTURED_TODO`], [`PICTURED_OK`] or [`PICTURED_FAILED`].
    pub pictured: u8,
    /// The picture id whose full bytes wait in the spool for this number.
    pub spooled_id: Option<String>,
}

impl NumberRow {
    /// The E.164 number (the JID's user part is the number).
    pub fn number(&self) -> String {
        format!("+{}", rosty_wa_proto::jid_user(&self.jid))
    }
}

/// A lookup answer as stored.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LookupRow {
    pub on_whatsapp: bool,
    pub business: bool,
    pub verified_name: Option<String>,
    pub checked_ms: i64,
}

/// Per-number progress to record with a job change.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct NumberMarks {
    pub looked: Vec<u32>,
    pub profiled: Vec<u32>,
    /// `(seq, pictured, spooled picture id)`.
    pub pictured: Vec<(u32, u8, Option<String>)>,
}

impl NumberMarks {
    pub fn is_empty(&self) -> bool {
        self.looked.is_empty() && self.profiled.is_empty() && self.pictured.is_empty()
    }
}

/// The job's state after a step of its task.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JobChange {
    pub state: PeopleJobState,
    pub phase: PeopleJobPhase,
    pub done: u32,
    pub total: u32,
    pub eta_ms: Option<u64>,
    pub wait: Option<PeopleWait>,
    pub error: Option<PeopleJobError>,
    pub result: Option<PeopleJobResult>,
}

impl JobChange {
    fn finished(&self) -> bool {
        !self.state.is_active()
    }
}

type RawJob = (
    (String, String, String, String, String, String, i64, i64),
    (i64, i64, Option<i64>, Option<i64>, Option<String>),
    (
        Option<String>,
        Option<String>,
        Option<i64>,
        Option<i64>,
        Option<i64>,
    ),
    (i64, i64, i64, Option<i64>),
);

fn raw_job(r: &rusqlite::Row<'_>) -> rusqlite::Result<RawJob> {
    Ok((
        (
            r.get(0)?,
            r.get(1)?,
            r.get(2)?,
            r.get(3)?,
            r.get(4)?,
            r.get(5)?,
            r.get(6)?,
            r.get(7)?,
        ),
        (r.get(8)?, r.get(9)?, r.get(10)?, r.get(11)?, r.get(12)?),
        (r.get(13)?, r.get(14)?, r.get(15)?, r.get(16)?, r.get(17)?),
        (r.get(18)?, r.get(19)?, r.get(20)?, r.get(21)?),
    ))
}

fn small(n: i64) -> u32 {
    u32::try_from(n.max(0)).unwrap_or(u32::MAX)
}

fn wide(n: i64) -> u64 {
    u64::try_from(n.max(0)).unwrap_or(0)
}

fn signed(n: u64) -> i64 {
    i64::try_from(n).unwrap_or(i64::MAX)
}

fn job_of(raw: RawJob) -> Result<JobRow, StoreError> {
    let ((instance, job_id, digest, mode, state, phase, rev, done), b, c, d) = raw;
    let (total, numbers, eta_ms, wait_until, wait_source) = b;
    let (error_code, error_message, records, pictures, bytes) = c;
    let (account_epoch, created_ms, updated_ms, finished_ms) = d;
    let wait = match (wait_until, wait_source) {
        (Some(until_ms), Some(source)) => Some(PeopleWait {
            until_ms,
            source: from_tag::<PeopleWaitSource>(&source)?,
        }),
        _ => None,
    };
    let error = error_code.map(|code| PeopleJobError {
        code,
        message: error_message.unwrap_or_default(),
    });
    let result = match (records, pictures, bytes) {
        (Some(records), Some(pictures), Some(bytes)) => Some(PeopleJobResult {
            records: small(records),
            pictures: small(pictures),
            bytes: wide(bytes),
        }),
        _ => None,
    };
    Ok(JobRow {
        instance: InstanceId::new(instance),
        job_id,
        digest,
        mode: from_tag(&mode)?,
        state: from_tag(&state)?,
        phase: from_tag(&phase)?,
        rev: wide(rev),
        done: small(done),
        total: small(total),
        numbers: small(numbers),
        eta_ms: eta_ms.map(wide),
        wait,
        error,
        result,
        account_epoch: small(account_epoch),
        created_ms,
        updated_ms,
        finished_ms,
    })
}

fn read_job(conn: &Connection, instance: &InstanceId) -> Result<Option<JobRow>, StoreError> {
    let raw = conn
        .query_row(
            &format!("SELECT {JOB_COLUMNS} FROM people_jobs WHERE instance_id = ?1"),
            [instance.as_str()],
            raw_job,
        )
        .optional()?;
    raw.map(job_of).transpose()
}

fn delete_job(tx: &Transaction<'_>, instance: &InstanceId) -> Result<(), StoreError> {
    tx.execute(
        "DELETE FROM people_job_numbers WHERE instance_id = ?1",
        [instance.as_str()],
    )?;
    tx.execute(
        "DELETE FROM people_jobs WHERE instance_id = ?1",
        [instance.as_str()],
    )?;
    Ok(())
}

/// Applies the age rules to the job of `instance` at `now_ms`: a working job past [`JOB_MAX_AGE_MS`] fails as
/// `expired`, a finished job past [`JOB_KEEP_MS`] is deleted. Returns the job that is left.
fn age(
    tx: &Transaction<'_>,
    instance: &InstanceId,
    now_ms: i64,
) -> Result<Option<JobRow>, StoreError> {
    let Some(job) = read_job(tx, instance)? else {
        return Ok(None);
    };
    if now_ms >= job.expires_ms() {
        if job.state.is_active() {
            tx.execute(
                "UPDATE people_jobs SET state = ?2, error_code = ?3, error_message = ?4, wait_until_ms = NULL,
                 wait_source = NULL, eta_ms = NULL, finished_ms = ?5, updated_ms = ?5, rev = rev + 1
                 WHERE instance_id = ?1",
                params![
                    instance.as_str(),
                    to_tag(&PeopleJobState::Failed),
                    job_error::EXPIRED,
                    "the job was not ready in time",
                    now_ms
                ],
            )?;
            log::info!("instance={instance} people job expired");
            return read_job(tx, instance);
        }
        delete_job(tx, instance)?;
        return Ok(None);
    }
    Ok(Some(job))
}

/// Lookup answers older than [`LOOKUP_KEEP_MS`] go.
fn prune_lookups(
    tx: &Transaction<'_>,
    instance: Option<&InstanceId>,
    now_ms: i64,
) -> Result<usize, StoreError> {
    let before = now_ms.saturating_sub(LOOKUP_KEEP_MS);
    Ok(match instance {
        Some(instance) => tx.execute(
            "DELETE FROM people_lookups WHERE instance_id = ?1 AND checked_ms < ?2",
            params![instance.as_str(), before],
        )?,
        None => tx.execute("DELETE FROM people_lookups WHERE checked_ms < ?1", [before])?,
    })
}

impl Store {
    /// The people job of `instance` after the age rules, if any.
    pub async fn people_job(
        &self,
        instance: InstanceId,
        now_ms: i64,
    ) -> Result<Option<JobRow>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let job = age(&tx, &instance, now_ms)?;
            tx.commit()?;
            Ok(job)
        })
        .await
    }

    /// Starts `new` or finds the job it belongs to (section 3.1 of the plan), in one transaction: the same request
    /// joins its working or ready job; another request leaves a working job alone (another device's); otherwise
    /// the old job and its numbers go and `new` starts with the instance's account epoch. Old lookup answers are
    /// pruned on a start.
    pub async fn people_begin(
        &self,
        instance: InstanceId,
        new: NewJob,
        now_ms: i64,
    ) -> Result<(BeginOutcome, JobRow), StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let old = age(&tx, &instance, now_ms)?;
            if let Some(old) = &old {
                let same = old.digest == new.digest;
                let outcome = match old.state {
                    PeopleJobState::Running | PeopleJobState::Waiting if same => {
                        Some(BeginOutcome::Joined)
                    }
                    PeopleJobState::Running | PeopleJobState::Waiting => Some(BeginOutcome::Other),
                    PeopleJobState::Ready if same => Some(BeginOutcome::Ready),
                    _ => None,
                };
                if let Some(outcome) = outcome {
                    tx.commit()?;
                    return Ok((outcome, old.clone()));
                }
            }
            delete_job(&tx, &instance)?;
            prune_lookups(&tx, Some(&instance), now_ms)?;
            let account_epoch: i64 = tx
                .query_row(
                    "SELECT account_epoch FROM instances WHERE id = ?1",
                    [instance.as_str()],
                    |r| r.get(0),
                )
                .optional()?
                .ok_or(StoreError::NotFound)?;
            let numbers = i64::try_from(new.entries.len()).unwrap_or(i64::MAX);
            tx.execute(
                "INSERT INTO people_jobs (instance_id, job_id, digest, mode, state, phase, rev, done, total,
                     numbers, account_epoch, created_ms, updated_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, 1, 0, ?7, ?7, ?8, ?9, ?9)",
                params![
                    instance.as_str(),
                    new.job_id,
                    new.digest,
                    to_tag(&new.mode),
                    to_tag(&PeopleJobState::Running),
                    to_tag(&PeopleJobPhase::Lookup),
                    numbers,
                    account_epoch,
                    now_ms
                ],
            )?;
            {
                let mut insert = tx.prepare(
                    "INSERT INTO people_job_numbers (instance_id, seq, jid, have_hash, have_picture)
                     VALUES (?1, ?2, ?3, ?4, ?5)",
                )?;
                for entry in &new.entries {
                    insert.execute(params![
                        instance.as_str(),
                        entry.seq,
                        entry.jid,
                        entry.hash,
                        entry.picture
                    ])?;
                }
            }
            let job = read_job(&tx, &instance)?.ok_or(StoreError::NotFound)?;
            tx.commit()?;
            Ok((
                BeginOutcome::Started {
                    replaced: old.map(|o| o.job_id),
                },
                job,
            ))
        })
        .await
    }

    /// The numbers of job `job_id`, in the device's order (none when the job is gone).
    pub async fn people_numbers(
        &self,
        instance: InstanceId,
        job_id: String,
    ) -> Result<Vec<NumberRow>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT n.seq, n.jid, n.have_hash, n.have_picture, n.looked, n.profiled, n.pictured, n.spooled_id
                 FROM people_job_numbers n JOIN people_jobs j ON j.instance_id = n.instance_id
                 WHERE n.instance_id = ?1 AND j.job_id = ?2 ORDER BY n.seq",
            )?;
            let rows = stmt.query_map(params![instance.as_str(), job_id], |r| {
                Ok(NumberRow {
                    seq: r.get(0)?,
                    jid: r.get(1)?,
                    have_hash: r.get(2)?,
                    have_picture: r.get(3)?,
                    looked: r.get(4)?,
                    profiled: r.get(5)?,
                    pictured: r.get(6)?,
                    spooled_id: r.get(7)?,
                })
            })?;
            Ok(rows.collect::<Result<Vec<_>, _>>()?)
        })
        .await
    }

    /// Records a step of job `job_id`: the lookup answers, the per-number marks and the job's new state, in one
    /// transaction, and raises `rev`. Nothing is written (and `None` comes back) unless the job still works.
    pub async fn people_commit(
        &self,
        instance: InstanceId,
        job_id: String,
        change: JobChange,
        marks: NumberMarks,
        lookups: Vec<WaUser>,
        now_ms: i64,
    ) -> Result<Option<JobRow>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let working: Option<String> = tx
                .query_row(
                    "SELECT state FROM people_jobs WHERE instance_id = ?1 AND job_id = ?2",
                    params![instance.as_str(), job_id],
                    |r| r.get(0),
                )
                .optional()?;
            let active = working
                .map(|s| from_tag::<PeopleJobState>(&s))
                .transpose()?
                .is_some_and(PeopleJobState::is_active);
            if !active {
                return Ok(None);
            }
            write_lookups(&tx, &instance, &lookups, now_ms)?;
            for seq in &marks.looked {
                tx.execute(
                    "UPDATE people_job_numbers SET looked = 1 WHERE instance_id = ?1 AND seq = ?2",
                    params![instance.as_str(), seq],
                )?;
            }
            for seq in &marks.profiled {
                tx.execute(
                    "UPDATE people_job_numbers SET profiled = 1 WHERE instance_id = ?1 AND seq = ?2",
                    params![instance.as_str(), seq],
                )?;
            }
            for (seq, pictured, spooled) in &marks.pictured {
                tx.execute(
                    "UPDATE people_job_numbers SET pictured = ?3, spooled_id = ?4
                     WHERE instance_id = ?1 AND seq = ?2",
                    params![instance.as_str(), seq, pictured, spooled],
                )?;
            }
            let (records, pictures, bytes) = change
                .result
                .map_or((None, None, None), |r| {
                    (Some(r.records), Some(r.pictures), Some(signed(r.bytes)))
                });
            tx.execute(
                "UPDATE people_jobs SET state = ?3, phase = ?4, done = ?5, total = ?6, eta_ms = ?7,
                     wait_until_ms = ?8, wait_source = ?9, error_code = ?10, error_message = ?11,
                     result_records = ?12, result_pictures = ?13, result_bytes = ?14,
                     finished_ms = ?15, updated_ms = ?16, rev = rev + 1
                 WHERE instance_id = ?1 AND job_id = ?2",
                params![
                    instance.as_str(),
                    job_id,
                    to_tag(&change.state),
                    to_tag(&change.phase),
                    change.done,
                    change.total,
                    change.eta_ms.map(signed),
                    change.wait.map(|w| w.until_ms),
                    change.wait.map(|w| to_tag(&w.source)),
                    change.error.as_ref().map(|e| e.code.clone()),
                    change.error.as_ref().map(|e| e.message.clone()),
                    records,
                    pictures,
                    bytes,
                    change.finished().then_some(now_ms),
                    now_ms
                ],
            )?;
            let job = read_job(&tx, &instance)?;
            tx.commit()?;
            Ok(job)
        })
        .await
    }

    /// Cancels job `job_id` while it works, or deletes it once it finished (the device is done with it).
    pub async fn people_cancel(
        &self,
        instance: InstanceId,
        job_id: String,
        now_ms: i64,
    ) -> Result<CancelOutcome, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let Some(job) = age(&tx, &instance, now_ms)?.filter(|j| j.job_id == job_id) else {
                tx.commit()?;
                return Ok(CancelOutcome::Gone);
            };
            let outcome = if job.state.is_active() {
                tx.execute(
                    "UPDATE people_jobs SET state = ?2, wait_until_ms = NULL, wait_source = NULL, eta_ms = NULL,
                         finished_ms = ?3, updated_ms = ?3, rev = rev + 1
                     WHERE instance_id = ?1",
                    params![
                        instance.as_str(),
                        to_tag(&PeopleJobState::Cancelled),
                        now_ms
                    ],
                )?;
                CancelOutcome::Cancelled(Box::new(
                    read_job(&tx, &instance)?.ok_or(StoreError::NotFound)?,
                ))
            } else {
                delete_job(&tx, &instance)?;
                CancelOutcome::Dropped
            };
            tx.commit()?;
            Ok(outcome)
        })
        .await
    }

    /// The stored lookup answers of `jids` (those the gateway has).
    pub async fn people_lookups(
        &self,
        instance: InstanceId,
        jids: Vec<String>,
    ) -> Result<HashMap<String, LookupRow>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT on_whatsapp, business, verified_name, checked_ms FROM people_lookups
                 WHERE instance_id = ?1 AND jid = ?2",
            )?;
            let mut out = HashMap::with_capacity(jids.len());
            for jid in jids {
                let row = stmt
                    .query_row(params![instance.as_str(), jid], |r| {
                        Ok(LookupRow {
                            on_whatsapp: r.get(0)?,
                            business: r.get(1)?,
                            verified_name: r.get(2)?,
                            checked_ms: r.get(3)?,
                        })
                    })
                    .optional()?;
                if let Some(row) = row {
                    out.insert(jid, row);
                }
            }
            Ok(out)
        })
        .await
    }

    /// Every job that was working, after the age rules (to resume after a restart).
    pub async fn people_working(&self, now_ms: i64) -> Result<Vec<JobRow>, StoreError> {
        self.run(move |conn| {
            let instances: Vec<String> = conn
                .prepare("SELECT instance_id FROM people_jobs ORDER BY instance_id")?
                .query_map([], |r| r.get(0))?
                .collect::<Result<_, _>>()?;
            let tx = conn.transaction()?;
            let mut out = Vec::new();
            for instance in instances {
                if let Some(job) = age(&tx, &InstanceId::new(instance), now_ms)?
                    && job.state.is_active()
                {
                    out.push(job);
                }
            }
            tx.commit()?;
            Ok(out)
        })
        .await
    }

    /// The hourly housekeeping: the age rules for every job, and lookup answers past [`LOOKUP_KEEP_MS`]. Returns
    /// each instance with a job and that job's id after the rules (`None`: deleted).
    pub async fn people_tick(
        &self,
        now_ms: i64,
    ) -> Result<Vec<(InstanceId, Option<JobRow>)>, StoreError> {
        self.run(move |conn| {
            let instances: Vec<String> = conn
                .prepare("SELECT instance_id FROM people_jobs ORDER BY instance_id")?
                .query_map([], |r| r.get(0))?
                .collect::<Result<_, _>>()?;
            let tx = conn.transaction()?;
            let mut out = Vec::new();
            for instance in instances {
                let instance = InstanceId::new(instance);
                let job = age(&tx, &instance, now_ms)?;
                out.push((instance, job));
            }
            let pruned = prune_lookups(&tx, None, now_ms)?;
            tx.commit()?;
            if pruned > 0 {
                log::info!("people lookups pruned n={pruned}");
            }
            Ok(out)
        })
        .await
    }
}

fn write_lookups(
    tx: &Transaction<'_>,
    instance: &InstanceId,
    users: &[WaUser],
    now_ms: i64,
) -> Result<(), StoreError> {
    for user in users {
        tx.execute(
            "INSERT INTO people_lookups (instance_id, jid, on_whatsapp, business, verified_name, checked_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT (instance_id, jid) DO UPDATE SET on_whatsapp = ?3, business = ?4,
                 verified_name = ?5, checked_ms = ?6",
            params![
                instance.as_str(),
                user.jid,
                user.on_whatsapp,
                user.business,
                user.verified_name
                    .as_deref()
                    .map(str::trim)
                    .filter(|n| !n.is_empty()),
                now_ms
            ],
        )?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::NewKey;
    use rosty_wa_proto::people::accept_entries;
    use rosty_wa_proto::{ConnectKey, KeyId, PeopleEntry};

    fn id(n: u8) -> InstanceId {
        InstanceId::from_bytes(&[n; 12])
    }

    async fn store_with(ids: &[u8]) -> Store {
        let store = Store::open_in_memory().unwrap();
        for &n in ids {
            let key = ConnectKey::from_bytes(&[n; 32]);
            let new = NewKey {
                key_id: KeyId::from_bytes(&[n; 8]),
                hash: key.hash(),
                label: "k".into(),
            };
            store.create_instance(id(n), new, 1, 8).await.unwrap();
        }
        store
    }

    fn job(job_id: &str, digest: &str, numbers: &[&str]) -> NewJob {
        let entries: Vec<PeopleEntry> = numbers
            .iter()
            .map(|n| PeopleEntry {
                n: (*n).into(),
                ..Default::default()
            })
            .collect();
        NewJob {
            job_id: job_id.into(),
            digest: digest.into(),
            mode: PeopleSyncMode::Auto,
            entries: accept_entries(&entries).unwrap(),
        }
    }

    const NOW: i64 = 1_790_000_000_000;

    fn change(state: PeopleJobState) -> JobChange {
        JobChange {
            state,
            phase: PeopleJobPhase::Lookup,
            done: 1,
            total: 2,
            eta_ms: Some(20_000),
            wait: None,
            error: None,
            result: None,
        }
    }

    fn rows(store: &Store, table: &str, instance: &InstanceId) -> u32 {
        super::super::lock(&store.conn)
            .query_row(
                &format!("SELECT COUNT(*) FROM {table} WHERE instance_id = ?1"),
                [instance.as_str()],
                |r| r.get(0),
            )
            .unwrap()
    }

    #[tokio::test]
    async fn one_job_per_instance_joined_by_its_digest() {
        let store = store_with(&[1]).await;
        let numbers = ["+100000000001", "+100000000002"];
        let (outcome, first) = store
            .people_begin(id(1), job("j1", "d1", &numbers), NOW)
            .await
            .unwrap();
        assert_eq!(outcome, BeginOutcome::Started { replaced: None });
        assert_eq!(
            (first.state, first.numbers, first.rev),
            (PeopleJobState::Running, 2, 1)
        );
        assert_eq!(first.status(true).expires_ms, Some(NOW + JOB_MAX_AGE_MS));
        // The same request joins, another one is another device's: nothing changes.
        let (outcome, joined) = store
            .people_begin(id(1), job("j2", "d1", &numbers), NOW + 1)
            .await
            .unwrap();
        assert_eq!(
            (outcome, joined.job_id.as_str()),
            (BeginOutcome::Joined, "j1")
        );
        let (outcome, other) = store
            .people_begin(id(1), job("j3", "d2", &numbers[..1]), NOW + 2)
            .await
            .unwrap();
        assert_eq!(
            (outcome, other.job_id.as_str()),
            (BeginOutcome::Other, "j1")
        );
        assert_eq!(rows(&store, "people_job_numbers", &id(1)), 2);
        let numbers = store.people_numbers(id(1), "j1".into()).await.unwrap();
        assert_eq!(numbers[1].number(), "+100000000002");
        assert!(
            store
                .people_numbers(id(1), "j3".into())
                .await
                .unwrap()
                .is_empty()
        );

        // Ready: the same request finds it, another replaces it.
        let mut ready = change(PeopleJobState::Ready);
        ready.result = Some(PeopleJobResult {
            records: 2,
            pictures: 0,
            bytes: 200,
        });
        let row = store
            .people_commit(
                id(1),
                "j1".into(),
                ready,
                NumberMarks::default(),
                vec![],
                NOW + 3,
            )
            .await
            .unwrap()
            .unwrap();
        assert_eq!(
            (row.state, row.finished_ms, row.rev),
            (PeopleJobState::Ready, Some(NOW + 3), 2)
        );
        assert_eq!(row.status(true).expires_ms, Some(NOW + 3 + JOB_KEEP_MS));
        let (outcome, _) = store
            .people_begin(
                id(1),
                job("j4", "d1", &["+100000000001", "+100000000002"]),
                NOW + 4,
            )
            .await
            .unwrap();
        assert_eq!(outcome, BeginOutcome::Ready);
        let (outcome, new) = store
            .people_begin(id(1), job("j5", "d9", &["+100000000003"]), NOW + 5)
            .await
            .unwrap();
        assert_eq!(
            outcome,
            BeginOutcome::Started {
                replaced: Some("j1".into())
            }
        );
        assert_eq!(new.job_id, "j5");
        assert_eq!(rows(&store, "people_job_numbers", &id(1)), 1);
    }

    #[tokio::test]
    async fn writes_only_apply_while_the_job_works() {
        let store = store_with(&[1]).await;
        store
            .people_begin(id(1), job("j1", "d", &["+100000000001"]), NOW)
            .await
            .unwrap();
        let user = WaUser {
            jid: "100000000001@s.whatsapp.net".into(),
            on_whatsapp: true,
            business: true,
            verified_name: Some(" Shop ".into()),
        };
        let marks = NumberMarks {
            looked: vec![1],
            ..Default::default()
        };
        let row = store
            .people_commit(
                id(1),
                "j1".into(),
                change(PeopleJobState::Running),
                marks.clone(),
                vec![user.clone()],
                NOW,
            )
            .await
            .unwrap()
            .unwrap();
        assert_eq!((row.done, row.total, row.rev), (1, 2, 2));
        let lookups = store
            .people_lookups(id(1), vec![user.jid.clone(), "x".into()])
            .await
            .unwrap();
        assert_eq!(
            lookups[&user.jid],
            LookupRow {
                on_whatsapp: true,
                business: true,
                verified_name: Some("Shop".into()),
                checked_ms: NOW
            }
        );
        assert!(store.people_numbers(id(1), "j1".into()).await.unwrap()[0].looked);
        // A write for another job id changes nothing.
        assert!(
            store
                .people_commit(
                    id(1),
                    "other".into(),
                    change(PeopleJobState::Running),
                    marks.clone(),
                    vec![],
                    NOW
                )
                .await
                .unwrap()
                .is_none()
        );
        // Cancelled: the task's next write changes nothing; a second cancel drops it.
        match store
            .people_cancel(id(1), "j1".into(), NOW + 1)
            .await
            .unwrap()
        {
            CancelOutcome::Cancelled(row) => {
                assert_eq!((row.state, row.rev), (PeopleJobState::Cancelled, 3));
            }
            other => panic!("{other:?}"),
        }
        assert!(
            store
                .people_commit(
                    id(1),
                    "j1".into(),
                    change(PeopleJobState::Running),
                    marks,
                    vec![],
                    NOW + 2
                )
                .await
                .unwrap()
                .is_none()
        );
        assert_eq!(
            store
                .people_cancel(id(1), "j1".into(), NOW + 3)
                .await
                .unwrap(),
            CancelOutcome::Dropped
        );
        assert_eq!(
            store
                .people_cancel(id(1), "j1".into(), NOW + 3)
                .await
                .unwrap(),
            CancelOutcome::Gone
        );
        // The answers stay: they are what a new job resumes from.
        assert_eq!(rows(&store, "people_lookups", &id(1)), 1);
    }

    #[tokio::test]
    async fn jobs_expire_and_old_answers_are_pruned() {
        let store = store_with(&[1, 2]).await;
        store
            .people_begin(id(1), job("j1", "d", &["+100000000001"]), NOW)
            .await
            .unwrap();
        let old = WaUser {
            jid: "100000000009@s.whatsapp.net".into(),
            on_whatsapp: false,
            business: false,
            verified_name: None,
        };
        store
            .people_commit(
                id(1),
                "j1".into(),
                change(PeopleJobState::Running),
                NumberMarks::default(),
                vec![old],
                NOW,
            )
            .await
            .unwrap();
        assert_eq!(store.people_working(NOW + 1).await.unwrap().len(), 1);
        // Not ready in six hours: failed, expired.
        let later = NOW + JOB_MAX_AGE_MS;
        let row = store.people_job(id(1), later).await.unwrap().unwrap();
        assert_eq!(row.state, PeopleJobState::Failed);
        assert_eq!(row.error.unwrap().code, job_error::EXPIRED);
        assert!(store.people_working(later).await.unwrap().is_empty());
        // Gone a day after it ended.
        let ticked = store.people_tick(later + JOB_KEEP_MS).await.unwrap();
        assert_eq!(ticked, vec![(id(1), None)]);
        assert_eq!(rows(&store, "people_job_numbers", &id(1)), 0);
        // Answers are kept 30 days.
        assert_eq!(rows(&store, "people_lookups", &id(1)), 1);
        store.people_tick(NOW + LOOKUP_KEEP_MS + 1).await.unwrap();
        assert_eq!(rows(&store, "people_lookups", &id(1)), 0);
    }

    #[tokio::test]
    async fn the_instance_delete_removes_the_rows() {
        let store = store_with(&[1]).await;
        store
            .people_begin(id(1), job("j1", "d", &["+100000000001"]), NOW)
            .await
            .unwrap();
        store
            .people_commit(
                id(1),
                "j1".into(),
                change(PeopleJobState::Running),
                NumberMarks::default(),
                vec![WaUser {
                    jid: "100000000001@s.whatsapp.net".into(),
                    on_whatsapp: true,
                    business: false,
                    verified_name: None,
                }],
                NOW,
            )
            .await
            .unwrap();
        assert!(store.delete_instance(id(1)).await.unwrap());
        for table in ["people_jobs", "people_job_numbers", "people_lookups"] {
            assert_eq!(rows(&store, table, &id(1)), 0, "{table}");
        }
    }
}
