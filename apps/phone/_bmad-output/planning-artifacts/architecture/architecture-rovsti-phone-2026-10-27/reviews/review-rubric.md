# Review: ARCHITECTURE-SPINE.md (Rovsti Phone), rubric pass

Reviewed 2026-10-27 against the PRD (FR-1..40, NFR-1..13), the existing code (`rovsti_android`, `rovsti_core`, `rovsti_ui`, `apps/phone/src/main.rs`, `gpuiforge.json`, workspace `Cargo.toml`) and the pinned gpui-fusion rev d6c8412.

**Verdict: PASS WITH FIXES.** The spine is strong: one clear paradigm, a rare honest treatment of the Q1/Q2/Q3 decisions, and AD-21 honours the owner rule. It is not yet safe for parallel units. Two enforcement claims are false as written, one named version is stale, and several real divergence points between the Kotlin and Rust units are left to each side to invent.

## Findings (ranked)

### F1 HIGH. Lint-based rules are not enforceable as configured (AD-13, Errors convention)
Workspace lints set `unwrap_used = "warn"` and the required check is `cargo clippy --workspace --all-targets` with no `-D warnings`. AD-13's `disallowed-methods` ban on `Theme::colors` and the "No `unwrap`" convention therefore only warn; nothing fails. A unit can bypass the accent layer and nobody is blocked. Also, `Theme::colors()` is used today in `rovsti_ui/src/components.rs` (6 sites), `apps/stops/src/main.rs` and `apps/messages/src/whatsapp.rs`; a workspace-wide `clippy.toml` ban breaks or floods warnings in the other apps and the spine has no migration step. Fix: state the gate (`cargo clippy ... -- -D warnings` or `[workspace.lints]` set to `deny` for these two lints), scope the ban (per-crate clippy.toml for Phone's crates, or migrate all callers in one named step), and name the one `#[allow]` site in `rovsti_ui::theme`. Same problem for "CI check in `scripts/check-apk.sh`" (AD-18): there is no CI; say it is a mandatory pre-finish command and add it to the checks list.

### F2 HIGH. Real divergence points left open between the Kotlin and Rust units
- **Caller identity resolved in two places.** The incoming/ongoing notification (Kotlin, AD-2) must show the Contact name before Rust exists; the in-call UI (Rust, FR-13) uses `ContactIndex` + `PhoneNumber::matches`. AD-10 only says Kotlin supplies `normalized`. Not decided: does the snapshot carry `display_name` resolved by Kotlin, or does Rust re-resolve? Two resolvers will disagree (formatting, hidden number = "Unknown", `*#` strings).
- **Telecom state mapping not fixed.** `Call.STATE_*` (NEW, CONNECTING, DIALING, RINGING, ACTIVE, HOLDING, DISCONNECTING, DISCONNECTED, SELECT_PHONE_ACCOUNT, PULLING) to the PRD's five Call States, and `DisconnectCause` to missed / rejected / ended, are not specified. FR-25 and the missed badge depend on it. The `CallSnapshot` field list itself is not given (AD-10 fixes the container, not the contents).
- **Audio source is "audio state or `CallEndpoint`" (AD-4).** With minSdk 34 `CallEndpoint` is always available; the either/or invites two implementations. Decide one, and say what `route(name)` accepts (names enum).
- **Notification Answer path (AD-2 vs FR-23).** Answer must end up showing the In-Call screen, but `CallActionReceiver` is a broadcast receiver; starting an Activity from it is a blocked notification trampoline on Android 12+. Specify that Answer is an activity PendingIntent (or answers via receiver and relies on the in-call full-screen/`CallStyle` content intent) and how the in-call surface is raised.
- **Lock-screen with no calls (AD-3).** The rule only covers `keyguard_locked && calls non-empty`. When the last call ends while locked, the activity still has showWhenLocked until reset; between the snapshot and `moveTaskToBack` the Tab Bar/contacts can render over the keyguard. Add: `keyguard_locked && calls empty` renders blank/no content.
- **`permissions_changed` has no source.** Nothing says what emits it (onResume only?). Setup facts struct (AD-14) and the app version needed for the Xiaomi reset have no named producer.
- **CALL_PHONE flow (FR-11).** AD-11 says pass the raw string, but existing `telecom::place_call` returns false and opens the system dialpad when permission is missing. Who requests the permission and replays the call is undecided.

### F3 MEDIUM. Stack not "verified-current"; one pin is stale
Stack table has no verification date or source. `cargo info jni` today reports installed 0.21.1, **latest 0.22.4**; the pin is a major-minor behind with a known API change and no decision (stay and why, or migrate). `serde_json` is listed as "1" but the table gives no checked versions for gpui crates beyond the rev. AGP 9.2.1 / Gradle 9.4.1, androidx.core 1.18.0 and compileSdk 36.1 were not verifiable offline here and should be stamped "verified on <date> against <source>". Local SDK also has NDK 30.0.16248370 beside 29; the spine pins 29 only via env, fine, but say 30 is not used. Pinned-rev APIs the spine relies on do exist at d6c8412 (`set_frame_diagnostics_enabled`, `on_thermal_state_change`, `on_open_urls`, `draw_backdrop_blurs`, `setOnError` in hosting.md).

