# People sync v3: the gateway prepares, the phone downloads once

Status: plan (2026-11-16). Base: `62e1642a` on `messages-kotlin`. Supersedes the transport of
`_bmad-output/plans/people-sync-v2.md` (its engine stays as the fallback against an old gateway).
Scope: `crates/rosty_wa_proto`, `crates/rosty_wa_client`, `services/wa_gateway`, `crates/rosty_app_core`,
`crates/rosty_ffi`, `android/{core-bridge,wa,people,contacts,phone}`, `docs/`.

Owner request (2026-11-16): Contacts syncs all the time. The server prepares everything first, then the phone
downloads in bulk as the very last step; data that is already the same is not synced (hashes of the previous data are
kept and compared); the Contacts UI still shows live what the server is doing; periodic sync every 12 h instead of 6 h.

## 0. Facts checked in the code (read before changing anything)

- **The sync never writes to the device's contacts.** No `ContactsContract` write, `applyBatch`, sync adapter or
  account exists in the people sync (`grep` over `android/people`, `android/contacts/.../people`: the only
  `ContentProviderOperation` users are the contact editor, `android/platform/.../contacts/ContactsEdit.kt` and
  `ContactPhotos.kt`). What the sync writes is `people.json` (`crates/rosty_app_core/src/people.rs:25`), the picture
  files in `filesDir/people/` (`PeopleStore.store`, `android/people/.../sync/PeopleStore.kt:95`) and
  `people-sync.json`; readers hear of it through one provider notification (`PeopleStore.notifyChanged`, line 121).
  The settings footer says so ("Nothing is added to your contacts", `settings_people_footer`). In this plan "device
  writes" therefore means: `people.json`, picture files, the provider notification.
- `people.json` is already written only on a real change: `Book::apply` counts `changed` only when `person != before`
  (`people.rs:359-362`), `people.upsert` saves only then (`crates/rosty_ffi/src/ops/people.rs:104`), the sync saves
  only when `book_dirty` (`crates/rosty_ffi/src/ops/people/sync.rs:217`). What is missing today is that the
  *requests* are not avoided.
- whatsapp-rust 0.7.0 sets **no batch size**: `Contacts::is_on_whatsapp` and `get_user_info` put every JID into one
  usync IQ (`whatsapp-rust-0.7.0/src/features/contacts.rs:115-194`, `267-306`). The 50 of `MAX_LOOKUP` /
  `MAX_PROFILES` (`rosty_wa_proto/src/lookup.rs:12`, `meta.rs:14`) is the gateway's route limit. WhatsApp's 429 is
  `IqError::ServerError { code: 429, backoff }` (`request.rs:114-121`), mapped by `contact_failed`
  (`services/wa_gateway/src/wa/session.rs:85`) to `DriverError::RateLimited { retry_after_s }`.
- usync user info gives LID, about, **picture id**, business and verified name (`wacore-0.7.0/src/iq/usync.rs:172-188`),
  not the push name and not "is registered". Push names reach the gateway only with messages and history
  (`profiles.push_name`). So a number needs a lookup (registered, LID, verified name) and a profile query (picture id).
- The gateway's lookup answers live only in memory (`LookupCache`, `services/wa_gateway/src/lib.rs:61-79`, 24 h, lost
  at restart); profiles are in SQLite (`profiles` table, `store.rs:314-326`); the WhatsApp cooldown (`cooldown.rs`)
  and both buckets (`lib.rs:49-58`) are in memory.
- `EventSink::change_profile` publishes a `profile` event for every changed profile (`driver.rs:808-816`);
  `Store::change_profile` (`store/rich.rs:1211`) does not. Contacts answers every `profile` event with an upsert and a
  debounced live picture pass (`PeopleSync.kt:181-187`, `253-263`).
- The client's HTTP agent has one global timeout of 120 s per call (`rosty_wa_client/src/http.rs:841`) and reads JSON
  up to 8 MiB (`JSON_LIMIT`, line 30). A missing route of an old gateway is recognised by
  `from_status_optional` (`error.rs:61`): the router's bare 404 becomes `ErrorCode::NotFound`.
- Gateway migrations: `MIGRATIONS` has 8 entries (`store.rs:228-417`, v8 = account and history columns). The next is **v9**.

## 1. Today's flow and where "all the time" comes from

Engine: `rosty_app_core::people_sync` plans, `PeopleSyncRunner` (`android/people/.../sync/PeopleSyncRunner.kt`)
performs one gateway call per step. Per-contact calls are fired in `PeopleSyncRunner.lookup` (line 422,
`POST /v1/contacts/lookup`, 15 JIDs), `.profiles` (440, `POST /v1/contacts/profiles`, 15 JIDs) and `.picture`
(458, `GET /v1/pictures/{jid}?full=true`, **one call per person**). Pace: 15 JIDs per 20 s for both usync stages,
one picture per 3 s (`people_sync.rs:47-51`).

Calls per run for N numbers, W of them on WhatsApp, P with a picture, P0 = W - P without:

| Run | Lookup calls | Profile calls | Picture calls | N=1000, W=700, P=450 |
|---|---|---|---|---|
| First run / Sync Now | ceil(N/15) (numbers not yet known as on WhatsApp, `lookup_work` line 843) | ceil(W/15) | min(P + P0, 300) (`MAX_PICTURES`) | 67 + 47 + 300 = 414 calls, about 53 min at the base pace, the rest of the pictures in later runs |
| Periodic run, every 6 h (incremental) | ceil((N-W)/15) once per 7 days (`NEGATIVE_TTL_MS`) | ceil(W/15) once per 24 h (`PROFILE_FRESH_MS`): one run in four | **P0 on about three runs in four** + P once per 7 days | 250 picture calls x 3 s = 12.5 min, three times a day; 47 profile calls once a day; 20 lookups once a week |

The P0 term is the main cause: `Book::plan` asks again for every person *without* a picture when the last check is
older than `NONE_FOR_MS` = 6 h (`people.rs:454`, constant from `wa_link/avatars.rs:21`), which is exactly the period.
None of these calls reaches WhatsApp (the gateway answers a fresh "none" from its store, `rich_routes.rs:465-473`),
but each is a paced request, so the run (worker, notification or 8-minute slices with a continuation every 60 s)
lasts many minutes while nothing changes.

Triggers today:

1. Periodic work every 6 h, network and battery not low (`PeopleSyncWorker.request`, `PeopleSyncWorker.kt:70-74`,
   `KEEP` at line 88).
