//! Epic 17 and 18 on the Backend: the system blocklist as an immutable
//! snapshot, Block and Unblock off the UI thread, and the incoming-call
//! actions that are not plain call commands (decline with a text, silence).
//! Views read cheap clones and never see a `Result`.

use std::sync::Arc;

use gpui::{Context, Task};
use rovsti_android::{Error, blocklist, calls, calls::Reject};
#[cfg(not(target_os = "android"))]
use rovsti_core::wire::SilencedBy;
use rovsti_core::{
    blocklist::{BlockRefusal, BlockSet, can_block},
    wire::{BlockedNumber, BlocklistPayload, CallLogEntry, CallLogPayload, EmergencyList},
};

#[cfg(not(target_os = "android"))]
use super::CallCommand;
use super::{Backend, Load, Outcome, decode, now_ms};
use crate::sample;

/// The blocklist as pulled, with the set that answers "is this blocked".
#[derive(Debug)]
pub struct BlockState {
    /// Newest first (the provider's order by id, reversed).
    pub rows: Vec<BlockedNumber>,
    pub set: BlockSet,
}

impl BlockState {
    pub fn new(mut rows: Vec<BlockedNumber>) -> Self {
        rows.sort_by_key(|r| std::cmp::Reverse(r.id));
        let set = BlockSet::from_rows(&rows);
        Self { rows, set }
    }
}

/// What declining with a text did, for the label on the card.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReplyOutcome {
    /// Telecom sent the text ("Sent").
    Sent,
    /// The SMS app opened with the text ("Opened Messages").
    Opened,
    /// Declined, no text could go out ("Declined").
    Declined,
    /// The call was gone or Android refused: nothing happened.
    Failed,
}

impl ReplyOutcome {
    /// The 1.2 s label on the card.
    pub fn label(self) -> &'static str {
        match self {
            Self::Sent => "Sent",
            Self::Opened => "Opened Messages",
            Self::Declined | Self::Failed => "Declined",
        }
    }
}

impl From<Reject> for ReplyOutcome {
    fn from(result: Reject) -> Self {
        match result {
            Reject::Sent => Self::Sent,
            Reject::Opened => Self::Opened,
            Reject::RejectedOnly => Self::Declined,
            Reject::Stale => Self::Failed,
        }
    }
}

/// The system blocklist is not usable in the desktop preview when
/// `ROVSTI_PREVIEW_BLOCK=off` (rehearses "Blocking isn't available").
fn preview_off() -> bool {
    cfg!(not(target_os = "android"))
        && std::env::var("ROVSTI_PREVIEW_BLOCK").is_ok_and(|v| v == "off")
}

fn load_blocklist() -> (Load<BlockState>, bool) {
    let can = match blocklist::can_block() {
        Ok(can) => can,
        Err(Error::Unsupported) => !preview_off(),
        Err(_) => false,
    };
    let result = match blocklist::pull() {
        Ok(json) => decode(&json, |p: BlocklistPayload| BlockState::new(p.numbers)),
        Err(Error::Unsupported) if preview_off() => Load::Unavailable,
        Err(Error::Unsupported) => Load::Ready(Arc::new(BlockState::new(sample::blocked_rows()))),
        Err(Error::NotInitialized) => Load::Loading,
        #[allow(unreachable_patterns)]
        Err(error) => {
            log::warn!("blocklist pull failed: {error}");
            Load::Unavailable
        }
    };
    (result, can)
}

fn load_blocked_calls() -> Load<Vec<CallLogEntry>> {
    match blocklist::pull_calls(200) {
        Ok(json) => decode(&json, |p: CallLogPayload| p.entries),
        Err(Error::Unsupported) => Load::Ready(Arc::new(sample::blocked_calls(now_ms()))),
        Err(Error::NotInitialized) => Load::Loading,
        #[allow(unreachable_patterns)]
        Err(error) => {
            log::warn!("blocked calls pull failed: {error}");
            Load::Unavailable
        }
    }
}

