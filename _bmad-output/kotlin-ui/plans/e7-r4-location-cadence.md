# E7 R4: how often a trip asks for the location (plan)

Status: plan only, nothing is implemented. One implementation round (step list in section 6).

## The owner's request

"Location fixes are triggered far too often. It makes sense that they get more frequent as we approach the stop, but
even far away, say with 700 m still to go, it pulls the location constantly. Estimate the speed from the distance
covered in the last 5 minutes and pick the frequency from that. It must guarantee that it speeds up as we get closer
(never miss the stop) and it must save battery. Both at once."

## 1. What happens today

**Who decides.** Rust decides, Kotlin applies (AD-ST2, AD-SA1, AD-SA2 in `docs/architecture.md`).

| Piece | Where | What it does |
|---|---|---|
| The curve | `crates/rosty_geo/src/poll.rs:42` `poll_interval` | `slack = line_m - acc - max(10 m, acc)`, `interval = clamp(slack / (2 * 60 m/s), 3 s, 300 s)` |
| The speed | `crates/rosty_geo/src/trip.rs:28` `MAX_SPEED_MPS = 60.0` | Always a 216 km/h train. The real speed is never used for the interval |
| The call | `crates/rosty_geo/src/crossing.rs:489` `CrossingTrip::next_fix_in` | Tracking: the curve. Unusable fix: the last usable distance with accuracy 150 m. Arrived or dismissed: 60 s |
| Priority | `crates/rosty_geo/src/session.rs:24,317` | `high_accuracy = next_fix_in <= 15 s` |
| Watchdog | `poll.rs:55` `retry_after`, `FixOut.retry_ms` | `interval + clamp(interval / 4, 5 s, 30 s)` |
| The request | `android/platform/.../location/BridgeTripComponents.kt:197-234` `TripService.requestUpdates` | `LocationManager` (no Play Services). High accuracy: GPS provider, `QUALITY_HIGH_ACCURACY`. Otherwise the fused provider (network without one), `QUALITY_BALANCED_POWER_ACCURACY`. `setMinUpdateIntervalMillis(interval / 2)`. No minimum distance, no batching. Before Android 12: `requestLocationUpdates(provider, interval, 0f, ..)` |
| Each fix | `BridgeTripComponents.kt:246-285` `onFix` | `Headless.call("trip_fix")` (loads and saves `trip.json`), a notification update, `requestUpdates` again, an exact alarm for the watchdog |
| Start | `android/stops/.../trip/TripViewModel.kt:338,391,478` | `FIRST_FIX_MS = 5 s`, high accuracy, until the first answer |
| The map's blue dot | `android/stops/.../home/HomeMap.kt:324-340`, `LocationRepository.kt:133-140,186-187` | While the Stops screen is resumed: a second request, every 2 s / 5 m, also during a trip |

**Numbers** (accuracy 10 m, so `slack = line_m - 20`):

| Distance to the line | Interval | Provider |
|---|---|---|
| 5 km | 41.5 s | fused, balanced |
| 2 km | 16.5 s | fused, balanced |
| 1.8 km and closer | 15 s and less | GPS, high accuracy |
| 700 m | 5.7 s | GPS, high accuracy |
| 380 m and closer | 3 s | GPS, high accuracy |

**Why it feels constant at 700 m.** Four causes, all real:

1. The curve assumes 60 m/s whatever the user does. On foot, 700 m is more than 8 minutes away, and the phone is asked
   every 5.7 s, then every 3 s for the last 380 m (4.5 minutes of walking).
2. GPS high accuracy starts at 1.8 km. A walker has the GPS chip on without a break for about 21 minutes.
3. The interval is a continuous function of the distance, so almost every answer carries a new value and
   `requestUpdates` removes and registers the request again on every fix. Many devices answer a new registration with
   an immediate fix. `setMinUpdateIntervalMillis(interval / 2)` also lets fixes arrive at twice the asked rate.
