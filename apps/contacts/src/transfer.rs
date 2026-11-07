//! vCard export and import, and the events Kotlin sends back.
//!
//! Both start a system file picker and return at once. The answer is a
//! one-shot `document_result` event (`rovsti_core::wire::DocumentResult`),
//! which `ContactsApp::on_event` routes here. No storage permission is used.

use gpui::Context;
use rovsti_android::events::{RawEvent, kind};
use rovsti_core::{
    contact_photo, import_plan, vcard,
    wire::{DocumentOp, DocumentResult, WireContact},
};

use crate::{ContactsApp, backend, confirm::Confirm};

/// What the export notice says for `count` contacts written.
pub(crate) fn export_words(count: usize) -> String {
    match count {
        0 => "There are no contacts to export.".to_owned(),
        1 => "Saved 1 contact to the file.".to_owned(),
        n => format!("Saved {n} contacts to the file."),
    }
}

impl ContactsApp {
    /// Reads every contact and offers it as `contacts.vcf`.
    pub(crate) fn export_contacts(&mut self, cx: &mut Context<Self>) {
        if self.transfer_busy {
            self.say_busy(cx);
            return;
        }
        self.transfer_busy = true;
        self.notice = Some("Preparing the file…".into());
        let hidden: Vec<i64> = self
            .pending_delete
            .as_ref()
            .map(|p| p.ids.clone())
            .unwrap_or_default();
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let (text, count) = backend::export_text(&hidden)?;
                    if count == 0 {
                        return Ok(0);
                    }
                    backend::start_export(&text).map(|()| count)
                })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(0) => {
                        this.transfer_busy = false;
                        this.notice = Some(export_words(0).into());
                    }
                    Ok(count) => this.exported = Some(count),
                    Err(words) => {
                        this.transfer_busy = false;
                        this.notice = Some(format!("Can't export: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Opens the picker for a `.vcf` to add.
    pub(crate) fn import_contacts(&mut self, cx: &mut Context<Self>) {
        if self.transfer_busy {
            self.say_busy(cx);
            return;
        }
        self.transfer_busy = true;
        self.clear_notice();
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::start_import() })
                .await;
            this.update(cx, |this, cx| {
                if let Err(words) = result {
                    this.transfer_busy = false;
                    this.notice = Some(format!("Can't import: {words}.").into());
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// A file button was tapped while another file job runs (the rows are dimmed).
    fn say_busy(&mut self, cx: &mut Context<Self>) {
        if self.notice.is_none() {
            self.notice = Some("Wait for the file being saved or read to finish.".into());
            cx.notify();
        }
    }

    /// One event from Kotlin.
    pub(crate) fn on_event(&mut self, event: &RawEvent, cx: &mut Context<Self>) {
        if event.kind == kind::DOCUMENT_RESULT {
            self.on_document_result(&event.json, cx);
        } else if event.kind == kind::WA_PUSH {
            self.whatsapp.update(cx, |w, cx| w.wake(cx));
        }
    }

    fn on_document_result(&mut self, json: &str, cx: &mut Context<Self>) {
        let result: DocumentResult = match serde_json::from_str(json) {
            Ok(result) => result,
            Err(error) => {
                log::warn!("bad document_result: {error}");
                return;
            }
        };
        match result.op {
            DocumentOp::ExportContacts => {
                self.transfer_busy = false;
                let count = self.exported.take();
                // A cancelled picker answers `ok: false` with no error: say nothing.
                self.notice = match (result.ok, result.error, count) {
                    (true, _, Some(count)) => Some(export_words(count).into()),
                    (false, Some(_), _) => Some("Can't save the file.".into()),
                    _ => None,
                };
                cx.notify();
            }
            DocumentOp::ImportContacts => {
                if !result.ok {
                    self.transfer_busy = false;
                    if result.error.is_some() {
                        self.notice = Some("Can't read that file.".into());
                    }
                    cx.notify();
                    return;
                }
                // Still busy while the file is read; the card that follows ends it.
                self.plan_import(result.text.unwrap_or_default(), cx);
            }
            DocumentOp::ContactPhoto => {
                self.transfer_busy = false;
                // A cancelled picker answers `ok: false` with no error: say nothing.
                if !result.ok {
                    if result.error.is_some() {
                        self.notice =
                            Some("Can't change the photo: that image can't be read.".into());
                    }
                    cx.notify();
                    return;
                }
                let Some(path) = result.path else {
                    self.notice = Some("Can't change the photo: that image can't be read.".into());
                    cx.notify();
                    return;
                };
                if !contact_photo::accepts(result.mime.as_deref()) {
                    self.notice = Some("Can't change the photo: that is not an image.".into());
                    cx.notify();
                    return;
                }
                self.photo_picked(path, cx);
            }
            _ => {}
        }
    }

    /// Crops, scales and stores the image at `path` as the photo of contact
    /// `id` on the background executor, then refreshes its thumbnail.
    pub(crate) fn apply_photo(&mut self, id: i64, path: String, cx: &mut Context<Self>) {
        self.notice = Some("Saving the photo…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::change_photo(id, &path) })
                .await;
            this.update(cx, |this, cx| {
                this.photo_changed(id, result.map(|()| "Photo updated."), cx);
            })
            .ok();
        })
        .detach();
    }

    /// Removes the photo of contact `id`.
    pub(crate) fn clear_photo(&mut self, id: i64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::remove_photo(id) })
                .await;
            this.update(cx, |this, cx| {
                this.photo_changed(id, result.map(|()| "Photo removed."), cx);
            })
            .ok();
        })
        .detach();
    }

    /// A photo write finished: say how it went, and on success load the new
    /// thumbnail and pull again (the next pull carries the fresh `photo_uri`).
    fn photo_changed(
        &mut self,
        id: i64,
        result: Result<&'static str, String>,
        cx: &mut Context<Self>,
    ) {
        self.wa_photo_busy = false;
        match result {
            Ok(done) => {
                // The viewer shows the old picture.
                self.viewer = None;
                self.photos.forget(id);
                self.notice = Some(done.into());
                self.reload(cx);
            }
            Err(words) => {
                self.notice = Some(format!("Can't change the photo: {words}.").into());
            }
        }
        cx.notify();
    }

    /// Reads the cards of `text` and asks before adding the new ones.
    fn plan_import(&mut self, text: String, cx: &mut Context<Self>) {
        let existing: Vec<WireContact> = self
            .index()
            .map(|index| index.all().cloned().collect())
            .unwrap_or_default();
        self.notice = Some("Reading the file…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let (plan, unreadable) = cx
                .background_executor()
                .spawn(async move {
                    let parsed = vcard::parse(&text);
                    (
                        import_plan::plan(&existing, parsed.contacts),
                        parsed.skipped,
                    )
                })
                .await;
            this.update(cx, |this, cx| {
                this.clear_notice();
                this.transfer_busy = false;
                this.confirm = Some(Confirm::Import {
                    cards: plan.add,
                    duplicates: plan.duplicates,
                    unreadable,
                });
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

#[cfg(test)]
mod tests {
    use super::export_words;

    #[test]
    fn export_words_by_count() {
        assert_eq!(export_words(0), "There are no contacts to export.");
        assert_eq!(export_words(1), "Saved 1 contact to the file.");
        assert_eq!(export_words(12), "Saved 12 contacts to the file.");
    }
}
