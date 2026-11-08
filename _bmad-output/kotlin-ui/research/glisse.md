# glisse research: iOS 26 Liquid Glass components, spring, lens math, and how to port them to Compose/AGSL

Source: `github.com/0kzh/glisse` v0.5.0 (HEAD `46be5ba`), checked out at
`$G` = `/tmp/claude-0/-home-user-rovsti-apps/fec9d351-b8cd-5c7f-af54-f98d09255a4a/scratchpad/ref/glisse`.
TypeScript + React + CSS + SVG filters, "iOS 26 Liquid Glass for the web". Paths below are relative to `$G`;
`file:line` citations are to the real per-file line numbers (verified with `grep -n`).

## 0. Licence and attribution (read first)

- glisse is **MIT**, `LICENSE:1-21`, "Copyright (c) 2026 Kelvin Zhang". `package.json` also says `"license": "MIT"`.
  The owner's rule: **glisse code may be ported directly** (translated TS -> Kotlin/Compose/AGSL). Convx is GPL-3.0 and must not be copied.
- Obligations when porting (owner decision, repeated here so implementers do not have to look it up):
  1. Every Kotlin/AGSL file that contains code ported from glisse starts with this header comment:
     `Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang`
  2. `android/THIRD_PARTY_NOTICES.md` must contain glisse's full MIT licence text (21 lines, reproduced in Appendix A).
