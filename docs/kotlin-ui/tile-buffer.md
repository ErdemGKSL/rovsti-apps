# Tile buffer v1 (`RVT1`)

The `tile(handle, z, x, y, flags)` export of `RustNative` answers one map tile as a flat binary buffer that the
Stops map draws without parsing a vector tile in Kotlin. Rust does the work that does not change per frame: it
downloads or reads the cached tile, classifies the features (Shortbread to draw classes), clips them to the tile
plus a 1/16 margin, simplifies them (tolerance `extent / 1024`) and attaches the colours of the chosen style.
Writer: `crates/rovsti_ffi/src/tile_buffer.rs` (`encode`, with `decode` as the reference reader). Reader:
`dev.rovsti.core.model.stops.TileBuffer.decode`. Both are held to this document by tests on the same real
tile, `crates/rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt`.

## Telling a buffer from an error

The export answers either a buffer or a JSON error envelope (`{"err":{"kind","message","detail"?}}`, the same
as every other call). A buffer starts with the four bytes `52 56 54 31` (`RVT1`); an envelope starts with
`{` (`7B`). Kotlin checks the magic and nothing else. A tile that is not cached while the cache-only flag is set
is the envelope `not_found` with `detail.reason = "not_cached"`.

## Layout

All numbers are little-endian. Floats are IEEE 754 binary32. There is no padding.

```
header
  4   magic      "RVT1"
  u16 version    1
  u16 flags      bit 0  dark: the dark (eclipse) palette was used
                 bit 1  cache only: the tile came from the disk cache, no network was touched
                 bits 2-15 reserved, 0
  u32 z
  u32 x
  u32 y
  u32 extent     tile units per side, 4096 for Shortbread tiles
  u32 background 0xRRGGBBAA, the colour under the tile
  u16 class_count
class_count records, bottom of the paint order first
  u16 paint_id   class number (table below); bit 15 set: casing pass
  u32 rgba       0xRRGGBBAA as one little-endian number (so the bytes are AA BB GG RR)
  f32 width14    line width in px at display zoom 14, 0 for fills
  u8  kind       0 polygon, 1 line
  u32 part_count
  part_count parts
    u32 point_count
    point_count * (f32 x, f32 y)   tile-local units, 0..extent (up to 1/16 extent outside)
```

A record is 15 bytes plus its parts; a part is 4 bytes plus 8 bytes a point. The flags in the header are the
flags of the request (the dark bit also selects the palette). `z`, `x`, `y` repeat the request so a cached buffer
is self-describing.

### Colours

`rgba` is `0xRRGGBBAA` read as a number, so red is `rgba >> 24`; alpha is 0..255 (tunnels and the park fill are
half transparent). The background and the class colours come from `rovsti_geo::tiles::MapStyle` (`Colorful` for
light, `Eclipse` for dark), so the map matches the GPUI map it replaces.

### Paint order, casing and widths

Records come in paint order: draw them in the order they are written, each on top of the one before. The order
is `DrawClass` declaration order (water, land, areas, buildings, waterways, streets from path to motorway, rail,
boundaries), tunnels right after their surface class.

* A **line** class whose style has a casing is written twice: first the casing (`paint_id | 0x8000`, the casing
  colour, `width14 + 1.5`), then the line itself. Both carry the same parts.
* A **polygon** class whose style has an outline (buildings) is written as the fill (kind 0), then the outline
  as a line record (`paint_id | 0x8000`, kind 1, `width14 = 1`) whose parts are the polygon rings with the first
  point repeated at the end.
* Width at a given zoom: `max(0.5, width14 * 2^clamp(displayZoom - 14, -3, 2))` px, the same rule as
  `rovsti_geo::tiles::line_width`. Strokes use round joins and caps.
* The vertex count of a buffer, counting the non-casing records only, equals `TileGeometry::vertices` of the
  tile (tests assert this on the fixture). Casing and outline records repeat points on top of that.

### Paint ids

Stable numbers; a new class takes the next free one, an id is never reused.

| id | class |
|---|---|
| 0 | ocean |
| 1 | water |
| 2 | glacier |
| 3-17 | land: commercial, industrial, residential, agriculture, waste, park, garden, burial, leisure, rock, forest, grass, vegetation, sand, wetland |
| 18 | pier area |
| 19 | pedestrian area |
| 20 | building |
| 21, 22 | waterway (21 large, 22 small) |
| 23-40 | street: `23 + 2 * kind + tunnel`, kind 0..8 = path, cycleway, track, pedestrian, service, minor, tertiary, arterial, motorway |
| 41, 42 | rail (41 surface, 42 tunnel) |
| 43, 44 | boundary (43 region, 44 country) |

Ids increase with the paint order, so the writer never needs to sort. A reader that meets an id it does not know
draws the record anyway: the colour and width are in the record.

## Polygons and the fill rule

A polygon record (kind 0) holds rings as parts. A **polygon** is an exterior ring followed by its holes, and
the orientation of a ring tells which is which: with y pointing down, the doubled signed area
(`sum(x_i * y_(i+1) - x_(i+1) * y_i)`) is **positive for an exterior ring and negative for a hole**. A part with
positive area starts a new polygon, a part with negative area belongs to the polygon before it. The writer
reverses a ring that came out the wrong way and drops a polygon whose exterior has no area, so a reader can
trust the sign. Rings are open (the closing point is not repeated).

The fill rule is **even-odd**, applied **per polygon**: the exterior and its holes go into one path and are
filled even-odd, which cuts the holes out (this is what `build_layer` and the GPUI map assume). Do not put two
polygons of one class into one even-odd path: tiles overlap by their 1/16 margin and a coarse stand-in tile
overlaps finer ones, so overlapping polygons would cancel each other out. Fill each polygon on its own, or batch
only polygons whose bounding boxes do not meet. Fills with an alpha below 255 must not overlap themselves for
the same reason, so draw them per polygon too.

## Lines

A line record (kind 1) holds open polylines as parts, each with at least two points, already clipped to the tile
plus margin. A part is never closed except in the outline record of a polygon class.

## Empty tiles

A tile the server has no data for (HTTP 204 or 404, stored as an empty file) is still a valid buffer: the header
and the background, `class_count = 0`. A reader draws just the background.

## Versioning

`version` is 1. Any change to the layout bumps it and the magic stays `RVT`; a reader refuses a version it does
not know and falls back to the error path. Reserved flag bits must be written as 0 and ignored when read.
Adding a class needs no version bump (see paint ids).

## Where it is used

* `tile(handle, z, x, y, flags)` (JNI) reads a handle from `tiles.open` (see `crates/rovsti_ffi/OPS.md`).
* `tiles.encode_mvt {mvt, z, x, y, flags}` builds the same buffer from a vector tile without a cache or a
  network; its fixtures are the input of `TileBufferTest` in `:core-bridge`.
