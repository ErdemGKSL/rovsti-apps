//! The In-Call zone (stories 15.4 to 15.8): the slot constants every scene
//! shares, the view-models built from `rovsti_core::scene`, and the springs of
//! the multi-call moments (S19 Hold-away, S20 Swap crossing, S21 Merge weave,
//! S22 Participant eject).
//!
//! The zone is the flexible space between the top gap and the control grid. Its
//! content changes by scene; its height and everything below it never do.
//! View-models are built only when the scene diff says a part changed, so a
//! timer tick or a pending flag never rebuilds a name or a row.

use std::{sync::Arc, time::Duration};

use gpui::{Context, Render, SharedString, Window, div, prelude::*, px};
use rovsti_core::{
    avatar,
    call::{caller_label, format_elapsed, format_number},
    scene::{CallScene, Control, DisabledReason, Layout},
    wire::WireCall,
};
use rovsti_ui::{current_theme, motion_tokens, spring::Spring, theme, tokens::typography};

use crate::backend::now_ms;

/// Slot constants (AD-35): one module, used by every scene. Heights are in dp
/// on a 402 x 874 screen; nothing here depends on the scene.
pub(crate) mod slots {
    /// Top inset 48 plus the 64 px gap above the zone.
    pub const TOP: f32 = 112.0;
    pub const CONTROL: f32 = 76.0;
    pub const GRID_GAP: f32 = 24.0;
    pub const CAPTION_H: f32 = 16.0;
    pub const CAPTION_GAP: f32 = 4.0;
    /// A control and its caption.
    pub const CELL_H: f32 = CONTROL + CAPTION_GAP + CAPTION_H;
    pub const GRID_H: f32 = 2.0 * CELL_H + GRID_GAP;
    /// Between the grid and End.
    pub const GRID_TO_END: f32 = 32.0;
    pub const END_W: f32 = 220.0;
    pub const END_H: f32 = 64.0;
    /// Bottom inset 34 plus 24.
    pub const BOTTOM: f32 = 58.0;
    pub const CHIP_ROW_H: f32 = 22.0;

    pub const SIDE: f32 = 16.0;
    pub const HELD_H: f32 = 64.0;
    pub const HELD_GAP: f32 = 12.0;
    pub const HELD_AVATAR: f32 = 40.0;

    pub const PANEL_H: f32 = 156.0;
    pub const PANEL_ROW: f32 = 52.0;
    pub const PANEL_RADIUS: f32 = 26.0;
    pub const STACK: f32 = 56.0;

    /// The waiting card overlay.
    pub const WAITING_TOP: f32 = 12.0;
    pub const WAITING_H: f32 = 184.0;
    pub const WAITING_RADIUS: f32 = 38.0;
    pub const WAITING_INSET: f32 = 16.0;
    pub const WAITING_PILL_H: f32 = 44.0;

    // Zone-relative y of each block, per scene.
    pub const ONE_AVATAR: f32 = 96.0;
    pub const ONE_NAME_Y: f32 = ONE_AVATAR + 16.0;
    pub const ONE_STATUS_Y: f32 = ONE_NAME_Y + 38.0;
    pub const ONE_NUMBER_Y: f32 = ONE_STATUS_Y + 44.0;
    pub const ONE_CHIPS_Y: f32 = ONE_NUMBER_Y + 18.0;

    pub const TWO_AVATAR: f32 = 72.0;
    pub const TWO_NAME_Y: f32 = TWO_AVATAR + 8.0;
    pub const TWO_STATUS_Y: f32 = TWO_NAME_Y + 34.0;
    pub const TWO_CHIPS_Y: f32 = TWO_STATUS_Y + 44.0;
    pub const TWO_HELD_Y: f32 = TWO_CHIPS_Y + CHIP_ROW_H + HELD_GAP;

    pub const CONF_TITLE_Y: f32 = STACK + 8.0;
    pub const CONF_TIMER_Y: f32 = CONF_TITLE_Y + 28.0;
    pub const CONF_PANEL_Y: f32 = CONF_TIMER_Y + 22.0 + 12.0;
    pub const CONF_HELD_Y: f32 = CONF_PANEL_Y + PANEL_H + HELD_GAP;

    /// The inner height of the zone's scrolling content (the tallest scene).
    pub const CONTENT_H: f32 = CONF_HELD_Y + HELD_H;

