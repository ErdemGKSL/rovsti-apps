//! HTTP client for tile servers: rustls with the pure-Rust graviola provider.

use std::io;
use std::sync::Arc;
use std::time::Duration;

use rovsti_geo::tiles::{MvtError, TileId, TileSource, decode_any};
use ureq::config::Config;
use ureq::tls::{RootCerts, TlsConfig, TlsProvider};
use ureq::{Agent, Error as UreqError};

/// Names the app and nothing about the person.
pub const USER_AGENT: &str = concat!(
    "RovstiStops/",
    env!("CARGO_PKG_VERSION"),
    " (offline arrival alarm; map tiles)"
);

/// A tile larger than this (after decompression) is refused.
const MAX_BODY: u64 = 4 * 1024 * 1024;

/// Layers a Shortbread tile can hold; a server answering with none of them is
/// not a Shortbread tile server.
const SHORTBREAD_LAYERS: [&str; 5] = ["ocean", "water_polygons", "land", "boundaries", "streets"];

#[derive(Debug, PartialEq, Eq)]
pub enum Fetched {
    Tile(Vec<u8>),
    /// The server has no data here (204 or 404).
    Empty,
}

#[derive(Debug, thiserror::Error)]
pub enum TileError {
    #[error("network error: {0}")]
    Http(String),
    #[error("the server answered {0}")]
    Status(u16),
    #[error("the tile is too large")]
    TooLarge,
    #[error("not a vector tile: {0}")]
    Decode(#[from] MvtError),
    #[error("storage error: {0}")]
    Io(#[from] io::Error),
}

#[derive(Clone, Debug)]
pub struct TileClient {
    agent: Agent,
}

impl Default for TileClient {
    fn default() -> Self {
        Self::new()
    }
}

impl TileClient {
    pub fn new() -> Self {
        let tls = TlsConfig::builder()
            .provider(TlsProvider::Rustls)
            .unversioned_rustls_crypto_provider(Arc::new(rustls_graviola::default_provider()))
            .root_certs(RootCerts::WebPki)
            .build();
        let config = Config::builder()
            .tls_config(tls)
            .http_status_as_error(false)
            .user_agent(USER_AGENT)
            .timeout_connect(Some(Duration::from_secs(10)))
            .timeout_global(Some(Duration::from_secs(30)))
            .build();
        Self {
            agent: Agent::new_with_config(config),
        }
    }

    /// Downloads one tile. Sends only `User-Agent` and `Accept-Encoding: gzip`
    /// (no cookies, no identifiers); the body is returned decompressed.
    pub fn fetch(&self, src: &TileSource, id: TileId) -> Result<Fetched, TileError> {
        let mut resp = self
            .agent
            .get(&src.url(id))
            .call()
            .map_err(|e| TileError::Http(e.to_string()))?;
        match resp.status().as_u16() {
            204 | 404 => return Ok(Fetched::Empty),
            200 => {}
            s => return Err(TileError::Status(s)),
        }
        let bytes = resp
            .body_mut()
            .with_config()
            .limit(MAX_BODY)
            .read_to_vec()
            .map_err(|e| match e {
                UreqError::BodyExceedsLimit(_) => TileError::TooLarge,
                e => TileError::Http(e.to_string()),
            })?;
        Ok(Fetched::Tile(bytes))
    }

    /// Downloads and decodes tile 0/0/0, so a wrong URL is caught before it is
    /// saved. `Ok(n)` is the layer count, and only when at least one Shortbread
    /// layer is present.
    pub fn test_server(&self, src: &TileSource) -> Result<usize, TileError> {
        let id = TileId { z: 0, x: 0, y: 0 };
        let bytes = match self.fetch(src, id)? {
            Fetched::Tile(b) => b,
            Fetched::Empty => return Err(TileError::Status(404)),
        };
        let tile = decode_any(&bytes)?;
        if SHORTBREAD_LAYERS.iter().any(|n| tile.layer(n).is_some()) {
            Ok(tile.layers.len())
        } else {
            Err(TileError::Http(
                "the tiles are not in the Shortbread schema".to_owned(),
            ))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    const FIXTURE: &[u8] =
        include_bytes!("../../rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt");

    /// Serves one canned response, returns the URL template and the request text.
    fn serve(
        status: &'static str,
        headers: &'static str,
        body: Vec<u8>,
    ) -> (TileSource, thread::JoinHandle<String>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let h = thread::spawn(move || {
            let (mut s, _) = listener.accept().unwrap();
            let mut req = Vec::new();
            let mut buf = [0u8; 1024];
            while !req.windows(4).any(|w| w == b"\r\n\r\n") {
                let n = s.read(&mut buf).unwrap();
                if n == 0 {
                    break;
                }
                req.extend_from_slice(&buf[..n]);
            }
            let head = format!(
                "HTTP/1.1 {status}\r\n{headers}Content-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            s.write_all(head.as_bytes()).unwrap();
            s.write_all(&body).unwrap();
            String::from_utf8_lossy(&req).into_owned()
        });
        let src =
            TileSource::parse(&format!("http://127.0.0.1:{port}/t/{{z}}/{{x}}/{{y}}")).unwrap();
        (src, h)
    }

    fn id() -> TileId {
        TileId::new(14, 9520, 6130).unwrap()
    }

    #[test]
    fn fetch_ok_sends_user_agent_and_no_cookie() {
        let (src, h) = serve("200 OK", "", FIXTURE.to_vec());
        let got = TileClient::new().fetch(&src, id()).unwrap();
        assert_eq!(got, Fetched::Tile(FIXTURE.to_vec()));
        let req = h.join().unwrap().to_ascii_lowercase();
        assert!(req.starts_with("get /t/14/9520/6130 "));
        assert!(req.contains(&format!("user-agent: {}", USER_AGENT.to_ascii_lowercase())));
        assert!(req.contains("accept-encoding: gzip"));
        assert!(!req.contains("cookie"));
        assert!(!req.contains("authorization"));
    }

    #[test]
    fn fetch_decompresses_gzip() {
        use std::io::Write as _;
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        enc.write_all(FIXTURE).unwrap();
        let gz = enc.finish().unwrap();
        let (src, h) = serve("200 OK", "Content-Encoding: gzip\r\n", gz);
        let got = TileClient::new().fetch(&src, id()).unwrap();
        h.join().unwrap();
        assert_eq!(got, Fetched::Tile(FIXTURE.to_vec()));
    }

    #[test]
    fn fetch_204_and_404_are_empty() {
        for status in ["204 No Content", "404 Not Found"] {
            let (src, h) = serve(status, "", Vec::new());
            assert_eq!(TileClient::new().fetch(&src, id()).unwrap(), Fetched::Empty);
            h.join().unwrap();
        }
    }

    #[test]
    fn fetch_500_is_status_error() {
        let (src, h) = serve("500 Internal Server Error", "", b"oops".to_vec());
        let err = TileClient::new().fetch(&src, id()).unwrap_err();
        h.join().unwrap();
        assert!(matches!(err, TileError::Status(500)), "{err:?}");
    }

    #[test]
    fn fetch_refuses_huge_body() {
        let (src, h) = serve("200 OK", "", vec![0; MAX_BODY as usize + 10]);
        let err = TileClient::new().fetch(&src, id()).unwrap_err();
        let _ = h.join();
        assert!(matches!(err, TileError::TooLarge), "{err:?}");
    }

    #[test]
    fn connection_refused_is_http_error() {
        let port = TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        let src = TileSource::parse(&format!("http://127.0.0.1:{port}/{{z}}/{{x}}/{{y}}")).unwrap();
        let err = TileClient::new().fetch(&src, id()).unwrap_err();
        assert!(matches!(err, TileError::Http(_)), "{err:?}");
    }

    #[test]
    fn test_server_accepts_shortbread_tile() {
        let (src, h) = serve("200 OK", "", FIXTURE.to_vec());
        let n = TileClient::new().test_server(&src).unwrap();
        let req = h.join().unwrap();
        assert!(req.starts_with("GET /t/0/0/0 "));
        assert!(n > 0);
    }

    #[test]
    fn test_server_rejects_garbage() {
        let (src, h) = serve("200 OK", "", b"<html>not a tile</html>".to_vec());
        let err = TileClient::new().test_server(&src).unwrap_err();
        h.join().unwrap();
        assert!(matches!(err, TileError::Decode(_)), "{err:?}");
    }
}
