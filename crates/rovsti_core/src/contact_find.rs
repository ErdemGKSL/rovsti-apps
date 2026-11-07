//! Search in contact details: company, job title, nickname, note, address,
//! email and website. Names and numbers are covered by [`crate::search`]; this
//! index backs the "In details" section under them.

use crate::contact_edit::ContactDraft;
use crate::search::fold;

/// Longest snippet shown under a hit, ellipses included.
const SNIPPET_CHARS: usize = 48;
/// Shortest query that searches the details.
const MIN_QUERY_CHARS: usize = 2;

/// The detail a query matched, in matching order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DeepField {
    Company,
    JobTitle,
    Nickname,
    Note,
    Address,
    Email,
    Website,
}

impl DeepField {
    pub fn label(self) -> &'static str {
        match self {
            Self::Company => "Company",
            Self::JobTitle => "Job title",
            Self::Nickname => "Nickname",
            Self::Note => "Note",
            Self::Address => "Address",
            Self::Email => "Email",
            Self::Website => "Website",
        }
    }
}

/// One contact whose details matched.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DeepHit {
    pub id: i64,
    pub field: DeepField,
    pub snippet: String,
}

struct Entry {
    field: DeepField,
    /// Original text with line breaks turned into spaces.
    text: String,
    folded: String,
}

struct Row {
    id: i64,
    entries: Vec<Entry>,
}

/// Folded detail texts of all contacts, built once per contacts payload.
pub struct DeepIndex {
    rows: Vec<Row>,
}

impl DeepIndex {
    /// Contacts without an id are skipped.
    pub fn new(drafts: &[ContactDraft]) -> Self {
        let rows = drafts
            .iter()
            .filter_map(|d| {
                let id = d.id?;
                let mut entries = Vec::new();
                let mut add = |field: DeepField, value: &str| {
                    let text: String = value
                        .trim()
                        .chars()
                        .map(|c| if c.is_control() { ' ' } else { c })
                        .collect();
                    if !text.is_empty() {
                        let folded = fold(&text);
                        entries.push(Entry {
                            field,
                            text,
                            folded,
                        });
                    }
                };
                add(DeepField::Company, &d.org);
                add(DeepField::JobTitle, &d.title);
                add(DeepField::Nickname, &d.nickname);
                add(DeepField::Note, &d.note);
                for a in &d.addresses {
                    add(DeepField::Address, &a.value);
                }
                for e in &d.emails {
                    add(DeepField::Email, &e.value);
                }
                for w in &d.websites {
                    add(DeepField::Website, w);
                }
                (!entries.is_empty()).then_some(Row { id, entries })
            })
            .collect();
        Self { rows }
    }

    /// Contacts whose details contain `query`, at most `limit`, one hit per
    /// contact (the first matching field). `skip` holds the ids already shown
    /// by name. Queries under 2 characters and digit-only queries (numbers are
    /// searched elsewhere) find nothing.
    pub fn search(&self, query: &str, skip: &[i64], limit: usize) -> Vec<DeepHit> {
        let query = query.trim();
        if query.chars().count() < MIN_QUERY_CHARS || is_digits_only(query) {
            return Vec::new();
        }
        let needle = fold(query);
        if needle.is_empty() {
            return Vec::new();
        }
        let mut hits = Vec::new();
        for row in &self.rows {
            if hits.len() >= limit {
                break;
            }
            if skip.contains(&row.id) {
                continue;
            }
            let found = row
                .entries
                .iter()
                .find_map(|e| e.folded.find(&needle).map(|at| (e, at)));
            if let Some((entry, at)) = found {
                hits.push(DeepHit {
                    id: row.id,
                    field: entry.field,
                    snippet: snippet(&entry.text, at, needle.len()),
                });
            }
        }
        hits
    }
}

fn is_digits_only(query: &str) -> bool {
    query.chars().any(|c| c.is_ascii_digit())
        && query
            .chars()
            .all(|c| c.is_ascii_digit() || c.is_whitespace() || "+-().".contains(c))
}

