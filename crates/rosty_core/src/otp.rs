//! One-time-passcode detection in SMS and WhatsApp bodies.
//!
//! A code is a 4 to 8 digit run (`123-456` and `123 456` are accepted and
//! returned without the separator) that sits within [`KEYWORD_WINDOW`]
//! characters of an English or Turkish keyword such as "code", "kod" or
//! "şifre". The Google `G-123456` form is accepted on its own. Phone numbers,
//! amounts, times and dates never match: a candidate is the whole chain of
//! digit groups, so `0532 123 45 67` or `2026-11-05` is rejected as a unit.

use std::ops::Range;

/// Maximum gap, in characters, between a keyword and the code.
pub const KEYWORD_WINDOW: usize = 40;

/// A detected code.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Otp {
    /// The digits only, with any separator or `G-` prefix removed.
    pub code: String,
    /// Byte range of the code as written in the body, separator included.
    pub span: Range<usize>,
}

/// Folded (ASCII-lowercase, Turkish letters flattened) keywords. The flag
/// requires a non-letter after the keyword, so "pin" does not match "pinned".
const KEYWORDS: [(&str, bool); 9] = [
    ("code", false),
    ("passcode", false),
    ("kod", false),
    ("otp", true),
    ("sifre", false),
    ("password", false),
    ("verification", false),
    ("dogrulama", false),
    ("pin", true),
];

/// Words after a number that make it a quantity, not a code.
const UNITS: [&str; 27] = [
    "tl", "try", "usd", "eur", "gbp", "lira", "dk", "dakika", "min", "mins", "minute", "minutes",
    "sn", "saniye", "sec", "saat", "hour", "hours", "gun", "day", "days", "gb", "mb", "kb", "km",
    "kg", "adet",
];

const MONTHS: [&str; 24] = [
    "ocak",
    "subat",
    "mart",
    "nisan",
    "mayis",
    "haziran",
    "temmuz",
    "agustos",
    "eylul",
    "ekim",
    "kasim",
    "aralik",
    "january",
    "february",
    "march",
    "april",
    "may",
    "june",
    "july",
    "august",
    "september",
    "october",
    "november",
    "december",
];

fn fold(c: char) -> char {
    match c {
        'İ' | 'ı' | 'I' => 'i',
        'ş' | 'Ş' => 's',
        'ğ' | 'Ğ' => 'g',
        'ü' | 'Ü' => 'u',
        'ö' | 'Ö' => 'o',
        'ç' | 'Ç' => 'c',
        other => other.to_ascii_lowercase(),
    }
}

fn is_space(c: char) -> bool {
    c == ' ' || c == '\u{a0}'
}

fn keyword_spans(folded: &[char]) -> Vec<Range<usize>> {
    let mut spans = Vec::new();
    for (word, strict_end) in KEYWORDS {
        let word: Vec<char> = word.chars().collect();
        if folded.len() < word.len() {
            continue;
        }
        for start in 0..=folded.len() - word.len() {
            let end = start + word.len();
            if folded[start..end] != word[..] {
                continue;
            }
            if start > 0 && folded[start - 1].is_alphanumeric() {
                continue;
            }
            if strict_end && folded.get(end).is_some_and(|c| c.is_alphabetic()) {
                continue;
            }
            spans.push(start..end);
        }
    }
    spans
}

fn digit_run_end(chars: &[(usize, char)], mut at: usize) -> usize {
    while chars.get(at).is_some_and(|&(_, c)| c.is_ascii_digit()) {
        at += 1;
    }
    at
}

/// Maximal chain of digit groups joined by separators.
struct Chain {
    groups: Vec<Range<usize>>,
    /// Separator between group `n` and `n + 1`.
    seps: Vec<char>,
    end: usize,
}

fn read_chain(chars: &[(usize, char)], start: usize) -> Chain {
    let mut end = digit_run_end(chars, start);
    let mut groups = Vec::new();
    groups.push(start..end);
    let mut seps = Vec::new();
    while let Some(&(_, sep)) = chars.get(end) {
        let next = end + 1;
        if !chars.get(next).is_some_and(|&(_, c)| c.is_ascii_digit()) {
            break;
        }
        let next_end = digit_run_end(chars, next);
        let joins = match sep {
            '-' | '.' | '/' | ':' | ',' => true,
            s if is_space(s) => {
                let prev_len = groups.last().map_or(0, |g| g.len());
                (3..=4).contains(&prev_len) && (3..=4).contains(&(next_end - next))
            }
            _ => false,
        };
        if !joins {
            break;
        }
        seps.push(sep);
        groups.push(next..next_end);
        end = next_end;
    }
    Chain { groups, seps, end }
}

fn word_before(folded: &[char], mut at: usize) -> String {
    while at > 0 && is_space(folded[at - 1]) {
        at -= 1;
    }
    let end = at;
    while at > 0 && folded[at - 1].is_alphabetic() {
        at -= 1;
    }
    folded[at..end].iter().collect()
}

fn unit_after(chars: &[(usize, char)], folded: &[char], mut at: usize) -> bool {
    while folded.get(at).is_some_and(|&c| is_space(c)) {
        at += 1;
    }
    match chars.get(at) {
        Some(&(_, c)) if "₺$€£%".contains(c) => true,
        Some(_) => {
            let word: String = folded[at..]
                .iter()
                .take_while(|c| c.is_alphabetic())
                .collect();
            UNITS.contains(&word.as_str())
        }
        None => false,
    }
}

/// How a candidate chain was accepted.
enum Shape {
    /// Needs a keyword nearby.
    Plain,
    /// `G-123456`: trusted on its own.
    Google,
}

