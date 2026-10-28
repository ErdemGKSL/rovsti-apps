//! Slow-frame detection (story 11.10) and the frame recorder (story 12.1,
//! AD-21, AD-22).
//!
//! This only *observes*. Frames that run long are reported once per streak
//! under the `RovstiPerf` tag; nothing here removes, thins or skips a visual
//! effect, glass pass or aura. Speed comes from cheaper code, never from
//! doing less on screen.
//!
//! The detector lives inside [`crate::motion::Motion`], which already sees the
//! real time between animation frames, so it costs one comparison per frame
//! and never allocates on the hot path (a report formats a line, and happens
//! at most once per streak).

use std::{
    fmt::Write as _,
    sync::{
        OnceLock,
        atomic::{AtomicU8, Ordering},
    },
    time::{Duration, Instant},
};

use gpui::Window;
use rovsti_core::perf::FrameStats;

/// The display period assumed until frames show a shorter one.
const DEFAULT_BUDGET_MS: f32 = 1000.0 / 60.0;
/// The shortest period believed (240 Hz); anything shorter is a timer glitch.
const MIN_BUDGET_MS: f32 = 1000.0 / 240.0;
/// Short frames in a row before the budget follows a faster display.
const FASTER_RUN: u8 = 4;
/// A frame is slow when it takes more than this many budgets.
const SLOW_FACTOR: f32 = 2.0;
/// This many slow frames in a row make a report.
const STREAK: u8 = 2;
/// Reports of one detector are at least this far apart.
const REPORT_GAP: Duration = Duration::from_secs(1);

/// What the owner is doing, as named in the log line. Set from the render of
/// the root view (one atomic store).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Scenario {
    Idle = 0,
    Keypad,
    Favorites,
    Recents,
    Contacts,
    Sheet,
    InCall,
    Incoming,
    /// The scripted tab ping-pong (`S-tab`).
    Tab,
}

impl Scenario {
    pub const fn name(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Keypad => "S-keypad",
            Self::Favorites => "S-favorites",
            Self::Recents => "S-scroll-recents",
            Self::Contacts => "S-contacts",
            Self::Sheet => "S-sheet",
            Self::InCall => "S-incall",
            Self::Incoming => "S-incoming",
            Self::Tab => "S-tab",
        }
    }

    const ALL: [Self; 9] = [
        Self::Idle,
        Self::Keypad,
        Self::Favorites,
        Self::Recents,
        Self::Contacts,
        Self::Sheet,
        Self::InCall,
        Self::Incoming,
        Self::Tab,
    ];
}

static CURRENT: AtomicU8 = AtomicU8::new(0);
static SINK: OnceLock<fn(&str)> = OnceLock::new();

/// Names the scenario the next reports belong to.
pub fn set_scenario(scenario: Scenario) {
    CURRENT.store(scenario as u8, Ordering::Relaxed);
}

static FORCED: AtomicU8 = AtomicU8::new(u8::MAX);

/// Pins the scenario name for a scripted run (the screen no longer decides),
/// or `None` to let the screen name it again.
pub fn force_scenario(scenario: Option<Scenario>) {
    FORCED.store(scenario.map_or(u8::MAX, |s| s as u8), Ordering::Relaxed);
}

/// The scripted run's scenario, if one is pinned.
pub fn forced() -> Option<Scenario> {
    Scenario::ALL
        .get(usize::from(FORCED.load(Ordering::Relaxed)))
        .copied()
}

/// The scenario set last.
pub fn scenario() -> Scenario {
    Scenario::ALL
        .get(usize::from(CURRENT.load(Ordering::Relaxed)))
        .copied()
        .unwrap_or(Scenario::Idle)
}

/// Where report lines go (the app wires this to logcat tag `RovstiPerf`).
/// The first sink wins.
pub fn install_sink(sink: fn(&str)) {
    let _ = SINK.set(sink);
}

fn emit(line: &str) {
    match SINK.get() {
        Some(sink) => sink(line),
        None => log::warn!(target: "RovstiPerf", "{line}"),
    }
}

/// Thermal state as last reported by the platform (0 nominal .. 3 critical).
/// Logged with each report, never used to change what is drawn.
static THERMAL: AtomicU8 = AtomicU8::new(0);

/// Records the platform's thermal state for the next reports.
pub fn set_thermal(state: gpui::ThermalState) {
    let value = match state {
        gpui::ThermalState::Nominal => 0,
        gpui::ThermalState::Fair => 1,
        gpui::ThermalState::Serious => 2,
        gpui::ThermalState::Critical => 3,
    };
    THERMAL.store(value, Ordering::Relaxed);
}

