# Round 09 plan: E3 R2 = files, permissions, `:wa`, WhatsApp settings, `:shell` (E3.8a-E3.11)

Planned 2026-11-09 at base `840588c` (branch `ui-overhaul`). Epic: **E3 Platform layer**, round R2. It is the last
round of E3. After it, E4 Contacts can start (its hard dependencies are E3.2, E3.6, E3.8a/b, E3.10 and E3.11).

Owner request for this workflow: "make our project GPL, port the code with very high similarity". It was delivered in
R3 (E1.23, commit 542e271) and re-checked at this base:
- `LICENSE` is the GPL-3.0 text and `Cargo.toml` has `license = "GPL-3.0-or-later"`; every crate uses
  `license.workspace`.
- `README.md` "Licence" names Convx and the notices file, and `CLAUDE.md` has the GPL and attribution line.
- `android/THIRD_PARTY_NOTICES.md` lists Convx, Kyant0 backdrop / AndroidLiquidGlass, compose-hig and glisse.
- The glass stack is the Convx port (E1.24-E1.26, `GlassRealPathTest`). The "no glass on device" bug is closed by
  E1.26 and the catalog APK.

The rule for this round is the owner's: where a Convx file fits, it is **ported close to verbatim**. Here that means
the settings-page layout: `IosSettingsGroup.kt`, `screens/settings/SettingsPage.kt`, and the about/licence rows of
`screens/settings/AboutScreen.kt`. The ported file keeps the Convx header (`Convx Project (C) 2026 / Licensed under
GPL-3.0`) and adds `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`.
Everything else in this round is Rovsti's own code (GPL-3.0-or-later).

## State found (verified in code, not trusted)

- `git status` is clean. HEAD is `840588c` (E3 R1 close). `stash@{0}` is the deprecated GPUI layout WIP. Never pop it.
- **E1 done** (R5) and **E2 done** (R2). Neither has changed since. The previous round (08) changed no UI and left no
  PNG. These goldens are the building blocks E3.10/E3.11 reuse, and both were opened and judged:
  - `android/catalog/screenshots/e1.16-field-with-selection-light.png`: a capsule field with the selection and two
    handles, a search field with a clear button, and a composer. It is correct.
  - `e1.22-states-page-dark.png`: buttons and capsules are evenly spaced and nothing overlaps. It is correct.

  No fix is carried into this round.
- **E3 R1 done**, checked file by file:
  - `android/settings.gradle.kts` includes `:platform`.
  - `android/platform/src/main/kotlin/dev/rovsti/platform/{calllog,contacts,location,notify,sms,system,telephony,wa,wire}`
    exist.
  - All 17 guard tests that round-08 lists exist under `android/platform/src/test/kotlin/dev/rovsti/platform/**`:
    `LibraryManifestTest`, `PrefsNamesTest`, `PlatformConventionTest`, `WireModelsTest`, `FqcnTest`,
    `AndroidContactsRepositoryTest`, `AndroidCallLogRepositoryTest`, `AndroidSmsRepositoryTest`, `ScheduledSmsTest`,
    `IncomingOrderTest`, `NotificationChannelsTest`, `ReplyReceiverTest`, `TripServiceTest`, `TripAlarmTest`,
    `PendingIntentContractTest`, `PortInventoryTest` and `WaIncomingTest`.
  - `grep -rln "external fun\|RovstiBridge\|loadLibrary" android/platform/src/main` finds nothing.
  - `crates/rovsti_app_core/tests/kotlin_contract.rs` exists.
- What R2 has to port. Bridge files are in `crates/rovsti_android/kotlin/dev/rovsti/bridge/`:
  - `Documents.kt` (428 lines): SAF export/import, contacts/calls export, photo pick, scan pick, camera capture,
    `fileCap`, `copyLimited`, `readLimited` and `resultJson`.
  - `MediaShare.kt` (135 lines): the `<pkg>.rovstishare` authority, the `share` cache dir, safe names, mime table,
    `saveToGallery` and `stageForShare`. **`RovstiShareProvider` is the class at `MediaShare.kt:110`**; it has no file
    of its own.
  - In `RovstiBridge.kt`:
    - permissions: `groups` table, `permissionState`, `requestPermission` with prefs `rovsti_bridge` key
      `asked_<group>` (`:640-690`), and `emitPermissionsNow` (`:765`);
    - roles: `isRoleHeld` and `requestRole` (`:797-815`);
    - special access: `canScheduleExactAlarms`, `canUseFullScreenIntent`, `openFullScreenIntentSettings`,
      `openAppDetails`, `openDefaultApps` (`:744-762`), and `isIgnoringBatteryOptimizations` and
      `requestIgnoreBatteryOptimizations` (`:1107-1115`);
    - sharing and clipboard: `copyText`, `shareText`, `shareImage`, `shareFile`, `saveImageToGallery` (`:932-975`),
      `copyToClipboard(text, sensitive)` (`:513`) and `readSharedUri` (`:347`).
