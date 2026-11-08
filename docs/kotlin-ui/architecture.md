# Kotlin UI architecture (no GPUI)

Status: accepted plan for branch `ui-overhaul`. Owner: architect. Stories that implement it:
`_bmad-output/kotlin-ui/epics.md`. Toolchain and the check script: [toolchain.md](toolchain.md).

This document replaces the UI half of [../architecture.md](../architecture.md). The domain half of that
file stays true: every AD-ST, AD-SA, AD-SX, AD-CT, AD-MSG and AD-WA decision keeps its meaning, only the
layer that draws the screens changes. Where this file and `docs/architecture.md` disagree about the UI,
this file wins; where they disagree about domain rules, `docs/architecture.md` wins.

## 0. Ground rules

| Rule | Why | Where it is written |
|---|---|---|
| The UI is Kotlin + Jetpack Compose. No GPUI, no gpui-fusion, no GPUiForge in the new apps. | Owner decision for this branch. | task brief; `.claude/skills/convx-motion-compose/SKILL.md` section 1 |
| No Material overlays: menus, popovers, sheets, dialogs, toasts, text toolbars around our own fields are ours. Material3 may be used for nothing visible (it is not a dependency of `:design`). | The look is iOS 26 liquid glass; Material overlays live in separate windows and cannot sample our glass. | `SKILL.md` section 1; `references/overlays.md` section 1 |
| Domain logic stays in the platform-free Rust crates. Kotlin maps, draws and talks to Android. | Same rule as today (`CLAUDE.md`, `docs/architecture.md` "Pure-core rule"). | `docs/architecture.md` lines 75-78 |
| UI number policies (placement, projection, rubber band, collapse fraction, glass tier, map camera maths) are pure Kotlin functions with JVM tests. | They run every frame; a JNI hop per frame is not allowed (`references/performance.md` 9). | `SKILL.md` section 1 |
| Convx (GPL-3.0) is ported with attribution; Rovsti is GPL-3.0-or-later. Ported files keep their upstream header and add `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`. glisse (MIT) may be ported with the header `// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang`. `android/THIRD_PARTY_NOTICES.md` lists every source. | Owner licensing decision (round 3): the whole project is GPL-3.0-or-later (`Cargo.toml` `[workspace.package]`, `LICENSE`). | `SKILL.md` section 0; `references/checklist.md` A |
| The GPUI apps (`apps/*`, `crates/rovsti_ui`, `crates/rovsti_wa_ui`, `crates/liquid_glass_kit`) are a feature inventory, not a design reference. They keep building until epic E8 removes them from the default build. | Owner complaints in `docs/design/layout.md` Part 1 (X1-X10) are the reason for the rewrite. | task brief |

## 1. What the owner complained about, and which part of this design fixes it

| Complaint (GPUI apps) | Fix in this architecture | Section |
|---|---|---|
| Messy layout: overlapping avatar stacks (`docs/design/layout.md` M1), wrapping filter chips (M2), a source pill on every row, inconsistent spacing (X3) | One spacing token set in `:design`; `ListRow` takes exactly one leading avatar slot; `ChipRow` is a single non-wrapping `LazyRow`; the source (SMS / WhatsApp) is a 14 dp glyph after the name or a chip, never a pill per row. Convention tests forbid ad-hoc dp literals in app modules. | 4.3, 9 |
| Floating tab bar covered the last row (X2) | `ChromeMetrics` measures the real bar and publishes `LocalContentInsets`; every list takes it as `contentPadding`; each list screen has a screenshot scrolled to the end. | 4.4 |
| Text boxes worse than an HTML input | Every field is `BasicTextField(state = TextFieldState)`: tap places the caret, long-press selects, handles, magnifier, the platform cut/copy/paste toolbar, correct IME geometry come from the platform. | 4.6 |
| No popovers or context menus; tapping made buttons flow into the content and push it | One `OverlayHost` per window; menus morph out of their anchor; press feedback is draw-phase only; a unit test asserts trigger bounds are identical before and after open. | 4.5 |
| Stops must be a full-screen map, controls floating on top | Stops' home is a full-window map layer with floating glass chrome and a sheet that allows background interaction; no map box anywhere. | 8.4 |

## 2. Gradle layout (`android/`)

The Gradle root already exists (`android/settings.gradle.kts`, `android/gradle/libs.versions.toml`, AGP 9.5.1,
Kotlin 2.4.21, Compose BOM 2026.09.00, Robolectric 4.17, Roborazzi 1.77.0; see [toolchain.md](toolchain.md)).
Modules:

```
android/
  build-logic/                 included build: convention plugins (rovsti.android.library, rovsti.android.app,
                               rovsti.compose, rovsti.screenshots, rovsti.rust). One place for SDK levels,
                               Compose compiler reports, Robolectric/Roborazzi config, lint, ktlint.
  design/        :design       tokens, theme, motion, haptics, glass, overlays, controls, text fields, tab bar,
                               large-title scaffold, rows, chips, avatars, photo viewer. No domain types, no
                               Android providers, no Rust.
  testing/       :testing      test-only library (dev.rovsti.testing): ShotTest (Robolectric native graphics, pinned
                               window, locale, zone, clock, simulated system bars, light/dark/fs2/rtl/landscape
                               capture), FixedClock. (`StripedBackdrop` lives in `:catalog`, which ships to no user.) Used as testImplementation(project(":testing"))
                               only. A module instead of AGP test fixtures keeps Kotlin test-fixture flags out.
  catalog/       :catalog      debug-only demo app (dev.rovsti.catalog) with one page per :design component and
                               its states; its screenshot tests are E1's proof.
  core-bridge/   :core-bridge  Kotlin side of the Rust core: JNI externs, the Rust dispatcher, typed facades per
                               domain, @Serializable models, error mapping, the event sink, the cargo build tasks.
  platform/      :platform     Android providers and services as plain Kotlin (contacts, call log, SMS/MMS,
                               telecom, notifications, alarms, location, media store, clipboard, share,
                               documents, roles, permissions, OEM settings). Ported from crates/rovsti_android/kotlin.
  wa/            :wa           WhatsApp over the bridge: link state, chats, events, calls, call audio, the
                               shared WhatsApp settings screen.
  shell/         :shell        what every app shares above :design: base activity, RovstiRoot, app container
                               pattern, permission request UI, common settings pages (appearance, haptics,
                               about and licences), Navigation 3 helpers.
  phone/         :phone        dev.rovsti.phone     (ROLE_DIALER)
  messages/      :messages     dev.rovsti.messages  (ROLE_SMS)
  contacts/      :contacts     dev.rovsti.contacts
  stops/         :stops        dev.rovsti.stops
  benchmark/     :benchmark    Macrobenchmark + baseline profile generator (E8; runs only with a device or
                               managed emulator)
```

Dependency graph (arrows point at dependencies; nothing points up):

```
 :phone  :messages  :contacts  :stops          :catalog
    \        |          |        /                 |
     +-------+----+-----+-------+                  |
                  |                                |
               :shell ----------> :wa              |
                  |                 |              |
                  +----> :design <--+--------------+
                  |                 |
                  +----> :platform  |
                  |         |       |
                  +----> :core-bridge <-------------+ (:wa, :platform for headless calls)
```

- `:design` depends on Compose UI/foundation/animation only (plus `androidx.graphics.shapes` if needed). It must
  compile and its tests must run without the Rust library. This keeps the catalog and E1 independent of E2.
- `:platform` depends on `:core-bridge` only where a cold path needs a Rust decision (receivers, `TripService`).
- `:wa` depends on `:core-bridge` and `:design` (its settings screen), never on `:platform` internals except the
  push and audio seams it owns.
