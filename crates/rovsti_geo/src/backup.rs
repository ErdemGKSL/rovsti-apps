//! One-file backup of everything Rovsti Stops keeps on the phone: the saved stops, the
//! trip history and the settings.
//!
//! The WhatsApp connect key is not part of [`StopsPrefs`] and is never written to a
//! backup, so a backup file is safe to store or share.

use serde::{Deserialize, Serialize};

use crate::history::{History, MAX_RECORDS};
use crate::prefs::StopsPrefs;
use crate::saved_stops::{FORMAT_VERSION, MAX_STOPS, SavedStops};

/// The `app` field every Stops backup carries.
pub const BACKUP_APP: &str = "rovsti-stops";
/// The newest backup layout this build reads and writes.
pub const BACKUP_VERSION: u32 = 1;

/// Everything a backup holds.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Backup {
    pub app: String,
    pub version: u32,
    pub created_ms: i64,
    pub saved: SavedStops,
    pub history: History,
    pub prefs: StopsPrefs,
}

impl Backup {
    pub fn new(saved: SavedStops, history: History, prefs: StopsPrefs, now_ms: i64) -> Self {
        Self {
            app: BACKUP_APP.to_owned(),
            version: BACKUP_VERSION,
            created_ms: now_ms,
            saved,
            history,
            prefs,
        }
    }
}

/// Why a backup file was refused.
#[derive(thiserror::Error, Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackupError {
    #[error("not a backup")]
    NotJson,
    #[error("another app")]
    NotStops,
    #[error("newer version")]
    Newer,
    #[error("damaged saved stops")]
    BadStops,
}

impl BackupError {
    /// Text for the pill.
    pub fn message(self) -> &'static str {
        match self {
            Self::NotJson => "That file is not a backup",
            Self::NotStops => "That backup is from another app",
            Self::Newer => "That backup is from a newer version",
            Self::BadStops => "The saved stops in that backup are damaged",
        }
    }
}

/// The backup as pretty JSON.
pub fn to_json(backup: &Backup) -> String {
    // Plain data with string keys cannot fail to serialize.
    serde_json::to_string_pretty(backup).unwrap_or_default()
}

/// Reads and cleans a backup: stops with an invalid gate are dropped, the lists are
/// capped and `next_id` is raised above every id in use.
pub fn parse(text: &str) -> Result<Backup, BackupError> {
    let mut backup: Backup = serde_json::from_str(text).map_err(|_| BackupError::NotJson)?;
    if backup.app != BACKUP_APP {
        return Err(BackupError::NotStops);
    }
    if backup.version > BACKUP_VERSION {
        return Err(BackupError::Newer);
    }
    if backup.saved.version != FORMAT_VERSION {
        return Err(BackupError::BadStops);
    }
    backup.saved.stops.retain(|s| s.diameter().is_ok());
    backup.saved.stops.truncate(MAX_STOPS);
    backup.history.records.truncate(MAX_RECORDS);
    let highest = backup.saved.stops.iter().map(|s| s.id).max().unwrap_or(0);
    backup.saved.next_id = backup.saved.next_id.max(highest.saturating_add(1));
    Ok(backup)
}

/// One line for the confirmation sheet, e.g. "12 stops, 48 trips and settings from
/// 5 Nov 2026".
pub fn summary(backup: &Backup, tz_offset_s: i32) -> String {
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    fn count(n: usize, one: &str, many: &str) -> String {
        format!("{n} {}", if n == 1 { one } else { many })
    }
    let (y, m, d) = rovsti_core::contact_dates::local_ymd(backup.created_ms, tz_offset_s);
    format!(
        "{}, {} and settings from {d} {} {y}",
        count(backup.saved.stops.len(), "stop", "stops"),
        count(backup.history.records.len(), "trip", "trips"),
        MONTHS[usize::from(m.clamp(1, 12) - 1)],
    )
}

/// The suggested file name for a backup made on `today` (e.g. `2026-11-05`).
pub fn export_file_name(today: &str) -> String {
    format!("rovsti-stops-backup-{today}.json")
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::history::{Outcome, TripRecord};
    use crate::{Coord, Endpoint};

    fn ep(lat: f64, lon: f64) -> Endpoint {
        Endpoint {
            label: String::new(),
            coord: Coord::new(lat, lon),
        }
    }

    fn sample() -> Backup {
        let mut saved = SavedStops::default();
        saved
            .add("Pier", ep(41.0, 29.0), ep(41.001, 29.0), 1)
            .unwrap();
        saved
            .add("Quay", ep(41.1, 29.1), ep(41.102, 29.1), 2)
            .unwrap();
        let mut history = History::default();
        history.push(TripRecord {
            id: 1,
            label: "Home".into(),
            ended_ms: 1_000,
            outcome: Outcome::Arrived,
            ..TripRecord::default()
        });
        let prefs = StopsPrefs {
            snooze_min: 10,
            ..StopsPrefs::default()
        };
        // 5 Nov 2026 12:00 UTC.
        Backup::new(saved, history, prefs, 1_793_880_000_000)
    }

    #[test]
    fn round_trip_is_equal() {
        let b = sample();
        assert_eq!(parse(&to_json(&b)).unwrap(), b);
    }

    #[test]
    fn prefs_missing_new_fields_get_defaults() {
        let mut v: serde_json::Value = serde_json::from_str(&to_json(&sample())).unwrap();
        v["prefs"] = serde_json::json!({ "snooze_min": 10 });
        let b = parse(&v.to_string()).unwrap();
        assert_eq!(b.prefs.snooze_min, 10);
        assert_eq!(
            StopsPrefs {
                snooze_min: 10,
                ..StopsPrefs::default()
            },
            b.prefs
        );
    }

    fn with(field: &str, value: serde_json::Value) -> String {
        let mut v: serde_json::Value = serde_json::from_str(&to_json(&sample())).unwrap();
        match field {
            "saved.version" => v["saved"]["version"] = value,
            _ => v[field] = value,
        }
        v.to_string()
    }

    #[test]
    fn refusals() {
        assert_eq!(
            parse(&with("app", "rovsti-phone".into())),
            Err(BackupError::NotStops)
        );
        assert_eq!(parse(&with("version", 2.into())), Err(BackupError::Newer));
        assert_eq!(parse("garbage"), Err(BackupError::NotJson));
        assert_eq!(parse("{}"), Err(BackupError::NotJson));
        assert_eq!(
            parse(&with("saved.version", 1.into())),
            Err(BackupError::BadStops)
        );
        assert!(!BackupError::NotJson.message().is_empty());
    }

    #[test]
    fn invalid_gate_is_dropped_and_next_id_fixed() {
        let mut b = sample();
        b.saved.stops[0].b = b.saved.stops[0].a.clone();
        b.saved.stops[1].id = 40;
        b.saved.next_id = 3;
        let got = parse(&to_json(&b)).unwrap();
        assert_eq!(got.saved.stops.len(), 1);
        assert_eq!(got.saved.next_id, 41);
    }

    #[test]
    fn summary_singular_and_plural() {
        let b = sample();
        assert_eq!(
            summary(&b, 0),
            "2 stops, 1 trip and settings from 5 Nov 2026"
        );
        let mut b = b;
        b.saved.stops.truncate(1);
        b.history.records.push(b.history.records[0].clone());
        assert_eq!(
            summary(&b, 0),
            "1 stop, 2 trips and settings from 5 Nov 2026"
        );
    }

    #[test]
    fn file_name() {
        assert_eq!(
            export_file_name("2026-11-05"),
            "rovsti-stops-backup-2026-11-05.json"
        );
    }
}
