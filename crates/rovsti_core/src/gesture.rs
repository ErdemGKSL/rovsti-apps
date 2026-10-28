//! The Incoming Call screen's card drag (story 18.5, S29): pure rules, so the
//! thresholds are tested and the view only draws.
//!
//! A drag commits only when it is deliberate: past [`COMMIT_PX`] along one
//! axis, or a fling above [`FLING_PX_S`] that also travelled at least
//! [`MIN_FLING_PX`]. Anything shorter springs back and does nothing. Down is
//! not a direction. The buttons stay the only other way to answer or decline.

/// Distance past which a release commits (px of finger travel).
pub const COMMIT_PX: f32 = 96.0;
/// A fling at least this fast (px/s along the drag's axis) commits.
pub const FLING_PX_S: f32 = 600.0;
/// ...if it also moved this far, so a twitch never counts.
pub const MIN_FLING_PX: f32 = 40.0;
/// The card follows the finger at this fraction of its travel.
pub const GAIN: f32 = 0.9;
/// Travel before a direction is chosen.
pub const LOCK_PX: f32 = 12.0;
/// The first (light) tick fires at this fraction of the commit distance.
pub const HALF: f32 = 0.5;
/// Beyond the commit distance the card follows this fraction of the finger.
const STRETCH: f32 = 0.25;
/// Velocity is measured over at most this long, in milliseconds.
const WINDOW_MS: f32 = 100.0;

/// Where a drag is heading.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dir {
    /// Answer.
    Right,
    /// Decline.
    Left,
    /// Decline with a message.
    Up,
}

/// A haptic the view should play.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tick {
    /// Light, at half the commit distance.
    Half,
    /// Medium, at the commit distance.
    Commit,
}

/// What the view draws for the latest finger position.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Follow {
    pub dir: Option<Dir>,
    /// Card offset in px, already with the gain and the rubber band.
    pub offset: (f32, f32),
    /// 0 to 1 over the commit distance: the wash alpha and the bubble grow with it.
    pub progress: f32,
    pub tick: Option<Tick>,
}

/// What a release means.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Release {
    Commit(Dir),
    /// Springs back, nothing happens.
    Cancel,
}

/// The card offset for `travel` px of finger travel: direct up to the commit
/// distance, then stretched.
pub fn follow_distance(travel: f32) -> f32 {
    let travel = travel.max(0.0);
    if travel <= COMMIT_PX {
        travel * GAIN
    } else {
        COMMIT_PX * GAIN + (travel - COMMIT_PX) * STRETCH
    }
}

#[derive(Clone, Copy, Debug)]
struct Sample {
    ms: f32,
    x: f32,
    y: f32,
}

/// One finger's drag on the card.
#[derive(Clone, Debug)]
pub struct Drag {
    start: (f32, f32),
    dir: Option<Dir>,
    samples: [Option<Sample>; 4],
    next: usize,
    half: bool,
    commit: bool,
}

impl Drag {
    pub fn new(x: f32, y: f32, ms: f32) -> Self {
        let mut drag = Self {
            start: (x, y),
            dir: None,
            samples: [None; 4],
            next: 0,
            half: false,
            commit: false,
        };
        drag.push(ms, x, y);
        drag
    }

    fn push(&mut self, ms: f32, x: f32, y: f32) {
        self.samples[self.next % 4] = Some(Sample { ms, x, y });
        self.next += 1;
    }

    /// Finger travel along `dir`.
    fn travel(&self, dir: Dir, x: f32, y: f32) -> f32 {
        match dir {
            Dir::Right => x - self.start.0,
            Dir::Left => self.start.0 - x,
            Dir::Up => self.start.1 - y,
        }
    }

    fn lock(&self, x: f32, y: f32) -> Option<Dir> {
        let (dx, dy) = (x - self.start.0, y - self.start.1);
        if dx.hypot(dy) < LOCK_PX {
            return None;
        }
        // A clear winner only: a diagonal drag waits until one axis leads.
        if dx.abs() > dy.abs() * 1.2 {
            Some(if dx > 0.0 { Dir::Right } else { Dir::Left })
        } else if -dy > dx.abs() * 1.2 {
            Some(Dir::Up)
        } else {
            None
        }
    }

