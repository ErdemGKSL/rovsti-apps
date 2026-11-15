# Owner-device acceptance (Kotlin apps)

What this machine cannot prove: it has no phone and no GPU. Every entry below is run by the owner on the phone with a
release build installed, and its result is written under the entry (date, phone, numbers, pass or fail). One section per
app; the list of what belongs here is E8.7 AC1 in `_bmad-output/kotlin-ui/epics.md`.

## Stops

### E7.13 AC2: the map at 120 Hz

**Why.** Stops draws its map with its own tile renderer ([architecture.md](architecture.md) section 8.4). On the host
it is fast (the numbers are in the [decision record](decisions/stops-map-renderer.md)), but a host JVM is not a phone.
This check says whether panning and pinching hold the 120 Hz frame budget on the real screen.

**Target.** 90th percentile frame time at most **8.3 ms** in each of the three runs below. Above it after the listed
fixes: the decision record on MapLibre is opened.

**Before you start.**

1. Install the release build of Stops (`dev.rosty.stops`). A debug build is not valid for this check.
2. Set the screen to 120 Hz (on the Xiaomi: Settings, Display, Refresh rate, 120 Hz) and switch battery saver off.
   Check the mode the phone is really in:
   `adb shell dumpsys display | grep -m1 -i "mActiveSfDisplayMode\|refreshRate"` (it must say 120).
3. Open Stops, let it download a city: centre the map on a dense city centre (Kadıköy or Eminönü are good) at about
   the 200 m scale bar, then pan around for a minute so the tiles are cached. The check measures drawing, not the
   network, so do it once more with the tiles already there.
4. No trip running, the sheet at its peek detent, light theme first.

**The three runs.** Each run is 20 seconds of continuous movement. Before each run reset the counters, after it save
the output.

| Run | What to do |
|---|---|
| A. Pan | Drag the map in long strokes in every direction, letting it fling between strokes. |
| B. Pinch | Pinch in and out between the 100 m and the 2 km scale bar, without stopping. |
| C. Pan with the sheet | Sheet at its medium detent, pan as in A (the glass of the sheet and the chrome lies over the moving map). |

```
adb shell dumpsys gfxinfo dev.rosty.stops reset
# ... 20 seconds of the run ...
adb shell dumpsys gfxinfo dev.rosty.stops framestats > stops-run-A.txt
```

**What to read.** The summary at the top of the file is enough for pass or fail:

```
Total frames rendered: ...
Janky frames: ... (..%)
50th percentile: ..ms
90th percentile: ..ms      <- the target: at most 8.3 ms (the summary rounds to whole ms: 8 passes, 9 fails)
95th percentile: ..ms
99th percentile: ..ms
```

The rounding matters at this budget, so also take the exact value from the per-frame table (between the two
`---PROFILEDATA---` lines): a frame's time is `FrameCompleted - IntendedVsync` in nanoseconds, rows whose `Flags` is
not 0 are skipped. This prints the percentiles in milliseconds:

```
awk -F, '/---PROFILEDATA---/{on=!on; next} on && $1=="0" {print ($14-$2)/1e6}' stops-run-A.txt \
  | sort -n | awk '{v[NR]=$1} END {print "frames", NR, "p50", v[int(NR*.5)], "p90", v[int(NR*.9)], "p99", v[int(NR*.99)]}'
```

(`$2` is `IntendedVsync` and `$14` is `FrameCompleted` on Android 12 and later; the header row of the table names the
columns, check it once on the phone.)

**What to record here.** For each run: frames, janky percent, p50, p90, p95, p99, and what the map showed (city,
scale). Plus the phone, its Android version, the Stops version, the date, and whether anything looked wrong (a blank
band at the edge while panning, tiles popping in late, a stutter when a new zoom level loads).

| Run | Frames | Janky | p50 | p90 | p95 | p99 | Notes |
|---|---|---|---|---|---|---|---|
| A. Pan | | | | | | | |
| B. Pinch | | | | | | | |
| C. Pan with the sheet | | | | | | | |

Phone: . Android: . Stops: . Date: . Result: .

**If a run misses the target**, these are the fixes to try first, in this order, each followed by the same run. They
are listed with what each one costs in the [decision record](decisions/stops-map-renderer.md):

1. Run C only: the glass over the map must keep its last capture while the map moves (`MapMotionEffect`); if C is
   slower than A, it is re-capturing, and the fix is the Flat tier for the chrome while the map moves.
2. Fewer and larger bitmaps in view: raster tiles of 1024 px instead of 512 px halve the draw calls per frame.
3. No rasterising while a finger is down: new bitmaps are made only when the camera settles (stand-ins until then).
4. Hardware bitmaps for the cached tiles (`Bitmap.Config.HARDWARE`), so drawing them uploads no texture.

Still above 8.3 ms after all four: fill in the numbers in the decision record and open it.
