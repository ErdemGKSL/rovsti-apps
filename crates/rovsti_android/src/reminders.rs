//! Call-back reminders (`CallReminders.kt`, reached through `RovstiBridge`).
//!
//! One inexact `AlarmManager` alarm per reminder (`setAndAllowWhileIdle`, so no
//! exact-alarm permission). The payload is `rovsti_core::reminder::arm_json`:
//! the reminder plus the finished notification `title` and `body`. Alarms are
//! lost on reboot, so the app calls [`rearm_all`] at launch (there is no boot
//! receiver). When one fires, Kotlin posts the notification on the `reminders`
//! channel and emits the `reminder_fired` event `{id}`. On desktop every
//! function returns [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Stores the payload and sets the alarm. `false` for a malformed payload.
pub fn arm(reminder_json: &str) -> Result<bool> {
    bridge::call_bool("armCallReminder", &[Arg::Str(reminder_json)])
}

/// Cancels the alarm and forgets the payload. `false` when it was not armed.
pub fn disarm(id: i64) -> Result<bool> {
    bridge::call_bool("disarmCallReminder", &[Arg::Str(&id.to_string())])
}

/// The armed reminders as a JSON array.
pub fn armed() -> Result<String> {
    bridge::call_string("armedCallReminders", &[])
}

/// Sets every stored alarm again (due ones fire at once).
pub fn rearm_all() -> Result<bool> {
    bridge::call_bool("rearmCallReminders", &[])
}

/// Message reminders (`MessageReminders.kt`): the same alarm scheme on the
/// `message_reminders` channel; a tap opens the chat and the message, and
/// `message_reminder_fired` `{id}` is emitted. Stores the payload and sets the
/// alarm. `false` for a malformed payload.
pub fn arm_message(reminder_json: &str) -> Result<bool> {
    bridge::call_bool("armMessageReminder", &[Arg::Str(reminder_json)])
}

/// Cancels the message reminder's alarm and forgets it. `false` when it was not armed.
pub fn disarm_message(id: i64) -> Result<bool> {
    bridge::call_bool("disarmMessageReminder", &[Arg::Str(&id.to_string())])
}

/// The armed message reminders as a JSON array.
pub fn armed_messages() -> Result<String> {
    bridge::call_string("armedMessageReminders", &[])
}

/// Sets every stored message-reminder alarm again (due ones fire at once).
pub fn rearm_messages() -> Result<bool> {
    bridge::call_bool("rearmMessageReminders", &[])
}

/// Birthday reminders (`BirthdayReminders.kt`): the same alarm scheme on the
/// `birthdays` channel; a tap queues the one-shot `open_contact` `{id}`. The
/// payload is `{id, contact_id, at_ms, title, body}`. Stores it and sets the
/// alarm. `false` for a malformed payload.
pub fn arm_birthday(reminder_json: &str) -> Result<bool> {
    bridge::call_bool("armBirthday", &[Arg::Str(reminder_json)])
}

/// Cancels the birthday alarm and forgets it. `false` when it was not armed.
pub fn disarm_birthday(id: i64) -> Result<bool> {
    bridge::call_bool("disarmBirthday", &[Arg::Str(&id.to_string())])
}

/// The armed birthday reminders as a JSON array.
pub fn armed_birthdays() -> Result<String> {
    bridge::call_string("armedBirthdays", &[])
}

/// Sets every stored birthday alarm again (due ones fire at once).
pub fn rearm_birthdays() -> Result<bool> {
    bridge::call_bool("rearmBirthdays", &[])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(arm("{}"), Err(Error::Unsupported)));
        assert!(matches!(disarm(1), Err(Error::Unsupported)));
        assert!(matches!(armed(), Err(Error::Unsupported)));
        assert!(matches!(rearm_all(), Err(Error::Unsupported)));
        assert!(matches!(arm_message("{}"), Err(Error::Unsupported)));
        assert!(matches!(disarm_message(1), Err(Error::Unsupported)));
        assert!(matches!(armed_messages(), Err(Error::Unsupported)));
        assert!(matches!(rearm_messages(), Err(Error::Unsupported)));
        assert!(matches!(arm_birthday("{}"), Err(Error::Unsupported)));
        assert!(matches!(disarm_birthday(1), Err(Error::Unsupported)));
        assert!(matches!(armed_birthdays(), Err(Error::Unsupported)));
        assert!(matches!(rearm_birthdays(), Err(Error::Unsupported)));
    }
}
