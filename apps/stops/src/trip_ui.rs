//! The Trip tab, the trip's life cycle in the UI (start, live status, stop),
//! and the alarm screen that covers the app while the alarm rings.
//!
//! The pieces that decide something (what the live card says, how a status
//! event is read, how a side reads) are plain functions with tests.
//! The controller methods only move that between the UI and `backend`.

use std::time::Duration;

use gpui::{
    AnyElement, Context, Hsla, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px,
};
use rovsti_android::trip::{AlarmAction, TripAlarmEvent};
use rovsti_geo::{
    ActiveTrip, Coord, Diameter, FixOut, NoticeState, Side, TripState, tiles::tiles_for_circle,
};
use rovsti_ui::{
    components::{backdrop, flat_glass, glass_pill, list_row, section},
    theme,
    tokens::{Theme, radius, typography},
};
use serde_json::Value;

use crate::{
    StopsApp,
    backend::{self, Preflight},
    map::side_color,
    pick,
    picker::{Destination, Slot},
    store::now_ms,
};

// ---- pure ------------------------------------------------------------------------

/// "450 m", "1.2 km": metres to the nearest 10 below a kilometre.
pub fn distance_text(metres: f64) -> String {
    // Rounded first, so 995 m reads "1.0 km", never "1000 m".
    let tens = (metres.max(0.0) / 10.0).round() as i64 * 10;
    if tens < 1_000 {
        format!("{tens} m")
    } else {
        format!("{:.1} km", metres / 1_000.0)
    }
}

/// An arrow glyph for the compass octant of a bearing (north is up).
pub fn octant_arrow(deg: f64) -> &'static str {
    const ARROWS: [&str; 8] = [
        "\u{2191}", "\u{2197}", "\u{2192}", "\u{2198}", "\u{2193}", "\u{2199}", "\u{2190}",
        "\u{2196}",
    ];
    let d = if deg.is_finite() {
        deg.rem_euclid(360.0)
    } else {
        0.0
    };
    ARROWS[(((d + 22.5) / 45.0) as usize) % 8]
}

/// "NE, 1.2 km": the compass point and distance from `here` to `target`.
pub fn bearing_text(here: Coord, target: Coord) -> String {
    format!(
        "{}, {}",
        rovsti_geo::coord_format::cardinal(here.bearing_deg(target)),
        distance_text(here.distance_m(target))
    )
}

/// The line under the A and B rows: what the circle is and what wakes you.
pub fn trigger_text(diameter: &Diameter) -> String {
    format!(
        "Circle {} across. Crossing the line A – B either way wakes you.",
        distance_text(diameter.length_m())
    )
}

/// "12 s", "4 min": how long until the next location check.
pub fn wait_text(ms: u64) -> String {
    let s = ms.div_ceil(1_000);
    if s < 90 {
        format!("{s} s")
    } else {
        format!("{} min", s.div_ceil(60))
    }
}

/// What the live card shows.
#[derive(Clone, Debug, PartialEq)]
pub struct Live {
    pub state: TripState,
    /// Metres to the line A-B.
    pub line_m: f64,
    pub side: Option<Side>,
    pub inside: bool,
    /// Metres to the circle; `None` when not known (a restored trip, an old answer).
    pub circle_m: Option<f64>,
    pub eta_min: Option<u32>,
    pub next_fix_ms: Option<u64>,
    pub notice_sent: bool,
    pub notice_failed: bool,
}

/// The pill after an alarm was dismissed: its text and whether it offers
/// Re-arm (only when the trip's ends were known).
pub fn dismiss_pill(ends: Option<&(Destination, Destination)>) -> (&'static str, bool) {
    ("Alarm dismissed", ends.is_some())
}

/// The trip's two ends as destinations, for Re-arm.
fn trip_ends(trip: Option<&ActiveTrip>) -> Option<(Destination, Destination)> {
    trip.map(|t| {
        (
            pick::dest_from_endpoint(&t.a),
            pick::dest_from_endpoint(&t.b),
        )
    })
}

/// The pill text for the live card's status, from the pure helper.
fn status_text(trip: &ActiveTrip, live: Option<&Live>, here: Option<Coord>) -> String {
    let crossed = live.is_some_and(|l| l.state != TripState::Tracking);
    rovsti_geo::status_share_text(
        &trip.label(),
        live.map(|l| l.line_m),
        live.and_then(|l| l.eta_min),
        here,
        trip.trip.diameter.center(),
        crossed,
    )
}

/// The arrival-message line, if any. Sent wins over failed, failed over planned.
pub fn notice_line(sent: bool, failed: bool, planned: bool) -> Option<&'static str> {
    if sent {
        Some("Arrival message sent")
    } else if failed {
        Some("Arrival message not sent. Text them yourself.")
    } else if planned {
        Some("Will text when arriving")
    } else {
        None
    }
}

/// True once, when the failed flag flips from false to true.
pub fn pill_on_failure(was_failed: bool, now_failed: bool) -> bool {
    !was_failed && now_failed
}

/// The snooze button label; `None` when no snoozes are left.
pub fn snooze_label(minutes: u8, left: u8) -> Option<String> {
    (left > 0).then(|| format!("Snooze {} min", minutes.clamp(1, 15)))
}

impl Live {
    pub fn from_out(out: &FixOut) -> Self {
        Self {
            state: out.state,
            line_m: out.distance_m,
            side: out.side,
            inside: out.inside,
            circle_m: out.circle_m,
            eta_min: eta_from_text(&out.text),
            next_fix_ms: Some(out.next_fix_ms),
            notice_sent: out.notice_sent,
            notice_failed: out.notice_failed,
        }
    }

    /// The last thing the saved trip knows, for a trip restored at launch.
    pub fn from_trip(trip: &ActiveTrip) -> Option<Self> {
        Some(Self {
            state: trip.trip.state(),
            line_m: trip.trip.last_line_m()?,
            side: trip.trip.side(),
            inside: false,
            circle_m: None,
            eta_min: None,
            next_fix_ms: None,
            notice_sent: trip.notice.as_ref().is_some_and(|n| n.sent),
            notice_failed: trip.notice.as_ref().is_some_and(NoticeState::gave_up),
        })
    }
}

