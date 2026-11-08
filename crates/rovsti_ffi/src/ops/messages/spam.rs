//! `spam.*` and `notify.build`: the spam rules, links in a message and the notification digest.

use super::NOW;
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::blocklist::BlockSet;
use rovsti_core::notify::{self, Incoming, NotificationSpec};
use rovsti_core::otp::Otp;
use rovsti_core::spam::{self, OpenLink, SpamRule, SpamRules, Verdict};
use rovsti_core::wire::BlockedNumber;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "spam.classify",
        ClassifyReq,
        ClassifyAns,
        classify,
        classify_cases
    ),
    op!("spam.add_rule", AddReq, RulesAns, add_rule, add_rule_cases),
    op!(
        "spam.insert_rule",
        InsertReq,
        InsertAns,
        insert_rule,
        insert_rule_cases
    ),
    op!(
        "spam.remove_rule",
        RemoveReq,
        RemoveAns,
        remove_rule,
        remove_rule_cases
    ),
    op!("spam.links", BodyReq, LinksAns, links, links_cases),
    op!(
        "spam.open_links",
        OpenLinksReq,
        OpenLinksAns,
        open_links,
        open_links_cases
    ),
    op!(
        "spam.contains_link",
        BodyReq,
        ContainsAns,
        contains_link,
        contains_cases
    ),
    op!(
        "notify.build",
        NotifyReq,
        NotifyAns,
        notify_build,
        notify_cases
    ),
];

fn rules() -> Value {
    json!({"enabled": true, "rules": ["unknown_with_link", {"keyword": {"text": "win"}}, {"sender": {"pattern": "PROMO*"}}],
           "allow": ["BANK"]})
}

// ---- classify ----

#[derive(Deserialize)]
struct ClassifyReq {
    #[serde(default)]
    rules: SpamRules,
    sender: String,
    body: String,
    /// The sender is in the contacts.
    #[serde(default)]
    known: bool,
    /// The system blocklist rows.
    #[serde(default)]
    blocked: Vec<BlockedNumber>,
}

#[derive(Serialize)]
struct ClassifyAns {
    spam: bool,
    /// A stable machine key: `blocked`, `sender_rule`, `keyword_rule`, `short_code`, `unknown_link`.
    reason: Option<&'static str>,
}

fn classify(req: ClassifyReq) -> Result<ClassifyAns, FfiError> {
    let verdict = spam::classify(
        &req.rules,
        &req.sender,
        &req.body,
        req.known,
        &BlockSet::from_rows(&req.blocked),
    );
    Ok(ClassifyAns {
        spam: verdict.is_spam(),
        reason: match verdict {
            Verdict::Spam(reason) => Some(reason),
            Verdict::Ham => None,
        },
    })
}

fn classify_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"rules": rules(), "sender": "PROMO-SHOP", "body": "Sale today"})),
        Case::named(
            "link_from_stranger",
            json!({"rules": rules(), "sender": "+49 151 2345678", "body": "Claim at bit.ly/x1"}),
        ),
        Case::named(
            "passcode_is_ham",
            json!({"rules": rules(), "sender": "12345", "body": "Your code is 482913"}),
        ),
        Case::named(
            "allowed",
            json!({"rules": rules(), "sender": "BANK", "body": "win win win"}),
        ),
        Case::named(
            "blocked",
            json!({"sender": "+49 151 2345678", "body": "Hallo",
                   "blocked": [{"id": 1, "number_raw": "+49 151 2345678", "normalized": "+491512345678"}]}),
        ),
    ]
}

// ---- rules ----

#[derive(Deserialize)]
struct AddReq {
    #[serde(default)]
    rules: SpamRules,
    rule: SpamRule,
}

#[derive(Serialize)]
struct RulesAns {
    rules: SpamRules,
}

fn add_rule(mut req: AddReq) -> Result<RulesAns, FfiError> {
    req.rules
        .add_rule(req.rule)
        .map_err(|e| FfiError::invalid(e.to_string()))?;
    Ok(RulesAns { rules: req.rules })
}

fn add_rule_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"rules": rules(), "rule": {"keyword": {"text": "  lottery "}}})),
        Case::named(
            "duplicate",
            json!({"rules": rules(), "rule": {"keyword": {"text": "WIN"}}}),
        ),
        Case::named("empty", json!({"rule": {"sender": {"pattern": " * "}}})),
        Case::named("short_code", json!({"rule": {"short_code_only": true}})),
    ]
}

#[derive(Deserialize)]
struct InsertReq {
    #[serde(default)]
    rules: SpamRules,
    index: usize,
    rule: SpamRule,
}

#[derive(Serialize)]
struct InsertAns {
    rules: SpamRules,
    /// `false` when the list is full.
    inserted: bool,
}

