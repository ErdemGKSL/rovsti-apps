# Unit `shots`, round 1: headless screenshot harness for all four apps

baseSha: ebeaac24cc6b23dab3790c7c9f98cc14ed92f3ce

## Note on the triggering request

The user's own message for this run is (Turkish): *"For God's sake code the mechanics of
these text boxes properly, even the default HTML input works better."* That is about the
**text field behaviour** (`crates/rovsti_ui/src/field.rs`), not the layout overhaul the
workflow assigned to this unit. This unit still builds the screenshot tool, because a text
field rework needs it to show its result. The tool also gets a text-field state gallery
(step 7) so it can prove that rework. The field behaviour itself is NOT fixed here. A
dedicated unit should do it next. What is wrong today (read in `field.rs`):

- The caret is always at the end: `selected_text_range` returns `end..end`, and
  `character_index_for_point` always answers the end. A tap cannot place the caret, there
  is no selection, and arrow keys and Home/End do nothing.
- `bounds_for_range` returns the whole box, so the IME candidate window and the Android
  cursor anchor sit in the wrong place.
- `delete_surrounding_text` refuses any `after > 0`, and `surrounding_text` always reports
  the caret at the end. Keyboards that edit mid-word (autocorrect, swipe replacement)
  therefore misbehave.
- There is no select-all, copy, cut, paste or long-press menu. There is no double-tap word
  selection. The field cannot scroll to the caret: while typing, the start is cut with an
  ellipsis.
- The caret is a fixed 2x20 dp bar drawn after the text. It does not blink and does not
  follow a caret position.

## What I verified (spike in a throw-away worktree, since removed; no source in the repo changed)

- `cargo test -p liquid_glass_kit --features headless-tests --test headless_frames -- --ignored`
  passes on lavapipe here (8.8 s) and writes real frames.
- I opened the real `PhoneApp`, `MessagesApp`, `ContactsApp` and `StopsApp` in
  `HeadlessAppContext::with_platform(CosmicTextSystem::new("Noto Sans"), IconAssets::new(), ||
  WgpuOffscreenRenderer ...)` at 412x915 dp. The output is 824x1830 px, because `TestWindow`
  hard-codes a scale factor of 2.0. All four rendered the real UI: glass header buttons, the
  tab bar with its puck, Turkish glyphs (Ş, ı, ğ, İ, Ö) and the preview data. Each one took
  0.5 to 2.4 s.
- Pitfalls the spike hit. The plan handles each one:
  1. `render_rgba` can fail with *"GPUI renderer requested a larger instance buffer; retry
     the frame"* (Stops map). The adapter must retry, up to 4 times, when the error text
     contains `retry`.
  2. `TestWindow::appearance()` is always Light. The theme comes from
     `rovsti_ui::set_theme_override`. **Stops** overwrites the override from its own prefs
     (`map_options::apply_appearance` and `prefs.appearance`), so the first spike shot
     requested dark and came out light. Stops must be given its appearance through its
     prefs, or the override must be set again after construction.
  3. Apps persist prefs under `$XDG_DATA_HOME/rovsti/<app>`. Without isolation, Phone came
     back on the tab stored in the developer's real profile, not the one the shot asked for.
     Every shot needs a fresh data dir.
  4. **Stops downloaded real tiles through the proxy.** Tests must point the tile source at
     an unreachable host so that the offline placeholder renders.
  5. Phone on desktop draws the `devtools` chip strip ("Ring Ayşe, Call, Hold ...") and a
     "Preview data" caption, and the caption overlaps the search field. Shots must hide
     both.
  6. `ROVSTI_PREVIEW_*` env vars are read once at construction and are process-global. In
     the spike, `ROVSTI_PREVIEW_DIAL` did not open the Keypad. Scenes must drive the state
     by calling crate-private app methods, not env vars.
  7. The preview data uses wall-clock `now`. The result is non-deterministic times
     ("Yesterday", "4 Nov", "15:32") in `messages/backend.rs` + `conversation.rs`,
     `phone/backend.rs`, `person_ui.rs`, `contacts/whatsapp.rs` and `stops/store.rs`.
  8. Fidelity check needed: in dark, every app's `components::backdrop` wash (160 deg linear
     gradient, fading out at 0.55) ends in a **hard diagonal edge** at about 475 dp. The
     device screenshot shows a smooth fade. Either lavapipe renders gradients with alpha
     differently, or the gradient is wrong on device too. Step 1 records which one, so
     reviewers do not "fix" a harness artifact.
