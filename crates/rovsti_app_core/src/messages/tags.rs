//! Conversation tags shared by notifications, drafts and prefs.

use rovsti_core::ThreadId;

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
