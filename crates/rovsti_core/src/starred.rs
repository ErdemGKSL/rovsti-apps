//! Rows for the starred-messages list.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

const PREVIEW_CHARS: usize = 120;

/// Most local stars kept; the oldest messages fall out first.
pub const MAX_LOCAL_STARS: usize = 1000;

/// A message as the starred list sees it.
#[derive(Clone, Copy, Debug)]
pub struct StarCandidate<'a> {
    pub id: &'a str,
    pub author: &'a str,
    pub preview: &'a str,
    pub at_ms: i64,
    pub starred: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StarredRow {
    pub id: String,
    pub author: String,
    pub preview: String,
    pub at_ms: i64,
}

/// Starred messages only, newest first, with one-line previews.
pub fn starred_rows(items: &[StarCandidate<'_>]) -> Vec<StarredRow> {
    let mut rows: Vec<StarredRow> = items
        .iter()
        .filter(|c| c.starred)
        .map(|c| StarredRow {
            id: c.id.to_string(),
            author: c.author.to_string(),
            preview: one_line(c.preview),
            at_ms: c.at_ms,
        })
        .collect();
    rows.sort_by_key(|r| std::cmp::Reverse(r.at_ms));
    rows
}

fn one_line(s: &str) -> String {
    let line = s.split_whitespace().collect::<Vec<_>>().join(" ");
    if line.chars().count() <= PREVIEW_CHARS {
        return line;
    }
    let mut out: String = line.chars().take(PREVIEW_CHARS).collect();
    out.push('…');
    out
}

/// A message starred on this device (SMS/MMS have no provider star).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct LocalStar {
    pub thread_id: String,
    pub at_ms: i64,
    pub author: String,
    pub preview: String,
}

/// Local stars by provider id ("sms:12").
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct LocalStars(BTreeMap<String, LocalStar>);

impl LocalStars {
    pub fn is_starred(&self, id: &str) -> bool {
        self.0.contains_key(id)
    }

    /// Stars (`Some`) or unstars (`None`) a message; evicts the oldest star past
    /// [`MAX_LOCAL_STARS`]. Returns true when anything changed.
    pub fn set(&mut self, id: &str, star: Option<LocalStar>) -> bool {
        match star {
            None => self.0.remove(id).is_some(),
            Some(star) => {
                if self.0.get(id) == Some(&star) {
                    return false;
                }
                self.0.insert(id.to_string(), star);
                while self.0.len() > MAX_LOCAL_STARS {
                    let oldest = self
                        .0
                        .iter()
                        .min_by_key(|(k, v)| (v.at_ms, (*k).clone()))
                        .map(|(k, _)| k.clone());
                    match oldest {
                        Some(k) => self.0.remove(&k),
                        None => break,
                    };
                }
                true
            }
        }
    }

    /// Rows of one thread, newest first.
    pub fn rows_for(&self, thread_id: &str) -> Vec<StarredRow> {
        let mut rows: Vec<StarredRow> = self
            .0
            .iter()
            .filter(|(_, s)| s.thread_id == thread_id)
            .map(|(id, s)| StarredRow {
                id: id.clone(),
                author: s.author.clone(),
                preview: one_line(&s.preview),
                at_ms: s.at_ms,
            })
            .collect();
        rows.sort_by_key(|r| std::cmp::Reverse(r.at_ms));
        rows
    }

    pub fn count_for(&self, thread_id: &str) -> usize {
        self.0.values().filter(|s| s.thread_id == thread_id).count()
    }

    /// Drops every star of a deleted thread; returns how many.
    pub fn forget_thread(&mut self, thread_id: &str) -> usize {
        let before = self.0.len();
        self.0.retain(|_, s| s.thread_id != thread_id);
        before - self.0.len()
    }

    /// Drops one star (a deleted message); true when it existed.
    pub fn forget(&mut self, id: &str) -> bool {
        self.0.remove(id).is_some()
    }

