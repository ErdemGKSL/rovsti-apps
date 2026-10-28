//! Preferences v2: typed settings, validation, the v1 upgrade and the backup
//! file (AD-30).
//!
//! This crate does not depend on `serde_json`; the file is read and written in
//! `rovsti_ui::storage`, which also picks the upgrade chain from the file's
//! `schema_version`. Everything here is pure: defaults, clamping, the
//! `PrefsV1 -> PrefsV2` conversion, the haptics filter and the import preview.
//!
//! Every type is `#[serde(default)]` and ignores unknown fields, so a file
//! written by a newer build still loads, and a field added later never breaks
//! an older file.

use crate::wire::{HapticsLevel, NameFormat, SettingsSync};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

/// The schema this build writes.
pub const SCHEMA_VERSION: u32 = 2;

/// Most quick replies the owner can keep.
pub const MAX_REPLIES: usize = 6;
/// Longest quick reply, in characters.
pub const MAX_REPLY_CHARS: usize = 100;
/// Speed-dial digits that can hold a number (1 is voicemail).
pub const SPEED_DIAL_DIGITS: std::ops::RangeInclusive<u8> = 2..=9;
/// Longest name or lookup key kept in a speed-dial entry.
const MAX_FIELD_CHARS: usize = 200;
/// A backup bigger than this is "not a backup".
pub const MAX_BACKUP_BYTES: usize = 256 * 1024;
/// More favourites than this is not a plausible order.
const MAX_FAVORITES: usize = 10_000;

/// The three replies a fresh install offers.
pub const DEFAULT_REPLIES: [&str; 3] = [
    "Can't talk now. I'll call you back.",
    "I'm in a meeting. Call you later.",
    "Can you text me instead?",
];

fn default_replies() -> Vec<String> {
    DEFAULT_REPLIES.iter().map(|s| (*s).to_owned()).collect()
}

fn schema() -> u32 {
    SCHEMA_VERSION
}

/// Which contact sort key orders the lists.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SortBy {
    /// `SORT_KEY_ALTERNATIVE`: family name first.
    LastName,
    /// `SORT_KEY_PRIMARY`: given name first.
    #[default]
    #[serde(other)]
    FirstName,
}

/// A contact on a speed-dial digit. The number is stored, so a contact edit
/// never retargets the digit (AD-33).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SpeedDialEntry {
    pub lookup_key: String,
    pub number_raw: String,
    pub name: String,
}

/// State the app keeps for itself (written by Rust only).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct UiState {
    /// The tab the owner left the app on; `None` before the first choice.
    pub last_tab: Option<u8>,
    pub missed_seen_ms: i64,
    pub app_version: String,
    /// Setup item key to the app version it was confirmed for.
    pub xiaomi_confirmed: BTreeMap<String, String>,
    /// How often each hint was shown (the three-call gesture hint).
    pub hint_counters: BTreeMap<String, u32>,
}

/// The owner's choices.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub haptics: HapticsLevel,
    pub proximity: bool,
    pub flip_to_silence: bool,
    pub vibrate_connect: bool,
    pub vibrate_end: bool,
    /// `None` follows the system dial-pad tone setting.
    pub keypad_tones: Option<bool>,
    pub sort_by: SortBy,
    pub name_format: NameFormat,
    pub show_frequent: bool,
}

impl Default for Settings {
    /// The v0.1 behaviour.
    fn default() -> Self {
        Self {
            haptics: HapticsLevel::Normal,
            proximity: true,
            flip_to_silence: false,
            vibrate_connect: false,
            vibrate_end: false,
            keypad_tones: None,
            sort_by: SortBy::FirstName,
            name_format: NameFormat::FirstLast,
            show_frequent: true,
        }
    }
}

