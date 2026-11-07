//! SMS and MMS through the Telephony provider (AD-MSG2). Kotlin side: `SmsQuery`,
//! `SmsSend`, `MmsCodec` and `ScheduledSms`, reached through `RovstiBridge`.
//!
//! Every call blocks on a content-provider read or a system service: call them
//! off the UI thread. Ids and timestamps cross the seam as decimal strings
//! (AD-5 allows strings, ints and bools only). A send returns at once; its
//! progress arrives later as the `sms_status` event
//! (`rovsti_core::sms::SmsStatus`). On desktop every function returns
//! [`crate::Error::Unsupported`].

use rovsti_core::sms::SmsSend;
use serde_json::json;

use crate::{
    Result,
    bridge::{self, Arg},
};

fn clamp_limit(limit: usize) -> i32 {
    i32::try_from(limit).unwrap_or(i32::MAX)
}

/// The newest `limit` threads: `Envelope<SmsThreadsPayload>` JSON.
pub fn threads(limit: usize) -> Result<String> {
    bridge::call_string("pullSmsThreads", &[Arg::Int(clamp_limit(limit))])
}

/// One page of a thread, newest first, older than `before_ms` when given:
/// `Envelope<SmsMessagesPayload>` JSON.
pub fn messages(thread_id: i64, before_ms: Option<i64>, limit: usize) -> Result<String> {
    let thread = thread_id.to_string();
    let before = before_ms.map(|ms| ms.to_string()).unwrap_or_default();
    bridge::call_string(
        "pullSmsMessages",
        &[
            Arg::Str(&thread),
            Arg::Str(&before),
            Arg::Int(clamp_limit(limit)),
        ],
    )
}

/// The active SIMs: `Envelope<SmsSimsPayload>` JSON.
pub fn sims() -> Result<String> {
    bridge::call_string("pullSmsSims", &[])
}

/// Starts an SMS send. `false` when it could not even be queued (no SMS role,
/// no recipient); otherwise `sms_status` events follow for `req.local_id`.
pub fn send(req: &SmsSend) -> Result<bool> {
    let json = serde_json::to_string(req).unwrap_or_default();
    bridge::call_bool("smsSend", &[Arg::Str(&json)])
}

/// The JSON `RovstiBridge.smsSendMms` reads.
pub fn mms_request(
    local_id: u64,
    to: &[String],
    text: &str,
    image_path: Option<&str>,
    sub_id: Option<i32>,
) -> String {
    json!({
        "local_id": local_id,
        "to": to,
        "text": text,
        "image_path": image_path,
        "sub_id": sub_id,
    })
    .to_string()
}

/// Starts an MMS send with an optional image file (a path the app can read).
pub fn send_mms(
    local_id: u64,
    to: &[String],
    text: &str,
    image_path: Option<&str>,
    sub_id: Option<i32>,
) -> Result<bool> {
    let json = mms_request(local_id, to, text, image_path, sub_id);
    bridge::call_bool("smsSendMms", &[Arg::Str(&json)])
}

/// Marks every message of a thread read.
pub fn mark_read(thread_id: i64) -> Result<bool> {
    bridge::call_bool("smsMarkRead", &[Arg::Str(&thread_id.to_string())])
}

/// Deletes a thread and its messages. Needs the SMS role.
pub fn delete_thread(thread_id: i64) -> Result<bool> {
    bridge::call_bool("smsDeleteThread", &[Arg::Str(&thread_id.to_string())])
}

/// Deletes one message by key (`"sms:12"` or `"mms:7"`). Needs the SMS role;
/// `false` when the key is malformed or the provider refused.
pub fn delete_message(key: &str) -> Result<bool> {
    bridge::call_bool("smsDeleteMessage", &[Arg::Str(key)])
}

/// Searches SMS bodies with a SQL `LIKE` pattern (`like` carries its own `%`
/// wildcards and `\` escapes), newest first, at most `limit` rows:
/// `Envelope<SmsMessagesPayload>` JSON, each row with its `thread_id`.
pub fn search(like: &str, limit: usize) -> Result<String> {
    bridge::call_string("smsSearch", &[Arg::Str(like), Arg::Int(clamp_limit(limit))])
}

/// Writes messages from a backup into the provider (needs the SMS role).
/// Answers `{"inserted":n,"skipped":m}`.
pub fn insert_restored(json: &str) -> Result<String> {
    bridge::call_string("smsInsertRestored", &[Arg::Str(json)])
}

/// Arms one alarm for a scheduled SMS (`{id, at_ms, to, body, sub_id}`).
pub fn arm_scheduled(item_json: &str) -> Result<bool> {
    bridge::call_bool("armScheduledSms", &[Arg::Str(item_json)])
}

/// Cancels the alarm and forgets the payload.
pub fn disarm_scheduled(id: u64) -> Result<bool> {
    bridge::call_bool("disarmScheduledSms", &[Arg::Str(&id.to_string())])
}

/// The scheduled sends that fired since the last call, as a JSON array of
/// `{id, ok, at_ms}`; the list is cleared by the call.
pub fn pull_fired() -> Result<String> {
    bridge::call_string("pullFiredSms", &[])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        let req = SmsSend::default();
        assert!(matches!(threads(10), Err(Error::Unsupported)));
        assert!(matches!(messages(1, None, 10), Err(Error::Unsupported)));
        assert!(matches!(messages(1, Some(5), 10), Err(Error::Unsupported)));
        assert!(matches!(sims(), Err(Error::Unsupported)));
        assert!(matches!(send(&req), Err(Error::Unsupported)));
        assert!(matches!(
            send_mms(1, &["+905".into()], "hi", None, None),
            Err(Error::Unsupported)
        ));
        assert!(matches!(mark_read(1), Err(Error::Unsupported)));
        assert!(matches!(delete_thread(1), Err(Error::Unsupported)));
        assert!(matches!(delete_message("sms:1"), Err(Error::Unsupported)));
        assert!(matches!(search("%hi%", 20), Err(Error::Unsupported)));
        assert!(matches!(insert_restored("{}"), Err(Error::Unsupported)));
        assert!(matches!(arm_scheduled("{}"), Err(Error::Unsupported)));
        assert!(matches!(disarm_scheduled(1), Err(Error::Unsupported)));
        assert!(matches!(pull_fired(), Err(Error::Unsupported)));
    }

    #[test]
    fn mms_request_shape() {
        let json = mms_request(7, &["+90 555".into()], "hi", Some("/c/a.jpg"), Some(2));
        let v: serde_json::Value = serde_json::from_str(&json).expect("json");
        assert_eq!(v["local_id"], 7);
        assert_eq!(v["to"][0], "+90 555");
        assert_eq!(v["image_path"], "/c/a.jpg");
        assert_eq!(v["sub_id"], 2);
        let v: serde_json::Value =
            serde_json::from_str(&mms_request(1, &[], "", None, None)).expect("json");
        assert!(v["image_path"].is_null());
    }

    #[test]
    fn limits_clamp() {
        assert_eq!(clamp_limit(5), 5);
        assert_eq!(clamp_limit(usize::MAX), i32::MAX);
    }
}
