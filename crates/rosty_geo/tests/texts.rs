//! Every sentence this crate writes for people, in English and in Turkish, with its edge cases.

#![allow(clippy::unwrap_used)]

use std::time::Duration;

use rosty_geo::coord_format::{self, CoordFormat};
use rosty_geo::history::{self, Outcome, OutcomeFilter, TripRecord, Usage};
use rosty_geo::saved_stops::{self, SavedStop, SavedStopError, SavedStops, StopColour};
use rosty_geo::{
    ActiveTrip, Appearance, Coord, DestError, DiameterError, Endpoint, Feed, Lang, Measure,
    NoticeWhen, Side, StopsPrefs, VibrationPattern, backup, history_io, notice, places_io, transit,
};

const EN: Lang = Lang::En;
const TR: Lang = Lang::Tr;

/// 3 hours east of UTC, as in Turkey.
const TZ: i32 = 10_800;
/// 5 Nov 2026 12:00 UTC.
const NOV_5: i64 = 1_793_880_000_000;
const DAY: i64 = 86_400_000;

fn end(label: &str, lon: f64) -> Endpoint {
    Endpoint {
        label: label.into(),
        coord: Coord::new(41.0, lon),
    }
}

#[test]
fn distances() {
    for (metres, en, tr) in [
        (0.0, "0 m", "0 m"),
        (4.9, "0 m", "0 m"),
        (5.0, "10 m", "10 m"),
        (850.0, "850 m", "850 m"),
        (994.9, "990 m", "990 m"),
        // Rounded before the unit is chosen: never "1000 m".
        (995.0, "1.0 km", "1,0 km"),
        (999.9, "1.0 km", "1,0 km"),
        (1_000.0, "1.0 km", "1,0 km"),
        (1_249.0, "1.2 km", "1,2 km"),
        (12_345.0, "12.3 km", "12,3 km"),
        (-30.0, "0 m", "0 m"),
        (f64::NAN, "0 m", "0 m"),
        (f64::INFINITY, "0 m", "0 m"),
        (f64::NEG_INFINITY, "0 m", "0 m"),
    ] {
        assert_eq!(rosty_geo::format_distance_in(EN, metres), en, "{metres}");
        assert_eq!(rosty_geo::format_distance_in(TR, metres), tr, "{metres}");
        assert_eq!(rosty_geo::format_distance(metres), en);
    }
}

#[test]
fn compass_points() {
    let points = [
        (0.0, "N", "K"),
        (45.0, "NE", "KD"),
        (90.0, "E", "D"),
        (135.0, "SE", "GD"),
        (180.0, "S", "G"),
        (225.0, "SW", "GB"),
        (270.0, "W", "B"),
        (315.0, "NW", "KB"),
        (359.9, "N", "K"),
        (-90.0, "W", "B"),
        (f64::NAN, "N", "K"),
    ];
    for (deg, en, tr) in points {
        assert_eq!(coord_format::cardinal_in(EN, deg), en);
        assert_eq!(coord_format::cardinal_in(TR, deg), tr);
    }
}

#[test]
fn from_you_and_the_map_view() {
    let here = Coord::new(41.0, 29.0);
    let north_east = Coord::new(41.004, 29.005);
    assert_eq!(
        coord_format::from_you_text_in(EN, here, north_east),
        "610 m from you, NE"
    );
    assert_eq!(
        coord_format::from_you_text_in(TR, here, north_east),
        "610 m uzağınızda, KD"
    );
    let far = Coord::new(41.0, 29.03);
    assert_eq!(
        coord_format::from_you_text_in(TR, here, far),
        "2,5 km uzağınızda, D"
    );
    assert_eq!(
        coord_format::from_you_text_in(EN, here, here),
        "You are here"
    );
    assert_eq!(
        coord_format::from_you_text_in(TR, here, here),
        "Buradasınız"
    );
    let broken = Coord::new(f64::NAN, 29.0);
    assert_eq!(
        coord_format::from_you_text_in(TR, here, broken),
        "Buradasınız"
    );
    let view = coord_format::map_view_share_text_in(TR, here, 14.0);
    assert!(view.starts_with("Harita görünümü\nhttps://"), "{view}");
    assert!(coord_format::map_view_share_text_in(EN, here, 14.0).starts_with("Map view\n"));
    assert_eq!(view.lines().count(), 3);
}