/// `prefs.json` as v0.2 writes it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct PrefsV2 {
    #[serde(default = "schema")]
    pub schema_version: u32,
    pub ui: UiState,
    pub settings: Settings,
    #[serde(default = "default_replies")]
    pub quick_replies: Vec<String>,
    pub speed_dial: BTreeMap<u8, SpeedDialEntry>,
    /// Lookup keys, in the order the owner arranged them.
    pub favorites_order: Vec<String>,
}

impl Default for PrefsV2 {
    fn default() -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            ui: UiState::default(),
            settings: Settings::default(),
            quick_replies: default_replies(),
            speed_dial: BTreeMap::new(),
            favorites_order: Vec::new(),
        }
    }
}

/// Cuts `s` to at most `max` characters on a character boundary.
fn clip(s: &str, max: usize) -> &str {
    match s.char_indices().nth(max) {
        Some((i, _)) => &s[..i],
        None => s,
    }
}

/// Characters left before the counter shows in the quick replies editor.
pub const REPLY_COUNTER_AT: usize = 80;

/// Moves the reply at `from` to position `to` (both clamped into the list).
/// Used by the drag handle and by Move up / Move down.
pub fn move_reply<T>(list: &mut Vec<T>, from: usize, to: usize) {
    if list.is_empty() {
        return;
    }
    let last = list.len() - 1;
    let (from, to) = (from.min(last), to.min(last));
    if from != to {
        let item = list.remove(from);
        list.insert(to, item);
    }
}

/// "Add reply" is allowed below [`MAX_REPLIES`].
pub const fn can_add_reply(count: usize) -> bool {
    count < MAX_REPLIES
}

/// Trimmed, non-empty, at most [`MAX_REPLY_CHARS`]; at most [`MAX_REPLIES`].
fn clamp_replies(replies: &mut Vec<String>) {
    replies.retain_mut(|r| {
        let trimmed = clip(r.trim(), MAX_REPLY_CHARS);
        *r = trimmed.trim_end().to_owned();
        !r.is_empty()
    });
    replies.truncate(MAX_REPLIES);
}

/// Only digits 2..=9 with a number; text fields bounded.
fn clamp_speed_dial(map: &mut BTreeMap<u8, SpeedDialEntry>) {
    map.retain(|digit, entry| {
        entry.number_raw = clip(entry.number_raw.trim(), MAX_FIELD_CHARS).to_owned();
        entry.lookup_key = clip(&entry.lookup_key, MAX_FIELD_CHARS).to_owned();
        entry.name = clip(entry.name.trim(), MAX_FIELD_CHARS).to_owned();
        SPEED_DIAL_DIGITS.contains(digit) && !entry.number_raw.is_empty()
    });
}

/// First occurrence wins; empty keys are dropped.
fn dedup_order(order: &mut Vec<String>) {
    let mut seen = BTreeSet::new();
    order.retain(|k| !k.is_empty() && seen.insert(k.clone()));
    order.truncate(MAX_FAVORITES);
}

impl PrefsV2 {
    /// Clamps every field into its bounds; never panics. Called on load and
    /// after every change, so invalid input cannot persist.
    pub fn validate(&mut self) {
        self.schema_version = SCHEMA_VERSION;
        clamp_replies(&mut self.quick_replies);
        clamp_speed_dial(&mut self.speed_dial);
        dedup_order(&mut self.favorites_order);
    }

    /// What Kotlin keeps in its settings mirror (`syncSettings`).
    pub fn sync(&self) -> SettingsSync {
        let s = &self.settings;
        SettingsSync {
            proximity: s.proximity,
            flip_to_silence: s.flip_to_silence,
            vibrate_connect: s.vibrate_connect,
            vibrate_end: s.vibrate_end,
            has_replies: !self.quick_replies.is_empty(),
            keypad_tones: s.keypad_tones,
            haptics: s.haptics,
            name_format: s.name_format,
        }
    }

    /// The tab to reopen on: the stored one if it exists among `count` tabs,
    /// else `default` (first launch, or a corrupt value).
    pub fn last_tab_or(&self, count: usize, default: usize) -> usize {
        self.ui
            .last_tab
            .map(usize::from)
            .filter(|tab| *tab < count)
            .unwrap_or(default)
    }
}

