//! Link previews: the first link of a text, and what a page says about itself.
//!
//! The phone fetches the page (owner decision, `plans/e6wa-r4.md`), reads at most [`MAX_HTML_BYTES`] of it and hands
//! the bytes here. Only `<meta>` tags (`og:`, `twitter:`, `description`) and `<title>` of the head are read; nothing
//! else of the page is interpreted, and no script runs.

use super::utf16::units_before;

/// The most HTML read of a page.
pub const MAX_HTML_BYTES: usize = 512 * 1024;
/// A title is cut to this many characters.
pub const MAX_TITLE_CHARS: usize = 200;
/// A description is cut to this many characters.
pub const MAX_DESCRIPTION_CHARS: usize = 300;

/// A link in a text.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct FoundUrl {
    /// The link to fetch: as written, with `https://` in front of a bare `www.` link.
    pub url: String,
    /// UTF-16 range of the link as written.
    pub start: usize,
    pub end: usize,
}

fn starts_with_ignore_case(text: &str, prefix: &str) -> bool {
    text.len() >= prefix.len()
        && text.is_char_boundary(prefix.len())
        && text[..prefix.len()].eq_ignore_ascii_case(prefix)
}

fn trim_trailing(candidate: &str) -> &str {
    let mut end = candidate.len();
    loop {
        let head = &candidate[..end];
        let Some(last) = head.chars().next_back() else {
            return head;
        };
        let cut = match last {
            '.' | ',' | ';' | ':' | '!' | '?' | '\'' | '"' => true,
            ')' => head.matches('(').count() < head.matches(')').count(),
            ']' => head.matches('[').count() < head.matches(']').count(),
            '}' => head.matches('{').count() < head.matches('}').count(),
            _ => false,
        };
        if !cut {
            return head;
        }
        end -= last.len_utf8();
    }
}

/// The first `http(s)://` or `www.` link of `text` whose host has a dot. Trailing punctuation and an unbalanced
/// closing bracket are not part of it.
#[must_use]
pub fn first_url(text: &str) -> Option<FoundUrl> {
    let mut prev: Option<char> = None;
    for (at, ch) in text.char_indices() {
        // Not inside a word, an e-mail address or a path.
        let word_start =
            prev.is_none_or(|p| !p.is_alphanumeric() && !matches!(p, '@' | '.' | '/' | '-' | '_'));
        prev = Some(ch);
        if !word_start || !matches!(ch, 'h' | 'H' | 'w' | 'W') {
            continue;
        }
        let rest = &text[at..];
        let www = starts_with_ignore_case(rest, "www.");
        if !(starts_with_ignore_case(rest, "http://")
            || starts_with_ignore_case(rest, "https://")
            || www)
        {
            continue;
        }
        let len = rest
            .find(|c: char| c.is_whitespace() || matches!(c, '<' | '>' | '"' | '`'))
            .unwrap_or(rest.len());
        let written = trim_trailing(&rest[..len]);
        let url = if www {
            format!("https://{written}")
        } else {
            written.to_owned()
        };
        if is_fetchable(&url) {
            return Some(FoundUrl {
                url,
                start: units_before(text, at),
                end: units_before(text, at + written.len()),
            });
        }
    }
    None
}

/// Splits `url` into its lowercase scheme and its authority (without user info) when it is `http` or `https`.
fn scheme_and_authority(url: &str) -> Option<(&'static str, &str)> {
    let (scheme, rest) = if starts_with_ignore_case(url, "https://") {
        ("https", &url[8..])
    } else if starts_with_ignore_case(url, "http://") {
        ("http", &url[7..])
    } else {
        return None;
    };
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    Some((scheme, &rest[..end]))
}

fn host_part(authority: &str) -> &str {
    if let Some(v6) = authority.strip_prefix('[') {
        return v6.split(']').next().unwrap_or_default();
    }
    authority.split(':').next().unwrap_or_default()
}

/// Whether the phone may fetch `url`: `http` or `https`, a host with a dot (or an IP literal) and no user info
/// (`user@host` hides where the link really goes).
#[must_use]
pub fn is_fetchable(url: &str) -> bool {
    let Some((_, authority)) = scheme_and_authority(url) else {
        return false;
    };
    if authority.contains('@') {
        return false;
    }
    let host = host_part(authority);
    let ok_chars = host
        .chars()
        .all(|c| c.is_alphanumeric() || matches!(c, '.' | '-' | ':'));
    !host.is_empty() && ok_chars && (host.contains('.') || host.contains(':'))
}

