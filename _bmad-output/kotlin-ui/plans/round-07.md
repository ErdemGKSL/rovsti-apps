# Round 07 plan: E2 R2 = domain ops (E2.9-E2.14)

Planned 2026-11-08 at base `09a4343` (branch `ui-overhaul`). Epic: **E2 Rust bridge**, round R2: every domain op the
Kotlin apps need, with a contract fixture and a Kotlin model for each, the `ContactIndex` / `ActiveTrip` / tile /
WhatsApp handles, binary tiles and call audio. This round touches no `:design`/`:catalog` UI code and records no
screenshots (E2 has none, epics.md).

Owner request relayed for this workflow: "make our project GPL, port the code with very high similarity". Delivered in
R3 and re-verified by this planner: `LICENSE` is the GPL-3.0 text, `Cargo.toml:9` `license = "GPL-3.0-or-later"`,
`android/THIRD_PARTY_NOTICES.md` (308 lines: Convx, Kyant0 backdrop / AndroidLiquidGlass, compose-hig, glisse, ...),
the glass stack is Convx's backdrop port (E1.24-E1.26, `GlassRealPathTest`). Those rules stay binding. The bridge is our
own code, so no Convx/glisse header is expected in this round; if a step does port Convx code (none planned) it keeps
the original header plus the "Ported from Convx ..." line.

## State found (verified in code, not trusted)

- `git status` clean; HEAD `09a4343` (R1 review fixes). `stash@{0}` is the deprecated GPUI layout WIP: never pop it.
- E1: done (R5, unchanged since; round 06 changed no UI and left no screenshots, so there is nothing new to open).
- E2 R1 holds: `crates/rovsti_app_core` (stops, messages, contacts, phone, wa_link, storage, headless),
  `crates/rovsti_ffi` (envelope, guard, handles, events, logger, state, `ops/{mod,core}.rs`), 42 files in
  `crates/rovsti_ffi/fixtures` (11 `core.*` + 10 `headless.*` pairs), the 8 JNI exports in `jni_exports.rs`
  (`tile` answers `unsupported`, `audioPush` drops, `audioPull` returns 0: stubs for E2.13/E2.14),
  `android/core-bridge` with `RustCore`/`NativeRustCore`/`Rust`/`HeadlessClient`, `ContractFixturesTest`
  (a hand-written stem -> serializer map; `fixturesExist` hard-codes `2 * (3 + 8 + 10)`), `testRustBridge`.
  E2.1-E2.8 stay **done**.
- Only `core.*` is dispatched (`ops/mod.rs`: `match domain { "core" => ..., _ => unsupported }`).
- **Serde gap**: ARCH 6.1 assumes "every domain type is already serde". It is not: 71 of 96 `rovsti_core/src/*.rs`
  have no `Serialize` (e.g. `recents`, `dial`, `speed_dial`, `blocklist`, `inbox`, `search`, `t9`, `vcard`, `scene`).
  `rovsti_geo` has private `session`/`crossing`/`trip` modules re-exported at the root (`ActiveTrip`, `CrossingWatch`).
  `rovsti_wa_proto` types are serde (they are the gateway wire). Rule for this round: below.
- Stateful types an op must hold as a handle: `rovsti_core::search::ContactIndex` (also an argument of
  `inbox::merge`, `inbox::person_for_*`), `t9::T9Scratch`, `contact_find::DeepIndex`, `schedule::ScheduleQueue`,
  `rovsti_geo::ActiveTrip`, `rovsti_tiles::{TileClient, DiskCache}`, `rovsti_wa_client::{Gateway, EventStream, CallAudio}`,
  `rovsti_app_core::phone::wa_call::Tracker`, `rovsti_geo::tiles::offline::AreaDownload`.
- QR: `qrcode 0.14` and `rqrr 0.11` are workspace deps (`Cargo.toml:59-60`), used by `rovsti_core` and
  `rovsti_wa_ui::qr::qr_png` (PNG; we need the module matrix instead).
- Fake gateway: `crates/rovsti_wa_client/tests/loopback.rs` has `stub()`/`stub_with()` one-shot HTTP stubs.
- Tile test data: `crates/rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt`.
- Disk 12 GB free; `target/` is the big one.

## Rules for this round