#[test]
fn menu_labels() {
    let formats: Vec<_> = CoordFormat::ALL.iter().map(|f| f.label_in(TR)).collect();
    assert_eq!(
        formats,
        [
            "Ondalık",
            "DMS",
            "DDM",
            "Plus Code",
            "geo: bağlantısı",
            "OpenStreetMap bağlantısı"
        ]
    );
    for f in CoordFormat::ALL {
        assert_eq!(f.label_in(EN), f.label());
    }
    let colours: Vec<_> = StopColour::ALL.iter().map(|c| c.label_in(TR)).collect();
    assert_eq!(colours, ["Turuncu", "Kırmızı", "Yeşil", "Mavi"]);
    assert_eq!(StopColour::Orange.label_in(EN), "Orange");
    let looks: Vec<_> = Appearance::ALL.iter().map(|a| a.label_in(TR)).collect();
    assert_eq!(looks, ["Otomatik", "Açık", "Koyu"]);
    assert_eq!(Appearance::Auto.label_in(EN), "Auto");
    let patterns: Vec<_> = VibrationPattern::ALL
        .iter()
        .map(|v| v.label_in(TR))
        .collect();
    assert_eq!(patterns, ["Standart", "Darbeli", "Uzun", "Kalp atışı"]);
    assert_eq!(VibrationPattern::Heartbeat.label_in(EN), "Heartbeat");
    assert_eq!(
        (Side::Left.label_in(TR), Side::Right.label_in(TR)),
        ("Sol", "Sağ")
    );
    assert_eq!(
        (Side::Left.label_in(EN), Side::Right.label_in(EN)),
        ("Left", "Right")
    );
    let filters = [
        OutcomeFilter::All,
        OutcomeFilter::Arrived,
        OutcomeFilter::Cancelled,
    ];
    assert_eq!(
        filters.map(|f| f.label_in(TR)),
        ["Tümü", "Varıldı", "İptal edildi"]
    );
    assert_eq!(
        filters.map(|f| f.label_in(EN)),
        ["All", "Arrived", "Cancelled"]
    );
    assert_eq!(
        NoticeWhen::WithAlarm.label_in(EN),
        "When you cross the line"
    );
    assert_eq!(NoticeWhen::WithAlarm.label_in(TR), "Çizgiyi geçtiğinizde");
    assert_eq!(
        NoticeWhen::MinutesBefore(10).label_in(EN),
        "10 min before the circle"
    );
    assert_eq!(
        NoticeWhen::MinutesBefore(10).label_in(TR),
        "Çembere 10 dk kala"
    );
    let prefs = StopsPrefs::default();
    assert_eq!(prefs.alarm_sound_label_in(EN), "Default alarm");
    assert_eq!(prefs.alarm_sound_label_in(TR), "Varsayılan alarm");
}