    /// Height of the zone on a screen `screen_h` tall.
    #[cfg(test)]
    pub fn zone_height(screen_h: f32) -> f32 {
        screen_h - TOP - GRID_H - GRID_TO_END - END_H - BOTTOM
    }
}

pub(crate) fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

// ---- View-models -------------------------------------------------------------

/// Everything the zone draws about one party, built once per change.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Party {
    pub id: i32,
    pub name: SharedString,
    pub secondary: Option<SharedString>,
    pub monogram: Option<SharedString>,
    pub hue: Option<usize>,
    pub connect_time_ms: i64,
    /// When the call went on hold (falls back to the connect time).
    pub hold_since_ms: i64,
}

fn middle_truncate(text: &str, max: usize) -> String {
    let count = text.chars().count();
    if count <= max {
        return text.to_owned();
    }
    let keep = (max - 1) / 2;
    let head: String = text.chars().take(keep).collect();
    let tail: String = text.chars().skip(count - keep).collect();
    format!("{head}…{tail}")
}

impl Party {
    pub(crate) fn of(call: &WireCall) -> Self {
        let label = caller_label(call.display_name.as_deref(), &call.number_raw, false);
        let named = call
            .display_name
            .as_deref()
            .is_some_and(|n| !n.trim().is_empty());
        let secondary = named
            .then(|| rovsti_core::PhoneNumber::parse(&call.number_raw).ok())
            .flatten()
            .map(|n| SharedString::from(middle_truncate(&format_number(&n), 28)));
        Self {
            id: call.id,
            name: middle_truncate(&label, 22).into(),
            secondary,
            monogram: call
                .display_name
                .as_deref()
                .and_then(avatar::monogram)
                .map(|c| SharedString::from(c.to_string())),
            hue: avatar::avatar_hue(
                call.contact_id
                    .map_or(avatar::AvatarKey::Unknown, avatar::AvatarKey::Contact),
            ),
            connect_time_ms: call.connect_time_ms,
            hold_since_ms: call.hold_since_ms.unwrap_or(call.connect_time_ms),
        }
    }

    /// "Held call: {name}, on hold 1:12. Double tap to swap" (TalkBack).
    pub(crate) fn of_view(view: &crate::incall::CallView) -> Self {
        Self {
            id: view.id,
            name: view.name.clone(),
            secondary: view.secondary.clone(),
            monogram: view.monogram.clone(),
            hue: view.hue,
            connect_time_ms: view.connect_time_ms,
            hold_since_ms: view.connect_time_ms,
        }
    }

    pub(crate) fn held_aria(&self) -> SharedString {
        let held = format_elapsed(now_ms(), self.hold_since_ms);
        format!(
            "Held call: {}, on hold {}. Double tap to swap",
            self.name,
            held.as_str()
        )
        .into()
    }
}

/// One participant row of the conference panel.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Row {
    pub id: i32,
    pub label: SharedString,
    pub subtitle: SharedString,
    pub monogram: Option<SharedString>,
    pub hue: Option<usize>,
    pub can_split: bool,
    pub can_drop: bool,
    /// "{name}. Actions: Split, End" (TalkBack).
    pub aria: SharedString,
}

/// The conference header and panel.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Conference {
    pub id: i32,
    pub rows: Arc<[Row]>,
    /// "3 people" when the carrier hides the participants.
    pub summary: Option<SharedString>,
    /// Up to three overlapped avatars, then `extra` more.
    pub stack: Vec<(Option<SharedString>, Option<usize>)>,
    pub extra: usize,
    pub connect_time_ms: i64,
}

