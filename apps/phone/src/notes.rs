//! Per-call notes (U8): the in-memory store, and loading and saving it.
//!
//! The pure rules (matching a note to a call, cleaning text) live in
//! `rovsti_core::call_notes`. This holds the notes between frames, counts
//! changes in `rev` so views rebuild their note glyphs only when it moves, and
//! reads and writes `call-notes.json` off the UI thread, following the WhatsApp
//! call history in `wa_call.rs`.

// The call details sheet and the in-call note (S5, S6) use the rest of this.
#![allow(dead_code)]

use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
};

use gpui::Context;
use rovsti_core::call_notes::{self, CallNote, Notes, SetOutcome};

use crate::PhoneApp;

/// The notes in memory and where they are saved.
#[derive(Default)]
pub(crate) struct NotesStore {
    notes: Notes,
    /// `None` until the data directory is known (and always in tests).
    dir: Option<PathBuf>,
    /// Bumped on every change.
    rev: u64,
    /// Numbers the saves, so a queued save yields to a newer one.
    write_gen: Arc<AtomicU64>,
}

impl NotesStore {
    /// Changes so far; views compare it with the value they last saw.
    pub(crate) fn rev(&self) -> u64 {
        self.rev
    }

    /// The note of the call with `number` at `at_ms`, if any.
    pub(crate) fn get(&self, number: &str, at_ms: i64) -> Option<&CallNote> {
        self.notes.find(number, at_ms).map(|i| &self.notes.notes[i])
    }

    /// Stores `text` as the note of the call (empty text removes it).
    pub(crate) fn set(&mut self, number: &str, at_ms: i64, text: &str, now_ms: i64) -> SetOutcome {
        let outcome = self.notes.set(number, at_ms, text, now_ms);
        if outcome != SetOutcome::Unchanged {
            self.rev += 1;
        }
        outcome
    }

    /// Removes the note; the returned note is what Undo restores.
    pub(crate) fn remove(&mut self, number: &str, at_ms: i64) -> Option<CallNote> {
        let removed = self.notes.remove(number, at_ms);
        if removed.is_some() {
            self.rev += 1;
        }
        removed
    }

    /// Puts a removed note back (Undo).
    pub(crate) fn restore(&mut self, note: CallNote) {
        self.notes.restore(note);
        self.rev += 1;
    }

    /// A copy of all notes (for the CSV export).
    pub(crate) fn snapshot(&self) -> Notes {
        self.notes.clone()
    }

    /// Glyph flags for `(number, date_ms)` calls, in order.
    pub(crate) fn flags(&self, calls: &[(String, i64)]) -> Vec<bool> {
        self.notes.for_calls(calls)
    }

    /// The file has been read: notes made meanwhile win over stored ones.
    fn loaded(&mut self, stored: Notes) {
        for note in stored.notes {
            if self.notes.find(&note.number, note.at_ms).is_none() {
                self.notes.restore(note);
            }
        }
        self.rev += 1;
    }
}

impl PhoneApp {
    /// Reads `call-notes.json` in the background once the data directory is known.
    pub(crate) fn load_notes(&mut self, cx: &mut Context<Self>) {
        let dir = rovsti_ui::storage::data_dir("phone", cx);
        cx.spawn(async move |this, cx| {
            let Ok(dir) = dir.await else { return };
            let stored = {
                let dir = dir.clone();
                cx.background_executor()
                    .spawn(async move { rovsti_ui::storage::load_json::<Notes>(&dir, call_notes::FILE) })
                    .await
            };
            this.update(cx, |this, cx| {
                this.notes.dir = Some(dir);
                if let Some(stored) = stored {
                    this.notes.loaded(stored);
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Writes the notes off the UI thread. Call it after every change.
    pub(crate) fn save_notes(&mut self, cx: &mut Context<Self>) {
        let Some(dir) = self.notes.dir.clone() else {
            return;
        };
        let snapshot = self.notes.snapshot();
        let generation = self.notes.write_gen.fetch_add(1, Ordering::SeqCst) + 1;
        let latest = self.notes.write_gen.clone();
        cx.background_executor()
            .spawn(async move {
                if latest.load(Ordering::SeqCst) != generation {
                    return; // a newer snapshot is queued behind this one
                }
                if let Err(error) = rovsti_ui::storage::save_json(&dir, call_notes::FILE, &snapshot)
                {
                    log::warn!("saving the call notes failed: {}", error.kind());
                }
            })
            .detach();
    }
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
}
