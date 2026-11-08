//! `msg_backup.*`: the encrypted message backup (`RMB1`): seal, open, the restore plan and the passphrase guide.

use super::{NOW, canon};
use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use rovsti_core::msg_backup::{
    self, BackupBody, BackupError, NONCE_LEN, RestorePlan, SALT_LEN, Strength,
};
use rovsti_core::sms::SmsRow;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "msg_backup.export",
        ExportReq,
        ExportAns,
        export,
        export_cases
    ),
    op!("msg_backup.parse", ParseReq, ParseAns, parse, parse_cases),
    op!("msg_backup.plan", PlanReq, RestorePlan, plan, plan_cases),
    op!(
        "msg_backup.strength",
        StrengthReq,
        StrengthAns,
        strength,
        strength_cases
    ),
];

/// Fixtures use a cheap key derivation; the apps send nothing and get [`msg_backup::ITERATIONS`].
const FIXTURE_ITERATIONS: u32 = 1_000;
/// The least a caller may ask for: the default is 600 000.
const MIN_ITERATIONS: u32 = 1_000;

fn bytes_of<const N: usize>(text: &str, what: &str) -> Result<[u8; N], FfiError> {
    let bytes = STANDARD
        .decode(text.trim())
        .map_err(|e| FfiError::invalid(format!("{what} is not base64: {e}")))?;
    <[u8; N]>::try_from(bytes.as_slice())
        .map_err(|_| FfiError::invalid(format!("{what} must be {N} bytes, got {}", bytes.len())))
}

// ---- export ----

#[derive(Deserialize)]
struct ExportReq {
    body: BackupBody,
    passphrase: String,
    /// 16 random bytes (standard base64) from a cryptographic RNG, fresh for every backup.
    salt: String,
    /// 12 random bytes (standard base64), fresh for every backup.
    nonce: String,
    #[serde(default)]
    iterations: Option<u32>,
    /// Keep only the text parts of each MMS (binary attachments live in files outside the archive).
    #[serde(default = "yes")]
    text_only_mms: bool,
}

fn yes() -> bool {
    true
}

#[derive(Serialize)]
struct ExportAns {
    /// The backup file, standard base64.
    bytes: String,
    size: usize,
    iterations: u32,
}

fn export(mut req: ExportReq) -> Result<ExportAns, FfiError> {
    if req.passphrase.is_empty() {
        return Err(FfiError::invalid("a backup needs a passphrase"));
    }
    let iterations = req.iterations.unwrap_or(msg_backup::ITERATIONS);
    if iterations < MIN_ITERATIONS {
        return Err(FfiError::invalid(format!(
            "at least {MIN_ITERATIONS} key derivation rounds are required"
        )));
    }
    let salt = bytes_of::<SALT_LEN>(&req.salt, "salt")?;
    let nonce = bytes_of::<NONCE_LEN>(&req.nonce, "nonce")?;
    if req.text_only_mms {
        req.body.mms = msg_backup::text_only_mms(&req.body.mms);
    }
    let sealed = msg_backup::seal(&req.body, &req.passphrase, salt, nonce, iterations);
    Ok(ExportAns {
        size: sealed.len(),
        bytes: STANDARD.encode(sealed),
        iterations,
    })
}

fn body() -> Value {
    canon::<BackupBody>(json!({"created_ms": NOW, "app_version": "0.1.0",
        "sms": [{"id": 1, "thread_id": 5, "address": "05321234567", "body": "See you at 8", "date_ms": NOW - 60_000,
                 "box": 1, "read": true, "delivery": "none"},
                {"id": 2, "thread_id": 5, "address": "05321234567", "body": "Ok", "date_ms": NOW - 30_000,
                 "box": 2, "read": true, "delivery": "delivered"}],
        "mms": [{"id": 3, "thread_id": 5, "addresses": ["05321234567"], "from": "05321234567", "date_ms": NOW - 20_000,
                 "outgoing": false, "read": true,
                 "parts": [{"content_type": "text/plain", "text": "Look"}, {"content_type": "image/jpeg", "path": "/cache/mms/3.jpg"}]}],
        "spam": {"enabled": true, "rules": ["unknown_with_link"], "allow": []},
        "scheduled": []}))
}

