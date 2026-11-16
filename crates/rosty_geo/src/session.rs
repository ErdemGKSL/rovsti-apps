//! A trip in progress: the alarm state machine plus travelled distance, the
//! optional "arriving soon" notice and snoozing. Pure data, serialisable so
//! the platform layer can persist it across process death.

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::alarm::MAX_TIMEOUT_RERINGS;
use crate::cadence::{
    self, Cadence, CadenceInput, LastPlan, SpeedBasis, SpeedEstimate, SpeedHistory, SpeedSample,
    V_MAX_MPS,
};
use crate::crossing::valid_coord;
use crate::notice;
use crate::poll::retry_after;
use crate::trip::{MAX_ACCURACY_M, MIN_SPEED_MPS};
use crate::{
    Coord, CrossingTrip, CrossingUpdate, Diameter, DiameterError, Endpoint, Fix, Lang, NoticeRule,
    Side, TripState,
};

/// Notice delivery attempts before giving up.
pub const MAX_NOTICE_ATTEMPTS: u8 = 3;
/// Snoozes allowed before the caller must dismiss.
pub const MAX_SNOOZES: u8 = 5;
const MAX_SNOOZE_MINUTES: u8 = 15;
/// "High accuracy" location requests are worth the battery below this.
const HIGH_ACCURACY_BELOW: Duration = Duration::from_secs(15);

/// Distance actually travelled, from accurate fixes only.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Track {
    pub last: Option<Coord>,
    pub travelled_m: f64,
    pub fixes: u32,
    /// Accuracy of `last`, so GPS jitter is not counted as movement.
    #[serde(default)]
    last_accuracy_m: f64,
}

impl Track {
    fn add(&mut self, fix: &Fix) {
        if fix.accuracy_m > MAX_ACCURACY_M {
            return;
        }
        self.fixes += 1;
        let Some(last) = self.last else {
            self.last = Some(fix.at);
            self.last_accuracy_m = fix.accuracy_m;
            return;
        };
        let moved = last.distance_m(fix.at);
        // Within the combined uncertainty the phone may as well be standing
        // still. The anchor stays put so slow real movement still adds up.
        if moved >= self.last_accuracy_m + fix.accuracy_m {
            self.travelled_m += moved;
            self.last = Some(fix.at);
            self.last_accuracy_m = fix.accuracy_m;
        }
    }
}

/// The "arriving soon" message to a chat.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct NoticeState {
    pub chat: String,
    pub text_template: String,
    /// When to send; may be earlier than the trip's own alarm.
    pub rule: NoticeRule,
    pub sent: bool,
    pub attempts: u8,
}

impl NoticeState {
    /// True once delivery failed for good (not sent, attempts exhausted).
    pub fn gave_up(&self) -> bool {
        !self.sent && self.attempts >= MAX_NOTICE_ATTEMPTS
    }
}

/// What the platform layer reports after trying to send the notice.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NoticeDelivery {
    Sent,
    /// Try again on a later fix (counts as an attempt).
    RetryLater,
    /// Permanent failure (no such chat, not linked): stop trying.
    GiveUp,
}

#[derive(Clone, Debug, PartialEq)]
pub struct FixOutcome {
    pub update: CrossingUpdate,
    /// The fix that produced `update`.
    pub fix: Fix,
    /// Rendered notice text to send now.
    pub send_notice: Option<String>,
    /// When to ask for the next fix (AD-SA10); `None` once the trip stopped tracking.
    pub cadence: Option<Cadence>,
    /// The measured speed (`v_est`), when there is one: the provider's or one from the history.
    pub speed_mps: Option<f64>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ActiveTrip {
    pub id: u64,
    pub a: Endpoint,
    pub b: Endpoint,
    pub trip: CrossingTrip,
    pub started_ms: i64,
    pub snoozes: u8,
    /// When a snoozed alarm rings again.
    pub ring_at_ms: Option<i64>,
    pub track: Track,
    pub notice: Option<NoticeState>,
    /// Ring timeouts so far (each re-rings; capped at [`MAX_TIMEOUT_RERINGS`]).
    #[serde(default)]
    pub timeouts: u8,
    /// The language of the texts this trip writes on its own (the notification, the arriving-soon
    /// message's "a few"): the phone's when the trip started, or of the last fix that named one.
    #[serde(default, skip_serializing_if = "Lang::is_default")]
    pub lang: Lang,
    /// The usable fixes of the last 5 minutes and the last plan, for the cadence (absent in older files).
    #[serde(default, skip_serializing_if = "SpeedHistory::is_empty")]
    pub speed: SpeedHistory,
}

/// A location fix as delivered by the Android side.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct FixIn {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f64,
    pub speed_mps: Option<f64>,
    pub at_ms: i64,
    /// The monotonic clock of the fix (`Location.getElapsedRealtimeNanos` in ms); absent from older senders.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub elapsed_ms: Option<i64>,
}

impl FixIn {
    /// The time the speed history uses: the monotonic clock when given, else the wall clock.
    pub fn clock_ms(&self) -> i64 {
        self.elapsed_ms.unwrap_or(self.at_ms)
    }

    pub fn fix(&self) -> Fix {
        Fix {
            at: Coord::new(self.lat, self.lon),
            accuracy_m: self.accuracy_m,
            speed_mps: self.speed_mps,
        }
    }
}

/// What the Android side needs after a fix.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct FixOut {
    pub state: TripState,
    /// Metres from the fix to the line A-B.
    pub distance_m: f64,
    pub next_fix_ms: u64,
    /// Wait this long for a fix before assuming it is missing and re-requesting.
    #[serde(default)]
    pub retry_ms: u64,
    pub high_accuracy: bool,
    pub ring: bool,
    pub title: String,
    pub text: String,
    pub notice_sent: bool,
    /// The notice could not be delivered and will not be retried.
    #[serde(default)]
    pub notice_failed: bool,
    /// The side of the line the phone is on, when known.
    #[serde(default)]
    pub side: Option<Side>,
    /// Inside the circle.
    #[serde(default)]
    pub inside: bool,
    /// Whole minutes to the line at the current speed (at least one), when the speed is known: the number in
    /// "about 14 min" of `text`, for the arrival clock (`arrival_clock`).
    #[serde(default)]
    pub eta_min: Option<u32>,
    /// Metres from the fix to the circle (0 inside). Absent in older answers.
    #[serde(default)]
    pub circle_m: Option<f64>,
    /// Where the phone was, for the map. Absent in answers from older builds.
    #[serde(default)]
    pub at: Option<FixPos>,
    /// The measured speed (`v_est`), m/s, when there is one.
    #[serde(default)]
    pub speed_mps: Option<f64>,
    /// The speed the cadence assumed for `next_fix_ms`, m/s; null when not tracking.
    #[serde(default)]
    pub assumed_mps: Option<f64>,
    /// Which term decided that speed; null when not tracking.
    #[serde(default)]
    pub cadence: Option<SpeedBasis>,
    /// The fix came after the previous answer's `retry_ms` (or no fix came: `trip_no_fix`).
    #[serde(default)]
    pub late: bool,
}

