//! `01-fields-{dark,light}`: every text-field state on one 412x915 page.
//!
//! GPUI has one focused element per window, so the focused rows cannot all be
//! live at once. The page is drawn once per focused row, and each row's band is
//! cut from the frame where that row had focus ([`compose`]).
//!
//! Rows also show the touch mechanics: caret mid-text with its insertion
//! handle, a selected word with handles, the edit menu, a three-line field.
//! The window is as tall as the rows need.
//!
//! Run: `cargo test -p rovsti_ui --features shots fields -- --ignored --nocapture`.

use std::ops::Range;

use gpui::{
    AppContext as _, ClipboardItem, Context, Entity, EntityInputHandler as _, IntoElement,
    ParentElement as _, Render, Size, Styled as _, TextInputAction, TextInputPurpose, Window, div,
    point, px,
};
use image::RgbaImage;
use liquid_glass_kit::tokens::Theme;

use super::{PHONE, Rig, SCALE, ShotSpec, isolate_data_dir};
use crate::{
    components,
    field::{TextField, field_box, secret_field_box},
    theme,
};

const TURKISH_URL: &str = "https://örnek.com.tr/çok/uzun/bir/adres?ş=1&ğ=İstanbul&ü=ÖĞRENCİ";
const LONG_TEXT: &str =
    "Şişli'deki İpek Güneş ile öğleden sonra buluşup çarşıda alışveriş yapacağız mı?";

