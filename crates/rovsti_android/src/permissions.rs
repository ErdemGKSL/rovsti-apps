//! Runtime permissions and the settings screens that fix them (FR-5, FR-6).
//!
//! Groups follow AD-18: `PHONE` is CALL_PHONE, READ_PHONE_STATE,
//! READ_CALL_LOG and WRITE_CALL_LOG; `CONTACTS` is READ_CONTACTS and
//! WRITE_CONTACTS. A group is granted only when every permission in it is.
//! Results are pulled; Kotlin also emits `permissions_changed` from
//! `MainActivity.onResume`, which is the cue to pull again.

use crate::{
    Result,
    bridge::{self, Arg},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Group {
    Phone,
    Contacts,
    Notifications,
}

impl Group {
    pub const ALL: [Group; 3] = [Group::Phone, Group::Contacts, Group::Notifications];

    /// The key `RovstiBridge` understands.
    pub fn key(self) -> &'static str {
        match self {
            Self::Phone => "phone",
            Self::Contacts => "contacts",
            Self::Notifications => "notifications",
        }
    }
}

/// State of a permission group.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum Access {
    #[default]
    Denied,
    Granted,
    /// Denied and Android will no longer show the dialog: send the user to
    /// the app's settings page instead.
    PermanentlyDenied,
}

impl Access {
    /// Decodes `RovstiBridge.permissionState`.
    pub fn from_code(code: i32) -> Self {
        match code {
            1 => Self::Granted,
            2 => Self::PermanentlyDenied,
            _ => Self::Denied,
        }
    }

    pub fn is_granted(self) -> bool {
        self == Self::Granted
    }
}

/// Current state of `group`.
pub fn state(group: Group) -> Result<Access> {
    bridge::call_int("permissionState", &[Arg::Str(group.key())]).map(Access::from_code)
}

/// Whether every permission in `group` is granted.
pub fn has(group: Group) -> Result<bool> {
    state(group).map(Access::is_granted)
}

/// Shows the system dialog. Returns `false` when it cannot be shown (already
/// granted, permanently denied, or no foreground activity). The outcome
/// arrives as `permissions_changed` when the app resumes.
pub fn request(group: Group) -> Result<bool> {
    bridge::call_bool("requestPermission", &[Arg::Str(group.key())])
}

/// Opens this app's system details page.
pub fn open_app_details() -> Result<bool> {
    bridge::call_bool("openAppDetails", &[])
}

/// Opens Settings, Apps, Default apps.
pub fn open_default_apps() -> Result<bool> {
    bridge::call_bool("openDefaultApps", &[])
}

/// `NotificationManager.canUseFullScreenIntent` (Android 14+ can revoke it).
pub fn can_use_full_screen_intent() -> Result<bool> {
    bridge::call_bool("canUseFullScreenIntent", &[])
}

/// Opens the "full-screen notifications" page for this app.
pub fn open_full_screen_intent_settings() -> Result<bool> {
    bridge::call_bool("openFullScreenIntentSettings", &[])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_decode() {
        assert_eq!(Access::from_code(0), Access::Denied);
        assert_eq!(Access::from_code(1), Access::Granted);
        assert_eq!(Access::from_code(2), Access::PermanentlyDenied);
        assert_eq!(Access::from_code(9), Access::Denied);
        assert!(Access::Granted.is_granted());
        assert!(!Access::PermanentlyDenied.is_granted());
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn desktop_is_unsupported() {
        use crate::Error;
        assert!(matches!(state(Group::Phone), Err(Error::Unsupported)));
        assert!(matches!(has(Group::Contacts), Err(Error::Unsupported)));
        assert!(matches!(
            request(Group::Notifications),
            Err(Error::Unsupported)
        ));
        assert!(matches!(open_app_details(), Err(Error::Unsupported)));
        assert!(matches!(open_default_apps(), Err(Error::Unsupported)));
        assert!(matches!(
            can_use_full_screen_intent(),
            Err(Error::Unsupported)
        ));
        assert!(matches!(
            open_full_screen_intent_settings(),
            Err(Error::Unsupported)
        ));
    }
}
