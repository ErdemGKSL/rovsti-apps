# Headless screenshots

Every app (Phone, Messages, Contacts, Stops) can render its real screens to PNG files
without a device or a window system. Real GPUI runs on the test platform and
`gpui_wgpu` draws it offscreen (lavapipe, the software Vulkan driver, is enough). Use the
shots to check a layout change, and to compare before and after.

The shots are test-only. They sit behind the cargo feature `shots` and every test is
`#[ignore]`, so `cargo test --workspace` neither compiles nor runs them.

## Commands

One test per app. Run from the workspace root:

```
cargo test -p rovsti_phone    --features shots shots -- --ignored --nocapture
cargo test -p rovsti_messages --features shots shots -- --ignored --nocapture
cargo test -p rovsti_contacts --features shots shots -- --ignored --nocapture
cargo test -p rovsti_stops    --features shots shots -- --ignored --nocapture
```

The shared rig has a smoke test and the text-field state gallery:

```
cargo test -p rovsti_ui --features shots shots -- --ignored --nocapture   # smoke + fields
cargo test -p rovsti_ui --features shots fields -- --ignored --nocapture  # fields only
cargo test -p rovsti_ui --features shots field_touch -- --ignored --nocapture  # handles + edit menu
```

Each app test takes under a minute once built (about 0.5 to 2.5 s per shot). The first
build with the feature compiles `gpui_wgpu` and `image`, so it is slow and needs disk
space; run `df -h .` first and delete `target/debug/incremental` if it is low.

Lint the feature-gated code too (the normal clippy run does not see it):

```
cargo clippy -p rovsti_phone -p rovsti_messages -p rovsti_contacts -p rovsti_stops \
  -p rovsti_ui --features shots --all-targets -- -D warnings
```

(With cargo's `--features` on several packages, use `rovsti_ui/shots` or run one package
at a time if your cargo version rejects the combined form:
`cargo clippy -p rovsti_phone --features shots --all-targets -- -D warnings`.)

## Output

Files go to `target/shots/<app>/` (or `$CARGO_TARGET_DIR/shots/<app>/`):

```
<NN>-<screen>-<dark|light>.png          412x915 dp, scale 2 = 824x1830 px
<NN>-<screen>-dark-small.png            360x640 dp = 720x1280 px, the first screen only
contact_sheet.png                       every shot of the app in a grid, 1200 px wide
```

`NN` is the scene number, so a directory listing is in screen order. The test prints each
path as it is written. `target/shots/rovsti_ui/` holds `00-smoke-*` and `01-fields-*`.

| App | Scenes | Files |
| --- | --- | --- |
| phone | 13 | 27 + sheet |
| messages | 13 | 27 + sheet |
| contacts | 13 | 27 + sheet |
| stops | 12 | 25 + sheet |

Committed baselines of the contact sheets live in `docs/screenshots/baseline/<app>.png`
(downscaled to at most 1200 px wide, under 1.5 MB each; messages is 1000 px wide to fit the size). They are the "before" picture for a layout round: render
again, open `contact_sheet.png` next to the baseline, then open single shots for detail.
Refresh a baseline only when the layout was changed on purpose.

## Isolation and determinism

- **Data dir.** `isolate_data_dir(app)` wipes `target/shots/.data/<app>/` and points
  `XDG_DATA_HOME` at it, so prefs and caches never come from the developer's profile.
  Phone, for example, otherwise opens on the tab stored in the real profile. Messages,
  Contacts and Stops seed their fixtures into this directory before the app starts.
- **Time zone.** The same call sets `TZ=Europe/Istanbul`.
- **Clock.** Rendered times come from `rovsti_core::clock::now_ms()`. The tests fix it to
  `2026-11-07T15:40:00+03:00` (`SHOT_NOW_MS`), so "Yesterday", "15:32" and relative times
  are identical on every run. Stops moves the clock on purpose for the running trip.
- **Animation.** The rig uses a virtual 60 fps clock (`FrameClock::set_fixed_step`).
  `Rig::settle()` draws and advances it until two captures in a row are equal (at most 240
  frames), so springs and sheets are at rest when the PNG is written.
- **Glass.** `force_backdrop_support(true)` makes the glass paint on the test platform.
- Fixtures, clock and animation are fixed, so reruns on the same machine should match; if a diff shows, suspect wall-clock data in a new fixture.

## Pitfalls

- **Retry.** The offscreen renderer can fail with "requested a larger instance buffer;
  retry the frame" (the Stops map). The rig retries up to 4 times when the error text
  contains `retry`. If you see that error in a new helper, call through the rig, not the
  renderer.
