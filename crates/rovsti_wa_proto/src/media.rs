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