1. **One op registry, no hand-maintained lists.** Step 1 builds `rovsti_ffi::ops::registry`; every later op is one
   `OpSpec` entry in its domain module's `OPS` table. Dispatch, the fixture test and the inventory test all read the
   tables, so an op without fixture cases, or a case without a committed fixture, fails `cargo test -p rovsti_ffi`.
2. **Inventory first, per domain.** `crates/rovsti_ffi/OPS.md` has one row per public item the GPUI apps call today
   (found with `grep -rhoE "rovsti_core::<mod>::[A-Za-z_]+" apps/<app>/src crates/rovsti_ui/src crates/rovsti_wa_ui/src`,
   same for `rovsti_geo`, `rovsti_tiles`, `rovsti_app_core`), classified:
   - `op <name>`: exposed through `call` (or `tile`/audio);
   - `kotlin`: per-keystroke or per-frame UI behaviour that must answer synchronously on the main thread
     (`dial::{push, backspace, long_press_*, font_step, erase_interval_ms}`, `keypad_layout`, `gesture`, `zoom`,
     `text_*`, `small_text`, `bubble_gesture`, `perf`). Ported to Kotlin in the app epic with the Rust unit tests
     mirrored as JVM tests; the row names the target Kotlin file;
   - `none <reason>`: GPUI-only (ARCH 6.6 "Not exposed").
   `tests/inventory.rs` parses the table and asserts every `op` row is registered, and every registered op has a row.
3. **Serde**: add `#[derive(Serialize, Deserialize)]` (with `#[serde(rename_all = "snake_case")]` on enums) directly to
   plain data types in `rovsti_core`/`rovsti_geo` (serde is already their dependency); no wire DTO copy of a type that
   can derive. Write a DTO in `rovsti_ffi::ops::<domain>` only when the core type borrows (`Participant<'a>`,
   `CallScene<'a>`, `&WireContact` results -> answer contact `id`s plus the rows) or holds a non-serde field
   (`SmallText` -> `String`). A derive must not change an existing on-disk JSON format: stores already have fixture tests
   in `rovsti_app_core/tests/stores.rs` and `rovsti_core/tests`; they must stay green unchanged.
4. **Deterministic ops**: no op reads the clock, the locale or the time zone. Requests carry `now_ms`, `tz_offset_s`,
   `locale` (`LocaleTime` serde), so fixtures are stable.
5. **Facades**: Kotlin `dev.rovsti.core.ops.<Domain>Ops` interfaces with `suspend fun <verb>(req): Outcome<Ans>`,
   implemented once by a generic `RustCore.typed(op, reqSerializer, ansSerializer)` (Step 1); `FakeRustCore` answers
   from registered lambdas. Models in `dev.rovsti.core.model.<domain>`, `@Serializable @Immutable`, listed in
   `android/compose_stability.conf`. Handles are `AutoCloseable` Kotlin classes (`RustHandle`, Step 1) calling
   `RustNative.release` once (idempotent, `Cleaner` as a backstop only, logged in debug).
6. **Check per step**: `bash scripts/kotlin-ui-check.sh` ALL GREEN (Rust: `cargo test` of the Kotlin-facing crates
   incl. `rovsti_ffi`; Gradle: `ContractFixturesTest`, `testRustBridge`). Rust crates outside that set that a step
   touches (`rovsti_core` derives affect the GPUI apps): `cargo check -p rovsti_phone -p rovsti_contacts
   -p rovsti_messages -p rovsti_stops` once per step. The full `CLAUDE.md` gate (`cargo fmt --all && cargo clippy
   --workspace --all-targets && cargo test --workspace` + Android `cargo check`) runs in Step 8 after `df -h .` shows
   >= 8 GB (else `rm -rf target/debug/incremental android/*/build`; never below 6 GB).
7. Rust style `.claude/skills/rust-best-practices`; `rovsti_ffi` stays thin (decode, call, encode). New logic that is
   not a conversion goes into `rovsti_app_core` or the pure crate with unit tests. No new dependency that needs a C
   toolchain (ARCH 6.7).
8. Commits: `Kotlin UI (E2 R2): <step title>`, one per step, ending with the session attribution lines.
9. Threading (SKILL `references/performance.md` 9, ARCH 6.4): every facade runs on `RustDispatcher`; `testRustBridge`
   gets one assertion per handle type that a call on the main thread throws in debug (`assertNotMain`).

