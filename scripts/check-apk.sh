#!/usr/bin/env bash
# Checks the built Rovsti Phone debug/release APK:
#   1. librovsti_phone.so exports the JNI bridge symbols (nm -D);
#   2. the merged manifest has no INTERNET or SEND_SMS permission, no privileged
#      permission, and all AD-18/AD-34 permissions (incl. VIBRATE);
#   3. the <queries> block lists exactly the three v0.2 intents and the
#      VoicemailReceiver is declared disabled by default (AD-34).
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
if grep -qx 'android.permission.SEND_SMS' <<<"$perms"; then
  echo "FAIL SEND_SMS permission is present (a declined-with-text reply uses Telecom or a hand-off)." >&2
  fail=1
else
  echo "ok   no SEND_SMS permission"
fi
REQUIRED=(
  CALL_PHONE READ_CALL_LOG WRITE_CALL_LOG READ_CONTACTS WRITE_CONTACTS
  READ_PHONE_STATE POST_NOTIFICATIONS USE_FULL_SCREEN_INTENT
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS BLUETOOTH_CONNECT FOREGROUND_SERVICE
  FOREGROUND_SERVICE_PHONE_CALL WAKE_LOCK VIBRATE
)
for p in "${REQUIRED[@]}"; do
  if grep -qx "android.permission.$p" <<<"$perms"; then
    echo "ok   $p"
  else
    echo "FAIL missing permission $p" >&2
    fail=1
  fi
done
for p in MANAGE_OWN_CALLS BLUETOOTH_SCAN READ_PHONE_NUMBERS MODIFY_PHONE_STATE \
         QUERY_ALL_PACKAGES BIND_SCREENING_SERVICE READ_EXTERNAL_STORAGE; do
  if grep -qx "android.permission.$p" <<<"$perms"; then
    echo "FAIL $p must be removed (AD-18, AD-34)." >&2
    fail=1
  fi
done

# 3. <queries> and the disabled VoicemailReceiver, from the compiled manifest.
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" >"$work/manifest.txt"
if python3 - "$work/manifest.txt" <<'PY'
import re, sys

ANDROID = "http://schemas.android.com/apk/res/android:"
lines = open(sys.argv[1], encoding="utf-8").read().splitlines()

# Rebuild the element tree from the indentation of `E:` lines.
class Node:
    def __init__(self, tag, depth):
        self.tag, self.depth, self.attrs, self.kids = tag, depth, {}, []

root = Node("root", -1)
stack = [root]
for line in lines:
    m = re.match(r"^(\s*)E: (\S+)", line)
    if m:
        depth = len(m.group(1))
        while stack[-1].depth >= depth:
            stack.pop()
        node = Node(m.group(2), depth)
        stack[-1].kids.append(node)
        stack.append(node)
        continue
    m = re.match(r"^\s*A: (\S+?)(?:\(0x[0-9a-f]+\))?=(.*)$", line)
    if m and stack[-1] is not root:
        name = m.group(1).removeprefix(ANDROID)
        value = m.group(2)
        raw = re.search(r'\(Raw: "(.*)"\)$', value)
        value = raw.group(1) if raw else value.strip().strip('"')
        stack[-1].attrs[name] = value

def find(node, tag):
    out = [k for k in node.kids if k.tag == tag]
    for k in node.kids:
        out += find(k, tag)
    return out

manifest = find(root, "manifest")[0]
problems = []

# <queries>: exactly the three intents, nothing else.
wanted = {
    "android.telephony.action.EMERGENCY_ASSISTANCE",
    "android.settings.HEARING_DEVICES_SETTINGS",
    "android.telecom.action.SHOW_CALL_SETTINGS",
}
queries = [k for k in manifest.kids if k.tag == "queries"]
if len(queries) != 1:
    problems.append("expected one <queries> block, found %d" % len(queries))
else:
    q = queries[0]
    others = [k.tag for k in q.kids if k.tag != "intent"]
    if others:
        problems.append("<queries> holds more than intents: %s" % ", ".join(others))
    actions = {
        a.attrs.get("name")
        for i in q.kids if i.tag == "intent"
        for a in i.kids if a.tag == "action"
    }
    for missing in sorted(wanted - actions):
        print("FAIL <queries> lacks the intent %s" % missing, file=sys.stderr)
        problems.append(missing)
    for extra in sorted(actions - wanted):
        problems.append("unexpected <queries> intent %s" % extra)
    if not (wanted - actions):
        print("ok   <queries> lists the three v0.2 intents")

# VoicemailReceiver: disabled by default, protected, with the broadcast action.
app = find(manifest, "application")[0]
rec = [r for r in app.kids if r.tag == "receiver"
       and r.attrs.get("name", "").endswith(".VoicemailReceiver")]
if not rec:
    problems.append("VoicemailReceiver is not declared")
else:
    r = rec[0]
    if r.attrs.get("enabled") != "false":
        problems.append("VoicemailReceiver is enabled by default (enabled=%s)" % r.attrs.get("enabled"))
    else:
        print("ok   VoicemailReceiver is disabled by default")
    if r.attrs.get("permission") != "android.permission.READ_PHONE_STATE":
        problems.append("VoicemailReceiver lacks android:permission=READ_PHONE_STATE")
    if r.attrs.get("exported") != "true":
        problems.append("VoicemailReceiver is not exported (the system must reach it)")
    actions = {a.attrs.get("name") for f in r.kids if f.tag == "intent-filter"
               for a in f.kids if a.tag == "action"}
    if "android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION" not in actions:
        problems.append("VoicemailReceiver lacks the SHOW_VOICEMAIL_NOTIFICATION action")

for p in problems:
    print("FAIL " + p, file=sys.stderr)
sys.exit(1 if problems else 0)
PY
then :; else fail=1; fi

[[ $fail -eq 0 ]] && echo "check-apk: all checks passed" || { echo "check-apk: FAILED" >&2; exit 1; }
