//! Picking A and B on the map: the pure state machine behind the pins.
//!
//! No GPUI here. The map glue feeds taps, drags and menu choices in, and
//! reads the stage, the hint and the labels out. Points are shown by name or
//! as "Point A" / "Point B"; a coordinate is only ever a details line.

use rovsti_geo::{
    Coord, DiameterError, Endpoint, MAX_DIAMETER_M, MIN_DIAMETER_M, PlaceError, SavedPlaces,
    places::DUPLICATE_RADIUS_M,
};

use crate::{
    picker::{Destination, Ends, Slot, coord_text},
    trip_ui::distance_text,
};

/// The empty-search line of the place picker.
pub fn no_match_text(query: &str, has_feed: bool) -> String {
    let q = query.trim();
    if has_feed {
        format!("No stops or saved places match \u{201c}{q}\u{201d}.")
    } else {
        format!(
            "No saved places match \u{201c}{q}\u{201d}. Import a GTFS feed in Settings to search stops too."
        )
    }
}

/// How much of the pair is chosen.
#[cfg(test)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Stage {
    Idle,
    HasA,
    HasB,
    HasAB,
}

/// What a tap on the map did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TapOutcome {
    /// A pin was dropped into this slot.
    Placed(Slot),
    /// A pin menu was open: the tap only closed it.
    MenuClosed,
    /// Both pins are set and none is being moved: nothing changed.
    Full,
}

/// The line that tells the user what to do next.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Hint {
    SetA,
    SetB,
    Moving(Slot),
    Ready { metres: f64 },
    Invalid(DiameterError),
}

impl Hint {
    pub fn text(&self) -> String {
        match self {
            Self::SetA => "Tap the map to set A".to_owned(),
            Self::SetB => "Tap the map to set B".to_owned(),
            Self::Moving(slot) => format!("Tap the map to move {}", slot.label()),
            Self::Ready { metres } => distance_line(*metres),
            Self::Invalid(DiameterError::TooShort | DiameterError::SamePoint) => format!(
                "A and B are too close. Drag a pin at least {} m away.",
                MIN_DIAMETER_M as i64
            ),
            Self::Invalid(DiameterError::TooLong) => format!(
                "A and B are more than {} km apart. Move a pin closer.",
                (MAX_DIAMETER_M / 1_000.0) as i64
            ),
            Self::Invalid(error) => error.message().to_owned(),
        }
    }
}

/// What the save-place field starts with: the point's name, else nothing.
pub fn save_prefill(dest: &Destination) -> String {
    dest.label.trim().to_owned()
}

/// A stored endpoint as a destination: generic labels ("Point A") mean no
/// name, and a saved place within reach gives its current name.
pub fn dest_from_endpoint(ep: &Endpoint, saved: &SavedPlaces) -> Destination {
    let dest = match ep.label.trim() {
        "" | "Point" | "Point A" | "Point B" => Destination::dropped(ep.coord),
        label => Destination::new(label, ep.coord),
    };
    dest.named_from(saved)
}

/// The toast after trying to save a place.
pub fn save_result_text(result: &Result<u64, PlaceError>, saved: &SavedPlaces) -> String {
    let name = |id: &u64| saved.get(*id).map(|p| p.name.clone()).unwrap_or_default();
    match result {
        Ok(id) => format!("Saved as {}", name(id)),
        Err(PlaceError::Duplicate(id)) => format!("Already saved as {}", name(id)),
        Err(error) => error.message().to_owned(),
    }
}

/// The hint while a saved place is being moved.
#[allow(dead_code)] // wired up by a later step
pub fn relocate_hint(name: &str) -> String {
    if name.is_empty() {
        "Tap the map to move the place".to_owned()
    } else {
        format!("Tap the map to move {name}")
    }
}

/// The toast after trying to move a saved place.
#[allow(dead_code)] // wired up by a later step
pub fn relocate_result_text(
    result: &Result<(Coord, Option<String>), PlaceError>,
    name: &str,
    saved: &SavedPlaces,
) -> String {
    match result {
        Ok(_) => format!("Moved {name}"),
        Err(PlaceError::Duplicate(id)) => {
            let other = saved.get(*id).map(|p| p.name.clone()).unwrap_or_default();
            format!("{other} is already saved there")
        }
        Err(error) => error.message().to_owned(),
    }
}

