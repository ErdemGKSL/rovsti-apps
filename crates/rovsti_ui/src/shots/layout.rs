//! `03-layout-{dark,light}`: the page-level building blocks on one 412x915
//! page: headers, a section header, the list-row variants (unread, muted and
//! pinned, long title, OTP chip, avatar stack, merged SMS+WhatsApp contact)
//! and an empty state.
//!
//! Thin guides mark the 16 dp margins, so alignment can be judged by eye.
//!
//! Run: `cargo test -p rovsti_ui --features shots layout -- --ignored --nocapture`.

use gpui::{
    AppContext as _, Context, IntoElement, ParentElement as _, Render, Styled as _, Window, div, px,
};
use liquid_glass_kit::tokens::{Theme, typography};

use super::{PHONE, Rig, ShotSpec, isolate_data_dir};
use crate::{
    Icon,
    chips::{ChipSpec, filter_chip_row},
    components,
    layout::{self, MARGIN, geom, space},
    page::{HeaderAction, PageHeader, empty_state, section_header},
    row::{BadgeTone, Inset, ListRow, SourceKind, StackItem, avatar_stack, with_source_badges},
    theme,
};

struct Gallery;

impl Render for Gallery {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let theme = crate::current_theme(window);
        let colors = theme::colors(theme);
        let guide = colors.label.opacity(0.18);
        let page = div()
            .size_full()
            .flex()
            .flex_col()
            .child(
                PageHeader::new("Messages")
                    .subtitle("3 unread")
                    .action(HeaderAction::new("g-search", Icon::LucideSearch, "Search"))
                    .action(HeaderAction::new("g-new", Icon::LucidePlus, "New chat").badge(true))
                    .render(window, theme),
            )
            .child(filter_chip_row(
                "g-chips",
                theme,
                &[
                    ChipSpec::new("All", None),
                    ChipSpec::new("Unread", Some("12".into())),
                    ChipSpec::new("WhatsApp", Some("230".into())),
                    ChipSpec::new("SMS", Some("277".into())),
                    ChipSpec::new("Groups", Some("999+".into())),
                    ChipSpec::new("Archived", Some("4".into())),
                ],
                1,
                |_, _, _| {},
            ))
            .child(section_header(theme, "Recent"))
            .child(
                ListRow::new("Ayşe Yılmaz")
                    .leading(with_source_badges(
                        theme,
                        components::avatar(theme, "AY", 48.0, colors.accent),
                        &[SourceKind::WhatsApp],
                    ))
                    .meta("09:41")
                    .unread(true)
                    .subtitle("See you at the station, bring the tickets")
                    .trailing_badge("3", BadgeTone::Accent)
                    .render(theme),
            )
            .child(
                ListRow::new("Family")
                    .leading(with_source_badges(
                        theme,
                        components::avatar(theme, "F", 48.0, colors.orange),
                        &[SourceKind::WhatsApp],
                    ))
                    .title_accessory(Icon::LucideBellOff)
                    .title_accessory(Icon::LucidePin)
                    .meta("Yesterday")
                    .subtitle_tick(Icon::LucideCheckCheck, true)
                    .subtitle("Mom: Dinner is at eight")
                    .trailing_badge("128", BadgeTone::Quiet)
                    .render(theme),
            )
            .child(
                ListRow::new("Dr. Alexandra Konstantinopoulou-Hernandez, Cardiology")
                    .leading(with_source_badges(
                        theme,
                        components::avatar(theme, "AK", 48.0, colors.green),
                        &[SourceKind::Sms],
                    ))
                    .meta("Mon")
                    .subtitle("Your appointment is confirmed for Thursday at 14:30")
                    .render(theme),
            )
            .child(
                ListRow::new("Garanti BBVA")
                    .leading(with_source_badges(
                        theme,
                        components::avatar(theme, "GB", 48.0, colors.secondary_label),
                        &[SourceKind::Sms],
                    ))
                    .meta("08:12")
                    .unread(true)
                    .subtitle("Your verification code is 482913")
                    .trailing_badge("1", BadgeTone::Accent)
                    .below(
                        div()
                            .h(px(32.0))
                            .px(px(space::M))
                            .rounded_full()
                            .bg(colors.fill)
                            .flex()
                            .items_center()
                            .self_start()
                            .text_size(typography::FOOTNOTE.size)
                            .font_weight(gpui::FontWeight(600.0))
                            .text_color(colors.label)
                            .child("Copy 482913"),
                    )
                    .render(theme),
            )
            .child(
                ListRow::new("Status")
                    .leading(avatar_stack(
                        theme,
                        &[
                            StackItem::new("AY", colors.accent),
                            StackItem::new("MK", colors.green),
                            StackItem::new("EB", colors.orange),
                            StackItem::new("SD", colors.red),
                            StackItem::new("OK", colors.accent),
                        ],
                        layout::STACK_AVATAR,
                        layout::STACK_OVERLAP,
                    ))
                    .leading_width(geom::avatar_stack_width(
                        geom::stack_discs(5, layout::STACK_MAX),
                        layout::STACK_AVATAR,
                        layout::STACK_OVERLAP,
                    ))
                    .subtitle("3 new updates")
                    .trailing_badge("3", BadgeTone::Accent)
                    .render(theme),
            )
            .child(
                ListRow::new("Mehmet Kaya")
                    .leading(with_source_badges(
                        theme,
                        components::avatar(theme, "MK", 48.0, colors.green),
                        &[SourceKind::Sms, SourceKind::WhatsApp],
                    ))
                    .meta("Sat")
                    .subtitle("Thanks!")
                    .separator(Inset::None)
                    .render(theme),
            )
            .child(empty_state(
                theme,
                Some(Icon::LucideMessageSquare),
                "No messages yet",
                "Start a chat and it will show up here.",
                Some(components::glass_pill(theme, "New message").into_any_element()),
            ));
        div()
            .size_full()
            .relative()
            .child(components::backdrop(theme, theme::accent_glow(theme)))
            .child(page)
            .child(
                div()
                    .absolute()
                    .top_0()
                    .bottom_0()
                    .left(px(MARGIN))
                    .w(px(0.5))
                    .bg(guide),
            )
            .child(
                div()
                    .absolute()
                    .top_0()
                    .bottom_0()
                    .right(px(MARGIN))
                    .w(px(0.5))
                    .bg(guide),
            )
    }
}

/// Run: `cargo test -p rovsti_ui --features shots layout -- --ignored --nocapture`
/// Output: `target/shots/rovsti_ui/03-layout-{dark,light}.png`.
#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn layout() {
    isolate_data_dir("rovsti_ui");
    for theme in [Theme::Dark, Theme::Light] {
        let spec = ShotSpec {
            app: "rovsti_ui",
            nn: 3,
            screen: "layout",
            theme,
            size: PHONE,
        };
        let mut rig = Rig::open(spec, |_, cx| cx.new(|_| Gallery));
        rig.theme();
        rig.settle();
        println!("{}", rig.save().display());
    }
}
