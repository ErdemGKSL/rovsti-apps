//! Pure helpers for outgoing media: kind/MIME classification and the size cap.

use crate::api::MediaKind;

/// Largest attachment the gateway accepts for sending (16 MiB).
pub const MAX_SEND_BYTES: u64 = 16 * 1024 * 1024;

const OCTET_STREAM: &str = "application/octet-stream";

/// An attachment exceeded [`MAX_SEND_BYTES`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
#[error("attachment is {len} bytes, the limit is {MAX_SEND_BYTES}")]
pub struct MediaTooLarge {
    pub len: u64,
}

/// Rejects attachments larger than [`MAX_SEND_BYTES`]; the cap itself is fine.
pub fn check_size(len: u64) -> Result<(), MediaTooLarge> {
    if len > MAX_SEND_BYTES {
        Err(MediaTooLarge { len })
    } else {
        Ok(())
    }
}

/// Largest preview image sent along with a photo, video or document.
pub const MAX_SEND_THUMBNAIL_BYTES: usize = 64 * 1024;
/// Samples in a voice-note waveform.
pub const WAVEFORM_LEN: usize = 64;
/// Largest value of one waveform sample.
pub const WAVEFORM_MAX: u8 = 100;

/// An option of an outgoing media message that the gateway refuses.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum MediaOptionError {
    #[error("thumbnail_b64 is not valid base64")]
    ThumbnailNotBase64,
    #[error("the thumbnail is {len} bytes, the limit is {MAX_SEND_THUMBNAIL_BYTES}")]
    ThumbnailTooLarge { len: usize },
    #[error("the thumbnail is not a JPEG image")]
    ThumbnailNotJpeg,
    #[error("thumbnail_len is {len}, the body has {body} bytes")]
    ThumbnailLength { len: usize, body: usize },
    #[error("waveform_b64 is not valid base64")]
    WaveformNotBase64,
    #[error("the waveform has {len} samples, it must have exactly {WAVEFORM_LEN}")]
    WaveformLength { len: usize },
    #[error("a waveform sample is above {WAVEFORM_MAX}")]
    WaveformRange,
    #[error("only a photo, a video or a voice message can be sent as view once")]
    ViewOnceKind,
}

/// Standard base64, padded (what every `_b64` field carries).
pub fn encode_b64(bytes: &[u8]) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

/// The bytes of a `_b64` field; `None` when it is not base64.
pub fn decode_b64(text: &str) -> Option<Vec<u8>> {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.decode(text).ok()
}

/// A preview image: a JPEG of at most [`MAX_SEND_THUMBNAIL_BYTES`].
pub fn check_thumbnail(bytes: &[u8]) -> Result<(), MediaOptionError> {
    if bytes.len() > MAX_SEND_THUMBNAIL_BYTES {
        Err(MediaOptionError::ThumbnailTooLarge { len: bytes.len() })
    } else if !bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Err(MediaOptionError::ThumbnailNotJpeg)
    } else {
        Ok(())
    }
}

/// A waveform: exactly [`WAVEFORM_LEN`] samples, none above [`WAVEFORM_MAX`].
pub fn check_waveform(bytes: &[u8]) -> Result<(), MediaOptionError> {
    if bytes.len() != WAVEFORM_LEN {
        Err(MediaOptionError::WaveformLength { len: bytes.len() })
    } else if bytes.iter().any(|&b| b > WAVEFORM_MAX) {
        Err(MediaOptionError::WaveformRange)
    } else {
        Ok(())
    }
}

/// WhatsApp has view once for photos, videos and voice messages only. A
/// document or sticker asked as view once is refused, never sent as an
/// ordinary message.
pub fn check_view_once(kind: MediaKind, view_once: bool) -> Result<(), MediaOptionError> {
    let can = matches!(kind, MediaKind::Image | MediaKind::Video | MediaKind::Audio);
    if view_once && !can {
        Err(MediaOptionError::ViewOnceKind)
    } else {
        Ok(())
    }
}

/// Decides the media kind and MIME type of a picked file.
///
/// The picker's MIME wins. When it is missing, blank or
/// `application/octet-stream`, the leading bytes are sniffed, then the file
/// extension is tried. Anything unrecognised is a generic document.
pub fn classify(mime: Option<&str>, name: &str, head: &[u8]) -> (MediaKind, String) {
    let given = mime
        .map(|m| m.trim().to_ascii_lowercase())
        .filter(|m| !m.is_empty() && m != OCTET_STREAM);
    let mime = given
        .or_else(|| sniff(head).map(str::to_owned))
        .or_else(|| from_extension(name).map(str::to_owned))
        .unwrap_or_else(|| OCTET_STREAM.to_owned());
    (kind_of(&mime), mime)
}

fn kind_of(mime: &str) -> MediaKind {
    match mime.split('/').next() {
        Some("image") => MediaKind::Image,
        Some("video") => MediaKind::Video,
        Some("audio") => MediaKind::Audio,
        _ => MediaKind::Document,
    }
}

