# util-phone: everyday utilities for Rovsti Phone (round 1)

baseSha: a7951dca4dd37e3d27138f2a2811883ed9bceb4d

## Audit (what already exists at baseSha)

Already shipped, so not planned again:
- Block/unblock with Undo pill, Blocked list page (`block_ui.rs`, `blocked_ui.rs`, `rovsti_core::blocklist`).
- Delete one Recents row, delete a person's history, delete all, each with a confirm sheet and Undo
  (`history_ui.rs`, `Backend::delete_calls/restore_calls/delete_all_calls`).
- Quick actions strip on the Number and Contact sheets: Message, Copy, Share, Block (`actions_ui.rs`,
  `telecom::copy_text/share_text`). It copies or shares only the display form (`shown_number`).
- Paste-to-dial with cleanup (`dial::normalise_paste`, `from_tel_uri`), T9 search with the matches strip
  and the results sheet (`t9.rs`, `keypad.rs`), long-press 0 for `+`, held delete.
- Speed dial management (`speed_dial.rs`, `speed_dial_ui.rs`), quick replies, backup/restore of prefs.
- In-call: mute, speaker/route list, hold, keypad DTMF, swap/merge/split, timer, add call.
- Contact photos as 128 px thumbnails in an LRU (`photos.rs`), WhatsApp pictures as stand-ins.

Missing (the backlog below): no full-screen photo viewer, no save-to-gallery or image share, no number
formats beyond the display form, no Recents search or filters, no CSV export (`DocumentOp::ExportCalls`
exists in `wire.rs` but nothing uses it), no call statistics, no per-call notes, no pause/wait inserts,
no QR share. No gallery (MediaStore) or image-share glue exists in `rovsti_android`. The phone manifest
has no FileProvider (Messages has `dev.rovsti.bridge.MmsFileProvider` as a pattern).

GPUI facts checked at the pinned checkout (`~/.cargo/git/checkouts/gpui-fusion-*/d6c8412/crates/gpui`):
`Div::on_pinch(|&PinchEvent{position, delta, ..}, window, cx|)` (elements/div.rs:377),
`PinchEvent.delta` is a relative zoom step (0.1 = +10%) (interactive.rs:603), and mouse events have
`click_count` (interactive.rs:191) for double-tap. Use only these and APIs already used in the app.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| U1 | Photo viewer: tap the sheet avatar to open full screen, pinch and double-tap zoom, pan, swipe down to close | done R1 |
| U2 | Save photo to gallery (MediaStore, Pictures/Rovsti) and share photo (share sheet) from the viewer | done R1 |
| U3 | Recents search (name, number digits) and filter chips: All, Missed, Incoming, Outgoing, WhatsApp | done R1 |
| U4 | Copy as / Share as: display, E.164, national, `tel:` link, vCard (with name) | done R1 |
| U5 | Export call log as CSV (system "save as"), from Settings > Backup | done R1 |
| U6 | Keypad: long-press `*` inserts `,` (pause), long-press `#` inserts `;` (wait) | done R1 |
| U7 | Call statistics: total talk time and call count per contact, per day and per week, shown in the Number/Contact sheet and a Settings "Call statistics" page | backlog R2 |
| U8 | Per-call notes: add/edit/delete a note on a history line and from the in-call screen (local `call_notes.json`, confirm on delete) | backlog R2 |
| U9 | Share contact as QR (vCard in a QR code, `qrcode` workspace dep) in the Contact sheet, with save/share of the QR image | backlog R2 |
| U10 | Set/replace/remove the contact photo from the viewer (`documents::pick_contact_photo`, `contacts_edit::set_photo/remove_photo`, confirm remove) | backlog R2 |
| U11 | Copy the whole call details line (number, time, duration) from a history line long-press | backlog R2 |
| U12 | Recents: select mode with multi-delete and Undo | backlog R3 |
| U13 | "Call back later" reminder on a missed call (local notification via `notify::show`) | backlog R3 |
| U14 | Copy a dialed number from the keypad field (long-press field: Copy / Paste / Clear with Undo) | backlog R3 |
| U15 | Recents date-section headers (Today, Yesterday, weekday) and jump to date | backlog R3 |
| U16 | Export one person's call history (CSV) from the Contact sheet | backlog R3 |
| U17 | In-call: copy the caller's number, open note, quick "Send my number" SMS | backlog R3 |

