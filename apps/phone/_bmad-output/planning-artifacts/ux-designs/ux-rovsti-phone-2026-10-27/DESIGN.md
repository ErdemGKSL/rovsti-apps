---
name: Rovsti Phone
description: A local, open Android dialer in iOS-26-style liquid glass with real spring motion. Glass floats above calm content; emerald-teal is the only accent.
status: final
created: 2026-10-27
updated: 2026-10-28
sources:
  - apps/phone/_bmad-output/planning-artifacts/prds/prd-rovsti-phone-2026-10-27/prd.md
  - apps/phone/_bmad-output/planning-artifacts/briefs/brief-rovsti-phone-2026-10-27/brief.md
  - apps/phone/_bmad-output/planning-artifacts/gap-analysis-2026-10-28.md
  - .claude/skills/liquid-glass-gpui/SKILL.md
  - crates/rovsti_ui/src/lib.rs
ui-system: liquid_glass_kit (Theme, GlassSpec, motion_tokens, metrics, typography) + rovsti_ui (TabBar, components, Motion, Presses, Entrance)
colors:
  # Neutrals and system hues are the kit's iOS palette (Theme::colors). Restated so devs need no lookup.
  background: '#FFFFFF'
  grouped-background: '#F2F2F7'
  secondary-background: '#FFFFFF'
  label: '#000000'
  secondary-label: '#3C3C43'      # 60% alpha
  tertiary-label: '#3C3C43'       # 30% alpha
  separator: '#3C3C43'            # 29% alpha
  fill: '#787880'                 # 20% alpha
  hairline: '#000000'             # 8% alpha
  glass-fill: '#F5F5F7'           # 40% alpha; the light glass tint
  # Rovsti accent: emerald to teal, taken from apps/phone/assets/app.png. ONE accent, never a second.
  accent: '#08806E'               # text/icon on white >= 4.5:1
  accent-glow: '#14C8A8'          # bright mid of the app icon; backdrop wash and puck glow only, never text
  # Semantic call colours (accessible variants, differ from the kit's raw iOS values on purpose)
  answer: '#248A3D'               # white glyph, 4.4:1
  danger: '#D70015'               # End, Decline, missed rows; white glyph 5.4:1, text on white 5.4:1
  warning: '#B25000'              # unverifiable Xiaomi items; text on white 4.6:1
  background-dark: '#000000'
  grouped-background-dark: '#000000'
  secondary-background-dark: '#1C1C1E'
  label-dark: '#FFFFFF'
  secondary-label-dark: '#EBEBF5' # 70% alpha
  tertiary-label-dark: '#EBEBF5'  # 30% alpha
  separator-dark: '#545458'       # 60% alpha
  fill-dark: '#787880'            # 36% alpha
  hairline-dark: '#FFFFFF'        # 12% alpha
  glass-fill-dark: '#FFFFFF'      # 15% alpha; dark glass is a lift, not smoke
  accent-dark: '#2DD4B4'          # on black 11:1
  accent-glow-dark: '#14C8A8'
  answer-dark: '#30D158'          # black glyph on it, 9:1
  danger-dark: '#FF453A'          # white glyph 3.4:1 (large control, non-text 3:1 rule); text on black 6.2:1
  warning-dark: '#FF9F0A'
typography:
  # Font: system UI font on Android (Roboto / MiSans on Xiaomi); SF Pro metrics from the kit. Sizes are sp (scale with system font size).
  large-title: { fontSize: 34px, lineHeight: 41px, fontWeight: 800, letterSpacing: -0.8px }
  title: { fontSize: 28px, lineHeight: 34px, fontWeight: 800, letterSpacing: -0.7px }
  title2: { fontSize: 22px, lineHeight: 28px, fontWeight: 700, letterSpacing: -0.4px }
  title3: { fontSize: 20px, lineHeight: 25px, fontWeight: 700, letterSpacing: -0.3px }
  headline: { fontSize: 16px, lineHeight: 21px, fontWeight: 600, letterSpacing: -0.2px }
  body: { fontSize: 16px, lineHeight: 22px, fontWeight: 400, letterSpacing: -0.2px }
  subheadline: { fontSize: 15px, lineHeight: 20px, fontWeight: 400, letterSpacing: -0.24px }
  footnote: { fontSize: 13px, lineHeight: 18px, fontWeight: 400, letterSpacing: -0.08px }
  caption: { fontSize: 12px, lineHeight: 16px, fontWeight: 400 }
  caption2: { fontSize: 11px, lineHeight: 13px, fontWeight: 600, letterSpacing: 0.06px }
  # App-specific roles built on the kit ramp
  dial-number: { fontSize: 36px, lineHeight: 44px, fontWeight: 300, note: 'Keypad number field. Shrinks in steps to 24px past 12 characters; never wraps.' }
  key-digit: { fontSize: 32px, lineHeight: 36px, fontWeight: 300 }
  incall-name: { fontSize: 34px, lineHeight: 41px, fontWeight: 700, note: 'Same as large-title size; one line, truncates in the middle for numbers' }
  incall-status: { fontSize: 17px, lineHeight: 22px, fontWeight: 400, note: 'Timer uses tabular figures so it never jitters' }
rounded:
  icon-tile: 7px
  button: 12px
  menu: 14px
  card: 20px
  grouped-list: 26px
  sheet: 38px
  full: 9999px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 24px
  '6': 32px
  '7': 48px
  row-inset: 16px
  tab-bar-margin: 12px
  tab-bar-height: 56px
  tab-bar-content-inset: 80px      # tab bar 56 + 2 x 12, plus the system bottom inset
  min-hit: 44px
  key-size: 78px
  key-gap: 24px
  incall-control-size: 76px
  # v0.2 (Epics 14-21)
  route-row: 52px
  menu-row: 48px
  notice-height: 48px
  action-tile: 64px                # Contact Sheet / Call details action strip
  secondary-incoming-size: 56px    # Message and Silence on the Incoming screen
  chip-height: 20px                # SIM, HD, Wi-Fi chips
  incall-chip-row: 22px            # reserved row under the number line, always present
  incall-zone: 'flex between the 64px top gap and the control grid (min 300px)'
motion:
  note: 'Springs from liquid_glass_kit::motion_tokens, (damping ratio, stiffness). Values are the contract; do not substitute durations.'
  select: { zeta: 0.76, stiffness: 750 }
  press: { zeta: 0.75, stiffness: 1220 }
  release: { zeta: 0.6, stiffness: 420 }
  appear: { zeta: 1.0, stiffness: 450 }
  morph: { zeta: 1.0, stiffness: 950 }
  sheet-snap: { zeta: 0.86, stiffness: 380 }
  sheet-dismiss: { zeta: 1.0, stiffness: 400 }
  glow: { zeta: 0.5, stiffness: 300 }
  theme-fade: { zeta: 1.0, stiffness: 600 }
  press-scale: 0.97
  # v0.2 adds no new spring. Everything below reuses the tokens above; gesture thresholds:
  commit-distance: 96px            # drag distance that commits answer / decline / reply
  arm-hold: 450ms                  # speed-dial and drag-to-reorder arm time (existing long-press)
  press-scale-card: 0.94
  puck-pressed: 1.39
components:
  tab-bar:
    backgroundColor: '{colors.glass-fill}'
    rounded: '{rounded.full}'
    height: '{spacing.tab-bar-height}'
    width: 264px
    marginBottom: '{spacing.tab-bar-margin}'
    glass: chrome
  tab-puck:
    rounded: '{rounded.full}'
    glass: chrome, lerps to lens under press
    iconSelected: '{colors.accent}'
    iconUnselected: '{colors.label}'
  key:
    size: '{spacing.key-size}'
    rounded: '{rounded.full}'
    glass: control
    digit: '{typography.key-digit}'
    caption: '{typography.caption2}'
  call-button:
    size: '{spacing.key-size}'
    rounded: '{rounded.full}'
    backgroundColor: '{colors.answer}'
  incall-control:
    size: '{spacing.incall-control-size}'
    rounded: '{rounded.full}'
    glass: control
  end-button:
    backgroundColor: '{colors.danger}'
    rounded: '{rounded.full}'
    height: 64px
  setup-card:
    rounded: '{rounded.card}'
    glass: sheet
    padding: '{spacing.4}'
  sheet:
    rounded: '{rounded.sheet}'
    glass: sheet
  row:
    minHeight: 58px
    paddingX: '{spacing.row-inset}'
  favorite-tile:
    rounded: '{rounded.card}'
    glass: control
  search-field:
    rounded: '{rounded.full}'
    height: 44px
    glass: control
  banner:
    rounded: '{rounded.full}'
    height: 44px
    glass: control
  route-popover:
    rounded: '{rounded.card}'
    width: 288px
    glass: sheet
    rowHeight: '{spacing.route-row}'
  notice-pill:
    rounded: '{rounded.full}'
    height: '{spacing.notice-height}'
    glass: control-flat
  action-tile:
    size: '{spacing.action-tile}'
    rounded: '{rounded.card}'
    glass: control-flat
  chip:
    height: '{spacing.chip-height}'
    rounded: '{rounded.full}'
    typography: '{typography.caption2}'
  toggle:
    width: 51px
    height: 31px
    rounded: '{rounded.full}'
    onColor: '{colors.accent}'
  segmented:
    height: 36px
    rounded: '{rounded.full}'
    glass: control-flat
  held-card:
    height: 64px
    rounded: '{rounded.full}'
    glass: control-flat
  waiting-card:
    rounded: '{rounded.sheet}'
    glass: sheet-flat
  conference-panel:
    rounded: '{rounded.grouped-list}'
    glass: control-flat
  settings-group:
    rounded: '{rounded.grouped-list}'
    backgroundColor: '{colors.secondary-background}'
  t9-strip:
    height: 56px
    slot: 'the large-title slot of the Keypad'
