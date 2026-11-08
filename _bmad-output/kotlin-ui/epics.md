# Kotlin UI: epics and stories

Architecture: `docs/kotlin-ui/architecture.md` (cited as **ARCH n**). Skill: `.claude/skills/convx-motion-compose`
(cited as **SKILL ref section**). Toolchain and the check script: `docs/kotlin-ui/toolchain.md`.
Branch: `ui-overhaul` (never push to `main`).

## How to use this file

- Epics run in order: E1 design system, E2 Rust bridge, E3 platform layer, E4 Contacts, E5 Phone, E6 Messages
  (with WhatsApp), E7 Stops, E8 release. E1 does not need E2 (`:design` has no Rust); E2 and E3 may overlap once E1
  round 1 is done.
- Each epic is split into **rounds of at most 8 stories**. A planner picks one round, writes its plan under
  `_bmad-output/kotlin-ui/plans/<epic>-r<n>.md`, and updates the **Status** column here: `todo`, `in-progress`,
  `done`. Add an **Evidence** line under a story when it is done (commit, the PNGs that were opened and what was
  seen).
- **Hard dependencies** (a round may start only when these are `done`; the planner checks this table first):

  | Needs | Stories |
  |---|---|
  | E3 `:shell` and `:wa` | E1.1-E1.8, E1.18 (nav), E2.6 |
  | E4 Contacts | E1 done; E2.7, E2.10; E3.2, E3.6, E3.8a/b, E3.10, E3.11 |
  | E5 Phone | E1 done; E2.9, E2.10 (`ContactIndex`), E2.14; E3.2, E3.3, E3.5, E3.8a/b, E3.9, E3.10, E3.11 |
  | E6 Messages | E1 done; E2.8, E2.11a/b, E2.14; E3.4, E3.6, E3.9, E3.10, E3.11 |
  | E7 Stops | E1 done; E2.8, E2.12, E2.13; E3.7, E3.8a/b, E3.11 |
  | E8 | all of E4-E7; E8.7 (owner acceptance) before E8.6 (removal) |

- Every app epic first reaches **feature parity** with its GPUI app (the parity table lists every feature ID from
  `_bmad-output/plans/util-*.md` and the roadmap, `docs/roadmap.md`, and the story that delivers it), then polish.
- Definition of done for every story (not repeated below):
  1. `scripts/kotlin-ui-check.sh` passes (and the `CLAUDE.md` Rust checks if a workspace crate changed).
  2. New pure functions have JVM tests; gestures have Compose tests with `mainClock.autoAdvance = false`.
  3. The listed screenshots exist as goldens (`android/<module>/screenshots/`), each in light and dark unless the
     story says otherwise, and **every new or changed PNG was opened with the Read tool and judged**
     (ARCH 9). What was seen goes in the Evidence line.
  4. `references/checklist.md` sections that apply are ticked or have a written reason.
  5. Licence: no Convx code; glisse-derived files carry the header; `android/THIRD_PARTY_NOTICES.md` updated for
     any new third-party code.
- Screenshot names: `<story>-<what>[-dark|-light][-fs2|-rtl]`, for example `e1.9-menu-open-below-dark.png`.

---

## E1. Design system, motion, overlays, text field (`:design`, `:catalog`)

Goal: every component the apps need exists, is demonstrated in the catalog app, and is proven by screenshots,
before any app screen is written. Spec: SKILL `references/*` (all), ARCH 4, 9.

Round plans: R1 -> `_bmad-output/kotlin-ui/plans/round-01.md` (planned 2026-11-08; implemented in `6cf6b37`..`c46d5dc`,
verified in code by the round-2 planner). R2 -> `_bmad-output/kotlin-ui/plans/round-02.md` (planned 2026-11-08 at base
`c46d5dc`; also fixes two R1 screenshot findings: content readable through Flat chrome, and both titles visible at
mid-scroll).

| Story | Title | Round | Status |
|---|---|---|---|
| E1.1 | Modules, convention plugins, screenshot harness, catalog shell | R1 | done |
| E1.2 | Theme: colours, type, spacing, shapes, contrast test | R1 | done |
| E1.3 | Motion tokens, spring solver, fling projection, rubber band, reduce motion, MotionGate | R1 | done |
| E1.4 | Press feedback, hold rule, haptics | R1 | done |
| E1.5 | Glass: tier function, capture engine, Flat/Frost/Liquid, AGSL lens and rim, renderer probe | R1 | done |
| E1.6 | Buttons, rows, inset-grouped sections, avatars, chip row, badges, empty state | R1 | done |
| E1.7 | Floating tab bar with puck, ChromeMetrics and content insets | R1 | done |
| E1.8 | Large-title scaffold, inline bar, toolbar capsules, back button | R1 | done |
| E1.9 | Overlay host and placement | R2 | done |
| E1.10 | Button-anchored menu (glisse morph, press-slide-release) | R2 | done |
| E1.11 | Long-press context menu (simple and preview-card variants) | R2 | done |
| E1.12 | Popover (anchored arbitrary content) | R2 | done |
| E1.13 | Sheets with detents, pushback, background interaction | R2 | done |
| E1.14 | Alert, action sheet, confirm | R2 | done |
| E1.15 | Toast and undo pill | R2 | in-progress |
| E1.16 | Text field, search field, composer field | R2 | done |
| E1.17 | Switch, slider, segmented control | R3 | todo |
| E1.18 | Navigation 3 scaffold: per-tab stacks, push/pop, predictive back, shared bounds | R3 | todo |
| E1.19 | Swipe-to-reveal row and alphabet rail | R3 | todo |
| E1.20 | Image loading and the photo viewer | R3 | todo |
| E1.21 | Performance harness and convention tests | R3 | todo |
| E1.22 | Catalog complete, E1 contact sheet, checklist pass | R3 | todo |

### E1.1 Modules, convention plugins, screenshot harness, catalog shell
- AC1 `android/build-logic` with convention plugins (`rovsti.android.library`, `rovsti.android.app`, `rovsti.compose`,
  `rovsti.screenshots`); SDK levels minSdk 34 / targetSdk 36 / compileSdk 37 (ARCH 3) in one place.
- AC2 `:design` and `:catalog` (applicationId `dev.rovsti.catalog`, debug only) exist; `settings.gradle.kts` includes them;
  `docs/kotlin-ui/toolchain.md` module names match ARCH 2.
- AC3 Roborazzi output dir is `<module>/screenshots/`; `ShotTest` helper renders light and dark and optional
  `FontScale2`, `Rtl`; a sample shot is recorded and verified.
- AC4 Compose compiler reports and metrics land in `build/compose_reports` through a Gradle property.
- AC6 `ShotTest` pins locale `en-US`, time zone UTC, a fake `Clock`, a seeded `Random`, a fake image fetcher, and sets
  the Roborazzi `changeThreshold` (ARCH 9 "Determinism"); two consecutive `verifyRoborazziDebug` runs pass without
  re-recording.
- AC5 `docs/kotlin-ui/api-notes.md` lists each Compose/Navigation API the skill names, checked against the pinned
  sources (name, artifact, version, file).
- Screenshots: `e1.1-catalog-home-{light,dark}`.
- Evidence (E1.1, commit 6cf6b37): `android/build-logic` (plugins `rovsti.android.library|app`, `rovsti.compose`,
  `rovsti.screenshots`, `RovstiSdk` 37/34/36), `:design` (`RovstiTheme` stub, `RovstiClock`), `:testing` (`ShotTest`,
  `FixedClock`, `StripedBackdrop`, `ShotVariant`), `:catalog` (`CatalogActivity`, `CatalogPage`, `CatalogApp`).
  Opened with Read: `e1.1-catalog-home-light.png` and `-dark.png` (title, subtitle, Pages and Planned sections on the
  page colour; first line sits below the simulated 28 dp status bar, last row ends well above the simulated gesture bar,
  secondary text legible), plus `e1.1-catalog-toolchain-{light,dark,fs2-*,rtl-*,landscape-*}.png` (fs2 wraps and fits,
  RTL mirrors rows and title, landscape caps content at 640 dp and centres it). Two consecutive
  `:catalog:verifyRoborazziDebug` runs pass; `-PcomposeReports` writes `android/design/build/compose_reports`.
  Findings: `enableAccessibilityChecks` is not public in ui-test 1.12.1 (see `docs/kotlin-ui/api-notes.md`);
  changing Robolectric qualifiers recreates the activity, so landscape renders in its own activity inside `ShotTest`
  and must be the last `shot()` of a test.

### E1.2 Theme
- AC1 `RovstiTheme` with light and dark colour sets, follows the system theme; page, grouped background, labels
  (primary, secondary, tertiary), separators, fills, accent, destructive, glass presets.
- AC2 Typography in `sp` on the system font (large title 34 sp bold, title, headline 17 sp semibold, body 17,
  callout, subheadline 15, footnote 13, caption) (SKILL navigation-layout 10-11).
- AC3 `Spacing` tokens (gutter 16, gaps 4/8/12/16/24, text gap 2, section gap 24) and `Sizes` (row min 56/64,
  avatar 40/44, touch 48, button 44).
- AC4 JVM test: every label colour on every background and glass preset is >= 4.5:1 in light and dark (fixes X5, X7 of
  `docs/design/layout.md`).
- Screenshots: `e1.2-palette`, `e1.2-type-scale`, `e1.2-type-scale-fs2`.
- Evidence (E1.2, commit 5905fcb): `android/design/.../theme/` `RovstiColors`, `GlassTokens` (glisse header), `RovstiType`, `Spacing`/`Sizes`/`RovstiShapes`/`concentric`,
  `RovstiTheme` + `RovstiText`, `Contrast.kt`; JVM `ContrastTest`, `ConcentricTest`, `SpacingTokensTest` green; catalog `PalettePage`/`TypeScalePage`
  with `ThemePageTest`. Design decision: glass fills over white/black backdrops cannot give 4.5:1 to labels (dark glass over white is 4.3:1 for pure
  white), so `RovstiColors.glassScrim` (white or black at 40 %) sits under every glass fill; E1.5 must paint it. Accent/destructive/success are 4.5:1 on
  flat surfaces and 3:1 on glass (glyph or bold text). Opened e1.2-palette-{light,dark}, e1.2-type-scale-{light,dark}, e1.2-type-scale-fs2-{light,dark}:
  measured ratios drawn on the swatches are legible (secondary 7.4-12.6:1 on page, tertiary 4.3+ light, glass tiles 3.3-21:1), selected pill reads, fs2 wraps
  to three lines without clipping (page scrolls, lower styles checked by `everyTypeStyleIsListedAtFontScale2`).

### E1.3 Motion
- AC1 `RovstiMotion` holds every spring/tween token of SKILL motion 2.1-2.3, each spring with `visibilityThreshold`;
  a test pins the values.
- AC2 Ported glisse analytic spring solver (header present) with tests: step response vs RK4 per regime; retarget mid-flight
  keeps velocity.
- AC3 `Fling.project(velocity)` (0.998) and `rubberBand(x, dim, 0.55)` pure, JVM-tested.
- AC4 Reduce-motion gate `.gated()` (no squash, parallax, bounce when on) and `MotionGate` (edge-only freeze, SKILL
  performance 5) with tests.
- Screenshots: none (pure); `e1.3-spring-curves` chart drawn by a test canvas to eyeball the curves.
- Evidence (E1.3, commit e43ae0a): `android/design/.../motion/` `RovstiMotion` (glisse header, all tokens of motion.md 2.1-2.3, thresholds required), `SpringState` (ported, header), `Fling`, `Rubber`, `ReduceMotion` (reads `ANIMATOR_DURATION_SCALE` directly, observer-driven; `bounceless` only touches under-damped springs), `MotionGate`. JVM tests: token pin, RK4 at 1 kHz (3 regimes), step splitting, retarget, GlassPressUp overshoot 28 %, Fling 499, Rubber, MotionGate edges. Catalog `MotionPage`; `e1.3-spring-curves-{light,dark,fs2-*}.png` opened: GlassPressUp (red) overshoots once, then dips slightly under, Toggle never overshoots, axes labelled (time ms, progress, target line), legend shows computed ratio/stiffness/overshoot. `scripts/kotlin-ui-check.sh` ALL GREEN.

