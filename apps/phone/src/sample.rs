//! Sample data for desktop runs (FR-38), where there is no phone to ask.
//! Nothing here touches Android; `backend.rs` decides when to serve it.

use rovsti_core::wire::{CallLogEntry, CallLogKind, WireContact, WireNumber};
#[cfg(not(target_os = "android"))]
use rovsti_core::{
    call_log::{DateOrder, TimeFormat},
    recents::LocaleTime,
};

const MINUTE: i64 = 60_000;
const HOUR: i64 = 60 * MINUTE;

fn number(raw: &str, label: Option<&str>) -> WireNumber {
    WireNumber {
        raw: raw.to_owned(),
        label: label.map(str::to_owned),
        default: false,
    }
}

/// "Ayşe Yılmaz" to "Yılmaz, Ayşe": the family-name-first form the provider keeps.
fn alternative(name: &str) -> Option<String> {
    let (given, family) = name.rsplit_once(' ')?;
    Some(format!("{family}, {given}"))
}

fn contact(id: i64, name: &str, numbers: Vec<WireNumber>, starred: bool) -> WireContact {
    let alt = alternative(name);
    WireContact {
        id,
        display_name: name.to_owned(),
        sort_key: Some(name.to_lowercase()),
        sort_key_alt: alt.as_ref().map(|a| a.to_lowercase()),
        display_name_alt: alt,
        numbers,
        starred,
        photo_uri: cfg!(not(target_os = "android"))
            .then(|| format!("sample:{id}"))
            .filter(|_| SAMPLE_PHOTOS.contains(&id)),
        ..Default::default()
    }
}

/// Contacts that carry a generated portrait in the desktop preview.
const SAMPLE_PHOTOS: [i64; 3] = [1, 3, 8];

/// Contacts starred or unstarred during this desktop run.
static STARRED: std::sync::Mutex<Vec<(i64, bool)>> = std::sync::Mutex::new(Vec::new());

/// Desktop stand-in for the `STARRED` write.
#[cfg(not(target_os = "android"))]
pub fn set_starred(id: i64, starred: bool) {
    if let Ok(mut changes) = STARRED.lock() {
        changes.retain(|(known, _)| *known != id);
        changes.push((id, starred));
    }
}

pub fn contacts() -> Vec<WireContact> {
    let mut all = base_contacts();
    // The provider sorts by name; so does the sample.
    all.sort_by_cached_key(|c| (rovsti_core::search::fold(&c.display_name), c.id));
    if let Ok(changes) = STARRED.lock() {
        for (id, starred) in changes.iter() {
            if let Some(c) = all.iter_mut().find(|c| c.id == *id) {
                c.starred = *starred;
            }
        }
    }
    all
}

fn base_contacts() -> Vec<WireContact> {
    if std::env::var_os("ROVSTI_PREVIEW_BIGCONTACTS").is_some() {
        return long_contacts(600);
    }
    vec![
        contact(
            1,
            "Ayşe Yılmaz",
            vec![number("+90 555 111 22 33", Some("Mobile"))],
            true,
        ),
        contact(
            2,
            "Burak Demir",
            vec![number("0532 123 45 67", Some("Mobile"))],
            false,
        ),
        contact(
            3,
            "Elif Kaya",
            vec![
                number("+90 212 555 00 11", Some("Work")),
                number("0533 765 43 21", None),
            ],
            true,
        ),
        contact(
            4,
            "Mehmet Öz",
            vec![
                number("+49 151 234 567 89", Some("Mobile")),
                WireNumber {
                    default: true,
                    ..number("0216 555 01 02", Some("Home"))
                },
            ],
            false,
        ),
        contact(
            7,
            "Ahmet Çelik",
            vec![number("0505 222 33 44", Some("Mobile"))],
            false,
        ),
        contact(
            8,
            "Can Aydın",
            vec![number("0506 333 44 55", Some("Mobile"))],
            true,
        ),
        contact(
            9,
            "Deniz Korkmaz",
            vec![number("0507 444 55 66", Some("Work"))],
            true,
        ),
        contact(
            10,
            "+90 850 123 45 67 (Bank)",
            vec![number("0850 123 45 67", None)],
            false,
        ),
        contact(
            5,
            "Özgür Şahin",
            vec![number("0544 987 65 43", Some("Mobile"))],
            true,
        ),
        contact(
            6,
            "Zeynep Arslan",
            vec![number("+1 415 555 0132", Some("Home"))],
            false,
        ),
    ]
}

