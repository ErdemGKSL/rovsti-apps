//! People sync v3 (`_bmad-output/kotlin-ui/plans/people-sync-v3.md`, section 3): the server-side job that looks
//! up the device's numbers on WhatsApp, asks for the profiles of those on it, fetches the pictures the device
//! does not hold, and then waits for the device to download the result in one bundle.
//!
//! * One job per instance (the store's primary key). Its numbers carry per-number flags, so a restart resumes
//!   from them ([`resume_all`]) and a cancel or a new job never asks WhatsApp twice for a fresh answer.
//! * The job shares the instance's allowances and WhatsApp cooldown with the per-contact routes
//!   ([`AppState::lookup_cached`], [`AppState::charge_lookups`], [`AppState::charge_profiles`],
//!   [`AppState::note_upstream_limit`]), and paces itself on top: [`Pacer`], the numbers of people sync v2.
//! * It writes profiles through [`crate::store::Store::change_profile`] directly: no `profile` event is
//!   published, so a first run does not flood the event socket.
//! * Every write names the job id and only applies while the job works; a write that changes nothing (cancel,
//!   expiry, a replacing job, the account wipe) ends the task.
//! * Logs carry counts only, never a number or JID.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

use rosty_wa_proto::people::{job_error, people_hash};
use rosty_wa_proto::{
    PeopleJobError, PeopleJobPhase, PeopleJobResult, PeopleJobState, PeopleRecord, PeopleSyncMode,
    PeopleWait, PeopleWaitSource, WaProfile, WaUser,
};
use tokio::sync::watch;
use tokio::task::{AbortHandle, JoinHandle};

use crate::AppState;
use crate::driver::{DriverError, PictureFetch};
use crate::media_cache::MediaCache;
use crate::rich_routes::{PICTURE_CACHE_BYTES, cached, picture_key, profile_is_stale};
use crate::store::{
    CancelOutcome, InstanceId, JobChange, JobRow, LookupRow, NumberMarks, NumberRow,
    PICTURED_FAILED, PICTURED_OK, PICTURED_TODO, ProfileChange, StoreError,
};

// ---- pace ----------------------------------------------------------------------------------------
//
// The numbers of people sync v2 (`rosty_app_core::people_sync`, copied, not shared, so the device planner is
// not touched): lookups and profiles are both usync queries on WhatsApp's side and share one gap.

/// JIDs per usync request.
pub const USYNC_BATCH: u32 = 15;
/// The smallest batch after limits.
pub const USYNC_BATCH_MIN: u32 = 5;
/// Gap between usync requests.
pub const USYNC_GAP_MS: u64 = 20_000;
/// The longest usync gap after limits.
pub const USYNC_GAP_MAX_MS: u64 = 300_000;
/// Gap between picture requests.
pub const PICTURE_GAP_MS: u64 = 3_000;
/// The longest picture gap after limits.
pub const PICTURE_GAP_MAX_MS: u64 = 60_000;
/// Every gap is drawn within ±this many percent.
pub const JITTER_PCT: u64 = 20;
/// Successes in a row that clear the strikes; then every success brings the gap 10 % back.
pub const RECOVER_AFTER: u32 = 5;
/// Every this many successes (once recovered) the batch grows by [`BATCH_STEP`].
pub const GROW_EVERY: u32 = 10;
pub const BATCH_STEP: u32 = 5;
/// How often a job whose WhatsApp session is down tries again.
pub const NOT_CONNECTED_RETRY_MS: u64 = 60_000;
/// Retries of a request WhatsApp failed (other than a limit) before the job fails.
pub const RETRIES: u32 = 2;
/// The picture bytes one job holds for the device at most; beyond it a picture is left for the next job.
pub const SPOOL_MAX_BYTES: u64 = 256 * 1024 * 1024;
/// The housekeeping of [`spawn_tick`].
pub const TICK_EVERY: Duration = Duration::from_secs(3_600);

/// The pace of the people jobs. Tests make it faster; the gateway uses [`JobPace::default`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct JobPace {
    pub usync_batch: u32,
    pub usync_gap_ms: u64,
    pub picture_gap_ms: u64,
    pub not_connected_retry_ms: u64,
    pub spool_max_bytes: u64,
}

impl Default for JobPace {
    fn default() -> Self {
        Self {
            usync_batch: USYNC_BATCH,
            usync_gap_ms: USYNC_GAP_MS,
            picture_gap_ms: PICTURE_GAP_MS,
            not_connected_retry_ms: NOT_CONNECTED_RETRY_MS,
            spool_max_bytes: SPOOL_MAX_BYTES,
        }
    }
}

/// How fast a job asks WhatsApp now: the base pace, slowed by limits, recovering with successes. Pure.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Pacer {
    base: JobPace,
    usync_gap_ms: u64,
    batch: u32,
    picture_gap_ms: u64,
    strikes: u32,
    ok_streak: u32,
}

