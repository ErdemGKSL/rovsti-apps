# Round 06 plan: E2 R1 = Rust bridge foundation (E2.1-E2.8)

Planned 2026-11-08 at base `a9f189c` (branch `ui-overhaul`). Epic: **E2 Rust bridge** (`crates/rovsti_app_core`,
`crates/rovsti_ffi`, `android/core-bridge`). E1 is done (verified below). E3 R1 is independent and may be planned in
parallel by another round; this round touches no `:design`/`:catalog` code.

Owner request relayed for this workflow: "make our project GPL, port the code with very high similarity". Delivered in
R3 (E1.23-E1.29) and re-verified here: `LICENSE` is GPL-3.0, `Cargo.toml:9` `license = "GPL-3.0-or-later"`, README
licence section, `CLAUDE.md` licence line, `android/THIRD_PARTY_NOTICES.md` (glisse, Convx, Kyant0 backdrop /
AndroidLiquidGlass, compose-hig, ...), `LicenceHeaderTest`. The glass stack is Convx's backdrop port with
`GlassRealPathTest`. Those rules stay binding: any Kotlin file in this round that ports Convx or glisse code carries the
required header (none is expected: the bridge is our own code). New Rust crates inherit `license.workspace = true`.

## State found (verified in code, not trusted)

- `git status`: clean. `git log`: R5 is `29c7154`..`a9f189c` (F9, E1.22 AC1-AC3, close, review fixes). `stash@{0}` is
  the deprecated GPUI layout WIP: leave it, never pop it.
- E1.22 evidence holds: `docs/kotlin-ui/screenshots/design.png` (1.9 MB), `docs/kotlin-ui/checklist-e1.md`,
  24 `android/catalog/screenshots/e1.22-states-*.png`, 242 goldens total. E1 stays **done**.
- Screenshot re-judged: `android/catalog/screenshots/e1.8-title-collapsed-light.png` (opened). The inline title
  "Contacts" sits left of centre (text centre ~x 284 of 923). This is the R5 review fix in `a9f189c`
  (`FrostedTopBar.kt` `Modifier.titleSpan`): with a two-capsule toolbar starting at ~238 dp on a 411 dp window, a
  centred 17 sp semibold title would run under the capsules, so it slides into the free span instead of truncating to
  "Con...". iOS does the same with wide trailing items. **Judged OK, no fix.** The faded "Contact 02" under the bar is
  the F6 scrim (judged in R4). No other R5 PNG changed after the review.
- E2 has nothing yet: no `crates/rovsti_app_core`, no `crates/rovsti_ffi`, no `android/core-bridge`.
- Toolchain: Rust targets `aarch64-linux-android`, `x86_64-linux-android`, host installed; NDK linkers exported by
  `scripts/android-env.sh`; **`cargo-ndk` is not installed** (crates.io is reachable). JDK present.
- Disk: 9.1 GB free; `target/` 1.7 GB, `android/*/build` 0.25 GB.

## What moves (source inventory, read for this plan)

| Story | From | Lines / tests | GPUI or JNI coupling to cut |
|---|---|---|---|
| E2.1 | `apps/stops/src/store.rs` | 174 / 4 | `rovsti_ui::storage` -> `rovsti_app_core::storage` |
| E2.1 | `apps/stops/src/pick.rs` | 799 / 30 | none |
| E2.1 | `apps/stops/src/snap.rs` | 244 / 10 | one `use` |
| E2.1/E2.8 | `apps/stops/src/headless.rs` | 539 / 17 | `rovsti_android::headless::{dispatch, Handler}`, `rovsti_wa_ui::headless::send_text_in` (needs E2.4 first) |
| E2.2 | `apps/messages/src/prefs.rs` | 614 / 26 | `rovsti_ui::storage` |
| E2.2 | `apps/messages/src/drafts.rs` | 242 / 9 | none |
| E2.2 | `apps/messages/src/access.rs` | 101 / 3 | `rovsti_android::permissions::Access` (see Step 3) |
| E2.2/E2.8 | `apps/messages/src/headless.rs` | 738 / 17 | `rovsti_android::headless`, `rovsti_ui::storage::load_json` |
| E2.3 | `apps/contacts/src/{trash_store,prefs}.rs` | 163+126 / 13 | none |
| E2.3 | `apps/contacts/src/whatsapp.rs` | 873 / 13 | `gpui::{Context, SharedString}`, `ContactsApp` in the UI half |
| E2.3 | `apps/phone/src/notes.rs` | 201 / 2 | `gpui::Context`, `PhoneApp` in the UI half |
| E2.3 | `apps/phone/src/wa_call.rs` lines 1-440 (Tracker, AnswerOnce, can_place, dial/handoff numbers, extra_calls, format_elapsed, status_line, MicStep, mic_ask...) | 1715 / 24 | everything from `struct Session` (l. 442) down is GPUI and stays; `rovsti_android::Result` in `mic_step`/`play_available`/`speaker_applied` stays in the app |
| E2.4 | `crates/rovsti_wa_ui/src/headless.rs`, `model.rs` (`LINK_FILE`, `StoredLink`), `avatar_book.rs`, `names.rs` | 290+692+94 / 26 | `rovsti_wa_ui` depends on gpui; the moved halves must not |
| storage | `crates/rovsti_ui/src/storage.rs` `save_json`, `load_json`, `load_prefs`, `save_prefs`, `backup_json`, `parse_backup` (+ their tests) | | `data_dir`/`PrefsWriter` that take `gpui::App` stay |

