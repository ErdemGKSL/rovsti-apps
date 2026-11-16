//! How often a running trip asks for the location (AD-SA10): as late as possible, but always before the
//! user can physically reach the line.
//!
//! [`crate::poll`] assumes the fastest plausible traveller ([`V_MAX_MPS`]) at every fix. This module
//! keeps that curve as the floor (rule `R0`, "never denser than before") and lengthens the interval when
//! the recent fixes prove the user is slower. The speed is never trusted as a guess: it is an *upper
//! bound* of the speed right now (from the last step and a bounded acceleration [`A_MAX_MPS2`]), and the
//! envelope [`reach_m`] lets the user accelerate as hard as a vehicle can after the fix.
//!
//! ```text
//! slack  = max(0, line - acc - max(DEAD_BAND_M, acc))
//! R0     = slack / (2 * V_MAX)                       today's curve (poll_interval)
//! R1     = slack / (2 * v)                           half the slack left at the assumed speed
//! R2     = the largest T with reach(T + grace(T), v) <= slack
//! T      = clamp(max(R0, min(R1, R2)), 3 s, 300 s), rounded down to the ladder
//! ```
//!
//! **Safety invariant.** With speed at most `V_MAX`, acceleration at most `A_MAX`, a fix wrong by at most
//! its accuracy and the next fix within `T + grace(T)`, the user cannot cover `slack` before the next fix
//! whenever `T > MIN_POLL`: `reach(T + grace(T), v) <= slack` when the adaptive rules decide, and
//! `2 * V_MAX * T <= slack` when `R0` decides. The tests sweep this and simulate it.
//!
//! The interval only changes when the ladder rung changes, so the location request is not registered
//! again on every fix; a longer rung needs a margin ([`plan`]), a shorter one is taken at once.

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::Coord;
use crate::crossing::DEAD_BAND_M;
use crate::poll::{MAX_POLL, MIN_POLL, retry_after};
use crate::trip::{MAX_ACCURACY_M, MAX_SPEED_MPS};

/// Top speed anyone can have (P1): a fast intercity train.
pub const V_MAX_MPS: f64 = MAX_SPEED_MPS;
/// Top acceleration anyone can have (P2). Trains, trams and buses in service stay under 1.5 m/s²; a car
/// reaches city speed quickly and then stops accelerating, which the envelope covers.
pub const A_MAX_MPS2: f64 = 2.0;
/// The speed assumed when no movement was seen at all: a brisk walk.
pub const V_FLOOR_MPS: f64 = 2.0;
/// Headroom on the 5-minute average: stop-and-go traffic cruises above its average.
pub const AVG_HEADROOM: f64 = 1.5;
/// How far back the speed history reaches.
pub const WINDOW: Duration = Duration::from_secs(300);
/// The most fixes the history keeps.
pub const MAX_SAMPLES: usize = 48;
/// The intervals a trip asks for, seconds. A value is rounded down to one of them.
pub const LADDER_S: [u64; 21] = [
    3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 75, 90, 120, 150, 180, 240, 300,
];

/// Half the slack stays as a margin (AD-SA1).
const K: f64 = 2.0;
/// A step implying more than this is a bad fix, not movement.
const OUTLIER_MPS: f64 = 90.0;
/// The average joins once the history spans this long.
const AVG_MIN_SPAN_S: f64 = 10.0;
/// A provider (Doppler) speed `s` bounds the speed now by `DOPPLER_SCALE * s + DOPPLER_PAD_MPS`.
const DOPPLER_SCALE: f64 = 1.2;
const DOPPLER_PAD_MPS: f64 = 1.0;
/// A longer rung is taken only when the raw interval is this much above it.
const LENGTHEN_MARGIN: f64 = 1.15;
/// High accuracy on at or below this raw interval, or when the line is closer than [`HIGH_ON_ETA_S`].
const HIGH_ON_AT_OR_BELOW: Duration = Duration::from_secs(15);
/// High accuracy stays on until the raw interval is at least this and the line further than [`HIGH_OFF_ETA_S`].
const HIGH_OFF_FROM: Duration = Duration::from_secs(25);
const HIGH_ON_ETA_S: f64 = 75.0;
const HIGH_OFF_ETA_S: f64 = 120.0;

