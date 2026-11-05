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
    })
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
        PhotoPlan { x, y, side, out }
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
}
