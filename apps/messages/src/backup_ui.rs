//! The encrypted backup flow's state (AD-MSG7), as a pure state machine.
//!
//! The Settings screen drives it: the export form checks the passphrase twice
//! and warns once about a weak one, a picked archive asks for its passphrase
//! (a wrong one asks again, it never gives up), and a readable archive shows
//! what a restore would do before anything is written. Nothing here touches
//! the platform; the file and crypto work lives in [`crate::settings_ui`].

use rovsti_core::{
    msg_backup::{BackupError, RestorePlan, Strength, passphrase_strength},
    schedule::{MIN_LEAD_MS, ScheduleTarget, ScheduledSms},
    spam::SpamRules,
};

use crate::conversation::civil_from_days;

/// What is wrong with the export form.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Issue {
    Empty,
    Mismatch,
    /// Shown once: submitting the same form again uses the passphrase anyway.
    Weak,
}

impl Issue {
    pub fn message(self) -> &'static str {
        match self {
            Self::Empty => "Type a passphrase. Without it the backup cannot be opened.",
            Self::Mismatch => "The two passphrases are different.",
            Self::Weak => "This passphrase is easy to guess. Press Export again to use it anyway.",
        }
    }
}

/// What the app is busy with.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Busy {
    /// Reading the messages, sealing, writing the file.
    Sealing,
    /// The system "save as" is open.
    Saving,
    /// The system file picker is open.
    Picking,
    /// Decrypting and comparing with the phone.
    Opening,
    /// Writing messages into the provider.
    Restoring,
}

impl Busy {
    pub fn label(self) -> &'static str {
        match self {
            Self::Sealing => "Encrypting your messages…",
            Self::Saving => "Choose where to save the backup…",
            Self::Picking => "Choose a backup file…",
            Self::Opening => "Opening the backup…",
            Self::Restoring => "Restoring messages…",
        }
    }
}

/// What a restore would do, shown before it happens.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Preview {
    pub insert: usize,
    pub skipped: usize,
    pub scheduled: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BackupState {
    Idle,
    /// The export form is open.
    Export {
        issue: Option<Issue>,
    },
    Busy(Busy),
    /// A file was picked and wants its passphrase.
    ImportAsk {
        wrong: bool,
    },
    ImportPreview(Preview),
    Done(String),
    Failed(String),
}

impl BackupState {
    pub fn is_busy(&self) -> bool {
        matches!(self, Self::Busy(_))
    }

    pub fn begin_export(&mut self) {
        if !self.is_busy() {
            *self = Self::Export { issue: None };
        }
    }

    /// The form's fields changed: the last complaint no longer applies, and a
    /// changed passphrase is warned about again.
    pub fn edited(&mut self) {
        if let Self::Export { issue } = self {
            *issue = None;
        }
    }

    /// Export was pressed. Returns whether the backup may be made now.
    pub fn submit_export(&mut self, passphrase: &str, confirm: &str) -> bool {
        let Self::Export { issue } = self else {
            return false;
        };
        let warned = *issue == Some(Issue::Weak);
        match check_export(passphrase, confirm, warned) {
            Ok(()) => {
                *self = Self::Busy(Busy::Sealing);
                true
            }
            Err(found) => {
                *issue = Some(found);
                false
            }
        }
    }

    /// The system picker opened (`true`) or could not (`false`).
    pub fn picker_started(&mut self, started: bool, busy: Busy) {
        *self = if started {
            Self::Busy(busy)
        } else {
            Self::Failed("The file picker could not open.".to_owned())
        };
    }

    /// The picker returned a file.
    pub fn picked(&mut self, ok: bool) {
        *self = if ok {
            Self::ImportAsk { wrong: false }
        } else {
            Self::Failed("That file could not be read, or it is too large.".to_owned())
        };
    }

