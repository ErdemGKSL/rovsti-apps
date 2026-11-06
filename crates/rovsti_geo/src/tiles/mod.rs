//! Map tiles: slippy-map maths and the tile server setting. Pure, no I/O.

pub mod clip;
pub mod geometry;
pub mod lru;
pub mod mvt;
pub mod queue;
pub mod shortbread;
pub mod slippy;
pub mod source;
pub mod style;

pub use clip::{Rect, clip_polygon, clip_polyline, simplify};
pub use geometry::{ClassGeometry, TileGeometry, TileLayer, TileTransform, build_layer, stand_in};
pub use lru::LruIndex;
pub use mvt::{
    GeomType, MAX_TILE_BYTES, MvtError, MvtFeature, MvtLayer, MvtTile, MvtValue, decode,
    decode_any, ring_area2,
};
pub use queue::{FetchQueue, MAX_IN_FLIGHT, Priority, RETRY_AFTER_MS};
pub use shortbread::{DrawClass, LandKind, Shape, StreetKind, classify};
pub use slippy::{
    MAX_MERCATOR_LAT, MAX_TILE_ZOOM, TILE_DISPLAY_PX, TileCover, TileId, display_zoom, fetch_zoom,
    frac_to_coord, tile_containing, tile_frac, tiles_for_circle, tiles_for_view,
};
pub use source::{ATTRIBUTION, DEFAULT_TILE_URL, TileSource, TileUrlError};
pub use style::{MapStyle, Paint, Rgba8, line_width};
