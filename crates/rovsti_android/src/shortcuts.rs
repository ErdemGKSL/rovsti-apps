//! Pinned home screen shortcuts. Kotlin side: `Shortcuts`.
//!
//! A pinned contact shortcut launches the app with the contact id, which the
//! bridge turns into the one-shot event `open_contact`. On desktop every
//! function returns [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Whether the launcher can pin shortcuts (`isRequestPinShortcutSupported`).
pub fn can_pin() -> Result<bool> {
    bridge::call_bool0("canPinShortcut")
}

/// Asks the launcher to pin a contact shortcut from `spec_json`
/// (`rovsti_core::contact_options::PinShortcut`). `false` when it refused.
pub fn pin_contact(spec_json: &str) -> Result<bool> {
    bridge::call_bool("pinContactShortcut", &[Arg::Str(spec_json)])
}

/// Asks the launcher to pin a saved-stop shortcut from `spec_json`
/// (`rovsti_geo::StopShortcut`). `false` when it refused.
pub fn pin_stop(spec_json: &str) -> Result<bool> {
    bridge::call_bool("pinStopShortcut", &[Arg::Str(spec_json)])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(can_pin(), Err(Error::Unsupported)));
        assert!(matches!(pin_contact("{}"), Err(Error::Unsupported)));
        assert!(matches!(pin_stop("{}"), Err(Error::Unsupported)));
    }
}
