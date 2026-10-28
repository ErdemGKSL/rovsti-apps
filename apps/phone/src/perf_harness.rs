//! Headless measurements of the real `PhoneApp` (story 12.1 to 12.6).
//!
//! The whole app runs on GPUI's test platform with real text shaping and the
//! real icon assets, driven by the same scripted scenarios the phone runs
//! (`perf_run`), on a virtual 60 fps clock. Per drawn frame it records the
//! scene build time (render, layout, prepaint, paint: everything the CPU does
//! before the GPU gets the scene), the view-cache decisions, and how many heap
//! allocations the frame made (a counting global allocator, this test binary
//! only).
//!
//! Numbers for the docs:
//!
//!   CARGO_PROFILE_RELEASE_LTO=off CARGO_PROFILE_RELEASE_OPT_LEVEL=3 \
//!     cargo test --release -p rovsti_phone perf_harness::measure -- --ignored --nocapture
//!
//! A desktop CPU is not the phone's, so absolute milliseconds only compare
//! runs on the same machine; allocation and render counts transfer.

use std::{
    alloc::{GlobalAlloc, Layout, System},
    cell::Cell,
    rc::Rc,
    sync::Arc,
    time::Duration,
};

use futures::channel::mpsc;
use gpui::{AnyWindowHandle, AppContext as _, HeadlessAppContext, WindowHandle, px, size};
use gpui_wgpu::CosmicTextSystem;
use rovsti_ui::{IconAssets, perf::Recorder, spring::FrameClock};

use crate::{
    PhoneApp,
    backend::Backend,
    perf_run::{Action, Step, script},
};

// ---- Counting allocator ---------------------------------------------------

thread_local! {
    static ALLOCS: Cell<u64> = const { Cell::new(0) };
    static BYTES: Cell<u64> = const { Cell::new(0) };
    /// Allocation profiling: sites of allocations of at least `PROFILE_MIN`
    /// bytes (and the running recursion guard).
    static PROFILE_ON: Cell<bool> = const { Cell::new(false) };
    static IN_PROFILE: Cell<bool> = const { Cell::new(false) };
    static SAMPLE_N: Cell<u64> = const { Cell::new(0) };
    static SITES: std::cell::RefCell<Vec<(usize, String)>> =
        const { std::cell::RefCell::new(Vec::new()) };
}

/// Smallest allocation the profiler records.
const PROFILE_MIN: usize = 2048;

fn profile_site(size: usize) {
    if !PROFILE_ON.with(Cell::get) {
        return;
    }
    // Large allocations are all recorded; small ones every 16th (the
    // backtrace is slow), so the counts below are scaled by 16.
    if size < PROFILE_MIN {
        let n = SAMPLE_N.with(|c| {
            c.set(c.get() + 1);
            c.get()
        });
        if !n.is_multiple_of(16) {
            return;
        }
    }
    if IN_PROFILE.with(|g| g.replace(true)) {
        return;
    }
    let trace = std::backtrace::Backtrace::force_capture().to_string();
    // Keep the first few frames outside the allocator and the std plumbing.
    let mut lines = Vec::new();
    for line in trace.lines() {
        let line = line.trim();
        let Some((_, name)) = line.split_once(": ") else {
            continue;
        };
        let skip = [
            "alloc::",
            "core::",
            "std::",
            "backtrace",
            "perf_harness",
            "__rust",
            "<alloc",
        ];
        if skip.iter().any(|s| name.starts_with(s)) || name.starts_with("_") {
            continue;
        }
        lines.push(name.to_owned());
        if lines.len() == 4 {
            break;
        }
    }
    SITES.with(|s| s.borrow_mut().push((size, lines.join(" < "))));
    IN_PROFILE.with(|g| g.set(false));
}

struct Counting;