- Library modules declare **no** activities, services, receivers or providers in their manifests. A library
  manifest merges into every app that depends on it; each app declares exactly the components it owns (section 7).
- The Compose compiler plugin is applied to every module that has a `@Composable` (`references/performance.md` 2).
  Bridge models get `compose_stability.conf` entries only when they are truly immutable `val` classes.

Package roots: `dev.rovsti.design`, `dev.rovsti.core` (bridge), `dev.rovsti.platform`, `dev.rovsti.wa`,
`dev.rovsti.shell`, `dev.rovsti.<app>`. Ported files that Android refers to by class name keep their old
fully qualified name (section 7.2), so a few files in `:platform` stay in `dev.rovsti.bridge` and in the apps
stay in `dev.rovsti.<app>`.

## 3. SDK levels and libraries

| Item | Value | Reason |
|---|---|---|
| minSdk | 34 | Same as every GPUI app (`apps/*/gpuiforge.json` `min_sdk`); `crates/rovsti_android/kotlin/dev/rovsti/bridge/MediaShare.kt:13` already assumes it. Gives `RenderEffect` (31) and AGSL `RuntimeShader` (33) on every device (`references/glass.md` 1). |
| targetSdk | 36 | Same as the GPUI apps today, so the runtime behaviour (foreground service types, exact alarms, full-screen intent rules) does not change during the rewrite. Bumping to 37 is story E8.8, after parity. |
| compileSdk | 37 | Required by the Compose BOM 2026.09 libraries ([toolchain.md](toolchain.md)). Convx also compiles against 37 (Convx `app/build.gradle.kts:32`). |
| Compose | BOM 2026.09.00, foundation text (`TextFieldState`), animation, ui-graphics | `libs.versions.toml` |
| Navigation | Navigation 3 (`androidx.navigation3` runtime + ui) | section 5.1 |
| Lifecycle | `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` (`collectAsStateWithLifecycle`) | section 5.2 |
| Serialization | `kotlinx-serialization-json` + its Gradle plugin | bridge models (section 6) and typed routes |
| Images | Coil 3 (`coil-compose`), one loader per app, crossfade off in rows | `references/performance.md` 4 |
| Immutable collections | `kotlinx-collections-immutable` for list parameters of composables | `references/performance.md` 2 |
| Tests | JUnit4, Robolectric 4.17 (`@GraphicsMode(NATIVE)`), Compose ui-test, Roborazzi 1.77.0, coroutines-test | [toolchain.md](toolchain.md) |
| Not used | Material3 components, Hilt/Dagger/Koin, MapLibre, Accompanist, Paparazzi | sections 5.3, 8.4, 10 |

Every version is pinned in `libs.versions.toml`. Before an implementer relies on a Compose API named here or in
the skill, they check it in the sources of the pinned artifact (the skill warns that overscroll, haptic and
shadow APIs moved in 1.8-1.9, `SKILL.md` section 1). E1.1 records the checked names in
`docs/kotlin-ui/api-notes.md`.

## 4. The design system (`:design`)

The skill is the specification; this section fixes the module structure and the public surface. Numbers and
recipes are in the reference files named in each row and are not repeated here.

### 4.1 Packages

| Package | Contents | Spec |
|---|---|---|
| `theme` | `RovstiTheme` (colors light/dark, follows the system theme like `FOLLOW_SYSTEM_THEME = true` today in `crates/rovsti_ui/src/lib.rs`), typography in `sp` on the system font (no SF Pro), shapes, `Spacing` tokens (gutter 16, gaps 4/8/12/16/24, text gap 2, section gap 24), contrast test for every label on every preset | `references/navigation-layout.md` 6, 11; `references/glass.md` 4 |
| `motion` | `RovstiMotion` spring and tween tokens with `visibilityThreshold`, the glisse spring solver (tests only), `Fling` projection (0.998), rubber band (0.55), reduce-motion gate `.gated()`, `MotionGate` freeze edges | `references/motion.md` 2-5; `references/performance.md` 5 |
| `haptics` | `RovstiHaptics` (Off / System / Strong), fired only on the events in the table | `references/navigation-layout.md` 8 |
| `glass` | Convx backdrop engine (`backdrop/`), `liquidGlass`, freeze, pool; fallbacks only as Convx does them (pre-31, reduce transparency, low RAM) | `references/glass.md` 2-7 |
| `press` | glass press node (ported from glisse `press.ts`), scale press, flat row press; all `Modifier.Node`, no `composed {}` | `references/motion.md` 7; `references/performance.md` 2 |
| `overlay` | `OverlayHost`, `OverlayController`, `OverlayEntry`, `placePanel()`, context menu (simple and preview-card variants), button-anchored menu (glisse morph), popover, sheet with detents and pushback, alert, action sheet, confirm, toast / undo pill | `references/overlays.md` 1-10 |
| `text` | `GlassTextField`, `SearchField`, `ComposerField` (multiline, grows to 6 lines, docks to the IME), selection colours, `onKeyboardAction` handling | `references/navigation-layout.md` 7 |
| `controls` | buttons (glass icon button 44 dp, capsule, text, destructive), switch, slider, segmented control (glisse geometry and gestures) | `references/navigation-layout.md` 5 |
| `chrome` | `RovstiTabBar` with puck (glisse), minimise on scroll, `ChromeMetrics`, `LocalContentInsets`, bottom progressive scrim, `LargeTitleScaffold` (title as item 0, inline bar fades by collapse fraction), toolbar capsules, back button | `references/navigation-layout.md` 2, 4, 10 |
| `list` | `ListRow` (one leading slot, title, one secondary line, trailing meta), `SectionHeader`, inset-grouped section, `ChipRow`, `Avatar` (photo or initials drawn with a cached text layout), `Badge`, `EmptyState`, swipe-to-reveal row, alphabet rail | `references/navigation-layout.md` 6; `references/motion.md` 5.3 |
| `media` | `PhotoViewer` (pinch and double-tap zoom, pan, swipe down to dismiss, tap hides chrome, paging "3 of 7"), used by all four apps | inventory: `apps/phone/src/photo_viewer.rs`, `apps/messages/src/media_viewer.rs`, `rovsti_core::zoom` (the maths is re-done in Kotlin; per-frame) |
| `transition` | shared-bounds helpers, push/pop/predictive-back specs for Navigation 3 | `references/motion.md` 8-9; `references/navigation-layout.md` 3 |

### 4.2 Layers of every screen

Content (list or map, full window) -> chrome (bars, floating buttons; siblings drawn after the content and never
inside the glass source) -> overlay host (`references/navigation-layout.md` 1). `RovstiRoot` in `:shell`
builds exactly this tree; an app never builds its own root.

### 4.3 Layout rules the convention tests enforce

A JVM test in `:shell` scans app sources and fails on: `import androidx.compose.material3`, `Popup(` or `Dialog(`
outside `:design/overlay`, `composed {`, a raw `.dp` literal in an app module outside a `Spacing`/`Sizes`
object, `FlowRow` in a chip row, and `animateScrollToItem` inside an alphabet rail. The skill's checklist B and G
(`references/checklist.md`) are the rest; they are checked by review and screenshots.

### 4.4 Insets

`ChromeMetrics` holds measured bar heights (written from `onSizeChanged`, changes only on resize);
`LocalContentInsets` = safe drawing + bar height + 8 dp; lists use it as `contentPadding`, never as outer padding;
the tab bar hides while the IME is up; `imePadding()` only on what docks to the keyboard
(`references/navigation-layout.md` 2). Test: scroll a 50-row list to the end and assert the last row's bottom is
above the bar's top.

### 4.5 Overlays