### E1.4 Press, hold, haptics
- AC1 Glass press node ported from glisse `press.ts` (grow, stretch toward finger, release bounce), scale press 0.96,
  flat row press; all `Modifier.Node`.
- AC2 Hold rule 150 ms (glisse `hold.ts`) as a pure function + node.
- AC3 A Compose test asserts that pressing changes no layout bounds (complaint: "buttons flowed into the content").
- AC4 `RovstiHaptics` (Off/System/Strong) with the event list of SKILL navigation-layout 8.
- Screenshots: `e1.4-press-rest`, `e1.4-press-down-+60ms`, `e1.4-press-release-+120ms`.
- Evidence (E1.4, commit 7499f54): `android/design/.../press/` `GlassPress.kt` (glisse header; pure `pressTargets`, `GlassPressNode` observing Final without consuming, four `Animatable`s, release in `finally` via shared `observePress`, `placeWithLayer`, optional 240 dp glow fading 220 ms), `ScalePress.kt` (0.96, `RovstiMotion.press`), `RowPress.kt` (`fillTertiary` behind content, optional `InteractionSource`, no ripple), `Hold.kt` (glisse header; `HOLD_MS`, `held`, `awaitHoldOrScroll`); `.../haptics/RovstiHaptics.kt` (Off/System/Strong, `HapticEvent`, `PlatformHaptics`, `LocalRovstiHaptics`), `:testing` `RecordingHaptics`, VIBRATE in catalog manifest only. Tests: `PressTargetsTest`, `HoldTest` (149/150 ms), `HapticEventTest`, Robolectric `PressNoLayoutShiftTest` (target and neighbour layout bounds identical before/during/after for glass, scale, row; inner visual does move), `HapticsTest`, `PressPageTest`. Catalog `PressPage`; `e1.4-press-{rest,down-+60ms,release-+120ms}-{light,dark}` and `e1.4-press-scale-row-held-*` opened: pressed button grows and shifts right toward the finger with a faint glow, release frame is slightly below rest size (overshoot), text below never moves; card shrinks, row shows flat grey fill.

### E1.5 Glass
- AC1 `glassTier(GlassConditions)` exactly as SKILL glass 6, table-tested.
- AC2 Capture engine (`glassSource` + glass node) records once, replays translated, builds effects only when size/spec
  change, capture scale tied to blur (SKILL glass 2, 5).
- AC3 AGSL lens and rim ported from glisse with the header and the Aave / kube.io credit; Kotlin CPU twins pass the
  glisse property tests (centre pull 0, rim signs, antisymmetry, relative band, `spectrum(0.4)[0] == 1.08`, rim alpha
  rules) (SKILL glass 7).
- AC4 Probe test records whether Robolectric runs `RuntimeShader`; result written in `api-notes.md`; tests use Flat.
- Screenshots: `e1.5-glass-flat-over-stripes`, `e1.5-glass-frost-over-stripes` (if renderable), `e1.5-rim-cpu-twin`,
  and `e1.5-glass-liquid-over-stripes` only if the probe passed.
- Evidence (E1.5, commit 8fe7a78): `android/design/.../glass/` `GlassTier.kt` (tier fn exactly glass.md 6, `LocalGlassConditions`, `rememberGlassConditions`), `GlassSpec.kt` (Bar/Popover/Sheet/Thumb presets, `captureScale`), `GlassSource.kt` + `GlassNode.kt` (source recording, node re-records only on offset/size/press-scale change, effects keyed on size/spec/corner, saturation as colorFilter, null effect, Flat fill 0.85 + hairline + dropShadow, `frozen` plain field, inverse-press replay), `Lens.kt`/`LensShader.kt`/`Rim.kt` (glisse headers + Aave/kube.io credit; CPU twins, pure `lensUniforms`, `RimBrush`, `rimImage`), `GlassEffect.kt` (blur then lens). `ShotTest` forces Flat. Tests: `GlassTierTest`, `CaptureScaleTest`, `LensPullTest` (incl. spectrum 1.08), `RimTwinTest`, `LensUniformsTest`, Robolectric `RuntimeShaderProbeTest` (result in api-notes.md: blur and AGSL both run), `GlassPageTest`. Catalog `GlassPage` + `RimTwinPanel`; `StripedBackdrop` moved to `:catalog`. Opened `e1.5-glass-{flat,frost,liquid}-over-stripes-{light,dark}` and `e1.5-rim-cpu-twin-{light,dark}`: Flat labels legible, shadow and hairline visible; Frost blurred with saturated tint; Liquid shows lens colour fringing at capsule ends; rim bright on top-left and bottom-right, faint at theme shine 0.13.

### E1.6 Rows and small components
- AC1 Buttons: glass icon button (44 dp), capsule, text, destructive, disabled.
- AC2 `ListRow` (one leading slot, title, one secondary line, trailing meta, `heightIn(min)`, merged semantics, custom
  actions), `SectionHeader`, inset-grouped section (radius 20, inset separators hidden after the last row).
- AC3 `Avatar`: photo or initials with colour from a pure function, text from a cached layout in `drawWithCache`; group
  avatar is one composite, never an overlapping stack.
- AC4 `ChipRow`: one non-wrapping `LazyRow`, 8 dp gaps, 16 dp content padding, selected chip filled with >= 4.5:1 text;
  count shown as a separate small label, not glued text.
- AC5 `Badge`, `EmptyState` (glyph, title, one line, optional action), source glyph (14 dp) for SMS/WhatsApp.
- Screenshots: `e1.6-buttons`, `e1.6-rows`, `e1.6-rows-fs2`, `e1.6-grouped-section`, `e1.6-avatars`,
  `e1.6-chip-row-overflowing`, `e1.6-empty-state`.
- Evidence (E1.6, commit f930a74): `android/design/.../controls/` (`Buttons.kt` glisse header, `PressFill.kt`, `ChipRow.kt`, `Badge.kt`, `EmptyState.kt`, `SourceGlyph.kt`), `list/` (`ListRow.kt`, `SectionHeader.kt`, `InsetGroupedSection.kt`, `Avatar.kt`), `icons/` (`RovstiIcons.kt`, `RovstiIcon.kt`); `kotlinx-collections-immutable` 0.4.0 in the catalog and `api` of `:design`. Tests: JVM `AvatarColorTest` (palette >= 4.5:1 with white, stable colour, initials, badge text), Robolectric `ListRowSemanticsTest` (one merged node, custom action, >= 56/64 dp, grows at fs2), `ChipRowTest` (chips share one top, row scrolls, one selected), `GroupedSectionTest` (2 separators for 3 rows, none after the last, inset 72 dp). Catalog pages Buttons, Rows, Grouped sections, Avatars, Chips, Empty states; home and toolchain rebuilt on `ListRow` + `InsetGroupedSection`. Opened `e1.6-*` light/dark and `e1.1-catalog-home-dark`: text column aligned at 72 dp in every row, 16 dp gutters, group avatar is one split circle, chips on one line and the fifth cut by the right edge, selected chip white on accent legible in light, fs2 rows grow (titles ellipsize rather than clip), separators stop before the last row, buttons 44 dp with disabled at 35 %. Known: glass buttons render in the Flat tier in screenshots by design (JVM renderer); on a plain page they read as white discs with a hairline.

### E1.7 Tab bar and insets
- AC1 `RovstiTabBar` geometry and puck ported from glisse (REST/DRAG springs, hold 150 ms, tap slop 24 dp, drag start
  6 dp, squash/stretch), `Role.Tab` + `selected`, labels drop at font scale >= 1.5, minimise on scroll, folded circle
  variant (Stops).
- AC2 `ChromeMetrics` + `LocalContentInsets`; bar bottom margin = system inset + 8 dp; bar hides while the IME is up.
- AC3 Compose test: a 50-row list scrolled to the end has its last row fully above the bar.
- AC4 Puck never exceeds the bar's inner edge while pressed or stretched (fixes X4); bounds test.
- Screenshots: `e1.7-tabbar-rest`, `e1.7-tabbar-drag-mid`, `e1.7-tabbar-minimised`, `e1.7-tabbar-folded`,
  `e1.7-list-end-above-bar`, `e1.7-tabbar-fs2`.

- Evidence (E1.7, commit 4792edf): `android/design/.../chrome/` (`ChromeMetrics.kt`, `TabBarGeometry.kt` and `RovstiTabBar.kt` with glisse headers, `SelectionPuck.kt`, `TabBarMinimizer.kt`, `BottomScrim.kt`); `press/Hold.kt` gained `awaitHold` (also returns the release position). Tests: JVM `TabBarGeometryTest` (puck inside inner rect for lift 0..1, squash 0..0.2, positions past both ends), `TabBarMinimizerTest`, `ChromeMetricsTest`; Robolectric `ListEndAboveBarTest` (row-49 bottom <= bar top; bar 8 dp above the nav inset), `TabBarGestureTest` (tap, reselect, 1.6-slot drag lands on slot 2, release >= 24 dp off ignored, vertical move before 150 ms scrolls the parent, haptics), `TabBarSemanticsTest`, `PuckBoundsTest` (real layout bounds while flicked), `ImeHidesBarTest`. Catalog pages Tab bar and Tab bar: folded. Opened `e1.7-*` light/dark: puck inside the bar and centred in rest, mid-drag puck between two tabs inside the bar, last row fully above the bar, fs2 icons only, folded circle bottom-start. Known: glass renders Flat in screenshots (lens not visible, lifted puck is a raised fill); minimised bar translates 40 dp so it is partly cut by the screen edge (spec look); content inset keeps the expanded bar height while minimised (no relayout during scroll); the bar is a sibling above the list, so a vertical swipe on it does not scroll the list (a parent scroll container still gets it).

### E1.8 Large-title scaffold
- AC1 `LargeTitleScaffold`: title as item 0, collapse fraction pure and tested, inline bar fades in, large title lifts
  12 dp, progressive top scrim; reduce-transparency variant with solid bar and divider.
- AC2 Toolbar capsules (44 dp glass buttons grouped; a spacer splits capsules) sharing the title baseline (fixes X6).
- AC3 Back button for pushed pages; header actions limited to two plus an overflow menu.
- AC4 Width cap 640 dp centred on wide screens (ARCH 9 "Adaptive layout"); landscape keeps the bars usable.
- Screenshots: `e1.8-title-landscape`, `e1.8-title-w840`, `e1.8-title-expanded`, `e1.8-title-collapsed`, `e1.8-title-mid-scroll`, `e1.8-title-fs2`.
- Round-2 re-judgement (planner, opened `e1.8-title-expanded-light`, `-collapsed-dark`, `-mid-scroll-light`,
  `e1.7-tabbar-rest-light`, `e1.7-tabbar-drag-mid-dark`): rows readable through the Flat tab bar and the collapsed top
  band; large and inline titles both half visible at mid-scroll. Fixed in R2 step 1 (F1, F2); stories stay `done`.