fn judge(chars: &[(usize, char)], folded: &[char], chain: &Chain, start: usize) -> Option<Shape> {
    let lens: Vec<usize> = chain.groups.iter().map(|g| g.len()).collect();
    let shape_ok = match lens.as_slice() {
        [n] => (4..=8).contains(n),
        [3, 3] => matches!(chain.seps[0], '-' | ' ' | '\u{a0}'),
        _ => false,
    };
    if !shape_ok {
        return None;
    }
    // Glued to a letter after the digits ("482913abc").
    if folded.get(chain.end).is_some_and(|c| c.is_alphabetic()) {
        return None;
    }
    if unit_after(chars, folded, chain.end) {
        return None;
    }
    let mut google = false;
    if start > 0 {
        let prev = folded[start - 1];
        match prev {
            '+' | '$' | '€' | '£' | '₺' | '#' | '/' => return None,
            '-' => {
                let before = start.checked_sub(2).map(|i| folded[i]);
                match before {
                    Some('g') if start < 3 || !folded[start - 3].is_alphanumeric() => {
                        google = lens == [6];
                    }
                    Some(c) if c.is_alphanumeric() => return None,
                    _ => {}
                }
            }
            c if c.is_alphabetic() => return None,
            _ => {}
        }
        // "+90 532 123 456": a digit just before the separator.
        let mut at = start;
        while at > 0 && is_space(folded[at - 1]) {
            at -= 1;
        }
        if at < start && at > 0 && folded[at - 1].is_ascii_digit() {
            return None;
        }
        if lens.len() == 1 && lens[0] == 4 && MONTHS.contains(&word_before(folded, start).as_str())
        {
            return None;
        }
    }
    Some(if google { Shape::Google } else { Shape::Plain })
}

/// Finds the first passcode in `body`.
pub fn detect(body: &str) -> Option<Otp> {
    let chars: Vec<(usize, char)> = body.char_indices().collect();
    let folded: Vec<char> = chars.iter().map(|&(_, c)| fold(c)).collect();
    let keywords = keyword_spans(&folded);

    let mut i = 0;
    while i < chars.len() {
        if !chars[i].1.is_ascii_digit() {
            i += 1;
            continue;
        }
        let chain = read_chain(&chars, i);
        let chain_start = i;
        i = chain.end;
        let Some(shape) = judge(&chars, &folded, &chain, chain_start) else {
            continue;
        };
        let (cs, ce) = (chain_start, chain.end);
        let near = keywords.iter().any(|k| {
            let gap = if k.start >= ce {
                k.start - ce
            } else {
                cs.saturating_sub(k.end)
            };
            gap <= KEYWORD_WINDOW
        });
        let google = matches!(shape, Shape::Google);
        if !near && !google {
            continue;
        }
        let first = if google { cs - 2 } else { cs };
        let byte_start = chars[first].0;
        let byte_end = chars.get(ce).map_or(body.len(), |&(b, _)| b);
        let code: String = chars[cs..ce]
            .iter()
            .map(|&(_, c)| c)
            .filter(char::is_ascii_digit)
            .collect();
        return Some(Otp {
            code,
            span: byte_start..byte_end,
        });
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn code(body: &str) -> Option<String> {
        detect(body).map(|o| o.code)
    }

    #[test]
    fn positives() {
        let cases = [
            ("Your code is 482913", "482913"),
            ("Doğrulama kodunuz: 4829", "4829"),
            ("DOĞRULAMA KODU:482913", "482913"),
            ("G-482913", "482913"),
            ("G-482913 is your Google verification code.", "482913"),
            ("123-456 is your code", "123456"),
            ("Your code is 123 456, valid 10 minutes", "123456"),
            ("Şifreniz 12345678 olarak belirlendi", "12345678"),
            ("Your PIN: 7731.", "7731"),
            ("OTP 556677 for login", "556677"),
            ("Use 112233 as your password", "112233"),
        ];
        for (body, want) in cases {
            assert_eq!(code(body).as_deref(), Some(want), "{body}");
        }
    }

    #[test]
    fn negatives() {
        let cases = [
            "Your code: call 0532 123 45 67",
            "kod: +905321234567",
            "code 05321234567",
            "Your code: 1500 TL charged",
            "password reset, total $1500",
            "kod ₺1500 tutar",
            "verification at 12:30",
            "kod 2026-11-05",
            "Your code 5 Kasım 2026 tarihinde",
            "Code 1,500 items",
            "Your code is 123",
            "Your code is 123456789",
            "order A4829 code",
            "+90 532 123 456 kod",
            "482913 without any keyword",
            "see you at 4829 street",
        ];
        for body in cases {
            assert_eq!(code(body), None, "{body}");
        }
    }

    #[test]
    fn keyword_window_is_enforced() {
        let far = format!("code {} 482913", "x".repeat(60));
        assert_eq!(code(&far), None);
        let near = format!("code {} 482913", "x".repeat(30));
        assert_eq!(code(&near).as_deref(), Some("482913"));
    }

    #[test]
    fn keyword_needs_word_start() {
        assert_eq!(code("barcode 482913"), None);
        assert_eq!(code("shipping 482913"), None);
    }

    #[test]
    fn span_covers_written_form() {
        let body = "Kodunuz: 123-456 gecerli";
        let otp = detect(body).expect("otp");
        assert_eq!(&body[otp.span.clone()], "123-456");
        let body = "Şifre: G-482913.";
        let otp = detect(body).expect("otp");
        assert_eq!(&body[otp.span], "G-482913");
    }

    #[test]
    fn first_valid_candidate_wins() {
        assert_eq!(
            code("Ref 12:30, your code is 482913").as_deref(),
            Some("482913")
        );
    }
}