Every popover, menu, sheet, alert and toast is an `OverlayEntry` in the root `OverlayHost`, not a `Popup` or
`Dialog` window (`references/overlays.md` 1: one back owner, one z-order, exit animations always play, glass can
sample the same source, screenshots capture it). Anchor rects are captured once at present time into a plain
`Rect`. The scrim eats input. Modal entries clear the page from semantics and set `paneTitle`. The only allowed
exception is a `Popup` for an IME-heavy case, written down in the story that needs it.

### 4.6 Text input

`BasicTextField(state = TextFieldState)` everywhere; state lives in the ViewModel or `rememberTextFieldState`,
never recreated per keystroke; validation via `inputTransformation`, formatting via `outputTransformation`;
manifest `windowSoftInputMode="adjustResize"` and `enableEdgeToEdge()`. Phone's keypad number is a real field
(paste works) with the system keyboard suppressed. The GPUI field's pure edit model
(`rovsti_core::text_edit`) is not used by Kotlin; the platform owns caret, selection, undo and IME.

## 5. App structure

### 5.1 Navigation: Navigation 3

Decision: **Navigation 3** (`NavDisplay` over a back stack that the app owns as a `SnapshotStateList<Route>`).

Reasons:
1. The back stack is plain state we own. Per-tab stacks are one list per tab in a `TabNavigator` that is a pure
   Kotlin class with JVM tests; tab switching cannot diverge into two code paths with different restore rules
   (the trap Convx fell into: `A/AppNavigation.kt:7-17` vs `A/AppRootContent.kt:784-800`, cited in
   `references/navigation-layout.md` 3).
2. Predictive back and the overlay host cooperate: `BackHandler` of the overlay host is checked first, then the
   current tab's stack, then the activity.
3. Routes are `@Serializable sealed interface Route` per app, so deep links from notifications, shortcuts and
   handoff intents (`open_contact`, open thread, `WA_CHAT`, `answer_call_id`) become a pure `fun routeFor(intent
   extras): List<Route>`, JVM-tested.

`chromeFor(route): ChromeSpec` decides tab bar visibility, folded state and title per route (pure, tested).
Push/pop use tweens with depth parallax so predictive back can scrub; tab-to-tab is a ~200 ms crossfade
(`references/navigation-layout.md` 3). E1.18 checks the Navigation 3 API names at the pinned version
(`NavDisplay`, entry provider, transition specs, predictive pop spec); if Navigation 3 is not usable at the
pinned BOM, the fallback is navigation-compose 2.9 typed routes with a single `navigateToTab` helper, recorded in
`docs/kotlin-ui/api-notes.md`.

### 5.2 State: ViewModel + StateFlow, unidirectional

```
UI (composable) --Action--> ViewModel --suspend/Flow--> Repository (:platform / :core-bridge / :wa)
        ^                       |
        +---- StateFlow<UiState>+   one-shot Effects: navigation, toasts, intents (Channel -> Flow)
```

- One `UiState` per screen: an `@Immutable` data class built in the ViewModel on `Dispatchers.Default`; list
  fields are `ImmutableList`. Composables never map, filter or sort (`references/performance.md` 2-3).
- `fun onAction(action: XAction)`; actions are a sealed interface. Non-trivial state transitions are a pure
  `reduce(state, event): State` in the ViewModel's file and tested on the JVM; anything that is domain logic goes
  to Rust instead.
- Exposed as `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`; collected with
  `collectAsStateWithLifecycle` (`references/performance.md` 9).
- Per-frame values (drag offsets, sheet position, camera) never enter `UiState`; they live in `Animatable`s or
  holders read in draw/layout lambdas (`references/performance.md` 1).
- Undo pills, toasts and confirms are effects that the screen turns into `OverlayEntry`s.
- Repositories expose `Flow`s that re-query on provider change (`ContentObserver` -> conflated signal), like
  `crates/rovsti_android/kotlin/dev/rovsti/bridge/ProviderObservers.kt` does today for the GPUI apps.

### 5.3 Dependency injection: manual containers

Decision: **manual constructor injection**, one `AppContainer` per app created in `Application.onCreate`, plus
`viewModelFactory { initializer { ... } }` per screen. No Hilt, Dagger or Koin.

Reasons:
1. The graph is small (one bridge, a handful of repositories per app) and the same in every app; a
   `ShellContainer` base class in `:shell` holds the shared parts (bridge, haptics, image loader, WhatsApp).
2. Cold paths (SMS receiver, FCM service, `TripService`, `InCallService`, alarm receivers) must reach
   repositories without an activity; `(context.applicationContext as HasContainer).container` works everywhere,
   with no entry-point boilerplate.
3. No annotation processor: faster builds on the agent machine (disk and time are tight, [toolchain.md](toolchain.md)),
   simpler R8 rules, Robolectric tests construct fakes directly. Convx uses Hilt (Convx `app/build.gradle.kts:23,348-350`);
   it is one app with dozens of feature modules, which is not our shape.

Containers are lazy: nothing heavy runs before the first frame (`references/performance.md` 9).

### 5.4 Activities and processes

One `MainActivity` per app (`ComponentActivity`, `enableEdgeToEdge()`, `android:enableOnBackInvokedCallback="true"`,
`adjustResize`). Phone keeps a separate lock-screen-capable call surface (section 8.2). Stops keeps the native
`AlarmActivity` over the lock screen (AD-ST3). No second process.

## 6. The Rust bridge (`:core-bridge`)

### 6.1 Decision: hand-written JNI with a JSON envelope (not UniFFI)

| Option | For | Against | Verdict |
|---|---|---|---|
| UniFFI (proc macros, Kotlin bindings over JNA) | Typed Kotlin API generated; callback interfaces; async | ~90 `rovsti_core` modules (`crates/rovsti_core/src/lib.rs`) would need `uniffi::Record`/`Enum` derives or wrapper types: the pure crates would gain a binding dependency, or we would write a wrapper layer as large as a JSON layer. Adds JNA (an extra native library per ABI and reflection keep rules). Version not yet in the workspace or the offline registry. | Rejected |
| Hand-written JNI, typed arguments per function | Fast, no JSON | Hundreds of hand-written `extern "system"` functions with manual object conversion; unmaintainable | Rejected |
| **Hand-written JNI, few entry points, serde JSON envelope, typed Kotlin facades** | Every domain type is already `serde` (the existing seam is "strings in, JSON out": `crates/rovsti_android/src/headless.rs`, `rovsti_core::wire::Envelope`, `crates/rovsti_android/kotlin/dev/rovsti/bridge/WireJson.kt`); fixture contract tests already exist (`apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/WireFixturesTest.kt`); one dependency (`jni`, already in the workspace); panics and errors map in one place | Kotlin model classes are written by hand; JSON cost per call | **Chosen**. Model drift is caught by contract fixtures (6.5). JSON cost is irrelevant off the main thread at our sizes; the two hot paths (map tiles, call audio) get binary entry points (6.3). |

### 6.2 Rust side: two new crates