---

## Step 1: op registry, inventory, Kotlin typed facade, shared ops (`phone_number`, `format`, `calling_code`, `copy`)

Rust (`crates/rovsti_ffi`):
- `src/ops/registry.rs`: `pub struct OpSpec { pub name: &'static str, pub run: fn(&[u8]) -> Result<Value, FfiError>,
  pub cases: fn() -> Vec<Case> }`, `pub struct Case { pub label: &'static str, pub request: Value, pub seed:
  Option<&'static str> }` (`seed` = a dir under `crates/rovsti_app_core/tests/fixtures/dirs` copied as the data dir,
  as headless fixtures do today). Helper `pub fn typed<Req: DeserializeOwned, Ans: Serialize>(f: fn(Req) ->
  Result<Ans, FfiError>) -> ...` is a macro `op!(name, Req, Ans, f, cases)` because `fn` pointers cannot capture
  generics; it yields `OpSpec`. `pub fn all() -> impl Iterator<Item = &'static OpSpec>` chains the domain tables;
  `dispatch` looks the name up in a `OnceLock<HashMap<&str, &OpSpec>>`; duplicates panic in a unit test.
- Move `core.*` into the registry with its existing cases; `core.panic`/`core.emit_test` stay behind `test-ops` and
  outside the fixture set as today. **All 42 existing fixture files must stay byte-identical** (proves the move).
- Fixture names: `<op>.request.json` / `<op>.answer.json`; a second case is `<op>.<label>.{request,answer}.json`
  (the `core.fail.<kind>` scheme). `tests/fixtures.rs` iterates `registry::all()` x `cases()`; headless part unchanged;
  stray-file check unchanged.
- `src/ops/shared.rs` (domain prefixes `phone_number`, `format`, `calling_code`, `copy`): normalise/parse/E.164/
  same-number (`rovsti_core::phone_number`), display format (`number_format`), country of a number and calling code
  (`calling_code`, U48/U56), copy choices (`copy_choices`). One case per op plus one error case where an op can fail.
- `OPS.md` + `tests/inventory.rs` (Rule 2) with the shared rows filled and every other domain row present with
  status `todo` (the inventory test treats `todo` as a failure only for the domains whose step is done: a const list
  `DONE_DOMAINS` grows per step).

Kotlin (`android/core-bridge`):
- `RustCore.typed(...)`: `suspend fun <Req, Ans> RustCore.call(op, req, reqSer, ansSer): Outcome<Ans>` (decode errors ->
  `RustError.Parse` with the op name).
- `RustHandle` (`AutoCloseable`, `id: Long`, `released` atomic, `close()` -> `RustNative.release`).
- `dev.rovsti.core.ops.SharedOps` + `NativeSharedOps`; models `dev.rovsti.core.model.shared.*`.
- `ContractFixturesTest` restructure: `models` built from per-domain `ContractModels` objects in the **test** source set
  (`CoreContracts`, `HeadlessContracts`, `SharedContracts`, ...); `fixturesExist` compares against the files the Rust
  registry lists in `crates/rovsti_ffi/fixtures/INDEX` (written by the Rust fixture test, one stem per line), not a
  hard-coded number. New test `everyFacadeOpHasAFixture`: each `*Ops` interface exposes `val ops: List<String>`
  (companion), every name must have `<op>.request.json`.
- Verify: `cargo test -p rovsti_ffi` (fixtures, inventory, registry duplicates), kotlin-ui-check ALL GREEN.

## Step 2 (E2.9 + search): Phone domain ops and the `ContactIndex` handle

- Handles (`src/ops/search.rs`): `search.index_new {contacts: [WireContact]}` -> `{handle}`; `search.index_replace`;
  `search.set_call_counts`; `search.query {handle, query, limit}` / `search.query_loose` -> `{ids, rows}`;
  `search.match_number`; `t9.query {handle, digits}` (the handle owns a `T9Scratch` and `NameFormat`). Handle value:
  a struct `Contacts { index: ContactIndex, t9: T9Scratch }` behind a `Mutex`.
