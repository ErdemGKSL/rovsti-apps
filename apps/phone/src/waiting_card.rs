//! The Waiting card (story 15.8, S23): four ways to handle a second caller.
//!
//! A flat sheet-glass card (no backdrop sample) that falls 40 px with an
//! overshoot over the top of the In-Call screen. It overlays the zone, which
//! dims to 0.85 and does not shrink, and it is tappable from the first frame.
//! Each pill acts on finger-up. The card never auto-dismisses.

use gpui::{
    AnyElement, Context, HapticFeedback, Hsla, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Role, SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::scene::DisabledReason;
use rovsti_ui::{
    Icon,
    components::flat_glass,
    motion::press_scale,
    theme::{self, call_colors},
    tokens::{Theme, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, zone::slots};

/// The card sits this far below the top of the window (top inset 48 + 12).
const TOP: f32 = 48.0 + slots::WAITING_TOP;
/// The pills spring in 40 ms apart.
const STAGGER: f32 = 0.04;

/// Ease-out with a visible overshoot, for the pills.
fn pop(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    let u = t - 1.0;
    1.0 + 2.2 * u * u * u + 1.2 * u * u
}

impl PhoneApp {
    /// A tap on one of the card's pills (finger-up).
    pub(crate) fn waiting_act(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        let Some(w) = self.incall.waiting.clone() else {
            return;
        };
        if !self.incall.waiting_open || self.incall.ending.is_some() {
            return;
        }
        let dimmed: Option<DisabledReason> = match id {
            "iw-hold" => w.hold_reason,
            "iw-end" => w.end_reason,
            _ => None,
        };
        if w.wa_call_id.is_some() {
            // A WhatsApp offer: Decline is the only action.
            match id {
                "iw-decline" => {
                    self.incall.waiting_acted = true;
                    self.wa_call.update(cx, |ui, cx| ui.hang_up(true, cx));
                }
                _ => self.show_notice_pill(DisabledReason::WhatsAppCall.text(), None, false, cx),
            }
            cx.notify();
            return;
        }
        if id == "iw-msg" {
            // Story 18.7: the reply sheet over the call; choosing a reply declines the
            // waiting call with that text and leaves the call underneath alone.
            self.reply_open(w.id, true, window, cx);
            return;
        }
        if let Some(reason) = dimmed {
            self.show_notice_pill(reason.text(), None, false, cx);
            return;
        }
        match id {
            "iw-hold" => {
                rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
                self.incall.waiting_acted = true;
                self.backend.update(cx, |b, cx| {
                    if w.relabelled {
                        b.answer_ending_held(w.id, cx);
                    } else {
                        b.answer_holding(w.id, cx);
                    }
                });
            }
            "iw-end" => {
                rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
                self.incall.waiting_acted = true;
                self.backend.update(cx, |b, cx| {
                    b.answer_replacing(w.id, cx);
                });
            }
            "iw-decline" => {
                self.incall.waiting_acted = true;
                self.backend.update(cx, |b, cx| {
                    b.reject(w.id, cx);
                });
            }
            _ => {}
        }
        cx.notify();
    }

    /// One pill of the 2 x 2 grid.
    #[allow(clippy::too_many_arguments)] // one call per pill; a struct would only repeat these
    fn waiting_pill(
        &self,
        theme: Theme,
        id: &'static str,
        label: &'static str,
        sub: Option<SharedString>,
        icon: Option<Icon>,
        fill: Option<Hsla>,
        text: Hsla,
        dimmed: bool,
        index: usize,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let press = self.presses.value(id).clamp(0.0, 1.0);
        let grow = pop((self.incall.waiting_age - 0.06 - STAGGER * index as f32) / 0.26);
        let k = press_scale(press, 0.97) * (0.55 + 0.45 * grow);
        let body = match fill {
            Some(fill) => div().bg(rovsti_ui::tokens::mix(fill, gpui::white(), 0.16 * press)),
            None => flat_glass(theme, None),
        };
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(match &sub {
                Some(sub) => SharedString::from(format!("{label}. {sub}")),
                None => SharedString::from(label),
            })
            .aria_disabled(dimmed)
            .relative()
            .flex_1()
            .h(px(slots::WAITING_PILL_H))
            .opacity(grow.clamp(0.0, 1.0) * if dimmed { 0.4 } else { 1.0 })
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| this.tap_press(id, window, cx)),
            )
            .child(
                body.absolute()
                    .left(px((1.0 - k) * 60.0))
                    .right(px((1.0 - k) * 60.0))
                    .top(px((1.0 - k) * 22.0))
                    .bottom(px((1.0 - k) * 22.0))
                    .rounded_full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .gap(px(8.0))
                    .children(
                        icon.map(|icon| svg().path(icon.path()).size(px(18.0)).text_color(text)),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .items_center()
                            .child(
                                div()
                                    .text_size(px(15.0))
                                    .font_weight(typography::HEADLINE.weight)
                                    .text_color(text)
                                    .child(label),
                            )
                            .children(sub.map(|sub| {
                                div()
                                    .text_size(px(11.0))
                                    .text_color(Hsla { a: 0.8, ..text })
                                    .child(sub)
                            })),
                    ),
            )
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
            .into_any_element()
    }

    /// The card, or nothing when no call waits and the card has risen away.
    pub(crate) fn waiting_card(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let ic = &self.incall;
        let w = ic.waiting.as_ref()?;
        let t = ic.waiting_in.value;
        if t < 0.003 && !ic.waiting_open {
            return None;
        }
        let photo = self.caller_photo(&w.photo, cx);
        let colors = theme::colors(theme);
        let call = call_colors(theme);
        let width = f32::from(window.viewport_size().width);
        // Falls 40 px with an overshoot on the way in; rises further away on the way out.
        let travel = if ic.waiting_open { 40.0 } else { 90.0 };
        let top = TOP - travel * (1.0 - t);
        let alpha = if ic.waiting_open {
            (t * 4.0).clamp(0.0, 1.0)
        } else {
            t.clamp(0.0, 1.0)
        };
        let on_answer = if theme.is_dark() {
            gpui::black()
        } else {
            gpui::white()
        };
        let end_sub: SharedString = format!("Ends {}", w.ends).into();
        let hold_label = if w.relabelled {
            "End held call and answer"
        } else {
            "Hold and answer"
        };
        let wa_offer = w.wa_call_id.is_some();
        let no_replies = !wa_offer && self.backend.read(cx).prefs().quick_replies.is_empty();
        let hold_dim = w.hold_reason.is_some();
        let end_dim = w.end_reason.is_some();
        let grid = div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(
                div()
                    .flex()
                    .gap(px(8.0))
                    .child(self.waiting_pill(
                        theme,
                        "iw-hold",
                        hold_label,
                        None,
                        None,
                        Some(call.answer),
                        on_answer,
                        hold_dim,
                        0,
                        cx,
                    ))
                    .child(self.waiting_pill(
                        theme,
                        "iw-end",
                        "End and answer",
                        Some(end_sub),
                        None,
                        None,
                        colors.label,
                        end_dim,
                        1,
                        cx,
                    )),
            )
            .child(
                div()
                    .flex()
                    .gap(px(8.0))
                    .child(self.waiting_pill(
                        theme,
                        "iw-decline",
                        "Decline",
                        None,
                        Some(Icon::LucidePhoneOff),
                        Some(call.danger),
                        gpui::white(),
                        false,
                        2,
                        cx,
                    ))
                    .child(self.waiting_pill(
                        theme,
                        "iw-msg",
                        "Message",
                        no_replies.then(|| SharedString::from("Add replies in Settings")),
                        Some(Icon::LucideMessageSquare),
                        None,
                        colors.label,
                        no_replies || wa_offer,
                        3,
                        cx,
                    )),
            );
        let header = div()
            .flex()
            .items_center()
            .gap(px(12.0))
            .child(crate::contacts::person_avatar(
                theme,
                w.hue,
                w.monogram.as_ref(),
                40.0,
                photo.as_ref().map(|(image, opacity)| (image, *opacity)),
            ))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.label)
                            .child(w.name.clone()),
                    )
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child("Waiting"),
                    ),
            );
        let _ = width;
        Some(
            div()
                .id("waiting")
                .role(Role::Alert)
                .aria_label(SharedString::from(format!("Waiting call from {}", w.name)))
                .absolute()
                .left(px(slots::WAITING_INSET))
                .right(px(slots::WAITING_INSET))
                .top(px(top))
                .h(px(slots::WAITING_H))
                .opacity(alpha)
                .child(
                    flat_glass(theme, None)
                        .size_full()
                        .rounded(px(slots::WAITING_RADIUS))
                        .p(px(16.0))
                        .flex()
                        .flex_col()
                        .justify_between()
                        .child(header)
                        .child(grid),
                )
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pills_overshoot_then_settle() {
        assert_eq!(pop(0.0), 0.0);
        assert!((pop(1.0) - 1.0).abs() < 1e-6);
        assert!((0.0..=1.4).contains(&pop(0.7)));
        let peak = (1..100).map(|i| pop(i as f32 / 100.0)).fold(0.0, f32::max);
        assert!(peak > 1.02, "{peak}");
    }

    #[test]
    fn the_card_fits_its_slot() {
        // header 40 + gap + grid (44 + 8 + 44) + padding 32 = 184 at most.
        let content = 40.0 + 2.0 * slots::WAITING_PILL_H + 8.0 + 32.0;
        assert!(content <= slots::WAITING_H, "{content}");
    }
}
