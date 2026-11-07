//! Media, links and docs: one glass sheet shared by the WhatsApp and SMS pages.
//! The grouping lives in `rovsti_core::chat_gallery`; the pages build the
//! [`GallerySheet`], answer the [`GalleryAction`]s and close it on Back.

use std::rc::Rc;

use gpui::{
    AnyElement, App, ImageSource, InteractiveElement, IntoElement, ObjectFit, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, StyledImage, Window, div, img, px,
};
use rovsti_core::chat_gallery::{self, DocEntry, Gallery, GalleryKind, GalleryTab, LinkEntry};
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, metrics, radius, shadow, typography},
};
use rovsti_wa_proto::{MediaKind, MediaMeta};

/// The page's handler for [`GalleryAction`]s.
pub(crate) type Dispatch = Rc<dyn Fn(GalleryAction, &mut Window, &mut App)>;

type ClickFn = Box<dyn Fn(&gpui::ClickEvent, &mut Window, &mut App)>;

/// Thumbnails per row of the Media tab.
const COLUMNS: usize = 3;
/// Longest link text in a Links row.
const LINK_CHARS: usize = 48;

/// An open gallery of one chat.
pub(crate) struct GallerySheet {
    pub tab: GalleryTab,
    pub gallery: Gallery,
    /// Older messages exist that are not loaded yet.
    pub partial: bool,
    /// How many timeline items `gallery` was built from; a longer timeline
    /// (after "Load earlier") rebuilds it.
    pub built_for: usize,
}

impl GallerySheet {
    pub fn new(gallery: Gallery, partial: bool, built_for: usize) -> Self {
        Self {
            tab: GalleryTab::default(),
            gallery,
            partial,
            built_for,
        }
    }
}

/// What a tap in the sheet asks the page to do.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum GalleryAction {
    Close,
    Tab(GalleryTab),
    /// A media tile: open the viewer (or the message for a video).
    Media(String),
    /// A link row: open it in the browser.
    Open(String),
    CopyLink(String),
    /// A link or document row: show the message in the chat.
    Jump(String),
    /// "Load earlier": one more page.
    Older,
}

/// What a media tile draws: the picture when it is loaded, a kind icon else.
pub(crate) struct Thumb {
    pub source: Option<ImageSource>,
    pub video: bool,
}

/// The gallery kind of a WhatsApp media message; stickers are left out.
pub(crate) fn kind_of(kind: MediaKind) -> Option<GalleryKind> {
    match kind {
        MediaKind::Image => Some(GalleryKind::Image),
        MediaKind::Video => Some(GalleryKind::Video),
        MediaKind::Audio => Some(GalleryKind::Audio),
        MediaKind::Document => Some(GalleryKind::Doc),
        MediaKind::Sticker => None,
    }
}

/// The file name of a WhatsApp document, when it has a real one.
pub(crate) fn file_name_of(media: Option<&MediaMeta>) -> Option<&str> {
    media
        .and_then(|m| m.file_name.as_deref())
        .map(str::trim)
        .filter(|n| !n.is_empty())
}

/// The gallery kind of an MMS part's content type; text and SMIL are no files.
pub(crate) fn kind_of_mime(content_type: &str) -> Option<GalleryKind> {
    let t = content_type.trim().to_ascii_lowercase();
    if t.is_empty() || t.starts_with("text/plain") || t.starts_with("application/smil") {
        None
    } else if t.starts_with("image/") {
        Some(GalleryKind::Image)
    } else if t.starts_with("video/") {
        Some(GalleryKind::Video)
    } else if t.starts_with("audio/") {
        Some(GalleryKind::Audio)
    } else {
        Some(GalleryKind::Doc)
    }
}

/// The last path segment of a file path.
pub(crate) fn base_name(path: &str) -> &str {
    path.rsplit(['/', '\\']).next().unwrap_or(path)
}

fn doc_icon(detail: &str) -> Icon {
    if detail.starts_with("Audio") {
        Icon::LucideHeadphones
    } else {
        Icon::LucidePaperclip
    }
}

