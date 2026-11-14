//! Planning a contact photo: a centred square crop of the picked image,
//! scaled down to at most [`MAX_SIDE`] pixels. Decoding and encoding are the
//! platform's job; this only decides the rectangle and the output size.

use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Longest side of the stored photo, in pixels.
pub const MAX_SIDE: u32 = 720;
/// Largest picked file that is read at all, in bytes (20 MiB).
pub const MAX_PICK_BYTES: u64 = 20 * 1024 * 1024;
/// Smallest usable crop side, in pixels.
pub const MIN_SIDE: u32 = 96;

/// The crop to take and the size to scale it to.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PhotoPlan {
    /// Left edge of the square crop.
    pub x: u32,
    /// Top edge of the square crop.
    pub y: u32,
    /// Side of the square crop.
    pub side: u32,
    /// Side of the stored photo (`min(side, MAX_SIDE)`).
    pub out: u32,
    /// Quarter turns clockwise (0..=3) applied to the source before the crop.
    /// `x`/`y` are in the rotated image.
    #[serde(default)]
    pub turns: u8,
}

/// Why a picked image cannot be a photo, in the owner's words.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Error)]
pub enum PhotoError {
    #[error("That image is empty.")]
    Empty,
    #[error("That image is too small for a contact photo.")]
    TooSmall,
}

/// The centred square crop of a `width` x `height` image.
pub fn plan(width: u32, height: u32) -> Result<PhotoPlan, PhotoError> {
    if width == 0 || height == 0 {
        return Err(PhotoError::Empty);
    }
    let side = width.min(height);
    if side < MIN_SIDE {
        return Err(PhotoError::TooSmall);
    }
    Ok(PhotoPlan {
        x: (width - side) / 2,
        y: (height - side) / 2,
        side,
        out: side.min(MAX_SIDE),
        turns: 0,
    })
}

/// Size of a `width` x `height` image after `turns` clockwise quarter turns.
pub fn rotated_size(width: u32, height: u32, turns: u8) -> (u32, u32) {
    if turns % 2 == 1 {
        (height, width)
    } else {
        (width, height)
    }
}

/// Largest zoom at which the crop side stays at least [`MIN_SIDE`] (never below 1).
pub fn max_zoom(width: u32, height: u32, turns: u8) -> f32 {
    let (rw, rh) = rotated_size(width, height, turns);
    (rw.min(rh) as f32 / MIN_SIDE as f32).max(1.0)
}

/// What the crop screen shows: rotation, zoom and the crop centre.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CropView {
    /// Quarter turns clockwise, 0..=3.
    pub turns: u8,
    /// Zoom, at least 1 (1 shows the whole short side).
    pub zoom: f32,
    /// Crop centre across the rotated image, 0..1.
    pub cx: f32,
    /// Crop centre down the rotated image, 0..1.
    pub cy: f32,
}

impl Default for CropView {
    fn default() -> Self {
        Self {
            turns: 0,
            zoom: 1.0,
            cx: 0.5,
            cy: 0.5,
        }
    }
}

impl CropView {
    /// Turn a quarter clockwise and recentre.
    pub fn rotate_right(self) -> Self {
        Self {
            turns: (self.turns + 1) % 4,
            zoom: 1.0,
            cx: 0.5,
            cy: 0.5,
        }
    }

    /// Drag the image by `dx_px`/`dy_px` inside a square view `view_side_px`
    /// wide, so the crop moves the opposite way.
    pub fn pan(self, dx_px: f32, dy_px: f32, view_side_px: f32, width: u32, height: u32) -> Self {
        let (rw, rh) = rotated_size(width, height, self.turns);
        if rw == 0 || rh == 0 || view_side_px <= 0.0 || !dx_px.is_finite() || !dy_px.is_finite() {
            return self;
        }
        let side = rw.min(rh) as f32 / self.zoom;
        let per_px = side / view_side_px;
        Self {
            cx: self.cx - dx_px * per_px / rw as f32,
            cy: self.cy - dy_px * per_px / rh as f32,
            ..self
        }
        .clamped(width, height)
    }

