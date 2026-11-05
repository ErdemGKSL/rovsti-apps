//! Map viewport: a local equirectangular projection around a centre point.
//!
//! Screen coordinates are pixels relative to the viewport centre, x right and
//! y down. The caller adds half the widget size to get widget coordinates.
//! Over a city (tens of km) the projection error is far below a pixel.

use crate::coord::{Coord, EARTH_RADIUS_M};

pub const MIN_METRES_PER_PX: f64 = 0.5;
pub const MAX_METRES_PER_PX: f64 = 2000.0;
const MAX_LAT: f64 = 85.0;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Viewport {
    pub center: Coord,
    pub metres_per_px: f64,
}

fn clamp_scale(m: f64) -> f64 {
    if m.is_nan() {
        MAX_METRES_PER_PX
    } else {
        m.clamp(MIN_METRES_PER_PX, MAX_METRES_PER_PX)
    }
}

impl Viewport {
    pub fn new(center: Coord, metres_per_px: f64) -> Self {
        Self {
            center,
            metres_per_px: clamp_scale(metres_per_px),
        }
    }

    fn lon_scale(&self) -> f64 {
        self.center.lat.to_radians().cos().max(1e-6)
    }

    /// Coordinate to pixels relative to the centre.
    pub fn project(&self, c: Coord) -> (f32, f32) {
        let x = (c.lon - self.center.lon).to_radians() * EARTH_RADIUS_M * self.lon_scale();
        let y = -(c.lat - self.center.lat).to_radians() * EARTH_RADIUS_M;
        (
            (x / self.metres_per_px) as f32,
            (y / self.metres_per_px) as f32,
        )
    }

    /// Inverse of [`Self::project`].
    pub fn unproject(&self, (x, y): (f32, f32)) -> Coord {
        let dx = f64::from(x) * self.metres_per_px;
        let dy = f64::from(y) * self.metres_per_px;
        Coord::new(
            (self.center.lat - (dy / EARTH_RADIUS_M).to_degrees()).clamp(-MAX_LAT, MAX_LAT),
            self.center.lon + (dx / (EARTH_RADIUS_M * self.lon_scale())).to_degrees(),
        )
    }

    /// The viewport that shows all `points` in a `width` x `height` box with
    /// `padding` pixels on every side. Zoomed out no further than the clamp,
    /// so far-flung points may fall outside; no points gives a world-ish view.
    pub fn fit(points: &[Coord], width: f32, height: f32, padding: f32) -> Self {
        let Some(first) = points.first() else {
            return Self::new(Coord::new(0.0, 0.0), MAX_METRES_PER_PX);
        };
        let (mut lo, mut hi) = (*first, *first);
        for p in points {
            lo.lat = lo.lat.min(p.lat);
            lo.lon = lo.lon.min(p.lon);
            hi.lat = hi.lat.max(p.lat);
            hi.lon = hi.lon.max(p.lon);
        }
        let center = Coord::new((lo.lat + hi.lat) / 2.0, (lo.lon + hi.lon) / 2.0);
        let cos = center.lat.to_radians().cos().max(1e-6);
        let span_x = (hi.lon - lo.lon).to_radians() * EARTH_RADIUS_M * cos;
        let span_y = (hi.lat - lo.lat).to_radians() * EARTH_RADIUS_M;
        let avail_x = f64::from(width - 2.0 * padding).max(1.0);
        let avail_y = f64::from(height - 2.0 * padding).max(1.0);
        let mpp = (span_x / avail_x).max(span_y / avail_y);
        Self::new(center, mpp)
    }

    /// Zooms by `factor` (above 1 zooms in) keeping the coordinate under the
    /// pixel `focal` fixed on screen.
    pub fn zoom_about(&mut self, factor: f64, focal: (f32, f32)) {
        if !factor.is_finite() || factor <= 0.0 {
            return;
        }
        let anchor = self.unproject(focal);
        self.metres_per_px = clamp_scale(self.metres_per_px / factor);
        // The longitude scale depends on the centre latitude, so settle the
        // centre with a few corrections instead of assuming it is constant.
        for _ in 0..3 {
            let q = self.unproject(focal);
            self.center.lat = (self.center.lat + anchor.lat - q.lat).clamp(-MAX_LAT, MAX_LAT);
            self.center.lon += anchor.lon - q.lon;
        }
    }

    /// Drags the map content by `(dx, dy)` pixels (the centre moves the
    /// other way).
    pub fn pan(&mut self, dx: f32, dy: f32) {
        self.center = self.unproject((-dx, -dy));
    }