impl Pacer {
    pub fn new(base: JobPace) -> Self {
        Self {
            base,
            usync_gap_ms: base.usync_gap_ms,
            batch: base.usync_batch.max(1),
            picture_gap_ms: base.picture_gap_ms,
            strikes: 0,
            ok_streak: 0,
        }
    }

    /// JIDs in the next usync request.
    pub fn batch(&self) -> usize {
        self.batch.max(1) as usize
    }

    /// The gap before the next request of this kind, without jitter.
    pub fn gap_ms(&self, picture: bool) -> u64 {
        if picture {
            self.picture_gap_ms
        } else {
            self.usync_gap_ms
        }
    }

    /// The gap drawn within ±[`JITTER_PCT`] by `rand`.
    pub fn jittered(&self, picture: bool, rand: u32) -> u64 {
        let gap = self.gap_ms(picture);
        let spread = u64::from(rand) % (2 * JITTER_PCT + 1);
        gap * (100 - JITTER_PCT + spread) / 100
    }

    /// A request went through.
    pub fn success(&mut self, picture: bool) {
        self.ok_streak = self.ok_streak.saturating_add(1);
        if self.ok_streak < RECOVER_AFTER {
            return;
        }
        self.strikes = 0;
        if picture {
            self.picture_gap_ms =
                (self.picture_gap_ms - self.picture_gap_ms / 10).max(self.base.picture_gap_ms);
        } else {
            self.usync_gap_ms =
                (self.usync_gap_ms - self.usync_gap_ms / 10).max(self.base.usync_gap_ms);
            if self.ok_streak.is_multiple_of(GROW_EVERY) {
                self.batch = (self.batch + BATCH_STEP).min(self.base.usync_batch.max(1));
            }
        }
    }

    /// WhatsApp said "rate-overlimit": the gap doubles, the usync batch halves.
    pub fn limited(&mut self, picture: bool) {
        self.strikes = self.strikes.saturating_add(1);
        self.ok_streak = 0;
        if picture {
            self.picture_gap_ms =
                (self.picture_gap_ms * 2).min(PICTURE_GAP_MAX_MS.max(self.base.picture_gap_ms));
        } else {
            self.usync_gap_ms =
                (self.usync_gap_ms * 2).min(USYNC_GAP_MAX_MS.max(self.base.usync_gap_ms));
            self.batch = (self.batch / 2).max(USYNC_BATCH_MIN.min(self.base.usync_batch.max(1)));
        }
    }
}

// ---- the registry ------------------------------------------------------------------------------

struct Slot {
    rev: watch::Sender<u64>,
    task: Option<(String, AbortHandle)>,
}

impl Slot {
    fn new() -> Self {
        Self {
            rev: watch::Sender::new(0),
            task: None,
        }
    }
}

/// The people jobs' tasks and their watchers, per instance.
pub struct PeopleJobs {
    pace: JobPace,
    slots: Mutex<HashMap<InstanceId, Slot>>,
}

impl PeopleJobs {
    pub fn new(pace: JobPace) -> Self {
        Self {
            pace,
            slots: Mutex::new(HashMap::new()),
        }
    }

    pub fn pace(&self) -> JobPace {
        self.pace
    }

    fn slots(&self) -> std::sync::MutexGuard<'_, HashMap<InstanceId, Slot>> {
        self.slots.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// A watcher of the instance's job: it changes with every new revision.
    pub fn subscribe(&self, instance: &InstanceId) -> watch::Receiver<u64> {
        self.slots()
            .entry(instance.clone())
            .or_insert_with(Slot::new)
            .rev
            .subscribe()
    }

    /// Wakes the watchers: the job is now at `rev`.
    pub fn notify(&self, instance: &InstanceId, rev: u64) {
        let slots = self.slots();
        if let Some(slot) = slots.get(instance) {
            slot.rev.send_replace(rev);
        }
    }

    /// Whether a task works on job `job_id` of `instance`.
    pub fn is_running(&self, instance: &InstanceId, job_id: &str) -> bool {
        self.slots()
            .get(instance)
            .and_then(|s| s.task.as_ref())
            .is_some_and(|(id, handle)| id == job_id && !handle.is_finished())
    }

    /// Whether any task works for `instance`.
    pub fn any_running(&self, instance: &InstanceId) -> bool {
        self.slots()
            .get(instance)
            .and_then(|s| s.task.as_ref())
            .is_some_and(|(_, handle)| !handle.is_finished())
    }

    fn set_task(&self, instance: &InstanceId, job_id: &str, handle: AbortHandle) {
        let mut slots = self.slots();
        let slot = slots.entry(instance.clone()).or_insert_with(Slot::new);
        if let Some((_, old)) = slot.task.replace((job_id.to_owned(), handle)) {
            old.abort();
        }
    }

    /// Stops the instance's task, if any.
    pub fn abort(&self, instance: &InstanceId) {
        if let Some((_, handle)) = self.slots().get_mut(instance).and_then(|s| s.task.take()) {
            handle.abort();
        }
    }

    /// Stops every task (a test's gateway shutting down).
    pub fn abort_all(&self) {
        for slot in self.slots().values_mut() {
            if let Some((_, handle)) = slot.task.take() {
                handle.abort();
            }
        }
    }

