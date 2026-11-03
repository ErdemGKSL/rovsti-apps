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

/// Opens the file picker for a `.vcf` (at most 8 MB is read). The answer is a
/// `document_result` with op `import_contacts`.
pub fn import_contacts() -> Result<bool> {
    bridge::call_bool0("importContactsDocument")
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
        assert!(matches!(import_contacts(), Err(Error::Unsupported)));
    }
}