- Real-UI issues visible in the spike shots, for the later layout units (not fixed here):
  Messages filter chips wrap to two rows ("Unread 2", "WhatsApp 1" counts glued on), the
  "All" selected chip has low contrast (accent text on accent glass), and the SMS +
  WhatsApp pills eat the preview text. The tab bar sits over the last row in Contacts with
  no bottom inset: "İpek Güneş" is under it. Phone light mode has very faint secondary
  text and times. The Stops "Start" button is disabled but looks like an empty pill.

## Steps

### 1. `rovsti_ui` feature `shots`: the shared rig
- `crates/rovsti_ui/Cargo.toml`: optional deps `gpui_wgpu`, `image`, and
  `feature shots = ["gpui/test-support", "dep:gpui_wgpu", "dep:image"]`. Do not use them
  on Android: the feature is never enabled there.
- New `crates/rovsti_ui/src/shots.rs` behind `#[cfg(feature = "shots")] pub mod shots;`:
  - `WgpuHeadless` (copied from `liquid_glass_kit/tests/headless_frames.rs`) plus a retry
    loop on errors whose text contains `retry` (pitfall 1).
  - `pub struct Rig<V>`. `Rig::open(spec: ShotSpec, build: impl FnOnce(&mut Window, &mut App) -> Entity<V>)`
    calls `force_backdrop_support(true)` and `FrameClock::set_fixed_step(Some(16.667 ms))`,
    sets the theme override, and opens the window at `spec.size` dp.
    `ShotSpec { app, nn, screen, theme, size: Size<f32> }`. Scale is fixed at 2 (TestWindow).
  - `Rig::settle(max_frames = 240)`: a loop of draw, `run_until_parked` and
    `advance_clock(16.667 ms)`. It stops early after 10 consecutive frames in which no view
    was notified (observe the root entity, as `perf_harness::Rig` does with `dirty`).
  - `Rig::update(|app, window, cx| ..)`, `Rig::theme(Theme)` (re-applies the override
    after the app has had a chance to set its own) and `Rig::save() -> PathBuf`, which
    writes `target/shots/<app>/<NN>-<screen>-<dark|light>.png`. Small-phone shots use the
    suffix `-dark-small`.
  - `pub fn isolate_data_dir(app)`: sets `XDG_DATA_HOME` to `target/shots/.data/<app>/`
    after wiping it, and sets `TZ=Europe/Istanbul`. It is called once per test, and the
    module docs require one `#[test]` per app (env is process-global).
  - `pub fn contact_sheet(app, &[PathBuf])`: a 5-column grid of tiles 240 px wide (1200 px
    max), in shot order, on a neutral grey. It writes `target/shots/<app>/contact_sheet.png`.
  - Output root: `$CARGO_TARGET_DIR` or `<workspace>/target`, then `/shots`.
- Pure helpers, unit-tested under the feature: `file_name(nn, screen, theme, small)`,
  `sheet_layout(n, tile_w, tile_h, cols) -> (w, h, positions)`, and `retryable(&err)`.
- Smoke `#[ignore]` test in `rovsti_ui`: it renders the shared `tab_bar` with
  `components::backdrop` and one `field_box` to `target/shots/rovsti_ui/00-smoke-{dark,light}.png`.
  Read both images. Note whether the backdrop's hard edge (pitfall 8) appears with only the
  backdrop on screen, and add one sentence on it to docs/screenshots.md under
  "Known renderer differences".
- Acceptance: both smoke PNGs show glass and the tab bar, and are not blank. All
  workspace checks pass, plus `cargo clippy -p rovsti_ui --features shots --all-targets -- -D warnings`
  and `cargo test -p rovsti_ui --features shots`.

### 2. Determinism seams (small, tested, shared)
- `rovsti_core::clock` (new, platform-free): `now_ms()` reads an `AtomicI64` override, or
  `SystemTime` when it is 0. `set_fixed_now_ms(Option<i64>)`. Unit tests cover both paths.
  Replace `SystemTime::now()` only at the call sites that feed rendered text: the files in
  pitfall 7, `rovsti_wa_ui/src/avatars.rs::now_ms`, and the Messages
  `locale().tz_offset_s` source on desktop. Fixed instant: `2026-11-07T15:40:00+03:00`.
- Phone: replace the `#[cfg(not(target_os = "android"))]` devtools strip and the
  "Preview data" caption with a runtime gate, `PhoneApp::devtools_visible` (default true on
  desktop). The rig sets it to false (pitfall 5).
