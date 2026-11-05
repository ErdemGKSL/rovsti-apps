//! Message notifications and the clipboard. Kotlin side: `MessageNotifications`.
//!
//! The spec is `rovsti_core::notify::NotificationSpec` as JSON; Kotlin only
//! renders it (`MessagingStyle`, an inline-reply action, "Copy code" and "Mark
//! read"). On desktop every function returns [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Posts or updates the notification described by `spec_json`, keyed by its tag.
pub fn show(spec_json: &str) -> Result<bool> {
    bridge::call_bool("showMessageNotification", &[Arg::Str(spec_json)])
}

/// Removes the notification with this tag (`sms:<id>` or `wa:<jid>`).
pub fn cancel(tag: &str) -> Result<bool> {
    bridge::call_bool("cancelMessageNotification", &[Arg::Str(tag)])
}

/// Copies `text`. With `sensitive` (passcodes) Android hides the clip from
/// the paste preview (`EXTRA_IS_SENSITIVE`).
pub fn copy_to_clipboard(text: &str, sensitive: bool) -> Result<bool> {
    bridge::call_bool("copyToClipboard", &[Arg::Str(text), Arg::Bool(sensitive)])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(show("{}"), Err(Error::Unsupported)));
        assert!(matches!(cancel("sms:1"), Err(Error::Unsupported)));
        assert!(matches!(
            copy_to_clipboard("123456", true),
            Err(Error::Unsupported)
        ));
    }
}
