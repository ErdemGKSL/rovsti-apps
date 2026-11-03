//! Dial-string rules for the Keypad (AD-11, AD-14).
//!
//! Dial strings stay raw: `*#06#`, USSD codes, `,` and `;` pauses and
//! emergency numbers all pass through unchanged. [`crate::PhoneNumber`] is
//! for matching and display only.

/// Longest number the entry field accepts.
pub const MAX_LEN: usize = 32;

/// What the field did with a key press.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Push {
    Accepted,
    /// The field is full: shake it and change nothing.
    Shake,
    /// Not a dialable key; ignored.
    Ignored,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum DialError {
    #[error("nothing to dial")]
    Empty,
    #[error("no digit, '*' or '#' in the number")]
    NoDialable,
}

/// Characters kept when a number is pasted.
fn is_dial_char(c: char) -> bool {
    matches!(c, '0'..='9' | '*' | '#' | '+' | ',' | ';')
}

/// Keys the keypad itself can produce.
fn is_key(c: char) -> bool {
    matches!(c, '0'..='9' | '*' | '#' | '+')
}

/// Cuts RFC 3966 parameters (`;ext=12`, `;phone-context=+90`, `;isub=..`):
/// a `;` followed by a letter. A pause `;` is followed by a digit or ends the
/// string, so `1,2;3` is untouched.
fn cut_uri_params(text: &str) -> &str {
    let mut chars = text.char_indices().peekable();
    while let Some((at, c)) = chars.next() {
        if c == ';' && chars.peek().is_some_and(|(_, n)| n.is_ascii_alphabetic()) {
            return &text[..at];
        }
    }
    text
}

/// Drops the optional trunk `(0)` after a leading `+country code`.
fn drop_trunk_zero(text: &str) -> std::borrow::Cow<'_, str> {
    crate::phone_number::drop_optional_trunk(text)
}

/// Cleans pasted text into a dial string: drops a `tel:` scheme, spaces,
/// dashes, brackets and every other character that cannot be dialled, and
/// keeps `+ * # , ;` and digits. `tel:%2B90` style escapes for `+`, `#` and
/// `*` are decoded. Text with nothing dialable becomes an empty string.
pub fn normalise_paste(input: &str) -> String {
    let trimmed = input.trim();
    let body = match trimmed.get(..4) {
        Some(scheme) if scheme.eq_ignore_ascii_case("tel:") => &trimmed[4..],
        _ => trimmed,
    };
    let is_uri = body.len() != trimmed.len();
    let body = if is_uri { cut_uri_params(body) } else { body };
    let body = drop_trunk_zero(body);
    let mut out = String::with_capacity(body.len().min(MAX_LEN * 2));
    let mut chars = body.chars();
    while let Some(c) = chars.next() {
        if c == '%' {
            let rest = chars.as_str();
            let decoded = match rest.get(..2) {
                Some(h) if h.eq_ignore_ascii_case("2b") => Some('+'),
                Some(h) if h.eq_ignore_ascii_case("23") => Some('#'),
                Some(h) if h.eq_ignore_ascii_case("2a") => Some('*'),
                _ => None,
            };
            if let Some(d) = decoded {
                out.push(d);
                chars = rest[2..].chars();
            }
            continue;
        }
        if is_dial_char(c) {
            out.push(c);
        }
    }
    out
}

/// The dial string a `tel:` URI (or a bare number) stands for: the scheme is
/// dropped, every `%XX` escape is decoded (`%23` is `#`, `%2B` is `+`, `%20`
/// a space), then anything that cannot be dialled is removed. The result is
/// cut to [`MAX_LEN`] characters. `"tel:"` and non-numbers give `""`.
pub fn from_tel_uri(uri: &str) -> String {
    let trimmed = uri.trim();
    let body = match trimmed.get(..4) {
        Some(scheme) if scheme.eq_ignore_ascii_case("tel:") => &trimmed[4..],
        _ => trimmed,
    };
    let bytes = body.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let escape = (bytes[i] == b'%')
            .then(|| bytes.get(i + 1..i + 3))
            .flatten()
            .and_then(|hex| std::str::from_utf8(hex).ok())
            .and_then(|hex| u8::from_str_radix(hex, 16).ok());
        match escape {
            Some(byte) => {
                decoded.push(byte);
                i += 3;
            }
            None => {
                decoded.push(bytes[i]);
                i += 1;
            }
        }
    }
    let text = String::from_utf8_lossy(&decoded);
    let text = drop_trunk_zero(cut_uri_params(&text)).into_owned();
    text.chars()
        .filter(|&c| is_dial_char(c))
        .take(MAX_LEN)
        .collect()
}

