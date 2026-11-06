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
    }
}