/// The pair being picked, with the transient state of the pin gestures.
#[derive(Debug, Default)]
pub struct Picking {
    pub ends: Ends,
    /// "Move" was chosen: the next tap relocates this pin.
    moving: Option<Slot>,
    /// The pin menu is open for this slot.
    menu: Option<Slot>,
    /// The pin being dragged and its value before the drag.
    drag: Option<(Slot, Destination)>,
    /// The slot the sheet (saved, recent, links) fills.
    sheet_slot: Slot,
}

// Methods the map glue does not call yet are listed with their step.
impl Picking {
    #[cfg(test)]
    pub fn stage(&self) -> Stage {
        match (&self.ends.a, &self.ends.b) {
            (None, None) => Stage::Idle,
            (Some(_), None) => Stage::HasA,
            (None, Some(_)) => Stage::HasB,
            (Some(_), Some(_)) => Stage::HasAB,
        }
    }

    /// Where a tap on empty map goes: the pin being moved, else the first
    /// empty end (A, then B), else nowhere.
    pub fn slot_for_tap(&self) -> Option<Slot> {
        if let Some(slot) = self.moving {
            return Some(slot);
        }
        if self.ends.a.is_none() {
            Some(Slot::A)
        } else if self.ends.b.is_none() {
            Some(Slot::B)
        } else {
            None
        }
    }

    /// A tap on the map that resolved to `dest`.
    pub fn tap(&mut self, dest: Destination) -> TapOutcome {
        if self.menu.is_some() {
            self.menu = None;
            return TapOutcome::MenuClosed;
        }
        match self.slot_for_tap() {
            Some(slot) => {
                self.place(slot, dest);
                TapOutcome::Placed(slot)
            }
            None => TapOutcome::Full,
        }
    }

    /// Puts `dest` into `slot`; the sheet then fills the other end.
    pub fn place(&mut self, slot: Slot, dest: Destination) {
        self.ends.set(slot, dest);
        self.moving = None;
        self.menu = None;
        self.sheet_slot = self.ends.next_slot();
    }

    pub fn set_pair(&mut self, a: Destination, b: Destination) {
        self.ends.set_pair(a, b);
        self.moving = None;
        self.menu = None;
        self.drag = None;
        self.sheet_slot = self.ends.next_slot();
    }

    /// Renames each end within [`DUPLICATE_RADIUS_M`] of `coord`.
    pub fn relabel_near(&mut self, coord: Coord, label: &str) {
        for end in [&mut self.ends.a, &mut self.ends.b].into_iter().flatten() {
            if end.coord.distance_m(coord) <= DUPLICATE_RADIUS_M {
                end.label = label.to_owned();
            }
        }
    }

    /// Puts back ends captured earlier (undo), dropping gestures in progress.
    pub fn restore(&mut self, ends: Ends) {
        self.ends = ends;
        self.moving = None;
        self.menu = None;
        self.drag = None;
        self.sheet_slot = self.ends.next_slot();
    }

    pub fn swap(&mut self) {
        self.ends.swap();
        self.menu = None;
        self.moving = None;
    }

    /// The ends differ from what was last written as the draft, and no pin is
    /// mid-drag (a drag changes the ends every frame; write once it settles).
    pub fn draft_due(&self, written: &Ends) -> bool {
        self.dragging().is_none() && self.ends != *written
    }

    /// Forgets both points and every gesture in progress.
    pub fn clear(&mut self) {
        *self = Self::default();
    }
    pub fn remove(&mut self, slot: Slot) {
        match slot {
            Slot::A => self.ends.a = None,
            Slot::B => self.ends.b = None,
        }
        self.moving = None;
        self.menu = None;
        self.drag = None;
        self.sheet_slot = self.ends.next_slot();
    }

    /// Arms `slot`: the next tap on the map relocates it.
    pub fn begin_move(&mut self, slot: Slot) {
        self.moving = Some(slot);
        self.menu = None;
    }

