//! Pure decisions about Android permissions: what to ask, what to say, what to
//! refresh when the user comes back from system settings.

use rovsti_android::permissions::Access as Perm;

/// How to ask for a permission.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Ask {
    /// Show the system dialog.
    Request,
    /// Android no longer shows the dialog: send the user to App info.
    OpenAppInfo,
}

/// What to do about a permission in this state (`None` when granted).
pub fn ask_for(state: Perm) -> Option<Ask> {
    match state {
        Perm::Granted => None,
        Perm::Denied => Some(Ask::Request),
        Perm::PermanentlyDenied => Some(Ask::OpenAppInfo),
    }
}

/// Banner text when Android has notifications off.
pub const NOTIFY_OFF: &str =
    "Android has notifications off for Rovsti Messages. Tap to allow them.";

/// Banner text when contacts access is off.
pub const CONTACTS_OFF: &str =
    "Contacts access is off: names and search by name need it. Tap to allow.";

/// The notifications banner: only when the app's own switch is on and Android
/// says the permission is missing (`None` = unknown, e.g. on desktop).
pub fn notify_banner(app_switch_on: bool, state: Option<Perm>) -> Option<&'static str> {
    (app_switch_on && state.is_some_and(|s| s != Perm::Granted)).then_some(NOTIFY_OFF)
}

/// What to pull again when the app returns to the foreground.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
#[allow(clippy::struct_excessive_bools)]
pub struct ResumePulls {
    pub role: bool,
    pub access: bool,
    pub threads: bool,
    pub contacts: bool,
}

/// Role and permission state are always re-read; threads and contacts only
/// when they were denied (the user may have fixed that in settings).
pub fn resume_pulls(threads: crate::backend::Access, contacts_denied: bool) -> ResumePulls {
    ResumePulls {
        role: true,
        access: true,
        threads: threads == crate::backend::Access::Denied,
        contacts: contacts_denied,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::backend::Access;

    #[test]
    fn ask_maps_each_state() {
        assert!(CONTACTS_OFF.contains("Contacts"));
        assert_eq!(ask_for(Perm::Granted), None);
        assert_eq!(ask_for(Perm::Denied), Some(Ask::Request));
        assert_eq!(ask_for(Perm::PermanentlyDenied), Some(Ask::OpenAppInfo));
    }

    #[test]
    fn banner_only_when_switch_on_and_android_says_no() {
        assert_eq!(notify_banner(true, Some(Perm::Denied)), Some(NOTIFY_OFF));
        assert_eq!(
            notify_banner(true, Some(Perm::PermanentlyDenied)),
            Some(NOTIFY_OFF)
        );
        assert_eq!(notify_banner(true, Some(Perm::Granted)), None);
        assert_eq!(notify_banner(true, None), None);
        assert_eq!(notify_banner(false, Some(Perm::Denied)), None);
    }

    #[test]
    fn resume_repulls_only_what_was_denied() {
        let all = resume_pulls(Access::Denied, true);
        assert!(all.role && all.access && all.threads && all.contacts);
        let none = resume_pulls(Access::Ready, false);
        assert_eq!(
            none,
            ResumePulls {
                role: true,
                access: true,
                threads: false,
                contacts: false
            }
        );
        assert!(!resume_pulls(Access::Loading, false).threads);
        assert!(!resume_pulls(Access::Unavailable, false).threads);
    }
}