    /// Index of the point whose pixel position is closest to `at`, within
    /// `max_px`.
    pub fn nearest_on_screen(
        &self,
        points: &[Coord],
        at: (f32, f32),
        max_px: f32,
    ) -> Option<usize> {
        points
            .iter()
            .enumerate()
            .map(|(i, &p)| {
                let (x, y) = self.project(p);
                (i, (x - at.0).hypot(y - at.1))
            })
            .filter(|&(_, d)| d <= max_px)
            .min_by(|a, b| a.1.total_cmp(&b.1))
            .map(|(i, _)| i)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const IST: Coord = Coord::new(41.0082, 28.9784);

    #[test]
    fn round_trip_within_a_centimetre() {
        for mpp in [0.5, 3.0, 40.0, 2000.0] {
            let v = Viewport::new(IST, mpp);
            let max = (5_000.0 / mpp).min(2_000.0) as f32;
            for (x, y) in [(0.0, 0.0), (max, -max), (-max, max / 3.0), (12.5, 99.25)] {
                let back = v.project(v.unproject((x, y)));
                assert!((back.0 - x).abs() < 1e-2 && (back.1 - y).abs() < 1e-2);
                let c = v.unproject((x, y));
                assert!(c.distance_m(v.unproject(v.project(c))) < 0.01);
            }
        }
    }

    #[test]
    fn axes_and_scale() {
        let v = Viewport::new(IST, 10.0);
        let east = v.project(Coord::new(IST.lat, IST.lon + 0.01));
        let north = v.project(Coord::new(IST.lat + 0.01, IST.lon));
        assert!(east.0 > 0.0 && east.1.abs() < 1e-3);
        assert!(north.1 < 0.0 && north.0.abs() < 1e-3);
        let metres = f64::from(east.0) * 10.0;
        let truth = IST.distance_m(Coord::new(IST.lat, IST.lon + 0.01));
        assert!((metres - truth).abs() / truth < 0.001);
    }

    #[test]
    fn fit_contains_all_points() {
        let pts = [
            Coord::new(41.00, 28.90),
            Coord::new(41.05, 29.10),
            Coord::new(41.02, 29.00),
        ];
        let (w, h, pad) = (400.0, 800.0, 24.0);
        let v = Viewport::fit(&pts, w, h, pad);
        for p in pts {
            let (x, y) = v.project(p);
            assert!(x.abs() <= w / 2.0 - pad + 0.5, "{x}");
            assert!(y.abs() <= h / 2.0 - pad + 0.5, "{y}");
        }
        // The wide axis is the binding one.
        let xs: Vec<f32> = pts.iter().map(|&p| v.project(p).0).collect();
        let span = xs.iter().cloned().fold(f32::MIN, f32::max)
            - xs.iter().cloned().fold(f32::MAX, f32::min);
        assert!((span - (w - 2.0 * pad)).abs() < 1.0, "{span}");
    }

    #[test]
    fn fit_degenerate_inputs() {
        let one = Viewport::fit(&[IST], 300.0, 300.0, 20.0);
        assert_eq!(one.metres_per_px, MIN_METRES_PER_PX);
        let none = Viewport::fit(&[], 300.0, 300.0, 20.0);
        assert_eq!(none.metres_per_px, MAX_METRES_PER_PX);
        let tiny = Viewport::fit(&[IST, IST], 10.0, 10.0, 50.0);
        assert!(tiny.metres_per_px.is_finite());
    }

    #[test]
    fn zoom_keeps_focal_point() {
        let mut v = Viewport::new(IST, 20.0);
        let focal = (120.0, -80.0);
        let before = v.unproject(focal);
        for f in [2.0, 0.5, 1.7] {
            v.zoom_about(f, focal);
            assert!(v.unproject(focal).distance_m(before) < 0.01, "{f}");
        }
        assert!(v.metres_per_px < 20.0);
    }

    #[test]
    fn scale_is_clamped() {
        let mut v = Viewport::new(IST, 1.0);
        v.zoom_about(1000.0, (0.0, 0.0));
        assert_eq!(v.metres_per_px, MIN_METRES_PER_PX);
        v.zoom_about(1e-9, (0.0, 0.0));
        assert_eq!(v.metres_per_px, MAX_METRES_PER_PX);
        v.zoom_about(f64::NAN, (0.0, 0.0));
        assert_eq!(v.metres_per_px, MAX_METRES_PER_PX);
    }

    #[test]
    fn pan_moves_content_with_the_finger() {
        let mut v = Viewport::new(IST, 5.0);
        let p = v.unproject((30.0, 40.0));
        v.pan(10.0, -5.0);
        let (x, y) = v.project(p);
        assert!((x - 40.0).abs() < 1e-2 && (y - 35.0).abs() < 1e-2);
    }

    #[test]
    fn nearest_on_screen_picks_closest_within_radius() {
        let v = Viewport::new(IST, 10.0);
        let pts = [
            v.unproject((100.0, 100.0)),
            v.unproject((10.0, 10.0)),
            v.unproject((14.0, 10.0)),
        ];
        assert_eq!(v.nearest_on_screen(&pts, (13.0, 10.0), 20.0), Some(2));
        assert_eq!(v.nearest_on_screen(&pts, (50.0, 50.0), 5.0), None);
        assert_eq!(v.nearest_on_screen(&[], (0.0, 0.0), 5.0), None);
    }
}
