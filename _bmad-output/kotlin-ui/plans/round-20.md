# Round 20 plan: X.4, open owner follow-ups

Planned 2026-11-19 at base `fedbd29` (branch `ui-overhaul`). Epic: **X. Cross-app owner requests**, story **X.4**
(the only open X row; X.3 closed in round 19, verified below). E6, E6-WA and E7 run in parallel sessions and are skipped.
**The next planner after X.4 plans E8 R1.**

Owner rules stay in force:
- (a) every pushed page has the glass back button and the full tab bar;
- (b) no `RustNative` call before `Rust.init`;
- (c) only `imeOverlapPadding`;
- (d) R8 keep rules for anything reached by name, checked with `dexdump`;
- GPL-3.0-or-later. Convx ports keep their header and the "Ported from Convx" line; glisse ports carry the glisse MIT
  header. Avatar initials are not touched (owner, X.3).
- Every cargo command takes `--profile checks` or `--release`; never debug, never `cargo clean`, never delete `target/`.

## State found (verified in code and PNGs, not trusted)

- `git status` is clean; `stash@{0}` is the deprecated GPUI WIP and is not touched. HEAD `fedbd29` = X.3 review fixes.
- **X.3 is done in code:** `testing/.../TextContrast.kt`, `catalog/src/test/.../FilledButtonContrastTest.kt`,
  `design/src/test/.../media/ViewerScrimTest.kt`, `phone/src/test/.../incall/CallPhotoContrastTest.kt` exist, and
  `docs/kotlin-ui/checklist-e4.md:256` has the X.3 checklist record.
- **Owner fix (a) holds:** `contacts/screenshots/e4.15-name-format-light.png` (opened) shows the glass back button top left
  and the full four-item tab bar (Contacts, Favorites, Groups, Settings).
- **Disk: 808 MB free (98 %). The 6 GB rule is broken before any build.** Sizes: `android/*/build` about 3.3 GB
  (phone 716M, messages 684M, catalog 622M, core-bridge 381M, contacts 329M, stops 303M, ...); `target/checks` 1.4G,
  `target/aarch64-linux-android` 909M, `target/release` 144M; `~/.gradle/caches` 2.2G. The Convx reference checkout in
  the scratchpad is gone (only `ref/glisse` remains); this round needs no Convx source. If a step does, re-clone with
  `--depth 1` only after the disk has room.
- **X.4.1a, menu over a sheet dims (confirmed).** `contacts/screenshots/x2-share-still-menu-light.png` (opened): with the
  "..." menu open, the whole Card Studio sheet turns mid grey and the toggle thumb with it. Cause:
  `ButtonMenuEntry` (`design/.../overlay/ButtonMenu.kt:32`) is an `IosContextMenuEntry`, whose host box always draws
  `Color.Black` at `scrimTarget` (`ios/component/IosContextMenu.kt:250-288`, 0.14 light / 0.28 dark), also over a sheet.
  `OverlayController.topBelow(entry)` (`overlay/OverlayHost.kt:129`) already tells whether a modal sheet is open below.
- **X.4.1b, toggle thumb in the sheet.** The sheet uses the same `IosToggle` (`CardStudioSheet.kt:161`, label variant) as
  the catalog; the thumb is 40 x 24 dp on a 64 x 28 dp track in both (`IosToggle.kt:80-81,330`), and
  `catalog/screenshots/e1.17-switch-off-on-light.png` (opened) shows the same capsule. In the menu shot the thumb is grey
  because of the scrim above, which reads as a "wide grey oval". Not proven yet on the real backdrop path (the thumb
  samples `thumbBackdrop`, which inside an overlay can be the root backdrop of the dimmed page): Step 2 measures it.