    fn remove(&self, instance: &InstanceId) {
        if let Some(slot) = self.slots().remove(instance)
            && let Some((_, handle)) = slot.task
        {
            handle.abort();
        }
    }
}

/// Where job `job_id` of `instance` keeps its picture bytes.
pub fn spool_dir(state: &AppState, instance: &InstanceId, job_id: &str) -> PathBuf {
    state
        .config()
        .people_job_dir(instance.as_str())
        .join(job_id)
}

/// Deletes the spool folders of `instance` except the one of `keep`.
pub async fn clean_spool(state: &AppState, instance: &InstanceId, keep: Option<&str>) {
    let root = state.config().people_job_dir(instance.as_str());
    let keep = keep.map(str::to_owned);
    let _ = tokio::task::spawn_blocking(move || {
        let Ok(entries) = std::fs::read_dir(&root) else {
            return;
        };
        for entry in entries.flatten() {
            if keep.as_deref() != entry.file_name().to_str() {
                let _ = std::fs::remove_dir_all(entry.path());
            }
        }
    })
    .await;
}

/// Starts the task of job `job_id` unless one works on it already.
pub fn ensure(state: &AppState, instance: &InstanceId, job_id: &str) {
    if state.people().is_running(instance, job_id) {
        return;
    }
    let task = tokio::spawn(run(state.clone(), instance.clone(), job_id.to_owned()));
    state
        .people()
        .set_task(instance, job_id, task.abort_handle());
}

/// Gives every job that was working when the gateway stopped its task again. Returns how many.
pub async fn resume_all(state: &AppState) -> Result<usize, StoreError> {
    let working = state.store().people_working(state.now()).await?;
    for job in &working {
        log::info!(
            "instance={} people job resumed phase={} rev={}",
            job.instance,
            job.phase.as_str(),
            job.rev
        );
        ensure(state, &job.instance, &job.job_id);
    }
    Ok(working.len())
}

/// Cancels job `job_id` of `instance` (or drops it once finished): the task stops, the spool goes. Answers
/// already stored stay; they are what the next job resumes from.
pub async fn cancel(
    state: &AppState,
    instance: &InstanceId,
    job_id: &str,
) -> Result<CancelOutcome, StoreError> {
    let outcome = state
        .store()
        .people_cancel(instance.clone(), job_id.to_owned(), state.now())
        .await?;
    match &outcome {
        CancelOutcome::Cancelled(row) => {
            state.people().abort(instance);
            log::info!(
                "instance={instance} people job cancelled phase={}",
                row.phase.as_str()
            );
            state.people().notify(instance, row.rev);
            clean_spool(state, instance, None).await;
        }
        CancelOutcome::Dropped => {
            log::info!("instance={instance} people job dropped");
            clean_spool(state, instance, None).await;
        }
        CancelOutcome::Gone => {}
    }
    Ok(outcome)
}

/// Forgets the people job of a deleted instance: its task and its spool (the rows went with the instance).
pub async fn forget(state: &AppState, instance: &InstanceId) {
    state.people().remove(instance);
    let root = state.config().people_job_dir(instance.as_str());
    let _ = tokio::task::spawn_blocking(move || std::fs::remove_dir_all(root)).await;
}

/// The hourly housekeeping: expiry, old lookup answers and spool folders of jobs that are gone.
pub async fn tick(state: &AppState) {
    let jobs = match state.store().people_tick(state.now()).await {
        Ok(jobs) => jobs,
        Err(e) => {
            log::warn!("people tick failed: {e}");
            return;
        }
    };
    for (instance, job) in jobs {
        match &job {
            Some(job) => {
                if !job.state.is_active() {
                    state.people().abort(&instance);
                }
                state.people().notify(&instance, job.rev);
            }
            None => state.people().abort(&instance),
        }
        let keep = job
            .as_ref()
            .filter(|j| j.state.is_active() || j.state == PeopleJobState::Ready)
            .map(|j| j.job_id.as_str());
        clean_spool(state, &instance, keep).await;
    }
}

/// Runs [`tick`] every `every`.
pub fn spawn_tick(state: AppState, every: Duration) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut ticker = tokio::time::interval(every);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            ticker.tick().await;
            tick(&state).await;
        }
    })
}

// ---- records -----------------------------------------------------------------------------------

fn clean(name: Option<&str>) -> Option<String> {
    name.map(str::trim)
        .filter(|n| !n.is_empty())
        .map(str::to_owned)
}