#[test]
fn error_sentences() {
    let gates = [
        (
            DiameterError::InvalidCoord,
            "Bu koordinatlar Dünya üzerinde değil.",
        ),
        (DiameterError::SamePoint, "A ve B aynı yer."),
        (
            DiameterError::TooShort,
            "A ile B arasında en az 20 m olmalı.",
        ),
        (
            DiameterError::TooLong,
            "A ile B arasında en fazla 50 km olmalı.",
        ),
    ];
    for (error, tr) in gates {
        assert_eq!(error.message_in(TR), tr);
        assert_eq!(error.message_in(EN), error.message());
        assert_eq!(SavedStopError::InvalidGate(error).message_in(TR), tr);
    }
    let links = [
        (
            DestError::NoCoordinates,
            "Bu bağlantıda koordinat yok. Bunun yerine harita uygulamanızdan konumu paylaşın.",
        ),
        (
            DestError::OutOfRange,
            "Bu koordinatlar Dünya üzerinde değil. Bağlantıyı kontrol edin.",
        ),
        (DestError::NotALocation, "Bu bir konuma benzemiyor."),
    ];
    for (error, tr) in links {
        assert_eq!(error.message_in(TR), tr);
        assert_eq!(error.message_in(EN), error.message());
    }
    let saved = [
        (
            SavedStopError::InvalidName,
            "Durak adı 1 ile 60 karakter arasında olmalı.",
        ),
        (SavedStopError::Duplicate(3), "Bu durağı zaten kaydettiniz."),
        (
            SavedStopError::Full,
            "200 durak kaydettiniz. Önce birini silin.",
        ),
        (SavedStopError::NotFound, "Bu durak artık yok."),
        (
            SavedStopError::NoteTooLong,
            "Notlar en fazla 200 karakter olabilir.",
        ),
    ];
    for (error, tr) in saved {
        assert_eq!(error.message_in(TR), tr);
        assert_eq!(error.message_in(EN), error.message());
    }
    let files = [
        (places_io::ImportError::Empty, "Bu dosya boş."),
        (
            places_io::ImportError::NotRecognised,
            "Bu dosya bir GPX ya da Rosty durak dosyası değil.",
        ),
        (
            places_io::ImportError::TooLarge,
            "Bu dosya çok büyük (en fazla 2 MB).",
        ),
    ];
    for (error, tr) in files {
        assert_eq!(error.message_in(TR), tr);
        assert_eq!(error.message_in(EN), error.message());
    }
    let backups = [
        (backup::BackupError::NotJson, "Bu dosya bir yedek değil"),
        (
            backup::BackupError::NotStops,
            "Bu yedek başka bir uygulamaya ait",
        ),
        (
            backup::BackupError::Newer,
            "Bu yedek daha yeni bir sürüme ait",
        ),
        (
            backup::BackupError::BadStops,
            "Bu yedekteki kayıtlı duraklar bozuk",
        ),
    ];
    for (error, tr) in backups {
        assert_eq!(error.message_in(TR), tr);
        assert_eq!(error.message_in(EN), error.message());
    }
    assert_eq!(
        notice::TemplateError::Empty.message_in(EN),
        "the message is empty"
    );
    assert_eq!(notice::TemplateError::Empty.message_in(TR), "Mesaj boş.");
    assert_eq!(
        notice::TemplateError::TooLong.message_in(EN),
        "the message is longer than 500 characters"
    );
    assert_eq!(
        notice::TemplateError::TooLong.message_in(TR),
        "Mesaj 500 karakterden uzun."
    );
}

#[test]
fn the_trip_status() {
    let label = "Pier West \u{2013} Pier East";
    let text = |lang, inside, side, line, circle, eta| {
        rosty_geo::status_text(lang, label, inside, side, line, circle, eta)
    };
    assert_eq!(
        text(EN, false, None, 0.0, 1_240.0, None),
        "Outside the circle, 1.2 km away"
    );
    assert_eq!(
        text(TR, false, None, 0.0, 1_240.0, None),
        "Çemberin dışında, 1,2 km uzakta"
    );
    assert_eq!(
        text(TR, false, Some(Side::Left), 0.0, 400.0, Some(7)),
        "Çemberin dışında, 400 m uzakta, yaklaşık 7 dk"
    );
    assert_eq!(
        text(EN, true, Some(Side::Left), 420.0, 0.0, Some(3)),
        "Left side, 420 m to the line Pier West \u{2013} Pier East, about 3 min"
    );
    assert_eq!(
        text(TR, true, Some(Side::Left), 420.0, 0.0, Some(3)),
        "Sol taraf, Pier West \u{2013} Pier East çizgisine 420 m, yaklaşık 3 dk"
    );
    assert_eq!(
        text(TR, true, Some(Side::Right), 0.0, 0.0, Some(1)),
        "Sağ taraf, Pier West \u{2013} Pier East çizgisine 0 m, yaklaşık 1 dk"
    );
    assert_eq!(text(EN, true, None, 0.0, 0.0, None), "On the line");
    assert_eq!(text(TR, true, None, 0.0, 0.0, None), "Çizginin üzerinde");
    assert_eq!(
        text(TR, false, None, 0.0, f64::NAN, None),
        "Çemberin dışında, 0 m uzakta"
    );
    assert_eq!(
        rosty_geo::crossed_title(EN, label),
        "Crossed Pier West \u{2013} Pier East"
    );
    assert_eq!(
        rosty_geo::crossed_title(TR, label),
        "Çizgi geçildi: Pier West \u{2013} Pier East"
    );
}

