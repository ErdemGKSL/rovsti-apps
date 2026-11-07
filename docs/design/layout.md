# Layout system

One shared layout system for Phone, Messages, Contacts and Stops. Part 1 audits the
current screens. Part 2 is the spec that `rovsti_ui` implements (`layout` tokens and
geometry, `PageHeader`, `ListRow`, `AvatarStack`, `FilterChipRow`, `SectionHeader`,
`EmptyState`, one bottom-inset function). All numbers are dp (1 dp = 2 px in the
412 x 915 dp headless shots).

Screens are cited by PNG name. Shots live in `target/shots/<app>/` (see
[../screenshots.md](../screenshots.md)); the committed contact sheets are
`docs/screenshots/baseline/{messages,phone,stops,contacts}.png`. The owner's device
shot of the Messages inbox is called "device shot" below (`054d28c4-image.jpg`, dark,
Xiaomi, gesture navigation).

---

## Part 1. Audit

### Cross-app problems (seen in every app)

| # | Problem | Evidence |
|---|---------|----------|
| X1 | No safe-area handling. Large titles use a hard `pt 56`, the tab bar sits at `bottom 12` and ignores the system bottom inset, so on a gesture-nav phone it lands on the gesture handle. | device shot (bar over the handle), `messages/01-inbox-dark.png`, `contacts/01-list-dark.png`, `phone/03-recents-dark.png`, `stops/06-saved-list-dark.png` |
| X2 | Scrollable content has no bottom inset for the floating bar, so the last row is hidden under it. Only Phone passes `safe_bottom`; Messages' tail, Contacts and Stops do not. | device shot (last row "Yig..." behind the bar), `messages/01-inbox-light.png` (row "Halı Saha" under the bar), `contacts/01-list-dark.png`, `contacts/04-groups-dark.png`, `stops/06-saved-list-dark.png`, `stops/11-settings-dark.png` |
| X3 | Vertical rhythm is inconsistent. Avatars are 44 dp, `list_row` is `min_h 58` / `py 8`, the inbox row is `py 8`, the chip row `py 6`. Separators are full-bleed in the inbox but inset elsewhere, 0.5 dp in one place and 0.33 dp in another. | `messages/01-inbox-dark.png` vs `messages/07-new-conversation-dark.png` vs `contacts/01-list-dark.png` |
| X4 | Tab bar puck is hand-computed inline. It grows past the 4 dp inner edge of the bar under press or stretch and looks off-centre or double-rimmed. | device shot (the selected puck is offset left and clipped), `messages/01-inbox-light.png`, `phone/01-keypad-empty-dark.png`, `stops/01-trip-idle-dark.png` |
| X5 | Secondary text is too faint. Kit `secondary_label` on white is 3.4:1, and the Phone and Stops settings rows use even smaller and fainter captions. | `phone/12-settings-light.png`, `stops/11-settings-light.png`, `messages/12-settings-light.png`, `contacts/13-settings-light.png` |
| X6 | Header action buttons are bigger and heavier than the 34 dp title they sit next to, and have no shared baseline with it. Messages shows three (mark read, search, compose). | device shot, `messages/01-inbox-light.png`, `phone/01-keypad-empty-dark.png` (gear and shield), `contacts/01-list-dark.png` |
| X7 | Selected states in light mode are pale accent on near-white (about 2.5:1), for chips, segmented controls and the selected tab. | `messages/01-inbox-light.png` ("All"), `phone/04-recents-missed-filter-light.png`, `phone/12-settings-light.png` (Haptics), `stops/09-map-options-light.png` |
| X8 | Empty states are a bare line of text with no icon, no hierarchy and no action. | `messages/03-inbox-empty-dark.png`, `contacts/10-trash-empty-dark.png`, `phone/06-contacts-search-typing-dark.png` |
| X9 | Sheets and dialogs differ per app (grabber present or missing, radii, margins, action heights). | `phone/13-setup-sheet-dark.png`, `stops/07-saved-stop-sheet-dark.png`, `stops/12-qr-sheet-dark.png`, `contacts/12-qr-sheet-dark.png`, `messages/11-row-menu-dark.png` |

### Messages

