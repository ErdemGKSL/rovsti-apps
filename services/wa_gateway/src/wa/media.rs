//! Media messages: uploaded files to WhatsApp message protobufs, and stored
//! protobufs back to something `client.download` accepts. Pure.

use rovsti_wa_proto::MediaKind;
use whatsapp_rust::prelude::*;
use whatsapp_rust::upload::UploadResponse;
use whatsapp_rust::wacore::download::{Downloadable, MediaType};
use whatsapp_rust::waproto::buffa::Message as _;

use crate::driver::DriverError;

/// The CDN encryption class of `kind`.
pub fn media_type(kind: MediaKind) -> MediaType {
    match kind {
        MediaKind::Image => MediaType::Image,
        MediaKind::Video => MediaType::Video,
        MediaKind::Audio => MediaType::Audio,
        MediaKind::Document => MediaType::Document,
        MediaKind::Sticker => MediaType::Sticker,
    }
}

/// What `client.upload` returned, as a plain struct (the library's own type
/// cannot be built outside it, which would make the mapping untestable).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Uploaded {
    pub url: String,
    pub direct_path: String,
    pub media_key: [u8; 32],
    pub file_enc_sha256: [u8; 32],
    pub file_sha256: [u8; 32],
    pub file_length: u64,
    pub media_key_timestamp: i64,
    pub streaming_sidecar: Option<Vec<u8>>,
}

impl From<UploadResponse> for Uploaded {
    fn from(r: UploadResponse) -> Self {
        Self {
            url: r.url,
            direct_path: r.direct_path,
            media_key: r.media_key,
            file_enc_sha256: r.file_enc_sha256,
            file_sha256: r.file_sha256,
            file_length: r.file_length,
            media_key_timestamp: r.media_key_timestamp,
            streaming_sidecar: r.streaming_sidecar,
        }
    }
}

/// Voice notes are Opus in Ogg; WhatsApp shows them as push-to-talk.
fn is_voice_note(mime: &str) -> bool {
    let mime = mime.to_ascii_lowercase();
    mime.contains("opus") || mime.starts_with("audio/ogg")
}

/// The message that sends `up` to a chat.
pub fn build_message(
    kind: MediaKind,
    up: &Uploaded,
    mime: &str,
    caption: Option<&str>,
) -> wa::Message {
    let url = Some(up.url.clone());
    let mimetype = Some(mime.to_owned());
    let file_sha256 = Some(up.file_sha256.to_vec());
    let file_enc_sha256 = Some(up.file_enc_sha256.to_vec());
    let media_key = Some(up.media_key.to_vec());
    let direct_path = Some(up.direct_path.clone());
    let file_length = Some(up.file_length);
    let media_key_timestamp = Some(up.media_key_timestamp);
    let caption = caption.filter(|c| !c.is_empty()).map(str::to_owned);

    let mut message = wa::Message::default();
    match kind {
        MediaKind::Image => {
            message.image_message = MessageField::some(wa::message::ImageMessage {
                url,
                mimetype,
                caption,
                file_sha256,
                file_length,
                media_key,
                file_enc_sha256,
                direct_path,
                media_key_timestamp,
                ..Default::default()
            });
        }
        MediaKind::Video => {
            message.video_message = MessageField::some(wa::message::VideoMessage {
                url,
                mimetype,
                caption,
                file_sha256,
                file_length,
                media_key,
                file_enc_sha256,
                direct_path,
                media_key_timestamp,
                streaming_sidecar: up.streaming_sidecar.clone(),
                ..Default::default()
            });
        }
        MediaKind::Audio => {
            message.audio_message = MessageField::some(wa::message::AudioMessage {
                url,
                mimetype: mimetype.clone(),
                file_sha256,
                file_length,
                media_key,
                file_enc_sha256,
                direct_path,
                media_key_timestamp,
                streaming_sidecar: up.streaming_sidecar.clone(),
                ptt: Some(is_voice_note(mime)),
                ..Default::default()
            });
        }
        MediaKind::Document => {
            message.document_message = MessageField::some(wa::message::DocumentMessage {
                url,
                mimetype,
                caption,
                file_sha256,
                file_length,
                media_key,
                file_enc_sha256,
                direct_path,
                media_key_timestamp,
                ..Default::default()
            });
        }
        MediaKind::Sticker => {
            message.sticker_message = MessageField::some(wa::message::StickerMessage {
                url,
                mimetype,
                file_sha256,
                file_length,
                media_key,
                file_enc_sha256,
                direct_path,
                media_key_timestamp,
                ..Default::default()
            });
        }
    }
    message
}