- WhatsApp:
  - The settings state machine lives in the **deprecated GPUI crate** `crates/rovsti_wa_ui/src/model.rs` (1 333 lines,
    26 tests). It is pure: no GPUI, only `rovsti_wa_proto` and `rovsti_app_core`. Its pieces are `LinkModel`,
    `Screen`, `Input`, `Effect`, `last_used_text`, `share_label` and the option strings.
  - `rovsti_ffi` does not expose it. `wa.*` covers the gateway, `link_read`/`link_write`, `avatars_plan`, `names` and
    `target` (`crates/rovsti_ffi/src/ops/wa/{mod,pure,rest,live}.rs`). The Kotlin facades are `WaOps` and
    `WaGateway` (`android/core-bridge/src/main/kotlin/dev/rovsti/core/ops/`).
  - The avatar policy is in Rust (`rovsti_app_core::wa_link::avatars::{AvatarBook, Backoff}`, op `wa.avatars_plan`).
  - Feature inventory of the GPUI screen (`crates/rovsti_wa_ui/src/view.rs`):
    - Gateway address row and "Use this address".
    - Two options: "Link WhatsApp for the first time" and "Connect to an existing instance".
    - QR or "Use a pairing code instead" (phone number), then "Show a QR code" / "Get a pairing code".
    - QR page with "Share QR to PC..." and Cancel.
    - Pairing-code page.
    - Paste key with "Paste from clipboard" and Connect.
    - Connecting... page.
    - Connected page: status Connected / Linking / Reconnecting / Logged out / Banned, plus "Show connect key"
      (mint, copy, "Copy again"), "Connected apps" key list with last-used text and Revoke (confirm), "Unlink this
      app" (confirm) and "Delete instance..." (two taps).
    - Logged-out page: "Link again" with QR or pairing code.
    - Failed page with "Try again".
- Shell: `RovstiBackdropRoot` and `RovstiNavHost`/`TabNavigator`/`ChromeSpec` are already in `:design`
  (`design/.../chrome/RovstiRoot.kt`, `design/.../transition/*`). `docs/kotlin-ui/shell-usage.md` is the contract,
  and `:catalog` (`CatalogApp.kt`) is the working example that E3.11 lifts into `:shell`.
- Prefs facts for the common settings pages:
  - `Appearance` (Auto/Light/Dark) exists only in Stops prefs (`core-bridge model/stops/StopsCommon.kt:68`).
  - `HapticsLevel` is **Off/Light/Normal** (`crates/rovsti_core/src/wire.rs:911`,
    `core-bridge model/phone/StoreModels.kt:25`). ARCH 7/E3.11 say "Off/System/Strong"; this round uses the real
    enum and fixes the docs.
- Disk: **8.8 GB free** (limit 6 GB). `target/` is 4.4 G, `android/core-bridge/build` 1.3 G, `android/catalog/build`
  651 M and `~/.gradle/caches` 6.3 G.

## Rules for this round

1. **Disk first, every step.** Run `df -h .` before any build.
   - Below 8 GB free: `rm -rf target/debug/incremental target/aarch64-linux-android/debug/incremental android/*/build`.
   - Below 7 GB: also run `cargo clean -p` for the deprecated GPUI crates (`rovsti_phone rovsti_messages
     rovsti_stops rovsti_contacts rovsti_ui rovsti_wa_ui liquid_glass_kit`), and remove old Gradle versions under
     `~/.gradle/caches/` other than the wrapper's.
   - Never go below 6 GB. Two new modules (`:wa`, `:shell`) add build dirs, so clean before Steps 4 and 6.
2. **Port, do not redesign** (same as R1). Bridge code keeps its logic, constants, request codes, authorities, prefs
   keys and extras. Add the line `Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/<File>.kt`. Activity-bound
   statics (`foreground`, `startActivityForResult`, `REQUEST_*`) become **`ActivityResultContract`s** plus
   `rememberLauncherForActivityResult` in the caller. Nothing in `:platform` holds an Activity.
3. **Domain logic in Rust.** The WhatsApp settings state machine moves into `rovsti_app_core` (it is pure already),
   and Kotlin only runs its effects. Permission decisions get a pure Kotlin twin of
   `rovsti_app_core::permission::Access` plus the "revoked while running" state (ARCH 7.5 allows a Kotlin twin with
   JVM tests). It is table-tested.
4. **Repository shape** (E3 AC1): an interface, `Android*` or `Gateway*` implementations, and a fake in
   `testFixtures`. A reflection test compares the method sets of the interface and its fake (the R1 pattern).
