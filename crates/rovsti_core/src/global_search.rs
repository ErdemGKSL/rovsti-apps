//! Pure global message search: folded matching, snippets with a highlight range.

use crate::search::fold;
use std::ops::Range;

/// Queries shorter than this (in chars, trimmed) find nothing.
pub const MIN_QUERY_CHARS: usize = 2;
/// Upper bound of returned hits.
pub const MAX_HITS: usize = 200;

const CONTEXT_CHARS: usize = 30;
const SNIPPET_CHARS: usize = 90;

/// One message offered to the search.
pub struct SearchCandidate<'a> {
    pub chat_key: &'a str,
    pub chat_title: &'a str,
    pub message_id: &'a str,
    pub author: Option<&'a str>,
    pub text: &'a str,
    pub at_ms: i64,
}

/// A matching message with a one-line snippet.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SearchHit {
    pub chat_key: String,
    pub chat_title: String,
    pub message_id: String,
    pub at_ms: i64,
    pub snippet: String,
    /// Byte range of the match inside `snippet`.
    pub highlight: Range<usize>,
}

/// Collapses all whitespace runs to single spaces and trims.
fn one_line(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Folds `text` char by char; returns the folded string plus, for each
/// original char, its folded byte range start (with a final end sentinel).
fn fold_with_map(text: &str) -> (String, Vec<usize>) {
    let mut folded = String::with_capacity(text.len());
    let mut starts = Vec::with_capacity(text.len() + 1);
    let mut buf = [0u8; 4];
    for c in text.chars() {
        starts.push(folded.len());
        folded.push_str(&fold(c.encode_utf8(&mut buf)));
    }
    starts.push(folded.len());
    (folded, starts)
}

/// Finds `needle` (already folded) in `text`; returns the matched char range.
fn find_chars(text: &str, needle: &str) -> Option<Range<usize>> {
    let (folded, starts) = fold_with_map(text);
    let at = folded.find(needle)?;
    let end = at + needle.len();
    let first = starts.partition_point(|&s| s <= at).saturating_sub(1);
    let last = starts.partition_point(|&s| s < end);
    Some(first..last.min(starts.len() - 1).max(first + 1))
}

fn snippet(text: &str, m: Range<usize>) -> (String, Range<usize>) {
    let chars: Vec<char> = text.chars().collect();
    let mut from = m.start.saturating_sub(CONTEXT_CHARS);
    let lead = from > 0;
    let budget = SNIPPET_CHARS - usize::from(lead);
    if m.end - from > budget {
        from = m.end - budget.min(m.end - m.start);
    }
    let lead = from > 0;
    let budget = SNIPPET_CHARS - usize::from(lead);
    let to = (from + budget).min(chars.len());
    let mut out = String::new();
    if lead {
        out.push('…');
    }
    let hs_chars = m.start.max(from);
    out.extend(&chars[from..hs_chars]);
    let hs = out.len();
    let he_chars = m.end.min(to).max(hs_chars);
    out.extend(&chars[hs_chars..he_chars]);
    let he = out.len();
    out.extend(&chars[he_chars..to]);
    (out, hs..he)
}

/// Messages containing the folded query, newest first, at most [`MAX_HITS`].
pub fn search(query: &str, items: &[SearchCandidate<'_>]) -> Vec<SearchHit> {
    let q = query.trim();
    if q.chars().count() < MIN_QUERY_CHARS {
        return Vec::new();
    }
    let needle = fold(&one_line(q));
    if needle.is_empty() {
        return Vec::new();
    }
    let mut order: Vec<&SearchCandidate<'_>> = items.iter().collect();
    order.sort_by_key(|c| std::cmp::Reverse(c.at_ms));
    let mut hits = Vec::new();
    for c in order {
        let line = one_line(c.text);
        let Some(m) = find_chars(&line, &needle) else {
            continue;
        };
        let (snippet, highlight) = snippet(&line, m);
        hits.push(SearchHit {
            chat_key: c.chat_key.to_owned(),
            chat_title: c.chat_title.to_owned(),
            message_id: c.message_id.to_owned(),
            at_ms: c.at_ms,
            snippet,
            highlight,
        });
        if hits.len() == MAX_HITS {
            break;
        }
    }
    hits
}

/// `%query%` for a SQL `LIKE ... ESCAPE '\'`, with `%`, `_` and `\` escaped.
pub fn like_pattern(query: &str) -> String {
    let mut out = String::with_capacity(query.len() + 2);
    out.push('%');
    for c in query.trim().chars() {
        if matches!(c, '%' | '_' | '\\') {
            out.push('\\');
        }
        out.push(c);
    }
    out.push('%');
    out
}

/// Result count line: "3 results", "1 result", "No results"; partial adds a note.
pub fn summary(hits: usize, partial: bool) -> String {
    let base = match hits {
        0 => "No results".to_owned(),
        1 => "1 result".to_owned(),
        n if n >= MAX_HITS => format!("{MAX_HITS}+ results"),
        n => format!("{n} results"),
    };
    if partial {
        format!("{base} in recent chats")
    } else {
        base
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cand<'a>(id: &'a str, text: &'a str, at_ms: i64) -> SearchCandidate<'a> {
        SearchCandidate {
            chat_key: "c",
            chat_title: "Chat",
            message_id: id,
            author: None,
            text,
            at_ms,
        }
    }

    #[test]
    fn short_query_is_empty() {
        assert!(search("a", &[cand("1", "abc", 1)]).is_empty());
        assert!(search("  ", &[cand("1", "abc", 1)]).is_empty());
    }

    #[test]
    fn folds_case_and_diacritics_newest_first() {
        let items = [cand("old", "Café au lait", 1), cand("new", "CAFE", 9)];
        let hits = search("café", &items);
        let ids: Vec<_> = hits.iter().map(|h| h.message_id.as_str()).collect();
        assert_eq!(ids, ["new", "old"]);
        let h = &hits[1];
        assert_eq!(&h.snippet[h.highlight.clone()], "Café");
    }

    #[test]
    fn snippet_highlight_on_multibyte_text() {
        let text = format!("{} ünïcode ßtraße tail {}", "ö".repeat(50), "ğ".repeat(80));
        let hits = search("strasse", &[cand("1", &text, 1)]);
        assert_eq!(hits.len(), 1);
        let h = &hits[0];
        assert!(h.snippet.starts_with('…'));
        assert!(h.snippet.chars().count() <= SNIPPET_CHARS);
        assert_eq!(&h.snippet[h.highlight.clone()], "ßtraße");
    }

    #[test]
    fn snippet_is_one_line_and_short() {
        let hits = search("end", &[cand("1", "line one\n\nline  two end", 1)]);
        assert_eq!(hits[0].snippet, "line one line two end");
        let long = format!("{}needle{}", "a".repeat(200), "b".repeat(200));
        let h = &search("needle", &[cand("1", &long, 1)])[0];
        assert!(h.snippet.chars().count() <= SNIPPET_CHARS);
        assert_eq!(&h.snippet[h.highlight.clone()], "needle");
    }

    #[test]
    fn caps_hits() {
        let texts: Vec<String> = (0..300).map(|i| format!("hello {i}")).collect();
        let items: Vec<_> = texts
            .iter()
            .enumerate()
            .map(|(i, t)| cand("x", t, i as i64))
            .collect();
        let hits = search("hello", &items);
        assert_eq!(hits.len(), MAX_HITS);
        assert_eq!(hits[0].at_ms, 299);
    }

    #[test]
    fn like_pattern_escapes() {
        assert_eq!(like_pattern(" 100%_a\\b "), "%100\\%\\_a\\\\b%");
    }

    #[test]
    fn summaries() {
        assert_eq!(summary(0, false), "No results");
        assert_eq!(summary(1, false), "1 result");
        assert_eq!(summary(3, true), "3 results in recent chats");
        assert_eq!(summary(MAX_HITS, false), "200+ results");
    }
}
