//! What Rovsti Contacts remembers between runs: the name format and the sort
//! order. The file is `contacts-prefs.json` in the app data dir; reading and
//! writing it is the app's job, this module only defines the value and how it
//! reads when the file is old, new, damaged or missing.

use serde::{Deserialize, Serialize};

use crate::{contacts_view::NameStyle, settings::SortBy, wire::NameFormat};

/// The schema this build writes.
pub const SCHEMA_VERSION: u32 = 1;

/// A file larger than this is not ours; it is ignored.
pub const MAX_FILE_BYTES: usize = 4 * 1024;

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
}

impl Default for ContactsPrefs {
    fn default() -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            name_format: NameFormat::default(),
            sort_by: SortBy::default(),
            save_to: String::new(),
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
    fn an_empty_object_is_the_default() {
        let prefs: ContactsPrefs = serde_json::from_str("{}").expect("ok");
        assert_eq!(prefs, ContactsPrefs::default());
    }
}
