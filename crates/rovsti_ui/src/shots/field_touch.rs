//! `02-field-touch-{dark,light}`: the touch states of a text field, one per
//! row: the insertion handle after a tap, a selected word with its two
//! handles and the edit menu, a long-press on an empty field (Paste only) and
//! a secret field (no Cut/Copy).
//!
//! Only one element holds focus, so each row is cut from the frame where it
//! was focused, like the `fields` gallery.
//!
//! Run: `cargo test -p rovsti_ui --features shots field_touch -- --ignored --nocapture`.

use std::ops::Range;

use gpui::{
    AppContext as _, ClipboardItem, Context, Entity, IntoElement, ParentElement as _, Render,
    Styled as _, TextInputAction, TextInputPurpose, Window, div, px,
};
use image::RgbaImage;
use liquid_glass_kit::tokens::Theme;

use super::{PHONE, Rig, SCALE, ShotSpec, isolate_data_dir};
use crate::{
    components,
    field::{TextField, field_box, secret_field_box},
    theme,
};

const TEXT: &str = "Yarın Şişli'deki İpek Güneş ile çarşıda buluşalım";

#[derive(Clone, Copy)]
enum Scene {
    /// A tap before the word `needle`: caret there, insertion handle under it.
    Tap(&'static str, &'static str),
    /// A selection of the word `needle` with the menu open.
    Selected(&'static str, &'static str),
    /// Long-press on an empty field.
    EmptyMenu,
    /// A secret field, all selected, menu open.
    Secret(&'static str),
}

const SCENES: [(&str, Scene); 4] = [
    ("1. Tap: insertion handle", Scene::Tap(TEXT, "İpek")),
    (
        "2. Long-press: word, handles, menu",
        Scene::Selected(TEXT, "Güneş"),
    ),
    ("3. Empty field: Paste only", Scene::EmptyMenu),
    (
        "4. Secret: no Cut or Copy",
        Scene::Secret("parola-ŞİFRE-12"),
    ),
];

/// Top of the first row, dp.
const TOP: f32 = 88.0;
/// One row: caption, room for the menu, the box, room for the handles.
const ROW_H: f32 = 150.0;

fn band(i: usize) -> Range<u32> {
    let top = (TOP + ROW_H * i as f32) * SCALE as f32;
    top as u32..(top + ROW_H * SCALE as f32) as u32
}

struct Page {
    fields: Vec<Entity<TextField>>,
}

impl Render for Page {
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
                    .child("Dokunma durumları"),
            );
        for (i, ((caption, scene), field)) in SCENES.iter().zip(&self.fields).enumerate() {
            let id = ("touch-field", i);
            let boxed = if matches!(scene, Scene::Secret(_)) {
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
                            .h(px(22.0))
                            .text_size(px(13.0))
                            .text_color(colors.label.opacity(0.7))
                            .child(*caption),
                    )
                    .child(div().h(px(56.0)))
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

fn open(theme: Theme) -> Rig<Page> {
    let spec = ShotSpec {
        app: "rovsti_ui",
        nn: 2,
        screen: "field-touch",
        theme,
        size: PHONE,
    };
    let mut rig = Rig::open(spec, |_, cx| {
        cx.write_to_clipboard(ClipboardItem::new_string("panodaki metin".to_owned()));
        let fields = SCENES
            .iter()
            .map(|(_, scene)| {
                let field = cx.new(|cx| {
                    TextField::with_input(TextInputPurpose::Text, TextInputAction::Done, cx)
                });
                field.update(cx, |f, cx| match *scene {
                    Scene::Tap(t, needle) => {
                        f.set(t, cx);
                        let at = t.find(needle).expect("the word is in the text");
                        f.set_selection(at, at, cx);
                        f.show_insertion_handle(cx);
                    }
                    Scene::Selected(t, needle) => {
                        f.set(t, cx);
                        let start = t.find(needle).expect("the word is in the text");
                        f.set_selection(start, start + needle.len(), cx);
                        f.open_edit_menu();
                    }
                    Scene::EmptyMenu => f.open_edit_menu(),
                    Scene::Secret(t) => {
                        f.set(t, cx);
                        f.set_secret(true);
                        f.select_all(cx);
                        f.open_edit_menu();
                    }
                });
                field
            })
            .collect();
        cx.new(|_| Page { fields })
    });
    rig.theme();
    rig
}

/// Run: `cargo test -p rovsti_ui --features shots field_touch -- --ignored --nocapture`
/// Output: `target/shots/rovsti_ui/02-field-touch-{dark,light}.png`.
#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn field_touch() {
    isolate_data_dir("rovsti_ui");
    for theme in [Theme::Dark, Theme::Light] {
        let mut rig = open(theme);
        let mut out: Option<RgbaImage> = None;
        for row in 0..SCENES.len() {
            let handle = rig
                .root
                .read_with(&rig.cx, |p, cx| p.fields[row].read(cx).focus_handle());
            rig.update(|cx, window| window.focus(&handle, cx));
            rig.settle();
            let frame = rig.capture();
            let out = out.get_or_insert_with(|| frame.clone());
            for y in band(row) {
                for x in 0..out.width().min(frame.width()) {
                    if y < frame.height() && y < out.height() {
                        out.put_pixel(x, y, *frame.get_pixel(x, y));
                    }
                }
            }
        }
        let img = out.expect("at least one scene");
        let path = rig.save_image(&img);
        println!("{}", path.display());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rows_fit_the_page() {
        assert!(band(SCENES.len() - 1).end <= (PHONE.height as u32) * SCALE);
        assert!(band(0).end <= band(1).start);
    }
}
