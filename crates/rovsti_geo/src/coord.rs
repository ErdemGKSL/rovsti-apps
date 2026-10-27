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
    fn zero_distance() {
        let p = Coord::new(52.52, 13.405);
        assert!(p.distance_m(p) < 1e-6);
    }
}