/// "Left side", "Right side" or "On the line": which half you are on.
pub fn side_text(side: Option<Side>) -> String {
    match side {
        Some(side) => format!("{} side", side.label()),
        None => "On the line".to_owned(),
    }
}

/// "Inside the circle" or "1.2 km to the circle"; nothing when it is not known.
pub fn circle_text(live: &Live) -> Option<String> {
    if live.inside {
        return Some("Inside the circle".to_owned());
    }
    live.circle_m
        .map(|m| format!("{} to the circle", distance_text(m)))
}

/// "About 4 min to the line", when there is an estimate.
pub fn eta_text(eta_min: Option<u32>) -> Option<String> {
    eta_min.map(|m| format!("About {m} min to the line"))
}

/// What the alarm screen says under the title: the half you ended up on.
pub fn crossed_to_text(side: Option<Side>) -> Option<String> {
    side.map(|s| format!("Now on the {} side", s.label().to_lowercase()))
}

/// The alarm's title: "Crossed A – B".
pub fn alarm_title(label: &str) -> String {
    format!("Crossed {label}")
}

/// The `about N min` that `FixOut::text` ends with, if any.
pub fn eta_from_text(text: &str) -> Option<u32> {
    let rest = text.split(", about ").nth(1)?;
    rest.strip_suffix(" min")?.trim().parse().ok()
}

/// A `trip_status` event from the service or from the simulation.
#[derive(Clone, Debug, PartialEq)]
pub enum Status {
    Fix(FixOut),
    /// The trip is over (the headless handler found no trip).
    Ended,
    /// The service was stopped from its notification.
    Stopped,
    /// The service has no fix yet and is looking for one.
    Searching,
    Error(&'static str),
}

pub fn parse_status(json: &str) -> Option<Status> {
    let value: Value = serde_json::from_str(json).ok()?;
    match value.get("state").and_then(Value::as_str)? {
        "ended" => Some(Status::Ended),
        "stopped" => Some(Status::Stopped),
        "searching" => Some(Status::Searching),
        "error" => Some(Status::Error(error_message(
            value.get("error").and_then(Value::as_str).unwrap_or(""),
        ))),
        _ => serde_json::from_value(value).ok().map(Status::Fix),
    }
}

fn error_message(code: &str) -> &'static str {
    match code {
        "permission" => "Location was turned off for Stops. Allow it in Settings to keep tracking.",
        "full_screen" => {
            "The alarm cannot cover the lock screen. Allow full-screen alerts in Settings."
        }
        "restart" => {
            "Android closed Stops and tracking paused. It resumes when Stops is open; allow location all the time to avoid this."
        }
        "foreground" => "Android would not keep the tracking service running.",
        _ => "Tracking hit a problem.",
    }
}

/// "Ringing again in 5 min" for a snooze that ends at `at_ms`.
pub fn snooze_text(at_ms: i64, now_ms: i64) -> String {
    let min = ((at_ms - now_ms).max(0) as u64).div_ceil(60_000).max(1);
    format!("Snoozed. Ringing again in {min} min.")
}

/// The alarm screen's content.
#[derive(Clone, Debug, PartialEq)]
pub struct AlarmView {
    pub title: String,
    pub distance_m: Option<f64>,
    /// The half the phone crossed to.
    pub side: Option<Side>,
}

/// Title of the last-trip card.
pub fn last_trip_title(record: &rovsti_geo::TripRecord) -> String {
    match record.outcome {
        rovsti_geo::Outcome::Arrived => format!("Arrived at {}", record.title()),
        rovsti_geo::Outcome::Cancelled => format!("Trip stopped: {}", record.title()),
    }
}

/// The card shows unless Close hid this very record.
pub fn show_last_trip(id: u64, hidden: Option<u64>) -> bool {
    hidden != Some(id)
}

// ---- views -----------------------------------------------------------------------

impl StopsApp {
    pub(crate) fn trip_tab(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let mut page = div().flex().flex_col().gap(px(24.0));
        if self.trip.is_some() {
            page = page.child(self.live_card(theme, cx));
            if let Some(map) = self.map_card(theme, cx) {
                page = page.child(map);
            }
        } else {
            // Idle: the map is the picker, the pick bar sits right under it.
            if let Some(map) = self.map_card(theme, cx) {
                page = page.child(map);
            }
            page = page.child(self.setup_cards(theme, cx));
            if let Some(card) = self.last_trip_card(theme, cx) {
                page = page.child(card);
            }
        }
        if let Some(hint) = backend::full_screen_notice(&self.preflight) {
            let row = list_row(
                theme,
                None,
                "Let the alarm wake the screen",
                Some(hint.into()),
                Some("Open".into()),
            )
            .id("full-screen-row")
            .on_click(cx.listener(|_, _, _, cx| {
                cx.background_executor()
                    .spawn(async { backend::open_full_screen_settings() })
                    .detach();
                // The window-activation refresh re-reads the grant on return.
                cx.notify();
            }));
            page = page.child(section(theme, None, [row.into_any_element()]));
        }
        if let Some(hint) = backend::background_location_notice(&self.preflight) {
            let row = list_row(
                theme,
                None,
                "Keep tracking in the background",
                Some(hint.into()),
                Some("Allow".into()),
            )
            .id("background-location-row")
            .on_click(cx.listener(|this, _, _, cx| {
                let pre = this.preflight;
                cx.background_executor()
                    .spawn(async move { backend::request_background_location(&pre) })
                    .detach();
                // The window-activation refresh re-reads the grant on return.
                cx.notify();
            }));
            page = page.child(section(theme, None, [row.into_any_element()]));
        }
        page.into_any_element()
    }

