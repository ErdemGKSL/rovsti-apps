//! Birthday and anniversary reminders: which local notifications to arm.
//!
//! [`plan`] turns the upcoming list into alarms (one per event, at a local
//! hour), and [`diff`] says what to disarm and arm given what is already armed.
//! Ids are stable across runs so re-planning never double-arms.

use serde::Serialize;

use crate::contact_dates::{self, Upcoming};
use crate::contact_edit::EventKind;

/// The default local hour of a reminder.
pub const DEFAULT_HOUR: u8 = 9;
/// How far ahead alarms are armed, in days.
pub const HORIZON_DAYS: u16 = 60;

/// One notification to arm.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct BirthdayAlarm {
    pub id: i64,
    pub contact_id: i64,
    /// When it fires, as a UTC timestamp in milliseconds.
    pub at_ms: i64,
    pub title: String,
    pub body: String,
}

fn fnv1a(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in bytes {
        h ^= u64::from(*b);
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

fn kind_key(kind: EventKind) -> &'static str {
    match kind {
        EventKind::Birthday => "b",
        EventKind::Anniversary => "a",
        EventKind::Custom => "c",
        EventKind::Other => "o",
    }
}

/// A stable positive id for one occurrence of an event: contact, kind, the
/// date and the year it falls in.
fn alarm_id(u: &Upcoming, year: i64) -> i64 {
    let key = format!(
        "{}|{}|{}|{year}",
        u.contact_id,
        kind_key(u.kind),
        u.date_text
    );
    i64::try_from(fnv1a(key.as_bytes()) >> 1).unwrap_or(i64::MAX)
}

/// The civil year of a day count since 1970-01-01.
fn year_of(day: i64) -> i64 {
    let z = day + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    yoe + era * 400 + i64::from(m <= 2)
}

fn first_name(name: &str) -> &str {
    name.split_whitespace().next().unwrap_or(name)
}

fn texts(u: &Upcoming) -> (String, String) {
    let who = first_name(&u.name);
    let age = contact_dates::turning_text(u);
    match u.kind {
        EventKind::Birthday => (
            format!("{who}'s birthday"),
            match u.turning.filter(|n| *n > 0) {
                Some(n) => format!("{who} turns {n} today"),
                None => format!("It is {who}'s birthday today"),
            },
        ),
        EventKind::Anniversary => (
            format!("{who}'s anniversary"),
            match age {
                Some(a) => format!("{who}'s anniversary today ({a})"),
                None => format!("It is {who}'s anniversary today"),
            },
        ),
        _ => (
            format!("{who}'s event"),
            format!("{who} has an event today"),
        ),
    }
}

/// Alarms for the events of `upcoming` within [`HORIZON_DAYS`] whose local
/// `hour`:00 is still in the future at `now_ms`. `tz_offset_s` is the local
/// UTC offset. Soonest first.
pub fn plan(upcoming: &[Upcoming], now_ms: i64, tz_offset_s: i32, hour: u8) -> Vec<BirthdayAlarm> {
    let hour = i64::from(hour.min(23));
    let today = contact_dates::local_day(now_ms, tz_offset_s);
    let mut out: Vec<BirthdayAlarm> = upcoming
        .iter()
        .filter(|u| (0..=i64::from(HORIZON_DAYS)).contains(&u.in_days))
        .filter_map(|u| {
            let day = today + u.in_days;
            let local_s = day * 86_400 + hour * 3600;
            let at_ms = (local_s - i64::from(tz_offset_s)) * 1000;
            if at_ms <= now_ms {
                return None;
            }
            let (title, body) = texts(u);
            Some(BirthdayAlarm {
                id: alarm_id(u, year_of(day)),
                contact_id: u.contact_id,
                at_ms,
                title,
                body,
            })
        })
        .collect();
    out.sort_by(|a, b| a.at_ms.cmp(&b.at_ms).then(a.id.cmp(&b.id)));
    out.dedup_by_key(|a| a.id);
    out
}

/// What to do to go from the `armed` ids to `wanted`: the ids to disarm
/// (armed but no longer wanted) and the alarms to arm (wanted, not armed).
pub fn diff<'a>(armed: &[i64], wanted: &'a [BirthdayAlarm]) -> (Vec<i64>, Vec<&'a BirthdayAlarm>) {
    let want: std::collections::HashSet<i64> = wanted.iter().map(|a| a.id).collect();
    let have: std::collections::HashSet<i64> = armed.iter().copied().collect();
    let mut disarm: Vec<i64> = have
        .iter()
        .copied()
        .filter(|id| !want.contains(id))
        .collect();
    disarm.sort_unstable();
    let arm = wanted.iter().filter(|a| !have.contains(&a.id)).collect();
    (disarm, arm)
}

