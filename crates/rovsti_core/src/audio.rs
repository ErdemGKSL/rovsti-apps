//! The audio route list as the route popover shows it (FR-41, FR-42, AD-23).
//!
//! Kotlin sends Telecom's endpoints in Telecom's order; display order,
//! labels, the dimmed Phone placeholder, ghost entries for vanished devices and
//! the arrival / loss diff are decided here, as pure and tested code, so the
//! views contain no routing rules.
//!
//! Display order: Bluetooth (newest arrival first), wired headset, Speaker,
//! Phone, then unknown types. At most [`MAX_ROWS`] rows live in a fixed array.

use crate::wire::{Endpoint, EndpointHint, EndpointType};

/// The most rows the popover ever holds (AD-35).
pub const MAX_ROWS: usize = 8;

/// What a row is, for icon choice and ordering.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum RowKind {
    Bluetooth,
    WiredHeadset,
    Speaker,
    Phone,
    #[default]
    Unknown,
}

impl RowKind {
    fn rank(self) -> u8 {
        match self {
            Self::Bluetooth => 0,
            Self::WiredHeadset => 1,
            Self::Speaker => 2,
            Self::Phone => 3,
            Self::Unknown => 4,
        }
    }
}

/// One line of the route list.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Row {
    /// The endpoint id Kotlin routes by; empty for the Phone placeholder.
    pub id: String,
    pub kind: RowKind,
    /// "Phone", "Speaker", the device name or "Bluetooth device 2".
    pub label: String,
    pub selected: bool,
    /// The Phone placeholder: Android did not report an earpiece.
    pub unavailable: bool,
    /// The device vanished; it stays dimmed until the popover is next opened.
    pub ghost: bool,
    pub hearing_aid: bool,
    /// A Bluetooth device whose name Android did not send.
    pub nameless: bool,
    /// Order of first sight; higher is newer.
    arrival: u32,
}

impl Row {
    /// The second line of the row; empty for Phone and Speaker.
    pub fn subtitle(&self) -> &'static str {
        if self.unavailable {
            "Unavailable with this device"
        } else if self.ghost {
            "Disconnected"
        } else if self.hearing_aid {
            "Hearing aid"
        } else if matches!(self.kind, RowKind::Bluetooth | RowKind::WiredHeadset) {
            "Connected"
        } else {
            ""
        }
    }
}

/// What a tap on the Audio control does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Decision {
    /// Only Phone and Speaker exist: flip between them.
    Toggle,
    /// Another device is there: open the list.
    OpensList,
}

/// Endpoints added, lost and re-selected between two lists.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Diff {
    pub added: Vec<String>,
    pub removed: Vec<String>,
    /// `(from, to)` endpoint ids when the selection moved.
    pub selection: Option<(Option<String>, Option<String>)>,
}

impl Diff {
    pub fn is_empty(&self) -> bool {
        self.added.is_empty() && self.removed.is_empty() && self.selection.is_none()
    }

    /// Folds a newer diff into this one (two snapshots before a frame).
    pub fn merge(&mut self, newer: Diff) {
        for id in newer.added {
            // A device that left and came back is neither lost nor new.
            if let Some(i) = self.removed.iter().position(|r| *r == id) {
                self.removed.remove(i);
            } else {
                self.added.push(id);
            }
        }
        for id in newer.removed {
            if let Some(i) = self.added.iter().position(|a| *a == id) {
                self.added.remove(i);
            } else {
                self.removed.push(id);
            }
        }
        if let Some((from, to)) = newer.selection {
            let from = self.selection.take().map_or(from, |(f, _)| f);
            self.selection = (from != to).then_some((from, to));
        }
    }
}

/// The ordered list, built once per snapshot whose endpoint part changed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EndpointList {
    rows: [Row; MAX_ROWS],
    len: usize,
    next_arrival: u32,
    /// Live (non-ghost) endpoints exist, i.e. Android sent a list.
    live: usize,
    toggle_only: bool,
}

impl Default for EndpointList {
    fn default() -> Self {
        Self {
            rows: std::array::from_fn(|_| Row::default()),
            len: 0,
            next_arrival: 0,
            live: 0,
            toggle_only: true,
        }
    }
}

