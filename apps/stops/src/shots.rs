//! Headless screenshots of Rovsti Stops (`docs/screenshots.md`).
//!
//! `cargo test -p rovsti_stops --features shots shots -- --ignored --nocapture`
//!
//! The data directory is seeded before the app starts: saved stops, history
//! and prefs. The prefs point the tile server at a closed local port, so no
//! tile is ever downloaded and the map shows its offline look. They also carry
//! the shot's appearance, because Stops sets the theme from its own prefs.

use std::path::{Path, PathBuf};

use futures::channel::mpsc;
use gpui::{AppContext as _, Context, Window};
use rovsti_geo::{
    Appearance, Coord, Endpoint, History, Outcome, SavedStops, StopColour, StopsPrefs, TripRecord,
};
use rovsti_ui::shots::{PHONE, Rig, SMALL_PHONE, ShotSpec, contact_sheet, isolate_data_dir};
use rovsti_ui::tokens::Theme;

use crate::{
    StopsApp, backend, pick::dest_from_endpoint, picker::Slot, settings_ui::SettingsPage, store,
};

/// The instant every shot is taken at: 2026-11-07T15:40:00+03:00.
pub(crate) const SHOT_NOW_MS: i64 = 1_794_055_200_000;

const SAVED_TAB: usize = 1;
const SETTINGS_TAB: usize = 2;

/// A tile server nobody listens on (port 9 refuses the connection).
const OFFLINE_TILES: &str = "http://127.0.0.1:9/{z}/{x}/{y}.pbf";

/// Pure fixture data: what the app is seeded with.
mod fixture {
    use super::*;

    /// One saved stop: name, the two gate labels, centre, colour, starred.
    pub(super) struct Place {
        pub name: &'static str,
        pub a: &'static str,
        pub b: &'static str,
        pub lat: f64,
        pub lon: f64,
        pub colour: StopColour,
        pub favourite: bool,
    }

    pub(super) const PLACES: [Place; 6] = [
        Place {
            name: "Kadıköy İskele",
            a: "Kadıköy İskele Girişi",
            b: "Kadıköy Çarşı",
            lat: 40.9905,
            lon: 29.0250,
            colour: StopColour::Blue,
            favourite: true,
        },
        Place {
            name: "Üsküdar Marmaray",
            a: "Üsküdar Meydanı",
            b: "Üsküdar Marmaray Çıkışı",
            lat: 41.0255,
            lon: 29.0150,
            colour: StopColour::Red,
            favourite: false,
        },
        Place {
            name: "Şişli Mecidiyeköy Metrobüs",
            a: "Mecidiyeköy Metrobüs",
            b: "Şişli Meydanı",
            lat: 41.0670,
            lon: 28.9950,
            colour: StopColour::Green,
            favourite: false,
        },
        Place {
            name: "Bakırköy Özgürlük Meydanı",
            a: "Özgürlük Meydanı",
            b: "Bakırköy Sahil",
            lat: 40.9810,
            lon: 28.8720,
            colour: StopColour::Orange,
            favourite: false,
        },
        Place {
            name: "Ataşehir Çağrı Merkezi",
            a: "Ataşehir Bulvarı",
            b: "Çağrı Merkezi Durağı",
            lat: 40.9920,
            lon: 29.1270,
            colour: StopColour::Blue,
            favourite: false,
        },
        Place {
            name: "İstanbul Havalimanı Havaist",
            a: "Havaist Peronu",
            b: "Gelen Yolcu Çıkışı",
            lat: 41.2750,
            lon: 28.7300,
            colour: StopColour::Red,
            favourite: true,
        },
    ];

    /// Half the gate's east-west span, in degrees of longitude (about 210 m).
    const HALF_SPAN_DEG: f64 = 0.0025;

    pub(super) fn endpoints(place: &Place) -> (Endpoint, Endpoint) {
        let end = |label: &str, lon: f64| Endpoint {
            label: label.to_owned(),
            coord: Coord::new(place.lat, lon),
        };
        (
            end(place.a, place.lon - HALF_SPAN_DEG),
            end(place.b, place.lon + HALF_SPAN_DEG),
        )
    }