/// What the gateway has for `number`, as a record, when the device needs it: when the record's hash differs from
/// the device's `h`, or it is on WhatsApp with a picture id other than the device's `p`. The flag says the
/// record wants its picture bytes (the second case). `None` without a lookup answer.
pub fn record_for(
    number: &NumberRow,
    lookup: Option<&LookupRow>,
    profile: Option<&WaProfile>,
) -> Option<(PeopleRecord, bool)> {
    let lookup = lookup?;
    let mut record = PeopleRecord {
        seq: number.seq,
        number: number.number(),
        on_whatsapp: lookup.on_whatsapp,
        ..Default::default()
    };
    if lookup.on_whatsapp {
        record.jid = Some(number.jid.clone());
        record.verified_name = clean(lookup.verified_name.as_deref());
        if let Some(profile) = profile {
            record.lid = clean(profile.lid.as_deref());
            record.push_name = clean(profile.push_name.as_deref());
            record.verified_name = clean(profile.verified_name.as_deref()).or(record.verified_name);
            record.picture_id = clean(profile.picture_id.as_deref());
        }
    }
    record.hash = people_hash(&record.hash_input());
    let picture = lookup.on_whatsapp
        && record.picture_id.is_some()
        && record.picture_id != number.have_picture;
    let changed = number.have_hash.as_deref() != Some(record.hash.as_str());
    (changed || picture).then_some((record, picture))
}

/// One record of a bundle and where its picture bytes are (`None`: none to send).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Planned {
    pub record: PeopleRecord,
    pub picture: Option<PathBuf>,
}

/// The records of job `job_id` after `after`, in order, from the gateway's stores now.
pub async fn plan_records(
    state: &AppState,
    instance: &InstanceId,
    job_id: &str,
    after: u32,
) -> Result<Vec<Planned>, StoreError> {
    let store = state.store();
    let numbers: Vec<NumberRow> = store
        .people_numbers(instance.clone(), job_id.to_owned())
        .await?
        .into_iter()
        .filter(|n| n.seq > after)
        .collect();
    let lookups = store
        .people_lookups(
            instance.clone(),
            numbers.iter().map(|n| n.jid.clone()).collect(),
        )
        .await?;
    let on: Vec<String> = numbers
        .iter()
        .filter(|n| lookups.get(&n.jid).is_some_and(|l| l.on_whatsapp))
        .map(|n| n.jid.clone())
        .collect();
    let profiles: HashMap<String, WaProfile> = store
        .profiles(instance.clone(), on)
        .await?
        .into_iter()
        .map(|p| (p.jid.clone(), p))
        .collect();
    let dir = spool_dir(state, instance, job_id);
    Ok(numbers
        .iter()
        .filter_map(|n| {
            let (mut record, wants) = record_for(n, lookups.get(&n.jid), profiles.get(&n.jid))?;
            let spooled = wants
                && n.pictured == PICTURED_OK
                && n.spooled_id.is_some()
                && n.spooled_id == record.picture_id;
            record.picture_missing = wants && !spooled;
            Some(Planned {
                record,
                picture: spooled.then(|| dir.join(format!("{}.bin", n.seq))),
            })
        })
        .collect())
}

// ---- the task ----------------------------------------------------------------------------------

enum Stop {
    /// The job no longer works (cancelled, expired, replaced, wiped): the task ends quietly.
    Gone,
    /// WhatsApp kept failing: the job fails.
    Failed(String),
    Store(StoreError),
}

impl From<StoreError> for Stop {
    fn from(e: StoreError) -> Self {
        Self::Store(e)
    }
}

fn random() -> u32 {
    let mut bytes = [0u8; 4];
    getrandom::fill(&mut bytes).map_or(0, |()| u32::from_le_bytes(bytes))
}

fn source_of(refused: crate::Refused) -> PeopleWaitSource {
    match refused {
        crate::Refused::Bucket { .. } => PeopleWaitSource::Gateway,
        crate::Refused::Cooldown { .. } => PeopleWaitSource::Whatsapp,
    }
}

/// Whether a number needs a lookup: no answer; `auto` also a negative older than 7 days and a positive older
/// than 30 days; `full` always.
pub fn needs_lookup(mode: PeopleSyncMode, lookup: Option<&LookupRow>, now_ms: i64) -> bool {
    const NEGATIVE_FRESH_MS: i64 = 7 * 24 * 3_600_000;
    const POSITIVE_FRESH_MS: i64 = 30 * 24 * 3_600_000;
    let Some(lookup) = lookup else {
        return true;
    };
    let age = now_ms.saturating_sub(lookup.checked_ms);
    match mode {
        PeopleSyncMode::Full => true,
        PeopleSyncMode::Auto if lookup.on_whatsapp => !(0..POSITIVE_FRESH_MS).contains(&age),
        PeopleSyncMode::Auto => !(0..NEGATIVE_FRESH_MS).contains(&age),
    }
}

struct Task {
    state: AppState,
    instance: InstanceId,
    job_id: String,
    mode: PeopleSyncMode,
    pacer: Pacer,
    phase: PeopleJobPhase,
    done: u32,
    total: u32,
    wait: Option<PeopleWait>,
    failures: u32,
    spool_bytes: u64,
    numbers: Vec<NumberRow>,
}