#[cfg(test)]
mod tests {
    use super::*;

    // 2024-03-10 08:00 UTC.
    const NOW: i64 = 1_710_057_600_000;

    fn up(id: i64, kind: EventKind, in_days: i64, turning: Option<i64>) -> Upcoming {
        Upcoming {
            contact_id: id,
            name: "Ayse Yilmaz".into(),
            kind,
            in_days,
            date_text: "Mar 10".into(),
            turning,
        }
    }

    #[test]
    fn today_before_the_hour_is_kept_after_is_skipped() {
        let u = [up(1, EventKind::Birthday, 0, Some(31))];
        let a = plan(&u, NOW, 0, 9);
        assert_eq!(a.len(), 1);
        assert_eq!(a[0].at_ms, NOW + 3600 * 1000);
        assert_eq!(a[0].title, "Ayse's birthday");
        assert_eq!(a[0].body, "Ayse turns 31 today");
        assert!(plan(&u, NOW, 0, 8).is_empty());
        assert!(plan(&u, NOW, 0, 7).is_empty());
    }

    #[test]
    fn tomorrow_is_included_and_horizon_respected() {
        let u = [
            up(1, EventKind::Birthday, 1, None),
            up(2, EventKind::Birthday, 60, None),
            up(3, EventKind::Birthday, 61, None),
        ];
        let a = plan(&u, NOW, 0, 7);
        assert_eq!(a.iter().map(|x| x.contact_id).collect::<Vec<_>>(), [1, 2]);
        assert_eq!(a[0].body, "It is Ayse's birthday today");
    }

    #[test]
    fn timezone_shifts_the_instant() {
        let u = [up(1, EventKind::Birthday, 1, None)];
        let utc = plan(&u, NOW, 0, 9)[0].at_ms;
        let plus3 = plan(&u, NOW, 3 * 3600, 9)[0].at_ms;
        assert_eq!(utc - plus3, 3 * 3600 * 1000);
        // At +14h it is 22:00 locally: today's 09:00 is past, though not in UTC.
        let today = [up(1, EventKind::Birthday, 0, None)];
        assert!(plan(&today, NOW, 14 * 3600, 9).is_empty());
        assert_eq!(plan(&today, NOW, 0, 9).len(), 1);
    }

    #[test]
    fn ids_are_stable_positive_and_distinct() {
        let u = [
            up(1, EventKind::Birthday, 1, None),
            up(1, EventKind::Anniversary, 1, None),
            up(2, EventKind::Birthday, 1, None),
        ];
        let a = plan(&u, NOW, 0, 9);
        let b = plan(&u, NOW + 1000, 0, 9);
        let ids = |v: &[BirthdayAlarm]| {
            let mut x: Vec<i64> = v.iter().map(|a| a.id).collect();
            x.sort_unstable();
            x
        };
        assert_eq!(ids(&a), ids(&b));
        assert!(a.iter().all(|x| x.id > 0));
        let mut d = ids(&a);
        d.dedup();
        assert_eq!(d.len(), 3);
        assert_eq!(fnv1a(b"a"), 0xaf63_dc4c_8601_ec8c);
    }

    #[test]
    fn anniversary_text() {
        let a = plan(&[up(1, EventKind::Anniversary, 1, Some(5))], NOW, 0, 9);
        assert_eq!(a[0].title, "Ayse's anniversary");
        assert!(a[0].body.contains("5 years"));
    }

    #[test]
    fn diff_arms_new_and_disarms_gone() {
        let w = plan(
            &[
                up(1, EventKind::Birthday, 1, None),
                up(2, EventKind::Birthday, 2, None),
            ],
            NOW,
            0,
            9,
        );
        let (disarm, arm) = diff(&[w[0].id, 777, 5], &w);
        assert_eq!(disarm, vec![5, 777]);
        assert_eq!(arm.len(), 1);
        assert_eq!(arm[0].id, w[1].id);
        let (d, a) = diff(&[], &[]);
        assert!(d.is_empty() && a.is_empty());
    }
}
