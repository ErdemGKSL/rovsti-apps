//! The "diameter crossing" trigger: two points A and B define a circle with AB
//! as its diameter. The line AB splits the circle into a left and a right half
//! (facing A toward B); crossing from one half to the other fires the alarm,
//! in either direction.
//!
//! All geometry runs in local metres (equirectangular projection around the
//! midpoint of AB), which is accurate for city-scale circles. The diameter is
//! capped at [`MAX_DIAMETER_M`] for that reason.

use std::fmt;

use serde::{Deserialize, Serialize};

use crate::coord::{Coord, EARTH_RADIUS_M};
use crate::trip::{Fix, MAX_ACCURACY_M};

/// Half-width of the band around line AB where the side is unknown (the
/// effective band is `max(DEAD_BAND_M, fix accuracy)`).
pub const DEAD_BAND_M: f64 = 10.0;
/// Shortest allowed |AB|.
pub const MIN_DIAMETER_M: f64 = 20.0;
/// Longest allowed |AB|.
pub const MAX_DIAMETER_M: f64 = 50_000.0;

/// Tolerance on the segment parameters so that touching an end point counts.
const PARAM_EPS: f64 = 1e-6;

/// Why two points cannot form a diameter.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DiameterError {
    InvalidCoord,
    SamePoint,
    TooShort,
    TooLong,
}

impl DiameterError {
    /// A sentence for the user.
    pub fn message(self) -> &'static str {
        match self {
            Self::InvalidCoord => "These coordinates are not on Earth.",
            Self::SamePoint => "A and B are the same place.",
            Self::TooShort => "A and B must be at least 20 m apart.",
            Self::TooLong => "A and B must be at most 50 km apart.",
        }
    }
}

impl fmt::Display for DiameterError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for DiameterError {}

/// Which half of the circle a position is in, facing from A toward B.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Side {
    Left,
    Right,
}

impl Side {
    pub fn other(self) -> Self {
        match self {
            Self::Left => Self::Right,
            Self::Right => Self::Left,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Left => "Left",
            Self::Right => "Right",
        }
    }
}

/// A side, or "too close to the line to tell".
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SideReading {
    Side(Side),
    OnLine,
}

#[derive(Serialize, Deserialize)]
struct DiameterRaw {
    a: Coord,
    b: Coord,
}

impl TryFrom<DiameterRaw> for Diameter {
    type Error = DiameterError;

    fn try_from(raw: DiameterRaw) -> Result<Self, Self::Error> {
        Diameter::new(raw.a, raw.b)
    }
}

impl From<Diameter> for DiameterRaw {
    fn from(d: Diameter) -> Self {
        DiameterRaw { a: d.a, b: d.b }
    }
}

/// Two validated points; the circle has AB as its diameter.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(try_from = "DiameterRaw", into = "DiameterRaw")]
pub struct Diameter {
    a: Coord,
    b: Coord,
}

fn valid_coord(c: Coord) -> bool {
    c.lat.is_finite()
        && c.lon.is_finite()
        && (-90.0..=90.0).contains(&c.lat)
        && (-180.0..=180.0).contains(&c.lon)
}

fn wrap180(deg: f64) -> f64 {
    (deg + 180.0).rem_euclid(360.0) - 180.0
}

fn project(center: Coord, c: Coord) -> (f64, f64) {
    let x =
        wrap180(c.lon - center.lon).to_radians() * EARTH_RADIUS_M * center.lat.to_radians().cos();
    let y = (c.lat - center.lat).to_radians() * EARTH_RADIUS_M;
    (x, y)
}

fn unproject(center: Coord, (x, y): (f64, f64)) -> Coord {
    let cos = center.lat.to_radians().cos().max(1e-6);
    Coord {
        lat: center.lat + (y / EARTH_RADIUS_M).to_degrees(),
        lon: wrap180(center.lon + (x / (EARTH_RADIUS_M * cos)).to_degrees()),
    }
}

fn cross((ax, ay): (f64, f64), (bx, by): (f64, f64)) -> f64 {
    ax * by - ay * bx
}

fn sub(a: (f64, f64), b: (f64, f64)) -> (f64, f64) {
    (a.0 - b.0, a.1 - b.1)
}

fn norm(v: (f64, f64)) -> f64 {
    v.0.hypot(v.1)
}

