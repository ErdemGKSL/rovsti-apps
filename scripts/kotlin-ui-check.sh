#!/usr/bin/env bash
# Everything a Kotlin UI step must keep green. Fails fast and names the failing step.
# RELEASE BUILDS ONLY: no debug cargo build, no debug APK, never `cargo clean` or `rm -rf target` (docs/kotlin-ui/toolchain.md).
# Host Rust (tests, the Gradle host library) uses the `checks` profile (release code generation, no LTO, target/checks);
# Android Rust is type-checked with `cargo check --release` and built by Gradle in release (arm64-v8a, R8 on the apps).
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
  step "cargo test --profile checks (Kotlin-facing crates)" cargo test --profile checks "${RUST_PKGS[@]}"
  if [[ -f crates/rovsti_android/Cargo.toml ]]; then
    step "cargo check --release rovsti_android aarch64-linux-android" \
      cargo check --release --target aarch64-linux-android -p rovsti_android
  fi
  step "cargo check --release rovsti_ffi aarch64-linux-android" \
    cargo check --release --target aarch64-linux-android -p rovsti_ffi
  # With Gradle the exports are checked on the packaged release APK libraries (check-catalog-apk.sh); Rust-only runs
  # check the release .so directly (builds it in release when missing).
  if [[ -n "${SKIP_GRADLE:-}" ]]; then
    step "ffi exports (nm -D)" bash scripts/check-ffi-exports.sh
  fi
fi

if [[ -z "${SKIP_GRADLE:-}" ]]; then
  # Gradle tasks that exist only once an Android module is included.
  if (cd android && ./gradlew --no-daemon -q projects 2>/dev/null | grep -q "Project ':"); then
    shot=verifyRoborazziRelease
    [[ -n "${RECORD:-}" ]] && shot=recordRoborazziRelease
    # :phone has product flavors (plain, firebase): its tasks carry the flavor, and the firebase flavor's own tests run too.
    phone_shot="${shot/Release/PlainRelease}"
    # :phone has no plain testReleaseUnitTest (flavors): :phone:testPlainReleaseUnitTest is named explicitly.
    step "gradle spotlessCheck lintRelease testReleaseUnitTest :phone:testPlainReleaseUnitTest :phone:testFirebaseReleaseUnitTest :core-bridge:testRustBridge assembleRelease $shot :phone:$phone_shot" \
      gradle spotlessCheck lintRelease testReleaseUnitTest :phone:testPlainReleaseUnitTest :phone:testFirebaseReleaseUnitTest :core-bridge:testRustBridge assembleRelease "$shot" ":phone:$phone_shot"
    step "release APKs have librovsti_ffi.so (arm64-v8a) with the JNI exports" bash scripts/check-catalog-apk.sh
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
