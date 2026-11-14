//! People sync v2 (`_bmad-output/plans/people-sync-v2.md`): the planner of a slow, resumable, observable sync of the
//! people cache ([`crate::people`]) from the owner's WhatsApp gateway.
//!
//! Pure and platform-free: no network, no clock, no randomness of its own. The caller (Kotlin's
//! `PeopleSyncRunner`, through the `people.sync_*` ops) asks [`next`] what to do, performs that gateway call and
//! hands the answer to [`report`], which commits it to the [`Book`]; the caller saves the book first and this
//! [`Checkpoint`] second (a crash in between repeats one batch: every report is an idempotent upsert).
//!
//! * The cursor is set based (D2): per run, the JIDs already looked up / profiled / pictured. Remaining work is the
//!   current targets minus those sets, so a contact list that changes between slices needs no invalidation.
//! * One pace for all usync traffic (lookups and profiles are both usync queries on WhatsApp's side), one for
//!   pictures; no burst. A 429 doubles the gap, halves the batch and backs off; successes recover slowly. The pace
//!   is persisted, so the next slice (or process) starts as careful as the last one ended.
//! * Interruptions are phases of the stored run ([`Phase`]); [`begin`] resumes them.

use std::{
    collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque},
    fs,
    io::{self, Write as _},
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};

use crate::people::{Book, FileInfo, Refresh, Update};

/// File name inside the app data directory, next to `people.json`.
pub const FILE: &str = "people-sync.json";
pub const SCHEMA_VERSION: u32 = 1;
/// A file bigger than this is not read back.
pub const MAX_FILE_BYTES: usize = 2 * 1024 * 1024;

// ---- pacing ------------------------------------------------------------------------------------
//
// WhatsApp answered `429 rate-overlimit` to two 50-JID profile batches seven seconds apart, right after a lookup
// stage had spent the same (usync) allowance. Its real budget is unknown, so these are deliberately conservative
// first guesses, calibrated from the developer-mode logs later:
// * a third of the batch that failed, with a 20 s gap shared by lookups and profiles: 45 JIDs a minute, no burst;
// * one picture IQ every 3 s;
// * every gap is jittered by ±20 % so requests are not periodic;
// * a 429 doubles the gap (cap 5 min for usync, 1 min for pictures), halves the usync batch (floor 5) and waits a
//   minute, doubling per strike up to 30 min, never less than the server's hint, plus up to 25 % (upwards only);
// * a wait that is short (90 s) sleeps inline, a longer one, or the third strike, ends the slice;
// * five successes in a row clear the strikes; from then every success brings the gap 10 % back towards the base
//   and every tenth grows the batch by 5, never above the base pace.

pub const USYNC_BATCH: u32 = 15;
pub const USYNC_BATCH_MIN: u32 = 5;
pub const USYNC_GAP_MS: i64 = 20_000;
pub const USYNC_GAP_MAX_MS: i64 = 300_000;
pub const PICTURE_GAP_MS: i64 = 3_000;
pub const PICTURE_GAP_MAX_MS: i64 = 60_000;
pub const JITTER_PCT: i64 = 20;
pub const BACKOFF_BASE_MS: i64 = 60_000;
pub const BACKOFF_MAX_MS: i64 = 1_800_000;
pub const BACKOFF_JITTER_PCT: i64 = 25;
pub const INLINE_WAIT_MAX_MS: i64 = 90_000;
pub const MAX_STRIKES: u32 = 3;
/// Successes in a row that clear the strikes.
pub const RECOVER_AFTER: u32 = 5;
/// Every this many successes (once recovered) the usync batch grows by [`BATCH_STEP`].
pub const GROW_EVERY: u32 = 10;
pub const BATCH_STEP: u32 = 5;
/// WorkManager stops a worker at 10 min; a background slice ends before that.
pub const WORKER_SLICE_MS: i64 = 8 * 60_000;
/// Gap between background slices when nothing is throttled.
pub const CONTINUE_DELAY_MS: i64 = 60_000;
/// Budget of a live picture pass.
pub const LIVE_SLICE_MS: i64 = 30_000;

// ---- work limits and freshness -------------------------------------------------------------------

/// A run older than this is not resumable; the next one starts afresh (incrementally).
pub const RESUME_MAX_AGE_MS: i64 = 24 * 3_600_000;
/// An incremental run does not ask again about a number WhatsApp said is not on it for this long.
pub const NEGATIVE_TTL_MS: i64 = 7 * 24 * 3_600_000;
/// An incremental run does not ask again for a profile answered this recently (the gateway's `PROFILE_TTL_MS`).
pub const PROFILE_FRESH_MS: i64 = 24 * 3_600_000;
/// Numbers asked about per run (the caller orders them: recent callers first).
pub const MAX_NUMBERS: usize = 2_000;
/// Pictures fetched per run (or live pass).
pub const MAX_PICTURES: usize = 300;
/// Failed pictures that end the picture stage.
pub const MAX_FAILURES: u32 = 5;
/// Remembered negative lookups.
pub const MAX_NEGATIVES: usize = 5_000;
/// A profile an (old) gateway leaves unanswered this many times in one run is given up for that run.
pub const MAX_UNANSWERED: u8 = 3;

const PHONE_SUFFIX: &str = "@s.whatsapp.net";
const LOG: &str = "rovsti::sync";

// ---- checkpoint ----------------------------------------------------------------------------------

/// Everything the sync remembers between slices, in `people-sync.json`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Checkpoint {
    pub schema_version: u32,
    /// Which link the file belongs to; a different link resets everything below.
    #[serde(default)]
    pub link: Option<LinkId>,
    /// The resumable or running run.
    #[serde(default)]
    pub run: Option<Run>,
    /// What we learned about the limit; survives runs.
    #[serde(default)]
    pub pace: Pace,
    /// JID -> when WhatsApp said "not on WhatsApp" (ms).
    #[serde(default)]
    pub negatives: BTreeMap<String, i64>,
    /// JID -> when its profile was last really answered (ms).
    #[serde(default)]
    pub profiled_ms: BTreeMap<String, i64>,
    /// The last finished run, for the settings row.
    #[serde(default)]
    pub last: Option<Finished>,
}

