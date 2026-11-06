//! Call statistics: totals, per-day and per-week talk time and top people.
//!
//! Both the system call log and the app's own WhatsApp history feed one
//! [`StatCall`] list, so every number is computed by the same code.

use std::collections::HashMap;

use crate::{
    call_log::civil_from_days,
    phone_number::PhoneNumber,
    recents::ExtraCall,
    wire::{CallLogEntry, CallLogKind},
};

/// The most calls `top_people` looks at.
const MAX_CALLS: usize = 5000;
const DAY_S: i64 = 86_400;
const WEEKDAYS: [&str; 7] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StatDir {
    Incoming,
    Outgoing,
    Missed,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StatCall {
    /// The number as stored (raw).
    pub number: String,
    pub name: Option<String>,
    pub date_ms: i64,
    pub duration_s: u64,
    pub dir: StatDir,
    pub whatsapp: bool,
}

/// Merges the call log and the extra (WhatsApp) calls. Blocked and unknown
/// entries are dropped; rejected and voicemail count as missed.
pub fn from_sources(entries: &[CallLogEntry], extras: &[ExtraCall]) -> Vec<StatCall> {
    let from_log = entries.iter().filter_map(|e| {
        let dir = match e.kind {
            CallLogKind::Incoming => StatDir::Incoming,
            CallLogKind::Outgoing => StatDir::Outgoing,
            CallLogKind::Missed | CallLogKind::Rejected | CallLogKind::Voicemail => StatDir::Missed,
            CallLogKind::Blocked | CallLogKind::Unknown => return None,
        };
        let number = if e.number_raw.is_empty() {
            &e.normalized
        } else {
            &e.number_raw
        };
        Some(StatCall {
            number: number.clone(),
            name: e.cached_name.clone().filter(|n| !n.is_empty()),
            date_ms: e.date_ms,
            duration_s: u64::try_from(e.duration_s).unwrap_or(0),
            dir,
            whatsapp: false,
        })
    });
    let from_extras = extras.iter().map(|x| StatCall {
        number: x.number_raw.clone(),
        name: x.label.clone().filter(|n| !n.is_empty()),
        date_ms: x.started_ms,
        duration_s: x.duration_s,
        dir: if x.missed {
            StatDir::Missed
        } else if x.outgoing {
            StatDir::Outgoing
        } else {
            StatDir::Incoming
        },
        whatsapp: true,
    });
    from_log.chain(from_extras).collect()
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Totals {
    pub calls: u32,
    pub incoming: u32,
    pub outgoing: u32,
    pub missed: u32,
    /// Talk time of answered calls only.
    pub talk_s: u64,
    pub longest_s: u64,
}

impl Totals {
    fn add(&mut self, c: &StatCall) {
        self.calls += 1;
        match c.dir {
            StatDir::Incoming => self.incoming += 1,
            StatDir::Outgoing => self.outgoing += 1,
            StatDir::Missed => self.missed += 1,
        }
        if c.dir != StatDir::Missed {
            self.talk_s += c.duration_s;
            self.longest_s = self.longest_s.max(c.duration_s);
        }
    }
}

fn digits_key(raw: &str) -> String {
    raw.chars()
        .filter(|c| c.is_ascii_digit() || *c == '+')
        .collect()
}

/// Totals over the calls whose number matches any of `numbers`.
pub fn for_numbers(calls: &[StatCall], numbers: &[&str]) -> Totals {
    let wanted: Vec<(Option<PhoneNumber>, String)> = numbers
        .iter()
        .map(|n| (PhoneNumber::parse(n).ok(), digits_key(n)))
        .filter(|(_, k)| !k.is_empty())
        .collect();
    let mut totals = Totals::default();
    for c in calls {
        let parsed = PhoneNumber::parse(&c.number).ok();
        let key = digits_key(&c.number);
        let hit = wanted.iter().any(|(p, k)| match (p, &parsed) {
            (Some(a), Some(b)) => a.matches(b),
            _ => !key.is_empty() && *k == key,
        });
        if hit {
            totals.add(c);
        }
    }
    totals
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DayBar {
    pub day_start_ms: i64,
    pub label: String,
    pub talk_s: u64,
    pub calls: u32,
}

/// Local day number (days since 1970-01-01) of a timestamp.
fn local_day(ms: i64, tz_offset_s: i32) -> i64 {
    (ms.div_euclid(1000) + i64::from(tz_offset_s)).div_euclid(DAY_S)
}

/// 0 = Monday.
fn weekday(day: i64) -> usize {
    // 1970-01-01 was a Thursday.
    (day + 3).rem_euclid(7) as usize
}

/// Buckets of `span` days ending at the one that holds `last_start`; oldest first.
fn buckets(
    calls: &[StatCall],
    tz_offset_s: i32,
    first_start: i64,
    span: i64,
    n: usize,
    label: impl Fn(i64) -> String,
) -> Vec<DayBar> {
    let mut bars: Vec<DayBar> = (0..n as i64)
        .map(|i| {
            let start = first_start + i * span;
            DayBar {
                day_start_ms: (start * DAY_S - i64::from(tz_offset_s)) * 1000,
                label: label(start),
                talk_s: 0,
                calls: 0,
            }
        })
        .collect();
    for c in calls {
        let idx = (local_day(c.date_ms, tz_offset_s) - first_start).div_euclid(span);
        if let Some(bar) = usize::try_from(idx).ok().and_then(|i| bars.get_mut(i)) {
            bar.calls += 1;
            if c.dir != StatDir::Missed {
                bar.talk_s += c.duration_s;
            }
        }
    }
    bars
}

/// `n` local days, oldest first, today last. Always `n` bars.
pub fn last_days(calls: &[StatCall], now_ms: i64, tz_offset_s: i32, n: usize) -> Vec<DayBar> {
    let today = local_day(now_ms, tz_offset_s);
    buckets(calls, tz_offset_s, today - n as i64 + 1, 1, n, |d| {
        WEEKDAYS[weekday(d)].to_owned()
    })
}

/// `n` Monday-based local weeks, oldest first, the current week last.
pub fn last_weeks(calls: &[StatCall], now_ms: i64, tz_offset_s: i32, n: usize) -> Vec<DayBar> {
    let today = local_day(now_ms, tz_offset_s);
    let this_monday = today - weekday(today) as i64;
    buckets(
        calls,
        tz_offset_s,
        this_monday - 7 * (n as i64 - 1),
        7,
        n,
        |d| {
            let (_, month, day) = civil_from_days(d);
            format!("{day} {}", MONTHS[(month as usize - 1) % 12])
        },
    )
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PersonStat {
    /// The canonical number, the same for national and international forms.
    pub key: String,
    pub name: Option<String>,
    pub number: String,
    pub totals: Totals,
}

struct Group {
    parsed: Option<PhoneNumber>,
    stat: PersonStat,
    name_ms: i64,
}

/// People ranked by talk time since `since_ms`. Looks at the first
/// [`MAX_CALLS`] calls only.
pub fn top_people(calls: &[StatCall], since_ms: i64, limit: usize) -> Vec<PersonStat> {
    let mut groups: Vec<Group> = Vec::new();
    let mut by_key: HashMap<String, usize> = HashMap::new();
    for c in calls
        .iter()
        .take(MAX_CALLS)
        .filter(|c| c.date_ms >= since_ms)
    {
        let raw_key = digits_key(&c.number);
        if raw_key.is_empty() {
            continue;
        }
        let parsed = PhoneNumber::parse(&c.number).ok();
        let found = by_key.get(&raw_key).copied().or_else(|| {
            let p = parsed.as_ref()?;
            groups
                .iter()
                .position(|g| g.parsed.as_ref().is_some_and(|q| q.matches(p)))
        });
        let i = found.unwrap_or_else(|| {
            let key = parsed
                .as_ref()
                .map_or_else(|| raw_key.clone(), |p| p.as_str().to_owned());
            groups.push(Group {
                parsed: parsed.clone(),
                stat: PersonStat {
                    key,
                    name: None,
                    number: c.number.clone(),
                    totals: Totals::default(),
                },
                name_ms: i64::MIN,
            });
            groups.len() - 1
        });
        by_key.insert(raw_key, i);
        let g = &mut groups[i];
        // Prefer the international form as the shown key and number.
        if let Some(p) = parsed.as_ref().filter(|p| p.is_international())
            && !g.parsed.as_ref().is_some_and(PhoneNumber::is_international)
        {
            g.stat.key = p.as_str().to_owned();
            g.stat.number = c.number.clone();
            g.parsed = parsed.clone();
        }
        if c.name.is_some() && c.date_ms >= g.name_ms {
            g.stat.name = c.name.clone();
            g.name_ms = c.date_ms;
        }
        g.stat.totals.add(c);
    }
    let mut out: Vec<PersonStat> = groups.into_iter().map(|g| g.stat).collect();
    out.sort_by(|a, b| {
        b.totals
            .talk_s
            .cmp(&a.totals.talk_s)
            .then(b.totals.calls.cmp(&a.totals.calls))
            .then_with(|| a.key.cmp(&b.key))
    });
    out.truncate(limit);
    out
}

/// `0 min`, `45 s`, `12 min`, `1 h 05 min`, `26 h 10 min`.
pub fn talk_text(seconds: u64) -> String {
    match seconds {
        0 => "0 min".to_owned(),
        1..=59 => format!("{seconds} s"),
        60..=3599 => format!("{} min", seconds / 60),
        _ => format!("{} h {:02} min", seconds / 3600, seconds % 3600 / 60),
    }
}

fn calls_text(n: u32) -> String {
    if n == 1 {
        "1 call".to_owned()
    } else {
        format!("{n} calls")
    }
}

/// `12 calls · 1 h 05 min`.
pub fn summary_line(t: &Totals) -> String {
    format!("{} · {}", calls_text(t.calls), talk_text(t.talk_s))
}

/// Plain multi-line text for sharing one person's statistics.
pub fn share_text(name: &str, all: &Totals, week: &Totals) -> String {
    format!(
        "Calls with {name}\nAll time: {}\nIncoming {} · Outgoing {} · Missed {}\nLongest call: {}\nThis week: {}",
        summary_line(all),
        all.incoming,
        all.outgoing,
        all.missed,
        talk_text(all.longest_s),
        summary_line(week),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn call(number: &str, date_ms: i64, duration_s: u64, dir: StatDir) -> StatCall {
        StatCall {
            number: number.to_owned(),
            name: None,
            date_ms,
            duration_s,
            dir,
            whatsapp: false,
        }
    }

    fn entry(kind: CallLogKind, dur: i64) -> CallLogEntry {
        CallLogEntry {
            number_raw: "0555 111 22 33".into(),
            kind,
            date_ms: 1000,
            duration_s: dur,
            ..Default::default()
        }
    }

    fn extra(missed: bool, outgoing: bool) -> ExtraCall {
        ExtraCall {
            number_raw: "+905551112233".into(),
            started_ms: 5,
            duration_s: 20,
            missed,
            outgoing,
            label: Some("Zoe".into()),
            call_id: "w".into(),
        }
    }

    #[test]
    fn sources_skip_blocked_and_unknown_and_map_rejected_to_missed() {
        let entries = [
            entry(CallLogKind::Blocked, 0),
            entry(CallLogKind::Unknown, 5),
            entry(CallLogKind::Rejected, 0),
            entry(CallLogKind::Voicemail, 0),
            entry(CallLogKind::Incoming, 9),
        ];
        let out = from_sources(&entries, &[extra(false, true), extra(true, false)]);
        let dirs: Vec<_> = out.iter().map(|c| c.dir).collect();
        assert_eq!(
            dirs,
            [
                StatDir::Missed,
                StatDir::Missed,
                StatDir::Incoming,
                StatDir::Outgoing,
                StatDir::Missed
            ]
        );
        assert!(out[3].whatsapp && !out[0].whatsapp);
    }

    #[test]
    fn missed_duration_is_ignored() {
        let calls = [
            call("111", 0, 50, StatDir::Missed),
            call("111", 0, 30, StatDir::Incoming),
            call("111", 0, 70, StatDir::Outgoing),
        ];
        let t = for_numbers(&calls, &["111"]);
        assert_eq!((t.calls, t.incoming, t.outgoing, t.missed), (3, 1, 1, 1));
        assert_eq!((t.talk_s, t.longest_s), (100, 70));
    }

    #[test]
    fn national_and_international_forms_merge() {
        let calls = [
            call("0555 111 22 33", 10, 60, StatDir::Incoming),
            call("+905551112233", 20, 40, StatDir::Outgoing),
            call("0555 999 88 77", 30, 500, StatDir::Incoming),
        ];
        let t = for_numbers(&calls, &["+90 555 111 22 33"]);
        assert_eq!((t.calls, t.talk_s), (2, 100));
        let top = top_people(&calls, 0, 10);
        assert_eq!(top.len(), 2);
        assert_eq!(top[1].totals.calls, 2);
        assert_eq!(top[1].key, "+905551112233");
    }

    #[test]
    fn for_numbers_falls_back_to_plain_equality() {
        let calls = [call("*123#", 0, 5, StatDir::Incoming)];
        assert_eq!(for_numbers(&calls, &["*123#"]).calls, 1);
        assert_eq!(for_numbers(&calls, &["999"]).calls, 0);
    }

    const MON_2024_11_04: i64 = 1_730_678_400; // 2024-11-04 00:00 UTC, a Monday

    #[test]
    fn day_bars_respect_the_timezone() {
        let tz = 10_800;
        // 2024-11-04 23:30 local (UTC+3) is 20:30 UTC.
        let late = (MON_2024_11_04 + 20 * 3600 + 1800) * 1000;
        let now = (MON_2024_11_04 + 3600) * 1000; // 04:00 local Monday
        let calls = [call("1", late, 60, StatDir::Incoming)];
        let bars = last_days(&calls, now, tz, 7);
        assert_eq!(bars.len(), 7);
        assert_eq!(bars[6].label, "Mon");
        assert_eq!(bars[6].talk_s, 60);
        assert_eq!(bars[5].calls, 0);
        assert_eq!(bars[6].day_start_ms, (MON_2024_11_04 - 10_800) * 1000);
    }

    #[test]
    fn seven_bars_even_when_empty() {
        let bars = last_days(&[], 0, 0, 7);
        assert_eq!(bars.len(), 7);
        assert!(bars.iter().all(|b| b.calls == 0 && b.talk_s == 0));
    }

    #[test]
    fn weeks_start_on_monday_local() {
        let tz = 3600;
        let monday_start_local = (MON_2024_11_04 - 3600) * 1000; // Mon 00:00 local
        let sunday_before = monday_start_local - 1;
        let calls = [
            call("1", monday_start_local, 10, StatDir::Incoming),
            call("1", sunday_before, 20, StatDir::Incoming),
        ];
        let now = monday_start_local + 3 * 86_400_000; // Thursday
        let bars = last_weeks(&calls, now, tz, 3);
        assert_eq!(bars.len(), 3);
        assert_eq!(bars[2].label, "4 Nov");
        assert_eq!(bars[1].label, "28 Oct");
        assert_eq!((bars[2].talk_s, bars[1].talk_s), (10, 20));
        assert_eq!(bars[2].day_start_ms, monday_start_local);
    }

    #[test]
    fn talk_text_edges() {
        assert_eq!(talk_text(0), "0 min");
        assert_eq!(talk_text(45), "45 s");
        assert_eq!(talk_text(59), "59 s");
        assert_eq!(talk_text(60), "1 min");
        assert_eq!(talk_text(3599), "59 min");
        assert_eq!(talk_text(3600), "1 h 00 min");
        assert_eq!(talk_text(3900), "1 h 05 min");
        assert_eq!(talk_text(26 * 3600 + 600), "26 h 10 min");
    }

    #[test]
    fn summary_and_share_text() {
        let t = Totals {
            calls: 12,
            incoming: 5,
            outgoing: 4,
            missed: 3,
            talk_s: 3900,
            longest_s: 600,
        };
        assert_eq!(summary_line(&t), "12 calls · 1 h 05 min");
        assert_eq!(
            summary_line(&Totals {
                calls: 1,
                ..Default::default()
            }),
            "1 call · 0 min"
        );
        let s = share_text("Zoe", &t, &Totals::default());
        assert!(s.starts_with("Calls with Zoe\n"));
        assert!(s.contains("Longest call: 10 min"));
    }

    #[test]
    fn top_people_order_limit_and_names() {
        let mut named = call("222", 50, 10, StatDir::Incoming);
        named.name = Some("Bea".into());
        let mut older = call("222", 5, 10, StatDir::Incoming);
        older.name = Some("Old".into());
        let calls = [
            call("333", 10, 100, StatDir::Incoming),
            named,
            older,
            call("111", 10, 20, StatDir::Incoming),
            call("444", 10, 20, StatDir::Incoming),
            call("999", 1, 900, StatDir::Incoming),
        ];
        let top = top_people(&calls, 2, 10);
        let keys: Vec<_> = top.iter().map(|p| p.key.as_str()).collect();
        // Ties on 20 s: 222 has two calls and wins, then key order.
        assert_eq!(keys, ["333", "222", "111", "444"]);
        assert_eq!(top_people(&calls, 2, 2).len(), 2);
        assert_eq!(top[1].name.as_deref(), Some("Bea"));
        assert!(top.iter().all(|p| p.key != "999"));
    }
}