/// `prefs.json` as v0.1 wrote it: flat, with no `schema_version`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct PrefsV1 {
    pub last_tab: Option<u8>,
    pub missed_seen_ms: i64,
    pub xiaomi_confirmed: BTreeMap<String, String>,
    pub app_version: String,
}

impl From<PrefsV1> for PrefsV2 {
    fn from(v1: PrefsV1) -> Self {
        Self {
            ui: UiState {
                last_tab: v1.last_tab,
                missed_seen_ms: v1.missed_seen_ms,
                app_version: v1.app_version,
                xiaomi_confirmed: v1.xiaomi_confirmed,
                hint_counters: BTreeMap::new(),
            },
            ..Self::default()
        }
    }
}

// ---- keypad tones ----------------------------------------------------------

/// Whether dial-pad tones play: the owner's choice, else what the system's
/// "dial pad tones" setting says (story 16.8, AD-29).
pub const fn effective_keypad_tones(setting: Option<bool>, system: bool) -> bool {
    match setting {
        Some(on) => on,
        None => system,
    }
}

// ---- haptics -------------------------------------------------------------

/// The kind of haptic a view asks for.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum HapticKind {
    /// Key presses, selection changes, scroll detents.
    Tick,
    /// A button press.
    Press,
    /// An action took effect (call placed, call answered).
    Confirm,
    /// Something needs attention (long-press reason, blocked action).
    Warning,
}

impl HapticKind {
    /// The kinds `Light` still allows.
    pub const fn is_tick_class(self) -> bool {
        matches!(self, Self::Tick)
    }
}

/// Whether `kind` plays at `level`: `Off` plays nothing, `Light` only
/// tick-class kinds, `Normal` everything.
pub const fn haptic_allowed(level: HapticsLevel, kind: HapticKind) -> bool {
    match level {
        HapticsLevel::Off => false,
        HapticsLevel::Light => kind.is_tick_class(),
        HapticsLevel::Normal => true,
    }
}

// ---- backup --------------------------------------------------------------

/// `kind` of a backup file.
pub const BACKUP_KIND: &str = "rovsti-phone-backup";
/// The backup format this build reads and writes.
pub const BACKUP_VERSION: u32 = 1;

/// The exported file: settings, replies, speed dial and favourites order.
/// Never the `ui` state or the Xiaomi confirmations.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct BackupFile {
    pub v: u32,
    pub kind: String,
    pub created_ms: i64,
    pub app_version: String,
    pub settings: Settings,
    pub quick_replies: Vec<String>,
    pub speed_dial: BTreeMap<u8, SpeedDialEntry>,
    pub favorites_order: Vec<String>,
}

impl Default for BackupFile {
    fn default() -> Self {
        Self {
            v: BACKUP_VERSION,
            kind: BACKUP_KIND.to_owned(),
            created_ms: 0,
            app_version: String::new(),
            settings: Settings::default(),
            quick_replies: Vec::new(),
            speed_dial: BTreeMap::new(),
            favorites_order: Vec::new(),
        }
    }
}

impl BackupFile {
    /// Builds the export from the current preferences.
    pub fn from_prefs(prefs: &PrefsV2, created_ms: i64, app_version: &str) -> Self {
        Self {
            v: BACKUP_VERSION,
            kind: BACKUP_KIND.to_owned(),
            created_ms,
            app_version: app_version.to_owned(),
            settings: prefs.settings.clone(),
            quick_replies: prefs.quick_replies.clone(),
            speed_dial: prefs.speed_dial.clone(),
            favorites_order: prefs.favorites_order.clone(),
        }
    }
}

/// Why a file is refused.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum ImportError {
    /// Another kind of file, or too large to be a backup.
    #[error("not a backup")]
    NotABackup,
    /// Written by a newer build.
    #[error("backup is from a newer version")]
    Newer,
}