| # | Problem | Screen |
|---|---------|--------|
| M1 | Status row: the stack of three 26 dp avatar rings is forced into a 44 dp slot but needs about 46 dp plus ring strokes (26 + 10 + 10), so it spills about 8 dp over the "Status" title and the "3 new updates" text. The trailing "3" is a bare grey number. | device shot, `messages/01-inbox-dark.png`, `messages/01-inbox-light.png`, `messages/09-status-dark.png` |
| M2 | Filter chips wrap onto two rows (`flex_wrap`). Each is a 44 dp pill with the count glued to the label ("SMS 277", "WhatsApp 230"), so chips are wide and the list starts about 120 dp lower. | device shot, `messages/01-inbox-dark.png`, `messages/01-inbox-light.png`, `messages/01-inbox-dark-small.png` |
| M3 | The selected chip "All" is accent at 22% opacity with accent text: low contrast in dark and about 2.5:1 in light. | device shot, `messages/01-inbox-light.png`, `messages/02-unread-filter-light.png` |
| M4 | Every chat row repeats a large green "WhatsApp" or blue "SMS" pill before the preview. It takes about 70 dp of preview width, rows with both channels show two pills, and the preview is truncated to a few words. | device shot, `messages/01-inbox-dark.png`, `messages/01-inbox-light.png`, `messages/02-unread-filter-dark.png` |
| M5 | The Spam row is a saturated orange 44 dp "!" disc above all real chats. It is the loudest element on the screen. | device shot, `messages/01-inbox-dark.png`, `messages/10-spam-folder-dark.png` |
| M6 | Floating tab bar covers the last row, and the Messages `Tail` item has no inset. | device shot, `messages/01-inbox-dark.png`, `messages/01-inbox-light.png` |
| M7 | Header: three glass buttons of about 44 dp on a 34 dp title. The time column is accent coloured in light (15:35) and grey in dark, and unread badges are not aligned to a trailing column (they sit at different x depending on the time width). | `messages/01-inbox-light.png`, device shot |
| M8 | Row text has three baselines (title, pill, preview) and the OTP "Copy 731924" chip sits at an arbitrary indent under the row. | `messages/01-inbox-light.png` (GARANTI row) |
| M9 | Search results and "No Results" use tiny captions, and the search field and Cancel button are not on the 16 dp margin grid. | `messages/08-global-search-dark.png`, `messages/08-global-search-light.png` |
| M10 | Conversation list in "New message" uses a different avatar size and row height than the inbox, with secondary lines too small to read. | `messages/07-new-conversation-dark.png`, `messages/07-new-conversation-light.png` |
| M11 | Settings page: group labels and descriptions are tiny and low contrast, and rows are denser than elsewhere. | `messages/12-settings-dark.png`, `messages/12-settings-light.png`, `messages/13-whatsapp-link-dark.png` |
| M12 | Conversation screens: the header chips ("WhatsApp", mute, more) are cramped, and the last bubble is close to the composer. | `messages/04-conversation-sms-dark.png`, `messages/05-conversation-whatsapp-group-dark.png`, `messages/06-composer-typing-dark.png` |
| M13 | Row context menu is a long unstructured list over a blurred inbox, and has no grabber or spacing groups. | `messages/11-row-menu-dark.png`, `messages/11-row-menu-light.png` |

### Phone

