#!/usr/bin/env bash
# E2.6 AC4 / E4.1: every debug APK carries librovsti_ffi.so for both ABIs, and the packaged arm64-v8a library exports the
# eight RustNative JNI symbols.
# Usage: scripts/check-catalog-apk.sh [apk ...]   (default: catalog-debug.apk and contacts-debug.apk)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if [[ $# -gt 0 ]]; then
  APKS=("$@")
else
  APKS=(android/catalog/build/outputs/apk/debug/catalog-debug.apk)
  # App modules are added here as they appear (E4 contacts; E5-E7 phone, messages, stops).
  for app in contacts; do
    APKS+=("android/$app/build/outputs/apk/debug/$app-debug.apk")
  done
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for APK in "${APKS[@]}"; do
  [[ -f "$APK" ]] || { echo "APK not found: $APK (run gradle assembleDebug)" >&2; exit 1; }
  # Read the listing once: `unzip -l | grep -q` under pipefail fails with SIGPIPE when grep stops reading early.
  listing="$(unzip -l "$APK")"
  for abi in arm64-v8a x86_64; do
    grep -q "lib/$abi/librovsti_ffi.so" <<<"$listing" || { echo "missing lib/$abi/librovsti_ffi.so in $APK" >&2; exit 1; }
  done
  dir="$tmp/$(basename "$APK" .apk)"
  mkdir -p "$dir"
  unzip -q -o "$APK" "lib/arm64-v8a/librovsti_ffi.so" -d "$dir"
  bash scripts/check-ffi-exports.sh "$dir/lib/arm64-v8a/librovsti_ffi.so"
  echo "$(basename "$APK"): librovsti_ffi.so for arm64-v8a and x86_64"
done
