//! Frequently contacted people: the local call log of the last 30 days,
//! matched to contacts, without starred ones (those are on Favorites already).

use std::collections::HashMap;

use crate::call_stats::{StatCall, StatDir};
use crate::import_plan::number_key;
use crate::phone_number::PhoneNumber;
use crate::wire::WireContact;

const DAY_MS: i64 = 86_400_000;
/// Calls older than this do not count.
pub const WINDOW_MS: i64 = 30 * DAY_MS;
/// People shown at most.
pub const MAX_SHOWN: usize = 6;
/// Calls this recent count double.
const RECENT_MS: i64 = 7 * DAY_MS;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Frequent {
    pub contact_id: i64,
    /// Calls in the window, any direction.
    pub calls: u32,
    /// Time of the latest call.
    pub last_ms: i64,
}

struct Known {
    id: i64,
    numbers: Vec<(Option<PhoneNumber>, Option<String>)>,
}

impl Known {
    fn has(&self, parsed: &Option<PhoneNumber>, key: &Option<String>) -> bool {
        self.numbers.iter().any(|(p, k)| match (p, parsed) {
            (Some(a), Some(b)) => a.matches(b),
            _ => k.is_some() && k == key,
        })
    }
}

/// The most called unstarred contacts of the last 30 days, best first. A
/// missed call counts half, a call in the last 7 days double. Contacts with a
/// single call are left out.
pub fn rank(
    calls: &[StatCall],
    contacts: &[WireContact],
    now_ms: i64,
    limit: usize,
) -> Vec<Frequent> {
    // Starred contacts must not be taken by an unstarred twin with the same
    // number: match against everyone, then drop the starred.
    let everyone: Vec<Known> = contacts
        .iter()
        .map(|c| Known {
            id: c.id,
            numbers: c
                .numbers
                .iter()
                .map(|n| (PhoneNumber::parse(&n.raw).ok(), number_key(&n.raw)))
                .collect(),
        })
        .collect();
    let starred: Vec<i64> = contacts
        .iter()
        .filter(|c| c.starred)
        .map(|c| c.id)
        .collect();

    let mut owner: HashMap<&str, Option<i64>> = HashMap::new();
    // contact id -> (score in halves, calls, last_ms)
    let mut totals: HashMap<i64, (u64, u32, i64)> = HashMap::new();
    for call in calls {
        let age = now_ms - call.date_ms;
        if age > WINDOW_MS {
            continue;
        }
        let id = *owner.entry(call.number.as_str()).or_insert_with(|| {
            let parsed = PhoneNumber::parse(&call.number).ok();
            let key = number_key(&call.number);
            if parsed.is_none() && key.is_none() {
                return None;
            }
            everyone.iter().find(|k| k.has(&parsed, &key)).map(|k| k.id)
        });
        let Some(id) = id else { continue };
        let mut score = if call.dir == StatDir::Missed { 1 } else { 2 };
        if age <= RECENT_MS {
            score *= 2;
        }
        let t = totals.entry(id).or_insert((0, 0, i64::MIN));
        t.0 += score;
        t.1 += 1;
        t.2 = t.2.max(call.date_ms);
    }
    let mut ranked: Vec<(u64, Frequent)> = totals
        .into_iter()
        .filter(|(id, (_, n, _))| *n >= 2 && !starred.contains(id))
        .map(|(id, (score, calls, last_ms))| {
            (
                score,
                Frequent {
                    contact_id: id,
                    calls,
                    last_ms,
                },
            )
        })
        .collect();
    ranked.sort_by(|a, b| {
        b.0.cmp(&a.0)
            .then(b.1.last_ms.cmp(&a.1.last_ms))
            .then(a.1.contact_id.cmp(&b.1.contact_id))
    });
    ranked.into_iter().take(limit).map(|(_, f)| f).collect()
}

