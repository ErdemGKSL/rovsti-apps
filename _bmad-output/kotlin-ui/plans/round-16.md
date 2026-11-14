# Round 16 plan: X.2 share cards with Takumi (birthday card first), Contacts

Planned 2026-11-14 at base `93bcad4` (branch `ui-overhaul`). Epic: **X. Cross-app owner requests**. E1-E5 are done and X.1
is done, so the first open story is **X.2** (scheduled for round 16 in `epics.md` "X.", before E6). This round does
all of X.2 in 8 steps: a Takumi spike, static templates with the liquid-glass panel, the FFI op and the Kotlin bridge,
one-shot animated cards, the Contacts preview sheet, share and save, budgets and cancellation, then the close. E6 R1 is
next round.

Owner decisions in force: Rovsti is GPL-3.0-or-later. Convx code is ported close to verbatim, keeping its original header
and adding `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`. glisse-derived code
(here: the spring solver and the refraction/lens displacement maps) carries `Portions ported from glisse
(https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang`. `android/THIRD_PARTY_NOTICES.md` lists
every new source, library and font. Owner fixes: (a) every pushed page has the glass back button and the full tab bar
(guarded by `PushedPagesBackTest`), (b) no `RustNative` call before `Rust.init`, (c) `imeOverlapPadding` only, (d) R8
keep rules for anything reached by name, checked with `dexdump` on the release APK. The epics.md Definition of done
item 6 also applies: Turkish strings, notification icons, and no crash on platform calls.

## State found at `93bcad4` (verified in code)

- Working tree clean. `git stash list` holds only `stash@{0}` (the deprecated GPUI WIP, never pop). The round-15 stashes
  were recovered in `3ad1c3b`, `5857821` and `8e9b12b`.
- **X.1 verified done in code:**
  - `crates/rovsti_app_core/src/people.rs`
  - `android/people/` (`PeopleContract`, `AvatarResolver`, `PeopleIndex`, `PeopleSources`, `ui/ContactAvatar`)
  - Contacts `people/{PeopleProvider,PeopleStore,PeopleSync,PeopleSyncWorker}.kt` (`a6fa10d`). The provider is
    guarded by `dev.rovsti.permission.READ_PEOPLE`.
  - The Contacts avatar surfaces (`8076917`, `ContactsAvatarSurfacesTest`, `AvatarSourceScanTest`).
  - The Phone surfaces and notifications (`93bcad4`).
  - X.1 stays `done`.
- **Hooks for X.2 in HEAD:**
  - Card overflow menu: `card/ContactCardScreen.kt:193` (`ButtonMenuEntry(anchor, overflowMenu(...))`).
  - Birthday "Wish" chip on the date row: `ContactCardScreen.kt:687-704`, `ContactCardAction.Wish`
    (`ContactCardState.kt:201`).
  - Upcoming Birthdays page: `favorites/UpcomingScreen.kt`.
  - Birthday alarms: `data/BirthdayAlarms.kt` and `dev.rovsti.bridge.BirthdayReminderReceiver`.
  - Share provider: `dev.rovsti.bridge.RovstiShareProvider` (`${applicationId}.rovstishare`, `cache/share`).
  - QR code: `crates/rovsti_ffi/src/ops/qr.rs` (`qrcode` is already a workspace dependency).
  - vCard text: `rovsti_core::vcard`.
- **No fonts are bundled anywhere** (`git ls-files` has no `.ttf/.otf/.woff2`). minSdk is 34 (`RovstiSdk.kt`).
- **Takumi on crates.io:**
  - 2.20.0 (2026-11-08) and 2.19.0 (2026-10-29) are too new for the "at least two weeks old" rule.
  - **Pin `takumi = "=2.18.2"`** (2026-10-26, MIT OR Apache-2.0, rust-version 1.91; local rustc is 1.99.1).
  - Features: `default = raster-backend, woff2, woff, svg-source, image-decoding, rayon`. Separate `gif`, `png`,
    `jpeg` and `webp` features exist.
  - It depends on `takumi-core ^0.34`, `takumi-raster ^0.5.11` and `takumi-svg ^0.3.33`.
  - Crate size is 7.2 MB, so check the `.so` growth.
