use super::*;
use crate::EARTH_RADIUS_M;
use crate::poll::poll_interval;

const MID: Coord = Coord::new(41.0, 29.0);

/// Local metres east/north of [`MID`].
fn at(east: f64, north: f64) -> Coord {
    let lat = MID.lat + (north / EARTH_RADIUS_M).to_degrees();
    let lon = MID.lon + (east / (EARTH_RADIUS_M * MID.lat.to_radians().cos())).to_degrees();
    Coord::new(lat, lon)
}

fn sample(t_s: f64, north: f64, accuracy_m: f64) -> SpeedSample {
    SpeedSample {
        t_ms: (t_s * 1000.0).round() as i64,
        at: at(0.0, north),
        accuracy_m,
    }
}

/// xorshift64*: a seeded generator, enough for the simulations (no test crate needed).
struct Rng(u64);

impl Rng {
    fn next_u64(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// Uniform in `[lo, hi)`.
    fn range(&mut self, lo: f64, hi: f64) -> f64 {
        let unit = (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64;
        lo + (hi - lo) * unit
    }

    fn coin(&mut self) -> bool {
        self.next_u64() & 1 == 1
    }

    fn pick<T: Copy>(&mut self, items: &[T]) -> T {
        items[(self.next_u64() % items.len() as u64) as usize]
    }
}

fn secs(d: Duration) -> f64 {
    d.as_secs_f64()
}

fn input(line_m: f64, accuracy_m: f64, assumed_mps: f64) -> CadenceInput {
    CadenceInput {
        line_m: Some(line_m),
        accuracy_m: Some(accuracy_m),
        notice_m: None,
        assumed_mps,
        est_mps: 0.0,
        basis: SpeedBasis::Bound,
        prev: None,
        prev_high: false,
    }
}

/// The cadence as a running trip plans it after `e`, with the previous plan for the hysteresis.
fn plan_after(line_m: f64, accuracy_m: f64, e: &SpeedEstimate, prev: Option<Cadence>) -> Cadence {
    plan(&CadenceInput {
        line_m: Some(line_m),
        accuracy_m: Some(accuracy_m),
        notice_m: None,
        assumed_mps: e.assumed_mps,
        est_mps: e.est_mps,
        basis: e.basis,
        prev: prev
            .filter(|p| p.basis != SpeedBasis::Cold)
            .map(|p| p.next_fix_in),
        prev_high: prev.is_some_and(|p| p.high_accuracy && p.basis != SpeedBasis::Cold),
    })
}

const SPEEDS: [f64; 9] = [
    V_FLOOR_MPS,
    3.0,
    6.0,
    8.0,
    12.0,
    20.0,
    30.0,
    45.0,
    V_MAX_MPS,
];

// ---- 1. reach ----

#[test]
fn reach_is_zero_at_zero_monotonic_and_below_the_top_speed() {
    for v in [0.0, 1.0, 2.0, 10.0, 30.0, 59.0, 60.0] {
        assert_eq!(reach_m(0.0, v), 0.0);
        let mut prev = 0.0;
        for i in 0..=6_000 {
            let t = f64::from(i) * 0.1;
            let r = reach_m(t, v);
            assert!(r >= prev, "t {t} v {v}");
            assert!(r <= V_MAX_MPS * t + 1e-9, "t {t} v {v}");
            assert!(r >= v * t - 1e-9, "t {t} v {v}");
            prev = r;
        }
        // Continuous where the top speed is reached.
        let t1 = (V_MAX_MPS - v) / A_MAX_MPS2;
        assert!((reach_m(t1 - 1e-9, v) - reach_m(t1 + 1e-9, v)).abs() < 1e-6);
    }
    for t in [1.0, 10.0, 100.0] {
        let mut prev = 0.0;
        for i in 0..=600 {
            let r = reach_m(t, f64::from(i) * 0.1);
            assert!(r >= prev);
            prev = r;
        }
    }
    assert_eq!(reach_m(f64::NAN, 5.0), 0.0);
    assert_eq!(reach_m(-3.0, 5.0), 0.0);
    assert_eq!(reach_m(2.0, f64::NAN), V_MAX_MPS * 2.0);
}

#[test]
fn time_to_reach_inverts_reach() {
    for v in [0.0, 2.0, 11.0, 40.0, 60.0] {
        for d in [0.5, 10.0, 100.0, 900.0, 5_000.0, 80_000.0] {
            let t = time_to_reach_s(d, v);
            assert!((reach_m(t, v) - d).abs() < 1e-6 * d.max(1.0), "d {d} v {v}");
        }
    }
    assert_eq!(time_to_reach_s(0.0, 5.0), 0.0);
    assert_eq!(time_to_reach_s(f64::NAN, 5.0), 0.0);
}

#[test]
fn the_grace_window_matches_retry_after() {
    for ms in (0..=300_000).step_by(250) {
        let t = Duration::from_millis(ms);
        let grace = retry_after(t) - t;
        assert!((grace_s(secs(t)) - secs(grace)).abs() < 1e-9, "{t:?}");
        // before_grace_s is the inverse of T + grace(T).
        let x = secs(t) + grace_s(secs(t));
        assert!((before_grace_s(x) - secs(t)).abs() < 1e-6, "{t:?}");
    }
}

// ---- 2. the invariant, as a sweep ----

#[test]
fn invariant_sweep_the_line_cannot_be_reached_before_the_next_fix() {
    let mut checked = 0u32;
    for i in 0..=20_000 {
        let slack = f64::from(i) * 5.0;
        for k in 0..=116 {
            let v = V_FLOOR_MPS + f64::from(k) * 0.5;
            for t in [
                safe_interval(slack, v),
                ladder_floor(safe_interval(slack, v)),
            ] {
                let t = secs(t);
                if t <= secs(MIN_POLL) + 1e-9 {
                    continue;
                }
                checked += 1;
                if adaptive_s(slack, v) >= cold_s(slack) {
                    let worst = reach_m(t + grace_s(t), v);
                    assert!(worst <= slack + 1e-6, "slack {slack} v {v} T {t}: {worst}");
                } else {
                    assert!(
                        2.0 * V_MAX_MPS * t <= slack + 1e-6,
                        "slack {slack} v {v} T {t}"
                    );
                }
            }
        }
    }
    assert!(checked > 1_000_000, "{checked}");
}

// ---- 3. monotonic ----

#[test]
fn safe_interval_tightens_as_the_distance_falls_and_the_speed_rises() {
    for v in SPEEDS {
        let mut prev = Duration::ZERO;
        for i in 0..=40_000 {
            let d = safe_interval(f64::from(i) * 2.5, v);
            assert!(d >= prev, "v {v} slack {}", f64::from(i) * 2.5);
            prev = d;
        }
    }
    for i in 0..=2_000 {
        let slack = f64::from(i) * 50.0;
        let mut prev = Duration::MAX;
        for k in 0..=120 {
            let d = safe_interval(slack, f64::from(k) * 0.5);
            assert!(d <= prev, "slack {slack} v {}", f64::from(k) * 0.5);
            prev = d;
        }
    }
}

#[test]
fn plan_tightens_as_the_distance_falls_and_worse_accuracy_never_lengthens() {
    for v in SPEEDS {
        for acc in [3.0, 10.0, 40.0, 150.0] {
            let mut prev = Duration::ZERO;
            for i in 0..=8_000 {
                let c = plan(&input(f64::from(i) * 10.0, acc, v));
                assert!(c.next_fix_in >= prev, "v {v} acc {acc} at {} m", i * 10);
                assert!(c.raw >= c.next_fix_in);
                prev = c.next_fix_in;
            }
        }
        for i in 0..=2_000 {
            let line = f64::from(i) * 40.0;
            let mut prev = Duration::MAX;
            for acc in [0.0, 5.0, 10.0, 20.0, 60.0, 100.0, 150.0] {
                let c = plan(&input(line, acc, v));
                assert!(c.next_fix_in <= prev, "v {v} {line} m acc {acc}");
                prev = c.next_fix_in;
            }
            // Unknown or out-of-range accuracy is the worst usable one.
            let worst = plan(&input(line, 150.0, v)).next_fix_in;
            for acc in [None, Some(f64::NAN), Some(f64::INFINITY), Some(900.0)] {
                let c = plan(&CadenceInput {
                    accuracy_m: acc,
                    ..input(line, 0.0, v)
                });
                assert!(c.next_fix_in <= worst);
            }
        }
    }
}

// ---- 4. never denser than today ----

#[test]
fn never_denser_than_today() {
    for acc in [0.0, 5.0, 10.0, 50.0, 150.0] {
        for i in 0..=4_000 {
            let line = f64::from(i) * 25.0;
            let today = poll_interval(Some(line), Some(acc));
            for k in 0..=120 {
                let v = f64::from(k) * 0.5;
                assert!(
                    safe_interval(slack_m(line, acc), v) >= today,
                    "{line} m acc {acc} v {v}"
                );
                for prev in [None, Some(MIN_POLL), Some(Duration::from_secs(20))] {
                    let c = plan(&CadenceInput {
                        prev,
                        ..input(line, acc, v)
                    });
                    assert!(
                        c.next_fix_in >= ladder_floor(today),
                        "{line} m acc {acc} v {v}"
                    );
                }
            }
        }
    }
}

// ---- 5. caps ----

#[test]
fn caps_at_the_line_far_away_and_unknown() {
    for v in SPEEDS {
        for acc in [0.0, 10.0, 150.0] {
            for line in [0.0, -1.0, -1e9, f64::NEG_INFINITY, 1.0, 10.0] {
                assert_eq!(
                    plan(&input(line, acc, v)).next_fix_in,
                    MIN_POLL,
                    "{line} {v}"
                );
            }
            for line in [1e6, 1e12, f64::MAX, f64::INFINITY] {
                assert_eq!(
                    plan(&input(line, acc, v)).next_fix_in,
                    MAX_POLL,
                    "{line} {v}"
                );
            }
            for line in [None, Some(f64::NAN)] {
                let c = plan(&CadenceInput {
                    line_m: line,
                    ..input(0.0, acc, v)
                });
                assert_eq!(c.next_fix_in, MAX_POLL);
                assert!(!c.high_accuracy);
            }
        }
    }
    for v in [f64::NAN, f64::INFINITY, -5.0, 0.0] {
        for slack in [0.0, 1e3, 1e5, f64::NAN, f64::INFINITY, -1.0] {
            let d = safe_interval(slack, v);
            assert!((MIN_POLL..=MAX_POLL).contains(&d), "{slack} {v}");
        }
    }
    // A NaN speed is planned as the top speed: today's curve.
    assert_eq!(
        safe_interval(5_000.0, f64::NAN),
        safe_interval(5_000.0, V_MAX_MPS)
    );
    assert_eq!(
        plan(&input(100.0, 10.0, 5.0)).retry_after,
        Duration::from_secs(8)
    );
}

// ---- 6. ladder ----

#[test]
fn the_ladder_rounds_down_by_less_than_a_quarter() {
    assert_eq!(ladder_floor(Duration::ZERO), MIN_POLL);
    assert_eq!(ladder_floor(Duration::from_secs(10_000)), MAX_POLL);
    for ms in (3_000..=300_000).step_by(37) {
        let d = Duration::from_millis(ms);
        let r = ladder_floor(d);
        assert!(r <= d);
        assert!(LADDER_S.contains(&r.as_secs()) && r.subsec_nanos() == 0);
        assert!(secs(r) > 0.75 * secs(d) - 1e-9, "{d:?} -> {r:?}");
    }
    assert!(LADDER_S.windows(2).all(|w| w[0] < w[1]));
    assert_eq!(LADDER_S[0], MIN_POLL.as_secs());
    assert_eq!(LADDER_S[LADDER_S.len() - 1], MAX_POLL.as_secs());
}

// ---- 7. hysteresis and priority ----

#[test]
fn shorter_at_once_longer_only_with_margin() {
    for v in [V_FLOOR_MPS, 6.0, 12.0, 30.0] {
        for i in 0..=3_000 {
            let line = f64::from(i) * 5.0;
            let free = plan(&input(line, 10.0, v));
            let today = ladder_floor(poll_interval(Some(line), Some(10.0)));
            for prev_s in LADDER_S {
                let prev = Duration::from_secs(prev_s);
                let c = plan(&CadenceInput {
                    prev: Some(prev),
                    ..input(line, 10.0, v)
                });
                assert!(c.next_fix_in <= free.next_fix_in, "{line} m prev {prev_s}");
                assert!(c.next_fix_in <= c.raw);
                if free.next_fix_in <= prev {
                    assert_eq!(c.next_fix_in, free.next_fix_in, "shorter is taken at once");
                } else if c.next_fix_in > prev.max(today) {
                    assert!(secs(c.raw) >= LENGTHEN_MARGIN * secs(c.next_fix_in) - 1e-9);
                } else {
                    assert_eq!(c.next_fix_in, prev.max(today));
                }
            }
        }
    }
    // Concretely: from 15 s, a raw 21 s stays at 15 s (21 < 1.15 * 20), a raw 24 s takes 20 s.
    let raw_of = |line: f64| plan(&input(line, 10.0, 2.0)).raw;
    let line_for = |target: f64| {
        (0..200_000)
            .map(|i| f64::from(i) * 0.05)
            .find(|l| secs(raw_of(*l)) >= target)
            .unwrap()
    };
    let from_15 = |line: f64| {
        plan(&CadenceInput {
            prev: Some(Duration::from_secs(15)),
            ..input(line, 10.0, 2.0)
        })
        .next_fix_in
    };
    assert_eq!(from_15(line_for(21.0)), Duration::from_secs(15));
    assert_eq!(from_15(line_for(24.0)), Duration::from_secs(20));
}

#[test]
fn priority_has_a_dead_band_between_15_and_25_seconds() {
    for i in 0..=20_000 {
        let line = f64::from(i) * 0.5;
        for v in [V_FLOOR_MPS, 8.0] {
            let off = plan(&input(line, 10.0, v));
            let on = plan(&CadenceInput {
                prev_high: true,
                ..input(line, 10.0, v)
            });
            if off.raw <= Duration::from_secs(15) {
                assert!(off.high_accuracy && on.high_accuracy);
            } else if off.raw < Duration::from_secs(25) {
                // Moving between 15 and 25 s never flips it.
                assert!(!off.high_accuracy && on.high_accuracy, "{line} m");
            } else {
                assert!(!off.high_accuracy && !on.high_accuracy);
            }
        }
    }
    // The line closer than 75 s at the measured speed turns it on; it stays on until 120 s.
    let near = CadenceInput {
        est_mps: 20.0,
        ..input(1_400.0, 10.0, V_FLOOR_MPS)
    };
    assert!(
        plan(&near).raw > Duration::from_secs(25),
        "{:?}",
        plan(&near)
    );
    assert!(plan(&near).high_accuracy);
    let mid = CadenceInput {
        line_m: Some(2_000.0),
        prev_high: true,
        ..near
    };
    assert!(plan(&mid).high_accuracy);
    let far = CadenceInput {
        line_m: Some(2_600.0),
        ..mid
    };
    assert!(!plan(&far).high_accuracy);
}

// ---- 8. the estimator ----

fn history(samples: impl IntoIterator<Item = SpeedSample>) -> SpeedHistory {
    let mut h = SpeedHistory::default();
    for s in samples {
        h.push(s);
    }
    h
}

#[test]
fn one_sample_is_cold_and_two_are_a_bound() {
    let h = history([sample(0.0, 1_000.0, 10.0)]);
    let e = h.estimate(Some(3.0));
    assert_eq!(e.basis, SpeedBasis::Cold);
    assert_eq!(e.assumed_mps, V_MAX_MPS);
    assert_eq!(e.est_mps, 3.0);
    assert_eq!(
        SpeedHistory::default().estimate(None).basis,
        SpeedBasis::Cold
    );
    let h = history([sample(0.0, 1_000.0, 10.0), sample(15.0, 1_000.0, 10.0)]);
    let e = h.estimate(None);
    assert_eq!(e.basis, SpeedBasis::Bound);
    // Standing still: only "could have just started moving" (sqrt(2 * 2 * 20) and a little).
    assert!((6.0..9.5).contains(&e.assumed_mps), "{e:?}");
}

#[test]
fn standing_still_with_jitter_averages_zero() {
    let mut rng = Rng(7);
    let h = history((0..20).map(|i| SpeedSample {
        at: at(rng.range(-8.0, 8.0), 700.0 + rng.range(-8.0, 8.0)),
        ..sample(f64::from(i) * 15.0, 700.0, 10.0)
    }));
    let e = h.estimate(None);
    assert_eq!(e.avg_mps, 0.0);
    assert_eq!(e.last_mps, None);
    assert_eq!(e.est_mps, 0.0);
}

#[test]
fn a_jump_gives_the_top_speed_once_and_leaves_the_average_clean() {
    let mut h =
        history((0..12).map(|i| sample(f64::from(i) * 15.0, 3_000.0 - 21.0 * f64::from(i), 10.0)));
    let before = h.estimate(None);
    h.push(sample(180.0, 3_000.0 - 252.0 + 2_000.0, 10.0));
    let jump = h.estimate(None);
    assert_eq!(jump.assumed_mps, V_MAX_MPS);
    assert!(jump.avg_mps <= before.avg_mps + 0.2, "{jump:?}");
    assert_eq!(jump.last_mps, None, "an impossible step is not a speed");
    // Back on the path: one more step at the top speed, then the walk again.
    h.push(sample(195.0, 3_000.0 - 273.0, 10.0));
    assert_eq!(h.estimate(None).assumed_mps, V_MAX_MPS);
    h.push(sample(210.0, 3_000.0 - 294.0, 10.0));
    let back = h.estimate(None);
    assert!(back.assumed_mps < 15.0, "{back:?}");
    assert!((back.avg_mps - 1.4).abs() < 0.3, "{back:?}");
}

#[test]
fn boarding_a_bus_raises_the_assumed_speed_on_the_first_bus_fix() {
    let mut h =
        history((0..17).map(|i| sample(f64::from(i) * 15.0, 5_000.0 - 21.0 * f64::from(i), 10.0)));
    let walking = h.estimate(None);
    // 15 s later the bus has covered 120 m.
    h.push(sample(255.0, 5_000.0 - 336.0 - 120.0, 10.0));
    let bus = h.estimate(None);
    assert!(
        bus.assumed_mps > walking.assumed_mps + 3.0,
        "{walking:?} {bus:?}"
    );
}

#[test]
fn a_bus_standing_at_a_stop_keeps_its_average() {
    let mut h =
        history((0..20).map(|i| sample(f64::from(i) * 10.0, 8_000.0 - 80.0 * f64::from(i), 10.0)));
    for k in 1..=4 {
        h.push(sample(190.0 + 15.0 * f64::from(k), 8_000.0 - 1_520.0, 10.0));
    }
    let e = h.estimate(Some(0.0));
    assert!(e.avg_mps > 6.0, "{e:?}");
    assert_eq!(e.basis, SpeedBasis::Average);
    assert!((e.assumed_mps - AVG_HEADROOM * e.avg_mps).abs() < 1e-9);
}

#[test]
fn a_long_gap_with_distance_moved_gives_the_top_speed() {
    let h = history([sample(0.0, 9_000.0, 10.0), sample(240.0, 6_000.0, 10.0)]);
    assert_eq!(h.estimate(None).assumed_mps, V_MAX_MPS);
}

#[test]
fn time_going_backwards_clears_the_history() {
    let mut h = history((0..5).map(|i| sample(1_000.0 + f64::from(i) * 15.0, 3_000.0, 10.0)));
    h.record(LastPlan {
        t_ms: 1_060_000,
        next_ms: 15_000,
        high: false,
        assumed_mps: 6.0,
        basis: SpeedBasis::Bound,
    });
    h.push(sample(5.0, 3_000.0, 10.0));
    assert_eq!(h.samples().len(), 1);
    assert_eq!(h.last_plan(), None);
    assert_eq!(h.estimate(None).basis, SpeedBasis::Cold);
    // The same time again replaces the newest fix.
    h.push(sample(5.0, 2_990.0, 10.0));
    assert_eq!(h.samples().len(), 1);
}

#[test]
fn a_provider_speed_tightens_the_bound_but_never_below_the_average() {
    let h =
        history((0..10).map(|i| sample(f64::from(i) * 15.0, 9_000.0 - 120.0 * f64::from(i), 10.0)));
    let bound = h.estimate(None);
    let doppler = h.estimate(Some(8.0));
    assert!(doppler.assumed_mps < bound.assumed_mps);
    assert!(doppler.assumed_mps >= AVG_HEADROOM * doppler.avg_mps - 1e-9);
    // A walker's Doppler speed tightens it to its own bound.
    let w = history((0..3).map(|i| sample(f64::from(i) * 15.0, 700.0 - 21.0 * f64::from(i), 10.0)));
    let e = w.estimate(Some(1.4));
    assert_eq!(e.basis, SpeedBasis::Doppler);
    assert!((e.assumed_mps - (1.2 * 1.4 + 1.0)).abs() < 1e-9);
    // Unusable provider speeds are ignored.
    assert_eq!(w.estimate(Some(f64::NAN)), w.estimate(None));
    assert_eq!(w.estimate(Some(-1.0)), w.estimate(None));
}

#[test]
fn the_window_keeps_five_minutes_and_48_fixes() {
    let h = history((0..100).map(|i| sample(f64::from(i) * 3.0, 9_000.0, 10.0)));
    assert_eq!(h.samples().len(), MAX_SAMPLES);
    let h = history((0..40).map(|i| sample(f64::from(i) * 20.0, 9_000.0, 10.0)));
    let span = h.samples().last().unwrap().t_ms - h.samples()[0].t_ms;
    assert!(span <= 300_000, "{span}");
    assert_eq!(h.samples().len(), 16);
    // Unusable fixes are not kept.
    let mut h = SpeedHistory::default();
    h.push(sample(0.0, 0.0, 151.0));
    h.push(sample(0.0, 0.0, f64::NAN));
    h.push(SpeedSample {
        at: Coord::new(f64::NAN, 0.0),
        ..sample(0.0, 0.0, 5.0)
    });
    assert!(h.is_empty());
}

#[test]
fn history_round_trips_and_an_empty_one_is_left_out() {
    let mut h = history((0..3).map(|i| sample(f64::from(i) * 15.0, 700.0, 10.0)));
    h.record(LastPlan {
        t_ms: 30_000,
        next_ms: 15_000,
        high: true,
        assumed_mps: 6.0,
        basis: SpeedBasis::Average,
    });
    let back: SpeedHistory = serde_json::from_str(&serde_json::to_string(&h).unwrap()).unwrap();
    assert_eq!(back, h);
    assert_eq!(
        serde_json::to_string(&SpeedHistory::default()).unwrap(),
        "{}"
    );
    assert_eq!(
        serde_json::to_string(&SpeedBasis::Doppler).unwrap(),
        "\"doppler\""
    );
    assert_eq!(SpeedBasis::Doppler.name(), "doppler");
}

// ---- 9. the invariant, as a simulation ----

/// One traveller heading straight for the line: `v0` until `switch_s`, then accelerating at `a` up to `top`.
#[derive(Clone, Copy, Debug)]
struct Traveller {
    start_m: f64,
    v0: f64,
    switch_s: f64,
    a: f64,
    top: f64,
}

impl Traveller {
    fn random(rng: &mut Rng) -> Self {
        let v0 = rng.pick(&[0.0, 1.4, 5.0]);
        Self {
            start_m: rng.range(150.0, 8_000.0),
            v0,
            switch_s: rng.range(0.0, 900.0),
            a: rng.range(0.3, A_MAX_MPS2),
            top: rng.range(8.0, V_MAX_MPS).max(v0),
        }
    }

    fn speed(&self, t: f64) -> f64 {
        if t <= self.switch_s {
            self.v0
        } else {
            (self.v0 + self.a * (t - self.switch_s)).min(self.top)
        }
    }

    fn travelled(&self, t: f64) -> f64 {
        if t <= self.switch_s {
            return self.v0 * t;
        }
        let u = t - self.switch_s;
        let t_acc = (self.top - self.v0) / self.a;
        let before = self.v0 * self.switch_s;
        if u <= t_acc {
            before + self.v0 * u + self.a * u * u / 2.0
        } else {
            before + self.v0 * t_acc + self.a * t_acc * t_acc / 2.0 + self.top * (u - t_acc)
        }
    }

    /// Signed metres to the line (its own side is positive).
    fn line(&self, t: f64) -> f64 {
        self.start_m - self.travelled(t)
    }
}

/// P1 to P4 hold (top speed, acceleration, fixes wrong by at most their accuracy, every fix late by up to
/// the whole grace for half the trips): no interval longer than `MIN_POLL` lets the traveller reach the line.
#[test]
fn invariant_simulation_no_fix_interval_skips_the_line() {
    let mut rng = Rng(0x5EED_CADE);
    let mut intervals = 0u32;
    let mut adaptive = 0u32;
    for trip in 0..4_000 {
        let who = Traveller::random(&mut rng);
        let acc = rng.range(5.0, 60.0);
        let late = rng.coin();
        let doppler = rng.coin();
        let mut h = SpeedHistory::default();
        let mut t = 0.0;
        let mut prev: Option<Cadence> = None;
        while who.line(t) > 0.0 && t < 4.0 * 3_600.0 {
            let (r, th) = (
                acc * rng.range(0.0, 1.0).sqrt(),
                rng.range(0.0, std::f64::consts::TAU),
            );
            let north = who.line(t) + r * th.sin();
            h.push(SpeedSample {
                t_ms: (t * 1000.0).round() as i64,
                at: at(r * th.cos(), north),
                accuracy_m: acc,
            });
            let speed = doppler.then(|| (who.speed(t) + rng.range(-0.5, 0.5)).max(0.0));
            let e = h.estimate(speed);
            let line_m = north.abs();
            let c = plan_after(line_m, acc, &e, prev);
            let interval = secs(c.next_fix_in);
            let wait = if late { secs(c.retry_after) } else { interval };
            if interval > secs(MIN_POLL) {
                intervals += 1;
                if c.raw > poll_interval(Some(line_m), Some(acc)) {
                    // The adaptive rules decided: safe with the whole grace, outside the dead band.
                    adaptive += 1;
                    let band = DEAD_BAND_M.max(acc);
                    assert!(
                        who.line(t + wait) >= band - 1e-6,
                        "trip {trip} {who:?} acc {acc} late {late}: {:.1} m at {:.1} s, {c:?}",
                        who.line(t + wait),
                        t + wait
                    );
                } else {
                    // Today's curve decided: its margin covers a fix one whole interval late.
                    assert!(
                        who.line(t + wait.min(2.0 * interval)) > 0.0,
                        "trip {trip} {who:?}"
                    );
                }
            }
            prev = Some(c);
            t += wait;
        }
    }
    assert!(intervals > 50_000, "{intervals}");
    assert!(adaptive > intervals / 2, "{adaptive} of {intervals}");
}

// ---- 10. the worked table ----

/// A steady approach at `v` m/s from 9 km further (accuracy 10 m, exact fixes, each fix when planned):
/// the cadence planned at the first fix at or past `at_m`. `v == 0` stands at `at_m` for ten minutes.
fn steady(v: f64, doppler: bool, at_m: f64) -> Cadence {
    let mut h = SpeedHistory::default();
    let mut t: f64 = 0.0;
    let mut pos = if v > 0.0 { at_m + 9_000.0 } else { at_m };
    let mut prev: Option<Cadence> = None;
    loop {
        h.push(SpeedSample {
            t_ms: (t * 1000.0).round() as i64,
            at: at(0.0, pos),
            accuracy_m: 10.0,
        });
        let c = plan_after(pos, 10.0, &h.estimate(doppler.then_some(v)), prev);
        if (v > 0.0 && pos <= at_m) || (v == 0.0 && t >= 600.0) {
            return c;
        }
        let dt = secs(c.next_fix_in);
        pos -= v * dt;
        t += dt;
        prev = Some(c);
    }
}

const COLUMNS: [f64; 5] = [5_000.0, 2_000.0, 700.0, 300.0, 100.0];

fn row(cell: impl Fn(f64) -> Cadence) -> String {
    COLUMNS
        .iter()
        .map(|m| {
            let c = cell(*m);
            let p = if c.high_accuracy { "H" } else { "b" };
            format!("{} s {p}", c.next_fix_in.as_secs())
        })
        .collect::<Vec<_>>()
        .join(" | ")
}

/// Steady movement, accuracy 10 m: the interval asked for at each distance and the priority
/// (`b` balanced, `H` high accuracy). "Doppler" rows: the provider reports the speed.
#[test]
fn example_table() {
    let cold = |m: f64| {
        plan(&CadenceInput {
            basis: SpeedBasis::Cold,
            ..input(m, 10.0, V_MAX_MPS)
        })
    };
    let today = |m: f64| {
        let d = poll_interval(Some(m), Some(10.0));
        Cadence {
            next_fix_in: Duration::from_secs(d.as_secs()),
            high_accuracy: d <= Duration::from_secs(15),
            ..cold(m)
        }
    };
    let rows = [
        ("Today, any speed", row(today)),
        ("Standing still", row(|m| steady(0.0, false, m))),
        ("Standing still, Doppler", row(|m| steady(0.0, true, m))),
        ("Walking, 1.4 m/s", row(|m| steady(1.4, false, m))),
        ("Walking, 1.4 m/s, Doppler", row(|m| steady(1.4, true, m))),
        ("Bus, 8 m/s", row(|m| steady(8.0, false, m))),
        ("Bus, 8 m/s, Doppler", row(|m| steady(8.0, true, m))),
        ("Train, 25 m/s", row(|m| steady(25.0, false, m))),
        ("Train, 25 m/s, Doppler", row(|m| steady(25.0, true, m))),
        ("First fix (cold)", row(cold)),
    ];
    for (name, cells) in &rows {
        println!("| {name} | {cells} |");
    }
    let expected = [
        "Today, any speed: 41 s b | 16 s b | 5 s H | 3 s H | 3 s H",
        "Standing still: 75 s b | 30 s b | 15 s b | 6 s H | 3 s H",
        "Standing still, Doppler: 75 s b | 30 s b | 20 s b | 10 s H | 3 s H",
        "Walking, 1.4 m/s: 60 s b | 30 s b | 15 s b | 6 s H | 3 s H",
        "Walking, 1.4 m/s, Doppler: 75 s b | 30 s b | 15 s b | 10 s H | 3 s H",
        "Bus, 8 m/s: 50 s b | 25 s b | 10 s H | 4 s H | 3 s H",
        "Bus, 8 m/s, Doppler: 60 s b | 30 s b | 15 s b | 6 s H | 3 s H",
        "Train, 25 m/s: 40 s b | 15 s H | 5 s H | 3 s H | 3 s H",
        "Train, 25 m/s, Doppler: 50 s b | 15 s H | 6 s H | 3 s H | 3 s H",
        "First fix (cold): 40 s b | 15 s b | 5 s H | 3 s H | 3 s H",
    ];
    let got: Vec<String> = rows.iter().map(|(n, c)| format!("{n}: {c}")).collect();
    assert_eq!(got, expected);
}

/// Fixes and seconds of high accuracy for one approach from `from_m` to the line at `v` m/s (or `secs`
/// of standing at `from_m` when `v == 0`), today's curve against the cadence.
fn approach(v: f64, doppler: bool, from_m: f64, stand_s: f64) -> ((u32, f64), (u32, f64)) {
    let run = |adaptive: bool| {
        let mut h = SpeedHistory::default();
        let (mut t, mut pos, mut fixes, mut high_s) = (0.0_f64, from_m, 0u32, 0.0);
        let mut prev: Option<Cadence> = None;
        while (v > 0.0 && pos > 0.0) || (v == 0.0 && t < stand_s) {
            h.push(SpeedSample {
                t_ms: (t * 1000.0).round() as i64,
                at: at(0.0, pos),
                accuracy_m: 10.0,
            });
            let (next, high) = if adaptive {
                let c = plan_after(pos, 10.0, &h.estimate(doppler.then_some(v)), prev);
                prev = Some(c);
                (secs(c.next_fix_in), c.high_accuracy)
            } else {
                let d = poll_interval(Some(pos), Some(10.0));
                (secs(d), d <= Duration::from_secs(15))
            };
            fixes += 1;
            if high {
                high_s += next;
            }
            t += next;
            pos -= v * next;
        }
        (fixes, high_s)
    };
    (run(false), run(true))
}

#[test]
fn whole_approaches_ask_less_often_than_today() {
    let cases = [
        ("Walk the last 5 km", 1.4, false, 5_000.0, 0.0),
        ("Walk the last 5 km, Doppler", 1.4, true, 5_000.0, 0.0),
        ("Walk the last 700 m", 1.4, false, 700.0, 0.0),
        ("Bus, last 5 km", 8.0, false, 5_000.0, 0.0),
        ("Train, last 5 km", 25.0, false, 5_000.0, 0.0),
        ("Stand still at 700 m, one hour", 0.0, false, 700.0, 3_600.0),
    ];
    for (name, v, doppler, from, stand) in cases {
        let ((today, today_high), (new, new_high)) = approach(v, doppler, from, stand);
        println!(
            "| {name} | {today} | {new} | {:.1} min | {:.1} min |",
            today_high / 60.0,
            new_high / 60.0
        );
        assert!(new <= today, "{name}: {new} > {today}");
    }
}

// ---- 11. the arriving-soon target ----

#[test]
fn a_pending_notice_brings_the_next_fix_to_half_the_time_left() {
    for v in [V_FLOOR_MPS, 5.0, 12.0] {
        for line in [1_000.0, 3_000.0, 8_000.0] {
            for notice in [50.0, 300.0, 900.0, 2_500.0] {
                let plain = plan(&input(line, 10.0, v));
                let with = plan(&CadenceInput {
                    notice_m: Some(notice),
                    ..input(line, 10.0, v)
                });
                assert!(with.next_fix_in <= plain.next_fix_in);
                let today = ladder_floor(poll_interval(Some(line), Some(10.0)));
                let half = Duration::from_secs_f64(((notice - 10.0) / (2.0 * v)).max(0.0));
                assert!(
                    with.next_fix_in <= today.max(half),
                    "v {v} line {line} notice {notice}: {with:?}"
                );
                assert!(with.next_fix_in >= today);
            }
            // A message already due (or no target) changes nothing.
            for notice in [None, Some(0.0), Some(-20.0), Some(f64::NAN)] {
                let c = plan(&CadenceInput {
                    notice_m: notice,
                    ..input(line, 10.0, v)
                });
                assert_eq!(c, plan(&input(line, 10.0, v)));
            }
        }
    }
}
