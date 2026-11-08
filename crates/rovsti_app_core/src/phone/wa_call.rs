//! WhatsApp calls in Phone (AD-WA5), the parts without UI or Android.
//!
//! The call itself runs on the gateway. [`Tracker`] folds its updates into the
//! one call Phone shows, [`AnswerOnce`] makes a notification action take
//! effect once, and the history of ended calls is read and written here
//! (`wa-calls.json`, `wa-calls-forgotten.json`). The microphone and speaker
//! helpers that use `rovsti_android` types stay in `apps/phone`.

use std::{collections::VecDeque, fs, path::Path};

use rovsti_core::{
    recents::ExtraCall,
    wire::{Handoff, HandoffAction, WaCallAction},
};
use rovsti_wa_proto::{CallDirection, CallPhase, CallUpdate, EndReason, WaCallHistory, WaCallView};

use crate::storage;

/// Shown when a WhatsApp call is wanted but no gateway link exists.
pub const NOT_CONNECTED: &str = "WhatsApp is not connected";

/// The local WhatsApp call history, next to `prefs.json`.
pub const HISTORY_FILE: &str = "wa-calls.json";
/// Ids of deleted calls, so a gateway snapshot does not bring them back.
pub const FORGOTTEN_FILE: &str = "wa-calls-forgotten.json";
/// A notification action waits this long for its call to reach the app.
pub const ACTION_TTL_MS: i64 = 60_000;
/// How many handled call ids the guard remembers.
pub const ONCE_MEMORY: usize = 16;

/// What an update did to the tracked call.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Change {
    /// Another call, or an update for a call that is already over.
    Ignored,
    /// The call was shown or changed.
    Shown,
    /// The call ended with this update.
    Ended,
}

#[derive(Clone, Debug, PartialEq)]
struct Tracked {
    update: CallUpdate,
    started_ms: i64,
    active_since_ms: Option<i64>,
    muted: bool,
}

/// The one WhatsApp call Phone shows at a time, folded from gateway updates.
#[derive(Debug, Default)]
pub struct Tracker {
    current: Option<Tracked>,
}

impl Tracker {
    pub fn on_update(&mut self, update: CallUpdate, now_ms: i64) -> Change {
        match &mut self.current {
            None if update.phase.is_ended() => Change::Ignored,
            None => {
                self.current = Some(Tracked {
                    started_ms: now_ms,
                    active_since_ms: (update.phase == CallPhase::Active).then_some(now_ms),
                    update,
                    muted: false,
                });
                Change::Shown
            }
            Some(tracked) if tracked.update.call_id != update.call_id => Change::Ignored,
            Some(tracked) => {
                if update.phase == CallPhase::Active && tracked.active_since_ms.is_none() {
                    tracked.active_since_ms = Some(now_ms);
                }
                let ended = update.phase.is_ended();
                tracked.update = update;
                if ended { Change::Ended } else { Change::Shown }
            }
        }
    }

    /// The gateway accepted an outgoing call: show it as ringing at once, before
    /// its first update arrives. Ignored while any call is on screen.
    pub fn on_placed(&mut self, call_id: &str, peer: &str, now_ms: i64) -> Change {
        if self.current.is_some() {
            return Change::Ignored;
        }
        self.on_update(
            CallUpdate {
                call_id: call_id.to_owned(),
                peer: peer.to_owned(),
                peer_name: None,
                direction: CallDirection::Out,
                phase: CallPhase::Ringing,
                is_video: false,
            },
            now_ms,
        )
    }

    pub fn view(&self, now_ms: i64) -> Option<WaCallView> {
        self.current.as_ref().map(|t| {
            WaCallView::from_update(&t.update, now_ms)
                .with_muted(t.muted)
                .with_active_since(t.active_since_ms, now_ms)
        })
    }

    /// When the tracked call first showed (its start, for the call history).
    pub fn started_ms(&self) -> Option<i64> {
        self.current.as_ref().map(|t| t.started_ms)
    }

    pub fn set_muted(&mut self, muted: bool) {
        if let Some(tracked) = &mut self.current {
            tracked.muted = muted;
        }
    }

