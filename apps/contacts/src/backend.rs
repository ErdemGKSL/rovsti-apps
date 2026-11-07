//! Everything Rovsti Contacts asks Android, and how the answers read.
//!
//! Every function here blocks on a JNI call: run them on the background
//! executor, never on the UI thread. Errors come back as short words for the
//! notice line ("Can't save: Android refused the change."), never as codes.
//! Desktop runs have no provider; the reads answer from `sample` and the
//! writes say they need a phone.

use std::{
    path::{Path, PathBuf},
    sync::Arc,
};

use rovsti_android::{Error, contacts_edit, documents, media, providers};
use rovsti_core::{
    contact_edit::{
        Account, AccountsPayload, ContactDraft, DetailPayload, FullPayload, Group, GroupsPayload,
        ImportPayload, MembersPayload, SavedPayload, SelectionChange,
    },
    contact_photo::{self, CropView, PhotoError},
    contact_select,
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
    /// The picked image has no readable size.
    Unreadable,
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
            Self::Unreadable => "that image can't be read".to_owned(),
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

/// What the notice line says after "Can't ...: " for an Android call that
/// failed outright (the detail goes to the log, never on screen).
pub(crate) fn android_words(error: &Error) -> String {
    match error {
        Error::Unsupported => Failed::Unsupported.words(),
        other => Failed::Other(other.to_string()).words(),
    }
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
    match decode::<ContactsPayload>(providers::contacts_all()) {
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

/// The accounts a new contact can be saved to. The phone itself is always
/// first; desktop has the phone and one sample Google account.
pub(crate) fn pull_accounts() -> Vec<Account> {
    match decode::<AccountsPayload>(contacts_edit::accounts()) {
        Ok(payload) => with_phone(payload.accounts),
        Err(Failed::Unsupported) => with_phone(sample::accounts()),
        Err(error) => {
            log::warn!("accounts pull failed: {}", error.words());
            with_phone(Vec::new())
        }
    }
}

/// `accounts` without repeats, with "on this phone" (the empty key) first.
fn with_phone(accounts: Vec<Account>) -> Vec<Account> {
    let mut out = vec![Account::default()];
    for account in accounts {
        if !out.iter().any(|a| a.key == account.key) {
            out.push(account);
        }
    }
    out
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

/// Creates (`group_id` 0, in `account`) or renames a group (`account` is then
/// ignored).
pub(crate) fn save_group(group_id: i64, title: &str, account: &str) -> Result<i64, String> {
    decode::<SavedPayload>(contacts_edit::save_group(group_id, title, account))
        .map(|saved| saved.id)
        .map_err(|error| error.words())
}

/// Adds and removes members of a group; how many changed.
pub(crate) fn set_group_members(
    group_id: i64,
    change: &SelectionChange,
) -> Result<MembersPayload, String> {
    let json = serde_json::json!({ "add": change.add, "remove": change.remove }).to_string();
    decode::<MembersPayload>(contacts_edit::set_group_members(group_id, &json))
        .map_err(|error| error.words())
}

pub(crate) fn delete_group(group_id: i64) -> Result<(), String> {
    done(contacts_edit::delete_group(group_id))
}

/// Every contact (with or without a number) as vCard text, and how many that is.
/// `hidden` is a contact in its Undo window: it is left out of the file.
pub(crate) fn export_text(hidden: Option<i64>) -> Result<(String, usize), String> {
    let mut contacts = pull_full()?;
    contacts.retain(|c| hidden.is_none() || c.id != hidden);
    Ok((vcard::write(&contacts), contacts.len()))
}

/// Every contact as an editable, cleaned and valid draft, sorted by folded
/// name. Desktop answers from the sample.
pub(crate) fn pull_full() -> Result<Vec<ContactDraft>, String> {
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
    Ok(contacts)
}

/// The chosen contacts as vCard text, and how many that is. `hidden` is a
/// contact in its Undo window: it is left out even when chosen.
#[allow(dead_code)] // used by the selection export (plan step S7)
pub(crate) fn export_selection(
    ids: &[i64],
    hidden: Option<i64>,
) -> Result<(String, usize), String> {
    let all = pull_full()?;
    let mut cards = contact_select::pick_drafts(&all, ids);
    cards.retain(|c| hidden.is_none() || c.id != hidden);
    Ok((vcard::write(&cards), cards.len()))
}

/// Opens "save as" for the `.vcf`. The answer is a `document_result`.
pub(crate) fn start_export(text: &str) -> Result<(), String> {
    start_export_named("contacts.vcf", text)
}

/// Opens "save as" for a `.vcf` under the suggested `name`.
pub(crate) fn start_export_named(name: &str, text: &str) -> Result<(), String> {
    done(documents::export_contacts(name, text))
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

/// Opens the photo picker. The answer is a `document_result` (`contact_photo`).
pub(crate) fn start_pick_photo() -> Result<(), String> {
    done(documents::pick_contact_photo())
}

/// Opens the camera for one photo. The answer is a `document_result`
/// (`contact_photo`), like the picker's.
#[allow(dead_code)] // used by the photo sheet (plan step S7)
pub(crate) fn start_capture_photo() -> Result<(), String> {
    done(documents::capture_photo())
}

/// Removes the contact's photo.
pub(crate) fn remove_photo(contact_id: i64) -> Result<(), String> {
    done(contacts_edit::remove_photo(contact_id))
}

/// Reads the pixel size out of the answer of `photo_size`: `{"w":..,"h":..}`,
/// bare or inside an envelope. A size of zero means Android could not read it.
fn decode_size(raw: rovsti_android::Result<String>) -> Result<(u32, u32), Failed> {
    let json = match raw {
        Ok(json) => json,
        Err(Error::Unsupported) => return Err(Failed::Unsupported),
        Err(error) => return Err(Failed::Other(error.to_string())),
    };
    let value: serde_json::Value =
        serde_json::from_str(&json).map_err(|error| Failed::Other(error.to_string()))?;
    if value.get("ok").and_then(serde_json::Value::as_bool) == Some(false) {
        let error = value
            .get("error")
            .and_then(|e| serde_json::from_value(e.clone()).ok())
            .unwrap_or_default();
        return Err(Failed::Wire(error));
    }
    let body = value.get("data").unwrap_or(&value);
    let side = |key: &str| {
        body.get(key)
            .and_then(serde_json::Value::as_u64)
            .and_then(|n| u32::try_from(n).ok())
    };
    match (side("w"), side("h")) {
        (Some(w), Some(h)) if w > 0 && h > 0 => Ok((w, h)),
        _ => Err(Failed::Unreadable),
    }
}

/// What a refused crop plan says after "Can't change the photo: ".
fn photo_words(error: PhotoError) -> String {
    match error {
        PhotoError::Empty => "that image is empty".to_owned(),
        PhotoError::TooSmall => "that image is too small".to_owned(),
    }
}

/// Sets the picked image at `path` as the photo of `contact_id`: asks for its
/// size, plans the centred crop and has Android crop, scale and store it.
pub(crate) fn change_photo(contact_id: i64, path: &str) -> Result<(), String> {
    change_photo_with(contact_id, path, CropView::default())
}

/// Like [`change_photo`], with the crop the owner chose (turns, zoom, pan).
pub(crate) fn change_photo_with(contact_id: i64, path: &str, view: CropView) -> Result<(), String> {
    let (width, height) = decode_size(contacts_edit::photo_size(path)).map_err(|e| e.words())?;
    let plan = contact_photo::plan_for(width, height, view).map_err(photo_words)?;
    let plan =
        serde_json::to_string(&plan).map_err(|error| Failed::Other(error.to_string()).words())?;
    done(contacts_edit::set_photo(contact_id, path, &plan))
}

/// A contact photo thumbnail, decoded. Desktop paints nothing.
pub(crate) fn contact_photo(uri: &str, size: u32) -> Option<image::RgbaImage> {
    match providers::contact_photo(uri, size) {
        Ok(path) if path.is_empty() => None,
        Ok(path) => contact_photo_at(&path),
        Err(Error::Unsupported) => None,
        Err(error) => {
            log::debug!("contact photo failed: {error}");
            None
        }
    }
}

// The photo viewer and share actions (plan steps S5 to S8) call these; until
// they land the wrappers are only exercised by tests.
#[allow(dead_code)]
mod hand_off {
    use super::*;

    /// Side length of the photo shown in the full-screen viewer.
    pub(crate) const FULL_PHOTO: u32 = 1080;

    /// Where files for save and share are written.
    fn temp_dir() -> Option<PathBuf> {
        let dir = std::env::temp_dir().join("rovsti-contacts");
        std::fs::create_dir_all(&dir).ok()?;
        Some(dir)
    }

    /// A file name that is safe on every file system.
    pub(super) fn safe_name(name: &str) -> String {
        let clean: String = name
            .chars()
            .map(|c| {
                if c.is_alphanumeric() || matches!(c, '-' | '_' | '.') {
                    c
                } else {
                    '_'
                }
            })
            .collect();
        let clean = clean.trim_matches(['_', '.']);
        if clean.is_empty() {
            "contact".to_owned()
        } else {
            clean.chars().take(60).collect()
        }
    }

    fn write_temp(name: &str, bytes: &[u8]) -> Result<PathBuf, String> {
        let words = || Failed::Other("temp file".to_owned()).words();
        let path = temp_dir().ok_or_else(words)?.join(safe_name(name));
        std::fs::write(&path, bytes).map_err(|_| words())?;
        Ok(path)
    }

    /// The full-size photo of a contact, decoded, plus a PNG file it can be saved
    /// or shared from. Blocks: run it on the background executor. Desktop paints
    /// a sample portrait into the temp PNG.
    pub(crate) fn full_photo(uri: &str) -> Option<(image::RgbaImage, PathBuf)> {
        let (image, source) = match providers::contact_photo(uri, FULL_PHOTO) {
            Ok(path) if path.is_empty() => return None,
            Ok(path) => (contact_photo_at(&path)?, Some(path)),
            Err(Error::Unsupported) => (sample::photo(FULL_PHOTO), None),
            Err(error) => {
                log::debug!("full photo failed: {error}");
                return None;
            }
        };
        let path = match source {
            Some(path) => PathBuf::from(path),
            None => {
                let name: String = uri.chars().filter(char::is_ascii_alphanumeric).collect();
                let path = temp_dir()?.join(format!("photo-{name}.png"));
                image
                    .save_with_format(&path, image::ImageFormat::Png)
                    .ok()?;
                path
            }
        };
        Some((image, path))
    }

    pub(super) fn contact_photo_at(path: &str) -> Option<image::RgbaImage> {
        let bytes = std::fs::read(path).ok()?;
        image::load_from_memory_with_format(&bytes, image::ImageFormat::Png)
            .map_err(|error| log::debug!("photo {path}: {error}"))
            .ok()
            .map(image::DynamicImage::into_rgba8)
    }

    /// Saves the PNG at `path` to the gallery (Pictures/Rovsti) as `name`.
    pub(crate) fn save_to_gallery(path: &Path, name: &str) -> Result<(), String> {
        done(media::save_image_to_gallery(
            &path.to_string_lossy(),
            name,
            "image/png",
        ))
    }

    /// Opens the share sheet with the PNG at `path`.
    pub(crate) fn share_image(path: &Path, title: &str) -> Result<(), String> {
        done(media::share_image(
            &path.to_string_lossy(),
            "image/png",
            title,
        ))
    }

    /// Opens the share sheet with `text` as a `.vcf` file called `name`.
    pub(crate) fn share_vcf(name: &str, text: &str) -> Result<(), String> {
        let stem = name.strip_suffix(".vcf").unwrap_or(name);
        let file = format!("{}.vcf", safe_name(stem));
        let path = write_temp(&file, text.as_bytes())?;
        done(media::share_file(
            &path.to_string_lossy(),
            "text/x-vcard",
            &file,
        ))
    }

    /// Saves `bytes` (a PNG) to the gallery as `name`.
    pub(crate) fn save_png(name: &str, bytes: &[u8]) -> Result<(), String> {
        let path = write_temp(name, bytes)?;
        save_to_gallery(&path, name)
    }

    /// Opens the share sheet with `bytes` as a PNG file called `name`.
    pub(crate) fn share_png(name: &str, bytes: &[u8]) -> Result<(), String> {
        let stem = name.strip_suffix(".png").unwrap_or(name);
        let file = format!("{}.png", safe_name(stem));
        let path = write_temp(&file, bytes)?;
        done(media::share_image(
            &path.to_string_lossy(),
            "image/png",
            &file,
        ))
    }
}
#[allow(unused_imports)]
pub(crate) use hand_off::*;

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
    fn save_png_is_unsupported_on_desktop() {
        assert!(save_png("qr.png", &[1]).is_err());
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
    fn android_failures_never_show_their_detail() {
        assert_eq!(
            android_words(&Error::Unsupported),
            "this needs an Android phone"
        );
        assert_eq!(
            android_words(&Error::NotInitialized),
            "something went wrong",
            "the bridge detail stays in the log"
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
    fn desktop_accounts_are_the_phone_and_a_sample_google_account() {
        let accounts = pull_accounts();
        assert_eq!(accounts.len(), 2);
        assert_eq!(accounts[0].key, "", "the phone comes first");
        assert_eq!(accounts[1].key, sample::GOOGLE_ACCOUNT);
    }

    #[test]
    fn the_phone_is_listed_once_and_first() {
        let google = Account {
            key: "com.google|a@b.com".to_owned(),
            ..Account::default()
        };
        let listed = with_phone(vec![google.clone(), Account::default(), google.clone()]);
        let keys: Vec<&str> = listed.iter().map(|a| a.key.as_str()).collect();
        assert_eq!(keys, ["", "com.google|a@b.com"]);
    }

    #[test]
    fn the_size_answer_reads_bare_or_in_an_envelope() {
        let bare = r#"{"w":4000,"h":3000}"#;
        assert!(matches!(decode_size(Ok(bare.to_owned())), Ok((4000, 3000))));
        let wrapped = r#"{"v":1,"ok":true,"data":{"w":300,"h":900}}"#;
        assert!(matches!(
            decode_size(Ok(wrapped.to_owned())),
            Ok((300, 900))
        ));
    }

    #[test]
    fn a_bad_size_answer_is_a_failure() {
        let zero = r#"{"w":0,"h":0}"#;
        assert!(matches!(
            decode_size(Ok(zero.to_owned())),
            Err(Failed::Unreadable)
        ));
        let denied = r#"{"v":1,"ok":false,"error":"permission"}"#;
        assert!(matches!(
            decode_size(Ok(denied.to_owned())),
            Err(Failed::Wire(WireError::Permission))
        ));
        assert!(matches!(
            decode_size(Ok("nope".to_owned())),
            Err(Failed::Other(_))
        ));
        assert!(matches!(
            decode_size(Err(Error::Unsupported)),
            Err(Failed::Unsupported)
        ));
    }

    #[test]
    fn desktop_photo_actions_need_a_phone() {
        let words = "this needs an Android phone";
        assert_eq!(start_pick_photo().expect_err("no picker"), words);
        assert_eq!(start_capture_photo().expect_err("no camera"), words);
        assert_eq!(
            change_photo_with(1, "/c/p", CropView::default().rotate_right())
                .expect_err("no provider"),
            words
        );
        assert_eq!(
            start_export_named("a.vcf", "BEGIN:VCARD").expect_err("no picker"),
            words
        );
        assert_eq!(remove_photo(1).expect_err("no provider"), words);
        assert_eq!(change_photo(1, "/c/p").expect_err("no provider"), words);
        assert_eq!(Failed::Unreadable.words(), "that image can't be read");
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
        let (text, count) = export_text(None).expect("sample exports");
        assert_eq!(count, sample::contacts().len());
        assert!(text.contains("BEGIN:VCARD"));
        let first = sample::contacts()[0].id;
        let (_, fewer) = export_text(Some(first)).expect("sample exports");
        assert_eq!(fewer, count - 1);
    }

    const NEEDS_PHONE: &str = "this needs an Android phone";

    #[test]
    fn desktop_hand_offs_need_a_phone() {
        let path = Path::new("/c/p.png");
        assert_eq!(
            save_to_gallery(path, "p.png").expect_err("no gallery"),
            NEEDS_PHONE
        );
        assert_eq!(share_image(path, "").expect_err("no sheet"), NEEDS_PHONE);
        assert_eq!(
            share_vcf("Ayşe Yılmaz", "BEGIN:VCARD").expect_err("no sheet"),
            NEEDS_PHONE
        );
        assert_eq!(
            share_png("qr", &[1, 2, 3]).expect_err("no sheet"),
            NEEDS_PHONE
        );
    }

    #[test]
    fn desktop_full_photo_is_a_sample_png_on_disk() {
        let (image, path) = full_photo("content://c/1").expect("sample photo");
        assert_eq!(image.width(), FULL_PHOTO);
        assert!(path.exists());
        assert_eq!(path.extension().and_then(|e| e.to_str()), Some("png"));
    }

    #[test]
    fn file_names_are_made_safe() {
        assert_eq!(safe_name("Ayşe Yılmaz"), "Ayşe_Yılmaz");
        assert_eq!(safe_name("../a/b"), "a_b");
        assert_eq!(safe_name("  "), "contact");
    }

    #[test]
    fn the_full_pull_is_clean_valid_and_feeds_the_export() {
        let all = pull_full().expect("sample");
        assert_eq!(all.len(), sample::contacts().len());
        assert!(all.iter().all(|c| c.validate().is_ok()));
        let (_, count) = export_text(None).expect("export");
        assert_eq!(count, all.len());
    }

    #[test]
    fn export_selection_writes_only_the_chosen_cards() {
        let (text, count) = export_selection(&[1, 3], None).expect("sample");
        assert_eq!(count, 2);
        assert_eq!(text.matches("BEGIN:VCARD").count(), 2);
        let (_, none) = export_selection(&[], None).expect("sample");
        assert_eq!(none, 0);
        let (_, left) = export_selection(&[1, 3], Some(1)).expect("sample");
        assert_eq!(left, 1);
    }
}