impl Default for Checkpoint {
    fn default() -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            link: None,
            run: None,
            pace: Pace::default(),
            negatives: BTreeMap::new(),
            profiled_ms: BTreeMap::new(),
            last: None,
        }
    }
}

/// The WhatsApp link a checkpoint belongs to: the instance and a fingerprint of the gateway URL.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct LinkId {
    pub instance_id: String,
    /// FNV-1a 64 of the gateway URL, hex: stable across builds, and the URL itself is not stored twice.
    pub gateway: String,
}

impl LinkId {
    pub fn new(instance_id: &str, gateway_url: &str) -> Self {
        let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
        for byte in gateway_url.trim().trim_end_matches('/').bytes() {
            hash ^= u64::from(byte);
            hash = hash.wrapping_mul(0x0100_0000_01b3);
        }
        Self {
            instance_id: instance_id.to_owned(),
            gateway: format!("{hash:016x}"),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    /// "Sync Now": asks about everything again (negatives and fresh profiles included).
    Full,
    /// The worker and the foreground trigger: skips fresh negatives and fresh profiles.
    Incremental,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    Lookup,
    Profiles,
    Pictures,
}

impl Stage {
    fn name(self) -> &'static str {
        match self {
            Self::Lookup => "lookup",
            Self::Profiles => "profiles",
            Self::Pictures => "pictures",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LimitSource {
    /// WhatsApp's own 429 (or the gateway's cooldown after one).
    Whatsapp,
    /// The gateway's token bucket.
    Gateway,
    /// An old gateway answered 200 with profiles it never fetched (D6).
    GatewayLegacy,
}

impl LimitSource {
    fn name(self) -> &'static str {
        match self {
            Self::Whatsapp => "whatsapp",
            Self::Gateway => "gateway",
            Self::GatewayLegacy => "gateway_legacy",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Interrupt {
    ProcessDeath,
    Network,
    WaDisconnected,
    SliceBudget,
    Cancelled,
    LinkNotReady,
}

impl Interrupt {
    fn name(self) -> &'static str {
        match self {
            Self::ProcessDeath => "process_death",
            Self::Network => "network",
            Self::WaDisconnected => "wa_disconnected",
            Self::SliceBudget => "slice_budget",
            Self::Cancelled => "cancelled",
            Self::LinkNotReady => "link_not_ready",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailReason {
    /// The gateway rejected the connect key.
    Auth,
    /// The core could not read or write its files.
    Core,
    /// The gateway answered with an error.
    Gateway,
    Other,
}

/// Where a run stands.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Phase {
    Running,
    /// A rate limit: continues by itself after `until_ms`.
    Waiting {
        until_ms: i64,
        source: LimitSource,
    },
    /// Resumable.
    Interrupted {
        reason: Interrupt,
    },
    /// Resumable once the cause is gone.
    Failed {
        reason: FailReason,
        message: String,
    },
}

/// Done and total of one stage, for "x/y".
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Progress {
    pub done: u32,
    pub total: u32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Totals {
    pub lookup: Progress,
    pub profiles: Progress,
    pub pictures: Progress,
}

impl Totals {
    fn of(&self, stage: Stage) -> Progress {
        match stage {
            Stage::Lookup => self.lookup,
            Stage::Profiles => self.profiles,
            Stage::Pictures => self.pictures,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Stats {
    pub batches: u32,
    pub people_found: u32,
    pub downloaded: u32,
    pub dropped: u32,
    pub waits_ms: i64,
    pub rate_limits: u32,
}

/// One sync run, possibly over several slices.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Run {
    /// When it started (ms).
    pub id: i64,
    pub mode: Mode,
    pub phase: Phase,
    pub stage: Stage,
    /// Asked this run (found or not).
    #[serde(default)]
    pub looked: BTreeSet<String>,
    #[serde(default)]
    pub profiled: BTreeSet<String>,
    /// Answered this run (image, unchanged, none, failed).
    #[serde(default)]
    pub pictured: BTreeSet<String>,
    /// Profiles an old gateway left unanswered, and how often.
    #[serde(default)]
    pub unanswered: BTreeMap<String, u8>,
    #[serde(default)]
    pub picture_failures: u32,
    #[serde(default)]
    pub totals: Totals,
    #[serde(default)]
    pub stats: Stats,
    #[serde(default)]
    pub heartbeat_ms: i64,
    #[serde(default)]
    pub slices: u32,
}

impl Run {
    fn new(now_ms: i64, mode: Mode, stage: Stage) -> Self {
        Self {
            id: now_ms,
            mode,
            phase: Phase::Running,
            stage,
            looked: BTreeSet::new(),
            profiled: BTreeSet::new(),
            pictured: BTreeSet::new(),
            unanswered: BTreeMap::new(),
            picture_failures: 0,
            totals: Totals::default(),
            stats: Stats::default(),
            heartbeat_ms: now_ms,
            slices: 1,
        }
    }

    fn expired(&self, now_ms: i64) -> bool {
        now_ms - self.id > RESUME_MAX_AGE_MS
    }
}

/// What the sync learned about WhatsApp's limit.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Pace {
    pub usync_gap_ms: i64,
    pub usync_batch: u32,
    /// No usync request before this.
    pub usync_next_ms: i64,
    pub picture_gap_ms: i64,
    pub picture_next_ms: i64,
    pub strikes: u32,
    pub ok_streak: u32,
    /// No request at all before this (a rate limit's wait).
    pub not_before_ms: i64,
    /// Who set `not_before_ms`.
    pub source: Option<LimitSource>,
}

impl Default for Pace {
    fn default() -> Self {
        Self {
            usync_gap_ms: USYNC_GAP_MS,
            usync_batch: USYNC_BATCH,
            usync_next_ms: 0,
            picture_gap_ms: PICTURE_GAP_MS,
            picture_next_ms: 0,
            strikes: 0,
            ok_streak: 0,
            not_before_ms: 0,
            source: None,
        }
    }
}

/// The last finished run.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Finished {
    pub at_ms: i64,
    /// People on WhatsApp in the book.
    pub people: u32,
    pub downloaded: u32,
    pub duration_ms: i64,
}

// ---- storage -------------------------------------------------------------------------------------

fn path(dir: &Path) -> PathBuf {
    dir.join(FILE)
}

/// The saved checkpoint. Never fails: a missing, oversized, damaged or newer-schema file reads as the default.
pub fn load(dir: &Path) -> Checkpoint {
    let Ok(bytes) = fs::read(path(dir)) else {
        return Checkpoint::default();
    };
    if bytes.len() > MAX_FILE_BYTES {
        log::warn!(target: LOG, "checkpoint_unreadable reason=oversized bytes={}", bytes.len());
        return Checkpoint::default();
    }
    match serde_json::from_slice::<Checkpoint>(&bytes) {
        Ok(cp) if cp.schema_version > SCHEMA_VERSION => {
            log::warn!(target: LOG, "checkpoint_unreadable reason=newer_schema version={}", cp.schema_version);
            Checkpoint::default()
        }
        Ok(cp) => cp,
        Err(error) => {
            log::warn!(target: LOG, "checkpoint_unreadable reason=damaged error={error}");
            Checkpoint::default()
        }
    }
}

/// Writes the checkpoint through a temporary file (synced, then renamed), so a crash never leaves half a file.
pub fn save(dir: &Path, cp: &Checkpoint) -> io::Result<()> {
    let mut copy = cp.clone();
    copy.schema_version = SCHEMA_VERSION;
    let json = serde_json::to_vec(&copy).map_err(io::Error::other)?;
    if json.len() > MAX_FILE_BYTES {
        return Err(io::Error::other("the sync checkpoint would be too big"));
    }
    fs::create_dir_all(dir)?;
    let temp = dir.join(format!("{FILE}.tmp"));
    {
        let mut file = fs::File::create(&temp)?;
        file.write_all(&json)?;
        file.sync_all()?;
    }
    fs::rename(&temp, path(dir))
}

/// A stored `Running` run in a process that just started belongs to a process that died: it becomes
/// `Interrupted(ProcessDeath)`. True when it changed something.
pub fn after_process_start(cp: &mut Checkpoint) -> bool {
    match cp.run.as_mut() {
        Some(run) if run.phase == Phase::Running => {
            run.phase = Phase::Interrupted {
                reason: Interrupt::ProcessDeath,
            };
            log::info!(target: LOG, "run_interrupted reason=process_death stage={}", run.stage.name());
            true
        }
        _ => false,
    }
}

// ---- planner API ---------------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Start {
    /// "Continue Where It Left Off": the stored run, or refused.
    Resume,
    /// "Sync Now" / "Start Over": a new full run.
    Restart,
    /// The worker and the foreground trigger: resume if resumable, else a new incremental run.
    Auto,
    /// The live picture pass: pictures only, under the shared pace; never creates or changes the stored run.
    LivePictures,
}

impl Start {
    fn name(self) -> &'static str {
        match self {
            Self::Resume => "resume",
            Self::Restart => "restart",
            Self::Auto => "auto",
            Self::LivePictures => "live_pictures",
        }
    }
}

/// Why [`begin`] did not start.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Refused {
    /// WhatsApp's penalty runs until then (D8).
    NotBefore {
        until_ms: i64,
    },
    NothingToResume,
}

/// What the caller is doing for the planner right now.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Inflight {
    Lookup,
    Profiles,
    PlanPictures,
    Picture(String),
}

/// One slice in memory: the targets of this slice and the picture plan.
#[derive(Debug)]
pub struct Session {
    live: bool,
    /// Phone JIDs in the caller's order (recent callers first).
    targets: Vec<String>,
    numbers: HashMap<String, String>,
    deadline_ms: Option<i64>,
    /// After `PlanPictures`: the pictures to fetch.
    queue: Option<VecDeque<Refresh>>,
    /// The picture file names that were on disk at plan time.
    files: HashSet<String>,
    inflight: Option<Inflight>,
    /// The jittered gap drawn for the request in flight.
    gap_ms: i64,
    stopped: Option<Stopped>,
    /// A live pass keeps its own run here; the stored run waits in `held` while a call runs.
    own: Option<Run>,
    held: Option<Run>,
}

impl Session {
    pub fn is_live(&self) -> bool {
        self.live
    }

    /// For a live pass, swaps its own run into the checkpoint for the duration of a call.
    fn enter(&mut self, cp: &mut Checkpoint) {
        if self.live {
            self.held = std::mem::replace(&mut cp.run, self.own.take());
        }
    }

    fn leave(&mut self, cp: &mut Checkpoint) {
        if self.live {
            self.own = std::mem::replace(&mut cp.run, self.held.take());
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Why {
    Pace,
    RateLimit,
}

/// How a slice ended.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "outcome", rename_all = "snake_case")]
pub enum Stopped {
    Done,
    Waiting { until_ms: i64, source: LimitSource },
    Interrupted { reason: Interrupt },
    Failed { reason: FailReason, message: String },
}

/// What the caller should do next.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Step {
    /// `wa.lookup_users` for these phone JIDs.
    Lookup {
        jids: Vec<String>,
    },
    /// `wa.profiles` for these JIDs.
    Profiles {
        jids: Vec<String>,
    },
    /// List the picture files on disk and answer [`Report::Files`].
    PlanPictures,
    /// `wa.picture` for this JID; `known_id` when the file of that id is on disk (an unchanged one costs no bytes).
    Picture {
        jid: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        known_id: Option<String>,
    },
    /// Wait this long, then ask again.
    Sleep {
        ms: i64,
        why: Why,
    },
    Stop {
        stopped: Stopped,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorKind {
    /// No connection, timeout: resumable when the network is back.
    Network,
    /// The gateway says WhatsApp is not connected (409 `not_connected`).
    NotConnected,
    Auth,
    Core,
    /// Any other gateway error.
    Gateway,
    Other,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PictureResult {
    /// The picture was stored as `file` (`avatars::file_name(jid, picture_id)`).
    Stored { picture_id: String, file: String },
    /// The known one is still current.
    Unchanged,
    /// No picture (or hidden).
    None,
    /// Could not be fetched or stored.
    Failed,
}

/// What the caller got for the step it performed.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Report {
    /// `found` are the people on WhatsApp among `asked` (number and `on_whatsapp` are filled in when missing).
    Lookup {
        asked: Vec<String>,
        #[serde(default)]
        found: Vec<Update>,
    },
    /// `answered` profiles become updates; `unanswered` ones (an old gateway's `fetched_ms: 0`) are not done.
    Profiles {
        asked: Vec<String>,
        #[serde(default)]
        answered: Vec<Update>,
        #[serde(default)]
        unanswered: Vec<String>,
    },
    /// The picture files on disk, for `people::Book::plan` (the planner answers what to delete).
    Files {
        #[serde(default)]
        files: Vec<FileInfo>,
        max_bytes: u64,
    },
    Picture {
        jid: String,
        result: PictureResult,
    },
    RateLimited {
        #[serde(default)]
        retry_after_ms: Option<i64>,
        source: LimitSource,
    },
    Error {
        error: ErrorKind,
        #[serde(default)]
        message: String,
    },
    Cancelled,
}

/// What a report committed.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Committed {
    /// People added or changed in the book.
    pub changed: usize,
    /// Files (names or paths) the caller deletes.
    pub drop_files: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stage: Option<Stage>,
    pub progress: Progress,
    /// The book changed and must be saved (before the checkpoint).
    #[serde(skip)]
    pub book_dirty: bool,
}

/// What the settings row shows.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Status {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub run: Option<RunStatus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last: Option<Finished>,
    /// WhatsApp's penalty runs until then: both buttons wait (D8).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub not_before_ms: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RunStatus {
    pub id: i64,
    pub mode: Mode,
    pub stage: Stage,
    pub phase: Phase,
    /// Of the current stage.
    pub progress: Progress,
    /// "Continue" can pick it up (it is not running).
    pub resumable: bool,
    pub heartbeat_ms: i64,
    pub stats: Stats,
}

/// `+905321234567` -> `905321234567@s.whatsapp.net`; `None` when it is not a plausible number.
pub fn jid_of(e164: &str) -> Option<String> {
    let digits = e164.trim().trim_start_matches('+');
    ((7..=15).contains(&digits.len()) && digits.bytes().all(|b| b.is_ascii_digit()))
        .then(|| format!("{digits}{PHONE_SUFFIX}"))
}

/// Starts a slice. Applies the link rule, prunes what no longer belongs to anybody, and resumes or creates a run
/// as `start` says. `numbers` are E.164 numbers in priority order.
pub fn begin(
    cp: &mut Checkpoint,
    book: &Book,
    link: LinkId,
    numbers: &[String],
    start: Start,
    slice_ms: Option<i64>,
    now_ms: i64,
) -> Result<Session, Refused> {
    if cp.link.as_ref() != Some(&link) {
        if cp.link.is_some() {
            log::info!(target: LOG, "link_changed reset=run,negatives,profiled,pace");
            cp.run = None;
            cp.negatives.clear();
            cp.profiled_ms.clear();
            cp.pace = Pace::default();
            cp.last = None;
        }
        cp.link = Some(link);
    }

    let mut targets = Vec::new();
    let mut numbers_of = HashMap::new();
    for number in numbers {
        if targets.len() >= MAX_NUMBERS {
            break;
        }
        if let Some(jid) = jid_of(number)
            && !numbers_of.contains_key(&jid)
        {
            numbers_of.insert(jid.clone(), number.trim().to_owned());
            targets.push(jid);
        }
    }
    if start != Start::LivePictures {
        prune(cp, book, &numbers_of);
    }

    if cp.run.as_ref().is_some_and(|r| r.expired(now_ms)) {
        log::info!(target: LOG, "run_expired");
        cp.run = None;
    }
    if let Some(Run {
        phase: Phase::Waiting { until_ms, .. },
        ..
    }) = &cp.run
        && *until_ms > now_ms
    {
        return Err(Refused::NotBefore {
            until_ms: *until_ms,
        });
    }
    if cp.pace.not_before_ms > now_ms {
        return Err(Refused::NotBefore {
            until_ms: cp.pace.not_before_ms,
        });
    }

    let mut session = Session {
        live: start == Start::LivePictures,
        targets,
        numbers: numbers_of,
        deadline_ms: slice_ms.map(|ms| now_ms + ms.max(0)),
        queue: None,
        files: HashSet::new(),
        inflight: None,
        gap_ms: 0,
        stopped: None,
        own: None,
        held: None,
    };
    let resumed = match start {
        Start::LivePictures => {
            session.own = Some(Run::new(now_ms, Mode::Incremental, Stage::Pictures));
            false
        }
        Start::Resume if cp.run.is_none() => return Err(Refused::NothingToResume),
        Start::Resume => true,
        Start::Auto if cp.run.is_some() => true,
        Start::Auto => {
            cp.run = Some(Run::new(now_ms, Mode::Incremental, Stage::Lookup));
            false
        }
        Start::Restart => {
            cp.run = Some(Run::new(now_ms, Mode::Full, Stage::Lookup));
            false
        }
    };
    if resumed && let Some(run) = cp.run.as_mut() {
        run.phase = Phase::Running;
        run.slices = run.slices.saturating_add(1);
        run.heartbeat_ms = now_ms;
        let wanted: HashSet<&String> = session.targets.iter().collect();
        run.looked.retain(|jid| wanted.contains(jid));
    }
    session.enter(cp);
    if let Some(run) = cp.run.as_mut() {
        refresh_totals(run, &session, book, &cp.negatives, &cp.profiled_ms, now_ms);
        log::info!(
            target: LOG,
            "run_begin id={} mode={} start={} stage={} targets={} to_lookup={} to_profile={} slice={}",
            run.id,
            match run.mode {
                Mode::Full => "full",
                Mode::Incremental => "incremental",
            },
            start.name(),
            run.stage.name(),
            session.targets.len(),
            run.totals.lookup.total - run.totals.lookup.done,
            run.totals.profiles.total - run.totals.profiles.done,
            run.slices,
        );
    }
    session.leave(cp);
    Ok(session)
}

/// Negatives only for current targets (newest kept), profile times only for people in the book.
fn prune(cp: &mut Checkpoint, book: &Book, targets: &HashMap<String, String>) {
    cp.negatives.retain(|jid, _| targets.contains_key(jid));
    if cp.negatives.len() > MAX_NEGATIVES {
        let mut by_age: Vec<(i64, String)> = cp
            .negatives
            .iter()
            .map(|(jid, at)| (*at, jid.clone()))
            .collect();
        by_age.sort();
        let excess = by_age.len() - MAX_NEGATIVES;
        for (_, jid) in by_age.into_iter().take(excess) {
            cp.negatives.remove(&jid);
        }
    }
    let known: HashSet<&str> = book.people.iter().map(|p| p.jid.as_str()).collect();
    cp.profiled_ms.retain(|jid, _| known.contains(jid.as_str()));
}

/// Numbers still to look up, in order.
fn lookup_work<'a>(
    run: &Run,
    s: &'a Session,
    book: &Book,
    negatives: &BTreeMap<String, i64>,
    now_ms: i64,
) -> Vec<&'a String> {
    s.targets
        .iter()
        .filter(|jid| !run.looked.contains(*jid))
        .filter(|jid| !book.lookup(jid).is_some_and(|p| p.on_whatsapp))
        .filter(|jid| {
            run.mode == Mode::Full
                || !negatives
                    .get(*jid)
                    .is_some_and(|at| now_ms - at < NEGATIVE_TTL_MS)
        })
        .collect()
}

/// People on WhatsApp among the targets whose profile is still to ask for.
fn profile_work<'a>(
    run: &Run,
    s: &Session,
    book: &'a Book,
    profiled_ms: &BTreeMap<String, i64>,
    now_ms: i64,
) -> Vec<&'a String> {
    let wanted: HashSet<&String> = s.targets.iter().collect();
    book.people
        .iter()
        .filter(|p| p.on_whatsapp && wanted.contains(&p.jid))
        .map(|p| &p.jid)
        .filter(|jid| !run.profiled.contains(*jid))
        .filter(|jid| {
            run.mode == Mode::Full
                || !profiled_ms
                    .get(*jid)
                    .is_some_and(|at| now_ms - at < PROFILE_FRESH_MS)
        })
        .collect()
}

fn picture_work<'a>(run: &Run, s: &'a Session) -> Vec<&'a Refresh> {
    s.queue
        .iter()
        .flatten()
        .filter(|r| !run.pictured.contains(&r.jid))
        .collect()
}