    /// The six saved stops, created over the days before `now_ms`.
    pub(super) fn saved(now_ms: i64) -> SavedStops {
        let mut saved = SavedStops::default();
        for (i, place) in PLACES.iter().enumerate() {
            let (a, b) = endpoints(place);
            let created = now_ms - (PLACES.len() - i) as i64 * rovsti_geo::DAY_MS;
            let id = saved
                .add(place.name, a, b, created)
                .expect("the fixture gate is valid");
            if place.favourite {
                saved.toggle_favourite(id);
            }
            saved.set_colour(id, place.colour);
        }
        saved
    }

    /// Five finished trips, newest first: (place index, days ago, outcome,
    /// minutes, km, snoozes, text sent).
    const TRIPS: [(usize, i64, Outcome, i64, f64, u8, bool); 5] = [
        (0, 0, Outcome::Arrived, 38, 11.4, 1, true),
        (1, 1, Outcome::Arrived, 24, 6.8, 0, false),
        (2, 3, Outcome::Cancelled, 6, 1.9, 0, false),
        (5, 6, Outcome::Arrived, 71, 42.0, 2, true),
        (3, 12, Outcome::Cancelled, 12, 4.2, 0, false),
    ];

    pub(super) fn history(now_ms: i64) -> History {
        let mut history = History::default();
        for (n, &(place, days, outcome, minutes, km, snoozes, sent)) in TRIPS.iter().enumerate() {
            let (a, b) = endpoints(&PLACES[place]);
            let ended_ms = now_ms - days * rovsti_geo::DAY_MS - 3_600_000;
            history.records.push(TripRecord {
                id: 100 + n as u64,
                label: format!("{} to {}", a.label, b.label),
                a: Some(a),
                b: Some(b),
                destination: None,
                started_ms: ended_ms - minutes * 60_000,
                ended_ms,
                outcome,
                travelled_m: km * 1_000.0,
                snoozes,
                notice_sent: sent,
            });
        }
        history
    }

    /// The prefs the rig seeds: offline tiles and the shot's appearance.
    pub(super) fn prefs(theme: Theme) -> StopsPrefs {
        StopsPrefs {
            tile_url: Some(super::OFFLINE_TILES.to_owned()),
            appearance: match theme {
                Theme::Dark => Appearance::Dark,
                Theme::Light => Appearance::Light,
            },
            ..StopsPrefs::default()
        }
    }
}

/// Writes the fixture files where the app reads them on start.
fn seed(dir: &Path, theme: Theme) {
    use rovsti_ui::storage::save_json;
    let dir = dir.join("rovsti").join("stops");
    std::fs::create_dir_all(&dir).expect("create the app data dir");
    save_json(&dir, store::PREFS_FILE, &fixture::prefs(theme)).expect("seed prefs");
    save_json(&dir, store::SAVED_FILE, &fixture::saved(SHOT_NOW_MS)).expect("seed saved stops");
    save_json(&dir, store::HISTORY_FILE, &fixture::history(SHOT_NOW_MS)).expect("seed history");
}

fn open(spec: ShotSpec) -> Rig<StopsApp> {
    rovsti_core::clock::set_fixed_now_ms(Some(SHOT_NOW_MS));
    let (_tx, rx) = mpsc::unbounded();
    let mut rig = Rig::open(spec, move |window, cx| {
        cx.new(|cx| StopsApp::new(rx, window, cx))
    });
    rig.theme();
    rig
}

/// What a scene does to the settled app.
type Scene = fn(&mut StopsApp, &mut Window, &mut Context<StopsApp>);

/// The gate of the first fixture place, as the picked A and B.
fn place_ends(app: &mut StopsApp, a: bool, b: bool) {
    let (ea, eb) = fixture::endpoints(&fixture::PLACES[0]);
    if a {
        app.pick.place(Slot::A, dest_from_endpoint(&ea));
    }
    if b {
        app.pick.place(Slot::B, dest_from_endpoint(&eb));
    }
    app.map.follow = true;
}

