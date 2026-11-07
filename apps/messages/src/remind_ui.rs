//! "Remind me" on a message: a time sheet over the chat, the pills that follow it and the
//! confirm before a reminder is cancelled. The wording, slots and storage are pure
//! (`rovsti_core::reminder::Slot`, `rovsti_core::message_reminder`); the alarm is armed by
//! `Backend::arm_message_reminder`.

use gpui::{
    AnyElement, Context, IntoElement, ParentElement, StatefulInteractiveElement, Styled, div, px,
};
use rovsti_android::Error;
use rovsti_core::{
    message_reminder::{self, MessageReminder},
    reminder::Slot,
};
use rovsti_ui::{
    Icon, theme,
    tokens::{Theme, typography},
};

use crate::{MessagesApp, Open, PillAction, menu_card, menu_item, now_ms};

/// What a tap on the sheet asks for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RemindEvent {
    /// Remind at this slot.
    Set(Slot),
    /// Drop the message's reminder (after the confirm).
    Cancel,
}

/// The open sheet: which message, in which chat.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RemindSheet {
    pub tag: String,
    pub title: String,
    pub message_id: String,
    pub message_at_ms: i64,
    pub preview: String,
    /// "Cancel the reminder?" shows instead of the slots.
    pub confirm_cancel: bool,
}

/// Message id -> badge text ("Reminder 18:05") for the reminders of the open chat.
pub fn badges(list: &[MessageReminder], tag: &str, tz_offset_s: i32) -> Vec<(String, String)> {
    list.iter()
        .filter(|r| r.tag == tag && !r.message_id.is_empty())
        .map(|r| {
            (
                r.message_id.clone(),
                message_reminder::badge_text(r, tz_offset_s),
            )
        })
        .collect()
}

/// The pill after a failed `arm`: Unsupported (desktop) and a refusal read differently.
pub fn failure_text(result: &Result<bool, Error>) -> Option<&'static str> {
    match result {
        Ok(true) => None,
        Err(Error::Unsupported) => Some("Reminders need an Android phone."),
        Ok(false) | Err(_) => Some("Couldn't set the reminder"),
    }
}

/// The sheet's rows as (event, label): the slots, then Cancel when one is armed.
pub fn rows(
    existing: Option<&MessageReminder>,
    now_ms: i64,
    tz_offset_s: i32,
) -> Vec<(RemindEvent, String)> {
    let mut out: Vec<(RemindEvent, String)> = Slot::ALL
        .into_iter()
        .map(|slot| (RemindEvent::Set(slot), slot.label(now_ms, tz_offset_s)))
        .collect();
    if let Some(r) = existing {
        out.push((
            RemindEvent::Cancel,
            format!(
                "Cancel reminder ({})",
                rovsti_core::reminder::clock(r.at_ms, tz_offset_s)
            ),
        ));
    }
    out
}

impl MessagesApp {
    /// Opens the sheet for a message of the open chat.
    pub(crate) fn open_remind(
        &mut self,
        message_id: String,
        message_at_ms: i64,
        preview: String,
        cx: &mut Context<Self>,
    ) {
        let Some(thread) = self.open_thread_id(cx) else {
            return;
        };
        let tag = crate::notifications::thread_tag(&thread);
        let title = match &self.open {
            Open::WhatsApp(_, c) => Some(c.read(cx).title().to_string()),
            Open::Sms(c) => Some(c.read(cx).title().to_string()),
            _ => None,
        }
        .or_else(|| self.title_of(&thread))
        .unwrap_or_default();
        self.remind = Some(RemindSheet {
            tag,
            title,
            message_id,
            message_at_ms,
            preview,
            confirm_cancel: false,
        });
        cx.notify();
    }

    /// The open chat's reminder badges, for its bubbles.
    pub(crate) fn reminder_badges(&self, cx: &Context<Self>) -> Vec<(String, String)> {
        let Some(thread) = self.open_thread_id(cx) else {
            return Vec::new();
        };
        let backend = self.backend.read(cx);
        badges(
            backend.message_reminders(),
            &crate::notifications::thread_tag(&thread),
            backend.locale().tz_offset_s,
        )
    }

