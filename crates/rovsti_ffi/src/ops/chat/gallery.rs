//! `chat_gallery.*`, `chat_export.*` and `multi_select.plan`: the media/links/docs tabs, the text export of a chat and
//! the clipboard block and pill texts of a message selection.

use super::{NOW, TZ};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::chat_export::{self, ExportLine};
use rovsti_core::chat_gallery::{self, GalleryInput, GalleryKind, GalleryTab};
use rovsti_core::multi_select;
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!("chat_gallery.build", BuildReq, BuildAns, build, build_cases),
    op!("chat_gallery.tabs", TabsReq, TabsAns, tabs, tabs_cases),
    op!(
        "chat_export.text",
        ExportReq,
        ExportAns,
        export_text,
        export_cases
    ),
    op!("chat_export.block", BlockReq, BlockAns, block, block_cases),
    op!(
        "chat_export.notice",
        NoticeReq,
        NoticeAns,
        notice,
        notice_cases
    ),
    op!("multi_select.plan", PlanReq, PlanAns, plan, plan_cases),
];

// ---- gallery ----

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum KindDto {
    Image,
    Video,
    Audio,
    Doc,
    Other,
}

impl From<KindDto> for GalleryKind {
    fn from(k: KindDto) -> Self {
        match k {
            KindDto::Image => Self::Image,
            KindDto::Video => Self::Video,
            KindDto::Audio => Self::Audio,
            KindDto::Doc => Self::Doc,
            KindDto::Other => Self::Other,
        }
    }
}

#[derive(Deserialize)]
struct ItemDto {
    id: String,
    at_ms: i64,
    /// `null` for a plain text message.
    #[serde(default)]
    kind: Option<KindDto>,
    #[serde(default)]
    text: Option<String>,
    #[serde(default)]
    file_name: Option<String>,
    #[serde(default)]
    size: Option<u64>,
}

#[derive(Deserialize)]
struct BuildReq {
    items: Vec<ItemDto>,
    tz_offset_s: i32,
    /// Longest link text in a row (40 when absent).
    #[serde(default)]
    short_max: Option<usize>,
}

#[derive(Serialize)]
struct MediaOut {
    id: String,
    at_ms: i64,
}

#[derive(Serialize)]
struct LinkOut {
    id: String,
    at_ms: i64,
    url: String,
    host: String,
    /// The link without scheme and `www.`, cut with an ellipsis.
    short: String,
}

#[derive(Serialize)]
struct DocOut {
    id: String,
    at_ms: i64,
    name: String,
    /// `PDF · 1.2 MB`, `Audio · 340 KB`.
    detail: String,
}

/// Consecutive items of one month: `November 2026`, items `start..end` of the tab's list.
#[derive(Serialize)]
struct SectionOut {
    label: String,
    start: usize,
    end: usize,
}

#[derive(Serialize)]
struct BuildAns {
    /// Pictures and videos, newest first.
    media: Vec<MediaOut>,
    links: Vec<LinkOut>,
    docs: Vec<DocOut>,
    media_sections: Vec<SectionOut>,
    link_sections: Vec<SectionOut>,
    doc_sections: Vec<SectionOut>,
}

fn sections_of<T>(items: &[T], at: impl Fn(&T) -> i64, tz: i32) -> Vec<SectionOut> {
    chat_gallery::sections(items, at, tz)
        .into_iter()
        .map(|(label, range)| SectionOut {
            label,
            start: range.start,
            end: range.end,
        })
        .collect()
}