fn count(n: usize) -> u32 {
    u32::try_from(n).unwrap_or(u32::MAX)
}

fn refresh_totals(
    run: &mut Run,
    s: &Session,
    book: &Book,
    negatives: &BTreeMap<String, i64>,
    profiled_ms: &BTreeMap<String, i64>,
    now_ms: i64,
) {
    if run.stage == Stage::Lookup || run.totals.lookup.total == 0 {
        let left = lookup_work(run, s, book, negatives, now_ms).len();
        let wanted: HashSet<&String> = s.targets.iter().collect();
        let done = run.looked.iter().filter(|j| wanted.contains(j)).count();
        run.totals.lookup = Progress {
            done: count(done),
            total: count(done + left),
        };
    }
    if run.stage <= Stage::Profiles {
        let left = profile_work(run, s, book, profiled_ms, now_ms).len();
        let done = run.profiled.len();
        run.totals.profiles = Progress {
            done: count(done),
            total: count(done + left),
        };
    }
    if s.queue.is_some() {
        let left = picture_work(run, s).len();
        let done = run.pictured.len();
        run.totals.pictures = Progress {
            done: count(done),
            total: count((done + left).min(MAX_PICTURES.max(done))),
        };
    }
}

/// `gap * (100 ± JITTER_PCT) / 100`.
fn jitter(gap_ms: i64, rand: u32) -> i64 {
    let spread = i64::from(rand) % (2 * JITTER_PCT + 1);
    gap_ms * (100 - JITTER_PCT + spread) / 100
}