### F4 MEDIUM. AD-21 honours the owner rule but is not enforceable as a rule
Positives: visual cuts are explicitly forbidden; thermal state is logged, not acted on; degradation only for user accessibility settings. Gaps:
- No equivalence gate: measures like "cheap shader path variant for non-pressed glass", "half-resolution blur", "merge adjacent glass" can silently change the look. Require pixel-equivalence via `glass_snapshot` tests (tolerance stated) for any shader or pass change, and a before/after capture in the PR.
- No escalation: if all five measures are exhausted and a target still misses, the spine is silent. State that a miss stays a blocker (not waived, not traded for visuals) and the next step is deeper engineering (e.g. profile-driven kit rewrite), so a unit cannot "resolve" it by cutting.
- "Reduced-transparency" has no Android API; define what signal is read (animator-duration-scale for motion; nothing for transparency) or drop it.
- Targets use p95 per display frame at 60/90/120 Hz while NFR-5 says 60 fps; state which Hz the owner phone runs and that the scripted scenarios are the acceptance test. Timer repaint (1 Hz) vs "idle 0 frames" needs the exception named.
- AD-22 "perf builds" has no switch named (cargo feature? profile?), so two units will define it differently.

### F5 MEDIUM. Existing code is changed without a ratification/migration list
Spine knowingly replaces `RovstiBridge.install` loading the library (AD-8) and replaces "each app copies the Kotlin class" with a symlink (AD-19), but does not update the claims in `rovsti_android/src/bridge.rs` and `lib.rs` docs, `docs/architecture.md`, or list the follow-ons: `xiaomi::Setting` has 3 variants vs PRD's 4 (Background pop-ups missing); `BRIDGE: OnceLock` is fine for idempotence but `with_bridge` has no Result for pre-init; `gpuiforge.json` still has `MANAGE_OWN_CALLS`, `BLUETOOTH_SCAN` and ABIs including x86_64 (AD-18 drops two; AD-20 says device builds are arm64-only). `rovsti_ui` has no `theme`/`perf` modules yet and `current_theme` lives in `lib.rs`; say where it moves. Mention that `rovsti_core::ContactId(i64)` matches the spine (good) and that `thread`/`Message` types in core are unrelated to Phone.

### F6 LOW-MEDIUM. Deferred items and small inconsistencies
- Deferred "Xiaomi confirmation reset policy (PRD Q5)" contradicts AD-14, which already implements the version-reset rule and PRD FR-8. Pick one: implemented with `[ASSUMPTION]`, and drop it from Deferred (or state default and that it is changeable in one place).
- Deferred "missed-call own notification (Q4)": PRD FR-25 already decides no handler. Fine, but state `markMissedCallsRead()` triggers on Recents view so the two units agree.
- AD-5: `seq` equal case (pull vs push of same snapshot) ambiguous; channel type for `UnboundedSender` is unnamed while "no Tokio" (pick `futures::channel::mpsc`, new dependency).
- AD-5/AD-8: the sink is replaced per session but nothing says `main()` runs once per `GpuiSession`; verify at the pin.
- Security/privacy gaps: `android:allowBackup`/`dataExtractionRules` not set in AD-18 (NFR-1 "no-backup"), notification lock-screen visibility for caller identity (CallStyle with private/public versions), debuggable perf APK handling, logcat rule covers numbers but not contact names in `RovstiPerf`.
- Capability map omits NFR-9 (Vulkan/GLES), NFR-10 (a11y labels, 44pt, font scale 1.3), NFR-2 acceptance, FR-19 (return-to-call pill / launcher opens In-Call), FR-10, FR-34..35 and FR-39..40 detail. None is contradicted, but unmapped means unowned.

## Checklist results

| Check | Result |
| --- | --- |
| Fixes divergence points for the level below | Partial. Strong on seam, ownership, threading, DTO placement. Misses F2 items. |
| AD rules enforceable and prevent stated divergence | Partial. AD-1, 2, 4, 5, 6, 7, 9, 10, 12, 15 are checkable. AD-13, AD-18 CI claim, AD-21, AD-22 are not (F1, F4). |
| Deferred cannot cause divergence | Mostly. Xiaomi reset policy contradicts AD-14; glass-merge API is safe since it is kit-internal. |
| Named tech verified-current | Fail on jni (0.22.4 latest); no dated verification for the rest (F3). |
| Ratifies existing code | Mostly. Deliberate changes are flagged in AD-8 and AD-19; follow-on edits unlisted (F5). |
| Covers FR/NFRs | FR-1..40 covered via ADs and map; NFR-9, NFR-10 unmapped; NFR-5 vs 120 Hz mismatch. |
| Dimensions decided/deferred/open | Deployment (sideload, decided), build and signing (decided), operations/diagnostics (decided), persistence (decided, backup attribute open), testing (decided, Kotlin JVM test task and device checklist owner open), security/privacy (partly: lock-screen visibility, backup open). |
| Owner rule (perf by code, never visual cuts) | Honoured in intent (AD-21); needs equivalence gate and no-waiver clause (F4). |

## Recommended edits before downstream use
1. Add a Snapshot contract paragraph (fields, state mapping, name resolution owner, audio source) to AD-10 or a new AD.
2. Make lints gating (`-D warnings` for the named lints) or reword AD-13 as convention; add the callers migration.
3. Decide jni 0.21.1 vs 0.22.x and stamp stack verification dates.
4. Add the AD-21 equivalence gate, no-waiver clause and a named perf switch.
5. Fix AD-3 empty-call keyguard case and the notification Answer launch path.
6. Add an "Existing code changes" list and map NFR-9/10, FR-19.