/// `rovsti-phone-backup-{yyyy-mm-dd}.json` for the UTC day of `ms`.
pub fn backup_file_name(ms: i64) -> String {
    // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm).
    let days = ms.div_euclid(86_400_000) + 719_468;
    let era = days.div_euclid(146_097);
    let doe = days.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!("rovsti-phone-backup-{year:04}-{month:02}-{day:02}.json")
}

/// Whether `len` bytes can be a backup at all (checked before parsing).
pub const fn plausible_backup_size(len: usize) -> bool {
    len > 0 && len <= MAX_BACKUP_BYTES
}

/// A speed-dial contact that appeared on two digits and kept the later one.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MovedDigit {
    pub to: u8,
    pub from: u8,
}

/// What an import would do, for the confirmation sheet. Replace semantics:
/// each section of the file replaces the same section of the current prefs.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ImportPreview {
    /// Settings that differ from the current ones.
    pub settings_changed: usize,
    pub quick_replies: usize,
    pub speed_dial: usize,
    pub favorites: usize,
    /// A contact on two digits keeps the later digit, with a "moved from" note.
    pub moved: Vec<MovedDigit>,
    /// Some value was out of bounds and clamped.
    pub clamped: bool,
    clean: Sections,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Sections {
    settings: Settings,
    quick_replies: Vec<String>,
    speed_dial: BTreeMap<u8, SpeedDialEntry>,
    favorites_order: Vec<String>,
}

fn changed_settings(a: &Settings, b: &Settings) -> usize {
    [
        a.haptics != b.haptics,
        a.proximity != b.proximity,
        a.flip_to_silence != b.flip_to_silence,
        a.vibrate_connect != b.vibrate_connect,
        a.vibrate_end != b.vibrate_end,
        a.keypad_tones != b.keypad_tones,
        a.sort_by != b.sort_by,
        a.name_format != b.name_format,
        a.show_frequent != b.show_frequent,
    ]
    .into_iter()
    .filter(|d| *d)
    .count()
}

impl ImportPreview {
    /// Validates `file` (kind, version, bounds) against the `current` prefs.
    pub fn from(file: &BackupFile, current: &PrefsV2) -> Result<Self, ImportError> {
        if file.kind != BACKUP_KIND || file.v == 0 {
            return Err(ImportError::NotABackup);
        }
        if file.v > BACKUP_VERSION {
            return Err(ImportError::Newer);
        }
        let mut probe = PrefsV2 {
            settings: file.settings.clone(),
            quick_replies: file.quick_replies.clone(),
            speed_dial: file.speed_dial.clone(),
            favorites_order: file.favorites_order.clone(),
            ..PrefsV2::default()
        };
        let before = (
            probe.quick_replies.clone(),
            probe.speed_dial.clone(),
            probe.favorites_order.len(),
        );
        probe.validate();
        let clamped = before.0 != probe.quick_replies
            || before.1 != probe.speed_dial
            || before.2 != probe.favorites_order.len();

        // One contact, one digit: the later digit keeps it.
        let mut moved = Vec::new();
        let mut by_key: BTreeMap<String, u8> = BTreeMap::new();
        let digits: Vec<u8> = probe.speed_dial.keys().copied().collect();
        for digit in digits {
            let key = probe.speed_dial[&digit].lookup_key.clone();
            if key.is_empty() {
                continue;
            }
            if let Some(earlier) = by_key.insert(key, digit) {
                probe.speed_dial.remove(&earlier);
                moved.push(MovedDigit {
                    to: digit,
                    from: earlier,
                });
            }
        }

        Ok(Self {
            settings_changed: changed_settings(&probe.settings, &current.settings),
            quick_replies: probe.quick_replies.len(),
            speed_dial: probe.speed_dial.len(),
            favorites: probe.favorites_order.len(),
            moved,
            clamped,
            clean: Sections {
                settings: probe.settings,
                quick_replies: probe.quick_replies,
                speed_dial: probe.speed_dial,
                favorites_order: probe.favorites_order,
            },
        })
    }

