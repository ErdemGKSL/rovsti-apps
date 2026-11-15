//! Transit data as the user reads it: the lines that call at a stop, the
//! one-line summary of an imported feed and when it was imported. Pure, on a
//! [`Feed`]; the GPUI app and the Kotlin app (through `stops.gtfs_lines` and
//! `stops.gtfs_summary`) show the same words.
//!
//! GTFS has no alarm rule of its own (AD-ST6): a line chosen here is drawn on
//! the map and remembered on the picked point as `Destination::pattern`. The
//! alarm is the crossing of the circle on AB.

use crate::Lang;
use crate::gtfs::{Feed, Route};

/// Most lines listed for one stop.
pub const MAX_LINES: usize = 20;

// ---- pure: lines through a stop ------------------------------------------------

/// "34", or the long name when the route has no short one, or its id.
pub fn route_name(route: &Route) -> String {
    [&route.short_name, &route.long_name, &route.id]
        .into_iter()
        .map(|s| s.trim())
        .find(|s| !s.is_empty())
        .unwrap_or("?")
        .to_owned()
}

/// One line that calls at a stop, as the stop sheet lists it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LineChoice {
    /// The pattern to build the alarm from.
    pub pattern: u32,
    pub name: String,
    pub headsign: String,
    /// Stops before this one on the pattern.
    pub before: usize,
}

/// Sorts "2" before "10" before "34A" before "M4".
pub fn natural_key(name: &str) -> (u32, String) {
    let digits: String = name.chars().take_while(char::is_ascii_digit).collect();
    (
        digits.parse().unwrap_or(u32::MAX),
        name.to_ascii_lowercase(),
    )
}

/// The lines calling at `stop` that have a stop before it, one entry per
/// (line, headsign): of several patterns with the same face the one with the
/// most stops before `stop` wins, so all of 1, 2 and 3 are on offer when any
/// variant allows them.
pub fn lines_through(feed: &Feed, stop: u32, limit: usize) -> Vec<LineChoice> {
    let mut lines: Vec<LineChoice> = Vec::new();
    for pattern in feed.patterns_through(stop) {
        let Some(p) = feed.patterns.get(pattern as usize) else {
            continue;
        };
        let Some(route) = feed.routes.get(p.route as usize) else {
            continue;
        };
        // The longest run of stops before the first call at `stop`.
        let Some(before) = p.stops.iter().position(|&s| s == stop).filter(|&i| i > 0) else {
            continue;
        };
        let name = route_name(route);
        match lines
            .iter_mut()
            .find(|l| l.name == name && l.headsign == p.headsign)
        {
            Some(existing) if existing.before >= before => {}
            Some(existing) => {
                existing.pattern = pattern;
                existing.before = before;
            }
            None => lines.push(LineChoice {
                pattern,
                name,
                headsign: p.headsign.clone(),
                before,
            }),
        }
    }
    lines.sort_by(|a, b| {
        natural_key(&a.name)
            .cmp(&natural_key(&b.name))
            .then_with(|| a.headsign.cmp(&b.headsign))
    });
    lines.truncate(limit);
    lines
}

// ---- pure: the report row ----------------------------------------------------------

/// `1234567` as "1 234 567".
pub fn grouped(n: usize) -> String {
    Lang::En.grouped(n)
}

/// "1 234 stops, 87 lines".
pub fn feed_summary(feed: &Feed) -> String {
    feed_summary_in(Lang::En, feed)
}

/// [`feed_summary`] in `lang`: "1.234 durak, 87 hat".
pub fn feed_summary_in(lang: Lang, feed: &Feed) -> String {
    let counted = |n: usize, one: &str, many: &str, tr: &str| match lang {
        Lang::En => format!("{} {}", lang.grouped(n), if n == 1 { one } else { many }),
        Lang::Tr => format!("{} {tr}", lang.grouped(n)),
    };
    format!(
        "{}, {}",
        counted(feed.stops.len(), "stop", "stops", "durak"),
        counted(feed.routes.len(), "line", "lines", "hat")
    )
}