4. With the app open, the blue dot asks every 2 s on top of the trip.

Standing still at 700 m (waiting for the bus) costs 635 GPS fixes an hour today.

**What already protects the alarm.** Detection is a path test (AD-SX3): the alarm rings when a confident fix lands on
the other side and the straight path from the last confident fix crosses the segment A-B. A late fix therefore rings
late, it does not lose the crossing. What the cadence must guarantee is that a fix arrives before the line can be
reached, and that fixes are dense at the line so the alarm is not late.

## 2. The algorithm

One sentence: the next fix is asked for as late as possible such that the user **cannot physically reach the line
before it**, given how fast they are moving now and how hard a vehicle can accelerate. The speed of the last 5 minutes
is the input the owner asked for; the acceleration bound is what makes it a guarantee and not a guess.

A plain "speed -> frequency" rule cannot be a guarantee: a walker 700 m from the gate can step on a tram. So the rule
never trusts the estimate alone. It asks: from the speed we can prove the user has now, with a vehicle accelerating as
hard as vehicles do, how long until the line? That time is the limit.

### 2.1 Distance to the gate

`line_m = Diameter::distance_to_segment_m(fix)` (`crossing.rs:251`), the distance to the segment A-B, not to a point
and not to the circle. It is the right measure because the alarm needs the path to cross that segment, and the
distance to a segment is 1-Lipschitz: moving `x` metres changes it by at most `x`. So "the user moved at most `x`"
gives "the line is still at least `line_m - x` away" for any direction of travel, any side.

`slack = max(0, line_m - acc - max(DEAD_BAND_M, acc))`, as today: the fix may be wrong by its accuracy, and the side
is only known outside the dead band.

**Second target, the arriving-soon message.** With a pending `NoticeRule::BeforeCircle { before }` the message must go
out when the time to the circle drops under `before`. Its distance is `notice_m = circle_m - v_est * before`, and
`slack_notice = max(0, notice_m - acc)`. The interval is the smaller one of both targets. Without this, longer
intervals would send the message later than today.

### 2.2 The speed

`SpeedHistory`: usable fixes (accuracy 150 m or better) of the last 300 s, at most 48, kept in `ActiveTrip`
(`#[serde(default)]`, so it survives a process death and old `trip.json` files load).

Each sample: time, position, accuracy, the interval that was planned after it. Time is the monotonic clock
(`Location.getElapsedRealtimeNanos`, new optional field `elapsed_ms` on `FixIn`), wall time `at_ms` when it is absent.

Three numbers come out of it:

| Name | Formula | Meaning |
|---|---|---|
| `v_avg` | `(path_300s + tail) / span` | The owner's "distance covered in the last 5 minutes". `path` adds a step only when it is longer than both accuracies together (the rule of `Track::add`, `session.rs:38`), so GPS jitter while standing still adds nothing. `tail` is the not yet counted distance from the last anchor to the newest fix |
| `v_last` | `D_last / dt_last`, `D_last = displacement + max(acc_prev, acc_now)` | Mean speed since the previous fix, rounded up by the accuracy |
| `v_now` | `sqrt(v_last^2 + 2 * A_MAX * D_last)`; with a provider speed `s`: `min(that, 1.2 * s + 1 m/s)` | **An upper bound of the speed right now.** With acceleration limited to `A_MAX`, speed can rise by at most `sqrt(2 * A_MAX * D)` over a distance `D`, and the lowest speed in the interval is at most the mean. A GPS Doppler speed measures it directly and is tighter |

The speed the plan uses:

```text
v_est = max(v_avg, v_last without the accuracy term, provider speed)     shown, ETA, notice
v     = clamp(max(v_now, H * v_avg, V_FLOOR), V_FLOOR, V_MAX)            planning
v     = V_MAX when there is no previous sample (first fix, reset)        cold
```

How each case in the brief is covered:

