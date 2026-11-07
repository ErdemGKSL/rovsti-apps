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

use rovsti_core::spam::{RuleError, SpamRule, SpamRules};
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

/// What [`MessagesPrefs::mark_spam`] changed, so Undo can restore it exactly.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SpamMark {
    /// A new sender rule was added (not already there).
    pub rule_added: bool,
    /// The sender was on the allow list and has been taken off.
    pub was_allowed: bool,
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
    /// The reply chips above the composers.
    #[serde(default = "rovsti_core::quick_replies::defaults")]
    pub quick_replies: Vec<String>,
    /// The voice note playback speed, remembered between notes and runs.
    #[serde(default)]
    pub voice_speed: rovsti_core::voice_player::Speed,
}

impl Default for MessagesPrefs {
    fn default() -> Self {
        Self {
            spam: default_spam_rules(),
            sim_choices: BTreeMap::new(),
            notifications: true,
            otp_copy: true,
            muted: BTreeSet::new(),
            quick_replies: rovsti_core::quick_replies::defaults(),
            voice_speed: rovsti_core::voice_player::Speed::default(),
        }
    }
}

impl MessagesPrefs {
    /// The defaults, except that the SIM choices stay: they are not a
    /// setting the user can see.
    pub fn reset(&self) -> MessagesPrefs {
        MessagesPrefs {
            sim_choices: self.sim_choices.clone(),
            quick_replies: self.quick_replies.clone(),
            ..MessagesPrefs::default()
        }
    }

    /// Whether the settings already equal [`MessagesPrefs::reset`].
    pub fn is_reset(&self) -> bool {
        self.reset() == *self
    }

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

