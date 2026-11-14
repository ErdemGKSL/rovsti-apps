//! Pure zoom, pan and swipe-to-dismiss math for full-screen image viewers.
//!
//! All values are `f32` viewport pixels. [`ZoomView::offset`] is the shift of the
//! content centre from the viewport centre; `scale` 1.0 means "fit". Focus points
//! are given in viewport coordinates with the origin at the top-left corner.

/// Width and height in pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Size {
    pub w: f32,
    pub h: f32,
}

/// A point or offset in pixels.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

pub const MIN_SCALE: f32 = 1.0;
pub const MAX_SCALE: f32 = 5.0;
pub const DOUBLE_TAP_SCALE: f32 = 2.5;
/// Fraction of finger movement that applies past the pan bounds.
pub const RUBBER: f32 = 0.35;
/// Downward drag distance that closes the viewer.
pub const DISMISS_PX: f32 = 120.0;
/// Fraction of the width a horizontal drag must cover to turn the page.
pub const PAGE_SWIPE_FRACTION: f32 = 0.25;
/// Horizontal fling speed that turns the page after a short drag.
pub const PAGE_FLING_PX_S: f32 = 800.0;

const GESTURE_MIN: f32 = MIN_SCALE * 0.8;
const GESTURE_MAX: f32 = MAX_SCALE * 1.2;
const ZOOMED_ABOVE: f32 = 1.01;
const FLING_PX_S: f32 = 1000.0;
const FLING_MIN_PX: f32 = 24.0;

/// Aspect-fit `content` inside `viewport`. Degenerate input yields a zero size.
pub fn fit(content: Size, viewport: Size) -> Size {
    if content.w <= 0.0 || content.h <= 0.0 || viewport.w <= 0.0 || viewport.h <= 0.0 {
        return Size { w: 0.0, h: 0.0 };
    }
    let k = (viewport.w / content.w).min(viewport.h / content.h);
    Size {
        w: content.w * k,
        h: content.h * k,
    }
}

/// Current zoom level and pan offset of an image.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ZoomView {
    pub scale: f32,
    pub offset: Point,
}

fn pan_axis(o: f32, d: f32, max: f32) -> f32 {
    if max <= 0.0 {
        return 0.0;
    }
    let n = o + d;
    if n.abs() <= max {
        return n;
    }
    let bound = max * n.signum();
    if o.abs() <= max {
        bound + (n - bound) * RUBBER
    } else if d * o.signum() > 0.0 {
        o + d * RUBBER
    } else {
        n
    }
}

impl ZoomView {
    pub const IDENTITY: ZoomView = ZoomView {
        scale: 1.0,
        offset: Point { x: 0.0, y: 0.0 },
    };

    /// Re-scale to `new_scale` keeping the content point under `focus` fixed.
    fn rescale(&mut self, new_scale: f32, focus: Point, viewport: Size) {
        let fx = focus.x - viewport.w / 2.0;
        let fy = focus.y - viewport.h / 2.0;
        let k = new_scale / self.scale;
        self.offset.x = fx - (fx - self.offset.x) * k;
        self.offset.y = fy - (fy - self.offset.y) * k;
        self.scale = new_scale;
    }

    /// Apply a pinch step (`scale *= 1 + delta`), loosely clamped during the gesture.
    pub fn pinch(&mut self, delta: f32, focus: Point, viewport: Size) {
        let target = (self.scale * (1.0 + delta)).clamp(GESTURE_MIN, GESTURE_MAX);
        self.rescale(target, focus, viewport);
    }

    /// Drag by `dx`, `dy`: free inside the bounds, rubber-banded past them.
    pub fn pan(&mut self, dx: f32, dy: f32, fitted: Size, viewport: Size) {
        let m = self.max_offset(fitted, viewport);
        self.offset.x = pan_axis(self.offset.x, dx, m.x);
        self.offset.y = pan_axis(self.offset.y, dy, m.y);
    }

    /// Toggle between fit and [`DOUBLE_TAP_SCALE`] around `focus`. Call
    /// [`ZoomView::settled`] on the result to clamp the offset.
    pub fn double_tap(&self, focus: Point, viewport: Size) -> ZoomView {
        if self.is_zoomed() {
            return ZoomView::IDENTITY;
        }
        let mut v = *self;
        v.rescale(DOUBLE_TAP_SCALE, focus, viewport);
        v
    }

    /// Clamp scale to `[MIN_SCALE, MAX_SCALE]` and the offset into bounds.
    pub fn settled(&self, fitted: Size, viewport: Size) -> ZoomView {
        let mut v = *self;
        v.scale = v.scale.clamp(MIN_SCALE, MAX_SCALE);
        let m = v.max_offset(fitted, viewport);
        v.offset.x = v.offset.x.clamp(-m.x, m.x);
        v.offset.y = v.offset.y.clamp(-m.y, m.y);
        v
    }

