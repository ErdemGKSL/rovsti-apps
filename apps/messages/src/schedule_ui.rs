//! Scheduled send (AD-MSG6): the "Send later" sheet and the Scheduled strip.
//!
//! Long-pressing Send (or right-clicking it on desktop) opens the sheet. An SMS
//! goes to the local `ScheduleQueue` (`scheduled.json`) and is armed as an
//! Android alarm; a WhatsApp text goes to the gateway, which owns it. Each
//! conversation shows a strip of what is still waiting, local and gateway
//! items together. The parsing, matching and wording are pure and tested here;
//! the sheet is the GPUI shell.

use gpui::{
    AnyElement, AppContext as _, Context, Entity, InteractiveElement, IntoElement, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, TextInputAction, TextInputPurpose, Window,
    div, px,
};
use rovsti_core::{
    PhoneNumber,
    schedule::{self, FiredSms, MIN_LEAD_MS, ScheduleTarget, ScheduledSms},
};
use rovsti_ui::{
    components::{glass_pill, list_row},
    field::{TextField, field_box},
    sheet::Sheet,
    theme,
    tokens::{Theme, typography},
};
use rovsti_wa_proto::{ScheduledState, ScheduledText};
use serde::Deserialize;

use crate::{
    MessagesApp,
    conversation::{civil_from_days, clock},
};

const DAY_S: i64 = 86_400;
const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

/// How many waiting items a strip lists before it says "and N more".
const STRIP_ROWS: usize = 3;

// ---- pure: what the sheet schedules -------------------------------------------

/// Where a scheduled text goes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Dest {
    Sms { to: String, sub_id: Option<i32> },
    WhatsApp { chat: String },
}

/// The text waiting in the sheet for a time.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Draft {
    pub dest: Dest,
    pub body: String,
}

/// The id of a new local item: the clock, or one past the largest id queued,
/// so ids survive restarts without colliding.
pub fn next_schedule_id(existing: &[ScheduledSms], now_ms: i64) -> u64 {
    let clock = u64::try_from(now_ms).unwrap_or(0);
    existing
        .iter()
        .map(|i| i.id.saturating_add(1))
        .fold(clock, u64::max)
}

/// The pill after scheduled sends failed: what to say and, when an SMS can be
/// reopened in the composer, its `(to, body)`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FailedNotice {
    pub text: String,
    pub reopen: Option<(String, String)>,
}

/// `None` when nothing failed. WhatsApp items never reopen (the gateway owns them).
pub fn failed_notice(failed: &[ScheduledSms]) -> Option<FailedNotice> {
    let sms = |item: &ScheduledSms| match &item.target {
        ScheduleTarget::Sms { to, .. } => Some((to.clone(), item.body.clone())),
        ScheduleTarget::WhatsApp { .. } => None,
    };
    match failed {
        [] => None,
        [one] => {
            let to = match &one.target {
                ScheduleTarget::Sms { to, .. } => to.as_str(),
                ScheduleTarget::WhatsApp { chat } => chat.split('@').next().unwrap_or(chat),
            };
            Some(FailedNotice {
                text: format!("Scheduled message to {to} couldn't be sent"),
                reopen: sms(one),
            })
        }
        many => Some(FailedNotice {
            text: format!("{} scheduled messages couldn't be sent", many.len()),
            reopen: many
                .iter()
                .filter(|i| sms(i).is_some())
                .max_by_key(|i| i.at_ms)
                .and_then(sms),
        }),
    }
}

/// Shown in the Send later sheet when Android may deliver an SMS late.
pub const LATE_NOTE: &str =
    "Android may send it a few minutes late. Tap to allow exact alarms in App info.";

/// The note only applies to SMS while exact alarms are known to be denied.
pub fn late_note(is_sms: bool, exact_allowed: Option<bool>) -> Option<&'static str> {
    (is_sms && exact_allowed == Some(false)).then_some(LATE_NOTE)
}

/// The payload `sms::arm_scheduled` takes (`{id, at_ms, to, body, sub_id}`);
/// `None` for a WhatsApp item, which the gateway owns.
pub fn arm_json(item: &ScheduledSms) -> Option<String> {
    let ScheduleTarget::Sms { to, sub_id } = &item.target else {
        return None;
    };
    serde_json::to_string(&serde_json::json!({
        "id": item.id,
        "at_ms": item.at_ms,
        "to": to,
        "body": item.body,
        "sub_id": sub_id,
    }))
    .ok()
}

#[derive(Deserialize)]
struct FiredWire {
    id: u64,
    #[serde(default)]
    ok: bool,
    #[serde(default)]
    at_ms: i64,
}

/// `sms::pull_fired` answers a JSON array of `{id, ok, at_ms}`; anything
/// unreadable counts as nothing fired.
pub fn parse_fired(json: &str) -> Vec<FiredSms> {
    serde_json::from_str::<Vec<FiredWire>>(json)
        .unwrap_or_default()
        .into_iter()
        .map(|f| FiredSms {
            id: f.id,
            ok: f.ok,
            at_ms: f.at_ms,
        })
        .collect()
}

/// Days since 1970-01-01 of a civil date; `None` when the date does not exist.
fn days_from_civil(year: i64, month: i64, day: i64) -> Option<i64> {
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    let y = year - i64::from(month <= 2);
    let era = y.div_euclid(400);
    let yoe = y.rem_euclid(400);
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    // Reject 31 February and friends by reading the date back.
    let (ry, rm, rd) = civil_from_days(days);
    (ry == year && i64::try_from(rm).ok() == Some(month) && rd == day).then_some(days)
}