/// Starts a trip on the first place's gate `ago_s` seconds before the shot
/// instant, then feeds it the simulation's fixes at the given seconds since
/// the start, through the same handler the Android service calls.
fn run_trip(app: &mut StopsApp, cx: &mut Context<StopsApp>, ago_s: i64, fixes_s: &[i64]) {
    let dir = app.dir.clone().expect("the app has loaded its data dir");
    let (a, b) = fixture::endpoints(&fixture::PLACES[0]);
    let started = SHOT_NOW_MS - ago_s * 1_000;
    let trip = backend::build_trip(7_001, a, b, started, None).expect("the gate is valid");
    store::save_trip(&dir, &trip).expect("write the trip");
    app.pick.clear();
    app.set_saved_trip(Some(trip), cx);
    app.map.follow = true;
    for &s in fixes_s {
        rovsti_core::clock::set_fixed_now_ms(Some(started + s * 1_000));
        if let Some(json) = backend::simulate_tick(&dir) {
            app.on_status_json(&json, cx);
        }
    }
    rovsti_core::clock::set_fixed_now_ms(Some(SHOT_NOW_MS));
}

/// Every main screen and important sheet, in shot order.
const SCENES: [(u32, &str, Scene); 14] = [
    (1, "trip-idle", |_, _, _| {}),
    (2, "trip-a-set", |app, _, _| place_ends(app, true, false)),
    (3, "trip-a-b-set", |app, _, _| place_ends(app, true, true)),
    (4, "trip-running", |app, _, cx| {
        run_trip(app, cx, 28, &[12, 16, 20, 24, 28]);
    }),
    (5, "alarm-ringing", |app, _, cx| {
        run_trip(app, cx, 48, &[28, 32, 36, 40, 44, 48]);
    }),
    (6, "saved-list", |app, _, _| app.tabs.jump_to(SAVED_TAB)),
    // 06b and 06c: the row "..." menu and the header "..." menu.
    (6, "saved-row-menu", |app, _, _| app.tabs.jump_to(SAVED_TAB)),
    (6, "saved-more-menu", |app, _, _| {
        app.tabs.jump_to(SAVED_TAB)
    }),
    (7, "saved-stop-sheet", |app, _, cx| {
        app.tabs.jump_to(SAVED_TAB);
        let id = app.saved.stops[0].id;
        app.open_saved_sheet(id, cx);
    }),
    (8, "history", |app, _, cx| {
        app.tabs.jump_to(SETTINGS_TAB);
        app.open_settings(SettingsPage::History, cx);
    }),
    (9, "map-options", |app, _, cx| app.open_map_options(cx)),
    (10, "pick", |app, _, cx| app.open_picker(cx)),
    (11, "settings", |app, _, _| app.tabs.jump_to(SETTINGS_TAB)),
    (12, "qr-sheet", |app, _, cx| {
        let (a, _) = fixture::endpoints(&fixture::PLACES[0]);
        app.open_qr(fixture::PLACES[0].name, a.coord, cx);
    }),
];

/// What a scene does once its first frames are painted: the anchors of a menu
/// are only known after the tab rendered.
fn follow_up(screen: &str) -> Option<Scene> {
    match screen {
        "saved-row-menu" => Some(|app, _, cx| {
            let id = app.saved.stops[1].id;
            app.open_row_menu_for_shot(id, cx);
        }),
        "saved-more-menu" => Some(|app, _, cx| app.open_more_menu_for_shot(cx)),
        _ => None,
    }
}

fn render(spec: ShotSpec, scene: Scene) -> PathBuf {
    // Trips, prefs and drafts persist; every shot starts from a fresh dir.
    let root = isolate_data_dir("stops");
    seed(&root, spec.theme);
    let screen = spec.screen;
    let mut rig = open(spec);
    // The seeded files arrive first; scenes act on the settled app.
    rig.settle();
    let root = rig.root.clone();
    rig.update(|cx, window| root.update(cx, |app, cx| scene(app, window, cx)));
    rig.settle();
    if let Some(next) = follow_up(screen) {
        rig.update(|cx, window| root.update(cx, |app, cx| next(app, window, cx)));
        rig.settle();
    }
    rig.save()
}

