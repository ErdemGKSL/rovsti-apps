//! Recently added / edited contacts and the recently viewed list.
//!
//! Android has no creation time for a contact, so "added" is a local mark: the
//! first pull only seeds the highest id seen, later pulls mark ids above it.
//! Everything here is pure; the app stores the results in `ContactsPrefs`.

use serde::{Deserialize, Serialize};

use crate::{contacts_view, wire::WireContact};

/// Most added marks kept.
pub const ADDED_KEEP: usize = 50;
/// Most recently viewed contacts kept.
pub const VIEWED_KEEP: usize = 8;
/// How many days a contact counts as recent.
pub const WINDOW_DAYS: u16 = 30;

/// A contact edit within this many ms of its added mark is the add itself.
const SAME_EVENT_MS: i64 = 60_000;
const DAY_MS: i64 = 86_400_000;

/// Confirm texts (title, body) for clearing the recently viewed list.
pub const CLEAR_VIEWED: (&str, &str) = (
    "Clear recently viewed?",
    "The contacts stay; only this list is cleared.",
);

/// The moment a contact was first seen by this app.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AddedMark {
    pub id: i64,
    pub ms: i64,
}

/// Which list the Recent page shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RecentTab {
    Added,
    Edited,
}

impl RecentTab {
    /// The tabs in the order the segmented control shows them.
    pub const ALL: [Self; 2] = [Self::Added, Self::Edited];

    /// The empty state (title, body) of the tab.
    pub fn empty(self) -> (&'static str, &'static str) {
        match self {
            Self::Added => ("Nothing New", "No contacts added in the last 30 days"),
            Self::Edited => ("Nothing Edited", "No contacts edited in the last 30 days"),
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Added => "Added",
            Self::Edited => "Edited",
        }
    }
}

fn tidy(mut marks: Vec<AddedMark>, now_ms: i64) -> Vec<AddedMark> {
    let oldest = now_ms.saturating_sub(i64::from(WINDOW_DAYS) * DAY_MS);
    marks.retain(|m| m.ms >= oldest);
    marks.sort_by(|a, b| b.ms.cmp(&a.ms).then(b.id.cmp(&a.id)));
    marks.dedup_by_key(|m| m.id);
    marks.truncate(ADDED_KEEP);
    marks
}

/// Folds a full pull (`ids` of every contact) into the marks. Returns the new
/// highest id (never below `known_max_id`) and the marks, newest first. The
/// first pull (`None`) seeds the max and marks nothing; an empty first pull
/// returns 0, so the app should keep `None` until the book has contacts.
pub fn note_pull(
    known_max_id: Option<i64>,
    marks: &[AddedMark],
    ids: &[i64],
    now_ms: i64,
) -> (i64, Vec<AddedMark>) {
    let top = ids.iter().copied().max();
    let Some(known) = known_max_id else {
        return (top.unwrap_or(0), Vec::new());
    };
    let mut next: Vec<AddedMark> = marks
        .iter()
        .filter(|m| ids.contains(&m.id))
        .cloned()
        .collect();
    for &id in ids {
        if id > known && !next.iter().any(|m| m.id == id) {
            next.push(AddedMark { id, ms: now_ms });
        }
    }
    (known.max(top.unwrap_or(known)), tidy(next, now_ms))
}

/// Marks a contact created in the app (no duplicate for the same id).
pub fn note_created(marks: &[AddedMark], id: i64, now_ms: i64) -> Vec<AddedMark> {
    let mut next: Vec<AddedMark> = marks.iter().filter(|m| m.id != id).cloned().collect();
    next.push(AddedMark { id, ms: now_ms });
    tidy(next, now_ms)
}

/// Marked contacts that are still in the book with their mark time, newest first.
pub fn added<'a>(contacts: &'a [WireContact], marks: &[AddedMark]) -> Vec<(&'a WireContact, i64)> {
    let mut sorted: Vec<&AddedMark> = marks.iter().collect();
    sorted.sort_by(|a, b| b.ms.cmp(&a.ms).then(b.id.cmp(&a.id)));
    let mut seen = Vec::new();
    let mut out = Vec::new();
    for m in sorted {
        if seen.contains(&m.id) {
            continue;
        }
        if let Some(c) = contacts.iter().find(|c| c.id == m.id) {
            seen.push(m.id);
            out.push((c, m.ms));
        }
    }
    out
}