- **Disk: 9.8 GB free.** Keep at least 6 GB free: before the Android builds, delete `target/checks` or a single
  `android/<module>/build`, never all of them.
- **Screenshot review of round 15** (opened in this planning round):
  - Fine:
    - `x1-wa-avatar-list-light`: WhatsApp pictures fill the rows; initials stay for people without a picture.
    - `x1-wa-avatar-card-dark`: hero picture; back, edit/star and more buttons are glass.
    - `x1-wa-avatar-recents-light`: stranger rows show the picture; the chip row scrolls and does not wrap.
    - `x1-people-sync-row-light`: Last Sync / Sync Now rows with a footer.
  - **F40 (new):** the X.1 goldens cannot prove their claim. The fake loader's "WhatsApp pictures" are featureless
    two-colour gradients (`X1PhoneAvatarShotTest.kt:33`, Contacts `AvatarRig.kt`).
    - In `x1-wa-avatar-incoming-dark` the green gradient background looks exactly like a tinted aura, not a photo.
    - In `x1-wa-avatar-list-light` the gradients look like coloured placeholder discs.
    - Fixed in Step 8 with recognisable test pictures.

## Design decisions for X.2 (apply to every step)

- **Where the work lives:**
  - Rendering is in `rovsti_app_core::cards`. It is platform-free and unit-tested, and it is the only code that names
    Takumi types.
  - `rovsti_ffi` exposes the ops `cards.render`, `cards.render_animated`, `cards.progress` and `cards.cancel`.
  - Kotlin keeps only the sheet, the share step and the strings.
- **Text comes from Kotlin.** Every visible word on a card is resolved from `res/values{,-tr}/strings.xml` and sent in
  the request (`texts: { title, subtitle, age_line, date_line, ... }`). The core decides only layout, so the Turkish
  rule is met without a second string table in Rust.
- **Files, not base64.** The request carries an output path under `cacheDir/share/cards/` and the answer returns
  `{ path, width, height, bytes, frames, millis }`. Photo input is also a path: the contact photo or the X.1 WhatsApp
  picture file from `AvatarResolver`, else no photo and initials.
- **Fonts:**
  - Bundle Inter 4.1 (SIL OFL 1.1, `github.com/rsms/inter` release `Inter-4.1.zip`) as static Regular, Semibold and
    Bold `.ttf` (or woff2) under `crates/rovsti_app_core/assets/fonts/`, loaded with `include_bytes!`.
  - Inter covers Turkish (ğ ş ı İ ç ö ü).
  - Add the OFL text to `THIRD_PARTY_NOTICES.md` and to `assets/fonts/OFL.txt`.
  - No system font fallback, so output is deterministic. Text with emoji is rendered without the emoji (documented);
    no emoji font in this round.
- **Glass without shaders** (the X.2 notes in `epics.md`):
  - The frosted panel is `backdrop-filter: blur() saturate()`, a white or ink tint, a 1 px gradient rim, an inner top
    highlight and a soft `box-shadow`.
  - The lens edge uses glisse's refraction maps, ported to Rust as pure functions in `cards/refraction.rs` with the glisse
    header. The maps are generated as PNG bytes:
    - the edge-band displacement map and the highlight map (`G/internal/refraction.ts:8`, `selectionMaps` around
      lines 430-461);
    - the arc profile `slope` and `scale = 0.5/mean` (`G/internal/lens.ts:119-138`).
  - The maps are fed as `feImage` (a data URI) into `feDisplacementMap`, which uses glisse's two passes, across and
    down (`G/internal/lens.ts:407-440`), plus `feSpecularLighting` for the rim.
  - If `backdrop-filter: url(#f)` does not displace in Takumi 2.18.2, render the background a second time clipped to
    the panel and apply `filter: url(#f)` to that copy. The Step 2 test decides which one, and the choice is written in
    the module doc.
- **Motion:**
  - Port glisse's analytic spring (`G/internal/spring.ts:21-48`, mass 1, stiffness k and damping coefficient c,
    `dampingRatio = c / (2 * sqrt(k))`) to `cards/spring.rs` with the glisse header. Do not depend on the deprecated
    `liquid_glass_kit`.
  - Tokens come from the skill (`references/motion.md` section 2.1):
    - panel drop: `sheetSettle` 0.86 / 380 for position and `glassPressUp` 0.373 / 250 for the jelly squash, so it
      overshoots once;
    - lens sweep: `menuMorphOpen` 0.939 / 600;
    - confetti: `appear` 1.0 / 450 for alpha, plus a ballistic path with drag;
    - contact panel: `morph` 1.0 / 950.
  - Squash and stretch keep the area (`sx * sy = 1`), as in the skill's press section.