    /// The archive was opened (or not) with the typed passphrase.
    pub fn opened(&mut self, result: Result<Preview, BackupError>) {
        *self = match result {
            Ok(preview) => Self::ImportPreview(preview),
            Err(BackupError::WrongPassphrase) => Self::ImportAsk { wrong: true },
            Err(other) => Self::Failed(describe(other).to_owned()),
        };
    }

    pub fn cancel(&mut self) {
        if !self.is_busy() {
            *self = Self::Idle;
        }
    }

    pub fn finished(&mut self, result: Result<String, String>) {
        *self = match result {
            Ok(done) => Self::Done(done),
            Err(failed) => Self::Failed(failed),
        };
    }
}

/// The export form's checks, in the order a person fixes them. A weak
/// passphrase passes once `warned`.
pub fn check_export(passphrase: &str, confirm: &str, warned: bool) -> Result<(), Issue> {
    if passphrase.is_empty() {
        return Err(Issue::Empty);
    }
    if passphrase != confirm {
        return Err(Issue::Mismatch);
    }
    if passphrase_strength(passphrase) == Strength::Weak && !warned {
        return Err(Issue::Weak);
    }
    Ok(())
}

pub fn strength_label(passphrase: &str) -> Option<&'static str> {
    if passphrase.is_empty() {
        return None;
    }
    Some(match passphrase_strength(passphrase) {
        Strength::Weak => "Weak",
        Strength::Fair => "Fair",
        Strength::Strong => "Strong",
    })
}

pub fn describe(error: BackupError) -> &'static str {
    match error {
        BackupError::NotABackup => "That file is not a Rovsti Messages backup.",
        BackupError::NewerVersion(_) => "That backup comes from a newer version of the app.",
        BackupError::WrongPassphrase => "Wrong passphrase, or the backup was changed.",
        BackupError::Corrupt => "The backup is damaged.",
    }
}

/// What the preview says about a plan and the backup's scheduled sends.
pub fn preview_of(plan: &RestorePlan, scheduled: usize) -> Preview {
    Preview {
        insert: plan.insert.len(),
        skipped: plan.skipped_duplicates,
        scheduled,
    }
}

/// The scheduled SMS of a backup that can still be sent: their time is ahead
/// and they are not WhatsApp items (the gateway keeps those).
pub fn restorable_scheduled(items: &[ScheduledSms], now_ms: i64) -> Vec<ScheduledSms> {
    items
        .iter()
        .filter(|i| {
            matches!(i.target, ScheduleTarget::Sms { .. }) && i.at_ms >= now_ms + MIN_LEAD_MS
        })
        .cloned()
        .collect()
}

/// Folds a backup's spam rules into the current ones: rules and allowed
/// senders that are missing are added, nothing is removed. Returns how many
/// were added.
pub fn merge_spam(current: &mut SpamRules, backup: &SpamRules) -> usize {
    let mut added = 0;
    for rule in &backup.rules {
        if current.add_rule(rule.clone()).is_ok() {
            added += 1;
        }
    }
    for sender in &backup.allow {
        let known = current.allow.iter().any(|a| a.eq_ignore_ascii_case(sender));
        if !known && !sender.trim().is_empty() {
            current.allow.push(sender.clone());
            added += 1;
        }
    }
    added
}

/// `rovsti-messages-20261105.rmb` for the local day of `now_ms` (the phone's
/// offset from UTC is `tz_offset_s`).
pub fn backup_file_name(now_ms: i64, tz_offset_s: i32) -> String {
    let local_s = now_ms.div_euclid(1000) + i64::from(tz_offset_s);
    let (year, month, day) = civil_from_days(local_s.div_euclid(86_400));
    format!("rovsti-messages-{year:04}{month:02}{day:02}.rmb")
}