/// The host of `url` as a preview card shows it: lowercase, without `www.` and the port.
#[must_use]
pub fn host_of(url: &str) -> Option<String> {
    let (_, authority) = scheme_and_authority(url)?;
    let host = host_part(authority).to_lowercase();
    let host = host.strip_prefix("www.").unwrap_or(&host).to_owned();
    (!host.is_empty()).then_some(host)
}

/// `href` as an absolute `http(s)` URL against the page `base`; `None` for another scheme.
#[must_use]
pub fn resolve_url(base: &str, href: &str) -> Option<String> {
    let href = href.trim();
    if href.is_empty() {
        return None;
    }
    if scheme_and_authority(href).is_some() {
        return Some(href.to_owned());
    }
    let (scheme, authority) = scheme_and_authority(base)?;
    if let Some(rest) = href.strip_prefix("//") {
        return Some(format!("{scheme}://{rest}"));
    }
    if let Some(colon) = href.find(':')
        && href.find('/').is_none_or(|slash| colon < slash)
    {
        // `javascript:`, `data:`, `mailto:` and other schemes.
        return None;
    }
    let origin = format!("{scheme}://{authority}");
    if href.starts_with('/') {
        return Some(format!("{origin}{href}"));
    }
    let after = &base[base.find(authority).map_or(0, |i| i + authority.len())..];
    let path = after.split(['?', '#']).next().unwrap_or_default();
    let dir = path.rfind('/').map_or("/", |i| &path[..=i]);
    Some(format!("{origin}{dir}{href}"))
}

/// What a page says about itself.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct LinkMeta {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// The preview image, absolute.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub image: Option<String>,
    /// The page's own URL (`og:url`), absolute; `None` when it names none.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
}

impl LinkMeta {
    /// Nothing worth a card: no title and no description.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.title.is_none() && self.description.is_none()
    }
}

fn decode_entities(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(amp) = rest.find('&') {
        out.push_str(&rest[..amp]);
        let tail = &rest[amp..];
        let Some(semi) = tail.bytes().take(12).position(|b| b == b';') else {
            out.push('&');
            rest = &tail[1..];
            continue;
        };
        let name = &tail[1..semi];
        let decoded = match name {
            "amp" => Some('&'),
            "lt" => Some('<'),
            "gt" => Some('>'),
            "quot" => Some('"'),
            "apos" => Some('\''),
            "nbsp" => Some(' '),
            _ => name
                .strip_prefix("#x")
                .or_else(|| name.strip_prefix("#X"))
                .map(|hex| u32::from_str_radix(hex, 16).ok())
                .or_else(|| name.strip_prefix('#').map(|dec| dec.parse::<u32>().ok()))
                .flatten()
                .and_then(char::from_u32),
        };
        match decoded {
            Some(c) => {
                out.push(c);
                rest = &tail[semi + 1..];
            }
            None => {
                out.push('&');
                rest = &tail[1..];
            }
        }
    }
    out.push_str(rest);
    out
}

/// Decodes entities, folds white space and cuts to `max` characters (an ellipsis marks the cut).
fn clean(raw: &str, max: usize) -> Option<String> {
    let decoded = decode_entities(raw);
    let folded = decoded.split_whitespace().collect::<Vec<_>>().join(" ");
    if folded.is_empty() {
        return None;
    }
    if folded.chars().count() <= max {
        return Some(folded);
    }
    let mut cut: String = folded.chars().take(max.saturating_sub(1)).collect();
    cut.truncate(cut.trim_end().len());
    cut.push('…');
    Some(cut)
}

/// The attributes of one tag, `start` just after its name; names lowercase. Returns them and the byte after `>`.
fn attributes(html: &str, start: usize) -> (Vec<(String, String)>, usize) {
    let bytes = html.as_bytes();
    let mut i = start;
    let mut out = Vec::new();
    loop {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b'/') {
            i += 1;
        }
        if i >= bytes.len() {
            return (out, i);
        }
        if bytes[i] == b'>' {
            return (out, i + 1);
        }
        let name_start = i;
        while i < bytes.len()
            && !bytes[i].is_ascii_whitespace()
            && !matches!(bytes[i], b'=' | b'>' | b'/')
        {
            i += 1;
        }
        let name = html[name_start..i].to_ascii_lowercase();
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let mut value = String::new();
        if i < bytes.len() && bytes[i] == b'=' {
            i += 1;
            while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < bytes.len() && matches!(bytes[i], b'"' | b'\'') {
                let quote = bytes[i];
                let value_start = i + 1;
                let value_end = bytes[value_start..]
                    .iter()
                    .position(|b| *b == quote)
                    .map_or(bytes.len(), |p| value_start + p);
                value = html[value_start..value_end].to_owned();
                i = (value_end + 1).min(bytes.len());
            } else {
                let value_start = i;
                while i < bytes.len() && !bytes[i].is_ascii_whitespace() && bytes[i] != b'>' {
                    i += 1;
                }
                value = html[value_start..i].to_owned();
            }
        }
        if name.is_empty() {
            i += 1;
        } else {
            out.push((name, value));
        }
    }
}