#[test]
fn a_trip_writes_its_status_in_its_own_language() {
    let mut trip = ActiveTrip::new(1, end("Home", 28.994), end("Work", 29.006), 0).unwrap();
    let far = rosty_geo::Fix {
        at: Coord::new(41.0, 28.90),
        accuracy_m: 10.0,
        speed_mps: None,
    };
    let outcome = trip.on_fix(far, 1_000);
    let english = trip.fix_out(&outcome);
    assert!(
        english.text.starts_with("Outside the circle, "),
        "{}",
        english.text
    );
    trip.lang = Lang::Tr;
    let turkish = trip.fix_out(&outcome);
    assert!(
        turkish.text.starts_with("Çemberin dışında, "),
        "{}",
        turkish.text
    );
    assert!(turkish.text.ends_with(" km uzakta"), "{}", turkish.text);
    assert_eq!(turkish.title, "Home \u{2013} Work");
    assert_eq!(trip.fix_out_in(Lang::En, &outcome).text, english.text);
    // The language is kept with the trip, and a trip stored before it existed is English.
    let stored = serde_json::to_value(&trip).unwrap();
    assert_eq!(stored["lang"], "tr");
    let back: ActiveTrip = serde_json::from_value(stored.clone()).unwrap();
    assert_eq!(back.lang, Lang::Tr);
    let mut old = stored;
    old.as_object_mut().unwrap().remove("lang");
    assert_eq!(
        serde_json::from_value::<ActiveTrip>(old).unwrap().lang,
        Lang::En
    );
    trip.lang = Lang::En;
    assert!(serde_json::to_value(&trip).unwrap().get("lang").is_none());
}

#[test]
fn the_shared_status() {
    let here = Coord::new(41.0, 29.0);
    let target = Coord::new(41.004, 29.005);
    let share = |lang, line, eta, here, crossed, arrive| {
        let text =
            rosty_geo::status_share_text_in(lang, "Pier", line, eta, here, target, crossed, arrive);
        let (head, link) = text.split_once('\n').unwrap();
        assert!(link.starts_with("geo:"), "{link}");
        head.to_owned()
    };
    assert_eq!(share(EN, None, None, None, true, None), "Arrived at Pier.");
    assert_eq!(share(TR, None, None, None, true, None), "Vardım: Pier.");
    assert_eq!(
        share(EN, None, None, None, false, None),
        "On my way to Pier."
    );
    assert_eq!(
        share(TR, None, None, None, false, None),
        "Yoldayım, hedef Pier."
    );
    assert_eq!(
        share(EN, Some(420.0), Some(3), Some(here), false, Some("14:05")),
        "On my way to Pier: 420 m to the line, about 3 min (around 14:05), heading NE."
    );
    assert_eq!(
        share(TR, Some(420.0), Some(3), Some(here), false, Some("14:05")),
        "Yoldayım, hedef Pier: çizgiye 420 m, yaklaşık 3 dk (saat 14:05 gibi), yön KD."
    );
    assert_eq!(
        share(TR, Some(2_340.0), Some(0), None, false, None),
        "Yoldayım, hedef Pier: çizgiye 2,3 km, yaklaşık 1 dk."
    );
    // A broken distance is left out, not written.
    assert_eq!(
        share(TR, Some(f64::NAN), None, None, false, None),
        "Yoldayım, hedef Pier."
    );
}

#[test]
fn durations_snoozes_and_the_summary_line() {
    for (ms, en, tr) in [
        (0, "under 1 min", "1 dk'dan kısa"),
        (59_999, "under 1 min", "1 dk'dan kısa"),
        (60_000, "1 min", "1 dk"),
        (18 * 60_000, "18 min", "18 dk"),
        (59 * 60_000, "59 min", "59 dk"),
        (60 * 60_000, "1 h 00 min", "1 sa 00 dk"),
        (65 * 60_000, "1 h 05 min", "1 sa 05 dk"),
        (25 * 60 * 60_000, "25 h 00 min", "25 sa 00 dk"),
    ] {
        assert_eq!(history::format_duration_in(EN, ms), en);
        assert_eq!(history::format_duration_in(TR, ms), tr);
    }
    assert_eq!(history::snoozes_text_in(TR, 0), None);
    assert_eq!(history::snoozes_text_in(EN, 1).as_deref(), Some("1 snooze"));
    assert_eq!(
        history::snoozes_text_in(TR, 1).as_deref(),
        Some("1 erteleme")
    );
    assert_eq!(
        history::snoozes_text_in(EN, 3).as_deref(),
        Some("3 snoozes")
    );
    assert_eq!(
        history::snoozes_text_in(TR, 3).as_deref(),
        Some("3 erteleme")
    );
    let record = TripRecord {
        started_ms: 0,
        ended_ms: 18 * 60_000,
        travelled_m: 3_400.0,
        snoozes: 1,
        ..TripRecord::default()
    };
    assert_eq!(
        record.summary_line_in(EN),
        "18 min \u{b7} 3.4 km \u{b7} 1 snooze"
    );
    assert_eq!(
        record.summary_line_in(TR),
        "18 dk \u{b7} 3,4 km \u{b7} 1 erteleme"
    );
    let odd = TripRecord {
        travelled_m: -5.0,
        ..TripRecord::default()
    };
    assert_eq!(odd.summary_line_in(TR), "1 dk'dan kısa \u{b7} 0 m");
}

