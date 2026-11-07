//! The sheets opened from the map: "My location" (tap the blue dot) and the
//! measure tool's "More". The row lists are pure and tested; the bodies are
//! thin glue over `list_row` and the existing copy, share, QR and save paths.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement,
    StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_geo::{Coord, Measure};
use rovsti_ui::{
    components::{list_row, section},
    haptics,
    tokens::Theme,
};

use crate::{
    StopsApp, backend, pick,
    picker::{Destination, SheetMode, Slot},
    saved_ui::export_stamp,
    settings_ui::distance_text,
    store::now_ms,
};

/// The measure sheet's rows for `m`: nothing below two points, and "Save as
/// stop" only when the first and last point make a valid gate.
pub fn measure_actions(m: &Measure) -> Vec<&'static str> {
    if m.points().len() < 2 {
        return Vec::new();
    }
    let mut rows = vec!["Copy result", "Share as GPX"];
    if m.as_gate().is_some() {
        rows.push("Save as stop");
    }
    rows
}

/// The clipboard text of a measurement: the summary, then one line per leg.
pub fn measure_copy_text(m: &Measure) -> String {
    let mut out = m.summary_text();
    for (i, leg) in m.legs_m().into_iter().enumerate() {
        out.push_str(&format!("\n{}. {}", i + 1, distance_text(leg)));
    }
    out
}

/// The "My location" subtitle.
pub fn accuracy_text(accuracy_m: f64) -> String {
    format!("Accurate to {} m", accuracy_m.max(0.0).round() as u64)
}

impl StopsApp {
    /// Opens the My location sheet for the blue dot.
    pub(crate) fn open_my_location(&mut self, at: Coord, accuracy_m: f64, cx: &mut Context<Self>) {
        self.sheet_mode = SheetMode::MyLocation(at, accuracy_m);
        self.sheet.open();
        cx.notify();
    }

    /// Opens the measure tool's "More" sheet when there is something to act on.
    pub(crate) fn open_measure_actions(&mut self, cx: &mut Context<Self>) {
        let ready = self
            .map
            .measure
            .as_ref()
            .is_some_and(|m| !measure_actions(m).is_empty());
        if !ready {
            return;
        }
        self.sheet_mode = SheetMode::MeasureActions;
        self.sheet.open();
        cx.notify();
    }

