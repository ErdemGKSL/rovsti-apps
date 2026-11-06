//! Transit data: importing a GTFS feed, and the line-aware "N stops before"
//! alarm built from it.
//!
//! The feed never leaves the phone. Kotlin copies the file the user picked into
//! the app cache and answers with a `document_result` (op `import_gtfs`); the
//! feed is parsed on the background executor, saved as `gtfs.json` and the
//! cache copy is deleted. The pure pieces (the rule from a pattern, the
//! report row, the date) are plain functions with unit tests.

use std::{fs::File, path::Path, sync::Arc};

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px,
};
use rovsti_android::{Error as AndroidError, documents};
use rovsti_core::wire::DocumentResult;
use rovsti_geo::{Feed, FeedReport, Route, read_zip};
use rovsti_ui::{
    components::{list_row, section},
    storage, theme,
    tokens::{Theme, typography},
};

use crate::{StopsApp, store};

/// The biggest feed the picker copies in (Kotlin enforces the same cap).
const MAX_FEED_BYTES: u64 = 64 * 1024 * 1024;
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
fn natural_key(name: &str) -> (u32, String) {
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
    let digits = n.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i).is_multiple_of(3) {
            out.push(' ');
        }
        out.push(c);
    }
    out
}

/// "1 234 stops, 87 lines".
pub fn feed_summary(feed: &Feed) -> String {
    let plural = |n: usize, one: &str, many: &str| {
        format!("{} {}", grouped(n), if n == 1 { one } else { many })
    };
    format!(
        "{}, {}",
        plural(feed.stops.len(), "stop", "stops"),
        plural(feed.routes.len(), "line", "lines")
    )
}

const MONTHS: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

/// (year, month 1..=12, day) of a Unix day number, proleptic Gregorian.
fn civil_from_days(days: i64) -> (i64, usize, i64) {
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

/// "5 Nov" for a moment in the current year (UTC), "5 Nov 2025" otherwise.
pub fn date_text(ms: i64, now_ms: i64) -> String {
    let (year, month, day) = civil_from_days(ms.div_euclid(86_400_000));
    let (this_year, _, _) = civil_from_days(now_ms.div_euclid(86_400_000));
    if year == this_year {
        format!("{day} {}", MONTHS[month - 1])
    } else {
        format!("{day} {} {year}", MONTHS[month - 1])
    }
}

/// "Imported 5 Nov", with a note when the import dropped rows.
pub fn imported_text(feed: &Feed, skipped_rows: u64, now_ms: i64) -> String {
    let mut text = format!("Imported {}", date_text(feed.imported_ms, now_ms));
    if skipped_rows > 0 {
        text.push_str(&format!(
            ", {} unusable rows skipped",
            grouped(usize::try_from(skipped_rows).unwrap_or(usize::MAX))
        ));
    }
    text
}

// ---- background: reading the picked file --------------------------------------------

/// Parses the feed at `path`, saves it as `gtfs.json` in `dir` and deletes
/// the picked copy. Errors are the sentence to show, `GtfsError` verbatim.
pub fn import_file(dir: &Path, path: &Path) -> Result<(Feed, FeedReport), String> {
    let result = File::open(path)
        .map_err(|e| format!("Could not open the file: {e}"))
        .and_then(|file| read_zip(file).map_err(|e| e.to_string()))
        .and_then(|(feed, report)| {
            storage::save_json(dir, store::GTFS_FILE, &feed)
                .map_err(|e| format!("Could not save the feed: {e}"))?;
            Ok((feed, report))
        });
    // The picked copy is only a hand-off: gone whether the import worked or not.
    let _ = std::fs::remove_file(path);
    result
}

/// Deletes the saved feed.
pub fn remove_file(dir: &Path) {
    if let Err(error) = std::fs::remove_file(dir.join(store::GTFS_FILE))
        && error.kind() != std::io::ErrorKind::NotFound
    {
        log::warn!("could not remove the transit feed: {error}");
    }
}

// ---- UI state ----------------------------------------------------------------------

/// Where the import stands.
#[derive(Clone, Debug, Default, PartialEq)]
pub enum ImportState {
    #[default]
    Idle,
    /// The system file picker is open.
    Picking,
    /// The feed is being read.
    Reading,
}

#[derive(Default)]
pub struct Transit {
    pub state: ImportState,
    /// The last import's failure, verbatim.
    pub error: Option<String>,
    /// Rows the last import dropped.
    pub skipped_rows: u64,
}

pub(crate) fn note(theme: Theme, text: impl Into<SharedString>, error: bool) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .px(px(20.0))
        .text_size(typography::FOOTNOTE.size)
        .text_color(if error {
            colors.red
        } else {
            colors.secondary_label
        })
        .child(text.into())
        .into_any_element()
}