| # | Problem | Screen |
|---|---------|--------|
| P1 | Keypad: the dial keys are visually small next to the free space, the gear and shield buttons are crammed in the top corner at a different size than the title, and the call button floats close to the tab bar. | `phone/01-keypad-empty-dark.png`, `phone/01-keypad-empty-light.png`, `phone/01-keypad-empty-dark-small.png` |
| P2 | Recents: rows are 44 dp avatar on a 58 dp row, so they are tighter than Messages. The filter chips are tiny, text-only and low contrast; the info icon at the right is not a 44 dp target. | `phone/03-recents-dark.png`, `phone/03-recents-light.png`, `phone/04-recents-missed-filter-light.png` |
| P3 | Recents rows: the missed line is red at small size (13 dp or less) and the time column is faint and not aligned across rows. | `phone/03-recents-dark.png` |
| P4 | Contacts: section index letters are tiny, the A-Z gutter is not reserved so row text can run under it, and the last rows sit under the tab bar. | `phone/05-contacts-list-dark.png`, `phone/05-contacts-list-light.png` |
| P5 | Search field is a thin outline with a faint placeholder, 36 dp tall (under the 44 dp minimum), and is not on the 16 dp margin. | `phone/06-contacts-search-typing-dark.png`, `phone/03-recents-light.png` |
| P6 | Favourites: the tiles are an uneven grid (gaps differ horizontally and vertically), the "Frequent" row has tiny avatars with 10 dp captions. | `phone/07-favorites-dark.png`, `phone/07-favorites-light.png` |
| P7 | Contact detail: action circles are small and their labels are about 10 dp. Section labels are faint. | `phone/08-contact-detail-dark.png`, `phone/08-contact-detail-light.png` |
| P8 | Call details sheet: rows are dense, the destructive "Delete call history" is as loud as the primary action, and nothing is grouped. | `phone/09-call-details-dark.png`, `phone/09-call-details-light.png` |
| P9 | In-call and incoming screens: control labels are about 10 dp and secondary text is faint. Layout is otherwise fine and is out of scope for the grid except margins. | `phone/10-incall-dark.png`, `phone/11-incoming-dark.png` |
| P10 | Settings: row captions are tiny and low contrast, groups run into the bottom bar with no inset, segmented controls are low contrast in light. | `phone/12-settings-dark.png`, `phone/12-settings-light.png` |
| P11 | Setup sheet: card list on a blurred keypad, row subtitles under 12 dp, and the checkmark circles are very faint. | `phone/13-setup-sheet-dark.png`, `phone/13-setup-sheet-light.png` |

### Contacts

| # | Problem | Screen |
|---|---------|--------|
| C1 | Header has "Select" and "+" side by side, and "Select" is a text pill about the size of the title cap height, which feels unbalanced. Search field and "Set up my card" are different widths and corner radii. | `contacts/01-list-dark.png`, `contacts/01-list-light.png` |
| C2 | List: the avatar is 36 to 40 dp with one text line, rows are about 52 dp, the alphabet index is tiny and overlaps the trailing edge, and the last rows hide under the tab bar. | `contacts/01-list-dark.png`, `contacts/01-list-dark-small.png`, `contacts/02-list-search-dark.png` |
| C3 | Search results: no result count or section header, selected row and matching text have no emphasis. | `contacts/02-list-search-dark.png`, `contacts/02-list-search-light.png` |
| C4 | Favorites: the "Upcoming" card, "See all" link and the plain list below have three different paddings and the wish pill is cramped. | `contacts/03-favorites-dark.png`, `contacts/03-favorites-light.png` |
| C5 | Groups: the row subtitle ("6 contacts - On this phone") is 12 dp and faint, and rows are shorter than the list rows. | `contacts/04-groups-dark.png`, `contacts/04-groups-light.png` |
| C6 | Detail: action circles are small with 10 dp labels, field cards have a different radius than the page cards, and the content runs under the bar. | `contacts/05-detail-dark.png`, `contacts/05-detail-light.png`, `contacts/07-me-card-dark.png` |
| C7 | Editor: the fields are stacked tightly with no labels visible while typing, and the avatar and "Add photo" are small. | `contacts/06-editor-dark.png`, `contacts/06-editor-light.png` |
| C8 | Birthdays: the date column and the name column do not align across rows, and the "Wish" pill is oversized against the 12 dp caption. | `contacts/08-birthdays-dark.png`, `contacts/08-birthdays-light.png` |
| C9 | Dupes and Trash: tiny text, no empty-state hierarchy ("Trash Is Empty" is a bare line in the middle). | `contacts/09-dupes-dark.png`, `contacts/10-trash-empty-dark.png`, `contacts/10-trash-empty-light.png` |
| C10 | Select mode: the selection check circles are small and faint (under 24 dp), and the action bar floats over the list with no inset. | `contacts/11-select-mode-dark.png`, `contacts/11-select-mode-light.png` |
| C11 | QR sheet: grabber missing or faint, QR card margins differ from other sheets. | `contacts/12-qr-sheet-dark.png`, `contacts/12-qr-sheet-light.png` |
| C12 | Settings: same tiny captions and low-contrast groups as the other apps. | `contacts/13-settings-dark.png`, `contacts/13-settings-light.png` |

### Stops

