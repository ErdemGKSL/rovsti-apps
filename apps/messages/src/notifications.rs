//! Message notifications while the UI is attached (AD-MSG4, AD-MSG5).
//!
//! Kotlin posts its own notifications when no activity is attached. While the
//! UI is attached it tells Kotlin so (`uiAttached(true)`), and this module
//! decides what to show: [`should_notify`] filters, [`Digests`] keeps the last
//! lines per conversation, and `rovsti_core::notify::build` makes the spec that
//! `rovsti_android::notify::show` renders. All of it is platform-free.

use std::collections::HashMap;

use rovsti_core::{
    Message, MessageDirection, ThreadId,
    notify::{self, Incoming, MAX_LINES, NotificationSpec},
    otp,
    wire::{Handoff, HandoffAction},
};
use rovsti_wa_proto::jid_user;
use serde::Deserialize;

/// The notification tag and conversation key: `sms:<id>` or `wa:<jid>`.
pub fn thread_tag(thread: &ThreadId) -> String {
    match thread {
        ThreadId::Sms(id) => format!("sms:{id}"),
        ThreadId::WhatsApp(jid) => format!("wa:{jid}"),
    }
}

/// The inverse of [`thread_tag`]; `None` for anything else.
pub fn parse_tag(tag: &str) -> Option<ThreadId> {
    if let Some(id) = tag.strip_prefix("sms:") {
        return id.parse().ok().map(ThreadId::Sms);
    }
    tag.strip_prefix("wa:")
        .filter(|jid| !jid.is_empty())
        .map(|jid| ThreadId::WhatsApp(jid.to_owned()))
}

/// The conversation tag a WhatsApp chat handoff opens (`wa:<jid>`); `None` for any other action.
pub fn handoff_tag(handoff: &Handoff) -> Option<String> {
    (handoff.action == HandoffAction::WaChat).then(|| format!("wa:{}", handoff.jid))
}

/// The title of a conversation opened from a handoff: the name of a known thread, else the name the
/// other app sent, else the number part of the JID.
pub fn handoff_title(known: Option<String>, handoff: &Handoff) -> String {
    known
        .filter(|title| !title.is_empty())
        .or_else(|| {
            handoff
                .name
                .as_deref()
                .map(str::trim)
                .filter(|name| !name.is_empty())
                .map(str::to_owned)
        })
        .unwrap_or_else(|| jid_user(&handoff.jid).to_owned())
}

/// Whether a message deserves a notification: it came from someone else and
/// its conversation is not the one on screen.
pub fn should_notify(open: Option<&ThreadId>, msg: &Message) -> bool {
    msg.direction == MessageDirection::Incoming && open != Some(&msg.thread)
}

/// `sms_received`: an SMS arrived while the UI is attached.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
pub struct SmsReceived {
    #[serde(default)]
    pub thread_id: i64,
    #[serde(default)]
    pub address: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub date_ms: i64,
}

/// `msg_reply`: text typed into a notification's reply box.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
pub struct Reply {
    #[serde(default)]
    pub tag: String,
    #[serde(default)]
    pub text: String,
}

/// `open_thread`: a notification was tapped.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
pub struct OpenTag {
    #[serde(default)]
    pub tag: String,
    /// The message to glow (a message reminder tap); empty for a plain notification tap.
    #[serde(default)]
    pub message_id: String,
    /// That message's time (epoch ms), 0 when absent.
    #[serde(default)]
    pub at_ms: i64,
}

impl OpenTag {
    /// The message to glow after opening the chat: set by a message-reminder tap only.
    pub fn jump_target(&self) -> Option<rovsti_core::global_search::JumpTarget> {
        let id = self.message_id.trim();
        (!id.is_empty()).then(|| rovsti_core::global_search::JumpTarget {
            id: id.to_owned(),
            at_ms: self.at_ms,
        })
    }
}

/// The recent messages of each notified conversation, newest last.
#[derive(Debug, Default)]
pub struct Digests {
    by_tag: HashMap<String, Vec<Incoming>>,
}

impl Digests {
    /// Adds `incoming` and returns the conversation's lines, oldest first.
    pub fn push(&mut self, incoming: Incoming) -> &[Incoming] {
        let lines = self.by_tag.entry(incoming.thread.clone()).or_default();
        lines.push(incoming);
        let extra = lines.len().saturating_sub(MAX_LINES);
        lines.drain(..extra);
        lines
    }

    /// Forgets a conversation (its notification was cancelled).
    pub fn clear(&mut self, tag: &str) {
        self.by_tag.remove(tag);
    }
}

