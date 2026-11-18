//! WhatsApp protobufs to gateway types. Everything here is pure: the live
//! path ([`message`]) and the history path ([`crate::wa::history`]) both go
//! through [`convert`], so a message looks the same however it arrived.

use rosty_wa_proto::meta::body_preview;
use rosty_wa_proto::{
    DeliveryStatus, LinkPreview, Location, MediaKind, MediaMeta, MessageBody, MessageMeta, Quoted,
    Reaction, SharedContact, Unavailable, WaMessage, WaUser,
};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::types::events::{UnavailableType, UndecryptableMessage};
use whatsapp_rust::wacore::types::message::{MessageInfo, MessageSource};
use whatsapp_rust::wacore_binary::JidExt as _;
use whatsapp_rust::waproto::buffa::Message as _;
use whatsapp_rust::waproto::whatsapp::message::protocol_message::Type as ProtocolType;

use super::lid::{self, LidMap, Pair};
use crate::driver::DriverError;
use crate::store::{MAX_THUMBNAIL_BYTES, MessageExtras};

/// Longest waveform passed on (WhatsApp sends 64 samples).
const MAX_WAVEFORM_BYTES: usize = 256;

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
    /// The other form of `chat` (one-to-one chats), when it is known.
    pub chat_alt: Option<&'a str>,
    /// The other form of `sender`, when it is known.
    pub sender_alt: Option<&'a str>,
}