// SAFETY: forwards every call to the system allocator unchanged and only
// bumps thread-local counters (const-initialised, no destructor, so touching
// them inside the allocator cannot allocate or recurse).
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        profile_site(layout.size());
        ALLOCS.with(|c| c.set(c.get() + 1));
        BYTES.with(|c| c.set(c.get() + layout.size() as u64));
        // SAFETY: same contract as ours.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY: same contract as ours.
        unsafe { System.dealloc(ptr, layout) }
    }

    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        profile_site(new_size);
        ALLOCS.with(|c| c.set(c.get() + 1));
        BYTES.with(|c| c.set(c.get() + new_size as u64));
        // SAFETY: same contract as ours.
        unsafe { System.realloc(ptr, layout, new_size) }
    }
}

#[global_allocator]
static COUNTING: Counting = Counting;

fn counters() -> (u64, u64) {
    (ALLOCS.with(Cell::get), BYTES.with(Cell::get))
}

// ---- The rig --------------------------------------------------------------

/// One drawn frame.
#[derive(Clone, Copy, Debug, Default)]
pub struct Frame {
    /// Virtual time of the frame since the scenario began, in ms.
    pub at_ms: u32,
    pub build_ms: f32,
    pub allocs: u64,
    pub bytes: u64,
    pub hits: u64,
    pub misses: u64,
}

const FRAME: Duration = Duration::from_micros(16_667);

pub struct Rig {
    cx: HeadlessAppContext,
    window: WindowHandle<PhoneApp>,
    /// Something changed since the last frame (a notification on the app or
    /// the backend), so a platform would draw.
    dirty: Rc<Cell<bool>>,
    last_seq: u64,
}

impl Rig {
    pub fn new() -> Self {
        FrameClock::set_fixed_step(Some(FRAME));
        let mut cx = HeadlessAppContext::with_platform(
            Arc::new(CosmicTextSystem::new("Noto Sans")),
            Arc::new(IconAssets::new()),
            || None,
        );
        let (_tx, rx) = mpsc::unbounded::<String>();
        let dirty = Rc::new(Cell::new(true));
        let flag = Rc::clone(&dirty);
        let window = cx
            .open_window(size(px(402.0), px(874.0)), move |window, cx| {
                let backend = Backend::start(cx);
                let app = cx.new(|cx| PhoneApp::new(backend.clone(), rx, window, cx));
                let backend_flag = Rc::clone(&flag);
                cx.observe(&backend, move |_, _| backend_flag.set(true))
                    .detach();
                let flag = Rc::clone(&flag);
                cx.observe(&app, move |_, _| flag.set(true)).detach();
                app
            })
            .expect("open the test window");
        let mut rig = Self {
            cx,
            window,
            dirty,
            last_seq: 0,
        };
        let any: AnyWindowHandle = rig.window.into();
        rig.cx
            .update_window(any, |_, window, _| Recorder::enable(window))
            .expect("enable diagnostics");
        rig
    }

    fn any(&self) -> AnyWindowHandle {
        self.window.into()
    }

    /// Lets timers and tasks run for `d` of virtual time.
    pub fn advance(&mut self, d: Duration) {
        self.cx.run_until_parked();
        self.cx.advance_clock(d);
        self.cx.run_until_parked();
    }

    pub fn take_dirty(&self) -> bool {
        self.dirty.replace(false)
    }

    pub fn dirty(&self) -> bool {
        self.dirty.get()
    }

    /// Whether the app asked for another animation frame.
    pub fn animating(&mut self) -> bool {
        self.cx
            .update_window(self.any(), |view, _, cx| {
                view.downcast::<PhoneApp>()
                    .is_ok_and(|app| app.read(cx).motion.requested())
            })
            .unwrap_or(false)
    }

    /// Renders and paints one frame.
    pub fn draw(&mut self) -> Frame {
        let any = self.any();
        let before = counters();
        let mut frame = Frame::default();
        let last = &mut self.last_seq;
        self.cx
            .update_window(any, |_, window, cx| {
                window.draw(cx).clear();
                if let Some(d) = window.frame_diagnostics()
                    && d.sequence != *last
                {
                    *last = d.sequence;
                    frame.build_ms = d.build_time.as_secs_f32() * 1000.0;
                    frame.hits = d.view_cache.hits;
                    frame.misses = d.view_cache.misses;
                }
            })
            .expect("draw");
        let after = counters();
        frame.allocs = after.0 - before.0;
        frame.bytes = after.1 - before.1;
        frame
    }