/// What the user allowed.
#[derive(Clone, Copy, Debug)]
pub struct Switches {
    pub enabled: bool,
    pub otp_copy: bool,
}

/// One incoming message, already classified, ready for a digest.
#[derive(Clone, Debug)]
pub struct Arrival {
    pub tag: String,
    pub title: String,
    pub is_group: bool,
    pub sender_title: String,
    pub body: String,
    pub at_ms: i64,
    pub spam: bool,
}

/// Adds the arrival to its digest and builds the notification. `None` when
/// notifications are off or muted for the conversation (the digest still
/// grows, so unmuting shows context) or when only spam is in the digest.
pub fn plan(
    digests: &mut Digests,
    arrival: Arrival,
    switches: Switches,
    muted: bool,
) -> Option<NotificationSpec> {
    let code = otp::detect(&arrival.body);
    let incoming = Incoming {
        thread: arrival.tag,
        sender_title: arrival.sender_title,
        body: arrival.body,
        at_ms: arrival.at_ms,
        verdict_spam: arrival.spam,
    };
    let recent = digests.push(incoming);
    if !switches.enabled || muted {
        return None;
    }
    let mut spec = notify::build(&arrival.title, arrival.is_group, recent, code)?;
    if !switches.otp_copy {
        // The reply action stays off: a passcode is not answered.
        spec.otp = None;
    }
    Some(spec)
}

