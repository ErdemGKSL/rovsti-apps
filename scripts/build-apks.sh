#!/usr/bin/env bash
# Builds signed release APKs for the Rovsti apps, one after the other (Gradle
# builds cannot share a workspace), and copies them to ~/Downloads as
# rovsti-<app>-release.apk. Signing uses the owner key in ~/.config/rovsti
# (see scripts/make-keystore.sh); passwords are loaded from signing.env and
# never printed. Usage: scripts/build-apks.sh [app ...]   (default: all apps)
# ROVSTI_ABI=arm64-v8a limits the build to one ABI (real phones; halves the build).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIGNING_ENV="${ROVSTI_SIGNING_ENV:-$HOME/.config/rovsti/signing.env}"
[[ -r "$SIGNING_ENV" ]] || { echo "Missing $SIGNING_ENV; run scripts/make-keystore.sh" >&2; exit 1; }
# shellcheck disable=SC1090
source "$SIGNING_ENV"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"
APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)"
OUT_DIR="${ROVSTI_OUT_DIR:-$HOME/Downloads}"
LOG_DIR="${ROVSTI_LOG_DIR:-$ROOT/target/apk-logs}"; mkdir -p "$LOG_DIR"
ABI_ARGS=(); [[ -n "${ROVSTI_ABI:-}" ]] && ABI_ARGS=(--abi "$ROVSTI_ABI")

if [[ $# -gt 0 ]]; then APPS=("$@"); else
  APPS=(); for dir in "$ROOT"/apps/*/; do APPS+=("$(basename "$dir")"); done
fi

failed=()
for app in "${APPS[@]}"; do
  echo "=== $app ==="
  dir="$ROOT/apps/$app"
  [[ -f "$dir/gpuiforge.json" ]] || { echo "no such app: $app" >&2; failed+=("$app"); continue; }
  if ! ( cd "$dir" \
      && rm -rf platforms/android/app/build/outputs/apk/release \
      && gpuiforge build android --release --signing configured "${ABI_ARGS[@]}" >"$LOG_DIR/$app.log" 2>&1 ); then
    echo "BUILD FAILED: $app (log: $LOG_DIR/$app.log)" >&2; tail -25 "$LOG_DIR/$app.log" >&2; failed+=("$app"); continue
  fi
  apk="$(find "$dir/platforms/android/app/build/outputs/apk/release" -name '*.apk' | head -1)"
  if [[ -z "$apk" ]]; then echo "No release APK for $app" >&2; failed+=("$app"); continue; fi
  out="$OUT_DIR/rovsti-$app-release.apk"
  cp "$apk" "$out"
  echo "APK: $out ($(du -h "$out" | cut -f1))"
  "$APKSIGNER" verify --print-certs "$out" 2>/dev/null | grep -E "Signer.*certificate (DN|SHA-256)" || true
done

if [[ ${#failed[@]} -gt 0 ]]; then echo "Failed: ${failed[*]}" >&2; exit 1; fi
echo "All APKs built."