/// The sheet over the page: a dimmed screen and a tall glass card.
pub(crate) fn render_gallery(
    theme: Theme,
    sheet: &GallerySheet,
    loading: bool,
    date: impl Fn(i64) -> String,
    tz_offset_s: i32,
    thumb: impl Fn(&str) -> Thumb,
    dispatch: Dispatch,
) -> AnyElement {
    let colors = theme::colors(theme);
    let act = |action: GalleryAction| -> ClickFn {
        let dispatch = Rc::clone(&dispatch);
        Box::new(
            move |_: &gpui::ClickEvent, window: &mut Window, cx: &mut App| {
                dispatch(action.clone(), window, cx);
            },
        )
    };

    let tabs = div()
        .flex()
        .gap(px(4.0))
        .p(px(3.0))
        .rounded(px(14.0))
        .bg(colors.label.opacity(0.08))
        .children(
            [GalleryTab::Media, GalleryTab::Links, GalleryTab::Docs]
                .into_iter()
                .enumerate()
                .map(|(i, tab)| {
                    let on = tab == sheet.tab;
                    let mut button = div()
                        .id(("gallery-tab", i))
                        .flex_1()
                        .flex()
                        .justify_center()
                        .items_center()
                        .min_h(px(34.0))
                        .rounded(px(12.0))
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(if on {
                            colors.label
                        } else {
                            colors.secondary_label
                        })
                        .child(tab.label())
                        .on_click(act(GalleryAction::Tab(tab)));
                    if on {
                        button = button.bg(colors.label.opacity(0.14));
                    }
                    button
                }),
        );

    let g = &sheet.gallery;
    let mut body: Vec<AnyElement> = Vec::new();
    match sheet.tab {
        GalleryTab::Media => {
            let sections = chat_gallery::sections(&g.media, |(_, at)| *at, tz_offset_s);
            for (label, range) in sections {
                body.push(header(theme, label));
                for chunk in g.media[range].chunks(COLUMNS) {
                    let mut line = div().flex().gap(px(2.0)).mb(px(2.0));
                    for (id, _) in chunk {
                        let t = thumb(id);
                        let mut tile = div()
                            .id(SharedString::from(format!("gallery-media-{id}")))
                            .flex_1()
                            .h(px(112.0))
                            .relative()
                            .overflow_hidden()
                            .rounded(px(6.0))
                            .bg(colors.label.opacity(0.08))
                            .flex()
                            .items_center()
                            .justify_center();
                        tile = match t.source {
                            Some(source) => {
                                tile.child(img(source).size_full().object_fit(ObjectFit::Cover))
                            }
                            None => tile.child(
                                gpui::svg()
                                    .path(
                                        if t.video {
                                            Icon::LucideVideo
                                        } else {
                                            Icon::LucideImage
                                        }
                                        .path(),
                                    )
                                    .size(px(26.0))
                                    .text_color(colors.secondary_label),
                            ),
                        };
                        if t.video {
                            tile = tile.child(
                                div().absolute().right(px(6.0)).bottom(px(6.0)).child(
                                    gpui::svg()
                                        .path(Icon::LucidePlay.path())
                                        .size(px(18.0))
                                        .text_color(gpui::white()),
                                ),
                            );
                        }
                        let id = id.clone();
                        line = line.child(tile.on_click(act(GalleryAction::Media(id))));
                    }
                    for _ in chunk.len()..COLUMNS {
                        line = line.child(div().flex_1());
                    }
                    body.push(line.into_any_element());
                }
            }
        }
        GalleryTab::Links => {
            let sections = chat_gallery::sections(&g.links, |l| l.at_ms, tz_offset_s);
            for (label, range) in sections {
                body.push(header(theme, label));
                for (i, link) in g.links[range.clone()].iter().enumerate() {
                    body.push(link_row(theme, range.start + i, link, &date, &act));
                }
            }
        }
        GalleryTab::Docs => {
            let sections = chat_gallery::sections(&g.docs, |d| d.at_ms, tz_offset_s);
            for (label, range) in sections {
                body.push(header(theme, label));
                for (i, doc) in g.docs[range.clone()].iter().enumerate() {
                    body.push(doc_row(theme, range.start + i, doc, &date, &act));
                }
            }
        }
    }
    let empty = body.is_empty();
    if empty {
        body.push(
            div()
                .px(px(16.0))
                .py(px(12.0))
                .text_size(typography::BODY.size)
                .text_color(colors.secondary_label)
                .child(chat_gallery::empty_text(sheet.tab, sheet.partial))
                .into_any_element(),
        );
    }
    if sheet.partial {
        body.push(
            crate::menu_item(
                theme,
                "gallery-older",
                Icon::LucideChevronUp,
                if loading {
                    "Loading…".into()
                } else {
                    "Load earlier".into()
                },
                colors.accent,
            )
            .on_click(act(GalleryAction::Older))
            .into_any_element(),
        );
    }

    let close = act(GalleryAction::Close);
    let dismiss = act(GalleryAction::Close);
    div()
        .id("gallery-sheet")
        .absolute()
        .top_0()
        .left_0()
        .size_full()
        .flex()
        .flex_col()
        .justify_end()
        .px(px(12.0))
        .pb(px(28.0))
        .bg(gpui::Hsla {
            a: 0.4,
            ..gpui::black()
        })
        .occlude()
        .on_click(dismiss)
        .child(
            glass(GlassSpec::control(theme))
                .occlude()
                .w_full()
                .rounded(px(26.0))
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .flex_col()
                .gap(px(8.0))
                .p(px(12.0))
                .child(
                    div()
                        .flex()
                        .items_center()
                        .justify_between()
                        .px(px(4.0))
                        .child(
                            div()
                                .truncate()
                                .text_size(typography::HEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child("Media, links and docs"),
                        )
                        .child(
                            div()
                                .id("gallery-close")
                                .flex_none()
                                .flex()
                                .items_center()
                                .justify_center()
                                .size(metrics::MIN_HIT)
                                .child(
                                    gpui::svg()
                                        .path(Icon::LucideX.path())
                                        .size(px(20.0))
                                        .text_color(colors.label),
                                )
                                .on_click(close),
                        ),
                )
                .child(tabs)
                .child(
                    div()
                        .id("gallery-scroll")
                        .h(px(460.0))
                        .overflow_y_scroll()
                        .flex()
                        .flex_col()
                        .children(body),
                ),
        )
        .into_any_element()
}

fn header(theme: Theme, label: String) -> AnyElement {
    div()
        .px(px(4.0))
        .pt(px(10.0))
        .pb(px(4.0))
        .text_size(typography::CAPTION.size)
        .font_weight(typography::HEADLINE.weight)
        .text_color(theme::colors(theme).secondary_label)
        .child(label)
        .into_any_element()
}

fn link_row(
    theme: Theme,
    index: usize,
    link: &LinkEntry,
    date: &impl Fn(i64) -> String,
    act: &impl Fn(GalleryAction) -> ClickFn,
) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .flex()
        .items_center()
        .gap(px(4.0))
        .child(
            div()
                .id(("gallery-link", index))
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .px(px(8.0))
                .py(px(6.0))
                .min_h(metrics::MIN_HIT)
                .rounded(px(14.0))
                .hover(|s| s.bg(colors.label.opacity(0.06)))
                .child(
                    div()
                        .truncate()
                        .text_size(typography::BODY.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .child(link.host.clone()),
                )
                .child(
                    div()
                        .truncate()
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.secondary_label)
                        .child(format!(
                            "{} · {}",
                            chat_gallery::short_url(&link.url, LINK_CHARS),
                            date(link.at_ms)
                        )),
                )
                .on_click(act(GalleryAction::Open(link.url.clone()))),
        )
        .child(
            div()
                .id(("gallery-link-jump", index))
                .flex_none()
                .flex()
                .items_center()
                .justify_center()
                .size(metrics::MIN_HIT)
                .child(
                    gpui::svg()
                        .path(Icon::LucideMessageCircle.path())
                        .size(px(20.0))
                        .text_color(colors.secondary_label),
                )
                .on_click(act(GalleryAction::Jump(link.id.clone()))),
        )
        .child(
            div()
                .id(("gallery-link-copy", index))
                .flex_none()
                .flex()
                .items_center()
                .justify_center()
                .size(metrics::MIN_HIT)
                .child(
                    gpui::svg()
                        .path(Icon::LucideCopy.path())
                        .size(px(20.0))
                        .text_color(colors.accent),
                )
                .on_click(act(GalleryAction::CopyLink(link.url.clone()))),
        )
        .into_any_element()
}