    /// Multiply the zoom by `factor`, kept within `1..=max_zoom`.
    pub fn pinch(self, factor: f32, width: u32, height: u32) -> Self {
        if !factor.is_finite() || factor <= 0.0 {
            return self;
        }
        Self {
            zoom: (self.zoom * factor).clamp(1.0, max_zoom(width, height, self.turns)),
            ..self
        }
        .clamped(width, height)
    }

    /// Keep zoom in range and the crop square inside the rotated image.
    fn clamped(self, width: u32, height: u32) -> Self {
        let (rw, rh) = rotated_size(width, height, self.turns);
        if rw == 0 || rh == 0 {
            return self;
        }
        let zoom = if self.zoom.is_finite() {
            self.zoom
        } else {
            1.0
        }
        .clamp(1.0, max_zoom(width, height, self.turns));
        let side = rw.min(rh) as f32 / zoom;
        let axis = |c: f32, len: u32| {
            let half = side / 2.0 / len as f32;
            let c = if c.is_finite() { c } else { 0.5 };
            c.clamp(half.min(0.5), (1.0 - half).max(0.5))
        };
        Self {
            turns: self.turns % 4,
            zoom,
            cx: axis(self.cx, rw),
            cy: axis(self.cy, rh),
        }
    }
}

/// The crop plan for what the crop screen shows.
pub fn plan_for(width: u32, height: u32, view: CropView) -> Result<PhotoPlan, PhotoError> {
    if width == 0 || height == 0 {
        return Err(PhotoError::Empty);
    }
    let view = view.clamped(width, height);
    let (rw, rh) = rotated_size(width, height, view.turns);
    if rw.min(rh) < MIN_SIDE {
        return Err(PhotoError::TooSmall);
    }
    let side = ((rw.min(rh) as f32 / view.zoom).round() as u32).clamp(MIN_SIDE, rw.min(rh));
    let x = ((view.cx * rw as f32 - side as f32 / 2.0).round().max(0.0) as u32).min(rw - side);
    let y = ((view.cy * rh as f32 - side as f32 / 2.0).round().max(0.0) as u32).min(rh - side);
    Ok(PhotoPlan {
        x,
        y,
        side,
        out: side.min(MAX_SIDE),
        turns: view.turns,
    })
}

/// File name for a camera capture in the app cache.
pub fn camera_file_name(now_ms: i64) -> String {
    format!("camera-{}.jpg", now_ms.max(0))
}