---

## Brand & Style

Rovsti Phone looks like a phone made of water. Content is calm and typographic: big titles, plain rows, a soft emerald wash on the background. Everything that floats (tab bar, keys, in-call controls, sheets, setup card, banners) is glass that bends the content behind it. Nothing is decorative: glass marks "this is a control or a layer above the page".

It moves like iOS 26: every thing the finger can interrupt is a spring with a little overshoot, and motion carries meaning (the puck travels to the tab you tapped, a key presses in and springs back, a number shakes to say no). The app is private, local and a bit opinionated; it never uses promotional colour, badges for engagement, or celebratory animation. The one emotional beat is reliability: when setup is complete the checklist folds away quietly.

The brand anchor is the app icon (`apps/phone/assets/app.png`): a glass handset on an emerald-to-teal field. The accent and the background wash are sampled from it.

Posture: consumer, personal, high trust. A missed visual flourish is fine; a late or unreadable Answer button is not (PRD SM-C1). Where polish and speed conflict, speed wins.

## Colors

Neutrals, labels, separators and fills are the kit's iOS system palette, light and dark (the table is in the frontmatter). The theme follows the system (`FOLLOW_SYSTEM_THEME = true`) and cross-fades with the `theme-fade` spring; colours cross-fade in RGB with `tokens::mix`, never in HSL.

- **Accent `{colors.accent}` / `{colors.accent-dark}`.** The single accent. Used for: the selected tab icon, text links and the "Set up" actions in the Setup Checklist, the star when a contact is starred, the active state of in-call toggles (Mute on, Speaker on, Hold on, tinted ring and icon, not filled), and the focus ring. Never for body text, never for decoration, never for destructive things.
- **Accent glow `{colors.accent-glow}`.** The bright teal of the icon. Only used (a) as the colour wash behind the page (`backdrop`: 160 degree gradient, 0.18 alpha light / 0.28 alpha dark, fading to transparent by 55% of the height), (b) as the puck's press glow. Never as a text or icon colour (contrast).
- **Answer `{colors.answer}`.** The Call button on the keypad and the Answer button on incoming calls. White glyph in light mode; black glyph on `{colors.answer-dark}` in dark mode.
- **Danger `{colors.danger}`.** End, Decline, missed-call rows (name in danger colour, as in iOS), the missed badge, and permission-denied error text. Filled danger buttons carry a white glyph.
- **Warning `{colors.warning}`.** Only the "Needs your confirmation" (unverifiable) state of a Xiaomi Setting and the preview-mode banner icon.
- **Label tiers.** `label` for names and numbers; `secondary-label` for subtitles, timestamps, captions on keys; `tertiary-label` for disabled and placeholder. Text on glass is always `label` or `secondary-label`, never coloured, except the selected-tab icon.

Avoid: a second accent, gradients on text, black "smoke" glass in dark mode (dark glass is a white 0.15 lift), coloured text on glass (except the selected tab icon), red used for anything but destructive or missed.

The page backdrop matters: glass over a flat fill reads as a grey plate. Every screen sits on `components::backdrop(theme, accent_glow)` so the glass has colour to refract. Over the lock screen and over an Android system surface the backdrop is the same wash on `{colors.background}`.

## Typography

System UI font on Android, kit ramp (frontmatter) for sizes and weights; all sizes are sp, so they scale with the system font size. The layout must hold up to 1.3x (PRD NFR-10): rows grow in height, in-call controls keep their size and move their labels onto two lines, nothing clips.

Roles:
- `large-title`: the tab title at the top of each tab (Favorites, Recents, Contacts, Keypad). 56px from the top inset to the cap, 16px side inset.
- `headline`: row names, button labels, sheet titles.
- `subheadline`: row subtitles (number label and type, call type and time), list section letters use `footnote` in `secondary-label` with weight 600.
- `footnote` / `caption`: status lines, setup reasons, counts.
- `dial-number`: weight 300, tabular figures, 36px, stepping down 36 / 32 / 28 / 24 as the number grows (at 11, 14, 18 characters), centered. The last character pops (scale 1.18, `release` spring).
- `incall-name`: 34px, weight 700, one line. Contact names truncate at the end; unsaved numbers truncate in the middle.
- `incall-status`: 17px, secondary-label, tabular figures for the timer (`m:ss`, `h:mm:ss`).
- Key letters use `caption2`, tracking +0.06, in `secondary-label`.

Numbers are LTR; names follow the string's own direction. English only in MVP.

## Layout & Spacing

Reference canvas is 402 x 874 dp (the desktop preview window), portrait only. All layouts use flex; none use fixed y offsets except the keypad key grid, which is a fixed 78 px key with 24 px gaps (3 x 78 + 2 x 24 = 282 px wide, centered). Narrower phones (360 dp) keep keys at 78 px and reduce the gap to 16 px; there is no landscape.

Scale: 4 / 8 / 12 / 16 / 24 / 32 / 48. Side inset 16. Row height 58 with subtitle (44 without). Section spacing 35.

Safe areas: the top inset (status bar / cutout) is added to the 56 px title offset; the bottom inset (gesture bar) is added to the tab bar margin. The tab bar floats 12 px above the bottom inset and does not reserve layout space; every scrolling page has `content-bottom-inset` (80 px plus the bottom inset) so the last row can scroll clear of it.

The Tab Bar is 264 x 56, centered horizontally. Over the Keypad tab the Call row sits at least 12 px above the bar.

In-call screen vertical rhythm (top to bottom): top inset, 64 px gap, avatar 96 (or none), 16, name, 4, status, flexible space, control grid (3 columns x 2 rows of 76 px controls, 24 px gaps), 32, End button (64 high, 220 wide), bottom inset + 24. No tab bar on the in-call or incoming screens.

**In-Call layout, v0.2.** Everything above the control grid lives in the *in-call zone*, the flexible space between the 64 px top gap and the grid (392 px on 402 x 874). Its content changes by state, its height never does, and the grid and End stay anchored to the bottom, so nothing below the zone moves:
- *One call:* avatar 96, name, status, number line, chip row (22 px reserved): about 222 px.
- *Two calls:* active block (avatar 72, name at 28 px, timer), chip row, then the Held card (64 px) with 12 px above it.
- *Conference:* avatar stack 56, "Conference" `title3`, timer, then the participant panel (156 px window).
- The Waiting card and the route popover are overlays and do not take zone space.
- Zone height on 402 x 874: 874 - top inset 48 - gap 64 - grid 216 - 32 - End 64 - 24 - bottom inset 34 = 392 px. The tallest state (conference: stack 56 + title 28 + timer 22 + 12 + panel 156 = about 290) and the two-call state (about 250) both fit with slack. Below 760 dp tall, the chip row and number line merge into one line and the zone scrolls vertically (the grid and End stay pinned).

**Settings and Call details** are full-height pages (no Tab Bar): top inset, 44 px Back button row, `large-title`, content scrolling with `content-bottom-inset` of just the bottom inset + 24. **Incoming v0.2:** secondary row (56 px circles) sits 24 px above the Decline/Answer row; the card (avatar, name, number) is draggable within a 96 px commit distance and never changes its resting position.

Touch targets: at least 44 x 44; keys and in-call controls are 76 to 78. Tab hit areas are 64 x 56, wider than the 22 px icon.

## Elevation & Depth

Depth comes from glass, not from drop shadows on content. Three levels:
1. **Content**: backdrop wash, rows, titles. No shadow.
2. **Chrome glass**: tab bar, in-call controls, search field, banners (refractive, within the budget); keys and Favorites tiles (flat glass look at rest, refractive only while pressed). `GlassSpec::chrome` for bar and floating buttons, `GlassSpec::control` for keys, controls, fields. Shadow `0 4 16` black 12% (light) / `0 8 24` black 35% (dark), via `shadow::glass`.
3. **Sheet glass**: Contact Sheet, number sheet, number chooser, Setup Checklist card. `GlassSpec::sheet` (blur 12 light / 14 dark, for legibility), shadow blur 32 black 10 to 15%.