    /// After a trip ends: what happened, with Start again / Save as stop /
    /// Share / Close.
    fn last_trip_card(&mut self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let record = self.history.last_finished(now_ms())?;
        if !show_last_trip(record.id, self.last_trip_hidden) {
            return None;
        }
        let (id, title, summary) = (record.id, last_trip_title(record), record.summary_line());
        let pair = record.pair();
        let share = rovsti_geo::history_io::share_text(record, self.tz_offset_s);
        let colors = theme::colors(theme);
        let row = list_row(theme, None, title, Some(summary.into()), None).into_any_element();
        let mut pills = div()
            .px(px(16.0))
            .pb(px(12.0))
            .flex()
            .flex_wrap()
            .gap(px(12.0));
        if let Some((a, b)) = pair {
            pills = pills
                .child(
                    glass_pill(theme, "Start again")
                        .id("last-trip-again")
                        .text_color(colors.accent)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.start_again(&a, &b, cx);
                        })),
                )
                .child(
                    glass_pill(theme, "Save as stop")
                        .id("last-trip-save")
                        .text_color(colors.accent)
                        .on_click(cx.listener(|this, _, _, cx| this.save_record_as_stop(0, cx))),
                );
        }
        pills = pills
            .child(
                glass_pill(theme, "Share")
                    .id("last-trip-share")
                    .text_color(colors.accent)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.share_out("Trip".into(), share.clone(), cx);
                    })),
            )
            .child(
                glass_pill(theme, "Close")
                    .id("last-trip-close")
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.last_trip_hidden = Some(id);
                        cx.notify();
                    })),
            );
        Some(
            div()
                .flex()
                .flex_col()
                .child(section(theme, None, [row]))
                .child(pills)
                .into_any_element(),
        )
    }

    /// Under the map while no trip runs: the pick bar, the other ways to pick,
    /// the A and B rows and the notice toggle.
    fn setup_cards(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let ready = self.pick.can_start();
        let any_end = !self.pick.ends.is_empty();
        let hint = self.pick.hint();
        let busy = self.busy;
        let label = if busy { "Starting..." } else { "Start" };
        let pair = self.pick.ends.a.clone().zip(self.pick.ends.b.clone());
        let start = glass_pill(theme, label)
            .id("start")
            .flex_1()
            .h(px(52.0))
            .text_color(if ready {
                colors.accent
            } else {
                colors.tertiary_label
            });
        let start = match pair {
            Some((a, b)) if ready => start.on_click(cx.listener(move |this, _, _, cx| {
                this.request_start(a.clone(), b.clone(), cx);
            })),
            _ => start,
        };
        let mut buttons = div().flex().items_center().gap(px(8.0));
        if any_end {
            buttons = buttons.child(
                glass_pill(theme, "Clear")
                    .id("clear-ends")
                    .h(px(52.0))
                    .on_click(cx.listener(|this, _, _, cx| {
                        let ends = this.pick.ends.clone();
                        this.pick.clear();
                        this.map.follow = true;
                        this.show_pill(
                            "Points cleared",
                            Some(crate::PillAction::RestoreEnds(ends)),
                            cx,
                        );
                    })),
            );
        }
        if any_end {
            buttons = buttons.child(
                glass_pill(theme, "Swap")
                    .id("swap-ends")
                    .h(px(52.0))
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.pick.swap();
                        cx.notify();
                    })),
            );
        }
        let offer = pick::save_offer(&self.pick.ends, &self.saved);
        if offer == pick::SaveOffer::Save {
            buttons = buttons.child(
                glass_pill(theme, "Save stop")
                    .id("save-stop")
                    .h(px(52.0))
                    .on_click(cx.listener(|this, _, _, cx| this.open_save_stop(cx))),
            );
        }
        buttons = buttons.child(start);
        let mut bar = div()
            .px(px(16.0))
            .flex()
            .flex_col()
            .gap(px(10.0))
            .child(
                div()
                    .text_size(typography::HEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(match hint {
                        pick::Hint::Invalid(_) => colors.red,
                        _ => colors.label,
                    })
                    .child(hint.text()),
            )
            .child(buttons);
        if let Some(Ok(d)) = self.pick.ends.diameter() {
            bar = bar.child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(trigger_text(&d)),
            );
        }

        if let pick::SaveOffer::Saved { name, .. } = offer {
            bar = bar.child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(format!("Saved as {name}")),
            );
        }

        let mut rows: Vec<AnyElement> = Vec::new();
        for slot in [Slot::A, Slot::B] {
            let name = pick::point_label(slot, self.pick.ends.get(slot));
            rows.push(
                list_row(theme, None, format!("{}  {name}", slot.label()), None, None)
                    .id(("end-row", slot as usize))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.pick.set_sheet_slot(slot);
                        this.open_picker(cx);
                    }))
                    .into_any_element(),
            );
        }
        let sources = list_row(
            theme,
            None,
            "Saved stops, recent trips, links",
            None,
            Some("Open".into()),
        )
        .id("pick-sources")
        .on_click(cx.listener(|this, _, _, cx| this.open_picker(cx)))
        .into_any_element();

        let mut page = div().flex().flex_col().gap(px(16.0)).child(bar);
        page = page.child(section(theme, None, [sources]));
        page = page.child(section(theme, None, rows));
        if self.notice_available(cx) {
            let chat = self
                .prefs
                .notice
                .as_ref()
                .map(|n| n.chat_name.clone())
                .filter(|n| !n.is_empty())
                .unwrap_or_else(|| "your chat".to_owned());
            page = page.child(section(
                theme,
                None,
                [list_row(
                    theme,
                    None,
                    format!("Text {chat} when arriving"),
                    None,
                    Some(if self.notice_on { "On" } else { "Off" }.into()),
                )
                .id("notice-toggle")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.notice_on = !this.notice_on;
                    this.save_next_trip(cx);
                    cx.notify();
                }))
                .into_any_element()],
            ));
        }
        page.into_any_element()
    }

    /// The running trip: where, how far, how long, and Stop.
    fn live_card(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let Some(trip) = &self.trip else {
            return div().into_any_element();
        };
        let label = trip.label();
        let rule = format!("Crossing the line {label} wakes you, either way.");
        let live = self.live.clone();
        let (big, small): (SharedString, SharedString) = match &live {
            Some(l) if l.state != TripState::Tracking => ("Crossed".into(), label.clone().into()),
            Some(l) => (distance_text(l.line_m).into(), "to the line".into()),
            None => ("Locating".into(), format!("near the line {label}").into()),
        };
        let tracking = live.as_ref().filter(|l| l.state == TripState::Tracking);
        let chip = tracking.map(|l| (side_text(l.side), side_color(theme, l.side)));
        let mut details = Vec::new();
        if let Some(l) = tracking {
            details.extend(circle_text(l));
            details.extend(eta_text(l.eta_min));
        }
        if let Some((here, _)) = self.map.position {
            let target = trip.trip.diameter.center();
            let deg = here.bearing_deg(target);
            details.push(format!(
                "{} {}",
                octant_arrow(deg),
                bearing_text(here, target)
            ));
        }
        if let Some(ms) = live.as_ref().and_then(|l| l.next_fix_ms) {
            details.push(format!("Next check in {}", wait_text(ms)));
        }
        let failed = live
            .as_ref()
            .is_some_and(|l| l.notice_failed && !l.notice_sent);
        let (sent, gave_up) = live
            .as_ref()
            .map_or((false, false), |l| (l.notice_sent, l.notice_failed));
        if let Some(line) = notice_line(sent, gave_up, trip.notice.is_some()) {
            details.push(line.to_owned());
        }
        let busy = self.busy;
        div()
            .px(px(16.0))
            .child(
                flat_glass(theme, None)
                    .rounded(radius::CARD)
                    .p(px(20.0))
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap(px(6.0))
                    .child(
                        div()
                            .text_size(typography::LARGE_TITLE.size)
                            .font_weight(typography::LARGE_TITLE.weight)
                            .text_color(colors.label)
                            .child(big),
                    )
                    .child(
                        div()
                            .text_size(typography::HEADLINE.size)
                            .text_color(colors.secondary_label)
                            .child(small),
                    )
                    .children(chip.map(|(text, color)| {
                        div()
                            .id("side-chip")
                            .px(px(12.0))
                            .py(px(4.0))
                            .rounded(radius::CARD)
                            .bg(Hsla { a: 0.18, ..color })
                            .text_size(typography::HEADLINE.size)
                            .text_color(color)
                            .child(text)
                    }))
                    .child(
                        div()
                            .pt(px(8.0))
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.tertiary_label)
                            .text_center()
                            .child(rule),
                    )
                    .children(details.into_iter().map(|d| {
                        let color = if failed && d.starts_with("Arrival message not sent") {
                            colors.red
                        } else {
                            colors.tertiary_label
                        };
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(color)
                            .child(d)
                    }))
                    .child(
                        div()
                            .flex()
                            .gap(px(8.0))
                            .pt(px(8.0))
                            .child(
                                glass_pill(theme, "Copy status")
                                    .id("copy-status")
                                    .on_click(cx.listener(|this, _, _, cx| this.copy_status(cx))),
                            )
                            .child(
                                glass_pill(theme, "Share status")
                                    .id("share-status")
                                    .on_click(cx.listener(|this, _, _, cx| this.share_status(cx))),
                            ),
                    )
                    .child(
                        glass_pill(theme, if busy { "Stopping..." } else { "Stop" })
                            .id("stop")
                            .mt(px(12.0))
                            .w(px(180.0))
                            .text_color(colors.red)
                            .on_click(cx.listener(|this, _, _, cx| this.stop_trip(cx))),
                    ),
            )
            .into_any_element()
    }

    fn status_now(&self) -> Option<String> {
        let trip = self.trip.as_ref()?;
        let here = self.map.position.map(|(c, _)| c);
        Some(status_text(trip, self.live.as_ref(), here))
    }

    /// Copy status: the trip's status text to the clipboard.
    fn copy_status(&mut self, cx: &mut Context<Self>) {
        let Some(text) = self.status_now() else {
            return;
        };
        cx.write_to_clipboard(gpui::ClipboardItem::new_string(text));
        self.show_pill("Status copied", None, cx);
    }

    /// Share status: the system share sheet; elsewhere a pill says so.
    fn share_status(&mut self, cx: &mut Context<Self>) {
        let Some(text) = self.status_now() else {
            return;
        };
        self.run_android(
            "Share status",
            move || rovsti_android::telecom::share_text(&text),
            cx,
        );
    }

    /// The arrival alarm, over everything, while the app is open.
    pub(crate) fn alarm_screen(
        &mut self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let alarm = self.alarm.clone()?;
        let colors = theme::colors(theme);
        let left = self.trip.as_ref().map_or(1, ActiveTrip::snoozes_left);
        let snooze = snooze_label(self.prefs.snooze_min, left);
        let distance = alarm.distance_m.map(distance_text);
        let crossed_to = crossed_to_text(alarm.side);
        let notice_failed = self
            .live
            .as_ref()
            .is_some_and(|l| l.notice_failed && !l.notice_sent);
        Some(
            div()
                .id("alarm-screen")
                .absolute()
                .inset_0()
                .occlude()
                .child(backdrop(theme, colors.red))
                .child(
                    div()
                        .absolute()
                        .inset_0()
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap(px(12.0))
                        .px(px(24.0))
                        .child(
                            div()
                                .text_size(typography::TITLE2.size)
                                .text_color(colors.secondary_label)
                                .child("Wake up"),
                        )
                        .child(
                            div()
                                .text_size(typography::LARGE_TITLE.size)
                                .font_weight(typography::LARGE_TITLE.weight)
                                .text_color(colors.label)
                                .text_center()
                                .child(alarm.title),
                        )
                        .children(crossed_to.map(|t| {
                            div()
                                .text_size(typography::TITLE2.size)
                                .text_color(colors.secondary_label)
                                .child(t)
                        }))
                        .children(distance.map(|d| {
                            div()
                                .text_size(typography::TITLE.size)
                                .font_weight(typography::TITLE.weight)
                                .text_color(colors.label)
                                .child(d)
                        }))
                        .children(notice_failed.then(|| {
                            div()
                                .text_size(typography::TITLE2.size)
                                .text_color(colors.red)
                                .text_center()
                                .child("Arrival message not sent")
                        }))
                        .child(
                            div()
                                .pt(px(32.0))
                                .w_full()
                                .flex()
                                .flex_col()
                                .gap(px(14.0))
                                .child(
                                    glass_pill(theme, "Dismiss")
                                        .id("alarm-dismiss")
                                        .w_full()
                                        .h(px(64.0))
                                        .text_color(colors.accent)
                                        .on_click(cx.listener(|this, _, _, cx| {
                                            this.alarm_action(AlarmAction::Dismiss, cx);
                                        })),
                                )
                                .children(match snooze {
                                    Some(label) => Some(
                                        glass_pill(theme, label)
                                            .id("alarm-snooze")
                                            .w_full()
                                            .h(px(64.0))
                                            .on_click(cx.listener(|this, _, _, cx| {
                                                this.alarm_action(AlarmAction::Snooze, cx);
                                            }))
                                            .into_any_element(),
                                    ),
                                    None => Some(
                                        div()
                                            .w_full()
                                            .text_center()
                                            .text_size(typography::FOOTNOTE.size)
                                            .text_color(colors.secondary_label)
                                            .child("No snoozes left")
                                            .into_any_element(),
                                    ),
                                }),
                        ),
                )
                .into_any_element(),
        )
    }

    // ---- controller --------------------------------------------------------------

    /// An arriving-soon message is set up and WhatsApp is linked.
    pub(crate) fn notice_available(&self, cx: &Context<Self>) -> bool {
        self.prefs
            .notice
            .as_ref()
            .is_some_and(|n| !n.chat.is_empty())
            && self.whatsapp.read(cx).is_connected()
    }

    /// Start: the permission checks first, then the trip file and the service.
    pub(crate) fn request_start(&mut self, a: Destination, b: Destination, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            self.show_pill("Still loading. Try again in a moment.", None, cx);
            return;
        };
        if self.busy {
            return;
        }
        self.pick.close_menu();
        self.busy = true;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let executor = cx.background_executor().clone();
            let pre = executor.spawn(async { Preflight::gather() }).await;
            let step = pre.next_step();
            if let Some(step) = step {
                executor.spawn(async move { step.perform() }).await;
            }
            this.update(cx, |this, cx| {
                this.preflight = pre;
                match step {
                    Some(step) => {
                        this.busy = false;
                        this.pending_start = Some((a, b));
                        this.show_pill(step.message(), None, cx);
                    }
                    None => this.begin_trip(a, b, dir, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Android came back from a permission prompt: carry on with the start
    /// that was waiting for it, if everything is granted now.
    pub(crate) fn resume_pending_start(&mut self, cx: &mut Context<Self>) {
        let Some((a, b)) = self.pending_start.take() else {
            return;
        };
        cx.spawn(async move |this, cx| {
            let pre = cx
                .background_executor()
                .spawn(async { Preflight::gather() })
                .await;
            this.update(cx, |this, cx| {
                this.preflight = pre;
                if pre.next_step().is_none() {
                    this.request_start(a, b, cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn begin_trip(
        &mut self,
        a: Destination,
        b: Destination,
        dir: std::path::PathBuf,
        cx: &mut Context<Self>,
    ) {
        self.map.follow_mode = self.map.follow_mode.on_pan();
        self.sync_follow(true, cx);
        let notice = if self.notice_on && self.notice_available(cx) {
            self.prefs.notice.clone()
        } else {
            None
        };
        let started = now_ms();
        let trip = match backend::build_trip(
            started.max(1) as u64,
            pick::trip_endpoint(Slot::A, &a),
            pick::trip_endpoint(Slot::B, &b),
            started,
            notice.as_ref(),
        ) {
            Ok(trip) => trip,
            Err(error) => {
                self.busy = false;
                self.show_pill(error.message(), None, cx);
                cx.notify();
                return;
            }
        };
        let session = trip.clone();
        let plan = self.prefs.alarm_plan();
        let line = b.pattern.or(a.pattern);
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::start_trip(&dir, &trip, &plan) })
                .await;
            this.update(cx, |this, cx| {
                this.busy = false;
                match result {
                    Ok(()) => {
                        let d = session.trip.diameter;
                        this.map_tiles.prefetch(
                            tiles_for_circle(d.center(), d.radius_m(), 500.0, 8, 600),
                            cx,
                        );
                        this.trip = Some(session);
                        this.live = None;
                        this.map.pattern = line;
                        this.map.follow = true;
                        this.pick.clear();
                        this.alarm = None;
                        this.tabs.jump_to(0);
                    }
                    Err(why) => this.show_pill(why, None, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Stop: the service stops, the trip is recorded as cancelled.
    pub(crate) fn stop_trip(&mut self, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            return;
        };
        if self.busy {
            return;
        }
        let ends = self.trip.as_ref().map(|t| {
            (
                pick::dest_from_endpoint(&t.a),
                pick::dest_from_endpoint(&t.b),
            )
        });
        self.busy = true;
        cx.notify();
        cx.spawn(async move |this, cx| {
            cx.background_executor()
                .spawn(async move { backend::stop_trip(&dir) })
                .await;
            this.update(cx, |this, cx| {
                this.busy = false;
                this.trip_over(cx);
                match ends {
                    Some((a, b)) => {
                        this.pick.set_pair(a.clone(), b.clone());
                        this.map.follow = true;
                        this.show_pill(
                            "Trip stopped",
                            Some(crate::PillAction::RestartTrip(a, b)),
                            cx,
                        );
                    }
                    None => this.show_pill("Trip stopped", None, cx),
                }
            })
            .ok();
        })
        .detach();
    }

    /// The trip is gone: clear it and read the history again.
    pub(crate) fn trip_over(&mut self, cx: &mut Context<Self>) {
        self.map.reset();
        self.trip = None;
        self.live = None;
        self.alarm = None;
        self.rering = None;
        self.refresh_trip(cx);
        cx.notify();
    }

    /// Reads `trip.json` and the history, which the service changes while the
    /// app is away, and shows the alarm again if it is still ringing.
    pub(crate) fn refresh_trip(&mut self, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            return;
        };
        cx.spawn(async move |this, cx| {
            let (trip, history) = cx
                .background_executor()
                .spawn(async move { backend::reload_trip(&dir) })
                .await;
            this.update(cx, |this, cx| {
                this.history = history;
                this.set_saved_trip(trip, cx);
                if this.trip.is_some() {
                    this.resume_service(cx);
                }
            })
            .ok();
        })
        .detach();
    }

    /// Takes the trip read from disk as the current one.
    pub(crate) fn set_saved_trip(&mut self, trip: Option<ActiveTrip>, cx: &mut Context<Self>) {
        match trip {
            None => {
                self.trip = None;
                self.live = None;
                self.alarm = None;
            }
            Some(trip) => {
                let ringing = trip.trip.state() == TripState::Arrived && trip.ring_at_ms.is_none();
                if self.live.is_none() {
                    self.live = Live::from_trip(&trip);
                }
                if ringing && self.alarm.is_none() {
                    self.alarm = Some(AlarmView {
                        title: alarm_title(&trip.label()),
                        distance_m: trip.trip.last_line_m(),
                        side: trip.trip.side(),
                    });
                }
                self.trip = Some(trip);
            }
        }
        cx.notify();
    }

    /// A trip left behind by a killed app: start the service again if needed.
    pub(crate) fn resume_service(&mut self, cx: &mut Context<Self>) {
        let (Some(dir), Some(_)) = (self.dir.clone(), &self.trip) else {
            return;
        };
        let plan = self.prefs.alarm_plan();
        cx.spawn(async move |this, cx| {
            let running = cx
                .background_executor()
                .spawn(async move { backend::resume_trip(&dir, &plan) })
                .await;
            if !running {
                this.update(cx, |this, cx| {
                    this.show_pill(
                        "Could not resume tracking. Stop the trip and start again.",
                        None,
                        cx,
                    );
                })
                .ok();
            }
        })
        .detach();
    }

    /// A `trip_status` payload, from Kotlin or from the simulation.
    pub(crate) fn on_status_json(&mut self, json: &str, cx: &mut Context<Self>) {
        let Some(status) = parse_status(json) else {
            log::warn!("unreadable trip status");
            return;
        };
        match status {
            Status::Fix(out) => {
                if self.loaded && self.trip.is_none() {
                    return;
                }
                let was_failed = self.live.as_ref().is_some_and(|l| l.notice_failed);
                self.live = Some(Live::from_out(&out));
                if pill_on_failure(was_failed, out.notice_failed) {
                    self.show_pill("Arrival message not sent. Text them yourself.", None, cx);
                }
                if let Some(at) = out.at {
                    self.map_fix(at);
                }
                if out.ring {
                    self.show_alarm(&out.title, Some(out.distance_m), out.side, cx);
                }
                cx.notify();
            }
            Status::Ended => self.trip_over(cx),
            Status::Stopped => {
                let Some(dir) = self.dir.clone() else {
                    return;
                };
                cx.spawn(async move |this, cx| {
                    cx.background_executor()
                        .spawn(async move { backend::stop_trip(&dir) })
                        .await;
                    this.update(cx, |this, cx| this.trip_over(cx)).ok();
                })
                .detach();
            }
            Status::Searching => {
                // Only before the first fix: later the card already says where
                // you are, and a repeat would push away an Undo or a snooze pill.
                if self.trip.is_some() && self.live.is_none() {
                    self.show_pill("Looking for your location...", None, cx);
                }
            }
            Status::Error(why) => {
                // A refused full-screen intent shows up as the settings row on the Trip tab.
                self.refresh_preflight(cx);
                self.show_pill(why, None, cx);
            }
        }
    }

    fn show_alarm(
        &mut self,
        title: &str,
        distance_m: Option<f64>,
        side: Option<Side>,
        cx: &mut Context<Self>,
    ) {
        self.alarm = Some(AlarmView {
            title: title.to_owned(),
            distance_m,
            side,
        });
        self.sheet.close();
        cx.notify();
    }

    /// The Kotlin alarm rang, snoozed or was dismissed (maybe from its own
    /// screen, with this app in the background).
    pub(crate) fn on_alarm_event(&mut self, event: TripAlarmEvent, cx: &mut Context<Self>) {
        match event {
            TripAlarmEvent::Ringing => {
                let title = self
                    .trip
                    .as_ref()
                    .map_or_else(|| "Your stop".to_owned(), |t| alarm_title(&t.label()));
                let distance = self.live.as_ref().map(|l| l.line_m);
                let side = self.live.as_ref().and_then(|l| l.side);
                self.show_alarm(&title, distance, side, cx);
            }
            TripAlarmEvent::Snoozed { at_ms } | TripAlarmEvent::TimedOut { at_ms } => {
                self.on_snoozed(at_ms, cx);
            }
            TripAlarmEvent::Dismissed => {
                let ends = trip_ends(self.trip.as_ref());
                self.trip_over(cx);
                self.dismissed(ends, cx);
            }
        }
    }

    /// Hides the alarm until `at_ms`, then rings again if the trip is still on.
    fn on_snoozed(&mut self, at_ms: i64, cx: &mut Context<Self>) {
        self.alarm = None;
        if let Some(dir) = self.dir.clone() {
            // Only the snooze count changes; the rest of the trip stays as is.
            cx.spawn(async move |this, cx| {
                let (trip, _) = cx
                    .background_executor()
                    .spawn(async move { backend::reload_trip(&dir) })
                    .await;
                this.update(cx, |this, cx| {
                    if let (Some(t), Some(new)) = (this.trip.as_mut(), trip) {
                        t.snoozes = new.snoozes;
                        cx.notify();
                    }
                })
                .ok();
            })
            .detach();
        }
        let now = now_ms();
        self.show_pill(snooze_text(at_ms, now), None, cx);
        let wait = Duration::from_millis((at_ms - now).max(0) as u64);
        self.rering = Some(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(wait).await;
            this.update(cx, |this, cx| {
                if let Some(trip) = &this.trip {
                    let title = alarm_title(&trip.label());
                    let distance = this.live.as_ref().map(|l| l.line_m);
                    let side = this.live.as_ref().and_then(|l| l.side);
                    this.show_alarm(&title, distance, side, cx);
                }
            })
            .ok();
        }));
        cx.notify();
    }

    /// The pill after Dismiss, with Re-arm when the ends are known.
    fn dismissed(&mut self, ends: Option<(Destination, Destination)>, cx: &mut Context<Self>) {
        let (text, offer) = dismiss_pill(ends.as_ref());
        let action = ends
            .filter(|_| offer)
            .map(|(a, b)| crate::PillAction::Rearm(a, b));
        self.show_pill(text, action, cx);
    }

    /// Dismiss or Snooze on the alarm screen.
    pub(crate) fn alarm_action(&mut self, action: AlarmAction, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            return;
        };
        self.alarm = None;
        let ends = trip_ends(self.trip.as_ref());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let reply = cx
                .background_executor()
                .spawn(async move { backend::alarm(&dir, action) })
                .await;
            this.update(cx, |this, cx| match reply {
                backend::AlarmReply::Snoozed { at_ms } => this.on_snoozed(at_ms, cx),
                backend::AlarmReply::Dismissed => {
                    this.trip_over(cx);
                    this.dismissed(ends, cx);
                }
                backend::AlarmReply::Ended => this.trip_over(cx),
                // Kotlin answers with a `trip_alarm` event.
                backend::AlarmReply::Sent => {}
                backend::AlarmReply::Failed => {
                    this.show_pill("Could not reach the alarm. Try again.", None, cx);
                    this.refresh_trip(cx);
                }
            })
            .ok();
        })
        .detach();
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use rovsti_geo::Coord;

    #[test]
    fn last_trip_title_by_outcome() {
        let mut r = rovsti_geo::TripRecord {
            label: "Home".into(),
            ..Default::default()
        };
        assert_eq!(last_trip_title(&r), format!("Arrived at {}", r.title()));
        r.outcome = rovsti_geo::Outcome::Cancelled;
        assert_eq!(last_trip_title(&r), format!("Trip stopped: {}", r.title()));
    }

    #[test]
    fn close_hides_only_that_record() {
        assert!(show_last_trip(3, None));
        assert!(!show_last_trip(3, Some(3)));
        assert!(show_last_trip(4, Some(3)));
    }

    fn live(side: Option<Side>, inside: bool, circle_m: Option<f64>) -> Live {
        Live {
            state: TripState::Tracking,
            line_m: 120.0,
            side,
            inside,
            circle_m,
            eta_min: None,
            next_fix_ms: None,
            notice_sent: false,
            notice_failed: false,
        }
    }

    #[test]
    fn dismiss_pill_offers_rearm_only_with_ends() {
        assert_eq!(dismiss_pill(None), ("Alarm dismissed", false));
        let ends = (
            Destination::new("A", Coord::new(41.0, 29.0)),
            Destination::new("B", Coord::new(41.1, 29.1)),
        );
        assert_eq!(dismiss_pill(Some(&ends)), ("Alarm dismissed", true));
    }

    #[test]
    fn pill_shows_only_on_the_flip() {
        assert!(pill_on_failure(false, true));
        assert!(!pill_on_failure(true, true));
        assert!(!pill_on_failure(false, false));
        assert!(!pill_on_failure(true, false));
    }

    #[test]
    fn notice_line_picks_the_first_that_applies() {
        assert_eq!(notice_line(true, true, true), Some("Arrival message sent"));
        assert_eq!(
            notice_line(false, true, true),
            Some("Arrival message not sent. Text them yourself.")
        );
        assert_eq!(
            notice_line(false, false, true),
            Some("Will text when arriving")
        );
        assert_eq!(notice_line(false, false, false), None);
    }

    #[test]
    fn snooze_label_hides_at_zero_and_clamps() {
        assert_eq!(snooze_label(5, 0), None);
        assert_eq!(snooze_label(5, 1).as_deref(), Some("Snooze 5 min"));
        assert_eq!(snooze_label(0, 2).as_deref(), Some("Snooze 1 min"));
        assert_eq!(snooze_label(99, 2).as_deref(), Some("Snooze 15 min"));
    }

    #[test]
    fn the_side_reads_left_right_or_on_the_line() {
        assert_eq!(side_text(Some(Side::Left)), "Left side");
        assert_eq!(side_text(Some(Side::Right)), "Right side");
        assert_eq!(side_text(None), "On the line");
        assert_eq!(
            crossed_to_text(Some(Side::Right)).as_deref(),
            Some("Now on the right side")
        );
        assert_eq!(crossed_to_text(None), None);
    }

    #[test]
    fn the_distance_texts_read_for_the_card() {
        assert_eq!(distance_text(120.0), "120 m");
        assert_eq!(
            eta_text(Some(3)).as_deref(),
            Some("About 3 min to the line")
        );
        assert_eq!(eta_text(None), None);
    }

    #[test]
    fn the_circle_text_is_inside_or_a_distance() {
        assert_eq!(
            circle_text(&live(Some(Side::Left), true, Some(0.0))).as_deref(),
            Some("Inside the circle")
        );
        assert_eq!(
            circle_text(&live(None, false, Some(1_240.0))).as_deref(),
            Some("1.2 km to the circle")
        );
        assert_eq!(circle_text(&live(None, false, None)), None);
    }

    #[test]
    fn the_alarm_title_names_the_pair() {
        assert_eq!(alarm_title("Home – Work"), "Crossed Home – Work");
    }

    #[test]
    fn the_trigger_text_states_the_circle() {
        let d = Diameter::new(Coord::new(41.0, 29.0), Coord::new(41.0, 29.0142)).unwrap();
        assert_eq!(
            trigger_text(&d),
            "Circle 1.2 km across. Crossing the line A – B either way wakes you."
        );
        let short = Diameter::new(Coord::new(41.0, 29.0), Coord::new(41.0, 29.001)).unwrap();
        assert!(trigger_text(&short).starts_with("Circle 80 m across."));
    }

    #[test]
    fn distances_and_waits_round_for_reading() {
        assert_eq!(distance_text(0.0), "0 m");
        assert_eq!(distance_text(487.0), "490 m");
        assert_eq!(distance_text(994.0), "990 m");
        assert_eq!(distance_text(995.0), "1.0 km");
        assert_eq!(distance_text(999.0), "1.0 km");
        assert_eq!(distance_text(f64::NAN), "0 m");
        assert_eq!(distance_text(1_240.0), "1.2 km");
        assert_eq!(distance_text(-5.0), "0 m");
        assert_eq!(wait_text(3_000), "3 s");
        assert_eq!(wait_text(2_100), "3 s");
        assert_eq!(wait_text(89_000), "89 s");
        assert_eq!(wait_text(90_000), "2 min");
        assert_eq!(wait_text(300_000), "5 min");
    }

    #[test]
    fn the_eta_is_read_from_the_service_text() {
        assert_eq!(eta_from_text("1.2 km to Kadikoy, about 4 min"), Some(4));
        assert_eq!(eta_from_text("1.2 km to Kadikoy"), None);
        assert_eq!(eta_from_text("300 m to A, about soon min"), None);
        assert_eq!(eta_from_text(""), None);
    }

    fn out_json(state: &str, ring: bool) -> String {
        format!(
            r#"{{"state":"{state}","distance_m":420.0,"next_fix_ms":15000,"high_accuracy":true,
            "ring":{ring},"title":"Kadikoy","text":"420 m to Kadikoy, about 3 min","notice_sent":true}}"#
        )
    }

    #[test]
    fn status_events_are_told_apart() {
        let Some(Status::Fix(out)) = parse_status(&out_json("Tracking", false)) else {
            panic!("expected a fix");
        };
        let live = Live::from_out(&out);
        assert_eq!(live.eta_min, Some(3));
        assert_eq!(live.next_fix_ms, Some(15_000));
        assert!(live.notice_sent);
        assert_eq!(live.state, TripState::Tracking);

        let Some(Status::Fix(out)) = parse_status(&out_json("Arrived", true)) else {
            panic!("expected a fix");
        };
        assert!(out.ring);
        assert_eq!(parse_status(r#"{"state":"ended"}"#), Some(Status::Ended));
        assert_eq!(
            parse_status(r#"{"state":"stopped"}"#),
            Some(Status::Stopped)
        );
        assert!(matches!(
            parse_status(r#"{"state":"error","error":"permission"}"#),
            Some(Status::Error(m)) if m.contains("Location")
        ));
        assert!(matches!(
            parse_status(r#"{"state":"error"}"#),
            Some(Status::Error(_))
        ));
        assert_eq!(
            parse_status(r#"{"state":"searching"}"#),
            Some(Status::Searching)
        );
        let Some(Status::Error(restart)) = parse_status(r#"{"state":"error","error":"restart"}"#)
        else {
            panic!("expected an error");
        };
        assert!(restart.contains("allow location all the time"));
        assert_ne!(restart, error_message(""));
        assert_eq!(parse_status(r#"{"state":"Tracking"}"#), None);
        assert_eq!(parse_status("nope"), None);
        assert_eq!(parse_status("{}"), None);
    }

    #[test]
    fn a_restored_trip_shows_its_last_distance_to_the_line() {
        let end = |label: &str, lon: f64| rovsti_geo::Endpoint {
            label: label.into(),
            coord: Coord::new(41.0, lon),
        };
        // About 1 km apart, west to east: north of it is the left side.
        let mut trip = ActiveTrip::new(1, end("A", 28.994), end("B", 29.006), 0).unwrap();
        assert_eq!(Live::from_trip(&trip), None);
        trip.on_fix(
            rovsti_geo::Fix {
                at: Coord::new(41.002, 29.0),
                accuracy_m: 10.0,
                speed_mps: None,
            },
            0,
        );
        let live = Live::from_trip(&trip).unwrap();
        assert!((live.line_m - 222.0).abs() < 5.0, "{}", live.line_m);
        assert_eq!(live.side, Some(Side::Left));
        assert_eq!(live.state, TripState::Tracking);
        assert!(!live.notice_sent);
    }

    #[test]
    fn a_status_with_a_side_is_read() {
        let json = r#"{"state":"Tracking","distance_m":80.0,"next_fix_ms":5000,
            "high_accuracy":true,"ring":false,"title":"A – B",
            "text":"Right side, 80 m to the line A – B","notice_sent":false,
            "side":"right","inside":true}"#;
        let Some(Status::Fix(out)) = parse_status(json) else {
            panic!("expected a fix");
        };
        let live = Live::from_out(&out);
        assert_eq!((live.side, live.inside), (Some(Side::Right), true));
        assert_eq!(live.circle_m, None);
        // An older answer without the fields.
        let Some(Status::Fix(old)) = parse_status(&out_json("Tracking", false)) else {
            panic!("expected a fix");
        };
        let live = Live::from_out(&old);
        assert_eq!((live.side, live.inside, live.circle_m), (None, false, None));
        assert_eq!(live.line_m, 420.0);
    }

    #[test]
    fn a_status_with_a_circle_distance_is_read() {
        let json = r#"{"state":"Tracking","distance_m":900.0,"next_fix_ms":5000,
            "high_accuracy":false,"ring":false,"title":"A – B","text":"Outside","notice_sent":false,
            "side":"left","inside":false,"circle_m":640.0}"#;
        let Some(Status::Fix(out)) = parse_status(json) else {
            panic!("expected a fix");
        };
        let live = Live::from_out(&out);
        assert_eq!(live.circle_m, Some(640.0));
        assert_eq!(circle_text(&live).as_deref(), Some("640 m to the circle"));
    }

    #[test]
    fn arrows_follow_the_octants() {
        assert_eq!(octant_arrow(0.0), "\u{2191}");
        assert_eq!(octant_arrow(44.0), "\u{2197}");
        assert_eq!(octant_arrow(90.0), "\u{2192}");
        assert_eq!(octant_arrow(180.0), "\u{2193}");
        assert_eq!(octant_arrow(270.0), "\u{2190}");
        assert_eq!(octant_arrow(359.0), "\u{2191}");
        assert_eq!(octant_arrow(-45.0), "\u{2196}");
        assert_eq!(octant_arrow(f64::NAN), "\u{2191}");
    }

    #[test]
    fn bearing_text_names_direction_and_distance() {
        let here = Coord::new(41.0, 29.0);
        let ne = Coord::new(41.008, 29.0106);
        let text = bearing_text(here, ne);
        assert!(text.starts_with("NE, "), "{text}");
        assert!(text.ends_with(" km"), "{text}");
        let north = Coord::new(41.003, 29.0);
        assert_eq!(bearing_text(here, north), "N, 330 m");
    }

    #[test]
    fn a_snooze_says_how_long() {
        assert_eq!(snooze_text(300_000, 0), "Snoozed. Ringing again in 5 min.");
        assert_eq!(snooze_text(300_001, 0), "Snoozed. Ringing again in 6 min.");
        assert_eq!(snooze_text(0, 5), "Snoozed. Ringing again in 1 min.");
    }
}
