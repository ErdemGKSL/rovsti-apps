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

use rovsti_android::{
    Error, contacts_edit, documents, media, providers, reminders, shortcuts, telecom,
};
use rovsti_core::{
    birthday_reminder::{self, BirthdayAlarm},
    call_stats, contact_csv,
    contact_dates::Upcoming,
    contact_edit,
    contact_edit::{
        Account, AccountsPayload, ContactDraft, DetailPayload, FullPayload, Group, GroupsPayload,
        ImportPayload, MembersPayload, SavedPayload, SelectionChange,
    },
    contact_find::DeepIndex,
    contact_frequent::{self, Frequent},
    contact_history::PULL_LIMIT,
    contact_intake::{self, Intake},
    contact_options::{self, ContactOptions, PinShortcut},
    contact_photo::{self, CropView, PhotoError},
    contact_scan::{self, Scanned},
    contact_select,
    contact_stats::{self, Summary},
    contacts_prefs::ContactsPrefs,
    recents::{self, HistoryLine},
    search::{ContactIndex, fold},
    vcard::{self, CardPhoto, PhotoFormat},
    wire::{CallLogPayload, ContactsPayload, Envelope, WireContact, WireError},
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
/// `hidden` are contacts in their Undo window: they are left out of the file.
pub(crate) fn export_text(hidden: &[i64]) -> Result<(String, usize), String> {
    let mut contacts = pull_full()?;
    contacts.retain(|c| c.id.is_none_or(|id| !hidden.contains(&id)));
    Ok((vcard::write(&contacts), contacts.len()))
}

/// Every contact as a Google-style CSV, and how many that is. `hidden` are
/// contacts in their Undo window: they are left out of the file.
#[allow(dead_code)] // used by plan steps S5 and S6
pub(crate) fn csv_text(hidden: &[i64]) -> Result<(String, usize), String> {
    let mut contacts = pull_full()?;
    contacts.retain(|c| c.id.is_none_or(|id| !hidden.contains(&id)));
    Ok((contact_csv::write(&contacts), contacts.len()))
}

/// The detail index for "In details" search over every contact.
#[allow(dead_code)] // used by plan steps S5 and S6
pub(crate) fn deep_index() -> Result<DeepIndex, String> {
    Ok(DeepIndex::new(&pull_full()?))
}

/// Most called unstarred contacts of the last 30 days, from the local call
/// log. Without the permission this is an error in words; desktop answers from
/// the sample calls.
#[allow(dead_code)] // used by plan steps S5 and S6
pub(crate) fn frequent(contacts: &[WireContact], now_ms: i64) -> Result<Vec<Frequent>, String> {
    let entries = match decode::<CallLogPayload>(providers::call_log(PULL_LIMIT)) {
        Ok(payload) => payload.entries,
        Err(Failed::Unsupported) => sample::calls(now_ms),
        Err(error) => return Err(error.words()),
    };
    let calls = call_stats::from_sources(&entries, &[]);
    Ok(contact_frequent::rank(
        &calls,
        contacts,
        now_ms,
        contact_frequent::MAX_SHOWN,
    ))
}

/// Cards of an opened file: a contacts CSV or vCard text, and how many cards
/// were unusable.
#[allow(dead_code)] // used by plan steps S5 and S6
pub(crate) fn cards_of(text: &str) -> (Vec<ContactDraft>, usize) {
    let parsed = if contact_csv::looks_like_csv(None, "", text.as_bytes()) {
        contact_csv::parse(text)
    } else {
        vcard::parse(text)
    };
    (parsed.contacts, parsed.skipped)
}

/// The photo of a text holding exactly one vCard.
#[allow(dead_code)] // used by plan steps S5 and S6
pub(crate) fn single_photo(text: &str) -> Option<CardPhoto> {
    let mut photos = vcard::photos(text);
    if photos.len() == 1 {
        photos.remove(0)
    } else {
        None
    }
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

/// The chosen contacts as vCard text, and how many that is. `hidden` are
/// contacts in their Undo window: they are left out even when chosen.
pub(crate) fn export_selection(ids: &[i64], hidden: &[i64]) -> Result<(String, usize), String> {
    let all = pull_full()?;
    let mut cards = contact_select::pick_drafts(&all, ids);
    cards.retain(|c| c.id.is_none_or(|id| !hidden.contains(&id)));
    Ok((vcard::write(&cards), cards.len()))
}

/// The editable drafts of the contacts with these ids, in address-book order.
pub(crate) fn drafts_of(ids: &[i64]) -> Result<Vec<ContactDraft>, String> {
    Ok(contact_select::pick_drafts(&pull_full()?, ids))
}

/// Calls with any of `numbers`, newest first (cellular only; local call log).
/// Without the call-log permission or on desktop this is an error in words.
pub(crate) fn call_history(numbers: &[String]) -> Result<Vec<HistoryLine>, String> {
    let payload: CallLogPayload =
        decode(providers::call_log(PULL_LIMIT)).map_err(|error| error.words())?;
    let wanted: Vec<&str> = numbers.iter().map(String::as_str).collect();
    Ok(recents::history(&payload.entries, &[], &wanted, PULL_LIMIT))
}

/// Makes entry `index` of the contact's numbers (or e-mails, when `emails`) its
/// default, or clears the default when `index` is `None`, and saves.
pub(crate) fn set_default(
    draft: &ContactDraft,
    emails: bool,
    index: Option<usize>,
) -> Result<i64, String> {
    let mut edited = draft.clone();
    let list = if emails {
        &mut edited.emails
    } else {
        &mut edited.numbers
    };
    contact_edit::set_primary(list, index);
    save(&edited)
}

/// What a scanned picture holds, or why it could not be read.
pub(crate) fn scan_image(path: &str) -> Result<Scanned, String> {
    let bytes = std::fs::read(path).map_err(|_| Failed::Unreadable.words())?;
    let decoded = image::load_from_memory(&bytes).map_err(|_| Failed::Unreadable.words())?;
    let side = contact_scan::MAX_SIDE;
    let luma = if decoded.width().max(decoded.height()) > side {
        decoded.resize(side, side, image::imageops::FilterType::Triangle)
    } else {
        decoded
    }
    .to_luma8();
    for text in contact_scan::decode_luma(luma.width(), luma.height(), luma.as_raw()) {
        let scanned = contact_scan::parse_payload(&text);
        if scanned != Scanned::Nothing {
            return Ok(scanned);
        }
    }
    Ok(Scanned::Nothing)
}

/// Opens one SMS composer for all `numbers`.
pub(crate) fn group_sms(numbers: &[String], body: &str) -> Result<(), String> {
    done(telecom::compose_group_sms(numbers, body))
}

/// Opens the e-mail composer for all `addresses`.
pub(crate) fn group_email(addresses: &[String]) -> Result<(), String> {
    done(telecom::email_group(addresses))
}

/// Brings the armed birthday alarms in line with `upcoming`: arms the missing
/// ones and disarms the stale ones. With reminders off, disarms them all.
pub(crate) fn sync_birthdays(
    prefs: &ContactsPrefs,
    upcoming: &[Upcoming],
    now_ms: i64,
    tz_offset_s: i32,
) -> Result<(), String> {
    let armed: Vec<i64> = serde_json::from_str::<Vec<serde_json::Value>>(
        &reminders::armed_birthdays().map_err(|error| android_words(&error))?,
    )
    .map_err(|error| Failed::Other(error.to_string()).words())?
    .iter()
    .filter_map(|v| v.get("id").and_then(serde_json::Value::as_i64))
    .collect();
    let wanted: Vec<BirthdayAlarm> = if prefs.birthday_reminders {
        birthday_reminder::plan(upcoming, now_ms, tz_offset_s, prefs.hour())
    } else {
        Vec::new()
    };
    let (stale, missing) = birthday_reminder::diff(&armed, &wanted);
    for id in stale {
        reminders::disarm_birthday(id).map_err(|error| android_words(&error))?;
    }
    for alarm in missing {
        let json = serde_json::to_string(alarm)
            .map_err(|error| Failed::Other(error.to_string()).words())?;
        done(reminders::arm_birthday(&json))?;
    }
    Ok(())
}

/// Opens "save as" for the `.vcf`. The answer is a `document_result`.
pub(crate) fn start_export(text: &str) -> Result<(), String> {
    start_export_named("contacts.vcf", text)
}

/// Opens "save as" for a `.vcf` under the suggested `name`.
pub(crate) fn start_export_named(name: &str, text: &str) -> Result<(), String> {
    done(documents::export_contacts(name, text))
}

/// Opens "save as" for a `.csv` under the suggested `name`. The answer is a
/// `document_result` with op `export_calls`.
#[allow(dead_code)] // used by plan steps S5 and S6
pub(crate) fn start_export_csv(name: &str, csv: &str) -> Result<(), String> {
    done(documents::export_calls(name, csv))
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

/// Opens the photo picker for a picture to scan. The answer is a
/// `document_result` (`scan_image`).
pub(crate) fn start_pick_scan() -> Result<(), String> {
    done(documents::pick_scan_image())
}

/// Opens the camera for one photo. The answer is a `document_result`
/// (`contact_photo`), like the picker's.
pub(crate) fn start_capture_photo() -> Result<(), String> {
    match documents::capture_photo() {
        // Android found nothing to handle the camera intent.
        Ok(false) => Err(NO_CAMERA.to_owned()),
        other => done(other),
    }
}

/// What `start_capture_photo` says when no camera app answers.
pub(crate) const NO_CAMERA: &str = "No camera app found";

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

/// Sets the picked image at `path` as the photo of `contact_id`, cropped as
/// the owner chose (turns, zoom, pan): asks for its size, plans the crop and
/// has Android crop, scale and store it.
pub(crate) fn change_photo_with(contact_id: i64, path: &str, view: CropView) -> Result<(), String> {
    let (width, height) = decode_size(contacts_edit::photo_size(path)).map_err(|e| e.words())?;
    let plan = contact_photo::plan_for(width, height, view).map_err(photo_words)?;
    let plan =
        serde_json::to_string(&plan).map_err(|error| Failed::Other(error.to_string()).words())?;
    done(contacts_edit::set_photo(contact_id, path, &plan))
}

/// Longest side of the picture the crop page shows.
pub(crate) const CROP_DISPLAY: u32 = 1440;

/// Decodes the image at `path` (PNG, JPEG or WebP; the EXIF turn applied) for
/// the crop page, downscaled so its longest side is at most [`CROP_DISPLAY`].
/// Blocks: run it on the background executor.
pub(crate) fn load_crop_image(path: &str) -> Option<image::RgbaImage> {
    use image::ImageDecoder;
    let reader = image::ImageReader::open(path)
        .ok()?
        .with_guessed_format()
        .ok()?;
    let mut decoder = reader.into_decoder().ok()?;
    let orientation = decoder.orientation().ok();
    let mut picture = image::DynamicImage::from_decoder(decoder)
        .map_err(|error| log::debug!("crop image {path}: {error}"))
        .ok()?;
    if let Some(orientation) = orientation {
        picture.apply_orientation(orientation);
    }
    if picture.width().max(picture.height()) > CROP_DISPLAY {
        picture = picture.resize(
            CROP_DISPLAY,
            CROP_DISPLAY,
            image::imageops::FilterType::Triangle,
        );
    }
    Some(picture.into_rgba8())
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

    /// The contact photo as a card photo: JPEG, long side at most 512 px, under
    /// the vCard cap (retried smaller when over). Blocks.
    pub(crate) fn card_photo(uri: &str) -> Option<CardPhoto> {
        let (image, _) = full_photo(uri)?;
        [(512, 85), (384, 70)]
            .into_iter()
            .find_map(|(side, quality)| {
                let bytes = encode_jpeg(&image, side, quality)?;
                (bytes.len() <= vcard::MAX_PHOTO_BYTES).then_some(CardPhoto {
                    format: PhotoFormat::Jpeg,
                    bytes,
                })
            })
    }

    /// `image` with its long side at most `side`, as JPEG.
    pub(super) fn encode_jpeg(image: &image::RgbaImage, side: u32, quality: u8) -> Option<Vec<u8>> {
        let mut dynamic = image::DynamicImage::ImageRgb8(
            image::DynamicImage::ImageRgba8(image.clone()).to_rgb8(),
        );
        if dynamic.width().max(dynamic.height()) > side {
            dynamic = dynamic.resize(side, side, image::imageops::FilterType::Triangle);
        }
        let rgb = dynamic.to_rgb8();
        let mut out = Vec::new();
        image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, quality)
            .encode_image(&rgb)
            .ok()?;
        Some(out)
    }

    /// Writes the card photo to a temp file; its path.
    pub(crate) fn photo_file(photo: &CardPhoto) -> Result<PathBuf, String> {
        static NEXT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
        let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let ext = match photo.format {
            PhotoFormat::Jpeg => "jpg",
            PhotoFormat::Png => "png",
        };
        write_temp(&format!("import-photo-{n}.{ext}"), &photo.bytes)
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

/// The contact's own ringtone and voicemail setting.
pub(crate) fn options_of(contact_id: i64) -> Result<ContactOptions, String> {
    let json = contacts_edit::contact_options(contact_id).map_err(|e| android_words(&e))?;
    contact_options::parse(&json).map_err(|e| Failed::Other(e.to_string()).words())
}

/// Sets the contact's ringtone: `None` default, `Some("")` silent.
pub(crate) fn set_ringtone(contact_id: i64, uri: Option<&str>) -> Result<(), String> {
    done(contacts_edit::set_ringtone(contact_id, uri))
}

pub(crate) fn set_voicemail(contact_id: i64, on: bool) -> Result<(), String> {
    done(contacts_edit::set_send_to_voicemail(contact_id, on))
}

/// Opens the system ringtone picker; the answer is the event `ringtone_picked`.
pub(crate) fn pick_ringtone(current: Option<&str>) -> Result<(), String> {
    done(contacts_edit::pick_ringtone(current))
}

/// Asks the launcher to pin a contact shortcut; `false` when it declined.
pub(crate) fn pin_shortcut(spec: &PinShortcut) -> Result<bool, String> {
    let json =
        serde_json::to_string(spec).map_err(|error| Failed::Other(error.to_string()).words())?;
    shortcuts::pin_contact(&json).map_err(|e| android_words(&e))
}

/// Whether the launcher can pin shortcuts.
pub(crate) fn can_pin() -> bool {
    shortcuts::can_pin().unwrap_or(false)
}

/// What a shared or opened file holds. Android copies the `content://` uri into
/// the cache first; desktop reads a plain path.
pub(crate) fn shared_file(uri: &str) -> Result<Intake, String> {
    let max = contact_intake::MAX_SHARED_BYTES;
    let (path, cached) = match documents::copy_shared(uri, max) {
        Ok(path) if path == "too-big" => return Ok(Intake::TooBig),
        Ok(path) if path.is_empty() => return Ok(Intake::Unreadable),
        Ok(path) => (PathBuf::from(path), true),
        Err(Error::Unsupported) => (
            PathBuf::from(uri.strip_prefix("file://").unwrap_or(uri)),
            false,
        ),
        Err(error) => return Err(android_words(&error)),
    };
    let read = std::fs::read(&path);
    if cached {
        let _ = std::fs::remove_file(&path);
    }
    let Ok(bytes) = read else {
        return Ok(Intake::Unreadable);
    };
    let name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or_default();
    Ok(contact_intake::from_file(None, name, &bytes))
}

/// Counts over the whole address book.
pub(crate) fn summary() -> Result<Summary, String> {
    pull_full().map(|contacts| contact_stats::summarize(&contacts))
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
    fn option_wrappers_say_they_need_a_phone() {
        let words = "this needs an Android phone";
        assert_eq!(options_of(1).expect_err("desktop"), words);
        assert_eq!(set_ringtone(1, None).expect_err("desktop"), words);
        assert_eq!(set_voicemail(1, true).expect_err("desktop"), words);
        assert_eq!(pick_ringtone(Some("")).expect_err("desktop"), words);
        let spec = contact_options::shortcut(1, None, "Ayse Yilmaz", None);
        assert_eq!(pin_shortcut(&spec).expect_err("desktop"), words);
        assert!(!can_pin());
    }

    #[test]
    fn shared_file_reads_a_plain_vcf_path() {
        let dir = std::env::temp_dir().join(format!("rovsti-shared-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("dir");
        let path = dir.join("one.vcf");
        std::fs::write(
            &path,
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ayse Yilmaz\r\nN:Yilmaz;Ayse;;;\r\nTEL:+905321112233\r\nEND:VCARD\r\n",
        )
        .expect("writes");
        let intake = shared_file(path.to_str().expect("utf8")).expect("reads");
        let Intake::Cards(cards) = intake else {
            panic!("expected cards, got {intake:?}");
        };
        assert_eq!(cards.len(), 1);
        let missing = dir.join("none.vcf");
        assert_eq!(
            shared_file(missing.to_str().expect("utf8")).expect("ok"),
            Intake::Unreadable
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn card_photo_on_the_sample_is_a_small_jpeg() {
        let photo = card_photo("content://x/1").expect("sample photo");
        assert_eq!(photo.format, PhotoFormat::Jpeg);
        assert!(photo.bytes.len() <= vcard::MAX_PHOTO_BYTES);
        let decoded = image::load_from_memory(&photo.bytes).expect("ok");
        assert!(decoded.width().max(decoded.height()) <= 512);
    }

    #[test]
    fn photo_file_writes_the_bytes() {
        let photo = card_photo("content://x/2").expect("ok");
        let path = photo_file(&photo).expect("ok");
        assert_eq!(path.extension().and_then(|e| e.to_str()), Some("jpg"));
        assert_eq!(std::fs::read(&path).expect("ok"), photo.bytes);
        let other = photo_file(&photo).expect("ok");
        assert_ne!(path, other);
    }

    #[test]
    fn csv_text_has_the_header_and_every_sample_row() {
        let (csv, count) = csv_text(&[]).expect("ok");
        assert!(csv.to_lowercase().starts_with("first name") || csv.contains("First Name"));
        assert_eq!(count, pull_full().expect("ok").len());
        let (_, fewer) = csv_text(&[1]).expect("ok");
        assert_eq!(fewer, count - 1);
        assert_eq!(cards_of(&csv).0.len(), count);
    }

    #[test]
    fn cards_of_reads_csv_and_vcf() {
        let (vcf, n) = export_text(&[]).expect("ok");
        assert_eq!(cards_of(&vcf).0.len(), n);
        assert!(cards_of("nothing here").0.is_empty());
    }

    #[test]
    fn deep_index_finds_a_sample_company() {
        let index = deep_index().expect("ok");
        let hits = index.search("acme", &[], 5);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].id, 1);
    }

    #[test]
    fn frequent_on_desktop_ranks_unstarred_sample_callers() {
        let now = 1_770_000_000_000;
        let list = frequent(&sample::contacts(), now).expect("ok");
        assert!(!list.is_empty() && list.len() <= contact_frequent::MAX_SHOWN);
        assert!(list.iter().all(|f| f.calls >= 2 && f.last_ms <= now));
        let starred: Vec<i64> = sample::contacts()
            .iter()
            .filter(|c| c.starred)
            .map(|c| c.id)
            .collect();
        assert!(list.iter().all(|f| !starred.contains(&f.contact_id)));
    }

    #[test]
    fn single_photo_needs_exactly_one_card_with_a_photo() {
        let photo = card_photo("content://x/3").expect("ok");
        let card = |n: &str| {
            let draft = ContactDraft {
                given: n.to_owned(),
                ..sample::draft(2).expect("ok")
            };
            vcard::write_one(&draft, Some(&photo))
        };
        assert_eq!(single_photo(&card("Ada")).as_ref(), Some(&photo));
        assert_eq!(
            single_photo(&format!("{}{}", card("Ada"), card("Bob"))),
            None
        );
        assert_eq!(single_photo("BEGIN:VCARD\r\nFN:X\r\nEND:VCARD\r\n"), None);
    }

    #[test]
    fn summary_counts_the_sample_book_on_desktop() {
        let s = summary().expect("sample");
        assert!(s.total > 0);
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
        assert_eq!(
            change_photo_with(1, "/c/p", CropView::default()).expect_err("no provider"),
            words
        );
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
        let (text, count) = export_text(&[]).expect("sample exports");
        assert_eq!(count, sample::contacts().len());
        assert!(text.contains("BEGIN:VCARD"));
        let first = sample::contacts()[0].id;
        let (_, fewer) = export_text(&[first]).expect("sample exports");
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
    fn the_crop_image_is_downscaled_and_missing_files_are_none() {
        let dir = std::env::temp_dir().join("rovsti-contacts-crop-test");
        std::fs::create_dir_all(&dir).expect("dir");
        let path = dir.join("wide.png");
        image::RgbaImage::new(2880, 1440)
            .save_with_format(&path, image::ImageFormat::Png)
            .expect("png");
        let loaded = load_crop_image(&path.to_string_lossy()).expect("decodes");
        assert_eq!((loaded.width(), loaded.height()), (1440, 720));
        assert!(load_crop_image("/definitely/not/here.png").is_none());
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
        let (_, count) = export_text(&[]).expect("export");
        assert_eq!(count, all.len());
    }

    #[test]
    fn export_selection_writes_only_the_chosen_cards() {
        let (text, count) = export_selection(&[1, 3], &[]).expect("sample");
        assert_eq!(count, 2);
        assert_eq!(text.matches("BEGIN:VCARD").count(), 2);
        let (_, none) = export_selection(&[], &[]).expect("sample");
        assert_eq!(none, 0);
        let (_, left) = export_selection(&[1, 3], &[1]).expect("sample");
        assert_eq!(left, 1);
    }

    #[test]
    fn desktop_android_wrappers_are_unsupported() {
        let list = vec!["+90 555 111 22 33".to_owned()];
        assert_eq!(call_history(&list).expect_err("no log"), NEEDS_PHONE);
        assert_eq!(group_sms(&list, "hi").expect_err("no sms"), NEEDS_PHONE);
        assert_eq!(group_email(&list).expect_err("no mail"), NEEDS_PHONE);
        let draft = pull_detail(4).expect("sample");
        assert_eq!(
            set_default(&draft, false, Some(0)).expect_err("no provider"),
            NEEDS_PHONE
        );
        assert_eq!(
            sync_birthdays(&ContactsPrefs::default(), &[], 0, 0).expect_err("no alarms"),
            NEEDS_PHONE
        );
    }

    #[test]
    fn drafts_of_picks_the_chosen_sample_contacts() {
        let got = drafts_of(&[3, 1, 999]).expect("sample");
        let ids: Vec<_> = got.iter().filter_map(|d| d.id).collect();
        assert_eq!(ids.len(), 2);
        assert!(ids.contains(&1) && ids.contains(&3));
        assert!(drafts_of(&[]).expect("sample").is_empty());
    }

    /// Writes `text` as a QR code PNG (a quiet zone and a scale the decoder likes).
    fn qr_png(name: &str, text: &str) -> PathBuf {
        let code = qrcode::QrCode::new(text.as_bytes()).expect("encodes");
        let img = code
            .render::<image::Luma<u8>>()
            .quiet_zone(true)
            .module_dimensions(6, 6)
            .build();
        let dir = std::env::temp_dir().join("rovsti-contacts-scan-test");
        std::fs::create_dir_all(&dir).expect("dir");
        let path = dir.join(name);
        img.save_with_format(&path, image::ImageFormat::Png)
            .expect("png");
        path
    }

    #[test]
    fn scan_image_reads_a_generated_qr_png() {
        let card = "BEGIN:VCARD\nVERSION:3.0\nFN:Ayşe Yılmaz\nN:Yılmaz;Ayşe;;;\nTEL:+905551112233\nEND:VCARD\n";
        match scan_image(&qr_png("card.png", card).to_string_lossy()).expect("scans") {
            Scanned::Cards(cards) => {
                assert_eq!(cards.len(), 1);
                assert_eq!(cards[0].numbers[0].value, "+905551112233");
            }
            other => panic!("expected a card, got {other:?}"),
        }
        let number = qr_png("number.png", "tel:+905551112233");
        assert_eq!(
            scan_image(&number.to_string_lossy()).expect("scans"),
            Scanned::Number("+905551112233".to_owned())
        );
    }

    #[test]
    fn scan_image_with_no_code_or_a_bad_file_says_so() {
        let dir = std::env::temp_dir().join("rovsti-contacts-scan-test");
        std::fs::create_dir_all(&dir).expect("dir");
        let blank = dir.join("blank.png");
        image::GrayImage::from_pixel(64, 64, image::Luma([255]))
            .save_with_format(&blank, image::ImageFormat::Png)
            .expect("png");
        assert_eq!(
            scan_image(&blank.to_string_lossy()).expect("scans"),
            Scanned::Nothing
        );
        let junk = dir.join("junk.png");
        std::fs::write(&junk, b"not an image").expect("writes");
        assert_eq!(
            scan_image(&junk.to_string_lossy()).expect_err("unreadable"),
            "that image can't be read"
        );
        assert!(scan_image("/definitely/not/here.png").is_err());
    }
}