fn sniff(head: &[u8]) -> Option<&'static str> {
    if head.starts_with(&[0xFF, 0xD8, 0xFF]) {
        Some("image/jpeg")
    } else if head.starts_with(&[0x89, b'P', b'N', b'G']) {
        Some("image/png")
    } else if head.len() >= 12 && &head[..4] == b"RIFF" && &head[8..12] == b"WEBP" {
        Some("image/webp")
    } else if head.starts_with(b"GIF8") {
        Some("image/gif")
    } else if head.starts_with(b"%PDF") {
        Some("application/pdf")
    } else if head.len() >= 8 && &head[4..8] == b"ftyp" {
        Some("video/mp4")
    } else {
        None
    }
}

fn from_extension(name: &str) -> Option<&'static str> {
    let (_, ext) = name.rsplit_once('.')?;
    Some(match ext.to_ascii_lowercase().as_str() {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "mp4" | "m4v" => "video/mp4",
        "mov" => "video/quicktime",
        "3gp" => "video/3gpp",
        "webm" => "video/webm",
        "mp3" => "audio/mpeg",
        "m4a" => "audio/mp4",
        "aac" => "audio/aac",
        "ogg" | "opus" => "audio/ogg",
        "wav" => "audio/wav",
        "pdf" => "application/pdf",
        "txt" => "text/plain",
        "zip" => "application/zip",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use MediaKind::{Audio, Document, Image, Video};

    #[test]
    fn classify_table() {
        let jpeg = [0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0];
        let webp = *b"RIFF\x01\x02\x03\x04WEBP";
        let mp4 = *b"\0\0\0\x18ftypmp42";
        type Case<'a> = (Option<&'a str>, &'a str, &'a [u8], MediaKind, &'a str);
        let cases: Vec<Case> = vec![
            (Some("video/mp4"), "x.jpg", &jpeg, Video, "video/mp4"),
            (Some("Audio/OGG"), "x", &[], Audio, "audio/ogg"),
            (Some("image/heic"), "x", &[], Image, "image/heic"),
            (Some("text/plain"), "x", &[], Document, "text/plain"),
            (Some(OCTET_STREAM), "x", &jpeg, Image, "image/jpeg"),
            (None, "x", b"\x89PNG\r\n", Image, "image/png"),
            (Some(" "), "x", &webp, Image, "image/webp"),
            (None, "x", b"GIF89a", Image, "image/gif"),
            (None, "x", &mp4, Video, "video/mp4"),
            (None, "doc.pdf", &[], Document, "application/pdf"),
            (None, "doc.PDF", b"junk", Document, "application/pdf"),
            (None, "song.mp3", &[], Audio, "audio/mpeg"),
            (None, "noext", &[], Document, OCTET_STREAM),
            (None, "a.xyz", &[], Document, OCTET_STREAM),
            (Some(OCTET_STREAM), "a.xyz", &[], Document, OCTET_STREAM),
        ];
        for (mime, name, head, kind, want) in cases {
            assert_eq!(
                classify(mime, name, head),
                (kind, want.to_owned()),
                "{name}"
            );
        }
    }

    #[test]
    fn thumbnail_rules() {
        let jpeg = |len: usize| {
            let mut v = vec![0u8; len];
            v[..3].copy_from_slice(&[0xFF, 0xD8, 0xFF]);
            v
        };
        assert_eq!(check_thumbnail(&jpeg(MAX_SEND_THUMBNAIL_BYTES)), Ok(()));
        assert_eq!(
            check_thumbnail(&jpeg(MAX_SEND_THUMBNAIL_BYTES + 1)),
            Err(MediaOptionError::ThumbnailTooLarge {
                len: MAX_SEND_THUMBNAIL_BYTES + 1
            })
        );
        assert_eq!(
            check_thumbnail(b"\x89PNG\r\n"),
            Err(MediaOptionError::ThumbnailNotJpeg)
        );
        assert_eq!(
            check_thumbnail(&[]),
            Err(MediaOptionError::ThumbnailNotJpeg)
        );
    }

    #[test]
    fn waveform_rules() {
        assert_eq!(check_waveform(&[100; WAVEFORM_LEN]), Ok(()));
        assert_eq!(check_waveform(&[0; WAVEFORM_LEN]), Ok(()));
        assert_eq!(
            check_waveform(&[0; WAVEFORM_LEN - 1]),
            Err(MediaOptionError::WaveformLength { len: 63 })
        );
        assert_eq!(
            check_waveform(&[0; WAVEFORM_LEN + 1]),
            Err(MediaOptionError::WaveformLength { len: 65 })
        );
        let mut loud = [0u8; WAVEFORM_LEN];
        loud[63] = 101;
        assert_eq!(check_waveform(&loud), Err(MediaOptionError::WaveformRange));
    }

    #[test]
    fn view_once_kinds() {
        use MediaKind::*;
        for kind in [Image, Video, Audio] {
            assert_eq!(check_view_once(kind, true), Ok(()));
        }
        for kind in [Document, Sticker] {
            assert_eq!(
                check_view_once(kind, true),
                Err(MediaOptionError::ViewOnceKind)
            );
            assert_eq!(check_view_once(kind, false), Ok(()));
        }
    }

    #[test]
    fn size_cap() {
        assert_eq!(check_size(0), Ok(()));
        assert_eq!(check_size(MAX_SEND_BYTES), Ok(()));
        assert_eq!(
            check_size(MAX_SEND_BYTES + 1),
            Err(MediaTooLarge {
                len: MAX_SEND_BYTES + 1
            })
        );
    }
}