/// Whole-word "hearing" or "aid" (case-insensitive), e.g. "Phonak Hearing Aid".
fn name_says_hearing_aid(name: &str) -> bool {
    name.split(|c: char| !c.is_alphanumeric())
        .any(|word| word.eq_ignore_ascii_case("hearing") || word.eq_ignore_ascii_case("aid"))
}

fn kind_of(kind: EndpointType) -> Option<RowKind> {
    match kind {
        EndpointType::Earpiece => Some(RowKind::Phone),
        EndpointType::Speaker => Some(RowKind::Speaker),
        EndpointType::Bluetooth => Some(RowKind::Bluetooth),
        EndpointType::WiredHeadset => Some(RowKind::WiredHeadset),
        EndpointType::Unknown => Some(RowKind::Unknown),
        EndpointType::Streaming => None,
    }
}

impl EndpointList {
    /// Builds the list. `baseline` is the previous list of this session (or
    /// `None` for the first one and after a re-attach): it carries arrival
    /// order, and its vanished devices stay as ghosts.
    pub fn from_snapshot(endpoints: &[Endpoint], baseline: Option<&EndpointList>) -> Self {
        let mut next_arrival = baseline.map_or(0, |b| b.next_arrival);
        let mut rows: Vec<Row> = Vec::with_capacity(endpoints.len() + 2);
        let mut bluetooth_ordinal = 0_u32;
        let mut has_phone = false;
        for endpoint in endpoints {
            let Some(kind) = kind_of(endpoint.kind) else {
                continue;
            };
            has_phone |= kind == RowKind::Phone;
            let name = endpoint.name.as_deref().map(str::trim).unwrap_or("");
            let nameless = kind == RowKind::Bluetooth && name.is_empty();
            if nameless {
                bluetooth_ordinal += 1;
            }
            let label = match kind {
                RowKind::Phone => "Phone".to_owned(),
                RowKind::Speaker => "Speaker".to_owned(),
                RowKind::Bluetooth if nameless => format!("Bluetooth device {bluetooth_ordinal}"),
                RowKind::Bluetooth => name.to_owned(),
                RowKind::WiredHeadset if name.is_empty() => "Wired headset".to_owned(),
                RowKind::Unknown if name.is_empty() => "Other output".to_owned(),
                _ => name.to_owned(),
            };
            let hearing_aid = kind == RowKind::Bluetooth
                && (endpoint.hint == Some(EndpointHint::HearingAid) || name_says_hearing_aid(name));
            let arrival = match baseline.and_then(|b| b.find(&endpoint.id)) {
                Some(old) => old.arrival,
                None => {
                    next_arrival += 1;
                    next_arrival
                }
            };
            rows.push(Row {
                id: endpoint.id.clone(),
                kind,
                label,
                selected: endpoint.selected,
                unavailable: false,
                ghost: false,
                hearing_aid,
                nameless,
                arrival,
            });
        }
        let live = rows.len();
        // Devices that vanished stay dimmed in place until the popover opens.
        if let Some(base) = baseline {
            for old in base.iter() {
                if old.unavailable || rows.iter().any(|r| r.id == old.id) {
                    continue;
                }
                rows.push(Row {
                    ghost: true,
                    selected: false,
                    ..old.clone()
                });
            }
        }
        if live > 0 && !has_phone {
            rows.push(Row {
                kind: RowKind::Phone,
                label: "Phone".to_owned(),
                unavailable: true,
                ..Row::default()
            });
        }
        let toggle_only = rows
            .iter()
            .filter(|r| !r.ghost)
            .all(|r| matches!(r.kind, RowKind::Phone | RowKind::Speaker));
        // Bluetooth newest first; the rest by kind; stable for ties.
        rows.sort_by(|a, b| {
            a.kind
                .rank()
                .cmp(&b.kind.rank())
                .then(b.arrival.cmp(&a.arrival))
        });
        while rows.len() > MAX_ROWS {
            let drop = rows
                .iter()
                .rposition(|r| r.ghost)
                .or_else(|| {
                    rows.iter()
                        .rposition(|r| !matches!(r.kind, RowKind::Phone | RowKind::Speaker))
                })
                .unwrap_or(rows.len() - 1);
            rows.remove(drop);
        }
        let live = rows.iter().filter(|r| !r.ghost && !r.unavailable).count();
        let len = rows.len();
        let mut array: [Row; MAX_ROWS] = std::array::from_fn(|_| Row::default());
        for (slot, row) in array.iter_mut().zip(rows) {
            *slot = row;
        }
        Self {
            rows: array,
            len,
            next_arrival,
            live,
            toggle_only,
        }
    }