fn salt() -> String {
    STANDARD.encode([7u8; SALT_LEN])
}

fn nonce() -> String {
    STANDARD.encode([9u8; NONCE_LEN])
}

fn export_req(body: Value) -> Value {
    json!({"body": body, "passphrase": "correct horse battery", "salt": salt(), "nonce": nonce(),
           "iterations": FIXTURE_ITERATIONS})
}

fn export_cases() -> Vec<Case> {
    let mut no_passphrase = export_req(body());
    no_passphrase["passphrase"] = json!("");
    let mut weak = export_req(body());
    weak["iterations"] = json!(10);
    let mut short_salt = export_req(body());
    short_salt["salt"] = json!(STANDARD.encode([1u8; 4]));
    vec![
        Case::new(export_req(body())),
        Case::named("empty_passphrase", no_passphrase),
        Case::named("too_few_rounds", weak),
        Case::named("short_salt", short_salt),
    ]
}

fn sealed_fixture(passphrase: &str) -> String {
    let body: BackupBody = serde_json::from_value(body()).expect("fixture body");
    let mut body = body;
    body.mms = msg_backup::text_only_mms(&body.mms);
    STANDARD.encode(msg_backup::seal(
        &body,
        passphrase,
        [7u8; SALT_LEN],
        [9u8; NONCE_LEN],
        FIXTURE_ITERATIONS,
    ))
}

// ---- parse ----

#[derive(Deserialize)]
struct ParseReq {
    /// The backup file, standard base64.
    bytes: String,
    passphrase: String,
}

#[derive(Serialize)]
struct ParseAns {
    body: BackupBody,
}

fn backup_error(error: BackupError) -> FfiError {
    let (kind, reason) = match error {
        BackupError::WrongPassphrase => (ErrorKind::Auth, "wrong_passphrase"),
        BackupError::NotABackup => (ErrorKind::Parse, "not_a_backup"),
        BackupError::NewerVersion(_) => (ErrorKind::Parse, "newer_version"),
        BackupError::Corrupt => (ErrorKind::Parse, "corrupt"),
    };
    FfiError::new(kind, error.to_string()).with_detail(json!({"reason": reason}))
}

fn parse(req: ParseReq) -> Result<ParseAns, FfiError> {
    let bytes = STANDARD
        .decode(req.bytes.trim())
        .map_err(|e| FfiError::invalid(format!("the backup is not base64: {e}")))?;
    Ok(ParseAns {
        body: msg_backup::open(&bytes, &req.passphrase).map_err(backup_error)?,
    })
}

fn parse_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"bytes": sealed_fixture("correct horse battery"), "passphrase": "correct horse battery"}),
        ),
        Case::named(
            "wrong_passphrase",
            json!({"bytes": sealed_fixture("correct horse battery"), "passphrase": "nope"}),
        ),
        Case::named(
            "not_a_backup",
            json!({"bytes": STANDARD.encode(b"just some text"), "passphrase": "x"}),
        ),
    ]
}

// ---- plan ----

#[derive(Deserialize)]
struct PlanReq {
    backup: BackupBody,
    #[serde(default)]
    existing: Vec<SmsRow>,
}

fn plan(req: PlanReq) -> Result<RestorePlan, FfiError> {
    Ok(msg_backup::restore_plan(&req.backup, &req.existing))
}

fn plan_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"backup": body(), "existing": canon::<Vec<SmsRow>>(json!([
            {"id": 40, "thread_id": 9, "address": "+90 532 123 45 67", "body": "Ok", "date_ms": NOW - 30_000, "box": 2}]))}),
        ),
        Case::named("nothing_on_device", json!({"backup": body()})),
    ]
}

// ---- strength ----

#[derive(Deserialize)]
struct StrengthReq {
    passphrase: String,
}

#[derive(Serialize)]
struct StrengthAns {
    strength: Strength,
}

fn strength(req: StrengthReq) -> Result<StrengthAns, FfiError> {
    Ok(StrengthAns {
        strength: msg_backup::passphrase_strength(&req.passphrase),
    })
}

fn strength_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"passphrase": "correct horse battery"})),
        Case::named("weak", json!({"passphrase": "abc"})),
        Case::named("fair", json!({"passphrase": "tomato-soup"})),
    ]
}