- Stops: a test-only way to start offline. Seed `stops-prefs.json` in the isolated dir
  with the tile server set to `http://127.0.0.1:9/{z}/{x}/{y}` (connection refused, so no
  network), and with `appearance` set to the shot's theme (pitfall 2). Use the existing
  prefs format and do not add a new code path. If `rovsti_tiles` retries with backoff, make
  sure the placeholder is visible within `settle`.
- Acceptance: `cargo test --workspace` is green, and new unit tests cover `clock`. Two
  successive phone smoke renders are byte-identical (`cmp`).

### 3. Phone shots (`apps/phone`, feature `shots = ["rovsti_ui/shots"]`, `src/shots.rs`)
- `main.rs`: `#[cfg(all(test, feature = "shots"))] mod shots;`. One
  `#[test] #[ignore] fn shots()`. Construct the app with `Backend::start` as
  `perf_harness::Rig::with_size` does (the sample/preview backend).
- Scenes (NN-screen), each in dark and light at 412x915:
  01-keypad-empty, 02-keypad-long-number (`+90 532 123 45 67 891 234`, via the keypad
  model, not env), 03-recents (`ROVSTI_PREVIEW_TAB` replaced by `tabs.jump_to(1)`),
  04-recents-missed-filter, 05-contacts-list, 06-contacts-search-typing ("Ayş" in the search
  field, focused), 07-favorites, 08-contact-detail (person_ui, Ayşe Yılmaz),
  09-call-details (details_ui), 10-incall (preview scene `call`), 11-incoming,
  12-settings, 13-setup-sheet. Small-phone: 01-keypad-empty at 360x640 dark.
- Acceptance: 27 PNGs plus `contact_sheet.png` in `target/shots/phone/`. Open
  02, 06, 08 and 10 (dark) and the small variant: each shows its screen, with no devtools
  strip and no "Preview data".

### 4. Messages shots (`apps/messages`, feature `shots`, `src/shots.rs`)
- Dev-deps: none beyond the feature. Messages has no test-support dev-dep today, and the
  feature brings it in.
- A richer test-only fixture in `backend/preview.rs` behind `#[cfg(test)]`:
  `Preview::shots(now)`. It has 24 threads mixing SMS, WhatsApp 1:1 and WhatsApp groups,
  long names ("Maltepe | Yazılım Mühendisliği Duyuruları"), Turkish snippets, unread badges
  1/3/12/120, an OTP row, 3 spam rows, and status updates from 3 people, so the Status row
  renders. WhatsApp threads come through `whatsapp_threads(now)`; extend it the same way.
- Scenes, dark and light: 01-inbox, 02-inbox-unread-filter, 03-inbox-empty (filter with no
  match), 04-conversation-sms (mixed in/out bubbles, delivery ticks, a date separator, a long
  bubble, an OTP), 05-conversation-whatsapp-group (sender names, reply quote, media
  placeholder), 06-composer-typing (focused, 2 lines of Turkish), 07-new-conversation,
  08-global-search, 09-status, 10-spam-folder, 11-row-menu (RowMenu sheet), 12-settings,
  13-whatsapp-link (connect key / QR sheet from `rovsti_wa_ui`). Small-phone: 01-inbox
  360x640 dark.
- Acceptance: 27 PNGs plus a contact sheet. Open 01, 04, 05 and 06 (dark). In 01 the
  Status row, two rows of chips and at least 10 rows are visible, so the device problems
  are reproducible.

### 5. Contacts shots (`apps/contacts`, feature `shots`, `src/shots.rs`)
- Fixture: extend `sample.rs` (test-only) to 40 contacts over A to Z plus Ç, Ö, Ş, Ü and İ,
  with photos where samples exist, favourites, groups, a birthday today, and a duplicate pair.
- Scenes, dark and light: 01-list, 02-list-search ("ş"), 03-favorites (quick),
  04-groups, 05-detail, 06-editor (all fields, one focused), 07-me-card, 08-birthdays,
  09-dupes, 10-trash-empty, 11-select-mode, 12-qr-sheet, 13-settings. Small-phone: 01-list
  360x640 dark.
- Acceptance: 27 PNGs plus a contact sheet. Open 01, 05 and 06 (dark) and the small one.

### 6. Stops shots (`apps/stops`, feature `shots`, `src/shots.rs`)
- Offline tiles and appearance via seeded prefs (step 2). Saved stops fixture: 6 stops with
  colour tags and Turkish names ("Kadıköy İskele", "Üsküdar Marmaray"), 5 history trips.
