//! Kotlin to Rust event channel (AD-5).
//!
//! `RovstiBridge.nativeOnEvent(kind, json)` lands in [`dispatch`], which only
//! enqueues a [`RawEvent`]; it never touches GPUI types and never blocks. The
//! app installs a fresh sink for each GPUI session ([`install_sink`]) and
//! drains the receiver in a foreground task. Events arriving with no sink are
//! dropped: the attach sequence re-pulls state anyway.
//!
//! This module is platform-free. On desktop nothing ever sends, so the
//! receiver simply never yields.

use std::sync::{Mutex, PoisonError};

#[cfg(test)]
use futures::channel::mpsc::TryRecvError;
use futures::channel::mpsc::{UnboundedReceiver, UnboundedSender, unbounded};

/// Event kinds Kotlin emits.
pub mod kind {
    pub const CALLS: &str = "calls";
    pub const CALL_LOG_CHANGED: &str = "call_log_changed";
    pub const CONTACTS_CHANGED: &str = "contacts_changed";
    pub const PERMISSIONS_CHANGED: &str = "permissions_changed";
    pub const BLOCKED_CHANGED: &str = "blocked_changed";
    pub const SIMS_CHANGED: &str = "sims_changed";
    pub const VOICEMAIL_CHANGED: &str = "voicemail_changed";
    pub const DOCUMENT_RESULT: &str = "document_result";
    pub const RINGTONE_RESULT: &str = "ringtone_result";
    pub const UI_REQUEST: &str = "ui_request";
    /// An FCM data message for WhatsApp (`{"kind":"call"|"message",..}`, AD-WA6). The
    /// event only wakes the process; the apps' gateway stream delivers the content.
    pub const WA_PUSH: &str = "wa_push";
    /// The user tapped Answer or Decline on a WhatsApp incoming-call notification
    /// (`{"call_id":..,"action":"answer"|"decline"}`); see [`parse_wa_call_action`].
    pub const WA_CALL_ACTION: &str = "wa_call_action";
    /// The keyguard was dismissed after `window::request_dismiss_keyguard` (no payload).
    pub const KEYGUARD_DISMISSED: &str = "keyguard_dismissed";
    /// The Telephony provider changed (`sms`/`mms` URIs); Rust re-pulls its threads.
    pub const SMS_CHANGED: &str = "sms_changed";
    /// A send moved along (`rovsti_core::sms::SmsStatus`).
    pub const SMS_STATUS: &str = "sms_status";
    /// An SMS arrived while the UI is attached (`{thread_id, address, body, date_ms}`).
    pub const SMS_RECEIVED: &str = "sms_received";
    /// An inline reply typed in a notification while the UI is attached (`{tag, text}`).
    pub const MSG_REPLY: &str = "msg_reply";
    /// An alarm for a scheduled SMS fired; Rust calls `sms::pull_fired`.
    pub const SCHEDULED_FIRED: &str = "scheduled_fired";
    /// A notification was tapped (`{tag}`): open that conversation.
    pub const OPEN_THREAD: &str = "open_thread";
}

/// What the user chose on a WhatsApp incoming-call notification.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WaCallAction {
    Answer,
    Decline,
}

/// Decodes a `wa_call_action` payload into the gateway call id and the action.
/// `None` for malformed JSON, a missing or empty id, or an unknown action.
pub fn parse_wa_call_action(json: &str) -> Option<(String, WaCallAction)> {
    let value: serde_json::Value = serde_json::from_str(json).ok()?;
    let id = value.get("call_id")?.as_str()?;
    if id.is_empty() {
        return None;
    }
    let action = match value.get("action")?.as_str()? {
        "answer" => WaCallAction::Answer,
        "decline" => WaCallAction::Decline,
        _ => return None,
    };
    Some((id.to_owned(), action))
}

/// One undecoded event.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RawEvent {
    pub kind: String,
    pub json: String,
}

pub type Receiver = UnboundedReceiver<RawEvent>;

/// The current sink and the generation that installed it.
struct Slot {
    generation: u64,
    tx: Option<UnboundedSender<RawEvent>>,
}

static SINK: Mutex<Slot> = Mutex::new(Slot {
    generation: 0,
    tx: None,
});