/// The provider write; the desktop preview edits the sample list.
fn write_block(raw: &str, block: bool) -> bool {
    let result = if block {
        blocklist::block(raw)
    } else {
        blocklist::unblock(raw)
    };
    match result {
        Ok(done) => done,
        Err(Error::Unsupported) if preview_off() => false,
        Err(Error::Unsupported) => {
            std::thread::sleep(std::time::Duration::from_millis(80));
            if block {
                sample::block_row(raw);
            } else {
                sample::unblock_row(raw);
            }
            true
        }
        Err(error) => {
            log::warn!("blocklist write failed: {error}");
            false
        }
    }
}

impl Backend {
    // ---- the blocklist ------------------------------------------------------

    /// The blocklist snapshot (cheap to clone).
    pub fn blocklist(&self) -> Load<BlockState> {
        self.blocklist.clone()
    }

    /// The type-6 rows, newest first (pulled on demand, see [`Self::want_blocked_calls`]).
    pub fn blocked_calls(&self) -> Load<Vec<CallLogEntry>> {
        self.blocked_calls.clone()
    }

    /// This user may use the system blocklist at all (`canCurrentUserBlockNumbers`).
    pub fn can_block_here(&self) -> bool {
        self.can_block
    }

    /// Whether `raw` is on the list now. An empty number never matches and
    /// never queries anything.
    pub fn is_blocked(&self, raw: &str) -> bool {
        match &self.blocklist {
            Load::Ready(state) if !raw.trim().is_empty() => state.set.is_blocked(raw),
            _ => false,
        }
    }

    /// A copy of the loaded block set (empty until it has loaded).
    pub fn blocked_numbers_set(&self) -> BlockSet {
        match &self.blocklist {
            Load::Ready(state) => state.set.clone(),
            _ => BlockSet::default(),
        }
    }

    /// Why `raw` cannot be blocked, if it cannot: the pure rules first
    /// (emergency, empty, dial codes), then the platform.
    pub fn block_refusal(&self, raw: &str) -> Option<BlockRefusal> {
        can_block(raw, &EmergencyList::default()).err()
    }

    /// The blocklist as a cheap clone for a background pull.
    pub(super) fn block_set(&self) -> Arc<BlockSet> {
        match &self.blocklist {
            Load::Ready(state) => Arc::new(state.set.clone()),
            _ => Arc::new(BlockSet::default()),
        }
    }

    /// The blocklist changed: the call log's rows follow it at once, from the
    /// entries already in memory.
    pub(super) fn refilter_call_log(&mut self) {
        let Load::Ready(log) = &self.call_log else {
            return;
        };
        let set = self.block_set();
        let next = super::build_call_log_with(Arc::clone(&log.source), log.locale, &set);
        if next.entries != log.entries {
            self.call_log = Load::Ready(Arc::new(next));
        }
    }

