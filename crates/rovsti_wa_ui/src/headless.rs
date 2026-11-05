//! WhatsApp work for the cold notification paths: no GPUI, no event loop.
//!
//! `rovsti_android::headless` hands these functions a JSON request while the
//! process may have no UI. Both block on the gateway, which is fine on the
//! receiver thread Kotlin runs them on. They read the same
//! `whatsapp-link.json` the settings screen writes.

use std::path::Path;

use rovsti_core::{
    notify::{self, Incoming, NotificationSpec},
    thread::MessageDirection,
};
use rovsti_wa_client::Gateway;
use rovsti_wa_proto::{
    ChatSummary, ConnectKey, GatewayUrl, MessageBody, SendText, WaMessage, jid_user, to_rovsti,
};
use serde::Deserialize;

use crate::model::{LINK_FILE, StoredLink};

/// How many recent messages a digest shows.
const DIGEST_MESSAGES: u32 = 5;

#[derive(Deserialize)]
struct DigestIn {
    dir: String,
    chat: String,
}

#[derive(Deserialize)]
struct ReplyIn {
    dir: String,
    chat: String,
    text: String,
}

/// The gateway for the link saved in `dir`; `None` when there is none or it
/// no longer parses.
fn gateway_in(dir: &str) -> Option<Gateway> {
    let link: StoredLink = rovsti_ui::storage::load_json(Path::new(dir), LINK_FILE)?;
    let url = GatewayUrl::parse(&link.gateway_url).ok()?;
    let key = ConnectKey::parse(&link.key).ok()?;
    Some(Gateway::new(url, Some(key)))
}

/// The pure half of [`wa_digest`]. `messages` is newest first, as the gateway
/// returns it; our own messages and unsupported ones are not notified.
pub fn build_digest(
    chat: &str,
    title: Option<&str>,
    is_group: bool,
    messages: &[WaMessage],
) -> Option<NotificationSpec> {
    let mut recent: Vec<Incoming> = messages
        .iter()
        .filter(|m| !m.from_me && !matches!(m.body, MessageBody::Unsupported))
        .map(|m| {
            let mapped = to_rovsti(m);
            debug_assert_eq!(mapped.direction, MessageDirection::Incoming);
            Incoming {
                thread: format!("wa:{chat}"),
                sender_title: m
                    .sender
                    .as_deref()
                    .map(|jid| jid_user(jid).to_owned())
                    .unwrap_or_default(),
                body: mapped.body,
                at_ms: m.timestamp_ms,
                verdict_spam: false,
            }
        })
        .collect();
    recent.sort_by_key(|m| m.at_ms);
    let fallback = jid_user(chat);
    let title = title.filter(|t| !t.is_empty()).unwrap_or(fallback);
    notify::build(title, is_group, &recent, None)
}

/// `{dir, chat}` in; a `NotificationSpec` as JSON out, `{}` on any error (no
/// link, gateway unreachable, nothing to show).
pub fn wa_digest(json: &str) -> String {
    digest(json).unwrap_or_else(|| "{}".to_owned())
}

fn digest(json: &str) -> Option<String> {
    let input: DigestIn = serde_json::from_str(json).ok()?;
    let gateway = gateway_in(&input.dir)?;
    let messages = gateway
        .messages(&input.chat, None, DIGEST_MESSAGES)
        .map_err(|e| log::warn!("wa_digest: messages failed: {e}"))
        .ok()?;
    // The title is a nicety: a failed chat list falls back to the number.
    let chat: Option<ChatSummary> = gateway
        .chats()
        .ok()
        .and_then(|chats| chats.into_iter().find(|c| c.jid == input.chat));
    let is_group = chat
        .as_ref()
        .map_or_else(|| input.chat.ends_with("@g.us"), |c| c.is_group);
    let title = chat.as_ref().and_then(|c| c.name.as_deref());
    let spec = build_digest(&input.chat, title, is_group, &messages)?;
    serde_json::to_string(&spec).ok()
}

/// `{dir, chat, text}` in; `{"ok":true|false}` out.
pub fn wa_reply(json: &str) -> String {
    let ok = reply(json).is_some();
    format!("{{\"ok\":{ok}}}")
}