fn sink() -> std::sync::MutexGuard<'static, Slot> {
    SINK.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Which installed sink a session owns; see [`install_sink_owned`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SinkToken(u64);

/// Installs a new sink, replacing (and closing) any previous one.
pub fn install_sink() -> Receiver {
    install_sink_owned().0
}

/// Like [`install_sink`], plus a token so that only this session can clear
/// its own sink ([`clear_sink_if`]): an old session released after a new one
/// started must not cut the new one off.
pub fn install_sink_owned() -> (Receiver, SinkToken) {
    let (tx, rx) = unbounded();
    let mut slot = sink();
    slot.generation += 1;
    slot.tx = Some(tx);
    (rx, SinkToken(slot.generation))
}

/// Removes the sink; later events are dropped.
pub fn clear_sink() {
    sink().tx = None;
}

/// Removes the sink only if `token` still owns it. Returns whether it did.
pub fn clear_sink_if(token: SinkToken) -> bool {
    let mut slot = sink();
    if slot.generation == token.0 {
        slot.tx = None;
        true
    } else {
        false
    }
}

/// Enqueues an event. Returns whether a live sink accepted it.
pub fn dispatch(kind: impl Into<String>, json: impl Into<String>) -> bool {
    let guard = sink();
    let Some(tx) = guard.tx.as_ref() else {
        return false;
    };
    tx.unbounded_send(RawEvent {
        kind: kind.into(),
        json: json.into(),
    })
    .is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The sink is process-wide, so tests that use it run one at a time.
    static SERIAL: Mutex<()> = Mutex::new(());

    fn serial() -> std::sync::MutexGuard<'static, ()> {
        SERIAL.lock().unwrap_or_else(PoisonError::into_inner)
    }

    #[test]
    fn wa_call_action_parses() {
        assert_eq!(
            parse_wa_call_action(r#"{"call_id":"abc","action":"answer"}"#),
            Some(("abc".to_owned(), WaCallAction::Answer))
        );
        assert_eq!(
            parse_wa_call_action(r#"{"action":"decline","call_id":"x y"}"#),
            Some(("x y".to_owned(), WaCallAction::Decline))
        );
    }

    #[test]
    fn wa_call_action_rejects_bad_input() {
        for bad in [
            "",
            "nope",
            "{}",
            r#"{"call_id":"","action":"answer"}"#,
            r#"{"call_id":"a"}"#,
            r#"{"call_id":"a","action":"hold"}"#,
            r#"{"call_id":7,"action":"answer"}"#,
        ] {
            assert_eq!(parse_wa_call_action(bad), None, "{bad}");
        }
    }

    #[test]
    fn dropped_without_a_sink() {
        let _g = serial();
        clear_sink();
        assert!(!dispatch("calls", "{}"));
    }

    #[test]
    fn enqueued_with_a_sink_in_order() {
        let _g = serial();
        let mut rx = install_sink();
        assert!(dispatch("calls", "{\"seq\":1}"));
        assert!(dispatch("call_log_changed", "{}"));
        let first = rx.try_recv().expect("event");
        assert_eq!(first.kind, "calls");
        assert_eq!(first.json, "{\"seq\":1}");
        assert_eq!(rx.try_recv().expect("event").kind, "call_log_changed");
        assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
        clear_sink();
    }

    #[test]
    fn a_new_sink_replaces_the_old_one() {
        let _g = serial();
        let mut old = install_sink();
        let mut new = install_sink();
        assert!(dispatch("contacts_changed", "{}"));
        assert_eq!(old.try_recv(), Err(TryRecvError::Closed));
        assert_eq!(new.try_recv().expect("event").kind, "contacts_changed");
        clear_sink();
    }

    #[test]
    fn clear_sink_stops_delivery() {
        let _g = serial();
        let mut rx = install_sink();
        clear_sink();
        assert!(!dispatch("calls", "{}"));
        assert_eq!(rx.try_recv(), Err(TryRecvError::Closed));
    }

    #[test]
    fn a_stale_session_cannot_clear_the_new_sink() {
        let _g = serial();
        let (_old_rx, old) = install_sink_owned();
        let (mut new_rx, new) = install_sink_owned();
        assert!(!clear_sink_if(old));
        assert!(dispatch("calls", "{}"));
        assert_eq!(new_rx.try_recv().expect("event").kind, "calls");
        assert!(clear_sink_if(new));
        assert!(!dispatch("calls", "{}"));
    }

    #[test]
    fn an_idle_sink_never_yields() {
        let _g = serial();
        let mut rx = install_sink();
        assert_eq!(rx.try_recv(), Err(TryRecvError::Empty));
        clear_sink();
    }
}
