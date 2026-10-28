//! The call model: stores the last snapshot Android reported and answers
//! queries about it. It never infers state from commands (AD-4).
//!
//! Also home to the pure formatting helpers used by the In-Call Screen:
//! [`format_elapsed`], [`caller_label`] and [`status_text`] (AD-7).

use crate::phone_number::PhoneNumber;
use crate::small_text::SmallText;
pub use crate::wire::{AudioRoute, DisconnectCause};
use crate::wire::{AudioState, CallSnapshot, WireCall, WireCallState};
use std::borrow::Cow;

/// A call's state with its disconnect cause folded in. `Copy`, so views can
/// match on it without borrowing the snapshot.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum CallState {
    Dialing,
    Ringing,
    Active,
    Holding,
    Disconnected {
        cause: DisconnectCause,
    },
    /// A state this build does not know; renders as an inert call.
    Unknown,
}

impl CallState {
    pub fn is_disconnected(self) -> bool {
        matches!(self, Self::Disconnected { .. })
    }
}

impl WireCall {
    /// The typed state of this call.
    pub fn call_state(&self) -> CallState {
        match self.state {
            WireCallState::Dialing => CallState::Dialing,
            WireCallState::Ringing => CallState::Ringing,
            WireCallState::Active => CallState::Active,
            WireCallState::Holding => CallState::Holding,
            WireCallState::Disconnected => CallState::Disconnected {
                cause: self.disconnect_cause.unwrap_or_default(),
            },
            WireCallState::Unknown => CallState::Unknown,
        }
    }
}

/// Holds the most recent [`CallSnapshot`].
#[derive(Clone, Debug, Default)]
pub struct CallModel {
    snapshot: CallSnapshot,
    /// `None` until the first snapshot is applied, so seq 0 is accepted.
    held_seq: Option<u64>,
}

impl CallModel {
    pub fn new() -> Self {
        Self::default()
    }

    /// The reducer. A snapshot whose `seq` is lower than the held one is
    /// ignored; equal or higher replaces the state. Returns whether the
    /// state was replaced. The snapshot is moved in, so there is no copy.
    pub fn apply(&mut self, snapshot: CallSnapshot) -> bool {
        if self.held_seq.is_some_and(|held| snapshot.seq < held) {
            return false;
        }
        self.held_seq = Some(snapshot.seq);
        self.snapshot = snapshot;
        true
    }

    /// Forgets everything, e.g. when the session sink is replaced.
    pub fn reset(&mut self) {
        *self = Self::default();
    }

    pub fn snapshot(&self) -> &CallSnapshot {
        &self.snapshot
    }

    pub fn seq(&self) -> Option<u64> {
        self.held_seq
    }

    pub fn calls(&self) -> &[WireCall] {
        &self.snapshot.calls
    }

    pub fn has_calls(&self) -> bool {
        !self.snapshot.calls.is_empty()
    }

    pub fn call(&self, id: i32) -> Option<&WireCall> {
        self.snapshot.calls.iter().find(|c| c.id == id)
    }

    /// The call Kotlin named as foreground, if it is present in `calls`.
    /// Never re-selects.
    pub fn foreground(&self) -> Option<&WireCall> {
        self.snapshot
            .foreground_call_id
            .and_then(|id| self.call(id))
    }

    /// The call Kotlin named as waiting, if it is present in `calls`.
    pub fn waiting(&self) -> Option<&WireCall> {
        self.snapshot.waiting_call_id.and_then(|id| self.call(id))
    }

    pub fn audio(&self) -> &AudioState {
        &self.snapshot.audio
    }

    /// AD-3: a payload that omitted the flag, or no snapshot yet, is locked.
    pub fn keyguard_locked(&self) -> bool {
        self.snapshot.keyguard_locked
    }

    /// AD-3 call-only mode: locked with at least one call renders only the
    /// Incoming or In-Call screen.
    pub fn is_call_only_mode(&self) -> bool {
        self.snapshot.keyguard_locked && self.has_calls()
    }

    pub fn now_ms(&self) -> i64 {
        self.snapshot.now_ms
    }
}

