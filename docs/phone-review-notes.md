# Phone review notes

Follow-up to the independent review of Rovsti Phone (commit db693ea). Items that were fixed
are covered by tests and commits; this file records what was deliberately left alone.

- **`sync_calls` None arm (incall.rs).** The "view is None, so the shown call is gone"
  assumption holds: `CallView::from_model` always yields the foreground call, so with the shown
  call still alive the view is never None. `route_incoming` returns None only for a call that
  is ringing, which cannot be the already-connected shown call. When the shown call vanished and
  a different call rings, ending the old screen is the correct result. No change.
- **`MainActivity.onResume` keyguard refresh.** `CallRegistry.refreshKeyguard()` is a single
  `KeyguardManager` binder call, not disk I/O, so it stays on the main thread. Only the
  permission checks (SharedPreferences) moved to a worker thread.
- **Aura drift.** The look is unchanged. While only the drift moves it now repaints about every
  33 ms instead of every vsync frame, and not at all while the window is inactive or under
  reduced motion. Spring motion still runs on vsync frames.
- **Perf build.** It now installs as `dev.rovsti.phone.perf` (debug build type) so it can sit
  next to the signed release. Component name for `am start`:
  `dev.rovsti.phone.perf/dev.rovsti.phone.MainActivity`.
- **Eject paths.** `build.gradle.kts` derives `phoneAppDir` from the Gradle project directory;
  `scripts/fix-eject-paths.sh` re-applies that after a `gpuiforge platform eject android`.
