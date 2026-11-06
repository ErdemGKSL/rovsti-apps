//! The headless handler behind `RovstiBridge.nativeHeadless(kind, json)` (AD-MSG4, AD-MSG5).
//!
//! Kotlin receivers (SMS delivered, MMS WAP push, inline reply, FCM digest) may run with no
//! activity. They load this library and ask for a decision here. Everything is a blocking
//! function over JSON: no GPUI, no event loop. A kind this app does not know answers `""`,
//! which Kotlin treats as "no decision" and falls back to a plain notification.
//!
//! | kind               | in                                                        | out                                          |
//! |--------------------|-----------------------------------------------------------|----------------------------------------------|
//! | `sms_classify`     | `{dir, sender, body, known?, blocked?}`                   | `{spam, reason, otp}`                        |
//! | `mms_notification` | `{pdu_b64}`                                               | `{ok, transaction_id, content_location, ..}` |
//! | `mms_retrieved`    | `{pdu_b64, dir?, known?, blocked?}`                       | `{ok, from, to, text, parts, spam, otp, ..}` |
//! | `mms_send_req`     | `{local_id, to[], text?, image_path?, sub_id?}`           | `{ok, pdu_b64}`                              |
//! | `wa_digest`        | `{dir, chat}`                                             | a `NotificationSpec`, or `{}`                |
//! | `wa_reply`         | `{dir, chat, text}`                                       | `{ok}`                                       |
//!
//! `dir` is the app data directory (`Headless.dataDir`), where the spam rules live in
//! `messages-prefs.json` and the WhatsApp link in `whatsapp-link.json`.

use std::{fs, io, path::Path};

use base64::{Engine as _, engine::general_purpose::STANDARD};
use rovsti_android::headless::{self, Handler};
use rovsti_core::{
    blocklist::BlockSet,
    mms_pdu::{self, PduPart},
    otp,
    spam::{self, SpamRules, Verdict},
    wire::BlockedNumber,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// The Messages preferences file. Only the part the cold paths need is read here;
/// unknown fields are ignored, so the UI can grow the file freely.
pub const PREFS_FILE: &str = "messages-prefs.json";

/// Largest attachment `mms_send_req` reads from disk. Carriers refuse far less.
/// The attach button picks no larger file (`backend::ATTACH_MAX_BYTES`).
pub(crate) const MAX_ATTACHMENT_BYTES: u64 = 4 * 1024 * 1024;

/// Handles one headless request; `""` for an unknown kind.
pub fn handle(kind: &str, json: &str) -> String {
    const TABLE: &[(&str, Handler)] = &[
        ("sms_classify", sms_classify),
        ("mms_notification", mms_notification),
        ("mms_retrieved", mms_retrieved),
        ("mms_send_req", mms_send_req),
        ("wa_digest", wa_digest),
        ("wa_reply", wa_reply),
    ];
    headless::dispatch(kind, json, TABLE)
}

// ------------------------------------------------------------------ spam and OTP

#[derive(Default, Deserialize)]
struct PrefsView {
    #[serde(default)]
    spam: SpamRules,
}

fn load_rules(dir: &str) -> SpamRules {
    if dir.is_empty() {
        return SpamRules::default();
    }
    rovsti_ui::storage::load_json::<PrefsView>(Path::new(dir), PREFS_FILE)
        .unwrap_or_default()
        .spam
}

#[derive(Deserialize)]
struct ClassifyIn {
    #[serde(default)]
    dir: String,
    #[serde(default)]
    sender: String,
    #[serde(default)]
    body: String,
    /// The sender is in the contacts (Kotlin `PhoneLookup`).
    #[serde(default)]
    known: bool,
    /// The sender is on the system blocklist (Kotlin `BlockedNumberContract`).
    #[serde(default)]
    blocked: bool,
}

#[derive(Debug, PartialEq, Eq, Serialize)]
struct Classified {
    spam: bool,
    reason: Option<&'static str>,
    otp: Option<String>,
}

fn classify(input: &ClassifyIn, rules: &SpamRules) -> Classified {
    let blocked = if input.blocked {
        BlockSet::from_rows(&[BlockedNumber {
            id: 0,
            number_raw: input.sender.clone(),
            normalized: String::new(),
        }])
    } else {
        BlockSet::default()
    };
    let verdict = spam::classify(rules, &input.sender, &input.body, input.known, &blocked);
    Classified {
        spam: verdict.is_spam(),
        reason: match verdict {
            Verdict::Spam(reason) => Some(reason),
            Verdict::Ham => None,
        },
        otp: otp::detect(&input.body).map(|o| o.code),
    }
}

/// Bad input is ham: a notification is better than a swallowed message.
fn sms_classify(_: &str, json: &str) -> String {
    let out = match serde_json::from_str::<ClassifyIn>(json) {
        Ok(input) => classify(&input, &load_rules(&input.dir)),
        Err(_) => Classified {
            spam: false,
            reason: None,
            otp: None,
        },
    };
    to_json(&out)
}

// ------------------------------------------------------------------ MMS

fn to_json<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| "{\"ok\":false}".to_owned())
}