    pub fn len(&self) -> usize {
        self.0.len()
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

/// Empty-state text; `partial` when only part of the chat is loaded.
pub fn empty_text(partial: bool) -> &'static str {
    if partial {
        "No starred messages in the loaded part of this chat"
    } else {
        "No starred messages"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c<'a>(id: &'a str, preview: &'a str, at_ms: i64, starred: bool) -> StarCandidate<'a> {
        StarCandidate {
            id,
            author: "Ann",
            preview,
            at_ms,
            starred,
        }
    }

    #[test]
    fn keeps_starred_newest_first() {
        let items = [
            c("a", "x", 1, true),
            c("b", "y", 2, false),
            c("c", "z", 3, true),
        ];
        let ids: Vec<_> = starred_rows(&items).into_iter().map(|r| r.id).collect();
        assert_eq!(ids, ["c", "a"]);
    }

    #[test]
    fn preview_is_one_line_and_short() {
        let long = format!("hello\n\nworld {}", "x".repeat(200));
        let rows = starred_rows(&[c("a", &long, 1, true)]);
        assert!(rows[0].preview.starts_with("hello world x"));
        assert!(!rows[0].preview.contains('\n'));
        assert_eq!(rows[0].preview.chars().count(), 121);
        assert!(rows[0].preview.ends_with('…'));
    }

    #[test]
    fn empty_text_partial() {
        assert_eq!(empty_text(false), "No starred messages");
        assert_eq!(
            empty_text(true),
            "No starred messages in the loaded part of this chat"
        );
    }

    fn ls(thread: &str, at_ms: i64, preview: &str) -> LocalStar {
        LocalStar {
            thread_id: thread.into(),
            at_ms,
            author: "Ann".into(),
            preview: preview.into(),
        }
    }

    #[test]
    fn local_stars_set_and_unset() {
        let mut s = LocalStars::default();
        assert!(s.set("sms:1", Some(ls("t1", 1, "a"))));
        assert!(!s.set("sms:1", Some(ls("t1", 1, "a"))));
        assert!(s.is_starred("sms:1"));
        assert!(s.set("sms:1", None));
        assert!(!s.set("sms:1", None));
        assert!(s.is_empty());
    }

    #[test]
    fn local_stars_rows_newest_first_one_line() {
        let mut s = LocalStars::default();
        s.set(
            "sms:1",
            Some(ls(
                "t1", 1, "a

b",
            )),
        );
        s.set("sms:2", Some(ls("t1", 5, "c")));
        s.set("sms:3", Some(ls("t2", 9, "d")));
        let rows = s.rows_for("t1");
        assert_eq!(rows[0].id, "sms:2");
        assert_eq!(rows[1].preview, "a b");
        assert_eq!(s.count_for("t1"), 2);
        assert_eq!(s.count_for("none"), 0);
    }

    #[test]
    fn local_stars_evict_oldest_past_cap() {
        let mut s = LocalStars::default();
        for i in 0..MAX_LOCAL_STARS as i64 {
            s.set(&format!("sms:{i}"), Some(ls("t", i + 10, "x")));
        }
        assert_eq!(s.len(), MAX_LOCAL_STARS);
        s.set("sms:new", Some(ls("t", 5_000, "x")));
        assert_eq!(s.len(), MAX_LOCAL_STARS);
        assert!(!s.is_starred("sms:0"));
        assert!(s.is_starred("sms:1") && s.is_starred("sms:new"));
        // A star older than everything is itself the one evicted.
        s.set("sms:old", Some(ls("t", 1, "x")));
        assert!(!s.is_starred("sms:old"));
    }

    #[test]
    fn local_stars_forget() {
        let mut s = LocalStars::default();
        s.set("sms:1", Some(ls("t1", 1, "a")));
        s.set("sms:2", Some(ls("t1", 2, "b")));
        s.set("sms:3", Some(ls("t2", 3, "c")));
        assert!(s.forget("sms:3"));
        assert!(!s.forget("sms:3"));
        assert_eq!(s.forget_thread("t1"), 2);
        assert!(s.is_empty());
    }

    #[test]
    fn local_stars_serde_round_trip_is_transparent() {
        let mut s = LocalStars::default();
        s.set("sms:1", Some(ls("t1", 1, "a")));
        let json = serde_json::to_string(&s).expect("serialize");
        assert!(json.starts_with("{\"sms:1\":{"));
        let back: LocalStars = serde_json::from_str(&json).expect("parse");
        assert_eq!(back, s);
    }
}
