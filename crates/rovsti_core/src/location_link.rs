//! OpenStreetMap links and share text for shared locations.

/// Whether the coordinates are finite, in range and not the (0, 0) placeholder.
pub fn is_valid(lat: f64, lon: f64) -> bool {
    lat.is_finite()
        && lon.is_finite()
        && (-90.0..=90.0).contains(&lat)
        && (-180.0..=180.0).contains(&lon)
        && !(lat == 0.0 && lon == 0.0)
}

fn wrap_lon(lon: f64) -> f64 {
    if !lon.is_finite() {
        return 0.0;
    }
    if (-180.0..=180.0).contains(&lon) {
        return lon;
    }
    let w = (lon + 180.0).rem_euclid(360.0) - 180.0;
    if w == -180.0 { 180.0 } else { w }
}

/// OpenStreetMap link centred on the point (6 decimals).
pub fn osm_url(lat: f64, lon: f64) -> String {
    let lat = if lat.is_finite() {
        lat.clamp(-90.0, 90.0)
    } else {
        0.0
    };
    let lon = wrap_lon(lon);
    format!("https://www.openstreetmap.org/?mlat={lat:.6}&mlon={lon:.6}#map=17/{lat:.6}/{lon:.6}")
}

/// Name, address (each only when not blank) and the link, one per line.
pub fn share_text(name: Option<&str>, address: Option<&str>, lat: f64, lon: f64) -> String {
    let mut lines: Vec<String> = [name, address]
        .into_iter()
        .flatten()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect();
    lines.push(osm_url(lat, lon));
    lines.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_format() {
        assert_eq!(
            osm_url(41.0082, 28.9784),
            "https://www.openstreetmap.org/?mlat=41.008200&mlon=28.978400#map=17/41.008200/28.978400"
        );
    }

    #[test]
    fn url_clamps_and_wraps() {
        assert!(osm_url(95.0, 0.5).contains("mlat=90.000000"));
        assert!(osm_url(-120.0, 0.5).contains("mlat=-90.000000"));
        assert!(osm_url(10.0, 190.0).contains("mlon=-170.000000"));
        assert!(osm_url(10.0, -190.0).contains("mlon=170.000000"));
        assert!(osm_url(10.0, 540.0).contains("mlon=180.000000"));
        assert!(osm_url(10.0, 180.0).contains("mlon=180.000000"));
        assert!(osm_url(f64::NAN, f64::INFINITY).contains("mlat=0.000000&mlon=0.000000"));
    }

    #[test]
    fn share_skips_blanks() {
        let url = osm_url(1.0, 2.0);
        assert_eq!(share_text(None, None, 1.0, 2.0), url);
        assert_eq!(share_text(Some("  "), Some(""), 1.0, 2.0), url);
        assert_eq!(
            share_text(Some(" Cafe "), Some("Main St 1"), 1.0, 2.0),
            format!("Cafe\nMain St 1\n{url}")
        );
        assert_eq!(
            share_text(None, Some("Main St"), 1.0, 2.0),
            format!("Main St\n{url}")
        );
    }

    #[test]
    fn validity() {
        assert!(is_valid(41.0, 29.0));
        assert!(is_valid(90.0, -180.0));
        assert!(!is_valid(0.0, 0.0));
        assert!(!is_valid(90.1, 0.0));
        assert!(!is_valid(0.0, 180.1));
        assert!(!is_valid(f64::NAN, 1.0));
        assert!(!is_valid(1.0, f64::INFINITY));
    }
}
