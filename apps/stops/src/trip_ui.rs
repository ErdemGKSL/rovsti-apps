//! The Trip tab, the trip's life cycle in the UI (start, live status, stop),
//! and the alarm screen that covers the app while the alarm rings.
//!
//! The pieces that decide something (what the live card says, how a status
//! event is read, what a rule means in words) are plain functions with tests.
//! The controller methods only move that between the UI and `backend`.

use std::time::Duration;

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px,
};
use rovsti_android::trip::{AlarmAction, TripAlarmEvent};
use rovsti_geo::{ActiveTrip, AlarmRule, FixOut, PlaceError, RuleChoice, TripState};
use rovsti_ui::{
    components::{backdrop, empty_state, flat_glass, glass_pill, list_row, section},
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, radius, typography},
};
use serde_json::Value;

use crate::{
    StopsApp,
    backend::{self, Preflight},
    picker::Destination,
    store::now_ms,
};

// ---- pure ------------------------------------------------------------------------

/// The rule in words, for the line under the chips and the saved list.
pub fn rule_text(rule: AlarmRule) -> String {
    match rule {
        AlarmRule::Radius { metres } => {
            format!("Wakes you {} before the stop", metres_text(metres))
        }
        AlarmRule::Eta { before, .. } => format!(
            "Wakes you about {} min before arriving",
            before.as_secs().div_ceil(60)
        ),
        AlarmRule::BeforeStop { metres, .. } => {
            format!("Wakes you {} before the previous stop", metres_text(metres))
        }
    }
}

fn metres_text(metres: f64) -> String {
    if metres >= 1_000.0 {
        let km = metres / 1_000.0;
        if (km - km.round()).abs() < 0.05 {
            format!("{km:.0} km")
        } else {
            format!("{km:.1} km")
        }
    } else {
        format!("{metres:.0} m")
    }
}

/// "450 m", "1.2 km": metres to the nearest 10 below a kilometre.
pub fn distance_text(metres: f64) -> String {
    if metres < 1_000.0 {
        format!("{} m", (metres.max(0.0) / 10.0).round() as i64 * 10)
    } else {
        format!("{:.1} km", metres / 1_000.0)
    }
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
    pub distance_m: f64,
    pub eta_min: Option<u32>,
    pub next_fix_ms: Option<u64>,
    pub notice_sent: bool,
}

impl Live {
    pub fn from_out(out: &FixOut) -> Self {
        Self {
            state: out.state,
            distance_m: out.distance_m,
            eta_min: eta_from_text(&out.text),
            next_fix_ms: Some(out.next_fix_ms),
            notice_sent: out.notice_sent,
        }
    }

    /// The last thing the saved trip knows, for a trip restored at launch.
    pub fn from_trip(trip: &ActiveTrip) -> Option<Self> {
        Some(Self {
            state: trip.trip.state(),
            distance_m: trip.trip.last_distance_m()?,
            eta_min: None,
            next_fix_ms: None,
            notice_sent: trip.notice.as_ref().is_some_and(|n| n.sent),
        })
    }
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
    Error(&'static str),
}

pub fn parse_status(json: &str) -> Option<Status> {
    let value: Value = serde_json::from_str(json).ok()?;
    match value.get("state").and_then(Value::as_str)? {
        "ended" => Some(Status::Ended),
        "stopped" => Some(Status::Stopped),
        "error" => Some(Status::Error(error_message(
            value.get("error").and_then(Value::as_str).unwrap_or(""),
        ))),
        _ => serde_json::from_value(value).ok().map(Status::Fix),
    }
}

fn error_message(code: &str) -> &'static str {
    match code {
        "permission" => "Location was turned off for Stops. Allow it in Settings to keep tracking.",
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
}

// ---- views -----------------------------------------------------------------------

/// The "Wake me" chips. `prefix` keeps the element ids of two copies apart.
pub(crate) fn rule_chips(
    app: &StopsApp,
    theme: Theme,
    prefix: &'static str,
    cx: &mut Context<StopsApp>,
) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .flex()
        .gap(px(8.0))
        .px(px(16.0))
        .py(px(8.0))
        .children(RuleChoice::ALL.into_iter().enumerate().map(|(i, choice)| {
            let selected = choice == app.rule;
            let chip = if selected {
                glass(GlassSpec::lens(theme))
            } else {
                div()
            };
            chip.id((prefix, i))
                .flex_1()
                .h(px(40.0))
                .rounded_full()
                .flex()
                .items_center()
                .justify_center()
                .text_size(typography::SUBHEADLINE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(if selected {
                    colors.accent
                } else {
                    colors.secondary_label
                })
                .child(choice.label())
                .on_click(cx.listener(move |this, _, _, cx| this.set_rule(choice, cx)))
        }))
        .into_any_element()
}

fn text_block(theme: Theme, text: impl Into<SharedString>) -> AnyElement {
    div()
        .px(px(16.0))
        .pb(px(12.0))
        .text_size(typography::FOOTNOTE.size)
        .text_color(theme::colors(theme).secondary_label)
        .child(text.into())
        .into_any_element()
}

impl StopsApp {
    pub(crate) fn trip_tab(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let mut page = div().flex().flex_col().gap(px(24.0));
        if self.trip.is_some() {
            page = page.child(self.live_card(theme, cx));
        } else {
            page = page.child(self.setup_cards(theme, cx));
        }
        if self.preflight.full_screen_missing() {
            let row = list_row(
                theme,
                None,
                "Let the alarm wake the screen",
                Some("Allow full-screen alerts so it shows over the lock screen.".into()),
                Some("Open".into()),
            )
            .id("full-screen-row")
            .on_click(cx.listener(|this, _, _, cx| {
                cx.background_executor()
                    .spawn(async { backend::open_full_screen_settings() })
                    .detach();
                this.preflight.full_screen = None;
                cx.notify();
            }));
            page = page.child(section(theme, None, [row.into_any_element()]));
        }
        page.into_any_element()
    }

