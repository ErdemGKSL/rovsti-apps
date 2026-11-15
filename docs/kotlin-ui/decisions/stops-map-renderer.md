# Decision record: the Stops map renderer (own renderer or MapLibre)

**Status: not opened.** It opens only if the owner-device check of E7.13 AC2
([acceptance.md](../acceptance.md), "The map at 120 Hz") misses its target after the listed fixes. Until then the
decision of [architecture.md](../architecture.md) section 8.4 stands: Stops draws its map with its own tile renderer.
This file keeps what is already known, so the re-evaluation starts from numbers and not from memory.

## The question

Does the own renderer hold the 120 Hz budget (p90 frame time at most 8.3 ms) while the map pans and pinches on the
owner's phone? If not, is MapLibre Android the better renderer for Stops, given what it costs?

## What is known (host, R1)

Measured on the build machine: host JVM, Robolectric native graphics, median after warm-up. A host is not a phone;
these numbers bound the work per tile, they do not predict frame times.

| Tile | Vertices | Paths | Prepare once | Whole tile into 512 px | 1/16 of it (one bitmap on a 3x phone) |
|---|---|---|---|---|---|
| fixture `osm-14-9520-6130` (a village) | 1 083 | 308 | 0.5 ms | 1.5 ms | 0.3 ms |
| made-up downtown, 16 x 16 blocks | 7 746 | 2 372 | 8.8 ms | 2.6 ms | 0.9 ms |
| made-up downtown, 32 x 32 blocks | 30 850 | 9 348 | 20 ms | 8.1 ms | 2.1 ms |
| made-up downtown, 48 x 48 blocks | 69 314 | 20 932 | 22 ms | 16.9 ms | 4.1 ms |

- E7.13 AC1 (rasterise a dense city tile in under 25 ms on the host) holds: the 30 000 vertex tile is asserted in
  `TileRasterizerTest`, the 69 000 vertex one is above the GPUI cap of 60 000 and only recorded.
- Rasterising never runs on the main thread (`Dispatchers.Default`), so it costs latency of a new tile, not frames.
- Drawing a frame is 16 to 25 cached bitmaps through one draw node. `TileLayerPainterTest` measures 0 bytes allocated
  over 500 frames while the camera pans and zooms; a pan or a pinch recomposes nothing (`StopsMapRecompositionTest`).
- What the host cannot show: the cost of drawing 16 to 25 software bitmaps of 512 px on the phone's GPU, texture
  uploads when new bitmaps arrive, and the glass over the map sampling it while it moves.

## Device numbers (to fill in)

From [acceptance.md](../acceptance.md). Phone: . Android: . Stops: . Date: .

| Run | Frames | Janky | p50 | p90 | p95 | p99 |
|---|---|---|---|---|---|---|
| A. Pan | | | | | | |
| B. Pinch | | | | | | |
| C. Pan with the sheet | | | | | | |

## Fixes tried before opening (to fill in)

| Fix | What it costs | Run | p90 before | p90 after |
|---|---|---|---|---|
| Glass over the map keeps its last capture while the map moves (it should already: `MapMotionEffect`), else Flat while moving | With Flat: no blur during a pan (the glass at rest stays) | C | | |
| Raster tiles of 1024 px | 4 MB per bitmap instead of 1 MB: a quarter of the tiles fit the same cache | A, B | | |
| Rasterise only on settle | A new zoom level shows its scaled parent until the fingers lift | B | | |
| Hardware bitmaps | No Robolectric screenshot of real tiles (hardware bitmaps cannot be read back); a copy per tile | A, B | | |

## Options (when opened)

| Option | For | Against |
|---|---|---|
| Keep the own renderer with the fixes | One tile pipeline and one cache (`rosty_tiles`), one network rule, glass can sample the map, every screen has screenshots | Whatever the fixes cost above; no labels on the map |
| MapLibre Android with the VersaTiles style | Labels, GPU rendering, mature gestures | A GL surface the glass cannot sample (Flat tier over the map everywhere); no rendering under Robolectric, so no map screenshots on this machine; its own tile cache beside `rosty_tiles` (two caches, two size settings, the offline download rewritten); the radar layers, picking and the camera padding rewritten against its API; a large native dependency |
| MapLibre for tiles only, own radar layers on top | GPU tiles, the radar code stays | Two cameras to keep in step every frame; the glass and screenshot problems of MapLibre remain |

## Decision (when opened)

Decision: . Date: . Decided by: the owner.

Reasons: .

What changes: .