    pub fn open_menu(&mut self, slot: Slot) {
        self.menu = Some(slot);
        self.moving = None;
    }
    pub fn close_menu(&mut self) {
        self.menu = None;
    }
    pub fn menu(&self) -> Option<Slot> {
        self.menu
    }
    #[cfg(test)]
    pub fn moving(&self) -> Option<Slot> {
        self.moving
    }

    /// Grabs the pin in `slot`. `false` when that end is empty.
    pub fn drag_start(&mut self, slot: Slot) -> bool {
        self.drag_cancel();
        let Some(original) = self.ends.get(slot).cloned() else {
            return false;
        };
        self.drag = Some((slot, original));
        self.menu = None;
        self.moving = None;
        true
    }

    /// The grabbed pin follows the finger: a raw, unnamed point.
    pub fn drag_to(&mut self, slot: Slot, coord: Coord) {
        if self.drag.as_ref().is_some_and(|(s, _)| *s == slot) {
            self.ends.set(slot, Destination::dropped(coord));
        }
    }

    /// The pin was let go at `dest` (a stop when it snapped).
    pub fn drag_end(&mut self, slot: Slot, dest: Destination) {
        if self.drag.as_ref().is_some_and(|(s, _)| *s == slot) {
            self.drag = None;
            self.place(slot, dest);
        }
    }

    /// The drag was interrupted: the pin goes back where it was.
    pub fn drag_cancel(&mut self) {
        if let Some((slot, original)) = self.drag.take() {
            self.ends.set(slot, original);
        }
    }

    pub fn dragging(&self) -> Option<Slot> {
        self.drag.as_ref().map(|(slot, _)| *slot)
    }

    pub fn sheet_slot(&self) -> Slot {
        self.sheet_slot
    }

    pub fn set_sheet_slot(&mut self, slot: Slot) {
        self.sheet_slot = slot;
    }

    pub fn hint(&self) -> Hint {
        if let Some(slot) = self.moving {
            return Hint::Moving(slot);
        }
        match self.ends.diameter() {
            Some(Ok(d)) => Hint::Ready {
                metres: d.length_m(),
            },
            Some(Err(error)) => Hint::Invalid(error),
            None if self.ends.a.is_none() => Hint::SetA,
            None => Hint::SetB,
        }
    }

    /// Start is allowed: a valid pair and no pin in the air.
    /// The end the confirmation sheet emphasises: the pin being moved, else the
    /// first empty end, else A.
    pub fn confirm_slot(&self) -> Slot {
        self.slot_for_tap().unwrap_or(Slot::A)
    }

    pub fn can_start(&self) -> bool {
        self.drag.is_none() && matches!(self.ends.diameter(), Some(Ok(_)))
    }
}

/// "A-B 850 m".
fn distance_line(metres: f64) -> String {
    format!("A-B {}", distance_text(metres))
}

/// What a point is called: its name, else "Point A" / "Point B".
pub fn point_label(slot: Slot, dest: Option<&Destination>) -> String {
    match dest {
        Some(d) if d.is_named() => d.label.trim().to_owned(),
        _ => format!("Point {}", slot.label()),
    }
}

/// Clipboard text ready for a field: trimmed, line breaks joined with one
/// space; `None` when nothing is left.
pub fn clean_paste(text: Option<&str>) -> Option<String> {
    let joined = text?.split_whitespace().collect::<Vec<_>>().join(" ");
    (!joined.is_empty()).then_some(joined)
}

/// Pill text after a point is queued for the next trip.
#[allow(dead_code)] // wired up by a later step
pub fn queued_text(slot: Slot, dest: &Destination) -> String {
    format!(
        "{} is {} for your next trip",
        point_label(slot, Some(dest)),
        slot.label()
    )
}

/// A stored label for a list row: itself, else "Point" (a dropped point has
/// none, and an old record may not either).
pub fn row_label(label: &str) -> String {
    let label = label.trim();
    if label.is_empty() {
        "Point".to_owned()
    } else {
        label.to_owned()
    }
}

/// "A-B 850 m" once both points are set.
#[cfg(test)]
pub fn pair_text(ends: &Ends) -> Option<String> {
    let (a, b) = (ends.a.as_ref()?, ends.b.as_ref()?);
    Some(distance_line(a.coord.distance_m(b.coord)))
}

