//! Reaction targets. Pure.

use rovsti_wa_proto::{SendReaction, WaMessage};
use whatsapp_rust::prelude::*;

use super::convert::is_group;

/// The key naming the message a reaction is about. A one-to-one chat is
/// identified by the chat JID alone; in a group the key also names the author
/// of the message.
pub fn message_key(
    chat: &str,
    id: &str,
    from_me: bool,
    participant: Option<&str>,
) -> wa::MessageKey {
    wa::MessageKey {
        remote_jid: Some(chat.to_owned()),
        from_me: Some(from_me),
        id: Some(id.to_owned()),
        participant: participant
            .filter(|_| is_group(chat))
            .filter(|p| !p.is_empty())
            .map(str::to_owned),
    }
}

/// The author of the target message in a group, from the most to the least
/// trustworthy source: the stored message, the request, then me (when it is
/// my own message). `None` in one-to-one chats and when nothing names the
/// author.
pub fn target_author(
    req: &SendReaction,
    stored: Option<&WaMessage>,
    own_jid: Option<&str>,
) -> Option<String> {
    if !is_group(&req.chat) {
        return None;
    }
    if req.from_me {
        return own_jid.map(str::to_owned);
    }
    stored
        .and_then(|m| m.sender.clone())
        .or_else(|| req.sender.clone())
        .filter(|s| !s.trim().is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_wa_proto::MessageBody;

    const DM: &str = "905321234567@s.whatsapp.net";
    const GROUP: &str = "120363-1@g.us";
    const ALICE: &str = "4915123@s.whatsapp.net";
    const ME: &str = "90500@s.whatsapp.net";

    fn req(chat: &str, from_me: bool, sender: Option<&str>) -> SendReaction {
        SendReaction {
            chat: chat.into(),
            message_id: "ABC".into(),
            from_me,
            sender: sender.map(str::to_owned),
            emoji: "👍".into(),
        }
    }

    fn stored(sender: &str) -> WaMessage {
        WaMessage {
            id: "ABC".into(),
            chat: GROUP.into(),
            sender: Some(sender.into()),
            from_me: false,
            timestamp_ms: 1,
            body: MessageBody::Text("x".into()),
            reactions: vec![],
        }
    }

    #[test]
    fn dm_key_has_no_participant() {
        let key = message_key(DM, "ABC", false, Some(ALICE));
        assert_eq!(key.remote_jid.as_deref(), Some(DM));
        assert_eq!(key.id.as_deref(), Some("ABC"));
        assert_eq!(key.from_me, Some(false));
        assert_eq!(key.participant, None);
        assert_eq!(
            target_author(&req(DM, false, Some(ALICE)), None, Some(ME)),
            None
        );
    }

    #[test]
    fn group_key_names_the_author() {
        let key = message_key(GROUP, "ABC", false, Some(ALICE));
        assert_eq!(key.remote_jid.as_deref(), Some(GROUP));
        assert_eq!(key.participant.as_deref(), Some(ALICE));
        assert_eq!(key.from_me, Some(false));
        // Blank participants are not sent.
        assert_eq!(message_key(GROUP, "ABC", false, Some("")).participant, None);
    }

    #[test]
    fn group_author_resolution() {
        let r = req(GROUP, false, Some("request@s.whatsapp.net"));
        // The stored message wins over what the client claims.
        assert_eq!(
            target_author(&r, Some(&stored(ALICE)), Some(ME)).as_deref(),
            Some(ALICE)
        );
        assert_eq!(
            target_author(&r, None, Some(ME)).as_deref(),
            Some("request@s.whatsapp.net")
        );
        assert_eq!(
            target_author(&req(GROUP, false, None), None, Some(ME)),
            None
        );
        // My own message in a group is authored by me.
        assert_eq!(
            target_author(&req(GROUP, true, None), None, Some(ME)).as_deref(),
            Some(ME)
        );
    }
}
