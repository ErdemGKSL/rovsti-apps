# Final measured run (story 12.7)

Not run yet: it needs the phone. Do it with `docs/perf/README.md` ("Measuring on the phone"), then
fill this file with the same tables as `baseline.md` (device columns), one row per scenario, plus:

| Target | Value | Measured | Met |
|---|---|---|---|
| p95 build_ms within one display frame (panel Hz) | 16.6 / 11.1 / 8.3 ms | | |
| Frames over budget | under 1%, none over 2x in tab, scroll, key, call flows | | |
| CPU frame work (build_ms) | under 4 ms | | |
| Cold start to first interactive frame | under 1.2 s | | |
| Idle | 0 frames | | |
| Warm incoming | under 1.5 s | | |
| Cold incoming | under 3 s | | |

Any target missed is recorded here as a blocker with the next measure from the AD-21 order. It is
never closed by a visual trade-off.