- Phone ops (`src/ops/phone.rs`), from the inventory of `apps/phone/src`: `recents.{build, refresh_delta,
  merge_extras, missed_extras, history, blocked_history, history_text, section_starts}`, `call_log.query`
  (`call_log_query`), `call_notes.*`, `call_stats.*` (this week / 7 days / 8 weeks / top people, U7), `speed_dial.
  {on_hold, assign, unassign, free_digits, digit_for, badges}`, `blocklist.*`, `dial.{normalise_paste, from_tel_uri,
  validate, field_menu, extract_numbers, paste_plan, redial_target, tone_plan, tones_text, tone_preview,
  paste_tones_label, is_emergency}`, `sim.*`, `setup.{facts, steps, xiaomi}`, `audio.*` (route choices),
  `scene.{of, diff}` (DTO: participants, primary/held ids, hold slot, disabled reasons per control), prefs
  (`phone.prefs_load/save` over `rovsti_core::settings` + `rovsti_app_core::storage`), notes store
  (`rovsti_app_core::phone::notes`), `phone.wa_tracker_*` (handle over `wa_call::Tracker`: apply snapshot, answer once,
  can_place, status_line, format_elapsed).
- AC3 benchmark: `crates/rovsti_ffi/tests/search_bench.rs` builds 5 000 synthetic contacts (seeded generator, names in
  Latin/Turkish/Cyrillic), types 20 queries of 1-6 chars through `ops::call("search.query")` (JSON both ways, i.e.
  the real cost). Debug test asserts median < 25 ms (smoke); `cargo test --release -p rovsti_ffi --test search_bench
  -- --ignored` asserts p95 < 5 ms; run it and paste the numbers into the evidence. `testRustBridge` adds a
  `ContactIndexTest`: create, query, `close()`, query after close -> `RustError.NotFound` (or the handle error kind the
  registry uses).
- Kotlin: `SearchOps`, `PhoneOps` + models `model.search`, `model.phone`; `ContactIndexHandle : RustHandle`.
- Verify: fixture + inventory tests (`DONE_DOMAINS += phone, search, t9`), `cargo check -p rovsti_phone`, kotlin-ui-check.

## Step 3 (E2.10 + AC0 QR): Contacts domain ops

- `src/ops/contacts.rs` from the inventory of `apps/contacts/src`: `contacts.{draft_validate, draft_diff, card, dates,
  age, dupes, merge_plan, cleanup, csv_export, csv_import, ics, vcard_parse, vcard_write, import_plan, intake, scan,
  photo_plan, trash_*, quick, frequent, recent, history, stats, me, groups_*, prefs_load/save, view, select_*,
  options, birthday_reminders, deep_find}` (names follow the module verbs; `DeepIndex` is a handle like
  `ContactIndex`). `contacts.trash_*` and `contacts.prefs_*` go through `rovsti_app_core::contacts` (stores in the
  init data dir; fixture cases use a `seed` dir).
- `src/ops/qr.rs`: `qr.encode {text, ecc}` -> `{side, modules: "base64 bitset"}` (row-major, MSB first; documented in
  `OPS.md`); `qr.decode {w, h, luma: base64}` -> `{texts: [...]}` via `rqrr`. Round-trip test in Rust (encode ->
  render to luma at 4 px/module with a quiet zone -> decode) and a Kotlin `QrMatrix` decoder unit test from the
  fixture. No ZXing / ML Kit.
- Kotlin: `ContactsOps`, `QrOps`, models `model.contacts`, `model.qr`.
- Verify: tests as before, `cargo check -p rovsti_contacts`, kotlin-ui-check.

## Step 4 (E2.11a): Messages core ops (inbox, sms, mms, notify, schedule, spam, backup)

- `src/ops/messages.rs`: `inbox.{merge (takes the ContactIndex handle), preferred_channel, person_for_number,
  person_for_jid, filter (inbox_filter), bulk (inbox_bulk), pending_delete}`, `sms.*`, `sms_timeline.build`,
  `mms_pdu.{parse, build_send_req}` (bytes as base64), `otp.find`, `spam.{classify, add_rule, insert_rule, remove_rule,
  links, open_links, contains_link}`, `schedule.*` (handle over `ScheduleQueue`, or stateless `{queue_json, op}` ->
  `{queue_json, ...}`; pick the stateless form if the queue is persisted by `rovsti_app_core::messages` anyway, and say
  why in `OPS.md`), `notify.*`, `msg_backup.{export, parse, plan}`, `messages.prefs_load/save`, `messages.drafts_*`,
  `messages.access` (`rovsti_app_core::messages::access`).
