//! Storage Access Framework hand-offs for backup and export (AD-30). Kotlin
//! side: `Documents`.
//!
//! Both start a system picker and return at once; the answer arrives later as
//! the one-shot event `document_result` (`rovsti_core::wire::DocumentResult`).
//! No storage permission is involved. On desktop they return
//! [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Opens "save as" for `json` under the suggested `name`. `false` when no
/// picker could be started.
pub fn export(name: &str, json: &str) -> Result<bool> {
    bridge::call_bool("exportDocument", &[Arg::Str(name), Arg::Str(json)])
}

/// Opens the file picker for a backup to import (at most 256 KB is read).
pub fn import() -> Result<bool> {
    bridge::call_bool0("importDocument")
}

/// Opens "save as" for a `.vcf` holding `text` under the suggested `name`.
/// The answer is a `document_result` with op `export_contacts`.
pub fn export_contacts(name: &str, text: &str) -> Result<bool> {
    bridge::call_bool("exportContactsDocument", &[Arg::Str(name), Arg::Str(text)])
}

/// Opens "save as" for a `.csv` holding the call log under the suggested
/// `name`. The answer is a `document_result` with op `export_calls`.
pub fn export_calls(name: &str, csv: &str) -> Result<bool> {
    bridge::call_bool("exportCallsDocument", &[Arg::Str(name), Arg::Str(csv)])
}

/// Opens the file picker for a `.vcf` (at most 8 MB is read). The answer is a
/// `document_result` with op `import_contacts`.
pub fn import_contacts() -> Result<bool> {
    bridge::call_bool0("importContactsDocument")
}

/// Opens "save as" for the file at `path` (copied by stream, no size limit in
/// the seam) under the suggested `name` and `mime`. The answer is a
/// `document_result` with op `export_messages`.
pub fn export_file(name: &str, mime: &str, path: &str) -> Result<bool> {
    bridge::call_bool(
        "exportFile",
        &[Arg::Str(name), Arg::Str(mime), Arg::Str(path)],
    )
}

/// Opens the file picker; the chosen file is copied into the app's cache and
/// the `document_result` (op `import_messages`) carries its `path`. Files
/// longer than `max_bytes` are refused (Kotlin caps this at 64 MB).
pub fn import_file(max_bytes: u64) -> Result<bool> {
    let max = i32::try_from(max_bytes).unwrap_or(i32::MAX);
    bridge::call_bool("importFile", &[Arg::Int(max)])
}

/// Opens the file picker for a GTFS feed (`.zip`). The chosen file is copied into
/// the app's cache and the `document_result` (op `import_gtfs`) carries its
/// `path`. Files longer than `max_bytes` are refused (Kotlin caps this at 64 MiB).
pub fn import_gtfs(max_bytes: u64) -> Result<bool> {
    let max = i32::try_from(max_bytes).unwrap_or(i32::MAX);
    bridge::call_bool("importGtfs", &[Arg::Int(max)])
}

/// Opens the file picker for a file to attach to a message. The chosen file is
/// copied into the app's cache; the `document_result` (op `attachment`) carries
/// its `path` and `mime`. Files longer than `max_bytes` are refused (Kotlin
/// caps this at 64 MB).
pub fn pick_attachment(max_bytes: u64) -> Result<bool> {
    let max = i32::try_from(max_bytes).unwrap_or(i32::MAX);
    bridge::call_bool("pickAttachment", &[Arg::Int(max)])
}

/// Opens the system Photo Picker (images only). The chosen image is copied into
/// the app's cache (at most 20 MiB); the `document_result` (op `contact_photo`)
/// carries its `path` and `mime`.
pub fn pick_contact_photo() -> Result<bool> {
    bridge::call_bool0("pickContactPhoto")
}

/// Opens the camera app for one photo (no `CAMERA` permission is used). The
/// picture is written to the app cache; the `document_result` (op
/// `contact_photo`) carries its `path` and `mime` `image/jpeg`, like the picker.
pub fn capture_photo() -> Result<bool> {
    bridge::call_bool0("capturePhoto")
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(export("b.json", "{}"), Err(Error::Unsupported)));
        assert!(matches!(import(), Err(Error::Unsupported)));
        assert!(matches!(
            export_contacts("c.vcf", ""),
            Err(Error::Unsupported)
        ));
        assert!(matches!(
            export_calls("calls.csv", ""),
            Err(Error::Unsupported)
        ));
        assert!(matches!(import_contacts(), Err(Error::Unsupported)));
        assert!(matches!(
            export_file("m.rmb", "application/octet-stream", "/c/m.rmb"),
            Err(Error::Unsupported)
        ));
        assert!(matches!(import_file(1024), Err(Error::Unsupported)));
        assert!(matches!(import_gtfs(1024), Err(Error::Unsupported)));
        assert!(matches!(pick_attachment(1024), Err(Error::Unsupported)));
        assert!(matches!(pick_contact_photo(), Err(Error::Unsupported)));
        assert!(matches!(capture_photo(), Err(Error::Unsupported)));
    }
}