fn build(req: BuildReq) -> Result<BuildAns, FfiError> {
    let inputs: Vec<GalleryInput<'_>> = req
        .items
        .iter()
        .map(|i| GalleryInput {
            id: &i.id,
            at_ms: i.at_ms,
            kind: i.kind.map(GalleryKind::from),
            text: i.text.as_deref(),
            file_name: i.file_name.as_deref(),
            size: i.size,
        })
        .collect();
    let gallery = chat_gallery::build(&inputs);
    let tz = req.tz_offset_s;
    let max = req.short_max.unwrap_or(40);
    Ok(BuildAns {
        media_sections: sections_of(&gallery.media, |m| m.1, tz),
        link_sections: sections_of(&gallery.links, |l| l.at_ms, tz),
        doc_sections: sections_of(&gallery.docs, |d| d.at_ms, tz),
        media: gallery
            .media
            .into_iter()
            .map(|(id, at_ms)| MediaOut { id, at_ms })
            .collect(),
        links: gallery
            .links
            .into_iter()
            .map(|l| LinkOut {
                short: chat_gallery::short_url(&l.url, max),
                id: l.id,
                at_ms: l.at_ms,
                url: l.url,
                host: l.host,
            })
            .collect(),
        docs: gallery
            .docs
            .into_iter()
            .map(|d| DocOut {
                id: d.id,
                at_ms: d.at_ms,
                name: d.name,
                detail: d.detail,
            })
            .collect(),
    })
}

fn build_cases() -> Vec<Case> {
    let day = 86_400_000;
    let items = json!([
        {"id": "m1", "at_ms": NOW, "kind": "image"},
        {"id": "m2", "at_ms": NOW - day, "kind": "video", "size": 2_400_000},
        {"id": "d1", "at_ms": NOW - 2 * day, "kind": "doc", "file_name": "Fatura Ekim.pdf", "size": 1_258_291},
        {"id": "a1", "at_ms": NOW - 3 * day, "kind": "audio", "size": 348_160},
        {"id": "t1", "at_ms": NOW - 40 * day, "text": "Bak https://www.example.com/a/very/long/path/to/a/page?id=1234567890&ref=chat ve example.org"},
        {"id": "t2", "at_ms": NOW - 41 * day, "text": "Düz yazı, link yok" },
    ]);
    vec![
        Case::new(json!({"items": items, "tz_offset_s": TZ})),
        Case::named("empty", json!({"items": [], "tz_offset_s": TZ})),
        Case::named(
            "short_links",
            json!({"items": [items[4]], "tz_offset_s": TZ, "short_max": 20}),
        ),
    ]
}

#[derive(Deserialize)]
struct TabsReq {
    /// Only part of the chat is loaded: the empty texts say so.
    #[serde(default)]
    partial: bool,
}

#[derive(Serialize)]
struct TabOut {
    /// `media`, `links` or `docs`.
    key: &'static str,
    label: &'static str,
    empty_text: &'static str,
}

#[derive(Serialize)]
struct TabsAns {
    tabs: Vec<TabOut>,
}

fn tabs(req: TabsReq) -> Result<TabsAns, FfiError> {
    let tab = |key, tab: GalleryTab| TabOut {
        key,
        label: tab.label(),
        empty_text: chat_gallery::empty_text(tab, req.partial),
    };
    Ok(TabsAns {
        tabs: vec![
            tab("media", GalleryTab::Media),
            tab("links", GalleryTab::Links),
            tab("docs", GalleryTab::Docs),
        ],
    })
}

fn tabs_cases() -> Vec<Case> {
    vec![
        Case::new(json!({})),
        Case::named("partial", json!({"partial": true})),
    ]
}

// ---- export ----

#[derive(Deserialize)]
struct LineDto {
    at_ms: i64,
    author: String,
    text: String,
}

fn lines_of(lines: &[LineDto]) -> Vec<ExportLine<'_>> {
    lines
        .iter()
        .map(|l| ExportLine {
            at_ms: l.at_ms,
            author: &l.author,
            text: &l.text,
        })
        .collect()
}

