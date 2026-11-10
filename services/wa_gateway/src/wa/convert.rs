//! WhatsApp protobufs to gateway types. Everything here is pure: the live
//! path ([`message`]) and the history path ([`crate::wa::history`]) both go
//! through [`convert`], so a message looks the same however it arrived.

use rovsti_wa_proto::meta::body_preview;
use rovsti_wa_proto::{
    DeliveryStatus, Location, MediaKind, MediaMeta, MessageBody, MessageMeta, Quoted, Reaction,
    SharedContact, WaMessage, WaUser,
};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::types::message::MessageSource;
use whatsapp_rust::waproto::buffa::Message as _;
use whatsapp_rust::waproto::whatsapp::message::protocol_message::Type as ProtocolType;

use super::lid::{self, LidMap};
use crate::driver::DriverError;
use crate::store::{MAX_THUMBNAIL_BYTES, MessageExtras};

/// Longest quote preview kept.
const QUOTE_PREVIEW_CHARS: usize = 120;

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
    /// The author's push name, as the envelope carried it.
    pub sender_name: Option<&'a str>,
}

/// What a WhatsApp message turned into.
#[derive(Clone, Debug, PartialEq)]
pub enum Converted {
    /// A message to show, with what is stored beside it (the raw protobuf
    /// is left for the caller to add).
    Message(WaMessage, Box<MessageExtras>),
    /// A reaction to an earlier message (`emoji` is empty when removed).
    Reaction {
        chat: String,
        target_id: String,
        reaction: Reaction,
    },
    /// The author edited an earlier message.
    Edit {
        chat: String,
        target_id: String,
        body: MessageBody,
        edited_ms: i64,
    },
    /// An earlier message was deleted for everyone, at `at_ms` by `by`
    /// ([`rovsti_wa_proto::DELETED_BY_ME`] or the JID of who deleted it).
    Revoke {
        chat: String,
        target_id: String,
        by: String,
        at_ms: i64,
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
    if let Some(pm) = base.protocol_message.as_option() {
        let target_id = pm
            .key
            .as_option()
            .and_then(|k| k.id.clone())
            .filter(|id| !id.is_empty());
        return match (pm.r#type, target_id) {
            (Some(ProtocolType::REVOKE), Some(target_id)) => Content::Revoke { target_id },
            (Some(ProtocolType::MESSAGE_EDIT), Some(target_id)) => {
                match pm.edited_message.as_option().map(content) {
                    Some(Content::Body(body)) if body != MessageBody::Unsupported => {
                        Content::Edit {
                            target_id,
                            body,
                            edited_ms: pm.timestamp_ms,
                        }
                    }
                    _ => Content::Ignore,
                }
            }
            _ => Content::Ignore,
        };
    }
    if base.sender_key_distribution_message.as_option().is_some() {
        return Content::Ignore;
    }
    Content::Body(MessageBody::Unsupported)
}

enum Content {
    Body(MessageBody),
    Reaction {
        target_id: String,
        emoji: String,
    },
    Edit {
        target_id: String,
        body: MessageBody,
        edited_ms: Option<i64>,
    },
    Revoke {
        target_id: String,
    },
    Ignore,
}

/// The reply context of the first sub-message that carries one.
fn context_of(base: &wa::Message) -> Option<&wa::ContextInfo> {
    let contexts = [
        base.extended_text_message
            .as_option()
            .and_then(|m| m.context_info.as_option()),
        base.image_message
            .as_option()
            .and_then(|m| m.context_info.as_option()),
        base.video_message
            .as_option()
            .and_then(|m| m.context_info.as_option()),
        base.audio_message
            .as_option()
            .and_then(|m| m.context_info.as_option()),
        base.document_message
            .as_option()
            .and_then(|m| m.context_info.as_option()),
        base.sticker_message
            .as_option()
            .and_then(|m| m.context_info.as_option()),
        base.location_message
            .as_option()
            .and_then(|m| m.context_info.as_option()),
        base.contact_message
            .as_option()
            .and_then(|m| m.context_info.as_option()),
    ];
    contexts.into_iter().flatten().next()
}

fn thumb(bytes: &Option<Vec<u8>>) -> Option<Vec<u8>> {
    bytes
        .as_ref()
        .filter(|b| !b.is_empty() && b.len() <= MAX_THUMBNAIL_BYTES)
        .cloned()
}

/// Media details and the embedded preview image of `base`.
fn media_details(base: &wa::Message) -> Option<(MediaMeta, Option<Vec<u8>>)> {
    if let Some(m) = base.image_message.as_option() {
        let preview = thumb(&m.jpeg_thumbnail);
        return Some((
            MediaMeta {
                width: m.width,
                height: m.height,
                view_once: m.view_once.unwrap_or(false),
                thumbnail: preview.is_some(),
                ..Default::default()
            },
            preview,
        ));
    }
    if let Some(m) = base.video_message.as_option() {
        let preview = thumb(&m.jpeg_thumbnail);
        return Some((
            MediaMeta {
                seconds: m.seconds,
                width: m.width,
                height: m.height,
                animated: m.gif_playback.unwrap_or(false),
                view_once: m.view_once.unwrap_or(false),
                thumbnail: preview.is_some(),
                ..Default::default()
            },
            preview,
        ));
    }
    if let Some(m) = base.audio_message.as_option() {
        return Some((
            MediaMeta {
                seconds: m.seconds,
                voice_note: m.ptt.unwrap_or(false),
                view_once: m.view_once.unwrap_or(false),
                ..Default::default()
            },
            None,
        ));
    }
    if let Some(m) = base.document_message.as_option() {
        let preview = thumb(&m.jpeg_thumbnail);
        let file_name = m
            .file_name
            .as_deref()
            .or(m.title.as_deref())
            .map(str::trim)
            .filter(|n| !n.is_empty())
            .map(str::to_owned);
        return Some((
            MediaMeta {
                file_name,
                pages: m.page_count,
                thumbnail: preview.is_some(),
                ..Default::default()
            },
            preview,
        ));
    }
    if let Some(m) = base.sticker_message.as_option() {
        let preview = thumb(&m.png_thumbnail);
        return Some((
            MediaMeta {
                width: m.width,
                height: m.height,
                animated: m.is_animated.unwrap_or(false),
                thumbnail: preview.is_some(),
                ..Default::default()
            },
            preview,
        ));
    }
    None
}

/// What `message` carries besides its body: reply, mentions, media details,
/// location or contact card, the message secret and a thumbnail.
pub fn extras(message: &wa::Message) -> MessageExtras {
    let base = message.get_base_message();
    let mut meta = MessageMeta::default();
    let mut thumbnail = None;

    if let Some(context) = context_of(base) {
        meta.forwarded = context.is_forwarded.unwrap_or(false);
        meta.mentions = context
            .mentioned_jid
            .iter()
            .filter(|j| !j.is_empty())
            .cloned()
            .collect();
        meta.quoted = context
            .stanza_id
            .as_deref()
            .filter(|id| !id.is_empty())
            .map(|id| Quoted {
                id: id.to_owned(),
                sender: context
                    .participant
                    .as_deref()
                    .filter(|p| !p.is_empty())
                    .and_then(|p| p.parse::<Jid>().ok())
                    .map(|jid| jid.to_non_ad().to_string()),
                preview: context
                    .quoted_message
                    .as_option()
                    .and_then(|q| match content(q) {
                        Content::Body(body) => body_preview(&body, QUOTE_PREVIEW_CHARS),
                        _ => None,
                    }),
            });
    }
    if let Some((media, preview)) = media_details(base) {
        meta.media = Some(media);
        thumbnail = preview;
    }
    if let Some(m) = base.location_message.as_option() {
        meta.location = Some(Location {
            latitude: m.degrees_latitude.unwrap_or(0.0),
            longitude: m.degrees_longitude.unwrap_or(0.0),
            name: m.name.clone().filter(|n| !n.is_empty()),
            address: m.address.clone().filter(|a| !a.is_empty()),
            live: false,
        });
        thumbnail = thumb(&m.jpeg_thumbnail);
    } else if let Some(m) = base.live_location_message.as_option() {
        meta.location = Some(Location {
            latitude: m.degrees_latitude.unwrap_or(0.0),
            longitude: m.degrees_longitude.unwrap_or(0.0),
            name: m.caption.clone().filter(|n| !n.is_empty()),
            address: None,
            live: true,
        });
    }
    let card = base.contact_message.as_option().or_else(|| {
        base.contacts_array_message
            .as_option()
            .and_then(|a| a.contacts.first())
    });
    if let Some(card) = card {
        meta.contact = Some(SharedContact {
            display_name: card.display_name.clone().unwrap_or_default(),
            vcard: card.vcard.clone().unwrap_or_default(),
        });
    }
    let secret = message
        .message_context_info
        .as_option()
        .or_else(|| base.message_context_info.as_option())
        .and_then(|c| c.message_secret.clone())
        .filter(|s| !s.is_empty());
    MessageExtras {
        meta,
        raw_proto: None,
        secret,
        thumbnail,
    }
}

/// Who deleted a message for everyone: me, the group member (sender or
/// admin) or the other person of a one-to-one chat.
fn revoked_by<'a>(origin: &Origin<'a>) -> &'a str {
    if origin.from_me {
        rovsti_wa_proto::DELETED_BY_ME
    } else {
        origin.sender.unwrap_or(origin.chat)
    }
}