/// `m:ss` or `h:mm:ss`. Longest output is `999:59:59` (9 bytes).
pub type ElapsedText = SmallText<12>;

/// Elapsed call time from wall clock (AD-7). Empty when `connect_time_ms`
/// is 0 (not connected); clock skew (`now < connect`) clamps to `0:00`.
pub fn format_elapsed(now_ms: i64, connect_time_ms: i64) -> ElapsedText {
    let mut out = ElapsedText::new();
    if connect_time_ms <= 0 {
        return out;
    }
    let total_s = (now_ms.saturating_sub(connect_time_ms)).max(0) / 1000;
    let total_s = total_s as u64;
    let hours = (total_s / 3600).min(999);
    let minutes = (total_s / 60) % 60;
    let seconds = total_s % 60;
    if hours > 0 {
        out.push_padded(hours, 1);
        out.push_str(":");
        out.push_padded(minutes, 2);
    } else {
        out.push_padded(minutes, 1);
    }
    out.push_str(":");
    out.push_padded(seconds, 2);
    out
}

/// Groups a parsed number for display. Turkish layouts are special-cased
/// (`+90 555 111 22 33`, `0555 111 22 33`); other numbers keep their
/// canonical digits. Numbers are never reformatted for dialing (AD-11).
pub fn format_number(number: &PhoneNumber) -> String {
    let canonical = number.as_str();
    if let Some(rest) = canonical.strip_prefix("+90")
        && rest.len() == 10
    {
        return format!(
            "+90 {} {} {} {}",
            &rest[0..3],
            &rest[3..6],
            &rest[6..8],
            &rest[8..10]
        );
    }
    if !number.is_international() {
        match canonical.len() {
            11 if canonical.starts_with('0') => {
                return format!(
                    "{} {} {} {}",
                    &canonical[0..4],
                    &canonical[4..7],
                    &canonical[7..9],
                    &canonical[9..11]
                );
            }
            10 => {
                return format!(
                    "{} {} {} {}",
                    &canonical[0..3],
                    &canonical[3..6],
                    &canonical[6..8],
                    &canonical[8..10]
                );
            }
            _ => {}
        }
    }
    canonical.to_owned()
}

/// What to show as the caller: the contact name, else the formatted number,
/// else the number as entered, else "Unknown" for hidden or empty numbers.
pub fn caller_label<'a>(
    display_name: Option<&'a str>,
    number_raw: &'a str,
    hidden: bool,
) -> Cow<'a, str> {
    if let Some(name) = display_name.map(str::trim).filter(|n| !n.is_empty()) {
        return Cow::Borrowed(name);
    }
    let raw = number_raw.trim();
    if hidden || raw.is_empty() {
        return Cow::Borrowed("Unknown");
    }
    match PhoneNumber::parse(raw) {
        Ok(number) => Cow::Owned(format_number(&number)),
        Err(_) => Cow::Borrowed(raw),
    }
}

/// Whether a ringing call that ended without being answered here was missed
/// (the owner should call back), as opposed to declined or taken elsewhere.
/// Telecom reports a ring-out as `Missed`, a caller who hangs up first as
/// `Remote`, and a caller who cancels as `Canceled`; all three land in the
/// call log as missed (story 5.5).
pub fn is_missed(cause: DisconnectCause) -> bool {
    matches!(
        cause,
        DisconnectCause::Missed | DisconnectCause::Remote | DisconnectCause::Canceled
    )
}

/// "Missed call from {caller}": shown for a moment where "Incoming call" was.
pub fn missed_headline(caller: &str) -> String {
    format!("Missed call from {caller}")
}

/// What TalkBack announces when the Incoming Call screen appears.
pub fn incoming_announcement(caller: &str) -> String {
    format!("Incoming call from {caller}")
}

/// The status line under the caller name.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct StatusText {
    headline: Headline,
    reason: Option<&'static str>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Headline {
    Static(&'static str),
    Elapsed(ElapsedText),
}

impl StatusText {
    /// "Calling...", the timer, "On hold", "Call ended" or "Call failed".
    pub fn headline(&self) -> &str {
        match &self.headline {
            Headline::Static(s) => s,
            Headline::Elapsed(t) => t.as_str(),
        }
    }