| # | Problem | Screen |
|---|---------|--------|
| S1 | Dark theme: the olive backdrop with grey cards gives low contrast, and the grey buttons ("Start", "Open") read as disabled. | `stops/01-trip-idle-dark.png`, `stops/06-saved-list-dark.png`, `stops/11-settings-dark.png` |
| S2 | Trip: the map buttons (the four circles at the right edge) are about 30 dp with unreadable labels ("Ruler", "Map", "Me"), under the 44 dp minimum. | `stops/01-trip-idle-dark.png`, `stops/02-trip-a-set-dark.png` |
| S3 | Trip: the A and B point cards run under the tab bar, and the "Saved stops, recent trips, links" card has a faint "Open" label. | `stops/02-trip-a-set-dark.png`, `stops/03-trip-a-b-set-dark.png` |
| S4 | Trip action row ("Clear", "Swap", "Start") has three different pill widths and heights, and the helper text under it is about 11 dp. | `stops/03-trip-a-b-set-dark.png`, `stops/03-trip-a-b-set-light.png` |
| S5 | Running screen: the "Copy status" and "Share status" pills and the "Stop" button are close together and low contrast. | `stops/04-trip-running-dark.png`, `stops/04-trip-running-light.png` |
| S6 | Saved list: the header buttons ("Export GPX", "Export JSON", "Import") are tight pills, the search field and "Sort" share a row with different heights, and row subtitles are about 11 dp. | `stops/06-saved-list-dark.png`, `stops/06-saved-list-light.png` |
| S7 | Saved stop sheet: the grabber is faint, actions are 40 dp tall and not separated from the title. | `stops/07-saved-stop-sheet-dark.png`, `stops/07-saved-stop-sheet-light.png` |
| S8 | History: statistics rows and trip rows use different paddings, and the trailing status text ("Arrived", "Cancelled") is faint. | `stops/08-history-dark.png`, `stops/08-history-light.png` |
| S9 | Map options and Pick sheets: the segmented control ("Auto / Light / Dark") is low contrast and the search field is cut at the right edge. | `stops/09-map-options-dark.png`, `stops/09-map-options-light.png`, `stops/10-pick-dark.png`, `stops/10-pick-light.png` |
| S10 | Settings: captions below 12 dp, the right-hand values ("On", "5 min") faint, and the last rows under the tab bar. | `stops/11-settings-dark.png`, `stops/11-settings-light.png` |
| S11 | Alarm screen: fine hierarchy but the secondary line ("Now on the right side") is faint on the red wash in light. | `stops/05-alarm-ringing-dark.png`, `stops/05-alarm-ringing-light.png` |
| S12 | QR sheet: same as C11. | `stops/12-qr-sheet-dark.png`, `stops/12-qr-sheet-light.png` |

---

## Part 2. The system

### 2.1 Grid and spacing

Base unit 4 dp. Every size, padding, gap and margin is a multiple of 4 dp, except hairlines
and the 2 dp rings (listed below).

| Token | dp | Use |
|-------|----|-----|
| `space::XS` | 4 | icon to label, badge padding |
| `space::S` | 8 | gap between buttons and chips, tab bar to screen edge, content top below safe inset |
| `space::M` | 12 | row padding, avatar to text gap, chip padding |
| `space::L` | 16 | screen margin, card padding, section gap |
| `space::XL` | 20 | large card padding |
| `space::XXL` | 24 | gap above a section header |
| `space::XXXL` | 32 | gap between page blocks, empty state spacing |

Screen margin is 16 dp on both sides, on every screen. Elements that scroll horizontally
(chip rows) start at the margin and scroll through it.

### 2.2 Typography

Roles map onto `liquid_glass_kit::tokens::typography` (size / line height / weight, all dp).
Nothing smaller than 11 dp is allowed; body copy is never below 13 dp.

| Role | Kit token | Size / line | Weight | Use |
|------|-----------|-------------|--------|-----|
| Large title | `LARGE_TITLE` | 34 / 41 | 800 | page header |
| Title | `TITLE2` | 22 / 28 | 700 | sheet titles, detail-screen titles |
| Title 3 | `TITLE3` | 20 / 25 | 700 | empty-state title, big card titles |
| Headline | `HEADLINE` | 16 / 21 | 600 | row title, button label |
| Body | `BODY` | 16 / 22 | 400 | message text, field text |
| Subhead | `SUBHEADLINE` | 15 / 20 | 400 | row subtitle (preview), secondary body |
| Footnote | `FOOTNOTE` | 13 / 18 | 400 (600 for section headers) | row time, section header, helper text |
| Caption | `CAPTION` | 12 / 16 | 400 (600 for badges) | chip label, small badges |
| Caption 2 | `CAPTION2` | 11 / 13 | 600 | count badges only |

