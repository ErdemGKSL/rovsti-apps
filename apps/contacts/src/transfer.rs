//! vCard export and import, and the events Kotlin sends back.
//!
//! Both start a system file picker and return at once. The answer is a
//! one-shot `document_result` event (`rovsti_core::wire::DocumentResult`),
//! which `ContactsApp::on_event` routes here. No storage permission is used.

use gpui::Context;
use rovsti_android::events::{RawEvent, kind};
use rovsti_core::contact_intake::Intake;
use rovsti_core::{
    contact_csv, contact_dates,
    contact_edit::ContactDraft,
    contact_ics,
    contact_photo::{self, CropView},
    contact_scan::{self, Scanned},
    import_plan,
    wire::{DocumentOp, DocumentResult, WireContact},
};

use crate::{
    ContactsApp, backend,
    confirm::Confirm,
    intake,
    trash::{self, ScanRoute},
};

/// What the export notice says for `count` contacts written.
pub(crate) fn export_words(count: usize) -> String {
    match count {
        0 => "There are no contacts to export.".to_owned(),
        1 => "Saved 1 contact to the file.".to_owned(),
        n => format!("Saved {n} contacts to the file."),
    }
}

/// The file formats of "Contacts file" in Settings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ExportFormat {
    Vcf,
    Csv,
}

/// One export row of Settings.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ExportRow {
    pub(crate) id: &'static str,
    pub(crate) label: &'static str,
    pub(crate) format: ExportFormat,
}

/// The export rows, in the order Settings shows them.
pub(crate) fn export_rows() -> [ExportRow; 2] {
    [
        ExportRow {
            id: "export-vcf",
            label: "Export to a .vcf file",
            format: ExportFormat::Vcf,
        },
        ExportRow {
            id: "export-csv",
            label: "Export to a CSV file",
            format: ExportFormat::Csv,
        },
    ]
}

/// The pill after a "save as" answer for an export. `None` for any other
/// operation. Inner `None`: say nothing (the picker was cancelled).
pub(crate) fn document_op_notice(
    op: &DocumentOp,
    ok: bool,
    has_error: bool,
    count: Option<usize>,
) -> Option<Option<String>> {
    if !matches!(
        op,
        DocumentOp::ExportContacts | DocumentOp::ExportCalls | DocumentOp::ExportMessages
    ) {
        return None;
    }
    // Contacts has no messages archive: that answer is the calendar file's.
    let saved = |count: usize| match op {
        DocumentOp::ExportMessages => contact_ics::saved_notice(count),
        _ => export_words(count),
    };
    Some(match (ok, has_error, count) {
        (true, _, Some(count)) => Some(saved(count)),
        (false, true, _) => Some("Can't save the file.".to_owned()),
        _ => None,
    })
}