/// What to do now. Every call may change the checkpoint (phase, stage, totals); the caller saves it.
pub fn next(cp: &mut Checkpoint, s: &mut Session, book: &Book, now_ms: i64, rand: u32) -> Step {
    s.enter(cp);
    let step = next_step(cp, s, book, now_ms, rand);
    s.leave(cp);
    if let Step::Stop { stopped } = &step {
        s.stopped = Some(stopped.clone());
    }
    step
}

fn next_step(cp: &mut Checkpoint, s: &mut Session, book: &Book, now_ms: i64, rand: u32) -> Step {
    if let Some(stopped) = &s.stopped {
        return Step::Stop {
            stopped: stopped.clone(),
        };
    }
    let Some(run) = cp.run.as_mut() else {
        return Step::Stop {
            stopped: Stopped::Done,
        };
    };
    match &run.phase {
        Phase::Running => {}
        Phase::Waiting { until_ms, source } if *until_ms > now_ms => {
            return Step::Stop {
                stopped: Stopped::Waiting {
                    until_ms: *until_ms,
                    source: *source,
                },
            };
        }
        Phase::Waiting { .. } => run.phase = Phase::Running,
        Phase::Interrupted { reason } => {
            return Step::Stop {
                stopped: Stopped::Interrupted { reason: *reason },
            };
        }
        Phase::Failed { reason, message } => {
            return Step::Stop {
                stopped: Stopped::Failed {
                    reason: *reason,
                    message: message.clone(),
                },
            };
        }
    }
    if s.deadline_ms.is_some_and(|d| now_ms >= d) {
        return interrupt(run, Interrupt::SliceBudget);
    }
    if cp.pace.not_before_ms > now_ms {
        let ms = cp.pace.not_before_ms - now_ms;
        let source = cp.pace.source.unwrap_or(LimitSource::Whatsapp);
        return sleep_or_stop(run, s, now_ms, ms, Why::RateLimit, source);
    }
    loop {
        refresh_totals(run, s, book, &cp.negatives, &cp.profiled_ms, now_ms);
        match run.stage {
            Stage::Lookup => {
                let work = lookup_work(run, s, book, &cp.negatives, now_ms);
                if work.is_empty() {
                    advance(run, Stage::Profiles);
                    continue;
                }
                if cp.pace.usync_next_ms > now_ms {
                    let ms = cp.pace.usync_next_ms - now_ms;
                    return sleep_or_stop(run, s, now_ms, ms, Why::Pace, LimitSource::Whatsapp);
                }
                let jids: Vec<String> = work
                    .into_iter()
                    .take(cp.pace.usync_batch.max(1) as usize)
                    .cloned()
                    .collect();
                s.inflight = Some(Inflight::Lookup);
                s.gap_ms = jitter(cp.pace.usync_gap_ms, rand);
                return Step::Lookup { jids };
            }
            Stage::Profiles => {
                let work = profile_work(run, s, book, &cp.profiled_ms, now_ms);
                if work.is_empty() {
                    advance(run, Stage::Pictures);
                    continue;
                }
                if cp.pace.usync_next_ms > now_ms {
                    let ms = cp.pace.usync_next_ms - now_ms;
                    return sleep_or_stop(run, s, now_ms, ms, Why::Pace, LimitSource::Whatsapp);
                }
                let jids: Vec<String> = work
                    .into_iter()
                    .take(cp.pace.usync_batch.max(1) as usize)
                    .cloned()
                    .collect();
                s.inflight = Some(Inflight::Profiles);
                s.gap_ms = jitter(cp.pace.usync_gap_ms, rand);
                return Step::Profiles { jids };
            }
            Stage::Pictures => {
                if s.queue.is_none() {
                    s.inflight = Some(Inflight::PlanPictures);
                    return Step::PlanPictures;
                }
                let work = picture_work(run, s);
                let capped = run.pictured.len() >= MAX_PICTURES;
                let failed = run.picture_failures >= MAX_FAILURES;
                let Some(first) = work.first().filter(|_| !capped && !failed) else {
                    return finish(cp, s, book, now_ms);
                };
                if cp.pace.picture_next_ms > now_ms {
                    let ms = cp.pace.picture_next_ms - now_ms;
                    return sleep_or_stop(run, s, now_ms, ms, Why::Pace, LimitSource::Whatsapp);
                }
                let jid = first.jid.clone();
                let known_id = first.picture_id.clone().filter(|id| {
                    book.lookup(&jid).is_some_and(|p| {
                        !p.picture_stale
                            && p.picture_id.as_deref() == Some(id.as_str())
                            && p.picture_file.as_ref().is_some_and(|f| s.files.contains(f))
                    })
                });
                s.inflight = Some(Inflight::Picture(jid.clone()));
                s.gap_ms = jitter(cp.pace.picture_gap_ms, rand);
                return Step::Picture { jid, known_id };
            }
        }
    }
}

