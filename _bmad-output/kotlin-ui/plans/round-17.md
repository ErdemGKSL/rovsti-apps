# Round 17 plan: finish X.2 (share cards), land the carry-over, prove the real path

Planned 2026-11-15 at base `3a03c05` (branch `ui-overhaul`). Epic: **X. Cross-app owner requests**, story **X.2**.

## Why X.2 again

`be4bf61` set X.2 to `done`, but X.2 was not finished. The round-16 root-cause note (`0a79716`, round-16.md Step 8) says
the `cards.*` FFI op never landed and every device render answered `e04`. The fix arrived only as the unreviewed
commit **`3a03c05 WIP: carry-over`** (a Haiku agent). It holds 45 files:
- `rosty_app_core::cards::{card,fit,model,motion,tests}`;
- `rosty_ffi/src/ops/cards.rs` with fixtures, `INDEX` and `OPS.md`;
- `:core-bridge` `NativeCardsOps`, `CardsContracts` and `testRust/.../CardsOpsNativeTest.kt`;
- Contacts `CardPhotos`, `CardsWiringGuardTest` and `CardStudioState` (`defaultTemplate`, `cardDate`).

Another agent was still editing on top of it while this plan was written. At 21:42 these files were uncommitted:
- modified: `card.rs` (rustfmt), `cards/tests.rs` (+138), `ops/cards.rs`, Contacts `CardsTestSupport.kt` (the fake
  copies the Rust goldens);
- untracked: `CardTextsTest.kt` and `crates/rosty_app_core/tests/cards/{birthday-dawn-en,birthday-dawn-tr,contact-dawn-en}.png`.

`epics.md` now marks X.2 **in-progress**. E6, E6-WA and E7 run in parallel sessions and are skipped. E6 and E7 are
`done` in the tables, so after X.2 the next planner is **E8 R1**.

Owner rules stay in force:
- (a) every pushed page has a glass back button and the full tab bar (`PushedPagesBackTest`);
- (b) no `RustNative` call before `Rust.init`;
- (c) only `imeOverlapPadding`;
- (d) R8 keep rules, checked with `dexdump`;
- DoD item 6: tr strings, icons, no crash on platform calls;
- GPL-3.0-or-later. Convx ports keep their header plus the "Ported from Convx" line; glisse ports carry the glisse
  MIT header.

## State found (verified in the tree, not trusted from claims)

- `crates/rosty_ffi/src/ops/cards.rs` exists and is registered. `ContactsContainer.createCards()` returns
  `NativeCardsOps(rust, CardPhotos(context)::file)` (line 263); `UnavailableCardsOps` is gone from production.
- `kotlin-ui-check.sh` runs `:core-bridge:testRustBridge` (line 66), so `CardsOpsNativeTest` runs in the gate.
- The `cards/fit.rs` text fit is in the tree (measured with `takumi::measure`, then shrink, then a measured "…").
- **No glass optics yet.** `card.rs` has `backdrop-filter: blur() saturate(1.6)` only. There is no `refraction.rs`, no
  `feDisplacementMap` and no specular glint, so the owner's "light bending as the GIFs' signature" is missing.
  Takumi 2.18.2 implements the filter primitives (`takumi-core-0.34.0/src/svg/layout/filter.rs`, `svg_drawing.rs`).
- No emulator or system image on this machine (`emulator` and `adb` are absent). Device proof is a host JNI test plus a
  release APK check.
- **Disk: 3.1 GB free (below the 6 GB rule).** `target/checks` 5.5 GB, `target/aarch64-linux-android` 0.9 GB,
  `android/*/build` about 350 MB.
- Stashes:
  - `stash@{0}` (r16-s4, the Takumi Cargo lines): superseded by HEAD.
  - `stash@{1}` and `stash@{2}` (r16-s3 and r16-s2): use the pre-rename `rovsti` paths.
  - `stash@{2}` also touches shell `AboutPage`, `CommonSettingsGroup`, `SegmentedControl`, `AndroidCommon`, and the
    Phone and Contacts settings entries.
  - `stash@{3}`: the deprecated GPUI WIP. Never pop it.

## Screenshots opened in this planning round