fn thermal_name() -> &'static str {
    match THERMAL.load(Ordering::Relaxed) {
        0 => "nominal",
        1 => "fair",
        2 => "serious",
        _ => "critical",
    }
}

/// Counts consecutive frames over twice the frame budget.
#[derive(Clone, Debug)]
pub struct SlowFrames {
    budget_ms: f32,
    streak: u8,
    reported: bool,
    last_over: bool,
    last_report: Option<Instant>,
    total: u32,
    /// Consecutive frames that were shorter than `budget_ms`, and the longest
    /// of them (the budget moves down to that, never to a lone short frame).
    shorter_run: u8,
    shorter_max: f32,
}

impl Default for SlowFrames {
    fn default() -> Self {
        Self {
            budget_ms: DEFAULT_BUDGET_MS,
            streak: 0,
            reported: false,
            last_over: false,
            last_report: None,
            total: 0,
            shorter_run: 0,
            shorter_max: 0.0,
        }
    }
}

impl SlowFrames {
    /// Feeds the real time since the previous frame. Returns the slow frame
    /// time in ms when this frame completes a streak (once per streak).
    pub fn observe(&mut self, frame: Duration, now: Instant) -> Option<f32> {
        let ms = frame.as_secs_f32() * 1000.0;
        if ms <= 0.0 {
            return None;
        }
        // The display period is the frame time a faster display settles on
        // (never above 60 Hz's). One short frame (two renders squeezed into
        // one vsync) is a glitch, so the budget follows only after
        // `FASTER_RUN` short frames in a row, and lands on the longest.
        if ms >= MIN_BUDGET_MS && ms < self.budget_ms * 0.92 {
            self.shorter_run += 1;
            self.shorter_max = self.shorter_max.max(ms);
            if self.shorter_run >= FASTER_RUN {
                self.budget_ms = self.shorter_max;
                self.shorter_run = 0;
                self.shorter_max = 0.0;
            }
        } else {
            self.shorter_run = 0;
            self.shorter_max = 0.0;
        }
        self.last_over = ms > self.budget_ms * SLOW_FACTOR;
        if !self.last_over {
            self.streak = 0;
            self.reported = false;
            return None;
        }
        self.total += 1;
        self.streak = self.streak.saturating_add(1);
        if self.streak < STREAK || self.reported {
            return None;
        }
        self.reported = true;
        if self
            .last_report
            .is_some_and(|at| now.saturating_duration_since(at) < REPORT_GAP)
        {
            return None;
        }
        self.last_report = Some(now);
        Some(ms)
    }

    /// Animation went idle: the gap before the next frame is not a slow frame.
    pub fn idle(&mut self) {
        self.last_over = false;
        self.streak = 0;
        self.reported = false;
    }

    /// Whether the newest frame took more than twice the budget (S15 snaps
    /// the theme change instead of melting it when this is true).
    pub fn last_over(&self) -> bool {
        self.last_over
    }

    pub fn budget_ms(&self) -> f32 {
        self.budget_ms
    }

    /// Slow frames seen so far (for tests and logs).
    pub fn total(&self) -> u32 {
        self.total
    }

    /// Reports `frame_ms` for the current scenario.
    pub fn report(&self, frame_ms: f32) {
        emit(&format!(
            "slow frames scenario={} frame_ms={:.1} budget_ms={:.1} total={}",
            scenario().name(),
            frame_ms,
            self.budget_ms,
            self.total
        ));
    }
}

/// What one scenario cost, as measured by [`Recorder`].
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Report {
    /// Renders of the root view (a render is one frame the app produced).
    pub renders: u32,
    /// Renders that asked for another animation frame.
    pub requested: u32,
    pub build: rovsti_core::perf::FrameSummary,
    pub interval: rovsti_core::perf::FrameSummary,
    /// Scene builds over the frame budget / over twice the budget, by
    /// `build_time` (CPU work).
    pub build_over: u32,
    pub build_over_2x: u32,
    /// Frame-to-frame gaps over the budget / over twice the budget while an
    /// animation ran (pacing).
    pub interval_over: u32,
    pub interval_over_2x: u32,
    pub cache_hits: u64,
    pub cache_misses: u64,
    pub budget_ms: f32,
}

impl Report {
    /// Refresh rate implied by the measured budget, rounded.
    pub fn hz(&self) -> u32 {
        if self.budget_ms > 0.0 {
            (1000.0 / self.budget_ms).round() as u32
        } else {
            0
        }
    }

