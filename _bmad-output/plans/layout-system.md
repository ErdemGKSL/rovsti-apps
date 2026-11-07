# Unit `system`, round 1: one shared layout system

baseSha: 4f51ee64d03f1fa69ab605ee778f6c6559e647fa

## Scope note (read first)

The relayed user request for this workflow run is the Turkish text-box complaint ("code the
mechanics of these text boxes properly, even the default HTML input works better"). The
`shots` and `text-input` units already handled that, and `layout-text-input-r2.md` marked it
done (one `TextField`, 34 core tests, 32 field tests; device checklist in `docs/android.md`).
This unit's computed task is something else: a layout overhaul, translated as an owner
request and backed by an uploaded inbox screenshot. This plan covers that layout overhaul
as assigned, and it must not regress any text field (`01-fields-*.png`,
`02-field-touch-*.png` must stay identical). The orchestrator should confirm with the owner
that the layout overhaul is wanted. If on-device text boxes are still bad, run the
`docs/android.md` text-field checklist on a device and file what fails as a separate unit.
More text-box work cannot be justified from headless shots.

## What I looked at

- Owner device shot (`054d28c4-image.jpg`, Messages inbox, dark).
- `target/shots/messages/01-inbox-{dark,light}.png` (rendered at HEAD, 412x915 dp @2x).
- `docs/screenshots/baseline/{phone,stops}.png` contact sheets.
- Code: `crates/rovsti_ui/src/{components,tab_bar,theme,shots}.rs`,
  `crates/liquid_glass_kit/src/tokens.rs`, `apps/messages/src/{main,inbox,status_ui}.rs`,
  `crates/rovsti_core/src/inbox_filter.rs`.
- No `docs/design/` exists yet, and there are no earlier `layout-system*` plans and no stash.

## Problems seen, with causes in the code

1. **Status avatars run over the text** (device + `01-inbox-*`). `status_ui::entry_avatars`
   forces the stack into `w(44)`, but it draws 3 rings of 26 dp at `ml(-16)`. That is
   26+10+10 = 46 dp plus ring strokes, so it spills about 8 dp into the title column. The
   trailing "3" shows as a bare grey number.
2. **Filter chips wrap onto 2 rows** (`main.rs` `ChatItem::Filters`: `flex_wrap`). They are
   44 dp `glass_pill`s, and `inbox_filter::chip_label` glues the count onto the label ("SMS 277").
   The selected chip is `accent.opacity(0.22)` with accent text. In light mode that is pale
   blue on near-white, at about 2.5:1 contrast.
3. **A WhatsApp/SMS pill on every row** (`inbox::row_element` badges) takes about 70 dp of
   preview width. Rows with both channels show two pills.
4. **The Spam row is loud.** It is a saturated orange 44 dp `avatar("!")` and it sits above
   the real chats.
5. **No safe area.** `large_title` uses a hard `pt(56)`. `TabBar::render` anchors at
   `bottom(TAB_BAR_MARGIN=12)` and ignores the system bottom inset, so on the device the
   bar sits on the gesture handle. `content_bottom_inset()` ignores the safe area, and only
   Phone uses `content_bottom_inset_with(safe_bottom)`. Messages' `ChatItem::Tail` and the
   pages in Contacts and Stops leave the last row under the bar.
6. **The tab bar puck** is computed inline in `bar_body` (`puck_cx = BAR_PAD + (puck+0.5)*tab_w`,
   width `tab_w*grow*sx`). Under press or stretch it grows past the bar's 4 dp inner edge,
   and on the device it looks offset or double-rimmed. The geometry has no test.
7. **Rhythm is inconsistent.** Avatars are 44 dp, but `list_row` uses `min_h 58` with
   `py 8`, the inbox row uses `py 8` and the chip row `py 6`. Separators are full-bleed in
   the inbox but inset elsewhere, and are 0.5 dp in one place and 0.33 dp in another. The
   header's 3 action buttons (about 44 dp, glass) sit on a 34 dp title with no shared
   baseline.
8. **Phone and Stops sheets** (baseline sheets): settings rows have tiny secondary text,
   and light-mode segmented and chips have low contrast. Stops dark uses an olive backdrop
   with grey cards, and its buttons have poor contrast. Those fixes belong to the per-app
   units, but they need the same tokens.

## The system (summary; full spec goes to `docs/design/layout.md` in step 1)

- Grid is 4 dp. Spacing scale: `XS 4, S 8, M 12, L 16, XL 20, XXL 24, XXXL 32`. Screen
  margin 16.
- Type roles map onto kit `typography`: LargeTitle 34/41 w800, Title 22/28 w700, Headline
  16/21 w600, Body 16/22 w400, Subhead 15/20 w400, Footnote 13/18, Caption 12/16. Row title
  = Headline, row subtitle = Subhead in secondary colour, row meta (time) = Footnote.
- Safe area: `layout::safe_area(window)` = max(window `insets().safe_area`, the shot
  simulation). Top of content = safe.top + 8. Tab bar bottom = safe.bottom + 8. Content
  bottom inset = safe.bottom + 8 + 56 (bar) + 16 (gap) + keyboard.
- List row: 16 margin | avatar 48 | 12 | text column | 8 | trailing column (min 44, right
  aligned). Min height 72, vertical padding 12. Line 1 is title + time, line 2 is preview +
  badge. The separator is 1 physical px (0.5 dp at 2x) and starts under the text
  (16+48+12 = 76 dp).
- Source indicator: a 16 dp circle at the avatar's bottom-right with a 2 dp ring in the
  background colour, a WhatsApp or SMS glyph inside (10 dp). No pill in the row.
- Page header: large title plus 40 dp circular glass buttons in a 44 dp hit box, 8 dp
  apart, centred on the title's cap height. At most 2 buttons visible, and the rest go
  into a "more" menu.
- Filter chips: one row with `overflow_x_scroll`, 32 dp visual inside a 44 dp hit, padding
  12 dp, 8 dp gap, the 16 dp margins scroll with the content. The count is a separate
  18 dp badge in a Caption2 pill. Selected = solid accent with white text, unselected =
  secondary fill with label text.
- Section header: Footnote w600, uppercase off, secondary colour, 16 dp margin, 24 dp top
  and 8 dp bottom.
- Empty state: 48 dp icon, Title3, Subhead, optional glass pill action, centred with a
  max width of 320.
- Colour: text roles keep at least 4.5:1 on their backgrounds in both themes, checked by a
  unit test on `theme::colors` (relative-luminance formula, pure f32).

## Steps

### 1. Write `docs/design/layout.md` (docs only)
Write the audit above in full, per app with screen file names: messages `01..09`, phone
from the baseline sheet (recents, contacts, settings, keypad), contacts `01..13`, stops
(trip, saved, settings, sheets). Then write the full system spec above: every number in
dp, the dark and light colour roles with their measured contrast, the row anatomy diagram
(ASCII), sheets and dialogs (grabber 36x5, top radius 38, 16 margin, actions 50 dp tall),
and the touch minimum of 44. Link it from `docs/architecture.md` and `CLAUDE.md`'s UI
line.
**Acceptance:** the file exists, every audit item names a PNG, and all checks pass
(nothing compiled changes).

### 2. `rovsti_ui::layout`: tokens and pure geometry with tests
Add `crates/rovsti_ui/src/layout.rs` (module docs explain the grid) with these parts:
- `pub mod space` (the scale above), `MARGIN`, `ROW_MIN_H = 72`, `AVATAR = 48`,
  `BUTTON = 40`, `HIT = 44`, `CHIP_H = 32`, `SOURCE_BADGE = 16`.
- `pub mod geom`: plain `f32` functions with no gpui types:
  `avatar_stack_width(n, size, overlap, ring) -> f32`,
  `row_text_inset(margin, avatar, gap)`,
  `chip_width(label_w, badge_w: Option<f32>)`,
  `puck_rect(index_pos, count, tab_w, pad, bar_h, grow, sx, sy) -> (x, y, w, h)` clamped
  inside `[pad, bar_w - pad]`,
  `content_bottom(safe_bottom, keyboard) -> f32`,
  `content_top(safe_top) -> f32`,
  `contrast_ratio(fg_rgb, bg_rgb) -> f32`.
- `safe_area(window) -> Edges<Pixels>`, plus a per-thread `set_simulated_safe_area`
  override for shots, on the same pattern as `field::set_selection_chrome`.
- Unit tests: a 3-ring stack at 26/-16 fits its returned width; the puck stays inside the
  bar at press 1.2 and sx 1.3; content_bottom = 8+56+16+safe+kbd; the contrast of a known
  pair is 21:1 for black/white.
**Acceptance:** `cargo test -p rovsti_ui layout` is green and all checks pass. No PNG
changes yet.

### 3. Safe-area-aware tab bar, one inset function, every caller migrated
- `TabBar::render`, `TabBarView` and `bar_body` take `safe_bottom` and anchor at
  `safe_bottom + space::S`. The puck uses `geom::puck_rect`.
- Replace `content_bottom_inset()` and `content_bottom_inset_with()` with one function,
  `rovsti_ui::layout::content_bottom_inset(window) -> Pixels`, which includes the keyboard
  (`ime - consumed`). Migrate every caller: messages `main.rs` 5698 and 5778,
  contacts `main.rs`, `photo_viewer.rs`, `photo_crop.rs`, phone `notice.rs`,
  `recents.rs`, `contacts.rs`, and stops `main.rs`.
- `large_title` top padding becomes `content_top(safe.top)` and drops the hard 56.
- `shots::Rig::open` sets a simulated safe area of top 24 and bottom 16 (a typical
  gesture-nav phone), so every shot shows the real geometry.
**Acceptance:** all checks pass. Re-render all 4 apps and open
`messages/01-inbox-dark.png`, `contacts/01-list-dark.png`, `phone` recents, and
`stops/` saved list. The bar floats 8 dp above a 16 dp bottom inset, and the title clears
24 dp. Scroll the list to its end in a new shot `messages/10-inbox-end-dark.png`: the last
row sits fully above the bar.

### 4. PageHeader, SectionHeader, EmptyState, and a component gallery
In `crates/rovsti_ui/src/components.rs`, or a new `crates/rovsti_ui/src/page.rs`:
- `PageHeader { title, actions: Vec<HeaderAction>, subtitle }`. Actions are 40 dp glass
  circles with a 22 dp icon in a 44 dp hit box, 8 dp gap, and at most 2 visible.
- `section_header`.
- `empty_state(icon, title, body, action)` with sizes per spec.
- Keep `large_title` as a thin wrapper so the other apps compile unchanged.
- Add a shots gallery test `layout` in `rovsti_ui` that writes
  `target/shots/rovsti_ui/03-layout-{dark,light}.png`. It shows a header with 1 and 2
  actions, a section header and an empty state.
**Acceptance:** open both PNGs. Everything aligns to the 16 dp margin, the buttons are
centred with the title, and nothing clips. All checks pass, and the
`shots --features` clippy line passes.

### 5. ListRow, AvatarStack, source badge
- `ListRow` builder with slots `leading`, `title`, `title_accessory` (mute/pin 14 dp
  icons), `meta` (time), `subtitle` (preview, with an optional leading tick),
  `trailing_badge` (unread count, 20 dp min, Caption w600), and `below` (the OTP chip,
  indented to the text inset). It also has `separator(Inset::Text | Inset::None)`. Sizes
  are per spec: 48 avatar and 72 min height.
- `avatar_stack(items, size 28, overlap 10)` sets its width from
  `geom::avatar_stack_width`, so neighbours can never be overlapped. If there are more
  than 3, it shows a "+n" disc.
- `source_badge(kind)` overlay for avatars (16 dp, 2 dp background ring).
- Extend the gallery with rows: unread with a badge, muted and pinned, a long title, an
  OTP chip, a 3-item stack, and an SMS+WhatsApp merged contact (two stacked 16 dp badges,
  overlapping 6 dp).
**Acceptance:** open `03-layout-*.png`. Time and badge columns right-align across rows,
separators start at 76 dp, and the stack does not touch the title. All checks pass.

### 6. FilterChipRow
- Pure: `rovsti_core::inbox_filter::chip_parts(filter, count) -> (&str, Option<String>)`
  (counts over 999 show as "999+"), with tests. Keep `chip_label` for TalkBack
  ("SMS, 277").
- `filter_chip_row(id, chips, selected, on_select)` uses `overflow_x_scroll` (it exists in
  gpui `div.rs:1504`). It is a single row of 32 dp visual chips inside 44 dp hit boxes,
  and the count is a Caption2 badge. Selected = solid accent with white text (unit test:
  contrast of at least 4.5 in both themes, via `geom::contrast_ratio`).
- Add the row to the gallery.
**Acceptance:** the gallery shows a single row that runs off the right edge (scroll
affordance) and the selected chip is readable in light mode. All checks pass.

### 7. Move the Messages inbox onto the system (proof screen)
Edit `apps/messages/src/main.rs` `ChatItem::{Status, Filters, SpamFilter, Tail}` and the
inbox header, `inbox::row_element`, and `status_ui::entry_avatars`:
- Header: `PageHeader` with Search and Compose visible. "Mark all read" moves into the
  compose/more menu or appears only in select mode.
- Status row: `ListRow` with an `avatar_stack`, and the unseen count as an accent badge.
- Filters: `filter_chip_row`.
- Spam: `ListRow` with a 48 dp neutral tile (secondary fill, `LucideShieldAlert` in
  secondary label colour) and a count badge in tertiary. Move it below the chips as a
  quiet row, or show it only when unread > 0. Note the choice in `docs/design/layout.md`.
- Chat rows: `ListRow`, a 48 dp avatar plus `source_badge`, and the per-row pills
  removed. The pure `inbox::badges` stays and feeds the badge.
- Tail: `layout::content_bottom_inset(window)`.
- Update the existing `inbox` tests where layout constants appear.
**Acceptance:** compare the before shots (`01-inbox-*`, `01-inbox-dark-small`,
`02-unread-filter-*`, `03-inbox-empty-*`, `09-status-*` at baseSha, saved to the
scratchpad) with the after shots, and open both sets. There is no overlap in the Status
row, the chips fit on one row, no pills remain, the preview text is wider, the Spam row is
quiet, the light-mode selected chip is readable, and `10-inbox-end-dark` clears the bar.
All checks pass.

### 8. Docs, baselines, full verification
- Update `docs/screenshots.md` with the `layout` gallery, the simulated safe area, and
  `10-inbox-end`.
- Update `docs/design/layout.md` with a "Migrated screens" table: Messages inbox done,
  the rest assigned to the per-app units. Refresh `docs/screenshots/baseline/messages.png`.
- Run every check in the unit's list plus the `shots` clippy line, and confirm that
  `rovsti_ui/01-fields-*.png` and `02-field-touch-*.png` are unchanged (text-box
  regression guard).
**Acceptance:** all checks pass, the baselines are committed, and the docs match the code.