async fn run(state: AppState, instance: InstanceId, job_id: String) {
    let job = match state
        .store()
        .people_job(instance.clone(), state.now())
        .await
    {
        Ok(Some(job)) if job.job_id == job_id && job.state.is_active() => job,
        Ok(_) => return,
        Err(e) => {
            log::warn!("instance={instance} people job did not start: {e}");
            return;
        }
    };
    let mut task = Task {
        pacer: Pacer::new(state.people().pace()),
        state,
        instance,
        job_id,
        mode: job.mode,
        phase: job.phase,
        done: job.done,
        total: job.total,
        wait: None,
        failures: 0,
        spool_bytes: 0,
        numbers: Vec::new(),
    };
    match task.run(&job).await {
        Ok(()) => {}
        Err(Stop::Gone) => {
            log::info!("instance={} people job task ended", task.instance);
        }
        Err(Stop::Failed(message)) => {
            log::warn!("instance={} people job failed: {message}", task.instance);
            task.fail(job_error::WHATSAPP, &message).await;
        }
        Err(Stop::Store(e)) => {
            log::error!("instance={} people job store: {e}", task.instance);
            task.fail(job_error::INTERNAL, "the gateway could not store the job")
                .await;
        }
    }
}

impl Task {
    async fn run(&mut self, job: &JobRow) -> Result<(), Stop> {
        // A wait stored before a restart is slept out first: the in-memory cooldown is gone.
        if let Some(wait) = job.wait
            && job.state == PeopleJobState::Waiting
        {
            self.wait_until(wait.until_ms, wait.source).await?;
        }
        self.numbers = self
            .state
            .store()
            .people_numbers(self.instance.clone(), self.job_id.clone())
            .await?;
        self.lookups(job).await?;
        self.profiles(job).await?;
        self.pictures(job).await?;
        self.bundle().await
    }

    fn enter(&mut self, job: &JobRow, phase: PeopleJobPhase, work: usize) {
        let work = u32::try_from(work).unwrap_or(u32::MAX);
        let resumed = job.phase == phase;
        self.done = if resumed { job.done } else { 0 };
        if self.phase != phase {
            log::info!(
                "instance={} people job phase={} work={work}",
                self.instance,
                phase.as_str()
            );
        }
        self.phase = phase;
        self.total = self.done.saturating_add(work);
    }

    fn eta(&self) -> u64 {
        let left = u64::from(self.total.saturating_sub(self.done));
        let work = match self.phase {
            PeopleJobPhase::Lookup | PeopleJobPhase::Profiles => {
                left.div_ceil(self.pacer.batch() as u64) * self.pacer.gap_ms(false)
            }
            PeopleJobPhase::Pictures => left * self.pacer.gap_ms(true),
            _ => 0,
        };
        let wait = self.wait.map_or(0, |w| {
            u64::try_from(w.until_ms.saturating_sub(self.state.now())).unwrap_or(0)
        });
        work + wait
    }

    async fn commit_change(
        &mut self,
        change: JobChange,
        marks: NumberMarks,
        lookups: Vec<WaUser>,
    ) -> Result<(), Stop> {
        let row = self
            .state
            .store()
            .people_commit(
                self.instance.clone(),
                self.job_id.clone(),
                change,
                marks,
                lookups,
                self.state.now(),
            )
            .await?
            .ok_or(Stop::Gone)?;
        self.state.people().notify(&self.instance, row.rev);
        Ok(())
    }

    async fn commit(&mut self, marks: NumberMarks, lookups: Vec<WaUser>) -> Result<(), Stop> {
        let state = if self.wait.is_some() {
            PeopleJobState::Waiting
        } else {
            PeopleJobState::Running
        };
        let change = JobChange {
            state,
            phase: self.phase,
            done: self.done,
            total: self.total,
            eta_ms: Some(self.eta()),
            wait: self.wait,
            error: None,
            result: None,
        };
        self.commit_change(change, marks, lookups).await
    }

    async fn fail(&mut self, code: &str, message: &str) {
        let change = JobChange {
            state: PeopleJobState::Failed,
            phase: self.phase,
            done: self.done,
            total: self.total,
            eta_ms: None,
            wait: None,
            error: Some(PeopleJobError {
                code: code.to_owned(),
                message: message.to_owned(),
            }),
            result: None,
        };
        if self
            .commit_change(change, NumberMarks::default(), Vec::new())
            .await
            .is_ok()
        {
            clean_spool(&self.state, &self.instance, None).await;
        }
    }

    /// Waits until `until_ms` as `waiting` (stored, so a restart sleeps the rest); nothing is sent before.
    async fn wait_until(&mut self, until_ms: i64, source: PeopleWaitSource) -> Result<(), Stop> {
        let now = self.state.now();
        if until_ms > now {
            self.wait = Some(PeopleWait { until_ms, source });
            self.commit(NumberMarks::default(), Vec::new()).await?;
            log::info!(
                "instance={} people job waiting source={} wait_ms={}",
                self.instance,
                source.as_str(),
                until_ms - now
            );
            let ms = u64::try_from(until_ms - now).unwrap_or(0);
            self.state.clock().sleep(ms).await;
        }
        if self.wait.take().is_some() {
            self.commit(NumberMarks::default(), Vec::new()).await?;
        }
        Ok(())
    }