/// A time typed in the sheet: `HH:MM` (today, or tomorrow when that has
/// passed) or `YYYY-MM-DD HH:MM`, in the phone's zone. The answer is a Unix
/// time at least [`MIN_LEAD_MS`] away, or the reason it is not.
pub fn parse_custom(input: &str, now_ms: i64, tz_offset_s: i32) -> Result<i64, &'static str> {
    const BAD: &str = "Type a time like 18:30 or 2026-12-31 09:00";
    let input = input.trim();
    let (date, time) = match input.split_once(' ') {
        Some((date, time)) => (Some(date.trim()), time.trim()),
        None => (None, input),
    };
    let (h, m) = time.split_once(':').ok_or(BAD)?;
    let (h, m): (i64, i64) = (h.parse().map_err(|_| BAD)?, m.parse().map_err(|_| BAD)?);
    if !(0..24).contains(&h) || !(0..60).contains(&m) {
        return Err(BAD);
    }
    let tz = i64::from(tz_offset_s);
    let local_now_s = now_ms.div_euclid(1000) + tz;
    let at = |day: i64| (day * DAY_S + h * 3600 + m * 60 - tz) * 1000;
    let at_ms = match date {
        Some(date) => {
            let mut parts = date.split('-');
            let mut next = || parts.next().and_then(|p| p.parse::<i64>().ok());
            let (y, mo, d) = (next().ok_or(BAD)?, next().ok_or(BAD)?, next().ok_or(BAD)?);
            if parts.next().is_some() {
                return Err(BAD);
            }
            at(days_from_civil(y, mo, d).ok_or(BAD)?)
        }
        None => {
            let today = local_now_s.div_euclid(DAY_S);
            let first = at(today);
            if first >= now_ms + MIN_LEAD_MS {
                first
            } else {
                at(today + 1)
            }
        }
    };
    if at_ms < now_ms + MIN_LEAD_MS {
        return Err("Pick a time at least a minute from now");
    }
    Ok(at_ms)
}

/// "Today 18:00", "Tomorrow 09:00" or "12 Nov 09:00".
pub fn when_label(at_ms: i64, now_ms: i64, tz_offset_s: i32) -> String {
    let day_of = |ms: i64| (ms.div_euclid(1000) + i64::from(tz_offset_s)).div_euclid(DAY_S);
    let day = day_of(at_ms);
    let time = clock(at_ms, tz_offset_s);
    match day - day_of(now_ms) {
        0 => format!("Today {time}"),
        1 => format!("Tomorrow {time}"),
        _ => {
            let (_, month, d) = civil_from_days(day);
            format!("{d} {} {time}", MONTHS[month.clamp(1, 12) - 1])
        }
    }
}

/// A send time picked with the sheet's -/+ rows: a day offset from today and a
/// time of day in 15-minute steps, in the phone's zone.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CustomPick {
    /// 0 is today, at most [`CustomPick::MAX_DAY`].
    pub day: u16,
    /// Minutes since local midnight, always a multiple of 15.
    pub minutes: u16,
}

impl CustomPick {
    pub const MAX_DAY: u16 = 30;
    const STEP: i32 = 15;
    const DAY_MINUTES: i32 = 24 * 60;

    /// The next quarter hour at least 15 minutes from `now_ms`.
    pub fn initial(now_ms: i64, tz_offset_s: i32) -> Self {
        let local_s = now_ms.div_euclid(1000) + i64::from(tz_offset_s);
        let now_min = local_s.rem_euclid(DAY_S) / 60;
        let step = i64::from(Self::STEP);
        let target = (now_min + step + step - 1).div_euclid(step) * step;
        let total = i64::from(Self::DAY_MINUTES);
        Self {
            day: u16::from(target >= total),
            minutes: u16::try_from(target.rem_euclid(total)).unwrap_or(0),
        }
    }

    /// Moves by whole days, clamped to today..=[`Self::MAX_DAY`] days ahead.
    pub fn step_day(self, delta: i32) -> Self {
        let day = (i32::from(self.day) + delta).clamp(0, i32::from(Self::MAX_DAY));
        Self {
            day: u16::try_from(day).unwrap_or(0),
            ..self
        }
    }

    /// Moves by quarter hours (`delta` counts steps) and wraps within the day.
    pub fn step_minutes(self, delta: i32) -> Self {
        let minutes = (i32::from(self.minutes) + delta * Self::STEP).rem_euclid(Self::DAY_MINUTES);
        Self {
            minutes: u16::try_from(minutes).unwrap_or(0),
            ..self
        }
    }

    /// The Unix time (ms) of this pick.
    pub fn at_ms(self, now_ms: i64, tz_offset_s: i32) -> i64 {
        let tz = i64::from(tz_offset_s);
        let today = (now_ms.div_euclid(1000) + tz).div_euclid(DAY_S);
        let local_s = (today + i64::from(self.day)) * DAY_S + i64::from(self.minutes) * 60;
        (local_s - tz) * 1000
    }