    fn find(&self, id: &str) -> Option<&Row> {
        if id.is_empty() {
            return None;
        }
        self.iter().find(|r| r.id == id)
    }

    pub fn iter(&self) -> impl DoubleEndedIterator<Item = &Row> {
        self.rows[..self.len].iter()
    }

    pub fn rows(&self) -> &[Row] {
        &self.rows[..self.len]
    }

    pub fn len(&self) -> usize {
        self.len
    }

    /// Android sent no endpoints at all (the control then follows `route`).
    pub fn is_empty(&self) -> bool {
        self.live == 0
    }

    /// The row Android reports as in use; `None` shows the snapshot's `route`.
    pub fn selected(&self) -> Option<&Row> {
        self.iter().find(|r| r.selected && !r.ghost)
    }

    pub fn get(&self, id: &str) -> Option<&Row> {
        self.find(id)
    }

    /// The popover opens: vanished devices leave.
    pub fn clear_ghosts(&mut self) {
        if !self.rows[..self.len].iter().any(|r| r.ghost) {
            return;
        }
        let kept: Vec<Row> = self.rows[..self.len]
            .iter()
            .filter(|r| !r.ghost)
            .cloned()
            .collect();
        self.len = kept.len();
        for (slot, row) in self.rows.iter_mut().zip(kept) {
            *slot = row;
        }
        for slot in &mut self.rows[self.len..] {
            *slot = Row::default();
        }
    }

    /// Some device has no name, so the "Show device names" row may help.
    pub fn any_nameless(&self) -> bool {
        self.iter().any(|r| r.nameless && !r.ghost)
    }

    /// Only Phone and Speaker (or nothing) exist: a tap toggles them.
    pub fn decision(&self) -> Decision {
        if self.toggle_only {
            Decision::Toggle
        } else {
            Decision::OpensList
        }
    }

    /// Endpoints that arrived, vanished or took the selection between `prev`
    /// and `next`. `prev == None` (first list, re-attach) reports nothing.
    pub fn diff(prev: Option<&EndpointList>, next: &EndpointList) -> Diff {
        let Some(prev) = prev else {
            return Diff::default();
        };
        fn live(list: &EndpointList) -> Vec<&str> {
            list.iter()
                .filter(|r| !r.ghost && !r.unavailable)
                .map(|r| r.id.as_str())
                .collect()
        }
        let before = live(prev);
        let after = live(next);
        let added = after
            .iter()
            .filter(|id| !before.contains(id))
            .map(|id| (*id).to_owned())
            .collect();
        let removed = before
            .iter()
            .filter(|id| !after.contains(id))
            .map(|id| (*id).to_owned())
            .collect();
        let from = prev.selected().map(|r| r.id.clone());
        let to = next.selected().map(|r| r.id.clone());
        Diff {
            added,
            removed,
            selection: (from != to).then_some((from, to)),
        }
    }
}

/// Keeps the current [`EndpointList`] and the pending [`Diff`] across
/// snapshots. The list is rebuilt only when the endpoint part of a snapshot
/// changed (AD-35), so a mute toggle or a timer-only snapshot costs nothing.
#[derive(Clone, Debug, Default)]
pub struct EndpointTracker {
    src: Vec<Endpoint>,
    list: EndpointList,
    pending: Diff,
    rev: u64,
}

