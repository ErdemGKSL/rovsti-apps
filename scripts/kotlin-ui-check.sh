#!/usr/bin/env bash
# Everything a Kotlin UI step must keep green. Fails fast and names the failing step.
# Env: SKIP_RUST=1 (Gradle only), SKIP_GRADLE=1 (Rust only), RECORD=1 (re-record screenshots).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=android-env.sh
source scripts/android-env.sh

# Rust crates the Kotlin apps use. The deprecated GPUI crates (apps/*, rovsti_ui,
# rovsti_wa_ui, liquid_glass_kit) are deliberately excluded.
RUST_PKGS=(-p rovsti_core -p rovsti_app_core -p rovsti_geo -p rovsti_tiles -p rovsti_wa_proto -p rovsti_wa_client -p rovsti_android -p rovsti_ffi)

step() {
  local name="$1"; shift
  echo "==> $name"
  if ! "$@"; then
    echo "FAILED: $name" >&2
    echo "  command: $*" >&2
    exit 1
  fi
}

# Maven Central occasionally answers 429; retry Gradle a few times only for that.
gradle() {
  local i log
  log="$(mktemp)"
  for i in 1 2 3; do
    ( cd android && ./gradlew --no-daemon -q --max-workers=2 "$@" ) 2>&1 | tee "$log"
    [[ ${PIPESTATUS[0]} -eq 0 ]] && { rm -f "$log"; return 0; }
    grep -q "status code 429" "$log" || break
    sleep 40
  done
  rm -f "$log"
  return 1
}

if [[ -z "${SKIP_RUST:-}" ]]; then
  step "cargo fmt --check" cargo fmt --all --check
  step "cargo test (Kotlin-facing crates)" cargo test "${RUST_PKGS[@]}"
  if [[ -f crates/rovsti_android/Cargo.toml ]]; then
    step "cargo build rovsti_android aarch64-linux-android" \
      cargo build -p rovsti_android --target aarch64-linux-android
  fi
  step "cargo build rovsti_ffi aarch64-linux-android" \
    cargo build -p rovsti_ffi --target aarch64-linux-android
  step "ffi exports (nm -D)" bash scripts/check-ffi-exports.sh
fi

if [[ -z "${SKIP_GRADLE:-}" ]]; then
  # Gradle tasks that exist only once an Android module is included.
  if (cd android && ./gradlew --no-daemon -q projects 2>/dev/null | grep -q "Project ':"); then
    shot=verifyRoborazziDebug
    [[ -n "${RECORD:-}" ]] && shot=recordRoborazziDebug
    # :phone has product flavors (plain, firebase): its tasks carry the flavor, and the firebase flavor's own tests run too.
    phone_shot="${shot/Debug/PlainDebug}"
    step "gradle spotlessCheck lintDebug testDebugUnitTest :core-bridge:testRustBridge assembleDebug $shot :phone:$phone_shot :phone:testFirebaseDebugUnitTest" \
      gradle spotlessCheck lintDebug testDebugUnitTest :core-bridge:testRustBridge assembleDebug "$shot" ":phone:$phone_shot" :phone:testFirebaseDebugUnitTest
    step "debug APKs have librovsti_ffi.so (both ABIs)" bash scripts/check-catalog-apk.sh
  else
    step "gradle spotlessCheck (no Android modules yet)" gradle spotlessCheck
  fi
  # E1.21 AC3: the hot composables must be restartable skippable in the compiler report. -PcomposeReports changes the
  # compiler arguments of :design (a recompile), so it runs as its own invocation after the test run.
  step "gradle :design:checkComposeStability (-PcomposeReports)" \
    gradle -PcomposeReports=true :design:checkComposeStability
  # A golden no test compares any more is dead weight that hides deletions; needs the run above.
  step "orphan goldens" bash scripts/orphan-goldens.sh
fi
echo "ALL GREEN"