5. **Screens:** a ViewModel with one `@Immutable UiState`, `onAction(sealed)`, `stateIn(WhileSubscribed(5_000))`
   (ARCH 5.2). Use only `:design` components:
   - rows: `InsetGroupedSection`, `ListRow`, `IosList`;
   - text: `GlassTextField` and `SearchField` (selection and paste menu, E1.16);
   - overlays: `ButtonMenuEntry`/`IosMenu`, `ContextMenuEntry`, `SheetEntry`, `Alert`/confirm, `Toast`;
   - chrome: `LargeTitleScaffold`, `IosToggle`, `SegmentedControl`.

   Follow `references/navigation-layout.md` 6, 7 and 10, and `references/overlays.md` 3-6. Run the screen review
   `references/checklist.md` B-H for every new screen and write it into the step's commit message.
6. **Screenshots** use `ShotTest` from `:testing` (light and dark, plus `-fs2` where a story asks for it). Goldens go
   in `android/<module>/screenshots/`. Every new PNG is **opened with Read and judged**, and what was seen goes into
   the Evidence line. Look for:
   - no overlap and no clipped text;
   - the last row clear of the bottom inset;
   - one source glyph per section, not per row;
   - consistent 16/20 dp gutters;
   - menus floating above content and never pushing it (owner complaints, ARCH 1).
7. **Check per step:** `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`. Rust steps (3) also run
   `cargo test -p rovsti_app_core -p rovsti_ffi -p rovsti_wa_ui`. The full `CLAUDE.md` gate runs in Step 8.
   `scripts/orphan-goldens.sh` must see the new modules' `screenshots/` directories. Check its module list in Step 4
   and extend it if it is hard-coded.
8. **Commits:** one per step, `Kotlin UI (E3 R2): <step title>`, ending with the session attribution lines. Never
   push to `main`.

## Steps

### Step 1. `platform.files`: documents, media, share provider, clipboard, picker, camera (E3.8a)

- Port `Documents.kt` and `MediaShare.kt` into `android/platform/src/main/kotlin/dev/rovsti/platform/files/`. Keep the
  constants: `MAX_BYTES`, `MAX_FILE_BYTES`, `cameraName`, `fileCap`, `copyLimited`, `readLimited`, `SAFE_NAME`,
  `CAMERA_NAME`, `mimeFor` and the `share` cache dir.
- `RovstiShareProvider` goes to `files/RovstiShareProvider.kt` under **`package dev.rovsti.bridge`** (FQCN), with the
  authority `"${packageName}.rovstishare"`. Its rules stay as they are: read-only, `openFile` limited to the share dir
  and safe names, and writable only for `camera-*.jpg`.
- Activity-result contracts, all in `files/Contracts.kt` (they replace `Documents.start*`, `startPhotoPick`,
  `startScanPick`, `startPhotoCapture`, `startFileExport`, `startFileImport`):
  - `ExportDocument(mime)` and `ImportDocument(maxBytes, kind = Json|Contacts|Calls|Attachment|Gtfs)`. The result is
    `FileResult` (`Ok(name, text|path, mime)` / `TooLarge` / `Cancelled` / `Failed(reason)`).
  - `PickPhoto` uses `PickVisualMedia(ImageOnly)` and never asks for `READ_MEDIA_*` (E3 AC7).
  - `CapturePhoto` uses `ACTION_IMAGE_CAPTURE` with an `EXTRA_OUTPUT` URI from `RovstiShareProvider`, no `CAMERA`
    permission, and keeps `FLAG_GRANT_WRITE_URI_PERMISSION`.
- `FilesRepository` (suspend):
  - `readUri(uri, maxBytes)`, which is the port of `readSharedUri`;
  - `writeExport(uri, text|path)`;
  - `saveToGallery(path, name, mime): Boolean` (MediaStore `IS_PENDING` as today);
  - `stageForShare(path, name): Uri?`.
- `ShareIntents`: `text`, `image`, `file`, `sendSmsTo` and `sendEmailTo` (chooser and grant flags as today). Each
  returns an `Intent`, and the caller starts it with `ActivityNotFoundException` caught.
- `ClipboardRepository`:
  - `copy(text, sensitive)` keeps the `ClipDescription.EXTRA_IS_SENSITIVE` flag from
    `MessageNotifications.copyToClipboard`;
  - `primaryText(): String?` and `changes(): Flow<Unit>`.
- Fakes are `FakeFilesRepository` and `FakeClipboardRepository` in testFixtures.
- Tests (Robolectric):
  - `RovstiShareProviderTest`:
    - a safe name opens read-only;
    - `../x` and an absolute path are refused;
    - `camera-1.jpg` opens for writing and `a.jpg` does not;
    - `getType` follows `mimeFor`.
  - `FilesLimitsTest`: `copyLimited`/`readLimited` at max-1, max and max+1 bytes, plus `fileCap` cases.
  - `ContractsTest`:
    - `createIntent` of each contract has today's action, mime and extras;
    - `parseResult` maps `RESULT_CANCELED` to `Cancelled` and an over-size stream to `TooLarge`.
  - `CaptureIntentTest`: the output URI has authority `<pkg>.rovstishare` and the write grant flag is set.
  - `ClipboardTest`: the sensitive extra is set when asked.
  - `NoMediaPermissionTest`: a source scan of `android/platform/src/main` plus the library manifest finds no
    `READ_MEDIA_`, `READ_EXTERNAL_STORAGE` or `CAMERA` permission.
