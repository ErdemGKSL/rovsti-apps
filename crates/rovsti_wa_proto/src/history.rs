//! Local history of WhatsApp calls, newest first.

use std::collections::VecDeque;

use crate::call::{CallDirection, EndReason, WaCallView};

/// The most records kept; older ones are dropped.
pub const MAX_RECORDS: usize = 200;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct WaCallRecord {
    pub call_id: String,
    pub peer: String,
    pub peer_label: String,
    pub direction: CallDirection,
    pub started_ms: i64,
    pub duration_s: u64,
    pub missed: bool,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct WaCallHistory {
    records: VecDeque<WaCallRecord>,
}

impl WaCallHistory {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records a finished call. Returns `false` when `call_id` is already
    /// recorded. A call is missed when it was incoming and never became
    /// active: `Hangup` and `Remote` only follow a call we answered.
    pub fn record_end(&mut self, view: &WaCallView, started_ms: i64, reason: &EndReason) -> bool {
        if self.records.iter().any(|r| r.call_id == view.call_id) {
            return false;
        }
        let answered =
            view.elapsed_s > 0 || matches!(reason, EndReason::Hangup | EndReason::Remote);
        self.records.push_front(WaCallRecord {
            call_id: view.call_id.clone(),
            peer: view.peer.clone(),
            peer_label: view.peer_label.clone(),
            direction: view.direction,
            started_ms,
            duration_s: view.elapsed_s,
            missed: view.direction == CallDirection::In && !answered,
        });
        self.records.truncate(MAX_RECORDS);
        true
    }

    pub fn iter(&self) -> impl Iterator<Item = &WaCallRecord> {
        self.records.iter()
    }

    pub fn len(&self) -> usize {
        self.records.len()
    }

    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(&self.records).unwrap_or_else(|_| "[]".to_owned())
    }

    /// Parses stored history; anything corrupt yields an empty history.
    pub fn from_json(json: &str) -> Self {
        let mut records: VecDeque<WaCallRecord> = serde_json::from_str(json).unwrap_or_default();
        records.truncate(MAX_RECORDS);
        Self { records }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::call::CallPhase;

    fn view(id: &str, direction: CallDirection, elapsed_s: u64) -> WaCallView {
        WaCallView {
            call_id: id.into(),
            peer: "1@s.whatsapp.net".into(),
            peer_label: "Ann".into(),
            direction,
            phase: CallPhase::Ended(EndReason::Hangup),
            is_video: false,
            muted: false,
            elapsed_s,
        }
    }

    #[test]
    fn newest_first_and_once_per_call() {
        let mut h = WaCallHistory::new();
        assert!(h.record_end(&view("a", CallDirection::Out, 5), 1, &EndReason::Hangup));
        assert!(h.record_end(&view("b", CallDirection::Out, 6), 2, &EndReason::Hangup));
        assert!(!h.record_end(&view("a", CallDirection::Out, 9), 3, &EndReason::Remote));
        let ids: Vec<_> = h.iter().map(|r| r.call_id.as_str()).collect();
        assert_eq!(ids, ["b", "a"]);
        assert_eq!(h.iter().last().unwrap().duration_s, 5);
    }

    #[test]
    fn missed_is_incoming_and_never_answered() {
        let mut h = WaCallHistory::new();
        h.record_end(&view("m", CallDirection::In, 0), 1, &EndReason::Missed);
        h.record_end(&view("r", CallDirection::In, 0), 2, &EndReason::Rejected);
        h.record_end(
            &view("e", CallDirection::In, 0),
            3,
            &EndReason::ElsewhereAnswered,
        );
        h.record_end(&view("ok", CallDirection::In, 30), 4, &EndReason::Remote);
        h.record_end(&view("quick", CallDirection::In, 0), 5, &EndReason::Hangup);
        h.record_end(&view("out", CallDirection::Out, 0), 6, &EndReason::Rejected);
        let missed: Vec<_> = h.iter().map(|r| (r.call_id.as_str(), r.missed)).collect();
        assert_eq!(
            missed,
            [
                ("out", false),
                ("quick", false),
                ("ok", false),
                ("e", true),
                ("r", true),
                ("m", true)
            ]
        );
    }

    #[test]
    fn capped_at_200() {
        let mut h = WaCallHistory::new();
        for i in 0..250 {
            h.record_end(
                &view(&i.to_string(), CallDirection::Out, 1),
                i,
                &EndReason::Hangup,
            );
        }
        assert_eq!(h.len(), MAX_RECORDS);
        assert_eq!(h.iter().next().unwrap().call_id, "249");
        assert_eq!(h.iter().last().unwrap().call_id, "50");
    }

    #[test]
    fn json_round_trip_and_garbage() {
        let mut h = WaCallHistory::new();
        h.record_end(&view("a", CallDirection::In, 0), 1, &EndReason::Missed);
        h.record_end(&view("b", CallDirection::Out, 4), 2, &EndReason::Hangup);
        assert_eq!(WaCallHistory::from_json(&h.to_json()), h);
        for bad in ["", "garbage", "{}", "[1,2]", "[{\"call_id\":1}]", "null"] {
            assert!(WaCallHistory::from_json(bad).is_empty(), "{bad}");
        }
    }
}
