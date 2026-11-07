//! The map's measure tool: an ordered polyline of tapped points.

use crate::{Coord, Diameter};

/// Most points a measurement keeps.
pub const MAX_POINTS: usize = 50;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Measure {
    points: Vec<Coord>,
}

impl Measure {
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds a point; false (and nothing added) once `MAX_POINTS` is reached.
    pub fn push(&mut self, c: Coord) -> bool {
        if self.points.len() >= MAX_POINTS {
            return false;
        }
        self.points.push(c);
        true
    }

    /// Removes and returns the last point.
    pub fn undo(&mut self) -> Option<Coord> {
        self.points.pop()
    }

    pub fn clear(&mut self) {
        self.points.clear();
    }

    pub fn points(&self) -> &[Coord] {
        &self.points
    }

    /// Distance of each segment in metres (one fewer than the points).
    pub fn legs_m(&self) -> Vec<f64> {
        self.points
            .windows(2)
            .map(|w| w[0].distance_m(w[1]))
            .collect()
    }

    pub fn total_m(&self) -> f64 {
        self.legs_m().iter().sum()
    }

    /// "1.4 km, 3 legs"; empty while there is no leg.
    pub fn summary_text(&self) -> String {
        let legs = self.points.len().saturating_sub(1);
        if legs == 0 {
            return String::new();
        }
        format!(
            "{}, {legs} {}",
            crate::format_distance(self.total_m()),
            if legs == 1 { "leg" } else { "legs" }
        )
    }

    /// The polyline as a GPX file with one track segment.
    pub fn to_gpx(&self, name: &str) -> String {
        let mut out = String::from(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Rovsti Stops\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n",
        );
        out.push_str(&format!(
            "  <trk>\n    <name>{}</name>\n    <trkseg>\n",
            crate::places_io::escape(name)
        ));
        for p in &self.points {
            out.push_str(&format!(
                "      <trkpt lat=\"{:.6}\" lon=\"{:.6}\"/>\n",
                p.lat, p.lon
            ));
        }
        out.push_str("    </trkseg>\n  </trk>\n</gpx>\n");
        out
    }

    /// First and last point as a gate, when they form a valid diameter.
    pub fn as_gate(&self) -> Option<(Coord, Coord)> {
        let (a, b) = (*self.points.first()?, *self.points.last()?);
        Diameter::new(a, b).ok().map(|_| (a, b))
    }

    /// File name for an export made on `today` (YYYY-MM-DD).
    pub fn export_file_name(today: &str) -> String {
        format!("rovsti-stops-measure-{today}.gpx")
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn line(n: usize) -> Measure {
        let mut m = Measure::new();
        for i in 0..n {
            m.push(Coord::new(41.0 + i as f64 * 0.005, 29.0));
        }
        m
    }

    #[test]
    fn summary_text_counts_legs() {
        assert_eq!(line(0).summary_text(), "");
        assert_eq!(line(1).summary_text(), "");
        assert_eq!(line(2).summary_text(), "560 m, 1 leg");
        assert_eq!(line(4).summary_text(), "1.7 km, 3 legs");
    }

    #[test]
    fn gpx_has_one_point_each_and_escapes() {
        let g = line(3).to_gpx("a & <b>");
        assert_eq!(g.matches("<trkpt").count(), 3);
        assert_eq!(g.matches("<trkseg>").count(), 1);
        assert!(g.contains("<name>a &amp; &lt;b&gt;</name>"));
        assert!(g.contains("lat=\"41.005000\" lon=\"29.000000\""));
    }

    #[test]
    fn gate_needs_a_long_enough_pair() {
        assert_eq!(line(1).as_gate(), None);
        let mut short = Measure::new();
        short.push(Coord::new(41.0, 29.0));
        short.push(Coord::new(41.00005, 29.0));
        assert_eq!(short.as_gate(), None);
        let m = line(3);
        assert_eq!(m.as_gate(), Some((m.points()[0], m.points()[2])));
    }

    #[test]
    fn export_name() {
        assert_eq!(
            Measure::export_file_name("2026-11-07"),
            "rovsti-stops-measure-2026-11-07.gpx"
        );
    }

    #[test]
    fn totals_are_sum_of_legs() {
        let mut m = Measure::new();
        for c in [(0.0, 0.0), (0.0, 0.01), (0.01, 0.01)] {
            assert!(m.push(Coord::new(c.0, c.1)));
        }
        let legs = m.legs_m();
        assert_eq!(legs.len(), 2);
        assert!((m.total_m() - (legs[0] + legs[1])).abs() < 1e-9);
        assert!(m.total_m() > 2000.0);
    }

    #[test]
    fn few_points_have_no_legs() {
        let mut m = Measure::new();
        assert_eq!(m.total_m(), 0.0);
        m.push(Coord::new(1.0, 1.0));
        assert!(m.legs_m().is_empty());
        assert_eq!(m.total_m(), 0.0);
    }

    #[test]
    fn undo_and_clear() {
        let mut m = Measure::new();
        assert_eq!(m.undo(), None);
        m.push(Coord::new(1.0, 1.0));
        m.push(Coord::new(2.0, 2.0));
        assert_eq!(m.undo(), Some(Coord::new(2.0, 2.0)));
        assert_eq!(m.points().len(), 1);
        m.clear();
        assert!(m.points().is_empty());
    }

    #[test]
    fn caps_at_max_points() {
        let mut m = Measure::new();
        for i in 0..MAX_POINTS {
            assert!(m.push(Coord::new(0.0, i as f64 * 0.001)));
        }
        assert!(!m.push(Coord::new(5.0, 5.0)));
        assert_eq!(m.points().len(), MAX_POINTS);
    }
}
