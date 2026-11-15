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
goldens of the real-core tests were re-recorded once (checked on the line-choice pair: the light picture now has
the map where its dark twin has it). After the merge of the app work (which narrows the top padding to the search
pill and re-recorded every golden) the same held true for its new shot classes: only the light pictures of
E7.8, E7.9, E7.11 and E7.12 moved (the first view placed after the insets instead of before), the dark ones did
not; they were re-recorded, together with the four E7.10 Settings pictures that gained the WhatsApp group.
A shot test stops at its first failing picture, so a red light picture hides its dark twin: the dark ones were
checked in a second verification after the light ones were recorded.

## 3. E7.13

`docs/kotlin-ui/acceptance.md` is new (the owner-device list of E8.7), with the Stops entry "The map at 120 Hz":
three 20 second runs (pan, pinch, pan under the sheet), `dumpsys gfxinfo dev.rosty.stops reset` / `framestats`, the
p90 <= 8.3 ms target, an awk line for the exact percentiles (the summary rounds to whole milliseconds, which matters
at this budget), the table to fill in and the four fixes to try before a record is opened.
`docs/kotlin-ui/decisions/stops-map-renderer.md` is the record's skeleton (status: not opened) with the R1 host
numbers, what the host cannot show, the fixes with their costs and the options table. ARCH 8.4 links both.

## 4. The arriving-soon message (E7.10 WhatsApp part, AD-ST7)

Package `dev.rosty.stops.notice`; the Settings hook `settings/ArrivingSoonSettings.kt` is filled from it.

**Who sends.** Nothing new: the trip service asks Rust for every fix (`trip_fix`), and Rust sends the message
itself through the stored gateway link (`rosty_app_core::stops::headless`, `wa_link::send`), at most once, with
its own retry rule. Kotlin only decides whether a trip *carries* a message: `stops.trip_start {notice}` writes it
into `trip.json`. So there is no `:platform` or `:wa` send code to add.

**Settings.**
- The group after Trip (`ArrivingSoonSettingsGroup`): "Arriving-Soon Message" (to whom, Off, or "Connect WhatsApp
  first") and "WhatsApp" (the link in a word). Routes `StopsRoute.ArrivingSoon`, `ArrivingSoonChat`, `WhatsApp`.
- `ArrivingSoonPage` (while connected): the chat row, Turn Off (with Undo: chat, text and time come back), the
  message field with "Reads: ..." under it (`stops.notice_render` with a sample stop and 8 minutes), the refusal
  from `stops.notice_validate` worded from `detail.reason` (our strings), Save Message, Use the Default Message,
  and When (the choices and their labels are `stops.prefs_choices`, in the phone's language). Not connected: one
  row to Settings > WhatsApp; the saved message is kept.
- Every change is one `StopsStore.edit` (`set_notice`, `set_template`, `set_notice_when`, `clear_notice`).
  Looking at the page writes nothing. The message is saved with its own row, not on every keystroke: a half-typed
  message never goes out with a trip.
- A message that was never rewritten (a default of any language; `prefs_choices.default_templates`, new) is shown
  as the default of the phone's language, which is also how a trip sends it.
- `StopsWhatsAppEntry` is the shared `:wa` `WhatsAppSettingsRoute` with `AppId.Stops`, wired as Contacts does.

**The chat picker** is a pushed page (`NoticeChatPage`: search field, the chats in the gateway's order, a tick on
the chosen one), not a sheet: it has a keyboard and a long list, and Back is the way out.

**Names (owner rule: saved contact, then WhatsApp's name, the number last).** The rule is not Kotlin's: the new
op `stops.notice_chats` names the whole chat list in one call with the shared rule of
`rosty_core::wa_identity::display_name` (contact, verified business name, push name, the chat's own name, the
number, else "WhatsApp user" / "WhatsApp kullanıcısı"; a group is its subject, else "Group"). `namedChats` only
hands it what is known. Stops holds no contacts permission, so the "saved contact" is the contact name WhatsApp
itself reports for the person (`WaProfile.contactName`). The profiles are asked for when the picker opens
(`WaAvatars.wantProfile`) and the list is named again when they arrive (`WaAvatars.revision`). A person shows the
number under the name; a bare number shows once; a LID never shows digits. The name is stored with the chat
(`chat_name`) as the picker showed it. **New `StopsOps` method: `noticeChats`** (fakes of `StopsOps` must add it;
none exist in the tree, the tests fake the core below it).

**The per-trip switch** (R1's `ArrivingSoonHook` stub). `TripNotice` (container `arrivingSoon`) implements the
hook: `noticeFor` answers the settings' message when a chat is chosen, WhatsApp is linked (or the stored link is
still being read: a chat can only have been chosen while connected) and the switch is on. `PlanRow` draws "Text
<chat> when arriving" with its switch under Start, only when a message can go out. Off lasts until the user
turns it on again (as GPUI kept `notice_on`). The live card says what became of it (`noticeLine`: "Will text
when arriving", "Arrival message sent", "Arrival message could not be sent"), from `RunningTrip.notice` (the
trip file has a notice) and the service's `notice_sent` / `notice_failed`.

**Edits outside the package:** `trip/TripViewModel.kt` (the hook's `PlanRow`, `RunningTrip.notice`,
`LiveTrip.noticeSent/noticeFailed`), `trip/TripSheet.kt` (the row and the line), `home/HomeEntry.kt` (passes the
hook), `StopsContainer.kt` (`arrivingSoon`), `StopsRoute.kt`, `StopsApp.kt`, `settings/ArrivingSoonSettings.kt`,
strings (`strings_notice.xml`, en + tr; the unused `trip_arriving_soon` removed).

**Tests.** `NoticeLogicTest` (naming on the real `stops.notice_chats`, search, availability, the card line),
`NoticeViewModelTest` (real `prefs_edit` / `prefs_choices` / `notice_*`, English and Turkish),
`TripNoticeTest`, `NoticeAppTest` (Settings to the picker and back; a started trip's `trip.json` carries the chat
and the message, or none when switched off or not linked; the card line follows the service),
`E710NoticeShotTest` (`e7.10-arriving-soon`, `e7.10-template-field-selection`, the picker, not connected,
Turkish, font scale 2, the per-trip switch; light and dark).