Headless kinds today: Messages `sms_classify`, `mms_notification`, `mms_retrieved`, `mms_send_req`, `wa_digest`,
`wa_reply`, `msg_remind` (`apps/messages/src/headless.rs:44-55`); Stops `trip_fix`, `trip_alarm`, `trip_end`
(`apps/stops/src/headless.rs:59-66`). They do not collide, so one `rovsti_ffi` `headless` serves both apps.

## Rules for this round

- Rust style: `.claude/skills/rust-best-practices`. `rovsti_app_core` depends only on `rovsti_core`, `rovsti_geo`,
  `rovsti_wa_client`, `rovsti_wa_proto`, `serde`, `serde_json`, `base64`, `log`, `thiserror` (as needed). **Never**
  `gpui*`, `rovsti_ui`, `rovsti_wa_ui`, `rovsti_android`, `jni`. A test `crates/rovsti_app_core/tests/deps.rs` parses
  `cargo metadata --format-version 1 --no-deps`-free: simpler, it reads `crates/rovsti_app_core/Cargo.toml` and fails if
  any of those names appears.
- **Move, do not copy** (ARCH 6.2 rule): the moved module keeps its tests verbatim (only `use` paths change); the GPUI
  crate deletes its copy and calls the moved one (`pub use rovsti_app_core::stops::pick::*;` re-exports are fine to keep
  call sites unchanged). `grep` after each step proves no second definition remains.
- File names and JSON schemas unchanged (ARCH 7.1). Each store gets a fixture test that reads a file written by the
  pre-move code: capture it with the old code **before** moving (run the existing test helper or write the JSON the
  current serializer emits into `crates/rovsti_app_core/tests/fixtures/<store>.json`) and commit it in the same step.
- GPUI apps must still compile, but building them is disk-heavy. Per step use `cargo check -p <touched app>` (not
  `cargo build`), plus `cargo test -p rovsti_app_core` (where the moved tests now run). Run the full `CLAUDE.md` set
  (`cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace`) once, at Step 8, after
  `df -h .` shows >= 8 GB free (else `rm -rf target/debug/incremental android/*/build` first; never below 6 GB).
- Checks per step: `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`. Step 1 adds `-p rovsti_app_core`, Step 5 adds
  `-p rovsti_ffi` to its `RUST_PKGS`; Step 6 adds the `:core-bridge` tasks. No goldens change this round (no UI); the
  orphan-golden check must still pass.
- Commit prefix `Kotlin UI (E2 R1): ...`, ending with the session attribution lines. One commit per step.
- Screenshots: none in E2 (epics.md says so); evidence lines name tests and fixture files instead.

---

## Step 1 (E2.1 part A): `rovsti_app_core` crate, `storage`, Stops `store`/`pick`/`snap`

- New `crates/rovsti_app_core/{Cargo.toml,src/lib.rs}` (`[package] name = "rovsti_app_core"`, workspace edition,
  licence, lints; add `rovsti_app_core = { path = "crates/rovsti_app_core" }` to `[workspace.dependencies]`).
  Crate doc: "App-level logic of the four apps without UI or JNI; the data dir is always a `&Path` argument."
