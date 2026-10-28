//! The host window, as far as Rust needs it.

use crate::{Result, bridge};

/// Moves the app's task to the background without finishing the activity, so
/// the GPUI session stays warm. Used after a call that began over the lock
/// screen ends (AD-3a): the lock screen shows again.
pub fn move_task_to_back() -> Result<bool> {
    bridge::call_bool0("moveTaskToBack")
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(move_task_to_back(), Err(Error::Unsupported)));
    }
}
