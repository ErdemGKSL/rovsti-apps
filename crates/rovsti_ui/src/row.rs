//! List row building blocks: [`ListRow`], [`avatar_stack`] and the avatar
//! source badge. Sizes come from [`crate::layout`]; the pure geometry
//! (stack width, separator start, badge width) is tested there.
//!
//! Anatomy (dp, 16 margin, 48 avatar, 12 gap):
//!
//! ```text
//! | 16 | avatar 48 | 12 | title  [icons]            meta | 16 |
//! |    |           |    | [tick] subtitle          badge |    |
//! |    |           |    | below (indented to the text)   |    |
//!                        '-- separator starts here (76) --'
//! ```
//!
//! Meta and badge are right-aligned to the margin, so the time and badge
//! columns line up across rows whatever the title length.

use gpui::{
    AnyElement, Div, Hsla, IntoElement, ParentElement as _, Rgba, SharedString, Styled as _, div,
    px, svg,
};
use gpui_icons::GpuiIcons;
use liquid_glass_kit::tokens::{Theme, mix, typography};

use crate::layout::{self, geom, space};

/// Where a row's separator starts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Inset {
    /// Under the text column (after the leading slot).
    Text,
    /// No separator.
    None,
}

/// Look of the trailing count badge.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BadgeTone {
    /// Unread: solid accent.
    Accent,
    /// Muted chats and quiet counts: neutral fill.
    Quiet,
}

/// Which service a conversation or contact comes from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SourceKind {
    Sms,
    WhatsApp,
}

/// One avatar of an [`avatar_stack`].
#[derive(Clone, Debug)]
pub struct StackItem {
    pub initials: SharedString,
    pub tint: Hsla,
}

impl StackItem {
    pub fn new(initials: impl Into<SharedString>, tint: Hsla) -> Self {
        Self {
            initials: initials.into(),
            tint,
        }
    }
}

/// Black or white, whichever reads better on `fill` (WCAG contrast).
pub fn readable_on(fill: Hsla) -> Hsla {
    let c: Rgba = fill.into();
    let ratio = |fg: f32| geom::contrast_ratio([fg; 3], [c.r, c.g, c.b]);
    if ratio(1.0) >= ratio(0.0) {
        gpui::white()
    } else {
        gpui::black()
    }
}

fn icon(path: GpuiIcons, size: f32, color: Hsla) -> impl IntoElement {
    svg()
        .path(path.path())
        .size(px(size))
        .flex_none()
        .text_color(color)
}

/// The first character: an overlapped disc shows only about 18 of its 28 dp,
/// so two letters would be cut by the next disc.
fn first_letter(initials: &str) -> SharedString {
    initials
        .chars()
        .next()
        .map(String::from)
        .unwrap_or_default()
        .into()
}

/// A small disc with a letter; the unit of an avatar stack.
fn stack_disc(theme: Theme, label: SharedString, tint: Hsla, size: f32) -> Div {
    let colors = crate::theme::colors(theme);
    div()
        .absolute()
        .size(px(size))
        .rounded_full()
        .bg(tint)
        // The ring is inside the 28 dp box, so the width stays exact.
        .border_2()
        .border_color(colors.background)
        .flex()
        .items_center()
        .justify_center()
        .text_size(px(10.0))
        .font_weight(gpui::FontWeight(700.0))
        .text_color(readable_on(tint))
        .child(label)
}

/// Overlapping avatars, 28 dp with 10 dp overlap by default
/// ([`layout::STACK_AVATAR`], [`layout::STACK_OVERLAP`]). Past
/// [`layout::STACK_MAX`] the last disc is "+n". The element is exactly
/// `geom::avatar_stack_width` wide, so it can never reach neighbouring text.
pub fn avatar_stack(theme: Theme, items: &[StackItem], size: f32, overlap: f32) -> Div {
    let colors = crate::theme::colors(theme);
    let (shown, hidden) = geom::stack_split(items.len(), layout::STACK_MAX);
    let discs = shown + usize::from(hidden > 0);
    let step = (size - overlap).max(0.0);
    let mut stack = div()
        .relative()
        .flex_none()
        .w(px(geom::avatar_stack_width(discs, size, overlap)))
        .h(px(size));
    for (i, item) in items.iter().take(shown).enumerate() {
        stack = stack.child(
            stack_disc(theme, first_letter(&item.initials), item.tint, size)
                .left(px(step * i as f32)),
        );
    }
    if hidden > 0 {
        stack = stack.child(
            stack_disc(
                theme,
                format!("+{hidden}").into(),
                mix(colors.background, colors.secondary_label, 0.22),
                size,
            )
            .left(px(step * shown as f32))
            .text_color(colors.label),
        );
    }
    stack
}

