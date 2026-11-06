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

/// Replaces the just-typed trailing `from` with `to`. Never touches the first
/// character, so a lone `*` or `#` (a USSD or MMI code start) stays as typed.
fn replace_trailing(field: &mut String, from: char, to: char) -> bool {
    if field.len() > 1 && field.ends_with(from) {
        field.pop();
        field.push(to);
        true
    } else {
        false
    }
}

/// Long-press on `*` turns the `*` that the press just typed into `,` (a
/// two-second pause). Only the last character, only once (afterwards the
/// field ends in `,`), and never the first character.
pub fn long_press_star(field: &mut String) -> bool {
    replace_trailing(field, '*', ',')
}

/// Long-press on `#` turns the `#` that the press just typed into `;` (wait
/// for confirmation). Same rules as [`long_press_star`].
pub fn long_press_hash(field: &mut String) -> bool {
    replace_trailing(field, '#', ';')
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

/// An action in the keypad number field's long-press menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FieldAction {
    Copy,
    Paste,
    Clear,
}

impl FieldAction {
    pub fn label(self) -> &'static str {
        match self {
            Self::Copy => "Copy",
            Self::Paste => "Paste",
            Self::Clear => "Clear",
        }
    }
}

/// The pill after a field-menu action that has feedback of its own (Paste
/// reports through `paste_press`).
pub fn field_done_text(action: FieldAction) -> &'static str {
    match action {
        FieldAction::Copy => "Copied",
        FieldAction::Clear => "Cleared",
        FieldAction::Paste => "Pasted",
    }
}

/// The menu for the number field: Copy and Clear need typed digits, Paste
/// needs text on the clipboard.
pub fn field_menu(dialed: &str, clipboard_has_text: bool) -> Vec<FieldAction> {
    let mut out = Vec::new();
    if !dialed.is_empty() {
        out.push(FieldAction::Copy);
    }
    if clipboard_has_text {
        out.push(FieldAction::Paste);
    }
    if !dialed.is_empty() {
        out.push(FieldAction::Clear);
    }
    out
}

/// Most numbers [`extract_numbers`] returns.
pub const MAX_EXTRACTED: usize = 8;

/// Characters that may sit inside a run that looks like a phone number.
fn is_run_char(c: char) -> bool {
    matches!(
        c,
        '0'..='9' | '*' | '#' | '+' | '(' | ')' | '-' | '.' | ' ' | ',' | ';'
    )
}

/// Whether a number run ends right before the char at `at`.
fn ends_run(text: &str, at: usize, c: char) -> bool {
    match c {
        '\n' | '\r' => true,
        ',' | ';' | '/' => text[at + c.len_utf8()..]
            .chars()
            .next()
            .is_some_and(char::is_whitespace),
        ' ' => text
            .get(at..at + 4)
            .is_some_and(|s| s.eq_ignore_ascii_case(" or ")),
        _ => false,
    }
}

fn keep_run(cleaned: &str) -> bool {
    let digits = cleaned.chars().filter(char::is_ascii_digit).count();
    (3..=MAX_LEN).contains(&digits)
        || ((1..=MAX_LEN).contains(&digits) && cleaned.contains(['*', '#']))
}

/// Phone numbers found in free text (a message, an e-mail, a web page),
/// each cleaned like [`normalise_paste`]. Runs of digits with `+ ( ) - .` and
/// spaces form a number; a letter, a newline, `, ` / `; ` / `/ ` or ` or `
/// ends it. A run needs 3 to [`MAX_LEN`] digits. Numbers that
/// `PhoneNumber::matches` an earlier one are dropped, and at most
/// [`MAX_EXTRACTED`] are returned. A `tel:` URI gives one entry.
pub fn extract_numbers(text: &str) -> Vec<String> {
    let trimmed = text.trim();
    let is_uri = trimmed
        .get(..4)
        .is_some_and(|s| s.eq_ignore_ascii_case("tel:"));
    let mut found: Vec<String> = Vec::new();
    let push = |run: &str, found: &mut Vec<String>| {
        let cleaned = normalise_paste(run);
        if keep_run(&cleaned) && !is_duplicate(&cleaned, found) {
            found.push(cleaned);
        }
    };
    if is_uri {
        push(trimmed, &mut found);
        return found;
    }
    let mut start: Option<usize> = None;
    for (at, c) in trimmed.char_indices() {
        if is_run_char(c) && !ends_run(trimmed, at, c) {
            start.get_or_insert(at);
            continue;
        }
        if let Some(from) = start.take() {
            push(&trimmed[from..at], &mut found);
            if found.len() >= MAX_EXTRACTED {
                return found;
            }
        }
    }
    if let Some(from) = start {
        push(&trimmed[from..], &mut found);
    }
    found.truncate(MAX_EXTRACTED);
    found
}