    /// The contact on `digit` after the import (for the "moved" notes).
    pub fn speed_dial_name(&self, digit: u8) -> Option<&str> {
        self.clean.speed_dial.get(&digit).map(|e| e.name.as_str())
    }

    /// Replaces the backed-up sections of `prefs`; `ui` is untouched.
    pub fn apply_to(self, prefs: &mut PrefsV2) {
        prefs.settings = self.clean.settings;
        prefs.quick_replies = self.clean.quick_replies;
        prefs.speed_dial = self.clean.speed_dial;
        prefs.favorites_order = self.clean.favorites_order;
        prefs.validate();
    }
}

#[cfg(test)]
mod name_tests {
    use super::backup_file_name;

    #[test]
    fn the_file_name_carries_the_utc_date() {
        assert_eq!(backup_file_name(0), "rovsti-phone-backup-1970-01-01.json");
        // 2026-10-28T12:00:00Z
        assert_eq!(
            backup_file_name(1_793_188_800_000),
            "rovsti-phone-backup-2026-10-28.json"
        );
        // Leap day.
        assert_eq!(
            backup_file_name(1_709_164_800_000),
            "rovsti-phone-backup-2024-02-29.json"
        );
    }
}

#[cfg(test)]
mod tone_tests {
    use super::*;

    #[test]
    fn the_setting_wins_over_the_system() {
        assert!(effective_keypad_tones(Some(true), false));
        assert!(!effective_keypad_tones(Some(false), true));
    }

