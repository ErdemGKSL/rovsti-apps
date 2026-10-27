# 04 · Design tokens

Code: `templates/src/tokens.rs` (`Theme`, `Colors`, `radius`, `metrics`,
`typography`, `shadow`, `material`), and the glass presets in `templates/src/glass.rs`.

## Contents
1. Colour
2. Glass presets
3. Materials (frosted)
4. Shape: radii, concentricity, squircles
5. Layout metrics
6. Typography
7. Shadows and elevation
8. Theme switching
9. Common mistakes

---

## 1. Colour (Apple system palette)

| Token | Light | Dark |
|---|---|---|
| accent (systemBlue) | #007AFF | #0A84FF |
| red (selection tint, as in Convx) | #FF3B30 | #FF453A |
| green | #34C759 | #30D158 |
| orange | #FF9500 | #FF9F0A |
| label | #000000 | #FFFFFF |
| secondaryLabel | #3C3C43 @ 60% | #EBEBF5 @ 70% |
| tertiaryLabel | #3C3C43 @ 30% | #EBEBF5 @ 30% |
| separator | #3C3C43 @ 29% | #545458 @ 60% |
| background | #FFFFFF | #000000 (true black) |
| groupedBackground | #F2F2F7 | #000000 |
| secondaryBackground | #FFFFFF | #1C1C1E (tertiary #2C2C2E) |
| fill | #787880 @ 20% | #787880 @ 36% |
| hairline / glass border | black 8% | white 12% |

Rules:
- Use **one** accent colour per app. It marks the selected tab, primary
  buttons and toggles. Everything else is label, secondary label or fill.
- Labels on glass use `label` / `secondary_label`. Never put coloured text on glass,
  except for the selected state.
- When the glass sits over artwork (album covers, photos), mix the artwork's dominant colour
  into the light-theme glass tint at about 0.25α. Do not do this in dark mode.

## 2. Glass presets (`GlassSpec::*`)

| Preset | blur | lens band / pull | sat | tint | Use |
|---|---|---|---|---|---|
| `chrome` | 2 | 12 / 24 | 1.5 | theme fill | tab bar, toolbar, floating buttons |
| `control` | 2 | 16 / 28 | 1.5 | theme fill | buttons, toggles, segmented control |
| `sheet` | 12 (light) / 14 (dark) | 20 / 32 | 1.5 | theme fill | sheets, menus, popovers (legibility first) |
| `side_panel` | 2 | 14 / 20 | 1.2 | fill @ 0.75α | sidebars over busy content |
| `lens` | 0 | 10 / 14, depth, dispersion 0.35 | 1.5 | white 0.04–0.10α | pressed puck, magnifier |

Theme fill (`Theme::glass_fill`):
- Dark: white at 0.15α. Dark glass is a *lift*, so it reads as lighter than the background.
- Light: #F5F5F7 at 0.40α.

Rim: 0.8 px, 0.5α, light from the top-left (225°). Dark outline: 1.6 px centred on the edge,
black 0.32α in both themes, symmetric at all four corners.

## 3. Materials (frosted, non-refracting)

These are fill alphas over a blurred backdrop. Use them for list sections or
backgrounds that must not refract:

| Material | Fill α |
|---|---|
| ultraThin | 0.28 |
| thin | 0.45 |
| regular | 0.65 |
| thick | 0.85 |

## 4. Shape

| Radius | px |
|---|---|
| icon tile (29×29 settings icon) | 7 |
| button | 12 |
| menu | 14 |
| card | 20 |
| grouped list section | 26 |
| sheet | 38 |
| tab bar, chips, toolbars | fully rounded (`rounded_full`) |

**Concentric corners.** A shape inset inside another must have `inner = outer − inset`,
floored at 4 px (`tokens::concentric`). Example: a puck inset 4 px inside a 28 px
tab bar has radius 24. If you get this wrong, it is immediately visible on glass because
the rims run parallel.

