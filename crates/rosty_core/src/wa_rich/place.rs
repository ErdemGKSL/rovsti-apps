//! Shared places: checking and writing coordinates, the `geo:` link handed to another app, and which map tiles a
//! small static map of a point needs (Web Mercator, the slippy tiles of every tile server).

use std::f64::consts::PI;

use serde::{Deserialize, Serialize};

/// Decimals of a coordinate as shown and linked (about a metre).
pub const COORD_DECIMALS: usize = 5;
/// The zoom of a map card: streets and their names.
pub const STATIC_MAP_ZOOM: u8 = 16;
/// Latitudes Web Mercator can draw.
const MAX_MERCATOR_LAT: f64 = 85.051_128_78;
/// Deepest zoom a static map asks for.
const MAX_ZOOM: u8 = 19;

/// A latitude and longitude that name a place (the poles and the date line included; NaN is not).
pub fn is_valid_coord(latitude: f64, longitude: f64) -> bool {
    latitude.is_finite()
        && longitude.is_finite()
        && (-90.0..=90.0).contains(&latitude)
        && (-180.0..=180.0).contains(&longitude)
}

fn fixed(value: f64) -> String {
    let text = format!("{value:.COORD_DECIMALS$}");
    // "-0.00000" reads as 0.
    if text
        .trim_start_matches('-')
        .chars()
        .all(|c| c == '0' || c == '.')
    {
        format!("{:.COORD_DECIMALS$}", 0.0)
    } else {
        text
    }
}

/// `41.02580, 28.97440`.
pub fn coords_text(latitude: f64, longitude: f64) -> String {
    format!("{}, {}", fixed(latitude), fixed(longitude))
}

fn percent_encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for b in text.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// The `geo:` link of a place (RFC 5870 with the `q=` query map apps read for a pin and its label).
pub fn geo_uri(latitude: f64, longitude: f64, label: Option<&str>) -> String {
    let (lat, lon) = (fixed(latitude), fixed(longitude));
    match label.map(str::trim).filter(|l| !l.is_empty()) {
        Some(label) => format!("geo:{lat},{lon}?q={lat},{lon}({})", percent_encode(label)),
        None => format!("geo:{lat},{lon}?q={lat},{lon}"),
    }
}

/// What a place bubble or the map page shows.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlaceView {
    /// The place's name; `None` for a place sent without one (the app shows "Shared location").
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    /// The address, or the coordinates when the place has a name but no address.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub subtitle: Option<String>,
    pub coords: String,
    pub geo_uri: String,
    /// A live location: only its first point is known.
    pub live: bool,
}

/// The view of a place. `None` for coordinates that name nothing.
pub fn place_view(
    latitude: f64,
    longitude: f64,
    name: Option<&str>,
    address: Option<&str>,
    live: bool,
) -> Option<PlaceView> {
    if !is_valid_coord(latitude, longitude) {
        return None;
    }
    let clean = |t: Option<&str>| {
        t.map(str::trim)
            .filter(|t| !t.is_empty())
            .map(str::to_owned)
    };
    let title = clean(name);
    let address = clean(address);
    let coords = coords_text(latitude, longitude);
    let subtitle = match (&title, address) {
        (_, Some(address)) => Some(address),
        (Some(_), None) => Some(coords.clone()),
        (None, None) => None,
    };
    Some(PlaceView {
        geo_uri: geo_uri(latitude, longitude, title.as_deref()),
        title,
        subtitle,
        coords,
        live,
    })
}

/// One tile of a static map and where its top-left corner sits in the picture, in pixels.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MapTile {
    pub z: u8,
    pub x: u32,
    pub y: u32,
    pub left: i32,
    pub top: i32,
}

/// The tiles a `width` x `height` picture of a point needs, with the point in the middle.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct StaticMap {
    pub zoom: u8,
    /// The side of one tile in the picture, pixels.
    pub tile_px: u32,
    pub tiles: Vec<MapTile>,
    /// Where the pin goes (the point), pixels from the top left.
    pub pin_x: i32,
    pub pin_y: i32,
}