fn failure(error: &str) -> String {
    json!({ "ok": false, "error": error }).to_string()
}

fn decode_pdu(b64: &str) -> Result<Vec<u8>, &'static str> {
    let bytes = STANDARD.decode(b64.trim()).map_err(|_| "bad_base64")?;
    if bytes.is_empty() {
        return Err("empty_pdu");
    }
    Ok(bytes)
}

fn pdu_error_key(error: mms_pdu::PduError) -> &'static str {
    match error {
        mms_pdu::PduError::Truncated => "truncated",
        mms_pdu::PduError::WrongType(_) => "wrong_type",
        mms_pdu::PduError::BadHeader(_) => "bad_header",
        mms_pdu::PduError::BadMultipart => "bad_multipart",
    }
}

#[derive(Deserialize)]
struct PduIn {
    #[serde(default)]
    pdu_b64: String,
    #[serde(default)]
    dir: String,
    #[serde(default)]
    known: bool,
    #[serde(default)]
    blocked: bool,
}

/// `m-notification-ind` in; where to download it from out.
fn mms_notification(_: &str, json: &str) -> String {
    let Ok(input) = serde_json::from_str::<PduIn>(json) else {
        return failure("bad_request");
    };
    let bytes = match decode_pdu(&input.pdu_b64) {
        Ok(bytes) => bytes,
        Err(error) => return failure(error),
    };
    match mms_pdu::decode_notification_ind(&bytes) {
        Ok(n) => json!({
            "ok": true,
            "transaction_id": n.transaction_id,
            "content_location": n.content_location,
            "from": n.from,
            "size": n.size,
            "expiry_s": n.expiry_s,
        })
        .to_string(),
        Err(error) => failure(pdu_error_key(error)),
    }
}

fn is_text(part: &PduPart) -> bool {
    part.content_type
        .to_ascii_lowercase()
        .starts_with("text/plain")
}

/// `m-retrieve-conf` in; parts and the spam and OTP verdict out. Text parts carry `text`,
/// every other part carries its bytes as `data_b64` for Kotlin to write to the provider.
fn mms_retrieved(_: &str, json: &str) -> String {
    let Ok(input) = serde_json::from_str::<PduIn>(json) else {
        return failure("bad_request");
    };
    let bytes = match decode_pdu(&input.pdu_b64) {
        Ok(bytes) => bytes,
        Err(error) => return failure(error),
    };
    let conf = match mms_pdu::decode_retrieve_conf(&bytes) {
        Ok(conf) => conf,
        Err(error) => return failure(pdu_error_key(error)),
    };
    let text = conf
        .parts
        .iter()
        .filter(|p| is_text(p))
        .map(|p| String::from_utf8_lossy(&p.data).into_owned())
        .filter(|t| !t.is_empty())
        .collect::<Vec<_>>()
        .join("\n");
    let sender = conf.from.clone().unwrap_or_default();
    let verdict = classify(
        &ClassifyIn {
            dir: String::new(),
            sender,
            body: text.clone(),
            known: input.known,
            blocked: input.blocked,
        },
        &load_rules(&input.dir),
    );
    let parts: Vec<Value> = conf
        .parts
        .iter()
        .map(|p| {
            if is_text(p) {
                json!({
                    "content_type": p.content_type,
                    "name": p.name,
                    "text": String::from_utf8_lossy(&p.data),
                })
            } else {
                json!({
                    "content_type": p.content_type,
                    "name": p.name,
                    "data_b64": STANDARD.encode(&p.data),
                })
            }
        })
        .collect();
    json!({
        "ok": true,
        "transaction_id": conf.transaction_id,
        "from": conf.from,
        "to": conf.to,
        "subject": conf.subject,
        "date_s": conf.date_s,
        "text": text,
        "parts": parts,
        "spam": verdict.spam,
        "reason": verdict.reason,
        "otp": verdict.otp,
    })
    .to_string()
}