/// Whether a picked document's MIME type is an image (`image/*`).
pub fn accepts(mime: Option<&str>) -> bool {
    mime.is_some_and(|m| {
        let m = m.trim();
        m.len() > "image/".len() && m.get(..6).is_some_and(|p| p.eq_ignore_ascii_case("image/"))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(x: u32, y: u32, side: u32, out: u32) -> PhotoPlan {
        PhotoPlan {
            x,
            y,
            side,
            out,
            turns: 0,
        }
    }

    #[test]
    fn plans_centred_square_crops() {
        assert_eq!(plan(4000, 3000), Ok(p(500, 0, 3000, 720)));
        assert_eq!(plan(300, 900), Ok(p(0, 300, 300, 300)));
        assert_eq!(plan(96, 96), Ok(p(0, 0, 96, 96)));
    }

    #[test]
    fn refuses_tiny_and_empty_images() {
        assert_eq!(plan(50, 50), Err(PhotoError::TooSmall));
        assert_eq!(plan(5000, 95), Err(PhotoError::TooSmall));
        assert_eq!(plan(0, 100), Err(PhotoError::Empty));
        assert_eq!(plan(100, 0), Err(PhotoError::Empty));
    }

    #[test]
    fn only_images_are_accepted() {
        assert!(accepts(Some("image/jpeg")));
        assert!(accepts(Some("IMAGE/PNG")));
        assert!(!accepts(Some("image/")));
        assert!(!accepts(Some("text/vcard")));
        assert!(!accepts(None));
    }

    #[test]
    fn default_view_equals_plan() {
        for (w, h) in [(4000, 3000), (300, 900), (96, 96), (1000, 1000)] {
            assert_eq!(plan_for(w, h, CropView::default()), plan(w, h));
        }
    }

    #[test]
    fn rotation_swaps_size_and_four_turns_return() {
        assert_eq!(rotated_size(400, 300, 0), (400, 300));
        assert_eq!(rotated_size(400, 300, 1), (300, 400));
        assert_eq!(rotated_size(400, 300, 2), (400, 300));
        assert_eq!(rotated_size(400, 300, 3), (300, 400));
        let v = CropView::default();
        assert_eq!(v.rotate_right().turns, 1);
        assert_eq!(
            v.rotate_right()
                .rotate_right()
                .rotate_right()
                .rotate_right(),
            v
        );
        let plan = plan_for(4000, 3000, v.rotate_right()).expect("ok");
        assert_eq!((plan.turns, plan.side, plan.x, plan.y), (1, 3000, 0, 500));
    }

    #[test]
    fn pan_clamps_at_every_edge() {
        let (w, h) = (4000, 3000);
        let v = CropView::default().pinch(2.0, w, h);
        let far = 1.0e6;
        let left = v.pan(far, 0.0, 300.0, w, h);
        let right = v.pan(-far, 0.0, 300.0, w, h);
        let up = v.pan(0.0, far, 300.0, w, h);
        let down = v.pan(0.0, -far, 300.0, w, h);
        // side = 1500 of 4000 wide, 3000 tall.
        assert!((left.cx - 0.1875).abs() < 1e-5);
        assert!((right.cx - 0.8125).abs() < 1e-5);
        assert!((up.cy - 0.25).abs() < 1e-5);
        assert!((down.cy - 0.75).abs() < 1e-5);
        // Dragging right moves the crop left.
        assert!(v.pan(10.0, 0.0, 300.0, w, h).cx < 0.5);
        // Nothing to pan on the short axis at zoom 1.
        let flat = CropView::default().pan(50.0, 50.0, 300.0, w, h);
        assert_eq!(flat.cy, 0.5);
    }

    #[test]
    fn pinch_clamps_at_one_and_max() {
        let (w, h) = (4000, 3000);
        let v = CropView::default();
        assert_eq!(v.pinch(0.1, w, h).zoom, 1.0);
        let max = max_zoom(w, h, 0);
        assert!((max - 3000.0 / 96.0).abs() < 1e-4);
        assert_eq!(v.pinch(1.0e6, w, h).zoom, max);
        assert_eq!(v.pinch(f32::NAN, w, h), v);
        assert_eq!(max_zoom(50, 50, 0), 1.0);
    }

    #[test]
    fn plan_for_stays_inside_the_image() {
        for (w, h) in [(4000u32, 3000u32), (300, 900), (96, 500), (1234, 777)] {
            for turns in 0..4u8 {
                let (rw, rh) = rotated_size(w, h, turns);
                for zoom in [1.0f32, 1.5, 3.0, 1.0e6] {
                    for (cx, cy) in [
                        (0.0, 0.0),
                        (1.0, 1.0),
                        (0.0, 1.0),
                        (1.0, 0.0),
                        (0.5, 0.5),
                        (-5.0, 9.0),
                    ] {
                        let view = CropView {
                            turns,
                            zoom,
                            cx,
                            cy,
                        };
                        let p = plan_for(w, h, view).expect("ok");
                        assert!(p.side >= MIN_SIDE && p.side <= rw.min(rh));
                        assert!(p.x + p.side <= rw && p.y + p.side <= rh);
                        assert_eq!(p.out, p.side.min(MAX_SIDE));
                        assert_eq!(p.turns, turns);
                    }
                }
            }
        }
    }

    #[test]
    fn plan_for_refuses_small_and_empty() {
        assert_eq!(
            plan_for(50, 500, CropView::default()),
            Err(PhotoError::TooSmall)
        );
        assert_eq!(
            plan_for(0, 500, CropView::default()),
            Err(PhotoError::Empty)
        );
    }

    #[test]
    fn old_json_without_turns_parses() {
        let p: PhotoPlan =
            serde_json::from_str(r#"{"x":1,"y":2,"side":300,"out":300}"#).expect("ok");
        assert_eq!(p.turns, 0);
        let back: PhotoPlan =
            serde_json::from_str(&serde_json::to_string(&p).expect("ok")).expect("ok");
        assert_eq!(back, p);
    }

    #[test]
    fn camera_names_are_share_safe() {
        assert_eq!(
            camera_file_name(1_700_000_000_123),
            "camera-1700000000123.jpg"
        );
        assert_eq!(camera_file_name(-5), "camera-0.jpg");
    }
}