    /// The finger moved to `(x, y)` at time `ms`.
    pub fn moved(&mut self, x: f32, y: f32, ms: f32) -> Follow {
        self.push(ms, x, y);
        // Horizontal drags may turn around (right to left); an upward drag stays up.
        let dir = match self.dir {
            Some(Dir::Up) => Some(Dir::Up),
            Some(_) => self.lock(x, y).filter(|d| *d != Dir::Up).or(self.dir),
            None => self.lock(x, y),
        };
        self.dir = dir;
        let Some(dir) = dir else {
            return Follow {
                dir: None,
                offset: (0.0, 0.0),
                progress: 0.0,
                tick: None,
            };
        };
        let travel = self.travel(dir, x, y).max(0.0);
        let along = follow_distance(travel);
        let offset = match dir {
            Dir::Right => (along, 0.0),
            Dir::Left => (-along, 0.0),
            Dir::Up => (0.0, -along),
        };
        let mut tick = None;
        if travel >= COMMIT_PX && !self.commit {
            self.commit = true;
            self.half = true;
            tick = Some(Tick::Commit);
        } else if travel < COMMIT_PX && self.commit {
            self.commit = false;
        }
        if travel >= COMMIT_PX * HALF && !self.half {
            self.half = true;
            tick = tick.or(Some(Tick::Half));
        } else if travel < COMMIT_PX * HALF {
            self.half = false;
        }
        Follow {
            dir: Some(dir),
            offset,
            progress: (travel / COMMIT_PX).clamp(0.0, 1.0),
            tick,
        }
    }

    /// Velocity along `dir` in px/s over the last [`WINDOW_MS`].
    fn velocity(&self, dir: Dir, x: f32, y: f32, ms: f32) -> f32 {
        let newest = Sample { ms, x, y };
        let oldest = self
            .samples
            .iter()
            .flatten()
            .filter(|s| newest.ms - s.ms <= WINDOW_MS && newest.ms > s.ms)
            .min_by(|a, b| a.ms.total_cmp(&b.ms));
        let Some(old) = oldest else {
            return 0.0;
        };
        let moved = match dir {
            Dir::Right => newest.x - old.x,
            Dir::Left => old.x - newest.x,
            Dir::Up => old.y - newest.y,
        };
        moved / ((newest.ms - old.ms) / 1000.0)
    }

