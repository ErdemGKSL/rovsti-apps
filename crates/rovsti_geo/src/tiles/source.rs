//! The tile server setting: a URL template with `{z}`, `{x}` and `{y}`.

use super::slippy::TileId;

/// The public VersaTiles server (Shortbread schema, zoom 0-14).
pub const DEFAULT_TILE_URL: &str = "https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}";
/// Shown on the map whenever OpenStreetMap data is drawn.
pub const ATTRIBUTION: &str = "© OpenStreetMap contributors";

const MAX_URL_LEN: usize = 512;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum TileUrlError {
    #[error("Enter a tile URL")]
    Empty,
    #[error("The URL must start with http:// or https://")]
    Scheme,
    #[error("The URL needs {0} exactly once")]
    Missing(&'static str),
    #[error("The URL must not contain spaces")]
    Whitespace,
    #[error("The URL is too long")]
    TooLong,
}

/// A validated tile URL template.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TileSource {
    template: String,
}

impl TileSource {
    /// Validates `text` (after trimming).
    pub fn parse(text: &str) -> Result<Self, TileUrlError> {
        let t = text.trim();
        if t.is_empty() {
            return Err(TileUrlError::Empty);
        }
        if t.len() > MAX_URL_LEN {
            return Err(TileUrlError::TooLong);
        }
        if t.chars().any(char::is_whitespace) {
            return Err(TileUrlError::Whitespace);
        }
        let lower = t.to_ascii_lowercase();
        let rest = lower
            .strip_prefix("https://")
            .or_else(|| lower.strip_prefix("http://"))
            .ok_or(TileUrlError::Scheme)?;
        if rest.is_empty() {
            return Err(TileUrlError::Scheme);
        }
        for p in ["{z}", "{x}", "{y}"] {
            if t.matches(p).count() != 1 {
                return Err(TileUrlError::Missing(p));
            }
        }
        Ok(Self {
            template: t.to_owned(),
        })
    }

    pub fn default_source() -> Self {
        Self {
            template: DEFAULT_TILE_URL.to_owned(),
        }
    }

    pub fn template(&self) -> &str {
        &self.template
    }

    pub fn is_default(&self) -> bool {
        self.template == DEFAULT_TILE_URL
    }

    /// The URL of one tile.
    pub fn url(&self, id: TileId) -> String {
        self.template
            .replace("{z}", &id.z.to_string())
            .replace("{x}", &id.x.to_string())
            .replace("{y}", &id.y.to_string())
    }

    /// 16 lowercase hex chars, FNV-1a 64 of the template. Stable across Rust
    /// versions (unlike `DefaultHasher`); names the per-server cache directory.
    pub fn key(&self) -> String {
        let mut h: u64 = 0xcbf2_9ce4_8422_2325;
        for b in self.template.bytes() {
            h ^= u64::from(b);
            h = h.wrapping_mul(0x0100_0000_01b3);
        }
        format!("{h:016x}")
    }
}

impl Default for TileSource {
    fn default() -> Self {
        Self::default_source()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    const MALTEPE: &str = "https://maps.maltepe.edu.tr/tiles/osm/{z}/{x}/{y}";

    #[test]
    fn accepts_owner_urls() {
        assert!(TileSource::parse(DEFAULT_TILE_URL).unwrap().is_default());
        let m = TileSource::parse(&format!("  {MALTEPE}\n")).unwrap();
        assert_eq!(m.template(), MALTEPE);
        assert!(!m.is_default());
        assert!(TileSource::parse("http://localhost:8080/{z}/{x}/{y}.pbf").is_ok());
    }

    #[test]
    fn rejects_bad_urls() {
        let p = TileSource::parse;
        assert_eq!(p("  "), Err(TileUrlError::Empty));
        assert_eq!(p("ftp://a.b/{z}/{x}/{y}"), Err(TileUrlError::Scheme));
        assert_eq!(p("https://"), Err(TileUrlError::Scheme));
        assert_eq!(p("https://a.b/{z}/{x}"), Err(TileUrlError::Missing("{y}")));
        assert_eq!(
            p("https://a.b/{z}/{z}/{x}/{y}"),
            Err(TileUrlError::Missing("{z}"))
        );
        assert_eq!(p("https://a b/{z}/{x}/{y}"), Err(TileUrlError::Whitespace));
        let long = format!("https://a.b/{}/{{z}}/{{x}}/{{y}}", "a".repeat(512));
        assert_eq!(p(&long), Err(TileUrlError::TooLong));
    }

    #[test]
    fn url_fills_placeholders() {
        let id = TileId::new(14, 9510, 6142).unwrap();
        assert_eq!(
            TileSource::default_source().url(id),
            "https://tiles.versatiles.org/tiles/osm/14/9510/6142"
        );
    }

    #[test]
    fn key_is_stable_and_per_server() {
        let d = TileSource::default_source();
        assert_eq!(d.key(), "d2e31ed09602913b");
        let m = TileSource::parse(MALTEPE).unwrap();
        assert_eq!(m.key(), "a9add904bbdbbfd7");
        assert_ne!(d.key(), m.key());
    }
}