| Case | What happens |
|---|---|
| GPS jitter, standing still | `path` gate: `v_avg = 0`. `v_now` is about 6 m/s from the accuracy term alone (10 m accuracy), which is what "could have just started moving" means |
| Outlier fix (a jump) | A jump makes `D_last` large, so `v_now` goes to `V_MAX`: one cycle at today's cadence, never a longer one. A step that implies more than 90 m/s is not added to `path`, so `v_avg` is not poisoned for 5 minutes |
| Poor accuracy | `slack` shrinks by twice the accuracy and `D_last` grows by it. Worse accuracy can only shorten the interval. Above 150 m: 2.4 |
| Tunnel, no fix for a while | No special case needed: after a gap `dt_last` and `D_last` are large, the bound grows with them, up to `V_MAX`. While no fix arrives: 2.6 |
| First minutes, no history | First fix: `V_MAX`, exactly today's curve. From the second fix `v_now` is a proven bound, so no warm-up period is needed. `v_avg` joins once the history spans 10 s |
| Sudden speed-up (bus after walking) | Before it shows in any estimate: the envelope in 2.3 already assumes full acceleration from the first second. After it: `v_now` rises on the next fix, the interval drops at once (2.5: shortening is never delayed) |
| Slow-down, mode change to walking | `v_now` falls on the next fix, `H * v_avg` fades over 5 minutes. The interval lengthens through the hysteresis in 2.5 |
| Bus standing at a stop or a red light | `v_last` is 0 but `H * v_avg` keeps the vehicle speed for up to 5 minutes. This is what the 5-minute memory is for |
| Clock reset, reboot, time going backwards | The history is cleared, next fix is cold |

### 2.3 The interval and the invariant

`reach(t, v)`: the farthest anyone can get in `t` seconds starting at speed `v`, accelerating at `A_MAX`, never faster
than `V_MAX`:

```text
t1 = (V_MAX - v) / A_MAX
reach(t, v) = v*t + A_MAX*t^2/2                         for t <= t1
            = v*t1 + A_MAX*t1^2/2 + V_MAX*(t - t1)      after
grace(T)    = clamp(T / 4, 5 s, 30 s)                   the watchdog window (retry_after, unchanged)
```

Three rules, for each target's `slack`:

```text
R1 plan      T1 = slack / (K * v)                       at the assumed speed half the slack is left over
R2 envelope  T2 = the largest T with  reach(T + grace(T), v) <= slack
R0 cold      T0 = slack / (K * V_MAX)                   today's curve (poll_interval), needs no estimate at all

T = clamp( max(T0, min(T1, T2)), MIN_POLL, MAX_POLL ),  then rounded DOWN to the ladder (2.5)
```

`T2` has a closed form: `x = reach^-1(slack, v)` (a quadratic, then a line), and `T = x - 5` for `x <= 25`,
`x / 1.25` up to 150, `x - 30` after.

**The safety invariant.** Assume

