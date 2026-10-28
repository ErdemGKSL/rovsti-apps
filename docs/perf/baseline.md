# Baseline (before Epic 12)

## Desktop harness (the numbers every later run is compared with)

The real `PhoneApp` on GPUI's headless platform with real text shaping, driven by the scripted
scenarios on a virtual 60 fps clock. Release build, `opt-level=3` everywhere, LTO off. One row per
scenario. `build` is the scene build time (render + layout + prepaint + paint) in ms; `allocs` are
heap allocations per drawn frame (counting global allocator); `KiB` is bytes allocated per frame.

| Scenario | Frames | build p50 | build p95 | build max | allocs p50 | allocs p95 | KiB p50 | view cache |
|---|---|---|---|---|---|---|---|---|
| tab | 1131 | 0.78 | 1.05 | 1.62 | 1379 | 1758 | 570 | none cached |
| recents (500 rows) | 640 | 1.27 | 1.34 | 1.77 | 2611 | 2743 | 1287 | none cached |
| keypad | 676 | 1.04 | 2.37 | 2.68 | 1549 | 1655 | 610 | none cached |
| incall | 364 | 0.32 | 0.80 | 0.98 | 758 | 1878 | 334 | none cached |
| incoming | 235 | 0.49 | 1.21 | 1.80 | 724 | 2301 | 303 | none cached |

Idle: 0 frames in 30 s on every tab (the harness test `idle_draws_no_frames_on_any_tab`; the Recents
"seen" timer is one frame, 1 s after the tab opens).

Glass (kit bench, 12-key pad, 402 x 480 offscreen, desktop GPU): twelve single backdrop effects cost
0.58 ms per frame over the background alone (1 copy, 2 blur passes and 1 composite each, and the main
render pass split 12 times).

## Phone

Not measured yet: see `docs/perf/README.md` (story 12.7). Fill the table there and copy it to
`docs/perf/final.md`.