#[derive(Deserialize)]
struct SendReqIn {
    #[serde(default)]
    local_id: u64,
    #[serde(default)]
    to: Vec<String>,
    #[serde(default)]
    text: String,
    #[serde(default)]
    image_path: Option<String>,
}

fn image_type(name: &str) -> Option<&'static str> {
    match name.rsplit_once('.')?.1.to_ascii_lowercase().as_str() {
        "jpg" | "jpeg" => Some("image/jpeg"),
        "png" => Some("image/png"),
        "gif" => Some("image/gif"),
        "webp" => Some("image/webp"),
        _ => None,
    }
}

/// The picture type read from the first bytes, for files whose name says
/// nothing (the picker's copy is `import-<ms>.bin`).
fn sniff_image(data: &[u8]) -> Option<(&'static str, &'static str)> {
    if data.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some(("image/jpeg", "jpg"))
    } else if data.starts_with(&[0x89, b'P', b'N', b'G']) {
        Some(("image/png", "png"))
    } else if data.starts_with(b"GIF8") {
        Some(("image/gif", "gif"))
    } else if data.len() >= 12 && data.starts_with(b"RIFF") && &data[8..12] == b"WEBP" {
        Some(("image/webp", "webp"))
    } else {
        None
    }
}

/// Builds the `m-send-req`. `read` loads the attachment, so tests need no files.
fn build_send_req(
    input: &SendReqIn,
    read: impl FnOnce(&str) -> io::Result<Vec<u8>>,
) -> Result<Vec<u8>, &'static str> {
    if input.to.iter().all(|t| t.trim().is_empty()) {
        return Err("no_recipient");
    }
    let mut parts = Vec::new();
    if !input.text.trim().is_empty() {
        parts.push(PduPart {
            content_type: "text/plain".into(),
            name: Some("text.txt".into()),
            data: input.text.clone().into_bytes(),
        });
    }
    if let Some(path) = input.image_path.as_deref().filter(|p| !p.is_empty()) {
        let name = path.rsplit('/').next().unwrap_or(path);
        // The picker's copy is `import-<ms>.bin`: then the bytes say what it is.
        let opaque = name
            .rsplit_once('.')
            .is_none_or(|(_, ext)| ext.eq_ignore_ascii_case("bin"));
        let (content_type, name, data) = match image_type(name) {
            Some(content_type) => {
                let data = read(path).map_err(|_| "attachment_unreadable")?;
                (content_type, name.to_owned(), data)
            }
            None if opaque => {
                let data = read(path).map_err(|_| "attachment_unreadable")?;
                let (content_type, ext) = sniff_image(&data).ok_or("unsupported_attachment")?;
                (content_type, format!("image.{ext}"), data)
            }
            None => return Err("unsupported_attachment"),
        };
        parts.push(PduPart {
            content_type: content_type.into(),
            name: Some(name),
            data,
        });
    }
    if parts.is_empty() {
        return Err("empty_message");
    }
    let to: Vec<&str> = input
        .to
        .iter()
        .map(|t| t.trim())
        .filter(|t| !t.is_empty())
        .collect();
    Ok(mms_pdu::encode_send_req(
        &to,
        None,
        &parts,
        &format!("T{:x}", input.local_id),
    ))
}