- `FqcnTest` gains `dev.rovsti.bridge.RovstiShareProvider` as the 13th entry, replacing the `// R2 E3.8a` line.
  `platform-port.md` rows `Documents`, `MediaShare` and `RovstiShareProvider` become `ported`.
- Acceptance: the check is green.

### Step 2. Roles, `PermissionController`, `PermissionState`, special access, HANDOFF list (E3.8b)

- `dev.rovsti.platform.system.permissions`:
  - `PermissionGroup`: an enum with the ids of today's `groups` table (`RovstiBridge.kt:640-665`). Each entry holds
    its manifest permissions, so the location group keeps both fine and coarse permissions, and
    `background_location` stays separate.
  - `AppPermissions`: one committed table per app built from ARCH 7.5 (`AppPermissions.phone`, `.messages`,
    `.contacts`, `.stops`).
  - `PermissionState` is a sealed type: `NeverAsked`, `Granted`, `DeniedOnce`, `DeniedForGood`, `RevokedWhileRunning`.
  - `decide(granted, askedBefore, shouldShowRationale, wasGrantedThisProcess): PermissionState` is a pure function,
    a Kotlin twin of `rovsti_app_core::permission::Access`. Its mapping to `Access` is `DeniedForGood` ->
    `PermanentlyDenied`, and every other non-granted state -> `Denied`.
  - `nextAction(state): Ask | OpenSettings | None` gives the row's next action (ARCH 7.5).
  - `PermissionController(context, app: AppPermissions, prefs = rovsti_bridge)`:
    - `state(group): StateFlow<PermissionState>`;
    - `refresh(activity)`, called on `ON_RESUME` only, off the main thread, which is today's rule from
      `emitPermissionsChanged`;
    - `contract(group)`, a `RequestMultiplePermissions` wrapper that writes `asked_<group>`, the same key as today;
    - a `permissionsChanged: Flow` that emits on `PlatformEvents` kind `permissions_changed` with today's payload.
- `SpecialAccess` interface plus `AndroidSpecialAccess` and `FakeSpecialAccess`:
  - `exactAlarms()`, `fullScreenIntent()`, `ignoresBatteryOptimisation()`;
  - `isRoleHeld(role)` (`RoleManager.isRoleAvailable && isRoleHeld`) and `requestRoleContract(role)`;
  - `settingsIntent(kind)` for app details, default apps, full-screen intent, exact alarms, battery, and Xiaomi via
    the existing `XiaomiOps` with its fallback.
- `ForegroundStart.map(throwable)` turns `ForegroundServiceStartNotAllowedException` and `SecurityException` into
  `NotAllowed`, as `TripStartResult` already does. The phone-call FGS reuses it (ARCH 7.5).
- `platform.system.Handoff`: finish the roles half and leave the intents as ported.
- Tests:
  - `PermissionStateTest`, table-driven: every combination of the 4 inputs gives the expected state and next action.
    Cases include asked plus no rationale gives `DeniedForGood`, and granted-then-not-granted in one process gives
    `RevokedWhileRunning`.
  - `PermissionControllerTest` (Robolectric `ShadowApplication.grantPermissions`/deny): `refresh` updates the
    `StateFlow`s, the asked key is written, and a revocation between resumes gives `RevokedWhileRunning`.
  - `AppPermissionsTest`: each app's runtime set equals ARCH 7.5, row by row.
  - `SpecialAccessTest` (ShadowRoleManager or reflection, ShadowAlarmManager, ShadowPowerManager): each intent has
    today's action and `package:` data.
  - `HandoffPermissionsTest` (E3 AC6): commit `src/test/resources/manifest/{phone,messages,contacts,stops}.permissions.txt`,
    extracted from the GPUI manifests and `gpuiforge.json`. It holds the `uses-permission` lines, the
    `dev.rovsti.permission.HANDOFF` `<permission>` declarations (Contacts, Messages, Phone) and the components
    protected by it. The test parses `apps/*/platforms/android/app/src/main/AndroidManifest.xml` and asserts
    equality, so a drift in the source is caught now. The same files are the expected sets for each app's merged
    manifest test in E4.1/E5.1/E6.1/E7.1.
- `platform-port.md`: the E3.8b rows become `ported`. ARCH 7.5 gains a line naming `PermissionState` and
  `SpecialAccess`.
- Acceptance: the check is green.

### Step 3. Rust: the WhatsApp settings machine moves to `rovsti_app_core`, op `wa.settings_apply`

- Move `crates/rovsti_wa_ui/src/model.rs` to `crates/rovsti_app_core/src/wa_link/settings.rs`, together with its 26
  tests. Keep `LinkModel`, `Screen`, `Input`, `Effect`, `last_used_text`, `share_label`, `DEFAULT_GATEWAY_URL`, the
  `OPTION_*`, `ACTION_*` and `MSG_*` strings, and every transition.