- Scenes, dark and light: 01-trip-idle (map placeholder), 02-trip-a-set,
  03-trip-a-b-set (gate A and gate B rings, distance), 04-trip-running (live card with
  arrival clock; use `ROVSTI_STOPS_SIMULATE`'s fix source by calling it directly, not via
  env), 05-alarm-ringing, 06-saved-list, 07-saved-stop-sheet, 08-history,
  09-map-options, 10-pick (stop picker), 11-settings, 12-qr-sheet. Small-phone:
  01-trip-idle 360x640 dark.
- Acceptance: 25 PNGs plus a contact sheet. No file appears under `.data/stops/tiles/*/`
  (proof that nothing was downloaded). Open 01, 03 and 05 (dark) and 01 (light).

### 7. Text field state gallery (for the user's text-box request)
- In the `rovsti_ui` shots test, add scene `01-fields-{dark,light}`: one 412x915 page that
  stacks `field_box` in each state. The states are: placeholder, focused empty, focused with
  short text, focused with a long Turkish URL ("https://örnek.com.tr/çok/uzun/bir/adres?ş=1"),
  unfocused with long text, composing (marked) text, secret, and a Phone-purpose field. Each
  state gets a caption label.
- Purpose: the baseline for the text-field mechanics unit. Today it shows the end-anchored
  caret and the start ellipsis. That unit adds mid-text caret and selection states to the
  same gallery.
- Acceptance: `target/shots/rovsti_ui/01-fields-dark.png` is opened and every state is
  legible.

### 8. Docs, baseline, verification
- `docs/screenshots.md` covers:
  - the commands: `cargo test -p rovsti_<app> --features shots shots -- --ignored --nocapture`
    for each app, and `cargo test -p rovsti_ui --features shots shots -- --ignored --nocapture`;
  - output paths, naming (`NN-screen-theme[-small].png`), and the isolated data dir;
  - the pitfalls above (retry, theme override, offline tiles, single test per app);
  - "Known renderer differences" (from step 1);
  - how to add a screen: a new `scene(rig, "14-name", |app, window, cx| ...)` line;
  - the extra lint: `cargo clippy -p rovsti_<app> --features shots --all-targets -- -D warnings`.
- Baseline: copy each `contact_sheet.png`, downscaled to at most 1200 px wide, to
  `docs/screenshots/baseline/{phone,messages,contacts,stops}.png`. Keep each one under about
  1.5 MB (PNG, 8-bit RGB).
- Fill in **Baseline** below with every produced file.
- Acceptance: all standard checks pass, the four feature-gated clippy runs pass, and
  `cargo test --workspace` shows the shots tests neither run nor compile (feature off).

## Baseline


### phone

- target/shots/phone/01-keypad-empty-dark-small.png (720x1280)
- target/shots/phone/01-keypad-empty-dark.png (824x1830)
- target/shots/phone/01-keypad-empty-light.png (824x1830)
- target/shots/phone/02-keypad-long-number-dark.png (824x1830)
- target/shots/phone/02-keypad-long-number-light.png (824x1830)
- target/shots/phone/03-recents-dark.png (824x1830)
- target/shots/phone/03-recents-light.png (824x1830)
- target/shots/phone/04-recents-missed-filter-dark.png (824x1830)
- target/shots/phone/04-recents-missed-filter-light.png (824x1830)
- target/shots/phone/05-contacts-list-dark.png (824x1830)
- target/shots/phone/05-contacts-list-light.png (824x1830)
- target/shots/phone/06-contacts-search-typing-dark.png (824x1830)
- target/shots/phone/06-contacts-search-typing-light.png (824x1830)
- target/shots/phone/07-favorites-dark.png (824x1830)
- target/shots/phone/07-favorites-light.png (824x1830)
- target/shots/phone/08-contact-detail-dark.png (824x1830)
- target/shots/phone/08-contact-detail-light.png (824x1830)
- target/shots/phone/09-call-details-dark.png (824x1830)
- target/shots/phone/09-call-details-light.png (824x1830)
- target/shots/phone/10-incall-dark.png (824x1830)
- target/shots/phone/10-incall-light.png (824x1830)
- target/shots/phone/11-incoming-dark.png (824x1830)
- target/shots/phone/11-incoming-light.png (824x1830)
- target/shots/phone/12-settings-dark.png (824x1830)
- target/shots/phone/12-settings-light.png (824x1830)
- target/shots/phone/13-setup-sheet-dark.png (824x1830)
- target/shots/phone/13-setup-sheet-light.png (824x1830)
- target/shots/phone/contact_sheet.png (1200x3198)