#[test]
fn trip_titles_and_generic_names() {
    let named = TripRecord {
        a: Some(end("Home", 28.994)),
        b: Some(end("Work", 29.006)),
        ..TripRecord::default()
    };
    assert_eq!(named.title_in(TR), "Home \u{2013} Work");
    // An end without a name, or stored under the generic name of either language, reads in the asked one.
    for (a, b) in [
        ("", " "),
        ("Point A", "Point B"),
        ("A noktası", "B noktası"),
    ] {
        let record = TripRecord {
            a: Some(end(a, 28.994)),
            b: Some(end(b, 29.006)),
            ..TripRecord::default()
        };
        assert_eq!(record.title_in(EN), "Point A \u{2013} Point B");
        assert_eq!(record.title_in(TR), "A noktası \u{2013} B noktası");
    }
    let legacy = TripRecord {
        label: " Ferry ".into(),
        ..TripRecord::default()
    };
    assert_eq!(legacy.title_in(TR), "Ferry");
    assert_eq!(TripRecord::default().title_in(EN), "Trip");
    assert_eq!(TripRecord::default().title_in(TR), "Yolculuk");
    assert_eq!(history::point_name(EN, "A"), "Point A");
    assert_eq!(history::point_name(TR, "B"), "B noktası");
    for generic in [
        "",
        "  ",
        "Point",
        "Point A",
        "Point B",
        "Nokta",
        "A noktası",
        " B noktası ",
    ] {
        assert!(history::is_generic_label(generic), "{generic:?}");
    }
    for name in ["Pointe", "A", "Nokta 1", "Work"] {
        assert!(!history::is_generic_label(name), "{name:?}");
    }
}

#[test]
fn usage_and_prune() {
    let usage =
        |lang, count, last_ms| history::usage_text_in(lang, Usage { count, last_ms }, NOV_5, TZ);
    assert_eq!(usage(EN, 1, NOV_5), "Used once, today");
    assert_eq!(usage(TR, 1, NOV_5), "1 kez kullanıldı, bugün");
    assert_eq!(usage(EN, 4, NOV_5 - DAY), "Used 4 times, last yesterday");
    assert_eq!(usage(TR, 4, NOV_5 - DAY), "4 kez kullanıldı, en son dün");
    assert_eq!(usage(EN, 4, NOV_5 - 4 * DAY), "Used 4 times, last 1 Nov");
    assert_eq!(
        usage(TR, 4, NOV_5 - 4 * DAY),
        "4 kez kullanıldı, en son 1 Kas"
    );
    assert_eq!(
        usage(TR, 2, NOV_5 - 400 * DAY),
        "2 kez kullanıldı, en son 1 Eki 2025"
    );
    assert_eq!(usage(TR, 0, NOV_5), "0 kez kullanıldı, en son bugün");
    assert_eq!(
        history::prune_label_in(EN, 1, 90),
        "Delete 1 trip older than 90 days"
    );
    assert_eq!(
        history::prune_label_in(EN, 12, 30),
        "Delete 12 trips older than 30 days"
    );
    assert_eq!(
        history::prune_label_in(TR, 1, 90),
        "90 günden eski 1 yolculuğu sil"
    );
    assert_eq!(
        history::prune_label_in(TR, 12, 30),
        "30 günden eski 12 yolculuğu sil"
    );
    assert_eq!(
        history::prune_label_in(TR, 0, 30),
        "30 günden eski 0 yolculuğu sil"
    );
}