- **X.4.2, glass "Delete" (not fixed by X.3).** `catalog/screenshots/e1.6-buttons-dark.png` (opened): in "Over a backdrop",
  `DestructiveButton("Delete")` is salmon red (#FF8A84-like) on blue-violet glass, clearly under 3:1. `DestructiveButton`
  (`controls/Buttons.kt:148-163`) is a Glass `CapsuleButton` with `tint = colors.destructive` and nothing behind the label
  but the refracted stripes. The same label over flat chrome ("Delete chat") reads fine.
- **X.4.3a, short and emergency codes.** `rosty_core::caller_id::NumberBook::lookup` matches short codes exactly
  (`caller_id.rs:192`, test `short_codes_and_service_numbers_match_exactly` asserts a contact saved as "112" names 112).
  Phone names numbers only through `platform/.../identity/CallerIds.kt` (`DeviceContactsSource` over the Rust book,
  `PhoneLookupSource` for cold processes, the people/WhatsApp source). The emergency rule already exists:
  `rosty_core::dial::is_emergency` (+ `ALWAYS_EMERGENCY` 112/911, the platform `EmergencyList`), op `phone.is_emergency`
  (`crates/rosty_ffi/src/ops/phone/dial.rs`), `PhoneOps.isEmergency`, `CallPlacer.isEmergency`. Recents subtitles are
  built in Rust (`rosty_core/src/recents.rs:180-196`, words "Missed", "Incoming", ...).
- **X.4.3b, Contacts card "Ara" (Call capsule).** `ContactCardViewModel.call()` (`card/ContactCardViewModel.kt:337-360`)
  sends `ContactCardEffect.Dial` for `CallTarget.Direct` (one number or a default) and `PickNumber` for `Choose`;
  `ContactCardEntry.kt:361` starts `ACTION_DIAL`, so a tap only opens a dialer, it does not call. Contacts has no
  `CALL_PHONE` permission (only Phone's manifest has it) and no call-source sheet. WhatsApp calls go to Phone through
  `platform/.../system/Handoff.kt` (`ACTION_WA_CALL`, signature-checked, `canStart`).
- X.4.3c (WhatsApp preview default "name and preview") needs no change: verify the default once in Step 7.

## Steps

### Step 1: disk, then "a menu over a sheet does not dim"

1. Disk to at least 6 GB free, recorded before and after in the step commit message:
   `rm -rf android/*/build`; `rm -rf target/release target/aarch64-linux-android`; if still short, `target/checks`;
   prune `~/.gradle/caches/transforms-*` and `build-cache-*` older than 7 days; empty the scratchpad except `ref/glisse`.
2. `IosContextMenuEntry` gets a `dimsOverSheet: Boolean = true` constructor parameter ("Changes for Rosty" note in the
   ported header). In `Content`, read `controller.topBelow(this)` (expose it to the ported file as an `internal`
   accessor if needed): when it is not `NaN` and `dimsOverSheet` is false, `scrimTarget = 0f`. The scrim box still
   catches the outside tap (dismiss), only its drawing is skipped. `ButtonMenuEntry` passes `dimsOverSheet = false`.
   Long-press context menus keep their scrim everywhere (iOS lifts the element and dims; unchanged).
3. Tests (design, Robolectric, `mainClock.autoAdvance = false`): `MenuOverSheetScrimTest`:
   - a `ButtonMenuEntry` over an open `SheetEntry`: after the open spring settles, a pixel of the sheet surface beside the
     menu equals the same pixel with no menu (tolerance 2/255), light and dark;
   - the same menu over the plain page still dims (0.14 light);
   - tapping the sheet outside the menu closes the menu and leaves the sheet open.
4. Goldens: re-record `contacts` `x2-share-still-menu-{light,dark,tr-light,tr-dark}`. Open at least
   `x2-share-still-menu-light` and `-dark`: the sheet keeps its normal light (or dark) surface, the thumb is white, the
   menu is the only lifted thing; the page behind the sheet keeps the sheet's own 0.32 dim. Catalog menu goldens must not
   change (verify mode).

Acceptance: `SKIP_RUST=1 bash scripts/kotlin-ui-check.sh` green, 6 GB free.

### Step 2: the Card Studio toggle thumb, measured on the real path

1. `contacts` test `CardStudioToggleShapeTest`: compose `CardStudioContent` inside the sheet with the real root backdrop
   (the same rig as the round-3 real-path test, API 33+, default settings) and assert from node bounds and
   `captureToImage`:
   - the thumb box is 40 x 24 dp (± 1 dp) inside a 64 x 28 dp track, centred vertically;
   - at rest the thumb centre pixel is white (each channel ≥ 250) in light and dark, i.e. the `onDrawSurface` white
     covers whatever the backdrop sampled;
   - the same numbers as the catalog `ControlsPage` toggle (one shared helper in `:testing`, `toggleThumbProbe`).
2. If the probe shows a grey or stretched thumb inside the sheet (backdrop of the dimmed page bleeding through, or the
   layer scale left at a held value): pass the sheet's own surface as `backdrop` (track-only refraction, the documented
   `backdrop = null` behaviour of `IosToggle`) from overlay content, decided in `IosToggle` by
   `LocalOverlayEntry`/an `insideOverlay` local, not per caller; reset `damped` scale on `checked` changes. If the probe
   passes, record in the X.4 evidence that the shape equals `IosToggle` everywhere and that the grey came from the scrim
   fixed in Step 1.
3. Goldens: new `contacts` `x4-studio-toggle-{light,dark}` (sheet at medium detent, no menu, Animated on). Open both and
   compare with `catalog/e1.17-switch-off-on-light`: same capsule, white thumb, green track, label baseline aligned.

Acceptance: checks green; the two PNGs opened and judged.

### Step 3: destructive labels on glass reach 3:1

1. `CapsuleButton` / `GlassIconButton` with a destructive tint on glass (`DestructiveButton`, and any `IosButtonRole.
   Destructive` glass use; there are 11 production call sites, list them in the commit) get a legibility plate, the way
   iOS puts red text on a vibrant material: the glass `onDrawSurface` adds a theme wash (light: white 0.55, dark: black
   0.45) and the label weight becomes semibold. Values are tuned until the check below passes; keep the refraction
   visible at the rim. Glass capsules without a destructive tint are unchanged.
2. If the wash alone does not reach 3:1 over the worst stripe, use `readableOn`-style choice of the red: light keeps
   `destructive` (#C8202A), dark uses `destructive` only when it passes, else a deeper red from `IosColors` (system red
   dark #FF453A at the plate) - decided by a pure function `glassDestructiveInk(plate: Color)` with a JVM test.
3. Tests: `catalog` `GlassDestructiveContrastTest`: `ButtonsPage` "Over a backdrop" captured in light and dark;
   `assertReadable(label bounds, 3.0)` on "Delete" and on the red trash glyph of the glass icon row
   (`e1.6-buttons` top row). `ContrastHelpersTest` gets the pure function cases.
4. Goldens: re-record `catalog` `e1.6-buttons-*`, `e1.6-buttons-360x640-*`, and any app golden that shows a glass
   destructive control (run verify first, re-record only what fails). Open `e1.6-buttons-dark` and `-light`: "Delete"
   clearly red on a soft plate, stripes still visible around it, "Delete chat" unchanged.

Acceptance: `SKIP_RUST=1` gate green; the PNGs opened.

### Step 4: Rust rule for short and emergency codes (pure, tested)

1. `rosty_core::caller_id`:
   - `pub enum NumberKind { Normal, ShortCode, Emergency }` and `pub fn number_kind(raw: &str, list: &EmergencyList) ->
     NumberKind`: `Emergency` when `dial::is_emergency(raw, list)`; `ShortCode` when the digits (separators ignored, no
     `+`, `*`, `#`) are 3 to 5 long; else `Normal`. `444 0 123`-style 7-digit service numbers stay `Normal`.
   - `NumberBook::lookup` returns `None` for a 3-5 digit `Key::Short` (owner: no contact-name lookup); `line_key` and
     `same_line` keep their rules (duplicates and imports still group short codes).
   - Update `short_codes_and_service_numbers_match_exactly` (112 now `None`, "4440123" still `Some(2)`) and add
     `short_codes_never_name_a_contact`, `number_kind_table` (112, 155, 156, 110, 177, 911, `1 1 2`, 4-5 digit codes,
     `*123#`, `+90 112`, a 7-digit service number, a mobile number).
2. `rosty_core::recents`: the row input gets `kind: NumberKind` (default `Normal`, serde-default so old fixtures load);
   an unnamed `Emergency` row's detail is "Emergency" (subtitle e.g. "Outgoing · Emergency"), its aria says
   "emergency number". Localise it through the same path the existing words ("Missed", "Incoming") take to Turkish
   (check it first; if the words are English-only in Rust, the row carries an `emergency: bool` instead and Kotlin
   words it from `strings.xml`: "Emergency" / "Acil durum").
3. `rosty_ffi`: op `caller_id.kind` `{number, list}` -> `{kind}` with fixtures in `fixtures/INDEX`, `OPS.md` row and
   prose; `:core-bridge` `PhoneOps.numberKind` (through `NativeRustCore.call`, gated on `Rust.init`, rule b), model
   `@Serializable` (no keep rule needed; confirm with `dexdump` in Step 7). `ContractFixturesTest` covers the fixtures.
4. Checks: `cargo fmt --all`, clippy and test with `--profile checks` on the gate package set, `cargo check --release -p
   rosty_android --target aarch64-linux-android`, then the full `bash scripts/kotlin-ui-check.sh`.

Acceptance: all green; no golden changes yet.

### Step 5: Phone shows short and emergency codes as dialled

1. `CallerIds.resolve` asks `PhoneOps.numberKind` once per new key (cached with the answer) and never sends a
   `ShortCode`/`Emergency` number to any source (covers `PhoneLookupSource` and the people/WhatsApp source too).
   `CallerIds.kindOf(number)` exposes the cached kind for screens.
2. Surfaces: Recents rows (Step 4 subtitle), call details header (number as title, "Emergency" subtitle), keypad
   suggestions (no contact suggestion for a short code), incoming and in-call screens (caption under the number),
   missed-call and ongoing notifications (title = number, text "Emergency" for emergency numbers). Short codes show just
   the number everywhere. Strings in `values` and `values-tr`, lint `MissingTranslation` green.
3. Tests: `CallerIdsShortCodeTest` (platform: a contact saved as "112" and "155" is never returned; a 7-digit service
   number still is), `RecentsEmergencyRowTest`, `IncomingEmergencyCaptionTest`, `MissedCallNotificationEmergencyTest`.
4. Goldens: `phone` `x4-emergency-recents-{light,dark}`, `x4-emergency-details-{light,dark}`,
   `x4-emergency-incall-{light,dark}`, `x4-short-code-recents-light`. Open each: 112 shows as "112" with "Emergency"
   under it, no contact name or photo even though the fixture has a contact saved as 112; the subtitle fits one line at
   fs2 (add `x4-emergency-recents-fs2-light`, open it).

Acceptance: checks green (`SKIP_RUST=1` is enough if no Rust changed in this step).

### Step 6: Contacts card "Ara": tap calls, long press or chevron opens the call-source sheet

1. Pure rule (Kotlin, `card/CallPlan.kt`, JVM-tested): `callPlan(numbers, default, waReachable) -> CallPlan`:
   - `Cellular(number)` when there is a default number or exactly one number;
   - `Sheet` when there are several numbers and no default (the user must choose anyway);
   - `WhatsApp(jid)` only when the contact has no number and WhatsApp is reachable (`Handoff.canStart(ACTION_WA_CALL)`
     and a linked jid);
   - `Nothing` otherwise (capsule disabled, as today for `CallTarget.Nothing`).
2. Placing a cellular call at once: Contacts manifest declares `android.permission.CALL_PHONE` (rule: declare every
   permission a call needs). The entry starts `Intent.ACTION_CALL` when the permission is granted; the first tap
   without it asks through `rememberLauncherForActivityResult(RequestPermission)` and on grant places the call; denied
   (or `SecurityException`/`ActivityNotFoundException`) falls back to today's `ACTION_DIAL` plus a toast. All of it in a
   small `CallStarter` in `:platform` (desktop-free, testable with a fake starter).
3. The call-source sheet (`card/CallSourceSheet.kt`, an `ActionSheet` from `:design`): one row per number ("mobile
   0532 ..."), then "WhatsApp voice call" and "WhatsApp video call" when reachable, then Cancel. Opened by a long press on
   the Call capsule (hold rule from `press/`, one haptic) and by a chevron: when the sheet would have more than one
   choice, the capsule label becomes "Ara ⌄"; the trailing chevron sits in its own hit zone (at least 32 x 48 dp, inside
   the capsule) that opens the sheet, the rest of the capsule calls. Accessibility: a custom action "Call options".
4. Tests: `CallPlanTest` (JVM), `ContactCardCallTest` (tap with permission -> `ACTION_CALL` to the default number; tap
   without -> permission request, then call; denied -> `ACTION_DIAL`; WhatsApp-only contact -> handoff intent; long
   press -> sheet; chevron tap -> sheet; sheet row -> call of that number), lint and R8: no class reached by name is added.
5. Goldens: `contacts` `x4-call-capsule-chevron-{light,dark}`, `x4-call-source-sheet-{light,dark,tr-light}`. Open each:
   the chevron sits beside the label without wrapping the four capsules (also at fs2: `x4-call-capsule-chevron-fs2-light`),
   the sheet rows read, Turkish says "Ara", "WhatsApp sesli arama", "WhatsApp görüntülü arama".

Acceptance: checks green; PNGs opened.

### Step 7: close X.4

1. Verify X.4.3c: the WhatsApp notification preview default is "name and preview" (`messages`/`wa` settings default, a
   one-line test if none exists). No Messages source edit (E6 territory); if a change were needed, note it in
   `plans/e6-status.md` instead.
2. Checklists: "X.4 checklist record" in `docs/kotlin-ui/checklist-e4.md` (sheet menu, toggle, call sheet) and
   `checklist-e5.md` (short and emergency codes), with the SKILL `references/checklist.md` sections that apply.
3. Full gate: `cargo fmt --all --check`, clippy and test `--profile checks` (gate package set), `cargo check --release -p
   rosty_android --target aarch64-linux-android`, `bash scripts/kotlin-ui-check.sh` EXIT 0. Release APKs of Contacts and
   Phone; `dexdump` shows `Ldev/rosty/core/RustNative;` and the new op models kept; record sizes. Keep 6 GB free (remove
   app build dirs after).
4. `epics.md`: X.4 row -> done (round 20) with an Evidence block (commits, tests, PNGs opened and what was seen, gate,
   APK sizes, disk). Next planner: **E8 R1**.

## Screenshot PNGs this round must open

`contacts/x2-share-still-menu-{light,dark}`, `contacts/x4-studio-toggle-{light,dark}`, `catalog/e1.6-buttons-{light,dark}`,
`phone/x4-emergency-recents-{light,dark,fs2-light}`, `phone/x4-emergency-details-light`, `phone/x4-emergency-incall-dark`,
`phone/x4-short-code-recents-light`, `contacts/x4-call-capsule-chevron-{light,dark,fs2-light}`,
`contacts/x4-call-source-sheet-{light,dark,tr-light}`.
