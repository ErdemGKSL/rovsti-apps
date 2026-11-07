//! The map's measure tool: an ordered polyline of tapped points.

use crate::Coord;

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
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

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
