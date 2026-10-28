#!/usr/bin/env bash
# Runs the scripted perf scenarios (story 12.2) on the phone and saves the
# numbers. Needs a `perf` build installed (scripts/perf-build.sh), USB debugging,
# the phone unlocked and Rovsti Phone in the foreground.
#
#   scripts/perf-run.sh                  # all scenarios, in order
#   scripts/perf-run.sh tab keypad       # some of them
#   OUT=docs/perf/runs/after-glass scripts/perf-run.sh all
#
# Per scenario it writes, under $OUT (default docs/perf/runs/<timestamp>):
#   <name>.rovstiperf.txt      the `RovstiPerf` report line(s) from logcat
#   <name>.surfaceflinger.txt  `dumpsys SurfaceFlinger --latency` samples of the
#                              GpuiView layer, taken once a second during the run
#   summary.md                 the report lines and the SurfaceFlinger pacing
#                              (p50/p95/p99/max frame gap, gaps over 1.5 x period)
#
# `RovstiPerf` numbers: build_ms is the CPU time to build one frame (the number
# the 4 ms target is judged by); interval_ms is the gap between animation frames
# (pacing); view_cache is the cached-view hit count. Names and phone numbers are
# never logged. The scenarios: tab, recents, keypad, incall, incoming, idle.
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
ADB=(adb)
[[ -n "${ANDROID_SERIAL:-}" ]] && ADB=(adb -s "$ANDROID_SERIAL")

declare -A REPORT=([tab]=S-tab [recents]=S-scroll-recents [keypad]=S-keypad
                   [incall]=S-incall [incoming]=S-incoming [idle]=idle)
# Seconds each scenario runs (see apps/phone/src/perf_run.rs) plus a margin.
declare -A SECONDS_FOR=([tab]=21 [recents]=14 [keypad]=13 [incall]=12 [incoming]=18 [idle]=32)

names=("$@")
[[ ${#names[@]} -eq 0 || "${names[0]}" == all ]] && names=(tab recents keypad incall incoming idle)

"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "No device: check 'adb devices'." >&2; exit 1; }
OUT="${OUT:-$(cd "$(dirname "$0")/.." && pwd)/docs/perf/runs/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
echo "Writing to $OUT"

layer() {
  "${ADB[@]}" shell dumpsys SurfaceFlinger --list | tr -d '\r' | grep -i 'GpuiView' | head -1
}

# p50/p95/p99/max of the gaps between presented frames, and how many gaps are
# longer than 1.5 x the refresh period, from a `--latency` dump on stdin.
PACING_PY='import sys
lines = [l.split() for l in sys.stdin if l.strip()]
period = None
presents = []
for cols in lines:
    if len(cols) == 1 and cols[0].isdigit():
        period = int(cols[0])
        continue
    if len(cols) == 3 and all(c.lstrip('\''-'\'').isdigit() for c in cols):
        t = int(cols[1])
        if 0 < t < 2**62:
            presents.append(t)
presents = sorted(set(presents))
gaps = [(b - a) / 1e6 for a, b in zip(presents, presents[1:])]
if not gaps:
    print("no frames sampled")
    sys.exit(0)
gaps.sort()
def pct(p):
    return gaps[max(0, min(len(gaps) - 1, -(-len(gaps) * p // 100) - 1))]
ms = (period or 16_666_666) / 1e6
late = sum(1 for g in gaps if g > 1.5 * ms)
print(f"presented frames {len(presents)}, period {ms:.2f} ms, gap p50 {pct(50):.1f} p95 {pct(95):.1f} "
      f"p99 {pct(99):.1f} max {gaps[-1]:.1f} ms, gaps over 1.5 periods {late}")'
pacing() {
  python3 -I -c "$PACING_PY"
}

{
  echo "# Perf run $(basename "$OUT")"
  echo
  echo "Device: $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r') / Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
  echo "Refresh: $("${ADB[@]}" shell dumpsys display | tr -d '\r' | grep -o 'renderFrameRate=[0-9.]*' | head -1)"
  echo
} >"$OUT/summary.md"

run=$(date +%s)
for name in "${names[@]}"; do
  want="${REPORT[$name]:-}"
  [[ -n "$want" ]] || { echo "Unknown scenario '$name'" >&2; continue; }
  echo "== $name"
  "${ADB[@]}" logcat -c
  : >"$OUT/$name.surfaceflinger.txt"
  "${ADB[@]}" shell setprop debug.rovsti.perf "$name:$run$RANDOM"
  end=$((SECONDS + ${SECONDS_FOR[$name]} + 10))
  L="$(layer || true)"
  while ((SECONDS < end)); do
    if [[ -n "$L" ]]; then
      "${ADB[@]}" shell dumpsys SurfaceFlinger --latency "$L" | tr -d '\r' >>"$OUT/$name.surfaceflinger.txt" || true
    fi
    sleep 1
    # The runner logs one report line when the scenario ends.
    if "${ADB[@]}" logcat -d -s RovstiPerf 2>/dev/null | grep -q "report scenario=$want "; then break; fi
  done
  "${ADB[@]}" logcat -d -s RovstiPerf | grep "report scenario=$want " >"$OUT/$name.rovstiperf.txt" || true
  {
    echo "## $name"
    echo
    echo '```'
    cat "$OUT/$name.rovstiperf.txt"
    echo '```'
    echo
    echo "SurfaceFlinger: $(pacing <"$OUT/$name.surfaceflinger.txt")"
    echo
  } >>"$OUT/summary.md"
  [[ -s "$OUT/$name.rovstiperf.txt" ]] || echo "  no report for $name (is a perf build installed and in the foreground?)" >&2
done
"${ADB[@]}" shell setprop debug.rovsti.perf ""
echo "Done: $OUT/summary.md"