- `src/storage.rs`: move `save_json`, `load_json` (atomic temp + rename, size guard as today), `load_prefs`,
  `save_prefs`, `backup_json`, `parse_backup` and the types they return (`Loaded`, `ImportPreview`, `BackupRefusal`)
  from `crates/rovsti_ui/src/storage.rs` with their tests. `rovsti_ui::storage` keeps `data_dir`, `DataDir`,
  `PrefsWriter` (GPUI) and `pub use rovsti_app_core::storage::{...}` for the rest.
- `src/stops/{mod,store,pick,snap}.rs`: move the three files. `apps/stops/src/{store,pick,snap}.rs` become
  `pub use rovsti_app_core::stops::<m>::*;` one-liners (or are deleted and `main.rs` `use`s change). `store::lock()`
  (the trip lock) moves with `store` and stays a process-wide static.
- Fixture tests (`crates/rovsti_app_core/tests/stores.rs`): `trip.json`, `trip.legacy.json` rename, `history.json`,
  and the shared `prefs.json` (`rovsti_core/tests/fixtures` already has a prefs golden: reuse it via a relative path
  instead of copying).
- `crates/rovsti_app_core/tests/deps.rs` (forbidden dependency names, see Rules).
- `scripts/kotlin-ui-check.sh`: add `-p rovsti_app_core` to `RUST_PKGS`.
- Verify: `cargo test -p rovsti_app_core` (>= 44 moved tests + fixtures), `cargo check -p rovsti_stops -p rovsti_ui`,
  `grep -rn 'fn snap_\|struct PickState' apps/stops/src` finds no definition (adjust names to the real items),
  kotlin-ui-check ALL GREEN.
- Commit: `Kotlin UI (E2 R1): E2.1 rovsti_app_core, storage, Stops store/pick/snap moved`.

## Step 2 (E2.4): `wa_link` (link file, headless send, avatar plan, display names)

- `src/wa_link/{mod,link,send,avatars,names}.rs`:
  - `link`: `LINK_FILE` (`whatsapp-link.json`), `StoredLink` and its read/write from `crates/rovsti_wa_ui/src/model.rs`
    (only the serde type and file I/O; view models stay).
  - `send`: `send_text_in`, `SendFailure` and the rest of `crates/rovsti_wa_ui/src/headless.rs` (it already has no gpui
    use: `rovsti_core`, `rovsti_wa_client`, `rovsti_wa_proto`, `serde`). 9 tests move.
  - `avatars`: the pure policy of `avatar_book.rs` (which picture to fetch when, 429 back-off, disk naming and
    pruning; 13 tests). If a part holds `image` decoding, that part stays in `rovsti_wa_ui::avatars` and the decision
    functions move.
  - `names`: `display_name`, `display_name_with_profile`, `number_label` (4 tests).
- `rovsti_wa_ui` gains `rovsti_app_core` as a dependency and re-exports (`pub use rovsti_app_core::wa_link::names::*`
  keeps `rovsti_wa_ui::display_name` working for the four GPUI apps).
- Fixture: `tests/fixtures/whatsapp-link.json` written by the pre-move `StoredLink` serializer; test reads it.
- Verify: `cargo test -p rovsti_app_core`, `cargo check -p rovsti_wa_ui -p rovsti_stops -p rovsti_messages`, kotlin-ui-check.
- Commit: `Kotlin UI (E2 R1): E2.4 wa_link moved (link file, headless send, avatar plan, names)`.

## Step 3 (E2.2): Messages `prefs`, `drafts`, `access`

- `src/messages/{mod,prefs,drafts,access}.rs` moved with tests (26 + 9 + 3). `PREFS_FILE` (`messages-prefs.json`) and
  the drafts file name move with them.
- `access.rs` uses `rovsti_android::permissions::Access as Perm`. Do not depend on `rovsti_android`: add a small
  `rovsti_app_core::permission::Access` enum with the same variants and serde names, and an
  `impl From<rovsti_android::permissions::Access> for ...` **in the GPUI app** (`apps/messages/src/access.rs` becomes
  that conversion plus the re-export). The Kotlin side will map `PermissionState` to the same enum in E3.8b.
- Fixtures: `messages-prefs.json` (with spam rules and a voice speed) and `drafts.json` written by the pre-move code.
- Verify: `cargo test -p rovsti_app_core`, `cargo check -p rovsti_messages`, kotlin-ui-check.
- Commit: `Kotlin UI (E2 R1): E2.2 Messages prefs, drafts, access moved`.

