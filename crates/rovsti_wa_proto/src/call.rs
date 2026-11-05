//! The pure call state machine. The gateway drives it from whatsapp-rust
//! events and HTTP commands; apps drive a mirror of it from `CallUpdate`s.

use crate::event::CallUpdate;

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CallDirection {
    In,
    Out,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EndReason {
    /// We hung up an established or outgoing call.
    Hangup,
    /// Declined, by us (incoming) or by the peer (outgoing).
    Rejected,
    Missed,
    /// The peer ended an established call.
    Remote,
    Failed(String),
    /// Another linked device answered or declined.
    ElsewhereAnswered,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CallPhase {
    Ringing,
    Connecting,
    Active,
    Ended(EndReason),
}

impl CallPhase {
    pub fn is_ended(&self) -> bool {
        matches!(self, Self::Ended(_))
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CallInput {
    /// We accept an incoming call.
    Accept,
    /// We decline an incoming call.
    Reject,
    /// The peer picked up our outgoing call.
    RemoteAccepted,
    /// Media (relay) is flowing.
    MediaUp,
    RemoteTerminate,
    /// We hang up an outgoing or established call.
    Hangup,
    Timeout,
    AnsweredElsewhere,
    Failed(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CallMachine {
    direction: CallDirection,
    phase: CallPhase,
}

impl CallMachine {
    pub fn new(direction: CallDirection) -> Self {
        Self {
            direction,
            phase: CallPhase::Ringing,
        }
    }

    pub fn direction(&self) -> CallDirection {
        self.direction
    }

    pub fn phase(&self) -> &CallPhase {
        &self.phase
    }

    /// Applies an input. Returns the new phase when it changed, or `None` for
    /// an input that is invalid in the current state (the state is untouched).
    pub fn apply(&mut self, input: CallInput) -> Option<CallPhase> {
        use CallDirection::{In, Out};
        use CallInput as I;
        use CallPhase as P;
        let next = match (&self.phase, self.direction, input) {
            (P::Ended(_), _, _) => return None,
            (_, _, I::Failed(msg)) => P::Ended(EndReason::Failed(msg)),
            (P::Ringing, In, I::Accept) => P::Connecting,
            (P::Ringing, In, I::Reject) => P::Ended(EndReason::Rejected),
            (P::Ringing, In, I::AnsweredElsewhere) => P::Ended(EndReason::ElsewhereAnswered),
            (P::Ringing, In, I::RemoteTerminate) => P::Ended(EndReason::Missed),
            (P::Ringing, Out, I::RemoteAccepted) => P::Connecting,
            (P::Ringing, Out, I::RemoteTerminate) => P::Ended(EndReason::Rejected),
            (P::Ringing, Out, I::Hangup) => P::Ended(EndReason::Hangup),
            (P::Ringing, _, I::Timeout) => P::Ended(EndReason::Missed),
            (P::Connecting, _, I::MediaUp) => P::Active,
            (P::Connecting, _, I::Timeout) => P::Ended(EndReason::Failed("timeout".into())),
            (P::Connecting | P::Active, _, I::Hangup) => P::Ended(EndReason::Hangup),
            (P::Connecting | P::Active, _, I::RemoteTerminate) => P::Ended(EndReason::Remote),
            _ => return None,
        };
        self.phase = next.clone();
        Some(next)
    }
}

/// What the label shows when the peer has neither a name nor a phone number.
pub const FALLBACK_PEER_LABEL: &str = "WhatsApp call";

/// What a call screen shows, derived from the gateway's [`CallUpdate`]s. Pure:
/// the clock is passed in.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WaCallView {
    pub call_id: String,
    pub peer_label: String,
    pub direction: CallDirection,
    pub phase: CallPhase,
    pub is_video: bool,
    pub muted: bool,
    /// Whole seconds since the call became active; 0 before that.
    pub elapsed_s: u64,
}

impl WaCallView {
    /// Maps an update to a view. Not muted, no elapsed time yet.
    pub fn from_update(update: &CallUpdate, _now_ms: i64) -> Self {
        Self {
            call_id: update.call_id.clone(),
            peer_label: peer_label(update.peer_name.as_deref(), &update.peer),
            direction: update.direction,
            phase: update.phase.clone(),
            is_video: update.is_video,
            muted: false,
            elapsed_s: 0,
        }
    }

    #[must_use]
    pub fn with_muted(mut self, muted: bool) -> Self {
        self.muted = muted;
        self
    }

    /// Sets the elapsed time from the instant the call became active. An
    /// unset or future instant, or a call that is not active, shows 0.
    #[must_use]
    pub fn with_active_since(mut self, active_since_ms: Option<i64>, now_ms: i64) -> Self {
        self.elapsed_s = match (&self.phase, active_since_ms) {
            (CallPhase::Active, Some(since)) => u64::try_from((now_ms - since) / 1000).unwrap_or(0),
            _ => 0,
        };
        self
    }

    pub fn is_ringing_in(&self) -> bool {
        self.direction == CallDirection::In && self.phase == CallPhase::Ringing
    }
}

/// The name, else `+<digits>` from a phone JID, else [`FALLBACK_PEER_LABEL`].
/// Other JIDs (`@lid`, groups) are not phone numbers.
pub fn peer_label(name: Option<&str>, peer: &str) -> String {
    if let Some(name) = name.map(str::trim).filter(|n| !n.is_empty()) {
        return name.to_owned();
    }
    let (user, server) = peer.split_once('@').unwrap_or((peer, "s.whatsapp.net"));
    let user = user.split(':').next().unwrap_or_default();
    let phone =
        server == "s.whatsapp.net" && !user.is_empty() && user.bytes().all(|b| b.is_ascii_digit());
    if phone {
        format!("+{user}")
    } else {
        FALLBACK_PEER_LABEL.to_owned()
    }
}

#[cfg(test)]
mod view_tests {
    use super::*;

    fn update(name: Option<&str>, peer: &str, phase: CallPhase) -> CallUpdate {
        CallUpdate {
            call_id: "c1".into(),
            peer: peer.into(),
            peer_name: name.map(Into::into),
            direction: CallDirection::In,
            phase,
            is_video: false,
        }
    }

    #[test]
    fn label_falls_back_name_number_generic() {
        let jid = "905551234567@s.whatsapp.net";
        assert_eq!(peer_label(Some("Ayşe"), jid), "Ayşe");
        assert_eq!(peer_label(Some("  "), jid), "+905551234567");
        assert_eq!(peer_label(None, jid), "+905551234567");
        assert_eq!(
            peer_label(None, "905551234567:12@s.whatsapp.net"),
            "+905551234567"
        );
        assert_eq!(peer_label(None, "905551234567"), "+905551234567");
        assert_eq!(peer_label(None, "123@lid"), FALLBACK_PEER_LABEL);
        assert_eq!(peer_label(None, ""), FALLBACK_PEER_LABEL);
        assert_eq!(peer_label(None, "abc@s.whatsapp.net"), FALLBACK_PEER_LABEL);
    }

    #[test]
    fn view_maps_the_update() {
        let view = WaCallView::from_update(
            &update(Some("Ayşe"), "1@s.whatsapp.net", CallPhase::Ringing),
            0,
        );
        assert_eq!(view.call_id, "c1");
        assert_eq!(view.peer_label, "Ayşe");
        assert!(view.is_ringing_in());
        assert!(!view.muted);
        assert_eq!(view.elapsed_s, 0);
    }

    #[test]
    fn elapsed_counts_only_while_active() {
        let active = WaCallView::from_update(&update(None, "1", CallPhase::Active), 0);
        assert_eq!(
            active
                .clone()
                .with_active_since(Some(1_000), 66_500)
                .elapsed_s,
            65
        );
        assert_eq!(
            active
                .clone()
                .with_active_since(Some(9_000), 1_000)
                .elapsed_s,
            0
        );
        assert_eq!(active.with_active_since(None, 5_000).elapsed_s, 0);
        let ringing = WaCallView::from_update(&update(None, "1", CallPhase::Ringing), 0);
        assert_eq!(ringing.with_active_since(Some(0), 9_000).elapsed_s, 0);
    }

    #[test]
    fn muted_is_kept() {
        let view =
            WaCallView::from_update(&update(None, "1", CallPhase::Active), 0).with_muted(true);
        assert!(view.muted);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use CallDirection::{In, Out};
    use CallInput as I;

    fn run(dir: CallDirection, steps: &[CallInput]) -> CallMachine {
        let mut m = CallMachine::new(dir);
        for s in steps {
            m.apply(s.clone());
        }
        m
    }

    #[test]
    fn happy_paths() {
        let mut m = CallMachine::new(In);
        assert_eq!(m.apply(I::Accept), Some(CallPhase::Connecting));
        assert_eq!(m.apply(I::MediaUp), Some(CallPhase::Active));
        assert_eq!(
            m.apply(I::Hangup),
            Some(CallPhase::Ended(EndReason::Hangup))
        );

        let mut m = CallMachine::new(Out);
        assert_eq!(m.apply(I::RemoteAccepted), Some(CallPhase::Connecting));
        assert_eq!(m.apply(I::MediaUp), Some(CallPhase::Active));
        assert_eq!(
            m.apply(I::RemoteTerminate),
            Some(CallPhase::Ended(EndReason::Remote))
        );
    }

    #[test]
    fn transition_table() {
        use EndReason as E;
        // (direction, setup, input, expected)
        let table: Vec<(CallDirection, Vec<CallInput>, CallInput, Option<CallPhase>)> = vec![
            (In, vec![], I::Reject, Some(CallPhase::Ended(E::Rejected))),
            (
                In,
                vec![],
                I::RemoteTerminate,
                Some(CallPhase::Ended(E::Missed)),
            ),
            (In, vec![], I::Timeout, Some(CallPhase::Ended(E::Missed))),
            (
                In,
                vec![],
                I::AnsweredElsewhere,
                Some(CallPhase::Ended(E::ElsewhereAnswered)),
            ),
            (In, vec![], I::RemoteAccepted, None),
            (In, vec![], I::Hangup, None),
            (In, vec![], I::MediaUp, None),
            (Out, vec![], I::Accept, None),
            (Out, vec![], I::Reject, None),
            (Out, vec![], I::MediaUp, None),
            (Out, vec![], I::AnsweredElsewhere, None),
            (Out, vec![], I::Hangup, Some(CallPhase::Ended(E::Hangup))),
            (
                Out,
                vec![],
                I::RemoteTerminate,
                Some(CallPhase::Ended(E::Rejected)),
            ),
            (Out, vec![], I::Timeout, Some(CallPhase::Ended(E::Missed))),
            (In, vec![I::Accept], I::Accept, None),
            (In, vec![I::Accept], I::Reject, None),
            (
                In,
                vec![I::Accept],
                I::Timeout,
                Some(CallPhase::Ended(E::Failed("timeout".into()))),
            ),
            (
                In,
                vec![I::Accept],
                I::RemoteTerminate,
                Some(CallPhase::Ended(E::Remote)),
            ),
            (In, vec![I::Accept, I::MediaUp], I::MediaUp, None),
            (In, vec![I::Accept, I::MediaUp], I::Timeout, None),
            (
                Out,
                vec![I::RemoteAccepted, I::MediaUp],
                I::Hangup,
                Some(CallPhase::Ended(E::Hangup)),
            ),
            (
                Out,
                vec![],
                I::Failed("relay".into()),
                Some(CallPhase::Ended(E::Failed("relay".into()))),
            ),
            (
                In,
                vec![I::Accept, I::MediaUp],
                I::Failed("x".into()),
                Some(CallPhase::Ended(E::Failed("x".into()))),
            ),
        ];
        for (dir, setup, input, expected) in table {
            let mut m = run(dir, &setup);
            let before = m.phase().clone();
            assert_eq!(
                m.apply(input.clone()),
                expected,
                "{dir:?} {setup:?} {input:?}"
            );
            if expected.is_none() {
                assert_eq!(m.phase(), &before, "invalid input must not change state");
            }
        }
    }

    #[test]
    fn ended_is_terminal() {
        let mut m = run(In, &[I::Reject]);
        for input in [
            I::Accept,
            I::Reject,
            I::RemoteAccepted,
            I::MediaUp,
            I::RemoteTerminate,
            I::Hangup,
            I::Timeout,
            I::AnsweredElsewhere,
            I::Failed("late".into()),
        ] {
            assert_eq!(m.apply(input), None);
        }
        assert_eq!(m.phase(), &CallPhase::Ended(EndReason::Rejected));
    }

    #[test]
    fn serde_is_pinned() {
        let json = |p: &CallPhase| serde_json::to_string(p).unwrap();
        assert_eq!(json(&CallPhase::Ringing), "\"ringing\"");
        assert_eq!(json(&CallPhase::Connecting), "\"connecting\"");
        assert_eq!(json(&CallPhase::Active), "\"active\"");
        for (reason, text) in [
            (EndReason::Hangup, "{\"ended\":\"hangup\"}"),
            (EndReason::Rejected, "{\"ended\":\"rejected\"}"),
            (EndReason::Missed, "{\"ended\":\"missed\"}"),
            (EndReason::Remote, "{\"ended\":\"remote\"}"),
            (
                EndReason::ElsewhereAnswered,
                "{\"ended\":\"elsewhere_answered\"}",
            ),
            (
                EndReason::Failed("x".into()),
                "{\"ended\":{\"failed\":\"x\"}}",
            ),
        ] {
            let phase = CallPhase::Ended(reason);
            assert_eq!(json(&phase), text);
            assert_eq!(serde_json::from_str::<CallPhase>(text).unwrap(), phase);
        }
        assert_eq!(serde_json::to_string(&In).unwrap(), "\"in\"");
        assert_eq!(serde_json::to_string(&Out).unwrap(), "\"out\"");
    }
}