### messages

- target/shots/messages/01-inbox-dark-small.png (720x1280)
- target/shots/messages/01-inbox-dark.png (824x1830)
- target/shots/messages/01-inbox-light.png (824x1830)
- target/shots/messages/02-unread-filter-dark.png (824x1830)
- target/shots/messages/02-unread-filter-light.png (824x1830)
- target/shots/messages/03-inbox-empty-dark.png (824x1830)
- target/shots/messages/03-inbox-empty-light.png (824x1830)
- target/shots/messages/04-conversation-sms-dark.png (824x1830)
- target/shots/messages/04-conversation-sms-light.png (824x1830)
- target/shots/messages/05-conversation-whatsapp-group-dark.png (824x1830)
- target/shots/messages/05-conversation-whatsapp-group-light.png (824x1830)
- target/shots/messages/06-composer-typing-dark.png (824x1830)
- target/shots/messages/06-composer-typing-light.png (824x1830)
- target/shots/messages/07-new-conversation-dark.png (824x1830)
- target/shots/messages/07-new-conversation-light.png (824x1830)
- target/shots/messages/08-global-search-dark.png (824x1830)
- target/shots/messages/08-global-search-light.png (824x1830)
- target/shots/messages/09-status-dark.png (824x1830)
- target/shots/messages/09-status-light.png (824x1830)
- target/shots/messages/10-spam-folder-dark.png (824x1830)
- target/shots/messages/10-spam-folder-light.png (824x1830)
- target/shots/messages/11-row-menu-dark.png (824x1830)
- target/shots/messages/11-row-menu-light.png (824x1830)
- target/shots/messages/12-settings-dark.png (824x1830)
- target/shots/messages/12-settings-light.png (824x1830)
- target/shots/messages/13-whatsapp-link-dark.png (824x1830)
- target/shots/messages/13-whatsapp-link-light.png (824x1830)
- target/shots/messages/contact_sheet.png (1200x3198)

### contacts

- target/shots/contacts/01-list-dark-small.png (720x1280)
- target/shots/contacts/01-list-dark.png (824x1830)
- target/shots/contacts/01-list-light.png (824x1830)
- target/shots/contacts/02-list-search-dark.png (824x1830)
- target/shots/contacts/02-list-search-light.png (824x1830)
- target/shots/contacts/03-favorites-dark.png (824x1830)
- target/shots/contacts/03-favorites-light.png (824x1830)
- target/shots/contacts/04-groups-dark.png (824x1830)
- target/shots/contacts/04-groups-light.png (824x1830)
- target/shots/contacts/05-detail-dark.png (824x1830)
- target/shots/contacts/05-detail-light.png (824x1830)
- target/shots/contacts/06-editor-dark.png (824x1830)
- target/shots/contacts/06-editor-light.png (824x1830)
- target/shots/contacts/07-me-card-dark.png (824x1830)
- target/shots/contacts/07-me-card-light.png (824x1830)
- target/shots/contacts/08-birthdays-dark.png (824x1830)
- target/shots/contacts/08-birthdays-light.png (824x1830)
- target/shots/contacts/09-dupes-dark.png (824x1830)
- target/shots/contacts/09-dupes-light.png (824x1830)
- target/shots/contacts/10-trash-empty-dark.png (824x1830)
- target/shots/contacts/10-trash-empty-light.png (824x1830)
- target/shots/contacts/11-select-mode-dark.png (824x1830)
- target/shots/contacts/11-select-mode-light.png (824x1830)
- target/shots/contacts/12-qr-sheet-dark.png (824x1830)
- target/shots/contacts/12-qr-sheet-light.png (824x1830)
- target/shots/contacts/13-settings-dark.png (824x1830)
- target/shots/contacts/13-settings-light.png (824x1830)
- target/shots/contacts/contact_sheet.png (1200x3198)

### stops