fn doc_row(
    theme: Theme,
    index: usize,
    doc: &DocEntry,
    date: &impl Fn(i64) -> String,
    act: &impl Fn(GalleryAction) -> ClickFn,
) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .id(("gallery-doc", index))
        .flex()
        .items_center()
        .gap(px(12.0))
        .px(px(8.0))
        .py(px(6.0))
        .min_h(metrics::MIN_HIT)
        .rounded(px(14.0))
        .hover(|s| s.bg(colors.label.opacity(0.06)))
        .child(
            div()
                .flex_none()
                .flex()
                .items_center()
                .justify_center()
                .size(px(40.0))
                .rounded(radius::CARD)
                .bg(colors.label.opacity(0.08))
                .child(
                    gpui::svg()
                        .path(doc_icon(&doc.detail).path())
                        .size(px(20.0))
                        .text_color(colors.secondary_label),
                ),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(
                    div()
                        .truncate()
                        .text_size(typography::BODY.size)
                        .text_color(colors.label)
                        .child(doc.name.clone()),
                )
                .child(
                    div()
                        .truncate()
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.secondary_label)
                        .child(format!("{} · {}", doc.detail, date(doc.at_ms))),
                ),
        )
        .on_click(act(GalleryAction::Jump(doc.id.clone())))
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn whatsapp_kinds_map_and_stickers_are_skipped() {
        assert_eq!(kind_of(MediaKind::Image), Some(GalleryKind::Image));
        assert_eq!(kind_of(MediaKind::Video), Some(GalleryKind::Video));
        assert_eq!(kind_of(MediaKind::Audio), Some(GalleryKind::Audio));
        assert_eq!(kind_of(MediaKind::Document), Some(GalleryKind::Doc));
        assert_eq!(kind_of(MediaKind::Sticker), None);
    }

    #[test]
    fn mime_kinds_skip_text_and_smil() {
        assert_eq!(kind_of_mime("image/JPEG "), Some(GalleryKind::Image));
        assert_eq!(kind_of_mime("video/mp4"), Some(GalleryKind::Video));
        assert_eq!(kind_of_mime("audio/amr"), Some(GalleryKind::Audio));
        assert_eq!(kind_of_mime("text/x-vcard"), Some(GalleryKind::Doc));
        assert_eq!(kind_of_mime("text/plain"), None);
        assert_eq!(kind_of_mime("application/smil"), None);
        assert_eq!(kind_of_mime(""), None);
    }

    #[test]
    fn base_name_takes_the_last_segment() {
        assert_eq!(base_name("/data/cache/mms/a.pdf"), "a.pdf");
        assert_eq!(base_name("a.pdf"), "a.pdf");
    }

    #[test]
    fn file_name_ignores_blank() {
        let mut m = MediaMeta::default();
        assert_eq!(file_name_of(Some(&m)), None);
        m.file_name = Some("  ".into());
        assert_eq!(file_name_of(Some(&m)), None);
        m.file_name = Some(" a.pdf ".into());
        assert_eq!(file_name_of(Some(&m)), Some("a.pdf"));
        assert_eq!(file_name_of(None), None);
    }
}