    /// Chips, destination and Start: what shows while no trip runs.
    fn setup_cards(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let rule = self
            .dest
            .as_ref()
            .and_then(|d| d.rule)
            .unwrap_or_else(|| self.rule.to_rule());
        let mut page = div().flex().flex_col().gap(px(24.0)).child(section(
            theme,
            Some("Wake me".into()),
            [
                rule_chips(self, theme, "rule", cx),
                text_block(theme, rule_text(rule)),
            ],
        ));
        let Some(dest) = self.dest.clone() else {
            return page
                .child(empty_state(
                    theme,
                    "Where are you going?",
                    "Pick a destination, or share a location to Rovsti Stops from any maps app.",
                ))
                .child(
                    div().flex().justify_center().child(
                        glass_pill(theme, "Choose destination")
                            .id("choose")
                            .on_click(cx.listener(|this, _, _, cx| this.open_picker(cx))),
                    ),
                )
                .into_any_element();
        };
        let row = list_row(
            theme,
            None,
            dest.label.clone(),
            Some(dest.coords_text().into()),
            Some("Change".into()),
        )
        .id("destination-row")
        .on_click(cx.listener(|this, _, _, cx| this.open_picker(cx)));
        let mut rows = vec![row.into_any_element()];
        if self.notice_available(cx) {
            let chat = self
                .prefs
                .notice
                .as_ref()
                .map(|n| n.chat_name.clone())
                .filter(|n| !n.is_empty())
                .unwrap_or_else(|| "your chat".to_owned());
            rows.push(
                list_row(
                    theme,
                    None,
                    format!("Text {chat} when arriving"),
                    None,
                    Some(if self.notice_on { "On" } else { "Off" }.into()),
                )
                .id("notice-toggle")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.notice_on = !this.notice_on;
                    cx.notify();
                }))
                .into_any_element(),
            );
        }
        page = page.child(section(theme, Some("Destination".into()), rows));
        let colors = theme::colors(theme);
        let label = if self.busy { "Starting..." } else { "Start" };
        page.child(
            div().flex().justify_center().child(
                glass_pill(theme, label)
                    .id("start")
                    .w(px(220.0))
                    .h(px(52.0))
                    .text_color(colors.accent)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.request_start(dest.clone(), false, cx);
                    })),
            ),
        )
        .into_any_element()
    }

    /// The running trip: where, how far, how long, and Stop.
    fn live_card(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let Some(trip) = &self.trip else {
            return div().into_any_element();
        };
        let label = trip.label.clone();
        let rule = rule_text(trip.trip.rule);
        let live = self.live.clone();
        let (big, small): (SharedString, SharedString) = match &live {
            Some(l) if l.state != TripState::Tracking => ("Arriving".into(), label.clone().into()),
            Some(l) => (
                distance_text(l.distance_m).into(),
                match l.eta_min {
                    Some(m) => format!("about {m} min to {label}").into(),
                    None => format!("to {label}").into(),
                },
            ),
            None => ("Locating".into(), format!("on the way to {label}").into()),
        };
        let mut details = Vec::new();
        if let Some(ms) = live.as_ref().and_then(|l| l.next_fix_ms) {
            details.push(format!("Next check in {}", wait_text(ms)));
        }
        if live.as_ref().is_some_and(|l| l.notice_sent) {
            details.push("Arrival message sent".to_owned());
        } else if trip.notice.is_some() {
            details.push("Will text when arriving".to_owned());
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
                    .child(
                        div()
                            .pt(px(8.0))
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.tertiary_label)
                            .text_center()
                            .child(rule),
                    )
                    .children(details.into_iter().map(|d| {
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.tertiary_label)
                            .child(d)
                    }))
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

    /// The arrival alarm, over everything, while the app is open.
    pub(crate) fn alarm_screen(
        &mut self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let alarm = self.alarm.clone()?;
        let colors = theme::colors(theme);
        let snooze = format!("Snooze {} min", self.prefs.snooze_min.clamp(1, 15));
        let distance = alarm.distance_m.map(distance_text);
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
                        .children(distance.map(|d| {
                            div()
                                .text_size(typography::TITLE.size)
                                .font_weight(typography::TITLE.weight)
                                .text_color(colors.label)
                                .child(d)
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
                                .child(
                                    glass_pill(theme, snooze)
                                        .id("alarm-snooze")
                                        .w_full()
                                        .h(px(64.0))
                                        .on_click(cx.listener(|this, _, _, cx| {
                                            this.alarm_action(AlarmAction::Snooze, cx);
                                        })),
                                ),
                        ),
                )
                .into_any_element(),
        )
    }

    // ---- controller --------------------------------------------------------------

    pub(crate) fn set_rule(&mut self, rule: RuleChoice, cx: &mut Context<Self>) {
        if self.rule == rule {
            return;
        }
        self.rule = rule;
        self.prefs.rule = rule;
        self.save_prefs(cx);
        cx.notify();
    }

    /// An arriving-soon message is set up and WhatsApp is linked.
    pub(crate) fn notice_available(&self, cx: &Context<Self>) -> bool {
        self.prefs
            .notice
            .as_ref()
            .is_some_and(|n| !n.chat.is_empty())
            && self.whatsapp.read(cx).is_connected()
    }

    /// Start: the permission checks first, then the trip file and the service.
    /// With `save`, the place is kept in Saved too.
    pub(crate) fn request_start(&mut self, dest: Destination, save: bool, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            self.show_pill("Still loading. Try again in a moment.", None, cx);
            return;
        };
        if self.busy {
            return;
        }
        if save {
            match self.saved.add(&dest.label, dest.coord) {
                Ok(_) => self.save_places(cx),
                Err(PlaceError::Duplicate(_)) => {}
                Err(error) => self.show_pill(error.message(), None, cx),
            }
        }
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
                        this.pending_start = Some(dest);
                        this.show_pill(step.message(), None, cx);
                    }
                    None => this.begin_trip(dest, dir, cx),
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
        let Some(dest) = self.pending_start.take() else {
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
                    this.request_start(dest, false, cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn begin_trip(&mut self, dest: Destination, dir: std::path::PathBuf, cx: &mut Context<Self>) {
        let rule = dest.rule.unwrap_or_else(|| self.rule.to_rule());
        let notice = if self.notice_on && self.notice_available(cx) {
            self.prefs.notice.clone()
        } else {
            None
        };
        let started = now_ms();
        let trip = backend::build_trip(
            started.max(1) as u64,
            &dest.label,
            dest.coord,
            rule,
            started,
            notice.as_ref(),
        );
        let session = trip.clone();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::start_trip(&dir, &trip) })
                .await;
            this.update(cx, |this, cx| {
                this.busy = false;
                match result {
                    Ok(()) => {
                        this.trip = Some(session);
                        this.live = None;
                        this.dest = None;
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
        self.busy = true;
        cx.notify();
        cx.spawn(async move |this, cx| {
            cx.background_executor()
                .spawn(async move { backend::stop_trip(&dir) })
                .await;
            this.update(cx, |this, cx| {
                this.busy = false;
                this.trip_over(cx);
                this.show_pill("Trip stopped.", None, cx);
            })
            .ok();
        })
        .detach();
    }

    /// The trip is gone: clear it and read the history again.
    pub(crate) fn trip_over(&mut self, cx: &mut Context<Self>) {
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
                        title: format!("Arriving at {}", trip.label),
                        distance_m: trip.trip.last_distance_m(),
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
        cx.spawn(async move |this, cx| {
            let running = cx
                .background_executor()
                .spawn(async move { backend::resume_trip(&dir) })
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
                self.live = Some(Live::from_out(&out));
                if out.ring {
                    self.show_alarm(&out.title, Some(out.distance_m), cx);
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
            Status::Error(why) => self.show_pill(why, None, cx),
        }
    }

    fn show_alarm(&mut self, title: &str, distance_m: Option<f64>, cx: &mut Context<Self>) {
        self.alarm = Some(AlarmView {
            title: title.to_owned(),
            distance_m,
        });
        self.sheet.close();
        cx.notify();
    }

    /// The Kotlin alarm rang, snoozed or was dismissed (maybe from its own
    /// screen, with this app in the background).
    pub(crate) fn on_alarm_event(&mut self, event: TripAlarmEvent, cx: &mut Context<Self>) {
        match event {
            TripAlarmEvent::Ringing => {
                let title = self.trip.as_ref().map_or_else(
                    || "Your stop".to_owned(),
                    |t| format!("Arriving at {}", t.label),
                );
                let distance = self.live.as_ref().map(|l| l.distance_m);
                self.show_alarm(&title, distance, cx);
            }
            TripAlarmEvent::Snoozed { at_ms } => self.on_snoozed(at_ms, cx),
            TripAlarmEvent::Dismissed => self.trip_over(cx),
        }
    }

    /// Hides the alarm until `at_ms`, then rings again if the trip is still on.
    fn on_snoozed(&mut self, at_ms: i64, cx: &mut Context<Self>) {
        self.alarm = None;
        let now = now_ms();
        self.show_pill(snooze_text(at_ms, now), None, cx);
        let wait = Duration::from_millis((at_ms - now).max(0) as u64);
        self.rering = Some(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(wait).await;
            this.update(cx, |this, cx| {
                if let Some(trip) = &this.trip {
                    let title = format!("Arriving at {}", trip.label);
                    let distance = this.live.as_ref().map(|l| l.distance_m);
                    this.show_alarm(&title, distance, cx);
                }
            })
            .ok();
        }));
        cx.notify();
    }

    /// Dismiss or Snooze on the alarm screen.
    pub(crate) fn alarm_action(&mut self, action: AlarmAction, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            return;
        };
        self.alarm = None;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let reply = cx
                .background_executor()
                .spawn(async move { backend::alarm(&dir, action) })
                .await;
            this.update(cx, |this, cx| match reply {
                backend::AlarmReply::Snoozed { at_ms } => this.on_snoozed(at_ms, cx),
                backend::AlarmReply::Dismissed | backend::AlarmReply::Ended => this.trip_over(cx),
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
    use rovsti_geo::{Coord, Trip};

    #[test]
    fn rules_read_in_words() {
        assert_eq!(
            rule_text(RuleChoice::Radius300.to_rule()),
            "Wakes you 300 m before the stop"
        );
        assert_eq!(
            rule_text(RuleChoice::Radius1k.to_rule()),
            "Wakes you 1 km before the stop"
        );
        assert_eq!(
            rule_text(RuleChoice::Eta2min.to_rule()),
            "Wakes you about 2 min before arriving"
        );
        assert_eq!(
            rule_text(AlarmRule::Radius { metres: 1_500.0 }),
            "Wakes you 1.5 km before the stop"
        );
        assert_eq!(
            rule_text(AlarmRule::BeforeStop {
                trigger: Coord::new(1.0, 2.0),
                metres: 150.0
            }),
            "Wakes you 150 m before the previous stop"
        );
    }

    #[test]
    fn distances_and_waits_round_for_reading() {
        assert_eq!(distance_text(0.0), "0 m");
        assert_eq!(distance_text(487.0), "490 m");
        assert_eq!(distance_text(999.0), "1000 m");
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
        assert_eq!(parse_status(r#"{"state":"Tracking"}"#), None);
        assert_eq!(parse_status("nope"), None);
        assert_eq!(parse_status("{}"), None);
    }

    #[test]
    fn a_restored_trip_shows_its_last_distance() {
        let mut trip = ActiveTrip::new(
            1,
            "Kadikoy",
            Trip::new(Coord::new(41.0, 29.0), AlarmRule::Radius { metres: 500.0 }),
            0,
        );
        assert_eq!(Live::from_trip(&trip), None);
        trip.on_fix(
            rovsti_geo::Fix {
                at: Coord::new(41.01, 29.0),
                accuracy_m: 10.0,
                speed_mps: None,
            },
            0,
        );
        let live = Live::from_trip(&trip).unwrap();
        assert!(
            (live.distance_m - 1_112.0).abs() < 20.0,
            "{}",
            live.distance_m
        );
        assert_eq!(live.state, TripState::Tracking);
        assert!(!live.notice_sent);
    }

    #[test]
    fn a_snooze_says_how_long() {
        assert_eq!(snooze_text(300_000, 0), "Snoozed. Ringing again in 5 min.");
        assert_eq!(snooze_text(300_001, 0), "Snoozed. Ringing again in 6 min.");
        assert_eq!(snooze_text(0, 5), "Snoozed. Ringing again in 1 min.");
    }
}