    /// Whether the job still works: checked before every request, so a job that was cancelled, replaced,
    /// expired or wiped with its account never sends one more.
    async fn alive(&self) -> Result<(), Stop> {
        let job = self
            .state
            .store()
            .people_job(self.instance.clone(), self.state.now())
            .await?;
        match job {
            Some(job) if job.job_id == self.job_id && job.state.is_active() => Ok(()),
            _ => Err(Stop::Gone),
        }
    }

    /// The gap after a request; the clock's sleep.
    async fn gap(&self, picture: bool) {
        let ms = self.pacer.jittered(picture, random());
        self.state.clock().sleep(ms).await;
    }

    /// What to do after a failed request: wait out a limit or a disconnected session (the same request is asked
    /// again), or retry another error up to [`RETRIES`] times.
    async fn failed(&mut self, error: DriverError, picture: bool) -> Result<(), Stop> {
        match error {
            DriverError::RateLimited { retry_after_s } => {
                self.pacer.limited(picture);
                let wait = self
                    .state
                    .note_upstream_limit(&self.instance, retry_after_s);
                log::warn!(
                    "instance={} people job rate limited source=whatsapp phase={} retry_after_ms={wait}",
                    self.instance,
                    self.phase.as_str()
                );
                let until = self
                    .state
                    .now()
                    .saturating_add(i64::try_from(wait).unwrap_or(i64::MAX));
                self.wait_until(until, PeopleWaitSource::Whatsapp).await
            }
            DriverError::NotConnected => {
                let retry = self.state.people().pace().not_connected_retry_ms;
                let until = self
                    .state
                    .now()
                    .saturating_add(i64::try_from(retry).unwrap_or(i64::MAX));
                self.wait_until(until, PeopleWaitSource::NotConnected).await
            }
            other => {
                self.failures += 1;
                if self.failures > RETRIES {
                    return Err(Stop::Failed(other.to_string()));
                }
                log::warn!(
                    "instance={} people job request failed phase={} try={}",
                    self.instance,
                    self.phase.as_str(),
                    self.failures
                );
                self.gap(picture).await;
                Ok(())
            }
        }
    }

    /// Waits for a refused allowance (the gateway's bucket or WhatsApp's cooldown).
    async fn refused(&mut self, refused: crate::Refused) -> Result<(), Stop> {
        let until = self
            .state
            .now()
            .saturating_add(i64::try_from(refused.retry_after_ms()).unwrap_or(i64::MAX));
        self.wait_until(until.max(self.state.now() + 1), source_of(refused))
            .await
    }

    async fn ask_lookup(&mut self, jids: &[String]) -> Result<Vec<WaUser>, Stop> {
        loop {
            self.alive().await?;
            let charged = match self.mode {
                PeopleSyncMode::Full => self
                    .state
                    .charge_lookups(&self.instance, jids.len())
                    .map(|()| (Vec::new(), jids.to_vec())),
                PeopleSyncMode::Auto => self.state.lookup_cached(&self.instance, jids),
            };
            let (mut hits, misses) = match charged {
                Ok(split) => split,
                Err(refused) => {
                    self.refused(refused).await?;
                    continue;
                }
            };
            if misses.is_empty() {
                return Ok(hits);
            }
            match self
                .state
                .driver()
                .lookup_users(&self.instance, misses)
                .await
            {
                Ok(fresh) => {
                    self.state.lookup_store(&self.instance, &fresh);
                    hits.extend(fresh);
                    return Ok(hits);
                }
                Err(error) => self.failed(error, false).await?,
            }
        }
    }

    async fn lookups(&mut self, job: &JobRow) -> Result<(), Stop> {
        let todo: Vec<NumberRow> = self.numbers.iter().filter(|n| !n.looked).cloned().collect();
        let known = self
            .state
            .store()
            .people_lookups(
                self.instance.clone(),
                todo.iter().map(|n| n.jid.clone()).collect(),
            )
            .await?;
        let now = self.state.now();
        let (work, fresh): (Vec<NumberRow>, Vec<NumberRow>) = todo
            .into_iter()
            .partition(|n| needs_lookup(self.mode, known.get(&n.jid), now));
        self.enter(job, PeopleJobPhase::Lookup, work.len());
        let marks = NumberMarks {
            looked: fresh.iter().map(|n| n.seq).collect(),
            ..Default::default()
        };
        self.commit(marks, Vec::new()).await?;
        let mut at = 0;
        while at < work.len() {
            let batch: Vec<&NumberRow> = work.iter().skip(at).take(self.pacer.batch()).collect();
            let jids: Vec<String> = batch.iter().map(|n| n.jid.clone()).collect();
            let mut users = self.ask_lookup(&jids).await?;
            // Every asked JID gets an answer (a driver answers each, `on_whatsapp: false` when unknown).
            for jid in &jids {
                if !users.iter().any(|u| &u.jid == jid) {
                    users.push(WaUser {
                        jid: jid.clone(),
                        on_whatsapp: false,
                        business: false,
                        verified_name: None,
                    });
                }
            }
            users.retain(|u| jids.contains(&u.jid));
            self.failures = 0;
            at += batch.len();
            self.done = self
                .done
                .saturating_add(u32::try_from(batch.len()).unwrap_or(u32::MAX));
            let marks = NumberMarks {
                looked: batch.iter().map(|n| n.seq).collect(),
                ..Default::default()
            };
            self.pacer.success(false);
            self.commit(marks, users).await?;
            if at < work.len() {
                self.gap(false).await;
            }
        }
        for number in &mut self.numbers {
            number.looked = true;
        }
        Ok(())
    }

