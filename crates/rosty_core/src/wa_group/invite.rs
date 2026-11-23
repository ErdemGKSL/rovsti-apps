//! Group invite links: `https://chat.whatsapp.com/<code>` (also `chat.whatsapp.com/invite/<code>`, without a scheme,
//! and `whatsapp://chat?code=<code>`), or a bare code. The library reads the same forms
//! (`whatsapp-rust` `features/groups.rs` `extract_invite_code`); this also finds a link inside a message and refuses
//! what is not a code.

use serde::Serialize;

/// The host of invite links.
pub const INVITE_HOST: &str = "chat.whatsapp.com";

/// A code is letters and digits; WhatsApp's are 20 to 24 long today, the bounds leave room.
const MIN_CODE: usize = 8;
const MAX_CODE: usize = 64;

fn is_code(code: &str) -> bool {
    (MIN_CODE..=MAX_CODE).contains(&code.len()) && code.bytes().all(|b| b.is_ascii_alphanumeric())
}

/// The invite code of a link or a bare code; `None` for anything else (another host, an empty or malformed code).
#[must_use]
pub fn invite_code(text: &str) -> Option<String> {
    let text = text.trim();
    if is_code(text) {
        return Some(text.to_owned());
    }
    if let Some(rest) = text.strip_prefix("whatsapp://chat") {
        let query = rest.split_once('?')?.1;
        let code = query
            .split('&')
            .find_map(|pair| pair.strip_prefix("code="))?;
        return is_code(code).then(|| code.to_owned());
    }
    let rest = text
        .strip_prefix("https://")
        .or_else(|| text.strip_prefix("http://"))
        .unwrap_or(text);
    let rest = rest.strip_prefix("www.").unwrap_or(rest);
    let path = rest.strip_prefix(INVITE_HOST)?.strip_prefix('/')?;
    let path = path.strip_prefix("invite/").unwrap_or(path);
    let code = path
        .split(['?', '#'])
        .next()
        .unwrap_or_default()
        .trim_end_matches('/');
    is_code(code).then(|| code.to_owned())
}

/// The link of a code.
#[must_use]
pub fn invite_link(code: &str) -> String {
    format!("https://{INVITE_HOST}/{code}")
}

/// An invite link found in a text.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct InviteMatch {
    pub code: String,
    /// Where the link is, in UTF-16 units (as Kotlin strings count).
    pub start: usize,
    pub end: usize,
}

/// The first invite link in `text` (a word that reads as one).
#[must_use]
pub fn find_invite(text: &str) -> Option<InviteMatch> {
    let mut offset16 = 0;
    for word in text.split_inclusive(char::is_whitespace) {
        let trimmed = word.trim_end();
        // Punctuation around a link in a sentence.
        let lead = trimmed.len() - trimmed.trim_start_matches(['(', '<', '"', '\'']).len();
        let inner =
            trimmed[lead..].trim_end_matches(['.', ',', ')', '>', '"', '\'', '!', '?', ';', ':']);
        if inner.contains(INVITE_HOST)
            && let Some(code) = invite_code(inner)
        {
            let start = offset16 + utf16_len(&trimmed[..lead]);
            return Some(InviteMatch {
                code,
                start,
                end: start + utf16_len(inner),
            });
        }
        offset16 += utf16_len(word);
    }
    None
}

fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    const CODE: &str = "AbCdEfGhIjKlMnOpQrStUv";

    #[test]
    fn links_and_bare_codes_read() {
        for text in [
            CODE.to_owned(),
            format!("https://chat.whatsapp.com/{CODE}"),
            format!("http://chat.whatsapp.com/invite/{CODE}/"),
            format!("chat.whatsapp.com/{CODE}?utm=x"),
            format!(" https://www.chat.whatsapp.com/{CODE}#top "),
            format!("whatsapp://chat?code={CODE}"),
        ] {
            assert_eq!(invite_code(&text).as_deref(), Some(CODE), "{text}");
        }
        assert_eq!(
            invite_link(CODE),
            format!("https://chat.whatsapp.com/{CODE}")
        );
    }

    #[test]
    fn other_links_and_bad_codes_are_refused() {
        for text in [
            "",
            "https://chat.whatsapp.com/",
            "https://example.com/AbCdEfGhIjKlMnOp",
            "https://chat.whatsapp.com.evil.test/AbCdEfGhIjKl",
            "https://chat.whatsapp.com/abc",
            "https://chat.whatsapp.com/AbCd-EfGh-IjKl",
            "whatsapp://chat?code=",
            "short",
        ] {
            assert_eq!(invite_code(text), None, "{text}");
        }
    }

    #[test]
    fn a_link_is_found_in_a_sentence() {
        let text = format!("Katıl 👋 (https://chat.whatsapp.com/{CODE}).");
        let found = find_invite(&text).unwrap();
        assert_eq!(found.code, CODE);
        let utf16: Vec<u16> = text.encode_utf16().collect();
        let link = String::from_utf16(&utf16[found.start..found.end]).unwrap();
        assert_eq!(link, format!("https://chat.whatsapp.com/{CODE}"));
        assert_eq!(find_invite("no link here https://example.com/x"), None);
        // A bare code in a sentence is not a link.
        assert_eq!(find_invite(&format!("code {CODE}")), None);
    }
}
