#!/usr/bin/env bash
# Idempotent Android SDK/NDK install for headless sessions (no Android Studio).
# Usage: scripts/android-setup.sh   (env: ANDROID_HOME, CMDLINE_TOOLS_REV, PLATFORM, BUILD_TOOLS, NDK_MAJOR)
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_REV="${CMDLINE_TOOLS_REV:-16111833}"
PLATFORM="${PLATFORM:-37.0}"
BUILD_TOOLS="${BUILD_TOOLS:-37.0.0}"
NDK_MAJOR="${NDK_MAJOR:-29}"

mkdir -p "$ANDROID_HOME"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/clt.zip" "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_REV}_latest.zip"
  unzip -q "$tmp/clt.zip" -d "$tmp/x"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/x/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

set +o pipefail
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
set -o pipefail

# Newer sdkmanager prints "ndk/29.0.x"; older prints "ndk;29.0.x". Stable only (no -rc).
NDK_PACKAGE="${NDK_PACKAGE:-$("$SDKMANAGER" --list 2>/dev/null | grep -oE "ndk[;/]${NDK_MAJOR}\.[0-9]+\.[0-9]+( |$)" | tr -d ' ' | tr '/' ';' | sort -V | tail -1 || true)}"
[[ -n "$NDK_PACKAGE" ]] || { echo "No ndk;${NDK_MAJOR}.* found; set NDK_PACKAGE." >&2; exit 1; }
echo "Installing platform-tools, platforms;android-$PLATFORM, build-tools;$BUILD_TOOLS, $NDK_PACKAGE"
yes | "$SDKMANAGER" "platform-tools" "platforms;android-$PLATFORM" "build-tools;$BUILD_TOOLS" "$NDK_PACKAGE" || true

rustup target add aarch64-linux-android x86_64-linux-android
echo "OK. Next: source scripts/android-env.sh"