    pub fn clear(&mut self) {
        self.current = None;
    }

    pub fn is_empty(&self) -> bool {
        self.current.is_none()
    }
}

/// Whether a WhatsApp call may be started now; the error is shown to the user.
pub fn can_place(connected: bool, busy: bool, number: &str) -> Result<(), &'static str> {
    if number.trim().is_empty() {
        Err("No number to call")
    } else if !connected {
        Err(NOT_CONNECTED)
    } else if busy {
        Err("Another call is in progress")
    } else {
        Ok(())
    }
}

/// Makes a notification action (Answer, Decline) take effect once per call,
/// however often Android redelivers its intent (`onCreate` and `onNewIntent`
/// both see it, and so does a recreated activity).
///
/// An Answer may arrive before the gateway has told the app about the call (a
/// cold start): it waits, up to [`ACTION_TTL_MS`], until that call rings. A
/// Decline needs no call on screen, the gateway can reject by id.
#[derive(Debug, Default)]
pub struct AnswerOnce {
    done: VecDeque<String>,
    pending: Option<PendingAction>,
}

#[derive(Debug)]
struct PendingAction {
    call_id: String,
    action: WaCallAction,
    at_ms: i64,
}

impl AnswerOnce {
    /// Registers an action. `false` when this call already had one (the first
    /// action wins, a late Decline cannot undo an Answer).
    pub fn offer(&mut self, call_id: &str, action: WaCallAction, now_ms: i64) -> bool {
        if self.done.iter().any(|id| id == call_id)
            || self.pending.as_ref().is_some_and(|p| p.call_id == call_id)
        {
            return false;
        }
        self.pending = Some(PendingAction {
            call_id: call_id.to_owned(),
            action,
            at_ms: now_ms,
        });
        true
    }

    /// The action that may run now, consumed: `shown` is the call on screen
    /// (`(call id, ringing incoming)`).
    pub fn due(
        &mut self,
        shown: Option<(&str, bool)>,
        now_ms: i64,
    ) -> Option<(String, WaCallAction)> {
        let pending = self.pending.as_ref()?;
        if now_ms.saturating_sub(pending.at_ms) > ACTION_TTL_MS {
            self.pending = None;
            return None;
        }
        let ready = match pending.action {
            WaCallAction::Decline => true,
            WaCallAction::Answer => shown == Some((pending.call_id.as_str(), true)),
        };
        if !ready {
            return None;
        }
        let PendingAction {
            call_id, action, ..
        } = self.pending.take()?;
        self.done.push_back(call_id.clone());
        if self.done.len() > ONCE_MEMORY {
            self.done.pop_front();
        }
        Some((call_id, action))
    }

    /// An action for this call already ran (a snapshot must not show it again).
    pub fn handled(&self, call_id: &str) -> bool {
        self.done.iter().any(|id| id == call_id)
    }

    /// The call is over: a waiting Answer for it is pointless now.
    pub fn call_ended(&mut self, call_id: &str) {
        if self
            .pending
            .as_ref()
            .is_some_and(|p| p.call_id == call_id && p.action == WaCallAction::Answer)
        {
            self.pending = None;
        }
    }
}

/// `+905551234567` for a phone JID (`905551234567:12@s.whatsapp.net`); `None`
/// for anything that is not a phone number (`@lid`, groups).
pub fn dial_number(peer: &str) -> Option<String> {
    let (user, server) = peer.split_once('@')?;
    let user = user.split(':').next()?;
    (server == "s.whatsapp.net" && !user.is_empty() && user.bytes().all(|b| b.is_ascii_digit()))
        .then(|| format!("+{user}"))
}

/// The number to call for a handoff from a sibling app: only `WaCall` handoffs for a phone JID
/// qualify (`+<digits>`); chats, unknown actions and `@lid` peers give `None`.
pub fn handoff_number(handoff: &Handoff) -> Option<String> {
    (handoff.action == HandoffAction::WaCall)
        .then(|| dial_number(&handoff.jid))
        .flatten()
}

