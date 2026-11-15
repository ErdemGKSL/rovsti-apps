# E7 R2: approach (fable part)

Rust sentences in Turkish, the R1 golden flake, the E7.13 device check and decision record, the arriving-soon message.
Read with `e7-r1-approach-map.md` (map engine, picking, the ops added in R1).

## 1. Rust sentences in Turkish

**The rule.** Every sentence Stops shows from Rust exists in English and Turkish. `rosty_geo::Lang` (`En`, `Tr`) is
read from any tag a phone writes (`tr`, `tr-TR`, `tr_TR`); absent, `null` or unknown is English.

**Shape in Rust.** Every text producer has an `_in(lang, ...)` form; the old name stays and writes English
(`format_distance` calls `format_distance_in(Lang::En, ..)`). That doubles the names but changes no caller: the GPUI
app builds untouched and every existing English test and fixture still pins the English text. Enum labels have
`label_in`, user-facing errors `message_in`. No ambient "current language": the language is an argument, so each
sentence is tested in both languages (`crates/rosty_geo/tests/texts.rs`, and in `rosty_app_core::stops::pick`).

**Turkish, as written.**
- Numbers: "850 m", "1,2 km" (decimal comma), "1.234 durak" (dot groups), "1 sa 05 dk", months Oca..Ara.
- Bearings: K, KD, D, GD, G, GB, B, KB.
- No plural after a number ("3 erteleme", "12 yolculuk"); English keeps "1 stop" / "2 stops".
- No suffix ever follows a name the user typed, because vowel harmony cannot be done blind on "Kadıköy" or "Pier
  West". Sentences are turned so the name stands alone: "Yoldayım, hedef Kadıköy", "Hat 34, Kadıköy yönü",
  "Varış: Ev – İş, 5 Kas 2026, 15:00", "Kullan: Kadıköy", "Ev – İş çizgisine 420 m" (the suffix is on "çizgi").
  Suffixes on fixed words are written out ("A'yı seç", "B'yi seç", "50 km'den fazla").
- A point without a name is "A noktası" / "B noktası". A trip stores the name the user saw, so both "Point A" and
  "A noktası" (and "Point", "Nokta", empty) read as "no name" in either language (`history::is_generic_label`).

**Edge cases fixed on the way.** `format_distance` rounded after choosing the unit, so 995 m read "1000 m": it now
rounds first ("1.0 km"), and a negative or NaN length reads "0 m" instead of "-10 m". `from_you_text` with a broken
coordinate reads "You are here". The notice's `{min}` uses the same never-zero minutes as the status.

**Through the ops.** The ops that write a sentence take an optional `locale` (the list is in `OPS.md`); old requests
answer as before. Each has a fixture case `tr` (errors: `tr_*`), 33 new cases, and no existing fixture changed.

**The trip keeps its language.** The service's notification is written by Rust on the headless path, with no app
around. Three places could know the phone's language there; all three are used, cheapest first:
1. `stops.trip_start {locale}` stores it in `trip.json` (`ActiveTrip.lang`, absent for English, so an old build
   still reads the file). The status and the "a few" of a message without an ETA are written in it.
2. The headless `trip_fix` request may carry `locale`; it replaces the stored one. `TripService` sends
   `resources.configuration.locales[0]` with every fix, so a language change during a trip shows on the next fix.
3. `stops.trip_{restore, fix, status_share, end}` take the same optional override for the app's own calls.
An arriving-soon message the user never rewrote (still the default of either language) goes out as the default of
the trip's language; a rewritten one is the user's and is left alone.

**Kotlin.** One helper: `StopsContainer.localeTag()` (the app's resources, read at every call, so no restart).
`NativeStopsOps(core, locale = { .. })` fills `locale` into every request that has the field unless the caller set
one, so no screen passes it. Files touched outside `map/`: `StopsContainer.kt` only (plus `:platform`
`TripModel.kt` and `BridgeTripComponents.kt` for the service, two lines and a test). `StopsOps` did not change
(`prefsChoices(Empty)` keeps its signature; the request with the locale is built inside).

**Not translated, on purpose.** Log lines; GTFS import errors (`GtfsError`, file and column names, shown as a detail);
the GPX `creator`; coordinate formats; the notification channel and the service's own strings (Android resources).

## 2. The R1 golden flake

**What it was.** Not one bug. Every app-level Stops test on the real core let the core's answers arrive whenever the
machine got round to them: `settleRust` slept 15 ms of real time per 100 ms clock step and hoped. The picture then
depended on when an answer landed:
- **The first view** is placed when the files are read, with the padding of that moment. Traced in
  `PlaceFirstView`: the same test placed it with a top padding of 168, 252 or 293 px, depending on whether the
  files were read before the test's window insets were applied (inside `composeFrame`), after, or after another
  class had run in the JVM. The first (light) picture of a test was usually "before" and the second (dark)
  "after"; a whole-module run flipped that now and then: `e7.5-line-choice-in-stop-ui-dark` in R1,
  `e7.6-arrival-summary-light` in an R2 run. The old light goldens of the real-core tests had recorded the
  "before" state.
- **A status pushed right after Start** was dropped when the start's last answer had not been picked up yet (the
  card stayed on "Locating"): `e7.5-background-row-light`, and the start that "never happened" in `TripAppTest`.
- **A tile** painted or not when the picture was taken (`Dispatchers.Default`): `e7.1-home-landscape-dark`.

None of it is a race in the product: placing the first view where the free area is at that moment is right, a
late tile is a late tile.

**The fix (test-side, plus one seam).**
- `Background` (test) holds the real core's answers. A call runs at once on the core's threads, but its caller
  goes on only when the test calls `Background.await()`, which first waits until every caller so far stands at its
  gate with the answer in hand (not merely until the core has answered: that was the last hole, a caller still on
  its way would get the answer at any later time). The gates open in the order the calls were made; an answer
  that asks the next question is waited for too. `settleRust` and the E7.1 landscape loop call it before every
  clock step. So no answer can land inside `composeFrame` or between two steps: every run sees the same sequence.
  Only `AppFixture` tests hold answers (`FakeStopsCore.holdRealAnswers`); view-model tests and `runBlocking`
  callers get theirs as before.
- Tiles: `StopsContainer.tileRasterDispatcher` (the seam; two threads in the app) is `Unconfined` in
  `TestStopsContainer`, so a screen test paints its tiles inline, in the step that asked for them.
- `settleRust` keeps a 15 ms real wait per step for the app's other threads (the container's scope, the permission
  controller), which nothing counts. `SavedViewModelTest` built its model before a test changed `here`; it is
  built on first use now.

**Evidence.** The three real-core shot classes recorded twice in a row are byte-identical; three whole-module
verifications in a row are green (the run before the last hole was closed had one failure in two). The light
goldens of the real-core tests were re-recorded once: they now show the same map position as their dark twins.

## 3. E7.13

`docs/kotlin-ui/acceptance.md` is new (the owner-device list of E8.7), with the Stops entry "The map at 120 Hz":
three 20 second runs (pan, pinch, pan under the sheet), `dumpsys gfxinfo dev.rosty.stops reset` / `framestats`, the
p90 <= 8.3 ms target, an awk line for the exact percentiles (the summary rounds to whole milliseconds, which matters
at this budget), the table to fill in and the four fixes to try before a record is opened.
`docs/kotlin-ui/decisions/stops-map-renderer.md` is the record's skeleton (status: not opened) with the R1 host
numbers, what the host cannot show, the fixes with their costs and the options table. ARCH 8.4 links both.