/// Converts one WhatsApp message.
pub fn convert(origin: Origin<'_>, message: &wa::Message) -> Converted {
    match content(message) {
        Content::Body(body) => {
            let mut extras = extras(message);
            if origin.from_me {
                extras.meta.status = Some(DeliveryStatus::Sent);
            } else {
                extras.meta.sender_name = origin
                    .sender_name
                    .map(str::trim)
                    .filter(|n| !n.is_empty())
                    .map(str::to_owned);
            }
            Converted::Message(
                WaMessage {
                    id: origin.id.to_owned(),
                    chat: origin.chat.to_owned(),
                    sender: origin.sender.map(str::to_owned),
                    from_me: origin.from_me,
                    timestamp_ms: origin.timestamp_ms,
                    body,
                    reactions: Vec::new(),
                },
                Box::new(extras),
            )
        }
        Content::Edit {
            target_id,
            body,
            edited_ms,
        } => Converted::Edit {
            chat: origin.chat.to_owned(),
            target_id,
            body,
            edited_ms: edited_ms.unwrap_or(origin.timestamp_ms),
        },
        Content::Revoke { target_id } => Converted::Revoke {
            chat: origin.chat.to_owned(),
            target_id,
            by: revoked_by(&origin).to_owned(),
            at_ms: origin.timestamp_ms,
        },
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

/// The chat and author JIDs of a live message, by phone number when `map`
/// knows the LID or WhatsApp told the number too (the `*_alt` fields).
pub fn chat_and_sender(source: &MessageSource, map: &LidMap) -> (String, Option<String>) {
    let chat = if source.is_group {
        source.chat.to_non_ad()
    } else if source.is_from_me {
        lid::resolve(&source.chat, source.recipient_alt.as_ref(), map)
    } else {
        lid::resolve(&source.chat, source.sender_alt.as_ref(), map)
    }
    .to_string();
    let author = lid::resolve(&source.sender, source.sender_alt.as_ref(), map);
    let sender = group_sender(&chat, &author);
    (chat, sender)
}

/// Converts a live message. The raw protobuf is kept for media, so the file
/// can be downloaded later.
pub fn message(ctx: &MessageContext, map: &LidMap) -> Converted {
    let source: &MessageSource = &ctx.info.source;
    let (chat, sender) = chat_and_sender(source, map);
    let mut converted = convert(
        Origin {
            id: &ctx.info.id,
            chat: &chat,
            sender: sender.as_deref(),
            from_me: source.is_from_me,
            timestamp_ms: ctx.info.timestamp.timestamp_millis(),
            sender_name: Some(ctx.info.push_name.as_str()),
        },
        &ctx.message,
    );
    if let Converted::Message(
        WaMessage {
            body: MessageBody::Media { .. },
            ..
        },
        extras,
    ) = &mut converted
    {
        extras.raw_proto = Some(ctx.message.encode_to_vec());
    }
    converted
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
            sender_name: Some(" Ada "),
        }
    }

    fn text(t: &str) -> wa::Message {
        wa::Message {
            conversation: Some(t.to_owned()),
            ..Default::default()
        }
    }

    fn extras_of(converted: Converted) -> MessageExtras {
        match converted {
            Converted::Message(_, extras) => *extras,
            other => panic!("not a message: {other:?}"),
        }
    }

    #[test]
    fn push_names_and_ticks() {
        let theirs = extras_of(convert(origin(false, None, CHAT), &text("x")));
        assert_eq!(theirs.meta.sender_name.as_deref(), Some("Ada"));
        assert_eq!(theirs.meta.status, None);
        let mine = extras_of(convert(origin(true, None, CHAT), &text("x")));
        assert_eq!(mine.meta.sender_name, None);
        assert_eq!(mine.meta.status, Some(DeliveryStatus::Sent));
    }

    #[test]
    fn replies_name_the_quoted_message() {
        let reply = wa::Message {
            extended_text_message: MessageField::some(wa::message::ExtendedTextMessage {
                text: Some("yes".into()),
                context_info: MessageField::some(wa::ContextInfo {
                    stanza_id: Some("Q1".into()),
                    participant: Some("4915:3@s.whatsapp.net".into()),
                    quoted_message: MessageField::some(text("are you coming?\nsecond line")),
                    mentioned_jid: vec!["4915@s.whatsapp.net".into()],
                    is_forwarded: Some(true),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            message_context_info: MessageField::some(wa::MessageContextInfo {
                message_secret: Some(vec![9; 32]),
                ..Default::default()
            }),
            ..Default::default()
        };
        let converted = convert(origin(false, None, CHAT), &reply);
        let extras = extras_of(converted.clone());
        assert_eq!(body(converted), MessageBody::Text("yes".into()));
        assert_eq!(
            extras.meta.quoted,
            Some(Quoted {
                id: "Q1".into(),
                sender: Some("4915@s.whatsapp.net".into()),
                preview: Some("are you coming?".into()),
            })
        );
        assert!(extras.meta.forwarded);
        assert_eq!(extras.meta.mentions, ["4915@s.whatsapp.net"]);
        assert_eq!(extras.secret, Some(vec![9; 32]));
    }

    #[test]
    fn media_details_and_thumbnails() {
        let voice = wa::Message {
            audio_message: MessageField::some(wa::message::AudioMessage {
                mimetype: Some("audio/ogg; codecs=opus".into()),
                seconds: Some(7),
                ptt: Some(true),
                ..Default::default()
            }),
            ..Default::default()
        };
        let media = extras_of(convert(origin(false, None, CHAT), &voice))
            .meta
            .media
            .unwrap();
        assert_eq!((media.seconds, media.voice_note), (Some(7), true));

        let doc = wa::Message {
            document_message: MessageField::some(wa::message::DocumentMessage {
                mimetype: Some("application/pdf".into()),
                file_name: Some("report.pdf".into()),
                page_count: Some(3),
                jpeg_thumbnail: Some(vec![0xFF, 0xD8, 0xFF]),
                ..Default::default()
            }),
            ..Default::default()
        };
        let extras = extras_of(convert(origin(false, None, CHAT), &doc));
        let media = extras.meta.media.unwrap();
        assert_eq!(media.file_name.as_deref(), Some("report.pdf"));
        assert_eq!(media.pages, Some(3));
        assert!(media.thumbnail);
        assert_eq!(extras.thumbnail, Some(vec![0xFF, 0xD8, 0xFF]));

        // Oversized thumbnails are not kept.
        let big = wa::Message {
            image_message: MessageField::some(wa::message::ImageMessage {
                jpeg_thumbnail: Some(vec![1; MAX_THUMBNAIL_BYTES + 1]),
                width: Some(640),
                ..Default::default()
            }),
            ..Default::default()
        };
        let extras = extras_of(convert(origin(false, None, CHAT), &big));
        assert_eq!(extras.thumbnail, None);
        assert!(!extras.meta.media.as_ref().unwrap().thumbnail);
        assert_eq!(extras.meta.media.unwrap().width, Some(640));
    }

    #[test]
    fn locations_and_contact_cards() {
        let place = wa::Message {
            location_message: MessageField::some(wa::message::LocationMessage {
                degrees_latitude: Some(41.0),
                degrees_longitude: Some(29.0),
                name: Some("Galata".into()),
                ..Default::default()
            }),
            ..Default::default()
        };
        let converted = convert(origin(false, None, CHAT), &place);
        let location = extras_of(converted.clone()).meta.location.unwrap();
        assert_eq!((location.latitude, location.longitude), (41.0, 29.0));
        assert_eq!(location.name.as_deref(), Some("Galata"));
        // Older apps still see an unsupported body.
        assert_eq!(body(converted), MessageBody::Unsupported);

        let card = wa::Message {
            contact_message: MessageField::some(wa::message::ContactMessage {
                display_name: Some("Bob".into()),
                vcard: Some("BEGIN:VCARD\nEND:VCARD".into()),
                ..Default::default()
            }),
            ..Default::default()
        };
        let contact = extras_of(convert(origin(false, None, CHAT), &card))
            .meta
            .contact
            .unwrap();
        assert_eq!(contact.display_name, "Bob");
    }

    fn protocol(kind: ProtocolType, edited: Option<wa::Message>) -> wa::Message {
        wa::Message {
            protocol_message: MessageField::some(wa::message::ProtocolMessage {
                key: MessageField::some(wa::MessageKey {
                    id: Some("T".into()),
                    from_me: Some(true),
                    ..Default::default()
                }),
                r#type: Some(kind),
                edited_message: edited.map_or_else(MessageField::none, MessageField::some),
                timestamp_ms: Some(5),
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    #[test]
    fn edits_and_revokes() {
        assert_eq!(
            convert(
                origin(true, None, CHAT),
                &protocol(ProtocolType::MESSAGE_EDIT, Some(text("fixed")))
            ),
            Converted::Edit {
                chat: CHAT.into(),
                target_id: "T".into(),
                body: MessageBody::Text("fixed".into()),
                edited_ms: 5,
            }
        );
        assert_eq!(
            convert(
                origin(false, None, CHAT),
                &protocol(ProtocolType::REVOKE, None)
            ),
            Converted::Revoke {
                chat: CHAT.into(),
                target_id: "T".into(),
                by: CHAT.into(),
                at_ms: 1_700_000_000_000,
            }
        );
        // My own revoke (from another device) and a group admin's.
        assert_eq!(
            convert(
                origin(true, None, CHAT),
                &protocol(ProtocolType::REVOKE, None)
            ),
            Converted::Revoke {
                chat: CHAT.into(),
                target_id: "T".into(),
                by: rovsti_wa_proto::DELETED_BY_ME.into(),
                at_ms: 1_700_000_000_000,
            }
        );
        assert_eq!(
            convert(
                origin(false, Some("1@s.whatsapp.net"), "g@g.us"),
                &protocol(ProtocolType::REVOKE, None)
            ),
            Converted::Revoke {
                chat: "g@g.us".into(),
                target_id: "T".into(),
                by: "1@s.whatsapp.net".into(),
                at_ms: 1_700_000_000_000,
            }
        );
        // An edit without content and other protocol messages are plumbing.
        assert_eq!(
            convert(
                origin(true, None, CHAT),
                &protocol(ProtocolType::MESSAGE_EDIT, None)
            ),
            Converted::Ignore
        );
        assert_eq!(
            convert(
                origin(true, None, CHAT),
                &protocol(ProtocolType::EPHEMERAL_SETTING, None)
            ),
            Converted::Ignore
        );
    }

    #[test]
    fn lids_give_way_to_phone_numbers() {
        let pn: Jid = "905321234567@s.whatsapp.net".parse().unwrap();
        let none = LidMap::default();
        let known = LidMap::from_entries([("1234", "905321234567")]);

        let mut source = MessageSource {
            chat: "1234:5@lid".parse().unwrap(),
            sender: "1234:5@lid".parse().unwrap(),
            ..Default::default()
        };
        // No alt, no mapping: the LID stays.
        assert_eq!(chat_and_sender(&source, &none).0, "1234@lid");
        // No alt, but the map knows it: the PN chat.
        assert_eq!(chat_and_sender(&source, &known).0, pn.to_string());
        // The alt supplies the number.
        source.sender_alt = Some(pn.clone());
        assert_eq!(chat_and_sender(&source, &none).0, pn.to_string());

        // My own message from another device: only the recipient alt counts.
        let mine = MessageSource {
            chat: "1234@lid".parse().unwrap(),
            sender: "999:3@s.whatsapp.net".parse().unwrap(),
            is_from_me: true,
            ..Default::default()
        };
        assert_eq!(chat_and_sender(&mine, &none).0, "1234@lid");
        assert_eq!(chat_and_sender(&mine, &known).0, pn.to_string());

        // Groups: the chat stays, the author resolves.
        let group = MessageSource {
            chat: "1-2@g.us".parse().unwrap(),
            sender: "1234:5@lid".parse().unwrap(),
            is_group: true,
            ..Default::default()
        };
        let (chat, sender) = chat_and_sender(&group, &known);
        assert_eq!(chat, "1-2@g.us");
        assert_eq!(sender.as_deref(), Some("905321234567@s.whatsapp.net"));
    }

    fn body(converted: Converted) -> MessageBody {
        match converted {
            Converted::Message(m, _) => m.body,
            other => panic!("not a message: {other:?}"),
        }
    }

    #[test]
    fn plain_text_incoming_direct() {
        let Converted::Message(m, _) = convert(origin(false, None, CHAT), &text("selam")) else {
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
        let Converted::Message(m, _) = convert(origin(true, None, CHAT), &text("ok")) else {
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
        let Converted::Message(m, _) = convert(origin(false, author.as_deref(), group), &text("x"))
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