#[derive(Deserialize)]
struct ExportReq {
    title: String,
    /// Oldest first.
    lines: Vec<LineDto>,
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct ExportAns {
    /// "Chat with Ada", a blank line, then the block.
    text: String,
    /// `chat-ada-2026-09-21.txt`.
    file_name: String,
}

fn export_text(req: ExportReq) -> Result<ExportAns, FfiError> {
    Ok(ExportAns {
        text: chat_export::export_text(&req.title, &lines_of(&req.lines), req.tz_offset_s),
        file_name: chat_export::export_file_name(&req.title, req.now_ms, req.tz_offset_s),
    })
}

fn sample_lines() -> serde_json::Value {
    json!([
        {"at_ms": NOW - 90_000_000, "author": "Ayse", "text": "Dün akşam\nne yaptın?"},
        {"at_ms": NOW - 89_000_000, "author": "Me", "text": "  "},
        {"at_ms": NOW - 3_600_000, "author": "Me", "text": "Kitap okudum.  "},
    ])
}

fn export_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"title": "Ayşe Yılmaz", "lines": sample_lines(), "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "empty",
            json!({"title": "***", "lines": [], "now_ms": NOW, "tz_offset_s": TZ}),
        ),
    ]
}

#[derive(Deserialize)]
struct BlockReq {
    lines: Vec<LineDto>,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct BlockAns {
    text: String,
}

fn block(req: BlockReq) -> Result<BlockAns, FfiError> {
    Ok(BlockAns {
        text: chat_export::block_text(&lines_of(&req.lines), req.tz_offset_s),
    })
}

fn block_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"lines": sample_lines(), "tz_offset_s": TZ}),
    )]
}

#[derive(Deserialize)]
struct NoticeReq {
    ok: bool,
    /// Older messages were not loaded.
    #[serde(default)]
    partial: bool,
}

#[derive(Serialize)]
struct NoticeAns {
    text: &'static str,
}

fn notice(req: NoticeReq) -> Result<NoticeAns, FfiError> {
    Ok(NoticeAns {
        text: chat_export::export_notice(req.ok, req.partial),
    })
}

fn notice_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"ok": true})),
        Case::named("partial", json!({"ok": true, "partial": true})),
        Case::named("failed", json!({"ok": false})),
    ]
}

// ---- multi_select ----

#[derive(Deserialize)]
struct PlanReq {
    /// How many messages are selected.
    count: usize,
    /// The selected messages, oldest first.
    lines: Vec<LineDto>,
    /// Whether each selected message is already starred.
    #[serde(default)]
    starred: Vec<bool>,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct PlanAns {
    /// "Select messages" or "3 selected".
    title: String,
    /// The clipboard text: a lone message is its text, several are a chat block.
    copy_text: String,
    /// The star button stars (true) or unstars (false).
    star: bool,
    copied_notice: String,
    starred_notice: String,
    deleted_notice: String,
    /// Shown when a tap would pass the cap.
    cap_notice: String,
    max_selected: usize,
}

fn plan(req: PlanReq) -> Result<PlanAns, FfiError> {
    let star = multi_select::star_all(&req.starred);
    Ok(PlanAns {
        title: multi_select::title(req.count),
        copy_text: multi_select::copy_block(&lines_of(&req.lines), req.tz_offset_s),
        star,
        copied_notice: multi_select::copied_notice(req.count),
        starred_notice: multi_select::starred_notice(req.count, star),
        deleted_notice: multi_select::deleted_notice(req.count),
        cap_notice: multi_select::cap_notice(),
        max_selected: multi_select::MAX_SELECTED,
    })
}

fn plan_cases() -> Vec<Case> {
    let lines = sample_lines();
    vec![
        Case::new(
            json!({"count": 2, "lines": [lines[0], lines[2]], "starred": [true, false], "tz_offset_s": TZ}),
        ),
        Case::named(
            "one_starred",
            json!({"count": 1, "lines": [lines[2]], "starred": [true], "tz_offset_s": TZ}),
        ),
        Case::named("none", json!({"count": 0, "lines": [], "tz_offset_s": TZ})),
    ]
}