/// (year, month 1..=12, day) of a Unix day number, proleptic Gregorian.
pub fn civil_from_days(days: i64) -> (i64, usize, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    (year, month as usize, day)
}

/// "5 Nov" for a moment in the current year, "5 Nov 2025" otherwise, on the
/// phone's calendar (`tz_offset_s` east of UTC, e.g. 10 800 in Turkey).
pub fn date_text(ms: i64, now_ms: i64, tz_offset_s: i32) -> String {
    date_text_in(Lang::En, ms, now_ms, tz_offset_s)
}

/// [`date_text`] in `lang`: "5 Kas", "5 Kas 2025".
pub fn date_text_in(lang: Lang, ms: i64, now_ms: i64, tz_offset_s: i32) -> String {
    let local_day = |ms: i64| {
        ms.saturating_add(i64::from(tz_offset_s) * 1_000)
            .div_euclid(86_400_000)
    };
    let (year, month, day) = civil_from_days(local_day(ms));
    let (this_year, _, _) = civil_from_days(local_day(now_ms));
    let month = lang.month(u8::try_from(month).unwrap_or(1));
    if year == this_year {
        format!("{day} {month}")
    } else {
        format!("{day} {month} {year}")
    }
}

/// "Imported 5 Nov", with a note when the import dropped rows.
pub fn imported_text(feed: &Feed, skipped_rows: u64, now_ms: i64, tz_offset_s: i32) -> String {
    imported_text_in(Lang::En, feed, skipped_rows, now_ms, tz_offset_s)
}

/// [`imported_text`] in `lang`: "5 Kas tarihinde içe aktarıldı, kullanılamayan 12 satır atlandı".
pub fn imported_text_in(
    lang: Lang,
    feed: &Feed,
    skipped_rows: u64,
    now_ms: i64,
    tz_offset_s: i32,
) -> String {
    let date = date_text_in(lang, feed.imported_ms, now_ms, tz_offset_s);
    let mut text = match lang {
        Lang::En => format!("Imported {date}"),
        Lang::Tr => format!("{date} tarihinde içe aktarıldı"),
    };
    if skipped_rows > 0 {
        let rows = lang.grouped(usize::try_from(skipped_rows).unwrap_or(usize::MAX));
        text.push_str(&match lang {
            Lang::En => format!(", {rows} unusable rows skipped"),
            Lang::Tr => format!(", kullanılamayan {rows} satır atlandı"),
        });
    }
    text
}

/// "Line 34 to Kadıköy", or "Line 34" for a pattern without a headsign.
pub fn line_title(name: &str, headsign: &str) -> String {
    line_title_in(Lang::En, name, headsign)
}

/// [`line_title`] in `lang`: "Hat 34, Kadıköy yönü", "Hat 34".
pub fn line_title_in(lang: Lang, name: &str, headsign: &str) -> String {
    match (lang, headsign.is_empty()) {
        (Lang::En, true) => format!("Line {name}"),
        (Lang::En, false) => format!("Line {name} to {headsign}"),
        (Lang::Tr, true) => format!("Hat {name}"),
        (Lang::Tr, false) => format!("Hat {name}, {headsign} yönü"),
    }
}

/// "1 stop earlier on this line" / "3 stops earlier on this line": how many
/// stops the line calls at before this one.
pub fn earlier_text(before: usize) -> String {
    earlier_text_in(Lang::En, before)
}

