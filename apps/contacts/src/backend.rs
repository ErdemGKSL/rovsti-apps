//! Everything Rovsti Contacts asks Android, and how the answers read.
//!
//! Every function here blocks on a JNI call: run them on the background
//! executor, never on the UI thread. Errors come back as short words for the
//! notice line ("Can't save: Android refused the change."), never as codes.
//! Desktop runs have no provider; the reads answer from `sample` and the
//! writes say they need a phone.

use std::sync::Arc;

use rovsti_android::{Error, contacts_edit, documents, providers};
use rovsti_core::{
    contact_edit::{
        ContactDraft, DetailPayload, FullPayload, Group, GroupsPayload, ImportPayload, SavedPayload,
    },
    search::{ContactIndex, fold},
    vcard,
    wire::{ContactsPayload, Envelope, WireError},
};
use serde::de::DeserializeOwned;

use crate::sample;

/// The address book as last pulled.
pub(crate) enum Book {
    Loading,
    Ready(Arc<ContactIndex>),
    /// `READ_CONTACTS` is not granted.
    Denied,
    Unavailable,
}

/// Why a call failed.
enum Failed {
    /// Desktop: there is no Android to ask.
    Unsupported,
    Wire(WireError),
    Other(String),
}

impl Failed {
    /// What the notice line says after "Can't ...: ".
    fn words(&self) -> String {
        match self {
            Self::Unsupported => "this needs an Android phone".to_owned(),
            Self::Wire(WireError::Permission) => "Contacts access is off".to_owned(),
            Self::Wire(WireError::Refused) => "Android refused the change".to_owned(),
            Self::Wire(WireError::NotFound) => "that contact is gone".to_owned(),
            Self::Wire(_) => "something went wrong".to_owned(),
            Self::Other(detail) => {
                log::warn!("contacts call failed: {detail}");
                "something went wrong".to_owned()
            }
        }
    }
}

fn decode<T: DeserializeOwned + Default>(raw: rovsti_android::Result<String>) -> Result<T, Failed> {
    let json = match raw {
        Ok(json) => json,
        Err(Error::Unsupported) => return Err(Failed::Unsupported),
        Err(error) => return Err(Failed::Other(error.to_string())),
    };
    let envelope: Envelope<T> =
        serde_json::from_str(&json).map_err(|error| Failed::Other(error.to_string()))?;
    envelope.into_result().map_err(Failed::Wire)
}

fn done(result: rovsti_android::Result<bool>) -> Result<(), String> {
    match result {
        Ok(true) => Ok(()),
        Ok(false) => Err(Failed::Wire(WireError::Refused).words()),
        Err(Error::Unsupported) => Err(Failed::Unsupported.words()),
        Err(error) => Err(Failed::Other(error.to_string()).words()),
    }
}

/// Pulls and indexes the contacts.
pub(crate) fn pull_book() -> Book {
    match decode::<ContactsPayload>(providers::contacts()) {
        Ok(payload) => Book::Ready(Arc::new(ContactIndex::new(payload.contacts))),
        Err(Failed::Wire(WireError::Permission)) => Book::Denied,
        Err(Failed::Unsupported) => Book::Ready(Arc::new(ContactIndex::new(sample::contacts()))),
        Err(error) => {
            log::warn!("contacts pull failed: {}", error.words());
            Book::Unavailable
        }
    }
}

/// The groups and their members; none when they cannot be read.
pub(crate) fn pull_groups() -> Vec<Group> {
    match decode::<GroupsPayload>(contacts_edit::groups()) {
        Ok(payload) => payload.groups,
        Err(Failed::Unsupported) => sample::groups(),
        Err(_) => Vec::new(),
    }
}

/// One contact in editable form.
pub(crate) fn pull_detail(contact_id: i64) -> Result<ContactDraft, String> {
    match decode::<DetailPayload>(contacts_edit::detail(contact_id)) {
        Ok(payload) => Ok(payload.contact),
        Err(Failed::Unsupported) => {
            sample::draft(contact_id).ok_or_else(|| Failed::Wire(WireError::NotFound).words())
        }
        Err(error) => Err(error.words()),
    }
}

/// Creates or updates a contact; the id it has afterwards.
pub(crate) fn save(draft: &ContactDraft) -> Result<i64, String> {
    let json =
        serde_json::to_string(draft).map_err(|error| Failed::Other(error.to_string()).words())?;
    decode::<SavedPayload>(contacts_edit::save(&json))
        .map(|saved| saved.id)
        .map_err(|error| error.words())
}

pub(crate) fn delete(contact_id: i64) -> Result<(), String> {
    done(contacts_edit::delete(contact_id))
}

