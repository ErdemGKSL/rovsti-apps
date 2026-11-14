/// Mean Earth radius (IUGG), metres.
pub const EARTH_RADIUS_M: f64 = 6_371_008.8;

/// WGS84 latitude/longitude in degrees.
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Coord {
    pub lat: f64,
    pub lon: f64,
}

impl Coord {
    pub const fn new(lat: f64, lon: f64) -> Self {
        Self { lat, lon }
    }

    /// Great-circle distance in metres (haversine; < 0.5 % error, far below
    /// GPS noise at the distances an arrival alarm cares about).
    pub fn distance_m(self, other: Coord) -> f64 {
        let (lat1, lat2) = (self.lat.to_radians(), other.lat.to_radians());
        let dlat = lat2 - lat1;
        let dlon = (other.lon - self.lon).to_radians();
        let a = (dlat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (dlon / 2.0).sin().powi(2);
        2.0 * EARTH_RADIUS_M * a.sqrt().asin()
    }

    /// Initial great-circle bearing towards `to`, degrees clockwise from
    /// north in `0.0..360.0` (0 when the points coincide).
    pub fn bearing_deg(self, to: Coord) -> f64 {
        let (lat1, lat2) = (self.lat.to_radians(), to.lat.to_radians());
        let dlon = (to.lon - self.lon).to_radians();
        let y = dlon.sin() * lat2.cos();
        let x = lat1.cos() * lat2.sin() - lat1.sin() * lat2.cos() * dlon.cos();
        let b = y.atan2(x).to_degrees().rem_euclid(360.0);
        if b.is_finite() && b < 360.0 { b } else { 0.0 }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_distance() {
        // Taksim Square → Kadıköy pier, Istanbul: about 5.0 km.
        let taksim = Coord::new(41.0370, 28.9850);
        let kadikoy = Coord::new(40.9917, 29.0233);
        let d = taksim.distance_m(kadikoy);
        assert!((5_700.0..6_100.0).contains(&d), "{d}");
    }

    #[test]
    fn bearing_cardinal_directions() {
        let o = Coord::new(10.0, 20.0);
        assert!((o.bearing_deg(Coord::new(11.0, 20.0)) - 0.0).abs() < 1e-6);
        assert!((o.bearing_deg(Coord::new(9.0, 20.0)) - 180.0).abs() < 1e-6);
        let e = Coord::new(0.0, 0.0).bearing_deg(Coord::new(0.0, 1.0));
        assert!((e - 90.0).abs() < 1e-6, "{e}");
        let w = Coord::new(0.0, 0.0).bearing_deg(Coord::new(0.0, -1.0));
        assert!((w - 270.0).abs() < 1e-6, "{w}");
        assert_eq!(o.bearing_deg(o), 0.0);
    }

    #[test]
    fn bearing_across_antimeridian() {
        let e = Coord::new(0.0, 179.5).bearing_deg(Coord::new(0.0, -179.5));
        assert!((e - 90.0).abs() < 1e-6, "{e}");
        let w = Coord::new(0.0, -179.5).bearing_deg(Coord::new(0.0, 179.5));
        assert!((w - 270.0).abs() < 1e-6, "{w}");
    }

    #[test]
    fn zero_distance() {
        let p = Coord::new(52.52, 13.405);
        assert!(p.distance_m(p) < 1e-6);
    }
}