- Kotlin: `MessagesOps` (core half), models `model.messages`.
- Verify: tests, `cargo check -p rovsti_messages`, kotlin-ui-check.

## Step 5 (E2.11b): Messages chat tools

- Same file or `src/ops/chat.rs`: `chat_find`, `chat_gallery`, `chat_export`, `global_search`, `forward`,
  `message_info`, `message_reminder`, `starred`, `thread_marks`, `mute`, `quick_replies`, `unread_jump`, `date_jump`,
  `voice_player.speeds`, `shared_card`, `text_detect`, `date_detect`, `location_link`, `multi_select` only if the
  inventory marks it `op` (selection maths is likely `kotlin`).
- Kotlin: `ChatOps`, models `model.chat`.
- Verify: tests (`DONE_DOMAINS` now covers every Messages row), `cargo check -p rovsti_messages`, kotlin-ui-check.

## Step 6 (E2.12): Stops domain ops and the `ActiveTrip` handle

- `src/ops/stops.rs`: `stops.trip_*` handle over `rovsti_geo::ActiveTrip` (start, fix -> `FixOut`, notice state,
  status share text, arrival clock, end), `stops.crossing_*` (diameter validate -> `DiameterError::message` in the
  envelope `message`, side reading), `stops.dest_parse` (`parse_location`, `parse_stop_share`), `stops.share_text`,
  `stops.point_qr_payload`, `stops.pick_*` (`rovsti_app_core::stops::pick`), `stops.snap`, `stops.saved_*`
  (`saved_stops`, `suggest_name`, colours, shortcut spec), `stops.history_*` (`history`, `format_duration`,
  `prune_label`, `usage_text`, `history_io::{to_csv, to_gpx, share_text, export_file_name}`), `stops.stats`,
  `stops.prefs_*`, `stops.notice_{render, validate}`, `stops.alarm_plan`, `stops.poll`, `stops.gtfs_import` (zip path
  in, `FeedReport` out; a tiny zip fixture under `crates/rovsti_ffi/tests/data`), `stops.coord_format`,
  `stops.measure`, `stops.backup_{export, parse}`, `stops.store_*` (trip lock, `rovsti_app_core::stops::store`).
- `view.*`: the slippy maths the Kotlin `MapCamera` must agree with (`rovsti_geo::view` + `tiles::slippy`:
  `frac_to_coord`, visible tile set for a viewport). Fixtures double as the cross-check data for `MapCamera` in E7.
- Kotlin: `StopsOps`, `ActiveTripHandle`, models `model.stops`.
- Verify: tests, `cargo check -p rovsti_stops`, kotlin-ui-check.

## Step 7 (E2.13): tiles: binary geometry, cache ops, offline area

- `docs/kotlin-ui/tile-buffer.md` (new, versioned): little-endian; header `magic "RVT1"`, `u16 version = 1`,
  `u16 flags`, `u32 z, x, y`, `u32 extent`, `u32 background rgba`, `u16 class_count`; per class `u16 paint_id`,
  `u32 rgba`, `f32 width14`, `u8 kind (0 polygon, 1 line)`, `u32 part_count`, per part `u32 point_count` then
  `point_count * (f32 x, f32 y)` in tile-local units (0..extent). Paint ids are the `DrawClass` order of
  `rovsti_geo::tiles::style`; flags bit 0 = dark (eclipse), bit 1 = from cache only (no network). Polygons keep
  holes as separate parts with ring orientation preserved (state the fill rule: non-zero/even-odd, whatever
  `build_layer` assumes).
- Rust: `src/ops/tiles.rs` + `src/tile_buffer.rs` (encoder with a unit test that decodes its own output);
  `tiles.open {server_url, cache_cap}` -> handle `{TileClient, Mutex<DiskCache>, TileSource}`; `tile(handle, z, x, y,
  flags)` in `jni_exports.rs` now calls the handle: cache hit or fetch, `TileGeometry::from_bytes`, style, encode.
  Errors stay envelopes (the Kotlin side tells them apart by the magic). `tiles.{usage, usage_by_source, clear_all,
  clear_except, set_cap, test_server, stand_in}`; `tiles.offline_start {handle, bbox, zooms}` -> job handle, progress
  events `tiles.offline` `{done, total, fraction, text}` through the sink, `tiles.offline_cancel`.
