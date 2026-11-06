//! Two small utilities on the Number and Contact sheets and the Recents row menu:
//! a quick reply to a number (opens Messages with the text filled in) and the
//! per-person call history export (CSV through the system "save as" picker).

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Role,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px, svg,
};
use rovsti_core::settings::reply_choices;
use rovsti_ui::{
    Icon,
    motion::press_scale,
    motion_tokens::scale,
    theme,
    tokens::{Theme, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, SheetKind, backend, backend::Outcome};

/// The open quick reply chooser.
pub(crate) struct QuickReply {
    pub number: SharedString,
    pub choices: Vec<String>,
}

/// Row id of the "Write your own..." row.
pub(crate) const OWN_ID: &str = "sheet-qr-own";

/// Number and Contact sheet "Export calls" ids.
pub(crate) const NUMBER_SHEET_CSV: &str = "sheet-nhist-csv";
pub(crate) const CONTACT_SHEET_CSV: &str = "sheet-chist-csv";

/// Row menu "Send a quick reply" id.
pub(crate) const RM_REPLY: &str = "rm-reply";

/// Row id of the chooser's `index`-th reply.
pub(crate) fn reply_id(index: usize) -> String {
    format!("sheet-qr-{index}")
}

/// The reply index a chooser row id stands for.
pub(crate) fn reply_index(id: &str) -> Option<usize> {
    id.strip_prefix("sheet-qr-")?.parse().ok()
}

/// The pill after handing a reply to the Messages app.
pub(crate) fn opened_pill(outcome: Outcome) -> &'static str {
    match outcome {
        Outcome::Done => "Opened Messages",
        other => other.why_not("Couldn't open Messages"),
    }
}

/// The pill when a person has no calls to export.
pub(crate) const NO_CALLS_PILL: &str = "No calls to export";

impl PhoneApp {
    /// Swaps the open sheet (or menu) for the reply chooser on `number`.
    pub(crate) fn open_quick_reply(&mut self, number: SharedString, cx: &mut Context<Self>) {
        let choices = reply_choices(&self.backend.read(cx).prefs().quick_replies);
        self.quick_reply = Some(QuickReply { number, choices });
        self.sheet_kind = SheetKind::QuickReplyPick;
        self.sheet.open();
        self.presses.release_all();
        cx.notify();
    }

    /// A tap on a chooser row.
    pub(crate) fn quick_reply_act(&mut self, id: &str, cx: &mut Context<Self>) {
        let Some(chooser) = self.quick_reply.take() else {
            return;
        };
        let outcome = if id == OWN_ID {
            backend::send_sms_to(&chooser.number)
        } else if let Some(text) = reply_index(id).and_then(|i| chooser.choices.get(i)) {
            backend::compose_sms(&chooser.number, text)
        } else {
            self.quick_reply = Some(chooser);
            return;
        };
        self.close_sheet(cx);
        self.show_notice_pill(opened_pill(outcome), None, false, cx);
    }