## Step 4 (E2.3): Contacts and Phone pure halves

- `src/contacts/{mod,trash_store,prefs,whatsapp}.rs`: trash store (`contacts-trash.json`, 30-day expiry) and prefs
  (`contacts-prefs.json`) whole; from `apps/contacts/src/whatsapp.rs` only the functions that take no `Context` /
  `ContactsApp` (target choice per number, lookup batching, folding, `LocaleTime` labels) and their tests. The
  `impl ContactsApp` half stays. `SharedString` in a moved signature becomes `String`/`&str`; the GPUI side converts.
- `src/phone/{mod,notes,wa_call}.rs`: from `notes.rs` the load/save/merge logic around
  `rovsti_core::call_notes` (the `Context` half stays); from `wa_call.rs` lines 47-440 minus the three functions that
  take `rovsti_android::Result` (`mic_step`, `play_available`, `speaker_applied`, `mic_ask`, `mic_started` stay in
  the app): `Tracker`, `Change`, `AnswerOnce`, `can_place`, `dial_number`, `handoff_number`, `extra_calls`,
  `format_elapsed`, `status_line`, `NOT_CONNECTED`, and the history store (`wa-calls.json`,
  `wa-calls-forgotten.json`). Move the matching tests out of `wa_call.rs mod tests` (24 today; record how many moved
  and how many stayed with the GPUI half in the commit message).
- Phone prefs (`PrefsV2`) already live in `rovsti_core::settings`; backup glue moved in Step 1. Note that in the
  E2.3 evidence instead of moving anything else.
- Fixtures: `contacts-trash.json`, `contacts-prefs.json`, `wa-calls.json`, a notes file.
- Verify: `cargo test -p rovsti_app_core`, `cargo check -p rovsti_contacts -p rovsti_phone`, kotlin-ui-check.
- Commit: `Kotlin UI (E2 R1): E2.3 Contacts and Phone logic moved (trash, prefs, WhatsApp glue, notes, WA calls)`.

## Step 5 (E2.1 part B + E2.8 Rust half): headless handlers in `rovsti_app_core`

- `src/headless.rs`: `pub type Handler = fn(&str, &str) -> String;`, `dispatch(kind, json, table)` and
  `run_guarded` (catch_unwind -> `""`), our own small copies (`rovsti_android` keeps its own for the GPUI binaries;
  it is deleted in E8).
- `src/stops/headless.rs` and `src/messages/headless.rs`: move `apps/{stops,messages}/src/headless.rs` whole with
  their 34 tests; they now use `crate::storage`, `crate::stops::store`, `crate::wa_link::send::send_text_in`.
  `apps/{stops,messages}/src/headless.rs` shrink to `pub use rovsti_app_core::<app>::headless::handle;` and
  `main.rs` keeps `rovsti_android::headless_entry!(headless::handle);`, so the GPUI APKs answer exactly as before.
- `src/headless.rs` also gets `pub fn handle_any(kind, json) -> String` = Messages table then Stops table (what
  `rovsti_ffi::headless` will call), plus a test that the two tables have no common kind.
- Byte-identical proof (E2.8 AC1): `crates/rovsti_app_core/tests/headless_golden.rs` with
  `tests/fixtures/headless/<kind>.{request,answer}.json` for every kind above (10). **Write the answers with the
  pre-move code first** (temporary test in the GPUI app crate run once with `cargo test -p rovsti_messages headless`
  / `-p rovsti_stops headless`, output saved, temporary test removed), then move the code and assert equality.
  Requests that need a data dir use a temp dir seeded from `tests/fixtures/dirs/{messages,stops}/`; requests that
  would hit the network (`wa_reply`, `trip_fix` notice) use the existing injected-sender seams (`fix_in(.., send)`)
  through a test-only function, not the real gateway.
- AD-MSG4 fallback (E2.8 AC2, Rust half): tests that an unknown kind, malformed JSON and a panicking handler all
  answer `""`.
- Verify: `cargo test -p rovsti_app_core`, `cargo check -p rovsti_stops -p rovsti_messages`, kotlin-ui-check.
- Commit: `Kotlin UI (E2 R1): E2.1/E2.8 headless handlers moved, golden answers`.

## Step 6 (E2.5): `rovsti_ffi`

