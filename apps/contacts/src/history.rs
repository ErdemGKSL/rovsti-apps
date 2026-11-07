//! The card's "Recent calls" section: the calls with this contact from the
//! phone's call log. Read-only, local, and only after the owner allowed the
//! call log.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px, svg,
};
use rovsti_android::permissions::{self, Group};
use rovsti_core::{
    call_log::CallType,
    contact_card::ContactCard,
    contact_history::{self, CARD_LIMIT},
    recents::{HistoryLine, LocaleTime},
};
use rovsti_ui::{
    Icon,
    components::section,
    theme,
    tokens::{Theme, metrics, typography},
};

use crate::{
    ContactsApp, NoticeAction, backend,
    confirm::Confirm,
    ui::{Tone, tap_row},
};

/// The pill when the call-log permission was refused.
pub(crate) const DENIED: &str = "Call log permission is off";

/// What the card knows about its calls.
#[derive(Clone, Debug, Default)]
pub(crate) enum Calls {
    /// No section: no number, a desktop run, or the card is closed.
    #[default]
    Hidden,
    Loading,
    /// The call log is not allowed yet.
    NeedsAccess,
    Ready(Vec<HistoryLine>),
    /// The call log could not be read (in words).
    Failed(String),
}

/// The icon of a history line.
pub(crate) fn line_icon(line: &HistoryLine) -> Icon {
    if line.blocked {
        return Icon::LucideBan;
    }
    match line.call_type {
        CallType::Incoming => Icon::LucidePhoneIncoming,
        CallType::Outgoing => Icon::LucidePhoneOutgoing,
        CallType::Missed | CallType::Declined => Icon::LucidePhoneMissed,
        CallType::Voicemail | CallType::Unknown => Icon::LucidePhone,
    }
}

/// The lines the card shows: text and the number a tap calls.
pub(crate) fn shown(
    lines: &[HistoryLine],
    now_ms: i64,
    locale: LocaleTime,
) -> Vec<(HistoryLine, String, String)> {
    contact_history::history_rows(lines, now_ms, locale)
        .into_iter()
        .zip(lines.iter().take(CARD_LIMIT))
        .map(|((title, detail), line)| (line.clone(), title, detail))
        .collect()
}

