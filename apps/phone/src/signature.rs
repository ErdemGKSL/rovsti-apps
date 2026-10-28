//! State for the whole-screen moments: S14 setup complete and S15 theme melt.
//!
//! Both are tiny, allocation-free values owned by the app view. They cost one
//! painted quad each, and only while they play.
//!
//! S15 choice (documented, see `docs/phone-motion-audit.md`): the theme is a
//! value every view reads while it renders, so colours cannot be interpolated
//! per element without rebuilding every call site. Instead the new theme is
//! painted at once and a single veil in the old theme's background colour
//! fades away over it on the `theme-fade` spring (zeta 1, k 600, about
//! 260 ms). Backgrounds therefore cross-fade exactly (RGB mix of old and new),
//! chrome and content emerge from it, and there is no readback of the frame
//! and no per-frame allocation.

use std::time::Duration;

use rovsti_ui::{moments::Tween, motion_tokens, spring::Spring, tokens::Theme};

/// The wash brightens by this much of alpha (S14).
pub const CELEBRATE_ALPHA: f32 = 0.10;
const CELEBRATE_LENGTH: Duration = Duration::from_millis(600);
/// The veil never starts fully opaque, so the new screen is hinted at once.
const VEIL_START: f32 = 0.9;

fn spring(value: f32, params: rovsti_ui::spring::SpringParams) -> Spring {
    Spring::new(value, params).with_epsilon(0.002)
}

// ---- S14 ------------------------------------------------------------------

/// "Setup complete": the shield morphs, its check pops 0.6 to 1.0 and the
/// page wash brightens for 600 ms. Plays once per completion in a session.
pub struct Celebrate {
    /// Whether the checklist was complete the last time it was looked at
    /// (`None` until the first trustworthy look: a launch never celebrates).
    seen: Option<bool>,
    glow: Tween,
    pop: Spring,
}

impl Default for Celebrate {
    fn default() -> Self {
        Self {
            seen: None,
            glow: Tween::linear(CELEBRATE_LENGTH),
            pop: spring(1.0, motion_tokens::release()),
        }
    }
}

impl Celebrate {
    /// Feeds the checklist state. Returns `true` when the last item just
    /// turned done in this session and the moment should play.
    pub fn observe(&mut self, complete: bool, reduced: bool) -> bool {
        let finished = self.seen == Some(false) && complete;
        self.seen = Some(complete);
        if finished && !reduced {
            self.glow.start();
            self.pop.snap(0.6);
            self.pop.retarget(1.0, motion_tokens::release());
        }
        finished
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.pop.tick(dt);
        moving |= self.glow.tick(dt);
        moving
    }

    /// Scale of the shield's check while it pops.
    pub fn check_scale(&self) -> f32 {
        self.pop.value.max(0.0)
    }

    /// Extra alpha of the page wash (0 at rest).
    pub fn wash(&self) -> f32 {
        if self.glow.running() {
            CELEBRATE_ALPHA * bump(self.glow.time())
        } else {
            0.0
        }
    }
}

/// 0 to 1 to 0: rises over the first quarter, eases back over the rest.
fn bump(t: f32) -> f32 {
    let x = if t < 0.25 { t / 0.25 } else { (1.0 - t) / 0.75 }.clamp(0.0, 1.0);
    x * x * (3.0 - 2.0 * x)
}

// ---- S15 ------------------------------------------------------------------

/// "Theme melt": the old theme's background as a veil that fades out.
pub struct Melt {
    last: Option<Theme>,
    from: Theme,
    veil: Spring,
}

impl Default for Melt {
    fn default() -> Self {
        Self {
            last: None,
            from: Theme::Light,
            veil: spring(0.0, motion_tokens::theme_fade()),
        }
    }
}

impl Melt {
    /// Feeds the theme the window wants now. On a change, the melt starts,
    /// unless motion is reduced or the previous frame already ran over twice
    /// the budget: then the new theme simply snaps in.
    pub fn observe(&mut self, now: Theme, reduced: bool, slow_frame: bool) {
        let before = self.last.replace(now);
        let Some(before) = before else {
            return;
        };
        if before == now {
            return;
        }
        self.from = before;
        if reduced || slow_frame {
            self.veil.snap(0.0);
        } else {
            self.veil.snap(VEIL_START);
            self.veil.set_target(0.0);
        }
    }