- **One-shot animation:**
  - 1.5-3 s long, 24-45 frames at 15 fps, 540-720 px wide.
  - At least the last 0.6 s is the held still frame. **The last frame equals the static render** (tested byte for byte
    on decoded pixels).
  - GIF loop count is "play once", which means **no NETSCAPE looping extension** (an extension with 0 means forever).
    APNG uses `num_plays = 1`. Animated WebP uses `loop_count = 1`.
- **The preview sheet** uses the ported `IosSheet` / `IosSheetPresentation` (E1.28) at the medium detent, with:
  - the template picker as `IosSegmentedControl`;
  - the colour choice as one non-wrapping row of glass swatches (the owner's "wrapping chips" complaint);
  - a Still / Animated `IosToggle` row;
  - Share and Save buttons as `IosButton` filled and plain.
  - The preview image crossfades. The layout never jumps: the preview box has a fixed aspect from the template.

## Steps

### Step 1. Takumi spike, fonts, first static render

- Workspace `Cargo.toml` `[workspace.dependencies]` gets
  `takumi = { version = "=2.18.2", default-features = false, features = ["raster-backend", "png", "jpeg", "webp", "gif", "svg-source", "image-decoding"] }`.
  - Drop `rayon` if it fails the Android build or adds more than about 300 KB; record the decision.
  - `rovsti_app_core/Cargo.toml` uses `takumi.workspace = true`.
- Read the real API in `~/.cargo/registry/src/*/takumi-2.18.2/` and `takumi-core-0.34.*` (the node tree, style, font
  loading, `render`, encoders, the animation API and the loop setting). **Do not invent APIs.** Write the call sites
  you found as comments in `cards/mod.rs`.
- `crates/rovsti_app_core/src/cards/{mod.rs,fonts.rs}`: `Fonts::bundled()` uses `include_bytes!` for Inter.
  `render_probe(texts, w, h) -> Vec<u8>` renders a gradient, a frosted panel (blur and saturate only) and the text
  "Doğum günün kutlu olsun, İlkay!".
- Tests in `cards/tests.rs`:
  - the PNG decodes with the right size;
  - the Turkish glyphs are not tofu, checked by comparing pixels inside the text box against a rendering of only ASCII;
  - the render is deterministic (two renders are byte-equal).
- Checks:
  - `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace`
  - `cargo check --release -p rovsti_android --target aarch64-linux-android`
  - Build the release `.so` once and record its size before and after in this plan file under "Measurements".
- Notices: add Takumi (MIT OR Apache-2.0) and Inter (OFL) with their full texts to `android/THIRD_PARTY_NOTICES.md`.
- Proof: write `target/checks/cards/probe.png`, then open it with Read. It must show a readable Turkish line on a
  frosted panel.

### Step 2. Static templates and the liquid-glass panel

- `cards/model.rs`:
  - `CardSpec { template: Template, palette: Palette, size: CardSize, texts: CardTexts, photo: Option<PathBuf>, initials, qr: Option<String>, format: StillFormat }`
  - `Template::{Birthday, Contact, NewNumber}`
  - `Palette::{Dawn, Lagoon, Meadow, Dusk, Ink}`: soft two-stop or three-stop gradients plus an accent. Light palettes
    use white glass; Ink uses dark glass. Use the theme tokens from `android/design` `AppleTokens` and
    `GlassTintTokens` for the tint alpha values, copied as numbers with a comment that cites them.
- `cards/refraction.rs`: the glisse-ported map builders `edge_band_maps(w, h, radius) -> Maps { displacement_png, highlight_png, scale }`
  and the `arc(sag, half)` / `slope` helpers.
- `cards/glass.rs`: the panel node (blur 24 px, saturate 1.6, tint, rim, specular, displacement filter) plus the
  fallback described under "Glass without shaders" if Takumi ignores `url()` in `backdrop-filter`.
- `cards/templates/{birthday,contact,new_number}.rs`:
  - Birthday: "Happy birthday, <name>!", the age line when the year is known (from `contact_dates`), a round photo or
    initials, the date line.
  - Contact: name, numbers (at most 3), and a QR of the vCard from `rovsti_core::vcard` encoded with `qrcode`.
  - New number: name, "My new number", the number in a large size.
- Tests:
  - Golden PNGs under `crates/rovsti_app_core/tests/cards/` for each template in two palettes, compared per channel with
    a tolerance of 2 and at most 0.1% of pixels different. Update them with `UPDATE_CARD_GOLDENS=1`.
  - Refraction maps: symmetry, and zero displacement in the centre.
  - The QR decodes back through `contact_scan::decode_luma` to the same vCard.
  - Long names: a 40-character Turkish name shrinks and wraps onto at most 2 lines, never clipped.
  - No photo means initials.
- Proof: open each golden with Read. Compare the panel with `android/contacts/screenshots/x1-wa-avatar-card-light.png`
  (the app's glass) and tune blur, tint and rim until the two read as one family. Note what was changed.

### Step 3. FFI ops and the Kotlin bridge (static)

- `crates/rovsti_ffi/src/ops/cards.rs` registered in `ops/mod.rs`:
  - `cards.templates`: the template ids, sizes, palettes and the text keys each template needs.
  - `cards.render`: spec plus `out` path, answering `{ path, width, height, bytes, millis }`.
  - Errors are codes (`photo_unreadable`, `out_unwritable`, `text_missing`), never English text (F30 rule).
- Fixtures `crates/rovsti_ffi/fixtures/cards.*.{request,answer}.json`, added to `INDEX`; documentation in `OPS.md`.
  The answer fixtures avoid nondeterministic fields (`millis` is masked as in the other timing ops).
- `:core-bridge`: `CardsOps.kt` with models (`CardTemplate`, `CardPalette`, `CardRenderRequest`, `CardRenderResult`,
  `CardError`) and a contract test over the fixtures, written like the other `*ContractTest`s.
  - Every call goes through the existing `Rust.init`-gated entry (owner fix (b)).
  - If a model is serialised by reflection, add `-keep` rules, then check the release APK with `dexdump`
    (owner fix (d)).
- Contacts: `cards/CardTexts.kt` maps a contact to `CardTexts` from string resources, with English and Turkish entries.
  `CardTextsTest` covers both locales, with a Robolectric `@Config(qualifiers = "tr")`.
- Checks: the Rust checks and `bash scripts/kotlin-ui-check.sh`.

### Step 4. One-shot animated cards (GIF, APNG, animated WebP)

- `cards/spring.rs`: the glisse spring port, with tests. A critically damped spring never overshoots; spring 0.373 / 250
  overshoots exactly once above 1%; the settle time matches glisse's numbers.
- `cards/anim.rs`: `Timeline { fps: 15, frames }` and `Choreography` per template. Every effect takes `t` and returns
  node changes, front-loaded:
  - **Birthday**, 2.6 s, 39 frames:
    1. 0-0.9 s: the panel drops in with `sheetSettle` and a jelly squash from `glassPressUp`. The displacement scale
       follows the squash, so the refraction stretches and relaxes.
    2. 0.5-1.4 s: one lens drifts across the name and leaves it sharp. It uses an animated `feDisplacementMap` scale
       and position, `menuMorphOpen`.
    3. 0.9-1.6 s: the specular glint travels the rim once and fades (the `feSpecularLighting` light position
       follows a keyframed path).
    4. 0.3-1.8 s: confetti (40 pieces, seeded RNG, deterministic) bursts and comes to rest on the panel's top edge.
    5. 2.0-2.6 s: still.
    - One hero effect at full strength (the panel drop); the others are restrained, with a peak chromatic fringe at
      most 1.5 px.
  - **Contact**, 1.8 s, 27 frames: the panel settles (`morph`), the gradient drift eases to rest, and the QR fades in
    with `appear`. Still from 1.2 s.
  - New number gets a static card only (documented).
- Encoders:
  - Use Takumi's own animation encoders if 2.18.2 exposes them (Step 1 notes).
  - Otherwise use the `gif` and `image` crates already in its tree, pinned versions only, with no new heavy dependency.
  - Play once: GIF without a NETSCAPE block, APNG `num_plays = 1`, WebP `loop_count = 1`.
  - Pick a palette per card (one global palette quantised from the final frame, so the held still frame is clean).
- Tests:
  - frame count and duration;
  - the first and last frames match the goldens;
  - the last frame equals the static render after decoding;
  - the loop setting is checked at the byte level for all three formats;
  - the GIF is under 3 MB at 720 px;
  - determinism.
- Proof: export frames 0, 6, 12, 18, 24 and the last frame of the birthday GIF as PNGs to `target/checks/cards/frames/`.
  Open each one with Read and judge the drop, the squash, the lens, the glint, the confetti and the still frame. Tune,
  then record the final constants.

### Step 5. Contacts preview sheet (Card Studio)

- `android/contacts/src/main/kotlin/dev/rovsti/contacts/cards/`:
  - `CardStudioState`, `CardStudioViewModel`: template, palette, animated on/off, render job state, last result path.
  - `CardStudioSheet.kt` (the layout under "Design decisions"). The sheet content uses `imeOverlapPadding` if a field is
    ever added (owner fix (c)); none is planned.
  - Rendering runs on `Dispatchers.Default` through `CardsOps`. A spec hash keys a small in-memory result cache, so going
    back to a palette is instant.
  - While a render runs, the old preview stays with a small progress ring on it. The new image crossfades in with the
    `appear` token. The preview box never changes size.
- Entries:
  1. The card overflow menu gets "Make a Card…" (all contacts; default template Contact, or Birthday when the contact
     has a birthday).
  2. The birthday row on the card gets a "Card" chip next to "Wish". It is a plain glass chip on the same line, never a
     wrapping chip row.
  3. `UpcomingScreen` rows get a long-press menu and a trailing "Card" action.
- Strings in en and tr (`card_studio_*`, template names, palette names, "Still", "Animated", "Share", "Save to Photos").
- Compose tests with `mainClock.autoAdvance = false`:
  - template and palette switches issue one render each and debounce taps faster than 150 ms;
  - closing the sheet cancels the job (see Step 7);
  - the back gesture closes the sheet before the page.
- Screenshots in `android/contacts/screenshots/` (Roborazzi, rendered through a fake `CardsOps` that returns the
  committed Rust goldens), light and dark:
  - `x2-card-preview-birthday`, `x2-card-preview-contact`, `x2-card-preview-gif` (rendering state with the progress
    ring);
  - `x2-card-preview-birthday-tr`, `x2-card-preview-birthday-fs2`;
  - `x2-card-chip-on-card` (the Card and Wish chips on the date row).
  - Open each one with Read and check:
    - the swatches stay on one row at fs2;
    - the sheet does not cover the preview's bottom;
    - the buttons sit above the gesture bar.

### Step 6. Share and save

- Share writes the result to `cacheDir/share/cards/<hash>.<ext>` and sends `ACTION_SEND` through
  `RovstiShareProvider` with `FLAG_GRANT_READ_URI_PERMISSION` and the MIME type for the format.
- Format choice per target:
  - Still: JPEG at quality 88, or PNG for the Ink palette's transparent corners if used.
  - Animated: GIF by default. Animated WebP when the chooser's chosen component is a messaging app known to loop GIFs
    (the `ChooserTarget` callback through an `IntentSender`, recorded only). Offer "Share as still image" in the sheet's
    menu too.
- Save writes through `MediaStore.Images` to `Pictures/Rovsti` with `IS_PENDING` (no permission on API 34+). A toast in
  en and tr confirms it. Failure shows the reject haptic and a toast (F31 rule).
- Birthday notification: `BirthdayReminderReceiver`'s notification gets a "Make a Card" action. It opens Contacts on the
  contact with the Card Studio sheet open (deep link extra handled in `MainActivity`).
  - It uses the existing notification kind's icons (DoD 6).
  - If a new receiver or activity alias is added, add a keep rule and check it with `dexdump` (owner fix (d)).
- Tests (Robolectric):
  - the share intent has the right MIME type, a grant flag and a provider URI;
  - the MediaStore insert has the right relative path and clears pending;
  - the notification action's PendingIntent targets the contact and the sheet;
  - the cache folder is cleaned when it holds more than 20 files.

### Step 7. Budgets, cancellation, robustness

- Cancellation: `cards.cancel(job)` sets an atomic flag the frame loop checks between frames. `cards.progress(job)`
  answers `{done, total}` for the ring. A cancelled job deletes its partial file.
- Budgets measured on the desktop with `--profile checks` as a proxy, since there is no device:
  - static render under 300 ms;
  - GIF under 3 s at 720 px with 39 frames.
  - Assert at twice those numbers in a `#[test]` gated by `RUN_BUDGETS=1`, which `kotlin-ui-check.sh` sets for the
    checks profile only. Record the real numbers here.
  - If a budget is missed: render frames in parallel only if `rayon` stayed in, reduce to 540 px, or reuse the
    unchanging background layer across frames.
- Robustness tests:
  - a missing or corrupt photo means initials, not an error;
  - a 4000x3000 photo is downscaled before rendering;
  - a read-only output folder answers `out_unwritable`;
  - an empty name falls back to the number.
- Kotlin: a ViewModel test where the sheet closes mid-render (no leak, no late image), and a test where rotation keeps
  the job.

### Step 8. F40, close X.2, full gate, APKs

- **F40:**
  - Replace the fake WhatsApp picture loaders in `X1PhoneAvatarShotTest` and Contacts `AvatarRig` with recognisable test
    portraits: a drawn head-and-shoulders silhouette on a patterned background, one per person, produced in code with
    no binary assets.
  - Re-record the `x1-wa-avatar-*` goldens (Contacts list, card, search; Phone recents, incoming, person sheet).
  - Open them and confirm the incoming background is visibly a photo, distinct from the aura.
- Run `references/checklist.md` over the Card Studio sheet (motion, sheet, haptics, insets, a11y labels for the preview
  "Birthday card for <name>", TalkBack order) and write the result into `docs/kotlin-ui/checklist-e4.md` under a new
  X.2 section.
- Full gate:
  - `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace`
  - `cargo check --release -p rovsti_android --target aarch64-linux-android`
  - `bash scripts/kotlin-ui-check.sh`
- Build the Contacts release APK and state its path in the step summary. Run `dexdump` on any new keep rule. Record the
  `.so` and APK size growth.
- `epics.md`: set X.2 to `done` with an Evidence line (commits, the PNGs that were opened and what was seen, the GIF
  frames reviewed, the measured budgets). The next planner is **E6 R1**.

## Measurements

(filled in by Steps 1, 4, 7 and 8: `.so` size before/after, APK size, static and GIF render times, GIF sizes)

**Step 1 (2026-11-14), `librovsti_ffi.so`, aarch64-linux-android, `cargo build --release -p rovsti_ffi` (stripped by the profile):**

| State | Bytes | Delta |
|---|---|---|
| HEAD without Takumi (baseline) | 10,030,336 | - |
| Step 1 as committed: Takumi is a dependency of `rovsti_app_core` but no FFI op calls `cards` yet | 10,300,632 | +270,296 (+2.7 %) |
| Temporary probe: one exported fn calling `cards::render_probe` (Takumi, Inter and the CSS and PNG paths linked; probe removed again) | 16,763,128 | +6,732,792 (+67 %) |

- The real cost of X.2 is therefore about **6.7 MB** per APK ABI slice, of which the three Inter TTFs are 1.25 MB
  (Regular 411,640 + SemiBold 419,744 + Bold 420,428). The 6.7 MB comes from Takumi's layout, text shaping (harfrust,
  icu) and raster code at `opt-level = "z"`.
- `rayon` is left **off**: the cards are 540-720 px, a render takes tens of milliseconds, and a rayon pool would add code
  and a thread pool on a phone. Revisit in Step 7 only if the Step 4 GIF times miss the budget.
- Size levers for later, not done now: Inter as woff2 (about 430 KB for the three, needs the `woff2` feature and its
  decoder), dropping `webp` and `jpeg` encoders if the formats are unused, and a per-package `opt-level` override.
- The extra 270 KB with no call comes from the dependency graph being linked (statics and trait objects that LTO could
  not prove dead); it is not the fonts, which are only included when `cards::fonts` is reachable.

