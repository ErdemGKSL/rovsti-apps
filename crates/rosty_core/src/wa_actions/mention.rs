//! `@` mentions in group messages.
//!
//! On the wire a mention is a token `@<user>` in the text (the digits of a phone number, or the user of a LID) plus
//! the JID in `contextInfo.mentioned_jid`. The composer shows `@Name` instead; [`encode`] turns the composer's text and
//! its picks into the wire form, [`spans`] finds the tokens of a received text so the app can draw the names.

use super::utf16::{byte_of, units_before};
use crate::wa_identity::{WaIdKind, parse};

/// The longest name query after `@` that still keeps the member list open.
const MAX_QUERY_CHARS: usize = 40;

/// The `@` the caret is in: where it is and what was typed after it.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MentionQuery {
    /// UTF-16 offset of the `@`.
    pub start: usize,
    /// What follows the `@` up to the caret (may be empty).
    pub query: String,
}

/// The mention the caret at UTF-16 offset `caret` is typing: an `@` at the start of the text or after a space, then
/// no space up to the caret. `None` otherwise.
#[must_use]
pub fn query_at(text: &str, caret: usize) -> Option<MentionQuery> {
    let end = byte_of(text, caret)?;
    let head = &text[..end];
    let at = head.rfind('@')?;
    let query = &head[at + 1..];
    if query.chars().any(char::is_whitespace) || query.chars().count() > MAX_QUERY_CHARS {
        return None;
    }
    if head[..at]
        .chars()
        .next_back()
        .is_some_and(|c| !c.is_whitespace())
    {
        return None;
    }
    Some(MentionQuery {
        start: units_before(text, at),
        query: query.to_owned(),
    })
}

/// The token that names `jid` in a text: the digits of a phone-number JID or the user of a LID. `None` for groups
/// and anything else.
#[must_use]
pub fn mention_token(jid: &str) -> Option<String> {
    let parsed = parse(jid)?;
    matches!(parsed.id.kind(), WaIdKind::Pn | WaIdKind::Lid).then(|| parsed.id.user().to_owned())
}

/// A member picked in the composer: the UTF-16 range of the `@Name` it inserted and who it names.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Pick {
    pub start: usize,
    pub end: usize,
    pub jid: String,
}

/// A text ready to send: the `@Name`s replaced by their tokens, and the JIDs to put in `mentioned_jid`.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Encoded {
    pub text: String,
    pub mentions: Vec<String>,
}

/// Replaces each pick's `@Name` in `text` by `@<token>` and lists the JIDs once each, in text order. A pick whose
/// range no longer starts with `@` (the person edited it), that overlaps an earlier one, falls inside a character or
/// names no person is dropped: its text stays as typed.
#[must_use]
pub fn encode(text: &str, picks: &[Pick]) -> Encoded {
    let mut picks: Vec<&Pick> = picks.iter().collect();
    picks.sort_by_key(|p| p.start);
    let mut out = String::with_capacity(text.len());
    let mut mentions: Vec<String> = Vec::new();
    let mut cursor = 0usize;
    for pick in picks {
        let (Some(start), Some(end)) = (byte_of(text, pick.start), byte_of(text, pick.end)) else {
            continue;
        };
        if start < cursor || end <= start || !text[start..end].starts_with('@') {
            continue;
        }
        let Some(token) = mention_token(&pick.jid) else {
            continue;
        };
        let jid = parse(&pick.jid).map_or_else(|| pick.jid.clone(), |p| p.id.bare());
        out.push_str(&text[cursor..start]);
        out.push('@');
        out.push_str(&token);
        cursor = end;
        if !mentions.contains(&jid) {
            mentions.push(jid);
        }
    }
    out.push_str(&text[cursor..]);
    Encoded {
        text: out,
        mentions,
    }
}

/// A person a received message mentions, with every token that may name them (the digits of each number and the
/// user of each LID the identity layer knows for them). The JID's own token always counts.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MentionTarget {
    pub jid: String,
    #[serde(default)]
    pub tokens: Vec<String>,
}

/// One `@<token>` of a received text: its UTF-16 range (the `@` included) and the person it names.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MentionSpan {
    pub start: usize,
    pub end: usize,
    pub jid: String,
}

/// The mentions in `text` of the people in `targets`, in text order. An `@` counts at the start or after a character
/// that is not a letter or digit (so an e-mail address is not a mention); the token is the run of digits after it and
/// must name one of the targets exactly.
#[must_use]
pub fn spans(text: &str, targets: &[MentionTarget]) -> Vec<MentionSpan> {
    let mut out = Vec::new();
    if targets.is_empty() {
        return out;
    }
    let mut prev: Option<char> = None;
    let mut iter = text.char_indices().peekable();
    while let Some((at, ch)) = iter.next() {
        let starts = ch == '@' && prev.is_none_or(|p| !p.is_alphanumeric());
        prev = Some(ch);
        if !starts {
            continue;
        }
        let from = at + 1;
        let mut to = from;
        while let Some(&(i, c)) = iter.peek() {
            if !c.is_ascii_digit() {
                break;
            }
            to = i + c.len_utf8();
            prev = Some(c);
            iter.next();
        }
        if to == from {
            continue;
        }
        let token = &text[from..to];
        let named = targets.iter().find(|t| {
            t.tokens.iter().any(|k| k == token) || mention_token(&t.jid).as_deref() == Some(token)
        });
        if let Some(target) = named {
            out.push(MentionSpan {
                start: units_before(text, at),
                end: units_before(text, to),
                jid: target.jid.clone(),
            });
        }
    }
    out
}