/// Recently changed contacts, minus those whose only change is being added.
/// A contact edited after it was added still counts.
pub fn edited<'a>(
    contacts: &'a [WireContact],
    marks: &[AddedMark],
    now_ms: i64,
    within_days: u16,
    max: usize,
) -> Vec<&'a WireContact> {
    contacts_view::recent(contacts, now_ms, within_days, usize::MAX)
        .into_iter()
        .filter(|c| {
            let t = c.updated_ms.unwrap_or(0);
            !marks
                .iter()
                .any(|m| m.id == c.id && (t - m.ms).abs() <= SAME_EVENT_MS)
        })
        .take(max)
        .collect()
}

/// The Recent entry's (title, subtitle) for the counts.
pub fn entry_title(added: usize, edited: usize) -> (&'static str, String) {
    match (added, edited) {
        (a, 0) => ("Recently added", a.to_string()),
        (0, e) => ("Recently edited", e.to_string()),
        (a, e) => ("Recent", format!("{a} added · {e} edited")),
    }
}

/// Whether the entry shows: not while searching, and only with something to list.
pub fn show_entry(query: &str, added: usize, edited: usize) -> bool {
    query.trim().is_empty() && added + edited > 0
}

/// `key` moved to the front; no repeats, at most `VIEWED_KEEP`. Empty keys are ignored.
pub fn note_viewed(keys: &[String], key: &str) -> Vec<String> {
    if key.is_empty() {
        return keys.to_vec();
    }
    let mut out = vec![key.to_owned()];
    out.extend(
        keys.iter()
            .filter(|k| k.as_str() != key && !k.is_empty())
            .cloned(),
    );
    out.truncate(VIEWED_KEEP);
    out
}

/// The viewed contacts in key order; keys with no contact and `hidden` ids are dropped.
pub fn viewed<'a>(keys: &[String], all: &'a [WireContact], hidden: &[i64]) -> Vec<&'a WireContact> {
    let mut out: Vec<&WireContact> = Vec::new();
    for k in keys {
        let found = all
            .iter()
            .find(|c| c.lookup_key.as_deref() == Some(k.as_str()));
        if let Some(c) =
            found.filter(|c| !hidden.contains(&c.id) && !out.iter().any(|o| o.id == c.id))
        {
            out.push(c);
        }
    }
    out.truncate(VIEWED_KEEP);
    out
}

