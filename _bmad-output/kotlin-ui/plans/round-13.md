# Round 13 plan: E5 Phone R2 part 1 (recover E5.5, Phone Turkish, call-screen owner fixes F17-F20, photo background, slide to answer, F21 tab bar, E5.8 WhatsApp calls, E5.7b multi-call)

Planned 2026-11-10 at base `ca993e6` (branch `ui-overhaul`). Epic: **E5 Phone**. E1-E4 are done. The first E5 round
(`plans/round-12.md`, revision 2) delivered E5.1-E5.4, E5.6 and E5.7a; E5.5 is stashed. This round takes E5.5 back,
closes every open owner/coordinator item on the call screens (F17-F20, caller photo background, slide to answer, iOS 26
Phone direction), fixes a tab-bar drawing defect found in the goldens (F21), then E5.8 (WhatsApp calls, audio proven end
to end first) and E5.7b (multi-call). E5.9-E5.14 stay for round 14.

Owner decisions in force: Rovsti is GPL-3.0-or-later; Convx code is ported close to verbatim with its original header plus
`Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`; glisse-derived code keeps the
glisse MIT header; `android/THIRD_PARTY_NOTICES.md` lists any new source. Owner rules (b) wait for `Rust.init` before any
`RustNative` call, (c) `imeOverlapPadding` only (never `imePadding` / `windowInsetsPadding(WindowInsets.ime)`), (d) R8
keep rules for anything reached by name, checked with `dexdump` on the release APK. Definition of done item 6 (Turkish,
launcher name/icon, notification icons, never crash on platform calls) applies to Phone.

## State found at `ca993e6` (verified in code, not trusted)

- Working tree clean. `stash@{0}` = "blocked: kotlin-ui r12 S6" (also branch `wip-r12-s6`, same commits `ab48efe` /
  `d1e7007` / `705f045`); `stash@{1}` = deprecated GPUI WIP: never pop.
- **Done and verified in code:** E5.1-E5.4, E5.6, E5.7a test classes exist (`KeypadLayoutTest`, `KeypadViewModelTest`,
  `KeypadScreenTest`, `DialFieldTest`, `RecentsViewModelTest`, `RecentsScreenTest`, `PersonSheetViewModelTest`,
  `CallDetailsViewModelTest`, `IncomingViewModelTest`, `IncomingScreenTest`, `CallRegistryIncomingTest`,
  `InCallViewModelTest`, `RoutePickerTest`, `InCallScreenTest`, `PushedPagesBackTest`), plus
  `docs/kotlin-ui/checklist-e5.md` and `docs/kotlin-ui/screenshots/phone.png`.
- **E5.5 not in the tree:** no `phone/.../favorites/` or `phone/.../contacts/` package, no `design/.../list/Reorder.kt`,
  no `e5.5-*` goldens. The stash holds them (untracked part `stash@{0}^3`: `Reorder.kt`, `PhoneContactsScreen/ViewModel`,
  `FavoritePicker`, `FavoritesScreen/ViewModel`, tests `E55ShotTest`, `PhoneContactsScreenTest`,
  `PhoneContactsViewModelTest`, `FavHost`, `FavoritesScreenTest`, `FavoritesViewModelTest`, `PeopleRig`,
  `testing/.../ComposeActivity.kt`, ten `e5.5-*` PNGs; tracked part: `PhoneApp.kt` +101, `PhoneContainer.kt` +7,
  `DetailsPresenter.kt` +8, phone `strings.xml` +28, `design/build.gradle.kts` +2, `contacts/.../FavoritesReorder.kt`
  reduced to a caller of `Reorder.kt`). `PhoneApp.kt` changed since (steps 7-8): a 3-way merge is needed.
- **Stashed E5.5 goldens opened** (extracted to the scratchpad): `e5.5-favorites-light` (large title, `+`/gear capsule,
  five favourites with 40 dp avatars, "Frequently Called" section, info buttons, full four-tab bar with the Favorites
  puck: correct), `e5.5-contacts-tab-dark` (search field, A/B/C/D sections, alphabet rail, star on a favourite, number
  in the shared display format, full tab bar: correct; the red lens blob at the bar's leading cap is the accepted F15
  Convx look).