/// The coordinates, for an optional details line only.
pub fn details_text(dest: &Destination) -> String {
    coord_text(dest.coord)
}

/// The point as the trip stores it, its label frozen to what the user saw.
pub fn trip_endpoint(slot: Slot, dest: &Destination) -> Endpoint {
    Endpoint {
        label: point_label(slot, Some(dest)),
        coord: dest.coord,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn no_match_text_names_the_query() {
        assert_eq!(
            no_match_text("  moda ", true),
            "No stops or saved places match \u{201c}moda\u{201d}."
        );
        assert_eq!(
            no_match_text("moda", false),
            "No saved places match \u{201c}moda\u{201d}. Import a GTFS feed in Settings to search stops too."
        );
    }

    /// 0.001 degrees of longitude at 41N is about 84 m.
    fn at(metres: f64) -> Destination {
        Destination::dropped(Coord::new(41.0, 29.0 + metres / 84_000.0))
    }

    fn stop(name: &str) -> Destination {
        Destination {
            gtfs_stop: Some("S1".into()),
            pattern: Some(4),
            ..Destination::new(name, Coord::new(41.0, 29.0))
        }
    }

    fn two() -> Picking {
        let mut p = Picking::default();
        p.tap(at(0.0));
        p.tap(at(850.0));
        p
    }

    fn has_coordinate(text: &str) -> bool {
        let b = text.as_bytes();
        b.windows(5)
            .any(|w| w[0].is_ascii_digit() && w[1] == b'.' && w[2..].iter().all(u8::is_ascii_digit))
            || text.contains(", ") && text.split(", ").all(|p| p.parse::<f64>().is_ok())
    }

    #[test]
    fn clean_paste_trims_and_joins_lines() {
        assert_eq!(clean_paste(None), None);
        assert_eq!(clean_paste(Some("  \n\t ")), None);
        assert_eq!(
            clean_paste(Some(" Kadikoy Pier\n41.0, 29.0\r\n")).as_deref(),
            Some("Kadikoy Pier 41.0, 29.0")
        );
        assert_eq!(
            clean_paste(Some("https://maps.app.goo.gl/abc")).as_deref(),
            Some("https://maps.app.goo.gl/abc")
        );
    }

    #[test]
    fn queued_text_names_the_place_or_the_slot() {
        assert_eq!(
            queued_text(Slot::B, &stop("Taksim")),
            "Taksim is B for your next trip"
        );
        assert_eq!(
            queued_text(Slot::A, &at(0.0)),
            "Point A is A for your next trip"
        );
    }

    #[test]
    fn endpoint_labelled_point_a_is_unnamed_as_b() {
        let ep = Endpoint {
            label: "Point A".into(),
            coord: Coord::new(41.0, 29.0),
        };
        let d = dest_from_endpoint(&ep, &SavedPlaces::default());
        assert!(!d.is_named());
        assert_eq!(point_label(Slot::B, Some(&d)), "Point B");
    }

    #[test]
    fn endpoint_near_a_renamed_place_takes_the_new_name() {
        let mut saved = SavedPlaces::default();
        saved
            .save("Work", Coord::new(41.0, 29.0), Some("W1".into()))
            .unwrap();
        let ep = Endpoint {
            label: "Old name".into(),
            coord: Coord::new(41.0, 29.0001),
        };
        let d = dest_from_endpoint(&ep, &saved);
        assert_eq!(d.label, "Work");
        assert_eq!(d.gtfs_stop.as_deref(), Some("W1"));
    }

    #[test]
    fn a_named_far_endpoint_keeps_its_label() {
        let mut saved = SavedPlaces::default();
        saved.save("Work", Coord::new(41.0, 29.0), None).unwrap();
        let ep = Endpoint {
            label: " Pier ".into(),
            coord: Coord::new(41.0, 29.1),
        };
        assert_eq!(dest_from_endpoint(&ep, &saved).label, "Pier");
    }

    #[test]
    fn a_fresh_picking_is_idle_and_asks_for_a() {
        let p = Picking::default();
        assert_eq!(p.stage(), Stage::Idle);
        assert_eq!(p.hint().text(), "Tap the map to set A");
        assert!(!p.can_start());
    }

    #[test]
    fn two_taps_fill_a_then_b() {
        let mut p = Picking::default();
        assert_eq!(p.tap(at(0.0)), TapOutcome::Placed(Slot::A));
        assert_eq!(p.stage(), Stage::HasA);
        assert_eq!(p.hint().text(), "Tap the map to set B");
        assert_eq!(p.tap(at(850.0)), TapOutcome::Placed(Slot::B));
        assert_eq!(p.stage(), Stage::HasAB);
        assert!(p.ends.a.as_ref().unwrap().coord.lon < p.ends.b.as_ref().unwrap().coord.lon);
    }

    #[test]
    fn a_third_tap_is_full_and_changes_nothing() {
        let mut p = two();
        let before = p.ends.clone();
        assert_eq!(p.tap(at(5_000.0)), TapOutcome::Full);
        assert_eq!(p.ends, before);
    }

    #[test]
    fn a_tap_with_the_menu_open_only_closes_it() {
        let mut p = Picking::default();
        p.tap(at(0.0));
        p.open_menu(Slot::A);
        assert_eq!(p.menu(), Some(Slot::A));
        assert_eq!(p.tap(at(850.0)), TapOutcome::MenuClosed);
        assert_eq!(p.menu(), None);
        assert_eq!(p.stage(), Stage::HasA);
        p.open_menu(Slot::A);
        p.close_menu();
        assert_eq!(p.menu(), None);
    }

    #[test]
    fn removing_a_leaves_b_and_the_next_tap_fills_a() {
        let mut p = two();
        p.remove(Slot::A);
        assert_eq!(p.stage(), Stage::HasB);
        assert_eq!(p.hint().text(), "Tap the map to set A");
        assert_eq!(p.sheet_slot(), Slot::A);
        assert_eq!(p.tap(at(10.0)), TapOutcome::Placed(Slot::A));
        assert_eq!(p.stage(), Stage::HasAB);
    }

    #[test]
    fn move_relocates_the_armed_pin_and_disarms() {
        let mut p = two();
        p.begin_move(Slot::B);
        assert_eq!(p.moving(), Some(Slot::B));
        assert_eq!(p.hint().text(), "Tap the map to move B");
        assert_eq!(p.tap(at(400.0)), TapOutcome::Placed(Slot::B));
        assert_eq!(p.moving(), None);
        assert_eq!(pair_text(&p.ends).unwrap(), "A-B 400 m");
        assert_eq!(p.tap(at(1.0)), TapOutcome::Full);
    }

    #[test]
    fn clear_goes_back_to_idle() {
        let mut p = two();
        p.open_menu(Slot::A);
        p.clear();
        assert_eq!(p.stage(), Stage::Idle);
        assert_eq!(p.menu(), None);
        assert_eq!(p.sheet_slot(), Slot::A);
    }

    #[test]
    fn dragging_makes_the_pin_a_raw_point() {
        let mut p = Picking::default();
        p.place(Slot::A, stop("Taksim"));
        assert!(p.drag_start(Slot::A));
        assert_eq!(p.dragging(), Some(Slot::A));
        p.drag_to(Slot::A, Coord::new(41.1, 29.1));
        let a = p.ends.a.as_ref().unwrap();
        assert_eq!(a.coord, Coord::new(41.1, 29.1));
        assert!(a.label.is_empty() && a.gtfs_stop.is_none() && a.pattern.is_none());
        p.drag_end(Slot::A, stop("Besiktas"));
        assert_eq!(p.dragging(), None);
        assert_eq!(p.ends.a.as_ref().unwrap().label, "Besiktas");
    }

    #[test]
    fn dragging_an_empty_end_is_refused() {
        let mut p = Picking::default();
        assert!(!p.drag_start(Slot::B));
        p.drag_to(Slot::B, Coord::new(41.0, 29.0));
        assert!(p.ends.b.is_none());
    }

    #[test]
    fn cancelling_a_drag_restores_the_original() {
        let mut p = Picking::default();
        p.place(Slot::A, stop("Taksim"));
        let original = p.ends.clone();
        p.drag_start(Slot::A);
        p.drag_to(Slot::A, Coord::new(41.5, 29.5));
        p.drag_cancel();
        assert_eq!(p.ends, original);
        assert_eq!(p.dragging(), None);
    }

    #[test]
    fn start_needs_a_valid_pair_and_no_drag() {
        let mut p = two();
        assert!(p.can_start());
        p.drag_start(Slot::B);
        assert!(!p.can_start());
        p.drag_cancel();
        assert!(p.can_start());

        let mut near = Picking::default();
        near.tap(at(0.0));
        near.tap(at(10.0));
        assert!(!near.can_start());

        let mut far = Picking::default();
        far.tap(at(0.0));
        far.tap(at(60_000.0));
        assert!(!far.can_start());
    }

    #[test]
    fn every_hint_has_its_text() {
        assert_eq!(Hint::SetA.text(), "Tap the map to set A");
        assert_eq!(Hint::SetB.text(), "Tap the map to set B");
        assert_eq!(Hint::Moving(Slot::A).text(), "Tap the map to move A");
        assert_eq!(Hint::Ready { metres: 850.0 }.text(), "A-B 850 m");
        let close = "A and B are too close. Drag a pin at least 20 m away.";
        assert_eq!(Hint::Invalid(DiameterError::TooShort).text(), close);
        assert_eq!(Hint::Invalid(DiameterError::SamePoint).text(), close);
        assert_eq!(
            Hint::Invalid(DiameterError::TooLong).text(),
            "A and B are more than 50 km apart. Move a pin closer."
        );
        assert_eq!(
            two().hint(),
            Hint::Ready {
                metres: two().hint_metres()
            }
        );
    }

    impl Picking {
        fn hint_metres(&self) -> f64 {
            match self.hint() {
                Hint::Ready { metres } => metres,
                other => panic!("{other:?}"),
            }
        }
    }

    #[test]
    fn the_hints_follow_the_pair() {
        let mut near = Picking::default();
        near.tap(at(0.0));
        near.tap(at(5.0));
        assert!(matches!(near.hint(), Hint::Invalid(_)));
        let mut far = Picking::default();
        far.tap(at(0.0));
        far.tap(at(60_000.0));
        assert_eq!(far.hint(), Hint::Invalid(DiameterError::TooLong));
    }

    #[test]
    fn labels_follow_the_slot_after_a_swap() {
        let mut p = Picking::default();
        p.tap(stop("Taksim"));
        p.tap(at(850.0));
        p.swap();
        assert_eq!(point_label(Slot::A, p.ends.a.as_ref()), "Point A");
        assert_eq!(point_label(Slot::B, p.ends.b.as_ref()), "Taksim");
        assert_eq!(point_label(Slot::B, None), "Point B");
    }

    #[test]
    fn pair_text_uses_metres_and_kilometres() {
        assert_eq!(pair_text(&two().ends).unwrap(), "A-B 850 m");
        let mut p = Picking::default();
        p.tap(at(0.0));
        assert_eq!(pair_text(&p.ends), None);
        p.tap(at(1_200.0));
        assert_eq!(pair_text(&p.ends).unwrap(), "A-B 1.2 km");
    }

    #[test]
    fn the_trip_freezes_the_label_the_user_saw() {
        let p = two();
        let b = trip_endpoint(Slot::B, p.ends.b.as_ref().unwrap());
        assert_eq!(b.label, "Point B");
        assert_eq!(b.coord, p.ends.b.as_ref().unwrap().coord);
        let named = trip_endpoint(Slot::A, &stop("Taksim"));
        assert_eq!(named.label, "Taksim");
    }

    #[test]
    fn details_keep_the_coordinates() {
        assert_eq!(details_text(&stop("X")), "41.0000, 29.0000");
    }

    #[test]
    fn unnamed_points_never_show_a_coordinate() {
        let p = two();
        let a = p.ends.a.as_ref().unwrap();
        let texts = [
            point_label(Slot::A, Some(a)),
            point_label(Slot::B, p.ends.b.as_ref()),
            p.hint().text(),
            Hint::SetA.text(),
            Hint::Moving(Slot::B).text(),
            Hint::Invalid(DiameterError::TooShort).text(),
            Hint::Invalid(DiameterError::TooLong).text(),
            trip_endpoint(Slot::A, a).label,
            a.title(),
        ];
        for text in texts {
            assert!(!has_coordinate(&text), "{text}");
        }
        assert!(has_coordinate(&details_text(a)));
    }

    #[test]
    fn the_confirm_sheet_emphasises_the_open_end_else_a() {
        let mut p = Picking::default();
        assert_eq!(p.confirm_slot(), Slot::A);
        p.place(Slot::A, Destination::dropped(Coord::new(41.0, 29.0)));
        assert_eq!(p.confirm_slot(), Slot::B);
        p.place(Slot::B, Destination::dropped(Coord::new(41.0, 29.01)));
        assert_eq!(p.confirm_slot(), Slot::A);
        p.begin_move(Slot::B);
        assert_eq!(p.confirm_slot(), Slot::B);
    }

    #[test]
    fn list_rows_fall_back_for_empty_labels() {
        assert_eq!(row_label("Home"), "Home");
        assert_eq!(row_label("  Home "), "Home");
        assert_eq!(row_label(""), "Point");
        assert_eq!(row_label("   "), "Point");
    }

    #[test]
    fn save_prefill_is_the_trimmed_label() {
        assert_eq!(
            save_prefill(&Destination::new("  Cafe ", Coord::new(41.0, 29.0))),
            "Cafe"
        );
        assert_eq!(save_prefill(&at(0.0)), "");
    }

    #[test]
    fn save_result_text_covers_ok_duplicate_and_errors() {
        let mut saved = SavedPlaces::default();
        let id = saved.add("Home", Coord::new(41.0, 29.0)).unwrap();
        assert_eq!(save_result_text(&Ok(id), &saved), "Saved as Home");
        assert_eq!(
            save_result_text(&Err(PlaceError::Duplicate(id)), &saved),
            "Already saved as Home"
        );
        assert_eq!(
            save_result_text(&Err(PlaceError::InvalidName), &saved),
            PlaceError::InvalidName.message()
        );
    }

    #[test]
    fn relocate_hint_names_the_place_or_falls_back() {
        assert_eq!(relocate_hint("Home"), "Tap the map to move Home");
        assert_eq!(relocate_hint(""), "Tap the map to move the place");
    }

    #[test]
    fn relocate_result_text_covers_ok_duplicate_and_errors() {
        let mut saved = SavedPlaces::default();
        let id = saved.add("Work", Coord::new(41.0, 29.0)).unwrap();
        let ok = Ok((Coord::new(41.1, 29.1), None));
        assert_eq!(relocate_result_text(&ok, "Home", &saved), "Moved Home");
        assert_eq!(
            relocate_result_text(&Err(PlaceError::Duplicate(id)), "Home", &saved),
            "Work is already saved there"
        );
        assert_eq!(
            relocate_result_text(&Err(PlaceError::InvalidCoord), "Home", &saved),
            PlaceError::InvalidCoord.message()
        );
    }

    #[test]
    fn relabel_near_touches_only_nearby_ends() {
        let mut p = two();
        p.relabel_near(Coord::new(41.0, 29.0), "Home");
        assert_eq!(p.ends.a.as_ref().unwrap().label, "Home");
        assert_eq!(p.ends.b.as_ref().unwrap().label, "");
    }

    #[test]
    fn restore_round_trips() {
        let mut p = two();
        let before = p.ends.clone();
        p.remove(Slot::B);
        p.open_menu(Slot::A);
        p.restore(before.clone());
        assert_eq!(p.ends, before);
        assert_eq!(p.menu(), None);
    }

    #[test]
    fn draft_is_due_only_when_changed_and_not_dragging() {
        let mut p = two();
        let written = p.ends.clone();
        assert!(!p.draft_due(&written));
        assert!(p.draft_due(&Ends::default()));
        p.remove(Slot::B);
        assert!(p.draft_due(&written));
        let mut d = two();
        assert!(d.drag_start(Slot::A));
        d.drag_to(Slot::A, Coord::new(1.0, 2.0));
        assert!(!d.draft_due(&Ends::default()));
        d.drag_cancel();
        assert!(d.draft_due(&Ends::default()));
    }
}