    /// The finger lifted at `(x, y)`.
    pub fn release(&self, x: f32, y: f32, ms: f32) -> Release {
        let Some(dir) = self.dir else {
            return Release::Cancel;
        };
        let travel = self.travel(dir, x, y);
        if travel >= COMMIT_PX {
            return Release::Commit(dir);
        }
        if travel >= MIN_FLING_PX && self.velocity(dir, x, y, ms) >= FLING_PX_S {
            return Release::Commit(dir);
        }
        Release::Cancel
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn drag_to(dx: f32, dy: f32) -> (Drag, Follow) {
        let mut d = Drag::new(200.0, 400.0, 0.0);
        let f = d.moved(200.0 + dx, 400.0 + dy, 400.0);
        (d, f)
    }

    #[test]
    fn the_card_follows_at_point_nine_and_stretches_past_the_commit_distance() {
        assert!((follow_distance(50.0) - 45.0).abs() < 1e-4);
        assert!((follow_distance(96.0) - 86.4).abs() < 1e-4);
        // Past 96 px the finger moves the card a quarter as much.
        let beyond = follow_distance(196.0) - follow_distance(96.0);
        assert!((beyond - 25.0).abs() < 1e-4);
        assert_eq!(follow_distance(-10.0), 0.0);
    }

    #[test]
    fn a_short_slow_drag_does_nothing() {
        let (d, follow) = drag_to(60.0, 4.0);
        assert_eq!(follow.dir, Some(Dir::Right));
        assert_eq!(d.release(260.0, 404.0, 800.0), Release::Cancel);
    }

    #[test]
    fn a_long_drag_commits_in_its_direction() {
        let (d, _) = drag_to(100.0, 0.0);
        assert_eq!(d.release(300.0, 400.0, 800.0), Release::Commit(Dir::Right));
        let (d, _) = drag_to(-100.0, 5.0);
        assert_eq!(d.release(100.0, 405.0, 800.0), Release::Commit(Dir::Left));
        let (d, _) = drag_to(3.0, -110.0);
        assert_eq!(d.release(203.0, 290.0, 800.0), Release::Commit(Dir::Up));
    }

    #[test]
    fn down_is_not_a_direction() {
        let (d, follow) = drag_to(0.0, 150.0);
        assert_eq!(follow.dir, None);
        assert_eq!(d.release(200.0, 550.0, 800.0), Release::Cancel);
    }

    #[test]
    fn a_diagonal_waits_for_one_axis_to_lead() {
        let (_, follow) = drag_to(30.0, -28.0);
        assert_eq!(follow.dir, None);
        let (_, follow) = drag_to(40.0, -10.0);
        assert_eq!(follow.dir, Some(Dir::Right));
    }

    #[test]
    fn a_fast_fling_commits_below_the_distance_but_a_twitch_does_not() {
        let mut d = Drag::new(200.0, 400.0, 0.0);
        d.moved(220.0, 400.0, 20.0);
        d.moved(250.0, 400.0, 40.0);
        // 50 px in 40 ms is 1250 px/s and past the 40 px floor.
        assert_eq!(d.release(250.0, 400.0, 45.0), Release::Commit(Dir::Right));
        let mut twitch = Drag::new(200.0, 400.0, 0.0);
        twitch.moved(213.0, 400.0, 10.0);
        // 13 px in 10 ms is fast, but it is a twitch.
        assert_eq!(twitch.release(213.0, 400.0, 12.0), Release::Cancel);
    }

    #[test]
    fn a_slow_drag_is_not_a_fling() {
        let mut d = Drag::new(200.0, 400.0, 0.0);
        d.moved(230.0, 400.0, 300.0);
        d.moved(260.0, 400.0, 600.0);
        assert_eq!(d.release(260.0, 400.0, 900.0), Release::Cancel);
    }

    #[test]
    fn dragging_back_below_the_distance_cancels() {
        let mut d = Drag::new(200.0, 400.0, 0.0);
        d.moved(310.0, 400.0, 300.0);
        d.moved(250.0, 400.0, 700.0);
        assert_eq!(d.release(250.0, 400.0, 1500.0), Release::Cancel);
    }

    #[test]
    fn ticks_fire_once_at_half_and_at_the_commit_distance() {
        let mut d = Drag::new(200.0, 400.0, 0.0);
        let mut ticks = Vec::new();
        for step in 1..=20 {
            let f = d.moved(200.0 + step as f32 * 6.0, 400.0, step as f32 * 30.0);
            ticks.extend(f.tick);
        }
        assert_eq!(ticks, [Tick::Half, Tick::Commit]);
        // Back across the half line and forward again ticks again.
        d.moved(220.0, 400.0, 700.0);
        let again = d.moved(270.0, 400.0, 730.0);
        assert_eq!(again.tick, Some(Tick::Half));
    }

    #[test]
    fn a_horizontal_drag_may_turn_around_but_an_upward_one_stays_up() {
        let mut d = Drag::new(200.0, 400.0, 0.0);
        d.moved(250.0, 400.0, 100.0);
        let f = d.moved(130.0, 400.0, 300.0);
        assert_eq!(f.dir, Some(Dir::Left));
        let mut up = Drag::new(200.0, 400.0, 0.0);
        up.moved(200.0, 340.0, 100.0);
        let f = up.moved(320.0, 340.0, 300.0);
        assert_eq!(f.dir, Some(Dir::Up));
    }

    #[test]
    fn progress_is_a_fraction_of_the_commit_distance() {
        let (_, follow) = drag_to(48.0, 0.0);
        assert!((follow.progress - 0.5).abs() < 1e-4);
        let (_, follow) = drag_to(300.0, 0.0);
        assert_eq!(follow.progress, 1.0);
    }
}
