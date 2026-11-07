//! A local trash for deleted contacts: they are kept as drafts for a while so
//! they can be restored.

use serde::{Deserialize, Serialize};

use crate::contact_edit::{Account, ContactDraft, Group};

/// Version written into the trash file.
pub const SCHEMA_VERSION: u32 = 1;
/// How long a deleted contact stays in the trash.
pub const RETENTION_DAYS: u32 = 30;
/// The most contacts the trash keeps; the oldest are dropped first.
pub const MAX_ENTRIES: usize = 500;
/// The largest trash file the app reads, in bytes.
pub const MAX_FILE_BYTES: usize = 8 * 1024 * 1024;

const DAY_MS: i64 = 24 * 60 * 60 * 1000;

/// One deleted contact.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct TrashEntry {
    /// Unique and increasing; never reused while the entry is alive.
    pub key: u64,
    pub deleted_ms: i64,
    pub draft: ContactDraft,
}

/// The deleted contacts, newest first.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Trash {
    #[serde(default)]
    pub schema_version: u32,
    #[serde(default)]
    pub entries: Vec<TrashEntry>,
}

impl Trash {
    /// Adds `drafts` and returns their new keys (in input order). The oldest
    /// entries are dropped past [`MAX_ENTRIES`].
    pub fn push(&mut self, drafts: Vec<ContactDraft>, now_ms: i64) -> Vec<u64> {
        self.schema_version = SCHEMA_VERSION;
        let first = self
            .entries
            .iter()
            .map(|e| e.key)
            .max()
            .map_or(1, |k| k + 1);
        let mut keys = Vec::with_capacity(drafts.len());
        let mut fresh = Vec::with_capacity(drafts.len());
        for (key, draft) in (first..).zip(drafts) {
            keys.push(key);
            fresh.push(TrashEntry {
                key,
                deleted_ms: now_ms,
                draft,
            });
        }
        // Newest first: later drafts of this batch lead.
        fresh.reverse();
        self.entries.splice(0..0, fresh);
        self.entries.truncate(MAX_ENTRIES);
        keys
    }

    /// Drops entries kept for [`RETENTION_DAYS`] or longer. Returns how many.
    pub fn purge_expired(&mut self, now_ms: i64) -> usize {
        let before = self.entries.len();
        let limit = i64::from(RETENTION_DAYS) * DAY_MS;
        self.entries.retain(|e| now_ms - e.deleted_ms < limit);
        before - self.entries.len()
    }

    /// Removes the entries with `keys` and returns their drafts without ids,
    /// ready to be inserted as new contacts.
    pub fn take(&mut self, keys: &[u64]) -> Vec<ContactDraft> {
        let mut out = Vec::new();
        self.entries.retain_mut(|e| {
            if keys.contains(&e.key) {
                let mut draft = std::mem::take(&mut e.draft);
                draft.id = None;
                out.push(draft);
                false
            } else {
                true
            }
        });
        out
    }

    /// Deletes the entries with `keys` for good. Returns how many.
    pub fn remove(&mut self, keys: &[u64]) -> usize {
        let before = self.entries.len();
        self.entries.retain(|e| !keys.contains(&e.key));
        before - self.entries.len()
    }

    /// Empties the trash. Returns how many entries it held.
    pub fn clear(&mut self) -> usize {
        let n = self.entries.len();
        self.entries.clear();
        n
    }

    /// The entries, newest deleted first.
    pub fn sorted(&self) -> Vec<&TrashEntry> {
        let mut v: Vec<&TrashEntry> = self.entries.iter().collect();
        v.sort_by(|a, b| b.deleted_ms.cmp(&a.deleted_ms).then(b.key.cmp(&a.key)));
        v
    }
}

/// Prepares drafts for insertion: drops groups that are gone or belong to
/// another account, and moves a draft whose account is gone to
/// `fallback_account`. An empty account ("on this phone") always exists.
pub fn restore_drafts(
    drafts: Vec<ContactDraft>,
    groups: &[Group],
    accounts: &[Account],
    fallback_account: &str,
) -> Vec<ContactDraft> {
    drafts
        .into_iter()
        .map(|mut d| {
            let known = d.account.is_empty() || accounts.iter().any(|a| a.key == d.account);
            if !known {
                d.account = fallback_account.to_string();
            }
            let account = d.account.clone();
            d.groups
                .retain(|id| groups.iter().any(|g| g.id == *id && g.account == account));
            d.id = None;
            d
        })
        .collect()
}

/// Whole days an entry has left, 0 once it is due.
pub fn days_left(deleted_ms: i64, now_ms: i64) -> u32 {
    let elapsed = (now_ms - deleted_ms).max(0) / DAY_MS;
    u32::try_from(i64::from(RETENTION_DAYS) - elapsed).unwrap_or(0)
}

/// "30 days left", "1 day left", or "Deleted today" when the entry is new.
pub fn left_text(deleted_ms: i64, now_ms: i64) -> String {
    if (now_ms - deleted_ms) < DAY_MS {
        return "Deleted today".to_string();
    }
    match days_left(deleted_ms, now_ms) {
        1 => "1 day left".to_string(),
        n => format!("{n} days left"),
    }
}

fn contacts(n: usize) -> String {
    if n == 1 {
        "1 contact".to_string()
    } else {
        format!("{n} contacts")
    }
}

/// Pill after a delete.
pub fn moved_notice(n: usize) -> String {
    if n == 1 {
        "Moved to Trash".to_string()
    } else {
        format!("Moved {n} contacts to Trash")
    }
}

