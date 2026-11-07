//! Trip history as files: CSV for spreadsheets, GPX routes for other apps.

use crate::Coord;
use crate::history::{Outcome, TripRecord};
use crate::places_io::escape;

const HEADER: &str = "started,ended,duration_min,outcome,label,a_lat,a_lon,b_lat,b_lon,travelled_m,snoozes,notice_sent";
const DAY_S: i64 = 86_400;

/// Days since 1970-01-01 to (year, month, day), proleptic Gregorian.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + i64::from(m <= 2);
    (y, m, d)
}

/// `YYYY-MM-DD` of an instant shifted by `tz_offset_s`.
fn date(ms: i64, tz_offset_s: i32) -> String {
    let local = ms.div_euclid(1000) + i64::from(tz_offset_s);
    let (y, m, d) = civil_from_days(local.div_euclid(DAY_S));
    format!("{y:04}-{m:02}-{d:02}")
}

/// `YYYY-MM-DD HH:MM` of an instant shifted by `tz_offset_s`.
fn date_time(ms: i64, tz_offset_s: i32) -> String {
    let local = ms.div_euclid(1000) + i64::from(tz_offset_s);
    let secs = local.rem_euclid(DAY_S);
    format!(
        "{} {:02}:{:02}",
        date(ms, tz_offset_s),
        secs / 3600,
        secs % 3600 / 60
    )
}

fn cell(s: &str) -> String {
    if s.contains([',', '"', '\r', '\n']) {
        format!("\"{}\"", s.replace('"', "\"\""))
    } else {
        s.to_owned()
    }
}

fn outcome_word(o: Outcome) -> &'static str {
    match o {
        Outcome::Arrived => "arrived",
        Outcome::Cancelled => "cancelled",
    }
}

fn coords(c: Option<Coord>) -> [String; 2] {
    c.map_or([String::new(), String::new()], |c| {
        [c.lat.to_string(), c.lon.to_string()]
    })
}

/// RFC 4180 CSV with CRLF line ends, one row per record in the given order.
/// Times are local (`tz_offset_s` east of UTC). Legacy records leave A empty
/// and put their destination in B.
pub fn to_csv(records: &[TripRecord], tz_offset_s: i32) -> String {
    let mut out = String::from(HEADER);
    out.push_str("\r\n");
    for r in records {
        let a = coords(r.a.as_ref().map(|e| e.coord));
        let b = coords(r.b.as_ref().map(|e| e.coord).or(r.destination));
        let cells = [
            date_time(r.started_ms, tz_offset_s),
            date_time(r.ended_ms, tz_offset_s),
            ((r.duration_ms() + 30_000) / 60_000).to_string(),
            outcome_word(r.outcome).to_owned(),
            cell(&r.label),
            a[0].clone(),
            a[1].clone(),
            b[0].clone(),
            b[1].clone(),
            format!("{:.0}", r.travelled_m.max(0.0)),
            r.snoozes.to_string(),
            r.notice_sent.to_string(),
        ];
        out.push_str(&cells.join(","));
        out.push_str("\r\n");
    }
    out
}

/// GPX 1.1 with one route per record that has both ends; the description
/// carries the outcome and the (UTC) date. Records without points are
/// skipped.
pub fn to_gpx(records: &[TripRecord]) -> String {
    let mut out = String::from(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
         <gpx version=\"1.1\" creator=\"Rovsti Stops\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n",
    );
    for r in records {
        let Some((a, b)) = r.pair() else { continue };
        out.push_str("  <rte>\n");
        out.push_str(&format!("    <name>{}</name>\n", escape(&r.label)));
        out.push_str(&format!(
            "    <desc>{}, {}</desc>\n",
            outcome_word(r.outcome),
            date(r.ended_ms, 0)
        ));
        for (tag, e) in [("A", &a), ("B", &b)] {
            out.push_str(&format!(
                "    <rtept lat=\"{}\" lon=\"{}\"><name>{tag}</name></rtept>\n",
                e.coord.lat, e.coord.lon
            ));
        }
        out.push_str("  </rte>\n");
    }
    out.push_str("</gpx>\n");
    out
}

