//! `sms.*`, `sms_timeline.build`, `mms_pdu.*` and `otp.find`: the SMS/MMS payloads, one thread's timeline and the
//! binary MMS transport format.

use super::{NOW, canon};
use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use rovsti_core::mms_pdu::{self, PduPart};
use rovsti_core::otp;
use rovsti_core::sms::{self, SegmentInfo, SmsMessagesPayload, SmsRow, SmsSend, SmsStatus};
use rovsti_core::sms_timeline::{SmsItem, SmsKey, SmsTimeline};
use rovsti_core::thread::Message;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "sms.segments",
        SegmentsReq,
        SegmentInfo,
        segments,
        segments_cases
    ),
    op!(
        "sms.to_message",
        ToMessageReq,
        ToMessageAns,
        to_message,
        to_message_cases
    ),
    op!("sms.key", KeyReq, KeyAns, key, key_cases),
    op!(
        "sms_timeline.build",
        TimelineReq,
        TimelineAns,
        timeline_build,
        timeline_cases
    ),
    op!("mms_pdu.parse", ParseReq, ParseAns, pdu_parse, parse_cases),
    op!(
        "mms_pdu.build_send_req",
        SendReqReq,
        BytesAns,
        build_send_req,
        send_req_cases
    ),
    op!(
        "mms_pdu.build_notify_resp",
        NotifyRespReq,
        BytesAns,
        build_notify_resp,
        notify_resp_cases
    ),
    op!("otp.find", OtpReq, OtpAns, otp_find, otp_cases),
];

fn b64(bytes: &[u8]) -> String {
    STANDARD.encode(bytes)
}

fn unb64(text: &str) -> Result<Vec<u8>, FfiError> {
    STANDARD
        .decode(text.trim())
        .map_err(|e| FfiError::invalid(format!("bytes are not base64: {e}")))
}

// ---- sms.segments ----

#[derive(Deserialize)]
struct SegmentsReq {
    body: String,
}

fn segments(req: SegmentsReq) -> Result<SegmentInfo, FfiError> {
    Ok(sms::segments(&req.body))
}

fn segments_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"body": "See you at 8"})),
        Case::named("unicode", json!({"body": "Merhaba ğüş çay 😊"})),
        Case::named("long", json!({"body": "x".repeat(200)})),
    ]
}

// ---- sms.to_message ----

#[derive(Deserialize)]
struct ToMessageReq {
    row: SmsRow,
}

#[derive(Serialize)]
struct ToMessageAns {
    message: Message,
}

fn to_message(req: ToMessageReq) -> Result<ToMessageAns, FfiError> {
    Ok(ToMessageAns {
        message: req.row.to_message(),
    })
}

fn sms_row(id: i64, outgoing: bool, body: &str, at: i64) -> Value {
    canon::<SmsRow>(
        json!({"id": id, "thread_id": 5, "address": "05321234567", "body": body, "date_ms": at,
           "box": if outgoing { 2 } else { 1 }, "read": true, "sub_id": 1, "delivery": "none"}),
    )
}

fn to_message_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"row": sms_row(1, false, "See you at 8", NOW - 60_000)})),
        Case::named("outgoing", json!({"row": sms_row(2, true, "Ok", NOW)})),
    ]
}

// ---- sms.key ----

#[derive(Deserialize)]
struct KeyReq {
    /// `"sms:12"`: the key the Android bridge deletes by.
    #[serde(default)]
    provider_id: Option<String>,
    #[serde(default)]
    key: Option<SmsKey>,
}

#[derive(Serialize)]
struct KeyAns {
    key: Option<SmsKey>,
    provider_id: Option<String>,
}

fn key(req: KeyReq) -> Result<KeyAns, FfiError> {
    let key = req.key.or_else(|| {
        req.provider_id
            .as_deref()
            .and_then(SmsKey::from_provider_id)
    });
    Ok(KeyAns {
        provider_id: key.and_then(SmsKey::provider_id),
        key,
    })
}

fn key_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"provider_id": "mms:7"})),
        Case::named("local", json!({"key": {"local": 3}})),
        Case::named("bad", json!({"provider_id": "wa:7"})),
    ]
}

// ---- sms_timeline.build ----