/// Pill after a restore.
pub fn restored_notice(n: usize) -> String {
    if n == 1 {
        "Contact restored".to_string()
    } else {
        format!("Restored {n} contacts")
    }
}

/// Title and message for "Empty Trash".
pub fn empty_confirm(n: usize) -> (String, String) {
    (
        "Empty Trash?".to_string(),
        format!(
            "{} will be deleted for good. This can't be undone.",
            contacts(n)
        ),
    )
}

/// Title and message for "Delete forever" on selected entries.
pub fn forever_confirm(n: usize) -> (String, String) {
    (
        "Delete forever?".to_string(),
        format!(
            "{} will be deleted for good. This can't be undone.",
            contacts(n)
        ),
    )
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    fn draft(name: &str) -> ContactDraft {
        ContactDraft {
            id: Some(7),
            given: name.into(),
            ..ContactDraft::default()
        }
    }

    fn group(id: i64, account: &str) -> Group {
        Group {
            id,
            account: account.into(),
            ..Group::default()
        }
    }

    fn account(key: &str) -> Account {
        Account {
            key: key.into(),
            ..Account::default()
        }
    }

    #[test]
    fn keys_are_monotonic_across_reloads() {
        let mut t = Trash::default();
        let a = t.push(vec![draft("a"), draft("b")], 1);
        assert_eq!(a, vec![1, 2]);
        let json = serde_json::to_string(&t).unwrap();
        let mut t: Trash = serde_json::from_str(&json).unwrap();
        let b = t.push(vec![draft("c")], 2);
        assert_eq!(b, vec![3]);
        assert_eq!(t.sorted()[0].draft.given, "c");
    }

    #[test]
    fn cap_drops_the_oldest() {
        let mut t = Trash::default();
        for i in 0..MAX_ENTRIES {
            t.push(vec![draft(&i.to_string())], i as i64);
        }
        t.push(vec![draft("new")], 10_000);
        assert_eq!(t.entries.len(), MAX_ENTRIES);
        assert_eq!(t.entries[0].draft.given, "new");
        assert!(t.entries.iter().all(|e| e.draft.given != "0"));
        assert!(t.entries.iter().any(|e| e.draft.given == "1"));
    }

    #[test]
    fn purge_at_exactly_thirty_days() {
        let mut t = Trash::default();
        t.push(vec![draft("a")], 0);
        let limit = 30 * DAY_MS;
        assert_eq!(t.purge_expired(limit - 1), 0);
        assert_eq!(t.purge_expired(limit), 1);
        assert!(t.entries.is_empty());
    }

    #[test]
    fn take_clears_ids_and_keeps_others() {
        let mut t = Trash::default();
        let keys = t.push(vec![draft("a"), draft("b"), draft("c")], 1);
        let got = t.take(&[keys[1]]);
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].given, "b");
        assert_eq!(got[0].id, None);
        assert_eq!(t.entries.len(), 2);
        assert_eq!(t.remove(&[keys[0], 99]), 1);
        assert_eq!(t.clear(), 1);
    }

    #[test]
    fn restore_drops_stale_groups_and_remaps_accounts() {
        let groups = [group(1, "g"), group(2, "other"), group(3, "")];
        let accounts = [account("g")];
        let mut a = draft("a");
        a.account = "g".into();
        a.groups = vec![1, 2, 9];
        let mut b = draft("b");
        b.account = "gone".into();
        b.groups = vec![1, 3];
        let mut c = draft("c");
        c.groups = vec![3, 1];
        let out = restore_drafts(vec![a, b, c], &groups, &accounts, "");
        assert_eq!(out[0].groups, vec![1]);
        assert_eq!(out[0].id, None);
        assert_eq!(out[1].account, "");
        assert_eq!(out[1].groups, vec![3]);
        assert_eq!(out[2].groups, vec![3]);
        let mut y = draft("y");
        y.account = "gone".into();
        y.groups = vec![1];
        let out = restore_drafts(vec![y], &groups, &accounts, "g");
        assert_eq!(out[0].account, "g");
        assert_eq!(out[0].groups, vec![1]);
    }

    #[test]
    fn json_round_trip_and_old_json() {
        let mut t = Trash::default();
        t.push(vec![draft("a")], 5);
        let json = serde_json::to_string(&t).unwrap();
        assert_eq!(serde_json::from_str::<Trash>(&json).unwrap(), t);
        let old: Trash = serde_json::from_str("{}").unwrap();
        assert!(old.entries.is_empty());
        assert_eq!(old.schema_version, 0);
    }

    #[test]
    fn days_and_texts() {
        assert_eq!(days_left(0, 0), 30);
        assert_eq!(days_left(0, 29 * DAY_MS), 1);
        assert_eq!(days_left(0, 30 * DAY_MS), 0);
        assert_eq!(days_left(0, 99 * DAY_MS), 0);
        assert_eq!(left_text(0, 100), "Deleted today");
        assert_eq!(left_text(0, DAY_MS), "29 days left");
        assert_eq!(left_text(0, 29 * DAY_MS), "1 day left");
        assert_eq!(moved_notice(1), "Moved to Trash");
        assert_eq!(moved_notice(3), "Moved 3 contacts to Trash");
        assert_eq!(restored_notice(1), "Contact restored");
        assert_eq!(restored_notice(3), "Restored 3 contacts");
        assert_eq!(
            empty_confirm(3),
            (
                "Empty Trash?".to_string(),
                "3 contacts will be deleted for good. This can't be undone.".to_string()
            )
        );
        assert!(empty_confirm(1).1.starts_with("1 contact will"));
        assert!(forever_confirm(2).1.starts_with("2 contacts will"));
    }
}