impl<'a> Origin<'a> {
    /// An origin without a push name or alt ids.
    pub fn new(
        id: &'a str,
        chat: &'a str,
        sender: Option<&'a str>,
        from_me: bool,
        timestamp_ms: i64,
    ) -> Self {
        Self {
            id,
            chat,
            sender,
            from_me,
            timestamp_ms,
            sender_name: None,
            chat_alt: None,
            sender_alt: None,
        }
    }
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
    /// ([`rosty_wa_proto::DELETED_BY_ME`] or the JID of who deleted it).
    Revoke {
        chat: String,
        target_id: String,
        by: String,
        at_ms: i64,
    },
    /// The chat's disappearing timer was set to `seconds` (0: off) at
    /// `at_ms` by `by` ([`rosty_wa_proto::DELETED_BY_ME`] or the JID): the
    /// `EPHEMERAL_SETTING` protocol message `id`.
    Disappearing {
        chat: String,
        id: String,
        seconds: u32,
        at_ms: i64,
        by: String,
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
            (Some(ProtocolType::EPHEMERAL_SETTING), _) => Content::Disappearing {
                seconds: pm.ephemeral_expiration.unwrap_or(0),
                at_s: pm.ephemeral_setting_timestamp,
            },
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
    Disappearing {
        seconds: u32,
        at_s: Option<i64>,
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

/// The waveform bytes of a voice note, as sent (base64).
fn waveform_b64(bytes: &Option<Vec<u8>>) -> Option<String> {
    bytes
        .as_deref()
        .filter(|b| !b.is_empty() && b.len() <= MAX_WAVEFORM_BYTES)
        .map(rosty_wa_proto::encode_b64)
}

/// Media details and the embedded preview image of `base`. `view_once` is
/// for the inline flag only; [`extras`] adds the wrapper.
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
                waveform_b64: waveform_b64(&m.waveform),
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
        meta.forwarding_score = context
            .forwarding_score
            .filter(|score| meta.forwarded && *score > 0);
        meta.expires_s = context.expiration.filter(|s| *s > 0);
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
    if let Some((mut media, preview)) = media_details(base) {
        // A view-once wrapper counts even when the media inside lacks the
        // inline flag.
        media.view_once = media.view_once || message.is_view_once();
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
    if let Some((preview, picture)) = link_preview(base) {
        meta.link_preview = Some(preview);
        thumbnail = thumbnail.or(picture);
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
        wire_chat: None,
        wire_sender: None,
    }
}

/// The preview card of the link a text carries, and its picture. `None` when
/// the text has no card (no title, no description).
fn link_preview(base: &wa::Message) -> Option<(LinkPreview, Option<Vec<u8>>)> {
    let m = base.extended_text_message.as_option()?;
    let title = non_empty(&m.title);
    let description = non_empty(&m.description);
    if title.is_none() && description.is_none() {
        return None;
    }
    let url = non_empty(&m.matched_text)
        .or_else(|| {
            m.text
                .as_deref()
                .and_then(rosty_core::wa_actions::first_url)
                .map(|found| found.url)
        })
        .unwrap_or_default();
    let picture = thumb(&m.jpeg_thumbnail);
    Some((
        LinkPreview {
            url,
            title,
            description,
            thumbnail: picture.is_some(),
        },
        picture,
    ))
}

/// The disappearing timer an incoming message states for its chat, with
/// when it was set (`contextInfo.expiration` and
/// `ephemeralSettingTimestamp`). `None` unless both are there: a message
/// alone does not prove when the timer was set.
pub fn timer_hint(message: &wa::Message) -> Option<(u32, i64)> {
    let context = context_of(message.get_base_message())?;
    let seconds = context.expiration.filter(|s| *s > 0)?;
    let at_s = context.ephemeral_setting_timestamp.filter(|t| *t > 0)?;
    Some((seconds, at_s.saturating_mul(1000)))
}

/// Who deleted a message for everyone: me, the group member (sender or
/// admin) or the other person of a one-to-one chat.
fn revoked_by<'a>(origin: &Origin<'a>) -> &'a str {
    if origin.from_me {
        rosty_wa_proto::DELETED_BY_ME
    } else {
        origin.sender.unwrap_or(origin.chat)
    }
}

/// Converts one WhatsApp message.
pub fn convert(origin: Origin<'_>, message: &wa::Message) -> Converted {
    match content(message) {
        Content::Body(body) => {
            let mut extras = extras(message);
            // A view-once wrapper with nothing inside: what the phone leaves
            // of a view-once message it does not share (history sync).
            if body == MessageBody::Unsupported && message.is_view_once() {
                extras.meta.unavailable = Some(Unavailable::ViewOnce);
            }
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
                    chat_alt: origin.chat_alt.map(str::to_owned),
                    sender_alt: origin.sender_alt.map(str::to_owned),
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
        Content::Disappearing { seconds, at_s } => Converted::Disappearing {
            chat: origin.chat.to_owned(),
            id: origin.id.to_owned(),
            seconds,
            at_ms: at_s
                .filter(|t| *t > 0)
                .map_or(origin.timestamp_ms, |t| t.saturating_mul(1000)),
            by: revoked_by(&origin).to_owned(),
        },
        Content::Reaction { target_id, emoji } => Converted::Reaction {
            chat: origin.chat.to_owned(),
            target_id,
            reaction: Reaction {
                sender: origin.sender.map(str::to_owned),
                from_me: origin.from_me,
                emoji,
                sender_alt: origin.sender_alt.map(str::to_owned),
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

/// Who a live message is from and for: the ids the apps get, their other
/// form where one is known, and the ids it arrived under.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Addressed {
    /// The chat by phone number when known, else as it arrived.
    pub chat: String,
    /// The author, for groups; resolved like `chat`.
    pub sender: Option<String>,
    /// The other form of `chat` (one-to-one chats).
    pub chat_alt: Option<String>,
    /// The other form of `sender`.
    pub sender_alt: Option<String>,
    /// The chat id on the wire, bare.
    pub wire_chat: String,
    /// The author id on the wire, bare (groups).
    pub wire_sender: Option<String>,
}

/// The phone number WhatsApp sent next to the chat of a one-to-one message,
/// if any. A message of theirs names it as the sender's other id. One of
/// mine, echoed from another of my devices, names it in `peer_recipient_pn`
/// (`peer_pn`): there the sender's other id is my own, never the chat's.
fn chat_hint<'a>(source: &'a MessageSource, peer_pn: Option<&'a Jid>) -> Option<&'a Jid> {
    if source.is_from_me {
        peer_pn.or(source.recipient_alt.as_ref())
    } else {
        source.sender_alt.as_ref()
    }
}

/// Resolves the chat and author of a live message: by phone number when
/// `map` knows the LID or WhatsApp told the number too. `peer_pn` is the
/// envelope's `peer_recipient_pn` ([`MessageInfo::peer_recipient_pn`]).
pub fn addressed(source: &MessageSource, peer_pn: Option<&Jid>, map: &LidMap) -> Addressed {
    let wire_chat = source.chat.to_non_ad();
    if source.is_group || wire_chat.is_group() {
        let author = lid::resolve(&source.sender, source.sender_alt.as_ref(), map);
        return Addressed {
            chat: wire_chat.to_string(),
            sender: Some(author.to_string()),
            chat_alt: None,
            sender_alt: lid::alt_of(&author, &source.sender, source.sender_alt.as_ref(), map),
            wire_chat: wire_chat.to_string(),
            wire_sender: Some(source.sender.to_non_ad().to_string()),
        };
    }
    let hint = chat_hint(source, peer_pn);
    let chat = lid::resolve(&source.chat, hint, map);
    Addressed {
        chat_alt: lid::alt_of(&chat, &source.chat, hint, map),
        chat: chat.to_string(),
        sender: None,
        sender_alt: None,
        wire_chat: wire_chat.to_string(),
        wire_sender: None,
    }
}

/// The chat and author JIDs of a live message ([`addressed`]).
pub fn chat_and_sender(
    source: &MessageSource,
    peer_pn: Option<&Jid>,
    map: &LidMap,
) -> (String, Option<String>) {
    let to = addressed(source, peer_pn, map);
    (to.chat, to.sender)
}

/// The LID and phone-number pairs a message envelope names: the author's
/// two ids, and for a one-to-one message of mine the other person's. My own
/// two ids, which my messages carry, are not a discovery.
pub fn envelope_pairs(info: &MessageInfo) -> Vec<Pair> {
    let source = &info.source;
    let mut pairs = Vec::new();
    if source.is_from_me {
        if !source.is_group
            && let Some(pair) = chat_hint(source, info.peer_recipient_pn.as_ref())
                .and_then(|pn| Pair::of(&source.chat, pn))
        {
            pairs.push(pair);
        }
    } else if let Some(pair) = source
        .sender_alt
        .as_ref()
        .and_then(|alt| Pair::of(&source.sender, alt))
    {
        pairs.push(pair);
    }
    pairs
}

/// The business name the envelope of someone else's message carried.
pub fn verified_name(info: &MessageInfo) -> Option<&str> {
    if info.source.is_from_me {
        return None;
    }
    info.verified_name
        .as_deref()
        .and_then(|v| v.name.as_deref())
        .map(str::trim)
        .filter(|name| !name.is_empty())
}

/// The people a message names besides its author: who it mentions and whose
/// message it quotes. For [`Resolver::prefetch`](super::lid::Resolver::prefetch).
pub fn context_jids(message: &wa::Message) -> Vec<Jid> {
    let Some(context) = context_of(message.get_base_message()) else {
        return Vec::new();
    };
    context
        .mentioned_jid
        .iter()
        .map(String::as_str)
        .chain(context.participant.as_deref())
        .filter_map(|id| id.parse::<Jid>().ok())
        .collect()
}

/// Names the people in `meta` (the quoted author, the mentions) the way
/// chats and authors are named: no device, and by phone number when `map`
/// knows their LID. Each person is mentioned once.
pub fn resolve_meta(meta: &mut MessageMeta, map: &LidMap) {
    if let Some(sender) = meta.quoted.as_mut().and_then(|q| q.sender.as_mut()) {
        *sender = lid::resolve_str(sender, map);
    }
    let mut mentions: Vec<String> = Vec::with_capacity(meta.mentions.len());
    for mention in &meta.mentions {
        let resolved = lid::resolve_str(mention, map);
        if !mentions.contains(&resolved) {
            mentions.push(resolved);
        }
    }
    meta.mentions = mentions;
}

/// Converts a live message. The raw protobuf is kept for media, so the file
/// can be downloaded later.
pub fn message(ctx: &MessageContext, map: &LidMap) -> Converted {
    let source: &MessageSource = &ctx.info.source;
    let to = addressed(source, ctx.info.peer_recipient_pn.as_ref(), map);
    let mut converted = convert(
        Origin {
            id: &ctx.info.id,
            chat: &to.chat,
            sender: to.sender.as_deref(),
            from_me: source.is_from_me,
            timestamp_ms: ctx.info.timestamp.timestamp_millis(),
            sender_name: Some(ctx.info.push_name.as_str()),
            chat_alt: to.chat_alt.as_deref(),
            sender_alt: to.sender_alt.as_deref(),
        },
        &ctx.message,
    );
    if let Converted::Message(message, extras) = &mut converted {
        if matches!(message.body, MessageBody::Media { .. }) {
            extras.raw_proto = Some(ctx.message.encode_to_vec());
        }
        resolve_meta(&mut extras.meta, map);
        extras.wire_chat = Some(to.wire_chat);
        extras.wire_sender = to.wire_sender;
    }
    converted
}

/// The placeholder reason of an `<unavailable>` fanout WhatsApp never
/// resends to a linked device. `None` for a plain fanout (the library asks
/// the phone for it and the message arrives as usual) and for a message
/// that failed to decrypt (the library retries it).
pub fn unavailable_reason(event: &UndecryptableMessage) -> Option<Unavailable> {
    if !event.is_unavailable {
        return None;
    }
    match event.unavailable_type {
        UnavailableType::ViewOnce => Some(Unavailable::ViewOnce),
        UnavailableType::Hosted => Some(Unavailable::Hosted),
        UnavailableType::Bot => Some(Unavailable::Bot),
        UnavailableType::Unknown => None,
    }
}

/// A message stored as a placeholder: a view-once (or bot, or hosted)
/// message WhatsApp keeps from linked devices. The body is `unsupported`
/// and [`MessageMeta::unavailable`] says why; there is nothing to download.
pub fn placeholder(info: &MessageInfo, reason: Unavailable, map: &LidMap) -> Converted {
    let source = &info.source;
    let to = addressed(source, info.peer_recipient_pn.as_ref(), map);
    let mut meta = MessageMeta {
        unavailable: Some(reason),
        ..MessageMeta::default()
    };
    if source.is_from_me {
        meta.status = Some(DeliveryStatus::Sent);
    } else {
        meta.sender_name = Some(info.push_name.trim())
            .filter(|n| !n.is_empty())
            .map(str::to_owned);
    }
    Converted::Message(
        WaMessage {
            id: info.id.to_string(),
            chat: to.chat,
            sender: to.sender,
            from_me: source.is_from_me,
            timestamp_ms: info.timestamp.timestamp_millis(),
            body: MessageBody::Unsupported,
            reactions: Vec::new(),
            chat_alt: to.chat_alt,
            sender_alt: to.sender_alt,
        },
        Box::new(MessageExtras {
            meta,
            raw_proto: None,
            secret: None,
            thumbnail: None,
            wire_chat: Some(to.wire_chat),
            wire_sender: to.wire_sender,
        }),
    )
}

/// The WhatsApp message to forward for a stored one: media from its stored
/// protobuf (the copy relays the same CDN blob), a text rebuilt from its body
/// with the forwarded mark and score it arrived with, so the copy's score
/// goes up from there. Anything else cannot be forwarded.
pub fn forwardable(
    body: &MessageBody,
    raw: Option<&[u8]>,
    meta: &MessageMeta,
) -> Result<wa::Message, DriverError> {
    match body {
        MessageBody::Media { .. } => {
            let raw = raw.ok_or_else(|| {
                DriverError::BadRequest("the media of this message is not stored".into())
            })?;
            super::media::decode(raw)
        }
        MessageBody::Text(text) => {
            let context =
                (meta.forwarded || meta.forwarding_score.is_some()).then(|| wa::ContextInfo {
                    is_forwarded: Some(meta.forwarded),
                    forwarding_score: meta.forwarding_score,
                    ..Default::default()
                });
            Ok(match context {
                Some(context) => wa::Message {
                    extended_text_message: MessageField::some(wa::message::ExtendedTextMessage {
                        text: Some(text.clone()),
                        context_info: MessageField::some(context),
                        ..Default::default()
                    }),
                    ..Default::default()
                },
                None => wa::Message {
                    conversation: Some(text.clone()),
                    ..Default::default()
                },
            })
        }
        MessageBody::Unsupported => Err(DriverError::BadRequest(
            "this message cannot be forwarded".into(),
        )),
    }
}

/// A text's mentions as a group that names its members by LID wants them:
/// each mentioned phone-number JID with a known LID (`pairs`: `(pn, lid)`,
/// bare) becomes that LID, in `mentioned_jid` and in its `@<digits>` token.
/// Mentions without a known LID stay as they are.
pub fn mentions_by_lid(
    text: &str,
    mentions: &[String],
    pairs: &[(String, String)],
) -> (String, Vec<String>) {
    use rosty_core::wa_actions::mention_token;
    let mut text = text.to_owned();
    let mut out = Vec::with_capacity(mentions.len());
    for mention in mentions {
        let lid = pairs.iter().find(|(pn, _)| pn == mention).map(|(_, l)| l);
        let (Some(lid), Some(from), Some(to)) = (
            lid,
            mention_token(mention),
            lid.and_then(|l| mention_token(l)),
        ) else {
            out.push(mention.clone());
            continue;
        };
        text = replace_token(&text, &from, &to);
        out.push(lid.clone());
    }
    (text, out)
}

/// Replaces each `@<from>` token of `text` (not followed by another digit)
/// with `@<to>`.
fn replace_token(text: &str, from: &str, to: &str) -> String {
    let needle = format!("@{from}");
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(at) = rest.find(&needle) {
        let after = &rest[at + needle.len()..];
        out.push_str(&rest[..at]);
        if after.starts_with(|c: char| c.is_ascii_digit()) {
            out.push_str(&needle);
        } else {
            out.push('@');
            out.push_str(to);
        }
        rest = after;
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use whatsapp_rust::waproto::buffa::MessageField;

    const CHAT: &str = "905321234567@s.whatsapp.net";

    fn origin<'a>(from_me: bool, sender: Option<&'a str>, chat: &'a str) -> Origin<'a> {
        Origin {
            sender_name: Some(" Ada "),
            ..Origin::new("ABC", chat, sender, from_me, 1_700_000_000_000)
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
        assert_eq!(media.waveform_b64, None);

        // The waveform WhatsApp sends with a voice note is passed on.
        let samples: Vec<u8> = (0..64).collect();
        let drawn = wa::Message {
            audio_message: MessageField::some(wa::message::AudioMessage {
                ptt: Some(true),
                waveform: Some(samples.clone()),
                ..Default::default()
            }),
            ..Default::default()
        };
        let media = extras_of(convert(origin(false, None, CHAT), &drawn))
            .meta
            .media
            .unwrap();
        assert_eq!(media.waveform(), Some(samples.clone()));
        assert_eq!(
            media.waveform_b64,
            Some(rosty_wa_proto::encode_b64(&samples))
        );
        // An empty or absurdly long one is dropped.
        for odd in [vec![], vec![1; MAX_WAVEFORM_BYTES + 1]] {
            let message = wa::Message {
                audio_message: MessageField::some(wa::message::AudioMessage {
                    waveform: Some(odd),
                    ..Default::default()
                }),
                ..Default::default()
            };
            let media = extras_of(convert(origin(false, None, CHAT), &message))
                .meta
                .media
                .unwrap();
            assert_eq!(media.waveform_b64, None);
        }

        // View once: the inline flag, or a wrapper around media without it.
        let photo = |view_once: Option<bool>| wa::Message {
            image_message: MessageField::some(wa::message::ImageMessage {
                view_once,
                ..Default::default()
            }),
            ..Default::default()
        };
        let view_once = |message: &wa::Message| {
            extras_of(convert(origin(false, None, CHAT), message))
                .meta
                .media
                .unwrap()
                .view_once
        };
        assert!(!view_once(&photo(None)));
        assert!(view_once(&photo(Some(true))));
        let wrapped = wa::Message {
            view_once_message_v2: MessageField::some(wa::message::FutureProofMessage {
                message: MessageField::some(photo(None)),
            }),
            ..Default::default()
        };
        assert!(view_once(&wrapped));
        assert!(view_once(&crate::wa::media::wrap_view_once(photo(None))));

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

    #[test]
    fn link_previews_forwarding_and_timers_are_read() {
        let jpeg = vec![0xFF, 0xD8, 0xFF, 0xE0, 1, 2];
        let message = wa::Message {
            extended_text_message: MessageField::some(wa::message::ExtendedTextMessage {
                text: Some("look https://example.com/a".into()),
                matched_text: Some("https://example.com/a".into()),
                title: Some("Example".into()),
                description: Some(String::new()),
                jpeg_thumbnail: Some(jpeg.clone()),
                context_info: MessageField::some(wa::ContextInfo {
                    is_forwarded: Some(true),
                    forwarding_score: Some(127),
                    expiration: Some(604_800),
                    ephemeral_setting_timestamp: Some(1_700_000_000),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            ..Default::default()
        };
        let extras = extras_of(convert(origin(false, None, CHAT), &message));
        let preview = extras.meta.link_preview.expect("preview");
        assert_eq!(preview.url, "https://example.com/a");
        assert_eq!(preview.title.as_deref(), Some("Example"));
        assert_eq!(preview.description, None);
        assert!(preview.thumbnail);
        assert_eq!(extras.thumbnail, Some(jpeg));
        assert!(extras.meta.forwarded);
        assert_eq!(extras.meta.forwarding_score, Some(127));
        assert_eq!(extras.meta.expires_s, Some(604_800));
        assert_eq!(timer_hint(&message), Some((604_800, 1_700_000_000_000)));
        // A plain text has none of it.
        let plain = wa::Message {
            conversation: Some("hi".into()),
            ..Default::default()
        };
        let extras = extras_of(convert(origin(false, None, CHAT), &plain));
        assert!(extras.meta.link_preview.is_none() && extras.meta.expires_s.is_none());
        assert_eq!(timer_hint(&plain), None);
    }

    #[test]
    fn mentions_follow_a_lid_group() {
        let pn = "100000000011@s.whatsapp.net".to_owned();
        let other = "100000000012@s.whatsapp.net".to_owned();
        let lid = "123456789012345@lid".to_owned();
        let (text, jids) = mentions_by_lid(
            "hi @100000000011 and @100000000012, not @1000000000111",
            &[pn.clone(), other.clone()],
            &[(pn, lid.clone())],
        );
        assert_eq!(
            text,
            "hi @123456789012345 and @100000000012, not @1000000000111"
        );
        assert_eq!(jids, vec![lid, other]);
    }

    #[test]
    fn forwardable_texts_keep_their_score_and_media_needs_its_proto() {
        let meta = MessageMeta {
            forwarded: true,
            forwarding_score: Some(3),
            ..Default::default()
        };
        let text = forwardable(&MessageBody::Text("hi".into()), None, &meta).unwrap();
        let context = text
            .extended_text_message
            .as_option()
            .and_then(|m| m.context_info.as_option())
            .expect("context");
        assert_eq!(context.forwarding_score, Some(3));
        let plain = forwardable(
            &MessageBody::Text("hi".into()),
            None,
            &MessageMeta::default(),
        )
        .unwrap();
        assert_eq!(plain.conversation.as_deref(), Some("hi"));
        let photo = MessageBody::Media {
            kind: MediaKind::Image,
            caption: None,
            mime: "image/jpeg".into(),
            size: 1,
        };
        assert!(forwardable(&photo, None, &meta).is_err());
        let raw = wa::Message {
            image_message: MessageField::some(wa::message::ImageMessage::default()),
            ..Default::default()
        }
        .encode_to_vec();
        assert!(forwardable(&photo, Some(&raw), &meta).is_ok());
        assert!(forwardable(&MessageBody::Unsupported, None, &meta).is_err());
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
                by: rosty_wa_proto::DELETED_BY_ME.into(),
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
        // The disappearing timer: a line in the chat (E6-WA R4).
        assert!(matches!(
            convert(
                origin(true, None, CHAT),
                &protocol(ProtocolType::EPHEMERAL_SETTING, None)
            ),
            Converted::Disappearing { seconds: 0, ref by, .. } if by == rosty_wa_proto::DELETED_BY_ME
        ));
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
        assert_eq!(chat_and_sender(&source, None, &none).0, "1234@lid");
        // No alt, but the map knows it: the PN chat.
        assert_eq!(chat_and_sender(&source, None, &known).0, pn.to_string());
        // The alt supplies the number.
        source.sender_alt = Some(pn.clone());
        assert_eq!(chat_and_sender(&source, None, &none).0, pn.to_string());

        // My own message from another device: only the recipient alt counts.
        let mine = MessageSource {
            chat: "1234@lid".parse().unwrap(),
            sender: "999:3@s.whatsapp.net".parse().unwrap(),
            is_from_me: true,
            ..Default::default()
        };
        assert_eq!(chat_and_sender(&mine, None, &none).0, "1234@lid");
        assert_eq!(chat_and_sender(&mine, None, &known).0, pn.to_string());

        // Groups: the chat stays, the author resolves.
        let group = MessageSource {
            chat: "1-2@g.us".parse().unwrap(),
            sender: "1234:5@lid".parse().unwrap(),
            is_group: true,
            ..Default::default()
        };
        let (chat, sender) = chat_and_sender(&group, None, &known);
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
                    sender_alt: None,
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

    const PEER_PN: &str = "905550000001@s.whatsapp.net";
    const PEER_LID: &str = "1234@lid";
    const MY_PN: &str = "905550000009@s.whatsapp.net";
    const MY_LID: &str = "777@lid";

    fn jid(s: &str) -> Jid {
        s.parse().unwrap()
    }

    /// My own message to a LID-addressed one-to-one chat, echoed from another
    /// of my devices: the chat is the recipient's LID, the sender's other id
    /// is my own, and the other person's number is `peer_recipient_pn`.
    fn my_echo() -> MessageSource {
        MessageSource {
            chat: jid(PEER_LID),
            sender: jid("777:3@lid"),
            is_from_me: true,
            sender_alt: Some(jid(MY_PN)),
            recipient: Some(jid(PEER_LID)),
            ..Default::default()
        }
    }

    #[test]
    fn my_echoed_message_lands_in_the_peers_number_chat() {
        let none = LidMap::default();
        let peer = jid("905550000001:2@s.whatsapp.net");
        // Without the peer's number and without a mapping it stays a LID
        // chat. It must never become a chat with my own number, which is
        // what the sender's other id names.
        let alone = addressed(&my_echo(), None, &none);
        assert_eq!(alone.chat, PEER_LID);
        assert_eq!(alone.chat_alt, None);
        // `peer_recipient_pn` names the chat.
        let to = addressed(&my_echo(), Some(&peer), &none);
        assert_eq!(
            to,
            Addressed {
                chat: PEER_PN.into(),
                sender: None,
                chat_alt: Some(PEER_LID.into()),
                sender_alt: None,
                wire_chat: PEER_LID.into(),
                wire_sender: None,
            }
        );
        assert_eq!(
            chat_and_sender(&my_echo(), Some(&peer), &none),
            (PEER_PN.to_owned(), None)
        );
        // The mapping store still wins over what the stanza says.
        let other = LidMap::from_entries([("1234", "905550000002")]);
        assert_eq!(
            addressed(&my_echo(), Some(&peer), &other).chat,
            "905550000002@s.whatsapp.net"
        );
    }

    #[test]
    fn live_messages_carry_wire_and_alt_ids() {
        let none = LidMap::default();
        let known = LidMap::from_entries([("1234", "905550000001")]);
        // Theirs, addressed by LID with the number next to it.
        let theirs = MessageSource {
            chat: jid(PEER_LID),
            sender: jid("1234:5@lid"),
            sender_alt: Some(jid(PEER_PN)),
            ..Default::default()
        };
        let to = addressed(&theirs, None, &none);
        assert_eq!(
            (to.chat.as_str(), to.chat_alt.as_deref()),
            (PEER_PN, Some(PEER_LID))
        );
        assert_eq!(to.wire_chat, PEER_LID);
        // Theirs, addressed by number with the LID next to it.
        let by_number = MessageSource {
            chat: jid(PEER_PN),
            sender: jid("905550000001:5@s.whatsapp.net"),
            sender_alt: Some(jid(PEER_LID)),
            ..Default::default()
        };
        let to = addressed(&by_number, None, &none);
        assert_eq!(
            (to.chat.as_str(), to.chat_alt.as_deref()),
            (PEER_PN, Some(PEER_LID))
        );
        assert_eq!(to.wire_chat, PEER_PN);
        // By number with nothing next to it: the mappings know the LID.
        let bare = MessageSource {
            sender_alt: None,
            ..by_number.clone()
        };
        assert_eq!(
            addressed(&bare, None, &known).chat_alt.as_deref(),
            Some(PEER_LID)
        );
        assert_eq!(addressed(&bare, None, &none).chat_alt, None);
        // A LID nobody knows the number of: no other form.
        let hidden = MessageSource {
            chat: jid("9999@lid"),
            sender: jid("9999@lid"),
            ..Default::default()
        };
        let to = addressed(&hidden, None, &known);
        assert_eq!((to.chat.as_str(), to.chat_alt), ("9999@lid", None));

        // A group that addresses its members by LID.
        let group = MessageSource {
            chat: jid("1-2@g.us"),
            sender: jid("1234:5@lid"),
            is_group: true,
            sender_alt: Some(jid(PEER_PN)),
            ..Default::default()
        };
        assert_eq!(
            addressed(&group, None, &none),
            Addressed {
                chat: "1-2@g.us".into(),
                sender: Some(PEER_PN.into()),
                chat_alt: None,
                sender_alt: Some(PEER_LID.into()),
                wire_chat: "1-2@g.us".into(),
                wire_sender: Some(PEER_LID.into()),
            }
        );
        // My own group message from another device, under my LID.
        let mine = MessageSource {
            chat: jid("1-2@g.us"),
            sender: jid("777:3@lid"),
            is_group: true,
            is_from_me: true,
            sender_alt: Some(jid(MY_PN)),
            ..Default::default()
        };
        let to = addressed(&mine, None, &none);
        assert_eq!(to.sender.as_deref(), Some(MY_PN));
        assert_eq!(to.sender_alt.as_deref(), Some(MY_LID));
        assert_eq!(to.wire_sender.as_deref(), Some(MY_LID));
    }

    #[test]
    fn converted_messages_and_reactions_carry_the_alt_ids() {
        let origin = Origin {
            chat_alt: Some(PEER_LID),
            ..Origin::new("ABC", PEER_PN, None, false, 1)
        };
        let Converted::Message(m, _) = convert(origin, &text("x")) else {
            panic!("expected a message");
        };
        assert_eq!(m.chat_alt.as_deref(), Some(PEER_LID));
        assert_eq!(m.sender_alt, None);

        let reaction = wa::Message {
            reaction_message: MessageField::some(wa::message::ReactionMessage {
                key: MessageField::some(wa::MessageKey {
                    id: Some("T".into()),
                    ..Default::default()
                }),
                text: Some("x".into()),
                ..Default::default()
            }),
            ..Default::default()
        };
        let origin = Origin {
            sender_alt: Some(PEER_LID),
            ..Origin::new("R", "1-2@g.us", Some(PEER_PN), false, 1)
        };
        let Converted::Reaction { reaction, .. } = convert(origin, &reaction) else {
            panic!("expected a reaction");
        };
        assert_eq!(reaction.sender.as_deref(), Some(PEER_PN));
        assert_eq!(reaction.sender_alt.as_deref(), Some(PEER_LID));
    }

    #[test]
    fn envelopes_state_pairs() {
        let pair = Pair::of(&jid(PEER_LID), &jid(PEER_PN));
        assert!(pair.is_some());
        let info = |source: MessageSource, peer: Option<&str>| MessageInfo {
            source,
            peer_recipient_pn: peer.map(jid),
            ..Default::default()
        };
        // Theirs: the sender's two ids, whichever is the alt.
        let theirs = MessageSource {
            chat: jid(PEER_LID),
            sender: jid("1234:5@lid"),
            sender_alt: Some(jid("905550000001:1@s.whatsapp.net")),
            ..Default::default()
        };
        assert_eq!(
            envelope_pairs(&info(theirs.clone(), None)),
            pair.clone().into_iter().collect::<Vec<_>>()
        );
        let reversed = MessageSource {
            chat: jid(PEER_PN),
            sender: jid(PEER_PN),
            sender_alt: Some(jid(PEER_LID)),
            ..Default::default()
        };
        assert_eq!(envelope_pairs(&info(reversed, None)).first(), pair.as_ref());
        // In a group: the author's.
        let group = MessageSource {
            chat: jid("1-2@g.us"),
            is_group: true,
            ..theirs
        };
        assert_eq!(envelope_pairs(&info(group, None)).first(), pair.as_ref());
        // Mine, echoed: the other person's, from `peer_recipient_pn`. My own
        // two ids are not a discovery.
        assert_eq!(
            envelope_pairs(&info(my_echo(), Some(PEER_PN))).first(),
            pair.as_ref()
        );
        assert!(envelope_pairs(&info(my_echo(), None)).is_empty());
        let my_group = MessageSource {
            chat: jid("1-2@g.us"),
            is_group: true,
            ..my_echo()
        };
        assert!(envelope_pairs(&info(my_group, Some(PEER_PN))).is_empty());
        // Nothing next to the sender: nothing learned.
        let bare = MessageSource {
            chat: jid(PEER_LID),
            sender: jid(PEER_LID),
            ..Default::default()
        };
        assert!(envelope_pairs(&info(bare, None)).is_empty());
    }

    fn mentioning(mentions: &[&str], quoted: Option<&str>) -> wa::Message {
        wa::Message {
            extended_text_message: MessageField::some(wa::message::ExtendedTextMessage {
                text: Some("hi".into()),
                context_info: MessageField::some(wa::ContextInfo {
                    stanza_id: Some("Q1".into()),
                    participant: quoted.map(str::to_owned),
                    mentioned_jid: mentions.iter().map(|m| (*m).to_owned()).collect(),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    #[test]
    fn mentions_and_quoted_authors_are_named_like_everybody_else() {
        let message = mentioning(
            &[
                "1234:7@lid",
                "905550000002:3@s.whatsapp.net",
                PEER_PN,
                "9999@lid",
            ],
            Some("1234:2@lid"),
        );
        let named: Vec<String> = context_jids(&message)
            .iter()
            .map(ToString::to_string)
            .collect();
        assert_eq!(
            named,
            [
                "1234:7@lid",
                "905550000002:3@s.whatsapp.net",
                PEER_PN,
                "9999@lid",
                "1234:2@lid"
            ]
        );
        assert!(context_jids(&text("plain")).is_empty());

        let known = LidMap::from_entries([("1234", "905550000001")]);
        let mut meta = extras(&message).meta;
        resolve_meta(&mut meta, &known);
        // No devices, the known LID by its number, each person once; a LID
        // nobody knows the number of stays a LID.
        assert_eq!(
            meta.mentions,
            [PEER_PN, "905550000002@s.whatsapp.net", "9999@lid"]
        );
        assert_eq!(
            meta.quoted.as_ref().unwrap().sender.as_deref(),
            Some(PEER_PN)
        );

        // Without a mapping only the devices go.
        let mut meta = extras(&message).meta;
        resolve_meta(&mut meta, &LidMap::default());
        assert_eq!(
            meta.mentions,
            [PEER_LID, "905550000002@s.whatsapp.net", PEER_PN, "9999@lid"]
        );
        assert_eq!(
            meta.quoted.as_ref().unwrap().sender.as_deref(),
            Some(PEER_LID)
        );

        // Text that is no id is kept as it is.
        let mut odd = MessageMeta {
            mentions: vec!["not a jid@@".into()],
            ..Default::default()
        };
        resolve_meta(&mut odd, &known);
        assert_eq!(odd.mentions, ["not a jid@@"]);
    }

    #[test]
    fn the_envelope_names_a_business() {
        use whatsapp_rust::wacore::stanza::business::VerifiedName;
        let named = |name: Option<&str>, from_me: bool| MessageInfo {
            source: MessageSource {
                is_from_me: from_me,
                ..Default::default()
            },
            verified_name: Some(Box::new(VerifiedName {
                name: name.map(str::to_owned),
                serial: None,
                issuer: None,
                certificate: None,
            })),
            ..Default::default()
        };
        assert_eq!(verified_name(&named(Some(" Shop "), false)), Some("Shop"));
        assert_eq!(verified_name(&named(Some("  "), false)), None);
        assert_eq!(verified_name(&named(None, false)), None);
        // My own messages say nothing about the chat.
        assert_eq!(verified_name(&named(Some("Shop"), true)), None);
        assert_eq!(verified_name(&MessageInfo::default()), None);
    }

    fn unavailable_event(
        info: MessageInfo,
        is_unavailable: bool,
        kind: UnavailableType,
    ) -> UndecryptableMessage {
        use whatsapp_rust::wacore::types::events::DecryptFailMode;
        UndecryptableMessage::builder()
            .info(std::sync::Arc::new(info))
            .is_unavailable(is_unavailable)
            .unavailable_type(kind)
            .decrypt_fail_mode(DecryptFailMode::Show)
            .build()
    }

    #[test]
    fn unrecoverable_fanouts_become_placeholders() {
        let info = MessageInfo::default();
        let reason = |unavailable, kind| {
            unavailable_reason(&unavailable_event(info.clone(), unavailable, kind))
        };
        assert_eq!(
            reason(true, UnavailableType::ViewOnce),
            Some(Unavailable::ViewOnce)
        );
        assert_eq!(
            reason(true, UnavailableType::Hosted),
            Some(Unavailable::Hosted)
        );
        assert_eq!(reason(true, UnavailableType::Bot), Some(Unavailable::Bot));
        // A plain fanout comes back from the phone; a decrypt failure is retried.
        assert_eq!(reason(true, UnavailableType::Unknown), None);
        assert_eq!(reason(false, UnavailableType::ViewOnce), None);
    }

    #[test]
    fn a_view_once_placeholder_is_a_stored_message() {
        let lid = "200000000001@lid";
        let pn = "100000000001@s.whatsapp.net";
        let known = LidMap::from_entries([("200000000001", "100000000001")]);
        let info = MessageInfo {
            source: MessageSource {
                chat: jid(lid),
                sender: jid("200000000001:3@lid"),
                ..Default::default()
            },
            id: "VO1".into(),
            push_name: " Ada ".into(),
            timestamp: whatsapp_rust::wacore::chrono::DateTime::from_timestamp(1_700_000_000, 0)
                .unwrap(),
            ..Default::default()
        };
        let Converted::Message(message, extras) = placeholder(&info, Unavailable::ViewOnce, &known)
        else {
            panic!("expected a message");
        };
        assert_eq!(message.id, "VO1");
        assert_eq!(message.chat, pn);
        assert_eq!(message.chat_alt.as_deref(), Some(lid));
        assert_eq!(message.sender, None);
        assert!(!message.from_me);
        assert_eq!(message.timestamp_ms, 1_700_000_000_000);
        assert_eq!(message.body, MessageBody::Unsupported);
        assert_eq!(extras.meta.unavailable, Some(Unavailable::ViewOnce));
        assert_eq!(extras.meta.sender_name.as_deref(), Some("Ada"));
        assert_eq!(extras.meta.status, None);
        assert_eq!(extras.wire_chat.as_deref(), Some(lid));
        assert!(extras.raw_proto.is_none() && extras.thumbnail.is_none());

        // Sent from my phone into a group: mine, with the author kept.
        let mine = MessageInfo {
            source: MessageSource {
                chat: jid("100000000009-1@g.us"),
                sender: jid("100000000002:1@s.whatsapp.net"),
                is_group: true,
                is_from_me: true,
                ..Default::default()
            },
            id: "VO2".into(),
            ..Default::default()
        };
        let Converted::Message(message, extras) =
            placeholder(&mine, Unavailable::Bot, &LidMap::default())
        else {
            panic!("expected a message");
        };
        assert!(message.from_me);
        assert_eq!(message.chat, "100000000009-1@g.us");
        assert_eq!(
            message.sender.as_deref(),
            Some("100000000002@s.whatsapp.net")
        );
        assert_eq!(extras.meta.status, Some(DeliveryStatus::Sent));
        assert_eq!(extras.meta.sender_name, None);
        assert_eq!(extras.meta.unavailable, Some(Unavailable::Bot));
    }

    #[test]
    fn an_empty_view_once_wrapper_reads_as_a_placeholder() {
        let empty = wa::Message {
            view_once_message: MessageField::some(wa::message::FutureProofMessage::default()),
            ..Default::default()
        };
        let Converted::Message(message, extras) =
            convert(Origin::new("H1", PEER_PN, None, false, 5), &empty)
        else {
            panic!("expected a message");
        };
        assert_eq!(message.body, MessageBody::Unsupported);
        assert_eq!(extras.meta.unavailable, Some(Unavailable::ViewOnce));
        // A view-once photo that came whole is media, not a placeholder.
        let whole = crate::wa::media::wrap_view_once(wa::Message {
            image_message: MessageField::some(wa::message::ImageMessage {
                mimetype: Some("image/jpeg".into()),
                ..Default::default()
            }),
            ..Default::default()
        });
        let Converted::Message(message, extras) =
            convert(Origin::new("H2", PEER_PN, None, false, 5), &whole)
        else {
            panic!("expected a message");
        };
        assert!(matches!(message.body, MessageBody::Media { .. }));
        assert_eq!(extras.meta.unavailable, None);
        assert!(extras.meta.media.is_some_and(|m| m.view_once));
        // Neither is anything else unsupported.
        let Converted::Message(_, extras) = convert(
            Origin::new("H3", PEER_PN, None, false, 5),
            &wa::Message::default(),
        ) else {
            panic!("expected a message");
        };
        assert_eq!(extras.meta.unavailable, None);
    }
}
