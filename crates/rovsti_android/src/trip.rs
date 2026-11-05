//! The Rovsti Stops trip seam: a foreground location service and a native alarm
//! (AD-ST2, AD-ST3). Kotlin side: `TripService`, `TripAlarm`, `AlarmActivity`.
//!
//! Rust never touches location itself. Kotlin owns the updates, calls the app's
//! headless entry (`trip_fix`, `trip_alarm`) with each fix or alarm action and
//! applies the answer. This module only starts and stops that machinery and
//! decodes the `trip_alarm` event. On desktop every function returns
//! [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// What the user did to a ringing alarm (or a test ring).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AlarmAction {
    Dismiss,
    Snooze,
    /// Rings once so the user can check volume and the full-screen permission.
    Test,
}

impl AlarmAction {
    /// The string `RovstiBridge.tripAlarm` understands.
    pub fn key(self) -> &'static str {
        match self {
            Self::Dismiss => "dismiss",
            Self::Snooze => "snooze",
            Self::Test => "test",
        }
    }
}

/// Starts (or updates) the foreground service. `spec_json` is
/// `{dir, title, text, next_fix_ms, high_accuracy}`: `dir` is the app data
/// directory Kotlin passes back to the headless entry. `false` when the service
/// could not start (no location permission, or the app is not allowed to start
/// a foreground service from the background).
pub fn start(spec_json: &str) -> Result<bool> {
    bridge::call_bool("tripStart", &[Arg::Str(spec_json)])
}

/// Stops the service and removes its notification. Does not touch the alarm.
pub fn stop() -> Result<bool> {
    bridge::call_bool("tripStop", &[])
}

/// Whether the service is running in this process.
pub fn is_running() -> Result<bool> {
    bridge::call_bool("tripRunning", &[])
}

/// Dismisses, snoozes or test-rings the alarm. For `Snooze` the headless entry
/// decides the next ring time and Kotlin arms it; the answer is a `trip_alarm`
/// event.
pub fn alarm(action: AlarmAction) -> Result<bool> {
    bridge::call_bool("tripAlarm", &[Arg::Str(action.key())])
}

/// Arms the snooze re-ring at `at_ms` (epoch milliseconds). The bridge takes a
/// string because the JNI seam has no 64-bit argument.
pub fn arm_snooze(at_ms: i64) -> Result<bool> {
    bridge::call_bool("tripSnooze", &[Arg::Str(&at_ms.to_string())])
}

/// `AlarmManager.canScheduleExactAlarms`. `USE_EXACT_ALARM` is granted for alarm
/// apps, so this is normally true; `false` means snoozes fall back to inexact
/// timing.
pub fn can_schedule_exact_alarms() -> Result<bool> {
    bridge::call_bool("canScheduleExactAlarms", &[])
}

/// A decoded `trip_alarm` event.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TripAlarmEvent {
    Ringing,
    Snoozed { at_ms: i64 },
    Dismissed,
}

/// Decodes a `trip_alarm` payload: `{"event":"ringing"}`,
/// `{"event":"snoozed","at_ms":1700000000000}` or `{"event":"dismissed"}`.
/// `None` for malformed JSON, an unknown event, or `snoozed` without a
/// positive `at_ms`.
pub fn parse_trip_alarm(json: &str) -> Option<TripAlarmEvent> {
    let value: serde_json::Value = serde_json::from_str(json).ok()?;
    match value.get("event")?.as_str()? {
        "ringing" => Some(TripAlarmEvent::Ringing),
        "dismissed" => Some(TripAlarmEvent::Dismissed),
        "snoozed" => {
            let at_ms = value.get("at_ms")?.as_i64().filter(|ms| *ms > 0)?;
            Some(TripAlarmEvent::Snoozed { at_ms })
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alarm_events_parse() {
        let cases: [(&str, Option<TripAlarmEvent>); 11] = [
            (r#"{"event":"ringing"}"#, Some(TripAlarmEvent::Ringing)),
            (r#"{"event":"dismissed"}"#, Some(TripAlarmEvent::Dismissed)),
            (
                r#"{"event":"snoozed","at_ms":1700000000000}"#,
                Some(TripAlarmEvent::Snoozed {
                    at_ms: 1_700_000_000_000,
                }),
            ),
            (r#"{"event":"snoozed"}"#, None),
            (r#"{"event":"snoozed","at_ms":0}"#, None),
            (r#"{"event":"snoozed","at_ms":"5"}"#, None),
            (r#"{"event":"exploded"}"#, None),
            (r#"{"event":7}"#, None),
            (r#"{}"#, None),
            ("nope", None),
            ("", None),
        ];
        for (json, want) in cases {
            assert_eq!(parse_trip_alarm(json), want, "{json}");
        }
    }

    #[test]
    fn action_keys_are_stable() {
        assert_eq!(AlarmAction::Dismiss.key(), "dismiss");
        assert_eq!(AlarmAction::Snooze.key(), "snooze");
        assert_eq!(AlarmAction::Test.key(), "test");
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn desktop_is_unsupported() {
        use crate::Error;
        assert!(matches!(start("{}"), Err(Error::Unsupported)));
        assert!(matches!(stop(), Err(Error::Unsupported)));
        assert!(matches!(is_running(), Err(Error::Unsupported)));
        for action in [AlarmAction::Dismiss, AlarmAction::Snooze, AlarmAction::Test] {
            assert!(matches!(alarm(action), Err(Error::Unsupported)));
        }
        assert!(matches!(arm_snooze(1), Err(Error::Unsupported)));
        assert!(matches!(
            can_schedule_exact_alarms(),
            Err(Error::Unsupported)
        ));
    }
}