impl StopsApp {
    /// Settings > Transit data.
    pub(crate) fn transit_section(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let busy = self.transit.state != ImportState::Idle;
        let mut rows: Vec<AnyElement> = Vec::new();
        match (&self.gtfs, &self.transit.state) {
            (_, ImportState::Picking) => rows.push(
                list_row(theme, None, "Choose a GTFS .zip file", None, None).into_any_element(),
            ),
            (_, ImportState::Reading) => rows.push(
                list_row(
                    theme,
                    None,
                    "Reading the feed...",
                    Some("Large feeds take a minute.".into()),
                    None,
                )
                .into_any_element(),
            ),
            (Some(feed), ImportState::Idle) => {
                rows.push(
                    list_row(
                        theme,
                        None,
                        feed_summary(feed),
                        Some(
                            imported_text(feed, self.transit.skipped_rows, store::now_ms()).into(),
                        ),
                        None,
                    )
                    .into_any_element(),
                );
                rows.push(
                    list_row(
                        theme,
                        None,
                        "Replace with another feed",
                        None,
                        Some("Choose".into()),
                    )
                    .id("feed-replace")
                    .on_click(cx.listener(|this, _, _, cx| this.pick_feed(cx)))
                    .into_any_element(),
                );
                rows.push(
                    list_row(
                        theme,
                        None,
                        "Remove transit data",
                        None,
                        Some("Remove".into()),
                    )
                    .id("feed-remove")
                    .on_click(cx.listener(|this, _, _, cx| this.remove_feed(cx)))
                    .into_any_element(),
                );
            }
            (None, ImportState::Idle) => rows.push(
                list_row(
                    theme,
                    None,
                    "Import a GTFS feed",
                    Some(
                        "Stop names and lines from your transit agency. Stays on this phone."
                            .into(),
                    ),
                    Some("Choose".into()),
                )
                .id("feed-import")
                .on_click(cx.listener(|this, _, _, cx| this.pick_feed(cx)))
                .into_any_element(),
            ),
        }
        let mut block = div().flex().flex_col().gap(px(6.0)).child(section(
            theme,
            Some("Transit data".into()),
            rows,
        ));
        if let Some(error) = &self.transit.error {
            block = block.child(note(theme, error.clone(), true));
        } else if self.gtfs.is_none() && !busy {
            block = block.child(note(
                theme,
                "With a feed, you can wake up 1, 2 or 3 stops before yours, and see stops on the map.",
                false,
            ));
        }
        block.into_any_element()
    }