/// One thing that happened to the thread, replayed in order.
#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Event {
    /// A provider page.
    Page { page: SmsMessagesPayload },
    /// An optimistic outgoing message.
    Pending {
        local_id: u64,
        body: String,
        #[serde(default)]
        sub_id: Option<i32>,
        now_ms: i64,
    },
    /// An `sms_status` event.
    Status { status: SmsStatus },
    /// Take a failed local send back to pending; the result carries the request to send again.
    Retry { local_id: u64 },
    /// Drop a failed local send; the result carries its text.
    Discard { local_id: u64 },
    /// Remove an item.
    Remove { key: SmsKey },
    /// A draft's first send created the provider thread.
    AdoptThread { thread_id: i64 },
}

#[derive(Deserialize)]
struct TimelineReq {
    thread_id: i64,
    /// Who a retry sends to.
    #[serde(default)]
    recipients: Vec<String>,
    #[serde(default)]
    events: Vec<Event>,
}

#[derive(Serialize)]
struct ItemOut {
    #[serde(flatten)]
    item: SmsItem,
    /// `"sms:12"` for the delete bridge; absent for a send without a provider row (yet).
    provider_id: Option<String>,
}

/// What one event did.
#[derive(Serialize)]
struct EventResult {
    /// The event changed something (a status moved forward, an item was found).
    changed: bool,
    /// `retry`: the request to send again.
    send: Option<SmsSend>,
    /// `discard`: the text of the dropped message.
    text: Option<String>,
}

#[derive(Serialize)]
struct TimelineAns {
    thread_id: i64,
    /// Ascending by time.
    items: Vec<ItemOut>,
    has_older: bool,
    /// Timestamp of the oldest provider-backed item, for paging.
    oldest_ms: Option<i64>,
    /// One per event, in order.
    results: Vec<EventResult>,
}

fn timeline_build(req: TimelineReq) -> Result<TimelineAns, FfiError> {
    let mut timeline = SmsTimeline::new(req.thread_id);
    timeline.set_recipients(req.recipients);
    let mut results = Vec::with_capacity(req.events.len());
    for event in req.events {
        let mut result = EventResult {
            changed: true,
            send: None,
            text: None,
        };
        match event {
            Event::Page { page } => timeline.apply_page(page),
            Event::Pending {
                local_id,
                body,
                sub_id,
                now_ms,
            } => timeline.push_pending(local_id, body, sub_id, now_ms),
            Event::Status { status } => result.changed = timeline.apply_status(&status),
            Event::Retry { local_id } => {
                result.send = timeline.retry(local_id);
                result.changed = result.send.is_some();
            }
            Event::Discard { local_id } => {
                result.text = timeline.discard(local_id);
                result.changed = result.text.is_some();
            }
            Event::Remove { key } => result.changed = timeline.remove(key),
            Event::AdoptThread { thread_id } => timeline.adopt_thread(thread_id),
        }
        results.push(result);
    }
    let items = timeline
        .items()
        .iter()
        .map(|item| ItemOut {
            provider_id: timeline
                .provider_key(item.key)
                .and_then(SmsKey::provider_id),
            item: item.clone(),
        })
        .collect();
    Ok(TimelineAns {
        thread_id: timeline.thread_id(),
        items,
        has_older: timeline.has_older(),
        oldest_ms: timeline.oldest_ms(),
        results,
    })
}

