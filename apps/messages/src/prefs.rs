//! `messages-prefs.json`: what Rovsti Messages remembers between runs.
//!
//! The file lives in the app's data dir next to the WhatsApp link. The cold
//! receivers read the `spam` field of the same file (see [`crate::headless`]),
//! so the field names are a contract with them; unknown fields are ignored on
//! both sides. A missing or corrupt file gives the defaults, never an error.

use std::{
    collections::{BTreeMap, BTreeSet},
    io,
    path::Path,
};

use rovsti_core::spam::{SpamRule, SpamRules};
use rovsti_ui::storage;
use serde::{Deserialize, Serialize};

pub use crate::headless::PREFS_FILE;

/// The per-thread SIM choices before they moved into the prefs file.
const LEGACY_SIM_FILE: &str = "sim-choices.json";

/// The rules a fresh install starts with: only unknown senders' links go to Spam.
pub fn default_spam_rules() -> SpamRules {
    SpamRules {
        rules: vec![SpamRule::UnknownWithLink],
        ..SpamRules::default()
    }
}

fn yes() -> bool {
    true
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MessagesPrefs {
    #[serde(default = "default_spam_rules")]
    pub spam: SpamRules,
    /// The SIM last used per SMS thread (`Telephony.Threads._ID` to subscription id).
    #[serde(default)]
    pub sim_choices: BTreeMap<i64, i32>,
    /// Master switch for message notifications made by the app.
    #[serde(default = "yes")]
    pub notifications: bool,
    /// Offer a "Copy code" chip on passcode messages.
    #[serde(default = "yes")]
    pub otp_copy: bool,
    /// Notification tags (`sms:<id>`, `wa:<jid>`) the user silenced.
    #[serde(default)]
    pub muted: BTreeSet<String>,
}

impl Default for MessagesPrefs {
    fn default() -> Self {
        Self {
            spam: default_spam_rules(),
            sim_choices: BTreeMap::new(),
            notifications: true,
            otp_copy: true,
            muted: BTreeSet::new(),
        }
    }
}

impl MessagesPrefs {
    /// Reads the file. A missing, unreadable or corrupt file gives the
    /// defaults; SIM choices from the old `sim-choices.json` are folded in.
    pub fn load(dir: &Path) -> Self {
        let mut prefs = storage::load_json::<Self>(dir, PREFS_FILE).unwrap_or_default();
        if let Some(old) = storage::load_json::<BTreeMap<i64, i32>>(dir, LEGACY_SIM_FILE) {
            for (thread, sub) in old {
                prefs.sim_choices.entry(thread).or_insert(sub);
            }
        }
        prefs
    }

    pub fn save(&self, dir: &Path) -> io::Result<()> {
        storage::save_json(dir, PREFS_FILE, self)
    }

    pub fn is_muted(&self, tag: &str) -> bool {
        self.muted.contains(tag)
    }

    pub fn set_muted(&mut self, tag: &str, muted: bool) {
        if muted {
            self.muted.insert(tag.to_owned());
        } else {
            self.muted.remove(tag);
        }
    }

    /// Adds a sender to the allow list (never spam). `false` when it was empty
    /// or already there.
    pub fn allow_sender(&mut self, sender: &str) -> bool {
        let sender = sender.trim();
        if sender.is_empty()
            || self
                .spam
                .allow
                .iter()
                .any(|a| a.eq_ignore_ascii_case(sender))
        {
            return false;
        }
        self.spam.allow.push(sender.to_owned());
        true
    }

    /// Takes a sender off the allow list (case-insensitive), for Undo. `false`
    /// when it was not there.
    pub fn unallow_sender(&mut self, sender: &str) -> bool {
        let sender = sender.trim();
        let before = self.spam.allow.len();
        self.spam.allow.retain(|a| !a.eq_ignore_ascii_case(sender));
        self.spam.allow.len() != before
    }

    pub fn remove_allowed(&mut self, index: usize) -> bool {
        if index < self.spam.allow.len() {
            self.spam.allow.remove(index);
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "rovsti-messages-prefs-{name}-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("temp dir");
        dir
    }

    #[test]
    fn round_trips_through_the_file() {
        let dir = tmp("round-trip");
        let mut prefs = MessagesPrefs::default();
        prefs.sim_choices.insert(7, 2);
        prefs.set_muted("sms:7", true);
        prefs.notifications = false;
        prefs
            .spam
            .add_rule(SpamRule::Keyword { text: "win".into() })
            .expect("ok");
        assert!(prefs.allow_sender("BANK"));
        prefs.save(&dir).expect("saved");
        assert_eq!(MessagesPrefs::load(&dir), prefs);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_corrupt_or_missing_file_gives_defaults() {
        let dir = tmp("corrupt");
        assert_eq!(MessagesPrefs::load(&dir), MessagesPrefs::default());
        std::fs::write(dir.join(PREFS_FILE), b"{ not json").expect("ok");
        assert_eq!(MessagesPrefs::load(&dir), MessagesPrefs::default());
        std::fs::write(dir.join(PREFS_FILE), b"[1,2,3]").expect("ok");
        assert_eq!(MessagesPrefs::load(&dir), MessagesPrefs::default());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn missing_fields_take_their_defaults() {
        let dir = tmp("partial");
        std::fs::write(dir.join(PREFS_FILE), br#"{"muted":["wa:x"],"future":1}"#).expect("ok");
        let prefs = MessagesPrefs::load(&dir);
        assert!(prefs.is_muted("wa:x"));
        assert!(prefs.notifications && prefs.otp_copy);
        assert_eq!(prefs.spam, default_spam_rules());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn legacy_sim_choices_are_folded_in_without_overriding() {
        let dir = tmp("legacy");
        std::fs::write(dir.join(PREFS_FILE), br#"{"sim_choices":{"1":5}}"#).expect("ok");
        std::fs::write(dir.join(LEGACY_SIM_FILE), br#"{"1":9,"2":3}"#).expect("ok");
        let prefs = MessagesPrefs::load(&dir);
        assert_eq!(prefs.sim_choices.get(&1), Some(&5));
        assert_eq!(prefs.sim_choices.get(&2), Some(&3));
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn the_default_rules_only_catch_unknown_links() {
        let rules = default_spam_rules();
        assert!(rules.enabled);
        assert_eq!(rules.rules, vec![SpamRule::UnknownWithLink]);
    }

    #[test]
    fn mute_and_allow_list_edits() {
        let mut prefs = MessagesPrefs::default();
        prefs.set_muted("wa:a", true);
        prefs.set_muted("wa:a", false);
        assert!(!prefs.is_muted("wa:a"));
        assert!(!prefs.allow_sender("  "));
        assert!(prefs.allow_sender("Bank"));
        assert!(!prefs.allow_sender("BANK"), "case-insensitive duplicate");
        assert!(prefs.remove_allowed(0));
        assert!(!prefs.remove_allowed(0));
    }

    #[test]
    fn unallow_sender_undoes_allow() {
        let mut prefs = MessagesPrefs::default();
        assert!(!prefs.unallow_sender("Bank"));
        assert!(prefs.allow_sender("Bank"));
        assert!(prefs.unallow_sender(" BANK "));
        assert!(prefs.spam.allow.is_empty());
    }
}
