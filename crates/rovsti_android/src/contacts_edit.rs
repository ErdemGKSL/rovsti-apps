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

/// Every contact, number or not, for export (`{contacts}`).
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

/// Creates a group in `account` (`group_id` 0; `type|name`, empty for the phone)
/// or renames one (the account is then ignored); answers `{id}`.
pub fn save_group(group_id: i64, title: &str, account: &str) -> Result<String> {
    bridge::call_string(
        "saveContactGroup",
        &[
            Arg::Str(&group_id.to_string()),
            Arg::Str(title),
            Arg::Str(account),
        ],
    )
}

/// Adds and removes group members from `change_json`
/// (`{add:[contact_id], remove:[contact_id]}`, a `SelectionChange`) in one batch;
/// answers `{added, removed, skipped}`. Contacts with no raw contact in the
/// group's account are skipped.
pub fn set_group_members(group_id: i64, change_json: &str) -> Result<String> {
    bridge::call_string(
        "setContactGroupMembers",
        &[Arg::Str(&group_id.to_string()), Arg::Str(change_json)],
    )
}

/// The accounts a contact can be saved to (`{accounts: [Account]}`): the ones
/// with contacts or groups whose sync adapter uploads. Needs no `GET_ACCOUNTS`.
pub fn accounts() -> Result<String> {
    bridge::call_string("contactAccounts", &[])
}

/// The pixel size of the image at `path` as `{"w":..,"h":..}`, rotation applied.
pub fn photo_size(path: &str) -> Result<String> {
    bridge::call_string("contactPhotoSize", &[Arg::Str(path)])
}

/// Crops the image at `path` by `plan_json` (`rovsti_core::contact_photo::PhotoPlan`),
/// scales it, and stores it as the contact's photo; the cached file is deleted
/// afterwards. `false` when Android refused or the image cannot be decoded.
pub fn set_photo(contact_id: i64, path: &str, plan_json: &str) -> Result<bool> {
    bridge::call_bool(
        "setContactPhoto",
        &[
            Arg::Str(&contact_id.to_string()),
            Arg::Str(path),
            Arg::Str(plan_json),
        ],
    )
}

/// Removes the contact's photo. `false` when Android refused.
pub fn remove_photo(contact_id: i64) -> Result<bool> {
    bridge::call_bool("removeContactPhoto", &[Arg::Str(&contact_id.to_string())])
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
        assert!(matches!(
            save_group(0, "Family", ""),
            Err(Error::Unsupported)
        ));
        assert!(matches!(
            set_group_members(1, "{}"),
            Err(Error::Unsupported)
        ));
        assert!(matches!(accounts(), Err(Error::Unsupported)));
        assert!(matches!(photo_size("/c/p"), Err(Error::Unsupported)));
        assert!(matches!(
            set_photo(1, "/c/p", "{}"),
            Err(Error::Unsupported)
        ));
        assert!(matches!(remove_photo(1), Err(Error::Unsupported)));
        assert!(matches!(delete_group(1), Err(Error::Unsupported)));
    }
}