/// A position with its uncertainty, as shown on the map.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct FixPos {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f64,
}

impl ActiveTrip {
    /// Fails when A and B cannot form a diameter.
    pub fn new(id: u64, a: Endpoint, b: Endpoint, started_ms: i64) -> Result<Self, DiameterError> {
        let diameter = Diameter::new(a.coord, b.coord)?;
        Ok(Self {
            id,
            a,
            b,
            trip: CrossingTrip::new(diameter),
            started_ms,
            snoozes: 0,
            ring_at_ms: None,
            track: Track::default(),
            notice: None,
            timeouts: 0,
            lang: Lang::default(),
            speed: SpeedHistory::default(),
        })
    }

    /// "A – B" in the trip's language. An end with no name of its own (a dropped pin, a measured
    /// point) reads "Point A" / "B noktası", so the label is never a bare dash.
    pub fn label(&self) -> String {
        self.label_in(self.lang)
    }

    /// [`Self::label`] in `lang`.
    pub fn label_in(&self, lang: Lang) -> String {
        let name = |end: &Endpoint, letter: &str| {
            let label = end.label.trim();
            if crate::history::is_generic_label(label) {
                crate::history::point_name(lang, letter)
            } else {
                label.to_owned()
            }
        };
        format!("{} – {}", name(&self.a, "A"), name(&self.b, "B"))
    }

    /// Feeds one fix taken at `t_ms` (the clock of [`FixIn::clock_ms`]): the crossing, the travelled
    /// distance, the speed history, the arriving-soon message and the next interval.
    pub fn on_fix(&mut self, fix: Fix, t_ms: i64) -> FixOutcome {
        let mut update = self.trip.update(fix);
        self.track.add(&fix);

        let usable = fix.accuracy_m <= MAX_ACCURACY_M;
        let late = self.is_late(t_ms);
        let estimate = (usable && valid_coord(fix.at)).then(|| {
            self.speed.push(SpeedSample {
                t_ms,
                at: fix.at,
                accuracy_m: fix.accuracy_m,
            });
            self.speed.estimate(fix.speed_mps)
        });
        let speed_mps = estimate
            .filter(|e| e.basis != SpeedBasis::Cold || fix.speed_mps.is_some())
            .map(|e| e.est_mps);
        // Without a provider speed the ETA and the arriving-soon rule use the measured one.
        let measured = match fix.speed_mps {
            None => speed_mps.filter(|v| *v >= MIN_SPEED_MPS),
            Some(_) => None,
        };
        if let Some(v) = measured
            && update.eta.is_none()
        {
            update.eta = Some(Duration::from_secs_f64(update.line_m / v));
        }
        let rule_fix = Fix {
            speed_mps: fix.speed_mps.or(measured),
            ..fix
        };

        let send_notice = match &self.notice {
            Some(n)
                if usable
                    && !n.sent
                    && n.attempts < MAX_NOTICE_ATTEMPTS
                    && self.trip.state() != TripState::Dismissed
                    && n.rule.is_met(&update, &rule_fix) =>
            {
                Some(notice::render_in(
                    self.lang,
                    &n.text_template,
                    &self.b.label,
                    update.eta,
                ))
            }
            _ => None,
        };

        let cadence = (self.trip.state() == TripState::Tracking).then(|| {
            let mut cadence = match estimate {
                Some(e) => self.plan_after(&update, fix.accuracy_m, e, late),
                None => self.dead_reckon(t_ms),
            };
            cadence.late = late;
            self.remember(t_ms, &cadence, estimate.is_some());
            cadence
        });
        FixOutcome {
            update,
            fix,
            send_notice,
            cadence,
            speed_mps,
        }
    }

    /// No fix came (both providers answered nothing, AD-SA10): the interval dead-reckons from the last usable
    /// fix, so the retries get denser and switch to high accuracy as the worst case nears the line. The
    /// state does not change and nothing rings. `None` when the trip is not tracking.
    pub fn no_fix(&mut self, now_ms: i64) -> Option<Cadence> {
        if self.trip.state() != TripState::Tracking {
            return None;
        }
        let mut cadence = self.dead_reckon(now_ms);
        cadence.late = true;
        self.remember(now_ms, &cadence, false);
        Some(cadence)
    }

    /// The status after [`Self::no_fix`]: the last usable position's distances, no position, no ETA; the
    /// text is empty when no fix was ever usable.
    pub fn no_fix_out(&self, cadence: Option<Cadence>) -> FixOut {
        let d = &self.trip.diameter;
        let known = self
            .speed
            .newest()
            .map(|s| {
                (
                    d.distance_to_segment_m(s.at),
                    d.distance_to_circle_m(s.at),
                    d.contains(s.at),
                )
            })
            .or_else(|| self.trip.last_line_m().map(|l| (l, l, false)));
        let (line_m, circle_m, inside) = known.unwrap_or((0.0, 0.0, false));
        let label = self.label();
        let side = self.trip.side();
        // Without any usable fix there is nothing to say yet.
        let text = known.map_or_else(String::new, |_| {
            status_text(self.lang, &label, inside, side, line_m, circle_m, None)
        });
        let next = cadence.map_or(crate::poll::IDLE_POLL, |c| c.next_fix_in);
        FixOut {
            state: self.trip.state(),
            distance_m: line_m,
            next_fix_ms: millis(next),
            retry_ms: millis(retry_after(next)),
            high_accuracy: cadence.is_some_and(|c| c.high_accuracy),
            ring: false,
            title: if self.trip.state() == TripState::Arrived {
                crossed_title(self.lang, &label)
            } else {
                label.clone()
            },
            text,
            notice_sent: self.notice.as_ref().is_some_and(|n| n.sent),
            notice_failed: self.notice.as_ref().is_some_and(NoticeState::gave_up),
            side,
            inside,
            eta_min: None,
            circle_m: Some(circle_m),
            at: None,
            speed_mps: None,
            assumed_mps: cadence.map(|c| c.assumed_mps),
            cadence: cadence.map(|c| c.basis),
            late: cadence.is_some_and(|c| c.late),
        }
    }

    /// The fix came after the previous plan's watchdog window.
    fn is_late(&self, t_ms: i64) -> bool {
        self.speed.last_plan().is_some_and(|p| {
            let window = millis(retry_after(Duration::from_millis(p.next_ms)));
            t_ms.saturating_sub(p.t_ms) > i64::try_from(window).unwrap_or(i64::MAX)
        })
    }