/// The history as Recents rows. Calls with a peer that has no phone number
/// cannot be called back from Recents and are left out.
pub fn extra_calls(history: &WaCallHistory) -> Vec<ExtraCall> {
    history
        .iter()
        .filter_map(|record| {
            let number = dial_number(&record.peer)?;
            let label = (record.peer_label != number).then(|| record.peer_label.clone());
            Some(ExtraCall {
                number_raw: number,
                started_ms: record.started_ms,
                duration_s: record.duration_s,
                missed: record.missed,
                outgoing: record.direction == CallDirection::Out,
                label,
                call_id: record.call_id.clone(),
            })
        })
        .collect()
}

/// `m:ss`, or `h:mm:ss` from an hour on.
pub fn format_elapsed(seconds: u64) -> String {
    rovsti_core::call_log::format_duration(seconds).to_string()
}

/// The line under the caller's name.
pub fn status_line(view: &WaCallView) -> String {
    match (&view.phase, view.direction) {
        (CallPhase::Ringing, CallDirection::In) => "WhatsApp call".into(),
        (CallPhase::Ringing, CallDirection::Out) => "Calling…".into(),
        (CallPhase::Connecting, _) => "Connecting…".into(),
        (CallPhase::Active, _) => format_elapsed(view.elapsed_s),
        (CallPhase::Ended(reason), _) => match reason {
            EndReason::Hangup | EndReason::Remote => "Call ended".into(),
            EndReason::Rejected => "Declined".into(),
            EndReason::Missed => "Missed call".into(),
            EndReason::ElsewhereAnswered => "Answered on another device".into(),
            EndReason::Failed(_) => "Call failed".into(),
        },
    }
}

// ---- history files --------------------------------------------------------------

/// Reads the saved WhatsApp call history and the ids of deleted calls. `None`
/// when there is no history file; a missing forgotten file reads as empty.
pub fn load_history(dir: &Path) -> Option<WaCallHistory> {
    let history = fs::read_to_string(dir.join(HISTORY_FILE)).ok()?;
    let forgotten = fs::read_to_string(dir.join(FORGOTTEN_FILE)).unwrap_or_default();
    Some(WaCallHistory::from_json(&history).with_forgotten_json(&forgotten))
}