    /// Opens the system file picker for a `.zip`.
    pub(crate) fn pick_feed(&mut self, cx: &mut Context<Self>) {
        if self.transit.state != ImportState::Idle {
            return;
        }
        self.transit.error = None;
        self.transit.state = ImportState::Picking;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let started = cx
                .background_executor()
                .spawn(async { documents::import_gtfs(MAX_FEED_BYTES) })
                .await;
            let problem = match started {
                Ok(true) => return,
                Ok(false) => Some("The file picker could not open.".to_owned()),
                Err(AndroidError::Unsupported) => {
                    Some("Importing a transit feed works on the phone only.".to_owned())
                }
                Err(error) => Some(format!("The file picker could not open: {error}")),
            };
            this.update(cx, |this, cx| {
                this.transit.state = ImportState::Idle;
                this.transit.error = problem;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The `document_result` of the picker: read the feed in the background.
    pub(crate) fn on_feed_picked(&mut self, result: &DocumentResult, cx: &mut Context<Self>) {
        let path = result.path.clone().filter(|_| result.ok);
        let (Some(path), Some(dir)) = (path, self.dir.clone()) else {
            // Cancelled, or the file was refused (too large, unreadable).
            self.transit.state = ImportState::Idle;
            if !result.ok && result.error.is_some() {
                self.transit.error =
                    Some("That file could not be copied. Feeds up to 64 MiB work.".into());
            }
            cx.notify();
            return;
        };
        self.transit.state = ImportState::Reading;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let outcome = cx
                .background_executor()
                .spawn(async move {
                    let outcome = import_file(&dir, Path::new(&path));
                    // Build the lookup index here, not on the first search.
                    if let Ok((feed, _)) = &outcome {
                        let _ = feed.patterns_through(0);
                    }
                    outcome
                })
                .await;
            this.update(cx, |this, cx| {
                this.transit.state = ImportState::Idle;
                match outcome {
                    Ok((feed, report)) => {
                        this.transit.error = None;
                        this.transit.skipped_rows = report.skipped_rows;
                        this.set_feed(Some(Arc::new(feed)));
                        this.show_pill("Transit data imported.", None, cx);
                    }
                    Err(why) => this.transit.error = Some(why),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Deletes the feed and everything that points into it.
    pub(crate) fn remove_feed(&mut self, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            return;
        };
        cx.background_executor()
            .spawn(async move { remove_file(&dir) })
            .detach();
        self.transit = Transit::default();
        self.set_feed(None);
        self.show_pill("Transit data removed.", None, cx);
        cx.notify();
    }

    /// Takes a new (or no) feed. Indices into the old one are dropped.
    pub(crate) fn set_feed(&mut self, feed: Option<Arc<Feed>>) {
        self.gtfs = feed;
        self.stop_hits = Default::default();
        self.stop_pattern = None;
        self.map.pattern = None;
        for dest in [&mut self.ends.a, &mut self.ends.b].into_iter().flatten() {
            dest.pattern = None;
        }
        if matches!(self.sheet_mode, crate::picker::SheetMode::Stop(_)) {
            self.sheet_mode = crate::picker::SheetMode::Pick;
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use std::io::Write;

    use rovsti_geo::{Coord, Pattern, Stop};
    use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

    use super::*;
    use crate::store::tests::tmp;

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
        assert_eq!(date_text(nov5_2025, nov5_2025 + 5_000), "5 Nov");
        assert_eq!(date_text(nov5_2024, nov5_2025), "5 Nov 2024");
        assert_eq!(date_text(0, nov5_2025), "1 Jan 1970");
        // Leap day, and the last second of a year.
        assert_eq!(date_text(1_709_164_800_000, nov5_2024), "29 Feb");
        assert_eq!(date_text(1_735_689_599_000, nov5_2024), "31 Dec");
        let f = feed();
        assert_eq!(imported_text(&f, 0, nov5_2024), "Imported 5 Nov");
        assert_eq!(
            imported_text(&f, 1_200, nov5_2024),
            "Imported 5 Nov, 1 200 unusable rows skipped"
        );
    }

    fn zip_bytes(files: &[(&str, &str)]) -> Vec<u8> {
        let mut zip = ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
        for (name, body) in files {
            zip.start_file(*name, options).unwrap();
            zip.write_all(body.as_bytes()).unwrap();
        }
        zip.finish().unwrap().into_inner()
    }

    const FEED_FILES: [(&str, &str); 4] = [
        (
            "stops.txt",
            "stop_id,stop_name,stop_lat,stop_lon\nA,Alpha,41.00,29.00\nB,Beta,41.01,29.00\nC,Gamma,41.02,29.00\n",
        ),
        (
            "routes.txt",
            "route_id,route_short_name,route_long_name,route_type\nr1,34,Line,3\n",
        ),
        (
            "trips.txt",
            "route_id,service_id,trip_id,trip_headsign\nr1,s,t1,Gamma\n",
        ),
        (
            "stop_times.txt",
            "trip_id,stop_id,stop_sequence\nt1,A,1\nt1,B,2\nt1,C,3\n",
        ),
    ];

    #[test]
    fn an_import_is_saved_and_the_picked_copy_removed() {
        let dir = tmp("transit-import");
        let picked = dir.join("picked.zip");
        std::fs::write(&picked, zip_bytes(&FEED_FILES)).unwrap();
        let (feed, report) = import_file(&dir, &picked).unwrap();
        assert_eq!((report.stops, report.routes, report.patterns), (3, 1, 1));
        assert_eq!(feed_summary(&feed), "3 stops, 1 line");
        assert!(!picked.exists());
        let saved = crate::backend::load(&dir).gtfs.unwrap();
        assert_eq!(saved.stops.len(), 3);
        assert_eq!(saved.imported_ms, feed.imported_ms);
        // The saved feed answers queries (the index is rebuilt after loading).
        assert_eq!(saved.patterns_through(2), vec![0]);
        remove_file(&dir);
        assert!(crate::backend::load(&dir).gtfs.is_none());
        // Removing again is harmless.
        remove_file(&dir);
    }

    #[test]
    fn import_errors_are_the_feeds_own_words() {
        let dir = tmp("transit-errors");
        let bad = dir.join("bad.zip");
        std::fs::write(&bad, b"this is not a zip").unwrap();
        let error = import_file(&dir, &bad).unwrap_err();
        assert!(error.starts_with("not a readable zip"), "{error}");
        assert!(!bad.exists());

        let partial = dir.join("partial.zip");
        std::fs::write(&partial, zip_bytes(&FEED_FILES[1..])).unwrap();
        assert_eq!(
            import_file(&dir, &partial).unwrap_err(),
            "missing stops.txt"
        );

        let missing = import_file(&dir, &dir.join("nope.zip")).unwrap_err();
        assert!(missing.starts_with("Could not open the file"), "{missing}");
        // A failed import leaves no feed behind.
        assert!(crate::backend::load(&dir).gtfs.is_none());
    }
}
