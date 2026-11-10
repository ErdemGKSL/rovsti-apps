#!/usr/bin/env bash
# E2.6 AC4 / E4.1: every release APK carries librovsti_ffi.so for arm64-v8a (the only release ABI), and that library
# exports the eight RustNative JNI symbols. Release builds only: a debug APK also needs a debug Rust build.
# Usage: scripts/check-catalog-apk.sh [apk ...]   (default: the catalog and contacts release APKs)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ $# -gt 0 ]]; then
  APKS=("$@")
else
  # Unsigned without a keystore (<app>-release-unsigned.apk), <app>-release.apk when signed.
  release_apk() {
    local f
    for f in "android/$1/build/outputs/apk/release/$1-release.apk" "android/$1/build/outputs/apk/release/$1-release-unsigned.apk"; do
      [[ -f "$f" ]] && { echo "$f"; return; }
    done
    echo "android/$1/build/outputs/apk/release/$1-release-unsigned.apk"
  }
  APKS=("$(release_apk catalog)")
  # App modules are added here as they appear (E4 contacts; E5-E7 phone, messages, stops).
  for app in contacts; do
    APKS+=("$(release_apk "$app")")
  done
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for APK in "${APKS[@]}"; do
  [[ -f "$APK" ]] || { echo "APK not found: $APK (run gradle assembleRelease)" >&2; exit 1; }
  # Read the listing once: `unzip -l | grep -q` under pipefail fails with SIGPIPE when grep stops reading early.
  listing="$(unzip -l "$APK")"
  grep -q "lib/arm64-v8a/librovsti_ffi.so" <<<"$listing" || { echo "missing lib/arm64-v8a/librovsti_ffi.so in $APK" >&2; exit 1; }
  if grep -q "lib/x86_64/" <<<"$listing"; then echo "release APK ships x86_64 libraries: $APK" >&2; exit 1; fi
  dir="$tmp/$(basename "$APK" .apk)"
  mkdir -p "$dir"
  unzip -q -o "$APK" "lib/arm64-v8a/librovsti_ffi.so" -d "$dir"
  bash scripts/check-ffi-exports.sh "$dir/lib/arm64-v8a/librovsti_ffi.so"
  echo "$(basename "$APK"): librovsti_ffi.so for arm64-v8a"
done
