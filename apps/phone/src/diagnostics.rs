//! The hidden Diagnostics page (story 14.0f, AD-36): platform probes the owner
//! runs on the phone, with a "Copy report" button. It replaces the Setup
//! sheet's content after a long press on the Setup title, so there is no
//! navigation entry anywhere else (16.1 moves the trigger to the Settings
//! title). Rows have fixed heights (UIQ-2); the result lines scroll in a fixed
//! window below them.

use gpui::{
    AnyElement, ClipboardItem, Context, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Role, SharedString, StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_android::diagnostics::{EVENTS, GROUPS, HEADER};
use rovsti_ui::{
    motion::press_scale,
    motion_tokens::scale,
    theme,
    tokens::{Theme, mix, radius, typography},
    touch::touch_surface,
};

use crate::PhoneApp;

/// Long-press on the Setup title opens the page.
pub(crate) const OPEN_AFTER_MS: u64 = 800;
/// Probe groups with a probe behind them; the rest read "Not built yet".
const REGISTERED: [&str; 5] = ["audio", "calls", "sim", "backup", "system"];
const ROW_H: f32 = 48.0;
const LOG_H: f32 = 168.0;

/// One finished probe.
struct Done {
    probe: &'static str,
    /// `pass`, `fail`, `unknown` counts for the row.
    summary: SharedString,
    lines: Vec<SharedString>,
}

/// Where the S13 round trip is (story 16.6): the owner drives two pickers.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BackupStage {
    Exporting,
    Importing,
}

/// The tiny file the S13 check writes and reads back.
const BACKUP_PROBE: &str = "{\"probe\":\"rovsti-s13\",\"n\":1}";

#[derive(Default)]
pub(crate) struct Diagnostics {
    pub(crate) backup: Option<BackupStage>,
    backup_lines: Vec<SharedString>,
    done: Vec<Done>,
    running: Option<&'static str>,
    /// "Copied" until the Notice pill takes over (14.3).
    pub(crate) copied: bool,
}

/// `pass`, `fail`, `unknown` line counts.
fn count(text: &str) -> (usize, usize, usize) {
    let mut c = (0, 0, 0);
    for line in text.lines() {
        if line.starts_with("pass") {
            c.0 += 1;
        } else if line.starts_with("fail") {
            c.1 += 1;
        } else if line.starts_with("unknown") {
            c.2 += 1;
        }
    }
    c
}

/// The text the clipboard gets: header, every probe of this session, the event
/// ring. No numbers and no names appear in any of them.
pub(crate) fn report(header: &str, sections: &[(&str, &str)], events: &str) -> String {
    let mut out = String::with_capacity(512);
    out.push_str("Rovsti Phone diagnostics\n");
    out.push_str(header.trim());
    out.push('\n');
    for (title, text) in sections {
        out.push_str("\n== ");
        out.push_str(title);
        out.push_str(" ==\n");
        out.push_str(text.trim());
        out.push('\n');
    }
    out.push_str("\n== Call event ring ==\n");
    out.push_str(if events.trim().is_empty() {
        "(empty)"
    } else {
        events.trim()
    });
    out.push('\n');
    out
}

/// Runs a probe synchronously; call from a background thread only.
fn run_probe(probe: &str) -> String {
    match rovsti_android::diagnostics::run(probe) {
        Ok(text) => text,
        Err(rovsti_android::Error::Unsupported) => {
            "unknown: Not available on this platform".to_owned()
        }
        Err(error) => format!("unknown: {error}"),
    }
}

/// The desktop header (the phone's comes from Kotlin).
fn desktop_header() -> String {
    format!(
        "app={} os={} date=desktop",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS
    )
}

impl PhoneApp {
    /// S13: export a small file, import it back, compare. Driven by the
    /// pickers, so it lives here and not in Kotlin.
    fn diag_backup_start(&mut self, cx: &mut Context<Self>) {
        self.diag.running = Some("backup");
        self.diag.copied = false;
        self.diag.backup_lines.clear();
        let outcome = self.backend.update(cx, |b, cx| {
            b.export_raw("rovsti-s13-probe.json", BACKUP_PROBE, cx)
        });
        if outcome.failed() {
            self.diag_backup_finish("fail: export picker started (no activity answered)", cx);
        } else {
            self.diag.backup = Some(BackupStage::Exporting);
            self.diag
                .backup_lines
                .push("info: choose a place to save the test file".into());
        }
        cx.notify();
    }

    fn diag_backup_finish(&mut self, last: &str, cx: &mut Context<Self>) {
        self.diag.backup = None;
        self.diag.backup_lines.push(last.to_owned().into());
        let lines = std::mem::take(&mut self.diag.backup_lines);
        let (p, f, u) = count(
            &lines
                .iter()
                .map(|l| l.as_ref())
                .collect::<Vec<_>>()
                .join("\n"),
        );
        self.diag.done.retain(|d| d.probe != "backup");
        self.diag.done.push(Done {
            probe: "backup",
            summary: format!("{p} pass, {f} fail, {u} unknown").into(),
            lines,
        });
        self.diag.running = None;
        cx.notify();
    }

