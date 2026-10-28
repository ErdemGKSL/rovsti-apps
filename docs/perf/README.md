# Rovsti Phone performance (Epic 12)

Performance comes from cheaper code, never from fewer effects (AD-21). Every glass
surface, refraction, dispersion and all 16 signature moments stay.

## What exists

| Piece | Where |
|---|---|
| Frame recorder (`RovstiPerf` reports) | `rovsti_ui::perf::Recorder`, Cargo feature `perf` on `rovsti_phone` |
| Scripted scenarios (tab, recents, keypad, incall, incoming, idle) | `apps/phone/src/perf_run.rs` |
| Phone driver: sets the trigger, collects logcat and SurfaceFlinger | `scripts/perf-run.sh` |
| Perf APK (release profile, `perf` feature, debug key) | `scripts/perf-build.sh` |
| Same scenarios headless on the desktop (CPU build time, allocations, cached views) | `apps/phone/src/perf_harness.rs` |
| Glass pixel tests and the glass timing bench | `crates/liquid_glass_kit/tests/` |

The signed release APK has none of the recorder: the `perf` feature is off, and
`debug.rovsti.perf` is never read.

## Measuring on the phone (story 12.7, DEVICE)

1. Build and install the perf APK (release code, `perf` on, debug-signed; it is for
   measuring only and is never handed over):

   ```bash
   export ANDROID_HOME=$HOME/Android/Sdk ANDROID_NDK_HOME=$ANDROID_HOME/ndk/30.0.16248370
   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
   scripts/perf-build.sh                      # prints the APK path
   adb install -r apps/phone/platforms/android/app/build/outputs/apk/debug/app-debug.apk
   ```

   (It is the same as `cd apps/phone && gpuiforge build android --abi arm64-v8a --rust-profile release`
   with `default = ["perf"]` added to `apps/phone/Cargo.toml` for the build; the script does that and
   restores the file.)
2. Open Rovsti Phone, unlock the phone, leave the screen on, plug the cable. Optionally pin the
   refresh rate you want to judge (Settings, Display, Smooth display), and let the phone cool for a
   minute: the thermal state is in every report.
3. Run the scenarios:

   ```bash
   scripts/perf-run.sh                  # tab, recents, keypad, incall, incoming, idle
   OUT=docs/perf/runs/after-glass scripts/perf-run.sh keypad incall
   ```

   By hand, the same thing is:

   ```bash
   adb logcat -c
   adb shell setprop debug.rovsti.perf "tab:1"      # any of tab recents keypad incall incoming idle all
   adb logcat -s RovstiPerf                          # one `report scenario=...` line when it ends
   adb shell dumpsys SurfaceFlinger --list | grep -i GpuiView
   adb shell dumpsys SurfaceFlinger --latency "<the GpuiView layer name>"
   adb shell setprop debug.rovsti.perf ""
   ```

   The number after the colon only has to change to run the same scenario again.
4. The scripts write `docs/perf/runs/<timestamp>/summary.md`. Copy the numbers into the tables
   below and into `docs/perf/final.md`.
5. Cold start and incoming times (not scripted):
   `adb shell am force-stop dev.rovsti.phone.perf; adb shell am start -W -n dev.rovsti.phone.perf/dev.rovsti.phone.MainActivity`
   (the perf build has the application ID `dev.rovsti.phone.perf`, so it installs next to the release)
   (`TotalTime` is cold start to first frame); the ring latency is the `RovstiPerf` line the
   Incoming screen already logs (stories 5.6, 5.7), warm and with the app killed.
6. StrictMode: a debuggable build logs violations under tag `StrictMode`
   (`adb logcat -s StrictMode`). There must be none from `RovstiBridge`, the providers or photos.

### Reading a report

```
report scenario=S-keypad renders=683 requested=681 hz=60 budget_ms=16.6 build_ms p50=.. p95=.. p99=.. max=..
over=.. over2x=.. interval_ms p50=.. p95=.. p99=.. max=.. over=.. over2x=.. view_cache hits=.. misses=.. thermal=nominal
```

* `build_ms` is CPU time to build one frame (render, layout, prepaint, paint). The 4 ms target and
  "p95 inside one display frame" are judged on it. `over`/`over2x` count frames over one and two
  display periods.
* `interval_ms` is the gap between animation frames (pacing, what the eye sees); `over` counts gaps
  over 1.5 periods, `over2x` over 2 periods.
* `renders` is how many frames the app produced; for `idle` it must be `0`. `requested` is how many
  of them asked for another animation frame.
* `view_cache` counts cached-view decisions (the Tab Bar is one); `thermal` is the OS thermal state.
* `budget_ms`/`hz` is the median animation gap snapped to 60, 90 or 120 Hz.
* GPU pacing: the SurfaceFlinger summary line (presented gaps over 1.5 periods) is the cross-check;
  `build_ms` cannot see the GPU.

### Targets (AD-21) and where to record them

| Target | Value | Measured by |
|---|---|---|
| p95 `build_ms` inside one display frame | 16.6 / 11.1 / 8.3 ms at 60 / 90 / 120 Hz | `RovstiPerf` `build_ms p95` |
| CPU frame work | under 4 ms | `build_ms p50` and `p95` |
| Frames over budget | under 1%, none over 2x in tab, scroll, key, call flows | `over`, `over2x`, SurfaceFlinger |
| Idle | 0 frames in 30 s on each tab | `idle` report, `renders=0` |
| Cold start to first interactive frame | under 1.2 s | `am start -W` TotalTime |
| Warm / cold incoming ring | under 1.5 s / 3 s | Incoming `RovstiPerf` line |

### Phone table (fill in; story 12.7 stays in backlog until this is done)

Device: ____  Android/HyperOS: ____  refresh: ____ Hz  thermal at start: ____

| Scenario | renders | build p50 | build p95 | build max | over / over2x | interval p95 | SF gaps over 1.5 periods | view cache hits / misses |
|---|---|---|---|---|---|---|---|---|
| S-tab | | | | | | | | |
| S-scroll-recents | | | | | | | | |
| S-keypad | | | | | | | | |
| S-incall | | | | | | | | |
| S-incoming | | | | | | | | |
| idle (30 s) | | | | | | | | |

Cold start (am start -W TotalTime): ____ ms. Incoming warm: ____ ms. Incoming cold: ____ ms.

A missed target is a blocker with the next measure from the AD-21 order (idle and re-render, then
allocations, then lists, then glass); it is never closed by removing an effect.

## Desktop numbers (this repo, no phone needed)

`docs/perf/baseline.md` holds the numbers before Epic 12 and `docs/perf/after-epic-12.md` after,
measured with the headless harness and the glass bench:

```bash
CARGO_PROFILE_RELEASE_LTO=off CARGO_PROFILE_RELEASE_OPT_LEVEL=3 \
  cargo test --release -p rovsti_phone perf_harness::tests::measure -- --ignored --nocapture
ALLOC_SCENARIO=recents cargo test --release -p rovsti_phone perf_harness::tests::alloc_sites -- --ignored --nocapture
cargo test --release -p liquid_glass_kit --test glass_grid -- --ignored --nocapture pad_cost
```

A desktop CPU is not the phone's: milliseconds only compare runs on the same machine; allocation
counts, render counts and glass pass counts carry over.