    /// The numbers on WhatsApp by the stored answers.
    async fn on_whatsapp(&self) -> Result<HashMap<String, LookupRow>, Stop> {
        let all = self
            .state
            .store()
            .people_lookups(
                self.instance.clone(),
                self.numbers.iter().map(|n| n.jid.clone()).collect(),
            )
            .await?;
        Ok(all.into_iter().filter(|(_, l)| l.on_whatsapp).collect())
    }

    async fn profiles(&mut self, job: &JobRow) -> Result<(), Stop> {
        let on = self.on_whatsapp().await?;
        let todo: Vec<NumberRow> = self
            .numbers
            .iter()
            .filter(|n| !n.profiled && on.contains_key(&n.jid))
            .cloned()
            .collect();
        let known: HashMap<String, WaProfile> = self
            .state
            .store()
            .profiles(
                self.instance.clone(),
                todo.iter().map(|n| n.jid.clone()).collect(),
            )
            .await?
            .into_iter()
            .map(|p| (p.jid.clone(), p))
            .collect();
        let now = self.state.now();
        let full = self.mode == PeopleSyncMode::Full;
        let (work, fresh): (Vec<NumberRow>, Vec<NumberRow>) = todo
            .into_iter()
            .partition(|n| profile_is_stale(known.get(&n.jid), full, now));
        self.enter(job, PeopleJobPhase::Profiles, work.len());
        let marks = NumberMarks {
            profiled: fresh.iter().map(|n| n.seq).collect(),
            ..Default::default()
        };
        self.commit(marks, Vec::new()).await?;
        let mut at = 0;
        while at < work.len() {
            let batch: Vec<&NumberRow> = work.iter().skip(at).take(self.pacer.batch()).collect();
            let jids: Vec<String> = batch.iter().map(|n| n.jid.clone()).collect();
            let fetched = loop {
                self.alive().await?;
                if let Err(refused) = self.state.charge_profiles(&self.instance, jids.len()) {
                    self.refused(refused).await?;
                    continue;
                }
                match self
                    .state
                    .driver()
                    .profiles(&self.instance, jids.clone())
                    .await
                {
                    Ok(fetched) => break fetched,
                    Err(error) => self.failed(error, false).await?,
                }
            };
            let now = self.state.now();
            for profile in fetched {
                let jid = profile.jid.clone();
                // Straight to the store: no `profile` event (D1).
                self.state
                    .store()
                    .change_profile(
                        self.instance.clone(),
                        jid,
                        ProfileChange::Fetched(profile),
                        now,
                    )
                    .await?;
            }
            self.failures = 0;
            at += batch.len();
            self.done = self
                .done
                .saturating_add(u32::try_from(batch.len()).unwrap_or(u32::MAX));
            let marks = NumberMarks {
                profiled: batch.iter().map(|n| n.seq).collect(),
                ..Default::default()
            };
            self.pacer.success(false);
            self.commit(marks, Vec::new()).await?;
            if at < work.len() {
                self.gap(false).await;
            }
        }
        Ok(())
    }

    async fn spool(&mut self, seq: u32, bytes: &[u8]) -> bool {
        let dir = spool_dir(&self.state, &self.instance, &self.job_id);
        let written = async {
            tokio::fs::create_dir_all(&dir).await?;
            tokio::fs::write(dir.join(format!("{seq}.bin")), bytes).await
        }
        .await;
        match written {
            Ok(()) => {
                self.spool_bytes = self.spool_bytes.saturating_add(bytes.len() as u64);
                true
            }
            Err(e) => {
                log::warn!("instance={} people job spool failed: {e}", self.instance);
                false
            }
        }
    }