    #[test]
    fn no_choice_follows_the_system() {
        assert!(effective_keypad_tones(None, true));
        assert!(!effective_keypad_tones(None, false));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(key: &str, number: &str) -> SpeedDialEntry {
        SpeedDialEntry {
            lookup_key: key.into(),
            number_raw: number.into(),
            name: "Ada".into(),
        }
    }

    // ---- defaults ------------------------------------------------------

    #[test]
    fn defaults_reproduce_v01() {
        let p = PrefsV2::default();
        assert_eq!(p.schema_version, 2);
        assert!(p.settings.proximity && p.settings.show_frequent);
        assert_eq!(p.settings.haptics, HapticsLevel::Normal);
        assert!(
            !p.settings.flip_to_silence && !p.settings.vibrate_connect && !p.settings.vibrate_end
        );
        assert_eq!(p.settings.keypad_tones, None);
        assert_eq!(p.quick_replies, DEFAULT_REPLIES);
        assert!(p.speed_dial.is_empty() && p.favorites_order.is_empty());
        assert_eq!(p.ui, UiState::default());
        assert_eq!(
            p.sync(),
            SettingsSync {
                has_replies: true,
                ..SettingsSync::default()
            }
        );
    }

    #[test]
    fn the_default_replies_are_valid() {
        let mut p = PrefsV2::default();
        let before = p.clone();
        p.validate();
        assert_eq!(p, before);
        for r in DEFAULT_REPLIES {
            assert!(r.chars().count() <= MAX_REPLY_CHARS);
        }
    }

    // ---- validation ----------------------------------------------------

    #[test]
    fn replies_are_trimmed_bounded_and_counted() {
        let long = "é".repeat(150);
        let mut p = PrefsV2 {
            quick_replies: vec![
                "  ok  ".into(),
                String::new(),
                "   ".into(),
                long,
                "3".into(),
                "4".into(),
                "5".into(),
                "6".into(),
                "7".into(),
            ],
            ..PrefsV2::default()
        };
        p.validate();
        assert_eq!(p.quick_replies.len(), MAX_REPLIES);
        assert_eq!(p.quick_replies[0], "ok");
        assert_eq!(p.quick_replies[1].chars().count(), MAX_REPLY_CHARS);
        assert_eq!(p.quick_replies[5], "6");
    }

    #[test]
    fn speed_dial_keeps_digits_two_to_nine_with_a_number() {
        let mut p = PrefsV2::default();
        for d in [0u8, 1, 2, 9, 10, 255] {
            p.speed_dial.insert(d, entry("k", "0555"));
        }
        p.speed_dial.insert(5, entry("k5", "  "));
        p.validate();
        assert_eq!(p.speed_dial.keys().copied().collect::<Vec<_>>(), [2, 9]);
    }

    #[test]
    fn favorites_order_is_deduplicated_first_wins() {
        let mut p = PrefsV2 {
            favorites_order: ["b", "a", "b", "", "c", "a"].map(String::from).to_vec(),
            ..PrefsV2::default()
        };
        p.validate();
        assert_eq!(p.favorites_order, ["b", "a", "c"]);
    }

    #[test]
    fn validate_is_idempotent_and_resets_the_schema() {
        let mut p = PrefsV2 {
            schema_version: 9,
            quick_replies: vec!["x".repeat(500)],
            ..PrefsV2::default()
        };
        p.validate();
        let once = p.clone();
        p.validate();
        assert_eq!(p, once);
        assert_eq!(p.schema_version, SCHEMA_VERSION);
    }

    // ---- upgrade -------------------------------------------------------

    #[test]
    fn every_v1_field_maps() {
        let mut v1 = PrefsV1 {
            last_tab: Some(2),
            missed_seen_ms: 1_790_000_000_000,
            app_version: "0.1.0".into(),
            ..PrefsV1::default()
        };
        v1.xiaomi_confirmed
            .insert("autostart".into(), "0.1.0".into());
        let v2 = PrefsV2::from(v1.clone());
        assert_eq!(v2.ui.last_tab, Some(2));
        assert_eq!(v2.ui.missed_seen_ms, 1_790_000_000_000);
        assert_eq!(v2.ui.app_version, "0.1.0");
        assert_eq!(v2.ui.xiaomi_confirmed, v1.xiaomi_confirmed);
        assert_eq!(v2.settings, Settings::default());
        assert_eq!(v2.quick_replies, DEFAULT_REPLIES);
        assert_eq!(v2.schema_version, 2);
    }

    #[test]
    fn the_last_tab_rule_is_unchanged() {
        let mut p = PrefsV2::default();
        assert_eq!(p.last_tab_or(4, 3), 3);
        p.ui.last_tab = Some(0);
        assert_eq!(p.last_tab_or(4, 3), 0);
        p.ui.last_tab = Some(9);
        assert_eq!(p.last_tab_or(4, 3), 3);
    }

    // ---- haptics -------------------------------------------------------

    #[test]
    fn the_haptics_filter_table() {
        use HapticKind::*;
        use HapticsLevel::*;
        for kind in [Tick, Press, Confirm, Warning] {
            assert!(!haptic_allowed(Off, kind));
            assert!(haptic_allowed(Normal, kind));
            assert_eq!(haptic_allowed(Light, kind), kind == Tick);
        }
    }

    // ---- backup --------------------------------------------------------

    fn current() -> PrefsV2 {
        let mut p = PrefsV2::default();
        p.ui.last_tab = Some(1);
        p.ui.xiaomi_confirmed
            .insert("autostart".into(), "0.1.0".into());
        p
    }

    fn backup() -> BackupFile {
        let mut p = PrefsV2::default();
        p.settings.haptics = HapticsLevel::Light;
        p.settings.show_frequent = false;
        p.quick_replies = vec!["Later".into()];
        p.speed_dial.insert(2, entry("k1", "0555 111"));
        p.speed_dial.insert(3, entry("k2", "0555 222"));
        p.favorites_order = vec!["k2".into(), "k1".into()];
        BackupFile::from_prefs(&p, 123, "0.2.0")
    }

    #[test]
    fn a_backup_leaves_out_ui_state() {
        let b = BackupFile::from_prefs(&current(), 5, "0.2.0");
        assert_eq!(b.kind, BACKUP_KIND);
        assert_eq!(b.v, 1);
        assert_eq!(b.created_ms, 5);
        // There is no field that could carry `ui`; the type says it.
        assert_eq!(b.settings, Settings::default());
    }

    #[test]
    fn the_preview_counts_each_section() {
        let preview = ImportPreview::from(&backup(), &current()).expect("preview");
        assert_eq!(preview.settings_changed, 2);
        assert_eq!(preview.quick_replies, 1);
        assert_eq!(preview.speed_dial, 2);
        assert_eq!(preview.favorites, 2);
        assert!(preview.moved.is_empty() && !preview.clamped);
    }

    #[test]
    fn apply_replaces_sections_and_keeps_ui() {
        let preview = ImportPreview::from(&backup(), &current()).expect("preview");
        let mut prefs = current();
        preview.apply_to(&mut prefs);
        assert_eq!(prefs.settings.haptics, HapticsLevel::Light);
        assert_eq!(prefs.quick_replies, ["Later"]);
        assert_eq!(prefs.speed_dial.len(), 2);
        assert_eq!(prefs.favorites_order, ["k2", "k1"]);
        assert_eq!(prefs.ui.last_tab, Some(1));
        assert!(prefs.ui.xiaomi_confirmed.contains_key("autostart"));
    }

    #[test]
    fn a_contact_on_two_digits_moves_with_a_note() {
        let mut b = backup();
        b.speed_dial.insert(7, entry("k1", "0555 111"));
        let preview = ImportPreview::from(&b, &current()).expect("preview");
        assert_eq!(preview.moved, [MovedDigit { to: 7, from: 2 }]);
        assert_eq!(preview.speed_dial, 2);
        let mut prefs = current();
        preview.apply_to(&mut prefs);
        assert!(!prefs.speed_dial.contains_key(&2));
        assert!(prefs.speed_dial.contains_key(&7));
    }

    #[test]
    fn out_of_bounds_content_is_clamped_and_flagged() {
        let mut b = backup();
        b.quick_replies = (0..20).map(|i| format!("r{i}")).collect();
        b.speed_dial.insert(1, entry("v", "123"));
        let preview = ImportPreview::from(&b, &current()).expect("preview");
        assert_eq!(preview.quick_replies, MAX_REPLIES);
        assert_eq!(preview.speed_dial, 2);
        assert!(preview.clamped);
    }

    #[test]
    fn foreign_and_invalid_files_are_refused() {
        let mut b = backup();
        b.kind = "something-else".into();
        assert_eq!(
            ImportPreview::from(&b, &current()),
            Err(ImportError::NotABackup)
        );
        let mut b = backup();
        b.v = 0;
        assert_eq!(
            ImportPreview::from(&b, &current()),
            Err(ImportError::NotABackup)
        );
        let mut b = backup();
        b.v = 2;
        assert_eq!(ImportPreview::from(&b, &current()), Err(ImportError::Newer));
        assert!(plausible_backup_size(100));
        assert!(!plausible_backup_size(0));
        assert!(!plausible_backup_size(MAX_BACKUP_BYTES + 1));
    }

    #[test]
    fn replies_move_and_the_list_stops_at_six() {
        let mut list = vec!["a", "b", "c", "d"];
        move_reply(&mut list, 0, 2);
        assert_eq!(list, ["b", "c", "a", "d"]);
        move_reply(&mut list, 3, 0);
        assert_eq!(list, ["d", "b", "c", "a"]);
        // Out of range is clamped, never a panic.
        move_reply(&mut list, 1, 99);
        assert_eq!(list, ["d", "c", "a", "b"]);
        move_reply(&mut Vec::<u8>::new(), 0, 1);
        assert!(can_add_reply(5));
        assert!(!can_add_reply(MAX_REPLIES));
    }
}
