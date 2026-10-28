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

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(export("b.json", "{}"), Err(Error::Unsupported)));
        assert!(matches!(import(), Err(Error::Unsupported)));
    }
}
