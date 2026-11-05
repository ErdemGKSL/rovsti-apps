//! Whole-contact edits for Rovsti Contacts: open, save, delete, import, and
//! the contact groups. Kotlin side: `ContactsEdit`.
//!
//! Every function blocks on a content-provider call and returns one JSON
//! envelope `{v, ok, error?, data?}` (see `rovsti_core::wire::Envelope`), the
//! same as [`crate::providers`]. Run them on a background executor, never on
//! the UI thread. The payloads are `rovsti_core::contact_edit` types. On
//! desktop they return [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// One contact as a `ContactDraft` (`{contact}`), read from its primary raw contact.
pub fn detail(contact_id: i64) -> Result<String> {
    bridge::call_string("contactDetail", &[Arg::Str(&contact_id.to_string())])
}

/// Every contact that has a number, for export (`{contacts}`).
pub fn full() -> Result<String> {
    bridge::call_string("contactsFull", &[])
}

/// Creates (no `id`) or updates a contact from a `ContactDraft` JSON; answers `{id}`.
pub fn save(draft_json: &str) -> Result<String> {
    bridge::call_string("saveContact", &[Arg::Str(draft_json)])
}

/// Deletes the contact entirely. `false` when Android refused.
pub fn delete(contact_id: i64) -> Result<bool> {
    bridge::call_bool("deleteContact", &[Arg::Str(&contact_id.to_string())])
}

/// Adds phone-only contacts from `{contacts: [ContactDraft]}`; answers `{added}`.
pub fn import(contacts_json: &str) -> Result<String> {
    bridge::call_string("importContacts", &[Arg::Str(contacts_json)])
}

/// The contact groups and their members (`{groups}`).
pub fn groups() -> Result<String> {
    bridge::call_string("contactGroups", &[])
}

/// Creates a phone-only group (`group_id` 0) or renames one; answers `{id}`.
pub fn save_group(group_id: i64, title: &str) -> Result<String> {
    bridge::call_string(
        "saveContactGroup",
        &[Arg::Str(&group_id.to_string()), Arg::Str(title)],
    )
}

/// Deletes a group (its contacts stay). `false` when Android refused.
pub fn delete_group(group_id: i64) -> Result<bool> {
    bridge::call_bool("deleteContactGroup", &[Arg::Str(&group_id.to_string())])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(detail(1), Err(Error::Unsupported)));
        assert!(matches!(full(), Err(Error::Unsupported)));
        assert!(matches!(save("{}"), Err(Error::Unsupported)));
        assert!(matches!(delete(1), Err(Error::Unsupported)));
        assert!(matches!(import("{}"), Err(Error::Unsupported)));
        assert!(matches!(groups(), Err(Error::Unsupported)));
        assert!(matches!(save_group(0, "Family"), Err(Error::Unsupported)));
        assert!(matches!(delete_group(1), Err(Error::Unsupported)));
    }
}