#[test]
fn a_finished_trip_shared() {
    let record = TripRecord {
        a: Some(end("Home", 28.994)),
        b: Some(end("Work", 29.006)),
        started_ms: NOV_5 - 18 * 60_000,
        ended_ms: NOV_5,
        outcome: Outcome::Arrived,
        travelled_m: 3_400.0,
        ..TripRecord::default()
    };
    let head = |lang, record: &TripRecord| {
        let text = history_io::share_text_in(lang, record, TZ);
        assert_eq!(text.lines().count(), 2, "{text}");
        text.lines().next().unwrap().to_owned()
    };
    assert_eq!(
        head(EN, &record),
        "Arrived at Home \u{2013} Work on 5 Nov 2026, 15:00. 18 min \u{b7} 3.4 km."
    );
    assert_eq!(
        head(TR, &record),
        "Varış: Home \u{2013} Work, 5 Kas 2026, 15:00. 18 dk \u{b7} 3,4 km."
    );
    let stopped = TripRecord {
        outcome: Outcome::Cancelled,
        snoozes: 2,
        ..record
    };
    assert_eq!(
        head(EN, &stopped),
        "Trip to Home \u{2013} Work stopped on 5 Nov 2026, 15:00. 18 min \u{b7} 3.4 km \u{b7} 2 snoozes."
    );
    assert_eq!(
        head(TR, &stopped),
        "Durdurulan yolculuk: Home \u{2013} Work, 5 Kas 2026, 15:00. 18 dk \u{b7} 3,4 km \u{b7} 2 erteleme."
    );
}

fn feed(stops: usize, routes: usize) -> Feed {
    let stops: Vec<_> = (0..stops)
        .map(|i| {
            serde_json::json!({"id": format!("s{i}"), "name": format!("Stop {i}"),
                "coord": {"lat": 41.0, "lon": 29.0 + i as f64 * 0.001}, "parent": null})
        })
        .collect();
    let routes: Vec<_> = (0..routes)
        .map(|i| {
            serde_json::json!({"id": format!("r{i}"), "short_name": i.to_string(),
                "long_name": "", "kind": 3})
        })
        .collect();
    serde_json::from_value(serde_json::json!({
        "stops": stops, "routes": routes, "patterns": [], "imported_ms": NOV_5,
    }))
    .unwrap()
}

#[test]
fn the_transit_feed() {
    assert_eq!(
        transit::feed_summary_in(EN, &feed(1_234, 87)),
        "1 234 stops, 87 lines"
    );
    assert_eq!(
        transit::feed_summary_in(TR, &feed(1_234, 87)),
        "1.234 durak, 87 hat"
    );
    assert_eq!(transit::feed_summary_in(EN, &feed(1, 1)), "1 stop, 1 line");
    assert_eq!(transit::feed_summary_in(TR, &feed(1, 1)), "1 durak, 1 hat");
    assert_eq!(transit::feed_summary_in(TR, &feed(0, 0)), "0 durak, 0 hat");
    let small = feed(2, 1);
    assert_eq!(
        transit::imported_text_in(EN, &small, 0, NOV_5, TZ),
        "Imported 5 Nov"
    );
    assert_eq!(
        transit::imported_text_in(TR, &small, 0, NOV_5, TZ),
        "5 Kas tarihinde içe aktarıldı"
    );
    assert_eq!(
        transit::imported_text_in(EN, &small, 1_200, NOV_5 + 400 * DAY, TZ),
        "Imported 5 Nov 2026, 1 200 unusable rows skipped"
    );
    assert_eq!(
        transit::imported_text_in(TR, &small, 1_200, NOV_5 + 400 * DAY, TZ),
        "5 Kas 2026 tarihinde içe aktarıldı, kullanılamayan 1.200 satır atlandı"
    );
    assert_eq!(
        transit::date_text_in(TR, NOV_5 - 300 * DAY, NOV_5, TZ),
        "9 Oca"
    );
    assert_eq!(
        transit::line_title_in(EN, "34", "Kadıköy"),
        "Line 34 to Kadıköy"
    );
    assert_eq!(
        transit::line_title_in(TR, "34", "Kadıköy"),
        "Hat 34, Kadıköy yönü"
    );
    assert_eq!(transit::line_title_in(EN, "34", ""), "Line 34");
    assert_eq!(transit::line_title_in(TR, "34", ""), "Hat 34");
    assert_eq!(
        transit::earlier_text_in(EN, 1),
        "1 stop earlier on this line"
    );
    assert_eq!(transit::earlier_text_in(TR, 1), "Bu hatta 1 durak önce");
    assert_eq!(
        transit::earlier_text_in(EN, 3),
        "3 stops earlier on this line"
    );
    assert_eq!(transit::earlier_text_in(TR, 3), "Bu hatta 3 durak önce");
    assert_eq!(transit::earlier_text_in(TR, 0), "Bu hatta 0 durak önce");
    assert_eq!(
        transit::earlier_text_in(TR, 1_500),
        "Bu hatta 1.500 durak önce"
    );
}