fn read_attachment(path: &str) -> io::Result<Vec<u8>> {
    let file = fs::File::open(path)?;
    if file.metadata()?.len() > MAX_ATTACHMENT_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "too large"));
    }
    fs::read(path)
}

/// `{local_id, to[], text, image_path?, sub_id?}` in; `{"ok":true,"pdu_b64"}` out.
fn mms_send_req(_: &str, json: &str) -> String {
    let Ok(input) = serde_json::from_str::<SendReqIn>(json) else {
        return failure("bad_request");
    };
    match build_send_req(&input, read_attachment) {
        Ok(pdu) => json!({ "ok": true, "pdu_b64": STANDARD.encode(pdu) }).to_string(),
        Err(error) => failure(error),
    }
}

// ------------------------------------------------------------------ WhatsApp

fn wa_digest(_: &str, json: &str) -> String {
    rovsti_wa_ui::headless::wa_digest(json)
}

fn wa_reply(_: &str, json: &str) -> String {
    rovsti_wa_ui::headless::wa_reply(json)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(answer: &str) -> Value {
        serde_json::from_str(answer).expect("the answer is JSON")
    }

    fn tmp(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "rovsti-messages-headless-{name}-{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("temp dir");
        dir
    }

    fn b64(bytes: &[u8]) -> String {
        STANDARD.encode(bytes)
    }

    #[test]
    fn unknown_kind_and_empty_kind_answer_empty() {
        assert_eq!(handle("nope", "{}"), "");
        assert_eq!(handle("", "{}"), "");
    }

    #[test]
    fn every_kind_is_routed() {
        for kind in [
            "sms_classify",
            "mms_notification",
            "mms_retrieved",
            "mms_send_req",
            "wa_digest",
            "wa_reply",
        ] {
            assert!(!handle(kind, "not json").is_empty(), "{kind} fell through");
        }
    }

    #[test]
    fn classify_passes_a_plain_message_and_finds_the_code() {
        let out = parse(&handle(
            "sms_classify",
            r#"{"sender":"BANK","body":"Your code is 482913. Do not share it."}"#,
        ));
        assert_eq!(out["spam"], false);
        assert_eq!(out["otp"], "482913");
        assert!(out["reason"].is_null());
    }

    #[test]
    fn classify_reads_the_rules_from_the_prefs_file() {
        let dir = tmp("rules");
        fs::write(
            dir.join(PREFS_FILE),
            r#"{"theme":"x","spam":{"rules":[{"keyword":{"text":"free iphone"}}]}}"#,
        )
        .expect("write");
        let request = |sender: &str, known: bool| {
            json!({
                "dir": dir.to_str(), "sender": sender,
                "body": "Win a FREE iPhone now", "known": known,
            })
            .to_string()
        };
        let out = parse(&handle("sms_classify", &request("+905551112233", false)));
        assert_eq!(out["spam"], true);
        assert_eq!(out["reason"], "keyword_rule");
        // A contact is never spam.
        let known = parse(&handle("sms_classify", &request("+905551112233", true)));
        assert_eq!(known["spam"], false);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn classify_without_a_prefs_file_uses_defaults() {
        let dir = tmp("norules");
        let out = parse(&handle(
            "sms_classify",
            &json!({"dir": dir.to_str(), "sender": "+905551112233", "body": "free iphone"})
                .to_string(),
        ));
        assert_eq!(out["spam"], false);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn classify_honours_the_system_blocklist() {
        let out = parse(&handle(
            "sms_classify",
            r#"{"sender":"+905551112233","body":"hello","blocked":true}"#,
        ));
        assert_eq!(out["spam"], true);
        assert_eq!(out["reason"], "blocked");
    }

    #[test]
    fn classify_bad_input_is_ham() {
        let out = parse(&handle("sms_classify", "[1,2"));
        assert_eq!(out["spam"], false);
        assert!(out["otp"].is_null());
    }

    #[test]
    fn corrupt_prefs_fall_back_to_defaults() {
        let dir = tmp("corrupt");
        fs::write(dir.join(PREFS_FILE), "{{{").expect("write");
        assert_eq!(
            load_rules(dir.to_str().expect("utf8")),
            SpamRules::default()
        );
        fs::remove_dir_all(&dir).ok();
    }

    const NOTIFICATION: &str = "8c82985431008d908918802b31353535313233343536372f545950453d504c4d4e008a808e020400880481020e1083687474703a2f2f6d6d732e6578616d706c652f6d3100";
    const RETRIEVE: &str = "8c84985237008d93850465000000891a802b343931353131323334353637382f545950453d504c4d4e00972b31353535303030313131312f545950453d504c4d4e009648690084a30201058348656c6c6f0904089e85612e6a706700ffd8ffd9";

    fn unhex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex"))
            .collect()
    }

    #[test]
    fn notification_ind_gives_the_download_location() {
        let request = json!({"pdu_b64": b64(&unhex(NOTIFICATION))}).to_string();
        let out = parse(&handle("mms_notification", &request));
        assert_eq!(out["ok"], true, "{out}");
        assert_eq!(out["transaction_id"], "T1");
        assert_eq!(out["content_location"], "http://mms.example/m1");
        assert_eq!(out["from"], "+15551234567");
        assert_eq!(out["size"], 1024);
        assert_eq!(out["expiry_s"], 3600);
    }

    #[test]
    fn mms_pdu_errors_are_reported_by_key() {
        let bad =
            |kind: &str, pdu: &str| parse(&handle(kind, &json!({"pdu_b64": pdu}).to_string()));
        for kind in ["mms_notification", "mms_retrieved"] {
            let out = bad(kind, "!!!");
            assert_eq!(
                (out["ok"].clone(), out["error"].clone()),
                (json!(false), json!("bad_base64"))
            );
            assert_eq!(bad(kind, "")["error"], "empty_pdu");
            assert_eq!(bad(kind, &b64(&[0x8c]))["error"], "truncated");
            assert_eq!(bad(kind, &b64(&[0x8c, 0x99, 0x98]))["error"], "wrong_type");
        }
        assert_eq!(handle("mms_notification", "oops"), failure("bad_request"));
    }

    #[test]
    fn send_req_builds_a_pdu_with_the_local_id_as_transaction() {
        // The send PDU is a valid m-send-req; its parts are what a retrieve would carry.
        let out = parse(&handle(
            "mms_send_req",
            r#"{"local_id":255,"to":["+905551112233"],"text":"hi"}"#,
        ));
        assert_eq!(out["ok"], true, "{out}");
        let pdu = STANDARD
            .decode(out["pdu_b64"].as_str().expect("pdu"))
            .expect("b64");
        assert_eq!(&pdu[..2], &[0x8c, 0x80]);
        assert!(
            pdu.windows(3).any(|w| w == b"Tff"),
            "transaction id is T<hex local id>"
        );
        assert!(pdu.windows(2).any(|w| w == b"hi"));
    }

    #[test]
    fn send_req_attaches_an_image_and_rejects_bad_requests() {
        let dir = tmp("send");
        let png = dir.join("pic.PNG");
        fs::write(&png, [1, 2, 3, 4]).expect("write");
        let ok = parse(&handle(
            "mms_send_req",
            &json!({"local_id": 1, "to": ["+905551112233"], "image_path": png.to_str()})
                .to_string(),
        ));
        assert_eq!(ok["ok"], true, "{ok}");
        let pdu = STANDARD
            .decode(ok["pdu_b64"].as_str().expect("pdu"))
            .expect("b64");
        assert!(pdu.windows(9).any(|w| w == b"image/png"));

        let err =
            |request: Value| parse(&handle("mms_send_req", &request.to_string()))["error"].clone();
        assert_eq!(err(json!({"to": [], "text": "x"})), "no_recipient");
        assert_eq!(err(json!({"to": ["+90555"]})), "empty_message");
        assert_eq!(
            err(json!({"to": ["+90555"], "image_path": "/x/doc.pdf"})),
            "unsupported_attachment"
        );
        assert_eq!(
            err(json!({"to": ["+90555"], "image_path": dir.join("missing.png").to_str()})),
            "attachment_unreadable"
        );
        assert_eq!(handle("mms_send_req", "x"), failure("bad_request"));
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn send_req_reads_the_picture_type_of_a_picker_copy() {
        // The picker copies to `import-<ms>.bin`: the bytes decide the type.
        let jpeg = SendReqIn {
            local_id: 1,
            to: vec!["+905551112233".into()],
            text: String::new(),
            image_path: Some("/cache/import-17.bin".into()),
        };
        let pdu = build_send_req(&jpeg, |_| Ok(vec![0xFF, 0xD8, 0xFF, 0xE0, 1, 2])).expect("ok");
        assert!(pdu.windows(10).any(|w| w == b"image/jpeg"));
        assert!(pdu.windows(9).any(|w| w == b"image.jpg"));
        let webp = build_send_req(&jpeg, |_| Ok(b"RIFF\0\0\0\0WEBPVP8 ".to_vec())).expect("ok");
        assert!(webp.windows(10).any(|w| w == b"image/webp"));
        // A copy that is not a picture is refused, not sent as a broken MMS.
        assert_eq!(
            build_send_req(&jpeg, |_| Ok(b"%PDF-1.7".to_vec())),
            Err("unsupported_attachment")
        );
        let bare = SendReqIn {
            image_path: Some("/cache/photo".into()),
            ..jpeg
        };
        assert!(build_send_req(&bare, |_| Ok(b"GIF89a".to_vec())).is_ok());
    }

    #[test]
    fn retrieved_message_gets_parts_and_a_verdict() {
        let request = json!({"pdu_b64": b64(&unhex(RETRIEVE))}).to_string();
        let out = parse(&handle("mms_retrieved", &request));
        assert_eq!(out["ok"], true, "{out}");
        assert_eq!(out["from"], "+4915112345678");
        assert_eq!(out["to"], json!(["+15550001111"]));
        assert_eq!(out["subject"], "Hi");
        assert_eq!(out["text"], "Hello");
        assert_eq!(out["spam"], false);
        let parts = out["parts"].as_array().expect("parts");
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0]["text"], "Hello");
        assert!(parts[0].get("data_b64").is_none());
        assert_eq!(parts[1]["content_type"], "image/jpeg");
        assert_eq!(parts[1]["data_b64"], b64(&[0xFF, 0xD8, 0xFF, 0xD9]));
        assert!(parts[1].get("text").is_none());
    }

    #[test]
    fn retrieved_message_is_checked_against_the_rules() {
        let dir = tmp("mmsrules");
        fs::write(
            dir.join(PREFS_FILE),
            r#"{"spam":{"rules":[{"keyword":{"text":"hello"}}]}}"#,
        )
        .expect("write");
        let request = |known: bool| {
            json!({"pdu_b64": b64(&unhex(RETRIEVE)), "dir": dir.to_str(), "known": known})
                .to_string()
        };
        let spam = parse(&handle("mms_retrieved", &request(false)));
        assert_eq!(
            (spam["spam"].clone(), spam["reason"].clone()),
            (json!(true), json!("keyword_rule"))
        );
        assert_eq!(
            parse(&handle("mms_retrieved", &request(true)))["spam"],
            false
        );
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn wa_kinds_without_a_link_answer_the_documented_fallbacks() {
        let dir = tmp("wa");
        let request =
            json!({"dir": dir.to_str(), "chat": "905551112233@s.whatsapp.net", "text": "hi"})
                .to_string();
        assert_eq!(handle("wa_digest", &request), "{}");
        assert_eq!(handle("wa_reply", &request), "{\"ok\":false}");
        assert_eq!(handle("wa_digest", "garbage"), "{}");
        fs::remove_dir_all(&dir).ok();
    }
}