`TITLE` (28 / 34) is not used by the system. A collapsing large title is a later step and would shrink to `TITLE2`.

### 2.3 Safe area and the floating tab bar

Let `safe` be the window's system insets (top = status bar or cutout, bottom = gesture
area). The shots simulate `safe.top = 24` and `safe.bottom = 16`.

* Content top: `safe.top + 8`.
* Tab bar: height 56, centred horizontally, bottom edge at `safe.bottom + 8` from the
  window bottom. Inner pad 4. Each tab is 64 wide. The bar is therefore
  `64 * n + 8` wide (200 dp for 3 tabs, 264 dp for 4).
* Puck: sits inside the bar, so its rectangle is clamped to `[4, bar_w - 4]` horizontally
  and `[4, 56 - 4]` vertically, even at press scale 1.2 and stretch 1.3. Its centre is
  `4 + (index + 0.5) * 64`.
* Bar glass: `GlassSpec::chrome(theme)` with only `blur`, `tint` changed: blur 12 px
  (chrome default is 2 px, which left the row under the bar readable) and tint =
  `colors.background` at alpha 0.72 in light, 0.60 in dark (the skill's tab bar recipe
  gives no tint values, so the owner-brief numbers are used). The puck keeps the plain
  chrome spec so it stays crisp. A unit test in `tab_bar.rs` checks that at rest, with 3
  and 4 tabs, the puck centre equals the icon centre within 0.5 dp.
* Content bottom inset for every scrollable view: `safe.bottom + 8 + 56 + 16 + keyboard`
  (= `safe.bottom + 80 + keyboard`). `keyboard` is the IME height not already consumed
  by the screen. There is exactly one function for this
  (`rovsti_ui::layout::content_bottom_inset`) and a view never writes its own number.
* Views without a tab bar (conversation, editor, sheets) use `safe.bottom + 8 + keyboard`.

### 2.4 Touch targets

Every tappable element has a hit box of at least 44 x 44 dp. Visual size may be smaller
(a 40 dp button, a 32 dp chip) but the hit box is padded to 44 and neighbouring hit boxes
never overlap. Gap between hit boxes is at least 0.

### 2.5 List row anatomy

```
 x=0     16               64  76                       (w-16-44)  w-16
 |  16    |  48 avatar  | 12 |  text column (flex)  | 8 | trailing column (min 44) |  16
 +--------+-------------+----+----------------------+---+--------------------------+
 pad 12   +-------------+    Title        (Headline)       Time (Footnote, right)
          |   avatar    |    Preview ......(Subhead)       Badge (20 dp min, right)
          |  [16 src]   |    [below slot: indented to 76]
 pad 12   +-------------+
 ----------------------- 1 px separator, starts at x = 76, ends at the right edge
```

* Margin 16, avatar 48, gap 12, so the text inset is `16 + 48 + 12 = 76` dp.
* Min row height 72 (48 avatar + 2 x 12 padding). Rows with a `below` slot (OTP chip)
  grow; the slot starts at x = 76.
* Line 1: title (Headline, one line, ellipsis) and time (Footnote, secondary colour,
  right aligned, never accent coloured). Line 2: preview (Subhead, secondary colour,
  one line) and badge. Both lines are vertically centred in the 48 dp avatar height.
* Trailing column: min width 44, right aligned to the 16 dp margin, width is the max of
  the time and the badge so the time and badge of every row end at the same x. Gap from
  the text column is 8.
* Unread badge: 20 dp high, min width 20, 6 dp horizontal padding, Caption 600, solid
  accent fill with white text (see 2.12 for the fill colour), fully rounded. Muted chats
  use a grey fill. Counts above 999 show as "999+".
* Unread rows set the title to weight 700 and the time to the label colour.
* Separator: 1 physical px (0.5 dp at 2x), `separator` colour, starting at 76 dp and ending
  at the right screen edge. The last row has none.
