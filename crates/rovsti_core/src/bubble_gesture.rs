//! Swipe-right-to-reply and double-tap reaction logic for chat bubbles.
//!
//! Pure math; the UI feeds pointer positions and draws the offset.

/// Movement before the drag axis is decided.
pub const LOCK_PX: f32 = 12.0;
/// Offset at which a release triggers the reply.
pub const COMMIT_PX: f32 = 64.0;
/// Hard cap of the bubble offset.
pub const MAX_OFFSET_PX: f32 = 96.0;
const BAND: f32 = 0.35;

/// Which way a drag was locked.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Axis {
    Undecided,
    Horizontal,
    Vertical,
}

/// Result of one pointer move.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SwipeMove {
    /// Bubble offset to draw, in px.
    pub offset: f32,
    /// True exactly once per crossing of [`COMMIT_PX`] (haptic tick).
    pub crossed: bool,
}

/// One swipe-to-reply drag.
#[derive(Clone, Copy, Debug)]
pub struct SwipeReply {
    start: (f32, f32),
    axis: Axis,
    offset: f32,
    armed: bool,
}

impl SwipeReply {
    pub fn new(x: f32, y: f32) -> Self {
        Self {
            start: (x, y),
            axis: Axis::Undecided,
            offset: 0.0,
            armed: false,
        }
    }

    pub fn moved(&mut self, x: f32, y: f32) -> SwipeMove {
        let dx = x - self.start.0;
        let dy = y - self.start.1;
        if self.axis == Axis::Undecided && dx.abs().max(dy.abs()) >= LOCK_PX {
            self.axis = if dx > 0.0 && dx > 1.5 * dy.abs() {
                Axis::Horizontal
            } else {
                Axis::Vertical
            };
        }
        if self.axis != Axis::Horizontal {
            return SwipeMove {
                offset: 0.0,
                crossed: false,
            };
        }
        let dx = dx.max(0.0);
        let offset = if dx <= COMMIT_PX {
            dx
        } else {
            (COMMIT_PX + (dx - COMMIT_PX) * BAND).min(MAX_OFFSET_PX)
        };
        self.offset = offset;
        let over = dx >= COMMIT_PX;
        let crossed = over && !self.armed;
        self.armed = over;
        SwipeMove { offset, crossed }
    }

    pub fn axis(&self) -> Axis {
        self.axis
    }

    pub fn is_horizontal(&self) -> bool {
        self.axis == Axis::Horizontal
    }

    /// True when the release should start a reply.
    pub fn release(&self) -> bool {
        self.is_horizontal() && self.armed
    }
}

/// The reaction to send after a double tap: `quick` unless it is already
/// `own`, in which case the empty string removes it.
pub fn double_tap_reaction(own: Option<&str>, quick: &str) -> String {
    if own == Some(quick) {
        String::new()
    } else {
        quick.to_owned()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn locks_horizontal_on_rightward_drag() {
        let mut s = SwipeReply::new(0.0, 0.0);
        assert_eq!(s.moved(5.0, 0.0).offset, 0.0);
        assert_eq!(s.axis(), Axis::Undecided);
        assert_eq!(s.moved(20.0, 5.0).offset, 20.0);
        assert!(s.is_horizontal());
    }

    #[test]
    fn locks_vertical_and_stays_inert() {
        let mut s = SwipeReply::new(0.0, 0.0);
        assert_eq!(s.moved(5.0, 20.0).offset, 0.0);
        assert_eq!(s.axis(), Axis::Vertical);
        assert_eq!(s.moved(100.0, 20.0).offset, 0.0);
        assert!(!s.release());
    }

    #[test]
    fn diagonal_is_vertical() {
        let mut s = SwipeReply::new(0.0, 0.0);
        s.moved(20.0, 15.0);
        assert_eq!(s.axis(), Axis::Vertical);
    }

    #[test]
    fn leftward_drag_is_inert() {
        let mut s = SwipeReply::new(100.0, 0.0);
        assert_eq!(s.moved(60.0, 0.0).offset, 0.0);
        assert_eq!(s.axis(), Axis::Vertical);
        assert!(!s.release());
    }

    #[test]
    fn rubber_band_and_cap() {
        let mut s = SwipeReply::new(0.0, 0.0);
        s.moved(20.0, 0.0);
        assert_eq!(s.moved(64.0, 0.0).offset, 64.0);
        let m = s.moved(104.0, 0.0);
        assert!((m.offset - 78.0).abs() < 1e-4);
        assert_eq!(s.moved(1000.0, 0.0).offset, MAX_OFFSET_PX);
    }

    #[test]
    fn crossed_once_and_rearms() {
        let mut s = SwipeReply::new(0.0, 0.0);
        s.moved(20.0, 0.0);
        assert!(!s.moved(60.0, 0.0).crossed);
        assert!(s.moved(64.0, 0.0).crossed);
        assert!(!s.moved(80.0, 0.0).crossed);
        assert!(!s.moved(40.0, 0.0).crossed);
        assert!(!s.release());
        assert!(s.moved(70.0, 0.0).crossed);
        assert!(s.release());
    }

    #[test]
    fn release_needs_commit() {
        let mut s = SwipeReply::new(0.0, 0.0);
        s.moved(40.0, 0.0);
        assert!(!s.release());
    }

    #[test]
    fn double_tap_toggles() {
        assert_eq!(double_tap_reaction(None, "👍"), "👍");
        assert_eq!(double_tap_reaction(Some("❤"), "👍"), "👍");
        assert_eq!(double_tap_reaction(Some("👍"), "👍"), "");
    }
}