impl Diameter {
    pub fn new(a: Coord, b: Coord) -> Result<Self, DiameterError> {
        if !valid_coord(a) || !valid_coord(b) {
            return Err(DiameterError::InvalidCoord);
        }
        if a == b {
            return Err(DiameterError::SamePoint);
        }
        let d = Self { a, b };
        let len = d.length_m();
        if len < MIN_DIAMETER_M {
            Err(DiameterError::TooShort)
        } else if len > MAX_DIAMETER_M {
            Err(DiameterError::TooLong)
        } else {
            Ok(d)
        }
    }

    pub fn a(&self) -> Coord {
        self.a
    }

    pub fn b(&self) -> Coord {
        self.b
    }

    /// Midpoint of AB (longitude handled across the antimeridian).
    pub fn center(&self) -> Coord {
        Coord {
            lat: (self.a.lat + self.b.lat) / 2.0,
            lon: wrap180(self.a.lon + wrap180(self.b.lon - self.a.lon) / 2.0),
        }
    }

    /// |AB| in metres, measured in the local frame.
    pub fn length_m(&self) -> f64 {
        let c = self.center();
        norm(sub(project(c, self.b), project(c, self.a)))
    }

    pub fn radius_m(&self) -> f64 {
        self.length_m() / 2.0
    }

    /// The same diameter with A and B exchanged (left and right swap).
    pub fn swapped(&self) -> Self {
        Self {
            a: self.b,
            b: self.a,
        }
    }

    /// Local metres (east, north) of `c` around the midpoint.
    pub fn local(&self, c: Coord) -> (f64, f64) {
        project(self.center(), c)
    }

    pub fn from_local(&self, p: (f64, f64)) -> Coord {
        unproject(self.center(), p)
    }

    fn ab_local(&self) -> ((f64, f64), (f64, f64)) {
        (self.local(self.a), self.local(self.b))
    }

    /// Signed distance from line AB in metres; positive is left, facing A -> B.
    pub fn offset_m(&self, c: Coord) -> f64 {
        let (a, b) = self.ab_local();
        let ab = sub(b, a);
        cross(ab, sub(self.local(c), a)) / norm(ab)
    }

    /// Distance to the segment AB (not the infinite line), metres.
    pub fn distance_to_segment_m(&self, c: Coord) -> f64 {
        let (a, b) = self.ab_local();
        let ab = sub(b, a);
        let ap = sub(self.local(c), a);
        let t = ((ap.0 * ab.0 + ap.1 * ab.1) / (ab.0 * ab.0 + ab.1 * ab.1)).clamp(0.0, 1.0);
        norm((ap.0 - t * ab.0, ap.1 - t * ab.1))
    }

    /// Distance to the circle's edge; 0 inside.
    pub fn distance_to_circle_m(&self, c: Coord) -> f64 {
        (norm(self.local(c)) - self.radius_m()).max(0.0)
    }

    pub fn contains(&self, c: Coord) -> bool {
        norm(self.local(c)) <= self.radius_m()
    }

    /// Which side `c` is on; within `max(10 m, accuracy)` of the line it is
    /// [`SideReading::OnLine`].
    pub fn read_side(&self, c: Coord, accuracy_m: f64) -> SideReading {
        let off = self.offset_m(c);
        let band = DEAD_BAND_M.max(if accuracy_m.is_finite() {
            accuracy_m
        } else {
            0.0
        });
        if off.abs() <= band {
            SideReading::OnLine
        } else if off > 0.0 {
            SideReading::Side(Side::Left)
        } else {
            SideReading::Side(Side::Right)
        }
    }

    /// Does the straight path `p -> q` intersect the segment AB (ends included)?
    pub fn segment_crosses(&self, p: Coord, q: Coord) -> bool {
        let (a, b) = self.ab_local();
        let d = sub(b, a);
        let (p, q) = (self.local(p), self.local(q));
        let e = sub(q, p);
        let den = cross(d, e);
        if den.abs() < 1e-12 {
            // Parallel or degenerate path.
            return false;
        }
        let ap = sub(p, a);
        let t = cross(ap, e) / den;
        let u = cross(ap, d) / den;
        (-PARAM_EPS..=1.0 + PARAM_EPS).contains(&t) && (-PARAM_EPS..=1.0 + PARAM_EPS).contains(&u)
    }