- Add `serde` derives (tagged `#[serde(tag = "kind", rename_all = "snake_case")]`) so the whole model round-trips. The
  private fields stay private and serialize too.
- `rovsti_wa_ui::model` becomes `pub use rovsti_app_core::wa_link::settings::*;`. The GPUI crate keeps compiling and
  its tests keep passing, so the full gate stays green.
- New op `wa.settings_apply {model?, app, now_ms, input} -> {model, effects, view}`. It is **stateless**: the
  ViewModel holds `model` (as JSON in `SavedStateHandle`, so process death keeps the page).
  - `view` is a flat, render-ready description the Kotlin screen draws without logic: page kind, title, rows (label,
    value, role), buttons (id, label, destructive), notes, QR payload, code, and key rows with `last_used_text`
    already applied.
  - Model and input decode errors return `Invalid`.
  - Gateway URL rules (`GatewayUrl::parse`) and `rwk_` key checks run inside `apply` (inputs `UrlEntered`,
    `KeyPasted`), as today (AD-WA3).
- Fixtures go in `crates/rovsti_ffi/fixtures/wa.settings_apply.*`: one per screen (choose, enter URL, link-new QR,
  link-new code, show QR, show code, paste key with a bad key, connecting, connected, share key, keys,
  confirm-revoke, logged out, confirm-delete, confirm-unlink, failed). Register them in the op registry and in
  `OPS.md`.
- Kotlin side (`:core-bridge`):
  - `WaSettingsOps.apply(req)` added to the `WaOps` file, plus `model/wa/Settings.kt` (`@Serializable @Immutable`
    `SettingsView`, `SettingsEffect`, `SettingsInput`) with `compose_stability.conf` entries;
  - a `WireFixturesTest` case decodes every fixture.
- Tests:
  - Rust: the moved tests, plus `settings_serde_roundtrip` (every `Screen` survives JSON) and
    `view_for_every_screen`.
  - Kotlin: the fixture decode test, and `FakeRustCore` answers for the ViewModel tests in Step 5.
- Acceptance: the check is green, plus `cargo test -p rovsti_app_core -p rovsti_ffi -p rovsti_wa_ui` and
  `bash scripts/check-ffi-exports.sh`.

### Step 4. `:wa` module: `WaRepository`, link store, events, wake and resync (E3.9 AC1, AC3)

- Disk clean (Rule 1). Create `android/wa` with `rovsti.android.library`, `rovsti.compose`, `rovsti.screenshots` and
  `kotlin.serialization`, namespace `dev.rovsti.wa`. It depends on `api(:core-bridge)`, `implementation(:design)` and
  `implementation(:platform)` (seams only: `WaPush` events, `WaCallAudio`, `ClipboardRepository`, `ShareIntents`,
  `FilesRepository`). Enable `testFixtures` and add `include(":wa")`. The library manifest has no components.
  `LibraryManifestTest` gets a `:wa` copy, or a shared helper in `:testing`.
- `WaLinkStore`: `read()`/`write()`/`forget()` over `wa.link_read`/`link_write` (the same `whatsapp-link.json` in
  `noBackupFilesDir`, ARCH 7.1).
- `WaRepository` (ARCH 7.4), one per app container:
  - `linkState: StateFlow<WaLinkState>` with the states `None`, `Linking(qr|code)`, `Connected(status)`, `LoggedOut`,
    `Banned`, `Error(msg)`;
  - `gateway(): WaGateway?`, opened lazily from the stored link and closed on `forget`;
  - `events: SharedFlow<GatewayEvent>` from `startEvents`, filtered by instance;
  - `wake()`, which calls `eventsReconnectNow` and is collected from `PlatformEvents` kind `wa_push` (AD-WA9);
  - `chats(): Flow<List<RichChat>>` and `messages(jid): Flow<List<RichMessage>>`. Both refetch on `Resync` and on a
    gap (event sequence jump);
  - `relinkTo(link)`, used by the settings screen's `Save` effect.
- `GatewayEvent` stays the `:core-bridge` wire model. No second model.
- `FakeWaGateway` goes in testFixtures. It scripts `WaOps` answers and an events channel, and is built on
  `FakeRustCore`.
- Tests:
  - `WaRepositoryTest`: no link gives `None`; a stored link plus `StatusOk` gives `Connected`; `LoggedOut` and `Banned`
    events move the state; a `wa_push` platform event calls `eventsReconnectNow` once; a gap or `Resync` refetches
    chats exactly once (conflated); `forget` closes the gateway (later calls answer `NotFound`).
  - `WaLinkStoreTest`: round trip through the `wa.link_*` fixtures.
  - A `WaRepository` interface-vs-fake method-set test.
