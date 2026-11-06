//! Status updates (stories): WhatsApp messages to `status@broadcast` turned
//! into [`StoredStatus`] rows instead of chat messages. Pure, like
//! [`super::convert`]; the live and history paths both come through here.

use rovsti_wa_proto::{StatusItem, StatusKind, StatusMedia};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore_binary::JidExt as _;
use whatsapp_rust::waproto::buffa::Message as _;
use whatsapp_rust::waproto::whatsapp::message::protocol_message::Type as ProtocolType;

use super::lid::{self, LidMap};
use crate::driver::EventSink;
use crate::store::{MAX_THUMBNAIL_BYTES, StoredStatus};

/// What a message to `status@broadcast` means.
#[derive(Clone, Debug, PartialEq)]
pub enum StatusUpdate {
    /// A new update to store.
    New(Box<StoredStatus>),
    /// The author deleted update `id`.
    Revoke { id: String },
    /// Plumbing (key distribution, reactions to a status, edits).
    Ignore,
}

/// Who posted an update and when.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct StatusOrigin<'a> {
    pub id: &'a str,
    /// The author by phone number when known (shown, matched to contacts).
    pub author: &'a str,
    /// The author as WhatsApp addressed the update (named in receipts).
    pub participant: &'a str,
    pub from_me: bool,
    pub timestamp_ms: i64,
    pub push_name: Option<&'a str>,
}

fn thumb(bytes: &Option<Vec<u8>>) -> Option<Vec<u8>> {
    bytes
        .as_ref()
        .filter(|b| !b.is_empty() && b.len() <= MAX_THUMBNAIL_BYTES)
        .cloned()
}

fn non_empty(text: Option<&str>) -> Option<String> {
    text.map(str::trim)
        .filter(|t| !t.is_empty())
        .map(str::to_owned)
}

/// Converts one message posted to `status@broadcast`.
pub fn convert(origin: StatusOrigin<'_>, message: &wa::Message) -> StatusUpdate {
    let base = message.get_base_message();
    if let Some(pm) = base.protocol_message.as_option() {
        let target = pm
            .key
            .as_option()
            .and_then(|k| k.id.clone())
            .filter(|id| !id.is_empty());
        return match (pm.r#type, target) {
            (Some(ProtocolType::REVOKE), Some(id)) => StatusUpdate::Revoke { id },
            _ => StatusUpdate::Ignore,
        };
    }
    if base.sender_key_distribution_message.as_option().is_some()
        || base.reaction_message.as_option().is_some()
    {
        return StatusUpdate::Ignore;
    }
    let mut item = StatusItem {
        id: origin.id.to_owned(),
        author: origin.author.to_owned(),
        from_me: origin.from_me,
        timestamp_ms: origin.timestamp_ms,
        author_name: non_empty(origin.push_name).filter(|_| !origin.from_me),
        ..Default::default()
    };
    let mut thumbnail = None;
    let mut raw_proto = None;
    if let Some(m) = base.image_message.as_option() {
        item.kind = StatusKind::Image;
        item.text = non_empty(m.caption.as_deref());
        thumbnail = thumb(&m.jpeg_thumbnail);
        item.media = Some(StatusMedia {
            mime: m.mimetype.clone().unwrap_or_default(),
            size: m.file_length.unwrap_or(0),
            seconds: None,
            width: m.width,
            height: m.height,
            thumbnail: thumbnail.is_some(),
        });
        raw_proto = Some(message.encode_to_vec());
    } else if let Some(m) = base.video_message.as_option() {
        item.kind = StatusKind::Video;
        item.text = non_empty(m.caption.as_deref());
        thumbnail = thumb(&m.jpeg_thumbnail);
        item.media = Some(StatusMedia {
            mime: m.mimetype.clone().unwrap_or_default(),
            size: m.file_length.unwrap_or(0),
            seconds: m.seconds,
            width: m.width,
            height: m.height,
            thumbnail: thumbnail.is_some(),
        });
        raw_proto = Some(message.encode_to_vec());
    } else if let Some(text) = base.text_content() {
        item.kind = StatusKind::Text;
        item.text = non_empty(Some(text));
        if let Some(m) = base.extended_text_message.as_option() {
            item.background_argb = m.background_argb;
            item.text_argb = m.text_argb;
            item.font = m.font.and_then(|f| u32::try_from(f as i32).ok());
        }
    } else {
        item.kind = StatusKind::Other;
    }
    StatusUpdate::New(Box::new(StoredStatus {
        item,
        participant: origin.participant.to_owned(),
        raw_proto,
        thumbnail,
    }))
}