    /// The chooser sheet.
    pub(crate) fn quick_reply_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let chooser = self.quick_reply.as_ref()?;
        let colors = theme::colors(theme);
        let row = |id: SharedString, text: SharedString, own: bool, cx: &mut Context<Self>| {
            let press = self.presses.value(&id).clamp(0.0, 1.0);
            let act = id.clone();
            let ink = if own { colors.accent } else { colors.label };
            div()
                .id(id.clone())
                .role(Role::Button)
                .aria_label(text.clone())
                .relative()
                .w_full()
                .min_h(px(56.0))
                .px(px(20.0))
                .py(px(8.0))
                .flex()
                .items_center()
                .gap(px(14.0))
                .bg(gpui::Hsla {
                    a: 0.9 * press,
                    ..colors.fill
                })
                .child(
                    svg()
                        .path(
                            if own {
                                Icon::LucidePencil
                            } else {
                                Icon::LucideMessageCircle
                            }
                            .path(),
                        )
                        .size(px(22.0 * press_scale(press, scale::PRESS_CARD)))
                        .flex_none()
                        .text_color(ink),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .line_clamp(2)
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(ink)
                        .child(text),
                )
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, _, cx| {
                        this.presses.down(act.clone());
                        this.quick_reply_act(&act, cx);
                    }),
                )
                .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))))
        };
        let mut rows: Vec<AnyElement> = chooser
            .choices
            .iter()
            .enumerate()
            .map(|(i, text)| {
                row(reply_id(i).into(), text.clone().into(), false, cx).into_any_element()
            })
            .collect();
        rows.push(row(OWN_ID.into(), "Write your own...".into(), true, cx).into_any_element());
        let body = div()
            .flex()
            .flex_col()
            .pb(px(16.0))
            .children(rows)
            .into_any_element();
        self.modal_sheet(theme, window, "Quick reply", body, cx)
    }

    /// Builds one person's call CSV off the UI thread and opens "save as".
    pub(crate) fn export_person_calls(
        &mut self,
        numbers: Vec<String>,
        who: String,
        cx: &mut Context<Self>,
    ) {
        let (source, tz) = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => (log.source.clone(), log.locale.tz_offset_s),
            _ => {
                self.show_notice_pill("Call history isn't loaded yet", None, false, cx);
                return;
            }
        };
        let extras = self.wa_call.read(cx).recents_extras();
        let notes = self.notes.snapshot();
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_millis() as i64);
        cx.spawn(async move |this, cx| {
            let made = cx
                .background_executor()
                .spawn(async move {
                    let refs: Vec<&str> = numbers.iter().map(String::as_str).collect();
                    rovsti_core::call_log_query::person_export(
                        &source, &extras, &refs, &who, now_ms, tz, &notes,
                    )
                })
                .await;
            this.update(cx, |this, cx| match made {
                None => this.show_notice_pill(NO_CALLS_PILL, None, false, cx),
                Some((name, csv)) => {
                    let outcome = backend::export_calls(&name, &csv);
                    if outcome != backend::Outcome::Done {
                        this.show_notice_pill(outcome.why_not("Couldn't export"), None, false, cx);
                    }
                }
            })
            .ok();
        })
        .detach();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reply_ids_round_trip_and_stay_apart() {
        for i in [0, 3, 11] {
            assert_eq!(reply_index(&reply_id(i)), Some(i));
        }
        assert_eq!(reply_index(OWN_ID), None);
        assert_eq!(reply_index("sheet-qr-"), None);
        assert_eq!(reply_index("sheet-fmt-1"), None);
    }

    #[test]
    fn ids_keep_the_router_prefixes() {
        assert!(NUMBER_SHEET_CSV.starts_with("sheet-n"));
        assert!(CONTACT_SHEET_CSV.starts_with("sheet-c"));
        assert!(!NUMBER_SHEET_CSV.starts_with("sheet-nhx-"));
        assert!(!CONTACT_SHEET_CSV.starts_with("sheet-chx-"));
        assert!(RM_REPLY.starts_with("rm-"));
        for id in [OWN_ID, "sheet-qr-0"] {
            assert!(id.starts_with("sheet-"));
            assert!(!id.starts_with("sheet-c") && !id.starts_with("sheet-n"));
        }
    }

    #[test]
    fn pills_read_as_the_spec_says() {
        assert_eq!(opened_pill(Outcome::Done), "Opened Messages");
        assert_eq!(opened_pill(Outcome::Declined), "Couldn't open Messages");
        assert_eq!(
            opened_pill(Outcome::NotAvailable),
            "Not available on this platform"
        );
        assert_eq!(NO_CALLS_PILL, "No calls to export");
    }

    #[test]
    fn the_chooser_lists_the_reply_choices() {
        let choices = reply_choices(&["Hi".to_owned(), "Bye".to_owned()]);
        assert_eq!(choices, ["Hi", "Bye"]);
        assert!(!reply_choices(&[]).is_empty());
    }
}