- Check whether `scripts/orphan-goldens.sh` and `ScreenshotsConventionPlugin` pick up `:wa` and `:shell`
  automatically. If not, extend them here.
- Acceptance: the check is green.

### Step 5. `:wa` calls, audio and avatars (E3.9 AC2, AC4)

- `WaCalls`:
  - `calls: StateFlow<List<CallSummary>>`, driven by call events;
  - `place(jid, video=false)`, `accept(id)`, `reject(id)`, `end(id)`, `mute(id, on)`;
  - `speaker(on)` through `platform.telephony.AudioPath`.
  - The audio pump uses `platform.wa.WaCallAudio.start(context, gateway.openCallAudio(id).asFrames())`. It starts on
    accept/connect and stops on end, with voice-communication attributes as ported in R1.
  - `RECORD_AUDIO` comes from `PermissionController`, group `microphone`. Without it, `place`/`accept` answer
    `NeedsMicrophone` and do not start the pump.
- `WaAvatars`, the Kotlin half of `rovsti_wa_ui::avatars`:
  - `want(jid, pictureId)` and `wantAll(...)` ask `wa.avatars_plan` (Rust decides: dedup, max parallel, 429 back-off,
    pruning);
  - jobs run on `Dispatchers.IO` through `WaGateway.picture` and are decoded with
    `BitmapFactory.Options.inSampleSize` to the slot size (`RovstiImages` slot sizes, E1.20);
  - a 429 reports `rate_limited` back to the plan;
  - results land in a disk cache dir `wa-avatars/` that is pruned per the plan's `prune` list, and in a Coil
    `Fetcher` keyed `wa-avatar:<jid>`, so `RovstiImage` shows them with the E1.20 fade;
  - `name(jid)` and `profile(jid)` come through `wa.names`/`profiles`.
- Tests:
  - `WaCallsTest` (FakeWaGateway plus a fake `CallFrames`): accept starts the pump and end stops it (thread joins
    within 1 s); mute is forwarded; a missing microphone grant gives `NeedsMicrophone`.
  - `WaAvatarsTest`:
    - two wants for the same jid give one fetch;
    - a 429 means no fetch until the back-off passes (virtual time);
    - prune deletes the listed files;
    - the Coil fetcher answers from the cache.
  - `WaCallAudioFrameTest` stays green.
- Acceptance: the check is green. `platform-port.md` gets a `:wa` section.

### Step 6. Shared WhatsApp settings screen (E3.10)

- `dev.rovsti.wa.settings.WhatsAppSettingsViewModel(app: AppId, ops: WaOps, repo: WaRepository, clipboard, share, clock)`:
  - it holds the model JSON in `SavedStateHandle` and calls `wa.settings_apply` for every action;
  - it runs `effects` in order: `CreateInstance`, `Verify`, `Save` -> `repo.relinkTo`, `Forget`, `Subscribe`,
    `AbandonInstance`, `MintKey`, `CopyToClipboard` (sensitive = true), `ListKeys`, `RevokeKey`, `Relink`,
    `DeleteInstance`;
  - each answer is fed back as the matching `Input` (`Created`, `StatusOk`, `Unauthorized`, `Error`, `ShowKey`,
    `KeysLoaded`, `Revoked`, `Deleted`), and gateway `events` become `Input.Event`;
  - `UiState(view: SettingsView, busy: Boolean)` is `@Immutable`.
- `WhatsAppSettingsScreen(state, onAction)` draws `view` only, with no branching logic beyond the page kind:
  - `LargeTitleScaffold` titled "WhatsApp", then `InsetGroupedSection`s. The first page has exactly two option rows
    (AC1) and a "Gateway" section with the address row.
  - The URL and phone fields are `GlassTextField` (keyboard types URI and phone, IME action submits).
  - The paste-key field is a `GlassTextField` with a trailing "Paste" button (`ClipboardRepository.primaryText`); the
    field's own selection/paste menu keeps working.
  - The QR page uses the `qr.encode` image at 240 dp in a white rounded card, the instruction note, a "Share QR to
    PC..." button (`FilesRepository.stageForShare` + `ShareIntents.image`) and Cancel.
  - The pairing code is set in a monospaced large style with letter-spacing, in two groups of four.
  - The connected page shows a status row with a coloured dot, "Show connect key" (copy, share, QR), "Connected apps",
    "Unlink this app" and "Delete instance...".
  - The key list is rows with `last_used_text`; "This app" is marked. A row's trailing "..." opens an `IosMenu` with
    Revoke (destructive), which goes to a confirm `Alert`.
  - Unlink and Delete use a confirm `Alert` (destructive). For Delete, the model's two-tap rule is the second
    confirmation.
  - Every failure is a page with "Try again". A toast is used for "Copied".
  - Glass: the large-title chrome and menus sample the page through `RovstiBackdropRoot` (the screenshot host wraps
    it, as the catalog does).