#[cfg(test)]
mod tests {
    #[test]
    fn open_tag_parses_old_and_new_json() {
        let old: super::OpenTag = serde_json::from_str(r#"{"tag":"sms:3"}"#).expect("old");
        assert_eq!(old.tag, "sms:3");
        assert_eq!((old.message_id.as_str(), old.at_ms), ("", 0));
        let new: super::OpenTag =
            serde_json::from_str(r#"{"tag":"wa:x","message_id":"m1","at_ms":99}"#).expect("new");
        assert_eq!((new.message_id.as_str(), new.at_ms), ("m1", 99));
    }

    #[test]
    fn jump_target_only_for_reminder_taps() {
        let plain: super::OpenTag = serde_json::from_str(r#"{"tag":"sms:3"}"#).expect("plain");
        assert!(plain.jump_target().is_none());
        let tap: super::OpenTag =
            serde_json::from_str(r#"{"tag":"wa:x","message_id":" m1 ","at_ms":99}"#).expect("tap");
        let jump = tap.jump_target().expect("jump");
        assert_eq!((jump.id.as_str(), jump.at_ms), ("m1", 99));
    }

    use super::*;

    fn message(thread: ThreadId, direction: MessageDirection) -> Message {
        Message {
            thread,
            direction,
            sender: None,
            body: "hi".into(),
            timestamp_ms: 1,
            read: false,
        }
    }

    fn arrival(tag: &str, body: &str, at_ms: i64, spam: bool) -> Arrival {
        Arrival {
            tag: tag.into(),
            title: "Ali".into(),
            is_group: false,
            sender_title: "Ali".into(),
            body: body.into(),
            at_ms,
            spam,
        }
    }

    const ON: Switches = Switches {
        enabled: true,
        otp_copy: true,
    };

    #[test]
    fn tags_round_trip() {
        for thread in [
            ThreadId::Sms(7),
            ThreadId::WhatsApp("90532@s.whatsapp.net".into()),
        ] {
            assert_eq!(parse_tag(&thread_tag(&thread)), Some(thread));
        }
        for bad in ["", "sms:", "sms:x", "wa:", "mail:1"] {
            assert_eq!(parse_tag(bad), None, "{bad}");
        }
    }

    fn handoff(action: HandoffAction, name: Option<&str>) -> Handoff {
        Handoff {
            action,
            jid: "905321234567@s.whatsapp.net".into(),
            name: name.map(str::to_owned),
        }
    }

    #[test]
    fn a_chat_handoff_opens_its_wa_tag_with_the_given_name() {
        let chat = handoff(HandoffAction::WaChat, Some("Ayse"));
        let tag = handoff_tag(&chat).expect("a chat");
        assert_eq!(tag, "wa:905321234567@s.whatsapp.net");
        assert_eq!(
            parse_tag(&tag),
            Some(ThreadId::WhatsApp("905321234567@s.whatsapp.net".into()))
        );
        assert_eq!(handoff_title(None, &chat), "Ayse");
        // A thread the inbox already knows keeps its own title.
        assert_eq!(handoff_title(Some("Ayse K.".into()), &chat), "Ayse K.");
        // No chat yet and no name: fall back to the number.
        assert_eq!(
            handoff_title(None, &handoff(HandoffAction::WaChat, Some("  "))),
            "905321234567"
        );
        assert_eq!(
            handoff_title(None, &handoff(HandoffAction::WaChat, None)),
            "905321234567"
        );
    }

    #[test]
    fn other_handoff_actions_open_nothing_here() {
        assert_eq!(handoff_tag(&handoff(HandoffAction::WaCall, None)), None);
        assert_eq!(handoff_tag(&handoff(HandoffAction::Unknown, None)), None);
    }

    #[test]
    fn the_open_chat_and_my_own_messages_stay_quiet() {
        let wa = ThreadId::WhatsApp("a@s.whatsapp.net".into());
        let other = ThreadId::WhatsApp("b@s.whatsapp.net".into());
        let incoming = message(wa.clone(), MessageDirection::Incoming);
        assert!(should_notify(None, &incoming));
        assert!(should_notify(Some(&other), &incoming));
        assert!(!should_notify(Some(&wa), &incoming));
        let mine = message(wa, MessageDirection::Outgoing);
        assert!(!should_notify(None, &mine));
        // Channels never alias: SMS thread 1 is not the WhatsApp chat "1".
        let sms = message(ThreadId::Sms(1), MessageDirection::Incoming);
        assert!(should_notify(Some(&ThreadId::WhatsApp("1".into())), &sms));
        assert!(!should_notify(Some(&ThreadId::Sms(1)), &sms));
    }

    #[test]
    fn digest_keeps_the_newest_lines_per_conversation() {
        let mut digests = Digests::default();
        let mut spec = None;
        for i in 0..9 {
            spec = plan(
                &mut digests,
                arrival("sms:1", &format!("m{i}"), i, false),
                ON,
                false,
            );
        }
        let spec = spec.expect("spec");
        assert_eq!(spec.lines.len(), MAX_LINES);
        assert_eq!(spec.lines.last().map(String::as_str), Some("m8"));
        assert!(spec.reply && spec.otp.is_none());
        // Another conversation starts its own digest.
        let other = plan(&mut digests, arrival("sms:2", "yo", 1, false), ON, false).expect("ok");
        assert_eq!(other.lines, vec!["yo".to_owned()]);
        digests.clear("sms:1");
        let fresh = plan(&mut digests, arrival("sms:1", "new", 2, false), ON, false).expect("ok");
        assert_eq!(fresh.lines, vec!["new".to_owned()]);
    }

    #[test]
    fn switches_mute_and_spam() {
        let mut digests = Digests::default();
        let off = Switches {
            enabled: false,
            otp_copy: true,
        };
        assert!(plan(&mut digests, arrival("sms:1", "a", 1, false), off, false).is_none());
        assert!(plan(&mut digests, arrival("sms:1", "b", 2, false), ON, true).is_none());
        // Unmuted: the lines kept while quiet are still there.
        let spec = plan(&mut digests, arrival("sms:1", "c", 3, false), ON, false).expect("ok");
        assert_eq!(spec.lines.len(), 3);
        // Spam alone never notifies; spam after ham updates silently.
        assert!(plan(&mut digests, arrival("sms:9", "win", 1, true), ON, false).is_none());
        let silent = plan(&mut digests, arrival("sms:1", "win", 4, true), ON, false).expect("ok");
        assert!(silent.silent);
    }

    #[test]
    fn passcodes_get_a_chip_unless_switched_off() {
        let mut digests = Digests::default();
        let code = plan(
            &mut digests,
            arrival("sms:3", "Your code is 482913", 1, false),
            ON,
            false,
        )
        .expect("ok");
        assert_eq!(code.otp.as_deref(), Some("482913"));
        assert!(!code.reply);
        let no_chip = Switches {
            enabled: true,
            otp_copy: false,
        };
        let quiet = plan(
            &mut digests,
            arrival("sms:4", "Your code is 482913", 1, false),
            no_chip,
            false,
        )
        .expect("ok");
        assert_eq!(quiet.otp, None);
        assert!(!quiet.reply);
    }

    #[test]
    fn payloads_parse_with_missing_fields() {
        let r: SmsReceived =
            serde_json::from_str(r#"{"thread_id":4,"address":"+1","x":1}"#).expect("ok");
        assert_eq!((r.thread_id, r.body.as_str()), (4, ""));
        let reply: Reply = serde_json::from_str(r#"{"tag":"sms:4","text":"ok"}"#).expect("ok");
        assert_eq!(reply.text, "ok");
    }
}