    /// "Today 18:00", "Tomorrow 09:00" or "12 Nov 09:00".
    pub fn label(self, now_ms: i64, tz_offset_s: i32) -> String {
        when_label(self.at_ms(now_ms, tz_offset_s), now_ms, tz_offset_s)
    }

    /// The day row's text ("Today", "Tomorrow", "12 Nov").
    pub fn day_label(self, now_ms: i64, tz_offset_s: i32) -> String {
        let full = self.label(now_ms, tz_offset_s);
        full.rsplit_once(' ')
            .map_or(full.clone(), |(d, _)| d.to_owned())
    }

    /// The time row's text ("18:30").
    pub fn time_label(self, now_ms: i64, tz_offset_s: i32) -> String {
        clock(self.at_ms(now_ms, tz_offset_s), tz_offset_s)
    }

    /// Whether the pick is far enough ahead to schedule.
    pub fn is_schedulable(self, now_ms: i64, tz_offset_s: i32) -> bool {
        self.at_ms(now_ms, tz_offset_s) >= now_ms + MIN_LEAD_MS
    }
}

// ---- pure: the strip ----------------------------------------------------------

/// Which conversation a strip belongs to.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StripTarget {
    Sms(Vec<String>),
    WhatsApp(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StripKey {
    /// An item of the local queue.
    Local(u64),
    /// A gateway item (its id).
    Gateway(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StripItem {
    pub key: StripKey,
    pub at_ms: i64,
    pub body: String,
    /// The gateway tried and failed; the item stays so the user sees it.
    pub failed: bool,
}

fn same_address(a: &str, b: &str) -> bool {
    match (PhoneNumber::parse(a), PhoneNumber::parse(b)) {
        (Ok(a), Ok(b)) => a.matches(&b),
        _ => a.trim().eq_ignore_ascii_case(b.trim()),
    }
}

/// The waiting items of one conversation, soonest first: the local queue's
/// SMS to its recipient and the gateway's pending (or failed) texts to its
/// chat. Sent gateway items are history, not waiting.
pub fn strip_items(
    target: &StripTarget,
    local: &[ScheduledSms],
    remote: &[ScheduledText],
) -> Vec<StripItem> {
    let mut items: Vec<StripItem> = match target {
        StripTarget::Sms(recipients) => local
            .iter()
            .filter(|i| {
                matches!(&i.target, ScheduleTarget::Sms { to, .. }
                    if recipients.iter().any(|r| same_address(r, to)))
            })
            .map(|i| StripItem {
                key: StripKey::Local(i.id),
                at_ms: i.at_ms,
                body: i.body.clone(),
                failed: false,
            })
            .collect(),
        StripTarget::WhatsApp(chat) => remote
            .iter()
            .filter(|t| t.chat == *chat && !matches!(t.state, ScheduledState::Sent { .. }))
            .map(|t| StripItem {
                key: StripKey::Gateway(t.id.clone()),
                at_ms: t.send_at_ms,
                body: t.text.clone(),
                failed: matches!(t.state, ScheduledState::Failed { .. }),
            })
            .collect(),
    };
    items.sort_by_key(|i| i.at_ms);
    items
}

/// Where an overview entry goes.
#[derive(Clone, Debug, PartialEq, Eq)]
#[cfg_attr(not(test), allow(dead_code))]
pub enum EntryTarget {
    Sms(String),
    WhatsApp(String),
}

/// One waiting (or failed) scheduled message in the overview.
#[derive(Clone, Debug, PartialEq, Eq)]
#[cfg_attr(not(test), allow(dead_code))]
pub struct ScheduledEntry {
    pub key: StripKey,
    pub target: EntryTarget,
    pub at_ms: i64,
    pub body: String,
    pub failed: bool,
}

/// Everything scheduled, both queues together: failed first, then soonest.
/// Sent gateway items are history and left out.
#[cfg_attr(not(test), allow(dead_code))]
pub fn overview(local: &[ScheduledSms], remote: &[ScheduledText]) -> Vec<ScheduledEntry> {
    let mut entries: Vec<ScheduledEntry> = local
        .iter()
        .map(|i| ScheduledEntry {
            key: StripKey::Local(i.id),
            target: match &i.target {
                ScheduleTarget::Sms { to, .. } => EntryTarget::Sms(to.clone()),
                ScheduleTarget::WhatsApp { chat } => EntryTarget::WhatsApp(chat.clone()),
            },
            at_ms: i.at_ms,
            body: i.body.clone(),
            failed: false,
        })
        .chain(
            remote
                .iter()
                .filter(|t| !matches!(t.state, ScheduledState::Sent { .. }))
                .map(|t| ScheduledEntry {
                    key: StripKey::Gateway(t.id.clone()),
                    target: EntryTarget::WhatsApp(t.chat.clone()),
                    at_ms: t.send_at_ms,
                    body: t.text.clone(),
                    failed: matches!(t.state, ScheduledState::Failed { .. }),
                }),
        )
        .collect();
    entries.sort_by_key(|e| (!e.failed, e.at_ms));
    entries
}

/// The overview row's subtitle.
#[cfg_attr(not(test), allow(dead_code))]
pub fn overview_subtitle(entries: &[ScheduledEntry]) -> String {
    let failed = entries.iter().filter(|e| e.failed).count();
    let waiting = entries.len() - failed;
    match (waiting, failed) {
        (0, 0) => String::new(),
        (w, 0) => format!("{w} waiting"),
        (0, f) => format!("{f} couldn't be sent"),
        (w, f) => format!("{w} waiting, {f} couldn't be sent"),
    }
}

/// True when `after` is failed and `before` was not (unknown counts as not failed).
#[cfg_attr(not(test), allow(dead_code))]
pub fn newly_failed(before: Option<&ScheduledText>, after: &ScheduledText) -> bool {
    matches!(after.state, ScheduledState::Failed { .. })
        && !before.is_some_and(|b| matches!(b.state, ScheduledState::Failed { .. }))
}

/// The pill when a gateway-scheduled WhatsApp message failed.
#[cfg_attr(not(test), allow(dead_code))]
pub fn remote_failed_text(title: &str) -> String {
    format!(
        "Scheduled WhatsApp message to {} couldn't be sent",
        title.trim()
    )
}

/// Shown when Send later is chosen with an empty composer.
#[cfg_attr(not(test), allow(dead_code))]
pub const TYPE_FIRST: &str = "Type the message first, then choose Send later";

/// The rows the strip shows: the first few, or all when expanded.
pub fn visible_items(items: &[StripItem], expanded: bool) -> &[StripItem] {
    if expanded {
        items
    } else {
        &items[..items.len().min(STRIP_ROWS)]
    }
}

/// The expander's label for `hidden` more rows.
pub fn more_label(hidden: usize) -> String {
    format!("Show {hidden} more")
}

/// The text to put in the composer after cancelling a scheduled message: its
/// body, but never over what the user is typing.
pub fn text_after_cancel(composer: &str, body: &str) -> Option<String> {
    composer.trim().is_empty().then(|| body.to_owned())
}

/// Puts `item` into the gateway list, replacing the same id.
pub fn upsert_remote(list: &mut Vec<ScheduledText>, item: ScheduledText) {
    match list.iter_mut().find(|t| t.id == item.id) {
        Some(slot) => *slot = item,
        None => list.push(item),
    }
}

/// A one-line preview of the text being scheduled.
pub fn preview(body: &str) -> String {
    const MAX: usize = 60;
    let line = body.split_whitespace().collect::<Vec<_>>().join(" ");
    if line.chars().count() <= MAX {
        return line;
    }
    let cut: String = line.chars().take(MAX).collect();
    format!("{cut}…")
}

// ---- GPUI: the sheet ----------------------------------------------------------

/// The "Send later" sheet of [`MessagesApp`].
pub struct ScheduleSheet {
    pub sheet: Sheet,
    draft: Option<Draft>,
    custom: Entity<TextField>,
    error: Option<SharedString>,
    /// The -/+ pick; `None` until touched, then [`CustomPick::initial`] is shown.
    pick: Option<CustomPick>,
    /// Tappable hint that Android may deliver an SMS late.
    late_note: Option<&'static str>,
}

impl ScheduleSheet {
    pub fn new(cx: &mut Context<MessagesApp>) -> Self {
        let custom =
            cx.new(|cx| TextField::with_input(TextInputPurpose::Text, TextInputAction::Done, cx));
        // Typing redraws the field box.
        cx.observe(&custom, |_, _, cx| cx.notify()).detach();
        Self {
            sheet: Sheet::new(),
            draft: None,
            custom,
            error: None,
            pick: None,
            late_note: None,
        }
    }

    pub fn set_late_note(&mut self, note: Option<&'static str>) {
        self.late_note = note;
    }

    pub fn open(&mut self, draft: Draft, cx: &mut Context<MessagesApp>) {
        self.draft = Some(draft);
        self.error = None;
        self.pick = None;
        self.custom.update(cx, |field, cx| field.clear(cx));
        self.sheet.open();
    }

    pub fn close(&mut self) {
        self.sheet.close();
    }

    pub fn is_open(&self) -> bool {
        self.sheet.is_open()
    }

    pub fn draft(&self) -> Option<&Draft> {
        self.draft.as_ref()
    }

    pub fn custom_text(&self, cx: &gpui::App) -> String {
        self.custom.read(cx).text().to_owned()
    }

    /// The pick shown now: the touched one, else the next free quarter hour.
    pub fn pick(&self, now_ms: i64, tz_offset_s: i32) -> CustomPick {
        self.pick
            .unwrap_or_else(|| CustomPick::initial(now_ms, tz_offset_s))
    }

    pub fn step_pick(&mut self, now_ms: i64, tz_offset_s: i32, days: i32, quarters: i32) {
        self.error = None;
        self.pick = Some(
            self.pick(now_ms, tz_offset_s)
                .step_day(days)
                .step_minutes(quarters),
        );
    }

    pub fn set_error(&mut self, error: impl Into<SharedString>) {
        self.error = Some(error.into());
    }

    /// The sheet over the page, or `None` once it is gone.
    pub fn render(
        &self,
        theme: Theme,
        window: &Window,
        now_ms: i64,
        tz_offset_s: i32,
        cx: &mut Context<MessagesApp>,
    ) -> Option<AnyElement> {
        let draft = self.draft.as_ref()?;
        let colors = theme::colors(theme);
        // The sheet sits on the screen's bottom: lift the custom-time field
        // above the keyboard while it types.
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
        let mut body = div().flex().flex_col().pb(px(12.0) + keyboard).child(
            div()
                .px(px(20.0))
                .pb(px(8.0))
                .flex()
                .flex_col()
                .child(
                    div()
                        .text_size(typography::TITLE3.size)
                        .font_weight(typography::TITLE3.weight)
                        .text_color(colors.label)
                        .child("Send later"),
                )
                .child(
                    div()
                        .truncate()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.secondary_label)
                        .child(preview(&draft.body)),
                ),
        );
        for (index, (label, at_ms)) in schedule::quick_times(now_ms, tz_offset_s)
            .into_iter()
            .enumerate()
        {
            body = body.child(
                list_row(
                    theme,
                    None,
                    label,
                    Some(when_label(at_ms, now_ms, tz_offset_s).into()),
                    None,
                )
                .id(("sched-quick", index))
                .on_click(cx.listener(move |this, _, _, cx| this.schedule_at(at_ms, cx))),
            );
        }
        let pick = self.pick(now_ms, tz_offset_s);
        let stepper = |id: &'static str, label: String, days: i32, quarters: i32| {
            let button = |sign: &'static str, dir: i32, tag: &'static str| {
                glass_pill(theme, sign)
                    .id((tag, usize::from(id == "day")))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let offset = this.backend.read(cx).locale().tz_offset_s;
                        this.schedule.step_pick(
                            crate::now_ms(),
                            offset,
                            days * dir,
                            quarters * dir,
                        );
                        cx.notify();
                    }))
            };
            div()
                .flex()
                .items_center()
                .justify_between()
                .child(button("\u{2212}", -1, "sched-minus"))
                .child(
                    div()
                        .text_size(typography::BODY.size)
                        .text_color(colors.label)
                        .child(label),
                )
                .child(button("+", 1, "sched-plus"))
        };
        body = body.child(
            div()
                .px(px(16.0))
                .pt(px(10.0))
                .flex()
                .flex_col()
                .gap(px(8.0))
                .child(stepper("day", pick.day_label(now_ms, tz_offset_s), 1, 0))
                .child(stepper("time", pick.time_label(now_ms, tz_offset_s), 0, 1))
                .child(
                    glass_pill(
                        theme,
                        format!("Schedule {}", pick.label(now_ms, tz_offset_s)),
                    )
                    .id("sched-pick-go")
                    .on_click(cx.listener(|this, _, _, cx| this.schedule_pick(cx))),
                ),
        );
        let custom = div()
            .px(px(16.0))
            .pt(px(10.0))
            .flex()
            .items_center()
            .gap(px(8.0))
            .child(div().flex_1().min_w_0().child(field_box(
                theme,
                "sched-custom",
                &self.custom,
                "18:30 or 2026-12-31 09:00",
                window,
                cx,
            )))
            .child(
                glass_pill(theme, "Schedule")
                    .id("sched-custom-go")
                    .on_click(cx.listener(|this, _, _, cx| this.schedule_custom(cx))),
            );
        body = body.child(custom);
        if let Some(error) = self.error.clone() {
            body = body.child(
                div()
                    .px(px(20.0))
                    .pt(px(6.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.red)
                    .child(error),
            );
        }
        if let Some(note) = self.late_note {
            body = body.child(
                div()
                    .id("sched-late-note")
                    .px(px(20.0))
                    .pt(px(8.0))
                    .flex()
                    .items_center()
                    .gap(px(6.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(
                        gpui::svg()
                            .path(rovsti_ui::Icon::LucideAlarmClock.path())
                            .size(px(14.0))
                            .text_color(colors.secondary_label),
                    )
                    .child(note)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.backend
                            .update(cx, |backend, cx| backend.open_app_settings(cx));
                    })),
            );
        }
        let entity = cx.entity();
        let drag = entity.clone();
        self.sheet.render(
            theme,
            window,
            "Send later",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_schedule(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.schedule.sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }
}