- New `crates/rovsti_ffi` (`crate-type = ["cdylib", "rlib"]`, deps `jni` (workspace), `rovsti_app_core`,
  `rovsti_core`, `serde`, `serde_json`, `log`; `android_logger` only under `cfg(target_os = "android")` if it needs no
  C toolchain, otherwise `log` to stderr via a 20-line logger of our own).
- Modules:
  - `envelope.rs`: `enum Answer { Ok(Value), Err(FfiError) }`, `FfiError { kind: ErrorKind, message: String,
    detail: Option<Value> }`, `ErrorKind` = `invalid|not_found|io|parse|network|auth|unsupported|panic` (serde
    snake_case, ARCH 6.5). `fn encode(..) -> Vec<u8>`.
  - `guard.rs`: `fn guarded<F: FnOnce() -> Result<Value, FfiError>>(f) -> Vec<u8>` with `catch_unwind`; panic
    payload string goes into `message`. A panic hook installed in `init` logs once.
  - `handles.rs`: `Registry` = `Mutex<Slab<Box<dyn Any + Send>>>` (`slab` crate, pure Rust; or a `Vec<Option<..>>` +
    free list of our own if adding a crate is not wanted), `insert<T>`, `with<T, R>(id, f)`, `release(id) -> bool`.
    Ids are `i64` with a generation in the high 32 bits so a stale id never aliases a new object.
  - `events.rs`: `trait EventSink: Send + Sync { fn emit(&self, kind: &str, json: &[u8]); }`, a global
    `RwLock<Option<Arc<dyn EventSink>>>`, `emit(kind, value)`; the JNI implementation (`JniSink`) holds a
    `GlobalRef` + `JavaVM`, attaches the calling thread as daemon and calls `onEvent(String, byte[])`.
  - `ops/mod.rs`: `fn call(op: &str, request: &[u8]) -> Vec<u8>` splitting on the first `.` and dispatching to
    `ops::core` (R1: `core.version`, `core.echo` (returns its request), `core.data_dir`, `core.fail` with a `kind`
    argument for the error-mapping tests, `core.panic` behind `cfg(any(test, feature = "test-ops"))`), and an
    unknown op -> `unsupported`. Domain modules arrive in E2.9-E2.14.
  - `state.rs`: `init(data_dir, cache_dir, app_id, sdk)` stores an `AppDirs` in a `OnceLock` (second init with the
    same dirs is a no-op, different dirs -> `invalid`) and returns `{"version": CARGO_PKG_VERSION, "abi": 1}`.
  - `jni_exports.rs`: the 8 exports of ARCH 6.3 on class `dev.rovsti.core.RustNative`
    (`Java_dev_rovsti_core_RustNative_{init,call,headless,tile,audioPush,audioPull,setEventSink,release}`), each a
    thin wrapper over `guarded`. `tile`, `audioPush`, `audioPull` answer `unsupported` / empty / 0 until E2.13/E2.14.
    `headless` calls `rovsti_app_core::headless::handle_any` through `run_guarded` (null-safe: unreadable strings ->
    `""`). The exports compile on every target (no `cfg(target_os = "android")`), so the host `.so` is real.
- Tests (`cargo test -p rovsti_ffi`): envelope round trips for every `ErrorKind`; `core.panic` -> `kind = panic` and the
  next call still works; handle leak test (create and release 10 000 handles; registry length back to 0; a released id
  is refused); event sink test: a thread emits 1 000 events into a Rust fake sink, all arrive in order (the JVM fake
  is Step 7).
- Builds (E2.5 AC4): `cargo build -p rovsti_ffi` (host), `cargo build -p rovsti_ffi --target aarch64-linux-android`
  and `--target x86_64-linux-android` (env from `scripts/android-env.sh`). `nm -D --defined-only` on the aarch64 `.so`
  lists the 8 `Java_dev_rovsti_core_RustNative_*` symbols: add this as a step `scripts/check-ffi-exports.sh` called
  by `kotlin-ui-check.sh` after the Android `cargo build`.
- `kotlin-ui-check.sh`: `RUST_PKGS += -p rovsti_ffi`; the Android build step builds `rovsti_ffi` for aarch64 too.
- Commit: `Kotlin UI (E2 R1): E2.5 rovsti_ffi exports, envelope, panic guard, handles, event sink`.

## Step 7 (E2.6): `:core-bridge` Gradle module

- `scripts/android-setup.sh`: `command -v cargo-ndk || cargo install cargo-ndk --locked` (pin the version that
  installs: record it in `docs/kotlin-ui/toolchain.md`). Run it once in this step.