impl Conference {
    fn of(host: &WireCall, participants: &rovsti_core::scene::Participants<'_>) -> Self {
        let rows: Vec<Row> = participants
            .iter()
            .map(|p| {
                let label: SharedString = p.label().into_owned().into();
                let number = caller_label(None, &p.call.number_raw, false);
                let subtitle: SharedString = if p.call.number_raw.trim().is_empty() {
                    "Participant".into()
                } else if p
                    .call
                    .display_name
                    .as_deref()
                    .is_some_and(|n| !n.trim().is_empty())
                {
                    number.into_owned().into()
                } else {
                    "Participant".into()
                };
                let (can_split, can_drop) = (p.can_split(), p.can_drop());
                let actions = match (can_split, can_drop) {
                    (true, true) => ". Actions: Split, End",
                    (true, false) => ". Actions: Split",
                    (false, true) => ". Actions: End",
                    (false, false) => "",
                };
                Row {
                    id: p.call.id,
                    aria: format!("{label}{actions}").into(),
                    label,
                    subtitle,
                    monogram: p
                        .call
                        .display_name
                        .as_deref()
                        .and_then(avatar::monogram)
                        .map(|c| SharedString::from(c.to_string())),
                    hue: avatar::avatar_hue(
                        p.call
                            .contact_id
                            .map_or(avatar::AvatarKey::Unknown, avatar::AvatarKey::Contact),
                    ),
                    can_split,
                    can_drop,
                }
            })
            .collect();
        let stack = rows
            .iter()
            .take(3)
            .map(|r| (r.monogram.clone(), r.hue))
            .collect();
        let extra = rows.len().saturating_sub(3);
        Self {
            id: host.id,
            summary: participants.summary().map(|t| t.as_str().to_owned().into()),
            rows: rows.into(),
            stack,
            extra,
            connect_time_ms: host.connect_time_ms,
        }
    }
}

/// What kind of zone is showing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Face {
    Empty,
    Single,
    Two,
    Conference,
}

// ---- Control enablement (EXPERIENCE.md, the table) -------------------------------

/// The six grid slots, in reading order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Slot {
    Mute,
    Keypad,
    Audio,
    AddCall,
    HoldSwap,
    Merge,
}

impl Slot {
    pub(crate) const ALL: [Slot; 6] = [
        Slot::Mute,
        Slot::Keypad,
        Slot::Audio,
        Slot::AddCall,
        Slot::HoldSwap,
        Slot::Merge,
    ];

    pub(crate) fn control(self) -> Control {
        match self {
            Slot::Mute => Control::Mute,
            Slot::Keypad => Control::Keypad,
            Slot::Audio => Control::Audio,
            Slot::AddCall => Control::AddCall,
            Slot::HoldSwap => Control::HoldSwap,
            Slot::Merge => Control::Merge,
        }
    }

    pub(crate) fn index(self) -> usize {
        Self::ALL.iter().position(|s| *s == self).unwrap_or(0)
    }
}

/// Why each slot is dimmed (`None` = available), computed from the scene.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct Enablement {
    pub reasons: [Option<DisabledReason>; 6],
    pub swap: bool,
}

impl Enablement {
    pub(crate) fn of(scene: &CallScene<'_>) -> Self {
        let mut reasons = [None; 6];
        for slot in Slot::ALL {
            reasons[slot.index()] = scene.reason(slot.control());
        }
        Self {
            reasons,
            swap: scene.held().is_some(),
        }
    }

    pub(crate) fn reason(&self, slot: Slot) -> Option<DisabledReason> {
        self.reasons[slot.index()]
    }

    pub(crate) fn enabled(&self, slot: Slot) -> bool {
        self.reason(slot).is_none()
    }
}

// ---- The ticking elapsed line --------------------------------------------------

/// A line of text that shows "{prefix}m:ss" and repaints itself on the second.
/// It is its own view, so a once-a-second tick never touches the screen.
pub(crate) struct ElapsedLine {
    prefix: &'static str,
    since_ms: i64,
    size: f32,
    weight: gpui::FontWeight,
    /// 0 = secondary label, 1 = warning.
    tone: u8,
    visible: bool,
    task: Option<gpui::Task<()>>,
}

impl ElapsedLine {
    pub(crate) fn new(prefix: &'static str, size: f32, tone: u8) -> Self {
        Self {
            prefix,
            since_ms: 0,
            size,
            weight: typography::FOOTNOTE.weight,
            tone,
            visible: false,
            task: None,
        }
    }

    pub(crate) fn weight(mut self, weight: gpui::FontWeight) -> Self {
        self.weight = weight;
        self
    }

    /// Points the line at a time; it ticks only while `visible`.
    pub(crate) fn set(&mut self, since_ms: i64, visible: bool, cx: &mut Context<Self>) {
        if since_ms == self.since_ms && visible == self.visible {
            return;
        }
        self.since_ms = since_ms;
        self.visible = visible;
        self.task = (visible && since_ms > 0).then(|| {
            cx.spawn(async move |this, cx| {
                loop {
                    let wait = crate::incall::ms_to_next_second(since_ms);
                    cx.background_executor()
                        .timer(Duration::from_millis(wait))
                        .await;
                    if this.update(cx, |_, cx| cx.notify()).is_err() {
                        break;
                    }
                }
            })
        });
        cx.notify();
    }
}