* Title accessories (muted, pinned): 14 dp icons after the title, 4 dp gap.
* Rows that are not chats (contacts, groups, saved stops) use the same anatomy with a
  36 to 48 dp leading element only if they have an avatar; contacts use 48 too.

### 2.6 Source indicator

No pill per row. The channel is a 16 dp circular badge at the avatar's bottom-right,
overlapping by 4 dp (centre at avatar corner minus 4), with a 2 dp ring in the page
background colour and a 10 dp glyph (WhatsApp green with a phone glyph, SMS blue with a
message glyph). A contact that has both gets two badges, 6 dp overlap. The channel name
is only spelled out in the conversation header and as a 12 dp icon in the meta line of a
search result.

### 2.7 Avatar stack

Avatars of 28 dp, overlap 10 dp, ring 2 dp. Width = `size + (n - 1) * (size - overlap)`
plus `2 * ring` for the outer rings, so 3 avatars take `28 + 2 * 18 + 4 = 68` dp. The
slot reserves exactly this width and the text column starts 12 dp after it, so the stack
can never cover its neighbours. More than 3 shows a "+n" disc as the last element.

### 2.8 Page header

* Top: `safe.top + 8`, then a row of 48 dp.
* Large title at the left margin (Large title role), vertically centred with the action
  buttons.
* Actions: at most 2, 40 dp circular glass buttons with a 22 dp icon, each inside a 44 dp
  hit box, 4 dp gap between hit boxes (so 8 dp between visuals), the rightmost hit box ends
  at the margin minus 2 dp so its visual ends on the 16 dp margin. Anything beyond two goes
  into a "more" menu. A subtitle (Footnote, secondary) may sit under the title.
* Bottom gap to the first content: 8 dp (12 dp if the first element is a chip row).
* Detail screens use a 44 dp back button on the left and the title at Title 2.

### 2.9 Filter chips

* One row, horizontal scroll, no wrapping. Row height 44 (the hit box); the chip visual is
  32 dp high, centred in it.
* Chip: horizontal padding 12, label Caption-to-Subhead 14/18 w600, 8 dp gap between chips.
  Row padding left and right is 16 and scrolls with content.
* Count: separate badge inside the chip, 18 dp high, min 18 wide, Caption 2, 4 dp left of
  the label, counts over 999 show "999+". The count is secondary information: it uses a
  15% label fill when unselected and a 25% white fill when selected. Labels never carry
  the number, the accessibility label is "SMS, 277".
* Selected: solid accent fill (`accent_solid`, see 2.12) with white text. Unselected:
  `fill` colour with label text.
* A chip never grows past 160 dp; long labels ellipsise.

### 2.10 Section header, empty state, sheets, dialogs

**Section header.** Footnote 600, secondary label colour, no uppercase, left margin 16,
24 above and 8 below (12 above when it is the first element). Height is at least 44 with
the padding counted.

**Empty state.** Centred in the free area (above the bottom inset), max width 320:
48 dp icon in secondary colour, 16 gap, Title 3, 8 gap, Subhead in secondary colour
(centred, max 3 lines), 24 gap, optional primary action (glass pill, 44 dp high, 24 dp
horizontal padding).

**Sheets.** Top radius 38, grabber 36 x 5 dp at 8 dp from the top, 16 dp side margins,
title Title 2, 16 dp below the grabber area, content bottom padding `safe.bottom + 16`,
actions 50 dp high with a 12 dp gap and 12 dp radius, destructive actions use the danger
colour, are never the filled primary, and are separated by 16 dp. Max height is 85% of
the window.

**Dialogs.** Width `min(window - 2 * 32, 320)`, radius 26, padding 20, title Headline
centred, message Subhead in secondary colour, buttons 50 dp high stacked or side by side,
a 1 px separator above the button row. Scrim 40% black.

**Grouped cards.** Radius 20 (`CARD`), 16 padding, rows 52 dp high minimum, 16 dp inset
separators; group label is a section header, group footnote is Footnote secondary with
8 dp above.

### 2.11 Spam row decision

The Spam folder is a quiet row: a 48 dp neutral tile (secondary fill with a 24 dp
shield icon in secondary label colour), title "Spam" in Headline, subtitle
"223 conversations" in Subhead, and a trailing count badge in grey. It is shown only
while the folder has unread items, or when the "Spam" filter is selected from the chip
row; otherwise it is reachable from the compose/more menu. It sits directly below the
chip row and above the chats, so it never pushes the first chat off the screen by more
than 72 dp.

