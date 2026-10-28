# Rovsti Phone: motion audit (story 11.10)

Reduced motion = Android animation scale 0 (`Window::prefers_reduced_motion`). `Motion::frame`
settles every spring inside one frame, so spring-driven moments become instant; time-based ones
use the 120 ms real-time `Fade`. Verified: CARGO (unit tests) and desktop preview; feel is DEVICE.

| Moment | Reduced-motion fallback | Verified |
|---|---|---|
| S1 Drop key | ripple and digit copy skipped, digit appears | desktop |
| S2 Liquid number field | instant font step, ghost delete skipped | desktop |
| S3 Call bloom / S4 avatar flight / S8 answer morph | 120 ms cross-fade (`call_fade`) | desktop |
| S5 Tab flow | 120 ms page fade (`page_fade`) | desktop |
| S6 Call aura | static blobs (intro snaps to 1), no drift | desktop |
| S7 Incoming entrance | static blobs, one static ring, no drop, both buttons fade in over 120 ms (`buttons_fade`) | desktop |
| S9 Timer lands | instant swap (springs settle in one frame), no wobble | CARGO logic |
| S10 Control tint flood | instant tint, no wobble (`wobble` is not started) | desktop |
| S11 Call ended collapse | dims and avatar change land instantly, screen fade only | desktop |
| S12 Star burst | nothing moves (`StarFx::play` returns), fill changes | CARGO |
| S13 Badge pop | instant (pop settles in the frame) | CARGO |
| S14 Setup complete | instant collapse, no wash (`Celebrate::observe(.., reduced)`) | CARGO |
| S15 Theme melt | snaps (`Melt::observe` skips the veil) | CARGO |
| S16 Sheet rise | panel stays, 120 ms fade of scrim, panel and rows | CARGO |

## Decisions

* **S15** is a veil in the old theme's background colour, fading on `theme-fade` (zeta 1, k 600):
  one quad, no frame readback, no allocation. Backgrounds cross-fade exactly. It snaps when the
  previous frame took more than twice the budget (`Motion::last_frame_over_budget`) or under
  reduced motion.
* **S16**: no GPUI element transform exists and an inset would shift the layout, so the page
  "scale 0.97" is not drawn; the page dims to 0.85 through the scrim (black, alpha 0.15).
* **S12** dots use the star's gold rather than the accent so they read as shed from the star.
* **S14** has no "card" in this app: the header shield morphs to the check shield, the check pops
  0.6 to 1.0 and the page wash gains 0.10 alpha for 600 ms. It never fires before Android's
  permission and prefs answers are in, so a launch cannot replay it.
* **Slow frames** (AD-21): `rovsti_ui::perf::SlowFrames` sits in `Motion`. Two consecutive frames
  over twice the budget (shortest frame seen, capped at 60 Hz) log one line per streak (at most
  one a second) with the scenario name, through Kotlin to logcat tag `RovstiPerf`
  (`adb logcat -s RovstiPerf`). Nothing is degraded or removed.
* **Aura and the proximity screen-off**: the Kotlin `ProximityController` only holds the wake
  lock; the sensor state does not reach Rust, and with the display off Android delivers no
  frames. The aura and ring loops also stop whenever the window is inactive. No signal was wired.