    /// Largest allowed offset magnitude per axis (0 where the image is smaller
    /// than the viewport).
    pub fn max_offset(&self, fitted: Size, viewport: Size) -> Point {
        Point {
            x: ((fitted.w * self.scale - viewport.w) / 2.0).max(0.0),
            y: ((fitted.h * self.scale - viewport.h) / 2.0).max(0.0),
        }
    }

    pub fn is_zoomed(&self) -> bool {
        self.scale > ZOOMED_ABOVE
    }
}

/// Swipe-down progress, 0.0 to 1.0 (1.0 means release closes). Upward drags are 0.
pub fn dismiss_progress(dy: f32) -> f32 {
    (dy / DISMISS_PX).clamp(0.0, 1.0)
}

/// Whether releasing a downward drag of `dy` px at `velocity_px_s` closes the viewer.
pub fn should_dismiss(dy: f32, velocity_px_s: f32) -> bool {
    if dy <= 0.0 {
        return false;
    }
    dy >= DISMISS_PX || (velocity_px_s > FLING_PX_S && dy >= FLING_MIN_PX)
}

/// Horizontal shift to show while dragging page `index` of `count`; rubber-banded
/// when pulling past the first or last page.
pub fn page_drag(dx: f32, index: usize, count: usize) -> f32 {
    let at_first = index == 0 && dx > 0.0;
    let at_last = index + 1 >= count && dx < 0.0;
    if at_first || at_last { dx * RUBBER } else { dx }
}

/// Page to settle on after releasing a horizontal drag of `dx` px at `velocity_px_s`
/// (negative `dx` swipes towards the next page). Moves at most one page.
pub fn page_after_release(
    index: usize,
    count: usize,
    dx: f32,
    velocity_px_s: f32,
    width: f32,
) -> usize {
    if count == 0 {
        return 0;
    }
    let index = index.min(count - 1);
    let far = dx.abs() >= width * PAGE_SWIPE_FRACTION;
    let fling = velocity_px_s.abs() > PAGE_FLING_PX_S
        && dx.abs() >= FLING_MIN_PX
        && velocity_px_s.signum() == dx.signum();
    if dx == 0.0 || !(far || fling) {
        index
    } else if dx < 0.0 {
        (index + 1).min(count - 1)
    } else {
        index.saturating_sub(1)
    }
}

/// "3 of 7" for the viewer header; `None` when there is nothing to page.
pub fn page_caption(index: usize, count: usize) -> Option<String> {
    (count > 1).then(|| format!("{} of {}", index.min(count - 1) + 1, count))
}

#[cfg(test)]
mod tests {
    use super::*;

    const VP: Size = Size { w: 400.0, h: 800.0 };

