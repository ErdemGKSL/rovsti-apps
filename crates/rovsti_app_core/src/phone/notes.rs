//! Per-call notes (U8): the in-memory store, and loading and saving it.
//!
//! The pure rules (matching a note to a call, cleaning text) live in
//! `rovsti_core::call_notes`. This holds the notes between frames, counts
//! changes in `rev` so views rebuild their note glyphs only when it moves, and
//! reads and writes `call-notes.json`. The app runs the file work off its UI
//! thread, following the WhatsApp call history in `wa_call.rs`.

use std::{
    path::{Path, PathBuf},
    sync::{Arc, atomic::AtomicU64},
};

use rovsti_core::call_notes::{self, CallNote, Notes, SetOutcome};

use crate::storage;

/// The notes in memory and where they are saved.
#[derive(Default)]
pub struct NotesStore {
    pub notes: Notes,
    /// `None` until the data directory is known (and always in tests).
    pub dir: Option<PathBuf>,
    /// Bumped on every change.
    rev: u64,
    /// Numbers the saves, so a queued save yields to a newer one.
    pub write_gen: Arc<AtomicU64>,
}

impl NotesStore {
    /// Notes saved.
    pub fn len(&self) -> usize {
        self.notes.notes.len()
    }

    /// Whether no note is saved.
    pub fn is_empty(&self) -> bool {
        self.notes.notes.is_empty()
    }

    /// Changes so far; views compare it with the value they last saw.
    pub fn rev(&self) -> u64 {
        self.rev
    }

    /// The note of the call with `number` at `at_ms`, if any.
    pub fn get(&self, number: &str, at_ms: i64) -> Option<&CallNote> {
        self.notes.find(number, at_ms).map(|i| &self.notes.notes[i])
    }

    /// Stores `text` as the note of the call (empty text removes it).
    pub fn set(&mut self, number: &str, at_ms: i64, text: &str, now_ms: i64) -> SetOutcome {
        let outcome = self.notes.set(number, at_ms, text, now_ms);
        if outcome != SetOutcome::Unchanged {
            self.rev += 1;
        }
        outcome
    }

    /// Removes the note; the returned note is what Undo restores.
    pub fn remove(&mut self, number: &str, at_ms: i64) -> Option<CallNote> {
        let removed = self.notes.remove(number, at_ms);
        if removed.is_some() {
            self.rev += 1;
        }
        removed
    }

    /// Puts a removed note back (Undo).
    pub fn restore(&mut self, note: CallNote) {
        self.notes.restore(note);
        self.rev += 1;
    }

    /// A copy of all notes (for the CSV export).
    pub fn snapshot(&self) -> Notes {
        self.notes.clone()
    }

    /// Glyph flags for `(number, date_ms)` calls, in order.
    pub fn flags(&self, calls: &[(String, i64)]) -> Vec<bool> {
        self.notes.for_calls(calls)
    }

    /// The file has been read: notes made meanwhile win over stored ones.
    pub fn loaded(&mut self, stored: Notes) {
        for note in stored.notes {
            if self.notes.find(&note.number, note.at_ms).is_none() {
                self.notes.restore(note);
            }
        }
        self.rev += 1;
    }
}

/// Reads `call-notes.json`; a missing or damaged file is `None`.
pub fn load(dir: &Path) -> Option<Notes> {
    storage::load_json(dir, call_notes::FILE)
}

/// Writes `notes` to `call-notes.json` (atomic).
pub fn save(dir: &Path, notes: &Notes) -> std::io::Result<()> {
    storage::save_json(dir, call_notes::FILE, notes)
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_700_000_000_000;

    #[test]
    fn set_get_and_remove_in_memory() {
        let mut store = NotesStore::default();
        assert_eq!(store.rev(), 0);
        assert!(store.get("+905551112233", NOW).is_none());

        assert_eq!(
            store.set("+905551112233", NOW, "  call back  ", NOW),
            SetOutcome::Added
        );
        assert_eq!(store.rev(), 1);
        assert_eq!(
            store
                .get("0555 111 22 33", NOW + 5_000)
                .map(|n| n.text.as_str()),
            Some("call back")
        );

        assert_eq!(
            store.set("+905551112233", NOW, "call back", NOW),
            SetOutcome::Unchanged
        );
        assert_eq!(store.rev(), 1, "no change, no bump");

        let removed = store.remove("+905551112233", NOW).expect("removed");
        assert_eq!(store.rev(), 2);
        assert!(store.get("+905551112233", NOW).is_none());
        assert!(store.remove("+905551112233", NOW).is_none());
        assert_eq!(store.rev(), 2);

        store.restore(removed);
        assert_eq!(store.rev(), 3);
        assert!(store.get("+905551112233", NOW).is_some());
        assert_eq!(store.snapshot().notes.len(), 1);
    }

    #[test]
    fn loading_keeps_notes_made_in_the_meantime() {
        let mut store = NotesStore::default();
        store.set("+905551112233", NOW, "new", NOW);
        let mut stored = Notes::default();
        stored.set("+905551112233", NOW, "old", NOW - 1);
        stored.set("+905559998877", NOW, "other", NOW - 1);
        store.loaded(stored);
        assert_eq!(store.get("+905551112233", NOW).expect("note").text, "new");
        assert_eq!(store.get("+905559998877", NOW).expect("note").text, "other");
    }

    #[test]
    fn notes_round_trip_through_the_file() {
        let dir = crate::stops::store::test_support::tmp("notes-file");
        assert!(load(&dir).is_none());
        let mut store = NotesStore::default();
        store.set("+905551112233", NOW, "call back", NOW);
        save(&dir, &store.snapshot()).expect("saves");
        let back = load(&dir).expect("loads");
        assert_eq!(back, store.snapshot());
    }
}