- glisse itself credits two sources of its maths in comments, so their licences are not glisse's to grant:
  - "Aave's" web glass (`lens.ts:3-6,95,155`, `refraction.ts:6-12`, `slider.tsx:28`, `switch.tsx`): the displacement-map lens
    shape (dome slope, `erf` falloff, light constants `DARK`/`LIGHT` at `lens.ts:96-97`). The derivation is in a public article; we port glisse's
    implementation, but we should not claim glisse's MIT covers Aave's article text.
  - kube.io "liquid glass CSS SVG" (`refraction.ts:333-338`): the Snell's-law squircle profile (section 3.2). The formula is physics, written
    in glisse's own code; port glisse's version.
  Neither is a blocker (we port glisse's expression of them), but leave the two citations in the Kotlin header comments.
- The Convx side of the comparison lives in the sibling reports in this folder (`convx-glass.md`, `convx-motion.md`, `convx-overlays.md`,
  `convx-navigation.md`, `convx-performance.md`). Nothing here is Convx code; "Convx does better" notes only point at those reports' facts.

## 1. Inventory (what glisse is)

8,466 lines total; the library proper is ~4.1k lines of TS/CSS. Public exports (`src/index.ts:6-18`):
`Button`, `Glass`, `Input`, `Menu`, `SearchField`, `SegmentedControl`, `Slider`, `Switch`, `TabBar`.

| File | Lines | Role |
|---|---|---|
| `src/internal/spring.ts` | 104 | Analytic damped-spring solver + shared rAF loop |
| `src/internal/press.ts` | 118 | Button press: grow, rubber-band stretch, drag translate |
| `src/internal/hold.ts` | 9 | 150 ms "touch belongs to the control" rule |
| `src/internal/lens.ts` | 878 | Displacement-map lens, SVG filter graph builder, ink mode, backdrop lens |
| `src/internal/refraction.ts` | 505 | Glass rim light, "selection" lens (Snell profile), backdrop discovery |
| `src/internal/segments.tsx` | 534 | Engine of TabBar and SegmentedControl (selection, drag, lift, lens) |
| `src/switch.tsx` / `slider.tsx` / `menu.tsx` | 226 / 128 / 330 | Components |
| `src/button.tsx` / `input.tsx` / `search-field.tsx` / `glass.tsx` | 55 / 62 / 20 / 23 | Components |
| `src/styles.css` | 1083 | All visual tokens and layouts |
| `test/*.tsx` | ~1,400 | vitest + testing-library; useful as behavioural spec (section 12) |

Architecture in one paragraph: a "lens" is a rounded-rect region over some content. The content is not read back (no backdrop capture); instead
the content itself gets an SVG filter made of **pre-rendered greyscale displacement maps** (one image for pull-across, one for pull-down, one for
light, one for shape) that are drawn per shape/size on a canvas, cached by key, and applied through `feDisplacementMap` once per colour channel
(dispersion). Springs are a hand-rolled closed-form solver driving element styles. Everything that is DOM/SVG-filter plumbing (maps as PNG data URLs,
`filterUnits`, Safari box units, filter id churn, `MutationObserver` for theme) disappears on Android; the **maths and constants** are what we port.

---

## 2. Spring system

### 2.1 Solver (`src/internal/spring.ts`)

Mass-1 damped harmonic oscillator, `x'' + c x' + k x = 0` with `k = stiffness`, `c = damping` (the second constructor argument is the damping
*coefficient*, not a ratio). State per spring: `value, target, velocity, stiffness, damping, paint` (`spring.ts:8-15`).

Closed-form step `advance(s, dt)` (`spring.ts:21-48`), with `x = value - target`, `v = velocity`, `half = damping/2`, `D = half^2 - stiffness`:

| Regime (test) | Position | Velocity | Lines |
|---|---|---|---|
| critically damped, `|D| < 1e-4` | `value = target + e^{-half dt}(x + b dt)`, `b = v + half x` | `e^{-half dt}(v - half b dt)` | 27-31 |
| over-damped, `D > 0`, `root = sqrt(D)`, `r1 = -half+root`, `r2 = -half-root` | `a = (v - r2 x)/(r1 - r2)`, `b = x - a`, `value = target + a e^{r1 dt} + b e^{r2 dt}` | `a r1 e^{r1 dt} + b r2 e^{r2 dt}` | 32-39 |
| under-damped, `omega = sqrt(-D)` | `target + e^{-half dt}(x cos(omega dt) + ((v + half x)/omega) sin(omega dt))` | `e^{-half dt}(v cos - ((half v + stiffness x)/omega) sin)` | 40-47 |

(I re-derived the under-damped velocity: `h^2 + omega^2 = k`, so `-(h v + k x)/omega` is correct. The three branches are exact for any `dt`, so the
solver is frame-rate independent and unconditionally stable.)

Loop (`spring.ts:17-18,50-64`): one global `Set` of moving springs and one `requestAnimationFrame`; `dt = clamp((time - last)/1000, 0, 0.1)` (line 52,
so a stalled frame never advances more than 100 ms); settle test `|value - target| < 1e-4 && |velocity| < 1e-3` then snap `value = target, velocity = 0`
(56-60); `paint(value)` is called every tick including the final one (61). `last` is seeded to the start of the current frame so the first tick already
moves a frame's worth (90-93).

API (`spring.ts:1-6,70-104`):
- `set(target, instant=false)`: retargets **without touching `value` or `velocity`** (this is why everything is interruptible: velocity carries through a
  retarget). `instant` or `document.hidden` or `prefers-reduced-motion` jumps straight to target, zeroes velocity (79-87).
- `tune(stiffness, damping)`: swap constants mid-flight, velocity kept (96-99). Used to switch press/release feel and rest/drag feel.
- `paint()`: re-emit current value (e.g. after layout change). `stop()`: remove from loop.
- There is **no velocity-in** API (`set` cannot be given a release velocity), no mass, no fling projection. See 2.4.

### 2.2 Constants catalogue

`zeta = damping / (2 sqrt(stiffness))` (computed; period of the damped oscillation = `2 pi / (omega0 sqrt(1-zeta^2))`, `omega0 = sqrt(stiffness)`).

| Spring | stiffness | damping | zeta | omega0 (rad/s) | Citation |
|---|---|---|---|---|---|
| Button press, mouse | 417.6 | 29.2 | 0.714 | 20.4 | `press.ts:13` |
| Button release, mouse | 230 | 23.7 | 0.781 | 15.2 | `press.ts:13` |
| Button press, touch | 515 | 27.3 | 0.601 | 22.7 | `press.ts:16` |
| Button release, touch (bouncy) | 250 | 11.8 | **0.373** | 15.8 | `press.ts:16` |
| Tab/segment slide, rest | 1024 | 58.9 | 0.920 | 32.0 | `segments.tsx:61` |
| Tab/segment lens lift, rest | 676 | 47.8 | 0.919 | 26.0 | `segments.tsx:61` |
| Tab/segment slide, while dragging | 2500 | 94 | 0.940 | 50.0 | `segments.tsx:64` |
| Tab/segment lift, while dragging | 576 | 44.2 | 0.921 | 24.0 | `segments.tsx:64` |
| Switch thumb position | 1000 | 80 | 1.265 (over-damped) | 31.6 | `switch.tsx:84` |
| Switch track colour | 1000 | 80 | 1.265 | 31.6 | `switch.tsx:88` |
| Switch thumb lift | 2000 | 80 | 0.894 | 44.7 | `switch.tsx:91` |
| Slider thumb lift | 2000 | 80 | 0.894 | 44.7 | `slider.tsx:68` |
| Menu morph, opening | 600 | 46 | 0.939 | 24.5 | `menu.tsx:83` (initial), `:121` |
| Menu morph, closing | 900 | 57 | 0.950 | 30.0 | `menu.tsx:120` |
| Menu card press scale | 650 | 36 | 0.706 | 25.5 | `menu.tsx:136` |
| Menu card release x / y | 600 | 30 | 0.612 | 24.5 | `menu.tsx:140,144` |

Reading the table: structural motion (selection sliding, switch, menu) sits at zeta ~0.92-0.95, i.e. a hair under critical: one barely visible overshoot.
Only the touch-release spring (zeta 0.37) and the menu card/press springs (0.6-0.7) are visibly bouncy.
The constants are web-tuned to look like iOS 26; they are the right starting point for Rovsti without re-measurement.

### 2.3 Port to Compose

Compose's `spring(dampingRatio, stiffness)` is the same mass-1 oscillator, so **glisse constants map 1:1**:
`dampingRatio = damping / (2 * sqrt(stiffness))`, `stiffness = stiffness`. `Animatable<Float>.animateTo(target, spring(...))` retargets
with velocity carried, exactly like `Spring.set`. Differences to handle:

- Compose's default `visibilityThreshold` is 0.01 for floats; glisse settles at 1e-4 / 1e-3. For scale-like values (1.0 +- 0.001) pass
  `visibilityThreshold = 0.0001f` (or `Spring.DefaultDisplacementThreshold` scaled) so the final pixel-level wobble matches.
- `tune()` = start the next `animateTo` with a different `spring(...)`; Compose keeps value+velocity, same as glisse.
- `set(instant=true)` = `snapTo`. `prefers-reduced-motion` = `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (check once per gesture).
- If a pure-JVM spring is wanted (platform-free unit tests, Roborazzi without a frame clock), port `advance` verbatim; it is 28 lines and trivially
  testable (`value` and `velocity` after N steps against a high-resolution RK4 reference). Kotlin sketch (port of `spring.ts:21-48`, MIT, attribute):

```kotlin
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
class SpringState(var value: Float, var target: Float, var velocity: Float = 0f)

fun SpringState.advance(stiffness: Float, damping: Float, dt: Float) {
    val x = value - target; val v = velocity
    val half = damping / 2f
    val d = half * half - stiffness
    when {
        abs(d) < 1e-4f -> { val decay = exp(-half * dt); val b = v + half * x
            value = target + decay * (x + b * dt); velocity = decay * (v - half * b * dt) }
        d > 0f -> { val root = sqrt(d); val r1 = -half + root; val r2 = -half - root
            val a = (v - r2 * x) / (r1 - r2); val b = x - a
            value = target + a * exp(r1 * dt) + b * exp(r2 * dt)
            velocity = a * r1 * exp(r1 * dt) + b * r2 * exp(r2 * dt) }
        else -> { val w = sqrt(-d); val decay = exp(-half * dt); val c = cos(w * dt); val s = sin(w * dt)
            value = target + decay * (x * c + ((v + half * x) / w) * s)
            velocity = decay * (v * c - ((half * v + stiffness * x) / w) * s) }
    }
}
```

  Frame loop: clamp `dt` to 0.1 s (`spring.ts:52`), settle at `|x| < 1e-4 && |v| < 1e-3`.
- Recommendation: use Compose `Animatable`+`spring` in the UI (draw-phase reads, no recomposition) and keep the ported solver only for tests and for
  places where a spring must be stepped by hand (e.g. inside a custom `Modifier.Node`).

### 2.4 What Convx does better (spring/gesture side)

glisse has no velocity input and no fling projection: a tab-bar drag release picks `Math.round(position / slot)` (`segments.tsx:268`) and a switch release
picks `value > 0.5` (`switch.tsx:163`), both from the **release point**, ignoring how fast the finger was moving. It also has no velocity-based
squash/stretch on the selection puck (the only stretch is the button rubber-band, section 3). `convx-motion.md` section 6 (Apple projection
`velocity/1000 * rate/(1-rate)`, rate 0.998), section 7.1 (puck squash from drag velocity) and 3 (`initialVelocity` handed to the settle spring)
cover exactly these gaps. Rule for Rovsti: **take glisse's constants, add Convx's technique of passing the release velocity to the settle spring and
choosing the target from the projected rest point**.

---

## 3. Press and hold behaviour

### 3.1 Button press (`src/internal/press.ts`)

Four independent springs: `scale.x`, `scale.y`, `translate.x`, `translate.y`, initial `[1, 1, 0, 0]` (`press.ts:23`), created with the mouse-release
constants (28-34). Two "feels" chosen by `pointerType` (`press.ts:96`):

| Feel | grow (px added to the longer side) | press spring | release spring |
|---|---|---|---|
| POINTER (mouse) | 6 | 417.6 / 29.2 | 230 / 23.7 |
| TOUCH (finger, iOS 26 button) | 15 (comment: "about 15pt bigger, bounces back past its resting size on release") | 515 / 27.3 | 250 / 11.8 |

`track(event)` (`press.ts:44-72`) recomputed on pointerdown and every move, using the element box captured at down and the **down point** `(x0, y0)`:

```
base   = 1 + grow / max(width, height)                     // uniform press growth
aspect = width / height
dx     = (clientX - x0) / aspect                           // horizontal drag counts less for wide buttons
dy     =  clientY - y0
len    = hypot(dx, dy);  if len == 0 -> target [base, base, 0, 0]
nx, ny = dx/len, dy/len
rubber = 1 - 1 / (1 + len / height / (5 * aspect))         // saturating 0..1
stretch= 1 + (6 / height) * rubber * | |nx| - |ny| |       // stretch only when drag is axis-aligned
horizontal = |nx| > |ny|
scaleX = base * (horizontal ? stretch : 1/stretch)
scaleY = base * (horizontal ? 1/stretch : stretch)         // volume-preserving squash/stretch
translateX = nx * 8 * rubber ; translateY = ny * 8 * rubber   // max 8 px toward the finger
```

All four targets use the press spring; release sets `[1,1,0,0]` with the release spring (`press.ts:86`). Gates: primary pointer, button 0, not already
pressed, not disabled (`:90`); window `blur` ends the press (`:108`). While pressed the element gets `data-pressed`, the finger position as
`--lg-x/--lg-y` in percent of the box (47-48), which feeds a radial highlight.

Visual side (`styles.css`):
- Pressed/hover background: `--lg-glass` -> `--lg-glass-active`, instantly on press (`transition-duration: 0s`, `:172-175`), 200 ms fade back (`:153`).
- Finger glow: `::after` radial gradient `circle 240px at var(--lg-x) var(--lg-y)`, `rgb(255 255 255 / .035)` -> `.025` at 50% -> transparent, opacity 0 -> 1
  instantly on `[data-pressed]`, fade out 220 ms ease-in-out (`:111-130`).
- Filled variant presses to `color-mix(fill 85%, black)`; hover `88%` toward white (`:207-219`).

Port: `Modifier.Node` (or `pointerInput` + `graphicsLayer {}`) with four `Animatable`s; draw-phase `graphicsLayer { scaleX; scaleY; translationX; translationY }`.
Port the maths literally (units: dp -> px conversion; `grow`, `6`, `8` are CSS px = dp). Touch feel is the one we use on Android (always finger);
keep the mouse feel only for stylus/mouse (`PointerType.Mouse`, ChromeOS). The glow becomes a `drawWithContent` radial brush at the touch point with the
same stops, `alpha` animated 0 -> 1 instantly and 1 -> 0 over 220 ms. **Port directly.**

### 3.2 Hold rule (`src/internal/hold.ts:7-9`)

`HOLD = 150` ms. `held(at, moved) = moved || now - at >= 150`. A touch that starts on a control belongs to the page (scrolls) until it has rested
150 ms or a drag is under way (mirrors `UIScrollView.delaysContentTouches`). Used by tab bar (`segments.tsx:290-294`), switch (`switch.tsx:144,167`).
Port to Compose: custom `awaitPointerEventScope` detector: on down start a 150 ms timer; vertical slop beyond `viewConfiguration.touchSlop` before the timer
fires hands the gesture back to the scrollable parent (do not `consume()`); after the timer, consume. Switch's variant: cancel when `dy > 10 && dy > |dx| + 4`
before hold (`switch.tsx:144`). **Port directly** (it is 9 lines of policy).

---

## 4. Glass surface, tokens, rim light

### 4.1 Tokens (`src/styles.css:1-89`)

| Token | Light | Dark |
|---|---|---|
| `--lg-foreground` | `rgb(0 0 0 / .88)` | `#fff` |
| `--lg-muted` | `rgb(60 60 67 / .6)` | `rgb(235 235 245 / .6)` |
| `--lg-glass` (surface fill) | `rgb(255 255 255 / .6)` | `rgb(40 40 40 / .62)` |
| `--lg-glass-active` (pressed/hover/focus) | `rgb(236 236 240 / .85)` | `rgb(70 70 70 / .62)` |
| `--lg-edge` (0.5 px edge line) | `rgb(0 0 0 / .08)` | `rgb(255 255 255 / .08)` |
| `--lg-drop` (shadow `0 1px 3px`) | `rgb(0 0 0 / .06)` | transparent |
| `--lg-selection` (tab lens at rest) | `rgb(0 0 0 / .10)` | `rgb(255 255 255 / .145)` |
| `--lg-lens` (lifted lens fill) | `rgb(0 0 0 / .03)` | `rgb(255 255 255 / .055)` |
| `--lg-lens-drop` (lifted lens shadow) | `rgb(0 0 0 / .11)` | transparent |
| `--lg-segment-track` | `rgb(118 118 128 / .12)` | transparent |
| `--lg-segment-thumb` | `#fff` | `rgb(235 235 245 / .3)` |
| `--lg-segment-label` | `#000` | `#fff` |
| `--lg-segment-lens` | `rgb(0 0 0 / .03)` | transparent |
| `--lg-surface` (menu) | `rgb(250 250 252 / .86)` | `rgb(28 28 31 / .86)` |
| `--lg-surface-edge` | `rgb(0 0 0 / .08)` | `rgb(255 255 255 / .13)` |
| `--lg-shadow` (menu) | `rgb(25 25 35 / .12)` | `rgb(0 0 0 / .38)` |
| `--lg-highlight` (menu row) | `rgb(60 60 67 / .09)` | `rgb(235 235 245 / .13)` |
| `--lg-separator` | `rgb(60 60 67 / .15)` | `rgb(235 235 245 / .16)` |
| `--lg-destructive` | `#ff383c` | `#ff4245` |
| `--lg-track` (switch off track) | `rgb(59 59 66 / .3)` | `rgb(235 235 245 / .3)` |
| `--lg-rail` (slider rail) | `rgb(0 0 0 / .1)` | `rgb(255 255 255 / .1)` |
| `--lg-on` (switch on) | `#34c759` | `#30d158` |
| `--lg-fill` (accent / filled button / selected tab icons) | `#0088ff` | `#0091ff` |
| `--lg-ring` (focus ring) | `rgb(0 0 0 / .4)` | `rgb(255 255 255 / .5)` |
| `--lg-lens-shine` | **-1.5** (darkens rim) | **+1** (brightens rim) |
| `--lg-rim-shine` | 0.3 | 0.13 |

Dark is selected by `[data-theme=dark]` or `prefers-color-scheme: dark` unless `[data-theme=light]` (`:31,60-61`): same as our `FOLLOW_SYSTEM_THEME`
product choice; in Compose these become one `GlassTokens` data class with light/dark instances (`isSystemInDarkTheme()`).
Easing token `--lg-ease: cubic-bezier(.25,.1,.25,1)` (`:28`) is unused by the TS; the real motion is springs.

### 4.2 Glass base layer (`styles.css:91-130`, `glass.tsx`)

```
background-color: --lg-glass
backdrop-filter:  blur(3px) <refraction filter or saturate(1.5)>    // --lg-blur default 3px, `:98`
box-shadow:       inset 0 0 0 .5px --lg-edge, 0 1px 3px --lg-drop    // edge replaced by the rim bitmap once painted (`:107-109`)
```

So the surface is only **3 px blur + saturation 1.5 + translucent fill**, with the optical character coming from the lens bend (section 5) and the rim light (4.3).
Menus use heavier material: `blur(22px) saturate(1.9)` over `--lg-surface`, shadow `0 12px 40px --lg-shadow`, inset `0.5px` edge (`styles.css:946-950`).
Thumb material (switch/slider): `blur(2px*settled + .5px*lift) brightness(1 + .15*settled) saturate(1.5)` where `settled = 1 - lift`
(`:661-667`), plus shadows `0 6px 24px rgb(0 0 0 / .2), inset 0 0 0 1.5px rgb(255 255 255 / .5), inset 0 2px 1px rgb(255 255 255 / .45),
inset 2px 7px 24px rgb(0 0 0 / .09), inset -2px -7px 24px rgb(255 255 255 / .16)` (`:668-673`).

### 4.3 Rim light (`refraction.ts:28-94`, `rimMap`)

Measured by the author from iOS 26.1 `.glassEffect()` at 3x over grey: a line brightest at the very edge, gone ~1.2 px in, slightly bowed; light from the top-left lights the whole
left side and top, its reflection the right and bottom; fades over the other two corners; slightly wider on the two corners it faces
(`refraction.ts:28-37`). Constants `RIM_WIDTH = 1.22`, `EDGE_WIDTH = 0.5` (`:41-42`).

Per pixel at signed depth `depth = -SDF` (px inside the edge):
```
if depth < -half(px) or depth > 2*RIM_WIDTH: skip
(nx, ny) = outward normal of nearest edge     // corner arc: radial from corner centre (`:67-77`)
facing   = |nx + ny|                           // 1 on top/left/bottom/right lit sides, sqrt2 on the two lit corners, 0 on the unlit corners
along    = max(0,depth) / (RIM_WIDTH * sqrt(max(1, facing)))
cover    = clamp((depth + half) * density, 0, 1)                 // antialias of the shape edge
lined    = clamp((min(EDGE_WIDTH, depth+half) - max(0, depth-half)) * density, 0, 1)
light    = along < 1 ? shine * min(1, facing)^1.5 * (1 - along^1.5) * cover : 0
line     = edge.a * lined * (1 - light)
alpha    = light + line ;  rgb = (255*light + edge.rgb*line)/alpha  // white light over the 0.5 px edge line
```
`shine` = `--lg-rim-shine` (0.3 light page, 0.13 dark; fallback 0.13, `:119-120`). The comment explains: iOS lifts mid-grey 0.65 of the way to white on a light page,
0.3 on dark, but part of that is backdrop colour leaking at the edge, hence the lower numbers (`:31-37`).
This map is drawn once per `(width, height, radius, shine, edge, density)` and cached as a PNG (`:123-137`), re-fit on theme change (`:142-166`).

**Port**: compute this in a draw routine (or an AGSL `shader` for the rim: SDF + normal in the shader, no bitmap). Same formula with `density = 1` in the shader
(use `fwidth`-less AA: `cover = clamp(0.5 - sdf, 0, 1)`). Two-sided light direction is fixed at the top-left (normal dot `(1,1)` abs). Port directly. For the 0.5 px
edge line + `--lg-edge` colour: stroke the outline with `0.5.dp` under the rim. Note Convx's rim (`convx-glass.md` 3.4) is directional via `|dot(n, lightDir)|^falloff`,
which is a generalisation with a continuous angle; glisse's hard-coded `|nx+ny|` is the 45 degree special case. Use glisse's numbers, optionally expose the angle.

---

## 5. Refraction / lens

Two lens models exist in glisse. Use **both**: 5.1 for glass/switch/slider/segmented/tab bar rim bend, 5.2 for the physically-derived selection lens.

### 5.1 Displacement-map lens (`src/internal/lens.ts`)

#### Inputs (`LensOptions`, `lens.ts:37-78`)
`strength` (rim reach as share of the shorter side), `dispersion`, `depth` (bend band width; px, or share of shorter side when `relative`),
`dome` (sag of the dome whose slope sets the bend, same units as depth), `splay` (0..1: how far near-side bend keeps its own direction),
`relative`, `paintsLight` (glass paints its own rim so the filter does not light), `ink` (content is text/icons), `blur` (soften std-dev px), `shine`, `edgeWidth`.

Per component (the "tuning table", all of which should be copied as-is):

| Lens | strength | dispersion | depth | dome | splay | extras | Citation |
|---|---|---|---|---|---|---|---|
| Glass (cards, buttons, bars) | 0.45 | 0.4 | 0.25 (relative) | 0.5 (relative) | 1 | `relative`, `paintsLight` | `refraction.ts:14-24` |
| Tab bar lens | 0.2 | 2 | 4 px | 0 | 1 | `ink`, `blur: 2` | `segments.tsx:73-83` |
| Segmented lens | 0.3 | 0.1 | 2.5 px | 0 | 1 | | `segments.tsx:88-95` |
| Switch thumb | 0.45 | 1 | 2 px | 6 px | 0.4 | | `switch.tsx:31-38` |
| Slider thumb | 0.75 | 0.65 | 2 px | 5 px | 0.5 | | `slider.tsx:34-41` |

CSS overrides to keep as parameters: `--lg-glass-bend`, `--lg-glass-dispersion`, `--lg-tab-bend`, `--lg-tab-dispersion`, `--lg-segment-bend`,
`--lg-switch-bend`, `--lg-slider-bend` (strength/dispersion), `--lg-thumb-frost` (default `0.5px`), `--lg-slider-line` (default `8px`).
Test pins "a reach of 0.45 of its shorter side" (`test/glass.test.tsx:123-124,200,263`) and `1.08` as the red-channel scale factor at dispersion 0.4.

#### Shape maths (`lensMap`, `lens.ts:164-252`)

Per pixel, coordinates centred, `x in [-w/2,w/2]`, `y` likewise, `radius` clamped to `min(halfW, halfH)`:

1. `sdf(x,y,hw,hh,r)` (`lens.ts:111-115`): `q = |p| - (hw,hh) + r; hypot(max(q,0)) + min(max(qx,qy),0) - r` (negative inside). Outside (`edge >= 0`): neutral (grey 128).
2. `share = relative ? min(w,h) : 1`; `depth = options.depth*share`; `dome = options.dome*share`; `sag = clamp(dome, 0.01, min(hw,hh) - 1)`;
   `innerW = max(0, hw - depth)`, `innerH` likewise, `innerR = clamp(r, 0, min(innerW, innerH))`; `ramp = depth>0 ? 1/(depth*sqrt2) : 1e6` (`:180-190`).
3. Dome slope: for a circular arc that sags `sag` across half-span `h`: `R = (h^2 + sag^2)/(2 sag)`; `slope(x) = min(x, .999R)/sqrt(R^2 - x^2)`, then **scaled so the mean slope over `[0,h]` is 0.5**
   (`arc()` 124-134 integrates 200 trapezoid steps; `slope()` 136-139). Computed separately for the across axis (`h = hw`) and down axis (`h = hh`) (`:189-190`).
   `dx = sign(x) * slope(|x|, acrossArc)`, `dy = sign(y) * slope(|y|, downArc)`; if `dome == 0`: `dx = clamp(x/hw, -1, 1)` (linear) (`:213-216`).
4. Splay (`splay < 1`, `:220-233`): `reach = 0.5*min(hw,hh)`; `keepX = max(0, 1 - (hh - |y|)/reach)*(1 - splay)`; `keepY = max(0, 1 - (hw - |x|)/reach)*(1 - splay)`;
   `dx *= 1 - keepX; dy *= 1 - keepY`; then renormalise to the original magnitude `hypot(dx,dy)` (so near top/bottom edges the pull turns vertical, near left/right horizontal, magnitude kept).
5. Falloff (confines the bend to a rim band): `falloff = 0.5 * (1 + erf(sdf(x,y,innerW,innerH,innerR) * ramp))`, `erf(x) ~ tanh(sqrt(pi) x)` (`:107-108,235`).
   ~0 in the middle, ~1 at the rim, smooth.
6. Stored pull greys: `across = 0.5 - 0.5*dx*falloff`, `down = 0.5 - 0.5*dy*falloff` (`:237-238`). In the SVG `feDisplacementMap`, `P'(p) = P(p + scale*(C - 0.5))`, so the **effective sampling
   offset is `-0.5 * scale * dx * falloff` along x** (and y), i.e. each rim pixel shows content from further *inside* (convex lens magnifying the middle outward).
   `scale = reach * spectrum[channel]`, `reach = strength * min(w,h) * amount` (`:657`, with `amount` in 0..1 the "lift" drive; `place()` `:526-529`).
7. Dispersion: three rays with `spectrum = [1 + 0.2*d, 1 + 0.1*d, 1]` for R, G, B (`:525`). `d = dispersion`. (Tab bar `d = 2`: red bends 40% further than blue; glass `d = .4`: 8%.)
8. Light grey (only when the filter, not the glass, lights the rim; i.e. `!paintsLight`): `facing = |x/hw*cos(a) + y/hh*sin(a)|`; `glow = L.glow * clamp((facing - glowStart)/glowRange, 0, 1)^L.glowPower * falloff`,
   `glowStart = (1-spread)*sqrt2`, `glowRange = spread*sqrt2`; `rim = L.edge * max(0, 1 + sdf/L.edgeWidth) * facing^L.edgePower`; `amount = min(1, glow + rim)`;
   stored grey `127*amount + 128`, alpha `min(1, falloff*20, -sdf)`. Light parameter sets (`lens.ts:96-97`):
   `DARK = {angle 45, glow .4, spread .5, glowPower 1.5, edge .5, edgeWidth 2, edgePower 1.5}`, `LIGHT = {angle 30, glow .4, spread .5, glowPower 2, edge .5, edgeWidth 1.5, edgePower 1}`.
   Applied as `lit = shine*white*(grey-128)/255 + bent` (`:468-474`), or multiplied-in shade for a light page with negative shine (`:475-482`; shine -1.5 on light). Dark/light is `--lg-lens-shine`.
9. Shape mask grey = `falloff` with alpha = antialiased inside-ness (`clamp(-sdf, 0, 1)`), used for the stand-in cut (`:236`) and to weight the tab-bar soften blur.

Test spec (`test/lens.test.tsx:10-49`) for a 60x30 r15 lens, depth 2, dome 6, splay .4: the middle is exactly neutral (grey 128, light alpha 0); right rim `across < 128`, left rim `> 128`;
top rim `down > 128`, bottom `< 128`; down map is vertically antisymmetric; band is the same fraction of the size for 40 and 160 px `relative` glass.
These are good property tests for the Kotlin port (section 11).

#### Stand-in content ("replaces", `lens.ts:16-18,705-713`)

Switch and slider do not refract a live backdrop. A lifted thumb shows a **stand-in** (a second copy of the track drawn differently) only inside the lens, and the real track is masked out
under it: `switch-inset` track is drawn smaller (width `(100%-24px)*(.85 + .1*lift)`, height `(100%-12px)*.75*(.525 + .45*lift)`, `styles.css:742-747`) and the slider "magnified" track
thicker (`--lg-slider-line` 8 px * `(.6 + .4*lift)`, `:810-819`), so the glass thumb looks like a lens that magnifies the track. In Compose, do this in draw code
(draw track normally; when lifted, draw the stand-in clipped to the thumb rounded-rect and run the lens shader on it). No mask images needed.

#### Ink mode (text/icons through a lens; `lens.ts:67-75,321-364,393-395`)

Labels have most of their colour in alpha, which R,G,B share; splitting channel rays and averaging would only blur. Glisse's fix: treat labels as ink: take alpha of whichever
channel covers most and reconstruct each channel's own coverage against assumed page white/black. In AGSL this problem is smaller because we sample the actual composite
(labels over the bar), but if a layer holds labels on transparent, do: `out.rgb = (sampleR(uvR).r, sampleG(uvG).g, sampleB(uvB).b)` on premultiplied colour,
`out.a = max(aR, aG, aB)`. Provide a `ink` flag to switch that, off for composites. Port the idea, not the SVG matrices.

#### Soften blur (`blur`, tab lens `blur: 2`, `lens.ts:451-463,531-532`)

A Gaussian (std-dev 2 px * amount) applied only "as far as the rim bends" (weighted by the shape grey). Compose: `RenderEffect.createChainEffect(blur(2), lens)` then mix by `falloff` in the
shader, or approximate with a 5-tap line average along the displacement. Optional polish; default off for first milestone.

#### Backdrop vs content filtering

glisse filters *content it owns* (works in all browsers), and in Chromium additionally `backdrop-filter: url(#f)` on arbitrary content behind glass (`refraction.ts:208-209,314-317,
lens.ts:855-878`). On Android neither exists; the equivalent is: capture the content behind the glass into a `GraphicsLayer` (or draw the content to a `RenderNode` once and reuse), and run
an AGSL `RuntimeShader` as a `RenderEffect` on the glass layer's copy. That is the Convx/Kyant "backdrop" architecture (`convx-glass.md` 1-3) and is the one thing glisse cannot teach us.
So: **glisse supplies the lens maths and tuning; Convx's research supplies the capture pipeline, re-record policy, pooling and fallbacks.**

#### AGSL translation of the lens (our port of `lensMap` + `place`)

The pre-rendered maps become analytic: the shader evaluates steps 1-7 per pixel from uniforms. `arcRadius*/arcScale*` are computed on the CPU (the 200-step integral) once per size.

```glsl
// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
uniform shader content;          // backdrop / stand-in, in lens-local px
uniform float2 size;             // lens w,h
uniform float  radius;
uniform float  depth, ramp;      // band width px, 1/(depth*sqrt2)
uniform float2 innerHalf;        // max(0, half - depth)
uniform float  innerRadius;
uniform float2 arcR, arcS;       // dome arc radius & scale for across (x) / down (y); arcR<=0 => linear
uniform float  splay, splayReach;
uniform float  reach;            // strength * min(w,h) * amount (px)
uniform float3 spectrum;         // [1+.2d, 1+.1d, 1]

float sdRound(float2 p, float2 hb, float r) {
  float2 q = abs(p) - hb + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float erfApprox(float x) { return tanh(1.7724538509 * x); }
float slopeOf(float a, float R, float S) { float i = min(a, 0.999 * R); return i / sqrt(R * R - i * i) * S; }

half4 main(float2 frag) {
  float2 hb = size * 0.5;
  float2 p  = frag - hb;
  float edge = sdRound(p, hb, radius);
  if (edge >= 0.0) return content.eval(frag);
  float2 a = abs(p);
  float dx = arcR.x > 0.0 ? sign(p.x) * slopeOf(a.x, arcR.x, arcS.x) : clamp(p.x / hb.x, -1.0, 1.0);
  float dy = arcR.y > 0.0 ? sign(p.y) * slopeOf(a.y, arcR.y, arcS.y) : clamp(p.y / hb.y, -1.0, 1.0);
  if (splay < 1.0 && splayReach > 0.0) {                       // lens.ts:220-233
    float kx = max(0.0, 1.0 - (hb.y - a.y) / splayReach) * (1.0 - splay);
    float ky = max(0.0, 1.0 - (hb.x - a.x) / splayReach) * (1.0 - splay);
    float before = length(float2(dx, dy));
    dx *= 1.0 - kx; dy *= 1.0 - ky;
    float after = length(float2(dx, dy));
    if (after > 0.001) { dx *= before / after; dy *= before / after; }
  }
  float fall = 0.5 * (1.0 + erfApprox(sdRound(p, innerHalf, innerRadius) * ramp));  // lens.ts:235
  float2 off = -0.5 * float2(dx, dy) * fall * reach;           // sampling offset (inward)
  half4 r = content.eval(frag + off * spectrum.x);
  half4 g = content.eval(frag + off * spectrum.y);
  half4 b = content.eval(frag + off * spectrum.z);
  return half4(r.r, g.g, b.b, max(r.a, max(g.a, b.a)));
}
```

Notes: (a) glisse bends in two sequential passes (across, then down; `lens.ts:430-447`) because SVG displacement can only read one axis per pass; the shader's joint 2D offset is the intended behaviour and is
cheaper. (b) On wide-gamut displays glisse works in greys only (`lens.ts:20-26`): irrelevant for an analytic shader. (c) `amount` (0..1 lift) scales `reach`; also drive blur/alpha with it so the bend
never pops on at full strength (`segments.tsx:161-163`). (d) AGSL needs `RenderEffect.createRuntimeShaderEffect(shader, "content")` (API 33+); minSdk 34 in this project makes that available. (e) Provide a
no-shader fallback (blur + saturation + fill + rim, nothing refracted) per `convx-glass.md` 6.

### 5.2 Selection lens with Snell's law (`refraction.ts:333-505`)

Used for the **lifted tab-bar/segmented puck on a light page**, where a faint lens is invisible against light glass. This one is physically derived:

```
bezel     = clamp(min(w,h) * 0.12, 4, 10)         // rim band width px     (:367)
thickness = clamp(min(w,h) * 0.20, 8, 24)         // glass slab thickness  (:368)
profile(bezel, thickness): 128 samples, for i in 0..127:
  t        = max(0.001, i/127)                    // 0 = edge, 1 = inner end of the bezel
  surface  = (1 - (1-t)^4)^(1/4)                  // convex squircle height profile       (:356)
  incident = atan( (1-t)^3 / surface^3 )          // angle from the surface slope         (:357)
  refracted= asin( sin(incident) / 1.5 )          // Snell, n = 1.5 glass                 (:358)
  shift[i] = tan(incident - refracted) * (thickness + surface * bezel)   // lateral exit displacement (:359)
maximum = max(shift)
displacement field (RG, 128 in B): R = 127.5 + nx * shift(depth) / maximum * 127.5 ; G same with ny   (:402-403)
feDisplacementMap scale = maximum * 2 ; channel factors R 1.035, G 1.000, B 0.965                      (:441,463-465)
```
`depth` is the distance inside the edge in px; `shift` is linearly interpolated from the 128-sample table when `depth < bezel` and 0 beyond (`:393-399`). `(nx, ny)` is the outward normal at the nearest edge
(corner-aware, `:389-390`). Displacement points outward (content pushed outwards at the rim, "pushed outwards, split into red, green and blue", `:335`).

Highlight layers (bitmap overlay, computed in the same loop): `light = -(nx+ny)/sqrt2`; `glint = max(light,0)^3`; `reflected = max(-light,0)^3`; three gaussian bands at distances `[0.18, 0.42, 0.66]` px with width `0.22`;
`alpha = peak * (0.06 + 0.8*glint + 0.35*reflected) * 0.85`; channel tint `255*(0.65 + 0.35*band_c/peak)` for a little iridescence (`:407-419`).
"Bright" rim for light pages, from iOS 26.1 measurements: white line ~1.2 px wide just inside the edge, lifting the backdrop 60-70% toward white; bands at `[.4,.5,.6]` width `.4`, alpha `top*(.55 + .3*glint + .15*reflected)`,
colour `255*(.85 + .15*band_c/top)` (`:420-429`).

**Port to Compose**: the 128-sample profile is a CPU precompute (cache by `bezel, thickness`) uploaded as a small float array or `1xN` image shader; the shader maps `depth -> shift` and displaces **outward** along the normal,
three taps with `1.035/1/0.965`. Or replace the table lookup with the closed form evaluated per pixel (`pow`, `atan`, `asin` all exist in AGSL). The highlight is three gaussians + `glint`/`reflected`; evaluate in the same shader. **Port directly (MIT, with the kube.io attribution comment).**
Compared with 5.1, this lens bends *outward* (edge shows material from the middle pushed to the rim) while 5.1 pulls *inward* (rim shows content from further inside); iOS 26 uses both across components. Provide both functions in one shader file with a `mode` uniform.

---

## 6. Switch (`src/switch.tsx`, `styles.css:653-768`)

Geometry (CSS scale 0.375): track `160x67 * .375 = 60 x 25.1 px` (`:685-691`); thumb element `146x92 * .375 = 54.75 x 34.5 px`, `left = -21.95 * .375 = -8.23 px`, rest scale **0.65** (-> 35.6 x 22.4 px visible),
lifted scale `0.65 + 0.25*lift` = 0.90 (-> 49.3 x 31.0 px) (`switch.tsx:79,92`, `styles.css:749-755`). Travel `TRAVEL = 57.9 * .375 = 21.71 px` (`switch.tsx:25`). Hit area `::before` inset
`min(0px, (25.1 - 44)/2) -8px` -> at least 44 px tall, 8 px wider each side (`styles.css:701-705`). Disabled opacity .35. Thumb is a white `lg-thumb-face` whose opacity is `1 - .85*lift` (`switch.tsx:93`) so held it becomes
mostly clear glass (lens) with the stand-in track inside (section 5.1).

State/motion: three springs: `position` (1000/80), `color` (1000/80, drives the green fill opacity `unit(value)`), `lift` (2000/80) (`switch.tsx:84-97`).
Gestures (`:122-179`):
- pointerdown (primary, button 0, not disabled): `preventDefault`, setPointerCapture, `lift.set(1)`, record start x/y, `from = checked?1:0`, `at = now`.
- move: `dx, dy`; if not `held` and `dy > 10 && dy > |dx| + 4` -> treat as scroll, `settle()` without change (`:144`); deadzone `|dx| < 4` (`:148`);
  `value = from + dx/TRAVEL`; **rubber band** beyond ends `overshoot = value<0 ? value : value>1 ? value-1 : 0; position.set(unit(value) + overshoot/22)` (`:150-152`); colour flips when `value > .5`.
- up: `value = from + dx/TRAVEL`; a drag lands where it was let go (`moved ? value > 0.5`), a tap toggles only if released within 24 px vertically of the box (`near`, `:162-163`); then `lift.set(0)`, `requestAnimationFrame(sync)`.
- keyboard/click: `click` toggles unless `ignoreClick` (set after a pointer interaction) (`:176-179`); `role=switch`, `aria-checked`.
- `touchmove` `preventDefault` once held (`:166-168`).

Port to Compose: `Modifier.toggleable(role = Role.Switch)` for semantics + custom `pointerInput` for the drag; `Animatable` x3 with the same specs (zeta 1.265/1.265/0.894). **Port directly**; the lens and the stand-in track
via the AGSL lens with `Switch` tuning row. Convx better: velocity-aware release (`convx-glass.md` 7.3 toggle, `convx-motion.md` 5.1).

## 7. Slider (`src/slider.tsx`, `styles.css:770-903`)

Native `<input type=range>` for all interaction (keyboard, a11y, step/min/max), a purely visual glass thumb drawn beneath it; the native thumb is invisible and only takes the touch (`:860-876`).
Layout: 40 px tall; rail 6 px at `top: 21px`, inset 16 px, radius 3 (`:785-791`); fill width `(100% - 36px)*progress + 18px` so it ends under the thumb centre (`:796-802`); thumb 60x40, `scale .6` at rest
(36x24) -> `.6 + .4*lift` = 1.0 held (60x40), left `(100% - 36px)*progress - 12px` (`:831-837`); native thumb 36x40 hit target (`:862-864`). Lift spring 2000/80 on pointerdown, release on
`pointerup|pointercancel|lostpointercapture|blur` and `window pointerup` (`slider.tsx:68-93`). Face opacity `1 - .85*lift`. Thumb material = section 4.2 thumb material. Lens row: strength .75, d .65, depth 2, dome 5, splay .5.
Magnified stand-in track: height `--lg-slider-line (8px) * (.6 + .4*lift)`; near the ends the stand-in fill "gives way" from the thumb centre to the track end so an empty slider shows no fill through the thumb and a full one no bare track (`styles.css:821-829`).

Port: Material3 `Slider` is not appropriate; write a `Modifier.draggable`/`pointerInput` slider with `semantics { setProgress; progressBarRangeInfo }` for TalkBack. Port sizes/lift/lens directly. Convx's
`ElasticVolumeSlider` (`convx-motion.md` 7.6) shows rubber-band at the ends; glisse has none (value just clamps).

## 8. Tab bar and segmented control (`src/internal/segments.tsx`, `tab-bar.tsx`, `segmented-control.tsx`, `styles.css:229-569`)

One engine, two skins (`kind: "tab-bar" | "segmented"`). Roles: tab bar = `tablist` (or `navigation` if all items have `href`), segmented = `radiogroup` with `radio` buttons.

### 8.1 Geometry tokens

| | Tab bar (labels) | Tab bar (icons) | Segmented |
|---|---|---|---|
| tab width x height | 84 x 32 | 72 x 52 | 84 x 32 |
| outer inset (bar padding) | 4 | 4 | 0 |
| thumb inset | 0 | 0 | 2 |
| outer radius | `h/2 + inset` | same | `h/2` |
| lens grow when lifted (x, y) | 27 x 20 | 24 x 16 | 24 x 16 |
| label font | 14 | 10 / 500 | 13 (selected 500) |
| icon | | 26 px, gap 1 | |
| selected colour | `--lg-foreground` | `--lg-fill` | `--lg-segment-label` |
(`styles.css:233-239,358-377,512-535`.) Segmented is "measured from UIKit: a 32px capsule, 13px labels, thumb inset 2px; held, the thumb fades and the lens lifts in its place, 24px wider and 16px taller" (`:505-511`).

### 8.2 Selection ("puck") motion

State `{x, lift}` (`segments.tsx:116`); two springs, retuned per gesture phase (`:175-190`): `REST = [1024, 58.9, 676, 47.8]`, `DRAG = [2500, 94, 576, 44.2]` for (slide stiffness, slide damping, lift stiffness, lift damping).
Rest->selected uses REST; during a touch uses DRAG (stiffer: the puck tracks the finger tightly). `paint()` (`:142-173`):
```
sx = 1 + (growX / width)  * lift          // lens grows by growX px at full lift
sy = 1 + (growY / height) * lift
transform = translateX(x) scale(sx, sy)   // applied to lens, tint, (thumb)
tint row counter-scaled and counter-translated so selected-label copies stay put (`:153`)
```
`slot = (barInnerWidth) / tabs`; `width = slot - 2*inset`; target `x = active * slot` (`:131-140,200`).

Gesture (`:204-288`): pointerdown on a tab (primary, button 0, no modifier key): measure, `gesture = {anchor = tabIndex*slot, ...}`, `lift -> 1` (instant if first show), light spot `--lg-x` follows finger
(`:207-210`). Move: `|dx| > 6` => `moved`, setPointerCapture on bar; `x target = clamp(anchor + dx, 0, slot*(n-1))` (`:204-205,255-259`). Up: `index = round(position / slot)`; taps count only if released within
24 px vertically of the bar (`near`, `:272`); a drag selects wherever released, even off the bar (test `tab-bar.test.tsx:66-74`); `click()` is invoked on the destination tab so navigation links work;
`suppressClick` swallows the browser's click (`:221,296-301`). Keyboard: ArrowLeft/Right/Home/End select and focus (`:312-325`). Hold rule: touchmove default-prevented only if `held` (`:290-294`).
Visibility change / window blur / resize reset the gesture (`:327-339`).

### 8.3 Lift and light-page staging (`segments.tsx:62-63,161-172`, CSS `:425-503`)

`UNDER = 0.5`. Derived from `lift`: `amount = clamp(lift,0,1)` drives lens bend + blur; `under = clamp((lift - .5)/.5, 0, 1)`; `rest = clamp(1 - lift/.5, 0, 1)` fades the at-rest grey selection (`--lg-rest`).
On a light page (`--lg-lens-shine < 0` read on each hold, `:232`): the grey selection fades during the first half of the lift, then the lens drops *under* the labels (`data-under`, z-index 0) and its fill
(`--lg-under`) and the backdrop bend (`backdrop?.update(under)`) grow from zero: "so the selected label never changes colour at once" (`styles.css:484-489`). On a dark page the lens simply lies over the labels. The lifted lens's
shadow is `0 7px 11px` scaled `1/lift-y` (kept to iOS size despite the vertical stretch) with colour `--lg-lens-drop * lift` (`:434-435`).
Z-layers (DOM): glass, [segmented thumb], real tabs (hit targets, muted colour), "lensed" copy of the labels (full colour, visible only through the lens), "tint" (selected label coloured and clipped to the selection at rest), lens.
The tab's own label turns transparent while the selection is over it so it is never drawn twice ("fringes", `:336-347`).

### 8.4 Port to Compose

- Layout: one `Row` of equal-weight slots measured once (`Layout`); `selectionX: Animatable<Float>`, `lift: Animatable<Float>`; semantic `tab`/`selectableGroup` + `Role.Tab`/`Role.RadioButton` with `selected`.
- Drawing: do **not** reproduce the four stacked DOM copies. In Compose draw the bar's labels once; the lifted lens is a `RenderEffect` shader on a `graphicsLayer` over the puck rect, sampling the
  already-drawn labels+glass (via the backdrop layer or `Modifier.graphicsLayer { renderEffect }` on the label container with the puck rect uniform). The "selected colour inside the selection" at rest is a clip of a second
  coloured label draw (this one is cheap; it is exactly glisse's `tint`). Counter-scaling formula (`:153`) is only needed if the tint row is drawn inside the scaled puck; with `clipRect(puckRect)` in the parent no counter-scale is required.
- Port directly: tokens (8.1), spring pairs REST/DRAG, `UNDER` staging, thresholds (6 px drag start, 24 px tap slop, round-to-slot), keyboard, `HOLD`. Lens tuning from 5.1 (`LENS`, `SEGMENT_LENS`) and the Snell lens 5.2 for the light-page bright rim.
- Convx does better (`convx-glass.md` 7.2, `convx-motion.md` 5.1, 7.1-7.2, 6): velocity-driven squash/stretch of the puck, release-velocity handoff + projected target, overscroll shift at the ends,
  tab-bar fold/morph to a circle, one shared backdrop layer so the puck sees the tinted label row. glisse has none of these; use glisse for exact iOS-measured sizes and the staged lift, Convx techniques for the physical feel.

---

## 9. Menu (`src/menu.tsx`, `styles.css:905-1083`)

### 9.1 Structure and anchoring

A trigger `Button` (glass, icon-only 40 px circle, default ellipsis glyph, `aria-haspopup="menu"`, `aria-expanded`) and, while `phase != "closed"`, an absolutely positioned card
(`.lg-menu-card { position:absolute; top:0; right:0; width:200px; border-radius:20px; z-index:10 }`, `styles.css:912-926`). `align="end"` (default) pins the card's top-right to the trigger's top-right; `align="start"`
pins top-left (`:928-932`). **There is no collision/flip logic**: the card always opens downward/inward from the trigger corner; the host page must leave room. The card is 200 px wide (items shrink-wrap vertically).
Phases: `closed -> open -> leaving -> closed` (`menu.tsx:29,43`).

### 9.2 Open/close morph ("the glass grows out of the trigger", `menu.tsx:72-124`)

Measure `from = trigger size (40x40)`, `to = card size`; `origin = {x: trailing ? to.w - from.w/2 : from.w/2, y: from.h/2}` (centre of the trigger inside the card; `:78`). One master spring `t` in 0..1 (clamped for drawing):
open `600/46`, close `900/57` (`tune` on direction change, `:116-124`). Derived at each frame (`:85-114`):
```
width  = from.w + (to.w - from.w) * t ; height likewise          // surface grows from trigger rect to card rect, anchored to the trailing/leading edge
scale  = 0.5 + 0.5*t                                              // items scale up from the trigger centre
surface.opacity = clamp(t/0.2)         trigger.opacity = 1 - clamp(t/0.2)     // trigger swaps to surface in the first 20 %
drift  = 0.3*t ; glyph translate = (surfaceCentre - origin) * drift, glyph scale = 1 + 0.6*t, glyph blur = 8*t px, glyph opacity = 1 - clamp(t/0.45)
content.opacity = leaving ? clamp((t - 0.55)/0.45) : clamp((t - 0.15)/0.6)    // items fade in later than the glass; fade out earlier
content.blur   = 6*(1 - t) px ; content.transform = scale(scale) about `origin`
content.clip   = inset(...) mapped back through the scale so items never leave the glass rect, corner radius = r/scale
settled (t == 1): all inline styles cleared
leaving && value == 0 -> setPhase("closed")                       // unmount only after the spring finishes
```
Reduced motion: card `lg-menu-fade` 100 ms fade, plate transition only opacity (`styles.css:1069-1083`).

### 9.3 Touch behaviour

- Press on the card (`menu.tsx:187-195`): capture pointer, `card.dataset.pressed`, `scale.set(1 + 5/max(w,h,200))` (so +5 px on a 200 px card, never more than 2.5%), highlight the row under the finger.
- Drag (`menu.tsx:196-209`): row under the finger highlighted continuously ("scrubbing"), card follows the finger with **resistance**: `resistance(d) = sign(d) * 7 * (1 - exp(-|d| * 0.12 / 7))` (`menu.tsx:32`),
  saturating at 7 px, applied to x and y with `instant` springs (`x.set(..., true)`), released with `600/30` springs.
- Release over a row selects it (`pointerup` -> `rowAt()` -> `click()`); a 500 ms `suppressClickUntil` swallows the follow-up synthetic click (`menu.tsx:210-222`).
- Mouse hover moves the highlight plate; leaving the card clears it (`menu.tsx:196-226`).
- The **plate** (`.lg-menu-plate`, row highlight) is a single absolutely positioned element that slides between rows: `transition: transform 100ms cubic-bezier(.2,.8,.2,1), opacity 80ms ease-out`; the first highlight is placed
  with `transition: none` then re-enabled so it appears in place (`menu.tsx:159-181`). While scrubbing, per-row hover backgrounds are disabled (`styles.css:1049-1052`).
- Dismissal: Escape (focus returns to trigger), Tab (closes, no refocus), outside pointerdown in capture phase (`:237-262,270`), selecting an item (`close()` then `onSelect`), trigger click toggles.
  Arrow Up/Down/Home/End move focus with wrap (`:244-254`); first enabled item gets focus on open (`:128`). Disabled items are skipped.
- Item rows: `min-height 32`, `padding 6 12`, `gap 10`, `border-radius 14`, 16 px icons, label 14 px; destructive = `--lg-destructive`; disabled `opacity .38`; separators `margin 7 11`, 0.5 px `--lg-separator`;
  card content padding 6 (`styles.css:980-985,1015-1067`). Pressed card gets a diagonal white sheen overlay (`linear-gradient(135deg, white .11, .025)`, 120 ms) (`:987-1000`).

### 9.4 Port to Compose

Keep **tokens, the master-spring-with-derived-channels approach and the content/glyph/trigger crossfade timings** (all numbers above). Implement as an anchored `Popup` or in-window overlay `Box`
(`convx-overlays.md` recommends an in-window sibling `Box` for testability) driven by one `Animatable<Float>` with the two spring specs; apply `graphicsLayer { scaleX=scaleY=scale; transformOrigin }`,
`Modifier.blur(6.dp * (1-t))` (API 31+), `clip` via a custom `Shape` for the shrinking rect, and an `alpha` mapping. The sliding highlight plate is an `Animatable<Offset>` with `tween(100, CubicBezierEasing(.2f,.8f,.2f,1f))`.
Accessibility: `Role.DropdownList`/menu semantics with `onClick` per item; TalkBack needs focus handling, `BackHandler` for dismiss.
**Convx does better** (`convx-overlays.md` sections 1-3): placement and flip math (`above = spaceBelow < menuHeight + gap + margin`, clamped to screen) which glisse lacks entirely; the Apple-style lift-and-preview variant; submenus; per-item
press, scrim dimming. Take glisse for the one-morph motion model and numbers, Convx for placement, scrim and preview.

---

## 10. Input and SearchField (`src/input.tsx`, `search-field.tsx`, `styles.css:571-651`)

`Input` is a glass pill wrapping a native `<input>`: `height 40`, `padding 0 14px`, `border-radius 20`, `gap 10`, `max-width 360`, font 14, glass fill `--lg-glass`; **focus-within -> `--lg-glass-active`** with a 200 ms
background transition (`styles.css:573-589`). Placeholder colour `--lg-muted`. Optional leading `icon` (16x16, `--lg-muted`). Clicking anywhere on the pill (`onPointerDown`) focuses the input (`input.tsx:38-40`).
Disabled: `opacity .35`. For `type="search"` with a value and not disabled, a **clear button** appears: 32x32 circle, `margin-right: -8px`, 14 px "x" icon (`m4.5 4.5 7 7m0-7-7 7`, stroke 1.5 round, 16 viewBox),
`pointerdown` default-prevented so the input keeps focus, click sets `""` and refocuses (`input.tsx:44-60`, `styles.css:629-647`). `SearchField` = `Input type="search"` with default magnifier icon
(circle `cx7 cy7 r4.75` + path `m10.5 10.5 3.25 3.25`, 16 viewBox, stroke 1.5, round) and placeholder "Search" (`search-field.tsx:7-20`). The native cancel button is hidden (`styles.css:608-610`).

Port: `BasicTextField` in a glass `Box` (pill, 40 dp); `interactionSource.collectIsFocusedAsState()` -> animate fill to `glassActive` over 200 ms; `Modifier.clickable(indication = null) { focusRequester.requestFocus() }` on the pill;
clear button conditional on non-empty text with `pointerInput` that does not steal focus; IME action Search. Port directly (no animation complexity). **Convx does better**: nothing specific found in the reports; the hig
search bar could be consulted, but glisse's spec is enough.

## 11. Button (`src/button.tsx`, `styles.css:132-227`)

Glass variant (default) and `filled` (accent `--lg-fill`, white text, press `color-mix(85% black)`, hover 88% white); `destructive` = tint `--lg-destructive`; `tint` any colour (label colour for glass, fill for filled). Sizes:
`height 40`, `padding 0 16px` (with icon `padding-left 12`), `border-radius 20`, `gap 8`, font 14, icon 20 px; icon-only = 40x40 circle (requires an accessible label). Disabled: `opacity .35`, no state colours.
Focus ring: `outline 2px solid --lg-ring; offset 2px` (shared by tab, switch, menu item, `:221-227`). Uses `usePress` (section 3.1) and glass refraction (section 5.1, `GLASS` row) on the button itself.
Port: `GlassButton` composable (`Modifier.glass(...)` + press `Modifier.node`). **Port directly.**

---

## 12. Port-to-Compose summary

### 12.1 Module layout (suggested; do not copy Convx's module names)

```
android/glass/            (Kotlin library, no app code)
  GlassTokens.kt          // section 4.1 (light/dark) + sizes from 6-11
  Spring.kt               // section 2.3 ported solver for tests + helpers: springSpec(stiffness, damping)
  Press.kt                // section 3.1 Modifier.node
  Hold.kt                 // section 3.2
  GlassRim.kt / glass_rim.agsl   // section 4.3
  GlassLens.kt / glass_lens.agsl // section 5.1 (+ 5.2 Snell mode)
  GlassSurface.kt         // backdrop capture + effect chain (from the Convx/Kyant architecture, our own code)
  GlassButton / GlassSwitch / GlassSlider / GlassTabBar / GlassSegmented / GlassMenu / GlassInput
```

### 12.2 Port directly (MIT, with header) vs. take Convx technique

| Piece | glisse | Verdict |
|---|---|---|
| Spring solver + constants | `spring.ts`, all tables | **Port directly** (maths + constants). Use Compose `spring()` with converted ratios in UI; port solver for tests. |
| Button press (grow, rubber-band, 8 px translate, touch vs mouse feel) | `press.ts` | **Port directly.** |
| Hold rule 150 ms | `hold.ts` | **Port directly.** |
| Glass tokens (light/dark) | `styles.css:1-89` | **Port directly** (values), shape as `GlassTokens`. |
| Rim light formula | `refraction.ts:41-94` | **Port directly** (analytic shader). |
| Lens maths (dome slope, splay, erf falloff, dispersion spectrum, light constants) | `lens.ts:96-252,525` | **Port directly** into AGSL (5.1 listing). |
| Snell selection lens + bright rim | `refraction.ts:351-441` | **Port directly** (5.2). |
| Component geometry & lift specs (switch, slider, tab bar, segmented, input, button, menu rows) | components + CSS | **Port directly.** |
| Switch/slider/tab gesture logic (deadzones, rubber-band `/22`, near=24, drag=6, round-to-slot) | components | **Port directly.** |
| Menu morph channel formulas | `menu.tsx:85-114` | **Port directly**; add placement/flip from Convx research. |
| SVG filter graph, `feImage` maps, Safari box units, filter id churn, `MutationObserver`, `data-glisse-backdrop`, `findBackdrop`, `visibleBox` | `lens.ts:289-720`, `refraction.ts:142-286`, `svg.ts` | **Do not port** (web-only). |
| Stacked DOM copies of labels (lensed/tint/row) | `segments.tsx:515-530` | Replace by single draw + clip + shader. |
| Backdrop capture, GraphicsLayer pooling, re-record policy, fallback tiers, 120 Hz performance | -- | **Convx/Kyant research** (`convx-glass.md` 2-6, `convx-performance.md`). |
| Velocity squash/stretch, fling projection, release-velocity handoff, overscroll, tab fold/morph, menu flip/preview/scrim | -- | **Convx research** (`convx-motion.md`, `convx-overlays.md`, `convx-navigation.md`). |

### 12.3 Test plan derived from glisse's tests (all runnable on JVM, no device)

- `lensMap` property tests (`test/lens.test.tsx:10-49`): middle neutral; per-side signs; vertical antisymmetry; `relative` band scales with size. Reproduce against the Kotlin reference function that mirrors the AGSL (keep a CPU
  `lensOffset(x, y)` in Kotlin that the shader is derived from, test it, and golden-image test the shader via Roborazzi on API 34 where AGSL runs under Robolectric native graphics).
- Reach: `strength * min(w,h)` and red scale `1 + .2*d` (`glass.test.tsx:123-124,200,263-275`). Rim: alpha at edge > 0.55, decays to ~0 at 1.4 px, lit corners > 0.45, unlit corners < 0.03,
  dark shine = light shine * 0.3/0.65 (`glass.test.tsx:209-240`).
- Gesture tests (`tab-bar.test.tsx`, `switch.test.tsx`): tap on selected releases lift; drag selects at release even off-bar; tap released >24 px off does nothing; held touch (>=150 ms) blocks scroll, quick swipe does not;
  pointer cancel / lost capture cancels without selection; controlled value that rejects a tap settles back; modified/link taps left to the system. Port as Compose UI tests with `performTouchInput` and a virtual clock.
- Spring: step response vs RK4 for each regime; `tune` mid-flight keeps velocity.
- Screenshot tests: each component at rest / pressed / lifted, light and dark, over a colourful backdrop (so refraction is visible), PNGs opened and judged visually.

### 12.4 Open points for the integrator

1. glisse's `strength` etc. were tuned for CSS px on 1x-3x screens; convert to dp (identical numerically) and keep `density` only for CPU maps (not needed with the analytic shader).
2. Dark rim and tab lens: `--lg-lens-shine` +1 vs -1.5 selects "light" vs "shade" lighting for filter-lit lenses (`lens.ts:468-482`); in the shader use `mix(bent, white, shine*glint)` vs `bent * (1 + shine*glint)` (shade); both small.
3. `ink` handling and `blur` soften are optional polish; ship first without them and compare screenshots against glisse's demo site (`glisse.kelvinzhang.ca`, not reachable from here) only qualitatively.
4. Aave's article and kube.io's article are credited in the comments; keep those credits in the Kotlin header comments next to the glisse MIT line.
5. Verify `RuntimeShader` array/loop limits in AGSL before using the 128-sample table (use closed form instead; no table needed).

---

## Appendix A: glisse MIT licence text (for `android/THIRD_PARTY_NOTICES.md`)

```
MIT License

Copyright (c) 2026 Kelvin Zhang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Required per-file header for ported code:
`Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang`