/// The line under a person: "5 calls in the last 30 days".
pub fn subtitle(f: &Frequent) -> String {
    let noun = if f.calls == 1 { "call" } else { "calls" };
    format!("{} {noun} in the last 30 days", f.calls)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::WireNumber;

    const NOW: i64 = 100 * DAY_MS;

    fn contact(id: i64, number: &str, starred: bool) -> WireContact {
        WireContact {
            id,
            display_name: format!("C{id}"),
            numbers: vec![WireNumber {
                raw: number.into(),
                label: None,
                default: false,
                normalized: None,
            }],
            starred,
            ..WireContact::default()
        }
    }

    fn call(number: &str, days_ago: i64, dir: StatDir) -> StatCall {
        StatCall {
            number: number.into(),
            name: None,
            date_ms: NOW - days_ago * DAY_MS,
            duration_s: 10,
            dir,
            whatsapp: false,
        }
    }

    fn out(number: &str, days_ago: i64) -> StatCall {
        call(number, days_ago, StatDir::Outgoing)
    }

    fn ids(v: &[Frequent]) -> Vec<i64> {
        v.iter().map(|f| f.contact_id).collect()
    }

    #[test]
    fn ranks_by_score() {
        let contacts = [
            contact(1, "0532 111 11 11", false),
            contact(2, "0533 222 22 22", false),
        ];
        let calls = [
            out("05321111111", 20),
            out("05321111111", 21),
            out("05332222222", 20),
            out("05332222222", 21),
            out("05332222222", 22),
        ];
        let r = rank(&calls, &contacts, NOW, MAX_SHOWN);
        assert_eq!(ids(&r), vec![2, 1]);
        assert_eq!(r[0].calls, 3);
        assert_eq!(r[0].last_ms, NOW - 20 * DAY_MS);
    }

    #[test]
    fn recent_counts_double_and_missed_half() {
        let contacts = [contact(1, "111111", false), contact(2, "222222", false)];
        // 1: two old answered calls = 4 halves. 2: two recent missed = 4 halves
        // plus a third old missed = 5.
        let calls = [
            out("111111", 20),
            out("111111", 21),
            call("222222", 1, StatDir::Missed),
            call("222222", 2, StatDir::Missed),
            call("222222", 20, StatDir::Missed),
        ];
        assert_eq!(ids(&rank(&calls, &contacts, NOW, 6)), vec![2, 1]);
        // A recent answered call outweighs two old missed ones.
        let calls = [
            out("111111", 1),
            out("111111", 20),
            call("222222", 20, StatDir::Missed),
            call("222222", 21, StatDir::Missed),
            call("222222", 22, StatDir::Missed),
        ];
        assert_eq!(ids(&rank(&calls, &contacts, NOW, 6)), vec![1, 2]);
    }

    #[test]
    fn ties_go_to_recency_then_id() {
        let contacts = [
            contact(3, "333333", false),
            contact(1, "111111", false),
            contact(2, "222222", false),
        ];
        let calls = [
            out("333333", 19),
            out("333333", 22),
            out("111111", 20),
            out("111111", 21),
            out("222222", 20),
            out("222222", 21),
        ];
        assert_eq!(ids(&rank(&calls, &contacts, NOW, 6)), vec![3, 1, 2]);
    }

    #[test]
    fn window_edge() {
        let contacts = [contact(1, "111111", false)];
        let calls = [out("111111", 30), out("111111", 29)];
        assert_eq!(rank(&calls, &contacts, NOW, 6).len(), 1);
        let mut old = out("111111", 30);
        old.date_ms -= 1;
        let calls = [old, out("111111", 29)];
        assert!(rank(&calls, &contacts, NOW, 6).is_empty());
    }

    #[test]
    fn starred_and_single_call_left_out() {
        let contacts = [
            contact(1, "111111", true),
            contact(2, "222222", false),
            contact(3, "333333", false),
        ];
        let calls = [
            out("111111", 5),
            out("111111", 6),
            out("222222", 5),
            out("333333", 5),
            out("333333", 6),
        ];
        assert_eq!(ids(&rank(&calls, &contacts, NOW, 6)), vec![3]);
    }

    #[test]
    fn national_and_international_match_one_contact() {
        let contacts = [contact(1, "+90 532 111 22 33", false)];
        let calls = [
            out("05321112233", 5),
            out("+905321112233", 6),
            out("5321112233", 7),
        ];
        let r = rank(&calls, &contacts, NOW, 6);
        assert_eq!(ids(&r), vec![1]);
        assert_eq!(r[0].calls, 3);
    }

    #[test]
    fn unknown_and_unparseable_numbers_are_ignored() {
        let contacts = [contact(1, "111111", false)];
        let calls = [
            out("999999", 5),
            out("999999", 6),
            out("", 5),
            out("", 6),
            out("Private", 5),
            out("Private", 6),
        ];
        assert!(rank(&calls, &contacts, NOW, 6).is_empty());
    }

    #[test]
    fn limit_and_subtitle() {
        let contacts = [contact(1, "111111", false), contact(2, "222222", false)];
        let calls = [
            out("111111", 5),
            out("111111", 6),
            out("222222", 5),
            out("222222", 6),
        ];
        assert_eq!(rank(&calls, &contacts, NOW, 1).len(), 1);
        let f = Frequent {
            contact_id: 1,
            calls: 5,
            last_ms: 0,
        };
        assert_eq!(subtitle(&f), "5 calls in the last 30 days");
        let f = Frequent { calls: 1, ..f };
        assert_eq!(subtitle(&f), "1 call in the last 30 days");
    }
}
