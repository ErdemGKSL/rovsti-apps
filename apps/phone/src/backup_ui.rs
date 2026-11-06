//! Backup and restore (story 16.6): Export and Import rows, the Import
//! preview sheet, and the `document_result` hand-back. The files are chosen
//! with the system picker; no storage permission is involved.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Role,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_core::{settings::ImportPreview, wire::DocumentOp};

use crate::block_ui::PillAction;
use rovsti_ui::{
    motion::press_scale,
    motion_tokens::scale,
    storage, theme,
    tokens::{Theme, mix, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, SheetKind, backend::Outcome};

/// "This will replace: settings (n), favorites order (n), ..." (EXPERIENCE copy).
pub(crate) fn replace_line(p: &ImportPreview) -> String {
    format!(
        "This will replace: settings ({}), favorites order ({}), speed dial ({}), quick replies ({}).",
        p.settings_changed, p.favorites, p.speed_dial, p.quick_replies
    )
}

/// Title of the call history export row in Settings > Data.
pub(crate) const EXPORT_CALLS_TITLE: &str = "Export call history (CSV)";

/// The note that disables the export row, `None` once the call log is loaded.
pub(crate) fn export_calls_note(loaded: bool) -> Option<&'static str> {
    (!loaded).then_some("Call history isn't loaded yet")
}

/// Pill text for the answer of the call history export.
pub(crate) fn export_calls_pill(ok: bool, failed: bool) -> Option<&'static str> {
    if ok {
        Some("Call history exported")
    } else if failed {
        Some("Couldn't export")
    } else {
        Some("Export cancelled")
    }
}

#[derive(Default)]
pub(crate) struct BackupUi {
    /// The parsed file the open sheet would import.
    pub preview: Option<ImportPreview>,
    /// "{name} moved from {from} to {to}" notes under the line.
    pub notes: Vec<SharedString>,
    seen: u64,
}

impl PhoneApp {
    pub(crate) fn backup_export(&mut self, cx: &mut Context<Self>) {
        let outcome = self.backend.update(cx, |b, cx| b.export_backup(cx));
        if outcome.failed() {
            self.show_notice_pill("Couldn't open the file picker", None, false, cx);
        }
    }