/// The disc of a source badge: 16 dp, brand-ish fill, white glyph, 2 dp
/// ring in the page background drawn outside it (20 dp box in total).
pub fn source_badge(theme: Theme, kind: SourceKind) -> Div {
    let colors = crate::theme::colors(theme);
    let (glyph, fill) = match kind {
        SourceKind::WhatsApp => (GpuiIcons::LucideMessageCircle, colors.green),
        SourceKind::Sms => (GpuiIcons::LucideMessageSquare, colors.accent),
    };
    let disc = layout::SOURCE_BADGE;
    let ring = layout::SOURCE_RING;
    div()
        .flex_none()
        .size(px(disc + ring * 2.0))
        .rounded_full()
        .bg(colors.background)
        .flex()
        .items_center()
        .justify_center()
        .child(
            div()
                .size(px(disc))
                .rounded_full()
                .bg(fill)
                .flex()
                .items_center()
                .justify_center()
                .child(icon(glyph, 10.0, readable_on(fill))),
        )
}

/// Pins source badges to the bottom-right corner of `avatar` (a merged
/// contact passes both kinds; they overlap by [`layout::SOURCE_OVERLAP`]).
/// The result is exactly the avatar's size, so the layout does not change.
pub fn with_source_badges(theme: Theme, avatar: Div, kinds: &[SourceKind]) -> Div {
    let box_w = layout::SOURCE_BADGE + layout::SOURCE_RING * 2.0;
    let width = geom::avatar_stack_width(kinds.len(), box_w, layout::SOURCE_OVERLAP);
    let step = box_w - layout::SOURCE_OVERLAP;
    let mut row = div()
        .absolute()
        .bottom(px(-layout::SOURCE_RING))
        .right(px(-layout::SOURCE_RING * 2.0))
        .w(px(width))
        .h(px(box_w));
    for (i, kind) in kinds.iter().enumerate() {
        row = row.child(
            source_badge(theme, *kind)
                .absolute()
                .left(px(step * i as f32)),
        );
    }
    div().relative().flex_none().child(avatar).child(row)
}

/// A two-line list row with slots. Build it, then `.render(theme)` and add
/// `.id(..).on_click(..)` as needed.
pub struct ListRow {
    leading: Option<AnyElement>,
    leading_w: f32,
    title: SharedString,
    accessories: Vec<GpuiIcons>,
    meta: Option<SharedString>,
    unread: bool,
    subtitle: Option<SharedString>,
    tick: Option<(GpuiIcons, bool)>,
    badge: Option<(SharedString, BadgeTone)>,
    below: Option<AnyElement>,
    separator: Inset,
}

impl ListRow {
    pub fn new(title: impl Into<SharedString>) -> Self {
        Self {
            leading: None,
            leading_w: layout::AVATAR,
            title: title.into(),
            accessories: Vec::new(),
            meta: None,
            unread: false,
            subtitle: None,
            tick: None,
            badge: None,
            below: None,
            separator: Inset::Text,
        }
    }

    /// The leading element (avatar). It is placed in a slot
    /// [`layout::AVATAR`] wide; use [`Self::leading_width`] for others.
    pub fn leading(mut self, el: impl IntoElement) -> Self {
        self.leading = Some(el.into_any_element());
        self
    }

    /// Width of the leading slot when it is not a 48 dp avatar (a stack).
    /// The text column and the separator follow.
    pub fn leading_width(mut self, w: f32) -> Self {
        self.leading_w = w;
        self
    }

    /// A 14 dp icon between the title and the time (mute, pin). Call again
    /// for more.
    pub fn title_accessory(mut self, icon: GpuiIcons) -> Self {
        self.accessories.push(icon);
        self
    }

    /// The time, Footnote, right-aligned on the title line.
    pub fn meta(mut self, text: impl Into<SharedString>) -> Self {
        self.meta = Some(text.into());
        self
    }

    /// Unread rows draw the meta in the accent colour.
    pub fn unread(mut self, on: bool) -> Self {
        self.unread = on;
        self
    }

    pub fn subtitle(mut self, text: impl Into<SharedString>) -> Self {
        self.subtitle = Some(text.into());
        self
    }

    /// A 14 dp status tick before the subtitle; `accent` for "read".
    pub fn subtitle_tick(mut self, icon: GpuiIcons, accent: bool) -> Self {
        self.tick = Some((icon, accent));
        self
    }

    /// Count badge at the end of the subtitle line (20 dp minimum, Caption
    /// weight 600).
    pub fn trailing_badge(mut self, text: impl Into<SharedString>, tone: BadgeTone) -> Self {
        self.badge = Some((text.into(), tone));
        self
    }