- P1: speed never above `V_MAX`,
- P2: acceleration never above `A_MAX`,
- P3: a usable fix is wrong by at most its accuracy,
- P4: the next fix arrives within `T + grace(T)` (the fix itself, or the watchdog's).

Then for every fix with `T > MIN_POLL`:

```text
distance the user can cover before the next fix  <=  reach(T + grace(T), v)  <=  slack  <=  true distance to the line - dead band
```

So the user is still on the same side when the next fix arrives, and that fix plans the next step from a positive
slack again. By induction a fix always lands before the line: the stop cannot be skipped. The proof of the middle step
is `v_now <= v` (2.2, from P2) and the definition of `reach`. `R0` is safe on its own under P1, so taking the larger of
`R0` and the adaptive value keeps the invariant and gives a second property: **the new cadence never asks more often
than today's, rounding aside.**

An underestimate cannot miss the stop, because no term of `v` is an estimate that can be too low: `v_now` is a bound,
`V_FLOOR` covers "no movement seen", and the envelope adds the acceleration after the fix.

Inside the floor zone (`slack` under about 85 m on foot, 160 m in a bus, 360 m in a train) the rules would ask for less
than `MIN_POLL`; there it is a fix every 3 s, as today. That zone is where "more frequent as we approach" ends.

**Monotonic tightening.** For the same `v` and accuracy, `T` never grows when the distance falls (`R0`, `R1`, `R2` are
all non-decreasing in `slack`, rounding down keeps that). With the hysteresis the answer is never above this value.

### 2.4 Unusable and missing fixes

A fix worse than 150 m, or garbage coordinates: the plan dead-reckons from the last usable sample,
`slack_eff = slack_last - reach(elapsed, v_last_plan)`, with `v = V_MAX` for the next step. Far away this stays long;
near the line it falls to 3 s and high accuracy.

### 2.5 Fewer changes: ladder and hysteresis

- **Ladder.** `T` is rounded down to `3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 75, 90, 120, 150, 180, 240,
  300 s`. Rounding down is the safe side and costs at most 25 %. The location request then changes only when the
  rung changes, not on every fix (cause 3 in section 1).
- **Shorter: at once.** Safety is never delayed.
- **Longer: only with margin.** A longer rung is taken only when the unrounded value is at least 1.15 times that rung.
  A cold or late previous step does not count as a reason to wait.
- **Priority.** High accuracy on when `T_unrounded <= 15 s` (the constant of today, `HIGH_ACCURACY_BELOW`) or when the
  line is less than 75 s away at `v_est` (GPS needs time for its first fix). Off again only when
  `T_unrounded >= 25 s` and the line is more than 120 s away. Near the line accuracy is not a luxury: the side is
  only readable outside `max(10 m, accuracy)`, a 60 m network fix cannot ring until 60 m past the line.

### 2.6 What Android is asked for

| Setting | Value | Why |
|---|---|---|
| Interval | `T` | from Rust |
| Quality, provider | high: GPS provider, `QUALITY_HIGH_ACCURACY`. Else fused (or network), `QUALITY_BALANCED_POWER_ACCURACY` | as today, later switch. `QUALITY_LOW_POWER` is not used: cell-only fixes are worse than 150 m and would be thrown away |
| `setMinUpdateIntervalMillis` | `T` (today `T / 2`) | Fixes other apps trigger cost a Rust call, a file write and a notification each, and the guarantee does not need them |
| `setMinUpdateDistanceMeters` | 0, written out | A fix held back for "not moved enough" looks like a missing fix: the watchdog would fire and ask with `getCurrentLocation`, which costs more than the fix. It also saves no power in the chip |
| `setMaxUpdateDelayMillis` | 0, written out | Batching delivers fixes late, and the invariant needs each fix when it is taken |

**Background, Doze.** Nothing changes in the mechanism: a foreground service of type `location` keeps its updates with
the screen off, and the watchdog is `setExactAndAllowWhileIdle`. Deep Doze needs a phone that lies still, and a phone
that lies still is not approaching the gate. Longer intervals also mean fewer watchdog alarms.

**A late or missed fix (catch-up).**

- Late but it arrives: nothing special. `dt_last` and `D_last` are what really happened, so `v_now` and the next
  interval are right for the real gap. The answer says `late` for the log.
- It does not arrive: the watchdog fires at `retry_ms` and asks once per provider, as today. New: when both answer
  null, Kotlin calls the headless kind `trip_no_fix` and applies the answer, instead of repeating the old interval for
  ever. Rust dead-reckons as in 2.4, so the retries get denser and switch to high accuracy as the worst case comes
  close to the line.

### 2.7 Constants

| Constant | Value | Reason |
|---|---|---|
| `V_MAX` | 60 m/s | Exists (`MAX_SPEED_MPS`), a fast intercity train |
| `A_MAX` | 2.0 m/s² | Metro, tram and bus in service stay under 1.3 to 1.5 (standing passengers), trains under 1.0. An ordinary car reaches city speed in a few seconds but then stops accelerating, which the envelope covers. The gap between 2.0 and real vehicles is the margin for P3 and for curved paths |
| `V_FLOOR` | 2.0 m/s | A brisk walk, when no movement was seen at all |
| `H` | 1.5 | Headroom on the 5-minute average: stop-and-go traffic cruises well above its average |
| `K` | 2 | Exists (AD-SA1): half the slack stays as a margin, which also covers a fix that is one whole interval late |
| `MIN_POLL`, `MAX_POLL` | 3 s, 300 s | Exist |
| Window | 300 s, 48 samples | The owner's 5 minutes |
| Doppler bound | `1.2 * s + 1 m/s` | Typical GPS speed error is under 0.5 m/s |
| High accuracy | on at 15 s or 75 s to the line, off at 25 s and 120 s | 2.5 |

### 2.8 Worked table

Steady movement, accuracy 10 m, interval after the ladder. `b` balanced, `H` high accuracy (GPS).

| | 5 km | 2 km | 700 m | 300 m | 100 m |
|---|---|---|---|---|---|
| **Today, any speed** | 41.5 s b | 16.5 s b | 5.7 s H | 3 s H | 3 s H |
| Standing still | 75 s b | 30 s b | 15 s b | 10 s H | 3 s H |
| Walking, 1.4 m/s | 60 s b | 30 s b | 15 s b | 10 s H | 3 s H |
| Bus, 8 m/s | 50 s b | 25 s b | 15 s b | 6 s H | 3 s H |
| Train, 25 m/s | 40 s b | 15 s b | 8 s H | 3 s H | 3 s H |
| First fix (cold) | 40 s b | 15 s b | 5 s H | 3 s H | 3 s H |

Whole approaches, simulated with the same formulas:

| Approach | Fixes today | Fixes new | GPS-on time today | GPS-on time new |
|---|---|---|---|---|
| Walk the last 5 km | 315 | 164 | 21.6 min | 6.5 min |
| Walk the last 700 m | 145 | 73 | 8.4 min | 6.6 min |
| Bus, last 5 km | 54 | 34 | 3.7 min | 1.4 min |
| Train, last 5 km | 17 | 15 | 1.1 min | 1.3 min |
| Stand still at 700 m, one hour | 635, GPS | 240, balanced | 60 min | 0 |

At 700 m the answer to the owner is: from every 5.7 s on GPS to every 15 s on the low-power provider. It cannot be
much longer and stay a guarantee: a tram that leaves the previous stop reaches a gate 700 m away in under a minute.

The numbers were produced by a throw-away script. It also ran 20 000 random trips (start 150 m to 8 km away, standing,
walking or at 5 m/s, then a vehicle at 0.3 to 2.0 m/s² up to 8 to 60 m/s at a random moment, accuracy 5 to 60 m,
half of them with every fix late by the whole grace): no trip reached the line between two fixes outside the floor
zone. The same simulation becomes a Rust test (section 4).

## 3. Where it lives

Rust decides, Kotlin applies, as AD-ST1 and AD-ST2 say. No new crate, no new dependency.

**`crates/rosty_geo/src/cadence.rs` (new, pure).**

```rust
pub struct SpeedSample { pub t_ms: i64, pub at: Coord, pub accuracy_m: f64, pub planned_ms: u32 }
pub struct SpeedHistory { .. }                       // Serialize, Deserialize, Default; push(), clear()
pub struct SpeedEstimate { pub avg_mps: f64, pub last_mps: Option<f64>, pub now_max_mps: f64,
                           pub est_mps: f64, pub assumed_mps: f64, pub basis: SpeedBasis }
pub enum SpeedBasis { Cold, Bound, Doppler, Average, Floor }
pub struct CadenceInput { pub line_m: Option<f64>, pub accuracy_m: Option<f64>, pub notice_m: Option<f64>,
                          pub assumed_mps: f64, pub est_mps: f64,
                          pub prev: Option<Duration>, pub prev_high: bool }
pub struct Cadence { pub next_fix_in: Duration, pub retry_after: Duration, pub high_accuracy: bool,
                     pub assumed_mps: f64, pub basis: SpeedBasis, pub late: bool }

pub fn reach_m(t_s: f64, v_mps: f64) -> f64;
pub fn safe_interval(slack_m: f64, v_mps: f64) -> Duration;      // max(R0, min(R1, R2)), clamped, no ladder
pub fn ladder_floor(d: Duration) -> Duration;
pub fn plan(input: &CadenceInput) -> Cadence;
impl SpeedHistory { pub fn estimate(&self, speed_mps: Option<f64>) -> SpeedEstimate; }
```

`poll.rs` stays as it is: `poll_interval` is rule `R0`, `retry_after` is `grace`, their tests do not change.

**`rosty_geo::session`.** `ActiveTrip` gets `#[serde(default)] pub speed: SpeedHistory`. `ActiveTrip::on_fix(fix,
at_ms)` already takes the time and ignores it (`session.rs:219`): it now pushes the sample and calls
`cadence::plan`; callers pass `FixIn::clock_ms()` (`elapsed_ms` or `at_ms`). `FixOutcome` carries the `Cadence`;
`fix_out_in` (`session.rs:301`) fills `next_fix_ms`, `retry_ms` and `high_accuracy` from it. `CrossingTrip::update`
keeps its cold value. `ActiveTrip::no_fix(now_ms) -> Cadence` is 2.4 for the watchdog.

**Wire, all additive** (`#[serde(default)]`, Kotlin defaults, so old and new sides mix):

- `FixIn`: `elapsed_ms: Option<i64>`.
- `FixOut`: `speed_mps: Option<f64>` (`v_est`), `assumed_mps: Option<f64>`, `cadence: Option<SpeedBasis>`,
  `late: bool`. `next_fix_ms`, `retry_ms`, `high_accuracy` keep their names and meaning.

**Ops and kinds, following the existing ones.**

- `stops.cadence` in `crates/rosty_ffi/src/ops/stops/misc.rs`, next to `stops.poll` (`misc.rs:64,537-560`, the pattern
  to copy: `op!`, a `Req` struct, `Case` fixtures). Request `{line_m?, accuracy_m?, notice_m?, assumed_mps?, est_mps?,
  prev_ms?, prev_high?}`, answer `{interval_ms, retry_ms, high_accuracy, assumed_mps, cold_ms}`. It exists so the curve
  has contract fixtures and a Kotlin test can read the table; the trip itself does not call it.
- Headless kind `trip_no_fix` in `crates/rosty_app_core/src/stops/headless.rs` `TABLE` (`headless.rs:61-65`), request
  `{dir, now_ms, elapsed_ms?}`, answer a `FixOut` without position, or `{"state":"ended"}`.
- `stops.trip_fix` and the headless `trip_fix` need no change of their own: they serialise `FixOut`.
- `:core-bridge`: `StopsOps.cadence(CadenceRequest): Outcome<CadenceAnswer>` beside `StopsOps.poll`
  (`StopsOps.kt:288`), the new optional fields on `TripFixAnswer` and `FixIn` (`HeadlessModels.kt`, `TripModels.kt`),
  fixtures in `crates/rosty_ffi/fixtures` and `OPS.md`.

**Kotlin, `:platform` only.**

- `TripModel.kt`: `tripFixJson` adds `elapsed_ms`; `TripAnswer` unchanged.
- `BridgeTripComponents.kt` `requestUpdates`: the three explicit settings of 2.6. Nothing is computed there.
- `askOtherProvider`: on null, `Headless.call("trip_no_fix")`, then the same "apply" lines as `onFix`.
- One log line per fix (section 4).
- No interval, speed or distance rule is added to Kotlin. `tripWatchdogMs` stays the mirror for the start spec only.

`android/stops` needs no change for the algorithm. The blue dot is question 1.

## 4. Tests and proof on a phone

**Rust, `cadence.rs` unit tests** (grid sweeps like `poll.rs`, plus a seeded generator in the test module; the
workspace has no property-test crate and this plan adds none):

1. `reach_m`: 0 at 0, non-decreasing in `t` and in `v`, never above `V_MAX * t`, continuous at `t1`.
2. **The invariant, as a sweep:** for `slack` 0 to 100 km, `v` from `V_FLOOR` to `V_MAX`: when `safe_interval > MIN_POLL`
   and the adaptive rule decided, `reach_m(T + grace(T), v) <= slack`; when `R0` decided, `V_MAX * 2 * T <= slack`.
3. Monotonic: `safe_interval` non-decreasing in `slack`, non-increasing in `v`; `plan` non-decreasing in `line_m` for a
   fixed input; worse accuracy never lengthens.
4. Never denser than today: `safe_interval(slack, v) >= poll_interval`, and `plan(..) >= ladder_floor(poll_interval)`.
5. Caps: exactly 3 s at and behind the line, exactly 300 s far away, unknown distance 300 s, NaN and infinity safe.
6. Ladder: result is always a rung, `ladder_floor(d) <= d`, loss at most 25 %.
7. Hysteresis: a shorter value is taken at once; a longer one only at 1.15; the answer is never above the value
   without hysteresis; priority does not flip when `T` moves between 15 and 25 s.
8. Estimator: standing still with jitter gives `avg = 0`; a 2 km jump gives `V_MAX` once and leaves `avg` clean;
   walk 4 minutes then bus: `assumed` rises on the first bus fix; bus then a 60 s stop keeps `H * avg`; a 4-minute gap
   with 3 km moved gives `V_MAX`; time going backwards clears the history; one sample is `Cold`; a provider speed
   tightens the bound but never below `H * avg`; more than 48 samples or 300 s are dropped.
9. **The invariant, as a simulation** (seeded, a few thousand trips, the script's cases): a traveller in the plane
   with speed at most `V_MAX` and acceleration at most `A_MAX` heading for the segment, fixes with error inside the
   accuracy, late by up to `grace`. Assert: no fix interval longer than `MIN_POLL` starts on one side and ends past
   the line. Then with `ActiveTrip`: every such trip rings, at most `MIN_POLL + grace + dead band / speed` after the
   crossing.
10. The worked table of 2.8 as an `example_table` test, like `poll.rs:147`.
11. Notice target: with `BeforeCircle`, the fix before the send moment is at most half the remaining time away.

**Rust, around it.** `session.rs`: an old `trip.json` without `speed` loads; `fix_out` carries the cadence fields.
`headless.rs`: `trip_no_fix` answers, tightens with elapsed time, answers `ended` with no trip. `rosty_ffi`: fixtures
for `stops.cadence` (far, close, cold, unknown) and the regenerated `trip_fix` answers; `kotlin_contract.rs`: the
service contains `setMinUpdateDistanceMeters(0f)`, `setMaxUpdateDelayMillis(0)`, `trip_no_fix`, `elapsed_ms`.

**Kotlin, `:platform`** (`TripServiceTest`, Robolectric, `FakeRustCore`, as today):

- A fix sends `elapsed_ms`; an answer with the same interval and priority does not register again (count the
  shadow's requests); a new rung registers once with the right provider and quality.
- Both providers answer null: `trip_no_fix` is called and its interval and watchdog are applied (`TripClock` fake).
- `TripFixJsonTest`, `TripStatusTest`: the new optional fields, and an old answer without them still parses.
- `:core-bridge` `StopsOpsNativeTest`: `cadence` against the real library, one row of the table.

**On a phone.** One line per fix through `RostyLog.i("trip", ..)`, readable in Settings, About, hold Version, Logs:

```text
fix t=12:04:31 d=702m acc=9 v=1.3 assumed=11.2 next=15s high=false basis=bound late=false
```

Walk, bus and one standing wait; then read the gaps between the `t=` values against the table. Pass when: at 700 m
on foot the gap is 12 to 20 s and `high=false`; the gap never grows while `d` falls at a steady speed; boarding a bus
shortens the gap on the next line; the alarm rings within a few seconds of the line; no `searching` runs longer than
before.

**Battery reasoning.** The cost is GPS-on time first, wake-ups second. A GPS chip asked every 15 s or less never
sleeps; with 20 s and more between fixes it does. The plan moves the GPS zone for a walker from 1.8 km to about
450 m, halves the fixes, and each fix that is not taken also saves a `trip.json` write, a notification update and an
exact alarm. The request is no longer registered again on every fix. Standing still far from the gate, the worst case
of today, drops from one GPS fix every 5.7 s to one network fix every 15 s. Real figures need the phone:
`adb shell dumpsys batterystats` before and after one identical walk is enough, and is optional.

## 5. Risks and questions

**Risks.**

- `A_MAX` is the one assumption that carries the guarantee. A hard car launch on an open road, or a path that bends
  sharply between two fixes, eats into the margin. The floor zone and `R0` still hold. Mitigation: the constant is one
  line, and the simulation test states the limit.
- Balanced fixes are 20 to 60 m off. That shortens the interval (safe) and makes the gain smaller than the table in
  bad reception.
- GPS needs seconds to deliver after the switch to high accuracy. The 75 s lead covers a warm start, not a cold one
  indoors; the fused provider keeps answering meanwhile.
- `trip.json` grows by up to 48 samples (about 4 KB) and is written on every fix, as today.
- The arriving-soon message now uses `v_est` when the provider gives no speed. It can go out earlier than before on the
  balanced provider; before, it often waited for GPS.
- Device vendors throttle differently; the watchdog path is unchanged, so the worst case is today's.

**Questions for the owner.**

1. **The blue dot during a trip.** With the app open, the map asks every 2 s by itself. Keep that (the screen is on
   anyway), or let the dot follow the trip's own fixes while a trip runs? Proposal: follow the trip, and ask faster
   only while "follow me" is on.
2. **Tunnels.** In a metro there is no fix at all. Today nothing rings underground. With `trip_no_fix` Rust knows when
   the worst case has reached the line. Should the alarm ring then ("location lost near your stop")? Proposal: not
   in this round, the status stays "searching".
3. **Cars.** Is Stops also used in a car or taxi? If yes `A_MAX` stays 2.0; if it is public transport and walking
   only, 1.5 gives intervals about 15 % longer.

## 6. Steps (one round)

1. `rosty_geo::cadence`: `reach_m`, `safe_interval`, `ladder_floor`, `plan`, tests 1 to 7 and 10.
2. `SpeedHistory`, `estimate`, tests 8.
3. Wire into `ActiveTrip` (`speed`, `on_fix`, `FixOutcome.cadence`, `fix_out_in`, `no_fix`), the notice target and the
   `v_est` fallback for ETA and notice, `FixIn.elapsed_ms`, new `FixOut` fields; tests 9 and 11, session tests.
4. `trip_no_fix` headless kind; `stops.cadence` op; fixtures, `INDEX`, `OPS.md`.
5. `:core-bridge`: models, `StopsOps.cadence`, native test.
6. `:platform`: `tripFixJson`, `requestUpdates` settings, `trip_no_fix` on a null fix, the log line; `TripServiceTest`
   and the contract test.
7. Docs: AD-SA1 and AD-SX5 in `docs/architecture.md` point to the new rule (new entry AD-SA10), the Stops rows of
   `docs/kotlin-ui/architecture.md` and `checklist-e7.md`.
8. Gate: `scripts/kotlin-ui-check.sh` (release only), then the phone log walk of section 4.

Not in this round: the blue dot (question 1), a tunnel alarm (question 2), motion sensors to stretch the standing-still
case further.
