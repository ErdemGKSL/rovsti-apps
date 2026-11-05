//! The pure call state machine. The gateway drives it from whatsapp-rust
//! events and HTTP commands; apps drive a mirror of it from `CallUpdate`s.

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