2. **Every `onResume` of Contacts** (`MainActivity.kt:45-49` -> `PeopleSync.syncIfStale`, `PeopleSync.kt:214-226`):
   starts when the last finished run is 6 h old, **or whenever a resumable run exists**. A run the user stopped is
   resumable (`interrupted(cancelled)`), so after "Stop" every return to the app starts the sync again.
3. The continuation work: 60 s after every slice that did not finish, at the limit's end after a 429, 15 min when
   offline (`ForegroundSyncPlan.continueInMs`, `ForegroundSyncPlan.kt:86-111`).
4. "Sync Now" / "Continue" (`PeopleSync.kt:191-194`).
5. Every live `profile` event: an upsert plus a picture pass after 2 s (`PeopleSync.kt:181-187`).
6. Phone without Contacts: **every time the link becomes `Connected`** (key = instance id + state,
   `StandalonePeople.kt:100-114`), so every reconnect starts a run.
7. There is no contacts-changed observer in the people sync (the only observers are `ProviderChanges.kt:42` and
   `PeopleSource.kt:53`, both readers). New numbers are picked up at the next run.

Every start goes through `ForegroundPeopleSyncWorker`, which first asks to be a foreground service
(`ForegroundPeopleSyncWorker.kt:86`), so each of them can put up the ongoing notification.

Outside the sync, single lookups stay: `LiveWaNames` (`android/people/.../LiveWaNames.kt:145`, `162`: a caller or
sender nobody owns, one lookup and one profile per number per 6 h, paused on a 429 since `62e1642a`), the contact card
(`CardWaPort.kt:51`, `69`) and New Conversation (`NewConversationViewModel.kt:165`). They are user-driven and are not
part of this round; the burst of refused `/v1/contacts/lookup` calls in the gateway log was `LiveWaNames`, fixed in
`62e1642a`.

## 2. Design decisions

| # | Decision | Rejected, why |
|---|---|---|
| D1 | One **server-side job per instance** does lookups, profiles and pictures; the phone starts it, watches it and downloads the result. The job writes through `Store::change_profile` and the lookup store directly, so it publishes **no** `profile` events. | Publishing them: a first run would send hundreds of events through the 256-slot channel (`hub.rs:15`), force `resync` on slow sockets and trigger the app's per-contact live picture pass. |
| D2 | The phone sends at **start**: E.164 numbers in its order, each with the hash of the last synced result (`h`) and the picture id whose file it holds (`p`). | Hashes at download: the server would not know which picture bytes to fetch and would fetch all of them. Hashes of numbers instead of numbers: the gateway must send the numbers to WhatsApp anyway, and it is the owner's own server. |
| D3 | The hash is computed **only on the gateway** and stored opaquely on the device. | Computing on both sides: two implementations to keep byte-identical across app versions. A changed algorithm then only costs one data re-download (pictures are protected by `p`). |
| D4 | Live state by **long-poll `GET`** on the job, not by the event socket. | Events: lost on a reconnect (a `GET` is needed anyway), need the socket up in a cold worker process, and add a wire event old clients must skip. A long-poll returns at once on every change and costs no traffic otherwise. |
| D5 | The result is one **bundle**: JSON lines, a picture's raw bytes right after the line that announces them. Served in parts of at most 8 MiB, resumed by `after=<seq>`. A normal run is one request. | JSON with base64: +33 % and the 8 MiB JSON limit. Zip: needs a zip writer on the gateway and cannot be resumed by record. One unbounded response: a first run with pictures is tens of MiB and would break the client's 120 s call timeout. |
| D6 | The gateway sends the full picture as WhatsApp gave it; the phone downscales to 512 px as today (`PeoplePictureCodec`). | Downscaling on the gateway: a new image decoder on the server for untrusted bytes and a larger Raspberry Pi build, for about half the bytes of a transfer that happens once per picture. |
| D7 | The device planner stays in Rust (`people_sync.rs`) and gains a job mode; the v2 steps remain as the **legacy mode** for a gateway without the routes. Kotlin still only performs. | A second Kotlin state machine: against "domain logic in platform-free crates". |
| D8 | Only a run the **user** started (Sync Now, Continue) becomes a foreground service with the notification. Automatic runs are silent and short: start the job, watch 90 s, come back when the gateway says it will be ready. | Foreground for every start (v2 S11): the notification every few hours is part of "all the time", and the phone has nothing to do while the gateway works. |
| D9 | "Stop" cancels the job on the gateway. Resuming is cheap because the gateway keeps every answer it got (lookups in a new table, profiles as today). | Keeping a stopped job alive: it would keep asking WhatsApp after the user said stop. |

## 3. The server-side job

### 3.1 REST (all new, all under the connect key; `rosty_wa_proto::api::routes`)