    fn sz(w: f32, h: f32) -> Size {
        Size { w, h }
    }
    fn pt(x: f32, y: f32) -> Point {
        Point { x, y }
    }
    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-3
    }

    #[test]
    fn fit_cases() {
        assert_eq!(fit(sz(800.0, 400.0), VP), sz(400.0, 200.0));
        assert_eq!(fit(sz(400.0, 1600.0), VP), sz(200.0, 800.0));
        assert_eq!(fit(sz(1000.0, 1000.0), VP), sz(400.0, 400.0));
        assert_eq!(fit(sz(100.0, 100.0), VP), sz(400.0, 400.0));
        assert_eq!(fit(sz(0.0, 10.0), VP), sz(0.0, 0.0));
    }

    #[test]
    fn pinch_keeps_focus_fixed() {
        let fitted = sz(400.0, 400.0);
        let mut v = ZoomView::IDENTITY;
        let focus = pt(300.0, 500.0);
        // Content point under the focus before: (screen - centre - offset) / scale.
        let before = (
            (focus.x - VP.w / 2.0 - v.offset.x) / v.scale,
            (focus.y - VP.h / 2.0 - v.offset.y) / v.scale,
        );
        v.pinch(1.0, focus, VP);
        assert!(near(v.scale, 2.0));
        let after = (
            (focus.x - VP.w / 2.0 - v.offset.x) / v.scale,
            (focus.y - VP.h / 2.0 - v.offset.y) / v.scale,
        );
        assert!(near(before.0, after.0) && near(before.1, after.1));
        let _ = fitted;
    }

    #[test]
    fn pinch_clamps_during_gesture_and_settles() {
        let fitted = sz(400.0, 400.0);
        let mut v = ZoomView::IDENTITY;
        v.pinch(100.0, pt(200.0, 400.0), VP);
        assert!(near(v.scale, 6.0));
        assert!(near(v.settled(fitted, VP).scale, 5.0));
        let mut v = ZoomView::IDENTITY;
        v.pinch(-0.9, pt(10.0, 10.0), VP);
        assert!(near(v.scale, 0.8));
        assert_eq!(v.settled(fitted, VP), ZoomView::IDENTITY);
    }

    #[test]
    fn rubber_band_and_settle_back() {
        let fitted = sz(400.0, 400.0);
        let mut v = ZoomView {
            scale: 2.0,
            offset: pt(0.0, 0.0),
        };
        // Wide-fit image: max x offset = (400*2-400)/2 = 200.
        assert!(near(v.max_offset(fitted, VP).x, 200.0));
        v.pan(150.0, 0.0, fitted, VP);
        assert!(near(v.offset.x, 150.0));
        v.pan(100.0, 0.0, fitted, VP);
        assert!(near(v.offset.x, 200.0 + 50.0 * RUBBER));
        v.pan(40.0, 0.0, fitted, VP);
        assert!(near(v.offset.x, 200.0 + 50.0 * RUBBER + 40.0 * RUBBER));
        let s = v.settled(fitted, VP);
        assert!(near(s.offset.x, 200.0));
    }

    #[test]
    fn non_zoomed_image_cannot_pan() {
        let fitted = sz(400.0, 400.0);
        let mut v = ZoomView::IDENTITY;
        v.pan(80.0, -60.0, fitted, VP);
        assert_eq!(v, ZoomView::IDENTITY);
    }

    #[test]
    fn settle_centres_when_smaller_and_covers_when_larger() {
        let fitted = sz(400.0, 400.0);
        let v = ZoomView {
            scale: 2.0,
            offset: pt(-999.0, 999.0),
        };
        let s = v.settled(fitted, VP);
        assert!(near(s.offset.x, -200.0));
        assert!(near(s.offset.y, 0.0)); // 800 tall == viewport, so centred
    }

    #[test]
    fn double_tap_toggles() {
        let v = ZoomView::IDENTITY.double_tap(pt(300.0, 400.0), VP);
        assert!(near(v.scale, DOUBLE_TAP_SCALE));
        assert!(v.is_zoomed());
        assert_eq!(v.double_tap(pt(0.0, 0.0), VP), ZoomView::IDENTITY);
    }

    #[test]
    fn dismiss_thresholds() {
        assert!(should_dismiss(DISMISS_PX, 0.0));
        assert!(!should_dismiss(DISMISS_PX - 1.0, 500.0));
        assert!(should_dismiss(30.0, 1500.0));
        assert!(!should_dismiss(10.0, 3000.0)); // twitch
        assert!(!should_dismiss(-300.0, -5000.0)); // upward never
        assert!(!should_dismiss(-300.0, 5000.0));
        assert!(near(dismiss_progress(60.0), 0.5));
        assert!(near(dismiss_progress(500.0), 1.0));
        assert!(near(dismiss_progress(-5.0), 0.0));
    }

    #[test]
    fn page_moves_past_a_quarter() {
        assert_eq!(page_after_release(2, 5, -100.0, 0.0, 400.0), 3);
        assert_eq!(page_after_release(2, 5, 100.0, 0.0, 400.0), 1);
        assert_eq!(page_after_release(2, 5, -99.0, 0.0, 400.0), 2);
        assert_eq!(page_after_release(0, 0, -300.0, 0.0, 400.0), 0);
    }

    #[test]
    fn page_flings_move_short_drags() {
        assert_eq!(page_after_release(2, 5, -40.0, -900.0, 400.0), 3);
        assert_eq!(page_after_release(2, 5, 40.0, 900.0, 400.0), 1);
        assert_eq!(page_after_release(2, 5, -40.0, 900.0, 400.0), 2); // wrong way
        assert_eq!(page_after_release(2, 5, -10.0, -3000.0, 400.0), 2); // twitch
        assert_eq!(page_after_release(2, 5, -40.0, -700.0, 400.0), 2); // too slow
    }

    #[test]
    fn page_stays_at_the_ends() {
        assert_eq!(page_after_release(0, 3, 300.0, 0.0, 400.0), 0);
        assert_eq!(page_after_release(2, 3, -300.0, 0.0, 400.0), 2);
        assert_eq!(page_after_release(0, 1, -300.0, 0.0, 400.0), 0);
    }

    #[test]
    fn page_drag_rubber_bands_at_the_ends() {
        assert!(near(page_drag(100.0, 0, 3), 100.0 * RUBBER));
        assert!(near(page_drag(-100.0, 2, 3), -100.0 * RUBBER));
        assert!(near(page_drag(-100.0, 0, 3), -100.0));
        assert!(near(page_drag(100.0, 2, 3), 100.0));
        assert!(near(page_drag(100.0, 1, 3), 100.0));
        assert!(near(page_drag(100.0, 0, 1), 100.0 * RUBBER));
    }

    #[test]
    fn page_caption_hides_for_one() {
        assert_eq!(page_caption(0, 0), None);
        assert_eq!(page_caption(0, 1), None);
        assert_eq!(page_caption(2, 7).as_deref(), Some("3 of 7"));
    }
}
