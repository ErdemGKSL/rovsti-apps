# After Epic 12 (desktop evidence, reasoning for the phone)

Same harness and settings as `baseline.md` (release, `opt-level=3` everywhere, LTO off).

| Scenario | Frames | build p50 | build p95 | build max | allocs p50 | allocs p95 | KiB p50 | view cache hit |
|---|---|---|---|---|---|---|---|---|
| tab | 1131 | 0.84 | 1.06 | 1.72 | 1385 | 1768 | 574 | 0% (the bar moves the whole time) |
| recents (500 rows) | 640 | 1.27 | 1.43 | 2.61 | 2491 | 2630 | 1247 | 94% |
| keypad | 676 | 1.03 | 2.38 | 2.98 | 1443 | 1546 | 574 | 90% |
| incall | 364 | 0.33 | 0.78 | 0.90 | 759 | 1751 | 334 | 76% |
| incoming | 235 | 0.42 | 1.20 | 1.76 | 725 | 2176 | 304 | 55% |

Honest reading: scene-build CPU time did not change beyond noise (about 0.1 ms) on the desktop; the
frame was already cheap there (p50 0.3 to 1.3 ms). Allocations per frame fell 4.6% (recents) and 6.8%
(keypad); the Tab Bar is replayed instead of rebuilt in 90 to 94% of the frames of the scroll and key
scenarios. The big change is on the GPU side (below), which the desktop CPU numbers cannot show.
Whether the phone meets the 4 ms / one-frame targets is story 12.7.

## 12.1 Perf feature and `RovstiPerf`

* `rovsti_ui::perf::Recorder`: per root render it reads `Window::frame_diagnostics()` (enabled once
  with `Recorder::enable`), pushes `build_time` into a preallocated ring (`rovsti_core::perf::FrameStats`),
  counts view-cache hits/misses, renders and animation requests, and logs ONE report line when the
  scenario changes. Zero allocation per frame (test `observing_a_frame_does_not_allocate`).
* Thermal state: `App::on_thermal_state_change` feeds `perf::set_thermal`; it is printed, never used
  to change what is drawn.
* Off in release: the recorder field, the runner and the thermal hook only exist with the `perf`
  feature (the harness uses them in tests).
* Fixed a false "slow frames" report: the display period was "the shortest frame seen", so one
  squeezed frame (4.8 ms) made every normal frame look 3x slow. It now follows a faster display only
  after 4 short frames in a row (test `a_lone_short_frame_does_not_move_the_budget`).

## 12.2 Scenarios

Six scripted scenarios (`perf_run.rs`), played through the same code as a tap, on the phone (trigger:
`adb shell setprop debug.rovsti.perf <name>:<n>`, read through `__system_property_get`, no JNI) and on
the desktop (`ROVSTI_PERF=tab,keypad`). `scripts/perf-run.sh` drives them and captures logcat and
`dumpsys SurfaceFlinger --latency` of the GpuiView layer. The incall and incoming scenarios use the
fake call walker (never Telecom). Baseline numbers: `baseline.md`.

## 12.3 Idle discipline and view caching (audit)

* Idle: `idle_draws_no_frames_on_any_tab` runs 30 s of virtual time on all four tabs and requires
  0 frames. Timers that exist: the call timer (one wake per second, only with a live call on screen
  and on the whole second), the Return-to-call pill text (1 s, only while the pill shows), the
  Recents "seen" timer (one shot, 1 s), the setup restore timeout (one shot, 400 ms), long-press
  timers (one shot). No timer runs on an idle tab.
* `cx.notify()` audit, per source:
  * Springs notify nothing: `Motion::frame` asks for the next animation frame only while a spring is
    moving and stops asking (`requested() == false`) when everything settles.
  * `refresh_pill_text` notifies only when the text changed; `Recents` live labels refresh when the tab
    opens (more than 60 s old), not on a timer.
  * Provider pulls (`call_log_changed`) no longer replace the snapshot or notify when the pulled
    entries equal the held ones (`same_call_log`): no row-model rebuild, no frame.
  * Kotlin events are already debounced 300 ms (`ProviderObservers`); calls are pushed only when the
    Telecom state changes.
  * No JNI call runs per frame or per second: `prefers_reduced_motion` is a cached cell, the
    Xiaomi probe runs once, `incoming_frame` once per call.
* Cached views: the Tab Bar is its own view (`TabBarView`, `Entity::cached`), synced once per render
  and notified only when a drawn number or label changed. Replayed in 90 to 94% of the frames of the
  key and scroll scenarios. The checklist, list rows and keypad cannot be cached usefully: a row's
  bounds change on every scroll frame, a key is always one of the moving things while typing, and the
  Setup sheet is only on screen while it moves. The hit rate is in every report (`view_cache`).
* Setup checklist: `derive` is fixed-size, nothing to cache.

## 12.4 Allocation discipline

* Zero-allocation tests (counting allocator in the test binary): springs, `Presses`, `Entrance`, the
  Tab Bar tick and press lookups; timer text and press ids (stack `SmallText`); `FrameStats`;
  `Recorder::observe`.
* Fixed per-frame allocations: Favorites tiles formatted `Call <name>` per tile per frame (now
  built once with the row); Setup sheet ids, row ids and spoken labels formatted per item per frame
  while the sheet moves (now interned); Setup step text built per frame (static text when there is
  no tail); press ids for list rows formatted per frame (stack text); `Presses` kept every spring
  ever pressed and ticked them all forever (now forgotten once at rest).
