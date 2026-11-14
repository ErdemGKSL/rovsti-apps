//! Phone numbers and e-mail addresses inside a message body, for tap actions.

use crate::{otp, spam};

/// Something worth an action: call, add contact, write a mail.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Detected {
    Phone(String),
    Email(String),
}

impl Detected {
    /// The text as found in the body.
    pub fn label(&self) -> &str {
        match self {
            Self::Phone(s) | Self::Email(s) => s,
        }
    }
}

const EDGE: &[char] = &[
    '<', '>', '(', ')', '[', ']', '"', '\'', ',', ';', ':', '!', '?', '.',
];

fn email_of(token: &str) -> Option<&str> {
    let t = token.trim_matches(EDGE);
    let (local, domain) = t.split_once('@')?;
    let tld = domain.rsplit('.').next()?;
    let ok = !local.is_empty()
        && !domain.contains('@')
        && domain.contains('.')
        && !domain.starts_with('.')
        && tld.len() >= 2
        && tld.chars().all(|c| c.is_alphabetic())
        && local
            .chars()
            .all(|c| c.is_alphanumeric() || "._%+-".contains(c))
        && domain
            .chars()
            .all(|c| c.is_alphanumeric() || ".-".contains(c));
    ok.then_some(t)
}

fn is_date(chunk: &str) -> bool {
    for sep in ['.', '-'] {
        let parts: Vec<&str> = chunk.split(sep).collect();
        if parts.len() != 3 || !parts.iter().all(|p| p.chars().all(|c| c.is_ascii_digit())) {
            continue;
        }
        let l: Vec<usize> = parts.iter().map(|p| p.len()).collect();
        let short = |n: usize| (1..=2).contains(&n);
        if (short(l[0]) && short(l[1]) && l[2] == 4) || (l[0] == 4 && short(l[1]) && short(l[2])) {
            return true;
        }
    }
    false
}

fn is_sep(c: char) -> bool {
    matches!(c, ' ' | '-' | '.' | '(' | ')')
}

/// Phone runs of `body` as (start, end) byte ranges, skipping masked bytes.
fn phone_runs(body: &str, masked: &[bool]) -> Vec<(usize, usize)> {
    let chars: Vec<(usize, char)> = body.char_indices().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let (b, c) = chars[i];
        let starts = !masked[b]
            && (c.is_ascii_digit()
                || ((c == '+' || c == '(')
                    && chars.get(i + 1).is_some_and(|n| n.1.is_ascii_digit())))
            && (i == 0 || !chars[i - 1].1.is_alphanumeric());
        if !starts {
            i += 1;
            continue;
        }
        let plus = c == '+';
        let mut j = i + 1;
        let mut digits = usize::from(c.is_ascii_digit());
        let mut last_digit = if digits == 1 { i } else { i + 1 };
        while j < chars.len() && !masked[chars[j].0] {
            let cj = chars[j].1;
            if cj.is_ascii_digit() {
                digits += 1;
                last_digit = j;
                j += 1;
            } else if is_sep(cj) {
                // A group like "14" in "... 14:05" is a clock, not ours.
                let mut k = j + 1;
                while k < chars.len() && chars[k].1.is_ascii_digit() {
                    k += 1;
                }
                if cj == ' ' && k > j + 1 && chars.get(k).is_some_and(|n| n.1 == ':') {
                    break;
                }
                j += 1;
            } else {
                break;
            }
        }
        let start = chars[i].0;
        let end = chars[last_digit].0 + 1;
        let text = &body[start..end];
        let next = body[end..].chars().next();
        let bad_next = next.is_some_and(|n| n.is_alphanumeric())
            || (matches!(next, Some(',' | '.'))
                && body[end + 1..].starts_with(|n: char| n.is_ascii_digit()));
        let first_chunk = text.split(' ').next().unwrap_or("");
        let _ = plus;
        if is_date(first_chunk) {
            i = chars
                .iter()
                .position(|&(bb, _)| bb >= start + first_chunk.len())
                .unwrap_or(chars.len());
            continue;
        }
        let one_dot_amount = !text.starts_with('+')
            && !text.contains([' ', '-', '(', ')'])
            && text.matches('.').count() == 1
            && text.rsplit('.').next().is_some_and(|f| f.len() <= 2);
        if (7..=15).contains(&digits) && !bad_next && !one_dot_amount {
            out.push((start, end));
        }
        i = j.max(i + 1);
    }
    out
}

/// One tap action offered under a message for something [`detect`] found.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DetectAction {
    /// Place a call to the dialled number.
    Call(String),
    /// Start a conversation with the dialled number.
    Message(String),
    /// Copy the number as written.
    CopyNumber(String),
    /// Compose a mail to the address.
    Email(String),
    /// Copy the address.
    CopyEmail(String),
}

impl DetectAction {
    /// The pill text.
    pub fn label(&self) -> String {
        match self {
            Self::Call(n) => format!("Call {n}"),
            Self::Message(_) => "Message".to_owned(),
            Self::CopyNumber(_) => "Copy number".to_owned(),
            Self::Email(_) => "Email".to_owned(),
            Self::CopyEmail(_) => "Copy".to_owned(),
        }
    }
}

/// `number` reduced to what is dialled: digits and one leading `+`.
pub fn dial(number: &str) -> String {
    let mut out = String::new();
    for c in number.trim().chars() {
        if c.is_ascii_digit() || (c == '+' && out.is_empty()) {
            out.push(c);
        }
    }
    out
}

/// Whether two numbers are the same line, ignoring formatting and country
/// prefix (the last nine digits agree; at least seven digits each).
pub fn same_number(a: &str, b: &str) -> bool {
    let digits = |s: &str| -> Vec<char> { s.chars().filter(char::is_ascii_digit).collect() };
    let (a, b) = (digits(a), digits(b));
    let n = a.len().min(b.len()).min(9);
    n >= 7 && a[a.len() - n..] == b[b.len() - n..]
}