- Tests: Rust encoder test on `osm-14-9520-6130.mvt` (class count, vertex count equal to `TileGeometry::vertices`);
  a real op `tiles.encode_mvt {mvt: base64, z, x, y, flags}` -> `{buffer: base64}` (no network, no cache) gives the
  fixture pair `tiles.encode_mvt.{request,answer}.json` built from that `.mvt`. Kotlin `TileBuffer.decode(ByteArray)` + `TileBufferTest` (reads the fixture, checks
  header, classes, first point) per E2.13 AC3; `testRustBridge` `TileHandleTest` with a cache-only flag on a seeded
  cache dir (no network in tests).
- Verify: tests, kotlin-ui-check.

## Step 8 (E2.14): WhatsApp client ops, event stream, call audio, avatars/names; close E2

- `src/ops/wa.rs` over `rovsti_wa_client::Gateway` (handle `wa.open {url, key}`): `wa.{create_instance, status,
  relink, delete_instance, mint_key, keys, revoke_key, register_push, chats, chats_with_meta, messages,
  messages_with_meta, send_text, schedule_text, scheduled, cancel_scheduled, send_media(_with), media, thumbnail, react,
  mark_read, mark_played, set_presence, subscribe_presence, chat_state, groups, group, lookup_users, profiles,
  refresh_profiles, picture, statuses, status_media, status_thumbnail, mark_status_viewed, edit_message,
  revoke_message, delete_for_me, star_message, chat_action, place_call, calls, accept_call, reject_call, end_call,
  set_muted}` (bytes base64 in JSON; media over 1 MB answer a temp file path in the cache dir instead), `wa.push_parse`
  (`rovsti_wa_proto::push`), `wa.link_{read, write}` (`rovsti_app_core::wa_link::link`), `wa.avatars_plan`,
  `wa.names` (`rovsti_app_core::wa_link::{avatars, names}`). `ClientError` -> envelope kinds (`network`, `auth` for
  401/403, `invalid` for API errors with the gateway message, `parse` for decode errors).
- Events: `wa.events_start {handle}` -> stream handle (`rovsti_wa_client::events::subscribe`, its own thread, ARCH 6.4);
  every `ClientEvent` is emitted as `wa.event` `{instance, event}`; `wa.events_reconnect_now`, `release` stops it.
  Kotlin `WaEvents` maps sink overflow to a synthetic `resync` (ARCH 6.4).
- Audio: `wa.call_audio_open {handle, call_id}` -> handle over `CallAudio`; `audioPush`/`audioPull` exports use it with
  1 920-byte frames (960 i16, 60 ms, AD-WA5); wrong sizes are dropped with a debug log. Rust loopback test against the
  `stub` gateway pattern of `rovsti_wa_client/tests/loopback.rs` (push N frames, pull N frames equal), and a
  `testRustBridge` `CallAudioTest` through JNI with a fake gateway on localhost.
- Fixtures: request/answer pairs for the pure ops; network ops get fixtures through a `seed`-free fake: the fixture test
  starts a localhost stub that answers canned bodies from `crates/rovsti_ffi/tests/data/wa/*.json` (so answers are
  deterministic and real `Gateway` code runs). Kotlin `WaOps`, models `model.wa` (reuse the `rovsti_wa_proto` field
  names).
- Close: inventory has no `todo` row; full `CLAUDE.md` Rust gate (Rule 6) and kotlin-ui-check ALL GREEN; rebuild the
  catalog APK (it carries `librovsti_ffi.so`) and state its path and size; epics.md E2.9-E2.14 -> done with evidence
  (commits, test counts, bench numbers, fixture count from `INDEX`), E2 heading -> DONE (R2); hand-off line to E3 R1.

## Out of scope

UI, screenshots, `:platform`/`:wa`/`:shell` (E3), Kotlin ports of the `kotlin`-classified helpers (app epics), deleting
GPUI crates (E8). If Step 2's inventory shows a domain is far larger than one step, split it (e.g. Step 2a/2b) and push
Step 8 into the next round rather than skipping fixtures.