1. **`crates/rovsti_app_core`** (platform-free, has file I/O, no JNI, no GPUI). The app-level logic that today
   sits inside the GPUI binary crates moves here, with its unit tests, so both the old GPUI apps (until E8) and the
   bridge use one copy:
   - `storage`: atomic JSON stores in a data dir given at init (temp file + rename, size guards), the same file
     names as today (section 7.1). Replaces `rovsti_ui::storage::{save_json, load_json}` for non-GPUI callers.
   - `stops`: `store` (trip.json lock, legacy rename, AD-SX8), `pick` (AD-ST9 picking state and labels), `snap`,
     `headless` (`trip_fix`, `trip_alarm`, ... from `apps/stops/src/headless.rs`).
   - `messages`: `prefs` (`messages-prefs.json`), drafts, `headless` (`sms_classify`, `mms_*`, `wa_digest`,
     `wa_reply` from `apps/messages/src/headless.rs`), access decisions (`apps/messages/src/access.rs`).
   - `contacts`: trash store (`apps/contacts/src/trash_store.rs`), prefs, WhatsApp target and lookup glue
     (`apps/contacts/src/whatsapp.rs`).
   - `phone`: prefs (`PrefsV2`), notes, WhatsApp call tracker and history (`apps/phone/src/wa_call.rs` minus
     drawing), backup.
   - `wa_link`: `whatsapp-link.json` read/write and the headless send (`rovsti_wa_ui::headless` minus GPUI).
   Rule: a function moves only with its tests; the GPUI crate then calls the moved function, so behaviour is
   proven identical by the tests that already exist.
2. **`crates/rovsti_ffi`** (`crate-type = ["cdylib", "rlib"]`, the only crate with `#[no_mangle] extern "system"`
   JNI exports for the Kotlin apps). Thin: decode request, call `rovsti_core` / `rovsti_geo` / `rovsti_tiles` /
   `rovsti_wa_client` / `rovsti_app_core`, encode answer. It compiles on desktop too (the `jni` crate works on a
   host JVM), so Robolectric tests load a host build of the same library. It never calls Kotlin except through
   the event sink (6.4).

`crates/rovsti_android` (Rust) keeps serving the GPUI apps until E8; the Kotlin apps do not link it. Its Kotlin
files are ported into `:platform` (section 7.3). `CLAUDE.md`'s rule "Android-only code goes through
`rovsti_android`" is amended in E8 to: Android-only Kotlin lives in `:platform`; the only JNI crate is
`rovsti_ffi`, and it builds on desktop.

### 6.3 Entry points

```
Kotlin (dev.rovsti.core.RustNative)                          Rust (rovsti_ffi)
  external fun init(dataDir, cacheDir, appId, sdk): ByteArray    open stores, logger, panic hook; returns version
  external fun call(op: String, request: ByteArray): ByteArray   JSON in -> JSON envelope out (all domain APIs)
  external fun headless(kind: String, json: String): String      same contract as today's nativeHeadless (AD-MSG4)
  external fun tile(handle: Long, z: Int, x: Int, y: Int, flags: Int): ByteArray   binary geometry (8.4)
  external fun audioPush(call: Long, pcm: ByteArray)             960 i16 samples, 60 ms (AD-WA5), no base64
  external fun audioPull(call: Long, out: ByteArray): Int
  external fun setEventSink(sink: RustEvents)                    6.4
  external fun release(handle: Long)
```

- `op` names are `"<domain>.<verb>"` (`"recents.group"`, `"contacts.validate_draft"`, `"inbox.merge"`,
  `"stops.pick.tap"`, `"wa.send_text"`). One Rust `match` per domain module in `rovsti_ffi::ops::*`.
- Stateful Rust objects (a `ContactIndex` for search-as-you-type, a WhatsApp `Gateway` and `EventStream`, a
  `TileClient` + `DiskCache`, an `ActiveTrip` while the UI shows it) live in a handle registry
  (`Mutex<Slab<Box<dyn Any + Send>>>`); Kotlin holds a `Long` in an `AutoCloseable` wrapper tied to a ViewModel or
  container scope and calls `release`.
- `headless` keeps the exact `kind`/JSON contract of today, so the cold paths (SMS receiver, FCM, `TripService`)
  port unchanged; only the library name changes from the app's GPUI binary to `librovsti_ffi.so`.

### 6.4 Threading

- Every JNI call is blocking and runs on `RustDispatcher = Dispatchers.IO.limitedParallelism(4)`; facades are
  `suspend fun`s or `Flow`s. A debug-only `assertNotMain()` sits in `RustNative` wrappers, and StrictMode is on in
  debug (`references/performance.md` 9).
- Rust spawns no thread for plain calls. The WhatsApp event stream keeps its own thread inside
  `rovsti_wa_client::events` as today (`docs/architecture.md` "Async"); it delivers events through the sink.
- Event sink: Kotlin registers one `RustEvents` object (global ref); Rust attaches its thread as a daemon and
  calls `onEvent(kind: String, json: ByteArray)`. Kotlin `tryEmit`s into a `MutableSharedFlow` with a large buffer;
  on overflow it emits a synthetic `resync` (WhatsApp already recovers from `Resync`, AD-WA4).
- Call audio uses two dedicated threads (record and play, `WaCallAudio` port), never the dispatcher.
- Results are mapped to `@Immutable` UI models on `Dispatchers.Default`.

### 6.5 Models, contracts and errors

- Kotlin mirrors live in `dev.rovsti.core.model.<domain>` as `@Serializable` data classes with the same field
  names as the serde types (snake_case via `@SerialName` / a naming strategy). They are immutable `val` classes
  and listed in `compose_stability.conf`.
- Contract fixtures: a Rust test in `rovsti_ffi` writes one request and one answer JSON per op into
  `crates/rovsti_ffi/fixtures/` (committed; the test fails if the files differ, `UPDATE_FIXTURES=1` rewrites
  them). A Kotlin JVM test decodes each fixture into its model and re-encodes it; unknown keys fail the test.
  This is the pattern of today's `WireFixturesTest.kt`.
- Envelope: `{"ok": <value>}` or `{"err": {"kind": "invalid|not_found|io|parse|network|auth|unsupported|panic",
  "message": "...", "detail": {...}}}`. Every export runs inside `catch_unwind`; a panic becomes `kind = panic`
  and never crashes the process. Kotlin maps it to `sealed class RustError : Exception` and facades return
  `Result<T>`-like `Outcome<T>`; user-facing sentences (for example `DiameterError::message`) come from Rust in
  `message`.
- Init: `Application.onCreate` loads the library and calls `init(noBackupFilesDir, cacheDir, appId, SDK_INT)`
  off the main thread; the first frame does not wait for it, screens show their skeleton until the container's
  `ready` flow completes.

### 6.6 Which Rust APIs are exposed

