//! The notification digest for one conversation, and the headless SMS path.
//!
//! The platform layer receives a [`NotificationSpec`] as JSON and only
//! renders it.

use serde::{Deserialize, Serialize};

use crate::blocklist::BlockSet;
use crate::otp::{self, Otp};
use crate::spam::{self, SpamRules, Verdict};

/// Lines shown in the expanded notification.
pub const MAX_LINES: usize = 6;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Incoming {
    /// The conversation key and notification tag: `sms:<id>` or `wa:<jid>`.
    pub thread: String,
    pub sender_title: String,
    pub body: String,
    pub at_ms: i64,
    pub verdict_spam: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NotificationSpec {
    pub tag: String,
    pub title: String,
    /// Oldest first.
    pub lines: Vec<String>,
    /// A passcode for a "Copy code" action.
    pub otp: Option<String>,
    /// Whether to offer inline reply. Not for passcodes.
    pub reply: bool,
    /// Update the digest without alerting.
    pub silent: bool,
}

/// Builds the digest from the conversation's recent messages (oldest first).
/// Spam is left out; `None` when nothing is left. The notification stays
/// silent when the newest message was spam, since the user was already told
/// about the older ones.
pub fn build(
    thread_title: &str,
    is_group: bool,
    recent: &[Incoming],
    otp: Option<Otp>,
) -> Option<NotificationSpec> {
    let mut ham: Vec<&Incoming> = recent.iter().filter(|m| !m.verdict_spam).collect();
    let tag = ham.last()?.thread.clone();
    ham.sort_by_key(|m| m.at_ms);
    let skip = ham.len().saturating_sub(MAX_LINES);
    let lines = ham[skip..]
        .iter()
        .map(|m| {
            if is_group && !m.sender_title.is_empty() {
                format!("{}: {}", m.sender_title, m.body)
            } else {
                m.body.clone()
            }
        })
        .collect();
    Some(NotificationSpec {
        tag,
        title: thread_title.to_owned(),
        lines,
        reply: otp.is_none(),
        otp: otp.map(|o| o.code),
        silent: recent.last().is_some_and(|m| m.verdict_spam),
    })
}

#[derive(Deserialize)]
struct HeadlessIn {
    #[serde(default)]
    sender: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    known: bool,
    #[serde(default)]
    rules: SpamRules,
}

#[derive(Serialize)]
struct HeadlessOut {
    spam: bool,
    reason: Option<&'static str>,
    otp: Option<String>,
}

/// The receiver path with no UI process: `{sender, body, known, rules}` in,
/// `{spam, reason, otp}` out. Bad input is treated as ham.
pub fn headless_sms(json: &str) -> String {
    let out = match serde_json::from_str::<HeadlessIn>(json) {
        Ok(i) => {
            let verdict =
                spam::classify(&i.rules, &i.sender, &i.body, i.known, &BlockSet::default());
            HeadlessOut {
                spam: verdict.is_spam(),
                reason: match verdict {
                    Verdict::Spam(r) => Some(r),
                    Verdict::Ham => None,
                },
                otp: otp::detect(&i.body).map(|o| o.code),
            }
        }
        Err(_) => HeadlessOut {
            spam: false,
            reason: None,
            otp: None,
        },
    };
    serde_json::to_string(&out)
        .unwrap_or_else(|_| "{\"spam\":false,\"reason\":null,\"otp\":null}".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(i: i64, who: &str, body: &str, spam: bool) -> Incoming {
        Incoming {
            thread: "sms:7".into(),
            sender_title: who.into(),
            body: body.into(),
            at_ms: i,
            verdict_spam: spam,
        }
    }

    #[test]
    fn line_cap_keeps_the_newest() {
        let recent: Vec<_> = (0..10)
            .map(|i| msg(i, "Ali", &format!("m{i}"), false))
            .collect();
        let spec = build("Ali", false, &recent, None).expect("spec");
        assert_eq!(spec.lines.len(), MAX_LINES);
        assert_eq!(spec.lines.first().map(String::as_str), Some("m4"));
        assert_eq!(spec.lines.last().map(String::as_str), Some("m9"));
        assert_eq!(spec.tag, "sms:7");
        assert_eq!(spec.title, "Ali");
        assert!(spec.reply && !spec.silent && spec.otp.is_none());
    }

    #[test]
    fn group_lines_are_prefixed() {
        let recent = [msg(1, "Ayşe", "selam", false), msg(2, "Can", "nbr", false)];
        let spec = build("Family", true, &recent, None).expect("spec");
        assert_eq!(spec.lines, ["Ayşe: selam", "Can: nbr"]);
        let solo = build("Family", false, &recent, None).expect("spec");
        assert_eq!(solo.lines, ["selam", "nbr"]);
    }

    #[test]
    fn spam_is_dropped() {
        let all_spam = [msg(1, "x", "win", true), msg(2, "x", "win", true)];
        assert_eq!(build("x", false, &all_spam, None), None);
        assert_eq!(build("x", false, &[], None), None);
        let mixed = [msg(1, "x", "hi", false), msg(2, "x", "win", true)];
        let spec = build("x", false, &mixed, None).expect("spec");
        assert_eq!(spec.lines, ["hi"]);
        assert!(spec.silent);
    }

    #[test]
    fn otp_is_filled_and_disables_reply() {
        let recent = [msg(1, "Bank", "Your code is 482913", false)];
        let otp = otp::detect(&recent[0].body);
        let spec = build("Bank", false, &recent, otp).expect("spec");
        assert_eq!(spec.otp.as_deref(), Some("482913"));
        assert!(!spec.reply);
    }

    #[test]
    fn spec_serializes_for_kotlin() {
        let spec = build("A", false, &[msg(1, "A", "hi", false)], None).expect("spec");
        let json = serde_json::to_string(&spec).expect("json");
        assert_eq!(
            serde_json::from_str::<NotificationSpec>(&json).expect("de"),
            spec
        );
        assert!(json.contains("\"tag\":\"sms:7\""));
    }

    fn headless(json: &str) -> serde_json::Value {
        serde_json::from_str(&headless_sms(json)).expect("json out")
    }

    #[test]
    fn headless_paths() {
        let v = headless(
            r#"{"sender":"4455","body":"Your code is 482913","known":false,"rules":{"rules":[{"short_code_only":true}]}}"#,
        );
        assert_eq!(v["spam"], false);
        assert_eq!(v["otp"], "482913");

        let v = headless(
            r#"{"sender":"promo","body":"BAHIS firsati","rules":{"rules":[{"keyword":{"text":"bahis"}}]}}"#,
        );
        assert_eq!(v["spam"], true);
        assert_eq!(v["reason"], "keyword_rule");
        assert!(v["otp"].is_null());

        let v = headless(
            r#"{"sender":"promo","body":"bahis","known":true,"rules":{"rules":[{"keyword":{"text":"bahis"}}]}}"#,
        );
        assert_eq!(v["spam"], false);

        for bad in ["", "nope", "[]"] {
            let v = headless(bad);
            assert_eq!(v["spam"], false, "{bad}");
        }
    }
}
