#!/usr/bin/env bash
# Source me: `source scripts/android-env.sh`. Exports the Android SDK/NDK environment.
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME:-}" ]]; then
  _ndk="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
  [[ -n "$_ndk" ]] && export ANDROID_NDK_HOME="${_ndk%/}"
  unset _ndk
fi
export ANDROID_NDK_ROOT="${ANDROID_NDK_HOME:-}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:/opt/gradle/bin:$HOME/.cargo/bin:$PATH"
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  _tc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
  export PATH="$_tc:$PATH"
  # Cargo cross-linkers for the Rust core (API 26 matches minSdk).
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$_tc/aarch64-linux-android26-clang"
  export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$_tc/x86_64-linux-android26-clang"
  export CC_aarch64_linux_android="$_tc/aarch64-linux-android26-clang"
  export AR_aarch64_linux_android="$_tc/llvm-ar"
  export CC_x86_64_linux_android="$_tc/x86_64-linux-android26-clang"
  export AR_x86_64_linux_android="$_tc/llvm-ar"
  unset _tc
fi