- Evidence (E1.8, commit 7959075, review fixes c46d5dc): `android/design/.../chrome/` (`TitleCollapse.kt`, `LargeTitleScaffold.kt`, `Toolbar.kt`, `BackButton.kt`; `controls/Buttons.kt` `glassSurface` made internal). Tests: JVM `TitleCollapseTest` (fraction, clamps, NaN/zero height, top scrim stops); Robolectric `ScaffoldTest` (collapse fraction read through `onCollapseChanged`, which is the inline alpha by construction: strictly between 0 and 1 at half scroll, 1 when collapsed; third action goes to the overflow with its anchor rect; capsule height 44 dp and centre within 4 dp of the title's first line via `TextLayoutResult`; `topBarPx` = 80 dp; title is item 0 key "title"; back button pops the sub-page), `ScaffoldWideTest` (list and bar <= 640 dp and centred at w840). Catalog page Large title (40 rows, 2 actions + overflow, pushed sub-page; `TODO(E1.10)` for the menu). Opened `e1.8-title-expanded/collapsed/mid-scroll/fs2/landscape/w840/pushed(-rtl)/reduce-transparency` light/dark: capsules level with the title line and 44 dp; collapsed inline title centred with rows fading under a firm scrim (first pass at 0.42 left row text legible beside the title, so the scrim over the bar firms up to 0.94 as the title collapses); mid-scroll shows both titles partly; fs2 title clear of capsules (capsules stay in the 52 dp band, so at fs2 they sit above the first line centre); w840 centred 640 dp column (root now paints the page colour full width, the first landscape shot showed the sides unpainted); RTL pushed page has the mirrored chevron on the leading (right) edge; reduce transparency shows a solid bar with a 0.5 dp divider. Known: Flat glass in screenshots; no simulated display cutout in the harness (cutout handled by `safeDrawing` insets, not visually verified); inline title is hidden from accessibility (the large title carries the heading) so it is unannounced once item 0 is scrolled off; the pushed page's title sits below the button row (iOS style), so capsules only align with the title line on root pages.

### E1.9 Overlay host and placement
- AC1 `OverlayHost`, `OverlayController`, `OverlayEntry` as SKILL overlays 1; back closes only the top entry; exit
  animations play for programmatic dismiss; modal entries clear the page from semantics and set `paneTitle`.
- AC2 `placePanel()` pure with JVM tests: each corner and centre, too tall, RTL, landscape, cutout, IME open.
- AC3 Test: trigger bounds identical before and after `present()`.
- Screenshots: `e1.9-host-two-entries-stacked`.
- Evidence (E1.9 plus R1 fixes F1, F2, R2 step 1): `android/design/.../overlay/` (`OverlayHost.kt`, `Scrim.kt`, `Placement.kt`, all original); `GlassSpec.flatAlpha` (bar 0.96, popover 0.97, sheet 0.98, thumb 0.85); `chrome/TitleCollapse.kt` `inlineTitleAlpha`/`largeTitleAlpha`; Flat top band goes to 1.0 once collapsed. Tests: JVM `PlacePanelTest` (corners, centre, too tall, RTL, landscape, cutout, IME, origin), `TitleAlphaTest` (never both titles), `FlatAlphaTest`; Robolectric `OverlayHostTest` (back pops only the top entry, exit plays before removal, page semantics cleared and restored, `paneTitle`, scrim blocks the list), `TriggerBoundsTest` (identical bounds before, +16 ms, +200 ms, after dismiss), `FlatLegibilityTest` (luminance spread < 0.05 over a black/white stripe backdrop for the tab bar and the collapsed top band), `ScaffoldTest` probes f = 0.8. Opened `e1.9-host-two-entries-stacked-{light,dark}` (card 2 above card 1, page dimmed, both cards legible), `e1.7-tabbar-rest`, `e1.7-list-end-above-bar`, `e1.8-title-collapsed` (no readable row text under the chrome; a faint ghost remains only inside the translucent puck), `e1.8-title-mid-scroll` (one title, the large one half faded). Known: with the manual test clock a dismissal needs one small advance before a large one to start the spring; the page behind is not inert for touch except through the scrim.

### E1.10 Button-anchored menu
- AC1 Menu morph ported from glisse `menu.tsx` (header), per-axis growth from the anchor, clearing blur on open, short
  plain exit; card press +5 dp; drag resistance 7 dp.
- AC2 Press-slide-release from the trigger and on the open menu commits the row under the finger on lift; haptic tick
  on row change; rows with glyph, destructive row, section separators, checkmark row.
- AC3 `menuMorphFrame` invariants tested (t=0 surface = trigger rect; t=1 settled; clip inside card).
- Screenshots: `e1.10-menu-closed`, `e1.10-menu-+40ms`, `e1.10-menu-+100ms`, `e1.10-menu-open-below`,
  `e1.10-menu-open-above`, `e1.10-menu-clamped-edge`, `e1.10-menu-row-pressed`.
- Evidence (E1.10 part 2): `android/design/.../overlay/MenuPanel.kt` (glisse header; plate and card press from `menu.tsx:128-262`, `styles.css:985-1070`), `ButtonMenu.kt` (`menuTrigger` feeds the finger), `OverlayHost.kt` (`OverlayController.beginSlide(entry, Flow<Offset>)`, `SlideTarget`; no global pointer state). One non-consuming Final-pass observer on the card; row rects measured during layout into one `FloatArray` (`NaN` for separators and disabled rows); `hoveredRow(rects, y)` is a pure hit-test. Plate: `menuPlate()` 100 ms, first highlight in place, 80 ms fade out; card follows the finger with `menuResistance`, press `MenuCardPress`, release `MenuCardRelease`, 120 ms sheen. Row change gives `HapticEvent.TextHandle`, destructive commit `Confirm`. Keys: arrows wrap and skip disabled rows, Home/End, Enter commits, Escape/Tab close, first enabled row has logical focus. Semantics: Role.Button, checkmark rows Role.Checkbox + selected, disabled rows `disabled()`, rows 48 dp (`RowMinHeight` 44 -> 48). A slide that stays within 8 dp is a tap: the menu stays open and nothing commits; releasing outside the card commits nothing and leaves it open (glisse). Tests: JVM `MenuHitTest`; Robolectric `MenuSlideTest` (slide to row 3 runs it once and closes, 3 crossings give 3 ticks, release outside, tap, destructive Confirm, tap on a row, semantics click, keyboard wrap/Enter/Escape, roles and 48 dp). Opened `e1.10-menu-row-pressed-{light,dark}` (plate on the second row with rounded 14 dp corners, readable in both themes, disabled row and separator not lit, card sheen faint) and `e1.10-menu-open-below-rtl-{light,dark}` (mirrored, grows from the leading corner, rows right-aligned, check on the leading side); the other e1.10 shots were re-recorded for the 48 dp rows. Known: Compose reports a cancelled touch as a lift, so a cancel after sliding onto a row commits it (a detached trigger still cancels); the demo menu mixes rows with and without icons so labels do not align.

### E1.11 Long-press context menu
- AC1 Gesture resolves four outcomes (tap, long-press open, scroll cancel, child click wins) (SKILL overlays 3.1).
- AC2 Simple variant: lifted copy 1.05 + panel, scrim .28/.14; preview-card variant: card min(.78 W, 340), radius 22,
  drag to dismiss (64 dp or 40 %), return spring.
- AC3 TalkBack equivalent: `onLongClick` semantics and custom actions.
- Screenshots: `e1.11-context-row-open`, `e1.11-context-row-open-near-bottom` (panel above),
  `e1.11-context-preview-card`, `e1.11-context-preview-dragged`, `e1.11-context-open-rtl`.

- Evidence (E1.11): `android/design/.../overlay/` `ContextMenuGesture.kt` (`PressRamp` 300 ms in / spring 0.7-600 out, `Modifier.contextMenu` resolving Tap/Cancel/ChildTook/Long: Main pass before the timeout, Initial pass with consume after open, `onLongClick` + `customActions`), `ContextMenuEntry.kt` (`ContextMenuTarget`, `longPressMenu`, lifted dedicated preview 1.05, scrim .28/.14, panel spring 0.78/700, per-axis scale min 0.08, 20 dp clearing blur on open only, `LongPress` haptic), `ContextMenuPanel.kt` (rows reuse `MenuRows`), `PreviewCardLayout.kt` (pure), `PreviewCardEntry.kt` (menuLift/menuLand, 30 % cross-fade, radius 22, menuGrowX/Y, rubber 0.6/0.3, dismiss 64 dp or 40 %, `previewReturn`). All ORIGINAL, no glisse header. `captureAnchor` now records the unclipped window rect (a half-visible row first lifted as a clipped strip). Tests: JVM `PreviewCardLayoutTest`; Robolectric `ContextMenuGestureTest` (tap, 500 ms hold + LongPress haptic, 20 dp move scrolls, "..." click and hold win, OnLongClick, custom actions, bounds unchanged, panel row runs, 80 dp drag dismisses, short drag returns). Opened `e1.11-context-row-open`, `-row-open-near-bottom` (panel above, row exactly in its slot), `-preview-card(-left)`, `-preview-dragged` (card follows, panel fading), `-open-rtl` (mirrored) light/dark. Known: the finger that opened the menu cannot slide onto panel rows (touch target is fixed at down); the preview card's scrim also dims the tile it grew from.

### E1.12 Popover
- AC1 Anchored popover with arbitrary content (for example a date chooser or a small form), arrowless, placed by
  `placePanel`, scrolls when taller than the free space.
- AC2 Opening never moves the content under it (bounds test).
- Screenshots: `e1.12-popover-below`, `e1.12-popover-above`, `e1.12-popover-tall-scrolling`.

- Evidence (E1.12): `android/design/.../overlay/Popover.kt` (glisse header; `PopoverEntry` reuses `menuMorphFrame` without the glyph, max 320 dp, `placePanel`, height capped by `panelMaxHeight` with `verticalScroll`, transparent input-eating scrim; `Modifier.popoverTrigger`). Catalog `PopoverPage`. Tests: Robolectric `PopoverTest` (three page nodes keep bounds at +16/+100/+300/+1200 ms and after close, 320 dp cap, tall content `performScrollToNode` to the last row (needs `autoAdvance = true` while it runs: the scroll action animates), above when anchored low, scrim tap). Opened `e1.12-popover-below/-above/-tall-scrolling` light/dark: the glass grows out of its button and covers it (menu style), form rows readable, tall one fills the safe area and clips its 17th row mid-scroll.
- Evidence (E1.14): `Alert.kt` (270 dp, min 110, row or column by `maxIntrinsicWidth`, 1.2 -> 1 + fade on `appear`, 150 ms exit, scrim .2/.4, no outside dismiss, `OverlayEntry.onBack` hook so Back runs the cancel action; `dismissOnBack` only with a cancel), `ActionSheet.kt` (max 500 dp, 56 dp rows, separate cancel group, nav inset + 8 dp, 400 ms (0.2833,0.99,0.31833,0.99) in / 150 ms EaseIn out, `confirmDestructive` + two-tap: 'Tap again' 3 s, `Confirm` on commit, `Reject` on timeout, live-region state). Tests: `AlertTest`, `ActionSheetTest`. Opened `e1.14-alert`, `-alert-fs2`, `-action-sheet`, `-confirm-destructive` light/dark. Known: the lower group's drop shadow slightly veils the last row of the upper group in the action sheet.

### E1.13 Sheets
- AC1 Detents (peek, medium .5 H, large H - (status + 24)), settle .86/380 with release velocity, dismiss rule (+12 % H or
  projected), grabber 36x5, nested scroll hand-off (SKILL overlays 5).
- AC2 Pushback of the page behind (.91, 32 dp, dim .22) for modal sheets; a non-modal mode with background interaction
  at small detents (for Stops), no pushback.
- AC3 Back on a sheet: predictive preview 35 % then dismiss.
- Screenshots: `e1.13-sheet-peek`, `e1.13-sheet-medium`, `e1.13-sheet-large`, `e1.13-sheet-pushback`,
  `e1.13-sheet-nonmodal-over-stripes`.

### E1.14 Alert, action sheet, confirm
- AC1 Alert 270 dp, 1.2 -> 1 + fade, out 150 ms; action sheet up to 500 dp, 56 dp rows, cancel separate; destructive
  confirm with a two-tap variant.
- Screenshots: `e1.14-alert`, `e1.14-alert-fs2`, `e1.14-action-sheet`, `e1.14-confirm-destructive`.

### E1.15 Toast and undo pill
- AC1 Capsule above the measured tab bar, 3 s, swipe to dismiss, live region; undo pill with action and countdown;
  one at a time with replace semantics.
- Screenshots: `e1.15-toast-above-bar`, `e1.15-undo-pill`.

### E1.16 Text fields
- AC1 `GlassTextField` and `SearchField` ported from glisse `input.tsx` / `search-field.tsx` (header), over
  `BasicTextField(TextFieldState)`; clear button keeps focus; tapping the pill focuses.
- AC2 `ComposerField`: multiline up to 6 lines, docks with `imePadding()`, send action, hardware Enter handling.
- AC3 Compose tests: tap places the caret at the tapped offset; long-press selects a word; `performTextInputSelection`;
  paste; IME action submits; field stays above the simulated keyboard; font scale 2 does not clip.
- AC4 Selection colours and cursor brush from the accent.
- Screenshots: `e1.16-field-empty`, `e1.16-field-focused`, `e1.16-field-with-selection` (selection highlight and both
  handles visible), `e1.16-field-selection-toolbar` (platform toolbar if Robolectric draws it; otherwise the test asserts
  the toolbar was requested and the shot shows the selection), `e1.16-search-with-clear`, `e1.16-composer-3-lines`,
  `e1.16-composer-ime-open`.

### E1.17 Switch, slider, segmented
- AC1 Ported glisse geometry and gesture rules (deadzones, rubber band /22, near = 24, drag = 6, round to slot), toggle
  spring 1.265/1000, thumb lift; haptic on commit.
- AC2 Gesture tests from glisse tests (tap, drag off-bar selects on release, >24 px off does nothing, hold blocks scroll,
  cancel cancels).
- Screenshots: `e1.17-switch-off-on`, `e1.17-switch-pressed`, `e1.17-slider`, `e1.17-segmented-3`, `e1.17-segmented-drag`.

### E1.18 Navigation scaffold
- AC1 Navigation 3 checked at the pinned version (or the documented fallback, ARCH 5.1); `TabNavigator` with one stack
  per tab, pure and tested (reselect scrolls to top through an event, back pops the tab stack then exits).
- AC2 Push/pop tweens with depth parallax and dim, tab crossfade ~200 ms, predictive back scrubs the pop.
- AC3 Shared-bounds helper (avatar -> detail) (SKILL motion 8).
- AC4 `chromeFor(route)` pattern documented in `:shell` usage notes.
- Screenshots: `e1.18-push-mid`, `e1.18-predictive-back-50pct`.

### E1.19 Swipe row and alphabet rail
- AC1 Swipe-to-reveal: 76 dp per action, full-swipe threshold with haptic, settle 1.0/500, custom accessibility actions.
- AC2 Alphabet rail: `scrollToItem` while scrubbing, letter bubble, haptic per letter.
- Screenshots: `e1.19-swipe-revealed`, `e1.19-swipe-full`, `e1.19-alphabet-rail-scrubbing`.

### E1.20 Images and photo viewer
- AC1 Coil 3 loader factory (memory 25 %/15 % low RAM, crossfade off in rows, size from the slot).
- AC2 `PhotoViewer`: pinch and double-tap zoom, pan with bounds, swipe down to dismiss with background fade, tap hides
  chrome, paging with "3 of 7", toolbar slots (Save, Share, Forward, Edit, Remove); zoom maths pure and tested.
- Screenshots: `e1.20-viewer-fit`, `e1.20-viewer-zoomed`, `e1.20-viewer-dismiss-drag`, `e1.20-viewer-paging`.

### E1.21 Performance harness
- AC1 `CountRecompositions` test helper; a test drives a tab-bar drag and a menu open and asserts <= 2 recompositions per
  composable.
- AC2 Convention tests (ARCH 4.3): no Material3 import, no `composed {`, no `Popup(`/`Dialog(` outside overlay, no raw dp
  in app modules, no `FlowRow` chips.
- AC2b Convention tests also forbid string literals passed to `Text(`/`contentDescription` in app modules (text comes from
  `strings.xml` or Rust sentences), `System.currentTimeMillis()`/`Instant.now()` outside the injected `Clock`, and
  `Thread.sleep`.
- AC2c Every screen test in later epics calls `enableAccessibilityChecks()` (ARCH 9); the helper is in `ShotTest`.
- AC3 Compiler-report check: named hot composables are restartable and skippable.
- Screenshots: none.

### E1.22 Catalog complete
- AC1 The catalog has a page for every component and state above; a "states" page per overlay.
- AC2 `docs/kotlin-ui/screenshots/design.png` contact sheet; `docs/kotlin-ui/design-system.md` (short usage guide,
  points to the skill for numbers).
- AC3 Checklist (SKILL `references/checklist.md`) run over the catalog; findings fixed or written down.
- Screenshots: the contact sheet.

---

## E2. Rust bridge (`crates/rovsti_app_core`, `crates/rovsti_ffi`, `:core-bridge`)

Goal: the Kotlin apps can call every domain function they need, off the main thread, with typed models, contract
tests and the cold-path `headless` entry. Spec: ARCH 6.

| Story | Title | Round | Status |
|---|---|---|---|
| E2.1 | `rovsti_app_core` crate, storage, Stops logic moved (store, pick, snap, headless) | R1 | todo |
| E2.2 | Messages logic moved (prefs, drafts, access, headless) | R1 | todo |
| E2.3 | Contacts and Phone logic moved (trash store, prefs, WhatsApp glue, notes, WA call tracker) | R1 | todo |
| E2.4 | `wa_link` moved (link file, headless send) | R1 | todo |
| E2.5 | `rovsti_ffi`: exports, envelope, panic guard, handles, event sink, desktop build | R1 | todo |
| E2.6 | `:core-bridge`: cargo-ndk and host build tasks, `RustNative`, dispatcher, init | R1 | todo |
| E2.7 | Contract fixtures and Kotlin models | R1 | todo |
| E2.8 | `headless` entry compatible with today's receivers | R1 | todo |
| E2.9 | Phone domain ops | R2 | todo |
| E2.10 | Contacts domain ops | R2 | todo |
| E2.11a | Messages domain ops: inbox, sms, mms, notify, schedule, spam, backup | R2 | todo |
| E2.11b | Messages domain ops: chat tools (find, gallery, export, search, forward, info, reminders, stars, marks, mute, quick replies, voice speeds, shared card, detection) | R2 | todo |
| E2.12 | Stops domain ops | R2 | todo |
| E2.13 | Tiles: binary geometry, cache usage, clear, offline area | R2 | todo |
| E2.14 | WhatsApp client ops, event stream, call audio frames, avatar plan | R2 | todo |

### E2.1-E2.4 Move app logic out of the GPUI crates
- AC1 Each moved module keeps its unit tests; the GPUI app now calls the moved function (no duplicate copy).
- AC2 `rovsti_app_core` has no GPUI, no JNI, no `rovsti_ui` dependency; it takes the data dir as a `&Path`.
- AC3 File names and schemas unchanged (ARCH 7.1 table); a test per store reads a fixture written by the old code.
- AC4 `cargo test --workspace`, `cargo clippy --workspace --all-targets` and the Android `cargo check` from `CLAUDE.md`
  pass; GPUI apps still build.
- Moves: E2.1 `apps/stops/src/{store,pick,snap,headless}.rs` (pure parts); E2.2
  `apps/messages/src/{prefs,drafts,access,headless}.rs`; E2.3 `apps/contacts/src/{trash_store,prefs,whatsapp}.rs`,
  `apps/phone/src/{notes,wa_call}.rs` (non-drawing parts) and Phone prefs/backup glue; E2.4
  `rovsti_wa_ui::headless`, the link file, and the pure halves of `rovsti_wa_ui::{avatar_book, names}` (profile-picture fetch plan, 429 back-off, disk naming/pruning, display-name rule).
- Screenshots: none.

### E2.5 `rovsti_ffi`
- AC1 Exports of ARCH 6.3; every export in `catch_unwind`; envelope of ARCH 6.5; `op` dispatch per domain module.
- AC2 Handle registry with release; a leak test (create/release 10 000 handles).
- AC3 Event sink: a test thread emits 1 000 events into a JVM-side fake (host JVM test in E2.6).
- AC4 Builds for `x86_64-unknown-linux-gnu`, `aarch64-linux-android`, `x86_64-linux-android`.

### E2.6 `:core-bridge`
- AC1 `cargoBuildAndroid` and `cargoBuildHost` tasks (ARCH 6.7), incremental (second run is UP-TO-DATE); cargo-ndk in
  `scripts/android-setup.sh`.
- AC2 `RustNative` (externs), `RustDispatcher`, debug `assertNotMain`, `Rust.init` from `Application`.
- AC3 A **plain JUnit** test (no Robolectric, task `testRustBridge`, ARCH 6.7 "Classloader trap") loads the host `.so`, calls
  `init` with a temp dir and a round-trip op. A second test proves a Robolectric test in the same Gradle invocation still
  passes with a fake facade (the split works).
- AC4 APK of `:catalog` (or a test app) contains `lib/arm64-v8a/librovsti_ffi.so`; export list checked.

### E2.7 Contract fixtures
- AC1 `crates/rovsti_ffi/fixtures/<op>.{request,answer}.json` written by a Rust test; `UPDATE_FIXTURES=1` rewrites.
- AC2 Kotlin JVM test decodes and re-encodes every fixture; unknown keys fail.
- AC3 `RustError` mapping tested for every `kind`.

### E2.8 `headless`
- AC1 `headless(kind, json)` answers every kind the GPUI apps answer today (Messages: `sms_classify`, `mms_notification`,
  `mms_retrieved`, `mms_send_req`, `wa_digest`, `wa_reply`; Stops: `trip_fix` and the alarm kinds), byte-identical for
  the existing fixtures.
- AC2 Fallback rules of AD-MSG4 hold (library missing, panic, empty answer -> Kotlin posts a plain notification).

### E2.9-E2.12 Domain ops
- AC0 `qr.encode` / `qr.decode` ops (ARCH 6.6) land with E2.10 (first user, Contacts); Phone, Stops and `:wa` reuse them.
- AC1 Every op listed for the domain in ARCH 6.6 exists, has a fixture and a Kotlin facade method (`suspend`).
- AC2 Stateful objects are handles (`ContactIndex` for search-as-you-type, `ActiveTrip` view).
- AC3 A search-as-you-type benchmark on the host: 5 000 contacts, query answer < 5 ms per keystroke.

### E2.13 Tiles
- AC1 `tile(handle, z, x, y, flags)` returns the packed buffer of ARCH 8.4 (format documented in
  `docs/kotlin-ui/tile-buffer.md`, versioned); styles colorful/eclipse by flag.
- AC2 Cache ops: usage total and per server, clear, clear-except, cap change; offline area job with progress events and
  cancel.
- AC3 Kotlin decoder test reads a buffer made from `rovsti_geo/src/tiles/testdata`.

### E2.14 WhatsApp client
- AC1 Ops for link/relink, keys, chats, messages, send text/media, react, mark read, chat state, groups, presence,
  lookup, statuses, scheduled, calls, push wake parse (ARCH 6.6).
- AC2 Event stream handle: start, `reconnect_now`, stop; events through the sink with instance id.
- AC2b `wa.avatars` / `wa.names` ops (ARCH 6.6).
- AC3 Call audio: `audioPush`/`audioPull` with 1 920-byte frames; a loopback test against a fake gateway in
  `rovsti_wa_client` tests.

---

## E3. Platform layer (`:platform`, `:wa`, `:shell`)

Goal: every Android capability of the GPUI apps is a Kotlin repository or service class with JVM tests, and the shared
WhatsApp settings screen and app shell exist. Spec: ARCH 7, 5.

| Story | Title | Round | Status |
|---|---|---|---|
| E3.1 | `:platform` module, port of the bridge files, tests moved | R1 | todo |
| E3.2 | Contacts provider repository (read, write, groups, accounts, photos, options, observer) | R1 | todo |
| E3.3 | Call log, blocklist, blocked calls, SIM accounts | R1 | todo |
| E3.4 | SMS/MMS provider, send, scheduled SMS | R1 | todo |
| E3.5 | Telecom core: CallRegistry, audio path, proximity, call notifications | R1 | todo |
| E3.6 | Notifications, reminders, shortcuts | R1 | todo |
| E3.7 | Location, TripService, TripAlarm, AlarmSound, AlarmActivity | R1 | todo |
| E3.8a | Files, media store, share, clipboard, Photo Picker, camera capture, SAF import/export | R2 | todo |
| E3.8b | Handoff, roles, `PermissionController` + `PermissionState` rules, special access, OEM settings (ARCH 7.5) | R2 | todo |
| E3.9 | `:wa`: WaRepository, events, wake, calls and audio threads, `WaAvatars` | R2 | todo |
| E3.10 | Shared WhatsApp settings screen | R2 | todo |
| E3.11 | `:shell`: RovstiRoot, base activity, containers, permission UI, common settings, licences | R2 | todo |

### E3.1 Port
- AC1 Files ported per ARCH 7.3; classes named by any manifest, PendingIntent or shortcut keep their FQCN.
- AC2 The Rust-calls-Kotlin statics are gone; repositories return Kotlin models or wire JSON for Rust.
- AC3 Existing JVM tests moved and green (`apps/phone/platforms/android/app/src/test/...`,
  `apps/messages/platforms/android/app/src/test/...`).
- AC4 Library manifest declares no components; a test asserts it.
- AC5 SharedPreferences names unchanged (test lists them, ARCH 7.1).

### E3.2-E3.8b Repositories and services
- AC1 Each repository is an interface + Android implementation + fake for tests; provider-backed reads are `Flow`s that
  re-query on change (conflated).
- AC2 Robolectric tests with shadow providers for the main reads and writes (contacts save with every mime of
  `ContactsEdit.EDITED_MIMES`, call log delete/restore, SMS insert/read, scheduled alarm set/fire).
- AC3 E3.5 keeps the incoming order of ARCH 8.2: notification first, UI after; `CallRegistry` tests (`MultiCallTest`,
  `IncomingCallTest`, `OngoingCallTest`) green.
- AC4 E3.7 keeps AD-SA1 to AD-SA9; the Kotlin contract with the Rust alarm plan (`apps/stops/src/kotlin_contract.rs`) is
  moved to `rovsti_app_core` and still checks the Kotlin defaults.
- AC5 E3.8b `PermissionController` exposes state per group as `StateFlow` and asks through the activity result API;
  rationale and "open settings" decisions come from the pure access rules (`rovsti_app_core::messages::access` style).
  `PermissionState` covers never asked / granted / denied once / denied for good / revoked while running (ARCH 7.5);
  table-tested per app. Special access (full-screen intent, exact alarm, battery optimisation, role) has one
  `SpecialAccess` interface with a fake; role checks use `RoleManager.isRoleHeld`.
- AC6 E3.8b The HANDOFF signature permission declarations and `uses-permission` lines are exactly those of the GPUI
  manifests (ARCH 7.2); a test lists them. Foreground-service start failures map to a UI state (ARCH 7.5 rules).
- AC7 E3.8a Camera capture uses `ACTION_IMAGE_CAPTURE` with a `FileProvider`-style `RovstiShareProvider` URI (no `CAMERA`
  permission); Photo Picker is used instead of storage permissions everywhere (`READ_MEDIA_*` are not requested).

### E3.9 `:wa`
- AC1 `WaRepository` (ARCH 7.4): link state, events, chats, messages, `wake()` on push, resync on gap.
- AC2 `WaCalls`: place, accept, reject, end, mute, speaker; audio record/play threads with voice-communication attributes.
- AC3 Fake gateway tests for link, resync and call state.
- AC4 `WaAvatars`: fetch plan from Rust, 429 back-off honoured, disk pruning, picture shown through Coil; fake gateway test.

### E3.10 WhatsApp settings screen
- AC1 Exactly two options when not linked: Link WhatsApp (QR or pairing code) / Connect with a key (paste field
  validates `rwk_` keys through Rust); gateway URL field with the URL rules.
- AC2 Connected: account line, Show connect key (mints fresh, copy, share, QR), key list with current marked and revoke
  (confirm), Link again after logout, Delete instance (two-tap confirm).
- Screenshots: `e3.10-wa-not-linked`, `e3.10-wa-linking-qr`, `e3.10-wa-pairing-code`, `e3.10-wa-connect-key-field`
  (with a pasted key and selection), `e3.10-wa-connected`, `e3.10-wa-key-list-menu-open`, `e3.10-wa-logged-out`.

### E3.11 `:shell`
- AC1 `RovstiRoot` (content -> chrome -> overlay host), base activity (edge-to-edge, `adjustResize`, predictive back,
  highest refresh mode), `ShellContainer` base, `routeFor(intent)` pattern.
- AC2 Permission request UI (rationale sheet, denied state row, open settings), common settings pages (Appearance
  Auto/Light/Dark, Haptics Off/System/Strong, About with versions, Licences listing `THIRD_PARTY_NOTICES.md`).
- Screenshots: `e3.11-permission-rationale`, `e3.11-settings-appearance`, `e3.11-licences`.

---

## E4. Contacts (`:contacts`)

Goal: parity with `apps/contacts` (roadmap "Rovsti Contacts", `util-contacts*.md` C1-C54), then polish.

### Parity table (feature -> story)

| Features | Story |
|---|---|
| App, tabs (Contacts, Favorites, Groups, Settings), manifest, intake filters, shortcuts open card (AD-CT8) | E4.1 |
| Sectioned list, search (`ContactIndex`), C10 alphabet rail, C27 fuzzy, C41 more fields ("In details"), C51 recently viewed, no-results Create prefilled, C30 row quick actions, WhatsApp mark (AD-CT6) | E4.2 |
| Card: Call, Message, Email, Share, WhatsApp chat/call handoff (AD-CT5), presence; C5/C52 dates with age, C6 addresses, C17 WA chip per number, C18 websites/nickname/title, C21/C37 recent calls and SMS, C23 default number/email, C32 copy formats, C44 country, C46 phonetic and relation; field long-press menu (Copy, Share, Email, note copy); Open in Android Contacts | E4.3 |
| Editor: names, org, numbers, emails, addresses, dates, websites, relations, phonetic, note, groups, Save to account (AD-CT3), number-less contacts (AD-CT2), photo choose/remove/WhatsApp photo, C13 crop/rotate, C14 camera, discard confirm, C40 undo last edit | E4.4 |
| Delete with Undo, C20 Trash (30 days, Restore, Empty) | E4.5 |
| Favorites: star, C26 custom order, Move earlier/later, C42 frequent, C9 upcoming birthdays + Wish SMS, C50 .ics | E4.6 |
| Groups: create, rename, delete, members add/remove (picker), C25 message group, email group, export group | E4.7 |
| C11 select mode (select all, export, share .vcf, add to group, star, delete), C47 share as text, C53 copy numbers/emails | E4.8 |
| C4 share as text/.vcf/QR (Save/Share image), C12 export one .vcf, C16 copy whole / as vCard, C36 home screen shortcut | E4.9 |
| C7 vCard dates/ADR, C31 open/shared .vcf preview, import whole file with dedup, C28 clipboard import, C19 scan QR from image, C39 CSV export/import, C33 vCard PHOTO, C48 multi-card photos, C49 add to existing contact | E4.10 |
| C8 duplicates and merge, C24 / C54 Clean up filters | E4.11 |
| C1 photo viewer, C2 save/share photo, C3 change/remove from viewer | E4.12 |
| C34 ringtone and voicemail, C35 block/unblock | E4.13 |
| C38 My card, C43 emergency contacts, C15 / C45 recently added / edited | E4.14 |
| Settings: name format and sort, Save to account, C29 counts per account, C22 birthday reminders, WhatsApp, export all .vcf | E4.15 |
| Motion, shared element, checklist, RTL, font scale, perf | E4.16 |

| Story | Title | Round | Status |
|---|---|---|---|
| E4.1 | App module, manifest parity, container, tabs, routes, migration test | R1 | todo |
| E4.2 | Contacts list and search | R1 | todo |
| E4.3 | Contact card | R1 | todo |
| E4.4 | Editor | R1 | todo |
| E4.5 | Delete, Undo, Trash | R1 | todo |
| E4.6 | Favorites, frequent, upcoming birthdays | R1 | todo |
| E4.7 | Groups | R1 | todo |
| E4.8 | Select mode | R1 | todo |
| E4.9 | Share and shortcuts | R2 | todo |
| E4.10 | Import paths | R2 | todo |
| E4.11 | Duplicates, merge, Clean up | R2 | todo |
| E4.12 | Photo viewer on the card | R2 | todo |
| E4.13 | Ringtone, voicemail, block | R2 | todo |
| E4.14 | My card, emergency, recently added/edited | R2 | todo |
| E4.15 | Settings | R2 | todo |
| E4.16 | Polish | R2 | todo |

Shared AC for every E4 story: the GPUI behaviour named in the parity table is reproduced (same rules from Rust), every
list ends above the tab bar, one avatar per row, chip rows never wrap, every long-press opens a context menu from the
host, every destructive action confirms or offers Undo as the GPUI app does.

### E4.1 App module
- AC1 `applicationId dev.rovsti.contacts`, versionCode >= 100, release signing from env, component FQCNs and filters of
  the GPUI manifest (vCard SEND/VIEW, `RovstiShareProvider`), permissions READ/WRITE_CONTACTS, INTERNET, HANDOFF,
  READ_CALL_LOG, POST_NOTIFICATIONS (asked only on use, AD-CT7); manifest test against the committed list.
- AC2 Container, routes, `routeFor(intent)` for `EXTRA_CONTACT_ID` and shared files (tested).
- AC3 Migration test: fixture `contacts-prefs.json`, `contacts-trash.json`, `whatsapp-link.json` load.
- Screenshots: `e4.1-tabs-empty-book`.

### E4.2 List and search
- AC1 Letter sections, alphabet rail, search with fuzzy initials and number fragments, "In details" section, recently
  viewed (Clear with confirm), no-results row offering Create Contact prefilled, WhatsApp glyph (14 dp, once per row).
- AC2 Row long-press: context menu with Call, Message, Copy number, Share, Star, Select.
- Screenshots: `e4.2-list`, `e4.2-list-end-above-bar`, `e4.2-list-fs2`, `e4.2-list-rtl`, `e4.2-search-typing`,
  `e4.2-search-no-results`, `e4.2-rail-scrubbing`, `e4.2-row-context-menu`, `e4.2-recently-viewed`.

### E4.3 Card
- AC1 Header (avatar opens viewer, name, org), action capsules (Call, Message, WhatsApp when available, Email), field
  rows by kind with the C-features of the parity table, recent calls/SMS section behind its permission ask.
- AC2 Field long-press menu: Copy (formats), Share, Email, Set/Clear default, Open in maps (address), Open (website).
- AC3 Shared-element avatar from the list row.
- Screenshots: `e4.3-card-full`, `e4.3-card-minimal`, `e4.3-card-whatsapp-online`, `e4.3-field-menu-open`,
  `e4.3-card-recent-calls`, `e4.3-card-fs2`.

### E4.4 Editor
- AC1 All fields of the parity row; add/remove field rows; field labels picker (popover); account chooser; groups chooser.
- AC2 Photo: menu (Choose, Take photo, Use WhatsApp photo, Remove) -> crop/rotate screen -> save.
- AC3 Validation sentences from `rovsti_core::contact_edit`; discard confirm on dirty back; Undo pill after save.
- AC4 Every field is a `GlassTextField` (caret, selection, paste work); keyboard types per field (phone, email, url).
- Screenshots: `e4.4-editor-new`, `e4.4-editor-filled`, `e4.4-editor-keyboard-open`, `e4.4-editor-label-popover`,
  `e4.4-photo-menu`, `e4.4-crop`, `e4.4-discard-confirm`, `e4.4-validation-error`.

### E4.5 Delete and Trash
- Screenshots: `e4.5-delete-confirm`, `e4.5-undo-pill`, `e4.5-trash-list`, `e4.5-trash-empty`.

### E4.6 Favorites
- AC1 Favorites in the user's order; edit mode drag reorder with spring; Frequently contacted section; Upcoming birthdays
  section and page with Wish SMS and Add to calendar (.ics through the save picker).
- Screenshots: `e4.6-favorites`, `e4.6-favorites-reorder-drag`, `e4.6-upcoming-page`.

### E4.7 Groups
- Screenshots: `e4.7-groups`, `e4.7-group-page`, `e4.7-member-picker`, `e4.7-group-menu-open`.

### E4.8 Select mode
- Screenshots: `e4.8-select-mode`, `e4.8-select-actions-menu`.

### E4.9 Share
- Screenshots: `e4.9-share-menu`, `e4.9-qr-sheet`.

### E4.10 Import
- AC1 Intake decides through `rovsti_core::contact_intake`; preview sheet "Add these contacts?" with counts and dedup;
  CSV mapping via `rovsti_core::contact_csv`; Add to existing contact picker with Undo.
- Screenshots: `e4.10-import-preview`, `e4.10-scan-result`, `e4.10-add-to-existing-picker`.

### E4.11 Duplicates and Clean up
- Screenshots: `e4.11-duplicates`, `e4.11-merge-preview`, `e4.11-cleanup-chips`.

### E4.12 Photo viewer
- Screenshots: `e4.12-viewer`, `e4.12-viewer-menu`.

### E4.13 Ringtone, voicemail, block
- Screenshots: `e4.13-card-options`, `e4.13-voicemail-confirm`.

### E4.14 My card, emergency, recent
- Screenshots: `e4.14-my-card`, `e4.14-emergency-list`, `e4.14-added-edited-tabs`.

### E4.15 Settings
- Screenshots: `e4.15-settings`, `e4.15-settings-fs2`, `e4.15-accounts-summary`, `e4.15-whatsapp` (reuses E3.10).

### E4.16 Polish
- AC1 Checklist pass; recomposition test for list scroll and search typing; motion audit (springs retarget, no layout
  shift on press); contact sheet `docs/kotlin-ui/screenshots/contacts.png`.

---

## E5. Phone (`:phone`)

Goal: parity with `apps/phone` (roadmap "Rovsti Phone", `util-phone*.md` U1-U56, `phone.md`), then polish.

### Parity table

| Features | Story |
|---|---|
| App, ROLE_DIALER, manifest (DIAL, VIEW tel, InCallService, receivers, FCM flavor, share provider), tabs (Favorites, Recents, Contacts, Keypad), setup sheet checklist (`rovsti_core::setup`), hidden Diagnostics page, notice line | E5.1 |
| Keypad: number field (paste works), T9 suggestions, speed dial (long-press digit), voicemail on 1 (glyph, "No voicemail number set"), U6 pause/wait, U14/U53 field menu (Copy, Paste, Clear+Undo, Share, Copy as +E.164), U29 add/create contact, U31 redial, U32 smart paste chooser, U47 long-press Call: SIM / WhatsApp, Message, WhatsApp chip, keypad tones, dual-SIM chooser sheet when no SIM is remembered (`sim_ui`, `sim-choices.json`) | E5.2 |
| Recents: grouped by person, missed badges, U15 date sections, U3 search + filter chips (All, Missed, Incoming, Outgoing, WhatsApp) + U35 With notes, U21 notes search, U39 mark missed seen, row menu (call back, message, WhatsApp call, U20 quick reply, U13 remind later, U25/U56 copy number/country, U4 copy/share as, add to contacts, block, delete with Undo), U12/U45 select mode (delete, block, share), U48 country of unknown number, WhatsApp calls with badge, SIM chip on rows of a dual-SIM phone (S26), Block flow with confirmation and Undo (`block_ui`, S27) | E5.3 |
| Contact/Number sheets: quick actions (Message, Copy, Share, Block), history lines, U11 call details (copy, share, U8 note, delete this call, U44 remind me), U7 per-person stats, U16 export person CSV, U18 share stats, U26 share details, U36 copy all numbers, U9 QR (`qr.encode`), Delete call history confirm sheet (`history_ui`), per-person quick reply and CSV export (`person_ui`) | E5.4 |
| Favorites (order, drag, Move earlier/later, Frequent) and Contacts tab (search, sections, open sheet) | E5.5 |
| Incoming: full screen and lock screen, answer/decline, decline with message (reply sheet), silence, waiting-call card, answer replacing/holding/ending held, CallStyle notification first, first-frame report, native fallback | E5.6 |
| In-call (basics, E5.7a): mute, audio route picker (earpiece, speaker, Bluetooth, endpoints), keypad (DTMF, U49 paste tones + Stop, U50 copy digits, U51 remember tones), hold, timer, U17 note + copy number, U37 copy timer / share summary, proximity, SIM chip on the call screen, post-call notice pill with Block / Undo (`notice.rs`, 17.3-17.4), aura and shared-element moments S3, S4, S6, S8 (`aura`, `flight`) | E5.7a |
| In-call multi-call (E5.7b): Add call sheet (`addcall`: keypad, Favorites, Contacts without leaving the call), swap, merge, split, end all, Held card, conference panel, one/two/three-call faces and S19-S22 moments (`zone`, `zone_ui`) | E5.7b |
| WhatsApp calls: CallSource WhatsApp on Incoming/In-Call (Mute, Speaker, End; others dimmed with the sentence), arbitration, place from Recents/sheet/keypad chip, FCM wake to Incoming, answer from notification after cold start, call_end, missed WA calls in Recents, snapshot re-sync (AD-WA5, AD-WA10) | E5.8 |
| Settings: Blocked (list, U34 paste many, U55 prefix range, U27 share/unblock all + Undo, U38 export/import file, blocked calls), quick replies, speed dial page (U22 share, clear + Undo), WhatsApp, SIM choices, haptics, Data (U5 export CSV with U19 note column, U41 export names, U43 delete older, U24 delete all + Undo, U54 export contacts .vcf), prefs backup/restore | E5.9 |
| U7 statistics page (this week, last 7 days, last 8 weeks, top people), U28 CSV, U40 share, U46 tap a day, U52 records | E5.10 |
| U30 notes page (search, open call, delete + Undo), U33 share all, U42 export CSV | E5.11 |
| Call reminders (U13 notifications), missed-call notification actions, setup flows (role request, Xiaomi background settings, battery optimisation, full-screen intent) | E5.12 |
| U1 photo viewer, U2 save/share, U10 set/replace/remove photo | E5.13 |
| U23 haptic and pill audit, theme melt and setup-complete moments, checklist, perf | E5.14 |

| Story | Title | Round | Status |
|---|---|---|---|
| E5.1 | App module, role, manifest parity, tabs, setup sheet, diagnostics, migration test | R1 | todo |
| E5.2 | Keypad | R1 | todo |
| E5.3 | Recents | R1 | todo |
| E5.4 | Contact, Number and Call details sheets | R1 | todo |
| E5.5 | Favorites and Contacts tabs | R1 | todo |
| E5.6 | Incoming call | R1 | todo |
| E5.7a | In-call: single call | R1 | todo |
| E5.8 | WhatsApp calls | R1 | todo |
| E5.7b | In-call: multi-call, Add call, conference | R2 | todo |
| E5.9 | Settings | R2 | todo |
| E5.10 | Statistics | R2 | todo |
| E5.11 | Notes page | R2 | todo |
| E5.12 | Reminders, notifications, setup flows | R2 | todo |
| E5.13 | Photo viewer and photo edit | R2 | todo |
| E5.14 | Polish | R2 | todo |

### E5.1 App module
- AC1 `dev.rovsti.phone`, arm64-v8a release, every component FQCN of the GPUI manifest (`MainActivity` with DIAL/VIEW
  filters and the activity-alias, `RovstiInCallService`, `CallActionReceiver`, `VoicemailReceiver`,
  `CallReminderReceiver`, provider, firebase `RovstiMessagingService`); role request flow; manifest test.
- AC2 Migration test with fixture `prefs.json` (PrefsV2), `wa-calls.json`, `sim-choices.json`, notes.
- Screenshots: `e5.1-setup-sheet`, `e5.1-diagnostics`, `e5.1-notice-line`.

### E5.2 Keypad
- AC1 Keypad layout from Kotlin pure layout maths (not `rovsti_core::keypad_layout`), sized to the viewport; digits are
  press-feedback buttons with tones; the number is a `BasicTextField` with the soft keyboard suppressed, caret and
  selection working, paste working.
- AC2 T9 suggestion list above the keypad from the `ContactIndex` handle.
- Screenshots: `e5.2-sim-chooser-sheet`, `e5.2-keypad-empty`, `e5.2-keypad-typing-t9`, `e5.2-keypad-field-menu`, `e5.2-keypad-selection`,
  `e5.2-call-longpress-menu`, `e5.2-smart-paste-chooser`, `e5.2-keypad-fs2`, `e5.2-keypad-small-screen`.

### E5.3 Recents
- Screenshots: `e5.3-recents-dual-sim-chip`, `e5.3-block-confirm`, `e5.3-recents`, `e5.3-recents-end-above-bar`, `e5.3-recents-missed-filter`, `e5.3-recents-chips-scrolled`,
  `e5.3-recents-row-menu`, `e5.3-recents-select-mode`, `e5.3-recents-search`, `e5.3-recents-whatsapp-badge`.

### E5.4 Sheets
- Screenshots: `e5.4-contact-sheet`, `e5.4-number-sheet`, `e5.4-call-details`, `e5.4-call-note-editing`,
  `e5.4-person-stats`, `e5.4-qr`.

### E5.5 Favorites and Contacts tabs
- Screenshots: `e5.5-favorites`, `e5.5-favorites-reorder`, `e5.5-contacts-tab`, `e5.5-contacts-search`.

### E5.6 Incoming
- AC1 The notification path works without the UI (JVM test of `CallRegistry`); the Compose screen shows from the same
  snapshot; first-frame report logged; native fallback kept.
- Screenshots: `e5.6-incoming`, `e5.6-incoming-lockscreen-variant`, `e5.6-decline-with-message-sheet`,
  `e5.6-waiting-call-card`.

### E5.7a In-call (single call)
- AC1 Aura is an ambient animation obeying ARCH 10 rule 5; route picker lists endpoints and tolerates a missing
  `BLUETOOTH_CONNECT` grant (ARCH 7.5); the screen is shown over the lock screen only while a call is live.
- Screenshots: `e5.7-incall-active`, `e5.7-incall-keypad`, `e5.7-route-picker-popover`, `e5.7-incall-sim-chip`,
  `e5.7-incall-ended-summary`, `e5.7-post-call-block-pill`.

### E5.7b In-call (multi-call)
- AC1 Multi-call scenes from `rovsti_core::scene` through the bridge; springs of hold-away, swap, merge.
- Screenshots: `e5.7-incall-hold-two-calls`, `e5.7-incall-conference`, `e5.7-add-call-sheet`.

### E5.8 WhatsApp calls
- Screenshots: `e5.8-wa-incoming`, `e5.8-wa-incall-dimmed-controls`, `e5.8-wa-waiting-during-cellular`.

### E5.9 Settings
- Screenshots: `e5.9-settings`, `e5.9-blocked-list`, `e5.9-block-paste-many`, `e5.9-quick-replies`, `e5.9-speed-dial`,
  `e5.9-data-delete-older`, `e5.9-settings-fs2`.

### E5.10 Statistics
- Screenshots: `e5.10-stats-week`, `e5.10-stats-day-tapped`, `e5.10-records`.

### E5.11 Notes page
- Screenshots: `e5.11-notes`, `e5.11-notes-search`.

### E5.12 Reminders and setup flows
- Screenshots: `e5.12-remind-later-menu`, `e5.12-xiaomi-setup-rows`, `e5.12-full-screen-intent-revoked-row`.

### E5.13 Photo viewer
- Screenshots: `e5.13-viewer`, `e5.13-photo-edit-menu`.

### E5.14 Polish
- AC1 Every utility has a pill and a haptic (U23 audit table in the story notes); checklist pass; recomposition tests for
  keypad typing and Recents scroll; contact sheet `docs/kotlin-ui/screenshots/phone.png`.

---

## E6. Messages including WhatsApp (`:messages`)

Goal: parity with `apps/messages` (roadmap "Rovsti Messages", `util-messages*.md` M1-M50, `messages.md`, AD-MSG1 to
AD-MSG9, AD-WA11), then polish.

### Parity table

| Features | Story |
|---|---|
| App, ROLE_SMS components (SMS_DELIVER, WAP_PUSH, RESPOND_VIA_MESSAGE, SENDTO/SEND, boot, reply, scheduled receivers), firebase flavor, tabs (Chats, WhatsApp, Settings), permission asks and refresh on return (`access.rs`) | E6.1 |
| Unified inbox (one person one row, AD-MSG3), one avatar, source glyph once, M30 filter chips (All, Unread, WhatsApp, SMS) one row with counts as labels, M36 pins, M37 archive + Undo, M38 mark unread, mute, M7 mark all read, row menu, swipe actions, M39 bulk select, M21 avatar opens photo, WhatsApp Status row and feed (no overlapping avatar stack), M18 global search with jump, drafts marker | E6.2 |
| SMS/MMS conversation: timeline, delivery reports, dual-SIM send, composer, M45 undo send 4 s, drafts survive restarts, failed message Copy/Delete, M15 delete single + Undo, MMS view/save, attachments, M46 paste image (MMS), unblock and make-default rows | E6.3 |
| WhatsApp conversation (E6.4a thread, text, paging, ticks, edited/forwarded marks, group sender names, typing, keyboard Send, WhatsApp avatars with back-off; E6.4b the rest): history paging, text, media send (optimistic bubble, retry, 16 MiB rule), reactions, M32 double-tap react, M49 recent emoji, M31 swipe to reply, quoted replies, group sender names, typing, ticks, edited/forwarded marks, M17 voice notes with speed and scrubbing, keyboard Send, M46 paste image, save received files | E6.4a / E6.4b |
| Message context menu (preview card + reaction bar): reply, copy, M16 select text, M9 forward, M14 info, star, M33 remind me, delete; M20 select mode copy/forward as block; detection pills: M8 phone/email, M25 links, M19 dates, M40 maps links, M35 location card, OTP Copy code (SMS and M44 WhatsApp) | E6.5 |
| Conversation tools: M5 search with jump + glow, M24 jump to date (popover), M12 scroll-to-bottom with count + unread divider, M43 next unread, M13 media/links/docs gallery, M6 export .txt / share text, M48 export .zip, M27 clear chat, M42 header long-press copy, M29 mute override, M50 group sender mini card, M22 message privately | E6.6 |
| M1 image viewer, M3 swipe between images "3 of 7", M2 save/share, M4 header photo, M26 shared contact card sheet (save .vcf, add to contacts), M28 non-image attachment copy/share, Status viewer | E6.7 |
| New conversation (number or contact, SMS or WhatsApp), forward chooser, share-in (text, images), `sms:`/`smsto:`/`mms:`/`mmsto:` intents, contact handoff `WA_CHAT` (AD-CT5) | E6.8 |
| Notifications: per-conversation digest (`rovsti_core::notify`), MessagingStyle, inline reply SMS and WhatsApp (cold through `headless`), OTP copy action, M41 remind in 1 hour, `uiAttached` rule, FCM wake (AD-MSG5) | E6.9 |
| Scheduled send (AD-MSG6): long-press Send sheet, Scheduled strip, SMS alarms, WhatsApp gateway items, fired settle | E6.10 |
| Spam (AD-MSG8): rules, allow list, system blocklist, Spam folder, not spam, move sender to Spam, M47 delete codes after copying | E6.11 |
| Settings: M11 quick replies (+ composer chip row), M34 chat text size, notifications, WhatsApp, encrypted backup export/import with preview (AD-MSG7), default SMS app ask | E6.12 |
| M10 WhatsApp starred list, M23 local SMS stars, M33 reminders (notification opens and glows the message) | E6.13 |
| Keyboard-open shots, long-conversation performance, checklist | E6.14 |

| Story | Title | Round | Status |
|---|---|---|---|
| E6.1 | App module, SMS role, manifest parity, tabs, permissions, migration test | R1 | todo |
| E6.2 | Unified inbox | R1 | todo |
| E6.3 | SMS and MMS conversation | R1 | todo |
| E6.4a | WhatsApp conversation: thread, text, ticks, groups, paging | R1 | todo |
| E6.4b | WhatsApp conversation: media send, voice notes, reactions, replies, paste image | R2 | todo |
| E6.5 | Message context menu, select mode, detection pills | R2 | todo |
| E6.6 | Conversation tools | R1 | todo |
| E6.7 | Viewers: images, status, contact card, attachments | R1 | todo |
| E6.8 | New conversation, forward, share-in, intents | R1 | todo |
| E6.9 | Notifications and inline reply | R2 | todo |
| E6.10 | Scheduled send | R2 | todo |
| E6.11 | Spam | R2 | todo |
| E6.12 | Settings and encrypted backup | R2 | todo |
| E6.13 | Starred and reminders | R2 | todo |
| E6.14 | Polish | R2 | todo |

### E6.1 App module
- AC1 Every component FQCN of the GPUI Messages manifest (main and firebase); default-SMS role request; manifest test.
- AC2 Migration test with fixture `messages-prefs.json`, `drafts.json`, `scheduled.json`, `whatsapp-link.json`.
- Screenshots: `e6.1-default-sms-ask`, `e6.1-permission-row`.

### E6.2 Inbox
- AC1 Rows: one avatar (group: one composite), name + 14 dp source glyph when the row is WhatsApp-only or SMS-only (a
  merged person row shows no pill, the latest channel glyph only), preview one line, time, unread dot/badge, pin glyph,
  muted glyph. No row carries a source pill.
- AC2 Filter chips never wrap; counts are small secondary labels.
- AC4 WhatsApp avatars come from `WaAvatars` (E3.9); names follow the Rust rule contact name, WhatsApp name, number.
- AC3 Status row: one ring avatar of the latest update + "N new updates" text, never a stack (fixes layout.md M1).
- Screenshots: `e6.2-inbox`, `e6.2-inbox-end-above-bar`, `e6.2-inbox-chips-unread`, `e6.2-inbox-row-menu`,
  `e6.2-inbox-swipe`, `e6.2-inbox-select-mode`, `e6.2-inbox-empty`, `e6.2-status-row`, `e6.2-global-search`,
  `e6.2-inbox-fs2`, `e6.2-inbox-rtl`.

### E6.3 SMS conversation
- AC1 `ComposerField` docked to the keyboard; the last bubble stays visible above the composer with the keyboard open;
  long-press Send opens the schedule sheet (E6.10 fills it).
- Screenshots: `e6.3-sms-thread`, `e6.3-sms-keyboard-open`, `e6.3-composer-multiline-selection`, `e6.3-undo-send-pill`,
  `e6.3-failed-message-menu`, `e6.3-mms-bubble`, `e6.3-dual-sim-chooser`.

### E6.4a / E6.4b WhatsApp conversation
- E6.4a shots: `e6.4-wa-thread`, `e6.4-wa-group-thread`, `e6.4-wa-keyboard-open`. E6.4b shots: the rest below.
- Screenshots: `e6.4-wa-thread`, `e6.4-wa-group-thread`, `e6.4-wa-reply-swipe`, `e6.4-wa-quoted-composer`,
  `e6.4-wa-media-pending`, `e6.4-wa-voice-note`, `e6.4-wa-keyboard-open`.

### E6.5 Message menu and detection
- AC1 Long-press a bubble: preview-card context menu (E1.11) with the reaction bar above and the action panel below or
  above by placement; the list does not scroll or move under it.
- Screenshots: `e6.5-bubble-menu-below`, `e6.5-bubble-menu-above`, `e6.5-reaction-picker`, `e6.5-select-text-sheet`,
  `e6.5-message-info`, `e6.5-detection-pills`, `e6.5-otp-copy-code`, `e6.5-select-mode-block`.

### E6.6 Conversation tools
- Screenshots: `e6.6-find-in-chat`, `e6.6-jump-to-date-popover`, `e6.6-scroll-to-bottom-count`, `e6.6-unread-divider`,
  `e6.6-gallery-grid`, `e6.6-header-menu`, `e6.6-sender-mini-card`.

### E6.7 Viewers
- Screenshots: `e6.7-image-viewer-paging`, `e6.7-status-viewer`, `e6.7-contact-card-sheet`, `e6.7-attachment-menu`.

### E6.8 New conversation and forward
- Screenshots: `e6.8-new-conversation`, `e6.8-new-conversation-typing-number`, `e6.8-forward-chooser`, `e6.8-share-in`.

### E6.9 Notifications
- AC1 Robolectric tests: notification built from a digest spec; inline reply goes through `headless` when the UI is not
  attached and through the repository when it is; fallback notification when Rust is missing.
- Screenshots: none (notifications are system UI); JVM assertions on the built `Notification`.

### E6.10 Scheduled send
- Screenshots: `e6.10-schedule-sheet`, `e6.10-scheduled-strip`.

### E6.11 Spam
- Screenshots: `e6.11-spam-folder`, `e6.11-spam-rules`, `e6.11-rule-editor-field`.

### E6.12 Settings and backup
- Screenshots: `e6.12-settings`, `e6.12-quick-replies`, `e6.12-text-size`, `e6.12-backup-passphrase`,
  `e6.12-restore-preview`, `e6.12-settings-fs2`.

### E6.13 Starred and reminders
- Screenshots: `e6.13-starred-list`, `e6.13-remind-me-popover`, `e6.13-message-glow`.

### E6.14 Polish
- AC1 Recomposition tests: typing in the composer (<= 2 per bubble), scrolling 2 000 bubbles, opening a bubble menu;
  checklist pass; contact sheet `docs/kotlin-ui/screenshots/messages.png`.

---

## E7. Stops (`:stops`)

Goal: parity with `apps/stops` (roadmap "Rovsti Stops", `util-stops*.md` S1-S44, `stops*.md`, AD-ST1 to AD-ST9, AD-SA1
to AD-SA9, AD-SX1 to AD-SX8), as a **full-screen map with floating controls** (ARCH 8.4), then polish.

### Parity table

| Features | Story |
|---|---|
| App, manifest (TripService location FGS, AlarmActivity, boot, `geo:`/maps VIEW, SEND text/plain, exact alarm, full-screen intent), full-screen map home with floating chrome, folded tab bar, sheet host | E7.1 |
| Tile map: own renderer over `rovsti_tiles` (camera, pan, pinch, fling, double-tap zoom: E7.2a; bitmap LRU, parent stand-ins, colorful/eclipse by theme, radar look where tiles are missing, attribution, S28 scale bar: E7.2b) | E7.2a / E7.2b |
| Radar layers: A and B pins, circle + diameter line + halves, position + accuracy, breadcrumb, GTFS stops and line, S40 saved stops on the map (toggle, tap opens the stop) | E7.3 |
| Picking (AD-ST9): first tap A, second B, circle at once, third tap hint, pin drag (`PIN_GRAB_PX`, second finger -> pinch), snap to GTFS stop on flick, Move, Swap, Clear, labels "Point A"/"A-B 850 m"; pin menu: S1 copy as (decimal, DMS, DDM, Plus Code, geo:, OSM), S3 QR, S4 open in maps, S34 distance from you + Measure from here, save as stop | E7.4 |
| Trip: start/stop, preflight (location, background recommended row AD-SA7, notifications, exact alarm, full-screen intent, battery), live card (distance, S9 bearing arrow, S41 arrival clock, the line-aware "N stops before" alarm from imported GTFS, `transit.rs`), S20 copy/share status, arriving-soon toggle per trip, restart refused handling (AD-SA8) | E7.5 |
| Alarm: in-app alarm screen, native AlarmActivity over the lock screen, snooze, timeout re-ring (AD-SA5), S21 ring again in N min, S27 trip summary after arrival (share, save as stop) | E7.6 |
| Saved stops: list, S6 search and sort (saved order, name, nearest), reorder, rename, delete + Undo, S24 pin favourites, S35 colour tag, S44 note, S19 reverse and duplicate, S2 share text (paste fills A and B), S3 QR, S18 home screen shortcut, S29 open in maps + distance, S30 copy all, S38 used N times | E7.7 |
| Pick sheets: paste field (S23 DMS / DDM / Plus Code), recent trips, saved stops, GTFS stop search, S7 stop sheet (copy name/code, share, distance), Confirm sheet for shared links, S37 nearest saved stop chip | E7.8 |
| Map options: S17 follow-me (cycling Me button), S11 appearance Auto/Light/Dark, S42 copy map link / share view / copy centre, S8 + S26 measure tool (legs, Undo last, Clear, copy, GPX, save two points as stop), S25 share my location (Me long-press, blue dot) | E7.9 |
| Settings: alarm (S13 sound picker, S14 volume + test, S12 vibration pattern + preview, sound/vibration never both off, S39 reset), S43 units, tile server URL + test, S22 cache cap + per-server usage + Clear, S10 offline area download, GTFS import (AD-ST6), arriving-soon message (chat, template preview, when; AD-ST7), WhatsApp | E7.10 |
| History (AD-ST8): list, stats, S15 search + outcome filter + start again + save as stop, delete single + Undo, S36 delete older, S16 CSV/GPX export, S33 share summary, S31 separators fixed; S32 backup/restore everything | E7.11 |
| S5 saved stops GPX/JSON export and import with merge summary, duplicates skipped, Undo | E7.12 |
| Device performance gate for the tile renderer, MapLibre re-evaluation record | E7.13 |
| Checklist, motion, perf | E7.14 |

| Story | Title | Round | Status |
|---|---|---|---|
| E7.1 | App module, manifest parity, full-screen map home skeleton, migration test | R1 | todo |
| E7.2a | Camera, gestures, tile request/cancel, decode (`MapCamera`, `tile()` client) | R1 | todo |
| E7.2b | Tile rasteriser, bitmap LRU, stand-ins, styles, scale bar, JVM render benchmark | R1 | todo |
| E7.3 | Radar layers | R1 | todo |
| E7.4 | Picking and the pin menu | R1 | todo |
| E7.5 | Trip start, preflight, live card | R1 | todo |
| E7.6 | Alarm and arrival | R1 | todo |
| E7.7 | Saved stops | R1 | todo |
| E7.8 | Pick sheets | R2 | todo |
| E7.9 | Map options, measure, share location | R2 | todo |
| E7.10 | Settings | R2 | todo |
| E7.11 | History, stats, backup | R2 | todo |
| E7.12 | Saved stops import and export | R2 | todo |
| E7.13 | Tile renderer device check and MapLibre decision record | R2 | todo |
| E7.14 | Polish | R2 | todo |

Shared AC for every E7 story: the map is the full window in every state; no screen shows a map inside a box; every
control floats over the map as glass chrome; the sheet at peek and medium lets the map pan and take taps; floating
buttons ride the sheet by translation only; camera padding follows the settled sheet detent.

### E7.1 App module and home skeleton
- AC1 `dev.rovsti.stops`, components `TripService`, `AlarmActivity`, `BootReceiver`, VIEW `geo:` + maps links, SEND
  text/plain; manifest test.
- AC2 Home: map layer (fake tile source in tests), search/pick pill, button column, folded tab bar, sheet host; Saved,
  History, Settings open as sheets or pushed pages over the map.
- AC3 Migration test with fixture `stops-prefs.json`, `trip.json`, legacy trip (renamed, AD-SX8), `saved.json` v2,
  `history.json`, `gtfs.json`.
- Screenshots: `e7.1-home-idle`, `e7.1-home-idle-dark`, `e7.1-home-sheet-peek`, `e7.1-home-sheet-medium`,
  `e7.1-home-landscape`, `e7.1-home-fs2`.

### E7.2a / E7.2b Tile renderer
- Split: E7.2a is ARCH 8.4 steps 1-2 (camera, gestures, requests, cancellation, decode, offline fake source); E7.2b is step 3 and the visuals.
- AC1 Pipeline of ARCH 8.4 steps 1-3; camera holder read in draw; a pinch recomposes nothing (recomposition test).
- AC2 Bitmap LRU sized from memory class; tiles leaving the viewport are cancelled (test with a fake source); parent
  stand-in drawn while children load.
- AC3 Glass over the map is Frost while panning and Liquid (or Flat in tests) at rest (`MotionGate`).
- Screenshots: `e7.2-map-colorful-z14`, `e7.2-map-eclipse-z14`, `e7.2-map-missing-tiles-radar`, `e7.2-map-standin-parent`,
  `e7.2-scale-bar`.

### E7.3 Radar layers
- Screenshots: `e7.3-ab-circle`, `e7.3-position-accuracy`, `e7.3-breadcrumb`, `e7.3-gtfs-stops-line`,
  `e7.3-saved-stops-markers`.

### E7.4 Picking
- AC1 Gesture tests: tap drops A then B; drag near a pin grabs it immediately (no long-press); second finger cancels into
  pinch; flick snaps to a GTFS stop; map tap never moves the view; labels from `rovsti_app_core::stops::pick`.
- Screenshots: `e7.4-pick-a`, `e7.4-pick-ab`, `e7.4-pin-dragging`, `e7.4-pin-menu-open`, `e7.4-copy-as-menu`,
  `e7.4-third-tap-hint`.

### E7.5 Trip
- AC1 Preflight rows follow ARCH 7.5: location (fine) then background (separate step, recommended only), notifications, exact alarm n/a, full-screen intent, battery; a revoked grant while a trip runs shows the row and keeps the trip.
- Screenshots: `e7.5-line-aware-alarm-setting`, `e7.5-preflight-sheet`, `e7.5-trip-live-card`, `e7.5-trip-live-card-dark`, `e7.5-background-row`,
  `e7.5-restart-refused-row`.

### E7.6 Alarm
- AC1 AlarmActivity behaviour unchanged (native Views over the lock screen); the in-app screen matches its actions.
- Screenshots: `e7.6-alarm-in-app`, `e7.6-arrival-summary`, `e7.6-ring-again-menu`.

### E7.7 Saved stops
- Screenshots: `e7.7-saved-sheet`, `e7.7-saved-search-sort`, `e7.7-saved-row-menu`, `e7.7-saved-reorder`,
  `e7.7-saved-edit-note-field`, `e7.7-saved-end-above-bar`.

### E7.8 Pick sheets
- Screenshots: `e7.8-pick-sheet-paste-field`, `e7.8-stop-search`, `e7.8-stop-sheet`, `e7.8-confirm-shared-link`,
  `e7.8-nearest-chip`.

### E7.9 Map options and measure
- Screenshots: `e7.9-map-options-popover`, `e7.9-measure-two-legs`, `e7.9-measure-more-menu`, `e7.9-my-location-sheet`,
  `e7.9-follow-mode`.

### E7.10 Settings
- Screenshots: `e7.10-settings`, `e7.10-alarm-settings`, `e7.10-tiles-settings`, `e7.10-offline-download-progress`,
  `e7.10-arriving-soon`, `e7.10-template-field-selection`, `e7.10-settings-fs2`.

### E7.11 History
- Screenshots: `e7.11-history`, `e7.11-history-stats`, `e7.11-history-filter`, `e7.11-history-row-menu`.

### E7.12 Import and export
- Screenshots: `e7.12-import-summary`.

### E7.13 Performance gate
- AC1 (moved earlier: runs inside E7.2b so a failing renderer is known before E7.3-E7.8 build on it) JVM benchmark: rasterise a dense
  city tile (testdata) in < 25 ms on the host; draw 16 cached bitmaps per frame with no allocation (allocation-count test).
  E7.13 keeps AC2, the owner-device check.
- AC2 Owner device checklist entry: pan and pinch at 120 Hz over a city, frame stats recorded (`dumpsys gfxinfo`); if p90
  > 8.3 ms after the listed fixes, open a decision record on MapLibre.

### E7.14 Polish
- AC1 Checklist pass (B "Stops" line), contact sheet `docs/kotlin-ui/screenshots/stops.png`.

---

## E8. Release, profiles, docs, retiring GPUI from the default build

| Story | Title | Round | Status |
|---|---|---|---|
| E8.1 | Release build types, signing from env, versionCode scheme, ABI policy | R1 | todo |
| E8.2 | R8 and resource shrinking with minimal keep rules | R1 | todo |
| E8.3 | Baseline profiles and the Macrobenchmark module | R1 | todo |
| E8.4 | In-place upgrade: migration tests for all apps, data and role checklist | R1 | todo |
| E8.5 | Docs: architecture, android.md, CLAUDE.md rules, screenshots, notices audit | R1 | todo |
| E8.7 | Owner device acceptance checklist (runs before removal) | R1 | todo |
| E8.6 | Remove the GPUI apps and crates from the default build (only after E8.7 signed off) | R1 | todo |
| E8.8 | targetSdk 37 review | R1 | todo |

### E8.1 Release builds
- AC1 `assembleRelease` for the four apps with the release keystore from env (`scripts/signing.sh` style; when `ANDROID_STORE_PASSWORD` or the keystore file is absent, as on the agent machine where `gpuiforge.json` points at `/home/erdem/...`, `assembleRelease` signs with the debug key and `check-apk.sh` prints `UNSIGNED-FOR-RELEASE`); versionCode
  `100 + build number` (> GPUI's 1); arm64-v8a only in release.
- AC2 `scripts/build-apks.sh` builds the Kotlin APKs; `scripts/check-apk.sh` checks id, version, signer, ABI, `.so` exports.

### E8.2 R8
- AC1 `isMinifyEnabled` and `isShrinkResources`; keep rules only for `RustNative`/`RustEvents`, serialization and manifest
  classes; no package wildcards; a release check that cannot use Robolectric (it cannot run minified classes): a Gradle task parses `mapping.txt` and fails if
  `RustNative`, `RustEvents`, their members, `@Serializable` models or any manifest class were renamed or removed, and `check-apk.sh`
  lists the `.so` exports; an instrumented bridge call is added to `acceptance.md` for a device.

### E8.3 Baseline profiles
- AC1 `androidx.profileinstaller` in every app; hand-written rules for first screen, rows, overlay host, map node;
  `:benchmark` module ready (cold/warm start, list scroll, menu open, map pan) for when a device or managed emulator exists.

### E8.4 In-place upgrade
- AC1 Per app: fixture data dir from the GPUI build loads in the Kotlin app (JVM test); SharedPreferences names, channel
  ids, component FQCNs unchanged (tests from E3.1 and the manifest tests).
- AC2 `docs/kotlin-ui/upgrade.md`: release-signed update steps, what to check after updating (role still held, WhatsApp
  still linked, scheduled SMS still fires, trip resumes).

### E8.5 Docs
- AC1 `docs/architecture.md` points to the Kotlin UI architecture for the UI; GPUI sections marked historical.
- AC2 `CLAUDE.md` updated: Kotlin UI rules, the only JNI crate is `rovsti_ffi`, checks are `scripts/kotlin-ui-check.sh`.
- AC3 `android/THIRD_PARTY_NOTICES.md` audited (glisse, any Apache-2.0 dependency notices); every glisse-derived file has
  the header (grep test).

### E8.6 Remove GPUI from the default build
- AC1 Workspace `default-members` exclude `apps/*`, `rovsti_ui`, `rovsti_wa_ui`, `liquid_glass_kit`; owner decides delete
  vs `legacy/` (recorded); unused `rovsti_core` UI modules (ARCH 6.6 "Not exposed") removed or kept with a reason;
  `rovsti_android` Rust side retired once nothing uses it.
- AC2 All checks green without the GPUI dependency (`cargo test --workspace` no longer fetches gpui-fusion).

### E8.7 Owner device acceptance
- AC0 Ordering: the Kotlin apps are installed as release-signed updates on the owner's phone, the checklist is signed off, and only then E8.6 deletes or moves the GPUI code (the GPUI apps stay the rollback until then).
- AC1 One checklist per app in `docs/kotlin-ui/acceptance.md` covering what cannot be verified here: cross-app handoff (same signer, HANDOFF), permission revocation rows (ARCH 7.5), Turkish-vs-English decision (ARCH 12),  Liquid glass look,
  120 Hz, incoming call on the lock screen, SMS role, FCM wake, trip and alarm under Doze, map pan speed, text selection
  toolbar and magnifier on device.

### E8.8 targetSdk 37
- AC1 Behaviour changes of API 37 reviewed against foreground services, exact alarms, full-screen intents, predictive
  back; bump or keep 36 with the reason written in ARCH 3.