/// `rovsti-stops-history-2026-11-07.csv` for `("csv", "2026-11-07")`.
pub fn export_file_name(ext: &str, today: &str) -> String {
    format!(
        "rovsti-stops-history-{today}.{}",
        ext.trim_start_matches('.')
    )
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::Endpoint;
    use crate::places_io::parse_import;

    fn ep(label: &str, lat: f64, lon: f64) -> Endpoint {
        Endpoint {
            label: label.into(),
            coord: Coord::new(lat, lon),
        }
    }

    // 2026-11-07 12:00:00 UTC.
    const NOON: i64 = 1_794_052_800_000;

    fn rec(label: &str) -> TripRecord {
        TripRecord {
            label: label.into(),
            a: Some(ep("Home", 41.0, 29.0)),
            b: Some(ep("Work", 41.5, 29.25)),
            started_ms: NOON,
            ended_ms: NOON + 25 * 60_000,
            outcome: Outcome::Arrived,
            travelled_m: 1234.4,
            snoozes: 2,
            notice_sent: true,
            ..TripRecord::default()
        }
    }

    #[test]
    fn csv_header_and_row_are_exact() {
        let csv = to_csv(&[rec("Home – Work")], 0);
        assert_eq!(
            csv,
            "started,ended,duration_min,outcome,label,a_lat,a_lon,b_lat,b_lon,travelled_m,snoozes,notice_sent\r\n\
             2026-11-07 12:00,2026-11-07 12:25,25,arrived,Home – Work,41,29,41.5,29.25,1234,2,true\r\n"
        );
    }

    #[test]
    fn csv_quotes_special_labels() {
        let csv = to_csv(&[rec("a,b"), rec("say \"hi\""), rec("two\nlines")], 0);
        assert!(csv.contains(",\"a,b\","));
        assert!(csv.contains(",\"say \"\"hi\"\"\","));
        assert!(csv.contains(",\"two\nlines\","));
    }

    #[test]
    fn csv_time_zone_crosses_midnight() {
        let mut r = rec("x");
        r.started_ms = NOON + 11 * 3_600_000 + 50 * 60_000; // 23:50 UTC
        r.ended_ms = r.started_ms + 20 * 60_000; // 00:10 next day
        let utc = to_csv(&[r.clone()], 0);
        assert!(utc.contains("2026-11-07 23:50,2026-11-08 00:10,"));
        let plus3 = to_csv(&[r.clone()], 3 * 3600);
        assert!(plus3.contains("2026-11-08 02:50,2026-11-08 03:10,"));
        let minus5 = to_csv(&[r], -5 * 3600);
        assert!(minus5.contains("2026-11-07 18:50,2026-11-07 19:10,"));
    }

    #[test]
    fn csv_legacy_record_uses_destination_as_b() {
        let r = TripRecord {
            label: "Kadikoy".into(),
            destination: Some(Coord::new(40.99, 29.02)),
            started_ms: NOON,
            ended_ms: NOON,
            ..TripRecord::default()
        };
        let csv = to_csv(&[r], 0);
        let row = csv.lines().nth(1).unwrap();
        assert_eq!(
            row,
            "2026-11-07 12:00,2026-11-07 12:00,0,arrived,Kadikoy,,,40.99,29.02,0,0,false"
        );
        let bare = to_csv(&[TripRecord::default()], 0);
        assert!(bare.contains(",arrived,,,,,,0,0,false\r\n"));
    }

    #[test]
    fn gpx_reads_back_through_import() {
        let mut cancelled = rec("Tom & <Jerry>");
        cancelled.outcome = Outcome::Cancelled;
        let legacy = TripRecord {
            destination: Some(Coord::new(1.0, 2.0)),
            ..TripRecord::default()
        };
        let records = [rec("Home – Work"), legacy, cancelled];
        let gpx = to_gpx(&records);
        assert!(gpx.contains("<desc>arrived, 2026-11-07</desc>"));
        assert!(gpx.contains("<desc>cancelled, 2026-11-07</desc>"));
        let got = parse_import(&gpx).unwrap();
        assert_eq!(got.len(), 2);
        let want = [&records[0], &records[2]];
        for (g, r) in got.iter().zip(want) {
            let (a, b) = r.pair().unwrap();
            assert_eq!((g.a, g.b), (a.coord, b.coord));
            assert_eq!(g.name, r.label);
        }
    }

    #[test]
    fn dates_and_file_names() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(-1), (1969, 12, 31));
        assert_eq!(civil_from_days(11_016), (2000, 2, 29));
        assert_eq!(civil_from_days(20_399), (2025, 11, 7));
        assert_eq!(
            export_file_name("csv", "2026-11-07"),
            "rovsti-stops-history-2026-11-07.csv"
        );
        assert_eq!(
            export_file_name(".gpx", "2026-11-07"),
            "rovsti-stops-history-2026-11-07.gpx"
        );
    }
}