impl Render for ElapsedLine {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let color = if self.tone == 1 {
            theme::call_colors(theme).warning
        } else {
            colors.secondary_label
        };
        let time = format_elapsed(now_ms(), self.since_ms);
        let mut text = String::with_capacity(self.prefix.len() + 12);
        text.push_str(self.prefix);
        text.push_str(time.as_str());
        div()
            .truncate()
            .text_size(px(self.size))
            .font_weight(self.weight)
            .text_color(color)
            .child(SharedString::from(text))
    }
}

// ---- Zone state ----------------------------------------------------------------

/// A call that just ended beside a live one: shown as "Call ended" for a second.
#[derive(Clone, Debug)]
pub(crate) struct EndedParty {
    pub party: Party,
}

/// A swap in flight (S20): who was in front, who was held.
#[derive(Clone, Debug)]
pub(crate) struct Crossing {
    pub out: Party,
    pub inn: Party,
}

/// The conference row that is leaving (S22 End) or flying up (S22 Split).
#[derive(Clone, Debug)]
pub(crate) struct Eject {
    pub id: i32,
    pub since: std::time::Instant,
}

/// The springs and view-models of the multi-call zone.
pub(crate) struct ZoneState {
    pub face: Face,
    /// The party in front, and the ids the last scene named (for the moment rules).
    pub primary: Option<Party>,
    pub fg_id: Option<i32>,
    pub bg_id: Option<i32>,
    pub held: Option<Party>,
    /// The held party while the card fades out.
    pub held_ghost: Option<Party>,
    pub conference: Option<Conference>,
    pub ended: Option<EndedParty>,
    pub enablement: Enablement,
    /// 0 = single-call layout, 1 = two-call layout (S19 flies between them).
    pub layout: Spring,
    /// Presence of the Held card (0 = away, 1 = shown); the 120 ms fade.
    pub held_t: Spring,
    /// The new call's avatar drop (S19, `release`).
    pub drop: Spring,
    /// S20 progress, 0 to 1 on `morph`.
    pub swap: Spring,
    pub crossing: Option<Crossing>,
    /// S21 progress, 0 = two calls, 1 = conference.
    pub weave: Spring,
    /// Seconds since the weave began (rows stagger 30 ms).
    pub weave_age: f32,
    /// The party that was in the held card when the weave began (its avatar travels).
    pub weave_from: Option<Party>,
    /// S22 End: the row leaving, `since` its press.
    pub eject: Option<Eject>,
    /// S22 Split: the flying avatar (row index at the press, party) with its spring.
    pub split_fly: Option<(usize, Party)>,
    pub split_t: Spring,
    pub held_line: gpui::Entity<ElapsedLine>,
    pub conf_line: gpui::Entity<ElapsedLine>,
    pub scroll: gpui::ScrollHandle,
    pub press_row: Option<(usize, u8)>,
    /// A scene was applied at least once (the first sync is always a full one).
    pub synced: bool,
    /// The "Call ended" second of a call that ended beside a live one.
    pub ended_task: Option<gpui::Task<()>>,
    /// Reduced motion: the 120 ms cross-fade that replaces S19 to S22.
    pub cross: rovsti_ui::moments::Fade,
    pub tap: rovsti_ui::touch::TapGuard,
    pub bounds: std::rc::Rc<std::cell::Cell<gpui::Bounds<gpui::Pixels>>>,
}

fn sp(value: f32, params: rovsti_ui::spring::SpringParams) -> Spring {
    Spring::new(value, params).with_epsilon(0.002)
}