## Steps (round 1)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free, `rm -rf target/debug/incremental` first. One focused commit per step.

### S1. Pure zoom/pan math: `rovsti_core::zoom`
- New `crates/rovsti_core/src/zoom.rs`, `pub mod zoom;` in `lib.rs`. No deps, `f32` only, reusable by
  the Contacts and Messages apps later.
- Types: `#[derive(Clone, Copy, Debug, PartialEq)] pub struct Size { pub w: f32, pub h: f32 }`,
  `pub struct Point { pub x: f32, pub y: f32 }`,
  `pub struct ZoomView { pub scale: f32, pub offset: Point }` (offset = content centre shift from the
  viewport centre, in viewport px; `scale` 1.0 = fit).
- Consts: `MIN_SCALE = 1.0`, `MAX_SCALE = 5.0`, `DOUBLE_TAP_SCALE = 2.5`, `RUBBER = 0.35`,
  `DISMISS_PX = 120.0`.
- Functions:
  - `pub fn fit(content: Size, viewport: Size) -> Size` (aspect fit, never upscales past the viewport).
  - `impl ZoomView { pub const IDENTITY; pub fn pinch(&mut self, delta: f32, focus: Point, viewport: Size)`
    (scale *= 1 + delta, clamp to `[MIN_SCALE*0.8, MAX_SCALE*1.2]` while the gesture runs, keep the point
    under `focus` fixed); `pub fn pan(&mut self, dx: f32, dy: f32, fitted: Size, viewport: Size)` (free
    inside bounds, `RUBBER` fraction past them); `pub fn double_tap(&self, focus: Point, viewport: Size)
    -> ZoomView` (from ~1.0 zoom to `DOUBLE_TAP_SCALE` centred on focus; otherwise back to `IDENTITY`);
    `pub fn settled(&self, fitted: Size, viewport: Size) -> ZoomView` (scale clamped to
    `[MIN_SCALE, MAX_SCALE]`, offset clamped so the scaled image covers the viewport on axes where it is
    larger and is centred where it is smaller); `pub fn max_offset(&self, fitted: Size, viewport: Size) ->
    Point`; `pub fn is_zoomed(&self) -> bool` (scale > 1.01) }`.
  - `pub fn dismiss_progress(dy: f32) -> f32` (0..1 for a downward drag at scale 1, used for fade and
    close when >= 1.0 on release) and `pub fn should_dismiss(dy: f32, velocity_px_s: f32) -> bool`
    (past `DISMISS_PX` or a fling > 1000 px/s down that travelled 24 px).
- Tests: fit for wide/tall/square/small images; pinch keeps the focus point fixed (map a content point
  before and after); pinch in then settle clamps to 5.0; pinch out below 1 settles to identity; pan
  past the edge rubber-bands and settles back to the edge; a centred non-zoomed image cannot pan;
  double-tap toggles in and back; dismiss thresholds (distance, fling, twitch, upward drag never).
- Acceptance: `cargo test -p rovsti_core zoom` passes, no app code changes.

### S2. Pure number formats: `rovsti_core::number_format`
- New `crates/rovsti_core/src/number_format.rs`, `pub mod number_format;`.
- `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum NumberForm { Display, E164, National, TelLink,
  VCard }` with `pub const ALL`, `pub fn label(self) -> &'static str` ("As shown", "International
  (E.164)", "National", "Phone link", "Contact card (vCard)").
- `pub fn infer_home_cc<'a>(numbers: impl IntoIterator<Item = &'a str>) -> Option<String>`: for pairs
  where a national number (`0…` or no `+`) and an international one `PhoneNumber::matches`, the country
  code is the international digits minus the national significant digits (trunk zero dropped); the most
  frequent code wins, ties to the shorter code; `None` without evidence. Bound the work (first 500
  numbers, dedup by canonical form).
