//! `01-fields-{dark,light}`: every text-field state on one 412x915 page.
//!
//! GPUI has one focused element per window, so the focused rows cannot all be
//! live at once. The page is drawn once per focused row, and each row's band is
//! cut from the frame where that row had focus ([`compose`]).
//!
//! The text-field mechanics unit adds caret-mid-text and selection rows to
//! [`STATES`]; nothing else here needs to change.
//!
//! Run: `cargo test -p rovsti_ui --features shots fields -- --ignored --nocapture`.

use std::ops::Range;

use gpui::{
    AppContext as _, Context, Entity, EntityInputHandler as _, IntoElement, ParentElement as _,
    Render, Styled as _, TextInputAction, TextInputPurpose, Window, div, px,
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

#[derive(Clone, Copy, Debug)]
struct State {
    caption: &'static str,
    fill: Fill,
    focused: bool,
    secret: bool,
    purpose: TextInputPurpose,
}

const fn state(caption: &'static str, fill: Fill, focused: bool) -> State {
    State {
        caption,
        fill,
        focused,
        secret: false,
        purpose: TextInputPurpose::Text,
    }
}

const STATES: [State; 8] = [
    state("Placeholder (unfocused, empty)", Fill::Empty, false),
    state("Focused, empty", Fill::Empty, true),
    state("Focused, short text", Fill::Text("Merhaba"), true),
    state("Focused, long Turkish URL", Fill::Text(TURKISH_URL), true),
    state("Unfocused, long text", Fill::Text(LONG_TEXT), false),
    state(
        "Composing (marked) text",
        Fill::Composing("Merhaba ", "kar"),
        true,
    ),
    State {
        secret: true,
        ..state("Secret (passphrase)", Fill::Text("parola-ŞİFRE-12"), false)
    },
    State {
        purpose: TextInputPurpose::Phone,
        ..state("Phone purpose", Fill::Text("+90 532 123 45 67"), false)
    },
];

// ---- Pure layout --------------------------------------------------------------

/// Top of the first row, dp.
const TOP: f32 = 88.0;
/// One row (caption, gap, box, gap), dp.
const ROW_H: f32 = 92.0;
const CAPTION_H: f32 = 22.0;
const BOX_H: f32 = 44.0;

/// The pixel rows covered by row `i` (caption and box).
fn band(i: usize) -> Range<u32> {
    let top = (TOP + ROW_H * i as f32) * SCALE as f32;
    let h = (CAPTION_H + BOX_H) * SCALE as f32;
    top as u32..(top + h) as u32
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
                    .h(px(ROW_H))
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .h(px(CAPTION_H))
                            .text_size(px(13.0))
                            .text_color(colors.label.opacity(0.7))
                            .child(format!("{}. {}", i + 1, state.caption)),
                    )
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
        size: PHONE,
    };
    let mut rig = Rig::open(spec, |window, cx| {
        let fields = STATES
            .iter()
            .map(|s| {
                let purpose = s.purpose;
                let field = cx.new(|cx| TextField::with_input(purpose, TextInputAction::Done, cx));
                field.update(cx, |f, cx| match s.fill {
                    Fill::Empty => {}
                    Fill::Text(t) => f.set(t, cx),
                    Fill::Composing(done, marked) => {
                        f.set(done, cx);
                        f.replace_and_mark_text_in_range(None, marked, None, window, cx);
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
            frames.push((row, rig.capture()));
        }
        let img = compose(&base, &frames);
        assert_eq!(img.dimensions(), (824, 1830));
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
        assert!(last.end <= (PHONE.height as u32) * SCALE);
    }

    #[test]
    fn which_rows_need_focus() {
        assert_eq!(focused_rows(&STATES), vec![1, 2, 3, 5]);
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