    /// Moves the per-thread state of tag `from_tag` to `into_tag` (a merged
    /// chat). Returns `true` when anything changed.
    pub fn rekey_thread(&mut self, from_tag: &str, into_tag: &str) -> bool {
        if from_tag == into_tag {
            return false;
        }
        let mut changed = false;
        if self.muted.remove(from_tag) {
            self.muted.insert(into_tag.to_owned());
            changed = true;
        }
        changed
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

    /// Moves a sender into Spam: a sender rule, and off the allow list (which
    /// beats rules). An existing rule is fine; on any other error nothing changes.
    pub fn mark_spam(&mut self, sender: &str) -> Result<SpamMark, RuleError> {
        let sender = sender.trim();
        let was_allowed = self.unallow_sender(sender);
        let rule = SpamRule::Sender {
            pattern: sender.to_owned(),
        };
        match self.spam.add_rule(rule) {
            Ok(()) => Ok(SpamMark {
                rule_added: true,
                was_allowed,
            }),
            Err(RuleError::Duplicate) => Ok(SpamMark {
                rule_added: false,
                was_allowed,
            }),
            Err(e) => {
                if was_allowed {
                    self.allow_sender(sender);
                }
                Err(e)
            }
        }
    }

    /// Undoes [`MessagesPrefs::mark_spam`].
    pub fn unmark_spam(&mut self, sender: &str, mark: SpamMark) {
        let sender = sender.trim();
        if mark.rule_added {
            let found = self.spam.rules.iter().position(|r| {
                matches!(r, SpamRule::Sender { pattern } if pattern.trim().eq_ignore_ascii_case(sender))
            });
            if let Some(index) = found {
                self.spam.remove_rule(index);
            }
        }
        if mark.was_allowed {
            self.allow_sender(sender);
        }
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
    fn reset_keeps_sim_choices_only() {
        let mut prefs = MessagesPrefs::default();
        assert!(prefs.is_reset());
        prefs.sim_choices.insert(4, 2);
        assert!(prefs.is_reset());
        prefs.notifications = false;
        prefs.otp_copy = false;
        prefs.muted.insert("sms:1".into());
        prefs
            .spam
            .add_rule(SpamRule::Keyword { text: "win".into() })
            .expect("ok");
        assert!(!prefs.is_reset());
        let reset = prefs.reset();
        assert!(reset.is_reset());
        assert_eq!(reset.sim_choices.get(&4), Some(&2));
        assert!(reset.notifications && reset.otp_copy && reset.muted.is_empty());
        assert_eq!(reset.spam, MessagesPrefs::default().spam);
    }

    #[test]
    fn old_prefs_file_gets_default_replies() {
        let prefs: MessagesPrefs = serde_json::from_str("{}").expect("parsed");
        assert_eq!(prefs.quick_replies, rovsti_core::quick_replies::defaults());
    }

    #[test]
    fn old_prefs_file_defaults_speed() {
        use rovsti_core::voice_player::Speed;
        let prefs: MessagesPrefs = serde_json::from_str("{}").expect("parsed");
        assert_eq!(prefs.voice_speed, Speed::X1);
        let fast: MessagesPrefs = serde_json::from_str("{\"voice_speed\":\"X2\"}").expect("parsed");
        assert_eq!(fast.voice_speed, Speed::X2);
        assert_eq!(fast.reset().voice_speed, Speed::X1);
    }

    #[test]
    fn reset_keeps_quick_replies() {
        let mut prefs = MessagesPrefs {
            quick_replies: vec!["Mine".into()],
            ..MessagesPrefs::default()
        };
        assert!(prefs.is_reset());
        prefs.notifications = false;
        assert!(!prefs.is_reset());
        assert_eq!(prefs.reset().quick_replies, vec!["Mine".to_string()]);
    }

    #[test]
    fn undoing_a_reset_brings_everything_back() {
        let mut prefs = MessagesPrefs::default();
        prefs.sim_choices.insert(4, 2);
        prefs.muted.insert("wa:x".into());
        prefs.otp_copy = false;
        let old = prefs.clone();
        prefs = prefs.reset();
        assert!(prefs.is_reset());
        assert_ne!(prefs, old);
        prefs = old.clone();
        assert_eq!(prefs, old);
        assert!(!prefs.is_reset());
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
    }

    #[test]
    fn rekey_thread_moves_the_mute() {
        let mut prefs = MessagesPrefs::default();
        prefs.set_muted("wa:1@lid", true);
        assert!(prefs.rekey_thread("wa:1@lid", "wa:2@s.whatsapp.net"));
        assert!(!prefs.is_muted("wa:1@lid"));
        assert!(prefs.is_muted("wa:2@s.whatsapp.net"));
        assert!(!prefs.rekey_thread("wa:1@lid", "wa:2@s.whatsapp.net"));
        assert!(!prefs.rekey_thread("wa:2@s.whatsapp.net", "wa:2@s.whatsapp.net"));
    }

    #[test]
    fn allow_list_edits() {
        let mut prefs = MessagesPrefs::default();
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

    fn has_sender_rule(prefs: &MessagesPrefs, sender: &str) -> bool {
        prefs.spam.rules.iter().any(
            |r| matches!(r, SpamRule::Sender { pattern } if pattern.eq_ignore_ascii_case(sender)),
        )
    }

    #[test]
    fn mark_spam_adds_a_sender_rule_and_unallows() {
        let mut prefs = MessagesPrefs::default();
        assert!(prefs.allow_sender("TRENDYOL"));
        let mark = prefs.mark_spam(" TRENDYOL ").expect("ok");
        assert_eq!(
            mark,
            SpamMark {
                rule_added: true,
                was_allowed: true
            }
        );
        assert!(has_sender_rule(&prefs, "TRENDYOL"));
        assert!(prefs.spam.allow.is_empty());
    }

    #[test]
    fn unmark_spam_restores_exactly() {
        let mut prefs = MessagesPrefs::default();
        let original = prefs.clone();
        let mark = prefs.mark_spam("GARANTI").expect("ok");
        prefs.unmark_spam("garanti", mark);
        assert_eq!(prefs, original);

        prefs.allow_sender("GARANTI");
        let original = prefs.clone();
        let mark = prefs.mark_spam("GARANTI").expect("ok");
        assert!(mark.was_allowed && mark.rule_added);
        prefs.unmark_spam("GARANTI", mark);
        assert_eq!(prefs, original);
    }

    #[test]
    fn mark_spam_on_a_full_list_changes_nothing() {
        let mut prefs = MessagesPrefs::default();
        let mut n = 0;
        while prefs.spam.rules.len() < rovsti_core::spam::MAX_RULES {
            prefs
                .spam
                .add_rule(SpamRule::Keyword {
                    text: format!("k{n}"),
                })
                .expect("room");
            n += 1;
        }
        prefs.allow_sender("TRENDYOL");
        let before = prefs.clone();
        assert_eq!(prefs.mark_spam("TRENDYOL"), Err(RuleError::TooMany));
        assert_eq!(prefs, before);
        assert_eq!(prefs.mark_spam("  "), Err(RuleError::Empty));
        assert_eq!(prefs, before);
    }

    #[test]
    fn mark_spam_twice_is_a_duplicate_not_an_error() {
        let mut prefs = MessagesPrefs::default();
        prefs.mark_spam("TRENDYOL").expect("first");
        let rules = prefs.spam.rules.len();
        let mark = prefs.mark_spam("trendyol").expect("duplicate is fine");
        assert!(!mark.rule_added);
        assert_eq!(prefs.spam.rules.len(), rules);
        // Undoing the second mark must not remove the first rule.
        prefs.unmark_spam("trendyol", mark);
        assert!(has_sender_rule(&prefs, "TRENDYOL"));
    }
}
