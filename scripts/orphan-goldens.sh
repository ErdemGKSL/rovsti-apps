#!/usr/bin/env bash
# Fails when a golden PNG exists that no screenshot test compared in the last Gradle run.
# Roborazzi 1.77.0 writes <module>/build/test-results/roborazzi/debug/results-summary.json, whose
# results[].golden_file_path lists every image the run compared. Run it after verify/recordRoborazziDebug.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/android"
status=0
# Every module with goldens is found by the glob (catalog, design, :wa, :shell, ...); a module that applies
# rovsti.screenshots but has no screenshots/ directory yet (no UI, e.g. :wa before its screens) is skipped.
for dir in */screenshots; do
  [[ -d "$dir" ]] || continue
  module="${dir%/screenshots}"
  # Roborazzi creates an empty screenshots/ for a module whose tests record nothing yet (:wa before its screens).
  compgen -G "$dir/*.png" > /dev/null || continue
  summary="$module/build/test-results/roborazzi/debug/results-summary.json"
  if [[ ! -f "$summary" ]]; then
    echo "orphan-goldens: $summary is missing; run the Roborazzi task first" >&2
    status=1
    continue
  fi
  compared="$(grep -o '"golden_file_path":"[^"]*"' "$summary" | sed 's/.*:"//; s/"$//' | xargs -n1 basename | sort -u)"
  orphans="$(comm -13 <(printf '%s\n' "$compared") <(cd "$dir" && ls -1 ./*.png | sed 's|^\./||' | sort))"
  if [[ -n "$orphans" ]]; then
    echo "orphan-goldens: $module has screenshots no test compared:" >&2
    printf '  %s\n' $orphans >&2
    status=1
  fi
done
[[ $status -eq 0 ]] && echo "orphan-goldens: none"
exit $status
