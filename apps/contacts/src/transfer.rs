//! vCard export and import, and the events Kotlin sends back.
//!
//! Both start a system file picker and return at once. The answer is a
//! one-shot `document_result` event (`rovsti_core::wire::DocumentResult`),
//! which `ContactsApp::on_event` routes here. No storage permission is used.

use gpui::Context;
use rovsti_android::events::{RawEvent, kind};
use rovsti_core::{
    import_plan, vcard,
    wire::{DocumentOp, DocumentResult, WireContact},
};

use crate::{ContactsApp, backend, confirm::Confirm};

impl ContactsApp {
    /// Reads every contact with a number and offers it as `contacts.vcf`.
    pub(crate) fn export_contacts(&mut self, cx: &mut Context<Self>) {
        if self.transfer_busy {
            return;
        }
        self.transfer_busy = true;
        self.notice = Some("Preparing the file…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let (text, count) = backend::export_text()?;
                    backend::start_export(&text).map(|()| count)
                })
                .await;
            this.update(cx, |this, cx| {
                match result {
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
            return;
        }
        self.transfer_busy = true;
        self.notice = None;
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
                    (true, _, Some(count)) => Some(
                        format!(
                            "Saved {count} {} to the file.",
                            if count == 1 { "contact" } else { "contacts" }
                        )
                        .into(),
                    ),
                    (false, Some(_), _) => Some("Can't save the file.".into()),
                    _ => None,
                };
                cx.notify();
            }
            DocumentOp::ImportContacts => {
                self.transfer_busy = false;
                if !result.ok {
                    if result.error.is_some() {
                        self.notice = Some("Can't read that file.".into());
                    }
                    cx.notify();
                    return;
                }
                self.plan_import(result.text.unwrap_or_default(), cx);
            }
            _ => {}
        }
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
                this.notice = None;
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