/// Stores and announces what a status message meant.
pub async fn apply(sink: &EventSink, update: StatusUpdate) {
    match update {
        StatusUpdate::New(status) => sink.emit_status(*status).await,
        StatusUpdate::Revoke { id } => sink.revoke_status(&id).await,
        StatusUpdate::Ignore => {}
    }
}

/// Whether a live message was posted to `status@broadcast`.
pub fn is_status(ctx: &MessageContext) -> bool {
    ctx.info.source.chat.is_status_broadcast()
}

/// Converts a live status update.
pub fn live(ctx: &MessageContext, map: &LidMap) -> StatusUpdate {
    let source = &ctx.info.source;
    let author = lid::resolve(&source.sender, source.sender_alt.as_ref(), map).to_string();
    let participant = source.sender.to_non_ad().to_string();
    convert(
        StatusOrigin {
            id: &ctx.info.id,
            author: &author,
            participant: &participant,
            from_me: source.is_from_me,
            timestamp_ms: ctx.info.timestamp.timestamp_millis(),
            push_name: Some(ctx.info.push_name.as_str()),
        },
        &ctx.message,
    )
}

/// The updates in a history sync's `status@broadcast` conversation. Old
/// ones are dropped by the store.
pub fn from_history(conv: &wa::Conversation, own_pn: Option<&str>) -> Vec<StoredStatus> {
    conv.messages
        .iter()
        .filter_map(|m| m.message.as_option())
        .filter_map(|info| {
            let key = info.key.as_option()?;
            let id = key.id.as_deref().filter(|id| !id.is_empty())?;
            let content = info.message.as_option()?;
            let from_me = key.from_me.unwrap_or(false);
            let participant = info
                .participant
                .as_deref()
                .or(key.participant.as_deref())
                .filter(|p| !p.is_empty())
                .and_then(|p| p.parse::<Jid>().ok())
                .map(|jid| jid.to_non_ad().to_string())
                .or_else(|| from_me.then(|| own_pn.unwrap_or_default().to_owned()))?;
            let author = if from_me {
                own_pn.unwrap_or(&participant).to_owned()
            } else {
                participant.clone()
            };
            let timestamp_ms = i64::try_from(info.message_timestamp.unwrap_or(0))
                .unwrap_or(0)
                .saturating_mul(1000);
            match convert(
                StatusOrigin {
                    id,
                    author: &author,
                    participant: &participant,
                    from_me,
                    timestamp_ms,
                    push_name: info.push_name.as_deref(),
                },
                content,
            ) {
                StatusUpdate::New(status) => Some(*status),
                StatusUpdate::Revoke { .. } | StatusUpdate::Ignore => None,
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use whatsapp_rust::waproto::buffa::MessageField;
    use whatsapp_rust::waproto::whatsapp::message::extended_text_message::FontType;

    fn origin(from_me: bool) -> StatusOrigin<'static> {
        StatusOrigin {
            id: "S1",
            author: "905321234567@s.whatsapp.net",
            participant: "1234@lid",
            from_me,
            timestamp_ms: 1_700_000_000_000,
            push_name: Some(" Ada "),
        }
    }

    fn stored(update: StatusUpdate) -> StoredStatus {
        match update {
            StatusUpdate::New(s) => *s,
            other => panic!("not a status: {other:?}"),
        }
    }

    #[test]
    fn text_statuses_keep_their_colours_and_font() {
        let message = wa::Message {
            extended_text_message: MessageField::some(wa::message::ExtendedTextMessage {
                text: Some("Good morning".into()),
                background_argb: Some(0xFF1E_6E4F),
                text_argb: Some(0xFFFF_FFFF),
                font: Some(FontType::FB_SCRIPT),
                ..Default::default()
            }),
            ..Default::default()
        };
        let s = stored(convert(origin(false), &message));
        assert_eq!(s.item.kind, StatusKind::Text);
        assert_eq!(s.item.text.as_deref(), Some("Good morning"));
        assert_eq!(s.item.background_argb, Some(0xFF1E_6E4F));
        assert_eq!(s.item.text_argb, Some(0xFFFF_FFFF));
        assert_eq!(s.item.font, Some(2));
        assert_eq!(s.item.author_name.as_deref(), Some("Ada"));
        assert_eq!(s.participant, "1234@lid");
        assert!(s.raw_proto.is_none());
    }

    #[test]
    fn photos_and_videos_keep_media_details() {
        let photo = wa::Message {
            image_message: MessageField::some(wa::message::ImageMessage {
                caption: Some("beach".into()),
                mimetype: Some("image/jpeg".into()),
                file_length: Some(1234),
                width: Some(1080),
                height: Some(1920),
                jpeg_thumbnail: Some(vec![0xFF, 0xD8, 0xFF]),
                ..Default::default()
            }),
            ..Default::default()
        };
        let s = stored(convert(origin(false), &photo));
        assert_eq!(s.item.kind, StatusKind::Image);
        assert_eq!(s.item.text.as_deref(), Some("beach"));
        let media = s.item.media.clone().unwrap();
        assert_eq!(
            (media.width, media.height, media.size),
            (Some(1080), Some(1920), 1234)
        );
        assert!(media.thumbnail);
        assert_eq!(s.thumbnail, Some(vec![0xFF, 0xD8, 0xFF]));
        assert!(s.raw_proto.is_some());

        let video = wa::Message {
            video_message: MessageField::some(wa::message::VideoMessage {
                seconds: Some(14),
                mimetype: Some("video/mp4".into()),
                ..Default::default()
            }),
            ..Default::default()
        };
        let v = stored(convert(origin(true), &video));
        assert_eq!(v.item.kind, StatusKind::Video);
        assert_eq!(v.item.media.unwrap().seconds, Some(14));
        assert!(v.item.from_me);
        // My own name is not an author name.
        assert_eq!(v.item.author_name, None);
    }

    #[test]
    fn revokes_and_plumbing() {
        let revoke = wa::Message {
            protocol_message: MessageField::some(wa::message::ProtocolMessage {
                r#type: Some(ProtocolType::REVOKE),
                key: MessageField::some(wa::MessageKey {
                    id: Some("S0".into()),
                    ..Default::default()
                }),
                ..Default::default()
            }),
            ..Default::default()
        };
        assert_eq!(
            convert(origin(false), &revoke),
            StatusUpdate::Revoke { id: "S0".into() }
        );
        let reaction = wa::Message {
            reaction_message: MessageField::some(wa::message::ReactionMessage::default()),
            ..Default::default()
        };
        assert_eq!(convert(origin(false), &reaction), StatusUpdate::Ignore);
        let voice = wa::Message {
            audio_message: MessageField::some(wa::message::AudioMessage::default()),
            ..Default::default()
        };
        assert_eq!(
            stored(convert(origin(false), &voice)).item.kind,
            StatusKind::Other
        );
    }

    #[test]
    fn history_statuses_name_their_author() {
        let info = |id: &str, from_me: bool, participant: Option<&str>| wa::WebMessageInfo {
            key: MessageField::some(wa::MessageKey {
                remote_jid: Some("status@broadcast".into()),
                from_me: Some(from_me),
                id: Some(id.into()),
                participant: participant.map(Into::into),
            }),
            message: MessageField::some(wa::Message {
                conversation: Some("story".into()),
                ..Default::default()
            }),
            message_timestamp: Some(1_700_000_000),
            ..Default::default()
        };
        let conv = wa::Conversation {
            id: "status@broadcast".into(),
            messages: vec![
                wa::HistorySyncMsg {
                    message: MessageField::some(info(
                        "A",
                        false,
                        Some("905321234567:4@s.whatsapp.net"),
                    )),
                    ..Default::default()
                },
                wa::HistorySyncMsg {
                    message: MessageField::some(info("B", true, None)),
                    ..Default::default()
                },
                wa::HistorySyncMsg {
                    message: MessageField::some(info("C", false, None)),
                    ..Default::default()
                },
            ],
            ..Default::default()
        };
        let got = from_history(&conv, Some("me@s.whatsapp.net"));
        assert_eq!(got.len(), 2);
        assert_eq!(got[0].item.author, "905321234567@s.whatsapp.net");
        assert_eq!(got[0].item.timestamp_ms, 1_700_000_000_000);
        assert_eq!(got[1].item.author, "me@s.whatsapp.net");
        assert!(got[1].item.from_me);
    }
}