Glass recipe, fixed: blur 2 px on chrome and control (never heavy), edge-band refraction, saturation 1.5, symmetric dark outline 1.6 px centred on the edge, specular rim 0.8 px from the top-left (225 degrees). Glass paints in `on_paint_before_children`, labels paint after. **Refractive surfaces are budgeted, see Performance Budget**: at most 4 refractive (backdrop-sampling) surfaces on screen at once. Anything beyond that is a *flat glass look* (no shader): a `fill` or `material::THIN` tint, the same outline and rim, no backdrop sample.

Flat variants (`control-flat`, `sheet-flat`) exist for v0.2 surfaces that must not sample the backdrop; see v0.2 components.

Fallback: when `!window.supports_backdrop_blur()` or the user's system turns on reduced transparency, surfaces use `paint_glass_fallback`: an opaque-ish material (`material::THICK`, 0.85 alpha) over the plain backdrop with the same outline and rim. All text contrast values assume the fallback as the worst case.

Press glow: on a pressed surface the lens preset blends in (`GlassSpec::control.lerp(lens, press * 0.6)`), the surface scales to 0.94 (keys, tiles) or 0.97 (bars, rows), with the glow following the finger on the `glow` spring.

## Shapes

All floating controls are fully rounded: tab bar, puck, keys, call buttons, in-call controls, search field, banners, pills. Surfaces that hold content use circular-arc radii from the kit: card 20 (Setup Checklist, Favorites tiles), grouped list 26 (Setup item groups, Contact Sheet number group), sheet 38 (top corners of sheets and the incoming card). Nested shapes are concentric: inner radius = outer radius minus the inset, floored at 4 (puck 24 inside a 28 bar). Apple corners are squircles; GPUI quads are arcs, so cards get the radius plus about 10% where a nested shape would otherwise show a gap.

Avatars are circles (monogram on a tint, or the contact photo cropped to a circle). Favorite tiles are 20 px rounded squares with the avatar circle inside.

## Icons

Lucide, through `gpui_icons::GpuiIcons` (typed enum, verified present at rev d6c8412). Stroke style inherits from Lucide (2 px at 24, round caps and joins). Icons are monochrome and take the colour of the text role they sit in. Sizes: tab 22 (grows to about 25 under the puck), row accessories 16, in-call controls 28, call and end glyph 30, sheet actions 20.

| Use | Icon |
| --- | --- |
| Tab Favorites | `LucideStar` |
| Tab Recents | `LucideClock` |
| Tab Contacts | `LucideUsers` |
| Tab Keypad | `LucideGrid3x3` |
| Call (keypad, number rows, Answer) | `LucidePhone` |
| End / Decline | `LucidePhoneOff` |
| Outgoing row | `LucidePhoneOutgoing` (secondary-label) |
| Incoming row | `LucidePhoneIncoming` (secondary-label) |
| Missed row | `LucidePhoneMissed` (danger) |
| Declined row | `LucidePhoneOff` (secondary-label, 16) |
| Mute off / on | `LucideMic` / `LucideMicOff` |
| Speaker off / on | `LucideVolume2` (on = accent tint) |
| In-call Keypad | `LucideGrid3x3` |
| Hold / Resume | `LucidePause` / `LucidePlay` |
| Delete digit | `LucideDelete` |
| Paste | `LucideClipboardPaste` |
| Search field | `LucideSearch` |
| Info (row) | `LucideInfo` |
| Star on / off | `LucideStar` (accent, filled via fill token) / outline |
| Headset route | `LucideHeadphones`, `LucideBluetooth` |
| Setup item done | `LucideCircleCheck` (accent) |
| Setup item not done | open circle (drawn quad, 22 px, 1.5 px `secondary-label` stroke) |
| Setup item unverifiable | `LucideCircleAlert` (warning) |
| Permission denied / error | `LucideTriangleAlert` (danger) |
| Add to contacts | `LucideUserPlus` |
| Edit in Contacts | `LucidePencil`, trailing `LucideExternalLink` |
| Disclosure | `LucideChevronRight`, `LucideChevronDown` |
| Lock-screen note | `LucideLock` |
| Preview-mode banner | `LucideSmartphone` |
| Route: Phone (earpiece) | `LucideSmartphone` |
| Route: Speaker | `LucideVolume2` |
| Route: wired headset | `LucideHeadphones` |
| Route: Bluetooth device | `LucideBluetooth` (`LucideBluetoothConnected` when it is the current route) |
| Route: hearing aid (best effort, name says so) | `LucideEar` |
| Route selected | `LucideCheck` (accent) in a 24 px circle |
| Audio control (summary) | the current route's icon above |
| Add call | `LucideCirclePlus` |
| Swap | `LucideArrowUpDown` |
| Merge | `LucideMerge` |
| Split from conference | `LucideSplit` |
| End one participant | `LucideUserMinus` (danger) |
| Conference | `LucideUsers` |
| Settings gear | `LucideSettings` |
| Back | `LucideChevronLeft` |
| SIM chip glyph | `LucideCardSim` (only in Settings; chips use text) |
| Block / blocked | `LucideBan` (danger only on destructive actions, otherwise `label`) |
| Message | `LucideMessageSquare` |
| Silence ringer / silenced | `LucideBellOff` |
| Copy | `LucideCopy` |
| Share | `LucideShare2` |
| Delete (contact, history) | `LucideTrash2` (danger) |
| Undo | text button "Undo", no icon |
| Voicemail | `LucideVoicemail` |
| Emergency | `LucideSiren` |
| Reorder grip (a11y action only) | `LucideGripVertical` |
| Export / import | `LucideUpload` / `LucideDownload` |
| Phone forwarding (system shortcut) | `LucidePhoneForwarded` |
| Hearing-aid compatibility | `LucideEar` |
| Wi-Fi calling chip | `LucideWifi` 12 |
| Vibrate settings | `LucideVibrate` |
| Quick reply | `LucideMessageCircle` |

> `LucideDownload`, `LucideUpload`, `LucideBan`, `LucideEar`, `LucideMerge`, `LucideSplit`, `LucideCardSim`, `LucideVoicemail`, `LucideSiren`, `LucideVibrate`, `LucidePhoneForwarded` were checked in the pinned `gpui_icons` asset tree (groups core, common, connectivity, editing, media, misc). `LucideFileDown` lives in the `files` group, which the workspace does not enable: do not use it.

Every icon-only control keeps its meaning in the accessibility label (EXPERIENCE.md Accessibility Floor); the Tab Bar sets `aria_label` per tab and `Role::Tab`.

## Motion

All motion is the kit's analytic springs: interruptible (retarget keeps velocity), never fixed-duration tweens (except the optional bezier fades noted). One `Motion` per window ticks every spring with the same `dt` and requests frames only while something moves; it stops when all springs settle. Under reduced motion every spring snaps to its target (the `Motion::frame` implementation feeds `dt` = 1 s).

| Moment | Spring (token) | What moves |
| --- | --- | --- |
| Tab switch | `select` for the puck; `appear` for the page | Puck travels, stretches along the path (squash and stretch, X looser than Y), lens-grows while the finger is down; the new page fades from 0 and lifts 24 px to 0. A second tap mid-flight retargets with velocity kept. |
| Key / button press | `press` down, `release` up | Scale to 0.94 and lens glow; releases with one visible overshoot. Release is wired to the root's mouse-up so a finger that slides off still lets go. |
| Digit entered | `release` | Newest digit pops to 1.18 and settles to 1.0. |
| Delete control | `appear` | Fades in and slides 12 px as the first digit arrives; reverses when empty. |
| Invalid number | `glow` (kick -1400 px/s) | The number field shakes sideways and settles; no sound. |
| Row press | `press` | Row scales 0.97 and tints with `fill`; release `release`. |
| Sheet (Contact, number, chooser) | `appear` open; `sheet_snap` after a drag; `sheet_dismiss` | Rises from the bottom with the page behind dimming to 0.85 and shrinking to 0.97; drag-down dismisses with fling projection and rubber-banding above the top detent. |
| Setup card collapse | `morph` | The card morphs into the "Setup complete" row: width, height and corner radius interpolate on one spring (critically damped), its content cross-fades. Expanding is the reverse. |
| Incoming Call Screen enter | `appear` | Card lifts 24 px and fades; Answer / Decline spring in 80 ms apart. Full spec in Signature Moments S7 (aura bloom, avatar drop-in, sonar rings). |
| Answer → In-Call transition | `morph` | The Answer circle's position anchors the new controls: the Decline button fades, the End button grows in from the Answer's position as the control grid appears. |
| In-call toggle | `press`, `select` | Mute / Speaker / Hold: press scale, then the tint fills with `select` (slight overshoot) and the icon cross-fades. |
| "Call ended" | `appear` | Status text swaps; the controls fade to 0.4 and the screen dismisses after about 1 s. |
| Waiting Call banner | `appear` | Slides down from under the status bar; dismisses upward. |
| Theme change | `theme-fade` (ζ 1, k 600) | One `dark_t` spring cross-fades colours (RGB mix), glass tint and shadows together, about 260 ms. |
| Missed badge | `release` | Pops to 1.25 once when its count increases. |
| Empty / loading | `appear` | Skeleton rows fade in 0 to 1 over the spring; no shimmer loop. |