/// How the restore result of `sms::insert_restored` reads.
pub fn restore_outcome(answer: &str) -> Result<String, String> {
    let value: serde_json::Value = serde_json::from_str(answer)
        .map_err(|_| "Android gave an unreadable answer.".to_owned())?;
    if value.get("error").and_then(|e| e.as_str()) == Some("role") {
        return Err("Make Rovsti Messages your default SMS app to restore messages.".to_owned());
    }
    let count = |key: &str| {
        value
            .get(key)
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0)
    };
    let (inserted, skipped) = (count("inserted"), count("skipped"));
    Ok(match (inserted, skipped) {
        (0, 0) => "Nothing to restore.".to_owned(),
        (n, 0) => format!("Restored {n} message{}.", plural(n)),
        (n, s) => format!(
            "Restored {n} message{}, skipped {s} already on this phone.",
            plural(n)
        ),
    })
}

fn plural(n: u64) -> &'static str {
    if n == 1 { "" } else { "s" }
}

#[cfg(test)]
mod tests {
    use rovsti_core::{schedule::ScheduledSms, spam::SpamRule};

    use super::*;

    const STRONG: &str = "correct horse battery staple";

    fn export_form() -> BackupState {
        let mut state = BackupState::Idle;
        state.begin_export();
        state
    }

    #[test]
    fn export_needs_a_passphrase_typed_twice() {
        let mut state = export_form();
        assert!(!state.submit_export("", ""));
        assert_eq!(
            state,
            BackupState::Export {
                issue: Some(Issue::Empty)
            }
        );
        assert!(!state.submit_export(STRONG, "something else"));
        assert_eq!(
            state,
            BackupState::Export {
                issue: Some(Issue::Mismatch)
            }
        );
        assert!(state.submit_export(STRONG, STRONG));
        assert_eq!(state, BackupState::Busy(Busy::Sealing));
    }

    #[test]
    fn a_weak_passphrase_is_warned_about_once() {
        let mut state = export_form();
        assert!(!state.submit_export("abc12", "abc12"));
        assert_eq!(
            state,
            BackupState::Export {
                issue: Some(Issue::Weak)
            }
        );
        // Pressing Export again accepts it.
        assert!(state.submit_export("abc12", "abc12"));
        // Editing re-arms the warning.
        let mut state = export_form();
        assert!(!state.submit_export("abc12", "abc12"));
        state.edited();
        assert_eq!(state, BackupState::Export { issue: None });
        assert!(!state.submit_export("abc12", "abc12"));
    }

    #[test]
    fn a_mismatch_beats_a_weak_warning() {
        let mut state = export_form();
        assert!(!state.submit_export("abc12", "abc13"));
        assert_eq!(
            state,
            BackupState::Export {
                issue: Some(Issue::Mismatch)
            }
        );
    }

    #[test]
    fn a_wrong_passphrase_on_import_asks_again() {
        let mut state = BackupState::Busy(Busy::Picking);
        state.picked(true);
        assert_eq!(state, BackupState::ImportAsk { wrong: false });
        state.opened(Err(BackupError::WrongPassphrase));
        assert_eq!(state, BackupState::ImportAsk { wrong: true });
        let preview = Preview {
            insert: 3,
            skipped: 1,
            scheduled: 0,
        };
        state.opened(Ok(preview));
        assert_eq!(state, BackupState::ImportPreview(preview));
    }

    #[test]
    fn unreadable_archives_fail_with_a_reason() {
        for error in [
            BackupError::NotABackup,
            BackupError::Corrupt,
            BackupError::NewerVersion(9),
        ] {
            let mut state = BackupState::Busy(Busy::Opening);
            state.opened(Err(error));
            assert_eq!(state, BackupState::Failed(describe(error).to_owned()));
        }
        let mut state = BackupState::Busy(Busy::Picking);
        state.picked(false);
        assert!(matches!(state, BackupState::Failed(_)));
        state.picker_started(false, Busy::Picking);
        assert!(matches!(state, BackupState::Failed(_)));
        state.picker_started(true, Busy::Saving);
        assert_eq!(state, BackupState::Busy(Busy::Saving));
    }