fn stop(id: u64, name: &str, note: &str, favourite: bool) -> SavedStop {
    serde_json::from_value(serde_json::json!({
        "id": id, "name": name, "created_ms": 0, "favourite": favourite, "note": note,
        "a": {"label": "", "coord": {"lat": 41.0, "lon": 28.994}},
        "b": {"label": "", "coord": {"lat": 41.0, "lon": 29.006}},
    }))
    .unwrap()
}

#[test]
fn saved_stops_as_text() {
    let pier = stop(1, "Pier", "Back door", true);
    let ferry = stop(2, "Ferry", "", false);
    let here = Coord::new(41.0, 29.03);
    let en = saved_stops::list_text_in(EN, &[&pier, &ferry], Some(here));
    let tr = saved_stops::list_text_in(TR, &[&pier, &ferry], Some(here));
    assert!(
        en.starts_with(
            "Saved stops (2)\n\u{2605} Pier: 41.000000, 29.000000 (2.5 km away)\n  Back door\n"
        ),
        "{en}"
    );
    assert!(
        tr.starts_with(
            "Kayıtlı duraklar (2)\n\u{2605} Pier: 41.000000, 29.000000 (2,5 km uzakta)\n  Back door\n"
        ),
        "{tr}"
    );
    assert_eq!(en.lines().count(), tr.lines().count());
    let no_fix = saved_stops::list_text_in(TR, &[&ferry], None);
    assert!(
        no_fix.starts_with("Kayıtlı duraklar (1)\nFerry: 41.000000, 29.000000\n"),
        "{no_fix}"
    );
    assert_eq!(saved_stops::list_text_in(TR, &[], Some(here)), "");
    let unnamed = stop(7, "  ", "", false);
    assert_eq!(
        saved_stops::shortcut_spec_in(EN, &unnamed).short_label,
        "Stop"
    );
    assert_eq!(
        saved_stops::shortcut_spec_in(TR, &unnamed).short_label,
        "Durak"
    );
    assert_eq!(saved_stops::shortcut_spec_in(TR, &pier).long_label, "Pier");
}

#[test]
fn an_import_described() {
    let file = r#"[
      {"id": 1, "created_ms": 0, "name": "", "a": {"label": "", "coord": {"lat": 41.1, "lon": 29.0}},
       "b": {"label": "", "coord": {"lat": 41.1, "lon": 29.01}}},
      {"id": 1, "created_ms": 0, "name": "Bridge", "a": {"label": "", "coord": {"lat": 41.2, "lon": 29.0}},
       "b": {"label": "", "coord": {"lat": 41.2, "lon": 29.01}}},
      {"id": 1, "created_ms": 0, "name": "Bridge again", "a": {"label": "", "coord": {"lat": 41.2, "lon": 29.0}},
       "b": {"label": "", "coord": {"lat": 41.2, "lon": 29.01}}},
      {"id": 1, "created_ms": 0, "name": "Tiny", "a": {"label": "", "coord": {"lat": 41.3, "lon": 29.0}},
       "b": {"label": "", "coord": {"lat": 41.3, "lon": 29.00001}}}
    ]"#;
    let en = places_io::parse_import_in(EN, file).unwrap();
    let tr = places_io::parse_import_in(TR, file).unwrap();
    assert_eq!(en[0].name, "Imported stop");
    assert_eq!(tr[0].name, "İçe aktarılan durak");
    assert_eq!(tr[1].name, "Bridge");
    assert_eq!(places_io::parse_import(file).unwrap(), en);
    let plan = places_io::merge_plan(&SavedStops::default(), tr);
    assert_eq!((plan.add.len(), plan.duplicates, plan.invalid), (2, 1, 1));
    assert_eq!(
        places_io::summary_in(EN, &plan),
        "Add 2 stops. 1 already saved. 1 not usable."
    );
    assert_eq!(
        places_io::summary_in(TR, &plan),
        "2 durak eklenecek. 1 durak zaten kayıtlı. 1 durak kullanılamıyor."
    );
    let one = places_io::MergePlan {
        add: plan.add[..1].to_vec(),
        duplicates: 0,
        invalid: 0,
        over_cap: 4,
    };
    assert_eq!(
        places_io::summary_in(EN, &one),
        "Add 1 stop. 4 over the 200 stop limit."
    );
    assert_eq!(
        places_io::summary_in(TR, &one),
        "1 durak eklenecek. 4 durak 200 durak sınırını aşıyor."
    );
    let none = places_io::MergePlan {
        add: Vec::new(),
        duplicates: 3,
        invalid: 0,
        over_cap: 0,
    };
    assert_eq!(
        places_io::summary_in(EN, &none),
        "Nothing new to add. 3 already saved."
    );
    assert_eq!(
        places_io::summary_in(TR, &none),
        "Eklenecek yeni durak yok. 3 durak zaten kayıtlı."
    );
}

