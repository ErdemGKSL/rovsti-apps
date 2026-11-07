#!/usr/bin/env bash
# Builds a release APK for Rovsti Phone signed with the release key the installed
# apps carry (scripts/signing.sh: ~/ownCloud/Personal/rovsti by default, override
# with ROVSTI_SIGNING_DIR). Passwords are loaded from signing.env and never printed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/signing.sh
source "$ROOT/scripts/signing.sh"
trap 'rm -f "$ROOT/apps/phone/.gpuiforge-signing.json"' EXIT

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT/apps/phone"
rm -rf platforms/android/app/build/outputs/apk/release
gpuiforge --config "$(signing_config "$ROOT/apps/phone")" build android --release --signing configured

APK="$(find platforms/android/app/build/outputs/apk/release -name '*.apk' | head -1)"
[[ -n "$APK" ]] || { echo "No release APK found" >&2; exit 1; }
OUT="${1:-$HOME/Downloads/rovsti-phone-release.apk}"
cp "$APK" "$OUT"
echo "Release APK: $OUT ($(du -h "$OUT" | cut -f1))"
"$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)" verify --print-certs "$OUT" | grep -E "Signer.*certificate (DN|SHA-256)"