impl ContactsApp {
    /// Reads every contact and offers it in `format`.
    pub(crate) fn export_as(&mut self, format: ExportFormat, cx: &mut Context<Self>) {
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
        let today = contact_dates::local_ymd(crate::whatsapp::now_ms(), self.wa.offset_s);
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    match format {
                        ExportFormat::Vcf => {
                            let (text, count) = backend::export_text(&hidden)?;
                            if count == 0 {
                                return Ok(0);
                            }
                            backend::start_export(&text).map(|()| count)
                        }
                        ExportFormat::Csv => {
                            let (text, count) = backend::csv_text(&hidden)?;
                            if count == 0 {
                                return Ok(0);
                            }
                            let name = contact_csv::file_name(count, today);
                            backend::start_export_csv(&name, &text).map(|()| count)
                        }
                    }
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

    /// Settings, "Scan QR code": opens the picture picker; the answer is a
    /// `document_result` (`scan_image`).
    pub(crate) fn scan_qr(&mut self, cx: &mut Context<Self>) {
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
                .spawn(async move { backend::start_pick_scan() })
                .await;
            this.update(cx, |this, cx| {
                if let Err(words) = result {
                    this.transfer_busy = false;
                    this.notice = Some(format!("Can't scan: {words}.").into());
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Reads the QR code in the picked picture off the UI thread, then routes it.
    fn scan_picked(&mut self, path: String, cx: &mut Context<Self>) {
        self.notice = Some("Reading the picture…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::scan_image(&path) })
                .await;
            this.update(cx, |this, cx| {
                this.transfer_busy = false;
                match result {
                    Ok(scanned) => this.route_scan(scanned, cx),
                    Err(words) => {
                        this.notice = Some(format!("Can't read that picture: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn route_scan(&mut self, scanned: Scanned, cx: &mut Context<Self>) {
        match trash::scanned_route(&scanned) {
            ScanRoute::Editor => match trash::scanned_draft(&scanned) {
                Some(draft) => {
                    self.open_new_editor_with(draft, cx);
                    self.notice = Some(contact_scan::scan_notice(&scanned).into());
                }
                None => self.notice = Some(contact_scan::scan_notice(&Scanned::Nothing).into()),
            },
            ScanRoute::Import => {
                if let Scanned::Cards(cards) = scanned {
                    self.plan_cards(cards, cx);
                }
            }
            ScanRoute::Notice => {
                self.notice = Some(contact_scan::scan_notice(&scanned).into());
            }
        }
    }

    /// A file button was tapped while another file job runs (the rows are dimmed).
    pub(crate) fn say_busy(&mut self, cx: &mut Context<Self>) {
        if self.notice.is_none() {
            self.notice = Some("Wait for the file being saved or read to finish.".into());
            cx.notify();
        }
    }

    /// One event from Kotlin.
    pub(crate) fn on_event(&mut self, event: &RawEvent, cx: &mut Context<Self>) {
        if event.kind == kind::DOCUMENT_RESULT {
            self.on_document_result(&event.json, cx);
        } else if event.kind == kind::RINGTONE_PICKED {
            self.ringtone_picked(&event.json, cx);
        } else if event.kind == kind::WA_PUSH {
            self.whatsapp.update(cx, |w, cx| w.wake(cx));
        } else if event.kind == kind::OPEN_CONTACT {
            match rovsti_android::events::parse_open_contact(&event.json) {
                Some(id) => self.open_contact_from_event(id, cx),
                None => log::warn!("bad open_contact: {}", event.json),
            }
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
            ref op @ (DocumentOp::ExportContacts
            | DocumentOp::ExportCalls
            | DocumentOp::ExportMessages) => {
                self.transfer_busy = false;
                let count = self.exported.take();
                // A cancelled picker answers `ok: false` with no error: say nothing.
                self.notice = document_op_notice(op, result.ok, result.error.is_some(), count)
                    .flatten()
                    .map(Into::into);
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
            DocumentOp::ScanImage => {
                // Still busy while the picture is read; the answer ends it.
                let path = result.ok.then_some(result.path).flatten();
                match path {
                    Some(path) => self.scan_picked(path, cx),
                    None => {
                        self.transfer_busy = false;
                        // A cancelled picker answers `ok: false` with no error.
                        if result.error.is_some() || result.ok {
                            self.notice = Some("Can't read that picture.".into());
                        }
                        cx.notify();
                    }
                }
            }
            _ => {}
        }
    }

    /// Crops, scales and stores the image at `path` as the photo of contact
    /// `id` on the background executor, then refreshes its thumbnail.
    pub(crate) fn apply_photo(&mut self, id: i64, path: String, cx: &mut Context<Self>) {
        self.apply_photo_with(id, path, CropView::default(), cx);
    }

    /// Like [`Self::apply_photo`], with the crop the owner chose.
    pub(crate) fn apply_photo_with(
        &mut self,
        id: i64,
        path: String,
        view: CropView,
        cx: &mut Context<Self>,
    ) {
        self.notice = Some("Saving the photo…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::change_photo_with(id, &path, view) })
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

    /// Asks before adding the new ones among `cards` (a scanned code).
    pub(crate) fn plan_cards(&mut self, cards: Vec<ContactDraft>, cx: &mut Context<Self>) {
        let existing: Vec<WireContact> = self
            .index()
            .map(|index| index.all().cloned().collect())
            .unwrap_or_default();
        cx.spawn(async move |this, cx| {
            let plan = cx
                .background_executor()
                .spawn(async move { import_plan::plan(&existing, cards) })
                .await;
            this.update(cx, |this, cx| {
                this.clear_notice();
                this.confirm = Some(Confirm::Import {
                    cards: plan.add,
                    photos: Vec::new(),
                    duplicates: plan.duplicates,
                    unreadable: 0,
                });
                cx.notify();
            })
            .ok();
        })
        .detach();
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
            let (plan, photos, unreadable, single) = cx
                .background_executor()
                .spawn(async move {
                    let (cards, photos, skipped) = backend::cards_with_photos(&text);
                    // One card with a picture opens the form, like a shared file.
                    let intake = Intake::Cards(cards);
                    let photo = intake::photo_path(&intake, &text);
                    let Intake::Cards(cards) = intake else {
                        unreachable!("built above");
                    };
                    if photo.is_some() && skipped == 0 {
                        let none = (import_plan::Plan::default(), Vec::new());
                        return (none.0, none.1, 0, Some((cards, photo)));
                    }
                    let (plan, kept) = import_plan::plan_with_photos(&existing, cards, photos);
                    (plan, kept, skipped, None)
                })
                .await;
            this.update(cx, |this, cx| {
                this.clear_notice();
                this.transfer_busy = false;
                if let Some((cards, photo)) = single {
                    this.route_intake(Intake::Cards(cards), intake::Source::Shared, photo, cx);
                    return;
                }
                this.confirm = Some(Confirm::Import {
                    cards: plan.add,
                    photos,
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
    use super::*;

    #[test]
    fn export_words_by_count() {
        assert_eq!(export_words(0), "There are no contacts to export.");
        assert_eq!(export_words(1), "Saved 1 contact to the file.");
        assert_eq!(export_words(12), "Saved 12 contacts to the file.");
    }

    #[test]
    fn settings_lists_both_exports() {
        let rows = export_rows();
        assert_eq!(rows[0].format, ExportFormat::Vcf);
        assert_eq!(rows[0].label, "Export to a .vcf file");
        assert_eq!(rows[1].format, ExportFormat::Csv);
        assert_eq!(rows[1].label, "Export to a CSV file");
        assert_ne!(rows[0].id, rows[1].id);
    }

    #[test]
    fn the_calendar_answer_is_noticed_in_dates() {
        let op = DocumentOp::ExportMessages;
        assert_eq!(
            document_op_notice(&op, true, false, Some(12)),
            Some(Some("Saved 12 dates to the calendar file".to_owned()))
        );
        assert_eq!(
            document_op_notice(&op, true, false, Some(1)),
            Some(Some("Saved 1 date to the calendar file".to_owned()))
        );
        assert_eq!(
            document_op_notice(&op, false, true, None),
            Some(Some("Can't save the file.".to_owned()))
        );
        assert_eq!(document_op_notice(&op, false, false, None), Some(None));
    }

    #[test]
    fn export_calls_is_noticed_like_export_contacts() {
        for (ok, error, count) in [
            (true, false, Some(412)),
            (true, false, None),
            (false, true, Some(3)),
            (false, false, None),
        ] {
            assert_eq!(
                document_op_notice(&DocumentOp::ExportCalls, ok, error, count),
                document_op_notice(&DocumentOp::ExportContacts, ok, error, count),
            );
        }
        assert_eq!(
            document_op_notice(&DocumentOp::ExportCalls, true, false, Some(412)),
            Some(Some("Saved 412 contacts to the file.".to_owned()))
        );
        assert_eq!(
            document_op_notice(&DocumentOp::ExportCalls, false, true, None),
            Some(Some("Can't save the file.".to_owned()))
        );
        assert_eq!(
            document_op_notice(&DocumentOp::ExportCalls, false, false, None),
            Some(None)
        );
        assert_eq!(
            document_op_notice(&DocumentOp::ImportContacts, true, false, Some(1)),
            None
        );
    }
}
