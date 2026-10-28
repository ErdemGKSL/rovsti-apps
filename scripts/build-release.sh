#!/usr/bin/env bash
# Builds a release APK for Rovsti Phone signed with the owner's key
# (~/.config/rovsti, created by scripts/make-keystore.sh). Passwords are
# loaded from signing.env and never printed.
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

cd "$ROOT/apps/phone"
rm -rf platforms/android/app/build/outputs/apk/release
gpuiforge build android --release --signing configured

APK="$(find platforms/android/app/build/outputs/apk/release -name '*.apk' | head -1)"
[[ -n "$APK" ]] || { echo "No release APK found" >&2; exit 1; }
OUT="${1:-$HOME/Downloads/rovsti-phone-release.apk}"
cp "$APK" "$OUT"
echo "Release APK: $OUT ($(du -h "$OUT" | cut -f1))"
"$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)" verify --print-certs "$OUT" | grep -E "Signer.*certificate (DN|SHA-256)"
