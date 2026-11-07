//! The "View contact" sheet of a shared contact card, used by the WhatsApp and
//! SMS pages. The card comes from `rovsti_core::shared_card`; each page owns the
//! sheet and answers its `SheetAction`s.

use std::path::Path;

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement,
    StatefulInteractiveElement, Styled, div, px,
};
use rovsti_core::shared_card::{self, CardField, CardView};
use rovsti_ui::{
    components::glass_pill,
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, shadow, typography},
};

/// A tap inside the sheet.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum SheetAction {
    Close,
    Call(String),
    Message(String),
    Email(String),
    Copy(String),
    Add,
    SaveVcf,
    Share,
}

/// Writes the card as vCard text to `path`, creating `dir`.
pub(crate) fn write_vcf(dir: &Path, path: &Path, card: &CardView) -> Result<(), String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    std::fs::write(path, shared_card::to_vcard(card)).map_err(|e| e.to_string())
}

/// Reads a received vCard file (at most 64 KB) into a card.
pub(crate) fn read_vcf(path: &str) -> Option<CardView> {
    let len = std::fs::metadata(path).ok()?.len();
    if len > shared_card::MAX_VCARD_BYTES {
        return None;
    }
    let text = String::from_utf8_lossy(&std::fs::read(path).ok()?).into_owned();
    let card = shared_card::from_vcard("", &text);
    (!card.name.is_empty() || !card.numbers.is_empty() || !card.emails.is_empty()).then_some(card)
}

fn field_row<T: 'static>(
    theme: Theme,
    id: &'static str,
    index: usize,
    field: &CardField,
    pills: Vec<(&'static str, SheetAction)>,
    on: fn(&mut T, SheetAction, &mut Context<T>),
    cx: &mut Context<T>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let mut buttons = div().flex().flex_wrap().gap(px(6.0));
    for (n, (label, action)) in pills.into_iter().enumerate() {
        buttons = buttons.child(
            glass_pill(theme, label)
                .id((id, index * 4 + n))
                .on_click(cx.listener(move |this, _, _, cx| on(this, action.clone(), cx))),
        );
    }
    div()
        .flex()
        .flex_col()
        .gap(px(4.0))
        .py(px(4.0))
        .child(
            div()
                .flex()
                .flex_col()
                .child(
                    div()
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.secondary_label)
                        .child(field.label.clone()),
                )
                .child(
                    div()
                        .text_size(typography::BODY.size)
                        .text_color(colors.label)
                        .child(field.value.clone()),
                ),
        )
        .child(buttons)
        .into_any_element()
}

/// The sheet over the page: a dimmed screen and a glass card.
pub(crate) fn card_overlay<T: 'static>(
    theme: Theme,
    card: &CardView,
    cx: &mut Context<T>,
    on: fn(&mut T, SheetAction, &mut Context<T>),
) -> AnyElement {
    let colors = theme::colors(theme);
    let mut body = div()
        .id("card-scroll")
        .min_h_0()
        .overflow_y_scroll()
        .flex()
        .flex_col()
        .child(
            div()
                .text_size(typography::TITLE3.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(colors.label)
                .child(card.name.clone()),
        );
    if !card.org.is_empty() {
        body = body.child(
            div()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child(card.org.clone()),
        );
    }
    for (i, field) in card.numbers.iter().enumerate() {
        let v = field.value.clone();
        body = body.child(field_row(
            theme,
            "card-num",
            i,
            field,
            vec![
                ("Call", SheetAction::Call(v.clone())),
                ("Message", SheetAction::Message(v.clone())),
                ("Copy", SheetAction::Copy(v)),
            ],
            on,
            cx,
        ));
    }
    for (i, field) in card.emails.iter().enumerate() {
        let v = field.value.clone();
        body = body.child(field_row(
            theme,
            "card-mail",
            i,
            field,
            vec![
                ("Email", SheetAction::Email(v.clone())),
                ("Copy", SheetAction::Copy(v)),
            ],
            on,
            cx,
        ));
    }
    if !card.note.is_empty() {
        body = body.child(
            div()
                .pt(px(6.0))
                .text_size(typography::BODY.size)
                .text_color(colors.secondary_label)
                .child(card.note.clone()),
        );
    }
    let footer = [
        ("Add to contacts", SheetAction::Add),
        ("Save .vcf", SheetAction::SaveVcf),
        ("Share", SheetAction::Share),
        ("Close", SheetAction::Close),
    ];
    let mut pills = div().flex().flex_wrap().gap(px(6.0)).pt(px(8.0));
    for (n, (label, action)) in footer.into_iter().enumerate() {
        pills = pills.child(
            glass_pill(theme, label)
                .id(("card-foot", n))
                .on_click(cx.listener(move |this, _, _, cx| on(this, action.clone(), cx))),
        );
    }
    let card_box = glass(GlassSpec::control(theme))
        .occlude()
        .w_full()
        .max_h(px(560.0))
        .rounded(px(26.0))
        .shadow(shadow::glass(theme.is_dark()))
        .flex()
        .flex_col()
        .p(px(16.0))
        .child(body)
        .child(pills);
    div()
        .id("card-sheet")
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
        .on_click(cx.listener(move |this, _, _, cx| on(this, SheetAction::Close, cx)))
        .child(card_box)
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_a_small_vcf_and_refuses_junk() {
        let dir = std::env::temp_dir().join(format!("rovsti-vcf-{}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("test io");
        let ok = dir.join("a.vcf");
        std::fs::write(
            &ok,
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nN:;Ada;;;\r\nTEL:+905551112233\r\nEND:VCARD\r\n",
        )
        .expect("test io");
        let card = read_vcf(&ok.to_string_lossy()).expect("card");
        assert_eq!(card.name, "Ada");
        assert_eq!(card.numbers.len(), 1);
        let junk = dir.join("b.vcf");
        std::fs::write(&junk, "hello").expect("test io");
        assert!(read_vcf(&junk.to_string_lossy()).is_none());
        let big = dir.join("c.vcf");
        std::fs::write(&big, vec![b'x'; 70_000]).expect("test io");
        assert!(read_vcf(&big.to_string_lossy()).is_none());
        assert!(read_vcf("/no/such/file").is_none());
        let out = dir.join("out.vcf");
        write_vcf(&dir, &out, &card).expect("test io");
        assert!(
            std::fs::read_to_string(out)
                .expect("test io")
                .contains("BEGIN:VCARD")
        );
        std::fs::remove_dir_all(dir).ok();
    }
}
