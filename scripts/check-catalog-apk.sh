#!/usr/bin/env bash
# E2.6 AC4: catalog-debug.apk carries librovsti_ffi.so for both ABIs, and the packaged arm64-v8a library
# exports the eight RustNative JNI symbols. Usage: scripts/check-catalog-apk.sh [path/to/catalog-debug.apk]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
APK="${1:-android/catalog/build/outputs/apk/debug/catalog-debug.apk}"
[[ -f "$APK" ]] || { echo "APK not found: $APK (run gradle :catalog:assembleDebug)" >&2; exit 1; }

for abi in arm64-v8a x86_64; do
  unzip -l "$APK" | grep -q "lib/$abi/librovsti_ffi.so" || { echo "missing lib/$abi/librovsti_ffi.so in $APK" >&2; exit 1; }
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$APK" "lib/arm64-v8a/librovsti_ffi.so" -d "$tmp"
bash scripts/check-ffi-exports.sh "$tmp/lib/arm64-v8a/librovsti_ffi.so"
echo "APK has librovsti_ffi.so for arm64-v8a and x86_64"