    /// [`cadence::plan`] after a usable fix, with the arriving-soon target when a message is pending.
    fn plan_after(
        &self,
        update: &CrossingUpdate,
        accuracy_m: f64,
        e: SpeedEstimate,
        late: bool,
    ) -> Cadence {
        let last = self.speed.last_plan();
        let notice_m = match &self.notice {
            Some(n) if !n.sent && n.attempts < MAX_NOTICE_ATTEMPTS && !update.inside => {
                match n.rule {
                    NoticeRule::BeforeCircle { before } => {
                        Some(update.circle_m - e.est_mps * before.as_secs_f64())
                    }
                    NoticeRule::WithAlarm => None,
                }
            }
            _ => None,
        };
        cadence::plan(&CadenceInput {
            line_m: Some(update.line_m),
            accuracy_m: Some(accuracy_m),
            notice_m,
            assumed_mps: e.assumed_mps,
            est_mps: e.est_mps,
            basis: e.basis,
            // A cold or late step is no reason to wait for a longer interval.
            prev: last
                .filter(|p| !late && p.basis != SpeedBasis::Cold)
                .map(|p| Duration::from_millis(p.next_ms)),
            prev_high: last.is_some_and(|p| p.high && p.basis != SpeedBasis::Cold),
        })
    }

    /// The plan without a usable fix: the last usable one, moved closer by the farthest the user can have
    /// gone since, at the top speed. Without any, today's curve on the last distance (300 s if none).
    fn dead_reckon(&self, now_ms: i64) -> Cadence {
        let prev_high = self
            .speed
            .last_plan()
            .is_some_and(|p| p.high && p.basis != SpeedBasis::Cold);
        let (line_m, accuracy_m) = match self.speed.newest() {
            Some(last) => {
                let line = self.trip.diameter.distance_to_segment_m(last.at);
                let elapsed_s = now_ms.saturating_sub(last.t_ms).max(0) as f64 / 1000.0;
                let v = self.speed.last_plan().map_or(V_MAX_MPS, |p| p.assumed_mps);
                (
                    Some(line - cadence::reach_m(elapsed_s, v)),
                    Some(last.accuracy_m),
                )
            }
            None => (self.trip.last_line_m(), None),
        };
        cadence::plan(&CadenceInput {
            line_m,
            accuracy_m,
            notice_m: None,
            assumed_mps: V_MAX_MPS,
            est_mps: 0.0,
            basis: SpeedBasis::Cold,
            prev: None,
            prev_high,
        })
    }

    /// Records the plan; dead reckoning keeps the speed the last usable fix assumed.
    fn remember(&mut self, t_ms: i64, cadence: &Cadence, from_fix: bool) {
        let assumed_mps = if from_fix {
            cadence.assumed_mps
        } else {
            self.speed.last_plan().map_or(V_MAX_MPS, |p| p.assumed_mps)
        };
        self.speed.record(LastPlan {
            t_ms,
            next_ms: millis(cadence.next_fix_in),
            high: cadence.high_accuracy,
            assumed_mps,
            basis: cadence.basis,
        });
    }

    pub fn notice_result(&mut self, delivered: NoticeDelivery) {
        let Some(n) = self.notice.as_mut() else {
            return;
        };
        match delivered {
            NoticeDelivery::Sent => n.sent = true,
            NoticeDelivery::RetryLater => n.attempts = n.attempts.saturating_add(1),
            NoticeDelivery::GiveUp => n.attempts = MAX_NOTICE_ATTEMPTS,
        }
    }

    /// Snoozes still available.
    pub fn snoozes_left(&self) -> u8 {
        MAX_SNOOZES.saturating_sub(self.snoozes)
    }

    /// Silences the alarm for `minutes` (clamped to 1..=15) and returns when it
    /// should ring again, or `None` once snoozed [`MAX_SNOOZES`] times.
    pub fn snooze(&mut self, now_ms: i64, minutes: u8) -> Option<i64> {
        if self.snoozes >= MAX_SNOOZES {
            return None;
        }
        self.snoozes += 1;
        let minutes = minutes.clamp(1, MAX_SNOOZE_MINUTES);
        let at = now_ms.saturating_add(i64::from(minutes) * 60_000);
        self.ring_at_ms = Some(at);
        Some(at)
    }

    /// The alarm rang for the whole ring timeout unanswered: ring again in
    /// `minutes` (clamped to 1..=15). Does not use up a snooze. `None` when not
    /// ringing or after [`MAX_TIMEOUT_RERINGS`] timeouts.
    pub fn ring_timed_out(&mut self, now_ms: i64, minutes: u8) -> Option<i64> {
        if self.trip.state() != TripState::Arrived || self.timeouts >= MAX_TIMEOUT_RERINGS {
            return None;
        }
        self.timeouts += 1;
        let minutes = minutes.clamp(1, MAX_SNOOZE_MINUTES);
        let at = now_ms.saturating_add(i64::from(minutes) * 60_000);
        self.ring_at_ms = Some(at);
        Some(at)
    }

    pub fn dismiss(&mut self) {
        self.trip.dismiss();
        self.ring_at_ms = None;
    }

    pub fn fix_out(&self, outcome: &FixOutcome) -> FixOut {
        self.fix_out_in(self.lang, outcome)
    }

    /// The status of a fix with its notification title and text in `lang`.
    pub fn fix_out_in(&self, lang: Lang, outcome: &FixOutcome) -> FixOut {
        let u = &outcome.update;
        let ringing = u.state == TripState::Arrived;
        let label = self.label();
        let eta_min = u.eta.map(eta_minutes);
        let text = status_text(
            lang, &label, u.inside, u.side, u.line_m, u.circle_m, eta_min,
        );
        let c = outcome.cadence;
        let (next, retry, high) = match c {
            Some(c) => (c.next_fix_in, c.retry_after, c.high_accuracy),
            None => (
                u.next_fix_in,
                retry_after(u.next_fix_in),
                u.next_fix_in <= HIGH_ACCURACY_BELOW,
            ),
        };
        FixOut {
            state: u.state,
            distance_m: u.line_m,
            next_fix_ms: millis(next),
            retry_ms: millis(retry),
            high_accuracy: high,
            ring: u.ring,
            title: if ringing {
                crossed_title(lang, &label)
            } else {
                label
            },
            text,
            notice_sent: self.notice.as_ref().is_some_and(|n| n.sent),
            notice_failed: self.notice.as_ref().is_some_and(NoticeState::gave_up),
            side: u.side,
            inside: u.inside,
            eta_min,
            circle_m: Some(u.circle_m),
            at: Some(FixPos {
                lat: outcome.fix.at.lat,
                lon: outcome.fix.at.lon,
                accuracy_m: outcome.fix.accuracy_m,
            }),
            speed_mps: outcome.speed_mps,
            assumed_mps: c.map(|c| c.assumed_mps),
            cadence: c.map(|c| c.basis),
            late: c.is_some_and(|c| c.late),
        }
    }
}

fn millis(d: Duration) -> u64 {
    d.as_millis().try_into().unwrap_or(u64::MAX)
}