fn reply(json: &str) -> Option<()> {
    let input: ReplyIn = serde_json::from_str(json).ok()?;
    if input.text.trim().is_empty() || input.chat.is_empty() {
        return None;
    }
    let gateway = gateway_in(&input.dir)?;
    gateway
        .send_text(&SendText {
            chat: input.chat,
            text: input.text,
            quoted_id: None,
        })
        .map_err(|e| log::warn!("wa_reply: send failed: {e}"))
        .ok()
        .map(drop)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn text(id: &str, from_me: bool, at: i64, body: &str, sender: Option<&str>) -> WaMessage {
        WaMessage {
            id: id.into(),
            chat: "905551112233@s.whatsapp.net".into(),
            sender: sender.map(str::to_owned),
            from_me,
            timestamp_ms: at,
            body: MessageBody::Text(body.into()),
            reactions: vec![],
        }
    }

    fn tmp(name: &str) -> std::path::PathBuf {
        let dir =
            std::env::temp_dir().join(format!("rovsti-wa-headless-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn missing_link_file_is_empty_object() {
        let dir = tmp("nolink");
        let json = format!(
            "{{\"dir\":{:?},\"chat\":\"1@s.whatsapp.net\"}}",
            dir.to_str().unwrap()
        );
        assert_eq!(wa_digest(&json), "{}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn bad_digest_input_is_empty_object() {
        assert_eq!(wa_digest(""), "{}");
        assert_eq!(wa_digest("{\"dir\":\"/x\"}"), "{}");
    }

    #[test]
    fn reply_error_paths() {
        assert_eq!(wa_reply("not json"), "{\"ok\":false}");
        assert_eq!(
            wa_reply("{\"dir\":\"/x\",\"chat\":\"c\"}"),
            "{\"ok\":false}"
        );
        // Blank text and a missing link never reach the network.
        assert_eq!(
            wa_reply("{\"dir\":\"/nonexistent\",\"chat\":\"c\",\"text\":\"  \"}"),
            "{\"ok\":false}"
        );
        assert_eq!(
            wa_reply("{\"dir\":\"/nonexistent\",\"chat\":\"c\",\"text\":\"hi\"}"),
            "{\"ok\":false}"
        );
    }

    #[test]
    fn reply_with_an_unparsable_link_is_not_ok() {
        let dir = tmp("badlink");
        fs::write(
            dir.join(LINK_FILE),
            "{\"gateway_url\":\"not a url\",\"instance_id\":\"i\",\"key\":\"k\"}",
        )
        .unwrap();
        let json = format!(
            "{{\"dir\":{:?},\"chat\":\"c\",\"text\":\"hi\"}}",
            dir.to_str().unwrap()
        );
        assert_eq!(wa_reply(&json), "{\"ok\":false}");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn digest_is_oldest_first_and_skips_own_messages() {
        let msgs = vec![
            text("3", false, 30, "c", None),
            text("2", true, 20, "mine", None),
            text("1", false, 10, "a", None),
        ];
        let spec = build_digest("905551112233@s.whatsapp.net", Some("Ali"), false, &msgs).unwrap();
        assert_eq!(spec.title, "Ali");
        assert_eq!(spec.lines, vec!["a", "c"]);
        assert_eq!(spec.tag, "wa:905551112233@s.whatsapp.net");
        assert!(spec.reply);
    }

    #[test]
    fn digest_falls_back_to_the_number_and_prefixes_group_senders() {
        let msgs = vec![text(
            "1",
            false,
            1,
            "hi",
            Some("905320000000:3@s.whatsapp.net"),
        )];
        let spec = build_digest("120363@g.us", None, true, &msgs).unwrap();
        assert_eq!(spec.title, "120363");
        assert_eq!(spec.lines, vec!["905320000000: hi"]);
    }

    #[test]
    fn nothing_to_show_is_none() {
        assert!(build_digest("c", None, false, &[]).is_none());
        let own = vec![text("1", true, 1, "mine", None)];
        assert!(build_digest("c", None, false, &own).is_none());
    }
}