* Budget guard: `a_keypad_frame_stays_inside_its_allocation_budget` (p50 1800, p95 1900 in the debug
  test build; measured 1633; the release build makes about 12% fewer).
* What is left per frame is gpui's own element, layout and text work (about 100 allocations per
  list row from `div()`, taffy and the text system): reducing it means fewer elements, which would
  change the design.
* `build_time` p95 of S-scroll-recents: 1.34 ms before, 1.43 ms after; within noise (the table
  above), not worse beyond it.

## 12.5 Lists and photos

* `recents_builds_only_the_rows_on_screen`: 500 entries, scrolled to four places, 1 to 24 rows built
  per frame (about 12 fit). Heights are fixed (`ROW_H` 58 for Recents and Contacts; Favorites rows
  are fixed too). `a_refresh_keeps_the_scroll_offset`: a refresh moves the offset only by whole rows
  that came in above (anchoring), never jumps.
* Photos: `the_photo_cache_stays_at_64_and_decoding_is_off_the_caller`: 5000 photos pushed through
  the cache leave 64; `want_photos` returns before anything is decoded (the work is queued on the
  background executor). The LRU insert scans 64 entries once per decoded photo, never per frame.
* StrictMode: `RovstiPhoneApp` turns on `ThreadPolicy.detectAll().penaltyLog()` for debuggable builds
  (not the release). All provider and photo reads already run on the background executor.

## 12.6 Glass pass cost

What the renderer does per backdrop effect (gpui-fusion `wgpu_renderer::draw_backdrop_blurs`): ends
the main render pass, copies the backdrop region, blurs it (2 passes at half resolution, scissored
to the effect bounds plus 2 x radius), composites, and starts a new main pass. 12 keys meant 12 of
those sequences per frame (48 render passes and 12 copies; each main-pass break is a tile flush on a mobile GPU).

Done in `liquid_glass_kit` (kit tests: `shader_validates` and `glass_snapshot` unchanged and green,
plus new `glass_grid`):

* One grid effect for the dial pads (`paint_glass_grid`, `glass_grid_shader`): the unpressed keys of
  the 12-key pad (main and in-call) are one backdrop effect with one copy, one blur and one
  composite. A key that is pressed or springing back is left out of the grid (skip mask) and paints
  its own glass with the press glow, exactly as before. Pixel-equivalent (tolerance 2/255, both
  themes) to 12 single effects; the skipped-cell case equals the old paint; in the app the pad area
  of a window crop is identical to the old path (0 differing pixels).
* Rest variant (`glass_shader_rest`): the press-glow branch compiled out for surfaces with
  `press == 0`; `paint_glass` picks it automatically. Identical pixels (test), the same cost on the
  desktop GPU, less shader code to run per fragment on a phone.
* Pipelines compile in the first frame (`warm_up_layer`: three one-pixel effects outside the window,
  once), while only the backdrop shows, instead of at the first touch of a glass control.
* Bench (desktop GPU, release, `pad_cost`): glass cost over the background, 12 single effects
  0.58 to 0.65 ms, one grid effect 0.05 to 0.07 ms, about 9 to 11 times less. A tile-based phone GPU
  should gain more (11 fewer main-pass restarts and 33 fewer render passes per frame on the keypad).
* Not possible inside this repo: skipping the blur passes for `blur == 0` surfaces (the lens, the
  pressed puck) and an instance list in the renderer itself are changes to
  `gpui_wgpu::wgpu_renderer` (pinned fork). The copy and blur are already scissored to the bounds
  plus 2 x radius there. The in-call control row (3 + 1 buttons) and the Tab Bar (bar plus puck, the
  puck samples the bar) cannot share an effect pixel-exactly (per-control state specs; nested
  glass), so they keep their own.

## Release profile

`rovsti_phone` itself (the app's per-frame element building) and the crates that run on every frame
that changes (taffy: layout; cosmic-text, harfrust, rustybuzz: text shaping; swash, zeno: glyph
rasterisation; tiny-skia: icon rasterisation) are added to the `opt-level = 3` list (the rest stays
`s`; `panic = "unwind"` is unchanged). Desktop harness, LTO off, the Cargo.toml profile:

| | tab p50 / p95 | recents p50 / p95 | keypad p50 / p95 | incall p50 / p95 | incoming p50 / p95 |
|---|---|---|---|---|---|
| before (`s` + gpui, kit, ui, core, android at 3) | 0.91 / 1.14 | 1.41 / 1.53 | 1.15 / 2.58 | 0.36 / 0.89 | 0.38 / 1.33 |
| + rovsti_phone | 0.89 / 1.08 | 1.39 / 1.73 | 1.16 / 2.61 | 0.35 / 0.88 | 0.36 / 1.30 |
| + taffy, cosmic-text, harfrust, rustybuzz, swash, zeno, tiny-skia | 0.83 / 0.97 | 1.36 / 1.44 | 1.11 / 2.52 | 0.34 / 0.85 | 0.35 / 1.27 |

4 to 9% less scene-build time on the first frames of each scenario; `rovsti_phone` alone is within
noise but is the app's own per-frame code, which the file's own comment says keeps full optimisation.
Size cost (signed release, `librovsti_phone.so` / APK): 15,486,824 / 22,205,659 bytes before, 16,405,144 / 23,123,979 bytes after (+0.92 MB, +5.9% / +4.1%); part of it is the new glass variants, the Tab Bar view and the tests-free runtime code, most of it the opt-level 3 crates.