/// An ETA in whole minutes, rounded up and never less than one ("about 1 min" is the soonest).
pub fn eta_minutes(eta: std::time::Duration) -> u32 {
    let minutes = (eta.as_secs_f64() / 60.0).ceil().max(1.0);
    if minutes >= f64::from(u32::MAX) {
        u32::MAX
    } else {
        minutes as u32
    }
}

/// The notification text of a running trip: where the user is against the circle and the line,
/// and the ETA when there is one.
pub fn status_text(
    lang: Lang,
    label: &str,
    inside: bool,
    side: Option<Side>,
    line_m: f64,
    circle_m: f64,
    eta_min: Option<u32>,
) -> String {
    let mut text = if !inside {
        let away = format_distance_in(lang, circle_m);
        match lang {
            Lang::En => format!("Outside the circle, {away} away"),
            Lang::Tr => format!("Çemberin dışında, {away} uzakta"),
        }
    } else {
        match side {
            Some(side) => {
                let line = format_distance_in(lang, line_m);
                match lang {
                    Lang::En => format!("{} side, {line} to the line {label}", side.label()),
                    Lang::Tr => format!("{} taraf, {label} çizgisine {line}", side.label_in(lang)),
                }
            }
            None => lang.pick("On the line", "Çizginin üzerinde").to_owned(),
        }
    };
    if let Some(min) = eta_min {
        text.push_str(&match lang {
            Lang::En => format!(", about {min} min"),
            Lang::Tr => format!(", yaklaşık {min} dk"),
        });
    }
    text
}

/// The notification title once the line was crossed.
pub fn crossed_title(lang: Lang, label: &str) -> String {
    match lang {
        Lang::En => format!("Crossed {label}"),
        Lang::Tr => format!("Çizgi geçildi: {label}"),
    }
}

/// Short distance text: 10 m steps below a kilometre, then one decimal of km.
pub fn format_distance(metres: f64) -> String {
    format_distance_in(Lang::En, metres)
}

/// [`format_distance`] in `lang`: "850 m", "1.2 km" / "1,2 km". Rounded before the unit is chosen, so
/// 995 m reads "1.0 km" and never "1000 m"; a negative or broken length reads "0 m".
pub fn format_distance_in(lang: Lang, metres: f64) -> String {
    let metres = if metres.is_finite() {
        metres.max(0.0)
    } else {
        0.0
    };
    let tens = (metres / 10.0).round() as i64 * 10;
    if tens < 1_000 {
        format!("{tens} m")
    } else {
        format!("{} km", lang.one_decimal(metres / 1_000.0))
    }
}

/// Local `HH:MM` clock time of `now_ms` plus `eta_min` minutes (at least one).
pub fn arrival_clock(now_ms: i64, eta_min: u32, tz_offset_s: i32) -> String {
    let at_s = now_ms.div_euclid(1000) + i64::from(eta_min.max(1)) * 60 + i64::from(tz_offset_s);
    let secs = at_s.rem_euclid(86_400);
    format!("{:02}:{:02}", secs / 3600, secs % 3600 / 60)
}

/// Shareable status of a running trip, with a geo link on the second line.
/// `arrive` is an optional local clock time (see [`arrival_clock`]) shown
/// next to the ETA.
pub fn status_share_text(
    label: &str,
    line_m: Option<f64>,
    eta_min: Option<u32>,
    here: Option<Coord>,
    target: Coord,
    crossed: bool,
    arrive: Option<&str>,
) -> String {
    status_share_text_in(
        Lang::En,
        label,
        line_m,
        eta_min,
        here,
        target,
        crossed,
        arrive,
    )
}