- **Phone Turkish missing:** `phone/src/main/res/values-tr/` has only `launcher.xml`; `values/strings.xml` has 299
  strings with no Turkish. `diagnostics/DiagnosticsViewModel.kt:91-104` hard-codes English row labels ("Rust core",
  "Block guard", "Audio route change", "Last ring", ...).
- **Call-screen items still open (code + goldens):**
  - `e5.6-incoming-light.png` opened: **F17 open** (number under the name is raw `+905551112233`;
    `incoming/IncomingViewModel.kt:168-169` `subtitleOf` uses `numberRaw`; the formatter exists as
    `SharedOps.render(RenderRequest(raw, NumberForm.Display, home))`, used by `PersonSheetViewModel.kt:344`).
    **F18 open** (Answer/Decline glyphs are the stroked `RovstiIcons.Phone`, `IncomingScreen.kt:308-317`).
    **F19 open** (initials avatar dark green on the green aura). **F20 ringing open** (Remind me / Message / Silence are
    grey `GlassCircleButton` discs, `IncomingScreen.kt:278-293` through `CircleAction`).
  - `e5.7-incall-active-dark.png` opened: **F17 open** in In-call too (`+905559990000`), **F20 in-call open** (the grid
    is dark opaque discs, not white-tinted frosted glass; active toggles not solid white), F18 the End glyph is stroked.
  - **Caller photo background:** not implemented (no photo path in `incoming/` or `incall/`).
  - **Slide to answer (locked):** not implemented (`e5.6-incoming-lockscreen-variant` still has two buttons; no slide
    code under `phone/`).
- **F21 (new, from `e5.1-settings-dark.png`):** the pushed Settings page's tab bar shows only the selected "Recents" cell
  inside the full-width capsule; the other three cells are not drawn (the light frame draws all four). The F16 evidence
  called this a harness artifact ("from the second frame of one test on, only the selected cell ... is drawn"), but it
  is the exact symptom the owner complained about (a bar collapsing to one item) and it was never root-caused. Treat it
  as a product bug until proven otherwise.
- E5.8 starting points: `crates/rovsti_wa_client/src/audio.rs` (960 `i16` samples per binary frame over a WebSocket),
  `android/platform/src/main/kotlin/dev/rovsti/platform/wa/WaCallAudio.kt` (AudioRecord/AudioTrack),
  `android/wa/src/main/kotlin/dev/rovsti/wa/calls/WaCalls.kt`, `services/wa_gateway/src/wa/calls.rs` (20 ms rechunk,
  commit `457ac0f`), `phone/.../incoming/CallSource.kt`.
- Disk: 6.9 GB free at planning time: **Rule 1 at the 7 GB level is required before the first build.**

## Rules for this round

1. **Disk first, every step.** `df -h .` before any build. Below 8 GB: `rm -rf android/*/build` and
   `target/*/incremental` dirs. Below 7 GB: also `cargo clean -p` for the deprecated GPUI crates (`rovsti_phone
   rovsti_messages rovsti_stops rovsti_contacts rovsti_ui rovsti_wa_ui liquid_glass_kit`), `rm -rf
   ~/.gradle/caches/build-cache-1` and Gradle versions under `~/.gradle/caches/` other than the wrapper's. Never build
   below 6 GB. Never `cargo clean` the whole workspace or `rm -rf target` (toolchain.md: release-only checks).
2. **Domain logic in Rust** (number display, call scenes, arbitration, reply choices, notice): Kotlin orders effects; a
   missing decision becomes a `rovsti_ffi` op with fixtures and `scripts/check-ffi-exports.sh`.
3. **Screens** (ARCH 5.2): ViewModel, one `@Immutable UiState`, `onAction(sealed)`, `stateIn(WhileSubscribed(5_000))`,
   effects through a `Channel`; composables never map/sort/filter; only `:design`/`:shell` components.
4. **Call screens follow iOS 26 Phone** (owner direction in epics E5.6): large thin name (`largeTitle` weight Light /
   34-40 sp), small secondary line, label under each control, frosted controls over the caller photo or gradient,
   ringing -> answered morph, controls fade in, end-call shrink. Our own Compose code and icons, no Apple assets.