- A catalog page "WhatsApp settings" drives the screen with `FakeWaGateway`, so the APK shows it.
- Tests:
  - `WhatsAppSettingsViewModelTest` (FakeRustCore with the Step 3 fixtures and FakeWaGateway): one walk per path:
    - link new with QR, then a `Connected` event;
    - pairing code;
    - paste a bad key, then the rejected message, then a good key and Connected;
    - mint, then copy (the clipboard fake saw the key, marked sensitive);
    - revoke with confirm;
    - delete with two taps;
    - logged out, then link again;
    - recreate from `SavedStateHandle` restores the page.
  - Compose `WhatsAppSettingsScreenTest` (`mainClock.autoAdvance = false`): exactly two option rows when not linked;
    the key-row "..." opens the menu without moving the list (bounds of row 1 unchanged); text selection plus paste in
    the key field.
- Screenshots in `android/wa/screenshots/`, light and dark:
  - `e3.10-wa-not-linked`, `e3.10-wa-linking-qr`, `e3.10-wa-pairing-code`;
  - `e3.10-wa-connect-key-field` (pasted `rwk_...` key, part selected, selection toolbar shown);
  - `e3.10-wa-connected`, `e3.10-wa-key-list-menu-open`, `e3.10-wa-logged-out`;
  - plus `e3.10-wa-not-linked-fs2-light`.

  Open every PNG. Check that the QR is square and not clipped, that the menu floats over the rows (not inline), that
  long gateway URLs ellipsize in the middle, and that the fs2 rows grow and do not overlap.
- Acceptance: the check is green, and E3.10 AC1/AC2 are ticked against the screenshots.

### Step 7. `:shell` module: base activity, root, containers, `routeFor`, permission UI (E3.11 part 1)

- Disk clean. Create `android/shell` (`rovsti.android.library`, `rovsti.compose`, `rovsti.screenshots`), namespace
  `dev.rovsti.shell`. It depends on `api(:design)`, `api(:platform)` and `api(:wa)`. Add `include(":shell")`. The
  manifest has no components.
- `ShellContainer` (abstract): `rust` (lazy init with `noBackupFilesDir`), `haptics`, `imageLoader` (with the `WaAvatars`
  fetcher), `wa: WaRepository`, `permissions: PermissionController`, `specialAccess`, `clipboard` and `files`. All
  are lazy, so nothing heavy runs before the first frame. `interface HasContainer<C : ShellContainer>` and
  `Context.shellContainer()` complete ARCH 5.3.
- `RovstiShellActivity` (abstract `ComponentActivity`):
  - `enableEdgeToEdge()`, `adjustResize` (the docs say the app manifest sets it, and a merged-manifest test in E4.1
    checks it), and `OnBackInvokedCallback` through `BackHandler` order (overlay, then stack, then activity);
  - the highest refresh rate: `window.attributes.preferredDisplayModeId` set to the max-refresh mode at the current
    resolution;
  - `onResume` calls `permissions.refresh(this)`, the only place that does;
  - `onNewIntent`/`onCreate` take `routeFor(intent.extras)` and call `navigator.resetTo` (`Mailbox` replacement, ARCH
    7.3);
  - `abstract fun Content()`.
- `RovstiShell(navigator, tabs, chromeFor, overlays, entries)` wraps `RovstiNavHost` and is lifted from
  `CatalogApp.kt`. `:catalog` switches to it, and its goldens must stay byte-identical, which proves the move.
- Permission UI, `dev.rovsti.shell.permissions`:
  - `PermissionGate(group, rationale)` is a composable that returns the state and shows, when needed, a
    **rationale sheet**: a `SheetEntry` at medium detent with an icon, title, body and the buttons "Continue" ->
    contract and "Not now".
  - `PermissionRow(state)` is the denied-state row for setup sections, with "Allow" or "Open Settings" from
    `nextAction`.
  - `SpecialAccessRow(kind)` covers exact alarms, full screen and battery.
  - Copy strings go in `res/values/strings.xml` of `:shell`.
- Tests:
  - `RouteForPatternTest`: a sample `routeFor` with extras (`open_contact`, `answer_call_id`), using `TabNavigator`
    `resetTo`.
  - `ShellContainerTest`: lazy, no Rust init until first use (`FakeRustCore` counter).
  - `PermissionGateTest` (Compose, `autoAdvance = false`):
    - `NeverAsked` shows the sheet, and Continue launches the contract (a fake registry);
    - `DeniedForGood` shows "Open Settings" and launches the settings intent;
    - `RevokedWhileRunning` after a resume shows the row again.
  - `RefreshRateTest` (Robolectric, ShadowDisplay with 60/120 modes): the 120 Hz mode id is chosen.
- Screenshots in `android/shell/screenshots/`, light and dark: `e3.11-permission-rationale` (sheet over a list page)
  and `e3.11-permission-denied-row`. Open both. Check the sheet's grabber and corner radius, that the page behind is
  pushed back and not covered by an opaque block, and that the row's button is right-aligned without wrapping.