/// `count` generated contacts in name order (scroll and search checks).
fn long_contacts(count: usize) -> Vec<WireContact> {
    const FIRST: [&str; 12] = [
        "Ada", "Burak", "Cem", "Deniz", "Ece", "Fatma", "Gül", "Hasan", "Işık", "Jale", "Kerem",
        "Leyla",
    ];
    const LAST: [&str; 8] = [
        "Yılmaz", "Demir", "Kaya", "Şahin", "Çelik", "Öz", "Arslan", "Aydın",
    ];
    let mut out: Vec<WireContact> = (0..count)
        .map(|i| {
            let id = i64::try_from(i + 1).unwrap_or(1);
            let mut numbers = vec![number(
                &format!("0532 {:03} {:02} {:02}", i % 1000, i % 97, i % 89),
                Some("Mobile"),
            )];
            if i % 7 == 0 {
                numbers.push(number(
                    &format!("+90 212 {:03} {:02} {:02}", i % 1000, i % 89, i % 97),
                    Some("Work"),
                ));
            }
            contact(
                id,
                &format!("{} {}", FIRST[i % FIRST.len()], LAST[(i / 3) % LAST.len()]),
                numbers,
                i % 9 == 0,
            )
        })
        .collect();
    out.sort_by(|a, b| a.display_name.cmp(&b.display_name).then(a.id.cmp(&b.id)));
    out
}

pub fn favorites() -> Vec<WireContact> {
    contacts().into_iter().filter(|c| c.starred).collect()
}

/// Newest first, like the provider returns it.
pub fn call_log(now_ms: i64) -> Vec<CallLogEntry> {
    let entry =
        |id: i64, raw: &str, kind: CallLogKind, ago: i64, duration_s: i64, name: Option<&str>| {
            CallLogEntry {
                id,
                number_raw: raw.to_owned(),
                normalized: raw
                    .chars()
                    .enumerate()
                    .filter(|(i, c)| c.is_ascii_digit() || (*i == 0 && *c == '+'))
                    .map(|(_, c)| c)
                    .collect(),
                kind,
                date_ms: now_ms - ago,
                duration_s,
                cached_name: name.map(str::to_owned),
                number_label: None,
                contact_id: None,
                // Alternating accounts: only drawn by the dual-SIM preview.
                account_key: Some(format!("tel/pstn#{}", 1 + id % 2)),
                ..Default::default()
            }
        };
    vec![
        entry(
            8,
            "+90 555 111 22 33",
            CallLogKind::Missed,
            12 * MINUTE,
            0,
            Some("Ayşe Yılmaz"),
        ),
        entry(
            7,
            "+90 555 111 22 33",
            CallLogKind::Missed,
            20 * MINUTE,
            0,
            Some("Ayşe Yılmaz"),
        ),
        entry(
            6,
            "0532 123 45 67",
            CallLogKind::Incoming,
            3 * HOUR,
            184,
            Some("Burak Demir"),
        ),
        entry(
            5,
            "0544 987 65 43",
            CallLogKind::Outgoing,
            5 * HOUR,
            42,
            Some("Özgür Şahin"),
        ),
        entry(
            4,
            "+49 151 234 567 89",
            CallLogKind::Rejected,
            26 * HOUR,
            0,
            Some("Mehmet Öz"),
        ),
        entry(
            3,
            "0850 123 45 67",
            CallLogKind::Incoming,
            50 * HOUR,
            9,
            None,
        ),
        entry(2, "", CallLogKind::Missed, 74 * HOUR, 0, None),
        entry(
            1,
            "+1 415 555 0132",
            CallLogKind::Outgoing,
            120 * HOUR,
            611,
            Some("Zeynep Arslan"),
        ),
    ]
}

/// The log the desktop preview shows: the short hand-made one, or 500 entries
/// with `ROVSTI_PREVIEW_BIGLOG=1` (scroll and frame-time checks).
pub fn preview_call_log(now_ms: i64) -> Vec<CallLogEntry> {
    let mut log = if std::env::var_os("ROVSTI_PREVIEW_BIGLOG").is_some() {
        long_call_log(now_ms, 500)
    } else {
        call_log(now_ms)
    };
    // Calls added with the debug switcher come first (newest).
    if let Ok(extra) = EXTRA_CALLS.lock() {
        let mut all: Vec<CallLogEntry> = extra.iter().rev().cloned().collect();
        all.append(&mut log);
        return all;
    }
    log
}

/// Calls added during this desktop run (debug switcher), oldest first.
static EXTRA_CALLS: std::sync::Mutex<Vec<CallLogEntry>> = std::sync::Mutex::new(Vec::new());