/// Fills `slot` with `value` unless a source ranked at least as high already filled it.
fn offer(slot: &mut (Option<String>, u8), value: &str, rank: u8, max: usize) {
    if rank <= slot.1 && slot.0.is_some() {
        return;
    }
    if let Some(v) = clean(value, max) {
        *slot = (Some(v), rank);
    }
}

/// Reads the `<title>` and the `<meta>` tags of the head of `html` (the first [`MAX_HTML_BYTES`] at most), for the
/// page at `page_url`. `og:` wins over `twitter:`, which wins over `<title>` and `description`. URLs are made
/// absolute against the page; an image that is not `http(s)` is dropped.
#[must_use]
pub fn parse_html(html: &str, page_url: &str) -> LinkMeta {
    let mut end = html.len().min(MAX_HTML_BYTES);
    while !html.is_char_boundary(end) {
        end -= 1;
    }
    let html = &html[..end];
    let lower = html.to_ascii_lowercase();
    let stop = ["</head", "<body"]
        .iter()
        .filter_map(|m| lower.find(m))
        .min()
        .unwrap_or(lower.len());
    let mut title = (None, 0u8);
    let mut description = (None, 0u8);
    let mut image = (None, 0u8);
    let mut canonical = (None, 0u8);
    let mut at = 0usize;
    while at < stop {
        let Some(open) = lower[at..stop].find('<') else {
            break;
        };
        let tag = at + open;
        if lower[tag..].starts_with("<!--") {
            at = lower[tag..].find("-->").map_or(stop, |e| tag + e + 3);
            continue;
        }
        if lower[tag..].starts_with("<script") || lower[tag..].starts_with("<style") {
            let close = if lower[tag..].starts_with("<script") {
                "</script"
            } else {
                "</style"
            };
            at = lower[tag..]
                .find(close)
                .map_or(stop, |e| tag + e + close.len());
            continue;
        }
        if lower[tag..].starts_with("<title") {
            let (_, body) = attributes(html, tag + 6);
            let close = lower[body..]
                .find("</title")
                .map_or(stop, |e| body + e)
                .max(body);
            offer(&mut title, &html[body..close], 1, MAX_TITLE_CHARS);
            at = close;
            continue;
        }
        if lower[tag..].starts_with("<meta") {
            let (attrs, after) = attributes(html, tag + 5);
            let key = attrs
                .iter()
                .find(|(n, _)| n == "property" || n == "name")
                .map(|(_, v)| v.trim().to_ascii_lowercase());
            let content = attrs
                .iter()
                .find(|(n, _)| n == "content")
                .map(|(_, v)| v.as_str());
            if let (Some(key), Some(content)) = (key, content) {
                match key.as_str() {
                    "og:title" => offer(&mut title, content, 3, MAX_TITLE_CHARS),
                    "twitter:title" => offer(&mut title, content, 2, MAX_TITLE_CHARS),
                    "og:description" => offer(&mut description, content, 3, MAX_DESCRIPTION_CHARS),
                    "twitter:description" => {
                        offer(&mut description, content, 2, MAX_DESCRIPTION_CHARS);
                    }
                    "description" => offer(&mut description, content, 1, MAX_DESCRIPTION_CHARS),
                    "og:image" | "og:image:url" | "og:image:secure_url" => {
                        if let Some(url) = resolve_url(page_url, &decode_entities(content)) {
                            offer(&mut image, &url, 3, usize::MAX);
                        }
                    }
                    "twitter:image" | "twitter:image:src" => {
                        if let Some(url) = resolve_url(page_url, &decode_entities(content)) {
                            offer(&mut image, &url, 2, usize::MAX);
                        }
                    }
                    "og:url" => {
                        if let Some(url) = resolve_url(page_url, &decode_entities(content)) {
                            offer(&mut canonical, &url, 3, usize::MAX);
                        }
                    }
                    _ => {}
                }
            }
            at = after.max(tag + 1);
            continue;
        }
        at = tag + 1;
    }
    LinkMeta {
        title: title.0,
        description: description.0,
        image: image.0,
        url: canonical.0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_first_link_without_trailing_punctuation() {
        let found = first_url("see https://example.com/a(b), and http://x.org").expect("link");
        assert_eq!(found.url, "https://example.com/a(b)");
        assert_eq!((found.start, found.end), (4, 28));
        let found = first_url("(www.example.com/x).").expect("link");
        assert_eq!(found.url, "https://www.example.com/x");
        assert_eq!((found.start, found.end), (1, 18));
        assert_eq!(
            first_url("HTTPS://Example.com").map(|f| f.url).as_deref(),
            Some("HTTPS://Example.com")
        );
    }

    #[test]
    fn ignores_what_is_not_a_fetchable_link() {
        assert_eq!(first_url("no link here"), None);
        assert_eq!(first_url("ftp://example.com"), None);
        assert_eq!(first_url("http://localhost/x"), None);
        assert_eq!(first_url("mail me@www.example.com"), None);
        assert_eq!(first_url("http://user@example.com"), None);
        assert_eq!(first_url("xhttps://example.com"), None);
        assert!(is_fetchable("http://[::1]/"));
        assert!(!is_fetchable("javascript:alert(1)"));
    }

    #[test]
    fn host_for_the_card() {
        assert_eq!(
            host_of("https://WWW.Example.com:8443/a").as_deref(),
            Some("example.com")
        );
        assert_eq!(host_of("ftp://example.com"), None);
    }

    #[test]
    fn resolves_relative_urls() {
        let base = "https://example.com/news/item?id=3";
        assert_eq!(
            resolve_url(base, "/img/a.jpg").as_deref(),
            Some("https://example.com/img/a.jpg")
        );
        assert_eq!(
            resolve_url(base, "b.jpg").as_deref(),
            Some("https://example.com/news/b.jpg")
        );
        assert_eq!(
            resolve_url(base, "//cdn.example.com/c.jpg").as_deref(),
            Some("https://cdn.example.com/c.jpg")
        );
        assert_eq!(
            resolve_url(base, "http://other.org/d.png").as_deref(),
            Some("http://other.org/d.png")
        );
        assert_eq!(resolve_url(base, "data:image/png;base64,AAA"), None);
        assert_eq!(resolve_url(base, "javascript:alert(1)"), None);
        assert_eq!(
            resolve_url("https://example.com", "e.jpg").as_deref(),
            Some("https://example.com/e.jpg")
        );
    }

    #[test]
    fn reads_og_tags_before_title() {
        let html = r#"<!doctype html><html><head>
            <title>Plain &amp; simple</title>
            <meta name="description" content="Plain description">
            <meta property="og:title" content="Rich &quot;title&quot;" />
            <meta content='Rich &#233;t&#xE9;' property='og:description'>
            <meta property=og:image content=/img/card.jpg>
            <meta property="og:url" content="https://example.com/canonical">
            </head><body><meta property="og:title" content="ignored"></body></html>"#;
        let meta = parse_html(html, "https://example.com/page");
        assert_eq!(meta.title.as_deref(), Some("Rich \"title\""));
        assert_eq!(meta.description.as_deref(), Some("Rich été"));
        assert_eq!(
            meta.image.as_deref(),
            Some("https://example.com/img/card.jpg")
        );
        assert_eq!(meta.url.as_deref(), Some("https://example.com/canonical"));
    }

    #[test]
    fn falls_back_to_title_and_skips_scripts_and_comments() {
        let html = "<head><script>var s = '<meta property=\"og:title\" content=\"no\">';</script>\
            <!-- <title>commented</title> --><TITLE>\n  Spaced\n  title </TITLE>\
            <meta name=description content=\"Desc\"></head>";
        let meta = parse_html(html, "https://example.com/");
        assert_eq!(meta.title.as_deref(), Some("Spaced title"));
        assert_eq!(meta.description.as_deref(), Some("Desc"));
        assert_eq!(meta.image, None);
        assert!(!meta.is_empty());
        assert!(parse_html("<html><body>nothing</body></html>", "https://e.com/").is_empty());
    }

    #[test]
    fn cuts_long_text_and_survives_broken_html() {
        let long = "x".repeat(500);
        let html = format!(
            "<meta property=\"og:title\" content=\"{long}\"><meta property=\"og:description"
        );
        let meta = parse_html(&html, "https://e.com/");
        let title = meta.title.expect("title");
        assert_eq!(title.chars().count(), MAX_TITLE_CHARS);
        assert!(title.ends_with('…'));
        assert_eq!(meta.description, None);
        // A cut in the middle of a character.
        let mut big = "<title>é</title>".to_owned();
        big.push_str(&"é".repeat(MAX_HTML_BYTES));
        assert_eq!(
            parse_html(&big, "https://e.com/").title.as_deref(),
            Some("é")
        );
        // Unknown and broken entities stay as they are.
        assert_eq!(
            decode_entities("a &bogus; &#xZZ; & b"),
            "a &bogus; &#xZZ; & b"
        );
    }
}