- `android/settings.gradle.kts`: `include(":core-bridge")`. `android/core-bridge/build.gradle.kts`:
  `rovsti.android.library`, `kotlin.serialization`, namespace `dev.rovsti.core`, deps `kotlinx-serialization-json`,
  `coroutines-core`; test deps `junit`, `coroutines-test`, `robolectric`.
- Cargo tasks in a new convention file `build-logic/convention/src/main/kotlin/CargoTasks.kt` (typed `Exec` tasks,
  configuration-cache safe):
  - `cargoBuildAndroid`: `cargo ndk --platform 26 -t arm64-v8a -t x86_64 -o <buildDir>/rustJniLibs build -p rovsti_ffi`
    (`--release` for release variants). Platform 26 = minSdk, matching `android-env.sh` linkers (ARCH says 34;
    write the reason in `api-notes.md`: a library linked for 34 refuses to load on API 26-33). Inputs
    `crates/**/*.rs`, `crates/**/Cargo.toml`, `Cargo.toml`, `Cargo.lock`; output dir; `preBuild` depends on it;
    `android.sourceSets["main"].jniLibs.srcDir(...)`. Second run must be UP-TO-DATE (AC1).
  - `cargoBuildHost`: `cargo build -p rovsti_ffi` -> copies `target/debug/librovsti_ffi.so` to
    `<buildDir>/rustHostLib/`.
- Kotlin (`android/core-bridge/src/main/kotlin/dev/rovsti/core/`):
  - `RustNative.kt`: `object RustNative` with the 8 `external fun`s exactly as ARCH 6.3 and
    `fun load() = System.loadLibrary("rovsti_ffi")` (idempotent).
  - `RustDispatcher.kt`: `val RustDispatcher = Dispatchers.IO.limitedParallelism(4)`; `assertNotMain()` (debug only,
    uses `Looper` via a tiny `MainThreadCheck` interface so plain JVM tests can pass a fake).
  - `RustError.kt`: `sealed class RustError(message) : Exception` with one subclass per `ErrorKind`, `Outcome<T>`
    (`Ok`/`Err`), `Envelope.decode<T>(bytes, serializer)` (strict `Json { ignoreUnknownKeys = false }`).
  - `Rust.kt`: `interface RustCore { suspend fun version(): Outcome<String>; suspend fun call(op, request): Outcome<JsonElement>;
    suspend fun headless(kind, json): String }`, `class NativeRustCore : RustCore` (real, runs on `RustDispatcher`),
    `object Rust { fun init(dataDir: File, cacheDir: File, appId: String, sdk: Int, scope: CoroutineScope): Deferred<...> }`
    (called from `Application.onCreate` off the main thread; `ready` flow). No `Context` parameter anywhere (ARCH 6.7
    classloader trap).
  - `RustEvents.kt`: `class RustEvents : (String, ByteArray) -> Unit` with `onEvent` (`@JvmName`) and
    `val events: SharedFlow<RustEvent>` (`MutableSharedFlow(extraBufferCapacity = 1024)`, `tryEmit`, on failure emits
    one `RustEvent.Resync`).
- Tests:
  - `testRustBridge` (new `Test` task in `:core-bridge`, plain JUnit, `forkEvery = 0`, `maxParallelForks = 1`,
    `systemProperty("java.library.path", rustHostLib)`, depends on `cargoBuildHost`, source set
    `src/testRust/kotlin`): `RustBridgeTest` inits with a temp dir, `core.version` round trip, `core.echo` with 1 MB,
    error kinds through `core.fail`, `core.panic` maps to `RustError.Panic` and the next call works, `headless` with
    an unknown kind returns `""` and with a `sms_classify` fixture equals the Step 5 golden; `RustEventsTest`: a
    `core.emit_test` op (test-ops feature, host build only) emits 1 000 events from a Rust thread; the flow collects
    1 000 in order (E2.5 AC3).
  - `testDebugUnitTest` (Robolectric, same Gradle invocation): `FakeRustCoreTest` uses a `FakeRustCore` (in
    `src/testFixtures` or `:testing`) and passes while `testRustBridge` loaded the real library (E2.6 AC3 second half).
- APK proof (AC4): `:catalog` gets `implementation(project(":core-bridge"))` and calls nothing yet; a test in
  `scripts/check-apk.sh` style (`unzip -l`) asserts `lib/arm64-v8a/librovsti_ffi.so` and `lib/x86_64/librovsti_ffi.so`
  in `catalog-debug.apk`, and `scripts/check-ffi-exports.sh` runs on the packaged `.so`.