/// [`status_share_text`] in `lang`.
#[allow(clippy::too_many_arguments)]
pub fn status_share_text_in(
    lang: Lang,
    label: &str,
    line_m: Option<f64>,
    eta_min: Option<u32>,
    here: Option<Coord>,
    target: Coord,
    crossed: bool,
    arrive: Option<&str>,
) -> String {
    let link = crate::point_qr_payload(target, Some(label));
    if crossed {
        return match lang {
            Lang::En => format!("Arrived at {label}.\n{link}"),
            Lang::Tr => format!("Vardım: {label}.\n{link}"),
        };
    }
    let mut parts = Vec::new();
    if let Some(m) = line_m.filter(|m| m.is_finite()) {
        let line = format_distance_in(lang, m);
        parts.push(match lang {
            Lang::En => format!("{line} to the line"),
            Lang::Tr => format!("çizgiye {line}"),
        });
    }
    if let Some(min) = eta_min {
        let min = min.max(1);
        parts.push(match (lang, arrive) {
            (Lang::En, Some(at)) => format!("about {min} min (around {at})"),
            (Lang::En, None) => format!("about {min} min"),
            (Lang::Tr, Some(at)) => format!("yaklaşık {min} dk (saat {at} gibi)"),
            (Lang::Tr, None) => format!("yaklaşık {min} dk"),
        });
    }
    if let Some(h) = here {
        let point = crate::coord_format::cardinal_in(lang, h.bearing_deg(target));
        parts.push(match lang {
            Lang::En => format!("heading {point}"),
            Lang::Tr => format!("yön {point}"),
        });
    }
    let head = match (lang, parts.is_empty()) {
        (Lang::En, true) => format!("On my way to {label}."),
        (Lang::En, false) => format!("On my way to {label}: {}.", parts.join(", ")),
        (Lang::Tr, true) => format!("Yoldayım, hedef {label}."),
        (Lang::Tr, false) => format!("Yoldayım, hedef {label}: {}.", parts.join(", ")),
    };
    format!("{head}\n{link}")
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn status_text_variants() {
        let t = Coord::new(41.0, 29.0);
        let here = Coord::new(40.99, 28.99);
        let s = status_share_text("Moda", Some(1234.0), Some(4), Some(here), t, false, None);
        let mut lines = s.lines();
        assert_eq!(
            lines.next().unwrap(),
            "On my way to Moda: 1.2 km to the line, about 4 min, heading NE."
        );
        assert!(lines.next().unwrap().starts_with("geo:"));
        let s = status_share_text("Moda", Some(300.0), None, None, t, false, None);
        assert!(s.starts_with("On my way to Moda: 300 m to the line.\n"));
        let s = status_share_text("Moda", None, None, None, t, false, None);
        assert!(s.starts_with("On my way to Moda.\ngeo:"));
        let s = status_share_text("Moda", Some(5.0), Some(2), Some(here), t, true, None);
        assert!(s.starts_with("Arrived at Moda.\ngeo:"));
        assert_eq!(s.lines().count(), 2);
        let s = status_share_text("Moda", None, Some(4), None, t, false, Some("18:42"));
        assert!(s.starts_with("On my way to Moda: about 4 min (around 18:42).\n"));
        let s = status_share_text("Moda", None, None, None, t, false, Some("18:42"));
        assert!(s.starts_with("On my way to Moda.\n"));
    }

    #[test]
    fn arrival_clock_cases() {
        // 2024-01-01 12:00:00 UTC
        let noon = 1_704_110_400_000;
        assert_eq!(arrival_clock(noon, 4, 0), "12:04");
        assert_eq!(arrival_clock(noon, 0, 0), "12:01");
        assert_eq!(arrival_clock(noon, 90, 0), "13:30");
        // 23:30 UTC + 3h = 02:30 next day, +45 min = 03:15.
        let late = noon + 11 * 3_600_000 + 30 * 60_000;
        assert_eq!(arrival_clock(late, 45, 3 * 3600), "03:15");
        // 00:10 UTC, -5h offset: previous day 19:10, +20 min = 19:30.
        let early = noon - 12 * 3_600_000 + 10 * 60_000;
        assert_eq!(arrival_clock(early, 20, -5 * 3600), "19:30");
        // Before the epoch still wraps.
        assert_eq!(arrival_clock(-60_000, 1, 0), "00:00");
    }

    #[test]
    fn format_distance_edges() {
        assert_eq!(format_distance(0.0), "0 m");
        assert_eq!(format_distance(4.0), "0 m");
        assert_eq!(format_distance(994.0), "990 m");
        assert_eq!(format_distance(1000.0), "1.0 km");
        assert_eq!(format_distance(1250.0), "1.2 km");
        assert_eq!(format_distance(12_340.0), "12.3 km");
    }

    const MID: Coord = Coord::new(41.0, 29.0);

    /// Local metres east/north of MID.
    fn at(east: f64, north: f64) -> Coord {
        let lat = MID.lat + (north / crate::EARTH_RADIUS_M).to_degrees();
        let lon =
            MID.lon + (east / (crate::EARTH_RADIUS_M * MID.lat.to_radians().cos())).to_degrees();
        Coord::new(lat, lon)
    }

    fn ep(label: &str, east: f64) -> Endpoint {
        Endpoint {
            label: label.into(),
            coord: at(east, 0.0),
        }
    }

    /// A at 500 m west, B at 500 m east: left is north, right is south.
    fn trip() -> ActiveTrip {
        ActiveTrip::new(1, ep("Home", -500.0), ep("Work", 500.0), 0).unwrap()
    }

    fn fix(east: f64, north: f64, accuracy_m: f64, speed_mps: Option<f64>) -> Fix {
        Fix {
            at: at(east, north),
            accuracy_m,
            speed_mps,
        }
    }

    fn with_notice(t: &mut ActiveTrip, rule: NoticeRule) {
        t.notice = Some(NoticeState {
            chat: "chat".into(),
            text_template: notice::DEFAULT_TEMPLATE.into(),
            rule,
            sent: false,
            attempts: 0,
        });
    }

    #[test]
    fn new_rejects_degenerate_pairs() {
        let a = ep("A", 0.0);
        assert_eq!(
            ActiveTrip::new(1, a.clone(), a, 0).unwrap_err(),
            DiameterError::SamePoint
        );
        assert_eq!(
            ActiveTrip::new(1, ep("A", 0.0), ep("B", 5.0), 0).unwrap_err(),
            DiameterError::TooShort
        );
    }

    #[test]
    fn label_joins_both_ends() {
        assert_eq!(trip().label(), "Home – Work");
        // Ends without a name never leave a bare dash.
        let mut unnamed = trip();
        unnamed.a.label = String::new();
        unnamed.b.label = "  ".into();
        assert_eq!(unnamed.label(), "Point A – Point B");
        assert_eq!(unnamed.label_in(Lang::Tr), "A noktası – B noktası");
        unnamed.b.label = "Pier".into();
        unnamed.lang = Lang::Tr;
        assert_eq!(unnamed.label(), "A noktası – Pier");
    }

    #[test]
    fn track_ignores_jitter_and_bad_fixes() {
        let mut t = trip();
        t.on_fix(fix(0.0, 5_000.0, 10.0, None), 0);
        t.on_fix(fix(0.0, 5_008.0, 10.0, None), 1);
        t.on_fix(fix(0.0, 9_000.0, 2_000.0, None), 2);
        assert_eq!(t.track.fixes, 2);
        assert_eq!(t.track.travelled_m, 0.0);
        t.on_fix(fix(0.0, 4_000.0, 10.0, None), 3);
        assert!((t.track.travelled_m - 1_000.0).abs() < 10.0);
        assert_eq!(t.track.fixes, 3);
    }

    #[test]
    fn with_alarm_notice_goes_out_with_the_ring_only() {
        let mut t = trip();
        with_notice(&mut t, NoticeRule::WithAlarm);
        assert!(
            t.on_fix(fix(0.0, 300.0, 10.0, None), 0)
                .send_notice
                .is_none()
        );
        assert!(
            t.on_fix(fix(0.0, 100.0, 10.0, None), 1)
                .send_notice
                .is_none()
        );
        let o = t.on_fix(fix(0.0, -100.0, 10.0, None), 2);
        assert!(o.update.ring);
        assert_eq!(
            o.send_notice.as_deref(),
            Some("Arriving at Work in about a few min.")
        );
        t.notice_result(NoticeDelivery::Sent);
        assert!(t.fix_out(&o).notice_sent);
    }

    #[test]
    fn before_circle_notice_goes_out_early_once() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600),
            },
        );
        // 3 km out at 10 m/s: 2.5 km to the circle edge = 250 s.
        let o = t.on_fix(fix(0.0, 3_000.0, 10.0, Some(10.0)), 0);
        assert!(!o.update.ring);
        assert!(o.send_notice.is_some());
        t.notice_result(NoticeDelivery::Sent);
        assert!(
            t.on_fix(fix(0.0, 200.0, 10.0, None), 1)
                .send_notice
                .is_none()
        );
    }

    #[test]
    fn notice_retries_stop_after_three_attempts() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600),
            },
        );
        for i in 0..3 {
            assert!(
                t.on_fix(fix(0.0, 100.0, 10.0, None), i)
                    .send_notice
                    .is_some()
            );
            t.notice_result(NoticeDelivery::RetryLater);
        }
        assert!(
            t.on_fix(fix(0.0, 100.0, 10.0, None), 3)
                .send_notice
                .is_none()
        );
        assert!(!t.notice.as_ref().unwrap().sent);
    }

    #[test]
    fn notice_stops_on_give_up() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600),
            },
        );
        assert!(
            t.on_fix(fix(0.0, 100.0, 10.0, None), 0)
                .send_notice
                .is_some()
        );
        t.notice_result(NoticeDelivery::GiveUp);
        assert!(
            t.on_fix(fix(0.0, 100.0, 10.0, None), 1)
                .send_notice
                .is_none()
        );
    }

    #[test]
    fn inaccurate_fix_sends_no_notice() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600),
            },
        );
        assert!(
            t.on_fix(fix(0.0, 100.0, 2_000.0, None), 0)
                .send_notice
                .is_none()
        );
    }

    #[test]
    fn snooze_clamps_and_caps() {
        let mut t = trip();
        assert_eq!(t.snooze(1_000, 0), Some(1_000 + 60_000));
        assert_eq!(t.snooze(1_000, 99), Some(1_000 + 15 * 60_000));
        assert_eq!(t.ring_at_ms, Some(1_000 + 15 * 60_000));
        for _ in 0..3 {
            assert!(t.snooze(0, 5).is_some());
        }
        assert_eq!(t.snooze(0, 5), None);
        t.dismiss();
        assert_eq!(t.ring_at_ms, None);
        assert_eq!(t.trip.state(), TripState::Dismissed);
    }

    #[test]
    fn fix_out_describes_progress() {
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 120.0, 5.0, None), 0);
        let out = t.fix_out(&o);
        assert_eq!(out.text, "Left side, 120 m to the line Home – Work");
        assert_eq!(out.title, "Home – Work");
        assert_eq!(out.side, Some(Side::Left));
        assert!(out.inside && !out.ring);
        assert_eq!(out.distance_m.round(), 120.0);
    }

    #[test]
    fn fix_out_outside_the_circle_and_on_the_line() {
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 1_700.0, 5.0, None), 0);
        let out = t.fix_out(&o);
        assert_eq!(out.text, "Outside the circle, 1.2 km away");
        assert!(!out.inside);
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 2.0, 5.0, None), 0);
        assert_eq!(t.fix_out(&o).text, "On the line");
    }

    #[test]
    fn fix_out_rings_in_both_directions() {
        for (from, to) in [(150.0, -150.0), (-150.0, 150.0)] {
            let mut t = trip();
            t.on_fix(fix(0.0, from, 5.0, None), 0);
            let o = t.on_fix(fix(0.0, to, 5.0, None), 1);
            let out = t.fix_out(&o);
            assert!(out.ring);
            assert_eq!(out.state, TripState::Arrived);
            assert_eq!(out.title, "Crossed Home – Work");
        }
    }

    #[test]
    fn fix_out_carries_the_position_and_reads_old_answers() {
        let mut t = trip();
        let f = fix(0.0, 700.0, 12.0, None);
        let o = t.on_fix(f, 0);
        let out = t.fix_out(&o);
        let at = out.at.unwrap();
        assert_eq!((at.lat, at.lon, at.accuracy_m), (f.at.lat, f.at.lon, 12.0));
        let mut json: serde_json::Value = serde_json::to_value(&out).unwrap();
        let obj = json.as_object_mut().unwrap();
        obj.remove("at");
        obj.remove("side");
        obj.remove("inside");
        obj.remove("circle_m");
        let old: FixOut = serde_json::from_value(json).unwrap();
        assert_eq!((old.at, old.side, old.inside), (None, None, false));
        assert_eq!(old.circle_m, None);
    }

    #[test]
    fn fix_in_wire_round_trip() {
        let json = r#"{"lat":41.0,"lon":29.0,"accuracy_m":12.5,"speed_mps":null,"at_ms":7}"#;
        let f: FixIn = serde_json::from_str(json).unwrap();
        assert_eq!(f.fix().at, MID);
        assert_eq!(f.fix().speed_mps, None);
    }

    #[test]
    fn active_trip_serde_round_trip() {
        let mut t = trip();
        with_notice(&mut t, NoticeRule::WithAlarm);
        t.on_fix(fix(0.0, 5_000.0, 10.0, None), 0);
        t.on_fix(fix(0.0, 4_000.0, 10.0, None), 1);
        t.on_fix(fix(0.0, 100.0, 10.0, None), 2);
        let back: ActiveTrip = serde_json::from_str(&serde_json::to_string(&t).unwrap()).unwrap();
        assert_eq!(back.track, t.track);
        assert_eq!(back.notice, t.notice);
        assert_eq!(back.trip, t.trip);
        assert_eq!(back.trip.side(), Some(Side::Left));
        assert_eq!(back.a, t.a);
    }

    #[test]
    fn old_single_destination_trip_json_does_not_parse() {
        let json = r#"{"id":1,"label":"Kadikoy",
            "trip":{"destination":{"lat":41.0,"lon":29.0},"rule":{"Radius":{"metres":300.0}},
                    "state":"Tracking","last_distance_m":null},
            "started_ms":0,"snoozes":0,"ring_at_ms":null,
            "track":{"last":null,"travelled_m":0.0,"fixes":0},"notice":null}"#;
        assert!(serde_json::from_str::<ActiveTrip>(json).is_err());
    }

    #[test]
    fn retry_ms_follows_the_interval() {
        let mut t = trip();
        let far = t.on_fix(fix(0.0, 50_000.0, 5.0, None), 0);
        let out = t.fix_out(&far);
        assert_eq!(out.next_fix_ms, 300_000);
        assert_eq!(out.retry_ms, 330_000);
        let near = t.on_fix(fix(0.0, 3.0, 5.0, None), 1);
        let out = t.fix_out(&near);
        assert!(out.next_fix_ms <= 5_000);
        assert!(out.retry_ms > out.next_fix_ms);
        assert!(out.retry_ms <= out.next_fix_ms + 30_000);
    }

    #[test]
    fn fix_out_without_retry_ms_still_parses() {
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 700.0, 5.0, None), 0);
        let mut json = serde_json::to_value(t.fix_out(&o)).unwrap();
        assert!(json.as_object_mut().unwrap().remove("retry_ms").is_some());
        let old: FixOut = serde_json::from_value(json).unwrap();
        assert_eq!(old.retry_ms, 0);
    }

    #[test]
    fn notice_failed_after_give_up_and_retries_but_not_after_sent() {
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 700.0, 5.0, None), 0);
        with_notice(&mut t, NoticeRule::WithAlarm);
        assert!(!t.fix_out(&o).notice_failed);
        t.notice_result(NoticeDelivery::GiveUp);
        let out = t.fix_out(&o);
        assert!(out.notice_failed && !out.notice_sent);

        with_notice(&mut t, NoticeRule::WithAlarm);
        for _ in 0..MAX_NOTICE_ATTEMPTS {
            assert!(!t.fix_out(&o).notice_failed);
            t.notice_result(NoticeDelivery::RetryLater);
        }
        assert!(t.fix_out(&o).notice_failed);

        with_notice(&mut t, NoticeRule::WithAlarm);
        t.notice_result(NoticeDelivery::Sent);
        let out = t.fix_out(&o);
        assert!(out.notice_sent && !out.notice_failed);
    }

    #[test]
    fn snoozes_left_counts_down_and_saturates() {
        let mut t = arrived();
        assert_eq!(t.snoozes_left(), MAX_SNOOZES);
        t.snooze(0, 5);
        assert_eq!(t.snoozes_left(), MAX_SNOOZES - 1);
        t.snoozes = MAX_SNOOZES + 3;
        assert_eq!(t.snoozes_left(), 0);
    }

    #[test]
    fn fix_out_without_notice_failed_still_parses() {
        let mut t = trip();
        let o = t.on_fix(fix(0.0, 700.0, 5.0, None), 0);
        let mut json = serde_json::to_value(t.fix_out(&o)).unwrap();
        assert!(
            json.as_object_mut()
                .unwrap()
                .remove("notice_failed")
                .is_some()
        );
        let old: FixOut = serde_json::from_value(json).unwrap();
        assert!(!old.notice_failed);
    }

    fn arrived() -> ActiveTrip {
        let mut t = trip();
        t.on_fix(fix(0.0, 150.0, 5.0, None), 0);
        t.on_fix(fix(0.0, -150.0, 5.0, None), 1);
        assert_eq!(t.trip.state(), TripState::Arrived);
        t
    }

    #[test]
    fn ring_timed_out_caps_and_leaves_snoozes_alone() {
        let mut t = arrived();
        assert_eq!(t.ring_timed_out(1_000, 5), Some(1_000 + 300_000));
        assert_eq!(t.ring_at_ms, Some(301_000));
        assert_eq!(t.snoozes, 0);
        for _ in 1..MAX_TIMEOUT_RERINGS {
            assert!(t.ring_timed_out(0, 0).is_some());
        }
        assert_eq!(t.ring_timed_out(0, 5), None);
        assert_eq!(t.timeouts, MAX_TIMEOUT_RERINGS);
        // Snoozes are independent of timeouts.
        assert!(t.snooze(0, 5).is_some());
        assert_eq!(t.snoozes, 1);
    }

    #[test]
    fn ring_timed_out_needs_a_ringing_trip() {
        let mut t = trip();
        assert_eq!(t.ring_timed_out(0, 5), None);
        let mut d = arrived();
        d.dismiss();
        assert_eq!(d.ring_timed_out(0, 5), None);
        assert_eq!(d.timeouts, 0);
    }

    #[test]
    fn old_trip_json_without_timeouts_loads() {
        let t = arrived();
        let mut json = serde_json::to_value(&t).unwrap();
        assert!(json.as_object_mut().unwrap().remove("timeouts").is_some());
        let back: ActiveTrip = serde_json::from_value(json).unwrap();
        assert_eq!(back.timeouts, 0);
        assert_eq!(back.trip, t.trip);
    }

    #[test]
    fn eta_minutes_round_up_and_are_never_zero() {
        use std::time::Duration;
        assert_eq!(eta_minutes(Duration::ZERO), 1);
        assert_eq!(eta_minutes(Duration::from_millis(1)), 1);
        assert_eq!(eta_minutes(Duration::from_secs(60)), 1);
        assert_eq!(eta_minutes(Duration::from_secs(61)), 2);
        assert_eq!(eta_minutes(Duration::from_secs(14 * 60)), 14);
        assert_eq!(eta_minutes(Duration::from_secs(14 * 60 + 1)), 15);
        assert_eq!(eta_minutes(Duration::MAX), u32::MAX);
    }

    #[test]
    fn a_status_written_before_eta_min_existed_still_parses() {
        let old = r#"{"state":"Tracking","distance_m":10.0,"next_fix_ms":1000,"high_accuracy":false,"ring":false,
            "title":"t","text":"x","notice_sent":false}"#;
        let out: FixOut = serde_json::from_str(old).unwrap();
        assert_eq!(out.eta_min, None);
    }

    // ---- the cadence (AD-SA10) ----

    /// A fix `north` metres from the line, straight above the middle, at `t_s` seconds.
    fn timed(t: &mut ActiveTrip, north: f64, t_s: f64, accuracy_m: f64) -> FixOut {
        let o = t.on_fix(fix(0.0, north, accuracy_m, None), (t_s * 1000.0) as i64);
        t.fix_out(&o)
    }

    #[test]
    fn an_old_trip_without_a_speed_history_loads_and_an_empty_one_is_not_written() {
        let t = trip();
        let json = serde_json::to_value(&t).unwrap();
        assert!(json.get("speed").is_none());
        let back: ActiveTrip = serde_json::from_value(json).unwrap();
        assert!(back.speed.is_empty());
        let mut t = trip();
        timed(&mut t, 5_000.0, 0.0, 10.0);
        timed(&mut t, 4_980.0, 15.0, 10.0);
        let json = serde_json::to_value(&t).unwrap();
        assert!(json.get("speed").is_some());
        let back: ActiveTrip = serde_json::from_value(json).unwrap();
        assert_eq!(back.speed, t.speed);
    }

    #[test]
    fn fix_out_carries_the_cadence_and_reads_answers_without_it() {
        let mut t = trip();
        let first = timed(&mut t, 2_700.0, 0.0, 10.0);
        assert_eq!(first.cadence, Some(SpeedBasis::Cold));
        assert_eq!(first.assumed_mps, Some(V_MAX_MPS));
        assert_eq!(first.speed_mps, None);
        assert!(!first.late);
        // The first fix asks as today did (rounded down to the ladder).
        assert_eq!(first.next_fix_ms, 20_000);
        // Walking: the second fix 20 s later has a bound and asks later.
        let second = timed(&mut t, 2_672.0, 20.0, 10.0);
        assert_eq!(second.cadence, Some(SpeedBasis::Bound));
        assert!(second.next_fix_ms > first.next_fix_ms, "{second:?}");
        assert_eq!(
            second.retry_ms,
            millis(retry_after(Duration::from_millis(second.next_fix_ms)))
        );
        assert!((second.speed_mps.unwrap() - 1.4).abs() < 0.05, "{second:?}");
        // A fix after the watchdog window is late.
        let late = timed(&mut t, 2_600.0, 20.0 + 200.0, 10.0);
        assert!(late.late);

        let mut json = serde_json::to_value(&second).unwrap();
        let obj = json.as_object_mut().unwrap();
        for key in ["speed_mps", "assumed_mps", "cadence", "late"] {
            assert!(obj.remove(key).is_some(), "{key}");
        }
        let old: FixOut = serde_json::from_value(json).unwrap();
        assert_eq!(
            (old.speed_mps, old.assumed_mps, old.cadence, old.late),
            (None, None, None, false)
        );
    }

    #[test]
    fn a_ringing_trip_drops_back_to_the_idle_interval() {
        let mut t = trip();
        timed(&mut t, 150.0, 0.0, 5.0);
        let rang = timed(&mut t, -150.0, 10.0, 5.0);
        assert!(rang.ring);
        assert_eq!(rang.next_fix_ms, 60_000);
        assert_eq!(rang.cadence, None);
        assert!(!rang.high_accuracy);
        assert_eq!(t.no_fix(20_000), None);
    }

    #[test]
    fn the_eta_and_the_notice_use_the_measured_speed_without_a_provider_one() {
        let mut t = trip();
        with_notice(
            &mut t,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(300),
            },
        );
        // A bus at 10 m/s, 3.5 km from the circle (4 km from the line): 350 s to the circle.
        let o = t.on_fix(fix(0.0, 4_000.0, 10.0, None), 0);
        assert!(o.update.eta.is_none() && o.send_notice.is_none());
        let o = t.on_fix(fix(0.0, 3_800.0, 10.0, None), 20_000);
        assert!(o.send_notice.is_none());
        let eta = o.update.eta.unwrap().as_secs_f64();
        assert!((eta - 380.0).abs() < 5.0, "{eta}");
        // 2.9 km to the circle: 290 s, under the 300 s of the rule.
        let o = t.on_fix(fix(0.0, 3_400.0, 10.0, None), 60_000);
        assert!(o.send_notice.is_some());
    }

    #[test]
    fn a_pending_notice_asks_sooner() {
        let walk = |notice: bool| {
            let mut t = trip();
            if notice {
                with_notice(
                    &mut t,
                    NoticeRule::BeforeCircle {
                        before: Duration::from_secs(600),
                    },
                );
            }
            timed(&mut t, 2_500.0, 0.0, 10.0);
            timed(&mut t, 2_472.0, 20.0, 10.0);
            timed(&mut t, 2_444.0, 40.0, 10.0).next_fix_ms
        };
        assert!(walk(true) < walk(false), "{} {}", walk(true), walk(false));
    }

    #[test]
    fn no_fix_dead_reckons_and_tightens_with_time() {
        let mut t = trip();
        timed(&mut t, 2_000.0, 0.0, 10.0);
        timed(&mut t, 1_972.0, 20.0, 10.0);
        let mut prev = u64::MAX;
        let mut high = false;
        for k in 1..=40 {
            let now_ms = 20_000 + k * 5_000;
            let c = t.no_fix(now_ms).unwrap();
            let out = t.no_fix_out(Some(c));
            assert!(out.next_fix_ms <= prev, "{out:?}");
            assert!(out.late && out.at.is_none() && !out.ring);
            assert_eq!(out.state, TripState::Tracking);
            assert_eq!(out.cadence, Some(SpeedBasis::Cold));
            prev = out.next_fix_ms;
            high |= out.high_accuracy;
        }
        // 200 s without a fix can have covered the 2 km: down to the floor, on GPS.
        assert_eq!(prev, 3_000);
        assert!(high);
        // A fix again: back to a plan from the history.
        let back = timed(&mut t, 1_700.0, 230.0, 10.0);
        assert!(back.late);
        assert_ne!(back.cadence, Some(SpeedBasis::Cold));
        // An unusable fix dead-reckons too, and is not kept.
        let n = t.speed.samples().len();
        let bad = timed(&mut t, 1_690.0, 240.0, 900.0);
        assert_eq!(t.speed.samples().len(), n);
        assert_eq!(bad.cadence, Some(SpeedBasis::Cold));
    }

    #[test]
    fn no_fix_without_any_fix_keeps_todays_unknown_distance() {
        let mut t = trip();
        let c = t.no_fix(1_000).unwrap();
        assert_eq!(c.next_fix_in, crate::poll::MAX_POLL);
        let out = t.no_fix_out(Some(c));
        assert_eq!(out.title, "Home – Work");
        assert_eq!(out.text, "");
    }

    /// The traveller heads straight for the middle of the line, `v0` and then accelerating; the trip
    /// plans its own fixes (late by the whole grace for half of them). Every trip rings, soon after the
    /// crossing.
    #[test]
    fn simulated_trips_always_ring_soon_after_the_crossing() {
        let mut seed: u64 = 0xC0FF_EE11;
        let mut rnd = |lo: f64, hi: f64| {
            seed ^= seed >> 12;
            seed ^= seed << 25;
            seed ^= seed >> 27;
            let unit =
                (seed.wrapping_mul(0x2545_F491_4F6C_DD1D) >> 11) as f64 / (1u64 << 53) as f64;
            lo + (hi - lo) * unit
        };
        for n in 0..1_500 {
            let start = rnd(150.0, 6_000.0);
            let v0 = [1.4, 5.0, 8.0][(rnd(0.0, 3.0) as usize).min(2)];
            let (switch, a) = (rnd(0.0, 600.0), rnd(0.3, cadence::A_MAX_MPS2));
            let top = rnd(8.0, V_MAX_MPS).max(v0);
            let acc = rnd(5.0, 60.0);
            let late = rnd(0.0, 1.0) < 0.5;
            let doppler = rnd(0.0, 1.0) < 0.5;
            let speed = |t: f64| {
                if t <= switch {
                    v0
                } else {
                    (v0 + a * (t - switch)).min(top)
                }
            };
            let travelled = |t: f64| {
                if t <= switch {
                    return v0 * t;
                }
                let (u, t_acc) = (t - switch, (top - v0) / a);
                if u <= t_acc {
                    v0 * switch + v0 * u + a * u * u / 2.0
                } else {
                    v0 * switch + v0 * t_acc + a * t_acc * t_acc / 2.0 + top * (u - t_acc)
                }
            };
            let mut t = trip();
            let mut now = 0.0;
            let mut rang = None;
            while now < 6.0 * 3_600.0 {
                let (r, th) = (acc * rnd(0.0, 1.0).sqrt(), rnd(0.0, std::f64::consts::TAU));
                let f = Fix {
                    at: at(r * th.cos(), start - travelled(now) + r * th.sin()),
                    accuracy_m: acc,
                    speed_mps: doppler.then(|| (speed(now) + rnd(-0.5, 0.5)).max(0.0)),
                };
                let o = t.on_fix(f, (now * 1000.0) as i64);
                let out = t.fix_out(&o);
                if out.ring {
                    rang = Some(now);
                    break;
                }
                let wait = if late { out.retry_ms } else { out.next_fix_ms };
                now += wait as f64 / 1000.0;
            }
            let rang = rang.unwrap_or_else(|| panic!("trip {n} never rang"));
            // When the line was crossed (bisection on the monotonic travelled distance).
            let (mut lo, mut hi) = (0.0, rang);
            for _ in 0..60 {
                let mid = (lo + hi) / 2.0;
                if travelled(mid) < start {
                    lo = mid
                } else {
                    hi = mid
                }
            }
            let band = crate::DEAD_BAND_M.max(acc);
            let limit = 3.0 + 5.0 + (band + acc) / speed(hi) + 0.01;
            assert!(rang >= hi, "trip {n} rang before the crossing");
            assert!(
                rang - hi <= limit,
                "trip {n}: rang {:.1} s after the crossing (limit {limit:.1})",
                rang - hi
            );
        }
    }
}
