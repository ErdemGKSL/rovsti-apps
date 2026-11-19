//! WhatsApp work for the cold notification paths: no GPUI, no event loop.
//!
//! `rosty_android::headless` hands these functions a JSON request while the
//! process may have no UI. Both block on the gateway, which is fine on the
//! receiver thread Kotlin runs them on. They read the same
//! `whatsapp-link.json` the settings screen writes.

use std::path::Path;
use std::sync::Mutex;

use rosty_core::{
    notify::{self, Incoming, NotificationSpec},
    thread::MessageDirection,
};
use rosty_wa_client::{ClientError, Gateway};
use rosty_wa_proto::{
    ChatSummary, ConnectKey, GatewayUrl, MessageBody, SendText, WaMessage, jid_user, to_rosty,
};
use serde::Deserialize;
use zeroize::Zeroizing;

use super::link::{LINK_FILE, StoredLink};

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
pub fn gateway_in(dir: &str) -> Option<Gateway> {
    let link: StoredLink = crate::storage::load_json(Path::new(dir), LINK_FILE)?;
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
            let mapped = to_rosty(m);
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
    let mut out = serde_json::to_value(&spec).ok()?;
    if is_group {
        // Who wrote each line, so the app can put the name it knows (a saved contact first) before the text.
        out["senders"] = digest_senders(&messages, spec.lines.len()).into();
    }
    serde_json::to_string(&out).ok()
}

/// The sender of each of the `lines` newest notified messages, oldest first like [`build_digest`]'s lines: the bare
/// user JID (`<number>@<server>`, no device part), empty when the message names nobody. In a group every line of
/// the digest starts with `<number>: `; the caller swaps that for the name it shows everywhere else.
pub fn digest_senders(messages: &[WaMessage], lines: usize) -> Vec<String> {
    let mut notified: Vec<&WaMessage> = messages
        .iter()
        .filter(|m| !m.from_me && !matches!(m.body, MessageBody::Unsupported))
        .collect();
    notified.sort_by_key(|m| m.timestamp_ms);
    let skip = notified.len().saturating_sub(lines);
    notified[skip..]
        .iter()
        .map(|m| m.sender.as_deref().map(bare_jid).unwrap_or_default())
        .collect()
}

/// `905320000000:3@s.whatsapp.net` as `905320000000@s.whatsapp.net`.
fn bare_jid(jid: &str) -> String {
    match jid.split_once('@') {
        Some((_, server)) => format!("{}@{server}", jid_user(jid)),
        None => jid_user(jid).to_owned(),
    }
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
    send_text_in(&input.dir, &input.chat, &input.text)
        .map_err(|e| log::warn!("wa_reply: send failed: {e:?}"))
        .ok()
}

/// Why [`send_text_in`] failed, split by whether trying again can help.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SendFailure {
    /// No usable WhatsApp link in the data directory.
    NoLink,
    /// The request never got an answer (transport or TLS): a later attempt may work.
    Retryable(String),
    /// The gateway answered with a refusal, or the link is unusable: do not retry.
    Fatal(String),
}

impl From<ClientError> for SendFailure {
    fn from(error: ClientError) -> Self {
        match error {
            ClientError::Http(_) | ClientError::Tls(_) => Self::Retryable(error.to_string()),
            other => Self::Fatal(other.to_string()),
        }
    }
}

/// The link a Stops trip carries in RAM (`plans/wa-vault.md` 13.3): copied from the unlocked vault when a trip
/// with an arriving-soon message starts, dropped when the trip ends. Never written anywhere; a new process starts
/// without it.
struct TripLink {
    url: String,
    key: Zeroizing<String>,
}

static TRIP_LINK: Mutex<Option<TripLink>> = Mutex::new(None);

fn trip_link() -> std::sync::MutexGuard<'static, Option<TripLink>> {
    TRIP_LINK
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Keeps `url` and `key` for the running trip's notice, replacing what was kept. A link that does not parse is
/// refused and nothing is kept.
pub fn hold_trip_link(url: &str, key: &str) -> bool {
    let usable = GatewayUrl::parse(url).is_ok() && ConnectKey::parse(key).is_ok();
    *trip_link() = usable.then(|| TripLink {
        url: url.to_owned(),
        key: Zeroizing::new(key.to_owned()),
    });
    usable
}

/// Drops the trip's link (the trip ended). The key zeroizes itself.
pub fn release_trip_link() {
    trip_link().take();
}

/// Whether a trip link is kept.
pub fn trip_link_held() -> bool {
    trip_link().is_some()
}

/// Tests that hold a trip link, or that need none held, take this lock: the link is one per process.
#[cfg(test)]
pub(crate) fn trip_link_test_lock() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: Mutex<()> = Mutex::new(());
    LOCK.lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Sends `text` to `chat` through the link the running trip keeps in RAM ([`hold_trip_link`]); `NoLink` without
