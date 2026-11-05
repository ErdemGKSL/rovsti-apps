//! WhatsApp protobufs to gateway types. Everything here is pure: the live
//! path ([`message`]) and the history path ([`crate::wa::history`]) both go
//! through [`convert`], so a message looks the same however it arrived.

use rovsti_wa_proto::{MediaKind, MessageBody, Reaction, WaMessage, WaUser};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::types::message::MessageSource;
use whatsapp_rust::waproto::buffa::Message as _;

use crate::driver::DriverError;

/// Parses a JID from a request, rejecting anything malformed as a bad request.
pub fn parse_jid(text: &str) -> Result<Jid, DriverError> {
    let text = text.trim();
    if text.is_empty() {
        return Err(DriverError::BadRequest("jid is empty".to_owned()));
    }
    text.parse::<Jid>()
        .map_err(|e| DriverError::BadRequest(format!("invalid jid {text:?}: {e}")))
}

/// One usync answer: `(queried number JID, registered, business, verified name)`.
pub type LookupAnswer = (String, bool, bool, Option<String>);

/// Builds one [`WaUser`] per queried JID, in query order. A JID WhatsApp did
/// not answer comes back with `on_whatsapp: false`.
pub fn lookup_answers(
    queried: &[String],
    answers: impl IntoIterator<Item = LookupAnswer>,
) -> Vec<WaUser> {
    let answers: Vec<LookupAnswer> = answers.into_iter().collect();
    queried
        .iter()
        .map(|jid| {
            let answer = answers.iter().find(|(a, ..)| a == jid);
            WaUser {
                jid: jid.clone(),
                on_whatsapp: answer.is_some_and(|(_, on, ..)| *on),
                business: answer.is_some_and(|(_, on, business, _)| *on && *business),
                verified_name: answer.and_then(|(.., name)| name.clone()),
            }
        })
        .collect()
}

/// True for group chat JIDs.
pub fn is_group(jid: &str) -> bool {
    jid.ends_with("@g.us")
}

/// Where a message came from, in gateway terms.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Origin<'a> {
    pub id: &'a str,
    pub chat: &'a str,
    /// The author's JID. Kept for group chats only; in a one-to-one chat the
    /// chat JID already says who it is.
    pub sender: Option<&'a str>,
    pub from_me: bool,
    pub timestamp_ms: i64,
}

/// What a WhatsApp message turned into.
#[derive(Clone, Debug, PartialEq)]
pub enum Converted {
    Message(WaMessage),
    /// A reaction to an earlier message (`emoji` is empty when removed).
    Reaction {
        chat: String,
        target_id: String,
        reaction: Reaction,
    },
    /// Protocol plumbing that is not a message the user sees.
    Ignore,
}

fn non_empty(text: &Option<String>) -> Option<String> {
    text.as_deref().filter(|t| !t.is_empty()).map(str::to_owned)
}

fn media(
    kind: MediaKind,
    caption: Option<String>,
    mime: &Option<String>,
    size: Option<u64>,
) -> MessageBody {
    MessageBody::Media {
        kind,
        caption,
        mime: mime.clone().unwrap_or_default(),
        size: size.unwrap_or(0),
    }
}

/// The body of `message`, or the reaction it carries.
fn content(message: &wa::Message) -> Content {
    let base = message.get_base_message();

    if let Some(text) = base.text_content() {
        return Content::Body(MessageBody::Text(text.to_owned()));
    }
    if let Some(m) = base.image_message.as_option() {
        return Content::Body(media(
            MediaKind::Image,
            non_empty(&m.caption),
            &m.mimetype,
            m.file_length,
        ));
    }
    if let Some(m) = base.video_message.as_option() {
        return Content::Body(media(
            MediaKind::Video,
            non_empty(&m.caption),
            &m.mimetype,
            m.file_length,
        ));
    }
    if let Some(m) = base.audio_message.as_option() {
        return Content::Body(media(MediaKind::Audio, None, &m.mimetype, m.file_length));
    }
    if let Some(m) = base.document_message.as_option() {
        return Content::Body(media(
            MediaKind::Document,
            non_empty(&m.caption),
            &m.mimetype,
            m.file_length,
        ));
    }
    if let Some(m) = base.sticker_message.as_option() {
        return Content::Body(media(MediaKind::Sticker, None, &m.mimetype, m.file_length));
    }
    if let Some(r) = base.reaction_message.as_option() {
        let target = r
            .key
            .as_option()
            .and_then(|k| k.id.clone())
            .filter(|id| !id.is_empty());
        return match target {
            Some(target_id) => Content::Reaction {
                target_id,
                emoji: r.text.clone().unwrap_or_default(),
            },
            None => Content::Ignore,
        };
    }
    if base.protocol_message.as_option().is_some()
        || base.sender_key_distribution_message.as_option().is_some()
    {
        return Content::Ignore;
    }
    Content::Body(MessageBody::Unsupported)
}