/// The media sub-message of a stored message, as `client.download` takes it.
#[derive(Clone, Copy, Debug)]
pub enum MediaRef<'a> {
    Image(&'a wa::message::ImageMessage),
    Video(&'a wa::message::VideoMessage),
    Audio(&'a wa::message::AudioMessage),
    Document(&'a wa::message::DocumentMessage),
    Sticker(&'a wa::message::StickerMessage),
}

impl MediaRef<'_> {
    pub fn kind(&self) -> MediaKind {
        match self {
            Self::Image(_) => MediaKind::Image,
            Self::Video(_) => MediaKind::Video,
            Self::Audio(_) => MediaKind::Audio,
            Self::Document(_) => MediaKind::Document,
            Self::Sticker(_) => MediaKind::Sticker,
        }
    }

    pub fn downloadable(&self) -> &dyn Downloadable {
        match self {
            Self::Image(m) => *m,
            Self::Video(m) => *m,
            Self::Audio(m) => *m,
            Self::Document(m) => *m,
            Self::Sticker(m) => *m,
        }
    }
}

/// Finds the media in `message` (looking through ephemeral / view-once /
/// device-sent wrappers).
pub fn media_of(message: &wa::Message) -> Option<MediaRef<'_>> {
    let base = message.get_base_message();
    if let Some(m) = base.image_message.as_option() {
        return Some(MediaRef::Image(m));
    }
    if let Some(m) = base.video_message.as_option() {
        return Some(MediaRef::Video(m));
    }
    if let Some(m) = base.audio_message.as_option() {
        return Some(MediaRef::Audio(m));
    }
    if let Some(m) = base.document_message.as_option() {
        return Some(MediaRef::Document(m));
    }
    base.sticker_message.as_option().map(MediaRef::Sticker)
}

/// Decodes a stored protobuf (`raw_proto` column).
pub fn decode(raw: &[u8]) -> Result<wa::Message, DriverError> {
    wa::Message::decode_from_slice(raw)
        .map_err(|e| DriverError::Failed(format!("stored message is unreadable: {e}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn uploaded() -> Uploaded {
        Uploaded {
            url: "https://mmg.whatsapp.net/o1/v/t62/abc".into(),
            direct_path: "/o1/v/t62/abc".into(),
            media_key: [1; 32],
            file_enc_sha256: [2; 32],
            file_sha256: [3; 32],
            file_length: 4096,
            media_key_timestamp: 1_700_000_000,
            streaming_sidecar: Some(vec![9, 9]),
        }
    }

    #[test]
    fn upload_response_maps_to_an_image_message() {
        let msg = build_message(MediaKind::Image, &uploaded(), "image/jpeg", Some("kedi"));
        let m = msg.image_message.as_option().unwrap();
        assert_eq!(
            m.url.as_deref(),
            Some("https://mmg.whatsapp.net/o1/v/t62/abc")
        );
        assert_eq!(m.direct_path.as_deref(), Some("/o1/v/t62/abc"));
        assert_eq!(m.mimetype.as_deref(), Some("image/jpeg"));
        assert_eq!(m.caption.as_deref(), Some("kedi"));
        assert_eq!(m.media_key.as_deref(), Some(&[1u8; 32][..]));
        assert_eq!(m.file_enc_sha256.as_deref(), Some(&[2u8; 32][..]));
        assert_eq!(m.file_sha256.as_deref(), Some(&[3u8; 32][..]));
        assert_eq!(m.file_length, Some(4096));
        assert_eq!(m.media_key_timestamp, Some(1_700_000_000));
        // Nothing else is set on the envelope.
        assert!(msg.video_message.as_option().is_none());
        assert!(msg.conversation.is_none());
    }

    #[test]
    fn empty_captions_are_dropped() {
        let msg = build_message(MediaKind::Image, &uploaded(), "image/png", Some(""));
        assert_eq!(msg.image_message.as_option().unwrap().caption, None);
    }

    #[test]
    fn audio_and_video_carry_the_streaming_sidecar() {
        let video = build_message(MediaKind::Video, &uploaded(), "video/mp4", None);
        assert_eq!(
            video.video_message.as_option().unwrap().streaming_sidecar,
            Some(vec![9, 9])
        );
        let voice = build_message(
            MediaKind::Audio,
            &uploaded(),
            "audio/ogg; codecs=opus",
            None,
        );
        let a = voice.audio_message.as_option().unwrap();
        assert_eq!(a.streaming_sidecar, Some(vec![9, 9]));
        assert_eq!(a.ptt, Some(true));
        let song = build_message(MediaKind::Audio, &uploaded(), "audio/mpeg", None);
        assert_eq!(song.audio_message.as_option().unwrap().ptt, Some(false));
    }

    #[test]
    fn every_kind_round_trips_to_its_downloadable() {
        for kind in [
            MediaKind::Image,
            MediaKind::Video,
            MediaKind::Audio,
            MediaKind::Document,
            MediaKind::Sticker,
        ] {
            let msg = build_message(kind, &uploaded(), "x/y", None);
            let decoded = decode(&msg.encode_to_vec()).unwrap();
            let media = media_of(&decoded).expect("media found");
            assert_eq!(media.kind(), kind);
            let d = media.downloadable();
            assert_eq!(d.direct_path(), Some("/o1/v/t62/abc"));
            assert_eq!(d.media_key(), Some(&[1u8; 32][..]));
            assert_eq!(d.app_info(), media_type(kind));
        }
    }

    #[test]
    fn text_messages_have_no_media_and_garbage_does_not_decode() {
        let text = wa::Message {
            conversation: Some("hi".into()),
            ..Default::default()
        };
        assert!(media_of(&text).is_none());
        assert!(decode(&[0xff, 0xff, 0xff]).is_err());
    }
}