    /// A picker answered while the S13 check is waiting for it.
    pub(crate) fn diag_backup_result(
        &mut self,
        result: rovsti_core::wire::DocumentResult,
        cx: &mut Context<Self>,
    ) {
        use rovsti_core::wire::DocumentOp;
        match (self.diag.backup, result.op) {
            (Some(BackupStage::Exporting), DocumentOp::ExportBackup) => {
                if !result.ok {
                    let why = if result.error.is_some() {
                        "fail"
                    } else {
                        "unknown"
                    };
                    self.diag_backup_finish(
                        &format!(
                            "{why}: export wrote a file (the picker was cancelled or refused)"
                        ),
                        cx,
                    );
                    return;
                }
                self.diag
                    .backup_lines
                    .push("pass: export wrote a file with no storage permission".into());
                let outcome = self.backend.update(cx, |b, cx| b.import_backup(cx));
                if outcome.failed() {
                    self.diag_backup_finish("fail: import picker started", cx);
                } else {
                    self.diag.backup = Some(BackupStage::Importing);
                    self.diag
                        .backup_lines
                        .push("info: now choose the test file you just saved".into());
                }
            }
            (Some(BackupStage::Importing), DocumentOp::ImportBackup) => {
                let verdict = match (result.ok, result.text.as_deref()) {
                    (true, Some(text)) if text == BACKUP_PROBE => {
                        "pass: import read back the same text"
                    }
                    (true, _) => "fail: import read back the same text (the contents differ)",
                    (false, _) if result.error.is_some() => "fail: import read the file",
                    (false, _) => "unknown: import picker was cancelled",
                };
                self.diag_backup_finish(verdict, cx);
            }
            _ => {}
        }
    }

    pub(crate) fn diag_run(&mut self, probe: &'static str, cx: &mut Context<Self>) {
        if !REGISTERED.contains(&probe) || self.diag.running.is_some() {
            return;
        }
        if probe == "backup" {
            self.diag_backup_start(cx);
            return;
        }
        self.diag.running = Some(probe);
        self.diag.copied = false;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let text = cx
                .background_executor()
                .spawn(async move { run_probe(probe) })
                .await;
            this.update(cx, |this, cx| {
                let (p, f, u) = count(&text);
                let lines = text
                    .lines()
                    .map(|l| SharedString::from(l.to_owned()))
                    .collect();
                this.diag.done.retain(|d| d.probe != probe);
                this.diag.done.push(Done {
                    probe,
                    summary: format!("{p} pass, {f} fail, {u} unknown").into(),
                    lines,
                });
                this.diag.running = None;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Builds the report off the UI thread, then writes the clipboard.
    pub(crate) fn diag_copy(&mut self, cx: &mut Context<Self>) {
        let sections: Vec<(&'static str, String)> = self
            .diag
            .done
            .iter()
            .map(|d| {
                let title = GROUPS
                    .iter()
                    .find(|(p, _)| *p == d.probe)
                    .map_or(d.probe, |(_, t)| *t);
                let text = d
                    .lines
                    .iter()
                    .map(|l| l.as_ref())
                    .collect::<Vec<_>>()
                    .join("\n");
                (title, text)
            })
            .collect();
        cx.spawn(async move |this, cx| {
            let text = cx
                .background_executor()
                .spawn(async move {
                    let header = match rovsti_android::diagnostics::run(HEADER) {
                        Ok(h) => h,
                        Err(_) => desktop_header(),
                    };
                    let events = run_probe(EVENTS);
                    let events = if events.starts_with("unknown:") {
                        String::new()
                    } else {
                        events
                    };
                    let refs: Vec<(&str, &str)> =
                        sections.iter().map(|(t, s)| (*t, s.as_str())).collect();
                    report(&header, &refs, &events)
                })
                .await;
            this.update(cx, |this, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(text));
                this.show_notice_pill("Copied", None, false, cx);
            })
            .ok();
        })
        .detach();
    }

    fn diag_row(
        &self,
        theme: Theme,
        probe: &'static str,
        title: &'static str,
        cx: &mut Context<Self>,
    ) -> impl IntoElement {
        let colors = theme::colors(theme);
        let built = REGISTERED.contains(&probe);
        let running = self.diag.running == Some(probe);
        let busy = self.diag.running.is_some();
        let id = SharedString::from(format!("sheet-diag-{probe}"));
        let press = self.presses.value(&id);
        let trailing: SharedString = if !built {
            "Not built yet".into()
        } else if running {
            "Running...".into()
        } else if let Some(done) = self.diag.done.iter().find(|d| d.probe == probe) {
            done.summary.clone()
        } else {
            "Run".into()
        };
        let enabled = built && !busy;
        let k = press_scale(press, scale::PRESS);
        let mut row = div()
            .id(id.clone())
            .role(Role::Button)
            .aria_label(SharedString::from(format!("{title}, {trailing}")))
            .aria_disabled(!enabled)
            .relative()
            .h(px(ROW_H))
            .w_full()
            .px(px(14.0))
            .rounded(radius::CARD)
            .bg(mix(
                gpui::Hsla {
                    a: 0.6,
                    ..colors.fill
                },
                colors.label,
                0.10 * press.clamp(0.0, 1.0),
            ))
            .opacity(if built { 1.0 } else { 0.5 })
            .flex()
            .items_center()
            .justify_between()
            .child(
                div()
                    .text_size(px(17.0 * k))
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.label)
                    .child(title),
            )
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(if built {
                        colors.accent
                    } else {
                        colors.secondary_label
                    })
                    .child(trailing),
            );
        if enabled {
            row = row
                .on_mouse_down(MouseButton::Left, {
                    let id = id.clone();
                    cx.listener(move |this, _, _, cx| {
                        this.presses.down(id.clone());
                        this.diag_run(probe, cx);
                    })
                })
                .child(touch_surface(Self::touch_listener(cx, Some(id))));
        }
        row
    }

