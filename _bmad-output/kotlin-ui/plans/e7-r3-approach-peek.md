# E7 R3: the map sheet's peek shows its buttons (approach)

## The owner's request

At peek the map sheet showed the title and one line only. Peek now reaches down to the top action buttons:

| State | Peek shows |
|---|---|
| No plan | the title and the hint ("Tap the map to set point A") |
| A and B | title, subtitle (A-B distance), Start, Clear, and "Save as stop" (or "Saved as Pier") |
| Trip running | title, subtitle, the live card's key line (distance, side) and Copy, Share and Stop |

Medium and large keep their heights.

## How

1. **Measured, not guessed.** `MapSheetPeek` (home/SheetPeek.kt) holds the content's column coordinates (after its
   `verticalScroll`, so scrolling does not move the measure). Each element that must show at peek carries
   `rememberPeekEnd()`: the subtitle in `MapSheetContent`, the plan's Start row and Save row in `TripSection`, and the
   live card's Stop button. The lowest bottom edge is a state, written only when it changes.
2. **Height.** `mapSheetPeekPx` (pure, JVM-tested): grabber (`SheetMetrics.Grabber*`) + content bottom + `Spacing.m` +
   the bottom safe area. With no plan that is shorter than the old 120 dp (peek ends right under the hint; 120 dp is
   only the height before measuring). The cap is 48 dp under medium (never below 120 dp), so peek stays the lowest
   detent at font scale 2 and in landscape: there it is cut (trip at fs2 cuts Stop, landscape cuts Save and the trip
   card's buttons) and the sheet scrolls or goes to medium.
3. **`:design` addition (own commit).** `SheetEntry` fixed its detents at construction. It now has
   `updateDetents(list)`, a generic way to move the resting heights of a shown sheet. `SheetState.configure` follows the
   change: a sheet at rest moves to the detent at the same index (`followDetent`, pure; nearest when the count changed)
   with the settle spring; a sheet under a finger is left alone; a target given to `present` before the change maps
   through it. Tests: `SheetMathTest` (followDetent) and `SheetDetentUpdateTest` (Compose: peek follows, medium stays).
   No glass or tab-bar code was touched.
4. **The clean edge.** The 40 dp `peekRowsGap` existed only so the old fixed peek ended on a clean edge; it is now
   `Spacing.l`, and peek ends `Spacing.m` under its last element, where the next row starts.
5. **The live card.** Its buttons moved up under the key line (distance, side); the rule and the details follow them.
   Otherwise peek during a trip would have to show the whole card, taller than medium.
6. **Camera.** The camera's bottom padding already comes from the sheet's *settled* offset, so it follows the
   measured peek without new code (`HomeGestureTest.thePeekReachesTheMarkedContentAndTheCameraFollows`). Two gaps were
   closed: a pair restored from the last session now opens fitted to its gate (`firstView`), and when the plan's peek
   grows over a pin set low on the map, the view fits the gate again (`pinCovered`, pure). A trip already re-fitted on
   each settle.

## Tests

- `SheetPeekTest` (pure height), `HomeMapLogicTest` (draft first view, covered pin).
- `PeekAppTest`: on the real app root, each state's peek elements sit between the sheet's top and the safe area, and
  the plan's rule line starts under the edge.
- `HomeGestureTest`: the peek follows a marked block that grows, the camera padding equals the new peek, the button
  column still rides above the sheet.
- `PeekShotTest`: `r3-peek-{empty,plan,trip}-{light,dark,tr,fs2,landscape}`.