    #[test]
    fn busy_work_cannot_be_cancelled_or_restarted() {
        let mut state = BackupState::Busy(Busy::Restoring);
        state.cancel();
        state.begin_export();
        assert_eq!(state, BackupState::Busy(Busy::Restoring));
        state.finished(Ok("done".into()));
        assert_eq!(state, BackupState::Done("done".into()));
        state.cancel();
        assert_eq!(state, BackupState::Idle);
    }

    #[test]
    fn only_the_form_submits() {
        let mut state = BackupState::Idle;
        assert!(!state.submit_export(STRONG, STRONG));
        assert_eq!(state, BackupState::Idle);
    }

    #[test]
    fn restore_answers_read_naturally() {
        assert_eq!(
            restore_outcome(r#"{"inserted":1,"skipped":0}"#).expect("ok"),
            "Restored 1 message."
        );
        assert_eq!(
            restore_outcome(r#"{"inserted":5,"skipped":2}"#).expect("ok"),
            "Restored 5 messages, skipped 2 already on this phone."
        );
        assert_eq!(
            restore_outcome(r#"{"inserted":0,"skipped":0}"#).expect("ok"),
            "Nothing to restore."
        );
        assert!(restore_outcome(r#"{"inserted":0,"skipped":0,"error":"role"}"#).is_err());
        assert!(restore_outcome("nope").is_err());
    }

    #[test]
    fn file_names_carry_the_day() {
        // 2026-11-05 12:00 UTC.
        assert_eq!(
            backup_file_name(1_793_880_000_000, 0),
            "rovsti-messages-20261105.rmb"
        );
        assert_eq!(backup_file_name(0, 0), "rovsti-messages-19700101.rmb");
        // 22:30 UTC is already the next day in Turkey (+03:00).
        assert_eq!(
            backup_file_name(1_793_880_000_000 + 37_800_000, 3 * 3600),
            "rovsti-messages-20261106.rmb"
        );
    }

    #[test]
    fn only_future_sms_are_restored() {
        let now = 1_000_000;
        let sms = |id, at_ms| ScheduledSms {
            id,
            at_ms,
            target: ScheduleTarget::Sms {
                to: "+1".into(),
                sub_id: None,
            },
            body: "x".into(),
            created_ms: 0,
        };
        let wa = ScheduledSms {
            target: ScheduleTarget::WhatsApp { chat: "c".into() },
            ..sms(3, now + 10 * MIN_LEAD_MS)
        };
        let kept = restorable_scheduled(&[sms(1, now + MIN_LEAD_MS), sms(2, now + 1), wa], now);
        assert_eq!(kept.iter().map(|i| i.id).collect::<Vec<_>>(), vec![1]);
    }

    #[test]
    fn spam_rules_merge_without_removing_anything() {
        let mut current = SpamRules::default();
        current
            .add_rule(SpamRule::Keyword { text: "win".into() })
            .expect("ok");
        current.allow.push("BANK".into());
        let mut backup = SpamRules::default();
        backup
            .add_rule(SpamRule::Keyword { text: "WIN".into() })
            .expect("ok");
        backup
            .add_rule(SpamRule::Sender {
                pattern: "PROMO*".into(),
            })
            .expect("ok");
        backup.allow = vec!["bank".into(), "Mum".into(), " ".into()];
        assert_eq!(merge_spam(&mut current, &backup), 2);
        assert_eq!(current.rules.len(), 2);
        assert_eq!(current.allow, vec!["BANK".to_owned(), "Mum".to_owned()]);
    }

    #[test]
    fn plans_summarise() {
        let plan = RestorePlan {
            insert: vec![Default::default(); 2],
            skipped_duplicates: 4,
        };
        assert_eq!(
            preview_of(&plan, 1),
            Preview {
                insert: 2,
                skipped: 4,
                scheduled: 1
            }
        );
        assert_eq!(strength_label(""), None);
        assert_eq!(strength_label("abc"), Some("Weak"));
    }
}