impl EndpointTracker {
    /// Feeds the endpoint part of a snapshot. `in_call` is false when the
    /// snapshot carries no call: the baseline resets, so the first list of the
    /// next call seeds it with no arrival or loss reported. Returns whether
    /// the list changed.
    pub fn observe(&mut self, endpoints: &[Endpoint], in_call: bool) -> bool {
        if !in_call {
            if self.src.is_empty() && self.list.is_empty() {
                return false;
            }
            self.src.clear();
            self.list = EndpointList::default();
            self.pending = Diff::default();
            self.rev += 1;
            return true;
        }
        if endpoints == self.src.as_slice() {
            return false;
        }
        // Seeded once a list with live endpoints exists.
        let baseline = (!self.list.is_empty()).then_some(&self.list);
        let next = EndpointList::from_snapshot(endpoints, baseline);
        let diff = EndpointList::diff(baseline, &next);
        self.pending.merge(diff);
        self.src.clear();
        self.src.extend_from_slice(endpoints);
        self.list = next;
        self.rev += 1;
        true
    }

    pub fn list(&self) -> &EndpointList {
        &self.list
    }

    /// Bumped on every change of the list.
    pub fn rev(&self) -> u64 {
        self.rev
    }

    /// The arrivals and losses since the last call; the views consume them.
    pub fn take_diff(&mut self) -> Diff {
        std::mem::take(&mut self.pending)
    }

