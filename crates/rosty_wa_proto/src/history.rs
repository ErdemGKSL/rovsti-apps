//! Local history of WhatsApp calls, newest first.

use std::collections::VecDeque;

use crate::call::{CallDirection, CallPhase, EndReason, WaCallView};
use crate::event::{CallSummary, CallUpdate};

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

/// A call is missed when it was incoming and never became active: `Hangup`
/// and `Remote` only follow a call we answered.
fn is_missed(direction: CallDirection, elapsed_s: u64, reason: &EndReason) -> bool {
    let answered = elapsed_s > 0 || matches!(reason, EndReason::Hangup | EndReason::Remote);
    direction == CallDirection::In && !answered
}

/// What to feed the tracker and the history from `GET /v1/calls`.
#[derive(Clone, Debug, PartialEq)]
pub struct SnapshotPlan {
    /// The live call to show, when the tracker holds none.
    pub show: Option<CallUpdate>,
    /// Ended calls this device has not recorded yet.
    pub ended: Vec<CallSummary>,
}

/// Picks what a gateway snapshot changes. `show` is the newest live call while
/// the tracker is empty, a ringing incoming call before any other; `ended` are
/// the ended summaries whose call id is not in `history` yet.
pub fn plan_snapshot(
    tracker_busy: bool,
    summaries: &[CallSummary],
    history: &WaCallHistory,
) -> SnapshotPlan {
    let live = || summaries.iter().filter(|s| !s.update.phase.is_ended());
    let show = if tracker_busy {
        None
    } else {
        let ringing_in = |s: &&CallSummary| {
            s.update.direction == CallDirection::In && s.update.phase == CallPhase::Ringing
        };
        live()
            .filter(ringing_in)
            .max_by_key(|s| s.started_ms)
            .or_else(|| live().max_by_key(|s| s.started_ms))
            .map(|s| s.update.clone())
    };
    let ended = summaries
        .iter()
        .filter(|s| s.update.phase.is_ended())
        .filter(|s| !history.knows(&s.update.call_id))
        .cloned()
        .collect();
    SnapshotPlan { show, ended }
}

