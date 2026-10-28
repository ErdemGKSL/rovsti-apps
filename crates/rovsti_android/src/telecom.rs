//! Calls through Android's Telecom stack.

use crate::{Result, bridge};

/// Places a call with `TelecomManager.placeCall` (needs `CALL_PHONE`; as the
/// default dialer Rovsti Phone also gets the in-call UI). Returns `false` if
/// the permission is missing and the system dial pad was opened instead.
pub fn place_call(number: &str) -> Result<bool> {
    bridge::call_bool_str("placeCall", number)
}

/// A settings page the apps hand off to (story 14.4).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SystemPage {
    Bluetooth,
    HearingDevices,
    Accessibility,
    Sound,
    DefaultApps,
    CallSettings,
    CallAccessibility,
    ChangePhoneAccounts,
    EmergencyInfo,
    AppDetails,
}

impl SystemPage {
    pub const ALL: [SystemPage; 10] = [
        Self::Bluetooth,
        Self::HearingDevices,
        Self::Accessibility,
        Self::Sound,
        Self::DefaultApps,
        Self::CallSettings,
        Self::CallAccessibility,
        Self::ChangePhoneAccounts,
        Self::EmergencyInfo,
        Self::AppDetails,
    ];

    /// The key `SystemPages.ACTIONS` (Kotlin) understands.
    pub fn key(self) -> &'static str {
        match self {
            Self::Bluetooth => "bluetooth",
            Self::HearingDevices => "hearing_devices",
            Self::Accessibility => "accessibility",
            Self::Sound => "sound",
            Self::DefaultApps => "default_apps",
            Self::CallSettings => "call_settings",
            Self::CallAccessibility => "call_accessibility",
            Self::ChangePhoneAccounts => "change_phone_accounts",
            Self::EmergencyInfo => "emergency_info",
            Self::AppDetails => "app_details",
        }
    }
}

/// Opens a system settings page. Returns the envelope
/// `{v, ok, error?, data?:{fallback}}` (see `rovsti_core::wire::OpenSystemResponse`):
/// `fallback:true` means App info opened because the page has no activity.
pub fn open_system(page: SystemPage) -> Result<String> {
    bridge::call_string("openSystem", &[bridge::Arg::Str(page.key())])
}

/// Opens the SMS composer for `number` (`smsto:`). `false` when no app answered.
pub fn send_sms_to(number: &str) -> Result<bool> {
    bridge::call_bool_str("sendSmsTo", number)
}

/// Puts `text` on the clipboard.
pub fn copy_text(text: &str) -> Result<bool> {
    bridge::call_bool_str("copyText", text)
}

/// Opens the system share sheet with `text`.
pub fn share_text(text: &str) -> Result<bool> {
    bridge::call_bool_str("shareText", text)
}

/// Whether the phone reports hearing-aid compatibility
/// (`TelephonyManager.isHearingAidCompatibilitySupported()`); feeds the
/// informational row in Settings (story 16.10).
pub fn hearing_aid_compatible() -> Result<bool> {
    bridge::call_bool0("hearingAidCompatible")
}

/// Opens the system "add contact" screen (create or add to existing) with
/// `number` filled in. Needs no permission; `false` when no Contacts app
/// answered.
pub fn add_contact(number: &str) -> Result<bool> {
    bridge::call_bool_str("addContact", number)
}

/// Opens the system Contacts app. `false` when no app answered.
pub fn open_contacts() -> Result<bool> {
    bridge::call_bool0("openContacts")
}

/// Opens the system Contacts app to edit `contact_id`. `false` when no app
/// answered.
pub fn edit_contact(contact_id: i64) -> Result<bool> {
    bridge::call_bool_str("editContact", &contact_id.to_string())
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(place_call("1"), Err(Error::Unsupported)));
        assert!(matches!(add_contact("1"), Err(Error::Unsupported)));
        for page in SystemPage::ALL {
            assert!(matches!(open_system(page), Err(Error::Unsupported)));
        }
        assert!(matches!(send_sms_to("1"), Err(Error::Unsupported)));
        assert!(matches!(copy_text("x"), Err(Error::Unsupported)));
        assert!(matches!(share_text("x"), Err(Error::Unsupported)));
        assert!(matches!(hearing_aid_compatible(), Err(Error::Unsupported)));
        assert!(matches!(edit_contact(1), Err(Error::Unsupported)));
        assert!(matches!(open_contacts(), Err(Error::Unsupported)));
    }

    #[test]
    fn page_keys_are_unique() {
        for (i, a) in SystemPage::ALL.iter().enumerate() {
            assert!(
                SystemPage::ALL
                    .iter()
                    .skip(i + 1)
                    .all(|b| b.key() != a.key())
            );
        }
    }
}