**Continuous corners.** Apple's corners are squircle-like (G2 continuous).
GPUI quads are circular arcs. For pills, the difference is negligible. For cards, either
increase the radius by about 10% or use a superellipse SDF in your shader (see 02 §3).
The quad clip and the glass must use the same shape.

## 5. Layout metrics

| Metric | px |
|---|---|
| minimum hit target | 44 × 44 |
| list row | 44 (58 with a subtitle) |
| row inset | 16 |
| section spacing | 35 |
| separator | 0.33 (hairline) |
| tab bar height | 56, floating 12 px from the edges |
| sheet grabber | 36 × 5, radius 2.5 |
| floating glass button | 44–56 diameter |
| toolbar height | 44 (compact) / 52 |

Floating chrome does **not** reserve layout space. The content scrolls underneath
it, which is the whole point of glass. Add bottom padding equal to the
bar height + margin + safe area to scroll views, so the last row can scroll above the bar.

## 6. Typography

The font is SF Pro on Apple platforms. Elsewhere use Inter or the system UI font, and keep
the negative tracking on large sizes.

| Style | size / line | weight | tracking |
|---|---|---|---|
| largeTitle | 34 / 41 | 800 | −0.8 |
| title | 28 / 34 | 800 | −0.7 |
| title2 | 22 / 28 | 700 | −0.4 |
| title3 | 20 / 25 | 700 | −0.3 |
| headline | 16 / 21 | 600 | −0.2 |
| body | 16 / 22 | 400 | −0.2 |
| subheadline | 15 / 20 | 400 | −0.24 |
| footnote | 13 / 18 | 400 | −0.08 |
| caption | 12 / 16 | 400 | 0 |
| caption2 (tab labels) | 11 / 13 | 600 | +0.06 |

In GPUI, use `.text_size(t.size).line_height(t.line).font_weight(t.weight)`.
Tracking is not a `Styled` method in every GPUI build. Skip it if it is unavailable.

## 7. Shadows and elevation

| Context | Shadow |
|---|---|
| glass, light mode | `0 4 16` black 12% |
| glass, dark mode | `0 8 24` black 35% |
| sheet | blur 32, black 10–15% |
| inner shade (shader) | 0.08–0.15 on the edge opposite the light |

Apply with `.shadow(tokens::shadow::glass(dark))` on the same div as the glass.
GPUI paints box shadows before the element's background, so the shadow sits under
the glass and is refracted only by glass painted later.

## 8. Theme switching

- Read the OS theme: `Theme::from_appearance(window.appearance())`. To react to changes,
  `cx.observe_window_appearance(window, |this, window, cx| ..)`.
- Cross-fade over about 280 ms. Do not snap. Keep a `dark_t` spring
  (`motion_tokens::theme_fade()`, ζ 1) rather than a fixed tween: toggling again
  mid-fade then reverses smoothly from where it is. Every frame, derive from it:
  - colours: `Colors::lerp(&light, &dark, dark_t)`,
  - glass: `GlassSpec::chrome(Light).lerp(GlassSpec::chrome(Dark), dark_t)`,
  - shadows: `shadow::glass_mix(dark_t)`.
- **Mix colours in RGB (`tokens::mix`), never by lerping HSL.** HSL interpolation swings
  through unrelated hues, and greys carry arbitrary hues (white is h 0, #F5F5F7 is h ≈ 0.67).
  `GlassSpec::lerp` uses `mix` for its tint.
- The same pattern swaps any palette, such as the demo's per-zone ocean colours: snapshot the
  palette on screen, snap the fade spring to 0, and target 1.

## 9. Common mistakes

- Using black, smoky glass in dark mode. It looks muddy. Use a white 0.15α lift.
- Using a different accent colour per screen.
- Using non-concentric radii for nested pills.
- Reserving layout space for floating bars, so nothing scrolls under them and the glass shows nothing interesting.
- Putting coloured, low-contrast text on glass. Glass needs high-contrast labels.