enum Content {
    Body(MessageBody),
    Reaction { target_id: String, emoji: String },
    Ignore,
}

/// Converts one WhatsApp message.
pub fn convert(origin: Origin<'_>, message: &wa::Message) -> Converted {
    match content(message) {
        Content::Body(body) => Converted::Message(WaMessage {
            id: origin.id.to_owned(),
            chat: origin.chat.to_owned(),
            sender: origin.sender.map(str::to_owned),
            from_me: origin.from_me,
            timestamp_ms: origin.timestamp_ms,
            body,
            reactions: Vec::new(),
        }),
        Content::Reaction { target_id, emoji } => Converted::Reaction {
            chat: origin.chat.to_owned(),
            target_id,
            reaction: Reaction {
                sender: origin.sender.map(str::to_owned),
                from_me: origin.from_me,
                emoji,
            },
        },
        Content::Ignore => Converted::Ignore,
    }
}

/// The author to record for a message in `chat`: the sender's JID without its
/// device suffix, for groups; `None` in one-to-one chats.
pub fn group_sender(chat: &str, sender: &Jid) -> Option<String> {
    is_group(chat).then(|| sender.to_non_ad().to_string())
}

/// Converts a live message. The raw protobuf comes back for media, which is
/// kept so the file can be downloaded later.
pub fn message(ctx: &MessageContext) -> (Converted, Option<Vec<u8>>) {
    let source: &MessageSource = &ctx.info.source;
    let chat = source.chat.to_non_ad().to_string();
    let sender = group_sender(&chat, &source.sender);
    let converted = convert(
        Origin {
            id: &ctx.info.id,
            chat: &chat,
            sender: sender.as_deref(),
            from_me: source.is_from_me,
            timestamp_ms: ctx.info.timestamp.timestamp_millis(),
        },
        &ctx.message,
    );
    let raw = matches!(
        &converted,
        Converted::Message(WaMessage {
            body: MessageBody::Media { .. },
            ..
        })
    )
    .then(|| ctx.message.encode_to_vec());
    (converted, raw)
}

#[cfg(test)]
mod tests {
    use super::*;
    use whatsapp_rust::waproto::buffa::MessageField;

    const CHAT: &str = "905321234567@s.whatsapp.net";

    fn origin<'a>(from_me: bool, sender: Option<&'a str>, chat: &'a str) -> Origin<'a> {
        Origin {
            id: "ABC",
            chat,
            sender,
            from_me,
            timestamp_ms: 1_700_000_000_000,
        }
    }

    fn text(t: &str) -> wa::Message {
        wa::Message {
            conversation: Some(t.to_owned()),
            ..Default::default()
        }
    }

    fn body(converted: Converted) -> MessageBody {
        match converted {
            Converted::Message(m) => m.body,
            other => panic!("not a message: {other:?}"),
        }
    }

    #[test]
    fn plain_text_incoming_direct() {
        let Converted::Message(m) = convert(origin(false, None, CHAT), &text("selam")) else {
            panic!("expected a message");
        };
        assert_eq!(m.id, "ABC");
        assert_eq!(m.chat, CHAT);
        assert!(!m.from_me);
        assert_eq!(m.sender, None);
        assert_eq!(m.timestamp_ms, 1_700_000_000_000);
        assert_eq!(m.body, MessageBody::Text("selam".into()));
    }

    #[test]
    fn outgoing_is_from_me() {
        let Converted::Message(m) = convert(origin(true, None, CHAT), &text("ok")) else {
            panic!("expected a message");
        };
        assert!(m.from_me);
    }

    #[test]
    fn extended_text() {
        let msg = wa::Message {
            extended_text_message: MessageField::some(wa::message::ExtendedTextMessage {
                text: Some("link https://example.org".into()),
                ..Default::default()
            }),
            ..Default::default()
        };
        assert_eq!(
            body(convert(origin(false, None, CHAT), &msg)),
            MessageBody::Text("link https://example.org".into())
        );
    }

    #[test]
    fn image_with_and_without_caption() {
        let image = |caption: Option<&str>| wa::Message {
            image_message: MessageField::some(wa::message::ImageMessage {
                mimetype: Some("image/jpeg".into()),
                caption: caption.map(str::to_owned),
                file_length: Some(2048),
                ..Default::default()
            }),
            ..Default::default()
        };
        assert_eq!(
            body(convert(origin(false, None, CHAT), &image(Some("hey")))),
            MessageBody::Media {
                kind: MediaKind::Image,
                caption: Some("hey".into()),
                mime: "image/jpeg".into(),
                size: 2048,
            }
        );
        // An empty caption is no caption.
        assert!(matches!(
            body(convert(origin(false, None, CHAT), &image(Some("")))),
            MessageBody::Media { caption: None, .. }
        ));
    }