    /// `view_cache` hit rate in percent, or `None` without cached views.
    pub fn cache_hit_percent(&self) -> Option<u32> {
        let total = self.cache_hits + self.cache_misses;
        (total > 0).then(|| (self.cache_hits * 100 / total) as u32)
    }

    /// Appends the one-line log text (no names, no numbers of the owner's).
    pub fn write_line(&self, scenario: Scenario, out: &mut String) {
        let _ = write!(
            out,
            "report scenario={} renders={} requested={} hz={} budget_ms={:.1} \
             build_ms p50={:.2} p95={:.2} p99={:.2} max={:.2} over={} over2x={} \
             interval_ms p50={:.1} p95={:.1} p99={:.1} max={:.1} over={} over2x={} \
             view_cache hits={} misses={} thermal={}",
            scenario.name(),
            self.renders,
            self.requested,
            self.hz(),
            self.budget_ms,
            self.build.p50,
            self.build.p95,
            self.build.p99,
            self.build.max,
            self.build_over,
            self.build_over_2x,
            self.interval.p50,
            self.interval.p95,
            self.interval.p99,
            self.interval.max,
            self.interval_over,
            self.interval_over_2x,
            self.cache_hits,
            self.cache_misses,
            thermal_name(),
        );
    }
}

/// Collects frame timings per scenario and logs one [`Report`] when the
/// scenario changes (story 12.1). The rings are allocated once; observing a
/// frame is a few stores and never allocates. It only watches.
pub struct Recorder {
    build: FrameStats,
    interval: FrameStats,
    scenario: Scenario,
    last_sequence: u64,
    renders: u32,
    requested: u32,
    hits: u64,
    misses: u64,
    line: String,
}

impl Default for Recorder {
    fn default() -> Self {
        Self {
            build: FrameStats::with_capacity(2048),
            interval: FrameStats::with_capacity(2048),
            scenario: Scenario::Idle,
            last_sequence: 0,
            renders: 0,
            requested: 0,
            hits: 0,
            misses: 0,
            line: String::with_capacity(512),
        }
    }
}

impl Recorder {
    /// Switches GPUI's per-frame diagnostics on for this window.
    pub fn enable(window: &mut Window) {
        window.set_frame_diagnostics_enabled(true);
    }

    /// Call once per render of the root view. `animating` says the frame
    /// asked for another animation frame, `interval` is the real time since
    /// the previous animation frame.
    pub fn observe(
        &mut self,
        window: &Window,
        scenario: Scenario,
        animating: bool,
        interval: Duration,
    ) {
        if scenario != self.scenario {
            self.finish();
            self.scenario = scenario;
        }
        self.renders += 1;
        self.requested += u32::from(animating);
        if !interval.is_zero() {
            self.interval.push(interval.as_secs_f32() * 1000.0);
        }
        // The newest completed scene build is the previous frame's.
        if let Some(frame) = window.frame_diagnostics()
            && frame.sequence != self.last_sequence
        {
            self.last_sequence = frame.sequence;
            self.build.push(frame.build_time.as_secs_f32() * 1000.0);
            self.hits += frame.view_cache.hits;
            self.misses += frame.view_cache.misses;
        }
    }

    /// The display period the frames settled on: the median gap between
    /// animation frames, snapped to 60, 90 or 120 Hz (60 until 20 gaps).
    fn budget_ms(&mut self) -> f32 {
        if self.interval.len() < 20 {
            return DEFAULT_BUDGET_MS;
        }
        let median = self.interval.summary().p50;
        if median < 9.7 {
            8.3
        } else if median < 13.8 {
            11.1
        } else {
            16.6
        }
    }

    /// The numbers so far for the current scenario.
    pub fn report(&mut self) -> Report {
        let budget_ms = self.budget_ms();
        Report {
            renders: self.renders,
            requested: self.requested,
            build: self.build.summary(),
            interval: self.interval.summary(),
            build_over: self.build.over_budget(budget_ms) as u32,
            build_over_2x: self.build.over_double_budget(budget_ms) as u32,
            interval_over: self.interval.over_budget(budget_ms * 1.5) as u32,
            interval_over_2x: self.interval.over_double_budget(budget_ms) as u32,
            cache_hits: self.hits,
            cache_misses: self.misses,
            budget_ms,
        }
    }

    /// Logs the finished scenario (if it rendered anything) and starts over.
    pub fn finish(&mut self) {
        self.finish_with(false);
    }

    /// Like [`Recorder::finish`], and logs even when nothing rendered: that
    /// is the idle check (0 renders is the target).
    pub fn finish_always(&mut self) {
        self.finish_with(true);
    }