- Acceptance: the check is green and the catalog goldens are unchanged.

### Step 8. Common settings pages (Convx port), licences, close E3

- Port from Convx, near-verbatim with the headers (Rule in the intro):
  - `app/src/main/kotlin/com/convx/music/ui/component/IosSettingsGroup.kt` becomes
    `dev.rovsti.shell.settings.IosSettingsGroup` (icon tile, row, chevron, grouped card). Use our `IosColors`/
    `IosTheme` tokens; the rest stays as written.
  - `ui/screens/settings/SettingsPage.kt` becomes `SettingsPage`/`SettingsLazyPage`/`SettingsScaffold` (large title,
    gutter rule).
  - The rows of `ui/screens/settings/AboutScreen.kt` become `AboutPage`. Drop the Convx links and Discord; add the
    version name and code, the Rust core version (`RustCore.version()`, op `core.version`, already in `:core-bridge` `Rust.kt:21`) and "Licences".
  - Add the three files to `android/THIRD_PARTY_NOTICES.md`'s Convx file list.
- `CommonSettings` interface, which each app container implements over its own prefs ops in E4-E7:
  - `appearance: StateFlow<Appearance?>`: `null` hides the page for apps without an appearance pref;
  - `haptics: StateFlow<HapticsLevel?>`, with Off/Light/Normal as in `wire.rs:911`;
  - setters for both;
  - `FakeCommonSettings` in testFixtures.
- Pages:
  - `AppearancePage` is a `SegmentedControl` Auto/Light/Dark. `RovstiTheme` follows it (`FOLLOW_SYSTEM_THEME` for
    Auto).
  - `HapticsPage` is a radio list Off/Light/Normal, with a preview tap through `RovstiHaptics`.
  - `LicencesPage` lists `THIRD_PARTY_NOTICES.md`, bundled as an asset of `:shell` by a Gradle `copy` task from
    `android/THIRD_PARTY_NOTICES.md` (so it is single-sourced). It has one row per project (name, licence, files
    count) and a detail page with the full text, plus the project's own GPL-3.0 `LICENSE`.
  - The parser `NoticesParser` is pure and JVM-tested: every `##` project has a licence line.
- Docs: in ARCH 7.4/E3.11 fix "Off/System/Strong" to "Off/Light/Normal". `docs/kotlin-ui/shell-usage.md` gains
  "Activity, container, permissions, settings pages". `platform-port.md` is complete; `PortInventoryTest` allows only
  `ported`, `dropped`, or `app module`, and no `R2` rows remain.
- Screenshots in `android/shell/screenshots/`, light and dark: `e3.11-settings-appearance`, `e3.11-settings-haptics`,
  `e3.11-licences`, `e3.11-licence-detail`, `e3.11-about`, plus `e3.11-licences-fs2-light`. Open each one. Check
  that the icon tiles are aligned, that long licence names wrap inside the row and do not push the chevron off, and
  that the last row clears the bottom inset.
- Full `CLAUDE.md` gate after the disk check: `cargo fmt --all && cargo clippy --workspace --all-targets &&
  cargo test --workspace`, then `cargo check -p rovsti_android --target aarch64-linux-android`, then
  `bash scripts/kotlin-ui-check.sh`. The catalog APK, which now has the WhatsApp settings page, is rebuilt by the
  check at `android/catalog/build/outputs/apk/debug/catalog-debug.apk`. Name it in the commit message.
- epics.md:
  - set E3.8a-E3.11 to `done` with one Evidence line each (commit, test classes, and the PNGs opened with what was
    seen);
  - mark the E3 heading `- DONE (R2)`;
  - point the next planner at **E4 R1** (E4.1-E4.8), reminding them that the E4.1 merged-manifest test uses
    `src/test/resources/manifest/contacts.permissions.txt` from Step 2.

## Verification summary for the next planner

- `android/settings.gradle.kts` includes `:wa` and `:shell`, and neither library manifest has components.
- `dev.rovsti.bridge.RovstiShareProvider` loads (`FqcnTest`, 13 names), and `NoMediaPermissionTest` is green.
- These test classes exist and run:
  - Steps 1-2: `PermissionStateTest`, `PermissionControllerTest`, `HandoffPermissionsTest`;
  - Steps 4-5: `WaRepositoryTest`, `WaCallsTest`, `WaAvatarsTest`;
  - Steps 6-8: `WhatsAppSettingsViewModelTest`, `WhatsAppSettingsScreenTest`, `PermissionGateTest`,
    `RefreshRateTest`, `NoticesParserTest`.
- `crates/rovsti_app_core/src/wa_link/settings.rs` holds the machine, `rovsti_wa_ui::model` re-exports it, and the
  `wa.settings_apply` fixtures exist.
- Goldens: 8 `e3.10-*` (x2 themes, plus fs2) in `android/wa/screenshots/` and the `e3.11-*` set in
  `android/shell/screenshots/`, all opened and judged in the Evidence lines.