    #[test]
    fn group_sender_is_kept_without_device_suffix() {
        let group = "123-456@g.us";
        let sender: Jid = "4915123456789:7@s.whatsapp.net".parse().unwrap();
        assert_eq!(
            group_sender(group, &sender).as_deref(),
            Some("4915123456789@s.whatsapp.net")
        );
        // One-to-one chats carry no separate sender.
        assert_eq!(group_sender(CHAT, &sender), None);

        let author = group_sender(group, &sender);
        let Converted::Message(m) = convert(origin(false, author.as_deref(), group), &text("x"))
        else {
            panic!("expected a message");
        };
        assert_eq!(m.sender.as_deref(), Some("4915123456789@s.whatsapp.net"));
        assert_eq!(m.chat, group);
    }

    #[test]
    fn reaction_targets_the_reacted_message() {
        let msg = wa::Message {
            reaction_message: MessageField::some(wa::message::ReactionMessage {
                key: MessageField::some(wa::MessageKey {
                    id: Some("TARGET".into()),
                    from_me: Some(true),
                    ..Default::default()
                }),
                text: Some("\u{1F44D}".into()),
                ..Default::default()
            }),
            ..Default::default()
        };
        assert_eq!(
            convert(origin(false, Some("g@s.whatsapp.net"), CHAT), &msg),
            Converted::Reaction {
                chat: CHAT.into(),
                target_id: "TARGET".into(),
                reaction: Reaction {
                    sender: Some("g@s.whatsapp.net".into()),
                    from_me: false,
                    emoji: "\u{1F44D}".into(),
                },
            }
        );
    }

    #[test]
    fn removed_reaction_has_an_empty_emoji_and_keyless_one_is_ignored() {
        let with_key = |text: Option<&str>, key: bool| wa::Message {
            reaction_message: MessageField::some(wa::message::ReactionMessage {
                key: if key {
                    MessageField::some(wa::MessageKey {
                        id: Some("T".into()),
                        ..Default::default()
                    })
                } else {
                    MessageField::none()
                },
                text: text.map(str::to_owned),
                ..Default::default()
            }),
            ..Default::default()
        };
        let Converted::Reaction { reaction, .. } =
            convert(origin(true, None, CHAT), &with_key(Some(""), true))
        else {
            panic!("expected a reaction");
        };
        assert_eq!(reaction.emoji, "");
        assert!(reaction.from_me);
        assert_eq!(
            convert(origin(true, None, CHAT), &with_key(None, false)),
            Converted::Ignore
        );
    }

    #[test]
    fn unknown_content_is_unsupported_and_plumbing_is_ignored() {
        let location = wa::Message {
            location_message: MessageField::some(wa::message::LocationMessage::default()),
            ..Default::default()
        };
        assert_eq!(
            body(convert(origin(false, None, CHAT), &location)),
            MessageBody::Unsupported
        );
        let protocol = wa::Message {
            protocol_message: MessageField::some(wa::message::ProtocolMessage::default()),
            ..Default::default()
        };
        assert_eq!(
            convert(origin(false, None, CHAT), &protocol),
            Converted::Ignore
        );
    }

    #[test]
    fn wrapped_messages_are_unwrapped() {
        let wrapped = wa::Message {
            ephemeral_message: MessageField::some(wa::message::FutureProofMessage {
                message: MessageField::some(text("gone soon")),
            }),
            ..Default::default()
        };
        assert_eq!(
            body(convert(origin(false, None, CHAT), &wrapped)),
            MessageBody::Text("gone soon".into())
        );
    }

    #[test]
    fn jids_are_validated() {
        assert!(parse_jid(CHAT).is_ok());
        assert!(parse_jid(" 123-456@g.us ").is_ok());
        assert!(matches!(parse_jid(""), Err(DriverError::BadRequest(_))));
        assert!(matches!(parse_jid("  "), Err(DriverError::BadRequest(_))));
        assert!(matches!(
            parse_jid("a@b@c@d"),
            Err(DriverError::BadRequest(_))
        ));
    }

    #[test]
    fn unanswered_jids_are_not_on_whatsapp() {
        let queried = vec!["1@s.whatsapp.net".to_owned(), "2@s.whatsapp.net".to_owned()];
        let users = lookup_answers(
            &queried,
            [(
                "2@s.whatsapp.net".to_owned(),
                true,
                true,
                Some("Shop".into()),
            )],
        );
        assert_eq!(users.len(), 2);
        assert!(!users[0].on_whatsapp);
        assert!(users[1].on_whatsapp && users[1].business);
        assert_eq!(users[1].verified_name.as_deref(), Some("Shop"));
    }
}