    /// Runs `f` on the app with its window, like an event handler would.
    pub fn with_app<R>(
        &mut self,
        f: impl FnOnce(&mut PhoneApp, &mut gpui::Window, &mut gpui::Context<PhoneApp>) -> R,
    ) -> R {
        let mut out = None;
        self.cx
            .update_window(self.any(), |view, window, cx| {
                if let Ok(app) = view.downcast::<PhoneApp>() {
                    out = Some(app.update(cx, |app, cx| f(app, window, cx)));
                }
            })
            .expect("with_app");
        out.expect("the root view is the phone app")
    }

    pub fn apply(&mut self, action: Action) {
        self.cx
            .update_window(self.any(), |view, window, cx| {
                if let Ok(app) = view.downcast::<PhoneApp>() {
                    app.update(cx, |app, cx| app.perf_apply(action, window, cx));
                }
            })
            .expect("apply");
        self.dirty.set(true);
    }

    /// Draws frames until the app has been quiet for 2.5 s of virtual time
    /// (one-shot timers such as the Recents "seen" delay have fired by then).
    /// Returns the frames drawn.
    pub fn settle(&mut self) -> usize {
        let mut frames = 0;
        let mut quiet = 0;
        for _ in 0..1200 {
            self.advance(FRAME);
            if self.dirty() || self.animating() {
                self.take_dirty();
                self.draw();
                frames += 1;
                quiet = 0;
            } else {
                quiet += 1;
                if quiet >= 150 {
                    break;
                }
            }
        }
        frames
    }

    /// Plays a scenario on the virtual 60 fps clock: steps fire at their time,
    /// a frame is drawn whenever the app is dirty or animating.
    pub fn run(&mut self, steps: &[Step], total_ms: u32) -> Run {
        let mut run = Run::default();
        let mut next = 0;
        let ticks = (u64::from(total_ms) * 1000 / FRAME.as_micros() as u64) as u32;
        for tick in 0..ticks {
            let now_ms = (u64::from(tick) * FRAME.as_micros() as u64 / 1000) as u32;
            while next < steps.len() && steps[next].at_ms <= now_ms {
                self.apply(steps[next].action);
                next += 1;
            }
            self.advance(FRAME);
            if self.take_dirty() || self.animating() {
                let mut frame = self.draw();
                frame.at_ms = now_ms;
                run.frames.push(frame);
            } else {
                run.idle_ticks += 1;
            }
        }
        run
    }
}

/// What a scenario cost.
#[derive(Default)]
pub struct Run {
    pub frames: Vec<Frame>,
    /// Ticks (16.7 ms) in which no frame was needed.
    pub idle_ticks: u32,
}

impl Run {
    fn sorted(&self, key: impl Fn(&Frame) -> f32) -> Vec<f32> {
        let mut v: Vec<f32> = self.frames.iter().map(key).collect();
        v.sort_by(f32::total_cmp);
        v
    }

    pub fn percentile(&self, p: usize, key: impl Fn(&Frame) -> f32) -> f32 {
        let v = self.sorted(key);
        if v.is_empty() {
            return 0.0;
        }
        v[((v.len() * p).div_ceil(100)).clamp(1, v.len()) - 1]
    }

    pub fn build_p50(&self) -> f32 {
        self.percentile(50, |f| f.build_ms)
    }

    pub fn build_p95(&self) -> f32 {
        self.percentile(95, |f| f.build_ms)
    }

    pub fn build_max(&self) -> f32 {
        self.percentile(100, |f| f.build_ms)
    }

    pub fn allocs_p50(&self) -> f32 {
        self.percentile(50, |f| f.allocs as f32)
    }