    /// The circle as a polygon of `n` (at least 8) points.
    pub fn outline(&self, n: usize) -> Vec<Coord> {
        let n = n.max(8);
        let r = self.radius_m();
        (0..n)
            .map(|i| {
                let th = std::f64::consts::TAU * i as f64 / n as f64;
                self.from_local((r * th.cos(), r * th.sin()))
            })
            .collect()
    }

    /// One half of the disc as a polygon: A, an arc of `n` (at least 2)
    /// segments on `side`, then B.
    pub fn half_disc(&self, side: Side, n: usize) -> Vec<Coord> {
        let n = n.max(2);
        let (a, b) = self.ab_local();
        let ab = sub(b, a);
        let len = norm(ab);
        let u = (ab.0 / len, ab.1 / len);
        let left = (-u.1, u.0);
        let s = match side {
            Side::Left => 1.0,
            Side::Right => -1.0,
        };
        let r = len / 2.0;
        let mut out = Vec::with_capacity(n + 1);
        out.push(self.a);
        for i in 1..n {
            let phi = std::f64::consts::PI * (1.0 - i as f64 / n as f64);
            let (c, sn) = (r * phi.cos(), r * phi.sin() * s);
            out.push(self.from_local((c * u.0 + sn * left.0, c * u.1 + sn * left.1)));
        }
        out.push(self.b);
        out
    }
}

/// A confirmed crossing of the diameter.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Crossing {
    pub from: Side,
    pub to: Side,
}

/// Remembers the last confident side and detects crossings of segment AB.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct CrossingWatch {
    last: Option<(Side, Coord)>,
}

impl CrossingWatch {
    /// The last confident side, if any.
    pub fn side(&self) -> Option<Side> {
        self.last.map(|(s, _)| s)
    }