fn advance(run: &mut Run, stage: Stage) {
    log::info!(
        target: LOG,
        "stage_end stage={} done={} total={}",
        run.stage.name(),
        run.totals.of(run.stage).done,
        run.totals.of(run.stage).total
    );
    run.stage = stage;
}

fn interrupt(run: &mut Run, reason: Interrupt) -> Step {
    run.phase = Phase::Interrupted { reason };
    let p = run.totals.of(run.stage);
    log::info!(
        target: LOG,
        "slice_end outcome=interrupted reason={} stage={} done={} total={}",
        reason.name(),
        run.stage.name(),
        p.done,
        p.total
    );
    Step::Stop {
        stopped: Stopped::Interrupted { reason },
    }
}

/// A sleep that fits the slice, else the end of the slice: a rate limit's wait becomes `Waiting`, a pace gap
/// `Interrupted(SliceBudget)` (the next slice keeps the pace).
fn sleep_or_stop(
    run: &mut Run,
    s: &Session,
    now_ms: i64,
    ms: i64,
    why: Why,
    source: LimitSource,
) -> Step {
    if s.deadline_ms.is_some_and(|d| now_ms + ms > d) {
        return match why {
            Why::Pace => interrupt(run, Interrupt::SliceBudget),
            Why::RateLimit => {
                let until_ms = now_ms + ms;
                run.phase = Phase::Waiting { until_ms, source };
                log::info!(target: LOG, "slice_end outcome=waiting stage={} wait_ms={ms}", run.stage.name());
                Step::Stop {
                    stopped: Stopped::Waiting { until_ms, source },
                }
            }
        };
    }
    run.stats.waits_ms = run.stats.waits_ms.saturating_add(ms);
    if why == Why::RateLimit {
        log::info!(target: LOG, "pace sleep_ms={ms} why=rate_limit");
    }
    Step::Sleep { ms, why }
}