### 2.12 Colour roles and contrast

Text on its background must be at least 4.5:1 (large titles at least 3:1). Measured on
the real kit values with the WCAG relative-luminance formula:

| Role | Light | Dark |
|------|-------|------|
| Label (primary text) | `#000000` on `#FFFFFF`: 21.0 | `#FFFFFF` on `#000000`: 21.0 |
| Secondary label (preview, time, captions) | kit `3C3C43` at 60% = `#8A8A8E`, 3.4:1 on white: **fails** | kit `EBEBF5` at 70% = `#A5A5AC`, 8.6:1 on black and 7.6:1 on `#1C1C1E`: passes |
| Secondary label, system value | `#6C6C70` opaque: 5.2:1 on white, 4.7:1 on `#F2F2F7` | keep kit value |
| Tertiary label | decorative only (chevrons, disabled), never for text that must be read: 1.7:1 | 2.3:1, the same |
| Accent text and links | kit `#007AFF` on white: 4.0:1, **fails**; use `#0062CC`: 5.8:1 (5.2:1 on `#F2F2F7`) | kit `#0A84FF` on black: 5.8:1, on `#1C1C1E`: 4.7:1, passes |
| Accent fill with white text (selected chip, unread badge, primary button) | `accent_solid #0062CC`: white on it 5.8:1 (kit `#007AFF`: 4.0, fails) | `accent_solid #0B6EF5`: white on it 4.6:1 (kit `#0A84FF`: 3.6, fails) |
| Success text | `#248A3D` on white: 4.4:1 (use only at 16 dp w600 or larger, or darken to `#1E7A34`: 5.4:1) | `#30D158` on black: 10.4:1 |
| Danger text | `#D70015` on white: 5.4:1 (not kit `#FF3B30`, 3.5:1) | `#FF453A` on black: 6.2:1 |
| Warning text | `#B25000` | `#FF9F0A` |
| Separator | kit separator, decorative | kit separator, decorative |

Rules that follow:

1. Secondary text in light mode uses `#6C6C70`, not the translucent kit colour. The change is
   made in `rovsti_ui::theme::colors`, not by apps.
2. A solid accent fill with white text uses `accent_solid`, never the plain accent. The
   plain accent stays for text and icons on the page background.
3. Colour is restrained: one accent, grey for secondary things. Green and orange are for
   state (answer, warning), never decoration. An avatar's hue comes from `hue_pair`.
4. Tinted pills at 22% opacity are no longer used for selected states.

### 2.13 Component checklist

| Component | Replaces | Defined by |
|-----------|----------|------------|
| `layout::space`, `layout::geom` | ad-hoc px values | 2.1, 2.3, 2.5, 2.7 |
| `PageHeader` | `large_title` plus per-app button rows | 2.8 |
| `ListRow` | `list_row`, `inbox::row_element` | 2.5 |
| `source_badge` | WhatsApp/SMS pills | 2.6 |
| `AvatarStack` | `status_ui::entry_avatars` | 2.7 |
| `filter_chip_row` | `ChatItem::Filters` wrapped chips | 2.9 |
| `section_header`, `empty_state` | per-app text | 2.10 |
| `layout::content_bottom_inset` | `content_bottom_inset` and `content_bottom_inset_with` | 2.3 |

### 2.14 Migrated screens

| Screen | State | Shots |
|--------|-------|-------|
| Messages inbox (header, Status row, chips, Spam row, chat rows, end inset) | Done: `PageHeader`, `ListRow`, `avatar_stack`, `filter_chip_row`, `source_badge`, `content_bottom_inset` | `messages/01-inbox`, `02-unread-filter`, `03-inbox-empty`, `09-status`, `10-inbox-end` |
| Messages other screens (conversation, composer, search, settings) | Left to the Messages per-app unit | |
| Phone (all screens) | Left to the Phone per-app unit | |
| Contacts (all screens) | Left to the Contacts per-app unit | |
| Stops (all screens) | Left to the Stops per-app unit | |

The refreshed `docs/screenshots/baseline/messages.png` shows the Messages inbox on the
system; the other three baselines are still the "before" pictures.