    pub fn tick(&mut self, dt: Duration) -> bool {
        self.veil.tick(dt)
    }

    /// The theme to paint the veil in and its alpha, while it is visible.
    pub fn veil(&self) -> Option<(Theme, f32)> {
        let alpha = self.veil.value.clamp(0.0, 1.0);
        (alpha > 0.004).then_some((self.from, alpha))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(mut tick: impl FnMut(Duration) -> bool, frames: usize) {
        for _ in 0..frames {
            tick(Duration::from_secs_f32(1.0 / 120.0));
        }
    }

    #[test]
    fn a_launch_never_celebrates() {
        let mut c = Celebrate::default();
        assert!(!c.observe(true, false), "complete on the first look");
        assert_eq!(c.wash(), 0.0);
        assert!(!c.observe(true, false));
    }

    #[test]
    fn the_last_item_done_celebrates_once() {
        let mut c = Celebrate::default();
        assert!(!c.observe(false, false));
        assert!(c.observe(true, false));
        assert!(!c.observe(true, false), "not replayed");
        assert!(
            (c.check_scale() - 0.6).abs() < 1e-5,
            "the check starts small"
        );
        run(|dt| c.tick(dt), 30);
        assert!(c.wash() > 0.0, "the wash is up around 250 ms");
        run(|dt| c.tick(dt), 120);
        assert_eq!(c.wash(), 0.0);
        assert!((c.check_scale() - 1.0).abs() < 0.01);
        // Lost and regained: it plays again (the owner undid and redid a step).
        assert!(!c.observe(false, false));
        assert!(c.observe(true, false));
    }

    #[test]
    fn reduced_motion_collapses_instantly_without_brightening() {
        let mut c = Celebrate::default();
        c.observe(false, true);
        assert!(c.observe(true, true), "the state still changes");
        assert_eq!(c.wash(), 0.0);
        assert!(!c.tick(Duration::from_millis(16)));
        assert_eq!(c.check_scale(), 1.0);
    }

    #[test]
    fn the_wash_peaks_at_a_tenth_and_returns() {
        assert_eq!(bump(0.0), 0.0);
        assert!((bump(0.25) - 1.0).abs() < 1e-5);
        assert_eq!(bump(1.0), 0.0);
        assert!(bump(0.6) > 0.0 && bump(0.6) < 1.0);
    }

    #[test]
    fn the_first_theme_never_melts() {
        let mut m = Melt::default();
        m.observe(Theme::Dark, false, false);
        assert!(m.veil().is_none());
        m.observe(Theme::Dark, false, false);
        assert!(m.veil().is_none());
    }

    #[test]
    fn a_change_starts_a_veil_in_the_old_theme_that_fades_in_about_260_ms() {
        let mut m = Melt::default();
        m.observe(Theme::Light, false, false);
        m.observe(Theme::Dark, false, false);
        let (from, alpha) = m.veil().expect("veil");
        assert_eq!(from, Theme::Light);
        assert!((alpha - VEIL_START).abs() < 1e-5);
        run(|dt| m.tick(dt), 18);
        let mid = m.veil().map_or(0.0, |(_, a)| a);
        assert!(mid < alpha, "fading");
        run(|dt| m.tick(dt), 60);
        assert!(m.veil().is_none(), "gone after about half a second");
    }

    #[test]
    fn reduced_motion_or_a_slow_frame_snaps() {
        for (reduced, slow) in [(true, false), (false, true)] {
            let mut m = Melt::default();
            m.observe(Theme::Light, reduced, slow);
            m.observe(Theme::Dark, reduced, slow);
            assert!(m.veil().is_none());
        }
    }

    #[test]
    fn a_change_mid_melt_restarts_from_the_theme_it_left() {
        let mut m = Melt::default();
        m.observe(Theme::Light, false, false);
        m.observe(Theme::Dark, false, false);
        run(|dt| m.tick(dt), 10);
        m.observe(Theme::Light, false, false);
        assert_eq!(m.veil().map(|(t, _)| t), Some(Theme::Dark));
    }
}