/// Accepts a number for dialing, unchanged. Only strings with no digit,
/// `*` or `#` are rejected (`""`, `"abc"`, `",,"`).
pub fn validate(s: &str) -> Result<&str, DialError> {
    if s.is_empty() {
        return Err(DialError::Empty);
    }
    if s.chars()
        .any(|c| c.is_ascii_digit() || c == '*' || c == '#')
    {
        Ok(s)
    } else {
        Err(DialError::NoDialable)
    }
}

/// Appends `key` unless the field already holds [`MAX_LEN`] characters.
/// Only ASCII keys reach the field, so `len()` is the character count.
pub fn push(field: &mut String, key: char) -> Push {
    if !is_key(key) {
        return Push::Ignored;
    }
    if field.len() >= MAX_LEN {
        return Push::Shake;
    }
    field.push(key);
    Push::Accepted
}

/// Removes the last character. Returns whether anything was removed.
pub fn backspace(field: &mut String) -> bool {
    field.pop().is_some()
}

/// Holding Delete erases one character at a time from the right. The first
/// repeat waits [`ERASE_FIRST_MS`]; each later one comes sooner, down to
/// [`ERASE_FASTEST_MS`], so a long number goes quickly but each digit still
/// visibly leaves.
pub const ERASE_FIRST_MS: u64 = 140;
pub const ERASE_FASTEST_MS: u64 = 50;
const ERASE_STEP_MS: u64 = 15;

/// Milliseconds before the next held-Delete erase, after `step` repeats.
pub fn erase_interval_ms(step: u32) -> u64 {
    ERASE_FIRST_MS
        .saturating_sub(u64::from(step).saturating_mul(ERASE_STEP_MS))
        .max(ERASE_FASTEST_MS)
}

/// Long-press on `0` turns the `0` that the press just typed into `+`.
/// Fires at most once per typed zero: after replacement the field ends in
/// `+`, so a repeat is a no-op. The caller invokes it only after `push`
/// returned [`Push::Accepted`] for that `0`.
pub fn long_press_zero(field: &mut String) -> bool {
    if field.ends_with('0') {
        field.pop();
        field.push('+');
        true
    } else {
        false
    }
}

/// Number-field font size in px for a field of `len` characters:
/// 36 up to 10, 32 from 11, 28 from 14, 24 from 18.
pub fn font_step(len: usize) -> f32 {
    match len {
        0..=10 => 36.0,
        11..=13 => 32.0,
        14..=17 => 28.0,
        _ => 24.0,
    }
}

/// Numbers that are emergency numbers on every network we know of, so a
/// failed or late `emergency_numbers` pull never makes them dialable-as-normal.
pub const ALWAYS_EMERGENCY: [&str; 2] = ["112", "911"];

/// Longest emergency number (digits) the pure compare considers.
const EMERGENCY_MAX_DIGITS: usize = 8;

/// Separators ignored when deciding whether a typed string is an emergency
/// number: space, dash, dot, brackets, slash and invisible format marks.
fn is_separator(c: char) -> bool {
    c.is_whitespace()
        || matches!(c, '-' | '.' | '(' | ')' | '/' | '\u{a0}')
        || crate::phone_number::is_format_char(c)
}