    /// The popover opens: vanished devices leave the list.
    pub fn open(&mut self) {
        let before = self.list.len();
        self.list.clear_ghosts();
        if self.list.len() != before {
            self.rev += 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ep(id: &str, kind: EndpointType, name: Option<&str>, selected: bool) -> Endpoint {
        Endpoint {
            id: id.to_owned(),
            kind,
            name: name.map(str::to_owned),
            selected,
            hint: None,
        }
    }

    fn labels(list: &EndpointList) -> Vec<&str> {
        list.iter().map(|r| r.label.as_str()).collect()
    }

    fn phone_speaker() -> Vec<Endpoint> {
        vec![
            ep("p", EndpointType::Earpiece, None, true),
            ep("s", EndpointType::Speaker, None, false),
        ]
    }

    #[test]
    fn only_phone_and_speaker_toggle() {
        let list = EndpointList::from_snapshot(&phone_speaker(), None);
        assert_eq!(labels(&list), ["Speaker", "Phone"]);
        assert_eq!(list.decision(), Decision::Toggle);
        assert_eq!(list.selected().map(|r| r.id.as_str()), Some("p"));
        assert!(!list.is_empty());
    }

    #[test]
    fn an_empty_list_toggles_and_shows_route() {
        let list = EndpointList::from_snapshot(&[], None);
        assert!(list.is_empty());
        assert_eq!(list.len(), 0);
        assert_eq!(list.decision(), Decision::Toggle);
        assert!(list.selected().is_none());
    }

    #[test]
    fn a_headset_replacing_the_earpiece_keeps_a_dimmed_phone() {
        let list = EndpointList::from_snapshot(
            &[
                ep("w", EndpointType::WiredHeadset, Some("USB-C headset"), true),
                ep("s", EndpointType::Speaker, None, false),
            ],
            None,
        );
        assert_eq!(labels(&list), ["USB-C headset", "Speaker", "Phone"]);
        let phone = list.rows().last().expect("phone row");
        assert!(phone.unavailable && phone.id.is_empty());
        assert_eq!(phone.subtitle(), "Unavailable with this device");
        assert_eq!(list.decision(), Decision::OpensList);
    }

    #[test]
    fn bluetooth_comes_first_newest_arrival_on_top() {
        let first = EndpointList::from_snapshot(
            &[
                ep("p", EndpointType::Earpiece, None, true),
                ep("b1", EndpointType::Bluetooth, Some("Buds"), false),
                ep("s", EndpointType::Speaker, None, false),
            ],
            None,
        );
        let second = EndpointList::from_snapshot(
            &[
                ep("p", EndpointType::Earpiece, None, true),
                ep("b1", EndpointType::Bluetooth, Some("Buds"), false),
                ep("b2", EndpointType::Bluetooth, Some("Car"), false),
                ep("s", EndpointType::Speaker, None, false),
            ],
            Some(&first),
        );
        assert_eq!(labels(&second), ["Car", "Buds", "Speaker", "Phone"]);
        let diff = EndpointList::diff(Some(&first), &second);
        assert_eq!(diff.added, ["b2"]);
        assert!(diff.removed.is_empty() && diff.selection.is_none());
    }

    #[test]
    fn two_bluetooth_devices_with_one_name_stay_two_rows() {
        let list = EndpointList::from_snapshot(
            &[
                ep("a", EndpointType::Bluetooth, Some("Buds"), false),
                ep("b", EndpointType::Bluetooth, Some("Buds"), true),
            ],
            None,
        );
        let ids: Vec<_> = list
            .iter()
            .filter(|r| r.kind == RowKind::Bluetooth)
            .map(|r| r.id.as_str())
            .collect();
        assert_eq!(ids, ["b", "a"], "newest first, both present");
        assert_eq!(list.get("a").map(|r| r.label.as_str()), Some("Buds"));
    }

    #[test]
    fn nameless_bluetooth_devices_are_numbered_by_ordinal() {
        let list = EndpointList::from_snapshot(
            &[
                ep("a", EndpointType::Bluetooth, None, false),
                ep("b", EndpointType::Bluetooth, Some("  "), false),
                ep("c", EndpointType::Bluetooth, Some("Car"), false),
            ],
            None,
        );
        assert_eq!(
            list.get("a").map(|r| r.label.as_str()),
            Some("Bluetooth device 1")
        );
        assert_eq!(
            list.get("b").map(|r| r.label.as_str()),
            Some("Bluetooth device 2")
        );
        assert!(list.any_nameless());
        assert_eq!(list.get("c").map(|r| r.nameless), Some(false));
    }

    #[test]
    fn hearing_aid_by_hint_or_whole_word_name() {
        let mut hinted = ep("a", EndpointType::Bluetooth, Some("Audeo"), false);
        hinted.hint = Some(EndpointHint::HearingAid);
        let list = EndpointList::from_snapshot(
            &[
                hinted,
                ep("b", EndpointType::Bluetooth, Some("My Hearing-Aid"), false),
                ep("c", EndpointType::Bluetooth, Some("Maidstone"), false),
                ep("d", EndpointType::Bluetooth, Some("Aidan's buds"), false),
                ep("e", EndpointType::WiredHeadset, Some("Hearing aid"), false),
            ],
            None,
        );
        let flag = |id| list.get(id).map(|r| r.hearing_aid);
        assert_eq!(flag("a"), Some(true));
        assert_eq!(flag("b"), Some(true));
        assert_eq!(flag("c"), Some(false), "no substring matches");
        assert_eq!(flag("d"), Some(false));
        assert_eq!(flag("e"), Some(false), "only Bluetooth");
        assert_eq!(list.get("a").map(|r| r.subtitle()), Some("Hearing aid"));
    }

    #[test]
    fn a_vanished_current_device_becomes_a_ghost_and_selection_falls_back() {
        let first = EndpointList::from_snapshot(
            &[
                ep("p", EndpointType::Earpiece, None, false),
                ep("b", EndpointType::Bluetooth, Some("Buds"), true),
                ep("s", EndpointType::Speaker, None, false),
            ],
            None,
        );
        let second = EndpointList::from_snapshot(&phone_speaker(), Some(&first));
        let ghost = second.get("b").expect("ghost kept");
        assert!(ghost.ghost && !ghost.selected);
        assert_eq!(ghost.subtitle(), "Disconnected");
        assert_eq!(second.selected().map(|r| r.id.as_str()), Some("p"));
        let diff = EndpointList::diff(Some(&first), &second);
        assert_eq!(diff.removed, ["b"]);
        assert_eq!(
            diff.selection,
            Some((Some("b".to_owned()), Some("p".to_owned())))
        );
        // Ghosts do not count for the toggle decision.
        assert_eq!(second.decision(), Decision::Toggle);
        // A later list keeps the ghost until the popover opens.
        let third = EndpointList::from_snapshot(&phone_speaker(), Some(&second));
        assert!(third.get("b").is_some_and(|r| r.ghost));
        let mut opened = third;
        opened.clear_ghosts();
        assert!(opened.get("b").is_none());
        assert_eq!(labels(&opened), ["Speaker", "Phone"]);
    }

    #[test]
    fn the_first_list_and_a_reattach_report_nothing() {
        let list = EndpointList::from_snapshot(&phone_speaker(), None);
        assert!(EndpointList::diff(None, &list).is_empty());
    }

    #[test]
    fn nothing_selected_leaves_the_route_to_the_snapshot() {
        let list = EndpointList::from_snapshot(
            &[
                ep("p", EndpointType::Earpiece, None, false),
                ep("s", EndpointType::Speaker, None, false),
            ],
            None,
        );
        assert!(list.selected().is_none());
    }

    #[test]
    fn streaming_endpoints_are_ignored_and_unknown_ones_get_a_row() {
        let list = EndpointList::from_snapshot(
            &[
                ep("p", EndpointType::Earpiece, None, true),
                ep("x", EndpointType::Streaming, Some("Cast"), false),
                ep("u", EndpointType::Unknown, Some("Dock"), false),
            ],
            None,
        );
        assert!(list.get("x").is_none());
        assert_eq!(list.get("u").map(|r| r.kind), Some(RowKind::Unknown));
        assert_eq!(list.decision(), Decision::OpensList);
    }

    #[test]
    fn never_more_than_eight_rows_and_phone_survives() {
        let mut eps = vec![
            ep("p", EndpointType::Earpiece, None, true),
            ep("s", EndpointType::Speaker, None, false),
        ];
        for i in 0..10 {
            eps.push(ep(
                &format!("b{i}"),
                EndpointType::Bluetooth,
                Some("Dev"),
                false,
            ));
        }
        let list = EndpointList::from_snapshot(&eps, None);
        assert_eq!(list.len(), MAX_ROWS);
        assert!(list.get("p").is_some() && list.get("s").is_some());
    }

    #[test]
    fn the_tracker_rebuilds_only_when_endpoints_change() {
        let mut t = EndpointTracker::default();
        assert!(t.observe(&phone_speaker(), true));
        let rev = t.rev();
        assert!(
            !t.observe(&phone_speaker(), true),
            "same endpoints: no rebuild"
        );
        assert_eq!(t.rev(), rev);
        assert!(t.take_diff().is_empty(), "the first list reports nothing");
    }

    #[test]
    fn the_tracker_reports_arrival_loss_and_resets_between_calls() {
        let mut t = EndpointTracker::default();
        t.observe(&phone_speaker(), true);
        let mut with_bt = phone_speaker();
        with_bt.push(ep("b", EndpointType::Bluetooth, Some("Buds"), false));
        t.observe(&with_bt, true);
        assert_eq!(t.take_diff().added, ["b"]);
        assert!(t.take_diff().is_empty(), "taken once");
        t.observe(&phone_speaker(), true);
        assert_eq!(t.take_diff().removed, ["b"]);
        assert!(t.list().get("b").is_some_and(|r| r.ghost));
        t.open();
        assert!(t.list().get("b").is_none());
        // No call: everything resets, the next call seeds again.
        assert!(t.observe(&[], false));
        assert!(t.list().is_empty());
        t.observe(&with_bt, true);
        assert!(t.take_diff().is_empty(), "a new call is a new baseline");
    }

    #[test]
    fn merged_diffs_cancel_a_blip() {
        let mut d = Diff::default();
        d.merge(Diff {
            added: vec!["x".into()],
            ..Diff::default()
        });
        d.merge(Diff {
            removed: vec!["x".into()],
            ..Diff::default()
        });
        assert!(d.is_empty());
        let mut s = Diff::default();
        s.merge(Diff {
            selection: Some((Some("a".into()), Some("b".into()))),
            ..Diff::default()
        });
        s.merge(Diff {
            selection: Some((Some("b".into()), Some("a".into()))),
            ..Diff::default()
        });
        assert!(s.selection.is_none(), "a round trip is no change");
    }
}