/// one. `dir` is not read: no plaintext link file is used since the vault. Blocks.
pub fn send_text_held(_dir: &str, chat: &str, text: &str) -> Result<(), SendFailure> {
    let gateway = {
        let held = trip_link();
        let link = held.as_ref().ok_or(SendFailure::NoLink)?;
        let url = GatewayUrl::parse(&link.url).map_err(|e| SendFailure::Fatal(e.to_string()))?;
        let key = ConnectKey::parse(&link.key).map_err(|e| SendFailure::Fatal(e.to_string()))?;
        Gateway::new(url, Some(key))
    };
    gateway
        .send_text(&SendText::new(chat, text, None))
        .map(drop)
        .map_err(SendFailure::from)
}

/// Sends `text` to `chat` through the gateway linked in `dir`. Blocks.
pub fn send_text_in(dir: &str, chat: &str, text: &str) -> Result<(), SendFailure> {
    let gateway = gateway_in(dir).ok_or(SendFailure::NoLink)?;
    gateway
        .send_text(&SendText::new(chat, text, None))
        .map(drop)
        .map_err(SendFailure::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    const TRIP_KEY: &str = "rwk_BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU";

    #[test]
    fn a_trip_keeps_its_link_in_ram_until_it_is_released() {
        let _one = trip_link_test_lock();
        release_trip_link();
        assert_eq!(
            send_text_held("/nowhere", "chat", "hi"),
            Err(SendFailure::NoLink)
        );
        // A link that does not parse is not kept.
        assert!(!hold_trip_link("ftp://rosty.erdemdev.tr", TRIP_KEY));
        assert!(!hold_trip_link("https://rosty.erdemdev.tr", "rwk_short"));
        assert!(!trip_link_held());
        assert!(hold_trip_link("https://rosty.erdemdev.tr", TRIP_KEY));
        assert!(trip_link_held());
        release_trip_link();
        assert!(!trip_link_held());
        assert_eq!(
            send_text_held("/nowhere", "chat", "hi"),
            Err(SendFailure::NoLink)
        );
    }

    fn text(id: &str, from_me: bool, at: i64, body: &str, sender: Option<&str>) -> WaMessage {
        WaMessage {
            id: id.into(),
            chat: "905551112233@s.whatsapp.net".into(),
            sender: sender.map(str::to_owned),
            from_me,
            timestamp_ms: at,
            body: MessageBody::Text(body.into()),
            reactions: vec![],
            chat_alt: None,
            sender_alt: None,
        }
    }

    fn tmp(name: &str) -> std::path::PathBuf {
        let dir =
            std::env::temp_dir().join(format!("rosty-wa-headless-{name}-{}", std::process::id()));
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
    fn send_failures_split_by_retryability() {
        use rosty_wa_proto::{ApiError, ErrorCode};
        let retry = |e| matches!(SendFailure::from(e), SendFailure::Retryable(_));
        assert!(retry(ClientError::Http("reset".into())));
        assert!(retry(ClientError::Tls("handshake".into())));
        assert!(!retry(ClientError::Unauthorized));
        assert!(!retry(ClientError::Decode("x".into())));
        assert!(!retry(ClientError::Closed));
        assert!(!retry(ClientError::Api(ApiError::new(
            ErrorCode::Unauthorized,
            "no"
        ))));
    }

    #[test]
    fn send_without_a_link_is_no_link() {
        let dir = tmp("sendnolink");
        assert_eq!(
            send_text_in(dir.to_str().unwrap(), "c", "hi"),
            Err(SendFailure::NoLink)
        );
        let _ = fs::remove_dir_all(dir);
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
    fn digest_senders_follow_the_lines_oldest_first_without_the_device_part() {
        let msgs = vec![
            text("4", false, 40, "d", Some("905320000002@s.whatsapp.net")),
            text("3", true, 30, "mine", None),
            text("2", false, 20, "b", None),
            text("1", false, 10, "a", Some("905320000001:3@s.whatsapp.net")),
        ];
        let spec = build_digest("120363@g.us", Some("Flatmates"), true, &msgs).unwrap();
        assert_eq!(spec.lines, vec!["905320000001: a", "b", "905320000002: d"]);
        assert_eq!(
            digest_senders(&msgs, spec.lines.len()),
            vec![
                "905320000001@s.whatsapp.net",
                "",
                "905320000002@s.whatsapp.net"
            ]
        );
        // Only the lines the digest kept (its newest ones).
        assert_eq!(
            digest_senders(&msgs, 1),
            vec!["905320000002@s.whatsapp.net"]
        );
    }

    #[test]
    fn nothing_to_show_is_none() {
        assert!(build_digest("c", None, false, &[]).is_none());
        let own = vec![text("1", true, 1, "mine", None)];
        assert!(build_digest("c", None, false, &own).is_none());
    }
}
