//! Per-call notes (U8): the store lives in `rovsti_app_core::phone::notes`; this
//! is the GPUI half that reads and writes the file off the UI thread.

// The call details sheet and the in-call note (S5, S6) use the rest of this.
#![allow(dead_code)]

use std::sync::atomic::Ordering;

use gpui::Context;
use rovsti_app_core::phone::notes;
pub(crate) use rovsti_app_core::phone::notes::NotesStore;

use crate::PhoneApp;

impl PhoneApp {
    /// Reads `call-notes.json` in the background once the data directory is known.
    pub(crate) fn load_notes(&mut self, cx: &mut Context<Self>) {
        let dir = rovsti_ui::storage::data_dir("phone", cx);
        cx.spawn(async move |this, cx| {
            let Ok(dir) = dir.await else { return };
            let stored = {
                let dir = dir.clone();
                cx.background_executor()
                    .spawn(async move { notes::load(&dir) })
                    .await
            };
            this.update(cx, |this, cx| {
                this.notes.dir = Some(dir);
                if let Some(stored) = stored {
                    this.notes.loaded(stored);
                    this.sync_recents_notes(cx);
                    this.notes_page_sync(cx);
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Writes the notes off the UI thread. Call it after every change.
    pub(crate) fn save_notes(&mut self, cx: &mut Context<Self>) {
        self.sync_recents_notes(cx);
        self.notes_page_sync(cx);
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
                if let Err(error) = notes::save(&dir, &snapshot) {
                    log::warn!("saving the call notes failed: {}", error.kind());
                }
            })
            .detach();
    }
}