- `pub fn render(raw: &str, form: NumberForm, home_cc: Option<&str>, name: Option<&str>) -> Option<String>`:
  `Display` = `call::format_number` (or trimmed raw); `E164` = `+<cc><nsn>` (already-`+` numbers as is,
  national needs `home_cc`, else `None`); `National` = trunk `0` + NSN grouped like `format_number`
  for `+90`, else digits; `TelLink` = `tel:` + E.164 when known else canonical digits; `VCard` = one
  `vcard::write` card with `FN` = name or the number and one `TEL;TYPE=CELL` (build a
  `contact_edit::ContactDraft`, check its constructor in `contact_edit.rs`). Short codes, USSD (`*#06#`)
  and private numbers: only `Display` and `VCard` (return `None` for the others).
- `pub fn available(raw: &str, home_cc: Option<&str>) -> Vec<(NumberForm, String)>` (drops `None` and
  duplicates of an earlier form's text, so a chooser never shows two equal lines).
- Tests: infer from `05551112233` + `+905551112233` gives `"90"`; conflicting evidence picks the most
  frequent; none gives `None`; each form for Turkish mobile national and international, a US `+1`
  number, a short code `112`, a USSD code, an unparsable name; vCard round-trips through `vcard::parse`
  with name and number; `available` dedups.
- Acceptance: `cargo test -p rovsti_core number_format` passes.

### S3. Pure Recents query and CSV: `rovsti_core::call_log_query`
- New `crates/rovsti_core/src/call_log_query.rs`, `pub mod call_log_query;`.
- `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)] pub enum RecentsFilter { #[default] All,
  Missed, Incoming, Outgoing, WhatsApp }` with `ALL` and `label()`; `pub fn keeps(self, row:
  &RecentsRow) -> bool` (Missed includes `CallType::Missed` and declined/rejected-as-missed per
  `CallType`'s variants in `call_log.rs`; WhatsApp = `row.whatsapp`).
- `pub struct Query { pub text: String, pub filter: RecentsFilter }`, `pub fn matches(row: &RecentsRow,
  text: &str) -> bool`: empty text matches; case- and accent-insensitive substring on `name` (reuse the
  folding in `search.rs`/`names.rs`, check its public fn); digits-only text (>= 2 digits) matches a
  substring of the row's digits with formatting and `+`/trunk zero ignored.
- `pub fn apply(rows: &[RecentsRow], q: &Query) -> Vec<usize>` (indices into `rows`, order kept) and
  `pub fn empty_text(q: &Query) -> String` ("No missed calls", "No calls match \"…\"").
- CSV: `pub fn to_csv(entries: &[CallLogEntry], extras: &[recents::ExtraCall], tz_offset_s: i32) ->
  String`: header `date,time,direction,name,number,duration_s,duration,channel`; newest first, both
  sources merged by date; ISO date `YYYY-MM-DD` and `HH:MM` local (reuse `call_log`'s civil-date helper,
  make it `pub(crate)`); RFC 4180 quoting (quote fields containing `,` `"` CR LF; double quotes);
  CSV-injection guard: a cell starting with `=`, `@`, tab or CR, or with `+`/`-` not followed by a digit,
  gets a leading `'`; CRLF line ends; blocked entries included with direction `blocked`. Also
  `pub fn csv_file_name(now_ms: i64, tz_offset_s: i32) -> String` = `rovsti-calls-YYYY-MM-DD.csv`.
- Tests: each filter on a mixed set; text search by name with accents/case, by national digits matching
  an international row, by 1 digit (no digit match, name only); CSV header, quoting, injection guard,
  `+90…` numbers stay unquoted and unprefixed, merge order with WhatsApp extras, empty log is header only.
- Acceptance: `cargo test -p rovsti_core call_log_query` passes.

### S4. Android glue: gallery save, image share, CSV export (`rovsti_android`)
- New `crates/rovsti_android/src/media.rs` (`pub mod media;` in `lib.rs`):
  `pub fn save_image_to_gallery(path: &str, display_name: &str, mime: &str) -> Result<bool>` (Kotlin
  `saveImageToGallery`) and `pub fn share_image(path: &str, mime: &str, title: &str) -> Result<bool>`
  (Kotlin `shareImage`). Desktop: `Error::Unsupported` via the existing non-android `bridge` stubs, plus a
  `#[cfg(all(test, not(target_os = "android")))]` test like `documents.rs`.
- `documents.rs`: `pub fn export_calls(name: &str, csv: &str) -> Result<bool>` (Kotlin
  `exportCallsDocument`, answer `document_result` op `export_calls`, mime `text/csv`); add to the desktop
  test.
- Kotlin: new `crates/rovsti_android/kotlin/dev/rovsti/bridge/MediaShare.kt` with
  `object MediaShare { fun saveToGallery(ctx, path, name, mime): Boolean }` (MediaStore.Images insert
  with `RELATIVE_PATH = Pictures/Rovsti`, `IS_PENDING` 1 then 0, stream copy, no permission at minSdk 34;
  delete the row on failure) and `class RovstiShareProvider : ContentProvider` serving files from
  `cache/share` only (safe-name check like `MmsFileProvider`, read-only, `getType` from extension).
  `shareImage` copies the file into `cache/share`, builds
  `content://${packageName}.rovstishare/<name>`, and starts `ACTION_SEND` with `EXTRA_STREAM`,
  `FLAG_GRANT_READ_URI_PERMISSION` through a chooser. Add `@JvmStatic` entries in `RovstiBridge.kt` next
  to `shareText`, and `exportCallsDocument` next to `exportContactsDocument` (reuse `Documents` with a
  text payload and op `export_calls`; read `Documents.kt` first).
- `apps/phone/platforms/android/app/src/main/AndroidManifest.xml`: register
  `dev.rovsti.bridge.RovstiShareProvider`, authority `${applicationId}.rovstishare`,
  `exported=false`, `grantUriPermissions=true`. Check how the ejected host copies the bridge Kotlin
  (docs/android.md) so the new file is included.
- `apps/phone/src/backend.rs`: thin wrappers returning `Outcome` like `share_text`:
  `save_to_gallery(path, name)`, `share_image(path)`, `export_calls(name, csv)`; and
  `pub fn full_photo(uri: &str) -> Option<(image::RgbaImage, PathBuf)>` using
  `providers::contact_photo(uri, FULL_PHOTO_SIZE)` with `FULL_PHOTO_SIZE = 1080` (desktop: `sample::photo`
  and write a PNG to the temp dir so save/share paths exist; save/share then report Unsupported).
- Acceptance: all checks green, including the aarch64 check of `rovsti_android`.

### S5. Photo viewer screen (`apps/phone/src/photo_viewer.rs`)
- Read `.claude/skills/liquid-glass-gpui/SKILL.md` first. New module, a full-screen overlay drawn above
  sheets (add a `viewer: Option<PhotoViewer>` field on `PhoneApp`, not a `SheetKind`, so the sheet stays
  underneath and returns on close).
- `struct PhotoViewer { title: SharedString, image: Option<Arc<RenderImage>>, file: Option<PathBuf>,
  natural: zoom::Size, view: ZoomView, drag: Option<(Point, f32 /*ms*/)>, dismiss_dy: f32 }`.
  Open: tapping the avatar in the Contact sheet header (`contacts.rs` around the `person_avatar` slot)
  and in the Number sheet when a photo is shown; only when `row_photo` has an image. Show the 128 px
  thumbnail at once, load `backend::full_photo` on the background executor and swap in.
- Gestures: `on_pinch` -> `ZoomView::pinch`; drag (mouse down/move/up as the incoming card does) ->
  `pan` when zoomed, else `dismiss_progress` with background fade; release -> `settled` animated with the
  app's spring (`Spring` from `rovsti_ui`/`liquid_glass_kit`, as used elsewhere in the app); double-tap
  via `click_count == 2` -> `double_tap` animated. Map the image with an absolute `img`/`RenderImage`
  element sized `fit * scale` and offset, inside a clipped full-size box.
- Chrome: top glass bar with Close (X) and title (contact name); bottom glass bar with Save and Share
  buttons. Save -> `backend::save_to_gallery` -> pill "Saved to Photos" / outcome `why_not("Couldn't save
  the photo")`; Share -> `backend::share_image` -> pill on failure. Back gesture/Escape closes. Chrome
  fades out on single tap (toggle).
- Tests (pure, in the module): open only with a photo; the drawn image rect for a view equals
  `fit*scale` centred plus offset (helper `image_rect(view, natural, viewport) -> (x, y, w, h)`).
- Acceptance: all checks green; a headless frame test in `perf_harness.rs` opens the viewer on a sample
  contact and asserts the image rect is inside the viewport at scale 1.

### S6. Recents search and filter chips
- `apps/phone/src/recents.rs`: add `query: call_log_query::Query` and `visible: Vec<usize>` on
  `RecentsView`; recompute `visible` in `rebuild_recents` and when the query changes (never per frame).
  All row lookups (`row_at`, `recents_hit`, `recents_rows`, `avatar_rect`, `row_count`) go through
  `visible`.
- UI: a search field under the Recents large title (reuse `field.rs`, the same widget the Contacts tab
  uses) and a horizontal chip row (All, Missed, Incoming, Outgoing, WhatsApp) as glass capsules per the
  skill's segmented recipe; the selected chip is filled. Empty result shows `empty_text` in
  `state_view`. Clear (x) in the field resets the text. The filter persists in `Prefs.ui` only if
  `UiState` already has a matching slot; otherwise it resets on launch (no schema change this round).
- Tests: `row_at` over a filtered list maps to the right underlying row; switching filters keeps the
  scroll at top.
- Acceptance: all checks green; `perf_harness` Recents scroll budget tests still pass.

### S7. Copy as / Share as chooser and CSV export
- `actions_ui.rs`: long-press (existing hold machinery) or a second tap target on Copy and Share opens a
  small chooser sheet listing `number_format::available(raw, home_cc)` lines (label + preview text).
  Tap copies (`telecom::copy_text`, pill "Copied <label>") or shares (`backend::share_text`). `home_cc`
  is computed once per call-log snapshot with `infer_home_cc` over `CallLog.source` numbers and stored on
  the Backend `CallLog` (`pub home_cc: Option<String>`). The plain tap keeps today's behaviour.
- Settings > Backup (`backup_ui.rs`): new row "Export call history (CSV)". Builds the CSV off the UI thread
  from `CallLog.source` plus the WhatsApp extras, calls `backend::export_calls(csv_file_name(..), &csv)`;
  handle `DocumentOp::ExportCalls` in the `document_result` hand-back with pills "Call history exported"
  / "Export cancelled" / "Couldn't export". Disabled with a note when the call log is not loaded.
- Tests: chooser ids round-trip like `ids_keep_the_router_prefixes_and_round_trip`; the export row text.
- Acceptance: all checks green.

### S8. Keypad pause/wait inserts, docs and verification
- `dial.rs`: `pub fn long_press_star(field: &mut String) -> bool` and `long_press_hash` replace a just
  typed trailing `*`/`#` with `,`/`;` (same rules as `long_press_zero`: only the last char, only once, not
  as the first character); tests mirroring the zero tests. `keypad.rs`: arm holds on `*` and `#` with the
  same `LONG_ZERO` delay, new `HoldAction::Pause`/`HoldAction::Wait`, haptic `LongPress`, and pill
  "Pause added" / "Wait added" once per session; make sure `validate` and the number display accept them
  (`dial::is_dial_char` already allows `,` `;`).
- Docs: `docs/phone-acceptance.md` sections for the viewer, save/share, search/filters, Copy as, CSV
  export, pause/wait; `docs/android.md` the new Kotlin `MediaShare.kt` and provider. Update this file's
  backlog statuses to done.
- Run the full checks. Acceptance: all green, working tree clean after the commit.