fn timeline_cases() -> Vec<Case> {
    let page = canon::<SmsMessagesPayload>(
        json!({"sms": [sms_row(1, false, "See you at 8", NOW - 120_000), sms_row(2, true, "Ok", NOW - 90_000)],
        "mms": [{"id": 3, "thread_id": 5, "addresses": ["05321234567"], "from": "05321234567", "date_ms": NOW - 60_000,
                 "outgoing": false, "read": true,
                 "parts": [{"content_type": "text/plain", "text": "Look"}, {"content_type": "image/jpeg", "path": "/cache/mms/3.jpg"}]}],
        "has_older": true}),
    );
    let sending = json!([
        {"kind": "page", "page": page},
        {"kind": "pending", "local_id": 9, "body": "On my way", "sub_id": 1, "now_ms": NOW},
        {"kind": "status", "status": {"local_id": 9, "uri": "content://sms/10", "state": "sent"}},
        {"kind": "status", "status": {"local_id": 9, "state": "delivered"}},
        {"kind": "status", "status": {"local_id": 77, "state": "sent"}}
    ]);
    let failing = json!([
        {"kind": "pending", "local_id": 4, "body": "Hello?", "now_ms": NOW},
        {"kind": "status", "status": {"local_id": 4, "state": "failed", "error_code": 3}},
        {"kind": "retry", "local_id": 4},
        {"kind": "status", "status": {"local_id": 4, "state": "failed"}},
        {"kind": "discard", "local_id": 4},
        {"kind": "retry", "local_id": 4},
        {"kind": "remove", "key": {"sms": 404}}
    ]);
    vec![
        Case::new(json!({"thread_id": 5, "recipients": ["05321234567"], "events": sending})),
        Case::named(
            "failed_send",
            json!({"thread_id": 5, "recipients": ["05321234567"], "events": failing}),
        ),
        Case::named(
            "adopt",
            json!({"thread_id": -1, "events": [
                {"kind": "pending", "local_id": 1, "body": "First", "now_ms": NOW},
                {"kind": "adopt_thread", "thread_id": 12}]}),
        ),
        Case::named("empty", json!({"thread_id": 5})),
        Case::named(
            "bad_event",
            json!({"thread_id": 5, "events": [{"kind": "explode"}]}),
        ),
    ]
}

// ---- mms_pdu ----

#[derive(Deserialize)]
struct ParseReq {
    /// The PDU bytes, standard base64.
    bytes: String,
    /// `retrieve_conf` (the downloaded message) or `notification_ind` (the push that announces one).
    kind: String,
}

#[derive(Serialize)]
struct NotificationOut {
    transaction_id: String,
    content_location: String,
    from: Option<String>,
    size: u32,
    expiry_s: Option<u32>,
}

#[derive(Serialize, Deserialize)]
struct PartDto {
    content_type: String,
    #[serde(default)]
    name: Option<String>,
    /// Standard base64.
    data: String,
}

#[derive(Serialize)]
struct RetrieveOut {
    transaction_id: Option<String>,
    from: Option<String>,
    to: Vec<String>,
    subject: Option<String>,
    date_s: Option<u64>,
    parts: Vec<PartDto>,
}

#[derive(Serialize)]
struct ParseAns {
    notification: Option<NotificationOut>,
    retrieve: Option<RetrieveOut>,
}

fn pdu_error(error: mms_pdu::PduError) -> FfiError {
    FfiError::new(ErrorKind::Parse, error.to_string())
}

fn pdu_parse(req: ParseReq) -> Result<ParseAns, FfiError> {
    let bytes = unb64(&req.bytes)?;
    match req.kind.as_str() {
        "notification_ind" => {
            let n = mms_pdu::decode_notification_ind(&bytes).map_err(pdu_error)?;
            Ok(ParseAns {
                notification: Some(NotificationOut {
                    transaction_id: n.transaction_id,
                    content_location: n.content_location,
                    from: n.from,
                    size: n.size,
                    expiry_s: n.expiry_s,
                }),
                retrieve: None,
            })
        }
        "retrieve_conf" => {
            let c = mms_pdu::decode_retrieve_conf(&bytes).map_err(pdu_error)?;
            Ok(ParseAns {
                notification: None,
                retrieve: Some(RetrieveOut {
                    transaction_id: c.transaction_id,
                    from: c.from,
                    to: c.to,
                    subject: c.subject,
                    date_s: c.date_s,
                    parts: c
                        .parts
                        .into_iter()
                        .map(|p| PartDto {
                            data: b64(&p.data),
                            content_type: p.content_type,
                            name: p.name,
                        })
                        .collect(),
                }),
            })
        }
        other => Err(FfiError::invalid(format!("unknown PDU kind {other:?}"))),
    }
}

fn from_hex(hex: &str) -> String {
    let bytes: Vec<u8> = (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).expect("fixture hex"))
        .collect();
    b64(&bytes)
}