    /// Gets one picture: from the gateway's picture cache when it holds that id, else from WhatsApp. Answers the
    /// mark for its number (`pictured`, the spooled id) and whether WhatsApp was asked.
    async fn picture(
        &mut self,
        number: &NumberRow,
        id: &str,
    ) -> Result<(u8, Option<String>, bool), Stop> {
        let cache = MediaCache::new(
            self.state.config().pictures_dir(self.instance.as_str()),
            PICTURE_CACHE_BYTES,
        );
        if let Some(bytes) = cached(&cache, picture_key(&number.jid, false, id)).await {
            let ok = self.spool(number.seq, &bytes).await;
            return Ok(if ok {
                (PICTURED_OK, Some(id.to_owned()), false)
            } else {
                (PICTURED_FAILED, None, false)
            });
        }
        let fetched = loop {
            self.alive().await?;
            if let Err(refused) = self.state.charge_profiles(&self.instance, 1) {
                self.refused(refused).await?;
                continue;
            }
            match self
                .state
                .driver()
                .fetch_picture(&self.instance, number.jid.clone(), false, None)
                .await
            {
                Ok(fetched) => break Some(fetched),
                Err(error @ (DriverError::RateLimited { .. } | DriverError::NotConnected)) => {
                    self.failed(error, true).await?;
                }
                // One picture WhatsApp would not give does not fail the job.
                Err(_) => break None,
            }
        };
        self.pacer.success(true);
        let now = self.state.now();
        let store = self.state.store();
        Ok(match fetched {
            Some(PictureFetch::New { id, bytes }) => {
                let key = picture_key(&number.jid, false, &id);
                let copy = bytes.clone();
                let _ = tokio::task::spawn_blocking(move || cache.put(&key, &copy)).await;
                store
                    .change_profile(
                        self.instance.clone(),
                        number.jid.clone(),
                        ProfileChange::Picture(Some(id.clone())),
                        now,
                    )
                    .await?;
                if self.spool(number.seq, &bytes).await {
                    (PICTURED_OK, Some(id), true)
                } else {
                    (PICTURED_FAILED, None, true)
                }
            }
            Some(PictureFetch::None) => {
                store
                    .change_profile(
                        self.instance.clone(),
                        number.jid.clone(),
                        ProfileChange::Picture(None),
                        now,
                    )
                    .await?;
                (PICTURED_OK, None, true)
            }
            Some(PictureFetch::Unchanged) | None => (PICTURED_FAILED, None, true),
        })
    }

    async fn pictures(&mut self, job: &JobRow) -> Result<(), Stop> {
        let on = self.on_whatsapp().await?;
        let todo: Vec<NumberRow> = self
            .numbers
            .iter()
            .filter(|n| n.pictured == PICTURED_TODO && on.contains_key(&n.jid))
            .cloned()
            .collect();
        let profiles: HashMap<String, WaProfile> = self
            .state
            .store()
            .profiles(
                self.instance.clone(),
                todo.iter().map(|n| n.jid.clone()).collect(),
            )
            .await?
            .into_iter()
            .map(|p| (p.jid.clone(), p))
            .collect();
        let mut work: Vec<(NumberRow, String)> = Vec::new();
        let mut skip = NumberMarks::default();
        for number in todo {
            let id = profiles
                .get(&number.jid)
                .and_then(|p| clean(p.picture_id.as_deref()));
            match id {
                Some(id) if number.have_picture.as_deref() != Some(id.as_str()) => {
                    work.push((number, id));
                }
                // No picture costs nothing (usync is trusted); the device's own is current.
                _ => skip.pictured.push((number.seq, PICTURED_OK, None)),
            }
        }
        self.enter(job, PeopleJobPhase::Pictures, work.len());
        self.commit(skip, Vec::new()).await?;
        let last = work.len();
        for (index, (number, id)) in work.into_iter().enumerate() {
            let (pictured, spooled, asked_whatsapp) =
                if self.spool_bytes >= self.state.people().pace().spool_max_bytes {
                    // Over the spool budget: left for the next job (`picture_missing`).
                    (PICTURED_FAILED, None, false)
                } else {
                    self.picture(&number, &id).await?
                };
            self.done = self.done.saturating_add(1);
            let marks = NumberMarks {
                pictured: vec![(number.seq, pictured, spooled)],
                ..Default::default()
            };
            self.commit(marks, Vec::new()).await?;
            if asked_whatsapp && index + 1 < last {
                self.gap(true).await;
            }
        }
        Ok(())
    }

    async fn bundle(&mut self) -> Result<(), Stop> {
        self.phase = PeopleJobPhase::Bundle;
        let planned = plan_records(&self.state, &self.instance, &self.job_id, 0).await?;
        let mut result = PeopleJobResult {
            records: u32::try_from(planned.len()).unwrap_or(u32::MAX),
            ..Default::default()
        };
        for item in &planned {
            let mut record = item.record.clone();
            if let Some(path) = &item.picture
                && let Ok(meta) = tokio::fs::metadata(path).await
            {
                record.picture_bytes = Some(meta.len());
                result.pictures += 1;
                result.bytes += meta.len() + 1;
            }
            result.bytes += serde_json::to_vec(&record).map_or(0, |l| l.len() as u64) + 1;
        }
        let records = result.records;
        let change = JobChange {
            state: PeopleJobState::Ready,
            phase: PeopleJobPhase::Bundle,
            done: records,
            total: records,
            eta_ms: None,
            wait: None,
            error: None,
            result: Some(result),
        };
        self.commit_change(change, NumberMarks::default(), Vec::new())
            .await?;
        log::info!(
            "instance={} people job ready records={} pictures={} bytes={}",
            self.instance,
            result.records,
            result.pictures,
            result.bytes
        );
        Ok(())
    }
}

#[cfg(test)]
#[path = "people_job_tests.rs"]
mod tests;
