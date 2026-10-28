# Brief ↔ PRD Reconciliation — Rovsti Phone

Compared the brief (`briefs/brief-rovsti-phone-2026-10-27/brief.md` and `addendum.md`) against the PRD (`prd.md` and `addendum.md`). PRD §0 says it intentionally does not repeat the brief's market framing, so pure framing omissions are rated low.

## Summary

The PRD carries every brief success criterion, every in-scope item, every deferral and all five risks. There are no hard contradictions on core scope. The gaps fall into three groups:

- **Quiet scope expansions** beyond what the brief agreed for "one automated run": waiting-call hold semantics, the missed-call notification, the headset route display, number-sheet "Add to contacts" and the version reset of Xiaomi confirmations.
- **One deliberate deviation:** the native ringing fallback. It is consistent with the brief's risk note, but it is not recorded as a change to the brief's "In" scope.
- **Lost qualitative material:**
  - the "open" half of "fully local, fully open";
  - the "real refraction, not blurred rectangles" quality bar;
  - the secondary audience;
  - the long-term vision;
  - the deferral rationale.

## Contradictions or changed positions

| # | Brief says | PRD says | Severity | Note |
| --- | --- | --- | --- | --- |
| C1 | Scope "In": "In-call **and incoming-call UI in GPUI**" | §4.5 product decision: a native Kotlin answer/decline screen is acceptable if GPUI misses NFR-2 | medium | The brief only raised this as a risk ("may be needed"). The PRD turns it into a scope change. That is a reasonable change, but it should be recorded as a deliberate deviation from the brief, and the brief's "Real liquid glass" differentiator now has an explicit exception. |
| C2 | Out: "Call waiting appears as a second incoming call with **answer/decline only**"; addendum: "MVP handles one active call plus a waiting call (answer/decline)" because "multi-call state is the hardest in-call logic" | FR-20: answering holds the first Call, and the remaining held Call can be resumed with Hold `[ASSUMPTION]` | high | This expands the brief's own hardest-logic deferral. Either reduce it to answer/decline as Telecom drives it, or record it as a scope change. |
| C3 | Addendum grouping: same normalized number and **direction** | Glossary: "direction/type"; FR-26 and PRD addendum: "same **type**" | low | Rejected, blocked and voicemail types group differently under "type" than under "direction". Pick one. |
| C4 | Success criterion 5: "a few hundred contacts and **the default call-log window**" | FR-26 / NFR-4: capped at the **500** most recent entries | low | A cap is a reasonable performance choice, but it changes the brief's assumption. It is tagged `[ASSUMPTION]`, so it is visible. |
| C5 | Basic audio route: "earpiece/speaker" toggle | FR-15: also shows the headset route and allows switching to speaker from a headset | low | A small expansion. The glossary still says "earpiece or speaker in MVP". |
| C6 | Brief scope lists no missed-call notification (Recents: "missed-call count on the tab") | FR-25: a missed-call notification is required (system or own), and tapping it opens Recents | medium | This is an addition. The PRD memlog itself recorded "tab badge only in MVP", which the PRD then contradicts. It is arguably correct for a default dialer, but it is new scope and needs a decision (see the rubric review, Q4). |

## Dropped from the brief

### Qualitative tone and feel
- **"Fully local, fully open" / open source** (brief, What Makes This Different; Secondary users "who sideload open-source apps") — The PRD keeps "local" but never mentions "open". It has no licensing or source-availability statement and no non-goal. Severity: low (no FR impact), but it is a stated differentiator.
- **The glass quality bar: "true refraction and spring motion, not a blurred-rectangle approximation"** — The PRD says "refractive glass surfaces" (§4.9), but no FR or NFR makes refraction or visual fidelity checkable. FR-36 tests only the puck spring. An automated run could ship blurred rectangles and pass. Severity: medium. *Suggest:* A consequence on FR-36 or a new FR requiring that the Tab Bar, Contact Sheet and in-call controls use `liquid_glass_kit` backdrop-shader surfaces, with the blur-only fallback used only when the shader path is unavailable.
- **"iOS 26" fluid, spring-driven design language as the motivation** (brief, The Problem) — Reduced to "iOS-style liquid-glass interface with spring motion" (§1). Acceptable.
- **"The honest advantage is the owner's control and taste, not a moat. This is a personal, opinionated product."** — Dropped. Severity: low. This framing would justify single-device, single-OEM choices if someone pushes back.
- **The problem statement:**
  - the stock Xiaomi dialer is "visually inconsistent" and tied to MIUI conventions;
  - third-party dialers are ad-supported, need network access or sync to a cloud.

  Dropped by design (§0). Severity: low. Only the reliability half of the problem survives in §1.