5. **Motion** (SKILL refs): press = `press/GlassPress.kt` glisse port; toggles `motion.md` spring 0.8/300; slide handle
   follows the finger 1:1 then `Motion.spring(dampingRatio 0.75, stiffness 500)` back (`motion.md` 4 velocity hand-off),
   haptic `HapticEvent.ThresholdTick` once at 80 %; sheets `overlays.md` 5; context menus `overlays.md` 3; reduce
   motion through `MotionGate`; ambient aura ARCH 10 rule 5.
6. **Tests:** JVM ViewModel tests with the real core or `FakeRustCore`; gestures with `mainClock.autoAdvance = false`;
   `ShotTest` light + dark (+ `-fs2` / `-rtl` where named) into `android/phone/screenshots/`. **Every new or changed PNG
   is opened with Read and judged**; what was seen goes in the commit message and the epics Evidence line.
7. **Check per step:** `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`. Rust changes also run `cargo test -p
   rovsti_app_core -p rovsti_ffi -p rovsti_wa_client` (and `-p rovsti_wa_gateway` when the gateway changes) and `bash
   scripts/check-ffi-exports.sh`. Full `CLAUDE.md` gate in Step 8.
8. **Commits:** one per step, `Kotlin UI (E5 Phone, round 13 (E5 R2 part 1)): <step title>`, ending with the session
   attribution lines. Never push to `main`.

## Steps

### Step 1. Recover E5.5 (Favorites and Contacts tabs) from the stash, Phone Turkish

- Disk Rule 1 (7 GB level now).
- Recover only the files the recovery note in epics.md names: `git checkout stash@{0}^3 -- <untracked paths>` for
  `design/.../list/Reorder.kt`, the `phone/.../favorites/*`, `phone/.../contacts/*`, their tests, `testing/.../ComposeActivity.kt`
  and the `e5.5-*` PNGs; for the tracked files take the stash diff of `PhoneApp.kt`, `PhoneContainer.kt`,
  `DetailsPresenter.kt`, phone `values/strings.xml`, `design/build.gradle.kts` and `contacts/.../FavoritesReorder.kt`
  (`git diff stash@{0}^1 stash@{0} -- <path> | git apply -3`) and resolve against steps 7-8 by hand. Do **not** re-apply
  `CLAUDE.md`, `Cargo.toml`, `build-logic`, `scripts/*`, other `build.gradle.kts`, `QrSheetTest.kt`, `ShotTest.kt`,
  `MergedManifest.kt`, `docs/kotlin-ui/toolchain.md` (already in `5f51e2c` or newer). `FavoritesReorder.kt` keeps its
  Convx header; `Reorder.kt` (generic part, moved to `:design`) carries the same Convx header and "Ported from" line.
- Wire the tabs in `PhoneApp.kt`: Favorites and Contacts roots replace their placeholders; tap = call through
  `CallPlacer`, info / row tap on Contacts = `PersonSheet`; "+" on Contacts hands off to the Contacts editor
  (`Handoff`), "+" on Favorites opens `FavoritePicker`.
- **Phone Turkish:** create `phone/src/main/res/values-tr/strings.xml` with a natural Turkish entry for every string in
  `values/strings.xml` (Phone terms: Favoriler, Son Aramalar, Kişiler, Tuş Takımı, Cevapla, Reddet, Sessiz, Bana
  Hatırlat, Mesaj, Hoparlör, Bekletme, Arama Ekle, Not, ...); move the hard-coded Diagnostics labels
  (`DiagnosticsViewModel.kt:91-104`) to string resources resolved in the screen (ViewModel holds `@StringRes` ids).
  Lint `MissingTranslation` must pass under `lintRelease` (verify it is not disabled for `:phone`; if it is, enable it).
  Add `PhoneStringsTest` (every key in `values` exists in `values-tr`, no empty values).
- Tests (must pass): `FavoritesViewModelTest` (order persisted via `PhoneStore`, move earlier/later),
  `FavoritesScreenTest` (reorder drag with `autoAdvance=false`, menu Move Earlier/Later), `PhoneContactsViewModelTest`,
  `PhoneContactsScreenTest`, `E55ShotTest`, the existing `PhoneRoutesTest` / `PushedPagesBackTest`.