fn is_duplicate(cleaned: &str, found: &[String]) -> bool {
    let parsed = crate::PhoneNumber::parse(cleaned).ok();
    found
        .iter()
        .any(|f| match (&parsed, crate::PhoneNumber::parse(f)) {
            (Some(a), Ok(b)) => a.matches(&b),
            _ => f == cleaned,
        })
}

/// What a paste of clipboard text should do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PastePlan {
    /// No number in the text.
    Nothing,
    /// Put this number in the field.
    One(String),
    /// Ask which one ("Which number?").
    Choose(Vec<String>),
}

pub fn paste_plan(text: &str) -> PastePlan {
    let mut numbers = extract_numbers(text);
    match numbers.len() {
        0 => PastePlan::Nothing,
        1 => PastePlan::One(numbers.remove(0)),
        _ => PastePlan::Choose(numbers),
    }
}

/// The number the Call key on an empty keypad redials: the `number_raw` of
/// the newest outgoing call that has something to call. WhatsApp calls are
/// not in the provider log, so they never count.
pub fn redial_target(entries: &[crate::wire::CallLogEntry]) -> Option<String> {
    entries
        .iter()
        .filter(|e| e.kind == crate::wire::CallLogKind::Outgoing)
        .filter(|e| validate(e.number_raw.trim()).is_ok())
        .max_by_key(|e| e.date_ms)
        .map(|e| e.number_raw.trim().to_owned())
}

/// How long one tone sounds, in milliseconds.
pub const TONE_ON_MS: u64 = 160;
/// Silence between two tones, in milliseconds.
pub const TONE_GAP_MS: u64 = 90;
/// Length of one pause step, in milliseconds.
pub const PAUSE_MS: u64 = 2000;
/// Most tones one paste sends.
pub const TONE_MAX: usize = 40;

/// One step of a tone sequence.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ToneStep {
    Tone(char),
    Pause,
}

/// Tones to send during a call, built from pasted text.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TonePlan {
    pub steps: Vec<ToneStep>,
    pub tones: usize,
    pub truncated: bool,
}

/// Keeps `0-9 * #` as tones and `, ; p w` as pauses (collapsed, never
/// leading or trailing); everything else is dropped. Stops after
/// [`TONE_MAX`] tones.
pub fn tone_plan(text: &str) -> TonePlan {
    let mut plan = TonePlan::default();
    let mut pending_pause = false;
    for c in text.chars() {
        match c {
            '0'..='9' | '*' | '#' => {
                if plan.tones == TONE_MAX {
                    plan.truncated = true;
                    break;
                }
                if pending_pause {
                    plan.steps.push(ToneStep::Pause);
                    pending_pause = false;
                }
                plan.steps.push(ToneStep::Tone(c));
                plan.tones += 1;
            }
            ',' | ';' | 'p' | 'P' | 'w' | 'W' => pending_pause = plan.tones > 0,
            _ => {}
        }
    }
    plan
}

/// Feedback line after sending a plan.
pub fn tones_text(plan: &TonePlan) -> String {
    if plan.truncated {
        format!("Sent the first {} tones", plan.tones)
    } else if plan.tones == 1 {
        "Sent 1 tone".to_owned()
    } else {
        format!("Sent {} tones", plan.tones)
    }
}

