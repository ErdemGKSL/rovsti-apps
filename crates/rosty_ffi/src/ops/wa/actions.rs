//! The pure `wa.*` ops of the message actions (E6-WA R4): the `@` query at the caret, encoding the composer's
//! mentions, the first link of a text, reading a page's preview tags, and the sections of message info. Offsets are
//! UTF-16 code units.

use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rosty_core::wa_actions::{
    self, Encoded, FoundUrl, LinkMeta, MemberReceipt, MentionQuery, Pick, SectionRows,
};
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "wa.mention_query",
        QueryReq,
        QueryAns,
        mention_query,
        query_cases
    ),
    op!(
        "wa.mention_encode",
        EncodeReq,
        Encoded,
        mention_encode,
        encode_cases
    ),
    op!("wa.link_find", FindReq, FindAns, link_find, find_cases),
    op!("wa.link_parse", ParseReq, ParseAns, link_parse, parse_cases),
    op!(
        "wa.receipt_sections",
        SectionsReq,
        SectionsAns,
        receipt_sections,
        sections_cases
    ),
];

// ---- wa.mention_query

#[derive(Deserialize)]
struct QueryReq {
    text: String,
    /// The caret, UTF-16.
    caret: usize,
}

#[derive(Serialize)]
struct QueryAns {
    #[serde(skip_serializing_if = "Option::is_none")]
    query: Option<MentionQuery>,
}

fn mention_query(req: QueryReq) -> Result<QueryAns, FfiError> {
    Ok(QueryAns {
        query: wa_actions::query_at(&req.text, req.caret),
    })
}

fn query_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "selam @al", "caret": 9})),
        Case::named("email", json!({"text": "me@home", "caret": 7})),
    ]
}

// ---- wa.mention_encode

#[derive(Deserialize)]
struct EncodeReq {
    text: String,
    #[serde(default)]
    picks: Vec<Pick>,
}

fn mention_encode(req: EncodeReq) -> Result<Encoded, FfiError> {
    Ok(wa_actions::encode(&req.text, &req.picks))
}

fn encode_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "Hi @Ali and @Lid!", "picks": [
            {"start": 3, "end": 7, "jid": "100000000001@s.whatsapp.net"},
            {"start": 12, "end": 16, "jid": "777000000000001@lid"}]})),
        Case::named(
            "edited",
            json!({"text": "Hi Ali", "picks": [
            {"start": 3, "end": 6, "jid": "100000000001@s.whatsapp.net"}]}),
        ),
    ]
}

// ---- wa.link_find

#[derive(Deserialize)]
struct FindReq {
    text: String,
}

#[derive(Serialize)]
struct FindAns {
    #[serde(skip_serializing_if = "Option::is_none")]
    found: Option<FoundUrl>,
}

fn link_find(req: FindReq) -> Result<FindAns, FfiError> {
    Ok(FindAns {
        found: wa_actions::first_url(&req.text),
    })
}

fn find_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "bak: https://example.com/haber?id=3."})),
        Case::named("none", json!({"text": "link yok"})),
    ]
}

// ---- wa.link_parse

#[derive(Deserialize)]
struct ParseReq {
    /// The first bytes of the page as text (the phone reads at most `max_bytes`).
    html: String,
    /// The page's URL after redirects.
    url: String,
}

#[derive(Serialize)]
struct ParseAns {
    #[serde(flatten)]
    meta: LinkMeta,
    /// The host the card names.
    #[serde(skip_serializing_if = "Option::is_none")]
    host: Option<String>,
    /// How much of a page the phone reads.
    max_bytes: usize,
}

fn link_parse(req: ParseReq) -> Result<ParseAns, FfiError> {
    if !wa_actions::is_fetchable(&req.url) {
        return Err(FfiError::invalid("url must be http or https"));
    }
    let meta = wa_actions::parse_html(&req.html, &req.url);
    let host = wa_actions::host_of(meta.url.as_deref().unwrap_or(&req.url));
    Ok(ParseAns {
        meta,
        host,
        max_bytes: wa_actions::MAX_HTML_BYTES,
    })
}

fn parse_cases() -> Vec<Case> {
    vec![
        Case::new(json!({
            "html": "<html><head><title>Plain</title><meta property=\"og:title\" content=\"Haber &amp; yorum\">\
                <meta property=\"og:description\" content=\"Kısa özet\"><meta property=\"og:image\" content=\"/img/a.jpg\">\
                </head><body></body></html>",
            "url": "https://www.example.com/haber/1"
        })),
        Case::named(
            "bare",
            json!({"html": "<p>no head</p>", "url": "https://example.com/"}),
        ),
        Case::named("not_http", json!({"html": "", "url": "file:///etc/hosts"})),
    ]
}

// ---- wa.receipt_sections

#[derive(Deserialize)]
struct SectionsReq {
    members: Vec<MemberReceipt>,
    /// A voice note: plays get their own section.
    #[serde(default)]
    voice: bool,
}

#[derive(Serialize)]
struct SectionsAns {
    sections: Vec<SectionRows>,
}

fn receipt_sections(req: SectionsReq) -> Result<SectionsAns, FfiError> {
    Ok(SectionsAns {
        sections: wa_actions::sections(&req.members, req.voice),
    })
}

fn sections_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"members": [
            {"jid": "100000000001@s.whatsapp.net", "delivered_ms": 1000, "read_ms": 2000},
            {"jid": "100000000002@s.whatsapp.net", "delivered_ms": 1500},
            {"jid": "100000000003@s.whatsapp.net"},
            {"jid": "100000000004@s.whatsapp.net", "delivered_ms": 900, "read_ms": 1200, "left": true}
        ]})),
        Case::named(
            "voice",
            json!({"voice": true, "members": [
                {"jid": "100000000001@s.whatsapp.net", "delivered_ms": 1000, "read_ms": 2000, "played_ms": 3000}]}),
        ),
    ]
}