| Route | Meaning |
|---|---|
| `POST /v1/people/sync` (`PEOPLE_SYNC`) | Start or join. Body `PeopleSyncStart`. Answers 200 `PeopleJobStatus`. |
| `GET /v1/people/sync/{job_id}?rev=<n>&wait_ms=<ms>` (`PEOPLE_SYNC_JOB`) | The job's status. With `rev` equal to the current one and a job that is `running` or `waiting`, the answer waits up to `wait_ms` (cap `PEOPLE_WAIT_MAX_MS` = 25 000) for the next change. Unknown, expired or wiped job: 404 `not_found` with an `ApiError` body. |
| `GET /v1/people/sync/{job_id}/bundle?after=<seq>&max_bytes=<n>` (`PEOPLE_SYNC_BUNDLE`) | The result (3.5). 409 `conflict` unless the job is `ready`. |
| `DELETE /v1/people/sync/{job_id}` | Cancel a running job, or drop a finished one (the phone's "done"). 204, also when it is already gone. |

```json
// PeopleSyncStart
{"mode": "auto", "entries": [{"n": "+905321234567", "h": "9a41c0de5b7e1f02", "p": "1716839201"},
                             {"n": "+905559876543"}]}
// PeopleJobStatus
{"job_id": "5f0c9a1b22d4e7a3", "rev": 42, "mine": true,
 "state": "running", "phase": "profiles", "done": 120, "total": 340, "numbers": 812,
 "wait": {"until_ms": 1795000000000, "source": "whatsapp"},
 "eta_ms": 95000,
 "result": {"records": 3, "pictures": 1, "bytes": 61234},
 "error": {"code": "expired", "message": "..."},
 "account_epoch": 3, "created_ms": 1794999000000, "updated_ms": 1794999100000, "expires_ms": 1795085500000}
```

- `mode`: `auto` (the gateway's freshness rules, 3.3) or `full` ("Sync Now": everything is asked again). An unknown
  value reads as `auto`.
- `entries`: at most `MAX_PEOPLE_NUMBERS` = 5 000 (more is 400 `bad_request`); `n` must be `+` and 7 to 15 digits
  (the rule of `people_sync::jid_of`, `people_sync.rs:697`), anything else is skipped; repeats keep the first. `h` is
  the hash the device stored for that number (absent: nothing stored), `p` the picture id whose file is on the
  device (absent: no usable file). `numbers` in the status is how many were accepted.
- `state`: `running`, `waiting` (with `wait`), `ready` (with `result`), `failed` (with `error`), `cancelled`.
  `phase`: `lookup`, `profiles`, `pictures`, `bundle`; `done`/`total` belong to the phase. `wait.source`:
  `whatsapp` (WhatsApp's 429 or the running cooldown), `gateway` (the gateway's own bucket), `not_connected` (the
  WhatsApp session is down; the job tries again every 60 s). `eta_ms` is an estimate (left batches x gap + left
  pictures x gap + wait left). Every enum has an `other` catch-all (`#[serde(other)]`, as `LimitSource::Other`,
  `api.rs:485-487`); every optional field is `#[serde(default, skip_serializing_if = "Option::is_none")]`.
- `mine` is only meaningful on the start answer (below); elsewhere it is true.

Job id: 16 hex digits from `getrandom`, as `hub.rs:36-42` makes the epoch.

**Idempotency, one job per instance.** The start request has a digest (SHA-256 over mode and the accepted
`n|h|p` lines in order).
- No job, or a job in `failed` / `cancelled` / `ready` with another digest: the old one is dropped (rows and spool) and
  a new job starts.
- A job `running` or `waiting` with the same digest: joined, its status is returned (`mine: true`).
- A job `ready` with the same digest and not expired: returned as it is (a phone that lost its checkpoint downloads
  it).
- A job `running` or `waiting` with **another** digest (a second device on the same instance): its status is returned
  with `mine: false`; nothing is changed. That caller watches it and starts its own when it is over.

The phone normally never posts twice: it keeps `job_id` in its checkpoint and resumes by `GET`.

### 3.2 Persistence: store migration v9, backup

New file `services/wa_gateway/src/store/people.rs`; migration **v9** appended to `MIGRATIONS` (`store.rs:228`):

```sql
CREATE TABLE people_jobs (
    instance_id TEXT PRIMARY KEY REFERENCES instances(id) ON DELETE CASCADE,
    job_id TEXT NOT NULL,
    digest TEXT NOT NULL,
    mode TEXT NOT NULL,
    state TEXT NOT NULL,
    phase TEXT NOT NULL,
    rev INTEGER NOT NULL DEFAULT 0,
    wait_until_ms INTEGER,
    wait_source TEXT,
    error_code TEXT,
    error_message TEXT,
    result_records INTEGER,
    result_pictures INTEGER,
    result_bytes INTEGER,
    account_epoch INTEGER NOT NULL DEFAULT 0,
    created_ms INTEGER NOT NULL,
    updated_ms INTEGER NOT NULL,
    finished_ms INTEGER
);
CREATE TABLE people_job_numbers (
    instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,            -- the device's order, from 1; the bundle's cursor
    jid TEXT NOT NULL,               -- <digits>@s.whatsapp.net
    have_hash TEXT,
    have_picture TEXT,
    looked INTEGER NOT NULL DEFAULT 0,
    profiled INTEGER NOT NULL DEFAULT 0,
    pictured INTEGER NOT NULL DEFAULT 0,   -- 0 to do, 1 done or not needed, 2 failed
    PRIMARY KEY (instance_id, seq)
);
CREATE TABLE people_lookups (
    instance_id TEXT NOT NULL REFERENCES instances(id) ON DELETE CASCADE,
    jid TEXT NOT NULL,
    on_whatsapp INTEGER NOT NULL,
    business INTEGER NOT NULL DEFAULT 0,
    verified_name TEXT,
    checked_ms INTEGER NOT NULL,
    PRIMARY KEY (instance_id, jid)
);
```

- The primary key of `people_jobs` is the instance: one job per instance by construction.
- `people_lookups` is the durable form of the lookup answers (today memory only), so a restart, a cancel or a new job
  never asks WhatsApp twice for a fresh answer. Rows older than 30 days that no job row names are pruned when a job
  starts.
- Picture bytes wait in a spool directory `instances/<id>/people-job/<seq>.bin` (new `Config::people_job_dir`, next to
  `pictures_dir`, `config.rs:176`), at most `SPOOL_MAX_BYTES` = 256 MiB per job; beyond it a picture is left for the
  next job (`picture_missing`, 3.5). The LRU picture cache (`MediaCache`, 64 MiB) is read as a source but not relied on.
- **Database backup: yes, take one before deploying** (the project's rule for every migration, as for v7 and v8):
  `sqlite3 /var/lib/wa-gateway/gateway.db ".backup gateway-v8.db"`. The migration only creates three tables; no
  existing row is touched, and `rollback` to the previous binary fails on a v9 file ("newer than this gateway",
  `store.rs:423-427`), so the backup is the way back.
- **Restart.** `main.rs` (after `restart_instances`, line 63) calls `people_job::resume_all(&state)`: every job stored
  `running` or `waiting` gets its task again and continues from the per-number flags; a stored `wait_until_ms` in the
  future is slept out first (the in-memory cooldown is gone after a restart). `rev` continues from the row.
- **Account change** (`e6wa-r2.md`, "Account"): add the three tables to `ACCOUNT_TABLES` (`store/account.rs:13`), so
  the wipe transaction of `link_account` deletes them, and add `"people-job"` to `ACCOUNT_CACHE_DIRS`
  (`wa/session.rs:373`), so the spool goes with the media and pictures before `account_changed` is announced. The job
  task needs no signal: every write is `UPDATE ... WHERE instance_id = ?1 AND job_id = ?2`, and a write that changes
  0 rows ends the task. The phone's next `GET` answers 404 and it starts again (4.3).
- **Instance delete** (`routes.rs:236-247`): `ON DELETE CASCADE` removes the rows; add `state.forget_people(&instance)`
  (aborts the task, removes the spool) next to `forget_lookups`.
- **Epoch.** A gateway restart changes the process epoch; the job does not depend on it (the job id and `rev` are
  stored), so the phone's long-poll simply continues.

### 3.3 What the job computes, and its freshness rules

Per accepted number, from the gateway's own stores at bundle time:

| Field | Source |
|---|---|
| `on_whatsapp`, `verified_name` | `people_lookups` (from `WaDriver::lookup_users`, `wa/session.rs:1486`) |
| `jid` (phone JID), `lid` | `Store::profiles` (`store/rich.rs:1173`, fills `lid` from the identity pairs) |
| `push_name` | `profiles.push_name` (learned from messages and history, not from this job) |
| `verified_name` (business) | `profiles.verified_name`, else the lookup's |
| `picture_id` | `profiles.picture_id` (from `WaDriver::profiles`, `wa/session.rs:1723`) |
| picture bytes | `WaDriver::fetch_picture(jid, preview = false, known_id = None)` (`wa/session.rs:1788`), full size |

Work per phase (a number is skipped when its answer is fresh, so an unchanged run asks WhatsApp little or nothing):

- **lookup**: numbers with no `people_lookups` row; `auto`: also negatives older than 7 days and positives older than
  30 days; `full`: every number.
- **profiles**: numbers on WhatsApp whose `profiles.fetched_ms` is 0 or older than `PROFILE_TTL_MS` (24 h,
  `rich_routes.rs:31`; the rule of `profile_is_stale`, line 288); `full`: all of them.
- **pictures**: numbers on WhatsApp with a `picture_id` that differs from the device's `p`. Bytes come from the
  picture cache when it holds that id (`picture_key(jid, false, id)`, `rich_routes.rs:436`), else from WhatsApp. A
  number with no picture id costs nothing (usync is trusted; see risk R2).

The usync batch (`USYNC_BATCH` 15) is far below the 50 the routes allow and the library has no limit of its own.

### 3.4 Pacing, limits, cancel, expiry

New module `services/wa_gateway/src/people_job.rs`; its pacer is a pure struct with the v2 numbers (copied from
`people_sync.rs:47-64`, not shared, so the app planner is not touched):

- usync (lookups and profiles share it): 15 JIDs per request, 20 s between requests, +-20 % jitter; pictures: one per
  3 s, +-20 %.
- Before every request the job takes the instance's existing allowance: `AppState::lookup_cached` (`lib.rs:246`) for
  lookups (which also answers from the in-memory cache and is filled back with `lookup_store`, line 273) and
  `charge_profiles` (line 284) for profiles and pictures. A `Refused::Bucket` or `Refused::Cooldown` makes the job
  `waiting` until `retry_after_ms` (source `gateway` or `whatsapp`). So the job, the interactive routes and an old app
  share one budget and one cooldown.
- WhatsApp's 429 (`DriverError::RateLimited { retry_after_s }`): `AppState::note_upstream_limit` (`lib.rs:307`) starts
  or extends the cooldown (at least WhatsApp's `backoff`, at least 60 s doubling per strike, cap 30 min,
  `cooldown.rs:39-53`); the job becomes `waiting { until_ms, source: whatsapp }`, halves its batch (floor 5), doubles
  its gap (cap 5 min) and recovers as v2 does (five successes clear the strikes, then 10 % per success). The same
  batch is asked again after the wait. Nothing is sent before `until_ms`.
- `DriverError::NotConnected`: `waiting { source: not_connected }`, retried every 60 s.
- Any other driver error: the batch is retried twice (after the gap), then the job is `failed { code: "whatsapp" }`.
  A single failed picture does not fail the job: `pictured = 2`, the record goes out with `picture_missing`.
- Every state, phase or progress change raises `rev` and wakes the long-polls (a `tokio::sync::watch` per instance in
  `AppState`, next to `lookups`, `lib.rs:157`); progress is stored at most once per batch.
- **Cancel** (`DELETE` while `running`/`waiting`): state `cancelled`, the task stops after the request in flight, the
  spool is deleted. Answers already stored stay (that is the resume).
- **Expiry.** A job older than 6 h that is not `ready` becomes `failed { code: "expired" }`. A `ready`, `failed` or
  `cancelled` job is deleted 24 h after `finished_ms` (`expires_ms` in the status), checked when a job starts, on
  `GET` and hourly by the job module's own tick.
- Logging: job start, phase ends, waits and the end at INFO as `instance=<id> people job ...` with counts only; no
  number or JID (the rule of `request_log.rs`).

### 3.5 Hash and bundle

**Hash** (`rosty_wa_proto::people::people_hash`, used only by the gateway, pinned by a test vector): SHA-256 over
the ASCII line `rosty-people-v1\n` followed by seven fields, each written as `<byte length>:<bytes>,`: number
(E.164), `1`/`0` for on WhatsApp, phone JID, LID JID, push name, verified name, picture id; an absent field is the
empty string, names are the trimmed strings the store holds. The hash is the first 8 bytes in lower-case hex
(16 characters). These are exactly the fields that reach `people.json` (`Person`, `people.rs:42-76`); `about` and
`business` are not stored on the device and are not hashed. The length prefix makes the encoding unambiguous
whatever a name contains.

**A number is in the bundle iff** its hash differs from the device's `h`, **or** it has a `picture_id` that differs
from the device's `p`. Its picture bytes are in the bundle iff the second holds and the gateway has them. Numbers not
on WhatsApp are records too (about 70 bytes), so the device can store their hash and never hears of them again.

**Format** (`Content-Type: application/x-rosty-people-bundle`): UTF-8 JSON lines, each ended by `\n`; a record with
`picture_bytes: n` is followed by exactly n raw bytes and one `\n`.

```
{"v":1,"job_id":"5f0c9a1b22d4e7a3","after":0}
{"seq":17,"number":"+905321234567","hash":"9a41c0de5b7e1f02","on_whatsapp":true,"jid":"905321234567@s.whatsapp.net","lid":"123456@lid","push_name":"Ayse","picture_id":"1716839201","picture_bytes":61234}
<61234 bytes>
{"seq":18,"number":"+905559876543","hash":"0c11d2e3f4a5b6c7","on_whatsapp":false}
{"seq":40,"number":"+905550000000","hash":"77aa...","on_whatsapp":true,"jid":"905550000000@s.whatsapp.net","picture_id":"1716","picture_missing":true}
{"end":true,"last_seq":812,"more":false}
```

- Records come in `seq` order, only those with `seq > after`. The gateway stops adding records once the part passes
  `max_bytes` (default and cap 8 MiB; at least one record per part) and ends with `"more": true` and the last `seq`
  it sent; the phone asks again with `after = last_seq`. A part without its end line was cut: the phone keeps what it
  committed and asks again from its own cursor.
- 8 MiB fits the client's 120 s call timeout down to about 0.6 Mbit/s. The gateway builds a part in memory (8 MiB plus
  one picture of at most 4 MiB, `wa/picture.rs:12`).

**Sizes for 1 000 contacts** (700 on WhatsApp, 450 with a picture, a full picture 60 to 100 KiB):

| Case | Upload (start) | Download (bundle) |
|---|---|---|
| First run, data only | about 20 KB | 700 x ~230 B + 300 x ~70 B = about 180 KB, one part |
| First run with pictures | about 20 KB | 27 to 45 MiB in 4 to 6 parts |
| Unchanged run | about 60 KB (number, hash, picture id) | 2 lines, about 100 bytes; no file write for `people.json`, no picture, no provider notification |
| A usual day (5 changes, 2 new pictures) | about 60 KB | about 150 KiB, one part |

## 4. The device

### 4.1 Hashes: where they live, what invalidates them

`people-sync.json` (`Checkpoint`, `people_sync.rs:94-112`; already per link, reset when `LinkId` changes, line
714-724) gains, all `#[serde(default)]` so `SCHEMA_VERSION` stays 1 and an older build still reads the file:

```rust
pub struct Remote {
    /// None: not asked yet. Some(false): the gateway has no job routes (asked again after REMOTE_RETRY_MS = 24 h).
    pub support: Option<bool>,
    pub unsupported_ms: i64,
    /// phone JID -> what the gateway last delivered for that number.
    pub synced: BTreeMap<String, Synced>,
    /// The account epoch the hashes belong to (`PeopleJobStatus.account_epoch`).
    pub account_epoch: Option<u32>,
    /// When a run was last started (the trigger debounce, section 6).
    pub attempt_ms: i64,
}
pub struct Synced { pub hash: String, pub on_whatsapp: bool }
pub struct JobRef { pub id: String, pub after: u32, pub rev: u64, pub status: Option<JobView> }   // in `Run.job`
```

2 000 numbers are about 150 KB, inside `MAX_FILE_BYTES` (2 MiB). Rules, all in the planner:

- `h` is sent for a number only when the stored state still stands: a `Synced` with `on_whatsapp` needs a person in the
  book that is on WhatsApp under that number; otherwise no hash is sent (the book lost the person: `MAX_PEOPLE`
  eviction, a damaged `people.json`).
- `p` is the book's `picture_id` only when its file is on disk, not stale and of the current `PICTURE_FORMAT` (the
  test `known_id` uses today, `people_sync.rs:1051-1058`). The files are listed first (`Step::PlanPictures`).
- `synced` is pruned to the current numbers at `begin` (as `prune` does for negatives, `people_sync.rs:824`).
- Another link (`LinkId`) clears `Remote` with the rest. Another `account_epoch` clears `synced` and the job; the book
  stays (names and pictures belong to numbers, not to the account).
- **The user's own contact data is never hashed and never written.** Renaming a contact changes nothing for the
  sync. A new number has no hash, so it is looked up at the next run; a removed number is pruned from `synced`.
- A live `profile` event may update the book between runs; the stored hash is then older than the book. The next
  bundle carries that record, the upsert finds nothing to change (`changed == 0`, no file write), and only the hash
  is updated.

### 4.2 Zero writes for unchanged entries

A bundle part is committed as one `Report::Bundle`: the planner turns its records into `people::Update`s, calls
`Book::upsert` once, and `people.json` is saved once per part and only when `changed > 0` or files were dropped
(the existing `book_dirty` rule). The provider notification (`onPart`) fires only then. An unchanged run has no
records: `people.json`, the picture files and the provider are untouched; `people-sync.json` is written (the run's
bookkeeping and `last`). Record to update: `number`, `jid`, `lid`, `push_name`, `verified_name`,
`on_whatsapp`; `picture_id` with `picture_file` when the picture was stored, `picture_id` alone when it was not
(marks it stale, the old file keeps showing), `picture_removed` when the record is on WhatsApp without a
`picture_id`. A record `on_whatsapp: false` for a number in the book sets `on_whatsapp = false` and keeps the person.

### 4.3 Planner steps (additive to `Step` / `Report`, `people_sync.rs:557-640`)

Job mode is used when `remote.support != Some(false)` (or the re-probe is due) and the start is not `LivePictures`.

1. `Step::PlanPictures` -> `Report::Files` (as today; gives the files on disk and the orphans to delete).
2. No `run.job`: `Step::JobStart { mode, entries }` (`mode` = `full` for `Start::Restart`, else `auto`).
   Reports: `JobStarted { status }`; `JobUnsupported` (the gateway answered `not_found` to the POST) ->
   `remote.support = Some(false)`, the run continues in legacy mode at `Stage::Lookup`, unchanged v2 code;
   `RateLimited` / `Error` as today. A status with `mine: false` is watched (step 3) and, once that job is over,
   `run.job` is cleared and step 2 runs again.
3. `Step::JobWatch { job_id, rev, wait_ms }` -> `Report::Job { status }` or `Report::JobGone` (404: clear `run.job`,
   back to 2, at most twice per slice). `running` / `waiting`: stage and progress are updated and the next
   `JobWatch` follows; when the slice budget ends the slice stops `interrupted(server_working)` (new
   `Interrupt::ServerWorking`; the job goes on without the phone). `failed`: `Stopped::Failed { Gateway, message }`.
   `cancelled`: `interrupted(cancelled)`. `ready`: step 4.
4. `Step::JobFetch { job_id, after, max_bytes }` -> `Report::Bundle { job_id, records, last_seq, more }`: commit
   (4.2), `synced` updated, `job.after = last_seq`. `more`: again. A cut download is `Report::Error(Network)`:
   `interrupted(network)`, the cursor stays.
5. `Step::JobDone { job_id }` (Kotlin sends the `DELETE`, errors ignored) -> `Report::JobClosed` -> `finish`
   (`people_sync.rs:1124`). `Finished` gains `changed: u32` and `bytes: u64` (`#[serde(default)]`).

`Stage` gains `Server` and `Download`; `RunStatus` gains `job: Option<JobView>` (`phase`, `done`, `total`,
`state`, `wait_until_ms`, `wait_source`, `eta_ms`, `records`, `pictures`, `bytes`) for the row. `Pace`, `negatives`
and `profiled_ms` are not used in job mode and stay for the legacy mode.

Start semantics: `Auto` resumes a stored job by id (no POST) or starts one; `Resume` the same but refuses when there
is nothing; `Restart` cancels a stored job (`JobDone`) and starts a `full` one.

Legacy mode gets one fix: `Book::plan` re-asks a person without a picture after `REVALIDATE_MS` (7 days) instead of
`NONE_FOR_MS` (6 h) (`people.rs:454`), which removes the P0 term of section 1 against an old gateway too.

### 4.4 Kotlin

- `WaOps` / `WaGateway` (`android/core-bridge/.../ops/WaOps.kt:236`, `WaGateway.kt:158`): `peopleSyncStart`,
  `peopleSyncStatus`, `peopleSyncBundle`, `peopleSyncCancel` over the ops `wa.people_sync_start`,
  `wa.people_sync_status`, `wa.people_sync_bundle`, `wa.people_sync_cancel`.
- `wa.people_sync_bundle {handle, job_id, after, max_bytes}` reads the part as a stream
  (`resp.body_mut().with_config().limit(..).reader()`, ureq 3.5 `body/mod.rs:421`, `502`, `557`), writes each
  picture to `<cache_dir>/people-bundle/<job_id>-<seq>.bin` and answers `{records: [... , picture_path?], last_seq,
  more}` (no bytes over JNI). A part without the end line is a `network` error.
- `PeopleSyncRunner.perform` (`PeopleSyncRunner.kt:372`) gains the four steps. For `JobFetch` it downscales and stores
  each picture exactly as `saveBytes` does today (line 496: `codec.downscale(bytes, PICTURE_PX)`, `store.store`),
  deletes the temp file, and reports the record with `picture: stored{file} | failed | none`. The
  `people-bundle` folder is emptied when a slice begins.
- `cancel()` (the user's Stop, line 158) also sends `peopleSyncCancel(jobId)` from a non-cancellable launch; the
  pending long-poll returns at once because the cancel raises `rev`. A system stop (worker budget) does **not** cancel
  the job.
- `ForegroundPeopleSyncWorker.doWork` promotes to foreground only for `START_RESTART` and `START_RESUME` (D8). For
  `START_AUTO` it runs one slice of `QUIET_WATCH_MS` = 90 s (8 min when the app asked because the settings page is
  open, input `KEY_ATTENDED`). `ForegroundSyncPlan.continueInMs`: `interrupted(server_working)` -> the status's
  `eta_ms` clamped to 1..30 min.
- Live `profile` events: unchanged (an upsert that writes only on a change, then the single-picture pass), and skipped
  while a slice is active, as today.

## 5. Live state in the Contacts UI

The runner's `RunnerState` carries the last `JobView`; `PeopleSyncStatus` (`PeopleSync.kt:41-88`) gains

```kotlin
/** The gateway is working; [watched]: a slice is following it live. */
data class Preparing(val phase: String, val done: Int, val total: Int, val waitUntilMs: Long?, val waitSource: String?, val watched: Boolean)
data class Downloading(val done: Int, val total: Int)
```

and `Idle` gains `changed: Int?` (of the last run). While the settings page shows the row and the status is
`Preparing(watched = false)`, the view model asks once for an attended slice (`PeopleSyncControl.attend()` ->
`request(START_AUTO)` with `KEY_ATTENDED`), so the row goes live without a notification. `peopleSyncUi`
(`PeopleSyncWords.kt:13`) maps:

| State | Line (en) | Line (tr) | Bar | Buttons |
|---|---|---|---|---|
| Preparing, lookup | Gateway: checking numbers %1$d/%2$d | Ağ geçidi: numaralar denetleniyor %1$d/%2$d | done/total | Stop |
| Preparing, profiles | Gateway: getting names %1$d/%2$d | Ağ geçidi: adlar alınıyor %1$d/%2$d | done/total | Stop |
| Preparing, pictures | Gateway: getting photos %1$d/%2$d | Ağ geçidi: fotoğraflar alınıyor %1$d/%2$d | done/total | Stop |
| Preparing, wait `whatsapp`/`gateway` | Gateway is waiting for WhatsApp\'s limit. Continues in %1$s | Ağ geçidi WhatsApp sınırını bekliyor. %1$s sonra devam edecek | kept | Stop |
| Preparing, wait `not_connected` | Gateway: WhatsApp is not connected. Waiting | Ağ geçidi: WhatsApp bağlı değil. Bekleniyor | kept | Stop |
| Preparing, not watched | Being prepared on the gateway. Downloads when ready | Ağ geçidinde hazırlanıyor. Hazır olunca indirilecek | none | Stop |
| Downloading | Downloading changes %1$d/%2$d | Değişiklikler indiriliyor %1$d/%2$d | done/total | Stop |
| Idle, last run changed nothing | Synced, %1$s. Nothing changed | Eşitlendi, %1$s. Değişiklik yok | none | Sync Now |
| Idle, last run changed n | Synced, %1$s. %2$s (plural `settings_people_changes`: "%d change" / "%d changes") | Eşitlendi, %1$s. %2$s ("%d değişiklik") | none | Sync Now |

String names: `settings_people_server_lookup|profiles|pictures|limit|offline|quiet`, `settings_people_downloading`,
`settings_people_synced_unchanged`, `settings_people_synced_changed`, plural `settings_people_changes`
(`android/contacts/src/main/res/values/strings.xml` and `values-tr`). The minutes use the existing
`settings_people_minutes`. All other rows (never, connecting, the legacy stages, interrupted, failed) stay. The footer
loses its last sentence about slowness only if the owner wants (not in this round).

The ongoing notification (`SyncNotice`, `SyncNotices`; only for user-started runs) gains `Line.Server(phase, done,
total)` and `Line.Download(done, total)`; a wait uses the existing `Line.Limit`. Strings in
`android/people/src/main/res/values{,-tr}/strings.xml`: `people_sync_notice_server_lookup` "Gateway is checking
numbers %1$d/%2$d" / "Ağ geçidi numaraları denetliyor %1$d/%2$d", `..._server_profiles` "Gateway is getting names
%1$d/%2$d" / "Ağ geçidi adları alıyor %1$d/%2$d", `..._server_pictures` "Gateway is getting photos %1$d/%2$d" /
"Ağ geçidi fotoğrafları alıyor %1$d/%2$d", `people_sync_notice_download` "Downloading changes %1$d/%2$d" /
"Değişiklikler indiriliyor %1$d/%2$d".

## 6. Triggers and schedule

| Trigger | v3 rule |
|---|---|
| Periodic | `PeopleSync.PERIOD_MS = 12L * 3_600_000` (`PeopleSync.kt:341`). `PeopleSyncWorker.schedule` uses `ExistingPeriodicWorkPolicy.UPDATE` instead of `KEEP` (`PeopleSyncWorker.kt:88`; WorkManager 2.11, `gradle/libs.versions.toml:20`), otherwise installed apps keep the stored 6 h request for ever. Constraints unchanged: network connected, battery not low. Silent (D8). |
| App to the foreground (`MainActivity.onResume`) | Starts only when the last finished run is at least 12 h old (or there is none) **and** the last attempt (`remote.attempt_ms`) is at least 30 min old **and** the stored run was not stopped by the user (`interrupted(cancelled)`) and is not failed. It no longer resumes a resumable run: the continuation work does that. |
| Continuation work | `server_working`: at the gateway's `eta_ms` (1 to 30 min); a cut download: 60 s; offline: 15 min; a user stop: nothing. |
| Sync Now / Continue | As today; foreground with the notification. |
| Link connected (Phone without Contacts, `StandalonePeople.kt:100-114`) | A new instance id: one start. A reconnect: the foreground rule above (12 h and 30 min). |
| Contacts changed | No trigger (as today). The next run sends the new number without a hash. |
| Live `profile` event | The book upsert and the single-picture pass, as today. |

The decision is one pure function in `:people`, `PeopleSyncTriggers.shouldStart(kind, nowMs, lastFinishedMs,
lastAttemptMs, run)`, used by `PeopleSync.syncIfStale` and `StandalonePeople`.

Result: at most two automatic runs a day, each a 60 KB upload, a job that mostly finds fresh answers, and a
100-byte download; no notification unless the user pressed a button.

## 7. Compatibility

| App | Gateway | Result |
|---|---|---|
| new | old (no job routes) | `POST /v1/people/sync` hits the router's bare 404; the client maps it with `from_status_optional` (`error.rs:61`) to `not_found`; the planner records `support = Some(false)` and runs the v2 flow: 15 JIDs per 20 s, one picture per 3 s, a 429 honoured with `retry_after_ms` (`people_sync.rs:1448`), now without the 6-hourly "no picture" calls and under the new trigger rules. Asked again after 24 h. |
| old | new | Nothing changes for it: the three contact routes, their buckets, the cooldown and the events are as before; there is no new event. Its per-contact sync and a new app's job on the same instance share the cooldown, so they cannot add up. |
| new | new, restarted or account changed mid-job | Restart: the job resumes, the long-poll continues. Account change: `GET` 404 -> the phone starts again; another `account_epoch` clears its hashes. |
| new, checkpoint from v2 | new | `Remote` is absent and reads as default: the first run sends no hashes and downloads the data once; pictures already on disk are sent as `p` and are not downloaded again. |

Wire rules: only new routes and new types; no existing type changes. Pinned JSON tests in `rosty_wa_proto` for
`PeopleSyncStart`, `PeopleJobStatus` (every state), a bundle part, and "a build that knows fewer fields or enum values
still parses" (the pattern of `api.rs:647-661`). The planner's new `Stage`, `Step`, `Report` and `Interrupt` values
are additive on the FFI; Kotlin's strict decoder gets them in the same change (step 6).

## 8. Steps (one story each)

| Step | What | Layers | Tests |
|---|---|---|---|
| 1 | Wire: module `rosty_wa_proto::people` (types of 3.1, `people_hash`, start digest, bundle writer and streaming reader, the constants), the three route templates; client: `people_sync_start`, `people_sync_status(job, rev, wait_ms)`, `people_sync_bundle(job, after, max_bytes, on_record)`, `people_sync_cancel`, the start mapped with `from_status_optional`. | proto, client | Pinned JSON for every type and state; hash vector (fixed input -> fixed 16 hex), fields with `\n`, `,` and empty names do not collide; bundle round trip, a cut part (no end line) is an error, `more` paging; client loopback tests (`rosty_wa_client/tests/loopback.rs`) incl. the bare 404. |
| 2 | Gateway store: migration v9, `store/people.rs` (start/replace a job, numbers, flags, lookups, status, counts, expiry prune), the three tables in `ACCOUNT_TABLES`, `people-job` in `ACCOUNT_CACHE_DIRS`, `Config::people_job_dir`. Own commit. | `services/wa_gateway` | Migration from a v8 file (pattern of `store.rs:1520`), idempotent; one job per instance; the account wipe and the instance delete remove rows; prune of old lookups. |
| 3 | Gateway job engine: `people_job.rs` (pure pacer and work selection; the task; `resume_all`; cancel; expiry; the `watch` channel in `AppState`; `forget_people`). Own commit. | `services/wa_gateway` | With `FakeDriver` (`driver.rs:1699`, `1794`, `1816`, scripted failures): a full job reaches `ready`; fresh answers cause zero driver calls; a 429 with and without `backoff` waits at least the hint and never sends early; bucket refusal; `not_connected` then recovery; a failed picture; cancel mid-phase; restart resumes from the flags; a wiped account ends the task; pacer unit tests with a fake clock (tokio paused time). |
| 4 | Gateway routes: `people_routes.rs` (start/join by digest, long-poll, bundle parts, delete), `docs/whatsapp-gateway.md` section "People sync job". Own commit. | `services/wa_gateway`, docs | `tests/api.rs`: start, join (same digest), `mine: false` (other digest), replace a ready job, 400 over the cap, long-poll returns on change and on timeout, bundle only when ready (409), diff by `h` and `p`, `after` and `max_bytes` paging, unchanged = two lines, 404 after expiry, cancel. |
| 5 | Device planner: job mode, `Remote`/`Synced`/`JobRef`, the new steps and reports, hash consistency rules, account epoch, legacy fallback and its 24 h re-probe, `Book::plan` 7-day rule, `Finished.changed`. | `rosty_app_core` | In `people_sync_tests.rs`: first run; unchanged run commits nothing (`book_dirty` false, `changed` 0); changed record; negative record; picture stored / failed / kept; resume by job id after process death; cut download keeps the cursor; `JobGone` restarts; `mine: false`; unsupported -> legacy steps identical to v2; a lost person or file drops `h` / `p`; link and account change clear hashes; `people.rs` test for the 7-day rule. |
| 6 | FFI and bridge: the four `wa.people_sync_*` ops with loopback fixtures (`crates/rosty_ffi/tests/data/wa/`, a bundle as `body_b64`), new `people.sync_next` / `sync_report` / `sync_status` fixture cases, `OPS.md`, `scripts/check-ffi-exports.sh`; Kotlin models (`SyncModels.kt`, `SyncWire`), `WaOps`, `WaGateway`, `FakeWaGateway` (`android/wa/src/testFixtures`), `FakeGateway` (`core-bridge/src/testRust`), contract tests (`PeopleContracts.kt`). | `rosty_ffi`, `:core-bridge`, `:wa` | Fixtures both ways; the bundle op writes picture files and answers paths; native tests of `:core-bridge`. |
| 7 | Runner, worker, triggers: the runner's four steps, Stop cancels the job, quiet automatic runs and `KEY_ATTENDED`, `ForegroundSyncPlan` for `server_working`, `PeopleSyncTriggers`, `PERIOD_MS` 12 h with `UPDATE`, Phone's `StandalonePeople` on the same rules. | `:people`, Contacts, Phone | `PeopleSyncWorkerTest`: the period test becomes `...EveryTwelveHours...` (`TimeUnit.HOURS.toMillis(12)`), `keepLeavesTheRunningScheduleAlone` becomes "a second schedule updates the same work and keeps its id"; new `PeopleSyncJobTest` on `PeopleTestRig` (job run end to end with the fake gateway, unchanged run: no `onChanged`, no file written; Stop sends the cancel; system stop does not; old gateway falls back); `PeopleSyncTriggersTest`; `ForegroundSyncPlanTest`; `PeopleSyncForegroundTest` (auto does not promote); Phone standalone test for reconnects. |
| 8 | UI and close: `PeopleSyncStatus` and `peopleSyncUi`, strings en and tr, `SyncNotice` lines, `attend()` from the settings view model; `docs/architecture.md` "People sync" paragraph (lines 461-474) and the "How the apps use it" paragraph of `docs/whatsapp-gateway.md` (line 637); the gate; the note in `e6-status.md` (gateway commits, backup, what to try). | Contacts, `:people`, docs | `PeopleSyncWordsTest` (every new row), `SyncNoticeTest`, `ContactsSettingsSyncRowTest`; shots in `X1ShotTest`: `x1-people-sync-row-server` (lookup with bar), `-server-limit`, `-server-quiet`, `-download`, `-unchanged`, `-server-tr`; the existing six stay unchanged. |

Order: 1, then 2-3-4 (gateway) and 5-6 (device) in parallel, then 7, then 8. Steps 2 to 4 are gateway commits and
are reported for the deploy.

**Gate** (release and `checks` profile only, never debug):

```
cargo fmt --all
cargo clippy --profile checks --all-targets -p rosty_core -p rosty_app_core -p rosty_geo -p rosty_tiles -p rosty_wa_proto -p rosty_wa_client -p rosty_android -p rosty_ffi -p wa_gateway
cargo test --profile checks -p rosty_wa_proto -p rosty_wa_client -p rosty_app_core -p rosty_ffi -p wa_gateway
cargo check --release -p rosty_android --target aarch64-linux-android
scripts/kotlin-ui-check.sh
```

(`apps/*` is retired and is not built; the package set is the close gate's, `scripts/kotlin-ui-check.sh:15`, plus
`wa_gateway`.)

**On the real gateway** (after the backup and `scripts/deploy-wa-gateway.sh`):

1. Journal: the v9 migration line, no error; `scripts/deploy-wa-gateway.sh status`.
2. Contacts, Sync Now: the row and the notification show the gateway's phases with counts; the journal shows
   15-JID batches about 20 s apart and one picture about every 3 s; then one bundle request (several parts on the
   first run) and "Synced".
3. Sync Now again at once: the job is ready in seconds with no WhatsApp request in the journal; the bundle is two
   lines; the row says "Nothing changed".
4. Change the profile picture of a test account, wait for the next run or press Sync Now: exactly one record with one
   picture arrives.
5. Press Stop during the first run: the journal shows the job cancelled and no further WhatsApp request; Continue
   picks up without repeating answered batches.
6. Restart the gateway mid-job (`systemctl restart wa-gateway`): the row continues, the job finishes.
7. Force a limit only if it happens by itself: the row shows the wait and its end; nothing is sent before it.
8. Leave the phone for 24 h: two automatic runs, no notification; `WorkManager` shows a 12 h period
   (`adb shell dumpsys jobscheduler | grep people-sync`).
9. An old APK (before this round) against the new gateway still syncs.
10. Compare for about 20 contacts whether a person the job reports without a picture really has none in WhatsApp
    (risk R2).

## 9. Risks and open questions

Risks:

- **R1 Pace is still a guess.** WhatsApp's real usync budget is unknown; the job keeps v2's numbers and the shared
  cooldown. First-run duration for 1 000 numbers stays about 22 min lookups + 16 min profiles + 23 min pictures, but
  on the gateway, with the phone asleep.
- **R2 usync picture ids.** The job trusts "no picture id" from usync and sends no picture IQ for such people
  (today's route does the same for 24 h). If WhatsApp hides the id from usync for some privacy settings while the
  picture IQ (with its privacy token, `contacts.rs:237-256`) would show it, those people lose their picture. Checked
  in trial 10; the fix would be a picture IQ for id-less people at most every 7 days.
- **R3 Numbers at rest on the gateway.** `people_job_numbers` (until the job expires, 24 h) and `people_lookups` (up
  to 30 days, numbers not on WhatsApp included) now sit in `gateway.db`. Until now negatives were memory only. It is
  the owner's server and the tables are wiped with the account and the instance.
- **R4 Two devices on one instance** take turns (`mine: false`); correct but the second waits for the first.
- **R5 `UPDATE` of the periodic work** resets its next run time once, at the first start after the update.
- **R6 Old checkpoint readers.** A build from before this round that reads a v3 checkpoint with `stage: "server"`
  fails to parse it and starts from the default (logged, harmless); only relevant for a downgrade.

Open questions for the owner (each with the default the plan assumes):

- **Q1 Notification.** Only for runs you start yourself (Sync Now, Continue); the 12-hourly runs are silent and the
  row shows them live when Settings is open. Default: yes. Alternative: a notification for every run, as today.
- **Q2 Stop.** "Stop" cancels the job on the gateway (it stops asking WhatsApp); "Continue" starts a new job that
  reuses every answer the gateway already has. Default: yes.
- **Q3 New contact.** A number you add is looked up at the next 12 h run or on Sync Now (its card already asks
  WhatsApp when opened). Default: no extra trigger. Alternative: one run 10 minutes after the contacts changed.
- **Q4 Pictures.** The gateway sends the full picture and the phone shrinks it (about 60 to 100 KiB once per new
  picture). Default: yes. Alternative: shrink on the gateway (about half the bytes, a new image library on the Pi).
- **Q5 Numbers kept on the gateway** as in R3 (30 days for answers). Default: yes; shorter means more questions to
  WhatsApp.

## Owner answers (2026-11-16)

- Q1 Notification: **every run shows the notification** (automatic 12-hourly runs too), as in v2 S11. This overrides
  the plan's default; the long-poll slices of an automatic run therefore run in the foreground with the notification.
- Q2 Stop: not asked; the default stands (Stop cancels the job on the gateway; Continue starts a new one reusing answers).
- Q3 New contact: looked up at the next run or on Sync Now; no contacts-changed trigger (the default).
- Q4 Pictures: not asked; the default stands (the gateway sends the full picture, the phone downscales).
- Q5 Retention on the gateway: job numbers 24 h, lookup answers (negatives included) up to 30 days (the default).