/// The tiles of a static map of `latitude`, `longitude` at `zoom` (clamped to 0..=19), each drawn `tile_px` wide,
/// for a picture of `width` x `height` pixels. Tiles wrap around the date line; rows past the poles are left out.
/// `None` for an invalid point or an empty picture.
pub fn static_map(
    latitude: f64,
    longitude: f64,
    zoom: u8,
    width: u32,
    height: u32,
    tile_px: u32,
) -> Option<StaticMap> {
    if !is_valid_coord(latitude, longitude) || width == 0 || height == 0 || tile_px == 0 {
        return None;
    }
    let zoom = zoom.min(MAX_ZOOM);
    let n = f64::from(1u32 << zoom);
    let size = f64::from(tile_px);
    let lat = latitude
        .clamp(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        .to_radians();
    let world_x = (longitude + 180.0) / 360.0 * n * size;
    let world_y = (1.0 - (lat.tan() + 1.0 / lat.cos()).ln() / PI) / 2.0 * n * size;
    let left = (world_x - f64::from(width) / 2.0).floor() as i64;
    let top = (world_y - f64::from(height) / 2.0).floor() as i64;
    let ts = i64::from(tile_px);
    let count = 1i64 << zoom;
    let mut tiles = Vec::new();
    let (first_col, last_col) = (
        left.div_euclid(ts),
        (left + i64::from(width) - 1).div_euclid(ts),
    );
    let (first_row, last_row) = (
        top.div_euclid(ts),
        (top + i64::from(height) - 1).div_euclid(ts),
    );
    for row in first_row..=last_row {
        if !(0..count).contains(&row) {
            continue;
        }
        for col in first_col..=last_col {
            tiles.push(MapTile {
                z: zoom,
                x: u32::try_from(col.rem_euclid(count)).unwrap_or(0),
                y: u32::try_from(row).unwrap_or(0),
                left: i32::try_from(col * ts - left).unwrap_or(0),
                top: i32::try_from(row * ts - top).unwrap_or(0),
            });
        }
    }
    Some(StaticMap {
        zoom,
        tile_px,
        tiles,
        pin_x: i32::try_from((world_x.floor() as i64) - left).unwrap_or(0),
        pin_y: i32::try_from((world_y.floor() as i64) - top).unwrap_or(0),
    })
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]

    use super::*;

    #[test]
    fn coordinates_are_checked_and_written_with_five_decimals() {
        assert!(is_valid_coord(41.0258, 28.9744));
        assert!(is_valid_coord(-90.0, 180.0));
        assert!(!is_valid_coord(90.5, 0.0));
        assert!(!is_valid_coord(0.0, f64::NAN));
        assert_eq!(coords_text(41.0258, 28.9744), "41.02580, 28.97440");
        assert_eq!(coords_text(-0.000_001, 0.0), "0.00000, 0.00000");
    }

    #[test]
    fn a_geo_link_carries_the_pin_and_an_encoded_label() {
        assert_eq!(
            geo_uri(41.0258, 28.9744, Some("Galata Kulesi & Cafe")),
            "geo:41.02580,28.97440?q=41.02580,28.97440(Galata%20Kulesi%20%26%20Cafe)"
        );
        assert_eq!(
            geo_uri(1.0, 2.0, Some("  ")),
            "geo:1.00000,2.00000?q=1.00000,2.00000"
        );
    }

    #[test]
    fn a_place_with_no_name_has_no_title_and_shows_nothing_else_twice() {
        let view = place_view(41.0, 29.0, None, None, false).unwrap();
        assert_eq!(view.title, None);
        assert_eq!(view.subtitle, None);
        assert_eq!(view.coords, "41.00000, 29.00000");
        let named = place_view(41.0, 29.0, Some(" Galata "), None, true).unwrap();
        assert_eq!(named.title.as_deref(), Some("Galata"));
        assert_eq!(named.subtitle.as_deref(), Some("41.00000, 29.00000"));
        assert!(named.live);
        let full = place_view(41.0, 29.0, Some("Galata"), Some("Bereketzade"), false).unwrap();
        assert_eq!(full.subtitle.as_deref(), Some("Bereketzade"));
        assert!(place_view(100.0, 0.0, None, None, false).is_none());
    }

    #[test]
    fn a_static_map_centres_the_point_and_covers_the_picture() {
        let map = static_map(0.0, 0.0, 1, 256, 256, 256).unwrap();
        // At zoom 1 the point (0, 0) is the corner of the four tiles.
        assert_eq!((map.pin_x, map.pin_y), (128, 128));
        let mut keys: Vec<(u32, u32, i32, i32)> = map
            .tiles
            .iter()
            .map(|t| (t.x, t.y, t.left, t.top))
            .collect();
        keys.sort_unstable();
        assert_eq!(
            keys,
            vec![
                (0, 0, -128, -128),
                (0, 1, -128, 128),
                (1, 0, 128, -128),
                (1, 1, 128, 128)
            ]
        );
    }

    #[test]
    fn a_static_map_wraps_at_the_date_line_and_stops_at_the_poles() {
        let map = static_map(85.0, 179.99, 2, 300, 200, 256).unwrap();
        assert!(map.tiles.iter().all(|t| t.x < 4 && t.y < 4));
        assert!(
            map.tiles.iter().any(|t| t.x == 0),
            "wrapped to the first column"
        );
        assert!(map.tiles.iter().all(|t| t.y == 0), "no row above the top");
        // Every pixel is covered: the tiles span at least the picture.
        let near = static_map(41.0258, 28.9744, STATIC_MAP_ZOOM, 600, 300, 256).unwrap();
        let min_left = near.tiles.iter().map(|t| t.left).min().unwrap();
        let max_right = near.tiles.iter().map(|t| t.left + 256).max().unwrap();
        assert!(min_left <= 0 && max_right >= 600);
        assert_eq!((near.pin_x, near.pin_y), (300, 150));
        assert!(static_map(0.0, 0.0, 3, 0, 10, 256).is_none());
        assert_eq!(static_map(0.0, 0.0, 40, 10, 10, 256).unwrap().zoom, 19);
    }
}