- target/shots/stops/01-trip-idle-dark-small.png (720x1280)
- target/shots/stops/01-trip-idle-dark.png (824x1830)
- target/shots/stops/01-trip-idle-light.png (824x1830)
- target/shots/stops/02-trip-a-set-dark.png (824x1830)
- target/shots/stops/02-trip-a-set-light.png (824x1830)
- target/shots/stops/03-trip-a-b-set-dark.png (824x1830)
- target/shots/stops/03-trip-a-b-set-light.png (824x1830)
- target/shots/stops/04-trip-running-dark.png (824x1830)
- target/shots/stops/04-trip-running-light.png (824x1830)
- target/shots/stops/05-alarm-ringing-dark.png (824x1830)
- target/shots/stops/05-alarm-ringing-light.png (824x1830)
- target/shots/stops/06-saved-list-dark.png (824x1830)
- target/shots/stops/06-saved-list-light.png (824x1830)
- target/shots/stops/07-saved-stop-sheet-dark.png (824x1830)
- target/shots/stops/07-saved-stop-sheet-light.png (824x1830)
- target/shots/stops/08-history-dark.png (824x1830)
- target/shots/stops/08-history-light.png (824x1830)
- target/shots/stops/09-map-options-dark.png (824x1830)
- target/shots/stops/09-map-options-light.png (824x1830)
- target/shots/stops/10-pick-dark.png (824x1830)
- target/shots/stops/10-pick-light.png (824x1830)
- target/shots/stops/11-settings-dark.png (824x1830)
- target/shots/stops/11-settings-light.png (824x1830)
- target/shots/stops/12-qr-sheet-dark.png (824x1830)
- target/shots/stops/12-qr-sheet-light.png (824x1830)
- target/shots/stops/contact_sheet.png (1200x2665)

### rovsti_ui

- target/shots/rovsti_ui/00-smoke-dark.png (824x1830)
- target/shots/rovsti_ui/00-smoke-light.png (824x1830)
- target/shots/rovsti_ui/01-fields-dark.png (824x1830)
- target/shots/rovsti_ui/01-fields-light.png (824x1830)

Committed baselines: docs/screenshots/baseline/{phone,messages,contacts,stops}.png (contact sheets, 1200 px wide; messages 1000 px).


## Step 1 result (rovsti_ui `shots` rig)

Produced: target/shots/rovsti_ui/00-smoke-dark.png and 00-smoke-light.png (824x1830).
Both opened: the Turkish heading, a glass field_box ("Ara") and the floating tab bar with
its puck are visible in both themes (the glass refracts the backdrop; not blank).

Backdrop edge (pitfall 8): with only `components::backdrop` on screen, dark mode shows a
diagonal band at about y=500 dp where the accent wash reaches 0 alpha. It is a smooth ramp
that ends in a slope kink (Mach band), not a hard step. It comes from the gradient itself
(alpha 0.28 -> 0 at stop 0.55, linear, ends abruptly), not from lavapipe or the renderer,
so it will look the same on a device on a dark background. Reviewers should treat it as a
real design issue (ease the wash out or extend the stop), not a harness artifact.
Record this under "Known renderer differences" in docs/screenshots.md when that file is written.

Notes: `Rig::settle` has no GPUI "still animating" query, so it renders every 10 frames and stops when two
consecutive captures are equal (max 240 frames). `Rig` field order matters (root before cx) or
GPUI reports a leaked entity handle.

## Step 4 result (Messages shots)

`cargo test -p rovsti_messages --features shots shots -- --ignored --nocapture` writes 27 PNGs
(13 scenes x dark/light at 412x915, plus `01-inbox-dark-small.png` at 360x640) and
`target/shots/messages/contact_sheet.png`. Fixture: `backend/preview/shots_data.rs` (24 threads,
badges 1/2/3/12/120, OTP, 3 spam, 3 status authors). Scenes 05 and 09 run against a loopback fake
gateway (`src/shots/gateway.rs`). 03 is a no-match search (the Unread chip cannot be empty in the
preview), and 08 shows the search row with "SMS search not available" on desktop.

## Step 7 result (text field gallery)

`cargo test -p rovsti_ui --features shots fields -- --ignored --nocapture` writes
`target/shots/rovsti_ui/01-fields-dark.png` and `01-fields-light.png` (824x1830). Code:
`crates/rovsti_ui/src/shots/fields.rs`; `field.rs` is unchanged. Eight captioned `field_box` rows
(`STATES` table): placeholder, focused empty, focused short text, focused long Turkish URL,
unfocused long text, composing text, secret, phone purpose. GPUI has one focus per window, so the
page is drawn once per focused row and each row's band is composed from the frame where it had
focus. Baseline seen: caret always at the end of the text; the focused URL is cut at the start
with an ellipsis; the unfocused long text is cut at the end; the composing text shows no marked
underline (looks like committed text); the secret field shows one dot per char.
The text-field mechanics unit will add caret-mid-text and selection states to `STATES` in this
gallery (a new `State` row each; the compose code handles any number of focused rows).