fn finish(cp: &mut Checkpoint, s: &Session, book: &Book, now_ms: i64) -> Step {
    if s.live {
        log::info!(target: LOG, "live_end outcome=done");
        return Step::Stop {
            stopped: Stopped::Done,
        };
    }
    if let Some(run) = cp.run.take() {
        let people = count(book.people.iter().filter(|p| p.on_whatsapp).count());
        log::info!(
            target: LOG,
            "run_end outcome=done people={people} downloaded={} batches={} rate_limits={} waited_ms={} ms={}",
            run.stats.downloaded,
            run.stats.batches,
            run.stats.rate_limits,
            run.stats.waits_ms,
            now_ms - run.id,
        );
        cp.last = Some(Finished {
            at_ms: now_ms,
            people,
            downloaded: run.stats.downloaded,
            duration_ms: now_ms - run.id,
        });
    }
    Step::Stop {
        stopped: Stopped::Done,
    }
}

/// Commits what the caller got. The caller saves the book (when `book_dirty`) and then the checkpoint.
pub fn report(
    cp: &mut Checkpoint,
    s: &mut Session,
    book: &mut Book,
    now_ms: i64,
    rand: u32,
    r: Report,
) -> Committed {
    s.enter(cp);
    let mut out = report_inner(cp, s, book, now_ms, rand, r);
    if let Some(run) = cp.run.as_mut() {
        run.stats.dropped = run
            .stats
            .dropped
            .saturating_add(count(out.drop_files.len()));
        refresh_totals(run, s, book, &cp.negatives, &cp.profiled_ms, now_ms);
        out.stage = Some(run.stage);
        out.progress = run.totals.of(run.stage);
        if let Phase::Waiting { until_ms, source } = run.phase {
            s.stopped = Some(Stopped::Waiting { until_ms, source });
        }
    }
    s.leave(cp);
    out
}