| Domain (op prefix) | Crate / module | Used by |
|---|---|---|
| `phone_number`, `format`, `calling_code` | `rovsti_core::{phone_number, number_format, calling_code, copy_choices}` | all |
| `search`, `t9` | `rovsti_core::{search, t9, contact_find}` (handle: `ContactIndex`) | Phone, Contacts, Messages |
| `recents`, `call_log`, `call_notes`, `call_stats`, `speed_dial`, `blocklist`, `dial`, `sim`, `setup`, `audio`, `scene` (call states only) | `rovsti_core::*`, `rovsti_app_core::phone` | Phone |
| `contacts.*` (draft, card, dates, dupes, cleanup, csv, ics, vcard, import_plan, intake, scan, photo plan, trash, quick, frequent, recent, history, stats, me, group tools, prefs, view) | `rovsti_core::contact_*`, `vcard`, `import_plan`, `group_tools`, `rovsti_app_core::contacts` | Contacts (some in Phone, Messages) |
| `inbox`, `sms`, `sms_timeline`, `mms_pdu`, `otp`, `spam`, `schedule`, `notify`, `msg_backup`, `text_detect`, `date_detect`, `location_link`, `chat_find`, `chat_gallery`, `chat_export`, `global_search`, `forward`, `message_info`, `message_reminder`, `starred`, `thread_marks`, `mute`, `quick_replies`, `unread_jump`, `date_jump`, `voice_player` (speeds), `shared_card` | `rovsti_core::*`, `rovsti_app_core::messages` | Messages |
| `stops.*` (session, crossing, dest parsing, pick, snap, saved, history, stats, prefs, notice, alarm plan, poll, gtfs import, coord formats, measure, backup, csv/gpx) | `rovsti_geo::*`, `rovsti_app_core::stops` | Stops |
| `tiles.*` + binary `tile()` | `rovsti_tiles`, `rovsti_geo::tiles` | Stops |
| `wa.*` (link, relink, keys list/mint/revoke, chats, messages, send text/media, react, mark read, chat state, groups, presence, lookup, statuses, scheduled, calls place/accept/reject/end/mute, snapshot plan, arbitration, push wake parse) | `rovsti_wa_client::Gateway`, `rovsti_wa_proto::*`, `rovsti_app_core::wa_link` | all four (link), Phone (calls), Messages (chats), Contacts (lookup), Stops (notice) |
| `qr.encode`, `qr.decode` | `qrcode` and `rqrr` as used by `rovsti_core` (`contact_qr`/`location_link` style helpers) and `rovsti_wa_ui::qr` | Contacts (share QR, scan from image), Phone (number QR), Stops (point QR), `:wa` (link QR, connect-key QR). Encode returns the module matrix (`BooleanArray` + side), Kotlin draws it; decode takes a luma plane (`ByteArray`, w, h) from a `Bitmap`. No ZXing / ML Kit dependency. |
| `wa.avatars`, `wa.names` | `rovsti_wa_ui::{avatar_book, names}` logic moved to `rovsti_app_core::wa_link` (E2.4): which profile picture to fetch when, 429 back-off, on-disk naming and pruning; "contact name, then WhatsApp name, then number" | Messages, Phone, Contacts |
| `headless(kind, json)` | `rovsti_app_core::{messages,stops}::headless` | cold paths |

Not exposed (the Kotlin UI does not need them; the platform does the job): `text_edit`, `text_touch`,
`text_view`, `keypad_layout`, `gesture`, `bubble_gesture`, `zoom`, `perf`, `small_text`, `text_scale`. They stay
for the GPUI apps until E8 and are then deleted or kept only if another crate uses them.

### 6.7 Building the library in Gradle

- `cargo-ndk` is installed by `scripts/android-setup.sh` (`cargo install cargo-ndk --locked`; it is not on the
  machine today). `:core-bridge` registers `cargoBuildAndroid` (`Exec`):
  `cargo ndk --platform 34 -t arm64-v8a [-t x86_64] -o <buildDir>/rustJniLibs build -p rovsti_ffi --profile <p>`,
  with `--release` for release variants and the workspace `debug` profile otherwise. Output is added with
  `android.sourceSets["main"].jniLibs.srcDir(...)`; `preBuild` depends on it. Inputs are the `crates/**` sources
  and `Cargo.lock`, so Gradle skips it when nothing changed.
- `cargoBuildHost` builds `rovsti_ffi` for the host and every `testDebugUnitTest` task gets
  `systemProperty("java.library.path", ...)`; `RustNative` loads it with `System.loadLibrary("rovsti_ffi")`.
  Tests that should not need Rust use fakes of the facade interfaces.
- **Classloader trap.** A JVM allows a native library to be bound by one classloader only, and Robolectric builds a
  new instrumenting classloader per SDK/config sandbox, so `RustNative` loaded from two Robolectric configurations
  (or from a Robolectric and a plain test in one JVM) fails with `UnsatisfiedLinkError: already loaded in another
  classloader`. Rule: tests that really call Rust are **plain JUnit tests without Robolectric** (`RustNative` and
  the facades take no `Context`; the `Context`-dependent pieces are in `:platform`), run in their own Gradle test
  task `testRustBridge` with `forkEvery = 0` and one JVM. Robolectric and Compose tests use fakes of the facade
  interfaces, never the real library. E2.6 proves this split.
- The Android `.so` must not need the NDK sysroot at link time beyond what `scripts/android-env.sh` already
  configures (TLS is rustls + graviola, no OpenSSL or `ring`: `rovsti_wa_client`, `rovsti_tiles` Cargo.toml). A new
  dependency of `rovsti_ffi` that needs a C toolchain, OpenSSL or a system library is refused in review.
- ABIs: `arm64-v8a` for release (Phone already ships arm64 only, `apps/phone/gpuiforge.json`), `x86_64` added for
  debug and emulator builds. One `.so` per ABI shared by all four APKs (each APK packages its own copy).
- Strip and LTO in the release profile; the symbol list is checked by a test that the 8 exports exist
  (`nm -D` in the check script).
- `scripts/kotlin-ui-check.sh` adds `cargo test -p rovsti_app_core -p rovsti_ffi` to its Rust step.

## 7. Platform layer, components and data migration

### 7.1 Each app keeps its data

The new APKs are **updates of the installed GPUI apps**: same `applicationId`, same release keystore
(`apps/*/gpuiforge.json` `signing`: `rovsti-release.jks`, alias `rovsti`, passwords from `ANDROID_STORE_PASSWORD` /
`ANDROID_KEY_PASSWORD`), higher `versionCode` (GPUI builds are `versionCode 1`, `apps/*/platforms/android/app/build.gradle.kts`;
the Kotlin apps start at 100 and use `100 + build number`). An update keeps the app's private storage, its
default-app role and its granted permissions. Debug builds are signed with the debug key and therefore cannot
upgrade a release install; acceptance on the owner's phone uses release builds.