/// Which term decided the speed a plan assumed.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SpeedBasis {
    /// No previous fix: the top speed, today's curve.
    Cold,
    /// The bound from the last step and the acceleration limit.
    Bound,
    /// The bound, tightened by the provider's speed.
    Doppler,
    /// The 5-minute average with headroom.
    Average,
    /// Nothing moved: a brisk walk.
    Floor,
}

impl SpeedBasis {
    /// The wire name, as the log line shows it.
    pub fn name(self) -> &'static str {
        match self {
            Self::Cold => "cold",
            Self::Bound => "bound",
            Self::Doppler => "doppler",
            Self::Average => "average",
            Self::Floor => "floor",
        }
    }
}

/// One usable fix in the history. `t_ms` is the monotonic clock when the platform gives one.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct SpeedSample {
    pub t_ms: i64,
    pub at: Coord,
    pub accuracy_m: f64,
}

/// The interval planned last, so the next plan can apply the hysteresis and see a late fix.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct LastPlan {
    /// When it was planned (the clock of [`SpeedSample::t_ms`]).
    pub t_ms: i64,
    pub next_ms: u64,
    pub high: bool,
    /// The speed it assumed after a usable fix (dead reckoning keeps the previous one).
    pub assumed_mps: f64,
    pub basis: SpeedBasis,
}

/// The usable fixes of the last [`WINDOW`] (at most [`MAX_SAMPLES`]) and the last plan.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct SpeedHistory {
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    samples: Vec<SpeedSample>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    last_plan: Option<LastPlan>,
}

/// What the history says about the speed.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SpeedEstimate {
    /// Distance covered over the window (jitter gated) / its span; 0 before it spans 10 s.
    pub avg_mps: f64,
    /// Mean speed since the previous fix, when it moved more than both accuracies.
    pub last_mps: Option<f64>,
    /// An upper bound of the speed right now.
    pub now_max_mps: f64,
    /// The speed shown and used for the ETA: the largest measured one.
    pub est_mps: f64,
    /// The speed the plan assumes.
    pub assumed_mps: f64,
    pub basis: SpeedBasis,
}

/// What [`plan`] needs.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CadenceInput {
    /// Metres to the segment A-B; `None` is unknown.
    pub line_m: Option<f64>,
    /// Accuracy of the fix; `None` (or out of range) is the worst usable one.
    pub accuracy_m: Option<f64>,
    /// Metres to the point where the arriving-soon message is due, when one is pending.
    pub notice_m: Option<f64>,
    pub assumed_mps: f64,
    pub est_mps: f64,
    pub basis: SpeedBasis,
    /// The interval asked for last time, when the hysteresis applies.
    pub prev: Option<Duration>,
    pub prev_high: bool,
}

/// The decision: how soon the next fix, how long to wait for it, and the provider's priority.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Cadence {
    /// A rung of [`LADDER_S`].
    pub next_fix_in: Duration,
    pub retry_after: Duration,
    pub high_accuracy: bool,
    /// The interval before the ladder and the hysteresis.
    pub raw: Duration,
    pub assumed_mps: f64,
    pub basis: SpeedBasis,
    /// The fix came later than the previous plan's watchdog window.
    pub late: bool,
}

/// The farthest anyone gets in `t_s` seconds from speed `v_mps`, accelerating at [`A_MAX_MPS2`] up to
/// [`V_MAX_MPS`]. A NaN speed is the top speed, a NaN or negative time is zero.
pub fn reach_m(t_s: f64, v_mps: f64) -> f64 {
    let t = if t_s.is_nan() { 0.0 } else { t_s.max(0.0) };
    let v = clamp_speed(v_mps);
    let t1 = (V_MAX_MPS - v) / A_MAX_MPS2;
    if t <= t1 {
        v * t + A_MAX_MPS2 * t * t / 2.0
    } else {
        v * t1 + A_MAX_MPS2 * t1 * t1 / 2.0 + V_MAX_MPS * (t - t1)
    }
}

