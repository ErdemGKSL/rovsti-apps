//! Permission decisions live in `rovsti_app_core`; this adds the conversion from
//! the Android permission state and keeps the call sites unchanged.

use rovsti_android::permissions::Access as Perm;
pub use rovsti_app_core::messages::access::{Ask, CONTACTS_OFF, resume_pulls};
use rovsti_app_core::permission::Access as Core;

fn core(state: Perm) -> Core {
    match state {
        Perm::Granted => Core::Granted,
        Perm::Denied => Core::Denied,
        Perm::PermanentlyDenied => Core::PermanentlyDenied,
    }
}

/// What to do about a permission in this state (`None` when granted).
pub fn ask_for(state: Perm) -> Option<Ask> {
    rovsti_app_core::messages::access::ask_for(core(state))
}

/// The notifications banner, see `rovsti_app_core::messages::access::notify_banner`.
pub fn notify_banner(app_switch_on: bool, state: Option<Perm>) -> Option<&'static str> {
    rovsti_app_core::messages::access::notify_banner(app_switch_on, state.map(core))
}