    /// Re-pulls the blocklist off the UI thread. A late older pull never
    /// overwrites a newer one, and an identical pull draws nothing.
    pub(super) fn refresh_blocklist(&mut self, cx: &mut Context<Self>) {
        self.block_pull += 1;
        let pull = self.block_pull;
        cx.spawn(async move |this, cx| {
            let (load, can) = cx
                .background_executor()
                .spawn(async { load_blocklist() })
                .await;
            this.update(cx, |this, cx| {
                if this.block_pull != pull {
                    return;
                }
                let same = match (&this.blocklist, &load) {
                    (Load::Ready(a), Load::Ready(b)) => a.rows == b.rows,
                    _ => false,
                };
                if !same || this.can_block != can {
                    this.blocklist = load;
                    this.can_block = can;
                    this.refilter_call_log();
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// The Blocked calls view is open (or closed): its rows follow the call log.
    pub fn want_blocked_calls(&mut self, wanted: bool, cx: &mut Context<Self>) {
        self.blocked_calls_wanted = wanted;
        if wanted {
            self.refresh_blocked_calls(cx);
        }
    }

    pub(super) fn refresh_blocked_calls(&mut self, cx: &mut Context<Self>) {
        self.blocked_pull += 1;
        let pull = self.blocked_pull;
        cx.spawn(async move |this, cx| {
            let load = cx
                .background_executor()
                .spawn(async { load_blocked_calls() })
                .await;
            this.update(cx, |this, cx| {
                if this.blocked_pull != pull {
                    return;
                }
                let same = match (&this.blocked_calls, &load) {
                    (Load::Ready(a), Load::Ready(b)) => a == b,
                    _ => false,
                };
                if !same {
                    this.blocked_calls = load;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// "Try again" on the Blocked numbers page.
    pub fn retry_blocklist(&mut self, cx: &mut Context<Self>) {
        self.blocklist = Load::Loading;
        cx.notify();
        self.refresh_blocklist(cx);
        if self.blocked_calls_wanted {
            self.blocked_calls = Load::Loading;
            self.refresh_blocked_calls(cx);
        }
    }

    /// Blocks `raw` (the string as shown; the provider normalises). Resolves
    /// to whether Android took it. Refused numbers never reach Android.
    pub fn block(&mut self, raw: &str, cx: &mut Context<Self>) -> Task<bool> {
        self.write_block(raw, true, cx)
    }

    /// Unblocks the same raw string that was blocked.
    pub fn unblock(&mut self, raw: &str, cx: &mut Context<Self>) -> Task<bool> {
        self.write_block(raw, false, cx)
    }

    fn write_block(&mut self, raw: &str, block: bool, cx: &mut Context<Self>) -> Task<bool> {
        let raw = raw.trim().to_owned();
        if block && (self.block_refusal(&raw).is_some() || !self.can_block) {
            return Task::ready(false);
        }
        cx.spawn(async move |this, cx| {
            let number = raw.clone();
            let done = cx
                .background_executor()
                .spawn(async move { write_block(&number, block) })
                .await;
            if done {
                // The observer will fire too; this makes the page right at once.
                this.update(cx, |this, cx| this.refresh_blocklist(cx)).ok();
            }
            done
        })
    }

    // ---- incoming-call actions (Epic 18) -------------------------------------------

    /// Declines the ringing call `id` with `text` (story 18.1).
    pub fn reject_with_message(
        &mut self,
        id: i32,
        text: &str,
        cx: &mut Context<Self>,
    ) -> ReplyOutcome {
        match calls::reject_with_message(id, text) {
            Ok(result) => result.into(),
            Err(Error::Unsupported) => self.preview_reply(id, None, cx),
            Err(error) => {
                log::warn!("reject with message failed: {error}");
                ReplyOutcome::Failed
            }
        }
    }

    /// Desktop: the fake call is declined and the outcome is rehearsed
    /// (`ROVSTI_PREVIEW_REPLY` = `sent`, `opened` or `declined`).
    #[cfg(not(target_os = "android"))]
    fn preview_reply(
        &mut self,
        id: i32,
        fixed: Option<ReplyOutcome>,
        cx: &mut Context<Self>,
    ) -> ReplyOutcome {
        self.preview_command(CallCommand::Reject(id), cx);
        fixed.unwrap_or_else(|| match std::env::var("ROVSTI_PREVIEW_REPLY").as_deref() {
            Ok("opened") => ReplyOutcome::Opened,
            Ok("declined") => ReplyOutcome::Declined,
            _ => ReplyOutcome::Sent,
        })
    }

    #[cfg(target_os = "android")]
    fn preview_reply(
        &mut self,
        _id: i32,
        _fixed: Option<ReplyOutcome>,
        _cx: &mut Context<Self>,
    ) -> ReplyOutcome {
        ReplyOutcome::Failed
    }

    /// "Write your own...": declines and opens the SMS app on the caller.
    pub fn reject_and_compose(&mut self, id: i32, cx: &mut Context<Self>) -> ReplyOutcome {
        match calls::reject_and_compose(id) {
            Ok(true) => ReplyOutcome::Opened,
            Ok(false) => ReplyOutcome::Failed,
            Err(Error::Unsupported) => self.preview_reply(id, Some(ReplyOutcome::Opened), cx),
            Err(error) => {
                log::warn!("reject and compose failed: {error}");
                ReplyOutcome::Failed
            }
        }
    }

    /// Silences the ringer, the call stays answerable (story 18.6). A refusal
    /// hides the control for the rest of the session.
    pub fn silence_ringer(&mut self, cx: &mut Context<Self>) -> Outcome {
        let outcome = Outcome::from(calls::silence_ringer(), "silence ringer");
        match outcome {
            Outcome::Declined => {
                self.silence_refused = true;
                cx.notify();
            }
            #[cfg(not(target_os = "android"))]
            Outcome::NotAvailable => {
                self.preview_apply(cx, |s| {
                    s.ringer_silenced = true;
                    s.silenced_by = Some(SilencedBy::User);
                });
                return Outcome::Done;
            }
            _ => {}
        }
        outcome
    }

    /// Android refused `silenceRinger()` once: the control stays a blank slot.
    pub fn silence_refused(&self) -> bool {
        self.silence_refused
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const BLOCKLIST: &str =
        include_str!("../../../../crates/rovsti_core/tests/fixtures/blocklist.json");

    fn row(id: i64, raw: &str, normalized: &str) -> BlockedNumber {
        BlockedNumber {
            id,
            number_raw: raw.into(),
            normalized: normalized.into(),
        }
    }

    #[test]
    fn the_golden_blocklist_becomes_a_set() {
        let Load::Ready(state) =
            decode(BLOCKLIST, |p: BlocklistPayload| BlockState::new(p.numbers))
        else {
            panic!("expected rows");
        };
        assert!(!state.rows.is_empty());
        assert!(state.set.is_blocked(&state.rows[0].number_raw));
    }

    #[test]
    fn rows_are_newest_first_and_matching_is_format_insensitive() {
        let state = BlockState::new(vec![
            row(1, "0850 222 33 44", "08502223344"),
            row(5, "+90 212 555 01 99", "+902125550199"),
        ]);
        assert_eq!(state.rows[0].id, 5);
        assert!(state.set.is_blocked("0212 555 01 99"));
        assert!(!state.set.is_blocked("0555 000 00 00"));
        assert!(!state.set.is_blocked(""));
    }

    #[test]
    fn a_permission_error_is_not_a_list() {
        let denied = r#"{"v":1,"ok":false,"error":"permission"}"#;
        let loaded = decode(denied, |p: BlocklistPayload| BlockState::new(p.numbers));
        assert!(matches!(loaded, Load::PermissionDenied));
        let off = r#"{"v":1,"ok":false,"error":"unavailable"}"#;
        assert!(matches!(
            decode(off, |p: BlocklistPayload| BlockState::new(p.numbers)),
            Load::Unavailable
        ));
    }

    #[test]
    fn labels_follow_what_happened() {
        assert_eq!(ReplyOutcome::from(Reject::Sent).label(), "Sent");
        assert_eq!(
            ReplyOutcome::from(Reject::Opened).label(),
            "Opened Messages"
        );
        assert_eq!(ReplyOutcome::from(Reject::RejectedOnly).label(), "Declined");
        assert_eq!(ReplyOutcome::from(Reject::Stale), ReplyOutcome::Failed);
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn the_desktop_list_blocks_and_unblocks() {
        assert!(write_block("0599 111 22 33", true));
        assert!(
            sample::blocked_rows()
                .iter()
                .any(|r| r.number_raw == "0599 111 22 33")
        );
        // A duplicate replaces, it does not add a second row.
        assert!(write_block("0599 111 22 33", true));
        assert_eq!(
            sample::blocked_rows()
                .iter()
                .filter(|r| r.number_raw == "0599 111 22 33")
                .count(),
            1
        );
        assert!(write_block("0599 111 22 33", false));
        assert!(
            !sample::blocked_rows()
                .iter()
                .any(|r| r.number_raw == "0599 111 22 33")
        );
    }
}
