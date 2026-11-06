//! Content-provider pulls (AD-9, AD-10).
//!
//! Each function blocks on a JNI call and returns one JSON envelope
//! `{v, ok, error?, data?}` (see `rovsti_core::wire::Envelope`). Kotlin never
//! throws: a `SecurityException` is `ok:false, error:"permission"`. Run these
//! on a background executor, never on the UI thread. On desktop they return
//! [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// The newest `limit` call-log rows, `DATE DESC`.
pub fn call_log(limit: usize) -> Result<String> {
    let limit = i32::try_from(limit).unwrap_or(i32::MAX);
    bridge::call_string("pullCallLog", &[Arg::Int(limit)])
}

/// Deletes the call-log rows with these `_id`s; `{count}` on success.
pub fn delete_call_log(ids: &[i64]) -> Result<String> {
    let joined = ids.iter().map(i64::to_string).collect::<Vec<_>>().join(",");
    bridge::call_string("deleteCallLog", &[Arg::Str(&joined)])
}

/// Re-inserts call-log rows from a `{entries}` envelope (undo of a delete).
pub fn restore_call_log(entries_json: &str) -> Result<String> {
    bridge::call_string("restoreCallLog", &[Arg::Str(entries_json)])
}

/// Deletes the whole call log; `{count}` on success.
pub fn delete_all_call_log() -> Result<String> {
    bridge::call_string("deleteAllCallLog", &[])
}

/// Every contact that has a phone number.
pub fn contacts() -> Result<String> {
    bridge::call_string("pullContacts", &[])
}

/// Every visible contact, with or without a number: the `Phone` rows plus the
/// first email, in the same `contacts` envelope as [`contacts`] (`numbers` may be
/// empty, `email` set). Phone keeps [`contacts`].
pub fn contacts_all() -> Result<String> {
    bridge::call_string("pullContactsAll", &[])
}

/// Starred contacts.
pub fn favorites() -> Result<String> {
    bridge::call_string("pullFavorites", &[])
}

/// Story 8.5: sets or clears `STARRED` on a contact. `false` when Android
/// refused (no `WRITE_CONTACTS`). Blocks on a content-provider write: call it
/// off the UI thread.
pub fn set_starred(contact_id: i64, starred: bool) -> Result<bool> {
    bridge::call_bool(
        "setStarred",
        &[Arg::Str(&contact_id.to_string()), Arg::Bool(starred)],
    )
}

/// Story 10.5: a contact photo as a PNG file of at most `size` px, written by
/// Kotlin into the app's cache directory. Resolves to the file's path (the
/// seam moves only strings), or `""` when the contact has no readable photo.
/// Blocks on content-provider reads and image work: call it off the UI thread.
pub fn contact_photo(uri: &str, size: u32) -> Result<String> {
    let size = i32::try_from(size).unwrap_or(i32::MAX);
    bridge::call_string("contactPhoto", &[Arg::Str(uri), Arg::Int(size)])
}

/// The phone's clock style: `"<tz offset seconds>,<12|24>,<dmy|mdy|ymd>"`
/// (see `rovsti_core::recents::LocaleTime::parse`).
pub fn local_time_info() -> Result<String> {
    bridge::call_string("localTimeInfo", &[])
}

/// Clears Android's `NEW` flag on missed calls and asks Telecom to cancel the
/// system missed-call notification (story 6.4). `false` when Android refused
/// (not the default phone app, or the call-log permission is missing).
pub fn mark_missed_calls_read() -> Result<bool> {
    bridge::call_bool0("markMissedCallsRead")
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(call_log(500), Err(Error::Unsupported)));
        assert!(matches!(delete_call_log(&[1, 2]), Err(Error::Unsupported)));
        assert!(matches!(restore_call_log("{}"), Err(Error::Unsupported)));
        assert!(matches!(delete_all_call_log(), Err(Error::Unsupported)));
        assert!(matches!(contacts(), Err(Error::Unsupported)));
        assert!(matches!(contacts_all(), Err(Error::Unsupported)));
        assert!(matches!(favorites(), Err(Error::Unsupported)));
        assert!(matches!(set_starred(1, true), Err(Error::Unsupported)));
        assert!(matches!(local_time_info(), Err(Error::Unsupported)));
        assert!(matches!(
            contact_photo("content://x", 128),
            Err(Error::Unsupported)
        ));
        assert!(matches!(mark_missed_calls_read(), Err(Error::Unsupported)));
    }
}
