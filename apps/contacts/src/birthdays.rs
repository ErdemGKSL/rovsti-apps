//! Birthday reminders: the Settings section, the alarms kept in line with the
//! upcoming list, and the card a tapped notification opens.

use gpui::{
    AnyElement, Context, IntoElement, ParentElement, SharedString, StatefulInteractiveElement,
    Styled, div,
};
use rovsti_android::{permissions, reminders};
use rovsti_core::birthday_reminder::{HOUR_CHOICES, hour_label};
use rovsti_ui::{
    Icon,
    components::section,
    theme,
    tokens::{Theme, typography},
};

use crate::{
    Book, ContactsApp, NoticeAction, backend,
    ui::{Tone, tap_row},
    whatsapp::now_ms,
};

/// The pill when the notification permission was refused.
pub(crate) const DENIED: &str = "Notifications permission is off";

/// The pill after the toggle.
pub(crate) fn toggle_notice(on: bool, hour_text: &str) -> String {
    if on {
        format!("Birthday reminders on, at {hour_text}")
    } else {
        "Birthday reminders off".to_owned()
    }
}

impl ContactsApp {
    /// The Settings section: the toggle and, while it is on, the time rows.
    pub(crate) fn birthdays_section(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let on = self.birthday_reminders;
        let hour12 = self.locale.format.hour12;
        let mut rows = vec![
            tap_row(
                theme,
                "birthday-reminders",
                Some(Icon::LucideBell),
                "Remind me on the day",
                Tone::Plain,
            )
            .justify_between()
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(if on {
                        colors.accent
                    } else {
                        colors.secondary_label
                    })
                    .child(if on { "On" } else { "Off" }),
            )
            .on_click(cx.listener(|this, _, _, cx| this.toggle_birthday_reminders(cx)))
            .into_any_element(),
        ];
        rows.push(
            tap_row(
                theme,
                "birthday-export",
                Some(Icon::LucideCalendarPlus),
                "Export birthdays (.ics)",
                Tone::Plain,
            )
            .opacity(if self.transfer_busy { 0.4 } else { 1.0 })
            .on_click(cx.listener(|this, _, _, cx| this.export_calendar(cx)))
            .into_any_element(),
        );
        if on {
            rows.extend(HOUR_CHOICES.into_iter().map(|hour| {
                Self::choice_line(
                    theme,
                    ("reminder-hour", usize::from(hour)),
                    SharedString::from(hour_label(hour, hour12)),
                    self.reminder_hour == hour,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.set_reminder_hour(hour, cx)))
                .into_any_element()
            }));
        }
        section(theme, Some("Birthdays".into()), rows).into_any_element()
    }

    /// The toggle. Turning on asks for the notification permission first.
    fn toggle_birthday_reminders(&mut self, cx: &mut Context<Self>) {
        self.clear_notice();
        if self.birthday_reminders {
            self.apply_birthday_setting(false, self.reminder_hour, cx);
            return;
        }
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async { permissions::request(permissions::Group::Notifications) })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => this.apply_birthday_setting(true, this.reminder_hour, cx),
                    Ok(false) => {
                        this.notice = Some(DENIED.into());
                        this.notice_action = Some(NoticeAction::OpenAppDetails);
                    }
                    Err(error) => {
                        this.notice = Some(
                            format!(
                                "Can't turn on reminders: {}.",
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

    fn set_reminder_hour(&mut self, hour: u8, cx: &mut Context<Self>) {
        if hour != self.reminder_hour {
            self.apply_birthday_setting(true, hour, cx);
        }
    }

    /// Keeps and applies a reminder choice: saved, alarms re-planned, pill.
    fn apply_birthday_setting(&mut self, on: bool, hour: u8, cx: &mut Context<Self>) {
        self.birthday_touched = true;
        self.birthday_reminders = on;
        self.reminder_hour = hour;
        self.save_prefs(cx);
        let text = hour_label(hour, self.locale.format.hour12);
        self.notice = Some(toggle_notice(on, &text).into());
        self.sync_birthdays(true, cx);
        cx.notify();
    }

    /// At launch, once the saved choice is known: the stored alarms are set
    /// again (they are lost on reboot) and brought in line with the list.
    pub(crate) fn rearm_and_sync_birthdays(&mut self, cx: &mut Context<Self>) {
        if self.birthday_reminders {
            cx.background_executor()
                .spawn(async {
                    if let Err(error) = reminders::rearm_birthdays() {
                        log::debug!("birthday alarms not re-armed: {error}");
                    }
                })
                .detach();
        }
        self.sync_birthdays(false, cx);
    }

    /// Arms the missing alarms and disarms the stale ones for the current
    /// list. Waits until the preferences and the list are both known.
    /// `report` shows a failure as a pill (the owner just changed something).
    pub(crate) fn sync_birthdays(&mut self, report: bool, cx: &mut Context<Self>) {
        if !self.prefs_ready || !self.upcoming_ready {
            return;
        }
        let prefs = self.prefs_snapshot();
        let upcoming = self.upcoming.clone();
        let offset = self.wa.offset_s;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::sync_birthdays(&prefs, &upcoming, now_ms(), offset) })
                .await;
            if let Err(words) = result {
                log::warn!("birthday alarms not synced: {words}");
                if report {
                    this.update(cx, |this, cx| {
                        this.notice = Some(format!("Can't set the reminders: {words}.").into());
                        cx.notify();
                    })
                    .ok();
                }
            }
        })
        .detach();
    }

    /// A tapped birthday notification: the card opens, or waits for the book.
    pub(crate) fn open_contact_from_event(&mut self, id: i64, cx: &mut Context<Self>) {
        match &self.book {
            Book::Ready(index) if index.all().any(|c| c.id == id) => self.open_contact(id, cx),
            Book::Ready(_) => {
                self.notice = Some("That contact is no longer in your address book.".into());
                cx.notify();
            }
            _ => self.pending_open = Some(id),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pill_names_the_hour_when_on() {
        assert_eq!(
            toggle_notice(true, "9:00"),
            "Birthday reminders on, at 9:00"
        );
        assert_eq!(toggle_notice(false, "9:00"), "Birthday reminders off");
        assert!(DENIED.ends_with("permission is off"));
    }
}