/// Up to [`SNIPPET_CHARS`] characters of `text` around the folded byte range
/// `at..at + len`, with `…` where text was cut.
fn snippet(text: &str, at: usize, len: usize) -> String {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    if n <= SNIPPET_CHARS {
        return text.to_owned();
    }
    // Folding is per character, so walk both strings together to turn the
    // folded byte offsets back into character positions.
    let (mut start, mut end) = (n, n);
    let mut folded_bytes = 0;
    let mut buf = String::new();
    for (i, c) in chars.iter().enumerate() {
        if folded_bytes == at {
            start = i;
        }
        if folded_bytes >= at + len {
            end = i;
            break;
        }
        buf.clear();
        buf.push(*c);
        folded_bytes += fold(&buf).len();
    }
    let start = start.min(n);
    let end = end.max(start);
    let window = SNIPPET_CHARS - 2;
    let hit = (end - start).min(window);
    let from = start
        .saturating_sub((window - hit) / 2)
        .min(n.saturating_sub(window));
    let to = (from + window).min(n);
    let mut out = String::new();
    if from > 0 {
        out.push('…');
    }
    out.extend(&chars[from..to]);
    if to < n {
        out.push('…');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::contact_edit::{DraftField, FieldKind};

    fn draft(id: i64) -> ContactDraft {
        ContactDraft {
            id: Some(id),
            given: "Name".into(),
            ..ContactDraft::default()
        }
    }

    fn one(d: ContactDraft, q: &str) -> Option<DeepHit> {
        DeepIndex::new(&[d]).search(q, &[], 10).pop()
    }

    #[test]
    fn finds_each_field() {
        let mut d = draft(1);
        d.org = "Acme Corp".into();
        assert_eq!(one(d, "acme").map(|h| h.field), Some(DeepField::Company));
        let mut d = draft(1);
        d.title = "Plumber".into();
        assert_eq!(one(d, "plum").map(|h| h.field), Some(DeepField::JobTitle));
        let mut d = draft(1);
        d.nickname = "Chief".into();
        assert_eq!(one(d, "chie").map(|h| h.field), Some(DeepField::Nickname));
        let mut d = draft(1);
        d.note = "met at the fair".into();
        assert_eq!(one(d, "fair").map(|h| h.field), Some(DeepField::Note));
        let mut d = draft(1);
        d.addresses = vec![DraftField::new("5 Main Street\nParis", FieldKind::Home)];
        let hit = one(d, "paris").expect("hit");
        assert_eq!(hit.field, DeepField::Address);
        assert_eq!(hit.snippet, "5 Main Street Paris");
        let mut d = draft(1);
        d.emails = vec![DraftField::new("ayse@example.com", FieldKind::Home)];
        assert_eq!(one(d, "example").map(|h| h.field), Some(DeepField::Email));
        let mut d = draft(1);
        d.websites = vec!["https://rovsti.dev".into()];
        assert_eq!(one(d, "rovsti").map(|h| h.field), Some(DeepField::Website));
        assert_eq!(DeepField::JobTitle.label(), "Job title");
    }

    #[test]
    fn first_field_wins() {
        let mut d = draft(1);
        d.org = "Rovsti".into();
        d.note = "rovsti friend".into();
        assert_eq!(one(d, "rovsti").map(|h| h.field), Some(DeepField::Company));
    }

    #[test]
    fn turkish_folding() {
        let mut d = draft(1);
        d.org = "IŞÇI Birliği".into();
        let hit = one(d, "işçi").expect("hit");
        assert_eq!(hit.snippet, "IŞÇI Birliği");
    }

    #[test]
    fn skip_and_limit() {
        let drafts: Vec<ContactDraft> = (1..=4)
            .map(|i| ContactDraft {
                org: "Acme".into(),
                ..draft(i)
            })
            .collect();
        let index = DeepIndex::new(&drafts);
        let ids = |hits: Vec<DeepHit>| hits.iter().map(|h| h.id).collect::<Vec<_>>();
        assert_eq!(ids(index.search("acme", &[1, 3], 10)), vec![2, 4]);
        assert_eq!(ids(index.search("acme", &[], 2)), vec![1, 2]);
        assert!(index.search("acme", &[], 0).is_empty());
    }

    #[test]
    fn short_and_digit_queries_are_empty() {
        let mut d = draft(1);
        d.org = "A1 Cabs 24".into();
        d.note = "call 555 1234".into();
        let index = DeepIndex::new(&[d]);
        assert!(index.search("a", &[], 10).is_empty());
        assert!(index.search(" a ", &[], 10).is_empty());
        assert!(index.search("24", &[], 10).is_empty());
        assert!(index.search("555 12", &[], 10).is_empty());
        assert!(index.search("+90", &[], 10).is_empty());
        assert_eq!(index.search("a1", &[], 10).len(), 1);
    }

    #[test]
    fn drafts_without_id_are_skipped() {
        let mut d = draft(1);
        d.id = None;
        d.org = "Acme".into();
        assert!(DeepIndex::new(&[d]).search("acme", &[], 10).is_empty());
    }

    #[test]
    fn snippet_is_short_and_emoji_safe() {
        let mut d = draft(1);
        d.note = format!("{} needle {}", "😀é".repeat(30), "🎉".repeat(30));
        let hit = one(d, "needle").expect("hit");
        assert!(hit.snippet.chars().count() <= SNIPPET_CHARS);
        assert!(hit.snippet.contains("needle"));
        assert!(hit.snippet.starts_with('…') && hit.snippet.ends_with('…'));
    }

    #[test]
    fn snippet_edges() {
        let mut d = draft(1);
        d.note = format!("needle {}", "x".repeat(100));
        let hit = one(d, "needle").expect("hit");
        assert!(hit.snippet.starts_with("needle"));
        assert!(hit.snippet.ends_with('…'));
        assert!(hit.snippet.chars().count() <= SNIPPET_CHARS);
        let mut d = draft(1);
        d.note = format!("{} needle", "x".repeat(100));
        let hit = one(d, "needle").expect("hit");
        assert!(hit.snippet.starts_with('…'));
        assert!(hit.snippet.ends_with("needle"));
        assert!(hit.snippet.chars().count() <= SNIPPET_CHARS);
    }

    #[test]
    fn snippet_with_multi_char_fold() {
        let mut d = draft(1);
        d.note = format!("{} straße {}", "ß".repeat(40), "y".repeat(40));
        let hit = one(d, "strasse").expect("hit");
        assert!(hit.snippet.contains("straße"));
    }
}