- `kotlin-ui-check.sh`: add `:core-bridge:testRustBridge` to the Gradle line. Check Gradle still finishes in a
  reasonable time (the debug Rust build is incremental).
- Disk: `df -h .` before; the two Android ABIs plus host add ~1.5 GB of `target/`. Stay >= 6 GB.
- Commit: `Kotlin UI (E2 R1): E2.6 :core-bridge, cargo tasks, RustNative, dispatcher, events`.

## Step 8 (E2.7 + E2.8 Kotlin half + close R1)

- E2.7 fixtures: `crates/rovsti_ffi/tests/fixtures.rs` writes `crates/rovsti_ffi/fixtures/<op>.{request,answer}.json`
  for every R1 op (`core.version`, `core.echo`, `core.data_dir`, one `core.fail.<kind>` per kind) and every headless
  kind (`headless.<kind>.{request,answer}.json`, the same bytes as Step 5's goldens: reuse, do not duplicate; the
  Step 5 files may move here and `rovsti_app_core` reads them by relative path). Without `UPDATE_FIXTURES=1` the test
  fails if a file differs; with it, it rewrites.
- Kotlin models `dev.rovsti.core.model.core` (`VersionInfo`, `DataDirInfo`) and `dev.rovsti.core.model.headless`
  (`SmsClassifyRequest/Answer`, ... one pair per kind, field names via `@SerialName`), listed in
  `android/compose_stability.conf`. `ContractFixturesTest` (plain JVM test in `testDebugUnitTest`, no library needed)
  reads every file under `crates/rovsti_ffi/fixtures` (path passed as a system property), decodes into its model
  with `ignoreUnknownKeys = false`, re-encodes and compares as `JsonElement`. A fixture without a model mapping fails
  the test (so E2.9+ cannot add an op without a Kotlin model). `RustErrorTest`: every `ErrorKind` envelope maps to
  its `RustError` subclass, unknown kind -> `RustError.Unknown`.
- E2.8 AC2 Kotlin half: `HeadlessClient` (`suspend fun ask(kind, json): HeadlessAnswer?` returning `null` on `""`,
  on `UnsatisfiedLinkError` and on any `RustError`), with tests using a fake `RustCore` for: library missing,
  panic (`""`), empty answer -> `null` (the caller in `:platform` then posts the plain notification, E3.4/E3.6).
- Full Rust gate once (disk >= 8 GB first): `cargo fmt --all && cargo clippy --workspace --all-targets &&
  cargo test --workspace` and `cargo check -p rovsti_android --target aarch64-linux-android`. Fix clippy findings in
  the moved code only. If the workspace test run would drop below 6 GB, run `cargo test` per touched package
  (`rovsti_app_core rovsti_ffi rovsti_ui rovsti_wa_ui rovsti_stops rovsti_messages rovsti_contacts rovsti_phone`) and
  `cargo clippy` for the same list, and write that in the evidence.
- `bash scripts/kotlin-ui-check.sh` ALL GREEN. Rebuild the catalog APK (`./gradlew :catalog:assembleDebug`) and
  state its path and size (it now carries `librovsti_ffi.so`).
- epics.md: E2.1-E2.8 `done` with Evidence lines (commits, test names and counts, fixture paths, `nm` export list,
  APK path). `docs/kotlin-ui/api-notes.md`: the bridge API and the platform-26 decision. Hand-off: next planner does
  E2 R2 (E2.9-E2.14 domain ops) and/or E3 R1.
- Commit: `Kotlin UI (E2 R1): E2.7 contract fixtures and models, E2.8 Kotlin fallback, close E2 R1`.

## Risks

- `rovsti_wa_client` pulls rustls + graviola: its Android build is already proven by the GPUI apps; `rovsti_ffi` adds
  no C dependency (ARCH 6.7 rule). If `cargo ndk` misbehaves, `cargoBuildAndroid` may call `cargo build --target`
  with the linker env computed from `ndk.dir` instead; record which in `toolchain.md`.
- `wa_call.rs` and `whatsapp.rs` are tangled: if a function needs `cx`, it stays; note each kept item in the commit.
- Headless goldens touching time: inject `now_ms` (the existing `fix_in` pattern) rather than freezing the clock.
