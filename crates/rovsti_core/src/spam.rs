//! User spam rules and the verdict for one incoming message.
//!
//! Precedence in [`classify`]: allow list and known contacts are ham; a
//! passcode is ham unless the sender is blocked; a blocked sender is spam;
//! then the user rules; then [`SpamRule::UnknownWithLink`].

use serde::{Deserialize, Serialize};

use crate::blocklist::BlockSet;
use crate::otp;
use crate::phone_number::PhoneNumber;

/// At most this many rules; the list is evaluated per message.
pub const MAX_RULES: usize = 64;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SpamRule {
    /// Sender id or number; `*` is a wildcard, matching is case-insensitive.
    Sender { pattern: String },
    /// Case-insensitive substring of the body.
    Keyword { text: String },
    /// A link from a sender that is not a contact.
    UnknownWithLink,
    /// When `true`, short-code senders (3 to 6 digits) are spam. Passcodes
    /// from them are still ham.
    ShortCodeOnly(bool),
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SpamRules {
    #[serde(default = "enabled_by_default")]
    pub enabled: bool,
    #[serde(default)]
    pub rules: Vec<SpamRule>,
    /// Senders that are never spam.
    #[serde(default)]
    pub allow: Vec<String>,
}

fn enabled_by_default() -> bool {
    true
}

impl Default for SpamRules {
    fn default() -> Self {
        Self {
            enabled: true,
            rules: Vec::new(),
            allow: Vec::new(),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    Ham,
    /// The reason is a stable machine key.
    Spam(&'static str),
}

impl Verdict {
    pub fn is_spam(self) -> bool {
        matches!(self, Verdict::Spam(_))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum RuleError {
    #[error("the rule is empty")]
    Empty,
    #[error("the rule already exists")]
    Duplicate,
    #[error("at most {MAX_RULES} rules are allowed")]
    TooMany,
}

fn fold(s: &str) -> String {
    s.trim()
        .chars()
        .map(|c| match c {
            'İ' | 'ı' | 'I' => 'i',
            'ş' | 'Ş' => 's',
            'ğ' | 'Ğ' => 'g',
            'ü' | 'Ü' => 'u',
            'ö' | 'Ö' => 'o',
            'ç' | 'Ç' => 'c',
            other => other.to_ascii_lowercase(),
        })
        .collect()
}

fn normalized(rule: SpamRule) -> SpamRule {
    match rule {
        SpamRule::Sender { pattern } => SpamRule::Sender {
            pattern: pattern.trim().to_owned(),
        },
        SpamRule::Keyword { text } => SpamRule::Keyword {
            text: text.trim().to_owned(),
        },
        other => other,
    }
}

fn same_rule(a: &SpamRule, b: &SpamRule) -> bool {
    match (a, b) {
        (SpamRule::Sender { pattern: a }, SpamRule::Sender { pattern: b }) => fold(a) == fold(b),
        (SpamRule::Keyword { text: a }, SpamRule::Keyword { text: b }) => fold(a) == fold(b),
        (SpamRule::UnknownWithLink, SpamRule::UnknownWithLink) => true,
        // Only one short-code rule makes sense whatever its flag.
        (SpamRule::ShortCodeOnly(_), SpamRule::ShortCodeOnly(_)) => true,
        _ => false,
    }
}

impl SpamRules {
    /// Adds `rule` after trimming it. Rejects empty text, duplicates (case
    /// insensitive) and a list longer than [`MAX_RULES`].
    pub fn add_rule(&mut self, rule: SpamRule) -> Result<(), RuleError> {
        let rule = normalized(rule);
        let empty = match &rule {
            SpamRule::Sender { pattern } => pattern.replace('*', "").trim().is_empty(),
            SpamRule::Keyword { text } => text.is_empty(),
            _ => false,
        };
        if empty {
            return Err(RuleError::Empty);
        }
        if self.rules.iter().any(|r| same_rule(r, &rule)) {
            return Err(RuleError::Duplicate);
        }
        if self.rules.len() >= MAX_RULES {
            return Err(RuleError::TooMany);
        }
        self.rules.push(rule);
        Ok(())
    }

    /// Puts a rule back at `index` (clamped to the end), e.g. to undo a delete.
    /// `false` when the list is full.
    pub fn insert_rule(&mut self, index: usize, rule: SpamRule) -> bool {
        if self.rules.len() >= MAX_RULES {
            return false;
        }
        let index = index.min(self.rules.len());
        self.rules.insert(index, rule);
        true
    }

    pub fn remove_rule(&mut self, index: usize) -> bool {
        if index < self.rules.len() {
            self.rules.remove(index);
            true
        } else {
            false
        }
    }
}

/// Free-function form of [`SpamRules::add_rule`].
pub fn add_rule(rules: &mut SpamRules, rule: SpamRule) -> Result<(), RuleError> {
    rules.add_rule(rule)
}

fn same_sender(a: &str, b: &str) -> bool {
    match (PhoneNumber::parse(a), PhoneNumber::parse(b)) {
        (Ok(a), Ok(b)) => a.matches(&b),
        _ => fold(a) == fold(b),
    }
}

/// `*` matches any run of characters; everything else matches itself.
fn glob(pattern: &[char], text: &[char]) -> bool {
    let (mut p, mut t) = (0, 0);
    let (mut star, mut mark) = (None, 0);
    while t < text.len() {
        if p < pattern.len() && pattern[p] == '*' {
            star = Some(p);
            mark = t;
            p += 1;
        } else if p < pattern.len() && pattern[p] == text[t] {
            p += 1;
            t += 1;
        } else if let Some(s) = star {
            p = s + 1;
            mark += 1;
            t = mark;
        } else {
            return false;
        }
    }
    pattern[p..].iter().all(|&c| c == '*')
}

fn sender_matches(pattern: &str, sender: &str) -> bool {
    if pattern.contains('*') {
        let p: Vec<char> = fold(pattern).chars().collect();
        let t: Vec<char> = fold(sender).chars().collect();
        glob(&p, &t)
    } else {
        same_sender(pattern, sender)
    }
}

fn is_short_code(sender: &str) -> bool {
    let s = sender.trim();
    (3..=6).contains(&s.len()) && s.chars().all(|c| c.is_ascii_digit())
}

/// Whether `body` holds something that looks like a link. A small scanner,
/// no regex: `http(s)://`, `www.`, or a bare `host.tld` with a path or a
/// well-known TLD.
pub fn contains_link(body: &str) -> bool {
    !links(body).is_empty()
}

/// The links in `body`, in order, without duplicates, ready to open: the
/// original case is kept and `https://` is prepended when a token has no
/// scheme. Uses the same scanner as [`contains_link`].
pub fn links(body: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for raw in body.split(|c: char| c.is_whitespace()) {
        if let Some(url) = link_token(raw)
            && !out.contains(&url)
        {
            out.push(url);
        }
    }
    out
}

/// One "Open link" pill: its label and the URL it opens.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OpenLink {
    pub label: String,
    pub url: String,
}

/// Up to `max` pills for the links of `body`: "Open link" for a single link,
/// the host (at most 24 characters) when there are several.
pub fn open_links(body: &str, max: usize) -> Vec<OpenLink> {
    let found = links(body);
    let several = found.len() > 1;
    found
        .into_iter()
        .take(max)
        .map(|url| {
            let label = if several {
                host_label(&url)
            } else {
                "Open link".to_owned()
            };
            OpenLink { label, url }
        })
        .collect()
}

/// The URL without scheme and path, cut to 24 characters with an ellipsis.
fn host_label(url: &str) -> String {
    let rest = url.split_once("://").map_or(url, |(_, rest)| rest);
    let host = rest.split(['/', '?', '#']).next().unwrap_or(rest);
    if host.chars().count() > 24 {
        let cut: String = host.chars().take(23).collect();
        format!("{cut}\u{2026}")
    } else {
        host.to_owned()
    }
}

/// The openable URL for one whitespace-free token, if it looks like a link.
fn link_token(raw: &str) -> Option<String> {
    const TLDS: [&str; 30] = [
        "com", "net", "org", "io", "ly", "me", "co", "tr", "info", "biz", "xyz", "top", "link",
        "click", "app", "gl", "cc", "ru", "cn", "tk", "ml", "ga", "cf", "gq", "online", "site",
        "shop", "live", "icu", "vip",
    ];
    let shown = raw.trim_matches(|c: char| "()<>[]{}\"'`,;!?.".contains(c));
    let token = shown.to_ascii_lowercase();
    if token.starts_with("http://") || token.starts_with("https://") {
        return (token.len() > 8).then(|| shown.to_owned());
    }
    if token.starts_with("www.") {
        return (token.len() > 6).then(|| format!("https://{shown}"));
    }
    let (host, rest) = match token.find(['/', '?', '#']) {
        Some(i) => (&token[..i], Some(&token[i..])),
        None => (token.as_str(), None),
    };
    let labels: Vec<&str> = host.split('.').collect();
    if labels.len() < 2 {
        return None;
    }
    let host_ok = labels.iter().all(|l| {
        !l.is_empty()
            && !l.starts_with('-')
            && !l.ends_with('-')
            && l.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
    });
    let tld = labels[labels.len() - 1];
    if !host_ok || tld.len() < 2 || !tld.chars().all(|c| c.is_ascii_alphabetic()) {
        return None;
    }
    (TLDS.contains(&tld) || rest.is_some_and(|r| r.len() > 1)).then(|| format!("https://{shown}"))
}

pub fn classify(
    rules: &SpamRules,
    sender_raw: &str,
    body: &str,
    known_contact: bool,
    blocked: &BlockSet,
) -> Verdict {
    if known_contact || rules.allow.iter().any(|a| same_sender(a, sender_raw)) {
        return Verdict::Ham;
    }
    let is_blocked = blocked.is_blocked(sender_raw);
    if !is_blocked && otp::detect(body).is_some() {
        return Verdict::Ham;
    }
    if is_blocked {
        return Verdict::Spam("blocked");
    }
    if !rules.enabled {
        return Verdict::Ham;
    }
    let folded_body = fold(body);
    for rule in &rules.rules {
        match rule {
            SpamRule::Sender { pattern } if sender_matches(pattern, sender_raw) => {
                return Verdict::Spam("sender_rule");
            }
            SpamRule::Keyword { text } if folded_body.contains(&fold(text)) => {
                return Verdict::Spam("keyword_rule");
            }
            SpamRule::ShortCodeOnly(true) if is_short_code(sender_raw) => {
                return Verdict::Spam("short_code");
            }
            _ => {}
        }
    }
    if rules.rules.contains(&SpamRule::UnknownWithLink) && contains_link(body) {
        return Verdict::Spam("unknown_link");
    }
    Verdict::Ham
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::BlockedNumber;

    #[test]
    fn open_links_single_and_several() {
        let one = open_links("see https://a.com/x now", 3);
        assert_eq!(one.len(), 1);
        assert_eq!(one[0].label, "Open link");
        assert_eq!(one[0].url, "https://a.com/x");
        let many = open_links("a.com b.org https://c.net/p?q=1 www.d.io e.com", 3);
        let labels: Vec<_> = many.iter().map(|l| l.label.as_str()).collect();
        assert_eq!(labels, ["a.com", "b.org", "c.net"]);
        assert!(open_links("no links here", 3).is_empty());
    }

    #[test]
    fn open_links_truncates_long_hosts() {
        let l = open_links("https://averyveryverylonghostname-example.com/x b.com", 3);
        assert_eq!(l[0].label.chars().count(), 24);
        assert!(l[0].label.ends_with('\u{2026}'));
    }

    fn rules(list: Vec<SpamRule>) -> SpamRules {
        SpamRules {
            rules: list,
            ..SpamRules::default()
        }
    }

    fn blocked(number: &str) -> BlockSet {
        BlockSet::from_rows(&[BlockedNumber {
            number_raw: number.into(),
            ..BlockedNumber::default()
        }])
    }

    fn kw(t: &str) -> SpamRule {
        SpamRule::Keyword { text: t.into() }
    }

    #[test]
    fn defaults_are_ham() {
        let none = BlockSet::default();
        assert_eq!(
            classify(
                &SpamRules::default(),
                "+905321234567",
                "selam",
                false,
                &none
            ),
            Verdict::Ham
        );
    }

    #[test]
    fn precedence() {
        let none = BlockSet::default();
        let r = SpamRules {
            allow: vec!["PROMO".into()],
            ..rules(vec![
                kw("kazandınız"),
                SpamRule::Sender {
                    pattern: "promo*".into(),
                },
            ])
        };
        // Allow list beats rules.
        assert_eq!(
            classify(&r, "promo", "kazandınız", false, &none),
            Verdict::Ham
        );
        // Known contact beats rules and block.
        let b = blocked("+905321234567");
        assert_eq!(
            classify(&r, "+905321234567", "kazandınız", true, &b),
            Verdict::Ham
        );
        // OTP beats rules ...
        assert_eq!(
            classify(&r, "promo2", "kazandınız, kodunuz 482913", false, &none),
            Verdict::Ham
        );
        // ... but not a block.
        assert_eq!(
            classify(&r, "+905321234567", "Your code is 482913", false, &b),
            Verdict::Spam("blocked")
        );
        // Block beats ham text.
        assert_eq!(
            classify(&r, "0532 123 45 67", "selam", false, &b),
            Verdict::Spam("blocked")
        );
        // Rules.
        assert_eq!(
            classify(&r, "x", "KAZANDINIZ!", false, &none),
            Verdict::Spam("keyword_rule")
        );
        assert_eq!(
            classify(&r, "PROMO9", "hi", false, &none),
            Verdict::Spam("sender_rule")
        );
    }

    #[test]
    fn disabled_rules_do_not_apply_but_blocks_do() {
        let mut r = rules(vec![kw("win")]);
        r.enabled = false;
        assert_eq!(
            classify(&r, "x", "you win", false, &BlockSet::default()),
            Verdict::Ham
        );
        assert!(classify(&r, "1234", "hi", false, &blocked("1234")).is_spam());
    }

    #[test]
    fn unknown_with_link_runs_last_and_needs_the_rule() {
        let none = BlockSet::default();
        let r = rules(vec![SpamRule::UnknownWithLink]);
        assert_eq!(
            classify(&r, "x", "claim at http://a.tk/x", false, &none),
            Verdict::Spam("unknown_link")
        );
        assert_eq!(
            classify(&r, "x", "claim at http://a.tk/x", true, &none),
            Verdict::Ham
        );
        assert_eq!(
            classify(&r, "x", "no link here", false, &none),
            Verdict::Ham
        );
        assert_eq!(
            classify(&SpamRules::default(), "x", "http://a.tk", false, &none),
            Verdict::Ham
        );
        let both = rules(vec![SpamRule::UnknownWithLink, kw("claim")]);
        assert_eq!(
            classify(&both, "x", "claim http://a.tk", false, &none),
            Verdict::Spam("keyword_rule")
        );
    }

    #[test]
    fn short_code_rule() {
        let none = BlockSet::default();
        let on = rules(vec![SpamRule::ShortCodeOnly(true)]);
        let off = rules(vec![SpamRule::ShortCodeOnly(false)]);
        assert_eq!(
            classify(&on, "4455", "kampanya", false, &none),
            Verdict::Spam("short_code")
        );
        assert_eq!(
            classify(&on, "4455", "kod 482913", false, &none),
            Verdict::Ham
        );
        assert_eq!(
            classify(&on, "+905321234567", "x", false, &none),
            Verdict::Ham
        );
        assert_eq!(
            classify(&off, "4455", "kampanya", false, &none),
            Verdict::Ham
        );
    }

    #[test]
    fn link_scanner() {
        for yes in [
            "see https://x.co/a",
            "WWW.example.com",
            "go to bit.ly/3abc now",
            "(example.com).",
            "visit evil.tk",
            "a.b.shop",
        ] {
            assert!(contains_link(yes), "{yes}");
        }
        for no in [
            "hello.how are you",
            "v1.2",
            "3.14 tl",
            "end of sentence.Next one",
            "https://",
            "a@b",
            "file.txt",
        ] {
            assert!(!contains_link(no), "{no}");
        }
    }

    #[test]
    fn links_are_openable() {
        assert_eq!(links("see https://x.co/a"), ["https://x.co/a"]);
        assert_eq!(links("go to bit.ly/3abc now"), ["https://bit.ly/3abc"]);
        assert_eq!(links("(Example.com)."), ["https://Example.com"]);
        assert_eq!(links("www.a.com"), ["https://www.a.com"]);
        assert_eq!(
            links("a.com then http://b.org/x"),
            ["https://a.com", "http://b.org/x"]
        );
        assert_eq!(links("a.com and a.com"), ["https://a.com"]);
        for no in [
            "hello.how are you",
            "v1.2",
            "3.14 tl",
            "end of sentence.Next one",
            "https://",
            "a@b",
            "file.txt",
        ] {
            assert!(links(no).is_empty(), "{no}");
        }
    }

    #[test]
    fn glob_patterns() {
        assert!(sender_matches("PROMO*", "promo123"));
        assert!(sender_matches("*bank*", "My-Bank-TR"));
        assert!(sender_matches("a*c", "abbc"));
        assert!(!sender_matches("a*c", "abbd"));
        assert!(sender_matches("+90850*", "+908501234567"));
        // No wildcard: whole-sender, number-tolerant.
        assert!(sender_matches("05321234567", "+905321234567"));
        assert!(!sender_matches("promo", "promo2"));
    }

    #[test]
    fn rule_validation() {
        let mut r = SpamRules::default();
        assert_eq!(r.add_rule(kw("  ")), Err(RuleError::Empty));
        assert_eq!(
            r.add_rule(SpamRule::Sender {
                pattern: " * ".into()
            }),
            Err(RuleError::Empty)
        );
        assert_eq!(r.add_rule(kw(" Bahis ")), Ok(()));
        assert_eq!(r.add_rule(kw("bahis")), Err(RuleError::Duplicate));
        assert_eq!(r.rules, vec![kw("Bahis")]);
        assert_eq!(r.add_rule(SpamRule::UnknownWithLink), Ok(()));
        assert_eq!(
            r.add_rule(SpamRule::UnknownWithLink),
            Err(RuleError::Duplicate)
        );
        assert_eq!(r.add_rule(SpamRule::ShortCodeOnly(true)), Ok(()));
        assert_eq!(
            r.add_rule(SpamRule::ShortCodeOnly(false)),
            Err(RuleError::Duplicate)
        );
        while r.rules.len() < MAX_RULES {
            let n = r.rules.len();
            r.add_rule(kw(&format!("k{n}"))).expect("room");
        }
        assert_eq!(r.add_rule(kw("one more")), Err(RuleError::TooMany));
        assert!(r.remove_rule(0));
        assert!(!r.remove_rule(MAX_RULES));
        assert_eq!(add_rule(&mut r, kw("one more")), Ok(()));
    }

    #[test]
    fn insert_rule_restores_position_and_clamps() {
        let mut r = SpamRules::default();
        r.add_rule(kw("a")).expect("add");
        r.add_rule(kw("b")).expect("add");
        r.add_rule(kw("c")).expect("add");
        let removed = r.rules[1].clone();
        assert!(r.remove_rule(1));
        assert!(r.insert_rule(1, removed.clone()));
        assert_eq!(r.rules[1], removed);
        assert_eq!(r.rules.len(), 3);
        assert!(r.insert_rule(99, kw("z")));
        assert_eq!(r.rules.last(), Some(&kw("z")));
        while r.rules.len() < MAX_RULES {
            r.add_rule(kw(&format!("k{}", r.rules.len())))
                .expect("room");
        }
        assert!(!r.insert_rule(0, kw("full")));
    }

    #[test]
    fn serde_round_trip_and_defaults() {
        let mut r = SpamRules::default();
        r.add_rule(kw("a")).expect("add");
        r.add_rule(SpamRule::ShortCodeOnly(true)).expect("add");
        let json = serde_json::to_string(&r).expect("json");
        assert_eq!(serde_json::from_str::<SpamRules>(&json).expect("de"), r);
        let empty: SpamRules = serde_json::from_str("{}").expect("de");
        assert!(empty.enabled && empty.rules.is_empty() && empty.allow.is_empty());
    }
}
