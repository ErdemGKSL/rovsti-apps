#!/usr/bin/env bash
# `gpuiforge platform eject android` bakes absolute paths into
# apps/phone/platforms/android/app/build.gradle.kts. This rewrites them to be
# relative to the Gradle project (phoneAppDir), so the repo can move.
# Safe to re-run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/apps/phone/platforms/android/app/build.gradle.kts"

[[ -f "$GRADLE" ]] || { echo "Not found: $GRADLE (eject first)." >&2; exit 1; }

if ! grep -q '^val phoneAppDir' "$GRADLE"; then
  sed -i -E 's|^for \(profile in listOf\("debug", "release"\)\) \{|// apps/phone, found from the Gradle project (platforms/android) so the repo can move.\nval phoneAppDir = rootProject.projectDir.resolve("../..").canonicalFile\n\n&|' "$GRADLE"
fi

sed -i -E \
  -e 's|workingDir\("[^"]*"\)|workingDir(phoneAppDir)|' \
  -e 's|"--manifest-path", "[^"]*"|"--manifest-path", phoneAppDir.resolve("Cargo.toml").path|' \
  "$GRADLE"

echo "Updated $GRADLE to use paths relative to the Gradle project"
