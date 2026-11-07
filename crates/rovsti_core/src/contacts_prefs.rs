//! What Rovsti Contacts remembers between runs: the name format and the sort
//! order. The file is `contacts-prefs.json` in the app data dir; reading and
//! writing it is the app's job, this module only defines the value and how it
//! reads when the file is old, new, damaged or missing.

use serde::{Deserialize, Serialize};

use crate::{contacts_view::NameStyle, settings::SortBy, wire::NameFormat};

/// The schema this build writes.
pub const SCHEMA_VERSION: u32 = 1;

/// A file larger than this is not ours; it is ignored.
pub const MAX_FILE_BYTES: usize = 64 * 1024;

/// Most favorites keys kept in the saved order.
pub const MAX_ORDER: usize = 300;

fn schema() -> u32 {
    SCHEMA_VERSION
}

/// The saved settings. Unknown fields are ignored and missing ones take their
/// default, so a file written by a newer or older build still loads.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ContactsPrefs {
    #[serde(default = "schema")]
    pub schema_version: u32,
    pub name_format: NameFormat,
    pub sort_by: SortBy,
    /// The account key new contacts are saved to (empty: on this phone).
    #[serde(default)]
    pub save_to: String,
    /// Morning notifications for birthdays and anniversaries (opt in).
    #[serde(default)]
    pub birthday_reminders: bool,
    /// Local hour of those notifications, 0..=23.
    #[serde(default = "default_hour")]
    pub reminder_hour: u8,
    /// Lookup keys of the favorites in the user's order.
    #[serde(default)]
    pub favorites_order: Vec<String>,
}

fn default_hour() -> u8 {
    crate::birthday_reminder::DEFAULT_HOUR
}

impl ContactsPrefs {
    /// The reminder hour, clamped to 0..=23 (a damaged file may hold more).
    pub fn hour(&self) -> u8 {
        self.reminder_hour.min(23)
    }

    /// The saved favorites order: no empty or repeated keys, at most `MAX_ORDER`.
    pub fn order(&self) -> Vec<String> {
        let mut seen = std::collections::HashSet::new();
        self.favorites_order
            .iter()
            .filter(|k| !k.is_empty() && seen.insert(k.as_str()))
            .take(MAX_ORDER)
            .cloned()
            .collect()
    }
}

impl Default for ContactsPrefs {
    fn default() -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            name_format: NameFormat::default(),
            sort_by: SortBy::default(),
            save_to: String::new(),
            birthday_reminders: false,
            reminder_hour: default_hour(),
            favorites_order: Vec::new(),
        }
    }
}

impl ContactsPrefs {
    /// The style the lists use.
    pub fn style(&self) -> NameStyle {
        NameStyle {
            format: self.name_format,
            sort: self.sort_by,
        }
    }

    /// The prefs that remember `style`.
    pub fn from_style(style: NameStyle) -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            name_format: style.format,
            sort_by: style.sort,
            save_to: String::new(),
            birthday_reminders: false,
            reminder_hour: default_hour(),
            favorites_order: Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_the_default_style() {
        assert_eq!(ContactsPrefs::default().style(), NameStyle::default());
    }

    #[test]
    fn a_style_survives_a_round_trip() {
        let style = NameStyle {
            format: NameFormat::LastFirst,
            sort: SortBy::LastName,
        };
        let json = serde_json::to_string(&ContactsPrefs::from_style(style)).expect("serialises");
        let back: ContactsPrefs = serde_json::from_str(&json).expect("parses");
        assert_eq!(back.style(), style);
    }

    #[test]
    fn a_missing_field_takes_its_default() {
        let prefs: ContactsPrefs = serde_json::from_str(r#"{"sort_by":"last_name"}"#).expect("ok");
        assert_eq!(prefs.sort_by, SortBy::LastName);
        assert_eq!(prefs.name_format, NameFormat::FirstLast);
    }

    #[test]
    fn unknown_fields_and_values_are_ignored() {
        let prefs: ContactsPrefs = serde_json::from_str(
            r#"{"schema_version":9,"name_format":"weird","sort_by":"last_name","new_thing":1}"#,
        )
        .expect("ok");
        assert_eq!(prefs.name_format, NameFormat::FirstLast);
        assert_eq!(prefs.sort_by, SortBy::LastName);
    }

    #[test]
    fn an_old_file_without_save_to_loads() {
        let prefs: ContactsPrefs =
            serde_json::from_str(r#"{"schema_version":1,"name_format":"last_first"}"#).expect("ok");
        assert_eq!(prefs.save_to, "");
        let with: ContactsPrefs =
            serde_json::from_str(r#"{"save_to":"com.google|a@b"}"#).expect("ok");
        assert_eq!(with.save_to, "com.google|a@b");
    }

    #[test]
    fn reminders_default_off_at_nine_and_old_json_loads() {
        let prefs: ContactsPrefs = serde_json::from_str(r#"{"save_to":"x"}"#).expect("ok");
        assert!(!prefs.birthday_reminders);
        assert_eq!(prefs.reminder_hour, 9);
        let p: ContactsPrefs =
            serde_json::from_str(r#"{"birthday_reminders":true,"reminder_hour":200}"#).expect("ok");
        assert!(p.birthday_reminders);
        assert_eq!(p.hour(), 23);
    }

    #[test]
    fn favorites_order_defaults_empty_and_old_json_loads() {
        assert!(ContactsPrefs::default().favorites_order.is_empty());
        let p: ContactsPrefs = serde_json::from_str(r#"{"save_to":"x"}"#).expect("ok");
        assert!(p.order().is_empty());
        let p: ContactsPrefs =
            serde_json::from_str(r#"{"favorites_order":["a","b"]}"#).expect("ok");
        assert_eq!(p.order(), ["a", "b"]);
    }

    #[test]
    fn order_drops_empty_and_repeated_keys_and_caps() {
        let p = ContactsPrefs {
            favorites_order: ["a", "", "b", "a", "c"].map(String::from).to_vec(),
            ..ContactsPrefs::default()
        };
        assert_eq!(p.order(), ["a", "b", "c"]);
        let many = ContactsPrefs {
            favorites_order: (0..400).map(|i| format!("k{i}")).collect(),
            ..ContactsPrefs::default()
        };
        assert_eq!(many.order().len(), MAX_ORDER);
        assert_eq!(many.order()[299], "k299");
    }

    #[test]
    fn an_empty_object_is_the_default() {
        let prefs: ContactsPrefs = serde_json::from_str("{}").expect("ok");
        assert_eq!(prefs, ContactsPrefs::default());
    }
}