impl ZoneState {
    pub(crate) fn new(cx: &mut gpui::App) -> Self {
        Self {
            face: Face::Empty,
            primary: None,
            fg_id: None,
            bg_id: None,
            held: None,
            held_ghost: None,
            conference: None,
            ended: None,
            enablement: Enablement::default(),
            layout: sp(0.0, motion_tokens::morph()),
            held_t: sp(0.0, motion_tokens::appear()),
            drop: sp(1.0, motion_tokens::release()),
            swap: sp(1.0, motion_tokens::morph()),
            crossing: None,
            weave: sp(0.0, motion_tokens::select()),
            weave_age: 1.0,
            weave_from: None,
            eject: None,
            split_fly: None,
            split_t: sp(1.0, motion_tokens::morph()),
            held_line: cx.new(|_| ElapsedLine::new("On hold · ", 13.0, 0)),
            conf_line: cx
                .new(|_| ElapsedLine::new("", 15.0, 0).weight(typography::HEADLINE.weight)),
            scroll: gpui::ScrollHandle::new(),
            press_row: None,
            synced: false,
            ended_task: None,
            cross: rovsti_ui::moments::Fade::default(),
            tap: rovsti_ui::touch::TapGuard::default(),
            bounds: std::rc::Rc::new(std::cell::Cell::new(gpui::Bounds::default())),
        }
    }

    pub(crate) fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for s in [
            &mut self.layout,
            &mut self.held_t,
            &mut self.drop,
            &mut self.swap,
            &mut self.weave,
            &mut self.split_t,
        ] {
            moving |= s.tick(dt);
        }
        if self.weave_age < 2.0 {
            self.weave_age += dt.as_secs_f32();
            moving = true;
        }
        if self.crossing.is_some() && self.swap.is_settled() && self.swap.value > 0.99 {
            self.crossing = None;
        }
        if self.held_ghost.is_some() && self.held_t.value < 0.003 && self.held.is_none() {
            self.held_ghost = None;
        }
        if self.split_fly.is_some() && self.split_t.is_settled() && self.split_t.value > 0.99 {
            self.split_fly = None;
        }
        if let Some(e) = &self.eject {
            let age = e.since.elapsed();
            if age > Duration::from_millis(1200) {
                // The snapshot never took the row away: it stays (the End did not happen).
                self.eject = None;
            }
            // The 120 ms leave needs frames until it is done.
            moving |= age < Duration::from_millis(1200);
        }
        moving |= self.cross.tick(dt);
        moving
    }

    /// 0 to 1 over the 120 ms an ejected row takes to leave.
    pub(crate) fn eject_progress(&self, id: i32) -> f32 {
        match &self.eject {
            Some(e) if e.id == id => (e.since.elapsed().as_secs_f32() / 0.12).clamp(0.0, 1.0),
            _ => 0.0,
        }
    }
}

/// Everything the zone needs from one snapshot, owned so the backend borrow ends
/// before the screen is updated. Built only when the scene diff says a part changed.
pub(crate) struct SceneIn {
    pub face: Face,
    pub fg_id: Option<i32>,
    pub bg_id: Option<i32>,
    pub primary: Option<Party>,
    pub held: Option<Party>,
    pub ended: Option<Party>,
    pub conference: Option<Conference>,
    pub enablement: Enablement,
    pub failed: Option<rovsti_core::wire::PendingOp>,
}

impl SceneIn {
    pub(crate) fn of(scene: &CallScene<'_>, failed: Option<rovsti_core::wire::PendingOp>) -> Self {
        Self {
            face: face_of(scene),
            fg_id: scene.primary().map(|c| c.id),
            bg_id: scene.held().map(|c| c.id),
            primary: scene.primary().map(Party::of),
            held: scene.held().map(Party::of),
            ended: scene.ended.map(Party::of),
            conference: conference_of(scene),
            enablement: Enablement::of(scene),
            failed,
        }
    }
}

/// What a new scene means for the zone's moments.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Moment {
    None,
    /// A second call became the active one and the first went on hold.
    HoldAway,
    /// The active and the held call exchanged places.
    Swap,
    /// Two calls became a conference.
    Merge,
}

/// Which moment going from `old` to `new` is. Pure, so a test pins the rules.
pub(crate) fn moment(
    old: (Face, Option<i32>, Option<i32>),
    new: (Face, Option<i32>, Option<i32>),
) -> Moment {
    let (of, ofg, obg) = old;
    let (nf, nfg, nbg) = new;
    match (of, nf) {
        (Face::Single, Face::Two) if ofg.is_some() && nbg == ofg && nfg != ofg => Moment::HoldAway,
        (Face::Two, Face::Two) if ofg.is_some() && ofg == nbg && obg == nfg && ofg != nfg => {
            Moment::Swap
        }
        (Face::Two, Face::Conference) => Moment::Merge,
        _ => Moment::None,
    }
}