    fn finish_with(&mut self, always: bool) {
        if always || self.renders > 0 {
            let report = self.report();
            self.line.clear();
            report.write_line(self.scenario, &mut self.line);
            emit(&self.line);
        }
        self.reset();
    }

    pub fn scenario(&self) -> Scenario {
        self.scenario
    }

    /// Names the scenario the next frames belong to without logging the old
    /// one (the runner has just called [`Recorder::finish_always`]).
    pub fn set_scenario(&mut self, scenario: Scenario) {
        self.scenario = scenario;
    }

    fn reset(&mut self) {
        self.build.clear();
        self.interval.clear();
        self.renders = 0;
        self.requested = 0;
        self.hits = 0;
        self.misses = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ms(v: u64) -> Duration {
        Duration::from_millis(v)
    }

    #[test]
    fn steady_frames_are_never_slow() {
        let mut slow = SlowFrames::default();
        let now = Instant::now();
        for _ in 0..600 {
            assert_eq!(slow.observe(ms(16), now), None);
        }
        assert_eq!(slow.total(), 0);
    }

    #[test]
    fn one_hiccup_is_ignored_two_in_a_row_report_once() {
        let mut slow = SlowFrames::default();
        let now = Instant::now();
        for _ in 0..10 {
            slow.observe(ms(16), now);
        }
        assert_eq!(slow.observe(ms(40), now), None, "a single slow frame");
        slow.observe(ms(16), now);
        assert_eq!(slow.observe(ms(40), now), None);
        assert!(slow.observe(ms(45), now).is_some(), "second in a row");
        assert_eq!(slow.observe(ms(50), now), None, "same streak, already told");
    }

    #[test]
    fn the_budget_follows_a_faster_display() {
        let mut slow = SlowFrames::default();
        let now = Instant::now();
        for _ in 0..5 {
            slow.observe(Duration::from_micros(8333), now);
        }
        assert!((slow.budget_ms() - 8.333).abs() < 0.01);
        // 20 ms is over twice an 8.3 ms budget.
        slow.observe(ms(20), now);
        assert!(slow.observe(ms(20), now).is_some());
    }

    #[test]
    fn a_lone_short_frame_does_not_move_the_budget() {
        let mut slow = SlowFrames::default();
        let now = Instant::now();
        for _ in 0..10 {
            slow.observe(ms(16), now);
        }
        slow.observe(Duration::from_micros(4800), now);
        for _ in 0..10 {
            slow.observe(ms(16), now);
        }
        assert!(slow.budget_ms() > 16.0, "budget {}", slow.budget_ms());
        assert_eq!(slow.total(), 0);
    }

    #[test]
    fn reports_are_rate_limited() {
        let mut slow = SlowFrames::default();
        let t0 = Instant::now();
        slow.observe(ms(50), t0);
        assert!(slow.observe(ms(50), t0).is_some());
        slow.observe(ms(16), t0);
        slow.observe(ms(50), t0);
        assert_eq!(slow.observe(ms(50), t0), None, "within a second");
        slow.observe(ms(16), t0);
        slow.observe(ms(50), t0 + ms(1500));
        assert!(slow.observe(ms(50), t0 + ms(1500)).is_some());
    }

    #[test]
    fn going_idle_breaks_a_streak() {
        let mut slow = SlowFrames::default();
        let now = Instant::now();
        slow.observe(ms(50), now);
        slow.idle();
        assert_eq!(slow.observe(ms(50), now), None);
    }

    #[test]
    fn report_line_has_the_agreed_fields_and_no_free_text() {
        let mut build = FrameStats::with_capacity(8);
        for t in [1.0, 2.0, 3.0, 20.0] {
            build.push(t);
        }
        let report = Report {
            renders: 4,
            requested: 3,
            build: build.summary(),
            build_over: 1,
            budget_ms: 8.3,
            ..Report::default()
        };
        assert_eq!(report.hz(), 120);
        let mut line = String::new();
        report.write_line(Scenario::Recents, &mut line);
        assert!(line.starts_with("report scenario=S-scroll-recents renders=4 requested=3 hz=120"));
        for key in [
            "p50=", "p95=", "p99=", "max=", "over=", "over2x=", "thermal=",
        ] {
            assert!(line.contains(key), "{key} in {line}");
        }
        assert_eq!(report.cache_hit_percent(), None);
    }

    #[test]
    fn scenarios_round_trip() {
        set_scenario(Scenario::InCall);
        assert_eq!(scenario(), Scenario::InCall);
        assert_eq!(scenario().name(), "S-incall");
        set_scenario(Scenario::Idle);
    }
}
