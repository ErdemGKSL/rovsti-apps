# Wire golden fixtures

Shared by `crates/rovsti_core/tests/wire_golden.rs` (Rust) and the Kotlin JVM
unit test `WireFixturesTest` (story 3.2), which lives at
`apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/WireFixturesTest.kt`
and reads this directory by repo-relative path. Kotlin must parse every file
with `org.json.JSONObject` and compare field values; `call_snapshot_*` files
are what `CallRegistry` must be able to emit, `*_ok`/`*_permission` are what
the provider queries must return.

`call_snapshot_unknown_state.json` and `call_snapshot_no_keyguard.json` are
read-only fixtures (Rust ignores unknown fields and defaults a missing
`keyguard_locked` to locked), so they are not expected to round-trip.
