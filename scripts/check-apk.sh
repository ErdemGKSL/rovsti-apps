#!/usr/bin/env bash
# Checks the built Rovsti Phone debug/release APK:
#   1. librovsti_phone.so exports the JNI bridge symbols (nm -D);
#   2. the merged manifest has no INTERNET permission and all AD-18 permissions.
# Reads the APK path from `gpuiforge info android`. Pass a path to override.
# Needs ANDROID_HOME and ANDROID_NDK_HOME (or the defaults under ~/Android/Sdk).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME="$SDK"

APK="${1:-}"
if [[ -z "$APK" ]]; then
  APK=$(cd "$ROOT/apps/phone" && gpuiforge info android | sed -n 's/^Artifact: //p')
fi
[[ -f "$APK" ]] || { echo "APK not found: ${APK:-<none>}. Run 'gpuiforge build android' in apps/phone." >&2; exit 1; }
echo "APK: $APK"

AAPT2=$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
[[ -n "$AAPT2" ]] || { echo "aapt2 not found under $SDK/build-tools." >&2; exit 1; }

NM=""
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  NM=$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-nm 2>/dev/null | head -1 || true)
fi
[[ -n "$NM" ]] || NM=$(command -v llvm-nm || command -v nm || true)
[[ -n "$NM" ]] || { echo "No llvm-nm/nm found; set ANDROID_NDK_HOME." >&2; exit 1; }

fail=0
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 1. JNI symbols, per ABI.
SYMBOLS=(
  Java_dev_rovsti_bridge_RovstiBridge_nativeInit
  Java_dev_rovsti_bridge_RovstiBridge_nativeOnEvent
)
libs=$(unzip -Z1 "$APK" | grep -E '^lib/[^/]+/librovsti_phone\.so$' || true)
[[ -n "$libs" ]] || { echo "FAIL: no librovsti_phone.so in the APK." >&2; exit 1; }
# Only the ABIs configured in gpuiforge.json (arm64-v8a only) must be present.
configured=$(python3 -c 'import json,sys;print("\n".join(json.load(open(sys.argv[1]))["platforms"]["android"]["abis"]))' "$ROOT/apps/phone/gpuiforge.json")
while IFS= read -r abi; do
  if grep -qx "lib/$abi/librovsti_phone.so" <<<"$libs"; then
    echo "ok   ABI $abi present"
  else
    echo "FAIL configured ABI $abi has no librovsti_phone.so" >&2
    fail=1
  fi
done <<<"$configured"
while IFS= read -r lib; do
  unzip -qo "$APK" "$lib" -d "$work"
  exported=$("$NM" -D --defined-only "$work/$lib" | awk '{print $NF}')
  for sym in "${SYMBOLS[@]}"; do
    if grep -qx "$sym" <<<"$exported"; then
      echo "ok   $lib exports $sym"
    else
      echo "FAIL $lib is missing $sym" >&2
      fail=1
    fi
  done
done <<<"$libs"
if [[ $fail -ne 0 ]]; then
  cat >&2 <<'MSG'
Remedy (spike S1): force a reference to the bridge functions from backend.rs
(e.g. a black_box of the function pointers) so the linker keeps them, or
register them with RegisterNatives from nativeInit.
MSG
fi

# 2. Permissions.
perms=$("$AAPT2" dump permissions "$APK" | sed -n "s/^uses-permission: name='\(.*\)'.*/\1/p")
if grep -qx 'android.permission.INTERNET' <<<"$perms"; then
  echo "FAIL INTERNET permission is present (remove it from the ejected manifest)." >&2
  fail=1
else
  echo "ok   no INTERNET permission"
fi
REQUIRED=(
  CALL_PHONE READ_CALL_LOG WRITE_CALL_LOG READ_CONTACTS WRITE_CONTACTS
  READ_PHONE_STATE POST_NOTIFICATIONS USE_FULL_SCREEN_INTENT
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS BLUETOOTH_CONNECT FOREGROUND_SERVICE
  FOREGROUND_SERVICE_PHONE_CALL WAKE_LOCK
)
for p in "${REQUIRED[@]}"; do
  if grep -qx "android.permission.$p" <<<"$perms"; then
    echo "ok   $p"
  else
    echo "FAIL missing permission $p" >&2
    fail=1
  fi
done
for p in MANAGE_OWN_CALLS BLUETOOTH_SCAN; do
  if grep -qx "android.permission.$p" <<<"$perms"; then
    echo "FAIL $p must be removed (AD-18)." >&2
    fail=1
  fi
done

[[ $fail -eq 0 ]] && echo "check-apk: all checks passed" || { echo "check-apk: FAILED" >&2; exit 1; }