const NOTIFICATION_HEX: &str = "8c82985431008d908918802b31353535313233343536372f545950453d504c4d4e008a808e020400880481020e1083687474703a2f2f6d6d732e6578616d706c652f6d3100";
const RETRIEVE_HEX: &str = "8c84985237008d93850465000000891a802b343931353131323334353637382f545950453d504c4d4e00972b31353535303030313131312f545950453d504c4d4e009648690084a30201058348656c6c6f0904089e85612e6a706700ffd8ffd9";

fn parse_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"bytes": from_hex(RETRIEVE_HEX), "kind": "retrieve_conf"})),
        Case::named(
            "notification",
            json!({"bytes": from_hex(NOTIFICATION_HEX), "kind": "notification_ind"}),
        ),
        Case::named(
            "truncated",
            json!({"bytes": from_hex(&RETRIEVE_HEX[..40]), "kind": "retrieve_conf"}),
        ),
        Case::named(
            "wrong_type",
            json!({"bytes": from_hex(NOTIFICATION_HEX), "kind": "retrieve_conf"}),
        ),
        Case::named(
            "not_base64",
            json!({"bytes": "***", "kind": "retrieve_conf"}),
        ),
        Case::named("bad_kind", json!({"bytes": "", "kind": "m-send-conf"})),
    ]
}

#[derive(Deserialize)]
struct SendReqReq {
    to: Vec<String>,
    #[serde(default)]
    subject: Option<String>,
    parts: Vec<PartDto>,
    transaction_id: String,
}

#[derive(Serialize)]
struct BytesAns {
    /// Standard base64.
    bytes: String,
}

fn build_send_req(req: SendReqReq) -> Result<BytesAns, FfiError> {
    if req.to.is_empty() {
        return Err(FfiError::invalid("an MMS needs at least one recipient"));
    }
    let mut parts = Vec::with_capacity(req.parts.len());
    for part in req.parts {
        parts.push(PduPart {
            data: unb64(&part.data)?,
            content_type: part.content_type,
            name: part.name,
        });
    }
    Ok(BytesAns {
        bytes: b64(&mms_pdu::encode_send_req(
            &req.to,
            req.subject.as_deref(),
            &parts,
            &req.transaction_id,
        )),
    })
}

fn send_req_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"to": ["+15551234567", "0555 123 45 67"], "subject": "Tatil",
            "transaction_id": "tx9",
            "parts": canon::<Vec<PartDto>>(json!([
                {"content_type": "text/plain", "name": "text_0.txt", "data": b64("Merhaba".as_bytes())},
                {"content_type": "image/png", "data": b64(&[0x89, b'P', b'N', b'G'])}]))}),
        ),
        Case::named(
            "no_recipient",
            json!({"to": [], "parts": [], "transaction_id": "t"}),
        ),
    ]
}

#[derive(Deserialize)]
struct NotifyRespReq {
    transaction_id: String,
    retrieved: bool,
}

fn build_notify_resp(req: NotifyRespReq) -> Result<BytesAns, FfiError> {
    Ok(BytesAns {
        bytes: b64(&mms_pdu::encode_notify_resp(
            &req.transaction_id,
            req.retrieved,
        )),
    })
}

fn notify_resp_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"transaction_id": "T1", "retrieved": true})),
        Case::named(
            "deferred",
            json!({"transaction_id": "T1", "retrieved": false}),
        ),
    ]
}

// ---- otp.find ----

#[derive(Deserialize)]
struct OtpReq {
    body: String,
}

#[derive(Serialize)]
struct OtpHit {
    /// The digits only.
    code: String,
    /// UTF-16 offsets of the code as written (separator included) in `body`, the unit Kotlin strings use.
    start: usize,
    end: usize,
}

#[derive(Serialize)]
struct OtpAns {
    otp: Option<OtpHit>,
}

fn otp_find(req: OtpReq) -> Result<OtpAns, FfiError> {
    let utf16 = |byte: usize| req.body[..byte].encode_utf16().count();
    Ok(OtpAns {
        otp: otp::detect(&req.body).map(|hit| OtpHit {
            start: utf16(hit.span.start),
            end: utf16(hit.span.end),
            code: hit.code,
        }),
    })
}

fn otp_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"body": "Your verification code is 482-913. Do not share."})),
        Case::named(
            "after_emoji",
            json!({"body": "😊 G-123456 is your Google verification code"}),
        ),
        Case::named("none", json!({"body": "See you at 8"})),
    ]
}