    /// Extra content under the lines (an OTP chip), indented to the text.
    pub fn below(mut self, el: impl IntoElement) -> Self {
        self.below = Some(el.into_any_element());
        self
    }

    pub fn separator(mut self, inset: Inset) -> Self {
        self.separator = inset;
        self
    }

    pub fn render(self, theme: Theme) -> Div {
        let colors = crate::theme::colors(theme);
        let has_leading = self.leading.is_some();
        let leading_w = if has_leading { self.leading_w } else { 0.0 };

        // The title takes all the free width (and truncates), so the icons
        // and the time sit together at the right edge.
        let title_line = div()
            .flex()
            .items_center()
            .gap(px(space::S))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .truncate()
                    .text_size(typography::HEADLINE.size)
                    .line_height(typography::HEADLINE.line)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.label)
                    .child(self.title),
            )
            .children(
                self.accessories
                    .into_iter()
                    .map(|i| icon(i, layout::ROW_ICON, colors.tertiary_label)),
            )
            .children(self.meta.map(|m| {
                div()
                    .flex_none()
                    .text_size(typography::FOOTNOTE.size)
                    .line_height(typography::FOOTNOTE.line)
                    .text_color(if self.unread {
                        crate::theme::accent_text(theme)
                    } else {
                        colors.secondary_label
                    })
                    .child(m)
            }));

        let subtitle_line = (self.subtitle.is_some() || self.badge.is_some()).then(|| {
            div()
                .flex()
                .items_center()
                .gap(px(space::S))
                .min_h(px(layout::BADGE_MIN))
                .children(self.tick.map(|(i, accent)| {
                    icon(
                        i,
                        layout::ROW_ICON,
                        if accent {
                            colors.accent
                        } else {
                            colors.secondary_label
                        },
                    )
                }))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .truncate()
                        .text_size(typography::SUBHEADLINE.size)
                        .line_height(typography::SUBHEADLINE.line)
                        .text_color(colors.secondary_label)
                        .children(self.subtitle),
                )
                .children(self.badge.map(|(text, tone)| {
                    let fill = match tone {
                        BadgeTone::Accent => colors.accent,
                        BadgeTone::Quiet => colors.fill,
                    };
                    let ink = match tone {
                        BadgeTone::Accent => readable_on(fill),
                        BadgeTone::Quiet => colors.secondary_label,
                    };
                    div()
                        .flex_none()
                        .h(px(layout::BADGE_MIN))
                        .min_w(px(geom::badge_width(text.chars().count())))
                        .px(px(space::XS + 2.0))
                        .rounded_full()
                        .bg(fill)
                        .flex()
                        .items_center()
                        .justify_center()
                        .text_size(typography::CAPTION.size)
                        .line_height(typography::CAPTION.line)
                        .font_weight(gpui::FontWeight(600.0))
                        .text_color(ink)
                        .child(text)
                }))
        });

        let text_col = div()
            .flex_1()
            .min_w_0()
            .min_h(px(layout::AVATAR))
            .flex()
            .flex_col()
            .justify_center()
            .child(title_line)
            .children(subtitle_line)
            .children(self.below.map(|b| div().flex().pt(px(space::S)).child(b)));

        let mut row = div()
            .relative()
            .w_full()
            .min_h(px(layout::ROW_MIN_H))
            .px(px(layout::MARGIN))
            .py(px(space::M))
            .flex()
            .items_start()
            .gap(px(space::M));
        if let Some(lead) = self.leading {
            row = row.child(div().flex_none().w(px(leading_w)).child(lead));
        }
        row = row.child(text_col);
        if self.separator == Inset::Text {
            row = row.child(
                div()
                    .absolute()
                    .bottom_0()
                    .right_0()
                    .left(px(geom::separator_left(true, leading_w, space::M)))
                    .h(px(0.5))
                    .bg(colors.separator),
            );
        }
        row
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_letter_handles_unicode_and_empty() {
        assert_eq!(first_letter("AY"), "A");
        assert_eq!(first_letter("Şeyma"), "Ş");
        assert_eq!(first_letter(""), "");
    }

    #[test]
    fn readable_on_picks_the_higher_contrast() {
        assert_eq!(readable_on(gpui::black()), gpui::white());
        assert_eq!(readable_on(gpui::white()), gpui::black());
        // A mid blue (iOS dark accent) reads better with black.
        let blue: Hsla = gpui::rgb(0x0A84FF).into();
        let c: Rgba = blue.into();
        let on = readable_on(blue);
        let fg: Rgba = on.into();
        let got = geom::contrast_ratio([fg.r; 3], [c.r, c.g, c.b]);
        let other = geom::contrast_ratio([1.0 - fg.r; 3], [c.r, c.g, c.b]);
        assert!(got >= other);
    }
}