- `crates/rosty_app_core/tests/cards/birthday-dawn-tr.png` (new, untracked) is **good**. "Doğum günün / kutlu olsun,
  Ayşe / Yılmaz!" wraps to 3 lines inside the panel, with even side padding. The date pill reads "3 Ekim". The age
  line is fine. The panel rim and shadow read as glass.
  - Nit: the first panel corner of the yellow blob shows a hard tint step at the top-left of the panel. Check whether
    the blur is clipped to the panel radius. If it is not, the edge needs `overflow: hidden` on the blur layer.
- `crates/rosty_app_core/tests/cards/contact-dawn-en.png` (new) is **good**: avatar, name, number and the QR in a white
  quiet box, all centred. Nit: the block sits about 40 px above centre, so the bottom of the panel looks empty.
  Centre the column vertically, or give the QR more size (room allows about 260 px).
- `android/contacts/screenshots/x2-share-still-menu-tr-light.png` (Kotlin golden from `7e08ce8`) is **wrong**:
  - **R17-F1:** the preview is the old Kotlin stand-in, not the Rust card. The headline is cut ("m günün kutlu olsun,
    Ayşe Yı") and the date is English ("October 3"). Re-record with the Rust goldens.
  - **R17-F2:** with the "…" menu open, the whole sheet turns flat grey, including the segmented control, the
    preview and the buttons. Convx's overlay menu uses no heavy dim on a sheet (skill `references/overlays.md`).
    Use the ported `IosMenu`/`OverlayMenu` scrim as Convx does.
  - **R17-F3:** the menu covers the "Hareketli" toggle on its row (the switch is hidden) and clips the green and purple
    swatches. Anchor it upward, right-aligned to "…", above the button row. It may overlap the swatches, but it must
    not cut the toggle in half. Better: put "Share as still image" in the menu together with "Save as GIF/WebP", and
    check that its height fits.
- `android/contacts/screenshots/x2-card-preview-gif-dark.png` is **wrong**:
  - same R17-F1 stand-in: "Happy birthday, Ayşe Yılmaz!" crosses both panel edges;
  - **R17-F4:** the Animated `IosToggle` thumb is a long white pill filling almost the whole track. That is either a
    mid-press stretch frozen by the clock, or a wrong resting thumb width. A resting iOS switch has a round thumb.
    Find which and fix it (catalog switch goldens give the reference);
  - the progress ring overlapping the preview's top-right corner is acceptable.

## Steps

### Step 1. Disk, take over the carry-over, stashes

- Make sure no other agent is still writing. `ps aux | grep -E 'cargo|gradle'` must show nothing from another session,
  and `git status` must be stable over a minute. Then free disk:
  - delete `target/debug` if it exists;
  - then `target/checks` (5.5 GB; allowed by CLAUDE.md when disk is low);
  - get to 6 GB or more free (`df -h .`), with no `cargo clean` and no deleting `target/`.
- Review `3a03c05` and the uncommitted files listed above as a whole:
  - no Convx or glisse code without its header;
  - `motion.rs` is our own spring (no glisse code yet). If any glisse formula is in it, add the glisse header;
  - errors are codes (F30), not English text;
  - every Kotlin call goes through the `Rust.init`-gated entry (owner fix (b));
  - no `UnavailableCardsOps` in production.
  - Keep the good parts; fix what is wrong.
- Run `cargo fmt --all`, then clippy and tests for `rosty_app_core` and `rosty_ffi` with `--profile checks`, then
  `cargo check --release -p rosty_android --target aarch64-linux-android`.
- Commit as "X.2: cards renderer, cards.* ops and the native bridge (carry-over landed)". Include the three Rust goldens
  and `CardTextsTest.kt`. **Do not rewrite `3a03c05`**; add a commit on top.
- Stashes: compare `stash@{0..2}` file by file with HEAD (`git diff stash@{n}^ stash@{n}`, mapping `rovsti` to
  `rosty`).
  - Recover any change HEAD lacks (likely candidates: `stash@{2}` shell About/Licences rows, the
    `SegmentedControl` tweak, the `AndroidCommon` change) only if it is still correct and gated.
  - Record each decision in this file under "Stashes".
  - Then `git stash drop` the three r16 stashes, highest index first. Never touch the GPUI stash.
- Acceptance: Rust checks green, disk at 6 GB or more, a commit, and the stash decision recorded.

### Step 2. Real native path, keep rules, error logging

- `rosty_ffi`:
  - `cards.templates`, `cards.render` (still and animated) and `cards.cancel` have fixtures. They cover
    `number_name`, `out_invalid`, `out_unwritable` and `text_missing`, are listed in `INDEX` and documented in
    `OPS.md`, and `tests/inventory.rs` counts them.
  - `millis` is masked in the answers.
- `:core-bridge`:
  - The `ContractFixturesTest` cards rows pass.
  - `CardsOpsNativeTest` (testRust) renders through the real host `.so` with no fakes:
    - a still birthday card;
    - a contact card for a contact named "+90 532 000 11 22" with no birthday (the owner's device case);
    - an animated card with a progress poll and a cancel;
    - it asserts that the PNG or GIF decodes and has the size the answer gives.
- Contacts:
  - `CardsWiringGuardTest` fails if any production container or `createCards` override returns a fake or unavailable
    implementation.
  - `CardError` text is logged through `RostyLog`, masked (from `50b2b2a`).
- R8 (owner fix (d)):
  - If `CardsContracts` models are `@Serializable`, the existing serialization rules cover them; make sure.
  - If anything is reached by name, add an explicit `-keep`.
  - Build `:contacts:assembleRelease`, then run `dexdump -d` on `classes*.dex` of
    `android/contacts/build/outputs/apk/release/contacts-release-unsigned.apk` and grep for `NativeCardsOps`, the
    contract model serializers and `RustNative`.
  - Record the grep output in this file.
- Gate: `bash scripts/kotlin-ui-check.sh`.

### Step 3. Rust card layout polish and the golden set

- Set the golden set (`card_goldens_match`, `UPDATE_CARD_GOLDENS=1`):
  - Birthday Dawn en/tr, Birthday Ink tr;
  - Contact Dawn en, Contact Lagoon en;
  - Number Meadow tr;
  - Birthday for the long 40-character Turkish name;
  - Contact for the number-named contact;
  - 8 PNGs in total, committed under `crates/rosty_app_core/tests/cards/`.
  - Each one passes `assert_text_inside` (no ink outside the panel's inner box).
- Fixes from the review above:
  - the blur layer is clipped to the panel radius (no hard tint step at the corner);
  - the Contact column is centred vertically, and the QR gets the room left over (at least 240 px at 720 wide) while
    still decoding through `contact_scan::decode_luma`.
- Kotlin dates:
  - `cardDate` formats in the app locale: "3 Ekim" in tr, "October 3" in en, with no year unless the year is known and
    the template is Contact.
  - `CardTextsTest` covers en and tr with `@Config(qualifiers = "tr")`.
- Card Studio:
  - It opens on `Contact` when there is no birthday (`defaultTemplate`).
  - The Birthday tab still works without a birthday (no date pill, no age line, never an error).
  - `CardStudioViewModelTest` covers both cases.
- Proof: open every golden with Read and write what was seen into this file.

#### Step 3 result (implemented)

- `card.rs`: the blur and tint moved to a `.glass` sibling layer under the panel, with the panel's radius, overflow
  hidden and the same transform and opacity. (A child glass layer inside the panel was tried first and blurred the
  panel's own text, so it is a sibling.) The date pill now hugs its text (`width: auto`). The Contact QR room is 264 px
  at 720 wide (the box is about 285 px); it still decodes through `contact_scan::decode_luma`. The Contact column was
  already centred (content 178-724 in a panel 72-828); the empty look came from the small QR.
- Kotlin: `CardStudioSubject.templates` now offers all three templates (a contact without a birthday still has a
  Birthday tab: a greeting by name, no age line, no date pill); the sheet opens on Contact for those contacts.
  `CardTextsTest` covers en/tr dates, `cardDate` and the Birthday card without a date; `CardStudioViewModelTest` covers
  the Contact default and the Birthday tab without a birthday.
- Goldens opened with Read (`crates/rosty_app_core/tests/cards/`, 720x900), all pass `assert_text_inside`:
  - `birthday-dawn-en`: avatar "AY", "Happy birthday, / Ayse Yilmaz!" on 2 lines, "Turning 36", a compact "October 3"
    pill; even margins, rounded panel corner clean, no tint step.
  - `birthday-dawn-tr`: 3-line headline "Dogum gunun / kutlu olsun, Ayse / Yilmaz!", "36 yasina giriyorsun", compact
    "3 Ekim" pill (was full-width before).
  - `birthday-ink-tr`: same on dark glass, light ink readable on the dark tint, pill subtle.
  - `contact-dawn-en`: avatar, name, number, QR about 285 px in its white box, vertically centred.
  - `contact-lagoon-en`: two numbers stacked, QR below, nothing clipped, centred.
  - `number-meadow-tr`: "Yeni numaram", the number in a large size, the name; the column centred; no QR.
  - `birthday-long-name-tr` (40 chars): 3-line headline shrunk to fit, age and date below, inside the panel.
  - `contact-number-name`: number as title and number line (the number appears twice, accepted), the initials show "9"
    because "+" is not alphanumeric; QR fully visible.

### Step 4. Glass optics for the one-shot animation (glisse port)

- New file `crates/rosty_app_core/src/cards/refraction.rs`. Header: `Portions ported from glisse
  (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang`. It holds pure functions:
  - the edge-band displacement map and the highlight map (glisse `internal/refraction.ts`), and the arc profile
    `slope` with `scale = 0.5 / mean` (glisse `internal/lens.ts`);
  - output as PNG bytes for an SVG `feImage` data URI.
- `card.rs` and `motion.rs`, birthday animation (2.6 s, 15 fps, 39 frames):
  - **Hero:** the panel drops with `sheetSettle` (0.86 / 0.38 s), then a jelly squash with `glassPressUp`
    (0.373 / 0.25 s, area kept). Its displacement scale follows the squash.
  - **Lens:** one lens sweeps across the name once, from 0.5 s to 1.4 s with `menuMorphOpen` (0.939 / 0.6 s). It is an
    `feDisplacementMap` on a clipped copy of the background, as glisse does across and down. Use
    `backdrop-filter: url(#f)` instead if Takumi applies it; test which one and write the result in the module doc.
  - **Glint:** an `feSpecularLighting` glint travels the rim once (0.9-1.6 s) and fades.
  - **Fringe:** a chromatic fringe of at most 1.5 px at the peak.
  - Confetti stays.
  - The last 0.6 s and more is the still. **The last frame equals the static render** (the existing test).
- Contact animation: the panel settles with `morph` (1.0 / 0.95 s) and the QR fades in with `appear`. It has no lens.
- Tests:
  - map symmetry and zero displacement at the centre;
  - the displacement magnitude is non-zero in frames 8-20 and zero in the still frames;
  - play-once bytes (no NETSCAPE block);
  - the GIF is under 3 MB at 720 px;
  - determinism.
- Notices: confirm that glisse's full MIT text is in `android/THIRD_PARTY_NOTICES.md`, and add `cards/refraction.rs` to
  its file list.
- Proof: write frames 0, 6, 12, 18, 24 and the last frame to `target/checks/cards/frames/`. Open each one with Read and
  judge:
  - the drop and the squash;
  - the lens bending the name and leaving it sharp;
  - the glint on the rim;
  - the still frame clean.
  - Tune, then record the final constants here.

### Step 5. Card Studio sheet fixes and the x2 goldens

- R17-F1: the x2 goldens come from the Rust goldens. Make sure every x2 spec matches a golden spec, so the placeholder
  path is never used in a screenshot. Add an assert in `X2ShotTest` that the fake returned a golden.
- R17-F2:
  - The "…" menu uses the ported Convx `IosMenu`/`OverlayMenu` with its own scrim behaviour, not a full-sheet grey
    dim. It is shown in the overlay host above the sheet, so it samples the backdrop like the other glass menus.
  - Its items:
    1. "Share as still image";
    2. "Save as still image" (only when Animated is on);
    3. "Copy image" (if the clipboard image path already exists in `:platform`; skip otherwise).
- R17-F3: the menu opens upward and right-aligned to "…", and never hides half of a control row. Add a Compose test
  that the menu bounds stay inside the window and above the button row.
- R17-F4: find why `IosToggle` shows a stretched thumb in `x2-card-preview-gif-dark`. Either the shot captures a press
  (then set `mainClock` to settle before capture) or the resting width is wrong (then fix it in `:design` and re-record
  the catalog toggle goldens, which need opening).
- Preview a11y: `contentDescription` is "Birthday card for <name>" or "Contact card for <name>" (en/tr).
  `CardStudioSheetTest` checks it.
- Re-record all `x2-*` goldens in `android/contacts/screenshots/`:
  - light, dark, tr and fs2;
  - `x2-card-preview-birthday`, `-contact`, `-gif`, `x2-card-chip-on-card`;
  - `x2-share-still-menu(-tr)`, `x2-share-animated(-fs2)`.
- Open every one with Read. Check:
  - no text crosses the card panel;
  - the swatches stay on one row at fs2;
  - the toggle is round at rest;
  - the menu leaves the toggle readable;
  - the buttons sit above the gesture bar.
- Gate: `bash scripts/kotlin-ui-check.sh`.

### Step 6. Budgets, sizes, cancellation

- Measure with `RUN_BUDGETS=1` (checks profile, desktop proxy). Assert at 2x the budget:
  - static under 300 ms;
  - the birthday GIF under 3 s at 720 px and 39 frames, with the optics on.
- If a budget is missed:
  - first reuse the unchanging background layer across frames;
  - then cut the lens to the frames where it moves;
  - then go down to 540 px.
- Cancellation:
  - a cancelled animated job deletes its partial file;
  - closing the sheet mid-render leaves no late image (`CardStudioViewModelTest` with a gated fake);
  - rotation keeps the job.
- Record in "Measurements":
  - `librosty_ffi.so` size (arm64, release) against 12,519,024 bytes;
  - the Contacts release APK size;
  - static and GIF times;
  - GIF and WebP sizes.

#### Step 6 result (implemented)

- `cards/tests.rs`: `budget_static_render_under_300_ms`, `budget_birthday_gif_under_3_s` (720 px, 39 moving frames, GIF under 3 MB,
  prints the WebP size), `a_cancelled_job_leaves_no_partial_file` (cancel after 0, 5, 31, 32, 33 asks: no `.gif`, no `.part`).
- `CardStudioViewModelTest.closingMidRenderShowsNoLateImage` (the gated fake answers after close: the old preview stays, no failure, not
  rendering) and `aRotationKeepsTheJob` (the observers of the old activity leave and new ones attach: no cancel, no second render, the
  preview arrives). The model runs on the container scope, not on the activity.
- The optics of Step 4 (`refraction.rs`) are not in the tree; the budget was measured on the renderer as shipped. Re-run it after Step 4.

### Step 7. Device-path proof and share/save checks

- With no emulator, prove the release path statically and on the host:
  - `scripts/check-catalog-apk.sh` passes for the Contacts APK (`librosty_ffi.so` arm64 with the JNI exports);
  - the `dexdump` grep from Step 2 is repeated on the final APK;
  - `CardsOpsNativeTest` passes in `testRustBridge`.
- Add an acceptance entry to `docs/kotlin-ui/acceptance.md` (create the Contacts section if it is missing): "Card
  Studio renders a birthday and a contact card for a number-named contact without a birthday; Share and Save work;
  the animated GIF plays once".
- Robolectric re-check of the round-16 Step 6 tests:
  - share MIME type and grant flag;
  - MediaStore `Pictures/Rosty` with `IS_PENDING` cleared;
  - the birthday notification's "Make a Card" PendingIntent;
  - cache cleaning above 20 files.
  - Fix anything that broke with the real ops.

### Step 8. Close X.2

- Run `references/checklist.md` over Card Studio again and update the "X.2 checklist record" in
  `docs/kotlin-ui/checklist-e4.md`: motion, sheet, menu, haptics (reject on failure), insets, a11y, TalkBack order.
- Full gate:
  - `cargo fmt --all && cargo clippy --workspace --all-targets --profile checks && cargo test --workspace --profile checks`.
    If the retired `apps/*` fail, use the gate's package set per CLAUDE.md.
  - `cargo check --release -p rosty_android --target aarch64-linux-android`
  - `bash scripts/kotlin-ui-check.sh`
- Build the Contacts release APK and state its path:
  `android/contacts/build/outputs/apk/release/contacts-release-unsigned.apk`.
- `epics.md`:
  - set X.2 to `done` with an Evidence line: commits, the Rust goldens, frames and x2 PNGs opened and what was seen,
    the budgets, the sizes, the dexdump result;
  - remove the "Known open item" paragraph;
  - the next planner is **E8 R1**, since E6 and E7 are done in their tables. E6-WA continues in its own session.

## Stashes

(filled in by Step 1)

## Measurements

Step 6 (r17-s6), host `--profile checks`, `RUN_BUDGETS=1 cargo test -p rosty_app_core --profile checks cards::tests:: -- budget --nocapture --test-threads=1` (4 cores; tests that share the cores with other tests run about 2x slower, so the assert only runs when `RUN_BUDGETS=1`):

| Item | Before | After | Budget (asserted at 2x) |
| --- | --- | --- | --- |
| Static 720 x 900 birthday | 190 ms | 41-44 ms | 150 ms (assert < 300 ms) |
| Birthday GIF 720 x 900, 39 moving frames + still, **no optics yet** | 11.1 s | 1.4-1.6 s | 1.5 s (assert < 3 s) |
| Shipped animated size 540 x 675, same 40 frames | 6.3 s | 0.82-0.9 s | |
| GIF size 720 px / 540 px | 2,798,578 B | unchanged (same bytes, deterministic) | < 3 MB |
| Animated WebP 720 px, lossy q75, same frames (size only, not shipped) | | 608,168 B, encode 191 ms | |
| `librosty_ffi.so` arm64 release (stripped, in the APK) | 19,781,416 B (before the speed overrides) | 20,682,984 B | the 12,519,024 B figure predates Takumi; Takumi itself is +7.3 MB, the speed overrides add +901,568 B |
| Contacts release APK | | 25,897,967 B | |

Where the time went: Takumi and the GIF quantizer were compiled at the workspace `opt-level = "z"`. Fixes, in the order applied
(the plan's order 1 to 3 does not apply, see below):
1. `opt-level = 3` for `takumi`, `takumi-core`, `takumi-raster`, `gif`, `weezl`, `color_quant` (render 180 -> 57 ms per frame, quantize
   92 -> 38 ms per frame; +0.9 MB in the `.so`).
2. Takumi `rayon` feature: the GIF encoder quantizes the frames of a batch in parallel (encode 1.5 s -> 0.6 s).
3. `Prepared::write` renders the frames of a moving card on scoped threads (one per core, at most 4 lanes); `cancelled` is asked between
   chunks of frames (render 2.3 s -> about 0.7 s).
Not done: "reuse the background layer across frames". The panel's backdrop blur samples the background including the moving confetti inside
one Takumi render, so the background cannot be drawn once and reused without redrawing the blur. "Lens only on moving frames" and
"drop to 540 px" are not needed: the budget holds at 720 px. When Step 4's optics land, re-run the budget test: the lens adds work only on
frames 8-20 (0.5-1.4 s), and 540 px is the fallback (already 0.85 s without optics).


## r17-s2 result (R8 proof and native path)

Done: `CardsOpsNativeTest` (real host `librosty_ffi`, no fakes) now renders the contact "+90 532 000 11 22" without a
birthday on Contact, Birthday and New number, a one-shot animated card (GIF 540 x 675), and an animated render that is
polled with `cards.cancel` (the answer says whether it runs) and stopped: it answers `cancelled` and leaves no file.
There is no separate progress op; `cards.cancel`'s `cancelled` flag is the poll. `CardStudioViewModelTest.aFailedRenderIsLoggedByCode`
checks the masked `RostyLog` line (`render failed: template=... out_unwritable`). Fixtures `number_name`, `out_invalid`,
`out_unwritable`, `text_missing` were already in `INDEX`, `OPS.md` and `tests/inventory.rs`.

R8 check, `android/contacts/build/outputs/apk/release/contacts-release-unsigned.apk`, `dexdump -d classes.dex`
(app classes are renamed by R8, so the names come from `mapping.txt`; each obfuscated class descriptor was found in the dex):

```
Class descriptor  : 'Ldev/rosty/core/RustNative;'          (kept by core-bridge consumer-rules.pro)
NativeCardsOps                      -> e13   present
CardRenderRequest / $$serializer    -> ku / iu   present
CardRenderResult  / $$serializer    -> nu / lu   present
CardSpec          / $$serializer    -> xu / vu   present
CardCancelRequest / $$serializer    -> lt / jt   present
CardCancelAnswer  / $$serializer    -> ht / ft   present
const-string "cards.render", "cards.cancel" present ("cards.templates" is unused in production and shrunk)
```
No new keep rule is needed: the models are `@Serializable` (plugin serializers, no reflection) and `NativeCardsOps`
is reached by code, not by name.