impl ContactsApp {
    /// Reads the calls of the open card in the background, once per card
    /// open. Without the permission the card gets the "Show recent calls" row.
    pub(crate) fn load_card_calls(&mut self, cx: &mut Context<Self>) {
        self.calls_loads += 1;
        let load = self.calls_loads;
        let Some(card) = self.open.as_ref() else {
            self.calls = Calls::Hidden;
            return;
        };
        let (id, numbers) = (
            card.id,
            card.numbers
                .iter()
                .map(|n| n.raw.clone())
                .collect::<Vec<_>>(),
        );
        if numbers.is_empty() {
            self.calls = Calls::Hidden;
            return;
        }
        self.calls = Calls::Loading;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    match permissions::has(Group::CallLog) {
                        Ok(true) => Some(match backend::call_history(&numbers) {
                            Ok(lines) => Calls::Ready(lines),
                            Err(words) => Calls::Failed(words),
                        }),
                        Ok(false) => Some(Calls::NeedsAccess),
                        // Desktop, or no way to ask: the section stays away.
                        Err(_) => None,
                    }
                })
                .await;
            this.update(cx, |this, cx| {
                if this.calls_loads == load && this.open.as_ref().is_some_and(|c| c.id == id) {
                    this.calls = result.unwrap_or(Calls::Hidden);
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Drops the calls of a closed card (and a read still in flight).
    pub(crate) fn forget_card_calls(&mut self) {
        self.calls_loads += 1;
        self.calls = Calls::Hidden;
    }

    /// "Show recent calls" was tapped: explain, then ask.
    fn ask_call_log(&mut self, cx: &mut Context<Self>) {
        self.confirm = Some(Confirm::CallLogAccess);
        cx.notify();
    }

    /// The owner agreed: show the system permission dialog.
    pub(crate) fn request_call_log(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async { permissions::request(Group::CallLog) })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => this.load_card_calls(cx),
                    Ok(false) => {
                        this.notice = Some(DENIED.into());
                        this.notice_action = Some(NoticeAction::OpenAppDetails);
                    }
                    Err(error) => {
                        this.notice = Some(
                            format!(
                                "Can't ask for the call log: {}.",
                                backend::android_words(&error)
                            )
                            .into(),
                        );
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Title and body of the permission card.
    pub(crate) fn call_log_card_text() -> (&'static str, &'static str) {
        contact_history::permission_text()
    }

    /// The "Recent calls" section under the numbers.
    pub(crate) fn calls_section(
        &self,
        card: &ContactCard,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if card.numbers.is_empty() {
            return None;
        }
        let colors = theme::colors(theme);
        let note = |text: String| -> AnyElement {
            div()
                .min_h(metrics::MIN_HIT)
                .px(metrics::ROW_INSET)
                .py(px(10.0))
                .flex()
                .items_center()
                .border_b(metrics::SEPARATOR)
                .border_color(colors.separator)
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.secondary_label)
                .child(SharedString::from(text))
                .into_any_element()
        };
        let rows: Vec<AnyElement> = match &self.calls {
            Calls::Hidden => return None,
            Calls::Loading => vec![note("Reading the call log…".to_owned())],
            Calls::NeedsAccess => vec![
                tap_row(
                    theme,
                    "calls-show",
                    Some(Icon::LucideClock),
                    "Show recent calls",
                    Tone::Accent,
                )
                .on_click(cx.listener(|this, _, _, cx| this.ask_call_log(cx)))
                .into_any_element(),
            ],
            Calls::Failed(words) => vec![note(format!("Can't read the call log: {words}."))],
            Calls::Ready(lines) => {
                let now = crate::whatsapp::now_ms();
                let locale = self.locale;
                let header = contact_history::summary_at(lines, now, locale.tz_offset_s)
                    .unwrap_or_else(|| "No calls with this contact".to_owned());
                let mut rows = vec![note(header)];
                rows.extend(shown(lines, now, locale).into_iter().enumerate().map(
                    |(i, (line, title, detail))| {
                        let missed = line.blocked
                            || matches!(line.call_type, CallType::Missed | CallType::Declined);
                        let tone = if missed {
                            colors.red
                        } else {
                            colors.secondary_label
                        };
                        let number = line.number.clone();
                        div()
                            .id(("call-line", i))
                            .flex()
                            .items_center()
                            .gap(px(12.0))
                            .min_h(metrics::ROW_HEIGHT_SUBTITLE)
                            .px(metrics::ROW_INSET)
                            .py(px(6.0))
                            .border_b(metrics::SEPARATOR)
                            .border_color(colors.separator)
                            .child(
                                svg()
                                    .path(line_icon(&line).path())
                                    .size(px(18.0))
                                    .text_color(tone),
                            )
                            .child(
                                div()
                                    .flex_1()
                                    .min_w_0()
                                    .flex()
                                    .flex_col()
                                    .child(
                                        div()
                                            .text_size(typography::BODY.size)
                                            .text_color(if missed {
                                                colors.red
                                            } else {
                                                colors.label
                                            })
                                            .child(SharedString::from(title)),
                                    )
                                    .child(
                                        div()
                                            .text_size(typography::FOOTNOTE.size)
                                            .text_color(colors.secondary_label)
                                            .child(SharedString::from(detail)),
                                    ),
                            )
                            .on_click(cx.listener(move |this, _, _, cx| {
                                if number.trim().is_empty() {
                                    return;
                                }
                                let number = number.clone();
                                this.act(
                                    "call",
                                    move || rovsti_android::telecom::place_call(&number),
                                    cx,
                                );
                            }))
                            .into_any_element()
                    },
                ));
                rows
            }
        };
        Some(section(theme, Some("Recent calls".into()), rows).into_any_element())
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::call_log::EntryRef;

    use super::*;

    fn line(id: i64, kind: CallType, blocked: bool) -> HistoryLine {
        HistoryLine {
            entry: EntryRef::Log(id),
            blocked,
            call_type: kind,
            whatsapp: false,
            date_ms: 1_700_000_000_000 - id * 60_000,
            duration_s: 5,
            number: format!("555{id}"),
        }
    }

    #[test]
    fn icons_follow_the_direction() {
        assert_eq!(
            line_icon(&line(1, CallType::Incoming, false)),
            Icon::LucidePhoneIncoming
        );
        assert_eq!(
            line_icon(&line(1, CallType::Outgoing, false)),
            Icon::LucidePhoneOutgoing
        );
        assert_eq!(
            line_icon(&line(1, CallType::Missed, false)),
            Icon::LucidePhoneMissed
        );
        assert_eq!(
            line_icon(&line(1, CallType::Incoming, true)),
            Icon::LucideBan
        );
    }

    #[test]
    fn shown_keeps_each_line_with_its_text_and_stops_at_the_limit() {
        let lines: Vec<HistoryLine> = (0..9).map(|i| line(i, CallType::Outgoing, false)).collect();
        let rows = shown(&lines, 1_700_000_000_000, LocaleTime::default());
        assert_eq!(rows.len(), CARD_LIMIT);
        assert_eq!(rows[2].0.number, "5552");
        assert_eq!(rows[2].1, "Outgoing call");
        assert!(shown(&[], 0, LocaleTime::default()).is_empty());
    }

    #[test]
    fn the_permission_card_says_the_log_stays_on_the_phone() {
        let (title, body) = ContactsApp::call_log_card_text();
        assert!(title.contains("calls"));
        assert!(body.contains("this phone only"));
        assert!(DENIED.ends_with("permission is off"));
    }
}