    pub(crate) fn my_location_body(
        &mut self,
        theme: Theme,
        at: Coord,
        accuracy_m: f64,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let row =
            |title: &'static str, id: &'static str| list_row(theme, None, title, None, None).id(id);
        let rows = vec![
            row("Copy as", "myloc-copy")
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.open_copy_as("My location".to_owned(), at, cx);
                }))
                .into_any_element(),
            row("Share", "myloc-share")
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.sheet.close();
                    this.share_point("My location".to_owned(), at, "My location", cx);
                    cx.notify();
                }))
                .into_any_element(),
            row("QR code", "myloc-qr")
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.open_qr("My location", at, cx);
                }))
                .into_any_element(),
            row("Use as A", "myloc-a")
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.use_as(Slot::A, Destination::new("My location", at), cx);
                }))
                .into_any_element(),
            row("Use as B", "myloc-b")
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.use_as(Slot::B, Destination::new("My location", at), cx);
                }))
                .into_any_element(),
        ];
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, "My location"))
            .child(Self::footnote(theme, accuracy_text(accuracy_m)))
            .child(section(theme, None, rows))
            .into_any_element()
    }

    pub(crate) fn measure_actions_body(
        &mut self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let labels = self
            .map
            .measure
            .as_ref()
            .map(measure_actions)
            .unwrap_or_default();
        let rows = labels.into_iter().enumerate().map(|(i, label)| {
            list_row(theme, None, label, None, None)
                .id(("measure-action", i))
                .on_click(cx.listener(move |this, _, window, cx| match label {
                    "Copy result" => this.copy_measurement(window, cx),
                    "Share as GPX" => this.export_measurement(cx),
                    _ => this.save_measurement_as_stop(cx),
                }))
                .into_any_element()
        });
        let summary = self
            .map
            .measure
            .as_ref()
            .map(crate::map::measure_summary)
            .unwrap_or_default();
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, "Measurement"))
            .child(Self::footnote(theme, summary))
            .child(section(theme, None, rows))
            .into_any_element()
    }

    fn copy_measurement(&mut self, window: &Window, cx: &mut Context<Self>) {
        let Some(m) = self.map.measure.as_ref() else {
            return;
        };
        let text = measure_copy_text(m);
        cx.write_to_clipboard(gpui::ClipboardItem::new_string(text));
        haptics::perform(window, gpui::HapticFeedback::Selection);
        self.sheet.close();
        self.show_pill("Measurement copied", None, cx);
        cx.notify();
    }

    fn export_measurement(&mut self, cx: &mut Context<Self>) {
        let Some(m) = self.map.measure.as_ref() else {
            return;
        };
        let stamp = export_stamp(now_ms(), self.tz_offset_s);
        let name = Measure::export_file_name(&stamp);
        let gpx = m.to_gpx(&format!("Measurement {stamp}"));
        self.sheet.close();
        match backend::export_text(&name, "application/gpx+xml", &gpx) {
            Ok(()) => self.show_pill("Measurement exported", None, cx),
            Err(words) => self.show_pill(format!("Can't export: {words}"), None, cx),
        }
        cx.notify();
    }

    fn save_measurement_as_stop(&mut self, cx: &mut Context<Self>) {
        let Some((a, b)) = self.map.measure.as_ref().and_then(Measure::as_gate) else {
            return;
        };
        let a = pick::trip_endpoint(Slot::A, &Destination::dropped(a));
        let b = pick::trip_endpoint(Slot::B, &Destination::dropped(b));
        let name = rovsti_geo::saved_stops::suggest_name(self.gtfs.as_deref(), &a, &b);
        self.sheet.close();
        match self.saved.add(&name, a, b, now_ms()) {
            Ok(id) => {
                self.save_stops(cx);
                let name = name.trim().to_owned();
                self.show_pill(
                    format!("Saved as {name}"),
                    Some(crate::PillAction::UndoSave(id)),
                    cx,
                );
            }
            Err(rovsti_geo::SavedStopError::Duplicate(id)) => {
                let existing = self
                    .saved
                    .get(id)
                    .map_or_else(String::new, |s| s.name.clone());
                self.show_pill(format!("Already saved as {existing}"), None, cx);
            }
            Err(error) => self.show_pill(error.message(), None, cx),
        }
        cx.notify();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn measure(points: &[(f64, f64)]) -> Measure {
        let mut m = Measure::new();
        for &(lat, lon) in points {
            m.push(Coord::new(lat, lon));
        }
        m
    }

    #[test]
    fn measure_actions_need_two_points() {
        assert!(measure_actions(&measure(&[])).is_empty());
        assert!(measure_actions(&measure(&[(41.0, 29.0)])).is_empty());
    }

    #[test]
    fn save_as_stop_needs_a_valid_gate() {
        let far = measure(&[(41.0, 29.0), (41.01, 29.0)]);
        assert_eq!(
            measure_actions(&far),
            ["Copy result", "Share as GPX", "Save as stop"]
        );
        let same = measure(&[(41.0, 29.0), (41.0, 29.0)]);
        assert_eq!(measure_actions(&same), ["Copy result", "Share as GPX"]);
    }

    #[test]
    fn copy_text_has_the_summary_and_each_leg() {
        let m = measure(&[(41.0, 29.0), (41.01, 29.0), (41.02, 29.0)]);
        let text = measure_copy_text(&m);
        assert!(text.starts_with(&m.summary_text()));
        assert_eq!(text.lines().count(), 3);
        assert!(text.lines().nth(1).is_some_and(|l| l.starts_with("1. ")));
    }

    #[test]
    fn accuracy_is_rounded_metres() {
        assert_eq!(accuracy_text(14.6), "Accurate to 15 m");
        assert_eq!(accuracy_text(-1.0), "Accurate to 0 m");
    }
}
