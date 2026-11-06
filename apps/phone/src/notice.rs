//! The one-line notice under the page title (story 7.6 and call outcomes).
//!
//! One precedence stack: a call outcome ("Placed through the system phone app",
//! "Allow Phone access...") wins over the Preview banner ("Not the default
//! phone app"). The notice is a floating glass pill above the tab bar, never
//! part of its layout: it rises out of the tab bar and fades on a spring, and the page content
//! stays exactly where it is whether the pill is shown or not.

use gpui::{
    Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Pixels, Role,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::call::format_elapsed;
use rovsti_ui::{
    Icon,
    glass::{GlassInteraction, GlassSpec, glass_interactive},
    tab_bar::content_bottom_inset,
    theme::{self, call_colors},
    tokens::{Theme, metrics, shadow, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, backend};

/// Height of the banner capsule.
const BANNER_H: f32 = 44.0;
/// Space between the pill and the tab bar above which it floats.
const ABOVE_BAR: f32 = 6.0;
/// How far the pill rises from the tab bar while it opens.
const DROP: f32 = 18.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Notice {
    /// Rovsti Phone is not the default phone app (FR-4).
    Preview,
    /// A call went through the system phone app.
    System,
    /// `CALL_PHONE` was refused.
    NeedsPhone,
    Failed,
    /// A WhatsApp call could not start; the text says why.
    WhatsApp(&'static str),
}

impl Notice {
    fn text(self) -> &'static str {
        match self {
            Self::Preview => "Not the default phone app",
            Self::System => "Placed through the system phone app",
            Self::NeedsPhone => "Allow Phone access to place calls",
            Self::Failed => "The call could not be placed",
            Self::WhatsApp(why) => why,
        }
    }

    fn action(self) -> Option<&'static str> {
        match self {
            Self::Preview => Some("Fix"),
            Self::NeedsPhone => Some("Grant"),
            Self::System | Self::Failed | Self::WhatsApp(_) => None,
        }
    }

    fn icon(self) -> Icon {
        match self {
            Self::Preview | Self::System => Icon::LucideSmartphone,
            Self::NeedsPhone | Self::Failed | Self::WhatsApp(_) => Icon::LucideTriangleAlert,
        }
    }
}

impl PhoneApp {
    /// What the slot should show now.
    fn current_notice(&self, cx: &gpui::App) -> Option<Notice> {
        self.call_note.or_else(|| {
            let backend = self.backend.read(cx);
            (backend.permissions_known() && !backend.is_default_dialer()).then_some(Notice::Preview)
        })
    }

    /// Points the slot spring at the current notice. Call once per render,
    /// before the springs tick.
    pub(crate) fn sync_notice(&mut self, cx: &gpui::App) {
        match self.current_notice(cx) {
            Some(notice) => {
                self.notice_shown = Some(notice);
                self.notice_h.set_target(1.0);
            }
            None => {
                self.notice_h.set_target(0.0);
                if self.notice_h.value < 0.003 {
                    self.notice_shown = None;
                }
            }
        }
    }

    /// The slot, or `None` once it has fully closed.
    pub(crate) fn notice_slot(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<gpui::AnyElement> {
        let v = self.notice_h.value.clamp(0.0, 1.0);
        let notice = self.notice_shown?;
        let colors = theme::colors(theme);
        let tint = match notice {
            Notice::Preview | Notice::System => call_colors(theme).warning,
            Notice::NeedsPhone | Notice::Failed | Notice::WhatsApp(_) => call_colors(theme).danger,
        };
        let press = self.presses.value("notice-act").clamp(0.0, 1.0);
        let spec = GlassSpec::control(theme).lerp(GlassSpec::lens(theme), press * 0.6);
        let banner = glass_interactive(spec, move || GlassInteraction { press, focus: None })
            .h(px(BANNER_H))
            .w_full()
            .rounded_full()
            .shadow(shadow::glass(theme.is_dark()))
            .overflow_hidden()
            .flex()
            .items_center()
            .gap(px(10.0))
            .pl(px(14.0))
            .pr(px(6.0))
            .child(
                svg()
                    .path(notice.icon().path())
                    .size(px(18.0))
                    .flex_none()
                    .text_color(tint),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .truncate()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.label)
                    .child(notice.text()),
            )
            .children(notice.action().map(|label| {
                div()
                    .flex_none()
                    .px(px(12.0))
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .child(label)
            }));
        let mut slot = div()
            .id("notice")
            .role(Role::Status)
            .aria_label(SharedString::from(notice.text()))
            .absolute()
            .bottom(
                content_bottom_inset()
                    + px(ABOVE_BAR - DROP * (1.0 - v)
                        + (BANNER_H + 8.0) * self.pill_in.value.clamp(0.0, 1.0)),
            )
            .left_0()
            .right_0()
            .px(metrics::ROW_INSET)
            .opacity(v);
        if notice.action().is_some() {
            slot = slot
                .role(Role::Button)
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, _, _, cx| {
                        this.presses.down("notice-act");
                        this.notice_act(cx);
                    }),
                )
                .child(touch_surface(Self::touch_listener(
                    cx,
                    Some("notice-act".into()),
                )));
        }
        Some(slot.child(banner).into_any_element())
    }

    /// No sheet opens over a live call (story 9.3).
    pub(crate) fn can_open_sheet(&self) -> bool {
        !self.incall.has_call() && !self.incoming.covers()
    }

    /// Once per render, before the springs tick: shows the Return-to-call
    /// pill while a call lives behind the tabs, and keeps its timer ticking.
    pub(crate) fn sync_pill(&mut self, cx: &mut Context<Self>) {
        let wanted = self.pill_wanted();
        self.pill_in.set_target(f32::from(u8::from(wanted)));
        if wanted && self.pill_task.is_none() {
            self.refresh_pill_text(cx);
            let first = crate::incall::ms_to_next_second(self.incall.connect_time_ms());
            self.pill_task = Some(cx.spawn(async move |this, cx| {
                // Wakes just after each whole second of the call, so every wake
                // changes the text (a free-running 1 s timer drifts against the
                // call clock: some wakes change nothing, some seconds are skipped).
                let mut wait = first;
                loop {
                    cx.background_executor()
                        .timer(std::time::Duration::from_millis(wait))
                        .await;
                    let next = this
                        .update(cx, |this, cx| {
                            if !this.pill_wanted() {
                                this.pill_task = None;
                                return None;
                            }
                            this.refresh_pill_text(cx);
                            Some(crate::incall::ms_to_next_second(
                                this.incall.connect_time_ms(),
                            ))
                        })
                        .ok()
                        .flatten();
                    match next {
                        Some(ms) => wait = ms,
                        None => break,
                    }
                }
            }));
        }
    }

    /// Top edge (window y) of the band the banner and the Return-to-call pill float over, or
    /// `None` while neither is on screen. Taps below it belong to those overlays, never to the
    /// list rows behind them.
    pub(crate) fn overlay_band_top(&self, window: &Window) -> Option<Pixels> {
        let banner = self.notice_shown.is_some();
        let pill = self.pill_wanted() || self.pill_in.value > 0.003;
        if !banner && !pill {
            return None;
        }
        let stacked = if banner && pill { BANNER_H + 8.0 } else { 0.0 };
        Some(
            window.viewport_size().height
                - content_bottom_inset()
                - px(ABOVE_BAR + BANNER_H + stacked),
        )
    }

    fn pill_wanted(&self) -> bool {
        self.incall.has_call() && !self.incall.shown && !self.incoming.covers()
    }

    fn refresh_pill_text(&mut self, cx: &mut Context<Self>) {
        let connect = self.incall.connect_time_ms();
        let text: SharedString = if connect > 0 {
            let elapsed = format_elapsed(backend::now_ms(), connect);
            format!("Return to call \u{b7} {}", elapsed.as_str()).into()
        } else {
            "Return to call".into()
        };
        if text != self.pill_text {
            self.pill_text = text;
            cx.notify();
        }
    }

    /// The glass capsule that brings a live call back (story 9.3).
    pub(crate) fn return_pill(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<gpui::AnyElement> {
        let v = self.pill_in.value;
        if !self.pill_wanted() && v < 0.003 {
            return None;
        }
        let v = v.clamp(0.0, 1.0);
        let colors = theme::colors(theme);
        let answer = call_colors(theme).answer;
        let press = self.presses.value("rp-pill").clamp(0.0, 1.0);
        let spec = GlassSpec::control(theme).lerp(GlassSpec::lens(theme), press * 0.6);
        let k = rovsti_ui::motion::press_scale(press, rovsti_ui::motion_tokens::scale::PRESS);
        Some(
            div()
                .id("rp-pill")
                .role(Role::Button)
                .aria_label(self.pill_text.clone())
                .absolute()
                .bottom(content_bottom_inset() + px(ABOVE_BAR - DROP * (1.0 - v)))
                .left_0()
                .right_0()
                .h(px(BANNER_H))
                .flex()
                .justify_center()
                .opacity(v)
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, _, _, cx| {
                        this.presses.down("rp-pill");
                        this.app_activated(cx);
                    }),
                )
                .child(
                    glass_interactive(spec, move || GlassInteraction { press, focus: None })
                        .relative()
                        .h(px(BANNER_H * k))
                        .px(px(20.0))
                        .rounded_full()
                        .shadow(shadow::glass(theme.is_dark()))
                        .overflow_hidden()
                        .flex()
                        .items_center()
                        .gap(px(10.0))
                        .child(
                            div()
                                .absolute()
                                .inset_0()
                                .rounded_full()
                                .bg(gpui::Hsla { a: 0.20, ..answer }),
                        )
                        .child(
                            svg()
                                .path(Icon::LucidePhone.path())
                                .size(px(18.0))
                                .flex_none()
                                .text_color(answer),
                        )
                        .child(
                            div()
                                .text_size(typography::SUBHEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child(self.pill_text.clone()),
                        ),
                )
                .child(touch_surface(Self::touch_listener(
                    cx,
                    Some("rp-pill".into()),
                )))
                .into_any_element(),
        )
    }

    fn notice_act(&mut self, cx: &mut Context<Self>) {
        match self.notice_shown {
            // "Fix" opens Setup, where the default-app item comes first.
            Some(Notice::Preview) if self.can_open_sheet() => {
                self.setup_note = None;
                self.sheet_kind = crate::SheetKind::Setup;
                self.sheet.open();
            }
            Some(Notice::NeedsPhone) => {
                let number = self.last_dialed.clone();
                self.call_note = None;
                self.backend
                    .update(cx, |backend, cx| backend.grant_phone_access(&number, cx));
            }
            _ => {}
        }
        cx.notify();
    }
}