/// Builds the face and view-models from a scene.
pub(crate) fn face_of(scene: &CallScene<'_>) -> Face {
    match scene.layout {
        Layout::Empty => Face::Empty,
        Layout::Single { .. } => Face::Single,
        Layout::Two { .. } => Face::Two,
        Layout::Conference { .. } => Face::Conference,
    }
}

pub(crate) fn conference_of(scene: &CallScene<'_>) -> Option<Conference> {
    match scene.layout {
        Layout::Conference {
            host, participants, ..
        } => Some(Conference::of(host, &participants)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::wire::{CallSnapshot, WireCallState, caps};

    fn call(id: i32, state: WireCallState) -> WireCall {
        WireCall {
            id,
            state,
            number_raw: format!("0555 111 22 0{id}"),
            connect_time_ms: 1,
            can_hold: true,
            caps: caps::HOLD,
            ..WireCall::default()
        }
    }

    fn reasons(s: &CallSnapshot) -> Enablement {
        Enablement::of(&CallScene::of(s))
    }

    #[test]
    fn the_zone_arithmetic_is_392_on_the_reference_screen() {
        assert_eq!(slots::zone_height(874.0), 392.0);
        assert_eq!(slots::GRID_H, 216.0);
        assert_eq!(slots::CELL_H, 96.0);
        // The tallest scene (conference + held card) fits.
        const { assert!(slots::CONTENT_H <= 392.0) };
        assert_eq!(slots::TWO_HELD_Y + slots::HELD_H, 256.0);
        assert_eq!(slots::CONF_PANEL_Y + slots::PANEL_H, 282.0);
    }

    #[test]
    fn one_live_call_enables_everything_the_platform_allows() {
        let mut s = CallSnapshot {
            calls: vec![call(1, WireCallState::Active)],
            foreground_call_id: Some(1),
            can_add_call: true,
            ..CallSnapshot::default()
        };
        let e = reasons(&s);
        assert!(e.enabled(Slot::Mute) && e.enabled(Slot::Keypad) && e.enabled(Slot::Audio));
        assert!(e.enabled(Slot::AddCall) && e.enabled(Slot::HoldSwap));
        assert_eq!(e.reason(Slot::Merge), Some(DisabledReason::NeedsTwoCalls));
        assert!(!e.swap);
        s.can_add_call = false;
        assert_eq!(
            reasons(&s).reason(Slot::AddCall),
            Some(DisabledReason::AddCallCarrier)
        );
    }

    #[test]
    fn two_calls_swap_and_merge_follow_the_flags() {
        let mut s = CallSnapshot {
            calls: vec![
                call(1, WireCallState::Holding),
                call(2, WireCallState::Active),
            ],
            foreground_call_id: Some(2),
            background_call_id: Some(1),
            can_add_call: true,
            can_swap: true,
            can_merge: true,
            ..CallSnapshot::default()
        };
        let e = reasons(&s);
        assert!(e.swap && e.enabled(Slot::HoldSwap) && e.enabled(Slot::Merge));
        assert_eq!(e.reason(Slot::AddCall), Some(DisabledReason::TwoCalls));
        s.can_merge = false;
        assert_eq!(
            reasons(&s).reason(Slot::Merge),
            Some(DisabledReason::MergeCarrier)
        );
        s.can_swap = false;
        assert_eq!(
            reasons(&s).reason(Slot::HoldSwap),
            Some(DisabledReason::SwapUnsupported)
        );
    }

    #[test]
    fn a_held_single_call_dims_mute_keypad_audio_and_add_call() {
        let s = CallSnapshot {
            calls: vec![call(1, WireCallState::Holding)],
            foreground_call_id: Some(1),
            can_add_call: true,
            ..CallSnapshot::default()
        };
        let e = reasons(&s);
        for slot in [Slot::Mute, Slot::Keypad, Slot::Audio, Slot::AddCall] {
            assert_eq!(e.reason(slot), Some(DisabledReason::OnHold), "{slot:?}");
        }
        // Resume stays available.
        assert!(e.enabled(Slot::HoldSwap));
    }

    #[test]
    fn an_emergency_call_dims_add_call_and_merge_with_the_emergency_reason() {
        let mut c = call(1, WireCallState::Active);
        c.props = rovsti_core::wire::props::EMERGENCY;
        let s = CallSnapshot {
            calls: vec![c],
            foreground_call_id: Some(1),
            can_add_call: true,
            ..CallSnapshot::default()
        };
        let e = reasons(&s);
        assert_eq!(e.reason(Slot::AddCall), Some(DisabledReason::Emergency));
        assert_eq!(e.reason(Slot::Merge), Some(DisabledReason::Emergency));
    }

    #[test]
    fn moments_are_told_apart_by_the_roles_that_moved() {
        use Face::*;
        // Add call: the first call (1) went on hold, the new call (2) is in front.
        assert_eq!(
            moment((Single, Some(1), None), (Two, Some(2), Some(1))),
            Moment::HoldAway
        );
        // Swap: 2 and 1 exchanged.
        assert_eq!(
            moment((Two, Some(2), Some(1)), (Two, Some(1), Some(2))),
            Moment::Swap
        );
        // Merge.
        assert_eq!(
            moment((Two, Some(2), Some(1)), (Conference, Some(9), None)),
            Moment::Merge
        );
        // A hang-up leaves the zone without a moment.
        assert_eq!(
            moment((Two, Some(2), Some(1)), (Single, Some(1), None)),
            Moment::None
        );
        // Nothing moved.
        assert_eq!(
            moment((Two, Some(2), Some(1)), (Two, Some(2), Some(1))),
            Moment::None
        );
    }

    #[test]
    fn a_conference_builds_rows_with_actions_only_where_the_child_reports_them() {
        let mut host = call(9, WireCallState::Active);
        host.props = rovsti_core::wire::props::CONFERENCE;
        host.children = vec![1, 2];
        let mut a = call(1, WireCallState::Active);
        a.parent_id = Some(9);
        a.caps = caps::SPLIT | caps::DISCONNECT_PARTICIPANT;
        a.display_name = Some("Özgür Kaya".into());
        let mut b = call(2, WireCallState::Active);
        b.parent_id = Some(9);
        b.number_raw = String::new();
        let s = CallSnapshot {
            calls: vec![a, b, host],
            foreground_call_id: Some(9),
            ..CallSnapshot::default()
        };
        let scene = CallScene::of(&s);
        let conf = conference_of(&scene).expect("a conference");
        assert_eq!(conf.rows.len(), 2);
        assert!(conf.rows[0].can_split && conf.rows[0].can_drop);
        assert_eq!(&*conf.rows[0].label, "Özgür Kaya");
        assert_eq!(&*conf.rows[0].aria, "Özgür Kaya. Actions: Split, End");
        assert!(!conf.rows[1].can_split && !conf.rows[1].can_drop);
        assert_eq!(&*conf.rows[1].label, "Participant 2");
        assert_eq!(&*conf.rows[1].subtitle, "Participant");
        assert!(conf.summary.is_none());
        assert_eq!(conf.stack.len(), 2);
        assert_eq!(conf.extra, 0);
    }

    #[test]
    fn hidden_participants_become_one_summary() {
        let mut host = call(9, WireCallState::Active);
        host.props = rovsti_core::wire::props::CONFERENCE;
        host.children = vec![21, 22, 23];
        let s = CallSnapshot {
            calls: vec![host],
            foreground_call_id: Some(9),
            ..CallSnapshot::default()
        };
        let scene = CallScene::of(&s);
        let conf = conference_of(&scene).expect("a conference");
        assert!(conf.rows.is_empty());
        assert_eq!(conf.summary.as_deref(), Some("3 people"));
    }

    #[test]
    fn more_than_three_participants_overflow_into_a_count() {
        let mut host = call(9, WireCallState::Active);
        host.props = rovsti_core::wire::props::CONFERENCE;
        host.children = (1..=6).collect();
        let mut calls: Vec<WireCall> = (1..=6)
            .map(|i| WireCall {
                parent_id: Some(9),
                ..call(i, WireCallState::Active)
            })
            .collect();
        calls.push(host);
        let s = CallSnapshot {
            calls,
            foreground_call_id: Some(9),
            ..CallSnapshot::default()
        };
        let conf = conference_of(&CallScene::of(&s)).expect("a conference");
        assert_eq!(conf.rows.len(), 6);
        assert_eq!(conf.stack.len(), 3);
        assert_eq!(conf.extra, 3);
    }
}
