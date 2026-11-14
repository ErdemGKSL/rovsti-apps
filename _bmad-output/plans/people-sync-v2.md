# People sync v2: slow, resumable, observable

Status: in progress (2026-11-14, branch `ui-overhaul`): S1 to S4 done; see the notes under each step.
Scope: `services/wa_gateway`, `crates/rovsti_wa_proto`, `crates/rovsti_wa_client`, `crates/rovsti_app_core`,
`crates/rovsti_core`, `crates/rovsti_ffi`, `android/{core-bridge,people,shell,contacts,phone}`.
Priority order: correctness and account safety, then stability, then speed.

## 1. Root cause

Verified in the code and the gateway journal:

1. **WhatsApp itself rate-limits us.** Its servers answered `code=429, text='rate-overlimit'` to two profile
   batches (50 JIDs each, 7 s apart). Lookups (`is_on_whatsapp`) and profiles (`get_user_info`) are both usync
   queries, so the lookup stage that ran just before spent the same allowance. Our assumed budget (lookups 60 + 1/s,
   profiles 120 + 2/s, `services/wa_gateway/src/lib.rs:47-54`, mirrored in `PeopleSync.kt`) is far above the real one.
2. **The gateway hides it.** `rich_routes.rs:327` (`Err(e) => log::warn!(...)`) swallows the driver error and
   answers HTTP 200 with whatever is stored. For a JID never fetched that is `WaProfile { jid, ..Default }`:
   `fetched_ms = 0`, no picture id. The lookup and picture routes turn the same upstream 429 into a 500
   (`error.rs`, `DriverError::Failed` -> `Internal`), which the app counts as a hard failure.
3. **The app believes stage 2 worked.** `PeopleSync.profileUpdate` correctly ignores a profile with no answer, so
   nothing is written, `people.plan` has no picture ids, and stage 3 downloads nothing. The run is not even
   `Partial`.
4. **"Failed" has no reason.** `finish()` maps `SyncResult.NotLinked` (link not `Connected` yet when the worker
   starts, e.g. cold process) to `SyncOutcome.Failed`; every non-429 gateway error, `IOException` and core error
   also becomes `Failed`. Nothing is logged, only an outcome enum is stored (`PeopleSyncLog`), and the gateway logs
   neither requests nor its own 429s at INFO.