/// `keys` without `key`.
pub fn forget_viewed(keys: &[String], key: &str) -> Vec<String> {
    keys.iter().filter(|k| k.as_str() != key).cloned().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 100 * DAY_MS;

    fn mark(id: i64, ms: i64) -> AddedMark {
        AddedMark { id, ms }
    }

    fn c(id: i64, key: &str, updated: Option<i64>) -> WireContact {
        WireContact {
            id,
            display_name: format!("C{id}"),
            lookup_key: (!key.is_empty()).then(|| key.to_owned()),
            updated_ms: updated,
            ..WireContact::default()
        }
    }

    fn keys(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn first_pull_seeds_without_marks() {
        assert_eq!(note_pull(None, &[], &[3, 9, 5], NOW), (9, vec![]));
    }

    #[test]
    fn later_pull_marks_new_ids() {
        let (max, marks) = note_pull(Some(9), &[], &[3, 9, 10, 11], NOW);
        assert_eq!(max, 11);
        assert_eq!(marks, vec![mark(11, NOW), mark(10, NOW)]);
        let (_, again) = note_pull(Some(11), &marks, &[3, 9, 10, 11], NOW + 5);
        assert_eq!(again.len(), 2);
    }

    #[test]
    fn deleted_ids_drop() {
        let (_, marks) = note_pull(Some(9), &[mark(10, NOW), mark(11, NOW)], &[9, 11], NOW);
        assert_eq!(marks, vec![mark(11, NOW)]);
    }

    #[test]
    fn marks_expire() {
        let old = NOW - i64::from(WINDOW_DAYS) * DAY_MS - 1;
        let (_, marks) = note_pull(Some(9), &[mark(10, old), mark(11, NOW)], &[10, 11], NOW);
        assert_eq!(marks, vec![mark(11, NOW)]);
    }

    #[test]
    fn marks_are_capped_to_newest() {
        let ids: Vec<i64> = (100..200).collect();
        let (max, marks) = note_pull(Some(99), &[], &ids, NOW);
        assert_eq!((max, marks.len()), (199, ADDED_KEEP));
        let old: Vec<AddedMark> = (0..60).map(|i| mark(i, NOW - i)).collect();
        let all: Vec<i64> = (0..60).collect();
        let (_, kept) = note_pull(Some(60), &old, &all, NOW);
        assert_eq!(kept.len(), ADDED_KEEP);
        assert_eq!(kept[0].id, 0);
    }

    #[test]
    fn max_never_goes_down() {
        assert_eq!(note_pull(Some(50), &[], &[1, 2], NOW).0, 50);
        assert_eq!(note_pull(Some(50), &[], &[], NOW).0, 50);
    }

    #[test]
    fn created_dedups() {
        let m = note_created(&[mark(1, NOW - 10)], 1, NOW);
        assert_eq!(m, vec![mark(1, NOW)]);
        let m = note_created(&m, 2, NOW + 1);
        assert_eq!(m[0].id, 2);
    }

    #[test]
    fn added_edited_split() {
        let marks = vec![mark(1, NOW - 1000), mark(2, NOW - 200_000), mark(9, NOW)];
        let all = vec![
            c(1, "a", Some(NOW - 1000)),
            c(2, "b", Some(NOW - 2000)),
            c(3, "c", Some(NOW - 5000)),
        ];
        let a: Vec<i64> = added(&all, &marks).iter().map(|(c, _)| c.id).collect();
        assert_eq!(a, vec![1, 2]);
        let e: Vec<i64> = edited(&all, &marks, NOW, 30, 10)
            .iter()
            .map(|c| c.id)
            .collect();
        // 2 was edited minutes after it was added; 1 is only added.
        assert_eq!(e, vec![2, 3]);
    }

    #[test]
    fn entry_titles() {
        assert_eq!(entry_title(3, 0), ("Recently added", "3".to_owned()));
        assert_eq!(entry_title(0, 5), ("Recently edited", "5".to_owned()));
        assert_eq!(
            entry_title(3, 5),
            ("Recent", "3 added · 5 edited".to_owned())
        );
        assert!(show_entry("", 1, 0));
        assert!(!show_entry("ay", 1, 0));
        assert!(!show_entry(" ", 0, 0));
        assert_eq!(RecentTab::Added.label(), "Added");
        assert_eq!(RecentTab::Edited.label(), "Edited");
    }

    #[test]
    fn viewed_moves_to_front_and_caps() {
        let k = note_viewed(&keys(&["a", "b", "c"]), "b");
        assert_eq!(k, keys(&["b", "a", "c"]));
        assert_eq!(note_viewed(&k, ""), k);
        let mut k = Vec::new();
        for i in 0..12 {
            k = note_viewed(&k, &format!("k{i}"));
        }
        assert_eq!(k.len(), VIEWED_KEEP);
        assert_eq!(k[0], "k11");
    }

    #[test]
    fn viewed_skips_missing_and_hidden() {
        let all = vec![c(1, "a", None), c(2, "b", None), c(3, "c", None)];
        let got: Vec<i64> = viewed(&keys(&["c", "zz", "b", "a"]), &all, &[2])
            .iter()
            .map(|c| c.id)
            .collect();
        assert_eq!(got, vec![3, 1]);
        assert_eq!(forget_viewed(&keys(&["a", "b"]), "a"), keys(&["b"]));
        assert!(CLEAR_VIEWED.0.ends_with('?'));
    }
}
