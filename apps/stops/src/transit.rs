//! Transit data: importing a GTFS feed, and the line-aware "N stops before"
//! alarm built from it.
//!
//! The feed never leaves the phone. Kotlin copies the file the user picked into
//! the app cache and answers with a `document_result` (op `import_gtfs`); the
//! feed is parsed on the background executor, saved as `gtfs.json` and the
//! cache copy is deleted. The pure pieces (the lines through a stop, the
//! report row, the date) are `rosty_geo::transit`.

use std::{fs::File, path::Path, sync::Arc};

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px,
};
use rosty_android::{Error as AndroidError, documents};
use rosty_core::wire::DocumentResult;
use rosty_geo::{Feed, FeedReport, read_zip};
// The pure pieces (lines through a stop, the report row, the date) live in `rosty_geo::transit`, shared with the
// Kotlin app through `stops.gtfs_lines` and `stops.gtfs_summary`.
pub use rosty_geo::transit::{
    LineChoice, MAX_LINES, date_text, earlier_text, feed_summary, imported_text, line_title,
    lines_through,
};
use rosty_ui::{
    components::{list_row, section},
    storage, theme,
    tokens::{Theme, typography},
};

use crate::{StopsApp, store};

/// The biggest feed the picker copies in (Kotlin enforces the same cap).
const MAX_FEED_BYTES: u64 = 64 * 1024 * 1024;

// ---- background: reading the picked file --------------------------------------------

/// Parses the feed at `path`, saves it as `gtfs.json` in `dir` and deletes
/// the picked copy. Errors are the sentence to show, `GtfsError` verbatim.
pub fn import_file(dir: &Path, path: &Path) -> Result<(Feed, FeedReport), String> {
    let result = File::open(path)
        .map_err(|e| {
            log::warn!("could not open the picked feed: {e}");
            "Could not open the file. Pick it again.".to_owned()
        })
        .and_then(|file| read_zip(file).map_err(|e| e.to_string()))
        .and_then(|(feed, report)| {
            storage::save_json(dir, store::GTFS_FILE, &feed).map_err(|e| {
                log::warn!("could not save the feed: {e}");
                "Could not save the feed on the phone. Free some space and try again.".to_owned()
            })?;
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
                            imported_text(
                                feed,
                                self.transit.skipped_rows,
                                store::now_ms(),
                                self.tz_offset_s,
                            )
                            .into(),
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
                let armed = self.confirm_remove_feed;
                let mut remove = list_row(
                    theme,
                    None,
                    if armed {
                        "Tap again to remove"
                    } else {
                        "Remove transit data"
                    },
                    None,
                    Some("Remove".into()),
                )
                .id("feed-remove");
                if armed {
                    remove = remove.text_color(theme::colors(theme).red);
                }
                rows.push(
                    remove
                        .on_click(cx.listener(|this, _, _, cx| {
                            if this.confirm_remove_feed {
                                this.remove_feed(cx);
                            } else {
                                this.confirm_remove_feed = true;
                                cx.notify();
                            }
                        }))
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
                "With a feed, stops and their lines show on the map and in search, and any stop can be A or B.",
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
                Err(error) => {
                    log::warn!("the file picker could not open: {error}");
                    Some("The file picker could not open.".to_owned())
                }
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
                        this.confirm_remove_feed = false;
                        this.transit.error = None;
                        this.transit.skipped_rows = report.skipped_rows;
                        this.set_feed(Some(Arc::new(feed)));
                        this.show_pill("Transit data imported", None, cx);
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
        self.confirm_remove_feed = false;
        self.transit = Transit::default();
        self.set_feed(None);
        self.show_pill("Transit data removed", None, cx);
        cx.notify();
    }

    /// Takes a new (or no) feed. Indices into the old one are dropped.
    pub(crate) fn set_feed(&mut self, feed: Option<Arc<Feed>>) {
        self.gtfs = feed;
        self.stop_hits = Default::default();
        self.stop_pattern = None;
        self.map.pattern = None;
        self.map.forget_scene();
        for dest in [&mut self.pick.ends.a, &mut self.pick.ends.b]
            .into_iter()
            .flatten()
        {
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

    use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

    use super::*;
    use crate::store::test_support::tmp;

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
