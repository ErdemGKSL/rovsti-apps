#![cfg_attr(test, allow(clippy::unwrap_used))]
//! Fetching and caching map tiles. Blocking, so apps call it from the
//! background executor. Decoding and drawing live in `rovsti_geo`.

pub mod cache;
pub mod http;

pub use cache::{DEFAULT_CAP_BYTES, DiskCache, load};
pub use http::{Fetched, TileClient, TileError, USER_AGENT};
