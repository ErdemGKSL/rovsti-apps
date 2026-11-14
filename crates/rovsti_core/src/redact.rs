//! Masks what must never reach a log line: connect keys, credentials, phone
//! numbers (bare and inside WhatsApp JIDs), group ids and e-mail addresses.
//!
//! The same rules run in Kotlin (`dev.rovsti.core.log.LogMask`); both are
//! checked against `crates/rovsti_core/tests/data/redact_cases.json`. Masking
//! is idempotent: masking masked text changes nothing.
//!
//! Rules, applied left to right in one pass (ASCII only; any other character
//! is a separator):
//! - `rwk_<key>` becomes `rwk_***`.
//! - The value after `Authorization:` / `Authorization=` (keeping a `Bearer` or
//!   `Basic` scheme) and after `key=` becomes `***`.
//! - An e-mail address keeps only its domain: `***@example.com`.
//! - A run of 7 to 15 digits (an optional `+` before it) that stands alone
//!   keeps its first and last two digits: `90***67`. This covers user JIDs
//!   (`90***67@s.whatsapp.net`, `...@lid`). A run touching a letter, `_` or
//!   another number (`1.5`, a longer id) is left alone, and so is the value of
//!   a field whose name ends in `ms` (`at_ms=1731...`, `"fetched_ms":1731...`).
//! - A group JID keeps only its suffix: `***@g.us`.

use std::borrow::Cow;

/// What a masked secret or number part is replaced by.
const STARS: &str = "***";
/// Bare digit runs of this length are taken for phone numbers.
const MIN_NUMBER: usize = 7;
const MAX_NUMBER: usize = 15;
/// WhatsApp's own domains are JIDs, never e-mail addresses.
const WA_DOMAINS: [&str; 5] = ["s.whatsapp.net", "g.us", "c.us", "broadcast", "newsletter"];

fn is_word(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

fn is_local(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'%' | b'+' | b'-')
}

fn is_domain(b: u8) -> bool {
    b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-')
}

/// The end of a secret value starting at `i`: up to whitespace or a delimiter.
fn value_end(s: &[u8], mut i: usize) -> usize {
    while i < s.len()
        && !s[i].is_ascii_whitespace()
        && !matches!(s[i], b'"' | b'\'' | b',' | b';' | b'&' | b')' | b']' | b'}')
    {
        i += 1;
    }
    i
}

fn starts_with_ci(s: &[u8], i: usize, word: &str) -> bool {
    s.len() >= i + word.len() && s[i..i + word.len()].eq_ignore_ascii_case(word.as_bytes())
}