#[test]
fn a_backup_described() {
    let mut saved = SavedStops::default();
    saved.stops.push(stop(1, "Pier", "", false));
    let one = backup::Backup::new(
        saved,
        history::History::default(),
        StopsPrefs::default(),
        NOV_5,
    );
    assert_eq!(
        backup::summary_in(EN, &one, TZ),
        "1 stop, 0 trips and settings from 5 Nov 2026"
    );
    assert_eq!(
        backup::summary_in(TR, &one, TZ),
        "5 Kas 2026 tarihli yedek: 1 durak, 0 yolculuk ve ayarlar"
    );
    assert_eq!(backup::summary(&one, TZ), backup::summary_in(EN, &one, TZ));
}

#[test]
fn a_measurement_described() {
    let mut measure = Measure::new();
    assert_eq!(measure.summary_text_in(TR), "");
    measure.push(Coord::new(41.0, 29.0));
    assert_eq!(measure.summary_text_in(TR), "");
    measure.push(Coord::new(41.0, 29.01));
    assert_eq!(measure.summary_text_in(EN), "840 m, 1 leg");
    assert_eq!(measure.summary_text_in(TR), "840 m, 1 bölüm");
    measure.push(Coord::new(41.0, 29.02));
    assert_eq!(measure.summary_text_in(EN), "1.7 km, 2 legs");
    assert_eq!(measure.summary_text_in(TR), "1,7 km, 2 bölüm");
}

#[test]
fn the_arriving_soon_message() {
    assert_eq!(notice::default_template(EN), notice::DEFAULT_TEMPLATE);
    for lang in Lang::ALL {
        let template = notice::default_template(lang);
        assert!(template.contains("{stop}") && template.contains("{min}"));
        assert!(notice::validate(template).is_ok());
        assert!(notice::is_default_template(&format!(" {template} ")));
    }
    assert!(!notice::is_default_template("On my way to {stop}"));
    let nine = Some(Duration::from_secs(540));
    assert_eq!(
        notice::render_in(EN, notice::default_template(EN), "Work", nine),
        "Arriving at Work in about 9 min."
    );
    assert_eq!(
        notice::render_in(TR, notice::default_template(TR), "Work", nine),
        "Yaklaşık 9 dk sonra varıyorum: Work."
    );
    assert_eq!(
        notice::render_in(EN, notice::default_template(EN), "Work", None),
        "Arriving at Work in about a few min."
    );
    assert_eq!(
        notice::render_in(TR, notice::default_template(TR), "Work", None),
        "Yaklaşık birkaç dk sonra varıyorum: Work."
    );
    // Never "0 min": the soonest is one minute.
    assert_eq!(
        notice::render_in(TR, "{min}", "Work", Some(Duration::from_secs(1))),
        "1"
    );
    assert_eq!(
        notice::render_in(TR, "{min}", "Work", Some(Duration::ZERO)),
        "1"
    );
}
