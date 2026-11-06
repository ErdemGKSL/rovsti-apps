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

## Utilities feedback audit (R4)

Audit of U1-U34 for a pill on success and failure, the haptic (Confirm for completions, Selection
tick for toggles and opening a system sheet), a confirm or Undo on destructive actions, and the
desktop wording through `Outcome::why_not`.

| Utility | Gap | Fix |
|---|---|---|
| U5 Export call log (CSV) | Only `failed()` was checked, so desktop (`NotAvailable`) showed nothing. | Any outcome other than Done now shows `why_not("Couldn't export")`. |
| U16 Export one person's calls | Same as U5. | Same fix in `person_ui.rs`. |
| U34 / U27 Blocked add field | "Already blocked" gave no haptic, while the other refusals use Reject. | Reject haptic added. |
| U28 Stats CSV | The pill "Exported n people" shows when the picker opens; the picker's own answer ("Call history exported", "Export cancelled", "Couldn't export") follows. | Left alone: the later pill corrects it, and it matches the other exports. |
| U26, U27 Share, U33 Share all, U18, U22 | Share opens the system sheet, so no success pill; they use the Selection tick and `why_not("Couldn't open the share sheet")` on failure. | No change. |
| U2, U9, U10 Photo save/share/set/remove | Pill, Confirm haptic and the remove confirm sheet are in place; desktop uses `why_not`. | No change. |
| U12, U22, U24, U27, U30 Destructive actions | Confirm sheet and/or Undo pill exist on each (multi-delete, Clear all, Delete all, Unblock all, note delete). | No change. |
| U13, U14, U31, U32 | Pill or status text plus Confirm/Selection haptic present. | No change. |

Test added: `Outcome::why_not` wording (`backend.rs`).
