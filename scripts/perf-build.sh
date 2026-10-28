#!/usr/bin/env bash
# Builds the perf-realistic APK of Rovsti Phone (story 12.7): Rust release
# profile, the `perf` feature (frame recorder and scripted scenarios), signed
# with the automatic DEBUG key. For measuring on the phone only: it is never
# copied to ~/Downloads and never handed over; the signed release APK comes from
# scripts/build-release.sh. The `perf` feature is switched on by adding
# `default = ["perf"]` to apps/phone/Cargo.toml for the duration of the build
# (gpuiforge does not forward cargo features); the file is restored on exit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"

MANIFEST="$ROOT/apps/phone/Cargo.toml"
BACKUP="$(mktemp)"
cp "$MANIFEST" "$BACKUP"
trap 'cp "$BACKUP" "$MANIFEST"; rm -f "$BACKUP"' EXIT
sed -i 's/^perf = \["rovsti_ui\/perf"\]/default = ["perf"]\nperf = ["rovsti_ui\/perf"]/' "$MANIFEST"
grep -q '^default = \["perf"\]' "$MANIFEST" || { echo "Could not enable the perf feature." >&2; exit 1; }

cd "$ROOT/apps/phone"
gpuiforge build android --abi arm64-v8a --rust-profile release --android-build-type debug --signing debug
APK="$(find platforms/android/app/build/outputs/apk -name '*.apk' -newer "$BACKUP" | head -1)"
echo "Perf APK: $ROOT/apps/phone/$APK"
echo "Install:  adb install -r '$ROOT/apps/phone/$APK'"