- Screenshots re-recorded and OPENED: `e5.5-favorites`, `e5.5-favorites-empty`, `e5.5-favorites-reorder`,
  `e5.5-contacts-tab`, `e5.5-contacts-search` (light + dark), plus a new `e5.5-favorites-tr` (Turkish locale, light):
  last row above the bar, 16 dp gutters, one avatar per row, Turkish labels fit (no clipped tab labels).
- Check `bash scripts/kotlin-ui-check.sh`. epics: E5.5 -> `done` with Evidence; drop the recovery note (say "recovered
  in round-13 step 1"). After the commit, `git branch -D wip-r12-s6` only if `git diff wip-r12-s6 -- android/phone
  android/design/src/main/kotlin/dev/rovsti/design/list` shows nothing missing; leave `stash@{0}` in place.

### Step 2. F21: the tab bar must draw every cell in every frame (Contacts and Phone)

- Reproduce first: `:design` Compose test `TabBarCellsTest` (Robolectric, `ShotTest` harness) renders `RovstiShell`
  with four tabs on a pushed page, captures light, switches to dark (the second frame of the same test, the way
  `E51ShotTest` / `E415ShotTest` do), and asserts on pixels that every cell's label region is non-background (sample
  the label baseline of each of the four cells) in both frames. It must fail at `ca993e6`.
- Root-cause in `design/.../chrome/floatingtabbar/FloatingTabBarTabs.kt` and `FoldingTabGroup.kt` (fold progress or a
  `graphicsLayer` alpha keyed to state that survives the theme change; the Convx originals
  `ref/Convx/app/src/main/kotlin/com/convx/music/ui/component/floatingtabbar/` for comparison; Convx folds the
  non-selected cells only on scroll-minimise). Fix the product code if the state is wrong; only if the cause is proven
  to be in the harness (e.g. `ShotTest` re-using a composition across `setContent`), fix `testing/.../ShotTest.kt` and
  say why in the commit. Either way the test must pass and stay.
- Screenshots re-recorded and OPENED: `e5.1-settings-dark`, `e5.1-diagnostics-dark`, `e4.15-settings-dark`,
  `e4.15-name-format-dark`, every other `-dark` golden whose first frame was light in the same test (grep the ShotTests
  that record both in one test): full four-tab bar with labels in dark.
- Check. epics: F21 Evidence under E5 (cause, fix, test).

### Step 3. Incoming call, iOS 26 layout: F17, F18, F19, F20 (ringing)

- F17: `IncomingViewModel.subtitleOf` (and `titleOf` when the title is the number) renders through
  `SharedOps.render(RenderRequest(raw, NumberForm.Display, home))` (same helper as `PersonSheetViewModel.kt:344`;
  extract a shared `NumberDisplay` helper in `:phone` `data/` rather than a third copy). Same in `InCallViewModel`
  (name line, subtitle, ended summary, post-call pill) and `WaitingCallCard`. Test in `IncomingViewModelTest` /
  `InCallViewModelTest`: `+905551112233` -> `+90 555 111 22 33`.
- F18: new `RovstiIcons.PhoneFill` (filled handset, the keypad call button's glyph; move it to `:design` if it lives in
  `:phone`); Answer uses it, Decline and In-call End use it rotated 135 deg. White glyph on solid green
  (`RovstiTheme.colors.systemGreen`) / red discs, 76 dp, no glass rim.
- F19: avatar on the aura gets a contrasting treatment: the initials disc uses a white 22 % fill with a 0.5 dp white
  40 % rim and white initials (iOS style) instead of the palette colour that can match the aura; photo avatars unchanged.
- F20 ringing: Remind me / Message / Silence become **icon + label, no background**: white glyph 28 dp, footnote label
  under it, whole column is the touch target (min 48x48 dp, `Modifier.minimumInteractiveComponentSize`), pressed state
  `alpha 0.5` with the glisse press timing; TalkBack role Button. A new `:design` `controls/PlainIconAction.kt` (not
  in-screen code) so In-call/Waiting can reuse it.
- iOS 26 layout: status line ("Incoming call" / "WhatsApp audio" / SIM) small at the top; name `largeTitle` Light 36 sp
  centred, up to two lines then shrink-to-fit; number/label line `callout` 78 % white; avatar 96 dp above the name
  (hidden when a photo background is shown, Step 4); secondary row then Decline/Answer row at the iOS positions (both
  discs 76 dp, centres at 1/4 and 3/4 width, 48 dp above the gesture inset).
- Tests: `IncomingScreenTest` additions (secondary actions have no background node: assert via a `testTag` on the
  absence of `GlassCircleButton`; press dims to 0.5 with `autoAdvance=false`; touch target >= 48 dp); `PlainIconActionTest`.
- Screenshots re-recorded and OPENED: `e5.6-incoming`, `e5.6-incoming-fs2`, `e5.6-incoming-lockscreen-variant`,
  `e5.6-waiting-call-card`, `e5.6-waiting-call-card-held`, `e5.6-decline-with-message-sheet`, `e5.6-remind-sheet` (light +
  dark): formatted number, filled handsets, readable avatar, no discs behind the three secondary actions.
- Check. epics: F17-F19 and F20 (ringing) Evidence under E5.6.

### Step 4. In-call frosted controls (F20 in-call) and the caller photo as the call background

- F20 in-call: the 3x2 grid uses a new `rovstiGlass` preset `GlassPreset.CallControl` in `:design` (backdrop blur of the
  call background through the Convx `liquidGlass` path, white tint 20 % in both themes, no outer shadow, 0.5 dp white
  35 % rim, white glyph). Active toggle (mute on, speaker on, hold on) -> solid white disc with a dark (`#1C1C1E`)
  glyph, cross-fade 180 ms with the 0.8/300 spring on scale. Disabled (Add call while unavailable) -> 45 % alpha. End
  stays solid red with `PhoneFill` 135 deg. Collapse button top-left uses the same preset. `GlassRealPathTest` gets a
  case: on API >= 33 with default settings `CallControl` uses the backdrop effect (not the fallback).
- **Photo background** (owner request): new `:design` `media/CallBackdrop.kt`: when the caller has a photo
  (`ContactsRepository` photo URI for the contact id; `WaAvatars` for WhatsApp, Step 7), draw it full-bleed
  (centre-crop, Coil 3 at screen size, `memoryCacheKey` per contact), blurred 36 dp (`RenderEffect` on API >= 31; a
  downsampled pre-blurred bitmap below), with a vertical scrim (top 25 %, bottom 55 % black), cross-fade in over 300 ms
  after load; the ringing screen never waits for it (first frame is the aura). Without a photo the aura stays. The
  avatar circle is hidden when the photo is shown. Name contrast: `CallBackdropTest` checks WCAG 4.5:1 for white on the
  scrimmed mean of a bright fixture photo.
- Both `IncomingScreen` and `InCallScreen` use `CallBackdrop`; the aura-to-photo cross-fade keeps ARCH 10 rule 5.
- Tests: `InCallScreenTest` (active toggle colours; `CallControl` glass node present), `CallBackdropTest` (no photo ->
  aura only; photo -> image node after load with a fake Coil engine; first frame never blocked), `GlassRealPathTest`
  extended.
- Screenshots re-recorded and OPENED: `e5.7-incall-active`, `e5.7-incall-muted-speaker`, `e5.7-incall-hold`,
  `e5.7-incall-keypad`, `e5.7-incall-fs2`, `e5.7-incall-calling`, `e5.7-route-picker-popover`; new `e5.6-incoming-photo`,
  `e5.7-incall-photo` (light + dark): frosted white-tinted discs with visible blur of the background, active toggles
  solid white, photo background readable.
- Check. epics: F20 (in-call) and photo background Evidence.

### Step 5. Slide to answer on the locked screen (owner request)

- `LockScreenGate.kt` exposes `locked: StateFlow<Boolean>` from `KeyguardManager.isKeyguardLocked` (+ screen-off at ring
  start); `IncomingUiState.locked`.
- `:design` `controls/SlideToAnswer.kt`: glass capsule track (`GlassPreset.CallControl`, 76 dp high, 16 dp side
  gutters) with a green 68 dp handle (`PhoneFill`) and the "slide to answer" label that fades with progress; the handle
  follows the finger 1:1 (`draggable` + `Animatable`), past 80 % of the track (or a fling whose projected end with the
  skill's `projectFling` passes 80 %) -> answers and the handle snaps to the end; below -> spring 0.75/500 back to
  start; one `HapticEvent.ThresholdTick` when crossing 80 % (and again only after dropping below 70 %); RTL mirrored;
  accessibility: `customActions` "Answer" + a role/hint, double-tap answers.
- Locked layout: Remind me / Message (plain icon actions from Step 3) above the track; small "Decline" text button under
  it; power/volume key: first press silences (`silenceRinger`), second press declines (`MainActivity.onKeyDown` while
  ringing and locked). Unlocked keeps the two discs.
- Tests: `SlideToAnswerTest` (`autoAdvance=false`: drag to 79 % and release -> springs back, no answer; drag to 81 % ->
  answers once; fling from 60 % with high velocity -> answers; haptic once; RTL drag direction; accessibility action
  answers), `IncomingViewModelTest` (locked state switches layout; second key press declines).
- Screenshots OPENED: `e5.6-locked-slide-rest`, `e5.6-locked-slide-mid` (handle at 50 %), `e5.6-locked-slide-rtl`
  (light + dark); re-record `e5.6-incoming-lockscreen-variant` (now the slide layout).
- Check. epics: slide-to-answer Evidence under E5.6.

### Step 6. E5.8a WhatsApp call audio proven end to end (no UI)

- Trace and document in `docs/kotlin-ui/wa-call-audio.md` every hop with its format: `WaCallAudio.kt` capture
  (AudioRecord `VOICE_COMMUNICATION`, rate, channel, 960-sample frames = 20 ms at 48 kHz: verify against `audio.rs`
  line 1) -> `RustNative.audioPush` -> `rovsti_wa_client::audio::call_audio` (`audio.rs:51`) -> gateway
  (`services/wa_gateway/src/wa/calls.rs`, 20 ms rechunk of `457ac0f`) -> WhatsApp, and back through `audioPull` ->
  AudioTrack (`USAGE_VOICE_COMMUNICATION`), `MODE_IN_COMMUNICATION`, route (earpiece/speaker/BT SCO), audio focus, mic
  permission, foreground service type `microphone|phoneCall`, start order (open audio only after `connected`).
  Fix every mismatch found (sample rate, endianness, frame size, start order, missing FGS type).
- Tests: Rust `rovsti_wa_client` integration test with an in-process fake gateway WebSocket: push a 1 kHz tone, receive
  it on the far side (both directions), check frame size and Goertzel energy at 1 kHz; gateway test that the rechunk
  keeps 20 ms frames; Kotlin `WaCallAudioTest` (Robolectric shadows: AudioRecord/AudioTrack configured with the
  documented rate/format/usage; audio opened only after connected; FGS type in the merged manifest via `PhoneManifestTest`).
- No screenshots. Check + `cargo test -p rovsti_wa_client -p rovsti_wa_gateway`. epics: E5.8 -> in-progress, Evidence
  "audio path proven by <tests>"; the manual device check with gateway logs stays an open item for the owner.

### Step 7. E5.8b WhatsApp calls in the UI

- `CallSource.WhatsApp` on Incoming and In-call: status line "WhatsApp audio" with the 14 dp `SourceGlyph`; controls
  Mute, Speaker, End active; Keypad/Hold/Add call/Note dimmed (45 %) with the sentence from the core on tap (toast).
  WA photo background through `WaAvatars` (Step 4 `CallBackdrop`).
- Arbitration (AD-WA5, AD-WA10) through the core op (add `phone.wa_arbitrate` to `rovsti_ffi` if missing): WA ringing
  during a cellular call shows the waiting card ("End & Answer", "Decline"); cellular during WA likewise.
- Place a WA call from Recents row menu, PersonSheet and the keypad long-press Call menu (enable the R1-disabled items);
  WA hand-off JID -> number op `wa_call::handoff_number` (open item from E5.1).
- FCM wake (firebase flavor) -> Incoming; answer from the notification after a cold start (`headless` path, waits for
  `Rust.init`, rule b); `call_end` events end the screen; missed WA calls appear in Recents (`extraCalls`); snapshot
  re-sync on reconnect. R8: keep rules for any new class reached by name (FCM service already kept), verified with
  `dexdump` on `phone-firebase-release.apk`.
- Tests: `WaCallFlowTest` (fake `WaRepository`: ring -> answer -> audio opened -> end; decline; arbitration both ways;
  cold-start answer waits for init), `IncomingViewModelTest` / `InCallViewModelTest` WA cases (dimmed controls emit the
  sentence), `RecentsViewModelTest` WA place/delete enabled.
- Screenshots OPENED: `e5.8-wa-incoming`, `e5.8-wa-incall-dimmed-controls`, `e5.8-wa-waiting-during-cellular`,
  `e5.8-wa-incoming-photo` (light + dark).
- Check. epics: E5.8 -> `done` (manual audio check listed as owner acceptance item).

### Step 8. E5.7b multi-call, close the round, full gate, APKs

- `rovsti_core::scene` through the bridge (`zone`, `zone_ui` ops; add to `rovsti_ffi` if missing): Add call sheet
  (`addcall`: keypad, Favorites, Contacts without leaving the call; `IosSheet` large detent), swap, merge, split, end
  all, Held card above the active call (frosted `CallControl` card, tap to swap), conference panel (sheet listing
  participants with Split / End per row). Springs (skill `motion.md`): hold-away card shrink 0.86/380, swap cross-over
  0.8/420 with the avatars as shared bounds, merge pull-together 0.75/500; S19-S22 moments.
- Tests: `MultiCallViewModelTest` (scenes from the core map to the right `CallsApi` calls: hold, swap, merge, split, end
  all), `AddCallSheetTest`, `InCallScreenTest` multi-call cases with `autoAdvance=false` (swap animation settles,
  no layout jump).
- Screenshots OPENED: `e5.7-incall-hold-two-calls`, `e5.7-incall-conference`, `e5.7-add-call-sheet` (light + dark).
- Close: update `docs/kotlin-ui/checklist-e5.md` (A-H for every screen touched this round), regenerate
  `docs/kotlin-ui/screenshots/phone.png` (`:phone:contactSheet`) and open it. Full gate: `cargo fmt --all && cargo
  clippy --workspace --all-targets && cargo test --workspace`, `cargo check -p rovsti_android --target
  aarch64-linux-android`, `bash scripts/kotlin-ui-check.sh`. Build the release APKs of `:phone` (plain and firebase)
  and `android/catalog`, run `dexdump` checks for keep rules, and state the paths. epics: E5.7b `done`; next planner =
  E5 R2 part 2 (E5.9-E5.14).

## Verification list for the next planner

Files: `design/.../list/Reorder.kt` (Convx header), `phone/.../favorites/*`, `phone/.../contacts/*`,
`phone/src/main/res/values-tr/strings.xml`, `design/.../controls/PlainIconAction.kt`, `design/.../controls/SlideToAnswer.kt`,
`design/.../media/CallBackdrop.kt`, `docs/kotlin-ui/wa-call-audio.md`. Tests: `FavoritesViewModelTest`,
`FavoritesScreenTest`, `PhoneContactsViewModelTest`, `PhoneContactsScreenTest`, `PhoneStringsTest`, `TabBarCellsTest`,
`PlainIconActionTest`, `CallBackdropTest`, `SlideToAnswerTest`, `WaCallAudioTest`, the `rovsti_wa_client` tone loopback
test, `WaCallFlowTest`, `MultiCallViewModelTest`, `AddCallSheetTest`, `GlassRealPathTest` (`CallControl` case). Goldens:
`e5.5-*`, `e5.5-favorites-tr`, re-recorded `e5.6-*` / `e5.7-*`, `e5.6-incoming-photo`, `e5.7-incall-photo`,
`e5.6-locked-slide-*`, `e5.8-*`, `e5.7-incall-hold-two-calls`, `e5.7-incall-conference`, `e5.7-add-call-sheet`, and the
dark tab-bar goldens re-recorded in Step 2 (open `e5.1-settings-dark`: all four cells drawn).