/// The inverse of [`reach_m`] in time: seconds until `d_m` can be covered from `v_mps`.
fn time_to_reach_s(d_m: f64, v_mps: f64) -> f64 {
    if d_m.is_nan() || d_m <= 0.0 {
        return 0.0;
    }
    let v = clamp_speed(v_mps);
    let t1 = (V_MAX_MPS - v) / A_MAX_MPS2;
    let d1 = v * t1 + A_MAX_MPS2 * t1 * t1 / 2.0;
    if d_m <= d1 {
        (-v + (v * v + 2.0 * A_MAX_MPS2 * d_m).sqrt()) / A_MAX_MPS2
    } else {
        t1 + (d_m - d1) / V_MAX_MPS
    }
}

/// The watchdog window after an interval (`retry_after - interval`), seconds.
pub fn grace_s(t_s: f64) -> f64 {
    (t_s / 4.0).clamp(5.0, 30.0)
}

/// The largest `T` with `T + grace(T) <= x_s`, zero when none.
fn before_grace_s(x_s: f64) -> f64 {
    let t = if x_s <= 25.0 {
        x_s - 5.0
    } else if x_s <= 150.0 {
        x_s / 1.25
    } else {
        x_s - 30.0
    };
    t.max(0.0)
}

fn clamp_speed(v_mps: f64) -> f64 {
    if v_mps.is_nan() {
        V_MAX_MPS
    } else {
        v_mps.clamp(0.0, V_MAX_MPS)
    }
}

/// The planning speed: at least the floor, at most the top speed, NaN is the top speed.
fn planning_speed(v_mps: f64) -> f64 {
    clamp_speed(v_mps).max(V_FLOOR_MPS)
}

/// Accuracy as [`crate::poll::poll_interval`] reads it: unknown or out of range is the worst usable one.
fn accuracy(accuracy_m: Option<f64>) -> f64 {
    accuracy_m
        .filter(|a| a.is_finite() && *a <= MAX_ACCURACY_M)
        .map_or(MAX_ACCURACY_M, |a| a.max(0.0))
}

/// The distance that is surely still to go: the line minus the fix's error and the dead band.
pub fn slack_m(line_m: f64, accuracy_m: f64) -> f64 {
    (line_m - accuracy_m - DEAD_BAND_M.max(accuracy_m)).max(0.0)
}

fn sanitize_slack(slack_m: f64) -> f64 {
    if slack_m.is_nan() {
        0.0
    } else {
        slack_m.max(0.0)
    }
}

/// Rule `R0`, seconds, before the clamp: today's curve.
fn cold_s(slack: f64) -> f64 {
    slack / (K * V_MAX_MPS)
}

/// `min(R1, R2)`, seconds, before the clamp.
fn adaptive_s(slack: f64, v: f64) -> f64 {
    let r1 = slack / (K * v);
    let r2 = before_grace_s(time_to_reach_s(slack, v));
    r1.min(r2)
}

fn clamp_secs(secs: f64) -> Duration {
    let secs = if secs.is_nan() { 0.0 } else { secs };
    Duration::from_secs_f64(secs.clamp(MIN_POLL.as_secs_f64(), MAX_POLL.as_secs_f64()))
}

/// `clamp(max(R0, min(R1, R2)), MIN_POLL, MAX_POLL)` for `slack_m` metres at `v_mps`, before the ladder.
pub fn safe_interval(slack_m: f64, v_mps: f64) -> Duration {
    let slack = sanitize_slack(slack_m);
    let v = planning_speed(v_mps);
    clamp_secs(cold_s(slack).max(adaptive_s(slack, v)))
}

/// The largest rung of [`LADDER_S`] at or below `d` (the first rung below it).
pub fn ladder_floor(d: Duration) -> Duration {
    let rung = LADDER_S
        .iter()
        .rev()
        .copied()
        .find(|s| Duration::from_secs(*s) <= d)
        .unwrap_or(LADDER_S[0]);
    Duration::from_secs(rung)
}