/// The ended update for the call on screen (`tracked`), when the snapshot
/// says it is over. The tracker then ends it as if the live `ended` event had
/// arrived, so a missed event cannot leave a call ringing on screen.
pub fn tracked_end(tracked: Option<&str>, summaries: &[CallSummary]) -> Option<CallUpdate> {
    let tracked = tracked?;
    summaries
        .iter()
        .find(|s| s.update.call_id == tracked && s.update.phase.is_ended())
        .map(|s| s.update.clone())
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct WaCallHistory {
    records: VecDeque<WaCallRecord>,
    /// Ids of calls the user deleted, newest first; a gateway snapshot must
    /// not bring them back.
    forgotten: VecDeque<String>,
}

impl WaCallHistory {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records a finished call. Returns `false` when `call_id` is already
    /// recorded. A call is missed when it was incoming and never became
    /// active: `Hangup` and `Remote` only follow a call we answered.
    pub fn record_end(&mut self, view: &WaCallView, started_ms: i64, reason: &EndReason) -> bool {
        if self.knows(&view.call_id) {
            return false;
        }
        self.records.push_front(WaCallRecord {
            call_id: view.call_id.clone(),
            peer: view.peer.clone(),
            peer_label: view.peer_label.clone(),
            direction: view.direction,
            started_ms,
            duration_s: view.elapsed_s,
            missed: is_missed(view.direction, view.elapsed_s, reason),
        });
        self.records.truncate(MAX_RECORDS);
        true
    }

    /// Records an ended call from a gateway snapshot, for calls this device
    /// never saw end. Live summaries and known `call_id`s return `false`. The
    /// snapshot carries no talk time, so the duration is the whole span when
    /// the call was answered and zero when missed.
    pub fn record_summary(&mut self, s: &CallSummary, peer_label: &str) -> bool {
        let CallPhase::Ended(reason) = &s.update.phase else {
            return false;
        };
        if self.knows(&s.update.call_id) {
            return false;
        }
        let missed = is_missed(s.update.direction, 0, reason);
        let span_ms = s.ended_ms.map_or(0, |end| end.saturating_sub(s.started_ms));
        self.records.push_front(WaCallRecord {
            call_id: s.update.call_id.clone(),
            peer: s.update.peer.clone(),
            peer_label: peer_label.to_owned(),
            direction: s.update.direction,
            started_ms: s.started_ms,
            duration_s: if missed {
                0
            } else {
                span_ms.max(0).unsigned_abs() / 1000
            },
            missed,
        });
        self.records.truncate(MAX_RECORDS);
        true
    }

    /// Puts `newer` (calls ended since this history was read from disk) on top
    /// of this one, skipping call ids already present.
    pub fn extend_newest(&mut self, newer: &WaCallHistory) {
        for record in newer.records.iter().rev() {
            if !self.knows(&record.call_id) {
                self.records.push_front(record.clone());
            }
        }
        self.records.truncate(MAX_RECORDS);
    }

    /// Whether `call_id` is recorded or was deleted by the user.
    pub fn knows(&self, call_id: &str) -> bool {
        self.records.iter().any(|r| r.call_id == call_id)
            || self.forgotten.iter().any(|f| f == call_id)
    }

    fn tombstone(&mut self, id: &str) {
        if !self.forgotten.iter().any(|f| f == id) {
            self.forgotten.push_front(id.to_owned());
        }
        self.forgotten.truncate(MAX_RECORDS);
    }

    /// Deletes the records with these call ids and remembers the ids so a
    /// snapshot does not record them again. Returns the removed records (for
    /// undo), newest first.
    pub fn forget(&mut self, ids: &[String]) -> Vec<WaCallRecord> {
        let mut removed = Vec::new();
        let mut kept = VecDeque::with_capacity(self.records.len());
        for record in std::mem::take(&mut self.records) {
            if ids.contains(&record.call_id) {
                removed.push(record);
            } else {
                kept.push_back(record);
            }
        }
        self.records = kept;
        for id in ids {
            self.tombstone(id);
        }
        removed
    }

    /// Deletes every record, remembering their ids. Returns how many.
    pub fn forget_all(&mut self) -> usize {
        let ids: Vec<String> = self.records.iter().map(|r| r.call_id.clone()).collect();
        self.records.clear();
        for id in &ids {
            self.tombstone(id);
        }
        ids.len()
    }

    /// Undo of [`Self::forget`]: puts the records back in `started_ms` order
    /// and lifts their tombstones. Ids already present are skipped.
    pub fn restore(&mut self, records: Vec<WaCallRecord>) {
        for record in records {
            self.forgotten.retain(|f| *f != record.call_id);
            if !self.records.iter().any(|r| r.call_id == record.call_id) {
                self.records.push_back(record);
            }
        }
        self.records
            .make_contiguous()
            .sort_by_key(|r| std::cmp::Reverse(r.started_ms));
        self.records.truncate(MAX_RECORDS);
    }

    pub fn forgotten_to_json(&self) -> String {
        serde_json::to_string(&self.forgotten).unwrap_or_else(|_| "[]".to_owned())
    }

    /// Sets the deleted-call ids from stored JSON; corrupt input yields none.
    #[must_use]
    pub fn with_forgotten_json(mut self, json: &str) -> Self {
        let mut forgotten: VecDeque<String> = serde_json::from_str(json).unwrap_or_default();
        forgotten.truncate(MAX_RECORDS);
        self.forgotten = forgotten;
        self
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
        Self {
            records,
            forgotten: VecDeque::new(),
        }
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

    fn summary(id: &str, direction: CallDirection, phase: CallPhase) -> CallSummary {
        CallSummary {
            update: crate::event::CallUpdate {
                call_id: id.into(),
                peer: "1@s.whatsapp.net".into(),
                peer_name: None,
                direction,
                phase,
                is_video: false,
            },
            started_ms: 1_000,
            ended_ms: Some(31_000),
        }
    }

    #[test]
    fn summary_live_call_is_not_recorded() {
        let mut h = WaCallHistory::new();
        let s = summary("a", CallDirection::In, CallPhase::Ringing);
        assert!(!h.record_summary(&s, "Ann"));
        assert!(h.is_empty());
    }

    #[test]
    fn summary_missed_incoming_once() {
        let mut h = WaCallHistory::new();
        let s = summary("a", CallDirection::In, CallPhase::Ended(EndReason::Missed));
        assert!(h.record_summary(&s, "Ann"));
        assert!(!h.record_summary(&s, "Ann"));
        let r = h.iter().next().unwrap();
        assert_eq!(
            (r.missed, r.started_ms, r.duration_s, r.peer_label.as_str()),
            (true, 1_000, 0, "Ann")
        );
    }

    #[test]
    fn summary_answered_call_has_duration() {
        let mut h = WaCallHistory::new();
        let s = summary("a", CallDirection::In, CallPhase::Ended(EndReason::Remote));
        assert!(h.record_summary(&s, "Ann"));
        let r = h.iter().next().unwrap();
        assert_eq!((r.missed, r.duration_s), (false, 30));
    }

    #[test]
    fn summary_skips_call_recorded_by_end() {
        let mut h = WaCallHistory::new();
        assert!(h.record_end(&view("a", CallDirection::In, 7), 5, &EndReason::Remote));
        let s = summary("a", CallDirection::In, CallPhase::Ended(EndReason::Missed));
        assert!(!h.record_summary(&s, "Ann"));
        assert_eq!(h.len(), 1);
        assert_eq!(h.iter().next().unwrap().duration_s, 7);
    }

    fn at(id: &str, direction: CallDirection, phase: CallPhase, started_ms: i64) -> CallSummary {
        let mut s = summary(id, direction, phase);
        s.started_ms = started_ms;
        s
    }

    #[test]
    fn an_empty_snapshot_plans_nothing() {
        let plan = plan_snapshot(false, &[], &WaCallHistory::new());
        assert_eq!(plan.show, None);
        assert!(plan.ended.is_empty());
    }

    #[test]
    fn a_ringing_incoming_call_is_preferred() {
        use CallDirection::{In, Out};
        let list = [
            at("out", Out, CallPhase::Active, 9),
            at("in", In, CallPhase::Ringing, 1),
        ];
        let plan = plan_snapshot(false, &list, &WaCallHistory::new());
        assert_eq!(plan.show.unwrap().call_id, "in");
        let list = [
            at("old", Out, CallPhase::Active, 1),
            at("new", Out, CallPhase::Ringing, 2),
        ];
        let plan = plan_snapshot(false, &list, &WaCallHistory::new());
        assert_eq!(plan.show.unwrap().call_id, "new");
    }

    #[test]
    fn a_busy_tracker_shows_nothing_but_ended_calls_still_count() {
        let list = [
            at("in", CallDirection::In, CallPhase::Ringing, 1),
            at(
                "gone",
                CallDirection::In,
                CallPhase::Ended(EndReason::Missed),
                0,
            ),
        ];
        let plan = plan_snapshot(true, &list, &WaCallHistory::new());
        assert_eq!(plan.show, None);
        assert_eq!(plan.ended.len(), 1);
    }

    #[test]
    fn a_tracked_call_the_snapshot_ended_is_ended() {
        use CallDirection::In;
        let list = [
            at("other", In, CallPhase::Ended(EndReason::Missed), 2),
            at("on_screen", In, CallPhase::Ended(EndReason::Missed), 1),
            at("live", In, CallPhase::Ringing, 3),
        ];
        let end = tracked_end(Some("on_screen"), &list).unwrap();
        assert_eq!(end.call_id, "on_screen");
        assert_eq!(end.phase, CallPhase::Ended(EndReason::Missed));
        // Still live, unknown to the snapshot, or nothing on screen: no change.
        assert_eq!(tracked_end(Some("live"), &list), None);
        assert_eq!(tracked_end(Some("absent"), &list), None);
        assert_eq!(tracked_end(None, &list), None);
    }

    #[test]
    fn ended_calls_already_in_the_history_are_skipped() {
        let missed = at(
            "m",
            CallDirection::In,
            CallPhase::Ended(EndReason::Missed),
            1,
        );
        let mut history = WaCallHistory::new();
        let plan = plan_snapshot(false, std::slice::from_ref(&missed), &history);
        assert_eq!(plan.ended, vec![missed.clone()]);
        assert!(history.record_summary(&missed, "Ayse"));
        let plan = plan_snapshot(false, &[missed], &history);
        assert!(plan.ended.is_empty());
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
    #[test]
    fn newer_records_go_on_top_without_duplicates() {
        let mut old = WaCallHistory::new();
        old.record_end(&view("a", CallDirection::Out, 5), 1, &EndReason::Hangup);
        old.record_end(&view("b", CallDirection::Out, 5), 2, &EndReason::Hangup);
        let mut new = WaCallHistory::new();
        new.record_end(&view("b", CallDirection::Out, 5), 2, &EndReason::Hangup);
        new.record_end(&view("c", CallDirection::Out, 5), 3, &EndReason::Hangup);
        old.extend_newest(&new);
        let ids: Vec<_> = old.iter().map(|r| r.call_id.as_str()).collect();
        assert_eq!(ids, ["c", "b", "a"]);
    }

    fn ended(id: &str, at: i64) -> CallSummary {
        let mut s = summary(id, CallDirection::In, CallPhase::Ended(EndReason::Missed));
        s.started_ms = at;
        s
    }

    #[test]
    fn forgotten_call_is_not_recorded_again() {
        let mut h = WaCallHistory::new();
        assert!(h.record_summary(&ended("a", 1), "Ann"));
        let removed = h.forget(&["a".to_owned()]);
        assert_eq!(removed.len(), 1);
        assert!(h.is_empty());
        assert!(h.knows("a"));
        assert!(!h.record_summary(&ended("a", 1), "Ann"));
        assert!(!h.record_end(&view("a", CallDirection::Out, 5), 1, &EndReason::Hangup));
        let mut newer = WaCallHistory::new();
        newer.record_summary(&ended("a", 1), "Ann");
        h.extend_newest(&newer);
        assert!(h.is_empty());
    }

    #[test]
    fn plan_snapshot_skips_forgotten_ids() {
        let mut h = WaCallHistory::new();
        h.record_summary(&ended("a", 1), "Ann");
        h.forget(&["a".to_owned()]);
        let plan = plan_snapshot(false, &[ended("a", 1), ended("b", 2)], &h);
        let ids: Vec<_> = plan
            .ended
            .iter()
            .map(|s| s.update.call_id.as_str())
            .collect();
        assert_eq!(ids, ["b"]);
    }

    #[test]
    fn restore_resorts_and_lifts_tombstones() {
        let mut h = WaCallHistory::new();
        for (id, at) in [("a", 1), ("b", 2), ("c", 3)] {
            h.record_summary(&ended(id, at), "Ann");
        }
        let removed = h.forget(&["a".to_owned(), "c".to_owned()]);
        assert_eq!(removed.len(), 2);
        h.restore(removed.clone());
        h.restore(removed);
        let ids: Vec<_> = h.iter().map(|r| r.call_id.as_str()).collect();
        assert_eq!(ids, ["c", "b", "a"]);
        assert!(!h.forgotten_to_json().contains('a'));
    }

    #[test]
    fn forget_all_clears_and_remembers() {
        let mut h = WaCallHistory::new();
        h.record_summary(&ended("a", 1), "Ann");
        h.record_summary(&ended("b", 2), "Ann");
        assert_eq!(h.forget_all(), 2);
        assert!(h.is_empty());
        assert!(h.knows("a") && h.knows("b"));
    }

    #[test]
    fn tombstones_round_trip_and_survive_corruption() {
        let mut h = WaCallHistory::new();
        h.record_summary(&ended("a", 1), "Ann");
        h.forget(&["a".to_owned()]);
        let back = WaCallHistory::new().with_forgotten_json(&h.forgotten_to_json());
        assert!(back.knows("a"));
        let bad = WaCallHistory::new().with_forgotten_json("garbage");
        assert!(!bad.knows("a"));
    }
}