/// Creates (`group_id` 0) or renames a group.
pub(crate) fn save_group(group_id: i64, title: &str) -> Result<i64, String> {
    decode::<SavedPayload>(contacts_edit::save_group(group_id, title))
        .map(|saved| saved.id)
        .map_err(|error| error.words())
}

pub(crate) fn delete_group(group_id: i64) -> Result<(), String> {
    done(contacts_edit::delete_group(group_id))
}

/// Every contact that has a number as vCard text, and how many that is.
pub(crate) fn export_text() -> Result<(String, usize), String> {
    let mut contacts = match decode::<FullPayload>(contacts_edit::full()) {
        Ok(payload) => payload.contacts,
        Err(Failed::Unsupported) => sample::contacts()
            .iter()
            .filter_map(|c| sample::draft(c.id))
            .collect(),
        Err(error) => return Err(error.words()),
    };
    for contact in &mut contacts {
        contact.clean();
    }
    contacts.retain(|c| c.validate().is_ok());
    contacts.sort_by_cached_key(|c| fold(&c.display_name()));
    Ok((vcard::write(&contacts), contacts.len()))
}

/// Opens "save as" for the `.vcf`. The answer is a `document_result`.
pub(crate) fn start_export(text: &str) -> Result<(), String> {
    done(documents::export_contacts("contacts.vcf", text))
}

/// Opens the file picker for a `.vcf`. The answer is a `document_result`.
pub(crate) fn start_import() -> Result<(), String> {
    done(documents::import_contacts())
}

/// Adds the cards as phone-only contacts; how many were added.
pub(crate) fn add_contacts(cards: Vec<ContactDraft>) -> Result<u32, String> {
    let json = serde_json::to_string(&FullPayload { contacts: cards })
        .map_err(|error| Failed::Other(error.to_string()).words())?;
    decode::<ImportPayload>(contacts_edit::import(&json))
        .map(|payload| payload.added)
        .map_err(|error| error.words())
}

/// A contact photo thumbnail, decoded. Desktop paints nothing.
pub(crate) fn contact_photo(uri: &str, size: u32) -> Option<image::RgbaImage> {
    match providers::contact_photo(uri, size) {
        Ok(path) if path.is_empty() => None,
        Ok(path) => {
            let bytes = std::fs::read(&path).ok()?;
            image::load_from_memory_with_format(&bytes, image::ImageFormat::Png)
                .map_err(|error| log::debug!("photo {path}: {error}"))
                .ok()
                .map(image::DynamicImage::into_rgba8)
        }
        Err(Error::Unsupported) => None,
        Err(error) => {
            log::debug!("contact photo failed: {error}");
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_denied_pull_is_a_value_not_a_failure() {
        let json = r#"{"v":1,"ok":false,"error":"permission"}"#;
        let result = decode::<ContactsPayload>(Ok(json.to_owned()));
        assert!(matches!(result, Err(Failed::Wire(WireError::Permission))));
    }

    #[test]
    fn failures_read_in_plain_words() {
        assert_eq!(
            Failed::Wire(WireError::Refused).words(),
            "Android refused the change"
        );
        assert_eq!(
            Failed::Wire(WireError::NotFound).words(),
            "that contact is gone"
        );
        assert_eq!(
            Failed::Other("bad json at 3".to_owned()).words(),
            "something went wrong",
            "internal detail stays out of the notice"
        );
    }

    #[test]
    fn a_saved_answer_carries_the_id() {
        let json = r#"{"v":1,"ok":true,"data":{"id":42}}"#;
        let saved = decode::<SavedPayload>(Ok(json.to_owned()));
        assert!(matches!(saved, Ok(SavedPayload { id: 42 })));
    }

    #[test]
    fn garbage_is_an_error_not_a_panic() {
        assert!(matches!(
            decode::<SavedPayload>(Ok("<html>".to_owned())),
            Err(Failed::Other(_))
        ));
    }

    #[test]
    fn desktop_reads_the_sample_and_refuses_writes() {
        assert!(matches!(pull_book(), Book::Ready(_)));
        assert!(!pull_groups().is_empty());
        assert!(pull_detail(1).is_ok());
        assert_eq!(
            pull_detail(999).expect_err("no such contact"),
            "that contact is gone".to_owned()
        );
        assert_eq!(
            save(&ContactDraft::blank()).expect_err("desktop cannot save"),
            "this needs an Android phone"
        );
        assert_eq!(
            delete(1).expect_err("desktop cannot delete"),
            "this needs an Android phone"
        );
        let (text, count) = export_text().expect("sample exports");
        assert_eq!(count, sample::contacts().len());
        assert!(text.contains("BEGIN:VCARD"));
    }
}