fn same_person(a: &str, b: &str) -> bool {
    match (parse(a), parse(b)) {
        (Some(a), Some(b)) => a.id == b.id,
        _ => false,
    }
}

/// Whether a message is about me: it mentions one of my ids (`own`: my number and my LID), or it quotes a message of
/// mine (`quoted_sender` is one of them). WhatsApp lets such a message through a muted group.
#[must_use]
pub fn addresses_me(mentions: &[String], quoted_sender: Option<&str>, own: &[String]) -> bool {
    let mine = |jid: &str| own.iter().any(|o| same_person(o, jid));
    mentions.iter().any(|m| mine(m)) || quoted_sender.is_some_and(mine)
}

#[cfg(test)]
mod tests {
    use super::*;

    const ANNA: &str = "100000000011@s.whatsapp.net";
    const LID_ONLY: &str = "123456789012345@lid";

    #[test]
    fn query_after_at_sign() {
        assert_eq!(
            query_at("hi @an", 6),
            Some(MentionQuery {
                start: 3,
                query: "an".to_owned()
            })
        );
        assert_eq!(
            query_at("@", 1),
            Some(MentionQuery {
                start: 0,
                query: String::new()
            })
        );
        // An e-mail address, a space after the query, and a caret before the `@`.
        assert_eq!(query_at("me@home", 7), None);
        assert_eq!(query_at("hi @an x", 8), None);
        assert_eq!(query_at("hi @an", 2), None);
        // UTF-16 offsets after an emoji.
        assert_eq!(query_at("😀 @b", 5).map(|q| q.start), Some(3));
    }

    #[test]
    fn encode_replaces_names_by_tokens() {
        let text = "Hi @Anna and @Ö Lid!";
        let picks = [
            Pick {
                start: 3,
                end: 8,
                jid: ANNA.to_owned(),
            },
            Pick {
                start: 13,
                end: 19,
                jid: LID_ONLY.to_owned(),
            },
        ];
        let encoded = encode(text, &picks);
        assert_eq!(encoded.text, "Hi @100000000011 and @123456789012345!");
        assert_eq!(encoded.mentions, vec![ANNA.to_owned(), LID_ONLY.to_owned()]);
    }

    #[test]
    fn encode_drops_edited_overlapping_and_foreign_picks() {
        let text = "Anna and @Bo";
        let picks = [
            // No longer starts with `@`.
            Pick {
                start: 0,
                end: 4,
                jid: ANNA.to_owned(),
            },
            // A group is never a mention.
            Pick {
                start: 9,
                end: 12,
                jid: "120363000000000001@g.us".to_owned(),
            },
            // Past the end.
            Pick {
                start: 9,
                end: 40,
                jid: ANNA.to_owned(),
            },
        ];
        let encoded = encode(text, &picks);
        assert_eq!(encoded.text, text);
        assert!(encoded.mentions.is_empty());
    }

    #[test]
    fn encode_lists_a_person_once_with_device_dropped() {
        let text = "@A @A";
        let device = "100000000011:3@s.whatsapp.net";
        let picks = [
            Pick {
                start: 0,
                end: 2,
                jid: device.to_owned(),
            },
            Pick {
                start: 3,
                end: 5,
                jid: ANNA.to_owned(),
            },
        ];
        let encoded = encode(text, &picks);
        assert_eq!(encoded.text, "@100000000011 @100000000011");
        assert_eq!(encoded.mentions, vec![ANNA.to_owned()]);
    }

    #[test]
    fn spans_match_every_alias_token() {
        // The gateway resolved the LID mention to the number, but the text still carries the LID user.
        let text = "ping @123456789012345 and @100000000011, not me@100000000011";
        let targets = [MentionTarget {
            jid: ANNA.to_owned(),
            tokens: vec!["123456789012345".to_owned()],
        }];
        let found = spans(text, &targets);
        assert_eq!(found.len(), 2);
        assert_eq!((found[0].start, found[0].end), (5, 21));
        assert_eq!((found[1].start, found[1].end), (26, 39));
        assert!(found.iter().all(|s| s.jid == ANNA));
    }

    #[test]
    fn spans_of_a_lid_only_member_and_unknown_tokens() {
        let text = "@123456789012345 @999";
        let targets = [MentionTarget {
            jid: LID_ONLY.to_owned(),
            tokens: Vec::new(),
        }];
        let found = spans(text, &targets);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].jid, LID_ONLY);
        assert!(spans(text, &[]).is_empty());
    }

    #[test]
    fn spans_count_utf16() {
        let text = "😀 @100000000011";
        let targets = [MentionTarget {
            jid: ANNA.to_owned(),
            tokens: Vec::new(),
        }];
        let found = spans(text, &targets);
        assert_eq!((found[0].start, found[0].end), (3, 16));
    }

    #[test]
    fn about_me_by_mention_or_quote() {
        let own = vec![
            "100000000099@s.whatsapp.net".to_owned(),
            "555@lid".to_owned(),
        ];
        assert!(addresses_me(&["555:2@lid".to_owned()], None, &own));
        assert!(addresses_me(&[], Some("100000000099@s.whatsapp.net"), &own));
        assert!(!addresses_me(&[ANNA.to_owned()], Some(ANNA), &own));
        assert!(!addresses_me(&[ANNA.to_owned()], None, &[]));
    }
}