/// The next interval and priority. Shorter is taken at once; a longer rung only when the raw value is at
/// least [`LENGTHEN_MARGIN`] times that rung, else the previous interval stays. The answer is never above
/// the raw value, and never below today's curve rounded down to the ladder.
pub fn plan(input: &CadenceInput) -> Cadence {
    let (raw, cold) = raw_interval(input);
    let rung = ladder_floor(raw);
    let next = match input.prev {
        Some(prev) if rung > prev => {
            let prev = ladder_floor(prev);
            LADDER_S
                .iter()
                .rev()
                .map(|s| Duration::from_secs(*s))
                .find(|r| {
                    *r > prev
                        && *r <= rung
                        && raw.as_secs_f64() >= LENGTHEN_MARGIN * r.as_secs_f64()
                })
                .unwrap_or(prev)
        }
        _ => rung,
    }
    // The hysteresis never asks more often than today's curve does.
    .max(ladder_floor(cold));
    let eta_s = match input.line_m {
        Some(line) if !line.is_nan() && input.est_mps.is_finite() && input.est_mps > 0.0 => {
            line.max(0.0) / input.est_mps
        }
        _ => f64::INFINITY,
    };
    let high_accuracy = if input.prev_high {
        !(raw >= HIGH_OFF_FROM && eta_s > HIGH_OFF_ETA_S)
    } else {
        raw <= HIGH_ON_AT_OR_BELOW || eta_s < HIGH_ON_ETA_S
    };
    Cadence {
        next_fix_in: next,
        retry_after: retry_after(next),
        high_accuracy,
        raw,
        assumed_mps: planning_speed(input.assumed_mps),
        basis: input.basis,
        late: false,
    }
}

/// [`safe_interval`] of the line, with the arriving-soon target folded into the adaptive part (the message
/// can only make the interval shorter, and never shorter than today's curve), and today's curve `R0`.
fn raw_interval(input: &CadenceInput) -> (Duration, Duration) {
    let Some(line) = input.line_m.filter(|l| !l.is_nan()) else {
        return (MAX_POLL, MAX_POLL);
    };
    let acc = accuracy(input.accuracy_m);
    let v = planning_speed(input.assumed_mps);
    let slack = slack_m(line, acc);
    let mut adaptive = adaptive_s(slack, v);
    if let Some(notice) = input.notice_m.filter(|n| n.is_finite() && *n > 0.0) {
        adaptive = adaptive.min(adaptive_s((notice - acc).max(0.0), v));
    }
    (
        clamp_secs(cold_s(slack).max(adaptive)),
        clamp_secs(cold_s(slack)),
    )
}

impl SpeedHistory {
    pub fn is_empty(&self) -> bool {
        self.samples.is_empty() && self.last_plan.is_none()
    }

    pub fn samples(&self) -> &[SpeedSample] {
        &self.samples
    }

    pub fn newest(&self) -> Option<&SpeedSample> {
        self.samples.last()
    }

    pub fn last_plan(&self) -> Option<&LastPlan> {
        self.last_plan.as_ref()
    }

    pub fn clear(&mut self) {
        self.samples.clear();
        self.last_plan = None;
    }

    /// Adds a usable fix. Time going backwards (a reboot, another clock) clears the history first; a
    /// fix with the newest one's time replaces it. Unusable samples are ignored.
    pub fn push(&mut self, sample: SpeedSample) {
        let valid = sample.accuracy_m.is_finite()
            && (0.0..=MAX_ACCURACY_M).contains(&sample.accuracy_m)
            && sample.at.lat.is_finite()
            && sample.at.lon.is_finite()
            && sample.at.lat.abs() <= 90.0
            && sample.at.lon.abs() <= 180.0;
        if !valid {
            return;
        }
        if let Some(newest) = self.samples.last() {
            if sample.t_ms < newest.t_ms {
                self.clear();
            } else if sample.t_ms == newest.t_ms {
                self.samples.pop();
            }
        }
        self.samples.push(sample);
        let window = i64::try_from(WINDOW.as_millis()).unwrap_or(i64::MAX);
        let oldest = sample.t_ms.saturating_sub(window);
        self.samples.retain(|s| s.t_ms >= oldest);
        let extra = self.samples.len().saturating_sub(MAX_SAMPLES);
        self.samples.drain(..extra);
    }

    /// Remembers what was planned at `t_ms`.
    pub fn record(&mut self, plan: LastPlan) {
        self.last_plan = Some(plan);
    }

