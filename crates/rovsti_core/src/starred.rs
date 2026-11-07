//! Rows for the starred-messages list.

const PREVIEW_CHARS: usize = 120;

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
}
