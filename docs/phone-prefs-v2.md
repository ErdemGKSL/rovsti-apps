# Phone: Prefs v2 migration (stories 14.11, 14.12)

`prefs.json` in the no-backup data dir. v0.1 wrote a flat file with no
`schema_version`; v0.2 writes `PrefsV2` (`rovsti_core::settings`). Loading and
saving live in `rovsti_ui::storage` (`load_prefs`, `PrefsWriter`).

| Found on disk | Result | Side effect |
|---|---|---|
| no file | defaults | none |
| no `schema_version` or `1` | `From<PrefsV1>` | the first save copies the file to `prefs.json.bak-v1` (never overwritten) |
| `2` | read as is, then clamped | none |
| higher than 2 | best effort | copied to `prefs.json.bak-v{n}` at load |
| not valid for its version | defaults | copied to `prefs.json.corrupt` at load, before the first save |

Writes: one worker, newest snapshot wins, at most one write per 250 ms, an
immediate write when the window goes inactive, tmp file then rename. A failed
write logs only the error kind and sets `Backend::prefs_save_failed()`.

## Device script (owner): update over the previous release

1. Install the previous release APK (the one with "Audio routes"), open the app,
   switch to the Recents tab, and in Setup confirm one Xiaomi item (for example
   Autostart) so it shows as done.
2. Close the app from Recents (swipe away).
3. Install the new release APK over it (no uninstall), open it.
4. Expect: the app opens on Recents (the remembered tab) and the Xiaomi item is
   still confirmed. Nothing asks you to set anything up again.
5. Change tab to Favorites, close and reopen: it opens on Favorites.

Settings that do not exist in the UI yet (haptics, quick replies, ...) keep
their v0.1 behaviour; the Diagnostics page is unchanged.

## Call notes (`call-notes.json`)

Per-call notes (U8, U17) are kept in `call-notes.json`, next to `prefs.json` in the no-backup data
dir. The file is read and written off the UI thread (`apps/phone/src/notes.rs`); the matching and
cleaning rules are in `rovsti_core::call_notes` (a note is keyed by number and the time the call
rang or was placed, matched with a 2 minute window; text is clipped to 1000 characters and at most
2000 notes are kept).

It is not part of `prefs.json`, so it is **not in the prefs backup** (Settings, Backup export and
import carry preferences only). A note therefore stays on the phone and is lost on uninstall or
"clear data". The call log CSV export does include each note in its `note` column.