### Users
- **Secondary audience (later): privacy-minded Android users and Xiaomi owners who sideload** — The PRD lists only Non-Users (§2.2) and the Non-Goal "not a general Android dialer for other OEMs in v1". The future audience is lost. Severity: low.

### Success criteria
- All six brief criteria are carried:
  - 1 → FR-39 and FR-40;
  - 2 → FR-2;
  - 3 → FR-11 and FR-13 to FR-18;
  - 4 → FR-21 to FR-24;
  - 5 → NFR-4;
  - 6 → NFR-8 and SM-4.
- **Criterion 4, "after the Xiaomi checklist is completed"**, is the precondition in UJ-3, but SM-2 does not restate it. That is minor.
- **Criterion 3 lists "keypad" (DTMF) and "hold" as must-work.** SM-3 (first manual test) checks only FR-1, FR-2, FR-11 and FR-24, so DTMF and hold drop out of the first acceptance gate. Severity: low.

### Risks
- All five brief risks are carried in §9: toolchain, GPUI cold start, MIUI role dialog, Mali/GLES, and no automated device test. Nothing is dropped.

### Deferred items
- All brief deferrals are carried in §6.2: dual-SIM, blocklist, swap/merge/conference, T9, speed dial, voicemail, BT picker, contact editing, call notes, recording, Play/signing/localization.
- **The deferral rationale** (brief addendum table, e.g. "Blocklist writes are only allowed for the default dialer… easy to add once the role works") is not restated. The PRD addendum says the brief addendum "remains valid", which is acceptable, but §6.2 standing alone gives no reasons. Severity: low.
- **Brief Vision (long-term):** dual-SIM aware, on-device blocklist and spam rules, T9, conference, and a shared people model across calls, SMS and WhatsApp. The PRD Vision is MVP-only. Only "Shared people model" survives, as a §6.2 out-of-scope bullet. On-device spam rules are not mentioned at all, and Non-Goal §7 ("not a caller-ID, spam lookup …") could be misread as excluding them. Severity: low. *Suggest:* Add one line to §1 or §7 saying on-device spam rules remain a future goal, and only network lookup is a non-goal.

### Technical context
- The brief addendum's `gpuiforge.json` permission list includes `USE_FULL_SCREEN_INTENT` and `BLUETOOTH_CONNECT`. The PRD addendum's permissions note omits both. Severity: low.
- Brief: "Keep to the rule that Android logic stays in Kotlin and the Rust side stays thin." Carried as NFR-7.
- Brief: "Work done for Phone … is reused directly by Messages and Stops." §1 states the intent, but no NFR requires the bridge's event transport or the setup-checklist component to be app-agnostic and live in shared crates (`rovsti_android`, `rovsti_ui`). Severity: medium for the builder job. *Suggest:* An NFR saying the Kotlin-to-Rust event channel and the Setup Checklist live in shared crates with no Phone-specific types.

## Added in the PRD (not in the brief), for awareness

These are new scope beyond the brief. They are not necessarily wrong, but each adds to the single-run budget:

- FR-4 visible preview banner;
- FR-8 version-change reset;
- FR-12 keypad contact-name match;
- FR-19 "Return to call" pill and ongoing notification;
- FR-25 missed-call notification;
- FR-27 clearing Android's `NEW` flag;
- FR-28 number sheet with "Add to contacts";
- FR-29 live refresh;
- FR-32 "Edit in Contacts";
- FR-35 number chooser;
- FR-36 remember last tab;
- FR-38 desktop sample data;
- proximity wake lock (§4.4);
- self-managed ringing (addendum);
- SM-2 "zero missed calls" (stricter than the brief's one-week criterion, which is good);
- deleting Call Log Entries listed as out of scope.