fn report_inner(
    cp: &mut Checkpoint,
    s: &mut Session,
    book: &mut Book,
    now_ms: i64,
    rand: u32,
    r: Report,
) -> Committed {
    let inflight = s.inflight.take();
    let gap_ms = s.gap_ms;
    let deadline = s.deadline_ms;
    let Checkpoint {
        run,
        pace,
        negatives,
        profiled_ms,
        ..
    } = cp;
    let Some(run) = run.as_mut() else {
        return Committed::default();
    };
    run.heartbeat_ms = now_ms;
    let mut out = Committed::default();
    match r {
        Report::Lookup { asked, found } => {
            let found_jids: HashSet<String> = found.iter().map(|u| u.jid.clone()).collect();
            let updates: Vec<Update> = found
                .into_iter()
                .map(|mut u| {
                    u.on_whatsapp.get_or_insert(true);
                    if u.number.is_none() {
                        u.number = s.numbers.get(&u.jid).cloned();
                    }
                    u
                })
                .collect();
            commit(book, &updates, now_ms, &mut out);
            for jid in asked.iter().chain(found_jids.iter()) {
                run.looked.insert(jid.clone());
                if found_jids.contains(jid) {
                    negatives.remove(jid);
                } else {
                    negatives.insert(jid.clone(), now_ms);
                }
            }
            run.stats.batches += 1;
            run.stats.people_found = run
                .stats
                .people_found
                .saturating_add(count(found_jids.len()));
            log::info!(
                target: LOG,
                "batch stage=lookup n={} found={} changed={}",
                asked.len(),
                found_jids.len(),
                out.changed
            );
            success(pace, false, gap_ms, now_ms);
        }
        Report::Profiles {
            asked,
            answered,
            unanswered,
        } => {
            let answered_jids: HashSet<String> = answered.iter().map(|u| u.jid.clone()).collect();
            let mut missing: Vec<String> = unanswered;
            for jid in &asked {
                if !answered_jids.contains(jid) && !missing.contains(jid) {
                    missing.push(jid.clone());
                }
            }
            log::info!(
                target: LOG,
                "batch stage=profiles n={} answered={} unanswered={}",
                asked.len(),
                answered_jids.len(),
                missing.len()
            );
            if answered_jids.is_empty() && !missing.is_empty() {
                // An old gateway's way of saying "rate limited" (D6).
                rate_limited(
                    pace,
                    run,
                    deadline,
                    now_ms,
                    rand,
                    None,
                    LimitSource::GatewayLegacy,
                    false,
                );
                return out;
            }
            commit(book, &answered, now_ms, &mut out);
            for jid in answered_jids {
                profiled_ms.insert(jid.clone(), now_ms);
                run.unanswered.remove(&jid);
                run.profiled.insert(jid);
            }
            for jid in missing {
                let times = run.unanswered.entry(jid.clone()).or_insert(0);
                *times = times.saturating_add(1);
                if *times >= MAX_UNANSWERED {
                    run.profiled.insert(jid);
                }
            }
            run.stats.batches += 1;
            success(pace, false, gap_ms, now_ms);
        }
        Report::Files { files, max_bytes } => {
            let plan = book.plan(now_ms, &files, max_bytes);
            s.files = files
                .iter()
                .filter_map(|f| Path::new(&f.path).file_name()?.to_str().map(str::to_owned))
                .collect();
            log::info!(
                target: LOG,
                "pictures planned={} remove={} files={}",
                plan.refresh.len(),
                plan.remove.len(),
                files.len()
            );
            s.queue = Some(plan.refresh.into());
            out.drop_files = plan.remove;
        }
        Report::Picture { jid, result } => {
            run.pictured.insert(jid.clone());
            let label = match &result {
                PictureResult::Stored { .. } => "image",
                PictureResult::Unchanged => "unchanged",
                PictureResult::None => "none",
                PictureResult::Failed => "failed",
            };
            match result {
                PictureResult::Stored { picture_id, file } => {
                    let update = Update {
                        jid: jid.clone(),
                        picture_id: Some(picture_id),
                        picture_file: Some(file),
                        ..Update::default()
                    };
                    commit(book, &[update], now_ms, &mut out);
                    run.stats.downloaded += 1;
                }
                PictureResult::Unchanged => {
                    let held = book.lookup(&jid).and_then(|p| {
                        Some((
                            p.jid.clone(),
                            p.picture_id.clone()?,
                            p.picture_file.clone()?,
                        ))
                    });
                    match held {
                        Some((key, id, file)) => {
                            let update = Update {
                                jid: key,
                                picture_id: Some(id),
                                picture_file: Some(file),
                                ..Update::default()
                            };
                            commit(book, &[update], now_ms, &mut out);
                        }
                        None => run.picture_failures += 1,
                    }
                }
                PictureResult::None => {
                    let update = Update {
                        jid: jid.clone(),
                        picture_removed: true,
                        ..Update::default()
                    };
                    commit(book, &[update], now_ms, &mut out);
                }
                PictureResult::Failed => run.picture_failures += 1,
            }
            log::info!(target: LOG, "picture result={label}");
            success(pace, true, gap_ms, now_ms);
        }
        Report::RateLimited {
            retry_after_ms,
            source,
        } => {
            let picture = matches!(inflight, Some(Inflight::Picture(_)));
            rate_limited(
                pace,
                run,
                deadline,
                now_ms,
                rand,
                retry_after_ms,
                source,
                picture,
            );
        }
        Report::Error { error, message } => {
            log::warn!(target: LOG, "error kind={error:?} stage={} message={message}", run.stage.name());
            match error {
                ErrorKind::Gateway | ErrorKind::Other
                    if let Some(Inflight::Picture(jid)) = &inflight =>
                {
                    // One picture the gateway could not give: a failure of that picture, not of the run.
                    run.pictured.insert(jid.clone());
                    run.picture_failures += 1;
                    pace.picture_next_ms = now_ms + gap_ms;
                }
                ErrorKind::Network => {
                    interrupt(run, Interrupt::Network);
                }
                ErrorKind::NotConnected => {
                    interrupt(run, Interrupt::WaDisconnected);
                }
                ErrorKind::Auth => fail(run, FailReason::Auth, message),
                ErrorKind::Core => fail(run, FailReason::Core, message),
                ErrorKind::Gateway => fail(run, FailReason::Gateway, message),
                ErrorKind::Other => fail(run, FailReason::Other, message),
            }
        }
        Report::Cancelled => {
            interrupt(run, Interrupt::Cancelled);
        }
    }
    out
}