fn insert_rule(mut req: InsertReq) -> Result<InsertAns, FfiError> {
    let inserted = req.rules.insert_rule(req.index, req.rule);
    Ok(InsertAns {
        rules: req.rules,
        inserted,
    })
}

fn insert_rule_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"rules": rules(), "index": 1, "rule": {"keyword": {"text": "prize"}}})),
        Case::named(
            "past_the_end",
            json!({"index": 9, "rule": "unknown_with_link"}),
        ),
    ]
}

#[derive(Deserialize)]
struct RemoveReq {
    #[serde(default)]
    rules: SpamRules,
    index: usize,
}

#[derive(Serialize)]
struct RemoveAns {
    rules: SpamRules,
    removed: bool,
}

fn remove_rule(mut req: RemoveReq) -> Result<RemoveAns, FfiError> {
    let removed = req.rules.remove_rule(req.index);
    Ok(RemoveAns {
        rules: req.rules,
        removed,
    })
}

fn remove_rule_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"rules": rules(), "index": 0})),
        Case::named("out_of_range", json!({"rules": rules(), "index": 7})),
    ]
}

// ---- links ----

#[derive(Deserialize)]
struct BodyReq {
    body: String,
}

#[derive(Serialize)]
struct LinksAns {
    /// In order, without duplicates, ready to open (`https://` added when a token has no scheme).
    links: Vec<String>,
}

fn links(req: BodyReq) -> Result<LinksAns, FfiError> {
    Ok(LinksAns {
        links: spam::links(&req.body),
    })
}

fn link_body() -> &'static str {
    "see https://a.com/x and www.b.org, then c.net/p?q=1"
}

fn links_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"body": link_body()})),
        Case::named("none", json!({"body": "no links here"})),
    ]
}

#[derive(Deserialize)]
struct OpenLinksReq {
    body: String,
    #[serde(default = "three")]
    max: usize,
}

fn three() -> usize {
    3
}

#[derive(Serialize)]
struct OpenLinksAns {
    /// "Open link" for a single link, the host (cut to 24 characters) when there are several.
    pills: Vec<OpenLink>,
}

fn open_links(req: OpenLinksReq) -> Result<OpenLinksAns, FfiError> {
    Ok(OpenLinksAns {
        pills: spam::open_links(&req.body, req.max),
    })
}

fn open_links_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"body": link_body(), "max": 2})),
        Case::named("single", json!({"body": "visit https://a.com/x"})),
    ]
}

#[derive(Serialize)]
struct ContainsAns {
    contains: bool,
}

fn contains_link(req: BodyReq) -> Result<ContainsAns, FfiError> {
    Ok(ContainsAns {
        contains: spam::contains_link(&req.body),
    })
}

fn contains_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"body": "go to example.com/win"})),
        Case::named("plain", json!({"body": "version 1.2 is out"})),
    ]
}

// ---- notify.build ----

#[derive(Deserialize)]
struct NotifyReq {
    thread_title: String,
    #[serde(default)]
    is_group: bool,
    /// The conversation's recent messages, oldest first.
    recent: Vec<Incoming>,
    /// The passcode found in the newest message (`otp.find`); it turns off the inline reply.
    #[serde(default)]
    otp_code: Option<String>,
}

#[derive(Serialize)]
struct NotifyAns {
    /// `None` when nothing but spam is left.
    spec: Option<NotificationSpec>,
    max_lines: usize,
}

fn notify_build(req: NotifyReq) -> Result<NotifyAns, FfiError> {
    let otp = req.otp_code.map(|code| Otp { code, span: 0..0 });
    Ok(NotifyAns {
        spec: notify::build(&req.thread_title, req.is_group, &req.recent, otp),
        max_lines: notify::MAX_LINES,
    })
}

fn notify_cases() -> Vec<Case> {
    let recent = json!([
        {"thread": "sms:5", "sender_title": "Ayşe", "body": "See you at 8", "at_ms": NOW - 120_000, "verdict_spam": false},
        {"thread": "sms:5", "sender_title": "Ayşe", "body": "Bring the keys", "at_ms": NOW - 60_000, "verdict_spam": false}
    ]);
    vec![
        Case::new(json!({"thread_title": "Ayşe Yılmaz", "recent": recent})),
        Case::named(
            "group",
            json!({"thread_title": "Weekend plans", "is_group": true, "recent": recent}),
        ),
        Case::named(
            "passcode",
            json!({"thread_title": "BANK", "otp_code": "482913", "recent": [
                {"thread": "sms:6", "sender_title": "BANK", "body": "Your code is 482913", "at_ms": NOW, "verdict_spam": false}]}),
        ),
        Case::named(
            "only_spam",
            json!({"thread_title": "PROMO", "recent": [
                {"thread": "sms:8", "sender_title": "PROMO", "body": "Win!", "at_ms": NOW, "verdict_spam": true}]}),
        ),
    ]
}