    /// The sheet over the open chat.
    pub(crate) fn remind_overlay(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let sheet = self.remind.as_ref()?;
        let colors = theme::colors(theme);
        let (now, tz) = (now_ms(), self.backend.read(cx).locale().tz_offset_s);
        let dismiss = cx.listener(|this, _, _, cx| {
            this.remind = None;
            cx.notify();
        });
        if sheet.confirm_cancel {
            let items = vec![
                div()
                    .px(px(16.0))
                    .py(px(8.0))
                    .text_size(typography::BODY.size)
                    .text_color(colors.label)
                    .child("Cancel the reminder?")
                    .into_any_element(),
                menu_item(
                    theme,
                    "remind-cancel-yes",
                    Icon::LucideBellOff,
                    "Cancel reminder".into(),
                    colors.red,
                )
                .on_click(cx.listener(|this, _, _, cx| this.remind_act(RemindEvent::Cancel, cx)))
                .into_any_element(),
                menu_item(
                    theme,
                    "remind-cancel-keep",
                    Icon::LucideX,
                    "Keep it".into(),
                    colors.label,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    if let Some(sheet) = &mut this.remind {
                        sheet.confirm_cancel = false;
                    }
                    cx.notify();
                }))
                .into_any_element(),
            ];
            return Some(menu_card(
                theme,
                "remind-sheet",
                "Remind me".into(),
                items,
                dismiss,
            ));
        }
        let existing = message_reminder::for_message(
            self.backend.read(cx).message_reminders(),
            &sheet.message_id,
        );
        let items = rows(existing, now, tz)
            .into_iter()
            .enumerate()
            .map(|(index, (event, label))| {
                let (icon, color) = match event {
                    RemindEvent::Set(_) => (Icon::LucideAlarmClock, colors.label),
                    RemindEvent::Cancel => (Icon::LucideBellOff, colors.red),
                };
                menu_item(theme, ("remind-row", index), icon, label.into(), color)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        if event == RemindEvent::Cancel {
                            if let Some(sheet) = &mut this.remind {
                                sheet.confirm_cancel = true;
                            }
                            cx.notify();
                        } else {
                            this.remind_act(event, cx);
                        }
                    }))
                    .into_any_element()
            })
            .collect();
        Some(menu_card(
            theme,
            "remind-sheet",
            "Remind me".into(),
            items,
            dismiss,
        ))
    }

    /// One step back out of the sheet. `false` when it was not open.
    pub(crate) fn back_remind(&mut self, cx: &mut Context<Self>) -> bool {
        let Some(sheet) = &mut self.remind else {
            return false;
        };
        if sheet.confirm_cancel {
            sheet.confirm_cancel = false;
        } else {
            self.remind = None;
        }
        cx.notify();
        true
    }

    /// A slot was picked, or the cancel was confirmed.
    pub(crate) fn remind_act(&mut self, event: RemindEvent, cx: &mut Context<Self>) {
        let Some(sheet) = self.remind.take() else {
            return;
        };
        match event {
            RemindEvent::Set(slot) => self.set_reminder(&sheet, slot, cx),
            RemindEvent::Cancel => {
                let id = message_reminder::for_message(
                    self.backend.read(cx).message_reminders(),
                    &sheet.message_id,
                )
                .map(|r| r.id);
                if let Some(id) = id {
                    self.drop_reminder(id, cx);
                }
                self.show_pill("Reminder cancelled");
            }
        }
        cx.notify();
    }

    fn drop_reminder(&mut self, id: i64, cx: &mut Context<Self>) {
        self.backend.update(cx, |backend, cx| {
            backend.disarm_message_reminder(id, cx);
            backend.forget_message_reminder(id, cx);
        });
    }

    /// Undo of the "Reminder set" pill.
    pub(crate) fn undo_reminder(&mut self, id: i64, cx: &mut Context<Self>) {
        self.drop_reminder(id, cx);
        self.show_pill("Reminder cancelled");
        cx.notify();
    }

    fn set_reminder(&mut self, sheet: &RemindSheet, slot: Slot, cx: &mut Context<Self>) {
        let (now, tz) = (now_ms(), self.backend.read(cx).locale().tz_offset_s);
        let Some(reminder) = message_reminder::new_reminder(
            &sheet.tag,
            &sheet.message_id,
            sheet.message_at_ms,
            &sheet.title,
            &sheet.preview,
            slot,
            now,
            tz,
        ) else {
            self.show_pill("Couldn't set the reminder");
            return;
        };
        let old = message_reminder::for_message(
            self.backend.read(cx).message_reminders(),
            &sheet.message_id,
        )
        .map(|r| r.id);
        let task = self.backend.update(cx, |backend, cx| {
            backend.arm_message_reminder(&reminder, cx)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if let Some(text) = failure_text(&result) {
                    this.show_pill(text);
                } else {
                    // A second reminder for the same message replaces the first.
                    if let Some(old) = old {
                        this.backend
                            .update(cx, |backend, cx| backend.disarm_message_reminder(old, cx));
                    }
                    let text = message_reminder::set_text(&reminder, now, tz);
                    let id = reminder.id;
                    this.backend.update(cx, |backend, cx| {
                        backend.track_message_reminder(reminder, cx)
                    });
                    this.show_pill_action(text, "Undo", PillAction::UndoReminder(id), cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn armed() -> MessageReminder {
        message_reminder::new_reminder(
            "wa:a",
            "M1",
            1,
            "Ada",
            "hi",
            Slot::In1Hour,
            1_700_000_000_000,
            0,
        )
        .expect("reminder")
    }

    #[test]
    fn rows_list_every_slot_and_cancel_only_when_armed() {
        let none = rows(None, 1_700_000_000_000, 0);
        assert_eq!(none.len(), Slot::ALL.len());
        assert!(none.iter().all(|(e, _)| matches!(e, RemindEvent::Set(_))));
        let r = armed();
        let with = rows(Some(&r), 1_700_000_000_000, 0);
        assert_eq!(with.len(), Slot::ALL.len() + 1);
        let (event, label) = with.last().expect("last");
        assert_eq!(*event, RemindEvent::Cancel);
        assert_eq!(label, "Cancel reminder (23:13)");
    }

    #[test]
    fn badges_only_for_this_chat() {
        let r = armed();
        let list = vec![r];
        assert_eq!(
            badges(&list, "wa:a", 0),
            vec![("M1".to_owned(), "Reminder 23:13".to_owned())]
        );
        assert!(badges(&list, "wa:b", 0).is_empty());
    }

    #[test]
    fn failure_texts() {
        assert_eq!(failure_text(&Ok(true)), None);
        assert_eq!(
            failure_text(&Err(Error::Unsupported)),
            Some("Reminders need an Android phone.")
        );
        assert_eq!(failure_text(&Ok(false)), Some("Couldn't set the reminder"));
    }
}