    /// The page body (inside the sheet).
    pub(crate) fn diag_body(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let log: Vec<SharedString> = self
            .diag
            .done
            .iter()
            .flat_map(|d| d.lines.iter().cloned())
            .chain(self.diag.backup_lines.iter().cloned())
            .collect();
        let copy_id = SharedString::new_static("sheet-diag-copy");
        let press = self.presses.value(&copy_id);
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .px(px(14.0))
            .pb(px(16.0))
            .child(
                div()
                    .px(px(4.0))
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .text_size(typography::TITLE2.size)
                            .line_height(typography::TITLE2.line)
                            .font_weight(typography::TITLE2.weight)
                            .text_color(colors.label)
                            .child("Diagnostics"),
                    )
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child("Platform checks for this phone. Nothing leaves the device."),
                    ),
            )
            .children(
                GROUPS.iter().map(|(probe, title)| {
                    self.diag_row(theme, probe, title, cx).into_any_element()
                }),
            )
            .child(
                div()
                    .id("diag-log")
                    .role(Role::Log)
                    .aria_label("Probe results")
                    .h(px(LOG_H))
                    .w_full()
                    .p(px(10.0))
                    .rounded(radius::CARD)
                    .bg(gpui::Hsla {
                        a: 0.35,
                        ..colors.fill
                    })
                    .overflow_y_scroll()
                    .flex()
                    .flex_col()
                    .gap(px(2.0))
                    .text_size(px(11.0))
                    .text_color(colors.secondary_label)
                    .children(if log.is_empty() {
                        vec![SharedString::new_static("No probe has run yet.")]
                    } else {
                        log
                    }),
            )
            .child(
                div()
                    .id(copy_id.clone())
                    .role(Role::Button)
                    .aria_label("Copy report")
                    .relative()
                    .h(px(44.0))
                    .w_full()
                    .rounded_full()
                    .bg(mix(
                        colors.accent,
                        gpui::white(),
                        0.2 * press.clamp(0.0, 1.0),
                    ))
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(px(17.0 * press_scale(press, scale::PRESS)))
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(gpui::white())
                    .child("Copy report")
                    .on_mouse_down(MouseButton::Left, {
                        let id = copy_id.clone();
                        cx.listener(move |this, _, _, cx| {
                            this.presses.down(id.clone());
                            this.diag_copy(cx);
                        })
                    })
                    .child(touch_surface(Self::touch_listener(cx, Some(copy_id)))),
            )
            .into_any_element()
    }

    /// The Diagnostics overlay: the one sheet, showing this page.
    pub(crate) fn diag_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let body = self.diag_body(theme, cx);
        self.modal_sheet(theme, window, "Diagnostics", body, cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_read_the_line_prefixes() {
        let text = "t=1 earpiece\npass: a\nfail: b\nunknown: c\nunknown: d";
        assert_eq!(count(text), (1, 1, 2));
    }

    #[test]
    fn the_report_has_header_probes_and_ring() {
        let r = report(
            "app=1 model=X",
            &[("Audio", "pass: names")],
            "0 onCallAdded",
        );
        assert!(r.starts_with("Rovsti Phone diagnostics\napp=1 model=X"));
        assert!(r.contains("== Audio ==\npass: names"));
        assert!(r.contains("== Call event ring ==\n0 onCallAdded"));
        assert!(report("h", &[], "").contains("(empty)"));
    }

    #[test]
    fn the_built_groups_so_far() {
        assert_eq!(REGISTERED, ["audio", "calls", "sim", "backup", "system"]);
        assert!(GROUPS.iter().any(|(p, _)| *p == "audio"));
        assert!(GROUPS.iter().any(|(p, _)| *p == "calls"));
    }
}