    /// Feed one fix. Returns a [`Crossing`] when the confident side changed
    /// and the path from the last confident fix crosses segment AB.
    pub fn observe(&mut self, d: &Diameter, fix: &Fix) -> Option<Crossing> {
        if fix.accuracy_m.is_nan() || fix.accuracy_m > MAX_ACCURACY_M {
            return None;
        }
        let SideReading::Side(now) = d.read_side(fix.at, fix.accuracy_m) else {
            return None;
        };
        let prev = self.last.replace((now, fix.at));
        let (was, from_at) = prev?;
        (was != now && d.segment_crosses(from_at, fix.at))
            .then_some(Crossing { from: was, to: now })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ISTANBUL: Coord = Coord::new(41.0, 29.0);

    fn fix(at: Coord, accuracy_m: f64) -> Fix {
        Fix {
            at,
            accuracy_m,
            speed_mps: None,
        }
    }

    /// Diameter of `len` metres centred on `center`, pointing east.
    fn east(center: Coord, len: f64) -> Diameter {
        let h = len / 2.0;
        Diameter::new(unproject(center, (-h, 0.0)), unproject(center, (h, 0.0))).expect("valid")
    }

    fn north(center: Coord, len: f64) -> Diameter {
        let h = len / 2.0;
        Diameter::new(unproject(center, (0.0, -h)), unproject(center, (0.0, h))).expect("valid")
    }

    #[test]
    fn validation() {
        let d =
            |m: f64| Diameter::new(ISTANBUL, unproject(ISTANBUL, (m, 0.0))).map(|d| d.length_m());
        assert_eq!(d(19.0), Err(DiameterError::TooShort));
        assert!(d(20.5).is_ok());
        assert!(d(49_990.0).is_ok());
        assert_eq!(d(60_000.0), Err(DiameterError::TooLong));
        assert_eq!(
            Diameter::new(ISTANBUL, ISTANBUL),
            Err(DiameterError::SamePoint)
        );
        assert_eq!(
            Diameter::new(Coord::new(f64::NAN, 29.0), ISTANBUL),
            Err(DiameterError::InvalidCoord)
        );
        assert_eq!(
            Diameter::new(Coord::new(91.0, 29.0), ISTANBUL),
            Err(DiameterError::InvalidCoord)
        );
        assert_eq!(
            Diameter::new(ISTANBUL, Coord::new(41.0, 181.0)),
            Err(DiameterError::InvalidCoord)
        );
        for e in [
            DiameterError::InvalidCoord,
            DiameterError::SamePoint,
            DiameterError::TooShort,
            DiameterError::TooLong,
        ] {
            assert_eq!(e.to_string(), e.message());
        }
    }

    #[test]
    fn exact_limits_are_accepted() {
        let at = |m: f64| {
            let c = Coord::new(41.0, 29.0);
            let a = unproject(c, (-m / 2.0, 0.0));
            let b = unproject(c, (m / 2.0, 0.0));
            Diameter::new(a, b)
        };
        // Floating point may land a hair either side; the limits are about 20 m / 50 km.
        assert!(at(20.001).is_ok());
        assert!(at(49_999.9).is_ok());
    }

    #[test]
    fn center_radius_and_round_trip() {
        let d = east(ISTANBUL, 2_000.0);
        assert!(d.center().distance_m(ISTANBUL) < 0.01);
        assert!((d.length_m() - 2_000.0).abs() < 0.01);
        assert!((d.radius_m() - 1_000.0).abs() < 0.01);
        for p in [(0.0, 0.0), (500.0, -300.0), (-1200.0, 900.0)] {
            let back = d.local(d.from_local(p));
            assert!((back.0 - p.0).abs() < 0.01 && (back.1 - p.1).abs() < 0.01);
        }
    }

    #[test]
    fn left_is_positive() {
        let e = east(ISTANBUL, 1_000.0);
        let off = e.offset_m(e.from_local((100.0, 300.0)));
        assert!((off - 300.0).abs() < 0.01, "{off}");
        assert!(e.offset_m(e.from_local((100.0, -300.0))) < -299.0);

        // Facing north, left is west.
        let n = north(ISTANBUL, 1_000.0);
        assert!(n.offset_m(n.from_local((-300.0, 50.0))) > 299.0);
        assert!(n.offset_m(n.from_local((300.0, 50.0))) < -299.0);
    }

    #[test]
    fn swapped_flips_sides() {
        let e = east(ISTANBUL, 1_000.0);
        let p = e.from_local((100.0, 300.0));
        let s = e.swapped();
        assert_eq!(s.a(), e.b());
        assert_eq!(e.read_side(p, 5.0), SideReading::Side(Side::Left));
        assert_eq!(s.read_side(p, 5.0), SideReading::Side(Side::Right));
        assert!((s.length_m() - e.length_m()).abs() < 1e-6);
    }

    #[test]
    fn dead_band_uses_max_of_10_and_accuracy() {
        let e = east(ISTANBUL, 1_000.0);
        let at = |y: f64| e.from_local((0.0, y));
        assert_eq!(e.read_side(at(9.0), 3.0), SideReading::OnLine);
        assert_eq!(e.read_side(at(-9.0), 3.0), SideReading::OnLine);
        assert_eq!(e.read_side(at(12.0), 3.0), SideReading::Side(Side::Left));
        assert_eq!(e.read_side(at(-12.0), 3.0), SideReading::Side(Side::Right));
        // Accuracy 30 m widens the band.
        assert_eq!(e.read_side(at(25.0), 30.0), SideReading::OnLine);
        assert_eq!(e.read_side(at(35.0), 30.0), SideReading::Side(Side::Left));
    }

    #[test]
    fn segment_crossing_cases() {
        let e = east(ISTANBUL, 1_000.0);
        let c = |x: f64, y: f64| e.from_local((x, y));
        assert!(e.segment_crosses(c(0.0, -100.0), c(0.0, 100.0)));
        assert!(e.segment_crosses(c(100.0, 100.0), c(-100.0, -100.0)));
        // Through the end points A and B.
        assert!(e.segment_crosses(c(-500.0, -100.0), c(-500.0, 100.0)));
        assert!(e.segment_crosses(c(500.0, -100.0), c(500.0, 100.0)));
        // Beyond either end.
        assert!(!e.segment_crosses(c(-600.0, -100.0), c(-600.0, 100.0)));
        assert!(!e.segment_crosses(c(600.0, -100.0), c(600.0, 100.0)));
        // Parallel, and a path that stops short of the line.
        assert!(!e.segment_crosses(c(-100.0, 50.0), c(100.0, 50.0)));
        assert!(!e.segment_crosses(c(0.0, 100.0), c(0.0, 20.0)));
    }

    #[test]
    fn distances_and_containment() {
        let e = east(ISTANBUL, 2_000.0);
        let c = |x: f64, y: f64| e.from_local((x, y));
        assert!(e.distance_to_segment_m(c(0.0, 300.0)) - 300.0 < 0.01);
        assert!((e.distance_to_segment_m(c(0.0, 300.0)) - 300.0).abs() < 0.01);
        // Past B's end the distance is to the end point, not the line.
        assert!((e.distance_to_segment_m(c(1_400.0, 300.0)) - 500.0).abs() < 0.05);
        assert!(e.distance_to_circle_m(c(0.0, 0.0)) == 0.0);
        assert!((e.distance_to_circle_m(c(1_500.0, 0.0)) - 500.0).abs() < 0.05);
        assert!(e.contains(c(0.0, 900.0)));
        assert!(!e.contains(c(0.0, 1_100.0)));
        assert!(!e.contains(c(800.0, 800.0)));
    }

    #[test]
    fn works_at_lat_60_and_across_the_antimeridian() {
        for center in [Coord::new(60.0, 10.0), Coord::new(41.0, 179.99)] {
            let e = east(center, 2_000.0);
            assert!((e.length_m() - 2_000.0).abs() < 0.01);
            assert!(e.center().distance_m(center) < 0.5);
            assert!((e.offset_m(e.from_local((0.0, 400.0))) - 400.0).abs() < 0.01);
            // Real geodesic distance agrees with the projection.
            assert!((e.a().distance_m(e.b()) - 2_000.0).abs() < 15.0);
            let mut w = CrossingWatch::default();
            assert_eq!(w.observe(&e, &fix(e.from_local((0.0, 300.0)), 5.0)), None);
            let got = w.observe(&e, &fix(e.from_local((0.0, -300.0)), 5.0));
            assert_eq!(
                got,
                Some(Crossing {
                    from: Side::Left,
                    to: Side::Right
                })
            );
        }
        // The antimeridian fixture really wraps.
        let e = east(Coord::new(41.0, 179.99), 2_000.0);
        assert!(e.a().lon > 0.0 && e.b().lon < 0.0);
    }

    fn run(d: &Diameter, pts: &[(f64, f64)], acc: f64) -> Vec<Option<Crossing>> {
        let mut w = CrossingWatch::default();
        pts.iter()
            .map(|&p| w.observe(d, &fix(d.from_local(p), acc)))
            .collect()
    }

    #[test]
    fn fires_left_to_right_and_back() {
        let d = east(ISTANBUL, 2_000.0);
        let l2r = run(
            &d,
            &[(0.0, 300.0), (0.0, 100.0), (0.0, -100.0), (0.0, -300.0)],
            5.0,
        );
        assert_eq!(
            l2r,
            vec![
                None,
                None,
                Some(Crossing {
                    from: Side::Left,
                    to: Side::Right
                }),
                None
            ]
        );
        let r2l = run(&d, &[(-200.0, -300.0), (-200.0, 300.0)], 5.0);
        assert_eq!(
            r2l[1],
            Some(Crossing {
                from: Side::Right,
                to: Side::Left
            })
        );
    }

    #[test]
    fn fires_on_sparse_jump() {
        let d = east(ISTANBUL, 5_000.0);
        let got = run(&d, &[(0.0, 2_000.0), (0.0, -1_000.0)], 8.0);
        assert_eq!(
            got[1],
            Some(Crossing {
                from: Side::Left,
                to: Side::Right
            })
        );
    }

    #[test]
    fn going_round_outside_does_not_fire() {
        let d = east(ISTANBUL, 2_000.0);
        // Left to right past B's end (x = 1100 m, B is at 1000 m): misses AB.
        let mut w = CrossingWatch::default();
        assert_eq!(
            w.observe(&d, &fix(d.from_local((1_100.0, 300.0)), 5.0)),
            None
        );
        assert_eq!(
            w.observe(&d, &fix(d.from_local((1_100.0, -300.0)), 5.0)),
            None
        );
        // The side was still updated, so a later real crossing fires.
        assert_eq!(w.side(), Some(Side::Right));
        let back = w.observe(&d, &fix(d.from_local((0.0, 300.0)), 5.0));
        assert_eq!(
            back,
            Some(Crossing {
                from: Side::Right,
                to: Side::Left
            })
        );
        // Same beyond A's end.
        let got = run(&d, &[(-1_100.0, -300.0), (-1_100.0, 300.0)], 5.0);
        assert_eq!(got, vec![None, None]);
    }

    #[test]
    fn jitter_on_the_line_never_fires() {
        let d = east(ISTANBUL, 2_000.0);
        let mut w = CrossingWatch::default();
        assert_eq!(w.observe(&d, &fix(d.from_local((0.0, 200.0)), 10.0)), None);
        for y in [5.0, -5.0, 8.0, -8.0, 5.0] {
            assert_eq!(w.observe(&d, &fix(d.from_local((0.0, y)), 10.0)), None);
        }
        assert_eq!(w.side(), Some(Side::Left));
    }

    #[test]
    fn on_line_fix_keeps_last_side() {
        let d = east(ISTANBUL, 2_000.0);
        let got = run(&d, &[(0.0, 200.0), (0.0, 2.0), (0.0, -200.0)], 5.0);
        assert_eq!(
            got,
            vec![
                None,
                None,
                Some(Crossing {
                    from: Side::Left,
                    to: Side::Right
                })
            ]
        );
    }

    #[test]
    fn inaccurate_fix_is_ignored() {
        let d = east(ISTANBUL, 2_000.0);
        let mut w = CrossingWatch::default();
        assert_eq!(w.observe(&d, &fix(d.from_local((0.0, 300.0)), 5.0)), None);
        assert_eq!(
            w.observe(&d, &fix(d.from_local((0.0, -300.0)), 2_000.0)),
            None
        );
        assert_eq!(
            w.observe(&d, &fix(d.from_local((0.0, -300.0)), f64::NAN)),
            None
        );
        assert_eq!(w.side(), Some(Side::Left));
    }

    #[test]
    fn half_disc_stays_on_its_side() {
        for d in [
            east(ISTANBUL, 2_000.0),
            north(Coord::new(60.0, 10.0), 1_000.0),
        ] {
            for side in [Side::Left, Side::Right] {
                let pts = d.half_disc(side, 16);
                assert_eq!(pts.len(), 17);
                assert_eq!(pts[0], d.a());
                assert_eq!(pts[16], d.b());
                for p in &pts {
                    let off = d.offset_m(*p);
                    match side {
                        Side::Left => assert!(off >= -1e-3, "{off}"),
                        Side::Right => assert!(off <= 1e-3, "{off}"),
                    }
                    assert!(d.local(*p).0.hypot(d.local(*p).1) <= d.radius_m() + 0.01);
                }
            }
        }
        let o = east(ISTANBUL, 1_000.0).outline(32);
        assert_eq!(o.len(), 32);
    }

    #[test]
    fn side_helpers() {
        assert_eq!(Side::Left.other(), Side::Right);
        assert_eq!(Side::Right.other(), Side::Left);
        assert_eq!(Side::Left.label(), "Left");
        assert_eq!(Side::Right.label(), "Right");
        assert_eq!(
            serde_json::to_string(&Side::Left).expect("json"),
            "\"left\""
        );
    }

    #[test]
    fn serde_round_trip_and_rejection() {
        let d = east(ISTANBUL, 1_000.0);
        let json = serde_json::to_string(&d).expect("json");
        let back: Diameter = serde_json::from_str(&json).expect("parse");
        assert!(back.a().distance_m(d.a()) < 1e-6 && back.b().distance_m(d.b()) < 1e-6);

        let same = r#"{"a":{"lat":41.0,"lon":29.0},"b":{"lat":41.0,"lon":29.0}}"#;
        assert!(serde_json::from_str::<Diameter>(same).is_err());
        let short = r#"{"a":{"lat":41.0,"lon":29.0},"b":{"lat":41.00001,"lon":29.0}}"#;
        assert!(serde_json::from_str::<Diameter>(short).is_err());

        let mut w = CrossingWatch::default();
        w.observe(&d, &fix(d.from_local((0.0, 200.0)), 5.0));
        let wj = serde_json::to_string(&w).expect("json");
        let wb: CrossingWatch = serde_json::from_str(&wj).expect("parse");
        assert_eq!(wb.side(), w.side());
        assert_eq!(wb.side(), Some(Side::Left));
    }
}
