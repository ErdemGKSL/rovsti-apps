//! Map tiles: slippy-map maths and the tile server setting. Pure, no I/O.

pub mod mvt;
pub mod slippy;
pub mod source;

pub use mvt::{
    GeomType, MAX_TILE_BYTES, MvtError, MvtFeature, MvtLayer, MvtTile, MvtValue, decode,
    decode_any, ring_area2,
};
pub use slippy::{
    MAX_MERCATOR_LAT, MAX_TILE_ZOOM, TILE_DISPLAY_PX, TileCover, TileId, display_zoom, fetch_zoom,
    frac_to_coord, tile_containing, tile_frac, tiles_for_circle, tiles_for_view,
};
pub use source::{ATTRIBUTION, DEFAULT_TILE_URL, TileSource, TileUrlError};
