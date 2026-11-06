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

## Utilities feedback audit (R5)

Audit of U35-U43 for a pill on success and failure, the haptic (Confirm for completions, Selection
tick for toggles and opening a system sheet), a confirm or Undo on destructive actions, and the
desktop wording through `Outcome::why_not`.

| Utility | Gap | Fix |
|---|---|---|
| U38 Blocked Import | On a phone that cannot block (desktop preview) the button did nothing. | Pill "Not available on this platform". A failing picker still uses `why_not("Couldn't open the file picker")`. |
| U28 / U41 Stats CSV export | Fired a Confirm haptic when the picker opened, before anything was saved; the other exports use the Selection tick. | Selection tick; the result pill "Call statistics exported" follows. |
| U41 Export pills | The result pill used to be "Call history exported" for every CSV. | Pill names what was saved (history, statistics, blocked numbers, call notes); cancel reads "Export cancelled", error "Couldn't export". |
| U38 Import | Blocking from a file is bulk, so it has the same Undo pill as a pasted list ("Blocked n numbers ..." with Undo). A file with nothing new shows "No new numbers to block" without a haptic (the async step has no window handle). | Left alone; the pill is the feedback. |
| U40 Stats Share, U36 All numbers, U42 Notes export | Selection tick, `why_not` on failure, "No calls in this range" for an empty range. | No change. |
| U35 Notes chip, U37 After-call Add note | Non-destructive; U37 shows one pill ("Call with Anna, 4:32" + "Add note"), or the block offer instead. When the log has not caught up it says "The call isn't in Recents yet. Try again in a moment". | No change. |
| U43 Delete older calls | Chooser row dimmed with "No calls older than ..." at 0, confirm sheet with the count, Undo pill through `delete_call_ids`; over `UNDO_CAP` the body says "This can't be undone." | No change. |
| U39 Mark missed as seen | Automatic after `VIEW_DELAY`, no feedback needed. | No change. |

Tests: pure texts and parsers have in-module tests in `rovsti_core`; `export_calls_pill` per kind
and the new control ids are covered in `apps/phone`.
