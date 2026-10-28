#!/usr/bin/env bash
# One-time Android toolchain setup for the Rovsti apps. Safe to re-run.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
NDK_PACKAGE="${NDK_PACKAGE:-}"

rustup target add aarch64-linux-android x86_64-linux-android

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  echo "sdkmanager not found at $SDKMANAGER." >&2
  echo "Install 'Android SDK Command-line Tools' from Android Studio's SDK Manager first." >&2
  exit 1
fi

if [[ -z "$NDK_PACKAGE" ]]; then
  NDK_PACKAGE=$("$SDKMANAGER" --list 2>/dev/null | grep -oE 'ndk;29\.[0-9.]+' | sort -V | tail -1 || true)
  [[ -n "$NDK_PACKAGE" ]] || { echo "No ndk;29.* found; set NDK_PACKAGE." >&2; exit 1; }
fi
echo "Installing platform 36, build tools and $NDK_PACKAGE into $SDK"
"$SDKMANAGER" "platforms;android-36" "build-tools;36.0.0" "platform-tools" "$NDK_PACKAGE"

if ! command -v gpuiforge >/dev/null; then
  SRC="${GPUIFORGE_SRC:-$HOME/src/gpuiforge}"
  [[ -d "$SRC" ]] || git clone https://github.com/cradiy/gpuiforge "$SRC"
  cargo install --path "$SRC"
fi

cat <<MSG

Done. Add to your shell profile:
  export ANDROID_HOME=$SDK
  export ANDROID_NDK_HOME=$SDK/ndk/${NDK_PACKAGE#ndk;}
  export PATH=\$ANDROID_HOME/platform-tools:\$PATH
MSG
