//! Image hand-offs: save to the gallery and the share sheet. Kotlin side:
//! `MediaShare`.
//!
//! Saving inserts into `MediaStore.Images` under `Pictures/Rovsti` (no
//! permission at API 34+). Sharing copies the file into `cache/share` and
//! starts a chooser with a read-only `content://` URI. On desktop both return
//! [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Saves the image file at `path` to the gallery as `display_name` of type
/// `mime`. `false` when it could not be saved.
pub fn save_image_to_gallery(path: &str, display_name: &str, mime: &str) -> Result<bool> {
    bridge::call_bool(
        "saveImageToGallery",
        &[Arg::Str(path), Arg::Str(display_name), Arg::Str(mime)],
    )
}

/// Opens the share sheet with the image file at `path`. `false` when no
/// sheet could be started.
pub fn share_image(path: &str, mime: &str, title: &str) -> Result<bool> {
    bridge::call_bool(
        "shareImage",
        &[Arg::Str(path), Arg::Str(mime), Arg::Str(title)],
    )
}

/// Opens the share sheet with any file at `path` of type `mime`; `name` is the
/// file name the receiver sees. `false` when no sheet could be started.
pub fn share_file(path: &str, mime: &str, name: &str) -> Result<bool> {
    bridge::call_bool(
        "shareFile",
        &[Arg::Str(path), Arg::Str(mime), Arg::Str(name)],
    )
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(
            save_image_to_gallery("/c/p.png", "p.png", "image/png"),
            Err(Error::Unsupported)
        ));
        assert!(matches!(
            share_image("/c/p.png", "image/png", "Photo"),
            Err(Error::Unsupported)
        ));
        assert!(matches!(
            share_file("/c/a.pdf", "application/pdf", "a.pdf"),
            Err(Error::Unsupported)
        ));
    }
}
