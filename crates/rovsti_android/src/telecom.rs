//! Calls through Android's Telecom stack.

use crate::{Result, bridge};

/// Places a call with `TelecomManager.placeCall` (needs `CALL_PHONE`; as the
/// default dialer Rovsti Phone also gets the in-call UI). Returns `false` if
/// the permission is missing and the system dial pad was opened instead.
pub fn place_call(number: &str) -> Result<bool> {
    bridge::call_bool_str("placeCall", number)
}

/// Opens the system "add contact" screen with `number` filled in. Needs no
/// permission; `false` when no Contacts app answered.
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
        assert!(matches!(edit_contact(1), Err(Error::Unsupported)));
        assert!(matches!(open_contacts(), Err(Error::Unsupported)));
    }
}