/// The pills for the phone numbers and addresses in `body` (at most `max`
/// found). Call is left out for a number equal to one of `own` (the thread's
/// own counterpart, whose page menu already calls).
pub fn actions(body: &str, max: usize, own: &[&str]) -> Vec<DetectAction> {
    let mut out = Vec::new();
    for found in detect(body, max) {
        match found {
            Detected::Phone(raw) => {
                let dialled = dial(&raw);
                if !own.iter().any(|o| same_number(o, &dialled)) {
                    out.push(DetectAction::Call(raw.clone()));
                }
                out.push(DetectAction::Message(dialled));
                out.push(DetectAction::CopyNumber(raw));
            }
            Detected::Email(address) => {
                out.push(DetectAction::Email(address.clone()));
                out.push(DetectAction::CopyEmail(address));
            }
        }
    }
    out
}

/// Up to `max` phone numbers and e-mail addresses of `body`, in order of
/// appearance and without duplicates. Phones have 7-15 digits with spaces,
/// `-`, `.`, parentheses and one leading `+`. Links, the one-time code,
/// dates and decimal amounts are left out.
pub fn detect(body: &str, max: usize) -> Vec<Detected> {
    let mut masked = vec![false; body.len() + 1];
    let mut found: Vec<(usize, Detected)> = Vec::new();
    let mut at = 0;
    for raw in body.split_inclusive(char::is_whitespace) {
        let token = raw.trim_end();
        let start = at;
        at += raw.len();
        if token.is_empty() {
            continue;
        }
        if let Some(email) = email_of(token) {
            let off = start + token.find(email).unwrap_or(0);
            masked[off..off + email.len()].fill(true);
            found.push((off, Detected::Email(email.to_owned())));
        } else if !spam::links(token).is_empty() {
            masked[start..start + token.len()].fill(true);
        }
    }
    if let Some(code) = otp::detect(body) {
        masked[code.span.clone()].fill(true);
    }
    for (s, e) in phone_runs(body, &masked) {
        found.push((s, Detected::Phone(body[s..e].to_owned())));
    }
    found.sort_by_key(|f| f.0);
    let mut out: Vec<Detected> = Vec::new();
    for (_, d) in found {
        if out.len() >= max {
            break;
        }
        if !out.contains(&d) {
            out.push(d);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn phones(body: &str) -> Vec<String> {
        detect(body, 10)
            .into_iter()
            .filter_map(|d| match d {
                Detected::Phone(p) => Some(p),
                Detected::Email(_) => None,
            })
            .collect()
    }

    #[test]
    fn finds_turkish_mobile_and_international() {
        assert_eq!(phones("Ara: 0532 123 45 67 olur"), ["0532 123 45 67"]);
        assert_eq!(phones("Call +90 532 123 45 67."), ["+90 532 123 45 67"]);
        assert_eq!(phones("Ofis (0212) 555 12 34"), ["(0212) 555 12 34"]);
        assert_eq!(phones("5321234567"), ["5321234567"]);
    }

    #[test]
    fn finds_emails_without_trailing_dot() {
        let found = detect(
            "Write to ada@example.com. Or <bob.k@mail.example.org>, ok",
            10,
        );
        assert_eq!(
            found,
            [
                Detected::Email("ada@example.com".into()),
                Detected::Email("bob.k@mail.example.org".into())
            ]
        );
        assert_eq!(found[0].label(), "ada@example.com");
    }

    #[test]
    fn ignores_dates_amounts_otp_and_urls() {
        assert!(phones("On 12.11.2026 and 2026-11-12").is_empty());
        assert!(phones("12.11.2026 14:05").is_empty());
        assert!(phones("Total 1234567.50 TL, 1234567,50").is_empty());
        assert!(phones("Your code is 48213976, do not share").is_empty());
        assert!(phones("see https://example.com/p/5321234567 now").is_empty());
        assert!(phones("short 12345 and 1234567890123456").is_empty());
    }

    #[test]
    fn dedupes_and_caps() {
        let body = "0532 123 45 67 and 0532 123 45 67, 0555 987 65 43, a@b.co";
        assert_eq!(detect(body, 10).len(), 3);
        assert_eq!(detect(body, 2).len(), 2);
        assert!(detect(body, 0).is_empty());
    }

    #[test]
    fn dial_keeps_digits_and_leading_plus() {
        assert_eq!(dial("+90 (532) 123-45-67"), "+905321234567");
        assert_eq!(dial("0532 123 45 67"), "05321234567");
    }

    #[test]
    fn same_number_ignores_format_and_prefix() {
        assert!(same_number("+90 532 123 45 67", "0532 123 45 67"));
        assert!(!same_number("0532 123 45 67", "0532 123 45 68"));
        assert!(!same_number("123", "123"));
    }

    #[test]
    fn actions_skip_call_for_own_number_only() {
        let body = "Ara 0532 123 45 67 veya ada@example.com";
        let all = actions(body, 2, &[]);
        assert_eq!(
            all,
            [
                DetectAction::Call("0532 123 45 67".into()),
                DetectAction::Message("05321234567".into()),
                DetectAction::CopyNumber("0532 123 45 67".into()),
                DetectAction::Email("ada@example.com".into()),
                DetectAction::CopyEmail("ada@example.com".into()),
            ]
        );
        let own = actions(body, 2, &["+905321234567"]);
        assert!(!own.iter().any(|a| matches!(a, DetectAction::Call(_))));
        assert_eq!(own.len(), 4);
        assert_eq!(all[0].label(), "Call 0532 123 45 67");
    }
}