/// The plan as typed text, for example `4821#,12`.
pub fn tone_preview(plan: &TonePlan) -> String {
    plan.steps
        .iter()
        .map(|s| match s {
            ToneStep::Tone(c) => *c,
            ToneStep::Pause => ',',
        })
        .collect()
}

/// Label of the keypad "Paste" button, or `None` when the clipboard holds
/// no tone.
pub fn paste_tones_label(clip: Option<&str>) -> Option<String> {
    let plan = tone_plan(clip?);
    if plan.tones == 0 {
        return None;
    }
    let preview = tone_preview(&plan);
    if preview.chars().count() > 12 {
        let cut: String = preview.chars().take(12).collect();
        Some(format!("Paste {cut}\u{2026}"))
    } else {
        Some(format!("Paste {preview}"))
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn field_menu_matrix() {
        use FieldAction::{Clear, Copy, Paste};
        let cases = [
            ("", false, vec![]),
            ("", true, vec![Paste]),
            ("123", false, vec![Copy, Clear]),
            ("123", true, vec![Copy, Paste, Clear]),
        ];
        for (dialed, clip, want) in cases {
            assert_eq!(field_menu(dialed, clip), want, "{dialed:?} {clip}");
        }
        assert_eq!(Copy.label(), "Copy");
        assert_eq!(Paste.label(), "Paste");
        assert_eq!(Clear.label(), "Clear");
    }

    #[test]
    fn field_done_texts() {
        assert_eq!(field_done_text(FieldAction::Copy), "Copied");
        assert_eq!(field_done_text(FieldAction::Clear), "Cleared");
        assert_eq!(field_done_text(FieldAction::Paste), "Pasted");
    }

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
    fn long_press_star_and_hash_replace_the_typed_char_once() {
        let mut f = String::from("123");
        assert_eq!(push(&mut f, '*'), Push::Accepted);
        assert!(long_press_star(&mut f));
        assert_eq!(f, "123,");
        assert!(!long_press_star(&mut f), "exactly once");
        assert_eq!(f, "123,");
        assert_eq!(push(&mut f, '#'), Push::Accepted);
        assert!(long_press_hash(&mut f));
        assert_eq!(f, "123,;");
        assert!(!long_press_hash(&mut f), "exactly once");
    }

    #[test]
    fn long_press_star_and_hash_never_touch_the_first_char_or_others() {
        let mut f = String::from("*");
        assert!(!long_press_star(&mut f));
        assert_eq!(f, "*");
        let mut f = String::from("#");
        assert!(!long_press_hash(&mut f));
        assert_eq!(f, "#");
        let mut f = String::from("*12#");
        assert!(!long_press_star(&mut f), "last char is #");
        assert!(long_press_hash(&mut f));
        assert_eq!(f, "*12;");
        let mut f = String::from("1#5");
        assert!(!long_press_hash(&mut f));
        assert!(!long_press_star(&mut String::new()));
    }

    #[test]
    fn pause_and_wait_survive_validation() {
        let mut f = String::from("5551234");
        push(&mut f, '*');
        long_press_star(&mut f);
        push(&mut f, '1');
        push(&mut f, '#');
        long_press_hash(&mut f);
        assert_eq!(f, "5551234,1;");
        assert_eq!(validate(&f), Ok(f.as_str()));
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

    #[test]
    fn extract_finds_two_numbers_in_a_sentence() {
        assert_eq!(
            extract_numbers("Call Anna on 0555 111 22 33, or Bob (0532) 444-55-66 today"),
            ["05551112233", "05324445566"]
        );
        assert_eq!(
            extract_numbers("a: +90 555 111 22 33; b: 0850 000 00 00\n+1 212 555 0100"),
            ["+905551112233", "08500000000", "+12125550100"]
        );
    }

    #[test]
    fn extract_handles_uri_and_single_numbers_like_normalise_paste() {
        assert_eq!(extract_numbers("tel:%2B90555;ext=12"), ["+90555"]);
        assert_eq!(extract_numbers("TEL:+90 555 111 22 33"), ["+905551112233"]);
        for single in [
            "+90 (555) 111-22-33",
            "0555.111.22.33",
            "1,2;3",
            "*#06#",
            "*123#",
        ] {
            assert_eq!(
                extract_numbers(single),
                [normalise_paste(single)],
                "{single}"
            );
        }
    }

    #[test]
    fn extract_dedupes_national_and_international() {
        assert_eq!(
            extract_numbers("+90 555 111 22 33 or 0555 111 22 33 / 0555 111 22 33"),
            ["+905551112233"]
        );
    }

    #[test]
    fn extract_drops_digit_poor_text_and_caps() {
        assert!(extract_numbers("Room 12").is_empty());
        assert!(extract_numbers("").is_empty());
        let many: Vec<String> = (0..12).map(|i| format!("0555 111 22 {i:02}")).collect();
        assert_eq!(extract_numbers(&many.join(", ")).len(), MAX_EXTRACTED);
    }

    #[test]
    fn paste_plan_variants() {
        assert_eq!(paste_plan("hello"), PastePlan::Nothing);
        assert_eq!(
            paste_plan(" 0555 111 22 33 "),
            PastePlan::One("05551112233".into())
        );
        assert_eq!(
            paste_plan("0555 111 22 33, 0532 444 55 66"),
            PastePlan::Choose(vec!["05551112233".into(), "05324445566".into()])
        );
    }

    fn out(raw: &str, date_ms: i64, kind: crate::wire::CallLogKind) -> crate::wire::CallLogEntry {
        crate::wire::CallLogEntry {
            number_raw: raw.into(),
            date_ms,
            kind,
            ..Default::default()
        }
    }

    #[test]
    fn redial_picks_newest_callable_outgoing() {
        use crate::wire::CallLogKind::*;
        assert_eq!(redial_target(&[]), None);
        let log = [
            out("111", 10, Outgoing),
            out("222", 50, Outgoing),
            out("333", 90, Incoming),
            out("444", 80, Missed),
            out("", 70, Outgoing),
            out("333 ", 60, Outgoing),
        ];
        assert_eq!(redial_target(&log).as_deref(), Some("333"));
        assert_eq!(redial_target(&log[2..4]), None);
        assert_eq!(redial_target(&[out(" ", 5, Outgoing)]), None);
    }

    #[test]
    fn tone_plan_cases() {
        let p = tone_plan("PIN: 4821 #");
        assert_eq!(tone_preview(&p), "4821#");
        assert_eq!(p.tones, 5);
        assert!(!p.truncated);
        assert_eq!(tone_preview(&tone_plan("123,,456")), "123,456");
        assert_eq!(tone_preview(&tone_plan(",12;")), "12");
        assert_eq!(tone_preview(&tone_plan("4821#W12")), "4821#,12");
        assert_eq!(
            tone_preview(&tone_plan("+90 (532) 111-22-33")),
            "905321112233"
        );
        assert_eq!(tone_plan("abc xyz"), TonePlan::default());
        let long = tone_plan(&"1".repeat(41));
        assert_eq!(long.tones, TONE_MAX);
        assert!(long.truncated);
        let exact = tone_plan(&"1".repeat(40));
        assert!(!exact.truncated);
    }

    #[test]
    fn tones_text_variants() {
        assert_eq!(tones_text(&tone_plan("12345678")), "Sent 8 tones");
        assert_eq!(tones_text(&tone_plan("5")), "Sent 1 tone");
        assert_eq!(
            tones_text(&tone_plan(&"1".repeat(50))),
            "Sent the first 40 tones"
        );
    }

    #[test]
    fn paste_label_variants() {
        assert_eq!(paste_tones_label(None), None);
        assert_eq!(paste_tones_label(Some("hello")), None);
        assert_eq!(paste_tones_label(Some(",;")), None);
        assert_eq!(
            paste_tones_label(Some("PIN 4821#")).as_deref(),
            Some("Paste 4821#")
        );
        assert_eq!(
            paste_tones_label(Some("1234567890123456")).as_deref(),
            Some("Paste 123456789012\u{2026}")
        );
    }
}
