//! Per-chat gallery: media, links and documents, grouped by month.

use std::ops::Range;

use crate::call_log::civil_from_days;
use crate::spam;

const MONTHS_LONG: [&str; 12] = [
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GalleryKind {
    Image,
    Video,
    Audio,
    Doc,
    Other,
}

/// One message as the gallery sees it. `kind: None` is a plain text message.
#[derive(Clone, Copy, Debug)]
pub struct GalleryInput<'a> {
    pub id: &'a str,
    pub at_ms: i64,
    pub kind: Option<GalleryKind>,
    pub text: Option<&'a str>,
    pub file_name: Option<&'a str>,
    pub size: Option<u64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum GalleryTab {
    #[default]
    Media,
    Links,
    Docs,
}

impl GalleryTab {
    pub fn label(self) -> &'static str {
        match self {
            Self::Media => "Media",
            Self::Links => "Links",
            Self::Docs => "Docs",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinkEntry {
    pub id: String,
    pub at_ms: i64,
    pub url: String,
    pub host: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DocEntry {
    pub id: String,
    pub at_ms: i64,
    pub name: String,
    /// `PDF · 1.2 MB`, `Audio · 340 KB`.
    pub detail: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Gallery {
    /// `(message id, at_ms)` of pictures and videos.
    pub media: Vec<(String, i64)>,
    pub links: Vec<LinkEntry>,
    pub docs: Vec<DocEntry>,
}

/// Sorts the messages into the three tabs, newest first.
pub fn build(items: &[GalleryInput<'_>]) -> Gallery {
    let mut sorted: Vec<&GalleryInput<'_>> = items.iter().collect();
    sorted.sort_by_key(|a| std::cmp::Reverse(a.at_ms));
    let mut out = Gallery::default();
    for item in sorted {
        match item.kind {
            Some(GalleryKind::Image | GalleryKind::Video) => {
                out.media.push((item.id.to_owned(), item.at_ms));
            }
            Some(kind) => out.docs.push(DocEntry {
                id: item.id.to_owned(),
                at_ms: item.at_ms,
                name: doc_name(kind, item.file_name),
                detail: doc_detail(kind, item.file_name, item.size),
            }),
            None => {}
        }
        if let Some(text) = item.text {
            for url in spam::links(text) {
                if out.links.iter().any(|l| l.url == url) {
                    continue;
                }
                out.links.push(LinkEntry {
                    id: item.id.to_owned(),
                    at_ms: item.at_ms,
                    host: host_of(&url),
                    url,
                });
            }
        }
    }
    out
}

fn doc_name(kind: GalleryKind, file_name: Option<&str>) -> String {
    match file_name.map(str::trim).filter(|n| !n.is_empty()) {
        Some(name) => name.to_owned(),
        None if kind == GalleryKind::Audio => "Audio".to_owned(),
        None => "Document".to_owned(),
    }
}

fn doc_detail(kind: GalleryKind, file_name: Option<&str>, size: Option<u64>) -> String {
    let ext = file_name
        .and_then(|n| n.rsplit_once('.'))
        .map(|(_, e)| e)
        .filter(|e| !e.is_empty() && e.len() <= 5 && e.chars().all(|c| c.is_ascii_alphanumeric()));
    let label = match (kind, ext) {
        (GalleryKind::Audio, _) => "Audio".to_owned(),
        (_, Some(e)) => e.to_ascii_uppercase(),
        _ => "File".to_owned(),
    };
    match size {
        Some(bytes) => format!("{label} · {}", size_label(bytes)),
        None => label,
    }
}

fn host_of(url: &str) -> String {
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    let host = rest.split(['/', '?', '#']).next().unwrap_or(rest);
    host.strip_prefix("www.").unwrap_or(host).to_owned()
}

fn local_day(ms: i64, tz_offset_s: i32) -> i64 {
    (ms.div_euclid(1000) + i64::from(tz_offset_s)).div_euclid(86_400)
}

/// `November 2026`.
pub fn month_label(ms: i64, tz_offset_s: i32) -> String {
    let (y, m, _) = civil_from_days(local_day(ms, tz_offset_s));
    format!("{} {y}", MONTHS_LONG[(m as usize - 1) % 12])
}

/// Consecutive runs of items that fall in the same month.
pub fn sections<T>(
    items: &[T],
    at: impl Fn(&T) -> i64,
    tz_offset_s: i32,
) -> Vec<(String, Range<usize>)> {
    let mut out: Vec<(String, Range<usize>)> = Vec::new();
    for (i, item) in items.iter().enumerate() {
        let label = month_label(at(item), tz_offset_s);
        match out.last_mut() {
            Some((l, range)) if *l == label => range.end = i + 1,
            _ => out.push((label, i..i + 1)),
        }
    }
    out
}

/// `820 B`, `34 KB`, `1.2 MB`.
pub fn size_label(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    if bytes >= MB {
        format!("{:.1} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{} KB", bytes / KB)
    } else {
        format!("{bytes} B")
    }
}

/// The link without scheme and `www.`, cut to `max` characters with an ellipsis.
pub fn short_url(url: &str, max: usize) -> String {
    let rest = url.split_once("://").map_or(url, |(_, r)| r);
    let rest = rest.strip_prefix("www.").unwrap_or(rest);
    if rest.chars().count() <= max {
        return rest.to_owned();
    }
    let keep = max.saturating_sub(1);
    let mut s: String = rest.chars().take(keep).collect();
    s.push('…');
    s
}

pub fn empty_text(tab: GalleryTab, partial: bool) -> &'static str {
    match (tab, partial) {
        (GalleryTab::Media, false) => "No photos or videos",
        (GalleryTab::Media, true) => "No photos or videos in the loaded part of this chat",
        (GalleryTab::Links, false) => "No links",
        (GalleryTab::Links, true) => "No links in the loaded part of this chat",
        (GalleryTab::Docs, false) => "No documents",
        (GalleryTab::Docs, true) => "No documents in the loaded part of this chat",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input<'a>(id: &'a str, at_ms: i64) -> GalleryInput<'a> {
        GalleryInput {
            id,
            at_ms,
            kind: None,
            text: None,
            file_name: None,
            size: None,
        }
    }

    #[test]
    fn splits_media_links_docs() {
        let items = [
            GalleryInput {
                kind: Some(GalleryKind::Image),
                ..input("a", 1)
            },
            GalleryInput {
                kind: Some(GalleryKind::Video),
                ..input("b", 2)
            },
            GalleryInput {
                kind: Some(GalleryKind::Doc),
                file_name: Some("plan.pdf"),
                size: Some(1_258_291),
                ..input("c", 3)
            },
            GalleryInput {
                kind: Some(GalleryKind::Audio),
                size: Some(348_160),
                ..input("d", 4)
            },
            GalleryInput {
                text: Some("see https://example.com/x"),
                ..input("e", 5)
            },
            input("f", 6),
        ];
        let g = build(&items);
        assert_eq!(g.media.len(), 2);
        assert_eq!(g.links.len(), 1);
        assert_eq!(g.links[0].host, "example.com");
        assert_eq!(g.docs.len(), 2);
        assert_eq!(g.docs[1].name, "plan.pdf");
        assert_eq!(g.docs[1].detail, "PDF · 1.2 MB");
        assert_eq!(g.docs[0].name, "Audio");
        assert_eq!(g.docs[0].detail, "Audio · 340 KB");
    }

    #[test]
    fn links_deduplicate_keep_newest() {
        let items = [
            GalleryInput {
                text: Some("https://a.com/x"),
                ..input("old", 1)
            },
            GalleryInput {
                text: Some("https://a.com/x and https://b.com"),
                ..input("new", 9)
            },
        ];
        let g = build(&items);
        assert_eq!(g.links.len(), 2);
        assert_eq!(g.links[0].id, "new");
        assert!(g.links.iter().all(|l| l.id == "new"));
    }

    #[test]
    fn newest_first() {
        let items = [
            GalleryInput {
                kind: Some(GalleryKind::Image),
                ..input("a", 1)
            },
            GalleryInput {
                kind: Some(GalleryKind::Image),
                ..input("c", 3)
            },
            GalleryInput {
                kind: Some(GalleryKind::Image),
                ..input("b", 2)
            },
        ];
        let ids: Vec<_> = build(&items).media.into_iter().map(|m| m.0).collect();
        assert_eq!(ids, ["c", "b", "a"]);
    }

    // 2026-11-15 12:00 UTC and 2026-10-15 12:00 UTC.
    const NOV: i64 = 1_794_744_000_000;
    const OCT: i64 = NOV - 31 * 86_400_000;

    #[test]
    fn month_sections_group_consecutive() {
        let times = [NOV, NOV - 1000, OCT, OCT - 1000, NOV - 90 * 86_400_000];
        let s = sections(&times, |t| *t, 0);
        assert_eq!(s.len(), 3);
        assert_eq!(s[0], ("November 2026".to_owned(), 0..2));
        assert_eq!(s[1], ("October 2026".to_owned(), 2..4));
        assert_eq!(s[2].1, 4..5);
    }

    #[test]
    fn month_label_respects_offset() {
        // 2026-11-30 23:00 UTC.
        let ms = NOV + 15 * 86_400_000 + 11 * 3_600_000;
        assert_eq!(month_label(ms, 0), "November 2026");
        assert_eq!(month_label(ms, 3 * 3600), "December 2026");
    }

    #[test]
    fn size_labels() {
        assert_eq!(size_label(820), "820 B");
        assert_eq!(size_label(34 * 1024), "34 KB");
        assert_eq!(size_label(1_258_291), "1.2 MB");
    }

    #[test]
    fn short_url_strips_scheme() {
        assert_eq!(short_url("https://www.example.com/a", 40), "example.com/a");
        assert_eq!(
            short_url("https://example.com/abcdefghij", 10),
            "example.c…"
        );
        assert_eq!(short_url("example.com", 40), "example.com");
    }

    #[test]
    fn empty_text_partial() {
        assert_eq!(empty_text(GalleryTab::Links, false), "No links");
        assert_eq!(
            empty_text(GalleryTab::Docs, true),
            "No documents in the loaded part of this chat"
        );
        assert_eq!(empty_text(GalleryTab::Media, false), "No photos or videos");
        assert_eq!(GalleryTab::default().label(), "Media");
    }
}