fn commit(book: &mut Book, updates: &[Update], now_ms: i64, out: &mut Committed) {
    if updates.is_empty() {
        return;
    }
    let up = book.upsert(updates, now_ms);
    if up.changed > 0 || !up.drop_files.is_empty() {
        out.book_dirty = true;
    }
    out.changed += up.changed;
    out.drop_files.extend(up.drop_files);
}

fn fail(run: &mut Run, reason: FailReason, message: String) {
    log::warn!(target: LOG, "slice_end outcome=failed reason={reason:?} stage={}", run.stage.name());
    run.phase = Phase::Failed { reason, message };
}

/// A request went through: the next one waits the drawn gap; the pace recovers slowly.
fn success(pace: &mut Pace, picture: bool, gap_ms: i64, now_ms: i64) {
    pace.ok_streak = pace.ok_streak.saturating_add(1);
    if pace.ok_streak >= RECOVER_AFTER {
        pace.strikes = 0;
        if picture {
            pace.picture_gap_ms =
                (pace.picture_gap_ms - pace.picture_gap_ms / 10).max(PICTURE_GAP_MS);
        } else {
            pace.usync_gap_ms = (pace.usync_gap_ms - pace.usync_gap_ms / 10).max(USYNC_GAP_MS);
            if pace.ok_streak.is_multiple_of(GROW_EVERY) {
                pace.usync_batch = (pace.usync_batch + BATCH_STEP).min(USYNC_BATCH);
            }
        }
    }
    if picture {
        pace.picture_next_ms = now_ms + gap_ms;
    } else {
        pace.usync_next_ms = now_ms + gap_ms;
    }
}

/// A 429: slower, smaller, and a wait; the wait sleeps inline when it is short, else the run waits (section 6).
#[allow(clippy::too_many_arguments)]
fn rate_limited(
    pace: &mut Pace,
    run: &mut Run,
    deadline_ms: Option<i64>,
    now_ms: i64,
    rand: u32,
    retry_after_ms: Option<i64>,
    source: LimitSource,
    picture: bool,
) {
    pace.strikes = pace.strikes.saturating_add(1);
    pace.ok_streak = 0;
    run.stats.rate_limits += 1;
    if picture {
        pace.picture_gap_ms = (pace.picture_gap_ms * 2).min(PICTURE_GAP_MAX_MS);
    } else {
        pace.usync_gap_ms = (pace.usync_gap_ms * 2).min(USYNC_GAP_MAX_MS);
        pace.usync_batch = (pace.usync_batch / 2).max(USYNC_BATCH_MIN);
    }
    let shift = (pace.strikes - 1).min(20);
    let backoff = BACKOFF_BASE_MS
        .saturating_mul(1 << shift)
        .min(BACKOFF_MAX_MS);
    let base = backoff.max(retry_after_ms.unwrap_or(0).max(0));
    let wait = base + base * (i64::from(rand) % (BACKOFF_JITTER_PCT + 1)) / 100;
    let until_ms = now_ms + wait;
    pace.not_before_ms = pace.not_before_ms.max(until_ms);
    pace.source = Some(source);
    let inline = wait <= INLINE_WAIT_MAX_MS
        && pace.strikes < MAX_STRIKES
        && deadline_ms.is_none_or(|d| until_ms < d);
    log::warn!(
        target: LOG,
        "rate_limited source={} stage={} retry_after_ms={} strikes={} wait_ms={wait} inline={inline} gap_ms={} batch={}",
        source.name(),
        run.stage.name(),
        retry_after_ms.unwrap_or(-1),
        pace.strikes,
        if picture { pace.picture_gap_ms } else { pace.usync_gap_ms },
        pace.usync_batch,
    );
    if !inline {
        run.phase = Phase::Waiting {
            until_ms: pace.not_before_ms,
            source,
        };
    }
}

/// Ends a slice from outside (cancel, unlink). With `session`, its run (a live pass: nothing stored) becomes
/// `Interrupted(reason)` when it is still running; without, the stored run is interrupted unless it failed.
pub fn stop(cp: &mut Checkpoint, session: Option<&mut Session>, reason: Interrupt) {
    match session {
        Some(s) if s.live => {}
        Some(_) => {
            if let Some(run) = cp.run.as_mut()
                && run.phase == Phase::Running
            {
                interrupt(run, reason);
            }
        }
        None => {
            if let Some(run) = cp.run.as_mut()
                && !matches!(run.phase, Phase::Failed { .. })
            {
                interrupt(run, reason);
            }
        }
    }
}

/// What the settings row shows: the stored run (unless expired), the last finished run and a running penalty.
pub fn status(cp: &Checkpoint, now_ms: i64) -> Status {
    let run = cp
        .run
        .as_ref()
        .filter(|r| !r.expired(now_ms))
        .map(|r| RunStatus {
            id: r.id,
            mode: r.mode,
            stage: r.stage,
            phase: r.phase.clone(),
            progress: r.totals.of(r.stage),
            resumable: r.phase != Phase::Running,
            heartbeat_ms: r.heartbeat_ms,
            stats: r.stats,
        });
    Status {
        run,
        last: cp.last,
        not_before_ms: (cp.pace.not_before_ms > now_ms).then_some(cp.pace.not_before_ms),
    }
}

#[cfg(test)]
#[path = "people_sync_tests.rs"]
mod tests;