5. **Secondary:** negative lookups are not remembered by the app (only the gateway's 24 h cache), `onChanged()` fires
   once at the very end, a live picture pass holds the run lock, and `StandalonePeople.sync()` (Phone without
   Contacts) fires up to 1 000 lookups back to back with no pacing at every link.

## 2. Goals and non-goals

Goals: (a) never exceed WhatsApp's real limit on purpose, and back off hard when told; (b) every finished part is
durable and visible to Phone at once; (c) any interruption is resumable, automatically and by a button;
(d) every state has a truthful wording; (e) every stage, batch, wait, 429 and outcome is logged on the device and
readable in a developer-mode Logs screen; (f) older app builds keep working against the new gateway and the new app
keeps working against an old gateway.

Non-goals: merging lookup and profile into one usync query on the gateway (noted in section 13), a foreground
service, syncing groups, the GPUI Messages/Stops apps (they are replaced in E6/E7; they get developer mode from
`:shell` when ported).

## 3. Design decisions (and what was rejected)

| # | Decision | Rejected alternative, why |
| --- | --- | --- |
| D1 | The sync planner (work selection, cursor, pacing, backoff, state machine) is pure Rust in `rovsti_app_core::people_sync`, driven step by step through `people.sync_*` ops. Kotlin only performs the gateway call the planner asks for and reports the answer. | Keep growing `PeopleSync.kt` (605 lines): violates "domain logic in platform-free crates", cannot be shared with Phone standalone, and timing logic is hard to test in Robolectric. |
| D2 | The cursor is **set based**, not an index: per run, the sets of JIDs already looked up / profiled / pictured. Remaining work = current targets minus done sets. | Index into a stored list: breaks when the contact list changes between slices. Storing the target list: a second copy of the address book on disk for no gain. |
| D3 | Checkpoint is one JSON file `people-sync.json` next to `people.json` (temp file + rename, same helper as `people::save`). Every report commits `people.json` first, then the checkpoint. A crash between the two repeats one batch; upserts are idempotent. | SharedPreferences (`apply()` is not durable, not atomic with the book, Kotlin-only). SQLite: nothing else here uses it. |
| D4 | One pacer for all usync traffic (lookups + profiles share a gap), one for pictures. No burst. Constants in section 6. | Mirroring the gateway's token buckets (today): the gateway's numbers are guesses, and a burst is exactly what trips WhatsApp. |
| D5 | Gateway surfaces upstream throttling as **HTTP 429 `rate_limited` with `retry_after_ms` and `source`**, plus a `Retry-After` header and a per-instance cooldown. Exception: a JID with an older real answer (`fetched_ms > 0`) is still served stale-if-error; the 429 is returned only when at least one requested JID has no real answer or `refresh=true`. | Per-item "not fetched" marker only: old app builds ignore it and keep hammering; it also needs a wire change in `WaProfile`. Always-429 even when real (old) data exists: degrades the inbox for old and new builds for no benefit; `fetched_ms` already tells the age. |
| D6 | The app also treats a 200 profile with `fetched_ms == 0` as **unanswered** (not done, soft throttle). This makes the new app safe against a gateway that was not redeployed. | Trusting the status code alone: reproduces today's bug against an old gateway. |
| D7 | "Sync now" = new run (re-checks everything). "Continue" = same run, skips done sets. The 6 h worker resumes a resumable run, otherwise starts an incremental run (honours negative-lookup and profile freshness TTLs). | One button that guesses: the user asked for both, and a restart during a WhatsApp penalty is harmful (D8). |
| D8 | While WhatsApp's penalty is running (`not_before_ms` in the future) both buttons are disabled and the row shows the ETA; the continuation is scheduled automatically. | Letting the user retry: every early retry risks extending the penalty or flagging the account. |
| D9 | Progress reaches Phone through the existing provider notification, sent after every committed part (coalesced to at most one per 3 s, trailing edge guaranteed). | A new broadcast or a bound service: more surface, same result. `ProviderSource` + `PeopleIndex.attach` already reload on notify (conflated). |
| D10 | Rust logs reach the Logs screen by **pull**: `rovsti_ffi` keeps a bounded ring, Kotlin drains it with `core.logs_drain`. | Pushing each line through the event sink: a JNI call per log line from any thread, and `events::emit` itself logs on failure (recursion). |
| D11 | Developer mode, the log store and the Logs page live in shared modules: facade + ring + file + masking in `:core-bridge` (`dev.rovsti.core.log`, the lowest module every app module depends on; `:design` must stay Rust-free and gets nothing), UI in `:shell/settings/dev`. | A new `:devtools` module: one more module for ~6 files; `:shell` already owns the About page with the version row and is used by Contacts and Phone. |
| D12 | Phone without Contacts uses the same engine and the same `people.*` store in Phone's own data dir (supersedes X.1d "memory only"). See open question Q2; until answered, step S10 is the last step and can be dropped. | Leaving `StandalonePeople` unpaced: it is a second source of 429s on the same account. |

## 4. Checkpoint data model

New module `crates/rovsti_app_core/src/people_sync.rs` (no Android, no network; `serde`, `std::fs` like `people.rs`).

```rust
pub const FILE: &str = "people-sync.json";
pub const SCHEMA_VERSION: u32 = 1;
pub const MAX_FILE_BYTES: usize = 2 * 1024 * 1024;

pub struct Checkpoint {
    pub schema_version: u32,
    /// Which link the file belongs to; a different link resets everything below.
    pub link: Option<LinkId>,
    pub run: Option<Run>,          // the resumable or running run
    pub pace: Pace,                // survives runs: what we learned about the limit
    /// jid -> when WhatsApp said "not on WhatsApp" (ms). Pruned to current targets, cap 5 000.
    pub negatives: BTreeMap<String, i64>,
    /// jid -> when its profile was last really answered (ms). Pruned to people in the book.
    pub profiled_ms: BTreeMap<String, i64>,
    pub last: Option<Finished>,    // last finished run, for the settings row
}
pub struct LinkId { pub instance_id: String, pub gateway: String /* sha-256 hex of the gateway URL, first 16 */ }
pub struct Run {
    pub id: i64,                   // started_ms
    pub mode: Mode,                // Full | Incremental
    pub phase: Phase,
    pub stage: Stage,              // Lookup | Profiles | Pictures
    pub looked: BTreeSet<String>,  // asked this run (found or not)
    pub profiled: BTreeSet<String>,
    pub pictured: BTreeSet<String>,// answered this run (image, unchanged, none)
    pub picture_failures: u32,
    pub totals: Totals,            // lookup/profile/picture totals as last computed, for "x/y"
    pub stats: Stats,              // batches, people_found, downloaded, dropped, waits_ms, rate_limits
    pub heartbeat_ms: i64,
    pub slices: u32,
}
pub enum Phase {
    Running,
    Waiting { until_ms: i64, source: LimitSource },        // rate limit, auto-continues
    Interrupted { reason: Interrupt },                     // resumable
    Failed { reason: FailReason, message: String },        // resumable once the cause is gone
}
pub enum LimitSource { Whatsapp, Gateway, GatewayLegacy /* 200 with unanswered profiles */ }
pub enum Interrupt { ProcessDeath, Network, WaDisconnected, SliceBudget, Cancelled, LinkNotReady }
pub enum FailReason { Auth, Core, Gateway, Other }
pub struct Pace {
    pub usync_gap_ms: i64, pub usync_batch: u32, pub usync_next_ms: i64,
    pub picture_gap_ms: i64, pub picture_next_ms: i64,
    pub strikes: u32, pub ok_streak: u32, pub not_before_ms: i64,
}
pub struct Finished { pub at_ms: i64, pub people: u32, pub downloaded: u32, pub duration_ms: i64 }
```

Rules:

- **Load** never fails: missing, oversized, damaged or newer-schema file reads as `Checkpoint::default()` (logged at
  WARN). **Save** is temp + rename + fsync of the file.
- **Invalidation.** `begin` compares `LinkId`. Different instance or gateway: drop `run`, `negatives`,
  `profiled_ms`, reset `pace` to defaults (the limit belongs to the WhatsApp account). The people book itself is
  handled as today. Unlink (`WaLinkState.None/LoggedOut/Banned`) while a run exists: phase becomes
  `Interrupted(WaDisconnected)`; the run is dropped on the next `begin` with another link.
- **Contact list changes** need no invalidation (D2): targets are recomputed at every `begin`; done sets are
  intersected with the current targets; new numbers are simply more work.
- **Run expiry.** A run older than `RESUME_MAX_AGE_MS = 24 h` is not resumable: "Continue" is not offered and the
  worker starts an incremental run (which skips fresh negatives and fresh profiles anyway, so little is lost).
- **Process death.** There is exactly one writer process. At process start the container calls
  `people.sync_status { fresh_process: true }`: a stored `Phase::Running` becomes `Interrupted(ProcessDeath)`.
- **TTLs.** `NEGATIVE_TTL_MS = 7 d` (incremental runs skip them; a Full run asks again),
  `PROFILE_FRESH_MS = 24 h` (same as the gateway's `PROFILE_TTL_MS`; a Full run ignores it). Pictures keep the
  existing `people::REVALIDATE_MS` (7 d) through `Book::plan`.

## 5. State machine

Planner API (pure; `now_ms` and a random `u32` for jitter are inputs, so tests are deterministic):

```rust
pub enum Start { Resume, Restart, Auto, LivePictures } // Auto: resume if resumable, else Incremental (worker,
                                                       // foreground-stale). LivePictures: pictures only, no `run`.
pub struct Session { /* targets: Vec<(jid, e164)>, slice_deadline_ms: Option<i64>, in memory only */ }
pub fn begin(cp: &mut Checkpoint, book: &Book, link: LinkId, numbers: &[String], start: Start,
             slice_ms: Option<i64>, now_ms: i64) -> Result<Session, Refused>;
pub enum Refused { NotBefore { until_ms: i64 }, NothingToResume }
pub fn next(cp: &mut Checkpoint, s: &Session, book: &Book, now_ms: i64, rand: u32) -> Step;
pub enum Step {
    Lookup { jids: Vec<String> }, Profiles { jids: Vec<String> },
    PlanPictures,                                   // Kotlin lists the files, answers with Report::PicturePlan
    Picture { jid: String, known_id: Option<String> },
    Sleep { ms: i64, why: Why },                    // Pace | RateLimit
    Stop(Stopped),                                  // Done | Waiting{until_ms} | Interrupted | Failed
}
pub fn report(cp: &mut Checkpoint, s: &mut Session, book: &mut Book, now_ms: i64, r: Report) -> Committed;
pub enum Report {
    Lookup { asked: Vec<String>, found: Vec<people::Update> },
    Profiles { asked: Vec<String>, answered: Vec<people::Update>, unanswered: Vec<String> },
    PicturePlan { refresh: Vec<people::Refresh>, remove: Vec<String> },
    Picture { jid: String, result: PictureResult },   // Stored(update) | Unchanged | None | Failed
    RateLimited { retry_after_ms: Option<i64>, source: LimitSource },
    Error { kind: ErrorKind, message: String },       // Network | NotConnected | Auth | Core | Other
    Cancelled,
}
pub struct Committed { pub changed: usize, pub drop_files: Vec<String>, pub progress: Progress }
pub fn status(cp: &Checkpoint, now_ms: i64) -> Status;   // what the UI shows (section 8)
```

Transitions (phase):

```
(none | Done | expired)  --begin(Restart|Auto)-->  Running[Lookup]
Interrupted | Failed | Waiting(until <= now)  --begin(Resume|Auto)-->  Running[stage kept]
Waiting(until > now)  --begin(*)-->  Refused::NotBefore
Running[Lookup]    --no unknown numbers left-->  Running[Profiles]
Running[Profiles]  --no unprofiled people left-->  Running[Pictures]  (PlanPictures first)
Running[Pictures]  --plan empty | MAX_PICTURES | MAX_FAILURES-->  Stop(Done), run cleared, `last` set
Running  --RateLimited, wait fits inline-->  Sleep(RateLimit) then same step again
Running  --RateLimited, wait does not fit or strikes >= MAX_STRIKES-->  Waiting{until}
Running  --Error(Network)-->  Interrupted(Network)        Running --Error(NotConnected)--> Interrupted(WaDisconnected)
Running  --Error(Auth|Core|Other)-->  Failed{reason,message}
Running  --slice deadline reached-->  Interrupted(SliceBudget)
Running  --Cancelled-->  Interrupted(Cancelled)           Running --process death--> Interrupted(ProcessDeath)
```

Work selection per stage:

- Lookup: `targets - book jids - looked - (fresh negatives, Incremental only)`, recent callers first (the order
  `peopleNumbers()` already gives), at most `MAX_NUMBERS = 2 000`, in batches of `pace.usync_batch`.
- Profiles: people `on_whatsapp` in the book that are targets, minus `profiled`, minus fresh `profiled_ms`
  (Incremental only). `unanswered` JIDs are not added to `profiled`; if a whole batch is unanswered it counts as
  `RateLimited { source: GatewayLegacy }` (D6).
- Pictures: `Book::plan` as today (`MAX_PICTURES = 300`, `MAX_FAILURES = 5` per run), minus `pictured`. A
  rate-limited picture is never a failure.

Idempotency: every step can be repeated. `Report::Lookup/Profiles/Picture` are upserts keyed by JID; done sets are
sets; a picture file is named by JID + picture id (`wa.avatars_plan`), so storing twice writes the same name.

One run at a time: (1) in-process `Mutex` in the Kotlin runner (worker, button, foreground trigger and live pass
all go through it; a second caller gets "already running" and returns the live status instead of queuing);
(2) a `FileChannel.tryLock()` on `noBackupFilesDir/people-sync.lock` held for the slice, as a guard should a second
process ever exist (verify the Contacts manifest declares no `android:process`); (3) process death, covered by the
`fresh_process` rule above.

## 6. Pacing

All in `people_sync.rs` as `pub const`, with one comment block explaining the reasoning. They are deliberately
conservative first guesses; the developer-mode log (section 10) is how they get calibrated.

| Constant | Value | Reasoning |
| --- | --- | --- |
| `USYNC_BATCH` / `USYNC_BATCH_MIN` | 15 / 5 | Observed failure: 50-JID batches seconds apart. A third of that, and small parts are what the user asked for. |
| `USYNC_GAP_MS` (base) / `USYNC_GAP_MAX_MS` | 20 000 / 300 000 | 45 JIDs per minute sustained, no burst; lookups and profiles share it because both are usync. |
| `PICTURE_GAP_MS` (base) / `PICTURE_GAP_MAX_MS` | 3 000 / 60 000 | One IQ per picture; 20 per minute. |
| `JITTER_PCT` | 20 | Gap is `gap * (100 ± 20) / 100` so requests are not periodic. |
| `BACKOFF_BASE_MS` / `BACKOFF_MAX_MS` | 60 000 / 1 800 000 | First 429 waits a minute, then doubles, cap 30 min. |
| `INLINE_WAIT_MAX_MS` | 90 000 | Longer waits end the slice instead of holding a worker. |
| `MAX_STRIKES` | 3 | Three 429s in one slice: stop cleanly. |
| `WORKER_SLICE_MS` | 8 min | WorkManager stops a worker at 10 min. Foreground runs have no slice limit. |
| `CONTINUE_DELAY_MS` | 60 000 | Gap between background slices when nothing is throttled. |
| `NOTIFY_MIN_MS` (Kotlin) | 3 000 | Provider notifications to Phone. |

On a 429 (`Report::RateLimited`):

1. `strikes += 1; ok_streak = 0; usync_gap_ms = min(gap * 2, MAX)` (or the picture gap, by stage);
   `usync_batch = max(batch / 2, MIN)`.
2. `wait = max(retry_after_ms or 0, BACKOFF_BASE_MS << (strikes - 1))`, capped at `BACKOFF_MAX_MS`, plus 0..25 %
   jitter (upward only, never earlier than the server's hint). `pace.not_before_ms = now + wait`.
3. If `wait <= INLINE_WAIT_MAX_MS`, it fits the slice deadline and `strikes < MAX_STRIKES`: `Step::Sleep(RateLimit)`
   and the same batch (re-cut at the new batch size) is asked again. Otherwise `Stop(Waiting { until_ms })`.
4. Recovery: every success `ok_streak += 1`; at 5 `strikes = 0`; from then each success shrinks the gap by 10 %
   towards the base, and every 10 successes the batch grows by 5 up to `USYNC_BATCH`. Never below base / above
   `USYNC_BATCH`. `Pace` is persisted, so the next slice (or process) starts as careful as this one ended.

The live picture pass and the run share `Pace` (`usync_next_ms`, `picture_next_ms`, `not_before_ms`), so they
cannot add up to a burst.

Expected duration, first sync of 500 numbers, 200 on WhatsApp, 150 pictures: 34 lookups (about 11 min) +
14 profile batches (about 5 min) + 150 pictures (about 8 min) = about 24 min in three or four background slices.
An incremental run with nothing new: one `PlanPictures`, seconds.

## 7. Gateway changes (deploy with `scripts/deploy-wa-gateway.sh`)

1. `driver.rs`: new `DriverError::RateLimited { retry_after_s: Option<u32> }`. In `wa/session.rs` replace the
   `failed` helper on the three contact calls (`is_on_whatsapp`, `get_user_info`, `get_profile_picture`) with
   `contact_failed(e)`: if the error is (or wraps) `IqError::ServerError { code: 429, backoff, .. }` return
   `RateLimited { retry_after_s: backoff }`, else `Failed`. (whatsapp-rust 0.7 `request.rs:113`; check how
   `ContactError` wraps `IqError` at the pinned version and match by type; keep a string fallback on
   `"code=429"` covered by a test.)
2. `rovsti_wa_proto::ApiError`: two optional fields, `retry_after_ms: Option<u64>` and
   `source: Option<LimitSource>` (`"whatsapp" | "gateway"`), `#[serde(default, skip_serializing_if = "Option::is_none")]`.
   Old clients ignore unknown fields (no `deny_unknown_fields`; add a test that pins this).
3. `error.rs`: `From<DriverError>` maps `RateLimited` to `ErrorCode::RateLimited`; `IntoResponse` adds a
   `Retry-After` header (whole seconds, rounded up) when `retry_after_ms` is set.
4. `lib.rs`: per-instance `upstream_until_ms` next to the buckets (`Lookups`). `note_upstream_limit(instance,
   retry_after)` sets it to `now + max(retry_after, 60 s << strikes)` (cap 30 min, strikes reset after 10 min of
   successes). `charge_profiles` / `lookup_cached` return a small enum `Charge::{Ok, Bucket, Cooldown{left_ms}}`
   instead of `bool`; during the cooldown nothing is sent to WhatsApp. Bucket constants stay as they are in this
   round (older builds pace against them); the cooldown is the protection. Revisit after a week of logs.
5. `rich_routes.rs::profiles`: on `Err(DriverError::RateLimited)` (or `Charge::Cooldown/Bucket`): call
   `note_upstream_limit`; if `req.refresh` or any requested JID has no stored answer with `fetched_ms > 0`, answer
   429 with `retry_after_ms` and `source`; otherwise serve the stored (stale) profiles with 200 and the response
   header `x-rovsti-stale: rate-limited`. Other driver errors: keep serving stored data only under the same
   "every JID has a real answer" rule, else return the error (today's silent default profiles disappear).
6. `routes.rs::lookup_users` and `rich_routes.rs::fetch_picture`: upstream 429 now arrives as `RateLimited`
   through `?`; add `note_upstream_limit` and the `retry_after_ms`/`source` fields. Gateway bucket refusals carry
   `source: gateway` and `retry_after_ms` from the bucket's refill time (add `TokenBucket::wait_ms(n, now)` in
   `rovsti_wa_proto::limit`, unit tested).
7. Logging: one `axum::middleware::from_fn` layer `log_request` in `lib.rs`: at INFO
   `instance=<id> <METHOD> <matched route template> -> <status> <ms>ms` (uses `MatchedPath`, so no JID or query
   string is logged; `/health` and the event stream at DEBUG). Every 429 at WARN:
   `instance=<id> rate limited source=<whatsapp|gateway> route=<template> retry_after_ms=<n> n=<jids>`. Cooldown
   start and end at INFO. No phone numbers: JIDs are never formatted into these lines.
8. Docs: `docs/whatsapp-gateway.md` (sections around lines 220 and 368: new fields, header, stale rule, cooldown).

Compatibility matrix:

| App build | Gateway | Result |
| --- | --- | --- |
| old (6e95a72) | new | profile 429 is `isRateLimited` already: `Partial`, WorkManager retry. Its three quick retries are absorbed by the cooldown without reaching WhatsApp. Lookup/picture 429 was a 500 before (Failed), now Partial. |
| older (pre 6e95a72) | new | 429 = Failed, as for gateway 429s today. No regression. |
| new | old | 200 with `fetched_ms == 0`: D6 treats it as `GatewayLegacy` throttle; lookup/picture 500 is `Error(Other)` -> `Failed{Gateway}` with the message, resumable. |

## 8. Client, FFI and Kotlin wiring

- `rovsti_wa_client`: no API change (`ClientError::Api(ApiError)` now carries the new fields). Unit test in
  `error.rs` for a 429 body with and without the fields.
- `rovsti_ffi::ops::wa::map_error`: detail becomes `{ "code", "retry_after_ms"?, "source"? }`.
- `rovsti_ffi::ops::people`: new ops `people.sync_status`, `people.sync_begin`, `people.sync_next`,
  `people.sync_report`, `people.sync_stop` with fixture cases (same pattern as `people.upsert`); the `Session`
  lives in a `Mutex<Option<Session>>` in `state.rs`. Update `crates/rovsti_ffi/OPS.md` and run
  `scripts/check-ffi-exports.sh`.
- Kotlin models in `android/core-bridge/.../model/people/SyncModels.kt` (+ contract fixtures), `PeopleOps` gets
  the five calls, `RustError.rateLimit(): RateLimit?` (`retryAfterMs`, `source`) replaces the boolean
  `isRateLimited()` (kept as a thin wrapper for `WaAvatars`).
- Runner: `android/people/src/main/kotlin/dev/rovsti/people/sync/PeopleSyncRunner.kt` (shared by Contacts and,
  in S10, Phone). `PeopleStore` moves from `:contacts` to `:people` unchanged (it has no Contacts dependency).

```kotlin
class PeopleSyncRunner(store: PeopleStore, ops: PeopleOps, wa: WaRepository, numbers: suspend () -> List<String>,
    codec: AvatarCodec, clock: () -> Long, pause: suspend (Long) -> Unit, random: () -> Int,
    onPart: () -> Unit, log: RovstiLog) {
    val status: StateFlow<PeopleSyncStatus>
    suspend fun run(start: Start, sliceMs: Long?): SliceResult   // loop: next -> perform -> report
    fun cancel()
}
```

  The loop: `syncNext` -> `Lookup/Profiles/Picture` call the gateway and `syncReport`; `Sleep` calls `pause`
  (cancellable; a cancel reports `Cancelled`); `Stop` ends the slice. After each `Committed` with
  `changed > 0 || dropFiles.isNotEmpty()` it deletes `dropFiles` and calls `onPart()`. `IOException` and
  `RustError.Network` become `Report.Error(Network)`; cancellation of the coroutine (worker stopped) reports
  `Cancelled` in a `NonCancellable` block so the phase is never left `Running`.
- `PeopleSync` (Contacts) shrinks to: link following, live events, `syncNow()`, `resume()`, `syncIfStale()`,
  delegating to the runner. Deleted: `Allowance`, `Wait`, `Paced`, `PeopleSyncLog` (its prefs file `people-sync`
  is read once to seed `last` and then cleared; the name stays reserved).
- **Link readiness.** `run()` first waits up to `LINK_WAIT_MS = 20 s` for the link to settle. `WaLinkState.None`
  also means "repository not started", so add `WaRepository.settled: StateFlow<Boolean>` (true once the stored
  link was read and, if one exists, the first status arrived or failed). Not settled in time: slice result
  `Interrupted(LinkNotReady)`, worker retries; settled and no link: `NotLinked` (row hidden, never "Failed").
  `Connected` whose `status.state` is `Disconnected`, or a 409 `not_connected` from the gateway:
  `Interrupted(WaDisconnected)`.
- **Worker.** `PeopleSyncWorker.doWork()`: `run(Start.Auto, WORKER_SLICE_MS)`. Result mapping: `Done` and
  `NotLinked` -> `success()`; `Waiting(until)`, `Interrupted(*)` with work left -> enqueue the unique one-time work
  `people-sync-continue` (`ExistingWorkPolicy.REPLACE`, initial delay `max(until - now, CONTINUE_DELAY_MS)`,
  network constraint) and return `success()` (no WorkManager exponential backoff: the planner owns the timing);
  `Failed` -> `success()` too, with the reason stored; the next 6 h period tries again. `onStopped` cancels the
  runner. The periodic request is unchanged (`KEEP`), so no reschedule on update.
- **Live events.** `onProfile` upserts go through a short `commit` mutex shared with the runner's reports. The
  debounced live picture pass: if a run is active, do nothing (the run's picture stage will see the stale flag);
  otherwise run `Start.LivePictures`, a planner entry that does only `PlanPictures` + pictures under the shared
  `Pace`, with a 30 s budget, and never creates or changes `run`.
- **Progress to Phone (D9).** `onPart` = `PartNotifier(scope, NOTIFY_MIN_MS)`: leading call immediately, then at
  most one per 3 s, always a trailing one; each fires `peopleStore.notifyChanged(resolver)` and
  `refreshPeopleIndex()`. Phone side needs no change: `ProviderSource.changes` -> `PeopleIndex.attach` reloads the
  snapshot; picture URIs contain the picture id, so a new picture is a new image key. A Phone process that starts
  mid-sync reads everything committed so far on its first query.

## 9. Settings UI: states and strings

`PeopleSyncStatus` (from `people.sync_status` + link state + in-process "active"):
`NotLinked | Connecting | Idle(last?) | Running(stage, done, total) | Pacing(same + nextInMs) |
RateLimited(untilMs, done, total) | Interrupted(reason, done, total, resumable) | Failed(reason, message, resumable)`.
`done/total` are per stage, taken from `Run.totals`.

Rows of the "WhatsApp Names and Photos" group (`SettingsPages.kt::PeopleSyncGroup`, anatomy per
`docs/design/layout.md`; read `.claude/skills/convx-motion-compose` before touching it):

1. Status row: title "Last Sync" / "Son Eşitleme", value by state (table). While running, a thin determinate
   progress bar under the row (spring-animated fraction, no layout jump; reduced motion: no animation).
2. "Continue Where It Left Off" / "Kaldığı Yerden Devam Et": only when `resumable`; primary tint; disabled with
   the ETA while `RateLimited`.
3. "Sync Now" / "Şimdi Eşitle": always; while running it becomes "Stop" / "Durdur" (cancels, state becomes
   Interrupted(Cancelled), resumable). When a resumable run exists, tapping it asks in an alert: "Start over?
   Progress so far is kept, but everyone is checked again." / "Baştan başlansın mı? ...".
4. In developer mode only: "Sync Details" / "Eşitleme Ayrıntıları" opens the Logs page filtered to tag `sync`.

| State | English (`values/strings.xml`) | Turkish (`values-tr/strings.xml`) |
| --- | --- | --- |
| never | Not synced yet | Henüz eşitlenmedi |
| synced | Synced, %1$s | Eşitlendi, %1$s |
| connecting | Waiting for WhatsApp to connect… | WhatsApp bağlantısı bekleniyor… |
| running lookup | Checking numbers %1$d/%2$d | Numaralar denetleniyor %1$d/%2$d |
| running profiles | Getting names %1$d/%2$d | Adlar alınıyor %1$d/%2$d |
| running pictures | Downloading photos %1$d/%2$d | Fotoğraflar indiriliyor %1$d/%2$d |
| rate limited (ETA) | WhatsApp limit reached. Continues in %1$s | WhatsApp sınırına ulaşıldı. %1$s sonra devam edecek |
| rate limited (ETA passed) | WhatsApp limit reached. Ready to continue | WhatsApp sınırına ulaşıldı. Devam etmeye hazır |
| interrupted, generic | Interrupted at %1$d/%2$d, %3$s | %1$d/%2$d aşamasında kesildi, %3$s |
| interrupted, network | No connection. Paused at %1$d/%2$d | Bağlantı yok. %1$d/%2$d aşamasında duraklatıldı |
| interrupted, WhatsApp offline | WhatsApp is not connected. Paused at %1$d/%2$d | WhatsApp bağlı değil. %1$d/%2$d aşamasında duraklatıldı |
| interrupted, stopped by user | Stopped at %1$d/%2$d | %1$d/%2$d aşamasında durduruldu |
| partly synced (between slices) | Partly synced, continues automatically | Kısmen eşitlendi, kendiliğinden devam edecek |
| failed, auth | Failed: the gateway rejected the connect key | Başarısız: ağ geçidi bağlantı anahtarını reddetti |
| failed, gateway | Failed: the gateway reported an error | Başarısız: ağ geçidi hata bildirdi |
| failed, storage | Failed: could not save on this phone | Başarısız: bu telefona kaydedilemedi |
| failed, other | Failed, %1$s | Başarısız, %1$s |
| button continue | Continue Where It Left Off | Kaldığı Yerden Devam Et |
| button sync / stop | Sync Now / Stop | Şimdi Eşitle / Durdur |
| restart alert title / body | Start Over? / Progress so far is kept, but everyone is checked again. This takes longer. | Baştan Başlansın mı? / Şimdiye kadarki ilerleme korunur, ancak herkes yeniden denetlenir. Bu daha uzun sürer. |
| restart alert buttons | Start Over / Cancel | Baştan Başla / Vazgeç |
| footer addition | Syncing is slow on purpose so WhatsApp does not limit your account. | Eşitleme, WhatsApp hesabınızı sınırlamasın diye bilerek yavaştır. |

String ids: `settings_people_*` (keep the existing ids where the text is unchanged; `settings_people_failed` and
`settings_people_partial` are replaced). Durations ("12 min", "12 dk") use the existing relative-time formatter;
the ETA text refreshes once a minute from a ticker in the view model (no per-second recomposition).

## 10. Logging pipeline and developer mode

### Log store (`android/core-bridge/src/main/kotlin/dev/rovsti/core/log/`)

- `RovstiLog` (object facade): `d/i/w/e(tag: String, msg: String, t: Throwable? = null)` and
  `event(tag, name, vararg fields: Pair<String, Any?>)` which renders `name k=v k=v` (the structured form the sync
  uses). Always also writes to logcat (`android.util.Log`) under tag `rovsti` in debuggable builds only.
- `LogEntry(seq: Long, timeMs: Long, level: Level, tag: String, source: Source /* Kotlin | Rust */, text: String)`.
- `LogRing(capacity = 2 000)`: lock-protected ring, always on (memory only), `snapshot()`, `StateFlow<Long>` of
  the last seq for the UI. Lines are capped at 2 000 chars.
- `LogFile(dir = noBackupFilesDir/logs, maxBytes = 512 KiB, files = 2)`: `rovsti-0.log` rotates to
  `rovsti-1.log`; written by one background thread from a bounded queue (drop-oldest, never blocks a caller);
  **only while developer mode is on**; deleted when it is turned off and by "Clear".
- `LogMask.mask(text)`: applied at write time, before the ring and the file, to Kotlin and (again, defensively)
  Rust lines. Rules: `rwk_[A-Za-z0-9_-]+` -> `rwk_***`; `Authorization`/`key=` values -> `***`; JIDs
  `(\d{7,15})@(s\.whatsapp\.net|lid)` and bare phone-like runs (`\+?\d{7,15}`, not part of a longer number,
  not a timestamp field named `*_ms`/`ms=`) -> first two digits + `***` + last two (`90***67@s.whatsapp.net`);
  group JIDs keep only the suffix; e-mail local parts -> `***`. Names and message text are never logged by policy
  (review rule, plus a source-scan test like `AvatarSourceScanTest` that forbids `RovstiLog` calls with
  `name`/`pushName`/`body` arguments in `people` and `wa`).
- The same rules exist in Rust as `rovsti_core::redact::mask(&str) -> Cow<str>` (pure, unit tested). Both sides
  are checked against one fixture, `crates/rovsti_core/tests/data/redact_cases.json`, which the Kotlin test reads
  from the repo path the way `QrMatrixTest` reads its contract fixture.

### Rust logs (today: `crates/rovsti_ffi/src/logger.rs` writes to logcat tag `rovsti` only)

- `logger.rs`: `log()` additionally pushes `{seq, time_ms, level, target, text: redact::mask(msg)}` into
  `logbuf` (new `crates/rovsti_ffi/src/logbuf.rs`: `Mutex<VecDeque>`, capacity 2 000, `try_lock` so logging never
  blocks or deadlocks; a dropped line increments a counter). Filter: targets starting with `rovsti` at Debug,
  everything else at Warn and above (keeps `ureq`/`rustls` chatter and their URLs out).
- New op `core.logs_drain { after_seq, max }` -> `{ entries, dropped, last_seq }` (read-only, fixture case).
- Kotlin `RustLogPump` (core-bridge): while developer mode is on, drains every 1 s (and once when it is switched
  on, which brings the history since process start) into `LogRing` with `source = Rust`. Off: no polling, zero
  cost beyond the Rust ring.
- `people_sync` logs through `log::info!(target: "rovsti::sync", ...)`.

### What the sync logs (tag `sync`, structured, masked)

`run_begin id= mode= start=resume|restart|auto targets= to_lookup= to_profile=` ·
`link_wait settled= ms=` · `batch stage=lookup n= ok found= ms=` · `batch stage=profiles n= answered= unanswered= ms=` ·
`picture result=image|unchanged|none|failed bytes= ms=` · `pace sleep_ms= gap_ms= batch=` ·
`rate_limited source=whatsapp|gateway|gateway_legacy retry_after_ms= strikes= wait_ms= inline=` ·
`error kind= message=` · `commit changed= dropped=` · `notify parts=` ·
`slice_end outcome= stage= done= total= waited_ms= batches= next_at=` · `worker result= continue_in_ms=`.
Also `wa` (link state changes, gateway call + status + duration from `WaGateway.guarded`) and `core` (Rust).
The existing 68 `android.util.Log` call sites move to `RovstiLog` module by module (mechanical; a detekt/konsist
rule or a source-scan test then forbids new direct `android.util.Log` use in `src/main`).

### Developer mode

- State: `DevMode` in `:core-bridge` (`dev.rovsti.core.log.DevMode`): `enabled: StateFlow<Boolean>`, persisted in
  SharedPreferences file `rovsti_dev` (add the name to the pinned set in `PrefsNamesTest`/`PlatformPrefs` if that
  test scans `:core-bridge`; otherwise pin it in a new `DevPrefsTest`). Per app (each app has its own data).
- Toggle: the **Version** row of the shared About page (`android/shell/.../settings/AboutPage.kt`, the
  `settings_about_version` item; used by Contacts `ContactsSettings.kt:115` and Phone
  `PhoneSettingsEntries.kt:100`; Messages and Stops get it when their Kotlin settings land in E6/E7).
  `Modifier.holdToToggle(holdMs = 10_000, onToggle)` in `:shell/settings/dev/HoldToToggle.kt`: `pointerInput` with
  `awaitFirstDown`, then `withTimeoutOrNull(holdMs) { waitForUpOrCancellation() }`; null means held long enough.
  Moving outside the row's bounds or scrolling cancels. No visual hint before 10 s (hidden feature); at 10 s:
  `haptics.confirm()` (off: `haptics.toggle(false)`), and a toast through `OverlayController.showToast`:
  "Developer mode on" / "Geliştirici modu açık", "Developer mode off" / "Geliştirici modu kapalı". The timing
  decision is a pure function `holdReached(downMs, nowMs, holdMs)` for the unit test. `IosSettingsItem` gets the
  modifier through a new optional `modifier` parameter (check the item's current API first; do not fork it).
- When on, the settings root (`CommonSettingsGroup`) shows a "Developer" / "Geliştirici" group: "Logs" /
  "Günlükler" (opens `LogsPage`) and a "Developer Mode" / "Geliştirici Modu" switch to turn it off. Off deletes
  the log files and stops the pump; the memory ring stays (it is always there).
- `LogsPage` (`:shell/settings/dev/LogsPage.kt` + `LogsViewModel`): `SettingsPage` scaffold; search field
  (design-system text field, substring, case-insensitive, debounced 150 ms); level segmented control
  (All / Info / Warn / Error = minimum level); tag chips built from the tags present (`sync`, `wa`, `core`, ...),
  multi-select; `LazyColumn` with stable keys (`seq`), monospace caption style, level-coloured leading dot (not
  colour alone: the level letter is in the text), newest at the bottom, auto-follow while scrolled to the end and
  a "jump to latest" pill otherwise; tap expands a long line. Toolbar menu: Copy (filtered view to the clipboard,
  capped at 256 KiB, toast "Copied"), Share (writes the filtered view to `cacheDir/logs/rovsti-log-<time>.txt` and
  starts `ACTION_SEND` `text/plain` through the app's FileProvider with a read grant; verify each app declares one,
  else add a `logs` path to a shared provider in `:shell`'s manifest), Clear (alert first). The share sheet is the
  only way anything leaves the device; the header of the exported file says the content is masked.
  Strings: Logs/Günlükler, Search logs/Günlüklerde ara, All/Tümü, Copy/Kopyala, Share/Paylaş, Clear/Temizle,
  Clear logs?/Günlükler temizlensin mi?, Copied/Kopyalandı, No log lines yet/Henüz günlük satırı yok,
  Jump to latest/En yeniye git.
  Performance: filtering runs off the main thread on a snapshot; the list observes the last-seq flow conflated to
  at most 4 updates per second. Add a catalog page (`android/catalog`, `DevLogsPage`) and shot tests (light/dark,
  tr/en) per the repo's screen checklist.

## 11. Tests

Rust, `rovsti_app_core::people_sync` (pure, fake clock, fixed `rand`):
`fresh_run_walks_lookup_profiles_pictures`, `batches_are_at_most_usync_batch`, `gap_and_jitter_bounds`,
`no_burst_first_two_batches_are_a_gap_apart`, `lookups_and_profiles_share_the_usync_gap`,
`rate_limit_doubles_gap_halves_batch`, `retry_after_is_never_undercut`, `backoff_caps_at_max`,
`short_wait_sleeps_inline_long_wait_stops`, `three_strikes_stop_the_slice`, `recovery_restores_base_pace`,
`pace_survives_save_and_load`, `resume_skips_done_sets`, `resume_refused_before_not_before`,
`restart_clears_done_sets_keeps_book`, `auto_resumes_then_incremental`, `run_older_than_24h_is_not_resumable`,
`contact_added_between_slices_is_asked`, `contact_removed_between_slices_is_dropped`,
`link_change_resets_run_negatives_and_pace`, `fresh_process_turns_running_into_interrupted`,
`slice_deadline_interrupts_with_progress`, `unanswered_profiles_are_not_done`,
`all_unanswered_batch_counts_as_legacy_throttle`, `negatives_skipped_incremental_asked_full`,
`negative_and_profile_ttls`, `picture_failures_cap`, `rate_limited_picture_is_not_a_failure`,
`report_is_idempotent` (same report twice = same state), `crash_between_book_and_checkpoint_repeats_one_batch`,
`damaged_or_oversized_file_reads_as_default`, `newer_schema_reads_as_default`, `status_progress_per_stage`,
`live_pictures_do_not_touch_run`. Plus `people.sync_*` fixture cases in `rovsti_ffi` and a serde round-trip.

Rust, others: `rovsti_core::redact` (fixture cases: connect key, JID, bare number, number inside a longer id is
left alone, `_ms` fields are left alone, e-mail, idempotent masking); `rovsti_ffi::logbuf` (capacity, drain after
seq, dropped counter, non-`rovsti` debug filtered); `rovsti_wa_proto` (`ApiError` old JSON still parses, new
fields skipped when `None`, `TokenBucket::wait_ms`); `rovsti_wa_client::error` (429 body with fields);
`rovsti_ffi::ops::wa` (`map_error` detail carries `retry_after_ms` and `source`).

Gateway (`services/wa_gateway`, `FakeDriver` gains `fail_next(op, DriverError)`):
`tests/api.rs`: `profiles_surface_upstream_rate_limit` (never-fetched JID: 429, `retry_after_ms`, `source=whatsapp`,
`Retry-After` header), `profiles_serve_stale_when_every_jid_was_fetched` (200 + `x-rovsti-stale`),
`profiles_refresh_true_is_never_stale`, `lookup_upstream_rate_limit_is_429_not_500`,
`picture_upstream_rate_limit_is_429`, `cooldown_stops_upstream_calls` (driver call counter stays flat),
`cooldown_expires`, `gateway_bucket_429_has_source_gateway`, `profiles_other_driver_error_is_not_silent`.
Unit: `contact_failed` maps `IqError::ServerError{429, backoff}` and the string fallback; `status_of` unchanged;
the request-log line contains the route template and no JID (format function tested directly).

Kotlin (JVM/Robolectric, existing rigs):
- `:people` `PeopleSyncRunnerTest` on an extended `PeopleTestRig` (the `people.sync_*` ops run on the real host library the
  way `PeopleRustTest` does, so the planner is not duplicated in a Kotlin fake; where the host library is
  unavailable the test is skipped like the existing real-Rust tests): performs steps in
  order, commits and notifies after each part, 429 with `retry_after_ms` sleeps or stops, `IOException` ->
  `Interrupted(Network)`, coroutine cancellation reports `Cancelled`, second concurrent `run` returns "already
  running", file lock held and released.
- `:contacts` `PeopleSyncRateLimitTest` rewritten for the runner (429 from WhatsApp, gateway and legacy-200);
  `PeopleSyncResumeTest` (kill mid-run by cancelling, new runner instance resumes, nothing asked twice);
  `PeopleSyncLiveTest` (live profile during a run does not deadlock; live pass respects `not_before`);
  `PeopleSyncWorkerTest` (result mapping, `people-sync-continue` enqueued with the right delay, `Failed` does not
  retry-loop, `NotLinked` vs `LinkNotReady`); `PartNotifierTest` (leading + coalesced + trailing with virtual
  time); `PeopleProviderTest` addition (a reader sees rows committed by the first part while the run continues);
  `ContactsSettingsViewModelTest` (every state -> text, buttons enabled/disabled, ETA ticker, restart alert);
  shot tests for the group in each state (en/tr, light/dark).
- `:core-bridge` `LogMaskTest` (shared fixture), `LogRingTest`, `LogFileTest` (rotation, size cap, off = no file),
  `RustLogPumpTest` (fake `core.logs_drain`), `DevModeTest` (persist, off deletes files).
- `:shell` `HoldToToggleTest` (9.9 s no, 10 s yes, move-out cancels, haptic + toast recorded with
  `RecordingHaptics`), `LogsViewModelTest` (level/tag/search filters, copy cap), `LogsPage` shot and accessibility
  tests; source scan forbidding direct `android.util.Log` in `src/main`.
- `:phone` (S10) `StandaloneSourceTest` updated.

## 12. Implementation order (each step builds green and can ship alone)

Per step: `cargo fmt --all && cargo clippy --workspace --all-targets --profile checks && cargo test --workspace
--profile checks`, `cargo check --release -p rovsti_android --target aarch64-linux-android`, and for Kotlin steps
`scripts/kotlin-ui-check.sh` (release only; never a debug build, never `cargo clean`).

- **S1 Gateway truth (highest value, ship first).** Section 7 items 1-8 + proto fields + client/ffi detail + tests.
  Deploy with `scripts/deploy-wa-gateway.sh`, then check the journal shows request lines and, on the next sync, the
  WARN 429 line. The current app build immediately reports `Partial` instead of a fake success.
  *Done (implementation notes, deviations):* (a) `lookup_cached` / `charge_profiles` return
  `Result<_, Refused>` with `Refused::{Bucket{retry_after_ms}, Cooldown{left_ms}}` instead of a `Charge::Ok` variant
  (same information, idiomatic). (b) `note_upstream_limit` runs only on a real upstream 429, not on a bucket or
  cooldown refusal: otherwise every early retry during a pause would add a strike and extend the pause without end.
  A cooldown refusal carries `source: whatsapp` (the pause is WhatsApp's), a bucket refusal `source: gateway`.
  (c) The picture route also applies stale-if-error: when it holds the bytes of the known picture it serves them with
  `x-rovsti-stale: rate-limited` instead of a 429 (D5's reasoning; only a never-held picture gets the 429).
  (d) The Rust field is `ApiError::limit_source` with `#[serde(rename = "source")]`: `thiserror` treats a field
  named `source` as the error's cause. `LimitSource` has a `#[serde(other)] Other` so a newer source never breaks
  parsing. (e) The cooldown is a pure `services/wa_gateway/src/cooldown.rs` (unit tests cover expiry, doubling, cap,
  hint and strike reset); `cooldown_expires` is a unit test there, not an api test (the api tests run on the real
  clock and the first pause is 60 s). (f) The WARN 429 line is written by the contact handlers (they know `n`); the
  INFO request line by the `request_log` middleware (instance id filled in by the `Authed` extractor).
- **S2 Logging core.** `rovsti_core::redact`, `rovsti_ffi::logbuf` + `core.logs_drain`, Kotlin `RovstiLog`,
  `LogRing`, `LogMask`, `LogFile`, `DevMode`, `RustLogPump` (no UI yet; ring only). `PeopleSync` (still v1) gets
  log lines for run start, each batch, each 429, outcome and the NotLinked case. Tests.
  *Done (notes):* `redact::mask` is a one-pass ASCII scanner (no regex crate); `LogMask` is a line-for-line port and
  both pass `redact_cases.json` (the Kotlin test reads it through the `rovsti.redact` system property). In
  `logbuf` a busy ring drops the line but its seq is used, so `core.logs_drain`'s `dropped` counts every lost line
  from the gap; the fixture case asks after a seq far ahead, so its answer is the same in any process. `DevLog`
  (core-bridge) ties `DevMode` to the file sink and the pump; `ShellContainer.devLog` starts it off the main thread
  when the container is created, so a worker-only process writes the file too. `RustError.rateLimit()` sits next to
  `isRateLimited()` in `:wa` (`WaAvatars.kt`), where the only callers are, instead of `:core-bridge`.
- **S3 Developer mode UI.** `holdToToggle` on the version row, Developer group, `LogsPage`, share/copy/clear,
  strings en/tr, catalog page, shots. From here a failed sync can be diagnosed on the phone.
  *Done (notes, deviations):* `DevMode` reaches the shell pages through `LocalDevMode` (provided by
  `RovstiShellActivity` from `ShellContainer.devLog`), so `AboutPage` and `CommonSettingsGroup` need no new
  parameters and the apps only map `CommonSettingsPage.Logs` to their `Logs(tag)` route (Contacts, Phone). The tag
  filter is single-select (`ChipRow` is single-select; "All" plus one tag), not multi-select. The Logs page opens on
  the filters and follows new lines only while the end is in view (the "Jump to latest" pill otherwise); opening
  scrolled to the end clipped the filter row's touch targets at font scale 2. Share stages the file through the
  existing `RovstiShareProvider` (`FilesRepository.stageForShare`), which both apps already declare. "Sync Details"
  (open Logs on `sync`) is left to S7 with the new settings group; the route already takes the tag.
- **S4 Honest outcomes in v1 (small).** `NotLinked` no longer stored as Failed; link-settle wait
  (`WaRepository.settled`); profiles with `fetched_ms == 0` counted as unanswered -> Partial. This fixes the
  misleading row before the larger rewrite lands.
  *Done (notes):* `run()` waits up to `LINK_WAIT_MS` (20 s) for `WaRepository.settled`; not settled is the new
  `SyncResult.LinkNotReady` (worker `retry()`, nothing stored), settled without a link stays `NotLinked`, and neither
  is written as "Failed" any more (`finish` returns early). `DefaultWaRepository` settles after the first `connect`
  whatever its outcome. A profile with `fetched_ms == 0` and no picture id is unanswered: the run becomes `Partial`;
  a batch with no real answer at all is logged as `rate_limited source=gateway_legacy` and ends the profile stage.
  `Connected` with `status.state == Disconnected` is still attempted in v1 (its 409 ends as Failed); S6 maps it to
  `Interrupted(WaDisconnected)`.
- **S5 Planner in Rust.** `people_sync.rs` with all unit tests, `people.sync_*` ops + fixtures + Kotlin models and
  `PeopleOps` methods. Nothing uses it yet.
- **S6 Runner + Contacts switch-over.** `PeopleStore` to `:people`, `PeopleSyncRunner`, `PartNotifier`,
  `PeopleSync` delegates, worker continuation work, one-time migration of the old prefs, removal of
  `Allowance/Wait/Paced/PeopleSyncLog`. Sync log lines complete (section 10). Rewritten and new Kotlin tests.
- **S7 Settings UI.** New status model, progress, Continue / Stop / restart alert, all strings, shots.
- **S8 Live pass on the shared pace** (`Start.LivePictures`), commit mutex. (Can merge into S6 if small.)
- **S9 Mechanical:** move remaining `android.util.Log` sites to `RovstiLog`, add the source-scan rule; docs:
  `docs/whatsapp-gateway.md`, `docs/architecture.md` (people sync + logging paragraphs), `crates/rovsti_ffi/OPS.md`.
- **S10 Phone standalone on the engine** (pending Q2): `StandalonePeople` becomes a thin wrapper over
  `PeopleStore` + `PeopleSyncRunner` in Phone's data dir; `GatewaySource` reads the store; pictures from the store.

## 13. Risks and device verification

| Risk | Mitigation |
| --- | --- |
| Our constants are still above WhatsApp's limit | Adaptive backoff + gateway cooldown + persisted pace; all 429s are logged with timing, so one week of logs calibrates section 6. |
| Constants far too slow for large address books (2 000 numbers = about 45 min of lookups) | Recent callers go first, parts appear in Phone as they land, incremental runs are cheap. Later optimisation: one usync query on the gateway answering lookup + profile together halves the traffic. |
| Background slices deferred by Doze/Xiaomi battery rules (`docs/xiaomi.md`) | Resume is state driven, any later trigger (6 h worker, app open, button) continues; the row says "continues automatically". |
| Checkpoint/book divergence after a crash | Book first, checkpoint second, idempotent reports; test `crash_between_book_and_checkpoint_repeats_one_batch`. |
| Old gateway still deployed | D6 legacy detection; compatibility matrix tests. |
| Log privacy leak | Mask at write time on both sides with a shared fixture, no names/bodies by policy + scan test, file only in developer mode, no-backup dir, export only by explicit share. |
| Logging cost on hot paths (incoming call) | Ring append is a short lock; file writer is a bounded async queue; Rust ring uses `try_lock`; pump only in developer mode. Check with `scripts/perf-run.sh` (IncomingPerf) before and after S2. |
| `PeopleStore` move and worker class changes vs R8 | Worker class name and constructor unchanged (`proguard-rules.pro`); `scripts/check-apk.sh` after S6. |
| Provider notifications too chatty for Phone | 3 s coalescing; `PeopleIndex.replace` already recomposes nothing on an unchanged cache. |

Device checklist (release APKs via `scripts/build-apks.sh`, gateway journal open):

1. Deploy S1; run the old app's sync: journal shows `rate limited source=whatsapp`; app shows "Partly synced".
2. Hold the version row 10 s in Contacts and Phone: haptic + toast, Developer group appears; again turns it off.
3. Fresh link, about 500 contacts: row walks "Checking numbers x/y" -> "Getting names" -> "Downloading photos";
   Phone (open on the recents list) gains names and photos in steps without restarting it.
4. Mid-run: force-stop Contacts -> reopen: "Interrupted at x/y" + Continue; continue asks nothing twice (gateway
   request log shows no repeated batch).
5. Mid-run: airplane mode -> "No connection. Paused"; back online, worker or Continue resumes.
6. Force a 429 (temporarily set `USYNC_GAP_MS` low in a test build, or stop/start quickly): row shows the ETA,
   both buttons disabled, journal shows the cooldown, run resumes by itself after the ETA.
7. Disconnect WhatsApp on the gateway mid-run -> "WhatsApp is not connected. Paused"; reconnect -> resumes.
8. Change the contact list between slices (add one, delete one): the new one is asked, the removed one is not.
9. Relink with another instance: checkpoint reset, no Continue offered.
10. Logs screen: filter tag `sync`, search "rate", copy, share to a file; inspect the file: no full phone
    number, no `rwk_` key. Turn developer mode off: `noBackupFilesDir/logs` is empty.
11. Leave overnight: 6 h worker runs an incremental sync in seconds; journal shows a handful of requests.

## 14. Open questions for the user

- **Q1 Pace.** Is roughly 25 minutes for a first sync of 500 contacts acceptable (45 numbers per minute, no
  burst)? It is the safe default; it can be raised later from the logs.
- **Q2 Phone without Contacts.** May Phone keep its own on-disk people cache (same format, app-private, removed on
  unlink) so it can use the same slow, resumable engine? Today it is memory only and unpaced. Default if
  unanswered: yes, as step S10.
- **Q3 "Sync Now" while a resumable run exists** asks "Start over?" first. Is the confirmation wanted, or should
  it restart at once?
- **Q4 Developer mode in the GPUI Messages and Stops apps:** skip until their Kotlin versions (E6/E7), as planned
  here?

### Answers (user, 2026-11-14)

- Q1: yes, keep the safe pace (stability first).
- Q2: yes, Phone keeps its own on-disk people cache (S10).
- Q3: yes, "Sync Now" asks "Start over?" while a resumable run exists.
- Q4: skip GPUI; Messages and Stops get developer mode with their Kotlin versions.