#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn shots() {
    let mut saved = Vec::new();
    for &(nn, screen, scene) in &SCENES {
        for theme in [Theme::Dark, Theme::Light] {
            let spec = ShotSpec {
                app: "stops",
                nn,
                screen,
                theme,
                size: PHONE,
            };
            let path = render(spec, scene);
            println!("{}", path.display());
            saved.push(path);
        }
    }
    let small = ShotSpec {
        app: "stops",
        nn: 1,
        screen: "trip-idle",
        theme: Theme::Dark,
        size: SMALL_PHONE,
    };
    let path = render(small, SCENES[0].2);
    println!("{}", path.display());
    saved.push(path);
    let sheet = contact_sheet("stops", &saved).expect("write the contact sheet");
    println!("{}", sheet.display());
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn scenes_are_numbered_in_order_with_unique_names() {
        // The a/b/c variants of a screen share its number.
        let mut last = 0;
        for (nn, name, _) in &SCENES {
            assert!(*nn == last || *nn == last + 1, "{name}");
            last = *nn;
            assert!(SCENES.iter().filter(|s| s.1 == *name).count() == 1);
        }
    }

    #[test]
    fn the_run_writes_two_themes_per_scene_and_one_small_shot() {
        assert_eq!(SCENES.len() * 2 + 1, 29);
    }

    #[test]
    fn six_saved_stops_with_turkish_names_and_every_colour() {
        let saved = fixture::saved(SHOT_NOW_MS);
        assert_eq!(saved.stops.len(), 6);
        for colour in StopColour::ALL {
            assert!(saved.stops.iter().any(|s| s.colour == colour), "{colour:?}");
        }
        assert!(saved.stops.iter().any(|s| s.name == "Kadıköy İskele"));
        assert!(saved.stops.iter().any(|s| s.name.contains("Üsküdar")));
        assert_eq!(saved.stops.iter().filter(|s| s.favourite).count(), 2);
        let mut ids: Vec<_> = saved.stops.iter().map(|s| s.id).collect();
        ids.dedup();
        assert_eq!(ids.len(), 6);
    }

    #[test]
    fn every_fixture_gate_is_a_valid_gate_of_a_few_hundred_metres() {
        for place in &fixture::PLACES {
            let (a, b) = fixture::endpoints(place);
            let metres = a.coord.distance_m(b.coord);
            assert!((300.0..600.0).contains(&metres), "{}: {metres}", place.name);
            assert!(rovsti_geo::Diameter::new(a.coord, b.coord).is_ok());
        }
    }

    #[test]
    fn five_trips_newest_first_with_both_outcomes() {
        let history = fixture::history(SHOT_NOW_MS);
        assert_eq!(history.records.len(), 5);
        assert!(
            history
                .records
                .windows(2)
                .all(|w| w[0].ended_ms > w[1].ended_ms)
        );
        assert!(
            history
                .records
                .iter()
                .any(|r| r.outcome == Outcome::Arrived)
        );
        assert!(
            history
                .records
                .iter()
                .any(|r| r.outcome == Outcome::Cancelled)
        );
        assert!(history.records.iter().all(|r| r.pair().is_some()));
        assert!(history.records.iter().all(|r| r.duration_ms() > 0));
    }

    #[test]
    fn the_seeded_prefs_are_offline_and_follow_the_theme() {
        for (theme, want) in [
            (Theme::Dark, Appearance::Dark),
            (Theme::Light, Appearance::Light),
        ] {
            let prefs = fixture::prefs(theme);
            assert_eq!(prefs.appearance, want);
            let source = prefs.tile_source();
            assert_eq!(source.template(), OFFLINE_TILES);
            assert!(!source.is_default());
        }
    }

    #[test]
    fn the_seeded_files_read_back() {
        let dir = std::env::temp_dir().join(format!("rovsti-stops-seed-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        seed(&dir, Theme::Dark);
        let data = backend::load(&dir.join("rovsti").join("stops"));
        assert_eq!(data.saved.stops.len(), 6);
        assert_eq!(data.history.records.len(), 5);
        assert_eq!(data.prefs.appearance, Appearance::Dark);
        assert!(data.trip.is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
