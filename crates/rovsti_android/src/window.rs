//! The host window, as far as Rust needs it.

use crate::{Result, bridge};

/// Moves the app's task to the background without finishing the activity, so
/// the GPUI session stays warm. Used after a call that began over the lock
/// screen ends (AD-3a): the lock screen shows again.
pub fn move_task_to_back() -> Result<bool> {
    bridge::call_bool0("moveTaskToBack")
}

/// Whether the keyguard is up, re-read from the system now (not the last snapshot).
pub fn keyguard_locked() -> Result<bool> {
    bridge::call_bool0("isKeyguardLocked")
}

/// Asks the system to dismiss the keyguard; the `keyguard_dismissed` event
/// answers (at once when the phone is unlocked).
pub fn request_dismiss_keyguard() -> Result<bool> {
    bridge::call_bool0("requestDismissKeyguard")
}

/// Tells Kotlin whether Rust's session is live and the activity resumed
/// (story 16.3). While `false`, Kotlin answers system account pickers itself
/// and keeps one-shot events in its mailbox; `true` drains the mailbox.
pub fn ui_attached(attached: bool) -> Result<bool> {
    bridge::call_bool("uiAttached", &[bridge::Arg::Bool(attached)])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(move_task_to_back(), Err(Error::Unsupported)));
        assert!(matches!(keyguard_locked(), Err(Error::Unsupported)));
        assert!(matches!(
            request_dismiss_keyguard(),
            Err(Error::Unsupported)
        ));
        assert!(matches!(ui_attached(true), Err(Error::Unsupported)));
    }
}