/// [`earlier_text`] in `lang`: "Bu hatta 3 durak önce".
pub fn earlier_text_in(lang: Lang, before: usize) -> String {
    match lang {
        Lang::En if before == 1 => "1 stop earlier on this line".to_owned(),
        Lang::En => format!("{} stops earlier on this line", lang.grouped(before)),
        Lang::Tr => format!("Bu hatta {} durak önce", lang.grouped(before)),
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::coord::Coord;
    use crate::gtfs::{Pattern, Stop};

    fn stop(id: &str, name: &str, lat: f64) -> Stop {
        Stop {
            id: id.into(),
            name: name.into(),
            coord: Coord::new(lat, 29.0),
            parent: None,
        }
    }

    fn route(id: &str, short: &str, long: &str) -> Route {
        Route {
            id: id.into(),
            short_name: short.into(),
            long_name: long.into(),
            kind: 3,
        }
    }

    /// Stops 0..5 north to south; line "34" runs 0,1,2,3,4 to "Kadikoy";
    /// line "M4" runs 2,3 and 4,3,2 back; one short variant of 34 runs 3,4.
    fn feed() -> Feed {
        Feed::new(
            (0..5)
                .map(|i| {
                    stop(
                        &format!("s{i}"),
                        &format!("Stop {i}"),
                        41.0 - f64::from(i) * 0.01,
                    )
                })
                .collect(),
            vec![
                route("r1", "34", "Kadikoy - Sogutlucesme"),
                route("r2", "", "Metro 4"),
            ],
            vec![
                Pattern {
                    route: 0,
                    headsign: "Kadikoy".into(),
                    stops: vec![0, 1, 2, 3, 4],
                },
                Pattern {
                    route: 0,
                    headsign: "Kadikoy".into(),
                    stops: vec![3, 4],
                },
                Pattern {
                    route: 1,
                    headsign: "Tavsantepe".into(),
                    stops: vec![2, 3],
                },
            ],
            1_730_764_800_000,
        )
    }

    #[test]
    fn lines_keep_the_variant_with_the_most_stops_before() {
        let f = feed();
        // Stop 4 is on both variants of 34; the long one (pattern 0) wins.
        let lines = lines_through(&f, 4, 20);
        assert_eq!(
            lines,
            vec![LineChoice {
                pattern: 0,
                name: "34".into(),
                headsign: "Kadikoy".into(),
                before: 4
            }]
        );
        // Stop 3: 34 (3 before) and the metro (1 before), numbers first.
        let names: Vec<_> = lines_through(&f, 3, 20)
            .into_iter()
            .map(|l| l.name)
            .collect();
        assert_eq!(names, ["34", "Metro 4"]);
        // The first stop of every pattern has nothing before it.
        assert!(lines_through(&f, 0, 20).is_empty());
        assert_eq!(lines_through(&f, 3, 1).len(), 1);
    }

    #[test]
    fn line_names_sort_the_way_people_read_them() {
        let mut names = ["M4", "34A", "10", "2", "34"];
        names.sort_by_key(|n| natural_key(n));
        assert_eq!(names, ["2", "10", "34", "34A", "M4"]);
    }

    #[test]
    fn the_report_row_reads_like_a_sentence() {
        let f = feed();
        assert_eq!(feed_summary(&f), "5 stops, 2 lines");
        let one = Feed::new(
            vec![stop("a", "A", 1.0)],
            vec![route("r", "1", "")],
            vec![],
            0,
        );
        assert_eq!(feed_summary(&one), "1 stop, 1 line");
        assert_eq!(grouped(0), "0");
        assert_eq!(grouped(999), "999");
        assert_eq!(grouped(1_234), "1 234");
        assert_eq!(grouped(1_234_567), "1 234 567");
    }

    #[test]
    fn dates_drop_the_year_only_for_this_year() {
        // 2024-11-05 00:00 UTC and 2025-11-05 (a year of 365 days later).
        let nov5_2024 = 1_730_764_800_000;
        let nov5_2025 = nov5_2024 + 365 * 86_400_000;
        assert_eq!(date_text(nov5_2025, nov5_2025 + 5_000, 0), "5 Nov");
        assert_eq!(date_text(nov5_2024, nov5_2025, 0), "5 Nov 2024");
        assert_eq!(date_text(0, nov5_2025, 0), "1 Jan 1970");
        // Leap day, and the last second of a year.
        assert_eq!(date_text(1_709_164_800_000, nov5_2024, 0), "29 Feb");
        assert_eq!(date_text(1_735_689_599_000, nov5_2024, 0), "31 Dec");
        let f = feed();
        assert_eq!(imported_text(&f, 0, nov5_2024, 0), "Imported 5 Nov");
        assert_eq!(
            imported_text(&f, 1_200, nov5_2024, 0),
            "Imported 5 Nov, 1 200 unusable rows skipped"
        );
    }

    #[test]
    fn dates_follow_the_phone_time_zone() {
        const TURKEY: i32 = 3 * 3_600;
        // 2024-11-04 22:30 UTC is already 5 Nov 01:30 in Istanbul.
        let late = 1_730_764_800_000 - 90 * 60_000;
        assert_eq!(date_text(late, late, 0), "4 Nov");
        assert_eq!(date_text(late, late, TURKEY), "5 Nov");
        // 31 Dec 23:00 UTC is New Year in Istanbul: the year follows too.
        let new_year = 1_735_689_600_000 - 3_600_000;
        assert_eq!(date_text(new_year, new_year, TURKEY), "1 Jan");
        assert_eq!(
            date_text(new_year, new_year - 86_400_000, TURKEY),
            "1 Jan 2025"
        );
    }

    // ---- edge cases ----

    fn feed_of(stops: usize, routes: Vec<Route>, patterns: Vec<Pattern>) -> Feed {
        Feed::new(
            (0..stops)
                .map(|i| stop(&format!("s{i}"), &format!("Stop {i}"), 41.0))
                .collect(),
            routes,
            patterns,
            0,
        )
    }

    fn pattern(route: u32, headsign: &str, stops: &[u32]) -> Pattern {
        Pattern {
            route,
            headsign: headsign.into(),
            stops: stops.to_vec(),
        }
    }

    #[test]
    fn line_titles_and_the_earlier_line() {
        assert_eq!(line_title("34", "Kadikoy"), "Line 34 to Kadikoy");
        assert_eq!(line_title("34", ""), "Line 34");
        assert_eq!(earlier_text(1), "1 stop earlier on this line");
        assert_eq!(earlier_text(2), "2 stops earlier on this line");
        assert_eq!(earlier_text(0), "0 stops earlier on this line");
        assert_eq!(earlier_text(1_500), "1 500 stops earlier on this line");
    }

    #[test]
    fn an_empty_feed_and_an_unknown_stop_have_no_lines() {
        let empty = feed_of(0, Vec::new(), Vec::new());
        assert!(lines_through(&empty, 0, MAX_LINES).is_empty());
        assert!(lines_through(&empty, u32::MAX, MAX_LINES).is_empty());
        assert_eq!(feed_summary(&empty), "0 stops, 0 lines");
        let f = feed();
        assert!(lines_through(&f, 99, MAX_LINES).is_empty());
        // No room in the list: nothing.
        assert!(lines_through(&f, 3, 0).is_empty());
    }

    #[test]
    fn a_stop_on_no_pattern_has_no_lines() {
        // Stop 5 exists but no line calls at it.
        let f = feed_of(
            6,
            vec![route("r", "1", "")],
            vec![pattern(0, "End", &[0, 1, 2])],
        );
        assert!(lines_through(&f, 5, MAX_LINES).is_empty());
        assert_eq!(lines_through(&f, 2, MAX_LINES).len(), 1);
    }

    #[test]
    fn a_stop_that_is_first_on_every_pattern_has_no_lines() {
        let f = feed_of(
            4,
            vec![route("a", "1", ""), route("b", "2", "")],
            vec![
                pattern(0, "East", &[0, 1, 2]),
                pattern(1, "West", &[0, 3]),
                pattern(0, "Short", &[0]),
            ],
        );
        assert!(lines_through(&f, 0, MAX_LINES).is_empty());
        // The same lines are on offer one stop on.
        assert_eq!(lines_through(&f, 1, MAX_LINES)[0].before, 1);
    }

    #[test]
    fn on_a_loop_the_first_call_at_a_stop_counts() {
        // A ring line: 0, 1, 2, 3 and back to 0; and one that passes stop 1 twice.
        let f = feed_of(
            4,
            vec![route("ring", "R", ""), route("eight", "8", "")],
            vec![
                pattern(0, "Ring", &[0, 1, 2, 3, 0]),
                pattern(1, "Eight", &[2, 1, 3, 1, 0]),
            ],
        );
        // Stop 1 is called at twice by "8": the first call, one stop in, is the one counted.
        let at_one = lines_through(&f, 1, MAX_LINES);
        assert_eq!(
            at_one
                .iter()
                .map(|l| (l.name.as_str(), l.before))
                .collect::<Vec<_>>(),
            [("8", 1), ("R", 1)]
        );
        // The terminal of the ring is its first stop: its later, closing call is not offered.
        let at_zero = lines_through(&f, 0, MAX_LINES);
        assert_eq!(
            at_zero
                .iter()
                .map(|l| (l.name.as_str(), l.before))
                .collect::<Vec<_>>(),
            [("8", 4)]
        );
    }

    #[test]
    fn many_variants_of_one_line_are_one_entry_and_other_headsigns_are_their_own() {
        // Forty variants of line 5 to "Centre", each starting one stop later, and one to "Depot".
        let mut patterns: Vec<Pattern> = (0..40u32)
            .map(|start| {
                let stops: Vec<u32> = (start..=40).collect();
                pattern(0, "Centre", &stops)
            })
            .collect();
        patterns.push(pattern(0, "Depot", &[39, 40]));
        let f = feed_of(41, vec![route("r5", "5", "")], patterns);
        let lines = lines_through(&f, 40, MAX_LINES);
        assert_eq!(lines.len(), 2);
        // The longest variant (pattern 0, forty stops before) stands for all of them.
        assert_eq!(
            lines[0],
            LineChoice {
                pattern: 0,
                name: "5".into(),
                headsign: "Centre".into(),
                before: 40
            }
        );
        assert_eq!((lines[1].headsign.as_str(), lines[1].before), ("Depot", 1));
        // Equal runs: the first pattern keeps its place.
        let tie = feed_of(
            3,
            vec![route("r", "7", "")],
            vec![pattern(0, "X", &[0, 2]), pattern(0, "X", &[1, 2])],
        );
        assert_eq!(lines_through(&tie, 2, MAX_LINES)[0].pattern, 0);
    }

    #[test]
    fn the_limit_keeps_the_first_lines_in_reading_order() {
        // Thirty lines through stop 1, numbered 30 down to 1 in the feed.
        let routes: Vec<Route> = (0..30)
            .map(|i| route(&format!("r{i}"), &format!("{}", 30 - i), ""))
            .collect();
        let patterns: Vec<Pattern> = (0..30).map(|i| pattern(i, "End", &[0, 1])).collect();
        let f = feed_of(2, routes, patterns);
        let lines = lines_through(&f, 1, MAX_LINES);
        assert_eq!(lines.len(), MAX_LINES);
        assert_eq!(lines[0].name, "1");
        assert_eq!(lines[19].name, "20");
        assert_eq!(
            lines_through(&f, 1, 3)
                .iter()
                .map(|l| l.name.as_str())
                .collect::<Vec<_>>(),
            ["1", "2", "3"]
        );
    }

    #[test]
    fn a_pattern_with_a_missing_route_is_skipped() {
        let f = feed_of(
            2,
            vec![route("r", "1", "")],
            vec![pattern(9, "Nowhere", &[0, 1])],
        );
        assert!(lines_through(&f, 1, MAX_LINES).is_empty());
    }

    #[test]
    fn route_names_fall_back_from_short_to_long_to_id() {
        assert_eq!(route_name(&route("r1", "34", "Long")), "34");
        assert_eq!(route_name(&route("r1", "  ", "Metro 4")), "Metro 4");
        assert_eq!(route_name(&route("r1", "", " ")), "r1");
        assert_eq!(route_name(&route(" ", "", "")), "?");
        assert_eq!(route_name(&route("r1", " 34 ", "")), "34");
    }

    #[test]
    fn natural_order_with_letters_empty_names_and_big_numbers() {
        let mut names = vec![
            "", "b", "A", "10A", "10", "9", "100", "a1", "007", "7", "M10", "M2", "?",
        ];
        names.sort_by_key(|n| natural_key(n));
        // Numbers by value (the same value by spelling), then names without a leading number, case-blind.
        assert_eq!(
            names,
            [
                "007", "7", "9", "10", "10A", "100", "", "?", "A", "a1", "b", "M10", "M2"
            ]
        );
        // A number too big for the key sorts with the names instead of wrapping round.
        assert_eq!(natural_key("99999999999999999999").0, u32::MAX);
        assert_eq!(natural_key("4294967295").0, u32::MAX);
        assert_eq!(natural_key("42").0, 42);
        assert_eq!(natural_key("").0, u32::MAX);
    }

    #[test]
    fn grouping_and_plurals() {
        assert_eq!(grouped(1), "1");
        assert_eq!(grouped(12), "12");
        assert_eq!(grouped(1_000), "1 000");
        assert_eq!(grouped(100_000), "100 000");
        assert_eq!(grouped(1_000_000), "1 000 000");
        let big = feed_of(
            1_234,
            (0..87).map(|i| route(&format!("r{i}"), "", "")).collect(),
            Vec::new(),
        );
        assert_eq!(feed_summary(&big), "1 234 stops, 87 lines");
    }

    #[test]
    fn civil_dates_round_the_calendar() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(-1), (1969, 12, 31));
        assert_eq!(civil_from_days(59), (1970, 3, 1));
        // 2000 was a leap year, 1900 was not, 2100 will not be.
        assert_eq!(civil_from_days(11_016), (2000, 2, 29));
        assert_eq!(civil_from_days(-25_508), (1900, 3, 1));
        assert_eq!(civil_from_days(-25_509), (1900, 2, 28));
        assert_eq!(civil_from_days(47_541), (2100, 3, 1));
        assert_eq!(civil_from_days(47_540), (2100, 2, 28));
        assert_eq!(civil_from_days(-719_468), (0, 3, 1));
    }

    #[test]
    fn dates_before_1970_and_at_the_edges_of_time() {
        let now = 1_730_764_800_000;
        // One millisecond before the epoch is still 31 Dec 1969, not 1 Jan 1970.
        assert_eq!(date_text(-1, now, 0), "31 Dec 1969");
        assert_eq!(date_text(-86_400_000, now, 0), "31 Dec 1969");
        assert_eq!(date_text(-86_400_001, now, 0), "30 Dec 1969");
        // "This year" is judged in 1969 too.
        assert_eq!(date_text(-1, -1, 0), "31 Dec");
        // The extremes do not overflow or panic.
        assert!(!date_text(i64::MAX, now, i32::MAX).is_empty());
        assert!(!date_text(i64::MIN, now, i32::MIN).is_empty());
        let f = Feed::new(Vec::new(), Vec::new(), Vec::new(), -1);
        assert_eq!(imported_text(&f, 0, now, 0), "Imported 31 Dec 1969");
        assert_eq!(
            imported_text(&f, u64::MAX, -1, 0),
            format!(
                "Imported 31 Dec, {} unusable rows skipped",
                grouped(usize::MAX)
            )
        );
        assert_eq!(
            imported_text(&f, 1, -1, 0),
            "Imported 31 Dec, 1 unusable rows skipped"
        );
    }

    #[test]
    fn dates_in_offsets_east_west_and_off_the_hour() {
        // 2024-12-31 23:30 UTC.
        let t = 1_735_689_600_000 - 30 * 60_000;
        // Kiritimati, +14: already the afternoon of 1 Jan.
        assert_eq!(date_text(t, t, 14 * 3_600), "1 Jan");
        // Baker Island, -12: still the morning of 31 Dec.
        assert_eq!(date_text(t, t, -12 * 3_600), "31 Dec");
        // Nepal, +5:45, and Newfoundland, -3:30: quarter and half hours count.
        assert_eq!(date_text(t, t, 5 * 3_600 + 45 * 60), "1 Jan");
        assert_eq!(date_text(t, t, -(3 * 3_600 + 30 * 60)), "31 Dec");
        // +0:30 tips it over the year, +0:29 does not.
        assert_eq!(date_text(t, t, 30 * 60), "1 Jan");
        assert_eq!(date_text(t, t, 29 * 60), "31 Dec");
        // The year is dropped when the two moments share a local year, not a UTC year.
        let before = t - 86_400_000;
        assert_eq!(date_text(t, before, 3 * 3_600), "1 Jan 2025");
        assert_eq!(date_text(t, before, -3 * 3_600), "31 Dec");
    }
}