#[derive(Clone, Copy, Debug, PartialEq)]
enum Fill {
    Empty,
    Text(&'static str),
    /// Committed text followed by composing (marked) text.
    Composing(&'static str, &'static str),
}

/// What a row shows beyond its text. The needles are found in the row's text.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Extra {
    None,
    /// Caret right after the needle, with the insertion handle.
    CaretAfter(&'static str),
    /// The needle selected, with both handles.
    Select(&'static str),
    /// The needle selected and the edit menu open.
    Menu(&'static str),
    /// Nothing typed, the edit menu open (Paste only).
    PasteMenu,
    /// Panned with a finger swipe to the start of the text, field still focused.
    PannedToStart,
}

#[derive(Clone, Copy, Debug)]
struct State {
    caption: &'static str,
    fill: Fill,
    focused: bool,
    secret: bool,
    /// A three-line field (`TextField::multiline`).
    multiline: bool,
    purpose: TextInputPurpose,
    extra: Extra,
    /// `TextField::set_max_chars`.
    max_chars: Option<usize>,
}

const fn state(caption: &'static str, fill: Fill, focused: bool) -> State {
    State {
        caption,
        fill,
        focused,
        secret: false,
        multiline: false,
        purpose: TextInputPurpose::Text,
        extra: Extra::None,
        max_chars: None,
    }
}

const THREE_LINES: &str = "Selam!\nYarın Kadıköy'de buluşalım mı?\nSaat 19.30 olur.";

/// Exactly 20 characters (not bytes).
const FULL_TEXT: &str = "İpek'le Şişli'de ara";

const STATES: [State; 15] = [
    state("Placeholder (unfocused, empty)", Fill::Empty, false),
    state("Focused, empty", Fill::Empty, true),
    state("Focused, short text", Fill::Text("Merhaba"), true),
    State {
        extra: Extra::CaretAfter("Güneş"),
        ..state(
            "Caret mid-text, insertion handle",
            Fill::Text(LONG_TEXT),
            true,
        )
    },
    State {
        extra: Extra::Select("İpek"),
        ..state("Selected word with handles", Fill::Text(LONG_TEXT), true)
    },
    State {
        extra: Extra::Menu("Güneş"),
        ..state("Edit menu open", Fill::Text(LONG_TEXT), true)
    },
    state("Focused, long Turkish URL", Fill::Text(TURKISH_URL), true),
    state("Unfocused, long text", Fill::Text(LONG_TEXT), false),
    state(
        "Composing (marked) text",
        Fill::Composing("Merhaba ", "kar"),
        true,
    ),
    State {
        multiline: true,
        ..state("Multiline, 3 lines", Fill::Text(THREE_LINES), true)
    },
    State {
        secret: true,
        ..state(
            "Secret (Password mode)",
            Fill::Text("parola-ŞİFRE-12"),
            true,
        )
    },
    State {
        purpose: TextInputPurpose::Phone,
        ..state("Phone purpose", Fill::Text("+90 532 123 45 67"), false)
    },
    State {
        extra: Extra::PasteMenu,
        ..state("Empty, Paste-only menu", Fill::Empty, true)
    },
    State {
        extra: Extra::PannedToStart,
        ..state(
            "Long URL panned to the start while focused",
            Fill::Text(TURKISH_URL),
            true,
        )
    },
    State {
        extra: Extra::CaretAfter("İpek'le "),
        max_chars: Some(20),
        ..state(
            "At max length (20), caret mid-text (20/20)",
            Fill::Text(FULL_TEXT),
            true,
        )
    },
];

/// Byte range of `needle` in `text` (the whole text when missing).
fn find(text: &str, needle: &str) -> Range<usize> {
    text.find(needle)
        .map_or(0..text.len(), |at| at..at + needle.len())
}

/// The text a row starts with.
fn text_of(fill: Fill) -> String {
    match fill {
        Fill::Empty => String::new(),
        Fill::Text(t) => t.to_owned(),
        Fill::Composing(done, marked) => format!("{done}{marked}"),
    }
}

/// The selection a row starts with, as `(anchor, head)` bytes.
fn selection_of(s: &State) -> Option<(usize, usize)> {
    let text = text_of(s.fill);
    match s.extra {
        Extra::CaretAfter(n) => {
            let end = find(&text, n).end;
            Some((end, end))
        }
        Extra::Select(n) | Extra::Menu(n) => {
            let r = find(&text, n);
            Some((r.start, r.end))
        }
        Extra::PasteMenu | Extra::PannedToStart | Extra::None => None,
    }
}

// ---- Pure layout --------------------------------------------------------------

/// Top of the first row, dp.
const TOP: f32 = 88.0;
const CAPTION_H: f32 = 22.0;
const BOX_H: f32 = 44.0;
/// Space under every row, dp.
const GAP: f32 = 14.0;
/// Room for the edit menu between the caption and the box, dp.
const MENU_ROOM: f32 = 52.0;
/// Room under the box for the handles, dp.
const HANDLE_ROOM: f32 = 30.0;
const BOTTOM: f32 = 16.0;

/// Space between the caption and the box.
fn room_above(s: &State) -> f32 {
    match s.extra {
        Extra::Menu(_) | Extra::PasteMenu => MENU_ROOM,
        _ => 0.0,
    }
}

fn box_h(s: &State) -> f32 {
    if s.multiline {
        rovsti_core::text_view::box_height(3)
    } else {
        BOX_H
    }
}

/// Space under the box.
fn room_below(s: &State) -> f32 {
    match s.extra {
        Extra::CaretAfter(_) | Extra::Select(_) | Extra::Menu(_) => HANDLE_ROOM,
        _ => 0.0,
    }
}

/// Height of the content of one row (no trailing gap), dp.
fn content_h(s: &State) -> f32 {
    CAPTION_H + room_above(s) + box_h(s) + room_below(s)
}

/// `(top, height)` of each row's content, dp.
fn rows(states: &[State]) -> Vec<(f32, f32)> {
    let mut top = TOP;
    states
        .iter()
        .map(|s| {
            let row = (top, content_h(s));
            top += row.1 + GAP;
            row
        })
        .collect()
}

/// Window height that fits every row, dp.
fn page_height(states: &[State]) -> f32 {
    rows(states)
        .last()
        .map_or(TOP, |(top, h)| top + h + BOTTOM)
        .ceil()
}

/// The pixel rows covered by row `i` (caption, menu room, box, handles).
fn band(i: usize) -> Range<u32> {
    let (top, h) = rows(&STATES)[i];
    let top = top * SCALE as f32;
    top as u32..(top + h * SCALE as f32) as u32
}

/// Indices of the rows that need keyboard focus.
fn focused_rows(states: &[State]) -> Vec<usize> {
    states
        .iter()
        .enumerate()
        .filter(|(_, s)| s.focused)
        .map(|(i, _)| i)
        .collect()
}

/// `base` with each row's band replaced from the frame taken while that row
/// was focused.
fn compose(base: &RgbaImage, frames: &[(usize, RgbaImage)]) -> RgbaImage {
    let mut out = base.clone();
    for (row, frame) in frames {
        for y in band(*row) {
            for x in 0..out.width().min(frame.width()) {
                if y < frame.height() && y < out.height() {
                    out.put_pixel(x, y, *frame.get_pixel(x, y));
                }
            }
        }
    }
    out
}

// ---- The page -------------------------------------------------------------------

struct Gallery {
    fields: Vec<Entity<TextField>>,
}

impl Render for Gallery {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = crate::current_theme(window);
        let colors = theme::colors(theme);
        let mut page = div()
            .absolute()
            .top(px(0.0))
            .left(px(16.0))
            .right(px(16.0))
            .child(
                div()
                    .h(px(TOP))
                    .flex()
                    .items_end()
                    .pb(px(16.0))
                    .text_color(colors.label)
                    .text_size(px(24.0))
                    .child("Metin kutuları"),
            );
        for (i, (state, field)) in STATES.iter().zip(&self.fields).enumerate() {
            let id = ("gallery-field", i);
            let boxed = if state.secret {
                secret_field_box(theme, id, field, "Parola", window, cx).into_any_element()
            } else {
                field_box(theme, id, field, "Ara veya adres yaz", window, cx).into_any_element()
            };
            page = page.child(
                div()
                    .h(px(content_h(state) + GAP))
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .h(px(CAPTION_H))
                            .text_size(px(13.0))
                            .text_color(colors.label.opacity(0.7))
                            .child(format!("{}. {}", i + 1, state.caption)),
                    )
                    .child(div().h(px(room_above(state))))
                    .child(boxed),
            );
        }
        div()
            .size_full()
            .relative()
            .child(components::backdrop(theme, theme::accent_glow(theme)))
            .child(page)
    }
}

fn open(theme: Theme) -> Rig<Gallery> {
    let spec = ShotSpec {
        app: "rovsti_ui",
        nn: 1,
        screen: "fields",
        theme,
        size: Size {
            width: PHONE.width,
            height: page_height(&STATES),
        },
    };
    let mut rig = Rig::open(spec, |window, cx| {
        // The menus offer Paste only with something on the clipboard.
        cx.write_to_clipboard(ClipboardItem::new_string("Panodaki metin".into()));
        let fields = STATES
            .iter()
            .map(|s| {
                let purpose = s.purpose;
                let field = cx.new(|cx| {
                    if s.multiline {
                        TextField::multiline(3, 6, cx)
                    } else {
                        TextField::with_input(purpose, TextInputAction::Done, cx)
                    }
                });
                field.update(cx, |f, cx| {
                    match s.fill {
                        Fill::Empty => {}
                        Fill::Text(t) => f.set(t, cx),
                        Fill::Composing(done, marked) => {
                            f.set(done, cx);
                            f.replace_and_mark_text_in_range(None, marked, None, window, cx);
                        }
                    }
                    if let Some((anchor, head)) = selection_of(s) {
                        f.set_selection(anchor, head, cx);
                    }
                    if s.max_chars.is_some() {
                        f.set_max_chars(s.max_chars);
                    }
                    if matches!(s.extra, Extra::Menu(_) | Extra::PasteMenu) {
                        f.open_edit_menu();
                    }
                });
                field
            })
            .collect();
        cx.new(|_| Gallery { fields })
    });
    rig.theme();
    rig
}

/// Focuses `row`, or blurs the window for `None`, and settles.
fn focus_row(rig: &mut Rig<Gallery>, row: Option<usize>) {
    let handle = row.map(|r| {
        rig.root
            .read_with(&rig.cx, |g, cx| g.fields[r].read(cx).focus_handle())
    });
    rig.update(|cx, window| match &handle {
        Some(h) => window.focus(h, cx),
        None => window.blur(),
    });
    rig.settle();
}

/// Run: `cargo test -p rovsti_ui --features shots fields -- --ignored --nocapture`
/// Output: `target/shots/rovsti_ui/01-fields-{dark,light}.png`.
#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn fields() {
    isolate_data_dir("rovsti_ui");
    for theme in [Theme::Dark, Theme::Light] {
        let mut rig = open(theme);
        focus_row(&mut rig, None);
        let base = rig.capture();
        let mut frames = Vec::new();
        for row in focused_rows(&STATES) {
            focus_row(&mut rig, Some(row));
            if matches!(STATES[row].extra, Extra::CaretAfter(_)) {
                // The handle lasts 3 s of wall time: show it right before the frame.
                let field = rig.root.read_with(&rig.cx, |g, _| g.fields[row].clone());
                rig.update(|cx, _| field.update(cx, |f, cx| f.show_insertion_handle(cx)));
            }
            if matches!(STATES[row].extra, Extra::PannedToStart) {
                // A swipe to the right far past the start, like a finger pan.
                let field = rig.root.read_with(&rig.cx, |g, _| g.fields[row].clone());
                rig.update(|cx, window| {
                    field.update(cx, |f, cx| {
                        f.pan_by(point(px(10_000.0), px(0.0)), window, cx)
                    });
                });
                rig.settle();
            }
            frames.push((row, rig.capture()));
        }
        let img = compose(&base, &frames);
        assert_eq!(img.dimensions(), (824, page_height(&STATES) as u32 * SCALE));
        let path = rig.save_image(&img);
        println!("{}", path.display());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bands_do_not_overlap_and_fit() {
        for i in 0..STATES.len() - 1 {
            assert!(band(i).end <= band(i + 1).start);
        }
        let last = band(STATES.len() - 1);
        assert!(last.end <= (page_height(&STATES) as u32) * SCALE);
    }

    #[test]
    fn menu_rows_have_room_for_the_menu_and_handles() {
        let menu = &STATES[5];
        assert!(room_above(menu) >= rovsti_core::text_touch::MENU_H);
        assert!(content_h(menu) > content_h(&STATES[0]));
        assert!(room_below(&STATES[4]) >= rovsti_core::text_touch::HANDLE_SIZE);
        assert_eq!(room_below(&STATES[0]), 0.0);
    }

    #[test]
    fn needles_resolve_to_whole_words() {
        for s in &STATES {
            let text = text_of(s.fill);
            if let Some((a, b)) = selection_of(s) {
                assert!(text.is_char_boundary(a) && text.is_char_boundary(b));
                if let Extra::Select(n) | Extra::Menu(n) = s.extra {
                    assert_eq!(&text[a..b], n);
                }
            }
        }
        assert_eq!(find("abc", "zzz"), 0..3);
        assert_eq!(find("aşb", "b"), 3..4);
    }

    #[test]
    fn full_row_is_exactly_at_the_limit_with_caret_mid_text() {
        let s = &STATES[14];
        let text = text_of(s.fill);
        assert_eq!(Some(text.chars().count()), s.max_chars);
        let (a, b) = selection_of(s).expect("caret row");
        assert_eq!(a, b);
        assert_eq!(text[..a].chars().count(), 8);
    }

    #[test]
    fn which_rows_need_focus() {
        assert_eq!(
            focused_rows(&STATES),
            vec![1, 2, 3, 4, 5, 6, 8, 9, 10, 12, 13, 14]
        );
        assert!(!STATES[0].focused);
    }

    #[test]
    fn compose_takes_only_the_band() {
        let base = RgbaImage::from_pixel(10, 1830, image::Rgba([1, 1, 1, 255]));
        let other = RgbaImage::from_pixel(10, 1830, image::Rgba([9, 9, 9, 255]));
        let out = compose(&base, &[(1, other)]);
        let b = band(1);
        assert_eq!(out.get_pixel(0, b.start).0[0], 9);
        assert_eq!(out.get_pixel(0, b.end - 1).0[0], 9);
        assert_eq!(out.get_pixel(0, b.end).0[0], 1);
        assert_eq!(out.get_pixel(0, b.start - 1).0[0], 1);
    }
}