/// The Scheduled strip above a composer: what still waits, each with Cancel.
pub fn strip<V: 'static>(
    theme: Theme,
    items: &[StripItem],
    expanded: bool,
    (now_ms, tz_offset_s): (i64, i32),
    cx: &mut Context<V>,
    on_cancel: fn(&mut V, StripKey, &mut Context<V>),
    on_more: fn(&mut V, &mut Context<V>),
) -> Option<AnyElement> {
    if items.is_empty() {
        return None;
    }
    let colors = theme::colors(theme);
    let mut out = div()
        .flex_none()
        .flex()
        .flex_col()
        .gap(px(4.0))
        .px(px(16.0))
        .pb(px(4.0))
        .child(
            div()
                .text_size(typography::CAPTION.size)
                .text_color(colors.secondary_label)
                .child("Scheduled"),
        );
    for (index, item) in visible_items(items, expanded).iter().enumerate() {
        let key = item.key.clone();
        let when = when_label(item.at_ms, now_ms, tz_offset_s);
        let line = if item.failed {
            format!("Not sent · {}", preview(&item.body))
        } else {
            format!("{when} · {}", preview(&item.body))
        };
        out = out.child(
            div()
                .flex()
                .items_center()
                .gap(px(8.0))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .truncate()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(if item.failed {
                            colors.red
                        } else {
                            colors.label
                        })
                        .child(line),
                )
                .child(
                    glass_pill(theme, "Cancel")
                        .id(("sched-cancel", index))
                        .h(px(30.0))
                        .px(px(12.0))
                        .text_size(typography::FOOTNOTE.size)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            on_cancel(this, key.clone(), cx);
                        })),
                ),
        );
    }
    if !expanded && items.len() > STRIP_ROWS {
        out = out.child(
            div()
                .id("sched-more")
                .text_size(typography::CAPTION.size)
                .text_color(colors.accent)
                .child(more_label(items.len() - STRIP_ROWS))
                .on_click(cx.listener(move |this, _, _, cx| on_more(this, cx))),
        );
    }
    Some(out.into_any_element())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strip_item(n: i64) -> StripItem {
        StripItem {
            key: StripKey::Local(n as u64),
            at_ms: n,
            body: format!("m{n}"),
            failed: false,
        }
    }

    fn sched(id: u64, at_ms: i64, target: ScheduleTarget) -> ScheduledSms {
        ScheduledSms {
            id,
            at_ms,
            target,
            body: format!("b{id}"),
            created_ms: 0,
        }
    }

    fn sms_to(to: &str) -> ScheduleTarget {
        ScheduleTarget::Sms {
            to: to.into(),
            sub_id: None,
        }
    }

    #[test]
    fn failed_notice_names_one_and_counts_many() {
        assert_eq!(failed_notice(&[]), None);
        let one = failed_notice(&[sched(1, 5, sms_to("+90532"))]).expect("notice");
        assert_eq!(one.text, "Scheduled message to +90532 couldn't be sent");
        assert_eq!(one.reopen, Some(("+90532".into(), "b1".into())));
        let wa = ScheduleTarget::WhatsApp {
            chat: "90532@s.whatsapp.net".into(),
        };
        let only_wa = failed_notice(&[sched(2, 5, wa.clone())]).expect("notice");
        assert_eq!(only_wa.reopen, None);
        let many = failed_notice(&[
            sched(1, 5, sms_to("+1")),
            sched(2, 9, sms_to("+2")),
            sched(3, 12, wa),
        ])
        .expect("notice");
        assert_eq!(many.text, "3 scheduled messages couldn't be sent");
        assert_eq!(many.reopen, Some(("+2".into(), "b2".into())));
    }

    #[test]
    fn late_note_only_for_sms_without_exact_alarms() {
        assert_eq!(late_note(true, Some(false)), Some(LATE_NOTE));
        assert_eq!(late_note(true, Some(true)), None);
        assert_eq!(late_note(true, None), None);
        assert_eq!(late_note(false, Some(false)), None);
    }

    #[test]
    fn the_strip_collapses_to_a_few_rows() {
        let items: Vec<_> = (0..5).map(strip_item).collect();
        assert_eq!(visible_items(&items, false).len(), STRIP_ROWS);
        assert_eq!(visible_items(&items, true).len(), 5);
        assert_eq!(visible_items(&items[..2], false).len(), 2);
        assert!(visible_items(&[], false).is_empty());
        assert_eq!(more_label(items.len() - STRIP_ROWS), "Show 2 more");
    }

    #[test]
    fn cancel_never_overwrites_the_composer() {
        assert_eq!(text_after_cancel("", "hi").as_deref(), Some("hi"));
        assert_eq!(text_after_cancel("  \n", "hi").as_deref(), Some("hi"));
        assert_eq!(text_after_cancel("typing", "hi"), None);
    }

    /// 2026-11-05 12:00:00 UTC.
    const NOW: i64 = 1_793_880_000_000;

    fn sms_item(id: u64, at_ms: i64, to: &str) -> ScheduledSms {
        ScheduledSms {
            id,
            at_ms,
            target: ScheduleTarget::Sms {
                to: to.into(),
                sub_id: Some(2),
            },
            body: "hi".into(),
            created_ms: NOW,
        }
    }

    fn remote(id: &str, chat: &str, at_ms: i64, state: ScheduledState) -> ScheduledText {
        ScheduledText {
            id: id.into(),
            chat: chat.into(),
            text: "later".into(),
            send_at_ms: at_ms,
            created_ms: NOW,
            state,
        }
    }

    #[test]
    fn custom_time_forms() {
        // 12:00 UTC now, zone +03:00 (15:00 local).
        let tz = 3 * 3600;
        let today_1800 = parse_custom("18:00", NOW, tz).expect("ok");
        assert_eq!(today_1800, NOW + 3 * 3_600_000);
        // 09:00 has passed locally: tomorrow.
        let tomorrow_0900 = parse_custom(" 9:00 ", NOW, tz).expect("ok");
        assert_eq!(tomorrow_0900, NOW + 18 * 3_600_000);
        assert_eq!(
            parse_custom("2026-11-06 09:00", NOW, tz).expect("ok"),
            tomorrow_0900
        );
        // Under a minute away today rolls to tomorrow; a past date is refused.
        assert_eq!(
            parse_custom("15:00", NOW, tz).expect("ok"),
            NOW + 24 * 3_600_000
        );
        assert!(parse_custom("2026-11-05 15:00", NOW, tz).is_err());
        assert!(parse_custom("2026-11-05 14:00", NOW, tz).is_err());
    }

    #[test]
    fn custom_time_rejects_nonsense() {
        for bad in [
            "",
            "soon",
            "25:00",
            "12:60",
            "2026-02-30 10:00",
            "2026-13-01 10:00",
            "2026-11 10:00",
            "2026-11-06-01 10:00",
            "10",
        ] {
            assert!(parse_custom(bad, NOW, 0).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn dates_convert_both_ways() {
        for days in [-1, 0, 59, 60, 11_000, 20_397, 20_500] {
            let (y, m, d) = civil_from_days(days);
            assert_eq!(
                days_from_civil(y, i64::try_from(m).expect("ok"), d),
                Some(days)
            );
        }
        assert_eq!(days_from_civil(2024, 2, 29), Some(19_782));
        assert_eq!(days_from_civil(2025, 2, 29), None);
    }

    #[test]
    fn labels_name_the_day() {
        let tz = 3 * 3600;
        assert_eq!(when_label(NOW + 3 * 3_600_000, NOW, tz), "Today 18:00");
        assert_eq!(when_label(NOW + 18 * 3_600_000, NOW, tz), "Tomorrow 09:00");
        assert_eq!(when_label(NOW + 7 * 24 * 3_600_000, NOW, 0), "12 Nov 12:00");
    }

    #[test]
    fn ids_never_collide_with_the_queue() {
        assert_eq!(next_schedule_id(&[], 500), 500);
        assert_eq!(next_schedule_id(&[sms_item(900, 1, "+1")], 500), 901);
        assert_eq!(next_schedule_id(&[sms_item(9, 1, "+1")], 500), 500);
        assert_eq!(next_schedule_id(&[], -5), 0);
    }

    #[test]
    fn arm_payload_is_sms_only() {
        let json = arm_json(&sms_item(4, 99, "+905321234567")).expect("ok");
        let v: serde_json::Value = serde_json::from_str(&json).expect("ok");
        assert_eq!(v["id"], 4);
        assert_eq!(v["at_ms"], 99);
        assert_eq!(v["to"], "+905321234567");
        assert_eq!(v["body"], "hi");
        assert_eq!(v["sub_id"], 2);
        let wa = ScheduledSms {
            target: ScheduleTarget::WhatsApp {
                chat: "a@s.whatsapp.net".into(),
            },
            ..sms_item(5, 1, "")
        };
        assert_eq!(arm_json(&wa), None);
    }

    #[test]
    fn fired_list_parses_and_survives_junk() {
        let fired = parse_fired(r#"[{"id":3,"ok":true,"at_ms":9},{"id":4,"ok":false,"at_ms":10}]"#);
        assert_eq!(fired.len(), 2);
        assert!(fired[0].ok && !fired[1].ok);
        assert!(parse_fired("nope").is_empty());
        assert!(parse_fired("[]").is_empty());
    }

    #[test]
    fn strip_lists_this_conversations_waiting_items() {
        let local = [
            sms_item(2, NOW + 2000, "0532 123 45 67"),
            sms_item(1, NOW + 1000, "+90 532 123 45 67"),
            sms_item(3, NOW + 500, "+14155550100"),
        ];
        let items = strip_items(&StripTarget::Sms(vec!["+905321234567".into()]), &local, &[]);
        let keys: Vec<_> = items.iter().map(|i| i.key.clone()).collect();
        assert_eq!(keys, vec![StripKey::Local(1), StripKey::Local(2)]);

        let chat = "90532@s.whatsapp.net";
        let list = vec![
            remote("a", chat, NOW + 20, ScheduledState::Pending),
            remote(
                "b",
                chat,
                NOW + 10,
                ScheduledState::Failed { reason: "x".into() },
            ),
            remote(
                "c",
                chat,
                NOW + 5,
                ScheduledState::Sent {
                    message_id: "M".into(),
                },
            ),
            remote("d", "other@s.whatsapp.net", NOW, ScheduledState::Pending),
        ];
        let items = strip_items(&StripTarget::WhatsApp(chat.into()), &local, &list);
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].key, StripKey::Gateway("b".into()));
        assert!(items[0].failed && !items[1].failed);
    }

    #[test]
    fn upsert_replaces_by_id() {
        let mut list = vec![remote("a", "c", 1, ScheduledState::Pending)];
        upsert_remote(
            &mut list,
            remote(
                "a",
                "c",
                1,
                ScheduledState::Sent {
                    message_id: "M".into(),
                },
            ),
        );
        upsert_remote(&mut list, remote("b", "c", 2, ScheduledState::Pending));
        assert_eq!(list.len(), 2);
        assert!(matches!(list[0].state, ScheduledState::Sent { .. }));
    }

    #[test]
    fn preview_collapses_and_cuts() {
        assert_eq!(preview("a\n  b"), "a b");
        let long = "x".repeat(100);
        assert_eq!(preview(&long).chars().count(), 61);
        assert!(preview(&long).ends_with('…'));
    }

    /// A UTC midnight, then `h:m` after it.
    fn at_utc(h: i64, m: i64) -> i64 {
        20_000 * 86_400_000 + (h * 60 + m) * 60_000 // 2024-10-04
    }

    #[test]
    fn pick_starts_on_the_next_free_quarter_hour() {
        let p = CustomPick::initial(at_utc(10, 7), 0);
        assert_eq!((p.day, p.minutes), (0, 10 * 60 + 30));
        // Exactly on a quarter still leaves 15 minutes.
        assert_eq!(CustomPick::initial(at_utc(10, 0), 0).minutes, 10 * 60 + 15);
        // Late evening rolls to tomorrow.
        let p = CustomPick::initial(at_utc(23, 50), 0);
        assert_eq!((p.day, p.minutes), (1, 15));
        // The zone shifts the local day.
        let p = CustomPick::initial(at_utc(22, 0), 3 * 3600);
        assert_eq!((p.day, p.minutes), (0, 60 + 15));
    }

    #[test]
    fn pick_steps_wrap_and_clamp() {
        let p = CustomPick {
            day: 0,
            minutes: 23 * 60 + 45,
        };
        assert_eq!(p.step_minutes(1), CustomPick { day: 0, minutes: 0 });
        assert_eq!(p.step_minutes(-1).minutes, 23 * 60 + 30);
        assert_eq!(
            CustomPick { day: 0, minutes: 0 }.step_minutes(-1).minutes,
            23 * 60 + 45
        );
        assert_eq!(CustomPick { day: 0, minutes: 0 }.step_day(-1).day, 0);
        let far = CustomPick {
            day: CustomPick::MAX_DAY,
            minutes: 0,
        };
        assert_eq!(far.step_day(1).day, CustomPick::MAX_DAY);
        assert_eq!(far.step_day(-1).day, CustomPick::MAX_DAY - 1);
    }

    #[test]
    fn pick_matches_the_typed_form() {
        let tz = 3 * 3600;
        let now = at_utc(6, 0); // 09:00 local
        let pick = CustomPick {
            day: 0,
            minutes: 18 * 60 + 30,
        };
        assert_eq!(
            pick.at_ms(now, tz),
            parse_custom("18:30", now, tz).expect("time")
        );
        let next = pick.step_day(1);
        assert_eq!(
            next.at_ms(now, tz),
            parse_custom("2024-10-05 18:30", now, tz).expect("time")
        );
        assert_eq!(pick.label(now, tz), "Today 18:30");
        assert_eq!(next.label(now, tz), "Tomorrow 18:30");
        assert_eq!(next.day_label(now, tz), "Tomorrow");
        assert_eq!(next.time_label(now, tz), "18:30");
    }

    #[test]
    fn pick_in_the_past_is_not_schedulable() {
        let now = at_utc(10, 7);
        let early = CustomPick {
            day: 0,
            minutes: 60,
        };
        assert!(!early.is_schedulable(now, 0));
        assert!(early.step_day(1).is_schedulable(now, 0));
        assert!(CustomPick::initial(now, 0).is_schedulable(now, 0));
    }

    #[test]
    fn overview_lists_both_queues_failed_first() {
        let local = [
            sms_item(1, NOW + 3000, "+905321234567"),
            sms_item(2, NOW + 1000, "+14155550100"),
        ];
        let list = vec![
            remote("a", "a@s.whatsapp.net", NOW + 2000, ScheduledState::Pending),
            remote(
                "b",
                "b@s.whatsapp.net",
                NOW + 9000,
                ScheduledState::Failed { reason: "x".into() },
            ),
        ];
        let entries = overview(&local, &list);
        let keys: Vec<_> = entries.iter().map(|e| e.key.clone()).collect();
        assert_eq!(
            keys,
            vec![
                StripKey::Gateway("b".into()),
                StripKey::Local(2),
                StripKey::Gateway("a".into()),
                StripKey::Local(1),
            ]
        );
        assert!(entries[0].failed && !entries[1].failed);
        assert_eq!(entries[1].target, EntryTarget::Sms("+14155550100".into()));
        assert_eq!(
            entries[2].target,
            EntryTarget::WhatsApp("a@s.whatsapp.net".into())
        );
    }

    #[test]
    fn overview_skips_sent_gateway_items() {
        let list = vec![remote(
            "c",
            "c@s.whatsapp.net",
            NOW,
            ScheduledState::Sent {
                message_id: "M".into(),
            },
        )];
        assert!(overview(&[], &list).is_empty());
    }

    #[test]
    fn overview_subtitles_count() {
        let entry = |failed| ScheduledEntry {
            key: StripKey::Local(1),
            target: EntryTarget::Sms("1".into()),
            at_ms: 0,
            body: String::new(),
            failed,
        };
        assert_eq!(overview_subtitle(&[entry(false)]), "1 waiting");
        assert_eq!(
            overview_subtitle(&[entry(false), entry(false), entry(false)]),
            "3 waiting"
        );
        assert_eq!(
            overview_subtitle(&[entry(false), entry(false), entry(true)]),
            "2 waiting, 1 couldn't be sent"
        );
        assert_eq!(overview_subtitle(&[entry(true)]), "1 couldn't be sent");
    }

    #[test]
    fn newly_failed_only_on_the_transition() {
        let pending = remote("a", "c", 1, ScheduledState::Pending);
        let failed = remote("a", "c", 1, ScheduledState::Failed { reason: "x".into() });
        let sent = remote(
            "a",
            "c",
            1,
            ScheduledState::Sent {
                message_id: "M".into(),
            },
        );
        assert!(newly_failed(Some(&pending), &failed));
        assert!(newly_failed(None, &failed));
        assert!(!newly_failed(Some(&failed), &failed));
        assert!(!newly_failed(Some(&pending), &sent));
        assert!(!newly_failed(None, &pending));
        assert_eq!(
            remote_failed_text(" Ayse "),
            "Scheduled WhatsApp message to Ayse couldn't be sent"
        );
        assert!(TYPE_FIRST.starts_with("Type the message first"));
    }
}
