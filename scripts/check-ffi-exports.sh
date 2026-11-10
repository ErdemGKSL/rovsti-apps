#!/usr/bin/env bash
# The Kotlin object dev.rovsti.core.RustNative declares eight external funs; the library
# must export all eight JNI symbols. Checks the aarch64 Android release build (builds it if missing).
# Usage: scripts/check-ffi-exports.sh [path/to/librovsti_ffi.so]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=android-env.sh
source scripts/android-env.sh

LIB="${1:-target/aarch64-linux-android/release/librovsti_ffi.so}"
if [[ ! -f "$LIB" ]]; then
  cargo build --release -p rovsti_ffi --target aarch64-linux-android
fi

NM="nm"
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  NM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm"
fi
SYMBOLS="$("$NM" -D --defined-only "$LIB")"

missing=0
for name in init call headless tile audioPush audioPull setEventSink release; do
  sym="Java_dev_rovsti_core_RustNative_${name}"
  if ! grep -qw "$sym" <<<"$SYMBOLS"; then
    echo "missing export: $sym" >&2
    missing=1
  fi
done
[[ $missing -eq 0 ]] || exit 1
echo "all 8 RustNative exports present in $LIB"