/// Masks `text`; borrows it when nothing needed masking.
pub fn mask(text: &str) -> Cow<'_, str> {
    let s = text.as_bytes();
    let mut out = String::with_capacity(text.len());
    let mut i = 0;
    let mut copied = 0;
    let mut changed = false;
    // Pushes `text[copied..upto]`, then `masked`, and moves on to `resume`.
    macro_rules! replace {
        ($upto:expr, $masked:expr, $resume:expr) => {{
            out.push_str(&text[copied..$upto]);
            out.push_str($masked);
            copied = $resume;
            i = $resume;
            changed = true;
            continue;
        }};
    }
    while i < s.len() {
        let prev = if i == 0 { None } else { Some(s[i - 1]) };
        let word_start = prev.is_none_or(|p| !is_word(p));
        // Connect keys.
        if word_start && s[i..].starts_with(b"rwk_") {
            let mut end = i + 4;
            while end < s.len() && (is_word(s[end]) || s[end] == b'-') {
                end += 1;
            }
            if end > i + 4 {
                replace!(i + 4, STARS, end);
            }
        }
        // Credentials by name.
        let name_start = prev.is_none_or(|p| !p.is_ascii_alphanumeric());
        if name_start && starts_with_ci(s, i, "authorization") {
            let mut j = i + "authorization".len();
            while j < s.len() && s[j] == b' ' {
                j += 1;
            }
            if j < s.len() && matches!(s[j], b':' | b'=') {
                j += 1;
                while j < s.len() && s[j] == b' ' {
                    j += 1;
                }
                for scheme in ["bearer ", "basic "] {
                    if starts_with_ci(s, j, scheme) {
                        j += scheme.len();
                        break;
                    }
                }
                let end = value_end(s, j);
                if end > j && &s[j..end] != STARS.as_bytes() {
                    replace!(j, STARS, end);
                }
            }
        }
        if name_start && s[i..].starts_with(b"key=") {
            let j = i + 4;
            let end = value_end(s, j);
            if end > j && &s[j..end] != STARS.as_bytes() {
                replace!(j, STARS, end);
            }
        }
        // E-mail addresses (WhatsApp's own domains are JIDs).
        if prev.is_none_or(|p| !is_local(p)) && is_local(s[i]) {
            let mut at = i;
            while at < s.len() && is_local(s[at]) {
                at += 1;
            }
            if at < s.len() && s[at] == b'@' {
                let mut end = at + 1;
                while end < s.len() && is_domain(s[end]) {
                    end += 1;
                }
                // A trailing dot ends the sentence, not the domain.
                while end > at + 1 && s[end - 1] == b'.' {
                    end -= 1;
                }
                let domain = &text[at + 1..end];
                let is_mail = domain.contains('.')
                    && domain.rsplit('.').next().is_some_and(|tld| {
                        tld.len() >= 2 && tld.bytes().all(|b| b.is_ascii_alphabetic())
                    })
                    && !WA_DOMAINS.contains(&domain);
                if is_mail {
                    replace!(i, STARS, at);
                }
            }
        }
        // Numbers, user JIDs and group JIDs.
        let plus = s[i] == b'+' && s.get(i + 1).is_some_and(u8::is_ascii_digit);
        if (plus || s[i].is_ascii_digit()) && prev.is_none_or(|p| !is_word(p) && p != b'.') {
            let digits = if plus { i + 1 } else { i };
            let mut end = digits;
            while end < s.len() && s[end].is_ascii_digit() {
                end += 1;
            }
            // A group JID: digits, optionally `-digits`, then `@g.us`.
            let mut group_end = end;
            if s.get(group_end) == Some(&b'-') {
                let mut k = group_end + 1;
                while k < s.len() && s[k].is_ascii_digit() {
                    k += 1;
                }
                if k > group_end + 1 {
                    group_end = k;
                }
            }
            if !plus && s[group_end..].starts_with(b"@g.us") {
                replace!(i, STARS, group_end);
            }
            let next = s.get(end).copied();
            let touches = next.is_some_and(|n| {
                is_word(n) || (n == b'.' && s.get(end + 1).is_some_and(u8::is_ascii_digit))
            });
            let len = end - digits;
            let timestamp = {
                let before = text[..i].trim_end_matches(' ');
                before.ends_with("ms=") || before.ends_with("ms:") || before.ends_with("ms\":")
            };
            if !touches && (MIN_NUMBER..=MAX_NUMBER).contains(&len) && !timestamp {
                let masked = format!(
                    "{}{}{}",
                    &text[digits..digits + 2],
                    STARS,
                    &text[end - 2..end]
                );
                replace!(digits, &masked, end);
            }
            // Not a number to mask: copy the whole run, so no inner digit starts one.
            i = end;
            continue;
        }
        i += 1;
    }
    if !changed {
        return Cow::Borrowed(text);
    }
    out.push_str(&text[copied..]);
    Cow::Owned(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(serde::Deserialize)]
    struct Case {
        why: String,
        #[serde(rename = "in")]
        input: String,
        out: String,
    }

    fn cases() -> Vec<Case> {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/redact_cases.json");
        let text = std::fs::read_to_string(path).expect("redact_cases.json is readable");
        serde_json::from_str(&text).expect("redact_cases.json parses")
    }

    #[test]
    fn shared_fixture_cases() {
        let cases = cases();
        assert!(cases.len() >= 20);
        for case in cases {
            assert_eq!(mask(&case.input), case.out, "{}", case.why);
        }
    }

    #[test]
    fn masking_is_idempotent() {
        for case in cases() {
            assert_eq!(mask(&case.out), case.out, "{} (twice)", case.why);
        }
    }

    #[test]
    fn clean_text_is_borrowed() {
        assert!(matches!(
            mask("batch stage=lookup n=15 ok"),
            Cow::Borrowed(_)
        ));
        assert!(matches!(mask(""), Cow::Borrowed(_)));
        assert!(matches!(mask("+905321234567"), Cow::Owned(_)));
    }

    #[test]
    fn non_ascii_text_is_kept_whole() {
        assert_eq!(
            mask("Eşitleme 905321234567 bitti ✓"),
            "Eşitleme 90***67 bitti ✓"
        );
    }
}