    /// The speeds of the newest fix. `speed_mps` is the provider's speed of that fix, when it has one.
    pub fn estimate(&self, speed_mps: Option<f64>) -> SpeedEstimate {
        let doppler = speed_mps.filter(|s| s.is_finite() && *s >= 0.0);
        let avg_mps = self.average_mps();
        let n = self.samples.len();
        let (Some(now), Some(prev)) = (
            self.samples.last(),
            n.checked_sub(2).map(|i| &self.samples[i]),
        ) else {
            let est = doppler.unwrap_or(0.0).max(avg_mps).min(V_MAX_MPS);
            return SpeedEstimate {
                avg_mps,
                last_mps: None,
                now_max_mps: V_MAX_MPS,
                est_mps: est,
                assumed_mps: V_MAX_MPS,
                basis: SpeedBasis::Cold,
            };
        };
        let dt = (now.t_ms - prev.t_ms) as f64 / 1000.0;
        if dt <= 0.0 {
            // Not from `push` (it keeps the times increasing): trust nothing.
            return SpeedEstimate {
                avg_mps,
                last_mps: None,
                now_max_mps: V_MAX_MPS,
                est_mps: avg_mps,
                assumed_mps: V_MAX_MPS,
                basis: SpeedBasis::Bound,
            };
        }
        let moved = prev.at.distance_m(now.at);
        let errors = prev.accuracy_m + now.accuracy_m;
        // Under P3 the true step is at most `moved + errors`; the slowest moment of the step was at most
        // its mean speed, and from there the speed rose by at most sqrt(2 * A_MAX * distance).
        let step = moved + errors;
        let mean = step / dt;
        let bound = (mean * mean + 2.0 * A_MAX_MPS2 * step).sqrt();
        let (now_max, bound_basis) = match doppler {
            Some(s) if DOPPLER_SCALE * s + DOPPLER_PAD_MPS < bound => {
                (DOPPLER_SCALE * s + DOPPLER_PAD_MPS, SpeedBasis::Doppler)
            }
            _ => (bound, SpeedBasis::Bound),
        };
        let now_max = if now_max.is_nan() { V_MAX_MPS } else { now_max };
        let last_mps = (moved > errors)
            .then(|| moved / dt)
            .filter(|v| v.is_finite() && *v <= OUTLIER_MPS);

        let headroom = AVG_HEADROOM * avg_mps;
        let (assumed, basis) = if now_max >= headroom && now_max >= V_FLOOR_MPS {
            (now_max, bound_basis)
        } else if headroom >= V_FLOOR_MPS {
            (headroom, SpeedBasis::Average)
        } else {
            (V_FLOOR_MPS, SpeedBasis::Floor)
        };
        let est = avg_mps
            .max(last_mps.unwrap_or(0.0))
            .max(doppler.unwrap_or(0.0))
            .min(V_MAX_MPS);
        SpeedEstimate {
            avg_mps,
            last_mps,
            now_max_mps: now_max.min(V_MAX_MPS),
            est_mps: est,
            assumed_mps: planning_speed(assumed),
            basis,
        }
    }

    /// Distance over the window / its span. A step counts only when it is longer than both accuracies
    /// (jitter adds nothing) and implies less than [`OUTLIER_MPS`] (a jump does not poison the average).
    fn average_mps(&self) -> f64 {
        let (Some(first), Some(last)) = (self.samples.first(), self.samples.last()) else {
            return 0.0;
        };
        let span = (last.t_ms - first.t_ms) as f64 / 1000.0;
        if span < AVG_MIN_SPAN_S {
            return 0.0;
        }
        let counted = |from: &SpeedSample, to: &SpeedSample| -> Option<f64> {
            let d = from.at.distance_m(to.at);
            let dt = (to.t_ms - from.t_ms) as f64 / 1000.0;
            (d >= from.accuracy_m + to.accuracy_m && dt > 0.0 && d / dt <= OUTLIER_MPS).then_some(d)
        };
        let mut anchor = first;
        let mut path = 0.0;
        for sample in &self.samples[1..] {
            if let Some(d) = counted(anchor, sample) {
                path += d;
                anchor = sample;
            }
        }
        // The tail from the last anchor to the newest fix, beyond both accuracies.
        let tail = counted(anchor, last)
            .map_or(0.0, |d| d - anchor.accuracy_m - last.accuracy_m)
            .max(0.0);
        ((path + tail) / span).min(V_MAX_MPS)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
