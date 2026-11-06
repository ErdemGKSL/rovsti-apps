//! File-name helpers shared by the SMS and WhatsApp pages.

/// The file extension for a MIME type (parameters such as `; codecs=opus`
/// are ignored); `bin` when the type is unknown.
pub fn ext_for_mime(mime: &str) -> &'static str {
    let base = mime
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    match base.as_str() {
        "image/jpeg" | "image/jpg" => "jpg",
        "image/png" => "png",
        "image/webp" => "webp",
        "image/gif" => "gif",
        "video/mp4" => "mp4",
        "audio/ogg" => "ogg",
        "audio/mpeg" => "mp3",
        "application/pdf" => "pdf",
        _ => "bin",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extensions_follow_the_type() {
        assert_eq!(ext_for_mime("image/JPEG"), "jpg");
        assert_eq!(ext_for_mime("audio/ogg; codecs=opus"), "ogg");
        assert_eq!(ext_for_mime("application/pdf"), "pdf");
        assert_eq!(ext_for_mime("application/x-weird"), "bin");
        assert_eq!(ext_for_mime(""), "bin");
    }
}