/// Writes the history and the deleted ids (two files, each replaced whole). A
/// failure is logged and the other file is still written.
pub fn save_history(dir: &Path, history: &WaCallHistory) {
    let records: Vec<_> = history.iter().cloned().collect();
    let forgotten: Vec<String> =
        serde_json::from_str(&history.forgotten_to_json()).unwrap_or_default();
    if let Err(error) = storage::save_json(dir, HISTORY_FILE, &records) {
        log::warn!(
            "saving the WhatsApp call history failed: {:?}",
            error.kind()
        );
    }
    if let Err(error) = storage::save_json(dir, FORGOTTEN_FILE, &forgotten) {
        log::warn!(
            "saving the deleted WhatsApp calls failed: {:?}",
            error.kind()
        );
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::stops::store::test_support::tmp;
    use CallDirection::{In, Out};
    use rovsti_wa_proto::{CallSummary, plan_snapshot};

    fn update(id: &str, direction: CallDirection, phase: CallPhase) -> CallUpdate {
        CallUpdate {
            call_id: id.into(),
            peer: "905551234567@s.whatsapp.net".into(),
            peer_name: Some("Ayşe".into()),
            direction,
            phase,
            is_video: false,
        }
    }

    #[test]
    fn a_ringing_call_is_shown_and_answerable() {
        let mut t = Tracker::default();
        assert_eq!(
            t.on_update(update("a", In, CallPhase::Ringing), 0),
            Change::Shown
        );
        let view = t.view(0).unwrap();
        assert!(view.is_ringing_in());
        assert_eq!(view.peer_label, "Ayşe");
        assert_eq!(status_line(&view), "WhatsApp call");
    }

    #[test]
    fn a_second_call_and_stale_updates_are_ignored() {
        let mut t = Tracker::default();
        assert_eq!(
            t.on_update(update("old", In, CallPhase::Ended(EndReason::Missed)), 0),
            Change::Ignored
        );
        assert!(t.is_empty());
        t.on_update(update("a", In, CallPhase::Ringing), 0);
        assert_eq!(
            t.on_update(update("b", In, CallPhase::Ringing), 1),
            Change::Ignored
        );
        assert_eq!(t.view(1).unwrap().call_id, "a");
    }

    #[test]
    fn the_timer_starts_when_the_call_becomes_active() {
        let mut t = Tracker::default();
        t.on_update(update("a", In, CallPhase::Ringing), 0);
        t.on_update(update("a", In, CallPhase::Connecting), 1_000);
        assert_eq!(t.view(2_000).unwrap().elapsed_s, 0);
        t.on_update(update("a", In, CallPhase::Active), 3_000);
        t.on_update(update("a", In, CallPhase::Active), 4_000);
        let view = t.view(68_000).unwrap();
        assert_eq!(view.elapsed_s, 65);
        assert_eq!(status_line(&view), "1:05");
    }

    #[test]
    fn a_placed_call_shows_at_once_and_keeps_one_card() {
        let mut t = Tracker::default();
        assert_eq!(
            t.on_placed("a", "905551234567@s.whatsapp.net", 0),
            Change::Shown
        );
        let view = t.view(0).unwrap();
        assert_eq!(view.direction, Out);
        assert_eq!(status_line(&view), "Calling…");
        assert_eq!(
            t.on_update(update("a", Out, CallPhase::Active), 2_000),
            Change::Shown
        );
        assert_eq!(t.view(5_000).unwrap().elapsed_s, 3);
        assert_eq!(t.view(5_000).unwrap().call_id, "a");
    }

    #[test]
    fn a_second_placed_call_while_busy_is_ignored() {
        let mut t = Tracker::default();
        t.on_placed("a", "x@s.whatsapp.net", 0);
        assert_eq!(t.on_placed("b", "y@s.whatsapp.net", 1), Change::Ignored);
        assert_eq!(t.view(1).unwrap().call_id, "a");
        let mut incoming = Tracker::default();
        incoming.on_update(update("i", In, CallPhase::Ringing), 0);
        assert_eq!(
            incoming.on_placed("b", "y@s.whatsapp.net", 1),
            Change::Ignored
        );
    }

    #[test]
    fn can_place_gives_a_reason() {
        assert_eq!(can_place(true, false, "+905551234567"), Ok(()));
        assert_eq!(can_place(true, false, "  "), Err("No number to call"));
        assert_eq!(can_place(false, false, "1"), Err(NOT_CONNECTED));
        assert_eq!(
            can_place(true, true, "1"),
            Err("Another call is in progress")
        );
    }

    #[test]
    fn mute_is_kept_and_ending_is_reported() {
        let mut t = Tracker::default();
        t.on_update(update("a", Out, CallPhase::Active), 0);
        t.set_muted(true);
        assert!(t.view(0).unwrap().muted);
        let ended = update("a", Out, CallPhase::Ended(EndReason::Remote));
        assert_eq!(t.on_update(ended, 5), Change::Ended);
        assert_eq!(status_line(&t.view(5).unwrap()), "Call ended");
        t.clear();
        assert!(t.view(5).is_none());
    }

    #[test]
    fn elapsed_formats() {
        assert_eq!(format_elapsed(0), "0:00");
        assert_eq!(format_elapsed(59), "0:59");
        assert_eq!(format_elapsed(600), "10:00");
        assert_eq!(format_elapsed(3_725), "1:02:05");
    }

    #[test]
    fn status_lines_cover_the_phases() {
        let line = |dir, phase| status_line(&WaCallView::from_update(&update("a", dir, phase), 0));
        assert_eq!(line(Out, CallPhase::Ringing), "Calling…");
        assert_eq!(line(In, CallPhase::Connecting), "Connecting…");
        assert_eq!(line(In, CallPhase::Ended(EndReason::Rejected)), "Declined");
        assert_eq!(line(In, CallPhase::Ended(EndReason::Missed)), "Missed call");
        assert_eq!(
            line(In, CallPhase::Ended(EndReason::ElsewhereAnswered)),
            "Answered on another device"
        );
        assert_eq!(
            line(In, CallPhase::Ended(EndReason::Failed("x".into()))),
            "Call failed"
        );
    }

    #[test]
    fn an_action_runs_once_per_call() {
        let mut once = AnswerOnce::default();
        assert!(once.offer("a", WaCallAction::Answer, 0));
        // The same intent seen again (onCreate, then onNewIntent) is dropped.
        assert!(!once.offer("a", WaCallAction::Answer, 1));
        assert_eq!(
            once.due(Some(("a", true)), 2),
            Some(("a".into(), WaCallAction::Answer))
        );
        assert!(!once.offer("a", WaCallAction::Answer, 3));
        assert!(!once.offer("a", WaCallAction::Decline, 3));
        assert_eq!(once.due(Some(("a", true)), 4), None);
    }

    #[test]
    fn an_answer_waits_for_its_call_to_ring() {
        let mut once = AnswerOnce::default();
        once.offer("a", WaCallAction::Answer, 0);
        assert_eq!(once.due(None, 1), None);
        assert_eq!(once.due(Some(("other", true)), 2), None);
        assert_eq!(once.due(Some(("a", false)), 3), None);
        assert_eq!(
            once.due(Some(("a", true)), 4),
            Some(("a".into(), WaCallAction::Answer))
        );
    }

    #[test]
    fn a_decline_needs_no_call_on_screen() {
        let mut once = AnswerOnce::default();
        once.offer("a", WaCallAction::Decline, 0);
        assert_eq!(once.due(None, 1), Some(("a".into(), WaCallAction::Decline)));
    }

    #[test]
    fn a_stale_or_moot_answer_is_dropped() {
        let mut once = AnswerOnce::default();
        once.offer("a", WaCallAction::Answer, 0);
        assert_eq!(once.due(Some(("a", true)), ACTION_TTL_MS + 1), None);
        // Expired: nothing is left, and the call may be offered again.
        assert!(once.offer("a", WaCallAction::Answer, ACTION_TTL_MS + 2));
        once.call_ended("a");
        assert_eq!(once.due(Some(("a", true)), ACTION_TTL_MS + 3), None);
    }

    #[test]
    fn the_guard_forgets_old_calls() {
        let mut once = AnswerOnce::default();
        for n in 0..=ONCE_MEMORY {
            let id = format!("c{n}");
            once.offer(&id, WaCallAction::Decline, 0);
            once.due(None, 0);
        }
        assert!(once.offer("c0", WaCallAction::Decline, 0));
        assert!(!once.offer(&format!("c{ONCE_MEMORY}"), WaCallAction::Decline, 0));
    }

    fn summary(
        id: &str,
        direction: CallDirection,
        phase: CallPhase,
        started_ms: i64,
    ) -> CallSummary {
        let ended = phase.is_ended();
        CallSummary {
            update: update(id, direction, phase),
            started_ms,
            ended_ms: ended.then_some(started_ms + 5_000),
        }
    }

    #[test]
    fn an_answer_queued_before_the_snapshot_runs_when_it_shows_the_call() {
        let mut once = AnswerOnce::default();
        let mut tracker = Tracker::default();
        once.offer("a", WaCallAction::Answer, 0);
        assert_eq!(once.due(None, 1), None);
        let list = [summary("a", In, CallPhase::Ringing, 1)];
        let plan = plan_snapshot(!tracker.is_empty(), &list, &WaCallHistory::new());
        assert_eq!(tracker.on_update(plan.show.unwrap(), 2), Change::Shown);
        let view = tracker.view(2).unwrap();
        let shown = Some((view.call_id.as_str(), view.is_ringing_in()));
        assert_eq!(once.due(shown, 2), Some(("a".into(), WaCallAction::Answer)));
        assert!(once.handled("a"));
    }

    #[test]
    fn dial_numbers_come_from_phone_jids_only() {
        assert_eq!(
            dial_number("905551234567@s.whatsapp.net").as_deref(),
            Some("+905551234567")
        );
        assert_eq!(
            dial_number("905551234567:12@s.whatsapp.net").as_deref(),
            Some("+905551234567")
        );
        assert_eq!(dial_number("123@lid"), None);
        assert_eq!(dial_number("abc@s.whatsapp.net"), None);
        assert_eq!(dial_number("905551234567"), None);
    }

    #[test]
    fn handoff_numbers_need_a_call_to_a_phone_jid() {
        let handoff = |action, jid: &str| Handoff {
            action,
            jid: jid.into(),
            name: Some("Ayse".into()),
        };
        assert_eq!(
            handoff_number(&handoff(
                HandoffAction::WaCall,
                "905551234567@s.whatsapp.net"
            ))
            .as_deref(),
            Some("+905551234567")
        );
        assert_eq!(
            handoff_number(&handoff(
                HandoffAction::WaChat,
                "905551234567@s.whatsapp.net"
            )),
            None
        );
        assert_eq!(
            handoff_number(&handoff(
                HandoffAction::Unknown,
                "905551234567@s.whatsapp.net"
            )),
            None
        );
        assert_eq!(
            handoff_number(&handoff(HandoffAction::WaCall, "12@lid")),
            None
        );
    }

    fn ended(id: &str, peer: &str, name: Option<&str>, phase: CallPhase) -> WaCallView {
        let mut u = update(id, In, phase);
        u.peer = peer.into();
        u.peer_name = name.map(str::to_owned);
        WaCallView::from_update(&u, 0)
    }

    #[test]
    fn history_becomes_recents_extras() {
        let mut history = WaCallHistory::new();
        history.record_end(
            &ended(
                "a",
                "905551234567@s.whatsapp.net",
                Some("Ayşe"),
                CallPhase::Ended(EndReason::Missed),
            ),
            10,
            &EndReason::Missed,
        );
        history.record_end(
            &ended(
                "b",
                "905550000000@s.whatsapp.net",
                None,
                CallPhase::Ended(EndReason::Rejected),
            ),
            20,
            &EndReason::Rejected,
        );
        history.record_end(
            &ended("c", "9@lid", None, CallPhase::Ended(EndReason::Missed)),
            30,
            &EndReason::Missed,
        );
        let extras = extra_calls(&history);
        assert_eq!(extras.len(), 2, "the @lid peer has no number");
        assert_eq!(extras[0].number_raw, "+905550000000");
        assert_eq!(extras[0].label, None);
        assert_eq!(extras[1].label.as_deref(), Some("Ayşe"));
        assert_eq!(extras[0].call_id, "b");
        assert_eq!(extras[1].call_id, "a");
        assert!(extras[1].missed);
    }

    #[test]
    fn forgotten_calls_survive_a_storage_round_trip() {
        let mut history = WaCallHistory::new();
        history.record_end(
            &ended(
                "a",
                "905551234567@s.whatsapp.net",
                None,
                CallPhase::Ended(EndReason::Missed),
            ),
            10,
            &EndReason::Missed,
        );
        let removed = history.forget(&["a".to_owned()]);
        assert_eq!(removed.len(), 1);
        let saved = history.forgotten_to_json();
        let loaded = WaCallHistory::new().with_forgotten_json(&saved);
        assert!(loaded.knows("a"), "a snapshot must not bring it back");

        let mut loaded = loaded;
        loaded.restore(removed);
        assert_eq!(extra_calls(&loaded).len(), 1);
        assert!(loaded.knows("a"));
    }

    #[test]
    fn the_history_survives_a_save_and_load() {
        let dir = tmp("wa-history");
        assert!(load_history(&dir).is_none());
        let mut history = WaCallHistory::new();
        for (id, peer) in [
            ("a", "905551234567@s.whatsapp.net"),
            ("b", "905550000000@s.whatsapp.net"),
        ] {
            history.record_end(
                &ended(id, peer, None, CallPhase::Ended(EndReason::Missed)),
                10,
                &EndReason::Missed,
            );
        }
        history.forget(&["a".to_owned()]);
        save_history(&dir, &history);
        let back = load_history(&dir).expect("saved");
        assert_eq!(back.iter().count(), 1);
        assert!(back.knows("a"), "a deleted call stays deleted");
        assert_eq!(extra_calls(&back).len(), 1);
    }
}