/// Desktop stand-in for a call finishing: one more log entry for Özgür Şahin.
#[cfg(not(target_os = "android"))]
pub fn add_call(kind: CallLogKind, now_ms: i64) {
    if let Ok(mut extra) = EXTRA_CALLS.lock() {
        let id = 1000 + i64::try_from(extra.len()).unwrap_or(0);
        extra.push(CallLogEntry {
            id,
            number_raw: "0544 987 65 43".to_owned(),
            normalized: "05449876543".to_owned(),
            kind,
            date_ms: now_ms,
            duration_s: 0,
            cached_name: Some("Özgür Şahin".to_owned()),
            number_label: None,
            contact_id: None,
            ..Default::default()
        });
    }
}

/// `count` entries spread over a few months, newest first; some repeat so
/// they group, some are private.
pub fn long_call_log(now_ms: i64, count: usize) -> Vec<CallLogEntry> {
    const KINDS: [CallLogKind; 5] = [
        CallLogKind::Incoming,
        CallLogKind::Outgoing,
        CallLogKind::Missed,
        CallLogKind::Incoming,
        CallLogKind::Rejected,
    ];
    let people = contacts();
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let id = i64::try_from(count - i).unwrap_or(0);
        let kind = KINDS[(i * 7 + i / 3) % KINDS.len()];
        // Every 11th entry is a private number, every 5th a stranger.
        let (raw, name) = if i % 11 == 10 {
            (String::new(), None)
        } else if i % 5 == 4 {
            (
                format!("0850 {:03} {:02} {:02}", i % 1000, i % 97, i % 89),
                None,
            )
        } else {
            let person = &people[(i / 2) % people.len()];
            (
                person.numbers[0].raw.clone(),
                Some(person.display_name.clone()),
            )
        };
        let digits: String = raw.chars().filter(char::is_ascii_digit).collect();
        out.push(CallLogEntry {
            id,
            normalized: if raw.starts_with('+') {
                format!("+{digits}")
            } else {
                digits
            },
            number_raw: raw,
            kind,
            date_ms: now_ms - 5 * MINUTE - i64::try_from(i).unwrap_or(0) * 4 * HOUR,
            duration_s: 30,
            cached_name: name,
            number_label: None,
            contact_id: (i % 5 != 4 && i % 11 != 10).then_some(1 + ((i / 2) % people.len()) as i64),
            account_key: Some(format!("tel/pstn#{}", 1 + i % 2)),
            ..Default::default()
        });
    }
    out
}

/// Desktop stand-in for the phone's clock settings: the host's UTC offset
/// (from `date`), 24-hour, day first.
#[cfg(not(target_os = "android"))]
pub fn desktop_locale() -> LocaleTime {
    let tz_offset_s = std::process::Command::new("date")
        .arg("+%z")
        .output()
        .ok()
        .and_then(|out| String::from_utf8(out.stdout).ok())
        .and_then(|text| {
            let text = text.trim();
            let sign = if text.starts_with('-') { -1 } else { 1 };
            let hours = text.get(1..3)?.parse::<i32>().ok()?;
            let minutes = text.get(3..5)?.parse::<i32>().ok()?;
            Some(sign * (hours * 3600 + minutes * 60))
        })
        .unwrap_or(0);
    LocaleTime {
        tz_offset_s,
        format: TimeFormat {
            hour12: false,
            date_order: DateOrder::DayMonthYear,
        },
    }
}

/// A generated portrait for a `sample:<id>` URI (desktop preview): a vertical
/// gradient in the contact's hue with a head and shoulders silhouette.
#[cfg(not(target_os = "android"))]
pub fn photo(uri: &str, size: u32) -> Option<image::RgbaImage> {
    let id: i64 = uri.strip_prefix("sample:")?.parse().ok()?;
    let hue = (id.rem_euclid(12) as f32) / 12.0;
    let tint = |t: f32, light: f32| -> [u8; 4] {
        // A cheap hue wheel: three phase-shifted cosines.
        let channel = |shift: f32| {
            let wave = 0.5 + 0.5 * ((hue + shift + t * 0.08) * std::f32::consts::TAU).cos();
            (255.0 * (light * 0.55 + wave * 0.45 * light)).clamp(0.0, 255.0) as u8
        };
        [channel(0.0), channel(-1.0 / 3.0), channel(1.0 / 3.0), 255]
    };
    let side = size as f32;
    Some(image::RgbaImage::from_fn(size, size, |x, y| {
        let (fx, fy) = (x as f32 / side, y as f32 / side);
        let head = ((fx - 0.5) / 0.19).powi(2) + ((fy - 0.36) / 0.21).powi(2) <= 1.0;
        let body = ((fx - 0.5) / 0.38).powi(2) + ((fy - 1.02) / 0.44).powi(2) <= 1.0;
        image::Rgba(if head || body {
            tint(1.0, 0.45)
        } else {
            tint(fy, 1.0)
        })
    }))
}