Rules: no more than two things animate for attention at once; nothing loops except the Call aura and the incoming rings (see Signature Moments); no animation delays an Answer, Decline or End action by even one frame, because every press effect runs alongside the action and never before it. Angular loading spinners are not used.

Reduced motion (system setting `prefers_reduced_motion`): all springs snap, the puck jumps, sheets appear and vanish with a plain 120 ms opacity fade, shakes are replaced by the status text "Not a valid number", and the aura and sonar rings are static (see Signature Moments for each moment's fallback).

## Signature Moments

Distinctive on purpose, cheap by construction. Every moment below is built only from transforms, opacity and colour mixes of already-painted quads (radial-gradient quads, rings, dots), never from an extra refractive pass, blur change or layout spring. Springs are tokens from Motion unless a (ζ, k) pair is given. Each moment states its trigger, motion, cost class (C = cheap transform/opacity; M = moderate, a few quads; none are costly) and reduced-motion fallback. Max two signature moments animate at once per screen.

**Palette for atmosphere.** `{colors.accent-glow}` is the base. Each contact has a **hue pair** from a stable hash of the contact id over six muted pairs (emerald-teal, teal-sky, sky-indigo, violet-rose, amber-coral, lime-emerald), light and dark variants at 0.18 and 0.28 alpha. Unknown numbers use the base pair on grey. Avatars, auras and the Incoming Call card use the contact's pair, so each person has a recognisable atmosphere.

**Expressive avatars.** Monogram avatar = a 135 degree linear gradient of the contact's hue pair with the initial in white `headline`-weight, and a 1.5 px inner rim (white 0.35 alpha top-left). Photo avatars keep the same rim. Unknown: `fill` circle with `LucideUser`. In-call and incoming avatars (96 and 120 px) add a **state ring**, 3 px, drawn inside the rim: Dialing = accent-glow at 60% alpha, Active = hue pair top colour, Holding = `{colors.warning}`, Muted = ring drawn as a dashed arc segmentation (8 segments), Ending/failed = `{colors.danger}`. Ring colour changes cross-fade with `select`.

| # | Moment | Trigger | Motion spec | Cost | Reduced motion |
| --- | --- | --- | --- | --- | --- |
| S1 | **Drop key** (keypad key press) | Finger down on a key | (a) key presses to 0.94 on `press`, the flat glass swaps to refractive `control` + `lens` for the held key only; (b) a **ripple ring** (2 px, white 0.5 alpha, key's diameter) scales 1.0 to 1.5 and fades 0.5 to 0 over 380 ms with bezier [0.0, 0.0, 0.2, 1.0] (kit RELEASE_RING); (c) the digit **drops** into the field: a copy of the digit starts at the key centre (scale 1.0, opacity 0.9) and travels to the field's last-digit position on `appear` (ζ 1, k 450, about 220 ms) while fading to 0, as the real digit pops in at 1.18 on `release`. Up to 3 drops in flight; a faster typist just sees overlapping drops. | C | Press scale only; no ripple, no drop; digit appears instantly |
| S2 | **Liquid number field** | Digit appended / removed | New digit pop 1.18 to 1.0 (`release`). Delete: the last digit shrinks to 0.6 and fades over `morph` (ζ 1, k 950) while the remaining digits ease left by half a glyph width on the same spring (only the last glyph's x moves; the string re-lays out once at the end). Font step-down at 11/14/18 characters cross-fades size over `morph`. | C | Instant |
| S3 | **Call blooms** (Call button) | Tap Call with a valid string | The solid answer circle pulses to 0.94 (`press`), a ring of `{colors.answer}` expands 1.0 to 2.2 and fades (460 ms, RELEASE_RING bezier); on the first frame the In-Call Screen is ready, the Call circle **becomes the End button** (shared element, `morph`: it slides to the End button's position, widens from 78 to 220 px, colour mixes answer to danger, glyph cross-fades phone to phone-off). If the Call screen is not ready within 300 ms, the circle stays pressed and the status line shows "Calling…"; the morph runs when the screen is ready. | M (1 ring + 1 shape) | Instant swap, no ring |
| S4 | **Shared-element avatar** (tile or row to In-Call) | Tap a Favorites tile or Recents row that places a call | The tile's avatar lifts (scale 1.08, `press`) then travels from its on-screen position to the In-Call avatar slot while scaling to 96 px on `morph` (ζ 1, k 950, about 250 ms); the rest of the In-Call Screen fades in around it on `appear`. The same avatar flies back on End into the row/tile if it is still on screen, else just fades. | C | Cross-fade 120 ms |
| S5 | **Tab flow** | Tab selected | Puck travels with squash and stretch (`select`, X/Y springs ζ 0.6/0.7 k 250); the new page enters **from the direction of travel**: translate x 28 px toward its resting place (sign = new index minus old) with fade, on `appear`; the old page does not animate (it is already gone); the selected icon **hops**: scale 1.0 to 1.12 on `press`, settling on `release`. Title slides 12 px extra on the same spring for a parallax feel. | C | Instant swap with 120 ms fade, puck jumps |
| S6 | **Call aura** (in-call living gradient) | In-Call Screen visible | Behind the avatar: **three radial-gradient blobs** (diameter about 320, 260, 220 px, centred on the avatar, offsets of 36 px) in the contact's hue pair plus accent-glow, painted as plain quads. One shared phase value drives slow drift: each blob orbits its centre on a 7 s, 9 s and 11 s loop (position only, plus 0.96 to 1.04 scale on the same phase). State drives colour and energy through springs: Dialing = base hue, pulse 0.8 Hz amplitude 6% scale (a "searching" breathing); Active = calm drift, full pair; first answer = one **connect pulse** (scale 1.12, `glow` ζ 0.5 k 300, glow alpha +0.15 then back); Holding = saturation mixes toward grey, `{colors.warning}` tint at 25%, drift slows to 40% speed (`select`); Muted = alpha 60% and drift slows to 60%; Speaker on = blobs widen 1.15 (`select`); Waiting Call = one soft double-pulse; Disconnected = blobs collapse to the avatar (scale 0.6, alpha 0) on `sheet_dismiss` (ζ 1, k 400), tinted `{colors.danger}` for a failed call. **It does not react to audio level** (no audio data in the UI). Drift loops only while the In-Call Screen is visible and the screen is on; it pauses with the proximity screen-off and in the background. | M (3 gradient quads, transform only, one phase) | Static blobs at base positions; state changes cross-fade colour only (no pulses, no drift) |
| S7 | **Incoming entrance** | Incoming Call Screen appears | Within the first frame the wash and the caller name are visible (never blank). Then: aura blobs **bloom** from scale 0.6 and alpha 0 to 1 on `appear`; the avatar **drops in** from 40 px above with a visible overshoot on `release` (ζ 0.6, k 420); name and number fade up 12 px on `appear`; Decline and Answer spring in staggered 80 ms apart (Decline first, Answer second) from scale 0.8 on `select`. While ringing: two **sonar rings** (3 px, hue top colour, alpha 0.35 to 0) expand from the avatar from scale 1.0 to 1.9 every 1.6 s, 0.8 s apart; the avatar breathes 1.0 to 1.04 at 0.5 Hz; Answer carries a slow glow pulse (alpha 0.0 to 0.25, 1.2 s). It stops on answer, decline or remote end. | M (aura 3 quads, 2 rings, 1 pulse) | Static blobs and a single static ring; no drop-in, buttons appear with a 120 ms fade |
| S8 | **Answer morph** | Tap Answer | Answer circle presses (`press`), Decline fades to 0 (120 ms), the Answer circle becomes the End button (`morph`, as S3), the avatar scales from 120 to 96 and moves to the In-Call slot (`morph`), the aura's connect pulse (S6) plays, the timer text starts. Total about 300 ms, no gating the call: the Call is already answered when the finger lifts. | M | Cross-fade 120 ms |
| S9 | **Timer lands** | Call becomes Active | "Calling…" slides up 8 px and fades while the timer slides in from 8 px below, `appear`; the avatar state ring flips from accent-glow to the hue colour on `select`. A light haptic. | C | Instant swap |
| S10 | **Control tint flood** | Mute / Speaker / Hold toggled | The control's glass tint fills from the centre: a circle of accent at 20% alpha scales 0 to 1 inside the control on `select` (ζ 0.76, k 750) while the icon cross-fades; releasing reverses it. The icon performs a 6 degree tilt wobble on `glow` (ζ 0.5, k 300) on activation. | C | Instant tint |
| S11 | **Call ended collapse** | Disconnected | Controls dim to 0.4 (`appear`), the aura collapses (S6), the avatar scales to 0.9 and fades to 0.6 on `sheet_dismiss`, the End button fades; the status "Call ended" holds about 1 s (2 s for a failed call); then the whole screen fades out on `appear` as the tab page fades in from below (the avatar returns to its tile/row if visible, S4). | C | Fade only |
| S12 | **Star burst** | Star toggled on | The star scales 1.35 to 1.0 on `release` while 6 tiny dots (4 px, accent) fly 24 px outward and fade over 400 ms (RELEASE_RING bezier); off: star scales to 0.85 and back (`press`), no dots. Medium haptic. | C | Instant fill |
| S13 | **Badge pop** | Missed count increases | Badge scales 1.0 to 1.25 on `release` and the Recents icon wiggles 8 degrees on `glow` once. | C | Instant |
| S14 | **Setup complete** | Last Setup item turns done | The card **morphs** into the "Setup complete" row on `morph`; its check icon pops 0.6 to 1.0 on `release`; the page wash brightens (accent-glow alpha +0.10) for 600 ms on `appear` then eases back. Plays once per completion, never on later launches. | M (1 shape morph) | Instant collapse, no brighten |
| S15 | **Theme melt** | System theme changes | One `theme-fade` spring (ζ 1, k 600, about 260 ms) crossfades colours, glass tint, shadow, aura palette. | C | Instant snap |
| S16 | **Sheet rise** | Contact Sheet opens | Sheet rises on `appear` (ζ 1, k 450) with the page behind scaling to 0.97 and dimming to 0.85; the avatar inside the sheet scales 0.8 to 1.0 on `release` 60 ms later; number rows fade up 8 px staggered 30 ms. | C | 120 ms fade |

### v0.2 Signature Moments (Epics 14 to 21)

Same rules as above: transforms, opacity and colour mixes of already-painted quads, springs from the existing tokens only (no new spring), cost class C or M, a reduced-motion fallback, and at most two moments animating at once per screen. **Glass rule for scaled surfaces:** a refractive surface that changes size or scale during a spring (route popover, reply sheet while it opens) paints as the *flat glass look* until the spring is within 2% of rest, then swaps to the refractive pass in one frame. Cost stays cheap and the budget count is unchanged.

| # | Moment | Trigger | Motion spec | Cost | Reduced motion |
| --- | --- | --- | --- | --- | --- |
| S17 | **Route bloom and handoff** (Epic 14) | Audio control opens the route list; a row is chosen | Open: the popover scales 0.86 to 1.0 from the Audio control's top-centre (its transform origin) on `appear` (ζ 1, k 450) while rows rise 8 px staggered 24 ms; the Audio control presses to 0.97 and stays tinted while the list is open. Choose: the `LucideCheck` circle of the chosen row travels (`morph`, ζ 1, k 950) to the Audio control, whose icon cross-fades to the new route icon and whose label slides 6 px; the popover dismisses on `sheet_dismiss` (ζ 1, k 400) at the same time; a Bluetooth or wired choice adds one ripple ring (accent, RELEASE_RING) around the control. The control shows the pending look until Android reports the route (see EXPERIENCE State Patterns) | M (1 popover, 1 shared element, 1 ring) | 120 ms fade open and close; check and icon swap instantly; no ring |
| S18 | **Device arrival** | An endpoint appears while the list is open or the Audio control is visible | Open list: the new row fades in at the top edge on `appear` and its icon tile gets one halo ring (accent-glow 0.5 alpha, scale 1.0 to 1.6, 380 ms RELEASE_RING). Closed list: the Audio control's route icon is unchanged but a 6 px accent dot fades in at its top-right for 3 s ("a new device is available"); it never changes the selected route. Departure: the row dims to 0.4 in place with the subtitle "Disconnected"; if it was the current route the check moves to the fallback row on `select` | C | Instant; dot has no fade |
| S19 | **Hold-away** (second call placed) | Add call places a second call, or a second call becomes held | The first call's avatar, name and timer shrink (96 to 40, name 34 to 17 px) and travel on `morph` into the Held card slot while the new call's avatar drops in at the active slot with overshoot on `release` (ζ 0.6, k 420); aura: the first call's blobs desaturate to the Holding look, the new call's pair blooms from 0.6 scale on `appear` | M | 120 ms cross-fade, no travel |
| S20 | **Swap crossing** | Swap control or tap on the Held card | The two calls exchange places along arcs: the active block moves down and shrinks, the held block moves up and grows, each with a 12 px sideways offset that peaks mid-flight (sin curve), on one `morph` spring; timers and avatars are the same quads (shared elements), aura hue cross-fades on `select`; one medium haptic | C | 120 ms cross-fade |
| S21 | **Merge weave** | Merge succeeds (reported conference state) | The Held card's avatar travels to the active avatar and stops 28 px offset, overlapping; a third avatar overlaps on later joins (stack of up to 3, then "+n"); aura blobs of the two hue pairs mix in RGB over `select` with one `glow` pulse (scale 1.12, ζ 0.5, k 300); the participant panel rises from the bottom of the zone on `appear` and rows fade up staggered 30 ms (max 6 staggered). Plays only after Android reports the conference, never on tap | M | Instant swap; no pulse |
| S22 | **Participant eject** | Split or End on a participant row | Split: the row's avatar scales 1.15 and flies up to the active slot on `morph` as the split call's avatar (the remaining conference stack shrinks by one on the same spring). End: the row content fades and shifts 24 px sideways in 120 ms (`appear`), then the list re-lays instantly (no layout spring) | C | Fade only |
| S23 | **Waiting card drop** | A second call rings during a call | The Waiting card falls from 40 px above the top inset with a visible overshoot on `release` (ζ 0.6, k 420); its four choice pills spring in staggered 40 ms on `select`; the call underneath dims to 0.85 and the aura plays its double-pulse (S6 waiting); the card is tappable from the first frame | C | 120 ms fade |
| S24 | **Gear turn and page push** | Gear button tapped; Settings and sub-pages open | The gear rotates 60 degrees on `glow` (ζ 0.5, k 300) while held and settles back; the Settings page enters from the right: translate x 28 px to 0 with fade on `appear` (same direction logic as S5, title parallax 12 px extra), the old page does not animate; Back reverses with the page leaving toward the right; the tab bar fades out over `appear` | C | 120 ms fade, no rotation |
| S25 | **Toggle snap** | Any Settings toggle | Finger down: the thumb stretches to 1.2 x width on `press`; finger up: it travels to the other end on `select` (ζ 0.76, k 750) with the slight overshoot; the track mixes grey to `{colors.accent}` in RGB at the same time; light haptic (follows the haptics setting). Segmented controls move their glass indicator with the same `select` spring | C | Thumb and track jump |
| S26 | **SIM chip flight** | A SIM is chosen in the chooser | The chosen row's colour dot and "SIM n" label (a chip) travel to the reserved chip slot on the In-Call Screen on `morph`, or just fade if the screen is not ready; the other row fades out with the chooser (`sheet_dismiss`) | C | Cross-fade |
| S27 | **Block sweep** | Block confirmed from any surface | A `LucideBan` ring-and-slash draws itself over the number's avatar with a stroke sweep (260 ms, RELEASE_RING bezier), the avatar desaturates to grey (`select`), then in Recents the row fades out in 120 ms and the list re-lays instantly; the Notice pill rises (S28) | C | Row disappears with a 120 ms fade; no sweep |
| S28 | **Notice pill** | Block, Unblock, Delete, Copy, "Sent", "Opened Messages", Merge reasons | The pill rises 16 px with fade on `appear` from 12 px above the Tab Bar (or above the bottom inset where no Tab Bar), a 2 px hairline along its bottom edge shrinks from full width to zero over its lifetime (transform scale X, linear: the only timed animation, 5 s for Undo, 2 s for info); Undo press rewinds the line on `release`; leaves on `sheet_dismiss`. One pill at a time, a new one replaces the old in place | C | No rise, no hairline; plain fade |
| S29 | **Incoming slide** | Finger drags the incoming card | Direct manipulation, no spring while the finger is down: the card (avatar, name, number) follows the finger with 0.9 gain, rubber-banded past the commit distance. Right: a `{colors.answer}` wash grows from the right edge (alpha 0 to 0.35) and an Answer bubble grows 0.6 to 1.2; left: the same in `{colors.danger}` with a Decline bubble; up: the reply sheet is pulled up under the finger (sheet drag, detent medium). A light haptic tick at 50% and a medium one at the commit distance (96 px). Lifting before the commit distance, or below the fling velocity, springs back on `release`. Commit right = S8 Answer morph; left = the card flies off to the left with fade (`sheet_dismiss`) while Decline is sent first | C (transform, one alpha wash) | Buttons only; the gesture is off while reduced motion is on, but never blocked: a drag still commits at 96 px without the follow effect |
| S30 | **Silence hush** | Silence tapped, or flip-to-silence | Rings in flight finish and fade; the avatar breath eases to rest and the aura saturation drops 40% on `select`; the Silence control floods with accent tint (S10) and its label becomes "Silenced"; the Answer glow pulse stops. The call stays fully answerable | C | Instant |
| S31 | **Reply sent** | A quick reply is chosen | The chosen row's text becomes a glass-flat bubble that travels to the avatar on `morph` and fades; the sheet dismisses on `sheet_dismiss`; the card label reads "Sent" or "Opened Messages" for 1.2 s, then the screen leaves with the S11 fade. The Call is already rejected when the finger lifts | C | Fade, no travel |
| S32 | **Results morph** (T9) | First digit typed on an empty field, or the field empties | The large title "Keypad" slides up 12 px and fades while the best-match row rises from 12 px below and fades in, same slot, on `appear`; matched letters in the name go from weight 400 to 700 on `release` (colour unchanged); changing matches swap the row with a 120 ms cross-fade, no slide. Never delays a digit | C | 120 ms cross-fade |
| S33 | **Speed-dial charge** | Finger held on an assigned digit key on an empty Keypad | The digit appears at once (key rule). A 2 px accent ring around the key fills clockwise over 450 ms (conic sweep, the arm time); the key lens glow rises to 0.6; at 450 ms the key pops to 1.12 on `release` with a RELEASE_RING ripple and a medium haptic, the digit is removed and the call is placed (S3 bloom). Releasing early drains the ring on `press` and leaves the digit. A key with no assignment does nothing extra | C | No ring: a static ring appears at 450 ms with the haptic |
| S34 | **Reorder lift** | Long-press then drag on a Favorite tile | At 450 ms the tile lifts (scale 1.06 on `release`, shadow grows, it paints as flat glass), medium haptic; dragging moves it under the finger; neighbours translate into their new slots on `select` (transforms only, the grid cells do not change until drop); drop settles on `release` and the order commits once. If the finger has not moved by lift-off, the Contact Sheet opens instead | M (up to 8 tiles translating) | Tiles jump to slots, no lift scale |
| S35 | **Details arrival** | Info on a Recents row, or Call details from a sheet | The row's avatar flies to the header avatar slot (96 px) on `morph` (S4 variant); header text fades up 12 px on `appear`; the action strip tiles rise staggered 30 ms (S16 pattern); history rows fade in staggered 20 ms for the first 8 visible rows only | C | Fade |
| S36 | **Hold to delete** | "Delete all call history" confirm step | A `{colors.danger}` fill sweeps left to right inside the pill over 1 s while held (transform scale X); releasing early drains it on `press`; completion flashes the pill and the rows fade; haptic ticks at 33% and 66%, medium at completion. With TalkBack on, a plain "Delete everything" button replaces the hold | C | Fill appears in steps (no sweep), hold still required |

All v0.2 moments obey the Performance Budget rows below and the degradation rule: on degradation S18 dot, S21 pulse, S24 rotation, S29 wash, S34 neighbour displacement and S36 sweep stop first; functionality is unchanged.

**Cheap vs costly additions (Performance Budget extension):** the aura (S6) and the incoming rings (S7) are the only looping animations; both are transform/opacity on plain quads, only while their screen is visible and the screen is on, and they are first to go in the degradation rule. Signature moments never add refractive passes, so the glass budget is unchanged.

## Performance Budget

Fluid motion, a clean look and speed are product requirements, not polish (owner priority). The numbers below are acceptance criteria; the architecture and build steps must provide a way to measure them (frame-time log on device, `adb shell dumpsys gfxinfo dev.rovsti.phone framestats`).

**Frame time.** Budget is one display frame: 16.6 ms at 60 Hz, 11.1 ms at 90 Hz, 8.3 ms at 120 Hz (follow the panel's refresh rate). Target: p95 frame time inside the budget and no more than 1% of frames dropped during any scripted interaction below; no single frame over 2x the budget during scrolling or tab switch. CPU frame work (layout + paint build + spring tick) stays under 4 ms on the owner's phone so the GPU has the rest. Cold start of the main window to first interactive frame: under 1.2 s.

**Refractive surfaces per screen (hard limit 4, backdrop-sampling pass each).**

| Screen / state | Refractive surfaces | Which |
| --- | --- | --- |
| Favorites, Recents, Contacts | 2 (+1 while a sheet or search field shows) | Tab bar, puck; search field (Contacts) |
| Keypad | 2 (+1 while a key is held) | Tab bar, puck, the pressed key only |
| Setup Checklist expanded | 3 | Tab bar, puck, card |
| Contact Sheet / chooser open | 3 | Tab bar, puck, sheet (page behind is dimmed, not refracted again) |
| In-Call Screen | 1 per held control (max 2 for multi-touch) | No tab bar; controls are flat glass look at rest |
| Incoming Call Screen | 0 | Wash backdrop only; Answer and Decline are solid colour |
| In-Call Keypad | 1 held key | Same rule as Keypad |

A refractive surface may not scroll: list rows, Favorites tiles and Recents rows are never refractive. Glass sits only on fixed chrome and sheets. The tab bar and its puck count as two passes; the puck's lens blend is a parameter change on the same surface, not an extra one.

**v0.2 refractive-surface rows (same hard limit of 4).**

| Screen / state | Refractive surfaces | Which |
| --- | --- | --- |
| In-Call, route popover open | 1 | The popover (settled); the controls stay flat; page behind dimmed to 0.7 |
| In-Call, two calls or conference | 0 at rest, 1 per held control | Held card and panel are flat glass |
| In-Call, Waiting card | 0 | Flat sheet look; the card never samples the backdrop |
| In-Call, Add call sheet | 2 (+1 held key) | Sheet, segmented control indicator while moving; the Keypad inside the sheet follows the Keypad rule |
| Incoming, reply sheet open | 1 | The sheet (settled); Message and Silence are flat |
| Settings and sub-pages | 0 at rest | Back button and gear are flat; toggles are not glass; a sheet from Settings adds 1 |
| Call details | 0 at rest | Action tiles are flat; Back flat |
| Keypad with T9 strip | 2 (+1 held key) | Unchanged: the strip is flat; the results sheet adds 1 |
| SIM chooser, route popover, quick-reply editor sheet | 1 | The sheet or popover; Tab Bar hidden or dimmed behind it counts as already painted |
| Favorites during reorder | 2 | Tab bar and puck; the lifted tile is flat |

**v0.2 list rules.** Blocked numbers, Blocked calls, Call details history, conference participants, route list (more than 5 rows) and T9 results are virtualised with fixed row heights (58, 58, 58, 52, 52, 52). Insertions fade in with no layout spring; removals fade out then re-lay instantly. The only animated reflow is the Favorites reorder drag, where tiles move by transform and the grid itself does not change until drop. Endpoint, call and conference updates are coalesced to one snapshot per frame. T9 results are computed off the UI thread from a prebuilt index and swap in within one frame of arriving (PRD NFR-18); a stale result for an older digit string is dropped.

**v0.2 idle cost.** New surfaces add no looping animation. The Notice pill hairline is a timed transform for at most 5 s. The speed-dial ring and hold-to-delete sweep run only while a finger is held. The flip-to-silence sensor listener exists only while a call is ringing.

**Costly vs cheap motion.**

| Cheap (use freely, GPU transform or opacity only) | Costly (allowed only where listed) |
| --- | --- |
| Opacity fades; translate; uniform scale of an already-painted element; colour mix of a solid fill; spring tick of a few scalars | A refractive surface resizing or moving every frame: only the tab-bar puck (small, 56 px high) and a held key's lens; never a sheet while it drags (a sheet's glass tint stays fixed, only its position moves) |
| Press scale on rows, keys, controls | Morphing a glass shape's size and radius together: only the Setup card collapse, once, 1 surface |
| Page entrance (fade + 24 px lift) | Cross-fading the theme: one 260 ms spring, all surfaces at once; skipped (snap) if the previous frame exceeded 2x budget |
| Digit pop, shake, badge pop | Animated blur radius changes: never |
| Row insertion with fade (no layout spring) | Layout springs on lists, animated reflow of rows or tiles: never |

If any frame during a motion exceeds 2x budget twice in a row, the app degrades for the rest of that screen visit: aura drift and sonar rings stop first (static blobs), then puck lens and press glow stop, glass becomes the flat glass look, springs keep running. It recovers on the next screen.

**Long lists.** Recents (up to 500 rows), Contacts (about 500+) and Favorites use a virtualised list: only rows in the viewport plus one screen above and below are built and painted. Fixed row heights (Contacts 58, Recents 58, section header 28) so scroll offsets are arithmetic; no per-row measurement. Avatars are monogram circles drawn from quads; photos (Favorites, rows) are decoded off the UI thread to 128 px, cached by contact id in an LRU of 64 entries, and fade in over `appear` when ready; a monogram shows until then. Provider queries run off the UI thread (PRD NFR-4); the list shows skeleton rows on first open and swaps data in without moving the scroll position. Scrolling is inertial with no per-frame allocation in row builders. Section letters and the alphabet index do not animate.

**Idle cost.** With no spring moving and no call, the app requests no animation frames (0 fps, 0 timers). During a call, the only per-second repaint is the call timer text (a single small element); the Call aura (In-Call Screen) and the incoming sonar rings (Incoming Call Screen) are the only looping animations; both are transforms of plain gradient quads, run only while their screen is visible, and pause at screen-off.

**Uncluttered layout rules.** One purpose per screen. No more than one primary action per screen state (Keypad: Call; In-Call: End; Incoming: Answer). Keep at least 16 px between interactive groups and 24 px between the number field and keys. Never stack more than one banner or card above a list; precedence is Setup Checklist, then preview banner (dropped when the checklist already shows the default-app item), then Return-to-call pill. Row text is at most two lines (title, subtitle); no inline badges beyond the missed count; no icons beyond direction and info. No decorative imagery beyond the backdrop wash and avatars.

## Components

Visual specs. Behaviour is in EXPERIENCE.md.

- **Tab Bar** (`{components.tab-bar}`). 264 x 56 glass capsule centred, 12 px above the bottom inset. Four 64 x 48 slots with 4 px padding. The puck is a glass capsule (chrome, lerping to lens while pressed) behind the selected icon. Selected icon `{colors.accent}`, others `{colors.label}`; icons near the puck blend between them and swell up to 12%. The Recents icon carries the **Missed Call Badge**: a 18 px (min width, grows with digits) danger-filled capsule, white `caption2` count, offset 6 px up and 6 px right from the icon's top-right; shows "99+" above 99; hidden at 0.
- **Large title.** `large-title` label with an optional trailing 44 px glass round button (used on Contacts: none in MVP; reserved).
- **Key** (`{components.key}`). 78 px circle with the *flat glass look* at rest (`{colors.fill}` tint, outline, rim; no backdrop sampling) that swaps to the refractive `control` + `lens` glass only while pressed (one key at a time, so one refractive surface), digit `key-digit` (32 px, light weight), letters `caption2` below. The `*`, `#` keys have no letters; `0` shows "+". Fixed 78 px slot so neighbours never move when a key shrinks.
- **Call button** (`{components.call-button}`). 78 px circle, `{colors.answer}` fill (the one solid non-glass control besides End), white `LucidePhone` 30 px. Press: scale 0.94, fill mixes 18% white.
- **Delete control.** `LucideDelete` 28 px in `secondary-label`, no background, 78 px slot right of Call; hidden with the field empty.
- **Number field.** Centered `dial-number`; below it a 18 px status line (`footnote`, secondary-label) that shows the matched Contact name, or "Not a valid number", or the call status. Paste: a small "Paste" pill (glass, 36 high, `LucideClipboardPaste` 16 + "Paste") appears above the field only when the clipboard holds text and the field is empty.
- **Favorite tile** (`{components.favorite-tile}`). Glass control, 20 radius, 2 columns at 360 to 400 dp (3 columns at width >= 440 dp), tile height 132, 12 px gaps, 16 px side inset. Avatar circle 64 (photo or monogram) centered, name `subheadline` weight 600 below, one line, truncated. Monogram tint is a stable hash of the contact id over six muted hues.
- **Recents row.** `{components.row}` with the avatar circle 40 leading (monogram or photo; unknown numbers show `LucideUser` on `fill`), title `headline` (name or formatted number; danger colour when missed), a count suffix "(2)" in the same colour and weight 400, subtitle line: direction icon 16 + label (`mobile`, `home`, `work`, or the formatted number when the title is a name with no label), trailing `footnote` tertiary-label time, then a 44 px info button (`LucideInfo` 20, secondary-label) as its own target.
- **Contact row.** Avatar 40, `headline` name, `subheadline` secondary-label subtitle only when the contact has several numbers ("mobile and 2 more"). Alphabet section headers: `footnote` weight 600 secondary-label letter on `grouped-background` at 28 px high, sticky.
- **Search field** (`{components.search-field}`). Glass control capsule, 44 high, 16 side inset, `LucideSearch` 18 leading in secondary-label, placeholder "Search name or number" in tertiary-label, clear `LucideX` 16 trailing target 44 px when non-empty. Sticks below the title; never part of the tab bar.
- **Setup Checklist card** (`{components.setup-card}`). Sheet glass, 20 radius, 16 side margin, 16 padding. Header: `headline` "Finish setup so calls always ring" with a `footnote` secondary-label "{n} of {m} done" and a progress capsule (4 px high track `fill`, accent fill). Items are a grouped list (26 radius container inside: concentric inner rows 12 radius on press). Item row: 44 px state icon slot, title `headline`, reason `footnote` secondary-label (up to two lines), trailing action pill ("Set up", glass 36 high, accent text) when not done, or "Confirm" for unverifiable Xiaomi items. A done item shows no trailing control, state icon accent, title in secondary-label.
- **Setup complete row.** Collapsed form of the card: a 44 px glass capsule row, `LucideCircleCheck` accent, "Setup complete" `headline`, trailing `LucideChevronDown`. Expanding is a tap.
- **Preview banner.** 44 px glass capsule below the title: `LucideSmartphone` warning, "Not the default phone app" `footnote`, trailing "Fix" accent link that scrolls to the checklist.
- **Sheet** (`{components.sheet}`). Sheet glass, 38 top radius, grabber 36 x 5 (radius 2.5) 8 px from the top, content inset 16, detents: medium (about 55% height) and large (about 92%). The Contact Sheet opens at medium.
- **Contact Sheet.** Top: avatar 80 (centered), name `title2`, star toggle (44 px round glass button, top-right of the sheet, `LucideStar`, accent + filled when starred). Below: grouped list of numbers (26 radius): each row has a label (`footnote` secondary-label: mobile, home, work, other), the formatted number (`headline`), and a trailing round `{colors.answer}` call button 36 px with `LucidePhone` 18. Last group: "Edit in Contacts" row with `LucidePencil` + `LucideExternalLink`.
- **Number sheet** (Recents number with no Contact). Number in `title2` centered; two full-width glass pills: "Call" (`LucidePhone`) and "Add to contacts" (`LucideUserPlus`).
- **Number chooser.** A small sheet at medium detent listing the contact's numbers as in the Contact Sheet, with the title "Call {name}".
- **Empty state.** Centered stack: 48 px Lucide icon in tertiary-label (the tab's own icon), `title3` title, `subheadline` secondary-label body (max 280 px wide, centered), optional glass pill button. Vertically centered in the free area above the tab bar.
- **Permission-denied state.** Same as empty state with `LucideTriangleAlert` in danger, a body that names the permission, and a glass pill "Grant access" (accent text).
- **Skeleton rows.** Same geometry as rows with `fill` capsules in place of text (title 120 x 14, subtitle 80 x 12) and a `fill` circle; fade in only.
- **In-call controls** (`{components.incall-control}`). 76 px glass circles, icon 28 `label`, caption `caption` below (outside the circle, 4 px gap, secondary-label). On-state: ring 2 px `{colors.accent}` and icon `{colors.accent}`, glass tint raised 20%. Disabled (Hold when unsupported): opacity 0.4.
- **End button** (`{components.end-button}`). 220 x 64 capsule, `{colors.danger}` fill, white `LucidePhoneOff` 30. Centered below the control grid.
- **Incoming card.** See Incoming Call Screen layout in EXPERIENCE.md; visually: avatar 120, name `incall-name`, "Rovsti Phone incoming call" label `footnote`, two 76 px circles (Decline `{colors.danger}`, Answer `{colors.answer}`) 96 px apart, captions "Decline" / "Answer" under them in `label` over the wash.
- **Waiting Call banner.** Glass capsule 76 high, inset 16, 12 px under the top inset: avatar 40, name `headline`, "Waiting" `footnote`, trailing Decline (36 px, danger) and Answer (36 px, answer) circles.
- **Return-to-call pill.** Glass capsule 44 high, `{colors.answer}` at 20% tint, `LucidePhone` 18, "Return to call · 2:41" `headline` (timer updates each second); sits below the status bar above the page title.
- **Status line and Notice pill.** There are no toasts. Transient information uses the status line under the number field, inline row text, or (v0.2) the single Notice pill described below for reversible actions and confirmations. Errors are inline (see EXPERIENCE.md States).

### v0.2 components (Epics 14 to 21)

Visual specs. Behaviour and states are in EXPERIENCE.md.

- **Flat variants.** `control-flat` and `sheet-flat` are the existing "flat glass look" (tint, outline, rim, shadow; no backdrop sample) for surfaces that must not use a refractive pass: held card, conference panel, waiting card, notice pill, action tiles, segmented controls, secondary incoming controls. They keep the same look at rest as the refractive version so a swap is invisible.
- **In-call control grid, v0.2.** Still 3 columns x 2 rows of 76 px controls, 24 px gaps, in fixed slots: row 1 Mute, Keypad, Audio; row 2 Add call, Hold (becomes Swap with two calls), Merge. Disabled controls are dimmed to 0.4 and keep their place. The Audio caption shows the current route name (a 96 px wide, one-line, end-ellipsis caption slot; it is "Audio" when no route detail is known). With a Bluetooth route, the Audio icon is `LucideBluetoothConnected`.
- **Route popover** (`{components.route-popover}`). Sheet glass, 20 radius, 288 px wide, right edge on the 16 px inset, bottom edge 8 px above the top of the Audio control row (it opens upward and may cover the avatar zone; the End button and the control grid stay visible and tappable below it). Rows 52 px: 36 px icon tile (12 radius, `fill`), name `headline` (one line, ellipsis), optional subtitle `footnote` secondary-label ("Connected", "Disconnected", "Hearing aid"), trailing 24 px check circle (accent `LucideCheck`, only on the current route). Footer rows 44 px, `subheadline`, accent text with trailing `LucideExternalLink` 16: "Bluetooth settings", "Hearing aids". A hairline separates the footer. More than 5 device rows scroll inside a fixed 5-row window; the popover never exceeds 70% of the screen height.
- **Notice pill** (`{components.notice-pill}`). 48 px high capsule, flat glass, 16 side inset, centred text `subheadline` (one line, ellipsis in the middle for numbers), optional trailing text button (accent, 44 px target, "Undo", "Open"), bottom hairline 2 px (`{colors.accent}` at 60%) for the lifetime. It replaces every use of a toast: it is the one transient message surface.
- **Action tile** (`{components.action-tile}`). 64 x 64, 20 radius, flat glass; icon 20 centred over a `caption2` label (`label`). Used in strips of five with 12 px gaps (5 x 64 + 4 x 12 = 368 within the 370 px content width), on the Contact Sheet, number sheet and Call details. Destructive tiles (Block) keep a `label` icon; danger colour appears only on the confirm step.
- **Toggle** (`{components.toggle}`). iOS shape, 51 x 31, thumb 27 white, track `fill` off and `{colors.accent}` on; 44 px tall touch row (the whole Settings row is the target).
- **Segmented control** (`{components.segmented}`). 36 px high capsule, 2 to 4 segments, flat glass track, a glass indicator capsule (chrome, 2 px inset) moves between segments on `select`; labels `subheadline` weight 600.
- **Chip** (`{components.chip}`). 20 px high capsule, `caption2`, `fill` background, 8 px side padding. **SIM chip:** a 6 px dot in the SIM's slot colour plus "SIM 1" or the carrier label (max 8 characters, ellipsis); the dot colour is Android's account highlight colour *clamped* to contrast of at least 3:1 against the chip fill (mix toward `label` if lower); the text is always `label`. **Quality chips:** "HD", "Wi-Fi" (with `LucideWifi` 12), "VoLTE" in `secondary-label`, same shape. A chip is never the only carrier of meaning; the TalkBack label spells it out.
- **In-call chip row.** A reserved 22 px row directly under the number line, left to right: SIM chip, HD, Wi-Fi. Empty rows keep their height. It is centred.
- **Held card** (`{components.held-card}`). 64 px capsule, full width minus 32, flat glass tinted `{colors.warning}` 12%: avatar 40 with the Holding ring, name `headline`, "On hold · 1:12" `footnote` (timer tabular), trailing 44 px round swap button (`LucideArrowUpDown`). Whole card is the swap target.
- **Conference panel** (`{components.conference-panel}`). 26 radius, flat glass, full width minus 32, fixed three-row window (156 px) that scrolls beyond 3 participants. Row 52: avatar 36, name `headline`, subtitle `footnote` (number or "Participant"), trailing two 44 px icon buttons: Split (`LucideSplit`, label colour) and End (`LucideUserMinus`, danger). Unavailable actions are dimmed, never hidden; read-only rows show neither. Header above the panel: avatar stack, "Conference" `title3`, timer `incall-status`.
- **Waiting card** (`{components.waiting-card}`). Flat sheet glass, 38 radius, 16 side inset, 12 px under the top inset, height 184: header row (avatar 40, name `headline`, "Waiting" `footnote`), then a 2 x 2 grid of 44 px pills with 8 px gaps: "Hold and answer" (answer colour fill, white/black glyph per mode), "End and answer" (glass, label), "Decline" (danger fill), "Message" (glass, `LucideMessageSquare`). The Message pill is dimmed (not hidden) when there are no quick replies.
- **Incoming secondary controls.** Two 56 px flat-glass circles above the Decline/Answer row, 24 px apart, centred: **Message** (`LucideMessageSquare`, left) and **Silence** (`LucideBellOff`, right), captions `caption` below. Silenced state: accent ring and tint, caption "Silenced".
- **Reply sheet.** Sheet glass at medium detent; title `headline` "Decline with message"; rows 56 px (quick reply text `body`, up to two lines, `LucideMessageCircle` 18 leading in secondary-label); last row "Write your own…" (accent) which declines and opens Messages with an empty body.
- **SIM chooser.** Sheet glass, medium detent. Title "Call with"; rows 64 px: 12 px SIM dot (slot colour), "SIM 1" `headline`, carrier and number `footnote`, trailing "Default" `caption` when applicable. A final text row "Change default SIM" (accent) when the system page exists.
- **Settings page.** Large title "Settings" on a back button row (Back 44 px flat glass at the left inset). Content is grouped lists (`{components.settings-group}`): section label `footnote` weight 600 secondary-label 8 px above the group, rows 58 px (title `headline`, value `body` secondary-label, trailing chevron or toggle), 16 px between groups, 35 px between sections. Page background is `grouped-background` plus the usual wash.
- **Settings gear.** The reserved 44 px trailing glass round button of the large title, `LucideSettings` 20, on all four tab screens. It is a flat glass button, refractive only while pressed.
- **T9 strip** (`{components.t9-strip}`). 56 px, in the large-title slot of the Keypad: best-match row (avatar 36, name `headline` with the matched prefix in weight 700, matched number `footnote` secondary-label, trailing 36 px answer-colour call circle) and, when more matches exist, a 36 px "+n" capsule between name and call circle opening the results sheet. 16 px side inset.
- **Results sheet.** Sheet glass at medium detent: rows 58 as Contact rows with highlighted matches; tapping the row fills the field, the trailing call circle calls.
- **Speed-dial key badge.** A 14 px avatar dot (contact hue pair) at the key's top-right, only on assigned keys; the `1` key shows `LucideVoicemail` 14 in place of its empty caption.
- **Emergency Call button.** Same 78 px answer-colour circle with a `caption2` "Emergency call" label centred 6 px below it (in the 12 px gap above the Tab Bar), and the status line reads "Emergency call" in `{colors.danger}`.
- **Details header and history.** Header: avatar 96, name `title2`, number `subheadline` secondary-label, chip row (Blocked in danger outline when blocked, "Contact" when matched); totals card (settings-group, two cells "12 calls" and "1 h 04 min talk time", `title3` figures, `footnote` captions). History rows 58: direction icon 16, type label `headline` ("Outgoing", "Missed" in danger), date and time `subheadline`, trailing duration `body` tabular, chips under the date on the right for SIM, HD, Wi-Fi. Sticky date headers 28 px.
- **Blocked list.** Segmented control "Numbers | Blocked calls" under the title; add-number field (search-field shape, `LucideBan` leading, placeholder "Add a number to block") with a trailing "Block" accent button; rows 58 with a `LucideBan` tile, formatted number (+ name when matched), trailing "Unblock" glass pill 36 px.
- **Hold-to-confirm pill.** 52 px capsule, `{colors.danger}` at 15% with a danger fill sweeping in, label `headline` danger "Hold to delete everything".

## Do's and Don'ts

| Do | Don't |
| --- | --- |
| Float glass above content; let lists scroll under the tab bar | Reserve layout space for the Tab Bar so nothing scrolls beneath it |
| Use the kit's presets (`chrome`, `control`, `sheet`, `lens`) and their springs | Invent new blur values or tween durations |
| Use one accent (emerald-teal) | Add a second accent, coloured gradients on text, or a per-tab colour |
| Label glass with `label` / `secondary-label` | Put coloured or low-contrast text on glass |
| Dark glass as a white 0.15 lift with a thin symmetric dark outline | Black smoky glass or one-sided shadows |
| Keep nested radii concentric (outer minus inset) | Mix unrelated radii in nested shapes |
| Use red only for missed, decline, end and denied | Use red for decoration or warnings that are not destructive |
| Cross-fade theme changes on the `theme-fade` spring, in RGB | Snap themes or lerp in HSL |
| Run press feedback in parallel with the action | Delay Answer, Decline or End for an animation |
| Stop requesting frames when springs settle | Run an always-on animation (only the Call aura and incoming sonar rings loop) |
| Fall back to opaque material when backdrop blur is unsupported | Ship a flat translucent rectangle as "glass" |
| Keep at most 4 refractive surfaces per screen (Performance Budget) | Wrap rows, tiles or keys at rest in refractive glass |
| Respect reduced motion and the system font size | Pin text sizes in px that ignore the font scale |
| Keep In-Call control slots fixed and dim unsupported ones | Hide or reflow a control because the carrier or Android refuses it |
| Open pickers as overlays anchored to their control (route popover, SIM chooser) | Push the controls or keys that opened them |
| Reserve the space for SIM, HD and Wi-Fi chips, the Audio label, T9 and Silence | Add a chip or label that changes row height later |
| Show SIM chips only when two or more SIMs exist | Show "SIM 1" on a single-SIM phone |
| Use the Notice pill with Undo for reversible actions | Use a modal confirmation for Block or Copy |
| Confirm irreversible deletes inline (named contact, hold-to-delete) | Offer Undo for a provider delete the app cannot restore |
| Use flat glass for held card, panel, tiles, pills | Spend a refractive pass on something that scrolls or sits in a list |
