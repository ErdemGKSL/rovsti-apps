#!/usr/bin/env bash
# Re-writes the absolute paths that `gpuiforge platform eject android` bakes into
# apps/phone/platforms/android/app/build.gradle.kts, so the repo can move.
# Safe to re-run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT/apps/phone"
GRADLE="$APP_DIR/platforms/android/app/build.gradle.kts"

[[ -f "$GRADLE" ]] || { echo "Not found: $GRADLE (eject first)." >&2; exit 1; }

sed -i -E \
  -e "s|workingDir\(\"[^\"]*\"\)|workingDir(\"$APP_DIR\")|" \
  -e "s|\"--manifest-path\", \"[^\"]*\"|\"--manifest-path\", \"$APP_DIR/Cargo.toml\"|" \
  "$GRADLE"

echo "Updated $GRADLE to use $APP_DIR"