    /// Builds the call history CSV off the UI thread and opens "save as".
    pub(crate) fn backup_export_calls(&mut self, cx: &mut Context<Self>) {
        let (source, tz) = match self.backend.read(cx).call_log() {
            crate::backend::Load::Ready(log) => (log.source.clone(), log.locale.tz_offset_s),
            _ => {
                self.show_notice_pill("Call history isn't loaded yet", None, false, cx);
                return;
            }
        };
        let extras = self.wa_call.read(cx).recents_extras();
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| d.as_millis() as i64);
        cx.spawn(async move |this, cx| {
            let (name, csv) = cx
                .background_executor()
                .spawn(async move {
                    (
                        rovsti_core::call_log_query::csv_file_name(now_ms, tz),
                        rovsti_core::call_log_query::to_csv(&source, &extras, tz),
                    )
                })
                .await;
            this.update(cx, |this, cx| {
                if crate::backend::export_calls(&name, &csv).failed() {
                    this.show_notice_pill("Couldn't export", None, false, cx);
                }
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn backup_import(&mut self, cx: &mut Context<Self>) {
        self.settings.import_error = None;
        let outcome: Outcome = self.backend.update(cx, |b, cx| b.import_backup(cx));
        if outcome.failed() {
            self.show_notice_pill("Couldn't open the file picker", None, false, cx);
        }
    }

    /// Once per backend change: takes a finished picker's answer.
    pub(crate) fn sync_documents(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let generation = self.backend.read(cx).document_gen();
        if generation == self.backup.seen {
            return;
        }
        self.backup.seen = generation;
        let Some(result) = self.backend.update(cx, |b, _| b.take_document()) else {
            return;
        };
        // The Diagnostics "Backup" check (S13) is waiting for this answer.
        if self.diag.backup.is_some() {
            self.diag_backup_result(result, cx);
            return;
        }
        match result.op {
            DocumentOp::ExportBackup => {
                if result.ok {
                    let name = result.name.unwrap_or_else(|| "your device".into());
                    self.show_notice_pill(format!("Saved to {name}"), None, false, cx);
                } else if result.error.is_some() {
                    self.show_notice_pill("Couldn't save the file", None, false, cx);
                }
            }
            DocumentOp::ExportCalls => {
                if let Some(text) = export_calls_pill(result.ok, result.error.is_some()) {
                    self.show_notice_pill(text, None, false, cx);
                }
            }
            DocumentOp::ImportBackup => {
                if !(result.ok || result.error.is_some()) {
                    return; // the picker was cancelled
                }
                let text = result.text.filter(|_| result.ok).unwrap_or_default();
                let parsed = storage::parse_backup(&text, self.backend.read(cx).prefs());
                match parsed {
                    Ok(preview) => {
                        self.settings.import_error = None;
                        self.backup.notes = preview
                            .moved
                            .iter()
                            .map(|m| {
                                let name = preview.speed_dial_name(m.to).unwrap_or("A contact");
                                SharedString::from(format!(
                                    "{name} moved from {} to {}",
                                    m.from, m.to
                                ))
                            })
                            .collect();
                        self.backup.preview = Some(preview);
                        self.sheet_kind = SheetKind::ImportPreview;
                        self.sheet.open();
                    }
                    Err(refusal) => {
                        self.settings.import_error = Some(refusal.message());
                    }
                }
                self.sync_settings_rows(cx, false);
            }
            _ => {}
        }
        self.update_back(window);
        cx.notify();
    }

    fn import_confirm(&mut self, cx: &mut Context<Self>) {
        if let Some(preview) = self.backup.preview.take() {
            let undo = ImportPreview::restoring(self.backend.read(cx).prefs());
            self.backend.update(cx, |b, cx| b.apply_import(preview, cx));
            self.sync_settings_rows(cx, false);
            self.show_pill_action(
                "Backup imported",
                "Undo",
                PillAction::RestorePrefs(Box::new(undo)),
                cx,
            );
        }
        self.close_sheet(cx);
    }

    fn import_cancel(&mut self, cx: &mut Context<Self>) {
        self.backup.preview = None;
        self.close_sheet(cx);
    }

    pub(crate) fn backup_sheet_act(&mut self, id: &str, cx: &mut Context<Self>) {
        match id {
            "sheet-import-go" => self.import_confirm(cx),
            "sheet-import-cancel" => self.import_cancel(cx),
            _ => {}
        }
    }

    fn backup_button(
        &self,
        theme: Theme,
        id: &'static str,
        label: &'static str,
        primary: bool,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let colors = theme::colors(theme);
        let press = self.presses.value(id);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .relative()
            .h(px(50.0))
            .w_full()
            .rounded_full()
            .flex()
            .items_center()
            .justify_center()
            .bg(if primary {
                mix(colors.accent, gpui::white(), 0.2 * press.clamp(0.0, 1.0))
            } else {
                mix(colors.fill, colors.label, 0.1 * press.clamp(0.0, 1.0))
            })
            .text_size(px(17.0 * press_scale(press, scale::PRESS)))
            .font_weight(typography::HEADLINE.weight)
            .text_color(if primary { gpui::white() } else { colors.label })
            .child(label)
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, _, cx| {
                    this.presses.down(id);
                    this.backup_sheet_act(id, cx);
                }),
            )
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
    }

    /// The Import preview sheet (large detent) body.
    pub(crate) fn import_sheet_body(
        &self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let preview = self.backup.preview.as_ref()?;
        let colors = theme::colors(theme);
        Some(
            div()
                .flex()
                .flex_col()
                .gap(px(12.0))
                .px(px(20.0))
                .pb(px(20.0))
                .child(
                    div()
                        .text_size(typography::TITLE2.size)
                        .line_height(typography::TITLE2.line)
                        .font_weight(typography::TITLE2.weight)
                        .text_color(colors.label)
                        .child("Import backup"),
                )
                .child(
                    div()
                        .text_size(typography::BODY.size)
                        .text_color(colors.label)
                        .child(replace_line(preview)),
                )
                .children(self.backup.notes.iter().map(|note| {
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(note.clone())
                }))
                .child(
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child("This can't be undone."),
                )
                .child(self.backup_button(theme, "sheet-import-go", "Import", true, cx))
                .child(self.backup_button(theme, "sheet-import-cancel", "Cancel", false, cx))
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::settings::PrefsV2;

    #[test]
    fn export_row_text_and_states() {
        assert_eq!(EXPORT_CALLS_TITLE, "Export call history (CSV)");
        assert_eq!(export_calls_note(true), None);
        assert_eq!(
            export_calls_note(false),
            Some("Call history isn't loaded yet")
        );
        assert_eq!(
            export_calls_pill(true, false),
            Some("Call history exported")
        );
        assert_eq!(export_calls_pill(false, true), Some("Couldn't export"));
        assert_eq!(export_calls_pill(false, false), Some("Export cancelled"));
    }

    #[test]
    fn the_line_names_the_four_sections() {
        let text = storage::backup_json(&PrefsV2::default(), 0, "0.2.0");
        let preview = storage::parse_backup(&text, &PrefsV2::default()).expect("preview");
        assert_eq!(
            replace_line(&preview),
            "This will replace: settings (0), favorites order (0), speed dial (0), quick replies (3)."
        );
    }
}
