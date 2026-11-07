//! Quick reply templates: the editable list behind the reply chips.

use crate::search::fold;

pub const MAX_REPLIES: usize = 20;
pub const MAX_REPLY_CHARS: usize = 500;
const CHIP_CHARS: usize = 28;

/// Why a reply could not be added.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum ReplyError {
    #[error("Type a reply first")]
    Empty,
    #[error("Keep it under 500 characters")]
    TooLong,
    #[error("That reply is already in the list")]
    Duplicate,
    #[error("The list is full (20 replies)")]
    Full,
}

/// The replies a fresh install starts with.
pub fn defaults() -> Vec<String> {
    [
        "On my way.",
        "Can I call you later?",
        "I'm driving, I'll reply soon.",
        "Thanks!",
        "Running 10 minutes late.",
    ]
    .iter()
    .map(|s| (*s).to_string())
    .collect()
}

/// Appends a trimmed reply and returns its index.
pub fn add(list: &mut Vec<String>, text: &str) -> Result<usize, ReplyError> {
    let text = text.trim();
    if text.is_empty() {
        return Err(ReplyError::Empty);
    }
    if text.chars().count() > MAX_REPLY_CHARS {
        return Err(ReplyError::TooLong);
    }
    let key = fold(text);
    if list.iter().any(|r| fold(r) == key) {
        return Err(ReplyError::Duplicate);
    }
    if list.len() >= MAX_REPLIES {
        return Err(ReplyError::Full);
    }
    list.push(text.to_string());
    Ok(list.len() - 1)
}

/// Removes the reply at `index`, returning it for Undo.
pub fn remove(list: &mut Vec<String>, index: usize) -> Option<String> {
    (index < list.len()).then(|| list.remove(index))
}

/// Puts a removed reply back (index clamped); no-op when already present.
pub fn restore(list: &mut Vec<String>, index: usize, text: String) {
    if list.contains(&text) || list.len() >= MAX_REPLIES {
        return;
    }
    list.insert(index.min(list.len()), text);
}

/// Swaps the reply with the one above it; false when it cannot move.
pub fn move_up(list: &mut [String], index: usize) -> bool {
    if index == 0 || index >= list.len() {
        return false;
    }
    list.swap(index - 1, index);
    true
}

/// Draft text after tapping a reply chip.
pub fn insert_into(draft: &str, reply: &str) -> String {
    if draft.trim().is_empty() {
        reply.to_string()
    } else {
        format!("{} {}", draft.trim_end(), reply)
    }
}

/// One-line chip text, shortened with an ellipsis.
pub fn chip_label(reply: &str) -> String {
    let line = reply.split_whitespace().collect::<Vec<_>>().join(" ");
    if line.chars().count() <= CHIP_CHARS {
        return line;
    }
    let mut out: String = line.chars().take(CHIP_CHARS).collect();
    out.push('…');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_trims_and_rejects_blank() {
        let mut l = Vec::new();
        assert_eq!(add(&mut l, "  hi  "), Ok(0));
        assert_eq!(l, vec!["hi"]);
        assert_eq!(add(&mut l, "   "), Err(ReplyError::Empty));
        let long = "a".repeat(MAX_REPLY_CHARS + 1);
        assert_eq!(add(&mut l, &long), Err(ReplyError::TooLong));
        assert_eq!(ReplyError::Empty.to_string(), "Type a reply first");
    }

    #[test]
    fn add_rejects_duplicates_folded() {
        let mut l = defaults();
        assert_eq!(add(&mut l, " THANKS! "), Err(ReplyError::Duplicate));
        assert_eq!(l.len(), 5);
    }

    #[test]
    fn add_stops_when_full() {
        let mut l: Vec<String> = (0..MAX_REPLIES).map(|i| format!("r{i}")).collect();
        assert_eq!(add(&mut l, "extra"), Err(ReplyError::Full));
    }

    #[test]
    fn remove_then_restore_round_trips() {
        let mut l = defaults();
        let before = l.clone();
        let t = remove(&mut l, 2).expect("index in range");
        assert_eq!(l.len(), 4);
        restore(&mut l, 2, t.clone());
        assert_eq!(l, before);
        restore(&mut l, 2, t);
        assert_eq!(l, before);
        assert_eq!(remove(&mut l, 99), None);
        restore(&mut l, 99, "new".into());
        assert_eq!(l.last().map(String::as_str), Some("new"));
    }

    #[test]
    fn move_up_swaps() {
        let mut l = vec!["a".to_string(), "b".into(), "c".into()];
        assert!(move_up(&mut l, 2));
        assert_eq!(l, vec!["a", "c", "b"]);
        assert!(!move_up(&mut l, 0));
        assert!(!move_up(&mut l, 3));
    }

    #[test]
    fn insert_appends_with_space() {
        assert_eq!(insert_into("", "Thanks!"), "Thanks!");
        assert_eq!(insert_into("  ", "Thanks!"), "Thanks!");
        assert_eq!(insert_into("Hi ", "Thanks!"), "Hi Thanks!");
    }

    #[test]
    fn chip_label_shortens() {
        assert_eq!(chip_label("Thanks!"), "Thanks!");
        let l = chip_label("I'm driving, I'll reply to you very soon.");
        assert_eq!(l.chars().count(), 29);
        assert!(l.ends_with('…'));
        assert_eq!(chip_label("a\nb"), "a b");
    }
}