    /// Second line for failed calls: "Number busy", "Out of service" or
    /// "Number not reachable".
    pub fn reason(&self) -> Option<&'static str> {
        self.reason
    }
}

pub fn status_text(state: CallState, now_ms: i64, connect_time_ms: i64) -> StatusText {
    let (headline, reason) = match state {
        CallState::Dialing => (Headline::Static("Calling..."), None),
        CallState::Ringing => (Headline::Static("Incoming call"), None),
        CallState::Active => {
            let timer = if connect_time_ms > 0 {
                format_elapsed(now_ms, connect_time_ms)
            } else {
                // Active but Telecom has not stamped a connect time yet.
                format_elapsed(1, 1)
            };
            (Headline::Elapsed(timer), None)
        }
        CallState::Holding => (Headline::Static("On hold"), None),
        CallState::Disconnected { cause } => match cause {
            DisconnectCause::Busy => (Headline::Static("Call failed"), Some("Number busy")),
            DisconnectCause::OutOfService => {
                (Headline::Static("Call failed"), Some("Out of service"))
            }
            DisconnectCause::Unreachable => (
                Headline::Static("Call failed"),
                Some("Number not reachable"),
            ),
            DisconnectCause::Error => (Headline::Static("Call failed"), None),
            _ => (Headline::Static("Call ended"), None),
        },
        CallState::Unknown => (Headline::Static(""), None),
    };
    StatusText { headline, reason }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::{CallSnapshot, WireCall};

    fn call(id: i32, state: WireCallState) -> WireCall {
        WireCall {
            id,
            state,
            number_raw: String::new(),
            normalized: String::new(),
            display_name: None,
            contact_id: None,
            connect_time_ms: 0,
            can_hold: false,
            is_incoming: false,
            disconnect_cause: None,
        }
    }

    fn snap(seq: u64) -> CallSnapshot {
        CallSnapshot {
            seq,
            ..CallSnapshot::default()
        }
    }

    // ---- reducer -------------------------------------------------------

    #[test]
    fn a_ring_out_or_a_hang_up_is_missed_but_a_decline_is_not() {
        assert!(is_missed(DisconnectCause::Missed));
        assert!(is_missed(DisconnectCause::Remote));
        assert!(is_missed(DisconnectCause::Canceled));
        assert!(!is_missed(DisconnectCause::Rejected));
        assert!(!is_missed(DisconnectCause::Local));
        assert!(!is_missed(DisconnectCause::Unknown));
    }

    #[test]
    fn incoming_copy_names_the_caller() {
        assert_eq!(missed_headline("Ada"), "Missed call from Ada");
        assert_eq!(incoming_announcement("Ada"), "Incoming call from Ada");
    }

    #[test]
    fn first_snapshot_is_accepted_even_at_seq_zero() {
        let mut m = CallModel::new();
        assert_eq!(m.seq(), None);
        assert!(m.apply(snap(0)));
        assert_eq!(m.seq(), Some(0));
    }

    #[test]
    fn lower_seq_is_ignored() {
        let mut m = CallModel::new();
        let mut a = snap(5);
        a.now_ms = 111;
        assert!(m.apply(a));
        let mut stale = snap(4);
        stale.now_ms = 999;
        assert!(!m.apply(stale));
        assert_eq!(m.now_ms(), 111);
        assert_eq!(m.seq(), Some(5));
    }

    #[test]
    fn equal_or_higher_seq_replaces() {
        let mut m = CallModel::new();
        m.apply(snap(5));
        let mut same = snap(5);
        same.now_ms = 7;
        assert!(m.apply(same));
        assert_eq!(m.now_ms(), 7);
        let mut newer = snap(6);
        newer.now_ms = 8;
        assert!(m.apply(newer));
        assert_eq!(m.now_ms(), 8);
    }

    #[test]
    fn reset_accepts_a_lower_seq_again() {
        let mut m = CallModel::new();
        m.apply(snap(9));
        m.reset();
        assert!(m.apply(snap(1)));
    }

    #[test]
    fn foreground_and_waiting_are_exactly_as_given() {
        let mut s = snap(1);
        s.calls = vec![
            call(1, WireCallState::Holding),
            call(2, WireCallState::Ringing),
        ];
        // Deliberately "wrong" selection: the model must not second-guess it.
        s.foreground_call_id = Some(1);
        s.waiting_call_id = Some(2);
        let mut m = CallModel::new();
        m.apply(s);
        assert_eq!(m.foreground().map(|c| c.id), Some(1));
        assert_eq!(m.waiting().map(|c| c.id), Some(2));
    }

    #[test]
    fn missing_ids_yield_none_without_panic() {
        let mut s = snap(1);
        s.calls = vec![call(1, WireCallState::Active)];
        s.foreground_call_id = Some(99);
        s.waiting_call_id = None;
        let mut m = CallModel::new();
        m.apply(s);
        assert!(m.foreground().is_none());
        assert!(m.waiting().is_none());
        assert!(m.call(1).is_some());
    }

    #[test]
    fn empty_model_is_locked_and_callless() {
        let m = CallModel::new();
        assert!(m.keyguard_locked());
        assert!(!m.has_calls());
        assert!(!m.is_call_only_mode());
        assert!(m.foreground().is_none());
    }

    #[test]
    fn absent_keyguard_flag_parses_as_locked_and_call_only() {
        let s: CallSnapshot =
            serde_json::from_str(r#"{"seq":1,"calls":[{"id":1,"state":"ringing"}]}"#)
                .expect("parse");
        let mut m = CallModel::new();
        m.apply(s);
        assert!(m.keyguard_locked());
        assert!(m.is_call_only_mode());
    }

    #[test]
    fn unlocked_with_calls_is_not_call_only() {
        let mut s = snap(1);
        s.keyguard_locked = false;
        s.calls = vec![call(1, WireCallState::Active)];
        let mut m = CallModel::new();
        m.apply(s);
        assert!(!m.is_call_only_mode());
    }

    #[test]
    fn call_state_folds_in_the_cause() {
        let mut c = call(1, WireCallState::Disconnected);
        assert_eq!(
            c.call_state(),
            CallState::Disconnected {
                cause: DisconnectCause::Unknown
            }
        );
        c.disconnect_cause = Some(DisconnectCause::Busy);
        assert_eq!(
            c.call_state(),
            CallState::Disconnected {
                cause: DisconnectCause::Busy
            }
        );
        assert!(c.call_state().is_disconnected());
        assert_eq!(
            call(1, WireCallState::Unknown).call_state(),
            CallState::Unknown
        );
        assert_eq!(
            call(1, WireCallState::Dialing).call_state(),
            CallState::Dialing
        );
        assert_eq!(
            call(1, WireCallState::Ringing).call_state(),
            CallState::Ringing
        );
        assert_eq!(
            call(1, WireCallState::Holding).call_state(),
            CallState::Holding
        );
    }

    // ---- elapsed -------------------------------------------------------

    const T0: i64 = 1_790_000_000_000;

    fn el(secs: i64) -> String {
        format_elapsed(T0 + secs * 1000, T0).to_string()
    }

    #[test]
    fn elapsed_table() {
        let cases = [
            (0, "0:00"),
            (1, "0:01"),
            (9, "0:09"),
            (59, "0:59"),
            (65, "1:05"),
            (599, "9:59"),
            (3599, "59:59"),
            (3600, "1:00:00"),
            (12 * 3600 + 3 * 60 + 7, "12:03:07"),
        ];
        for (secs, want) in cases {
            assert_eq!(el(secs), want, "{secs}s");
        }
    }

    #[test]
    fn elapsed_truncates_sub_second() {
        assert_eq!(format_elapsed(T0 + 999, T0), "0:00");
        assert_eq!(format_elapsed(T0 + 1999, T0), "0:01");
    }

    #[test]
    fn elapsed_clock_skew_is_zero() {
        assert_eq!(format_elapsed(T0 - 5000, T0), "0:00");
    }

    #[test]
    fn elapsed_not_connected_is_empty() {
        assert!(format_elapsed(T0, 0).is_empty());
        assert!(format_elapsed(T0, -1).is_empty());
    }

    #[test]
    fn elapsed_extreme_values_do_not_panic() {
        let t = format_elapsed(i64::MAX, 1);
        assert!(t.as_str().starts_with("999:"), "{t}");
        assert!(!format_elapsed(i64::MIN, 1).is_empty());
    }

    // ---- caller label --------------------------------------------------

    #[test]
    fn label_prefers_the_name() {
        assert_eq!(caller_label(Some("Mehmet"), "0555", false), "Mehmet");
        assert!(matches!(
            caller_label(Some("Mehmet"), "0555", false),
            Cow::Borrowed(_)
        ));
    }

    #[test]
    fn label_blank_name_falls_through() {
        assert_eq!(
            caller_label(Some("  "), "05551112233", false),
            "0555 111 22 33"
        );
    }

    #[test]
    fn label_formats_parsable_numbers() {
        assert_eq!(
            caller_label(None, "+905551112233", false),
            "+90 555 111 22 33"
        );
        assert_eq!(caller_label(None, "05551112233", false), "0555 111 22 33");
        assert_eq!(caller_label(None, "5551112233", false), "555 111 22 33");
        assert_eq!(
            caller_label(None, "+4915123456789", false),
            "+4915123456789"
        );
        assert_eq!(caller_label(None, "112", false), "112");
    }

    #[test]
    fn label_unparsable_number_as_entered() {
        assert_eq!(caller_label(None, "*#06#", false), "*#06#");
        assert_eq!(caller_label(None, " ANKARA ", false), "ANKARA");
    }

    #[test]
    fn label_hidden_or_empty_is_unknown() {
        assert_eq!(caller_label(None, "", false), "Unknown");
        assert_eq!(caller_label(None, "   ", false), "Unknown");
        assert_eq!(caller_label(None, "05551112233", true), "Unknown");
        assert_eq!(caller_label(None, "", true), "Unknown");
    }

    #[test]
    fn hidden_still_shows_a_name_if_one_exists() {
        assert_eq!(caller_label(Some("Ada"), "", true), "Ada");
    }

    // ---- status --------------------------------------------------------

    #[test]
    fn status_table() {
        let now = T0 + 65_000;
        let s = status_text(CallState::Dialing, now, 0);
        assert_eq!((s.headline(), s.reason()), ("Calling...", None));
        let s = status_text(CallState::Active, now, T0);
        assert_eq!((s.headline(), s.reason()), ("1:05", None));
        let s = status_text(CallState::Holding, now, T0);
        assert_eq!((s.headline(), s.reason()), ("On hold", None));
        let s = status_text(CallState::Ringing, now, 0);
        assert_eq!(s.headline(), "Incoming call");
        assert_eq!(status_text(CallState::Unknown, now, 0).headline(), "");
    }

    #[test]
    fn active_before_connect_time_shows_zero() {
        assert_eq!(status_text(CallState::Active, T0, 0).headline(), "0:00");
    }

    #[test]
    fn status_disconnected_causes() {
        let cases = [
            (DisconnectCause::Local, "Call ended", None),
            (DisconnectCause::Remote, "Call ended", None),
            (DisconnectCause::Missed, "Call ended", None),
            (DisconnectCause::Rejected, "Call ended", None),
            (DisconnectCause::Canceled, "Call ended", None),
            (DisconnectCause::Unknown, "Call ended", None),
            (DisconnectCause::Busy, "Call failed", Some("Number busy")),
            (
                DisconnectCause::OutOfService,
                "Call failed",
                Some("Out of service"),
            ),
            (
                DisconnectCause::Unreachable,
                "Call failed",
                Some("Number not reachable"),
            ),
            (DisconnectCause::Error, "Call failed", None),
        ];
        for (cause, headline, reason) in cases {
            let s = status_text(CallState::Disconnected { cause }, T0, T0);
            assert_eq!((s.headline(), s.reason()), (headline, reason), "{cause:?}");
        }
    }
}