    pub fn allocs_p95(&self) -> f32 {
        self.percentile(95, |f| f.allocs as f32)
    }

    pub fn kib_p50(&self) -> f32 {
        self.percentile(50, |f| f.bytes as f32 / 1024.0)
    }

    pub fn hit_percent(&self) -> Option<u32> {
        let hits: u64 = self.frames.iter().map(|f| f.hits).sum();
        let misses: u64 = self.frames.iter().map(|f| f.misses).sum();
        (hits + misses > 0).then(|| (hits * 100 / (hits + misses)) as u32)
    }
}

/// Runs one named scenario on a fresh app after a settled start.
pub fn measure_scenario(name: &str) -> Run {
    let mut rig = Rig::new();
    rig.settle();
    let script = script(name).expect("known scenario");
    rig.run(&script.steps, script.total_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEYPAD_ALLOCS_P50: f32 = 1_800.0;
    const KEYPAD_ALLOCS_P95: f32 = 1_900.0;
    const SCENARIOS: [&str; 5] = ["tab", "recents", "keypad", "incall", "incoming"];

    /// Prints the table for docs/perf (see the module docs for the command).
    #[test]
    #[ignore = "measurement, run on demand"]
    fn measure() {
        println!(
            "{:<10} {:>6} {:>8} {:>8} {:>8} {:>9} {:>9} {:>8} {:>6}",
            "scenario",
            "frames",
            "build50",
            "build95",
            "buildmax",
            "allocs50",
            "allocs95",
            "KiB50",
            "hit%"
        );
        for name in SCENARIOS {
            let run = measure_scenario(name);
            if std::env::var_os("TRACE").is_some() {
                let mut slow: Vec<_> = run.frames.iter().collect();
                slow.sort_by(|a, b| b.build_ms.total_cmp(&a.build_ms));
                for f in slow.iter().take(8) {
                    println!(
                        "  {name} slow frame at {} ms: {:.2} ms, {} allocs",
                        f.at_ms, f.build_ms, f.allocs
                    );
                }
            }
            println!(
                "{:<10} {:>6} {:>8.2} {:>8.2} {:>8.2} {:>9.0} {:>9.0} {:>8.1} {:>6}",
                name,
                run.frames.len(),
                run.build_p50(),
                run.build_p95(),
                run.build_max(),
                run.allocs_p50(),
                run.allocs_p95(),
                run.kib_p50(),
                run.hit_percent().map_or("-".into(), |h| h.to_string()),
            );
        }
    }

    /// Where the bytes go: the biggest allocation sites of a scenario's frames.
    #[test]
    #[ignore = "measurement, run on demand"]
    fn alloc_sites() {
        let name = std::env::var("ALLOC_SCENARIO").unwrap_or_else(|_| "recents".into());
        let mut rig = Rig::new();
        rig.settle();
        let script = script(&name).expect("known scenario");
        PROFILE_ON.with(|p| p.set(true));
        let run = rig.run(&script.steps, script.total_ms);
        PROFILE_ON.with(|p| p.set(false));
        let mut by_site: std::collections::HashMap<String, (usize, usize)> = Default::default();
        SITES.with(|s| {
            for (size, site) in s.borrow().iter() {
                let e = by_site.entry(site.clone()).or_default();
                let weight = if *size < PROFILE_MIN { 16 } else { 1 };
                e.0 += weight;
                e.1 += size * weight;
            }
        });
        let mut sites: Vec<_> = by_site.into_iter().collect();
        sites.sort_by_key(|(_, (n, _))| std::cmp::Reverse(*n));
        println!(
            "{} frames; allocations of {PROFILE_MIN}+ bytes per frame:",
            run.frames.len()
        );
        for (site, (n, bytes)) in sites.iter().take(24) {
            println!(
                "{:>7.1} KiB/frame {:>6.1} allocs/frame  {site}",
                *bytes as f32 / 1024.0 / run.frames.len() as f32,
                *n as f32 / run.frames.len() as f32
            );
        }
    }

    // ---- Story 12.4: allocation discipline --------------------------------

    /// Allocations made while running `f`.
    fn allocs_in(f: impl FnOnce()) -> u64 {
        let before = counters().0;
        f();
        counters().0 - before
    }

    #[test]
    fn springs_and_presses_do_not_allocate_per_frame() {
        use rovsti_ui::{
            motion::{Entrance, Presses},
            spring::Spring,
            tab_bar::{Tab, TabBar},
        };
        let dt = Duration::from_micros(16_667);
        let mut presses = Presses::default();
        presses.down("key-5");
        presses.down("key-6");
        let mut entrance = Entrance::default();
        entrance.restart();
        let mut spring = Spring::new(0.0, rovsti_ui::motion_tokens::press());
        spring.set_target(1.0);
        let mut bar =
            TabBar::new((0..4).map(|i| Tab::new(rovsti_ui::Icon::LucideStar, format!("t{i}"))));
        bar.select(3);
        let n = allocs_in(|| {
            for frame in 0..600 {
                presses.tick(dt);
                std::hint::black_box(presses.value("key-5") + presses.value("nope"));
                if frame == 300 {
                    presses.release_all();
                }
                entrance.tick(dt);
                spring.tick(dt);
                bar.tick(dt);
            }
        });
        assert_eq!(
            n, 0,
            "springs, presses and the tab bar allocated {n} times in 600 frames"
        );
    }

    #[test]
    fn timer_and_press_text_are_built_on_the_stack() {
        let n = allocs_in(|| {
            for i in 0..500 {
                let elapsed = rovsti_core::call::format_elapsed(1_000_000 + i * 1000, 1_000);
                std::hint::black_box(elapsed.as_str());
                let key = crate::recents::press_key(i as usize, crate::recents::Zone::Row);
                std::hint::black_box(key.as_str());
                let key = crate::contacts::list_key("ct-", i as usize);
                std::hint::black_box(key.as_str());
            }
        });
        assert_eq!(n, 0, "timer text and press ids allocated {n} times");
    }

    #[test]
    fn the_frame_ring_never_allocates() {
        let mut stats = rovsti_core::perf::FrameStats::default();
        let n = allocs_in(|| {
            for i in 0..5000 {
                stats.push(i as f32 * 0.001);
                if i % 700 == 0 {
                    std::hint::black_box(stats.summary());
                }
            }
        });
        assert_eq!(n, 0, "FrameStats allocated {n} times");
    }

    /// The recorder runs inside every perf-build frame.
    #[test]
    fn observing_a_frame_does_not_allocate() {
        let mut rig = Rig::new();
        rig.settle();
        rig.apply(Action::Tab(1));
        rig.advance(FRAME);
        rig.draw();
        let any = rig.any();
        let mut recorder = Recorder::default();
        let mut observed = 0;
        rig.cx
            .update_window(any, |_, window, _| {
                // The first observation may size nothing; the next ones must not.
                recorder.observe(window, rovsti_ui::perf::Scenario::Tab, true, FRAME);
                observed = allocs_in(|| {
                    for _ in 0..200 {
                        recorder.observe(window, rovsti_ui::perf::Scenario::Tab, true, FRAME);
                    }
                });
            })
            .expect("observe");
        assert_eq!(
            observed, 0,
            "Recorder::observe allocated {observed} times in 200 frames"
        );
    }

    /// A frame of the keypad scenario costs about this many allocations
    /// (measured in the debug test build: 1633 at p50; the release build
    /// makes about 12% fewer); a jump past the margin means somebody put
    /// a String, Vec or format! on the per-frame path.
    #[test]
    fn a_keypad_frame_stays_inside_its_allocation_budget() {
        let run = measure_scenario("keypad");
        let (p50, p95) = (run.allocs_p50(), run.allocs_p95());
        assert!(run.frames.len() > 300, "{} frames", run.frames.len());
        assert!(
            p50 <= KEYPAD_ALLOCS_P50,
            "p50 {p50} allocs per frame (budget {KEYPAD_ALLOCS_P50})"
        );
        assert!(
            p95 <= KEYPAD_ALLOCS_P95,
            "p95 {p95} allocs per frame (budget {KEYPAD_ALLOCS_P95})"
        );
    }

    // ---- Story 12.5: lists and photos ------------------------------------

    fn recents_with_500_rows() -> Rig {
        let mut rig = Rig::new();
        rig.settle();
        rig.apply(Action::Tab(1));
        rig.apply(Action::BigLog);
        rig.settle();
        rig
    }

    #[test]
    fn recents_builds_only_the_rows_on_screen() {
        let mut rig = recents_with_500_rows();
        for y in [0.0, 5_000.0, 14_321.0, 28_000.0] {
            rig.apply(Action::Scroll(y));
            crate::recents::ROWS_BUILT.with(|c| c.set(0));
            rig.draw();
            let built = crate::recents::ROWS_BUILT.with(std::cell::Cell::get);
            // 874 px tall window: about 12 rows fit under the title.
            assert!(
                (1..=24).contains(&built),
                "scrolled {y}: {built} rows built"
            );
        }
        let total = rig.with_app(|app, _, _| app.recents.row_count());
        assert!(total >= 450, "the 500-entry log groups into {total} rows");
    }

    #[test]
    fn rows_have_the_fixed_heights_the_list_relies_on() {
        assert_eq!(crate::recents::ROW_H, 58.0);
        assert_eq!(crate::contacts::list_row_height(), 58.0);
    }

    #[test]
    fn a_refresh_keeps_the_scroll_offset() {
        let mut rig = recents_with_500_rows();
        rig.apply(Action::Scroll(9_000.0));
        rig.draw();
        let before = rig.with_app(|app, _, _| app.recents.scrolled());
        assert!((before - 9_000.0).abs() < 1.0, "scrolled to {before}");
        // A newer log arrives (a call just ended): the rows are rebuilt.
        rig.apply(Action::BigLog);
        rig.settle();
        let after = rig.with_app(|app, _, _| app.recents.scrolled());
        // The list keeps the reader's place: the offset moves only by whole
        // rows, and only by the rows that came in above (anchoring).
        let moved = (after - before) / crate::recents::ROW_H;
        assert!(
            (moved - moved.round()).abs() < 0.01 && moved.abs() <= 3.0,
            "offset moved from {before} to {after}"
        );
    }

    #[test]
    fn the_photo_cache_stays_at_64_and_decoding_is_off_the_caller() {
        let mut rig = Rig::new();
        rig.settle();
        let uri = gpui::SharedString::from("sample:3");
        rig.with_app(|app, _, cx| {
            app.want_photos([(9001_i64, &uri)], cx);
            // Nothing is decoded on the caller's stack: the work is queued.
            assert!(app.photos.get(9001).is_none(), "decoded inline");
        });
        rig.advance(FRAME);
        let decoded = rig.with_app(|app, _, _| app.photos.get(9001).is_some());
        assert!(decoded, "the background decode never landed");
        // Stress: far more photos than the cache holds, with reads in between.
        rig.with_app(|app, _, _| app.photos.stress_for_tests(5_000));
        let held = rig.with_app(|app, _, _| app.photos.len_for_tests());
        assert_eq!(held, crate::photos::CAPACITY);
    }

    /// Story 12.3: with no spring moving and no call, nothing is drawn for 30 s
    /// on any tab and no timer wakes the app.
    #[test]
    fn idle_draws_no_frames_on_any_tab() {
        let mut rig = Rig::new();
        rig.settle();
        for tab in [3, 1, 2, 0] {
            rig.apply(Action::Tab(tab));
            rig.settle();
            let run = rig.run(&[], 30_000);
            assert!(
                run.frames.is_empty(),
                "tab {tab} drew {} frames while idle",
                run.frames.len()
            );
        }
    }
}