| What | Today (GPUI) | Kotlin apps | Action |
|---|---|---|---|
| Data dir | `noBackupFilesDir` (`rovsti_ui::storage::data_dir`; `Headless.dataDir`, `crates/rovsti_android/kotlin/dev/rovsti/bridge/Headless.kt:50`) | Same: `init(noBackupFilesDir, ...)` | none; Rust reads the same files |
| Phone files | `prefs.json` (`PrefsV2`), `wa-calls.json`, `wa-calls-forgotten.json`, `sim-choices.json`, notes, `whatsapp-link.json` | same names, same schemas, same Rust code | none |
| Messages files | `messages-prefs.json`, `drafts.json`, `scheduled.json`, starred/marks files, `whatsapp-link.json` | same | none |
| Contacts files | `contacts-prefs.json`, `contacts-trash.json`, `whatsapp-link.json` | same | none |
| Stops files | `stops-prefs.json`, `trip.json` (+ `trip.legacy.json`), `saved.json` (v2), `places.json`, `history.json`, `gtfs.json`, `next-trip.json`, `tiles/<server key>/` | same | none |
| Kotlin `SharedPreferences` | `rovsti_bridge`, `rovsti_scheduled_sms`, `rovsti_birthday_reminders`, `rovsti_alarm`, `rovsti_call_reminders`, `rovsti_message_reminders` (`crates/rovsti_android/kotlin/dev/rovsti/bridge/*.kt`) | same names and keys in the ported classes | keep names; a JVM test lists them |
| Notification channels | ids created by the bridge (`trip_alarm_v2`, `trip_problem`, message, call channels) | same ids, same importance | keep ids (changing one would reset the user's channel settings) |
| Pending alarms, pinned shortcuts, launcher | `PendingIntent`s and shortcuts name component classes | same fully qualified class names | 7.2 |
| FCM token | registered per instance (`WaPush`) | same flavor `firebase`, same service class | token stays valid across the update |
| UI-only prefs (appearance, haptics, text size) | inside the Rust prefs files where they exist | same; new UI prefs are added to those files through Rust (schema bump with defaults) | no Kotlin DataStore |

A Robolectric migration test per app copies a fixture data dir (captured from the GPUI app's own test fixtures)
into `noBackupFilesDir`, starts the container and asserts the first screen's `UiState` (E8.4).

### 7.2 Manifest parity

Each app's manifest is rebuilt from the GPUI app's ejected manifest (`apps/<app>/platforms/android/app/src/main/AndroidManifest.xml`
and `src/firebase/AndroidManifest.xml`) and `gpuiforge.json` (permissions, URL schemes, share MIME types). Rules:
- Every component keeps its fully qualified class name: `dev.rovsti.<app>.MainActivity`, `dev.rovsti.phone.RovstiInCallService`,
  `dev.rovsti.phone.CallActionReceiver`, `dev.rovsti.messages.SmsDeliverReceiver`, `MmsWapPushReceiver`,
  `HeadlessSmsSendService`, `ComposeRequest`, `BootReceiver`, `dev.rovsti.bridge.CallReminderReceiver`,
  `TripService`, `AlarmActivity`, `ReplyReceiver`, the FCM `RovstiMessagingService`, providers and their
  authorities (`RovstiShareProvider`). Alarms set by the GPUI build, pinned shortcuts and the role grants keep
  working after the update.
- The dialer and SMS roles need all their components declared (`docs/architecture.md` "What each app needs from
  Android"); a manifest test compares the component and permission sets with a committed list per app. The test
  parses the merged manifest the build already produces (`build/intermediates/merged_manifests/<variant>/**/AndroidManifest.xml`,
  a Gradle test input wired from `processDebugMainManifest`) with a plain XML parser; no `aapt` binary is needed.
  It checks `exported`, intent filters (including `android:permission` on handoff-protected components), `uses-feature`
  (`android.hardware.telephony` is required in Messages), `<queries>` and the `allowBackup`/`dataExtractionRules`
  attributes against the GPUI manifest, so a library manifest cannot silently add one.
- The signature permission `dev.rovsti.permission.HANDOFF` is declared today by Contacts, Messages and Phone
  (`apps/*/platforms/android/app/src/main/AndroidManifest.xml`); the Kotlin apps keep exactly those declarations and
  `uses-permission` lines. It works only between APKs with the same signer, so cross-app handoff (AD-CT5) is
  verifiable only between release-signed builds; the acceptance checklist says so (E8.7).
- The GPUI-only host classes (`dev.gpui.android.*`, `GpuiFileProvider`) are dropped; nothing outside the app
  refers to them.

### 7.3 `:platform`

Ported from `crates/rovsti_android/kotlin/dev/rovsti/bridge/*.kt` (49 files, about 12 k lines) and the app hosts
(`apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/*.kt`, `apps/messages/.../messages/*.kt`,
`apps/stops/.../stops/*.kt`). The port removes the Rust-calls-Kotlin direction (`RovstiBridge` static methods
that return JSON strings for Rust): Kotlin repositories call the provider code directly and return Kotlin models;
when a domain decision is needed they pass the provider rows (the existing wire JSON, `WireJson.kt`) to Rust.
Their existing JVM tests (`apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/*Test.kt`,
`.../phone/*Test.kt`, `apps/messages/platforms/android/app/src/test/...`) move with them.

| Package | From | Contents |
|---|---|---|
| `contacts` | `ContactsQuery`, `ContactsEdit`, `ContactPhotos`, `ContactOptions`, `Shortcuts` | read (all, with numbers, favorites, full, detail), write (save, delete, star, groups, members, accounts, photo, ringtone, voicemail), import; observer flow |
| `calllog` | `CallLogQuery`, `CallLogWrite`, `MissedCalls`, `Blocklist`, `RejectPolicy` | call log read/delete/restore, blocked calls, blocklist |
| `telephony` | `SimAccounts`, `SimProbe`, `CallsApi`, `KeypadTone`, `VoicePlayer` (voicemail), phone host `CallRegistry`, `AudioPath`, `ProximityController`, `CallNotifications`, `IncomingCall`, `MultiCall`, `FlipDetector`, `CallEventRing` | SIM list, place call, InCallService core (the service class itself is declared in `:phone`), audio routes, proximity, call notifications |
| `sms` | `SmsQuery`, `SmsSend`, `MmsCodec`, `ScheduledSms`, messages host `MmsInbox`, `IncomingNotice` | provider read/write, send (multipart, dual SIM, delivery), MMS, scheduled alarms |
| `notify` | `MessageNotifications`, `NotificationIcon`, `MessageReminders`, `CallReminders`, `BirthdayReminders`, `ReplyReceiver` | channels, MessagingStyle, inline reply, reminders |
| `location` | `StopsLocation`, `TripService`, `TripAlarm`, `AlarmSound`, `AlarmOptions`, `AlarmActivity` | last known + fresh fix, trip foreground service, alarm (AD-SA1 to AD-SA9 unchanged) |
| `files` | `Documents`, `MediaShare`, `RovstiShareProvider`, clipboard, Photo Picker, camera capture (AD-CT4a) | SAF import/export, save to gallery, share text/file/image, clipboard read/write |
| `system` | `Handoff`, `SystemPages`, `XiaomiOps`, `SettingsMirror`, `Haptics`, `Mailbox`, `Headless`, roles, permissions | handoff intents (AD-CT5), system settings pages, OEM battery, role requests, permission groups as a Compose-friendly `PermissionController` |
| `wa` (seams only) | `WaPush`, `WaIncoming`, `WaCallAudio` | used by `:wa` |

Removed: `SpikeProbes.kt`, `Epic17Probes.kt` (GPUI spikes), `Mailbox` one-shots become intent handling in the
activity, `SettingsMirror` stays only for what Android must read without the app.

### 7.4 `:wa`

`WaRepository` per app container: `linkState: StateFlow<WaLinkState>` (none, linking with QR or pairing code,
connected, logged out, error), `events: Flow<GatewayEvent>` (from the sink, filtered by instance), `wake()` on FCM
(`reconnect_now`, AD-WA9), chats and messages as `Flow`s that refetch on `Resync`. `WaCalls` (Phone) wraps place,
accept, reject, end, mute and the audio pump (`audioPush`/`audioPull` on the two audio threads). The shared
`WhatsAppSettingsScreen` (Compose) has exactly the two options of today (link for the first time / connect with a
key), Show connect key (mints a fresh one), key list with revoke, Link again and Delete instance (AD-WA2, AD-WA8).
Gateway URL rules come from `rovsti_wa_proto::url` through the bridge (AD-WA3). `WaAvatars` is the Kotlin half of
`rovsti_wa_ui::avatars`: Rust decides what to fetch and when (`wa.avatars`, 429 back-off, pruning), Kotlin does the
download and decode off the main thread and hands bitmaps to the Coil loader; it is part of `:wa` because Messages,
Phone and Contacts all draw WhatsApp people.

### 7.5 Roles and permissions

Source of truth: `apps/*/gpuiforge.json` `permissions`, the ejected manifests, `docs/architecture.md` "What each
app needs from Android". `:shell`'s `PermissionController` knows this table; the manifest test (7.2) pins it.

| App | Roles | Runtime permissions (asked on first use, never at launch) | Special access (Settings page, can be revoked at any time) | Install-time |
|---|---|---|---|---|
| Phone | `ROLE_DIALER` (`RoleManager.createRequestRoleIntent`; the role carries the phone and call-log grants the app asks for, the setup sheet re-checks `isRoleHeld` on every resume) | `CALL_PHONE`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT` (route picker; a denied grant hides Bluetooth endpoints, not the screen), `RECORD_AUDIO` (WhatsApp calls only) | full-screen intent (`NotificationManager.canUseFullScreenIntent`, revocable since API 34, deep link `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`), ignore battery optimisation, Xiaomi background start (E5.12) | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`, `VIBRATE`, `WAKE_LOCK`, `INTERNET`, HANDOFF |
| Messages | `ROLE_SMS` (grants SMS/phone permissions; if the role is lost the UI falls back to the "make default" row and read-only provider access, `access.rs` rules) | `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, `READ_CONTACTS`, `READ_PHONE_STATE` (dual SIM), `POST_NOTIFICATIONS` | exact alarms (`SCHEDULE_EXACT_ALARM`; `AlarmManager.canScheduleExactAlarms()`, revocable; scheduled SMS falls back to an inexact window with a visible note, AD-MSG6) | `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`, `INTERNET`, HANDOFF |
| Contacts | none | `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_CALL_LOG` (recent calls on the card, optional), `POST_NOTIFICATIONS` (birthday reminders, asked when the first reminder is switched on, AD-CT7), camera capture goes through the system camera app (`ACTION_IMAGE_CAPTURE`, no `CAMERA` permission) | exact alarms are not used (birthday reminders are inexact) | `INTERNET` (WhatsApp lookup), HANDOFF |
| Stops | none | `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (one dialog, precise requested), then `ACCESS_BACKGROUND_LOCATION` as a **separate** second step with its own rationale (AD-SA7: recommended, not required), `POST_NOTIFICATIONS` | `USE_EXACT_ALARM` is install-time for alarm-clock apps, so no Settings page; full-screen intent (as Phone); ignore battery optimisation | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `VIBRATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`, `INTERNET` |

Rules the code follows:
- Foreground service type `location` can be started only while a location grant exists and (API 34+) from a
  visible activity or an exempt context; `TripService` start is therefore always initiated from the UI or the boot
  receiver path that exists today, and the failure (`ForegroundServiceStartNotAllowedException`) maps to the
  "trip could not start" row, never a crash. Same for `phoneCall` in Phone.
- Every permission has a pure `PermissionState` decision (never asked / granted / denied once / denied for good /
  revoked while running) from `rovsti_app_core::messages::access`-style Rust rules or a Kotlin twin with JVM tests;
  the UI shows a row with the next action (ask, open Settings page), not a dead end. Revocation is observed on
  `ON_RESUME`, and a process that is killed by a revocation restarts into the same route (`routeFor`).
- A missing permission degrades one feature and never blocks the app: no call-log permission means Recents shows
  the setup row, no contacts permission means names stay numbers.
- OEM: Xiaomi/MIUI background and lock-screen-display pages are opened by `XiaomiOps` with a fallback to the generic
  app-details page; any `ActivityNotFoundException` is caught.

## 8. Per app

### 8.1 Contacts (`dev.rovsti.contacts`)

Tabs: Contacts, Favorites, Groups, Settings (`apps/contacts/src/main.rs` `TABS`). No role. Intake of shared and
opened vCards (AD-CT10), `EXTRA_CONTACT_ID` one-shot (AD-CT8) becomes a route. Features: epic E4.

### 8.2 Phone (`dev.rovsti.phone`, `ROLE_DIALER`)

Tabs: Favorites, Recents, Contacts, Keypad (`apps/phone/src/main.rs` `TABS`). Incoming calls keep the order of
today: `RovstiInCallService` -> `CallRegistry` posts the `CallStyle` notification first, so a call is answerable
before any Compose frame (`docs/architecture.md` "Phone: incoming calls"); `MainActivity` shows the Compose
Incoming and In-Call screens with `setShowWhenLocked`/`setTurnScreenOn` while a call is live; the first-frame
report (`IncomingPerf.kt`) stays. `NativeIncoming.kt` (the native fallback curtain) is kept as the error view.
WhatsApp calls render on the same screens through `CallSource.WhatsApp` with the arbitration of
`rovsti_wa_proto::call::arbitrate`. Features: epic E5.

### 8.3 Messages (`dev.rovsti.messages`, `ROLE_SMS`)

Tabs: Chats, WhatsApp, Settings (`apps/messages/src/main.rs` `TABS`). Cold paths stay Kotlin receivers calling
`headless(kind, json)` (AD-MSG4, AD-MSG5); while the UI is attached the app posts notifications itself
(`uiAttached`). The composer is `ComposerField` docked with `imePadding()`; the list keeps the last bubble above
the composer with `reverseLayout`. Features: epic E6.

### 8.4 Stops (`dev.rovsti.stops`)

Home is a **full-window map** (edge to edge under the status and navigation bars). Floating on top: search/pick
pill (top), chip row (one line), button column (locate/follow, layers, map options) that rides the sheet with a
`graphicsLayer` translation, the live trip card, the attribution "(c) OpenStreetMap contributors", the scale bar,
and the tab bar folded to a circle. Saved stops, History and Settings are sheet or pushed pages over the map, never
a page with a map box. Sheet detents: peek ~120 dp / medium / large with background interaction at peek and
medium (`references/navigation-layout.md` 9; `references/overlays.md` 5). Map camera padding = the glass-covered
area at the sheet's settled detent, updated on settle, not per frame.

**Map renderer decision: our own tile renderer over `rovsti_tiles` + `rovsti_geo::tiles`, drawn by Compose.**

| Option | For | Against |
|---|---|---|
| MapLibre Android with the VersaTiles style | Labels, GPU rendering, mature gestures | GL surface: our glass cannot sample it (forces the Flat tier everywhere over the map, `references/glass.md` 6, `references/navigation-layout.md` 9); does not render under Robolectric, so no screenshot proof is possible on this machine; has its own tile cache that bypasses `rovsti_tiles` (128 MiB LRU per server dir, usage per server, Clear, offline area download S10, AD-ST4) and the tested Shortbread styles (colorful / eclipse); fetches glyphs and sprites too; large native library per ABI; pin grab/drag rules (AD-ST9: `PIN_GRAB_PX`, second finger cancels into pinch, map tap never moves the view) must be rebuilt on its gesture system anyway |
| **Own renderer** | Uses the existing, tested pipeline (`rovsti_geo::tiles::{mvt, shortbread, style, geometry, clip, slippy, queue, lru}`, `rovsti_tiles::{TileClient, DiskCache}`); one network rule (only the chosen tile server, `CLAUDE.md`); Compose draw content, so glass can sample it (Frost while panning, Liquid at rest); runs under Robolectric with a fake tile source, so every Stops screen has screenshots | No text labels (the GPUI map has none either, AD-ST4); we own performance |

Pipeline:
1. `MapCamera` (Kotlin, pure: centre, zoom, slippy maths, fling/zoom springs) is a holder read in draw; a pinch or
   pan recomposes nothing (`references/performance.md` 4). Cross-checked against `rovsti_geo::view` with fixtures.
2. On camera settle (and debounced while moving) Kotlin computes the visible tile set and asks
   `tile(handle, z, x, y)` on `RustDispatcher`; Rust reads the disk cache or fetches from the user's server,
   decodes MVT, styles it for the theme and returns a packed little-endian buffer (per class: paint id, then
   polygons and polylines as `f32` tile-local coordinates, already clipped and simplified). Requests for tiles that
   left the viewport are cancelled.
3. Kotlin rasterises each tile once into a `Bitmap` (512 px, `Dispatchers.Default`, `android.graphics.Canvas`,
   paints built once per style), keeps a bitmap LRU sized from `ActivityManager.memoryClass` (about 1/8), and draws
   visible bitmaps in one `drawWithCache`/`Canvas` node with the camera transform; a missing tile draws its scaled
   parent (`rovsti_geo::tiles::stand_in`) or the radar background. Re-rasterise at the new zoom when it settles.
4. Radar layers (saved stops, A and B pins, circle with diameter line and halves, position and accuracy, breadcrumb,
   GTFS stops and line, measure polyline) draw in a second node over the tiles from coarse state plus the camera.
5. Prefetch around the trip circle on start (AD-ST4); offline area download (S10) is a Rust job with progress events.

Fallback: if E7.13's device check shows the renderer misses the frame budget, MapLibre is re-evaluated as a
separate decision; the floating-chrome layout does not change.

## 9. Visual verification (no device)

- Roborazzi on Robolectric (`@GraphicsMode(NATIVE)`, `@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")`),
  as proven by the toolchain ([toolchain.md](toolchain.md)). `build-logic` sets the Roborazzi output to
  `<module>/screenshots/` so goldens are committed and `verifyRoborazziDebug` compares against them.
- `:design` provides `ShotTest` helpers: `shot(name) { content }` renders light and dark; `variants = FontScale2,
  Rtl, Landscape` add more; glass is forced to the **Flat** tier in tests (`glassTier(forTests = true)`), plus one
  probe test (E1.5) that records whether Robolectric executes `RuntimeShader`; if it does, Liquid-tier shots are
  added for the glass components, over a striped fake backdrop.
- **Determinism.** Every shot test pins locale `en-US`, time zone UTC, a fixed `Clock` injected through the
  container (never `System.currentTimeMillis()` in a composable or a ViewModel; a convention test greps for it),
  a fixed `Random` seed for avatar colours, no network (Coil with a fake fetcher, a fake tile source) and
  `mainClock.autoAdvance = false`. Roborazzi `changeThreshold` is 0.5 % so anti-aliasing noise does not fail
  `verifyRoborazziDebug`; goldens are produced and verified on the same toolchain image only (system fonts differ
  by machine), and the contact sheets are the human-readable view.
- **Accessibility in tests.** Compose UI tests call `enableAccessibilityChecks()` (touch target >= 48 dp,
  contrast, labels) for every screen test; failures are fixed or suppressed with a written reason in the story.
- **Adaptive layout.** Phone-portrait is the design target. Landscape, 600 dp+ widths and split-screen must not break:
  content is centred with a 640 dp maximum width except the Stops map layer, the tab bar and sheets cap at 560 dp;
  one `-landscape` shot per app (Stops already has one) and one `w840dp` shot per app.
- Animations are captured at fixed frames with `mainClock.autoAdvance = false` and `advanceTimeBy` (menu at closed,
  +40 ms, +100 ms, settled; sheet at each detent) (`references/overlays.md` 9).
- Required shots per screen (`references/checklist.md` H): light/dark, font scale 2.0, RTL for one screen per app,
  empty/short/long list, end of list with the tab bar, every overlay open (above and below its anchor), keyboard open
  where a field docks (the IME is simulated by `WindowInsets` in the test), Stops with a fake tile source.
- How an agent verifies: run `RECORD=1 scripts/kotlin-ui-check.sh` (or `./gradlew :<module>:recordRoborazziDebug`),
  **open every new or changed PNG with the Read tool**, judge spacing, overlap, covered rows, clipped puck, rim and
  contrast, and write what was seen in the story's notes (epics file, "Evidence" line). A story is not done until
  its listed PNGs exist, were opened, and `verifyRoborazziDebug` passes.
- Contact sheets per app go to `docs/kotlin-ui/screenshots/<app>.png` at the end of each app epic (a small JVM tool in
  `build-logic` tiles the goldens). PNG budget: keep goldens at 1x xxhdpi, under ~150 KB each; prune superseded ones.
- Disk: keep 6 GB free (`df -h .`); clean `target`, `android/**/build` and unused Gradle caches before big builds.

## 10. Performance rules (from the skill, binding for every story)

1. No per-frame value reaches composition; read it in `graphicsLayer {}`, draw or layout lambdas; pass `() -> T`
   (`references/performance.md` 1).
2. Lazy items have `key` and `contentType`; row models immutable; no allocation in item lambdas; list params are
   `ImmutableList` (`performance.md` 2-3).
3. Images and tiles sized from the slot in px and remembered; crossfade off in rows; Coil memory cache ~25 %
   (15 % on low-RAM) (`performance.md` 4).
4. Overlays live in the root host and cannot move content (`overlays.md` 1).
5. Ambient animation (call aura, connecting pulse, skeleton shimmer) only when visible, resumed, not frozen,
   animator scale > 0 and reduce motion off; at most 30 fps for ambient visuals (`performance.md` 6).
6. Glass only on chrome, with a tier, a capture scale tied to blur and a Flat fallback; frozen while content moves
   (`glass.md` 5-6; `performance.md` 5).
7. No main-thread JNI, no `runBlocking` on Main, no logging or `String.format` in hot paths (`performance.md` 9).
8. Custom modifiers are `Modifier.Node`; no `composed {}` (`performance.md` 2).
9. Each screen's hottest interaction has a recomposition-count test (<= 2 per composable) (`performance.md` 10).
10. Highest refresh-rate display mode with the same resolution; static screens may request a low frame-rate
    category where the API exists at compileSdk 37 (`performance.md` 8).
11. Release: R8 + resource shrinking with minimal keep rules (JNI owners `dev.rovsti.core.RustNative` and
    `RustEvents`, serialization, manifest classes); hand-written baseline profile rules for first screen, rows,
    overlay host and map node, `androidx.profileinstaller`; Macrobenchmark when a device exists
    (`performance.md` 9-10).

## 11. Check before a story is done

```
scripts/kotlin-ui-check.sh          # cargo fmt, Rust tests incl. rovsti_app_core + rovsti_ffi, Android .so build,
                                    # spotlessCheck lintDebug testDebugUnitTest assembleDebug verifyRoborazziDebug
```

While a story touches Rust crates that the GPUI apps use, the `CLAUDE.md` checks also run
(`cargo clippy --workspace --all-targets && cargo test --workspace`) until E8 removes the GPUI apps from the
default build.

## 12. Open risks

| Risk | Mitigation | Story |
|---|---|---|
| Robolectric does not execute AGSL, so Liquid glass is unverified without a device | Flat tier in shots; CPU twins of lens and rim tested on JVM; probe test; owner device checklist | E1.5, E8.7 |
| Navigation 3 API differs at the pinned BOM | Verify first; documented fallback | E1.18 |
| Own map renderer too slow on the phone | Bitmap tiles, parent stand-ins, settle-only rasterising; device perf check; MapLibre re-evaluation | E7.2b, E7.13 |
| Moving app logic out of GPUI crates breaks the GPUI apps before E8 | Move with tests; GPUI crates call the moved code; workspace tests stay green | E2.1-E2.4 |
| A native library cannot be loaded by two Robolectric classloaders, so a bridge test breaks unrelated tests | Real-Rust tests are plain JUnit in `testRustBridge`; Robolectric uses facade fakes | E2.6 |
| Permission or special access revoked while running (full-screen intent, exact alarm, background location, role) | 7.5 `PermissionState` rules, resume re-check, degrade one feature | E3.8b, E5.12, E7.5 |
| Strings: the GPUI apps are English literals; the owner may want Turkish | Default English; all user-facing text comes from Rust sentences or `strings.xml` (no literals in composables, convention test), so Turkish is a later resource addition. Owner confirms before E8 | E1.21, E8.7 |
| Release keystore path in `gpuiforge.json` (`/home/erdem/...`) does not exist on the agent machine | Release tasks read the keystore from env; without it `assembleRelease` signs with the debug key and `check-apk.sh` says so | E8.1 |
| In-place upgrade loses data or roles | Same id, key, component names, channel ids, prefs names; migration tests; release-signed acceptance | E8.4 |