/// Whether the typed string is an emergency number: `112` and `911` always,
/// plus the numbers Android reported for this country. Separators are ignored
/// (`1 1 2`, `1-1-2`); any other character (`*`, `#`, `+`, `,`, `;`, a letter)
/// makes it an ordinary dial string, so `*#06#` is false. Pure, allocation
/// free, meant to run in the key handler.
pub fn is_emergency(typed: &str, list: &crate::wire::EmergencyList) -> bool {
    let mut buf = [0u8; EMERGENCY_MAX_DIGITS];
    let mut len = 0;
    for c in typed.chars() {
        if is_separator(c) {
            continue;
        }
        if !c.is_ascii_digit() || len == EMERGENCY_MAX_DIGITS {
            return false;
        }
        buf[len] = c as u8;
        len += 1;
    }
    if len == 0 {
        return false;
    }
    let typed = &buf[..len];
    let same = |candidate: &str| {
        let mut digits = candidate.chars().filter(|c| !is_separator(*c));
        typed
            .iter()
            .all(|&d| digits.next().is_some_and(|c| c == char::from(d)))
            && digits.next().is_none()
    };
    ALWAYS_EMERGENCY.iter().any(|n| same(n)) || list.numbers.iter().any(|n| same(n))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paste_table() {
        let cases = [
            ("+90 (555) 111-22-33", "+905551112233"),
            ("1,2;3", "1,2;3"),
            ("abc", ""),
            ("tel:+90555", "+90555"),
            ("TEL:+90555", "+90555"),
            ("  tel:0555 111  ", "0555111"),
            ("tel:%2B90555", "+90555"),
            ("tel:*21%23", "*21#"),
            ("tel:%2a31%23555", "*31#555"),
            ("*#06#", "*#06#"),
            ("", ""),
            ("   ", ""),
            ("tel:", ""),
            ("0555.111.22.33", "05551112233"),
            ("\u{202a}+90 555\u{202c}", "+90555"),
            ("100%", "100"),
            ("%zz1", "1"),
            ("%2", "2"),
            ("ü5", "5"),
        ];
        for (input, want) in cases {
            assert_eq!(normalise_paste(input), want, "paste {input:?}");
        }
    }

    #[test]
    fn uri_parameters_and_optional_trunk_zero_are_cut() {
        let cases = [
            ("tel:+90555;ext=12", "+90555"),
            ("tel:+90555;phone-context=+90", "+90555"),
            ("tel:0555;isub=12;ext=3", "0555"),
            ("tel:1,2;3", "1,2;3"),
            ("tel:123;", "123;"),
            ("+90 (0) 555 111", "+90555111"),
            ("tel:+44(0)20 7946", "+44207946"),
            ("(0)555", "0555"),
            ("tel:*#06#", "*#06#"),
            ("tel:*21%23;ext=1", "*21#"),
        ];
        for (input, want) in cases {
            assert_eq!(normalise_paste(input), want, "paste {input:?}");
            if input.starts_with("tel:") {
                assert_eq!(from_tel_uri(input), want, "uri {input:?}");
            }
        }
    }

    #[test]
    fn escaped_uri_parameters_are_cut() {
        assert_eq!(from_tel_uri("tel:%2B90555%3Bext%3D5"), "+90555");
    }

    #[test]
    fn paste_short_input_with_multibyte_does_not_panic() {
        assert_eq!(normalise_paste("é"), "");
        assert_eq!(normalise_paste("ééé"), "");
    }

    #[test]
    fn tel_uri_table() {
        let cases = [
            ("tel:+905551112233", "+905551112233"),
            ("tel:%2B90%20555%20111", "+90555111"),
            ("tel:*21%23", "*21#"),
            ("tel:*21%23555", "*21#555"),
            ("TEL:1,2;3", "1,2;3"),
            ("tel:+90 555 111", "+90555111"),
            ("tel:", ""),
            ("", ""),
            ("tel:abc", ""),
            ("tel:%zz5%", "5"),
            ("tel:%C3%BC5", "5"),
            ("tel:112", "112"),
            ("+90(555)111-22-33", "+905551112233"),
        ];
        for (uri, want) in cases {
            assert_eq!(from_tel_uri(uri), want, "uri {uri:?}");
        }
    }

    #[test]
    fn tel_uri_is_cut_to_the_field_length() {
        let long = format!("tel:{}", "5".repeat(40));
        assert_eq!(from_tel_uri(&long).len(), MAX_LEN);
    }

    #[test]
    fn validate_table() {
        for ok in [
            "*#06#", "*123#", "112", "+90555", "1", "#", "*", "1,2;3", "abc1",
        ] {
            assert_eq!(validate(ok), Ok(ok), "{ok:?}");
        }
        assert_eq!(validate(""), Err(DialError::Empty));
        assert_eq!(validate("abc"), Err(DialError::NoDialable));
        assert_eq!(validate(",,"), Err(DialError::NoDialable));
        assert_eq!(validate("+"), Err(DialError::NoDialable));
        assert_eq!(validate(";"), Err(DialError::NoDialable));
    }

    #[test]
    fn validate_returns_the_same_slice() {
        let s = "*#06#";
        assert!(std::ptr::eq(validate(s).expect("ok"), s));
    }

    #[test]
    fn push_accepts_up_to_32_then_shakes() {
        let mut f = String::new();
        for _ in 0..MAX_LEN {
            assert_eq!(push(&mut f, '5'), Push::Accepted);
        }
        assert_eq!(f.len(), 32);
        assert_eq!(push(&mut f, '5'), Push::Shake, "33rd key");
        assert_eq!(f.len(), 32, "refused key changes nothing");
        assert_eq!(push(&mut f, '#'), Push::Shake);
    }

    #[test]
    fn push_ignores_non_keys() {
        let mut f = String::new();
        for k in ['a', ' ', ',', ';', 'é', '-'] {
            assert_eq!(push(&mut f, k), Push::Ignored, "{k:?}");
        }
        assert!(f.is_empty());
        for k in ['0', '9', '*', '#', '+'] {
            assert_eq!(push(&mut f, k), Push::Accepted);
        }
        assert_eq!(f, "09*#+");
    }

    #[test]
    fn backspace_removes_last() {
        let mut f = String::from("12");
        assert!(backspace(&mut f));
        assert_eq!(f, "1");
        assert!(backspace(&mut f));
        assert!(!backspace(&mut f));
    }

    #[test]
    fn font_steps() {
        let cases = [
            (0, 36.0),
            (1, 36.0),
            (10, 36.0),
            (11, 32.0),
            (13, 32.0),
            (14, 28.0),
            (17, 28.0),
            (18, 24.0),
            (32, 24.0),
            (1000, 24.0),
        ];
        for (len, px) in cases {
            assert_eq!(font_step(len), px, "len {len}");
        }
    }

    #[test]
    fn font_step_never_grows_with_length() {
        let mut prev = f32::MAX;
        for len in 0..=40 {
            let px = font_step(len);
            assert!(px <= prev);
            prev = px;
        }
    }

    #[test]
    fn long_press_zero_replaces_the_typed_zero_once() {
        let mut f = String::new();
        assert_eq!(push(&mut f, '0'), Push::Accepted);
        assert!(long_press_zero(&mut f));
        assert_eq!(f, "+");
        assert!(!long_press_zero(&mut f), "exactly once");
        assert_eq!(f, "+");
    }

    #[test]
    fn long_press_zero_only_touches_the_last_char() {
        let mut f = String::from("1005");
        assert!(!long_press_zero(&mut f));
        assert_eq!(f, "1005");
        let mut f = String::from("100");
        assert!(long_press_zero(&mut f));
        assert_eq!(f, "10+");
        let mut f = String::new();
        assert!(!long_press_zero(&mut f));
    }

    #[test]
    fn a_typed_number_survives_dial_validation_raw() {
        let mut f = String::new();
        for k in "*#06#".chars() {
            push(&mut f, k);
        }
        assert_eq!(validate(&f), Ok("*#06#"));
    }

    // ---- is_emergency --------------------------------------------------

    fn list(numbers: &[&str]) -> crate::wire::EmergencyList {
        crate::wire::EmergencyList {
            numbers: numbers.iter().map(|n| (*n).to_owned()).collect(),
        }
    }

    #[test]
    fn the_always_present_numbers_need_no_list() {
        let none = list(&[]);
        assert!(is_emergency("112", &none));
        assert!(is_emergency("911", &none));
    }

    #[test]
    fn the_country_list_extends_it() {
        let tr = list(&["155", "110", "1 1 2"]);
        assert!(is_emergency("155", &tr));
        assert!(is_emergency("110", &tr));
        assert!(!is_emergency("155", &list(&[])));
    }

    #[test]
    fn separators_are_ignored() {
        let none = list(&[]);
        for typed in [
            "1 1 2",
            "1-1-2",
            "1.1.2",
            " 112 ",
            "(1)12",
            "1\u{a0}12",
            "1\u{200e}12",
        ] {
            assert!(is_emergency(typed, &none), "{typed:?}");
        }
    }

    #[test]
    fn anything_else_is_an_ordinary_dial_string() {
        let tr = list(&["155"]);
        for typed in [
            "*#06#", "*112#", "+112", "112,", "112;", "1122", "11", "", "   ", "abc", "#112",
            "0112", "1127",
        ] {
            assert!(!is_emergency(typed, &tr), "{typed:?}");
        }
        // Longer than any emergency number: no buffer overrun, just false.
        assert!(!is_emergency("111111111111111111", &tr));
    }

    #[test]
    fn a_prefix_of_an_emergency_number_is_not_one_yet() {
        let none = list(&[]);
        assert!(!is_emergency("1", &none));
        assert!(!is_emergency("11", &none));
        assert!(!is_emergency("91", &none));
    }

    #[test]
    fn held_delete_speeds_up_to_a_floor() {
        assert_eq!(erase_interval_ms(0), ERASE_FIRST_MS);
        let steps: Vec<u64> = (0..20).map(erase_interval_ms).collect();
        assert!(steps.windows(2).all(|w| w[1] <= w[0]), "{steps:?}");
        assert_eq!(erase_interval_ms(u32::MAX), ERASE_FASTEST_MS);
        assert!(steps.iter().all(|ms| *ms >= ERASE_FASTEST_MS));
    }
}