- **Theme override.** `TestWindow` always reports Light. The theme comes from
  `rovsti_ui::set_theme_override`, which `Rig::open` and `rig.theme()` set. An app that
  sets the override itself overwrites it. Stops does (from `prefs.appearance`), so its
  test seeds `appearance` in the prefs file and the rig sets the theme again after
  construction. If a dark shot comes out light, look for this.
- **Offline tiles.** Nothing may use the network. The Stops prefs point the tile server at
  `http://127.0.0.1:9/{z}/{x}/{y}.pbf` (connection refused), so the map shows its offline
  placeholder. `target/shots/.data/stops/` must not contain downloaded tiles afterwards.
  Messages talks to a loopback fake gateway (`apps/messages/src/shots/gateway.rs`) for the
  WhatsApp scenes.
- **One test per app.** Env vars (`XDG_DATA_HOME`, `TZ`, `ROVSTI_PREVIEW_*`) are
  process-global and are read once. Do not add a second `#[test]` that renders; add a
  scene instead. Drive state by calling the app's `pub(crate)` methods in the scene, not
  with env vars (`ROVSTI_PREVIEW_DIAL` and friends are not re-read).
- **Phone devtools.** Phone on desktop draws a devtools chip strip and a "Preview data"
  caption. The shots set `PhoneApp::devtools_visible = false`.
- **Focus.** GPUI has one focused view per window. A scene that shows a focused field must
  call `window.focus(&handle, cx)`; two focused rows in one shot need two renders (the
  `fields` gallery composes them).
- **Disk.** Shots build a second feature set of every crate. If `df -h .` shows under 6 GB
  free, delete `target/debug/incremental` and old test binaries first.

## Known renderer differences

- **Fonts.** The tests use `CosmicTextSystem` with Noto Sans. The device uses the system
  font, so line breaks and widths differ slightly. Check truncation on a device before
  tuning pixels.
- **Scale.** `TestWindow` has a fixed scale factor of 2.0. A device at 2.6 or 3.0 draws
  the same dp layout with finer lines. There is no way to test other scales here.
- **Glass.** Lavapipe renders the refraction and blur shaders, but edges can differ in the
  last pixel from a GPU. Do not tune rim or highlight alpha from a shot alone.
- **Backdrop wash.** In dark mode the accent wash from `components::backdrop` (a 160 deg
  gradient, alpha 0.28 fading to 0 at stop 0.55) shows a diagonal band at about 500 dp.
  This is the gradient itself (a linear ramp that ends abruptly, a Mach band), not
  lavapipe: a device on a dark background draws the same. Treat it as a design issue, not
  a harness artifact.
- **Window insets.** There is no status bar, no navigation bar and no keyboard. Insets are
  the app's own defaults. The soft keyboard is not shown, so check composer and field
  layouts with the keyboard on a device.
- **Platform code.** Desktop backends and sample data fill the screens (the Android
  paths return `Error::Unsupported` there). Maps show the offline placeholder, SMS search
  says it is not available, and photos exist only where the samples have them.

## Adding a scene

Each app has `src/shots.rs` with a `SCENES` table of `(number, name, closure)`. The
closure gets the settled app and brings it to the state to shoot.

1. Add a line at the end of `SCENES` and raise the array length:

   ```rust
   (14, "my-screen", |app, window, cx| {
       app.select_tab(RECENTS_TAB, cx);
       app.open_my_sheet(window, cx);
   }),
   ```

   Use the next number so the file order stays the screen order. Names are lower-case with
   dashes and unique (a unit test checks both, and that numbers ascend).
2. Reach the state through `pub(crate)` app methods, not env vars. If the state needs a
   method that is private, make it `pub(crate)`; keep it free of shot-only branches.
3. Need data? Put it in the app's fixture (Messages: `backend/preview/shots_data.rs`,
   Contacts: `sample.rs`, Stops: the `fixture` module in `shots.rs`, seeded into the
   isolated data dir). Fixtures must be deterministic: use `SHOT_NOW_MS`, never
   `SystemTime::now()`. Pure logic belongs in `rovsti_core` / `rovsti_geo` with unit tests.
4. Run the app's command above, then open the new `NN-my-screen-dark.png` and `-light.png`
   with an image viewer. A blank, white or test-pattern file means the scene failed.
5. Run the feature-gated clippy command. Update the scene counts in the table above and, if
   the layout was meant to change, the baseline sheet.

A new text-field state goes into the `STATES` table in
`crates/rovsti_ui/src/shots/fields.rs`.

## Pure helpers

`file_name`, `sheet_layout` and `retryable` in `crates/rovsti_ui/src/shots.rs` are plain
functions with unit tests (`cargo test -p rovsti_ui --features shots`). Each app also has a
test that checks its scene table (numbering, unique names).
