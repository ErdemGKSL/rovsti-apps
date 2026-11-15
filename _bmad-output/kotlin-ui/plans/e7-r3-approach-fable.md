# E7 R3: does every Stops action do something? (approach)

## Method

1. **Find candidates by text, then by shape.** Grep `android/stops` for `= Unit`, empty lambdas, `TODO`,
   "belongs to", "stub", "placeholder", "coming soon". Then list every action interface and action class
   (`PinMenuActions`, `StopSheetActions`, `HistorySheetActions`, `MapToolsHost`, `MyLocationActions`,
   `SavedFileActions`, `HomeActions`, the settings page actions, `PickListener`) and read the one place that
   implements each. A member with a default body that the app never overrides is a silent no-op.
2. **Judge by what the user sees.** A stub is only a bug when a visible row or button reaches it. A hidden row
   (Haptics) or a default used by previews is fine and stays.
3. **Fix at the wiring, test in the app root.** Each fix gets a test that goes through the real screen on the real
   core (`AppFixture`), because the gaps were all between two tested halves: the menu had a test, the sheet had a
   test, the line between them did not.
4. **Make the pins reachable.** The pins are drawn on the map canvas, so no test (and no screen reader) could open a
   pin menu in the app. The map node now carries one accessibility action per pin ("Options for <label>"), which
   opens the same menu as a tap. The pin-menu tests use it.

## Save-a-stop flows

Each of the five ways to save a stop is walked end to end in the app root and ends on the saved list growing
(and Undo where the flow offers it). The table of flow to test is in the R3 report and in the test names:
`PinMenuAppTest`, `MapToolsAppTest`, `HistoryAppTest`, `AlarmAppTest`, `SavedImportAppTest`.

## Not done on purpose

- No new Rust op: the "looks like a link" rule is three lines of text shape and stays behind `PickRules`, so an op
  can replace it later without a UI change.
- No change to `:design` or `:shell`.

## Save a stop: the owner's phone bugs

| Problem | Change | Why |
|---|---|---|
| Saving two dropped pins failed with "Stop names need 1 to 60 characters". Rust's `suggest_name` answered an empty name when no feed stop was near and both ends were unnamed; Kotlin saved that name as it was. | `suggest_name_in(lang, feed, a, b, taken)` never answers an empty name: a feed stop near the middle, else an end's own name, else "Stop 3" / "Durak 3" numbered after the saved stops. A name that is taken gets the next number ("Pier 2"); long names are cut to fit. The op reads the imported feed itself (Kotlin never passed one, so the feed name was never used). | The rule belongs in Rust; every caller gets a name that `saved_edit add` accepts. |
| There was no place to type a name: "Save as stop" saved at once under the suggestion. | Every save path opens one name sheet (the Rename sheet with a Cancel button), the suggestion in the field. Save waits for a name that fits. After Save the Undo pill shows as before. | One sheet for naming and renaming; the user decides the name before anything is written. |
| A pair could only be saved from a pin's menu, which nobody finds; in practice people saved from the arrival summary. | "Save as stop" is a button on the plan sheet under Start and Clear whenever A and B can make a stop. A pair that is a stop already shows "Saved as Pier" there instead. The pin menu keeps its row. | The common action is a visible button, one tap after picking B. |
| On a first run (no `saved.json`) the pin menu had no "Save as stop" at all: a missing file was read as "cannot save". | A missing file is an empty list (`PickController.saveOffer`). | Rust's offer logic was right; the Kotlin caller skipped it. |
| A trip between two unnamed ends was called " – " (a bare dash) on the trip card, the alarm and the notification. | `ActiveTrip::label` names an unnamed end "Point A" / "B noktası" in the trip's language. | A label is never only punctuation. |
| Saving a pair that is a stop already said "already saved as a stop" only after the save was tried. | The core's offer is asked first; the toast names the stop ("already saved as Pier") and no name sheet opens. | Says what to look for. |

## Dead ends found by the sweep

| Problem | Change |
|---|---|
| Pin menu "Lines" did nothing. | Opens the stop sheet of that transit stop; choosing a line puts its pattern on B. |
| A pasted link or coordinates that cannot be read showed nothing in the pick sheet (the rule was a stub that always said no). | Text that looks like a link or coordinates shows the reason; a name still just searches. |
| A map edit the core refuses was silent. | A toast: "Could not change the points. Try again." |
| A "Coming soon" placeholder sheet and its five strings were still in the app (no caller). | Removed. |
| The pins could not be reached by a screen reader (they are drawn on the canvas). | The map has an action per pin, "Options for <name>". |
| Stale KDoc said the arriving-soon toggle was a stub. | Corrected. |

Checked and fine: the Haptics row is hidden (its setting is null), every member of every action class is wired,
the measure tool's empty `launch {}` cancels a pending listing on purpose, the disabled first rows of the pin menu
are captions.

## Usability pass (first-time phone user)

| Problem | Change | Why |
|---|---|---|
| Nothing on the plan says what A and B are for; "gate" only appears after a trip starts. | One line under the plan's buttons: "Start, and the alarm rings when you cross the line between A and B, from either side." | The one idea a new user needs, at the moment they have two pins. |
| The empty Saved list sent people to "a pin's menu". | It now points at the "Save as stop" button. | Matches where the action is. |
| The name limit in Kotlin's default state was 40, the core's is 60. | 60. | One limit. |
| Rename accepted an empty name, closed, then showed an error toast. | Save is disabled until the name fits. | No round trip for an obvious mistake. |

Looked at and left alone (decisions, not oversights):

- The peek detent shows the title and one line; the first-run hint stays one short line so peek ends cleanly.
- Start stays the only filled button on the plan; Save is glass and full width, so the two are not confused.
- The plan's subtitle is the A-B distance, not the two names; the names are on the pins. Worth a look with the
  owner: a named pair could read "Pier West – Pier East · 670 m".
- A trip started from a saved stop is labelled by its two ends, not by the stop's name. A Rust change
  (`ActiveTrip` would carry the stop name); not done here.
