//! The Settings tab: default SMS app, notifications, spam, backup, and the
//! way to the WhatsApp tab.
//!
//! Everything it changes lives in [`crate::prefs::MessagesPrefs`] (through the
//! backend, which saves it) or in Android (default role, system blocklist).
//! The encrypted backup runs off the UI thread: reading the provider, PBKDF2
//! and AES-GCM (`rovsti_core::msg_backup`), then the file hand-off through
//! `rovsti_android::documents`. Its state is the pure machine in
//! [`crate::backup_ui`].

use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
};

use gpui::{
    AnyElement, AppContext as _, Context, Entity, EventEmitter, InteractiveElement, IntoElement,
    ParentElement, Render, SharedString, StatefulInteractiveElement, Styled, TextInputAction,
    TextInputPurpose, Window, div, px,
};
use rovsti_android::{Error, documents, sms};
use rovsti_core::{
    ThreadId, blocklist,
    msg_backup::{self, BackupBody, BackupError, RestorePlan},
    sms::SmsThreadRow,
    spam::SpamRule,
    wire::{DocumentOp, DocumentResult, EmergencyList},
};
use rovsti_ui::{
    components::{glass_pill, list_row, section},
    current_theme,
    field::{TextField, field_box, secret_field_box},
    theme,
    tokens::{Theme, typography},
};
use rovsti_wa_proto::jid_user;

use crate::{
    backend::{Backend, BackendEvent, now_ms},
    backup_ui::{self, BackupState, Busy, Issue},
    notifications::parse_tag,
};

/// The largest archive the picker may hand over.
const IMPORT_MAX_BYTES: u64 = 64 * 1024 * 1024;

/// Where the sealed archive waits for the system "save as", under the data dir.
const CACHE_DIR: &str = "cache";

const MIME: &str = "application/octet-stream";

pub enum SettingsEvent {
    /// The row that points at the WhatsApp tab was tapped.
    OpenWhatsApp,
    /// Something was removed; the pill offers to put it back.
    Undoable {
        text: SharedString,
        undo: SettingsUndo,
    },
}

/// What "Undo" restores after a Settings delete.
#[derive(Clone, Debug)]
pub enum SettingsUndo {
    Rule(usize, SpamRule),
    Allow(String),
    Block(String),
    Mute(String),
}

impl EventEmitter<SettingsEvent> for SettingsView {}

/// What a spam rule form adds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RuleKind {
    Sender,
    Keyword,
    UnknownLinks,
    ShortCodes,
}

impl RuleKind {
    pub fn label(self) -> &'static str {
        match self {
            Self::Sender => "Sender",
            Self::Keyword => "Text contains",
            Self::UnknownLinks => "Unknown links",
            Self::ShortCodes => "Short codes",
        }
    }

    pub fn next(self) -> Self {
        match self {
            Self::Sender => Self::Keyword,
            Self::Keyword => Self::UnknownLinks,
            Self::UnknownLinks => Self::ShortCodes,
            Self::ShortCodes => Self::Sender,
        }
    }

    pub fn needs_text(self) -> bool {
        matches!(self, Self::Sender | Self::Keyword)
    }

    pub fn build(self, text: &str) -> SpamRule {
        match self {
            Self::Sender => SpamRule::Sender {
                pattern: text.to_owned(),
            },
            Self::Keyword => SpamRule::Keyword {
                text: text.to_owned(),
            },
            Self::UnknownLinks => SpamRule::UnknownWithLink,
            Self::ShortCodes => SpamRule::ShortCodeOnly(true),
        }
    }
}

/// One rule as the list shows it.
pub fn rule_text(rule: &SpamRule) -> String {
    match rule {
        SpamRule::Sender { pattern } => format!("Sender: {pattern}"),
        SpamRule::Keyword { text } => format!("Text contains: {text}"),
        SpamRule::UnknownWithLink => "Links from unknown senders".to_owned(),
        SpamRule::ShortCodeOnly(true) => "Short-code senders".to_owned(),
        SpamRule::ShortCodeOnly(false) => "Short-code senders (off)".to_owned(),
    }
}

/// A muted notification tag as a person reads it.
pub fn tag_label(
    tag: &str,
    threads: &[SmsThreadRow],
    name_of: impl Fn(&ThreadId) -> Option<String>,
) -> String {
    let parsed = parse_tag(tag);
    if let Some(name) = parsed
        .as_ref()
        .and_then(&name_of)
        .filter(|n| !n.trim().is_empty())
    {
        return name;
    }
    match parsed {
        Some(ThreadId::Sms(id)) => threads
            .iter()
            .find(|t| t.thread_id == id)
            .map(|t| t.addresses.join(", "))
            .filter(|label| !label.is_empty())
            .unwrap_or_else(|| format!("SMS conversation {id}")),
        Some(ThreadId::WhatsApp(jid)) => format!("WhatsApp {}", jid_user(&jid)),
        None => tag.to_owned(),
    }
}

/// An archive opened and compared with the phone, waiting for "Restore".
struct Restore {
    body: BackupBody,
    plan: RestorePlan,
}

enum OpenFailure {
    Backup(BackupError),
    Io(String),
}

pub struct SettingsView {
    backend: Entity<Backend>,
    rule_field: Entity<TextField>,
    allow_field: Entity<TextField>,
    block_field: Entity<TextField>,
    pass: Entity<TextField>,
    confirm: Entity<TextField>,
    open_pass: Entity<TextField>,
    rule_kind: RuleKind,
    /// A line under the Spam section ("Already in the list").
    note: Option<SharedString>,
    backup: BackupState,
    /// The sealed file handed to "save as", removed once it is saved.
    export_path: Option<PathBuf>,
    /// The archive the picker copied into the app's cache.
    picked: Option<String>,
    restore: Option<Restore>,
    /// WhatsApp chat titles by JID, for the names in Muted.
    wa_titles: HashMap<String, String>,
    _subscriptions: Vec<gpui::Subscription>,
}

impl SettingsView {
    /// The inbox's chat titles by JID, so Muted can name WhatsApp chats.
    pub fn set_wa_titles(&mut self, titles: HashMap<String, String>, cx: &mut Context<Self>) {
        if self.wa_titles != titles {
            self.wa_titles = titles;
            cx.notify();
        }
    }

    pub fn new(backend: Entity<Backend>, cx: &mut Context<Self>) -> Self {
        let field = |purpose, action, cx: &mut Context<Self>| {
            cx.new(|cx| TextField::with_input(purpose, action, cx))
        };
        let rule_field = field(TextInputPurpose::Text, TextInputAction::Done, cx);
        let allow_field = field(TextInputPurpose::Text, TextInputAction::Done, cx);
        let block_field = field(TextInputPurpose::Phone, TextInputAction::Done, cx);
        let pass = field(TextInputPurpose::Text, TextInputAction::Next, cx);
        let confirm = field(TextInputPurpose::Text, TextInputAction::Done, cx);
        let open_pass = field(TextInputPurpose::Text, TextInputAction::Done, cx);
        let mut subscriptions = vec![
            cx.observe(&backend, |_, _, cx| cx.notify()),
            cx.subscribe(&backend, |this, _, event, cx| {
                if let BackendEvent::Document(result) = event {
                    this.on_document(result, cx);
                }
            }),
        ];
        for field in [&rule_field, &allow_field, &block_field, &open_pass] {
            subscriptions.push(cx.observe(field, |_, _, cx| cx.notify()));
        }
        for field in [&pass, &confirm] {
            subscriptions.push(cx.observe(field, |this, _, cx| {
                this.backup.edited();
                cx.notify();
            }));
        }
        Self {
            backend,
            rule_field,
            allow_field,
            block_field,
            pass,
            confirm,
            open_pass,
            rule_kind: RuleKind::Sender,
            note: None,
            backup: BackupState::Idle,
            export_path: None,
            picked: None,
            restore: None,
            wa_titles: HashMap::new(),
            _subscriptions: subscriptions,
        }
    }

    // ---- spam ---------------------------------------------------------------

    fn add_rule(&mut self, cx: &mut Context<Self>) {
        let text = self.rule_field.read(cx).text().trim().to_owned();
        let rule = self.rule_kind.build(&text);
        let mut result = Ok(());
        self.backend.update(cx, |backend, cx| {
            let mut rules = backend.spam_rules().clone();
            result = rules.add_rule(rule);
            if result.is_ok() {
                backend.update_prefs(cx, |prefs| prefs.spam = rules);
            }
        });
        self.note = result.err().map(|e| e.to_string().into());
        if result.is_ok() {
            self.rule_field.update(cx, |field, cx| field.clear(cx));
        }
        cx.notify();
    }

    fn allow_sender(&mut self, cx: &mut Context<Self>) {
        let text = self.allow_field.read(cx).text().trim().to_owned();
        let mut added = false;
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |prefs| added = prefs.allow_sender(&text));
        });
        self.note = (!added).then(|| "Type a sender that is not in the list yet.".into());
        if added {
            self.allow_field.update(cx, |field, cx| field.clear(cx));
        }
        cx.notify();
    }

    fn block_number(&mut self, cx: &mut Context<Self>) {
        let typed = self.block_field.read(cx).text().to_owned();
        let number = match blocklist::parse_input(&typed, &EmergencyList::default()) {
            Ok(number) => number,
            Err(error) => {
                self.note = Some(error.to_string().into());
                cx.notify();
                return;
            }
        };
        self.block_field.update(cx, |field, cx| field.clear(cx));
        self.note = None;
        self.change_block(number, true, cx);
    }

    /// Blocks or unblocks through Android; the backend re-pulls when it changes.
    fn change_block(&mut self, number: String, block: bool, cx: &mut Context<Self>) {
        let task = cx.background_executor().spawn(async move {
            if block {
                rovsti_android::blocklist::block(&number)
            } else {
                rovsti_android::blocklist::unblock(&number)
            }
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.note = match result {
                    Ok(true) => None,
                    Err(Error::Unsupported) => Some("Blocking needs an Android phone.".into()),
                    Ok(false) | Err(_) => Some("Android refused that change.".into()),
                };
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---- backup: export -----------------------------------------------------

    fn export(&mut self, cx: &mut Context<Self>) {
        let pass = self.pass.read(cx).text().to_owned();
        let confirm = self.confirm.read(cx).text().to_owned();
        if !self.backup.submit_export(&pass, &confirm) {
            cx.notify();
            return;
        }
        for field in [&self.pass, &self.confirm] {
            field.update(cx, |field, cx| field.clear(cx));
        }
        let (snapshot, spam, scheduled, dir, tz_offset_s) = {
            let backend = self.backend.read(cx);
            (
                backend.snapshot(cx),
                backend.spam_rules().clone(),
                backend.queue().items().to_vec(),
                backend.data_dir().map(Path::to_path_buf),
                backend.locale().tz_offset_s,
            )
        };
        let Some(dir) = dir else {
            self.backup
                .finished(Err("The app's storage is not ready yet.".to_owned()));
            cx.notify();
            return;
        };
        let work = cx.spawn(async move |this, cx| {
            let rows = snapshot.await;
            let sealed = match rows {
                Ok((sms, mms)) => {
                    cx.background_executor()
                        .spawn(async move {
                            let body = BackupBody {
                                created_ms: now_ms(),
                                app_version: env!("CARGO_PKG_VERSION").to_owned(),
                                sms,
                                mms: msg_backup::text_only_mms(&mms),
                                spam,
                                scheduled,
                            };
                            seal_to_cache(
                                &dir,
                                &body,
                                &pass,
                                &backup_ui::backup_file_name(body.created_ms, tz_offset_s),
                            )
                        })
                        .await
                }
                Err(error) => Err(error),
            };
            this.update(cx, |this, cx| this.sealed(sealed, cx)).ok();
        });
        work.detach();
        cx.notify();
    }

    /// The archive is on disk: hand it to the system "save as".
    fn sealed(&mut self, sealed: Result<PathBuf, String>, cx: &mut Context<Self>) {
        let path = match sealed {
            Ok(path) => path,
            Err(error) => {
                self.backup.finished(Err(error));
                cx.notify();
                return;
            }
        };
        let name = path
            .file_name()
            .map_or_else(String::new, |n| n.to_string_lossy().into_owned());
        let shown = path.clone();
        let task = {
            let path = path.to_string_lossy().into_owned();
            cx.background_executor()
                .spawn(async move { documents::export_file(&name, MIME, &path) })
        };
        cx.spawn(async move |this, cx| {
            let started = task.await;
            this.update(cx, |this, cx| {
                match started {
                    Ok(true) => {
                        this.export_path = Some(shown);
                        this.backup.picker_started(true, Busy::Saving);
                    }
                    // Desktop: no picker, the file stays in the data dir.
                    Err(Error::Unsupported) => {
                        this.backup
                            .finished(Ok(format!("Saved to {}", shown.display())));
                    }
                    Ok(false) | Err(_) => {
                        remove_later(&shown, cx);
                        this.backup.picker_started(false, Busy::Saving);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---- backup: import -----------------------------------------------------

    fn start_import(&mut self, cx: &mut Context<Self>) {
        if self.backup.is_busy() {
            return;
        }
        self.backup.picker_started(true, Busy::Picking);
        let task = cx
            .background_executor()
            .spawn(async { documents::import_file(IMPORT_MAX_BYTES) });
        cx.spawn(async move |this, cx| {
            let started = task.await;
            this.update(cx, |this, cx| {
                match started {
                    Ok(true) => {}
                    Err(Error::Unsupported) => this
                        .backup
                        .finished(Err("Importing needs an Android phone.".to_owned())),
                    Ok(false) | Err(_) => this.backup.picker_started(false, Busy::Picking),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn on_document(&mut self, result: &DocumentResult, cx: &mut Context<Self>) {
        match (result.op, &self.backup) {
            (DocumentOp::ExportMessages, BackupState::Busy(Busy::Saving)) => {
                if let Some(path) = self.export_path.take() {
                    remove_later(&path, cx);
                }
                self.backup.finished(if result.ok {
                    Ok("The backup is saved.".to_owned())
                } else {
                    Err("The backup was not saved.".to_owned())
                });
            }
            (DocumentOp::ImportMessages, BackupState::Busy(Busy::Picking)) => {
                self.picked = result.path.clone().filter(|_| result.ok);
                self.backup.picked(self.picked.is_some());
            }
            _ => return,
        }
        cx.notify();
    }

    /// Decrypts the picked archive and compares it with the phone.
    fn open_import(&mut self, cx: &mut Context<Self>) {
        let pass = self.open_pass.read(cx).text().to_owned();
        let Some(path) = self.picked.clone().filter(|_| !pass.is_empty()) else {
            return;
        };
        self.open_pass.update(cx, |field, cx| field.clear(cx));
        self.backup.picker_started(true, Busy::Opening);
        let snapshot = self.backend.read(cx).snapshot(cx);
        cx.spawn(async move |this, cx| {
            let existing = snapshot.await;
            let opened = match existing {
                Ok((existing, _)) => {
                    cx.background_executor()
                        .spawn(async move { open_archive(Path::new(&path), &pass, &existing) })
                        .await
                }
                Err(error) => Err(OpenFailure::Io(error)),
            };
            this.update(cx, |this, cx| {
                match opened {
                    Ok((body, plan)) => {
                        let scheduled = backup_ui::restorable_scheduled(&body.scheduled, now_ms());
                        this.backup
                            .opened(Ok(backup_ui::preview_of(&plan, scheduled.len())));
                        this.restore = Some(Restore { body, plan });
                    }
                    Err(OpenFailure::Backup(error)) => this.backup.opened(Err(error)),
                    Err(OpenFailure::Io(error)) => this.backup.finished(Err(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Writes the previewed messages into the provider, then the settings.
    fn confirm_restore(&mut self, cx: &mut Context<Self>) {
        let Some(restore) = self.restore.take() else {
            return;
        };
        self.backup.picker_started(true, Busy::Restoring);
        let json = serde_json::json!({ "sms": restore.plan.insert }).to_string();
        let task = cx
            .background_executor()
            .spawn(async move { sms::insert_restored(&json) });
        cx.spawn(async move |this, cx| {
            let answer = task.await;
            this.update(cx, |this, cx| {
                let outcome = match answer {
                    Ok(answer) => backup_ui::restore_outcome(&answer),
                    Err(Error::Unsupported) => Err("Restoring needs an Android phone.".to_owned()),
                    Err(error) => {
                        log::warn!("restoring messages failed: {error}");
                        Err("Restoring failed. Try again.".to_owned())
                    }
                };
                if outcome.is_ok() {
                    this.restore_settings(&restore.body, cx);
                }
                if let Some(path) = this.picked.take() {
                    remove_later(Path::new(&path), cx);
                }
                this.backup.finished(outcome);
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// The backup's spam rules fold into the current ones and its future
    /// scheduled SMS return to the queue.
    fn restore_settings(&mut self, body: &BackupBody, cx: &mut Context<Self>) {
        let now = now_ms();
        self.backend.update(cx, |backend, cx| {
            let backup = body.spam.clone();
            backend.update_prefs(cx, |prefs| {
                backup_ui::merge_spam(&mut prefs.spam, &backup);
            });
            for item in backup_ui::restorable_scheduled(&body.scheduled, now) {
                // A duplicate id or a full queue just means it is already there.
                let _ = backend.schedule_sms(item, cx);
            }
        });
    }

    fn cancel_backup(&mut self, cx: &mut Context<Self>) {
        self.backup.cancel();
        self.restore = None;
        if let Some(path) = self.picked.take() {
            remove_later(Path::new(&path), cx);
        }
        for field in [&self.pass, &self.confirm, &self.open_pass] {
            field.update(cx, |field, cx| field.clear(cx));
        }
        cx.notify();
    }

    // ---- rendering ----------------------------------------------------------

    fn switch_row(
        theme: Theme,
        id: &'static str,
        title: &'static str,
        subtitle: Option<&'static str>,
        on: bool,
    ) -> gpui::Stateful<gpui::Div> {
        list_row(
            theme,
            None,
            title,
            subtitle.map(Into::into),
            Some(if on { "On" } else { "Off" }.into()),
        )
        .id(id)
    }

    fn text_line(theme: Theme, text: impl Into<SharedString>, warn: bool) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .px(px(16.0))
            .py(px(8.0))
            .text_size(typography::FOOTNOTE.size)
            .text_color(if warn {
                colors.red
            } else {
                colors.secondary_label
            })
            .child(text.into())
            .into_any_element()
    }

    fn form_row(inner: impl IntoElement) -> AnyElement {
        div()
            .px(px(12.0))
            .py(px(8.0))
            .flex()
            .items_center()
            .gap(px(8.0))
            .child(inner)
            .into_any_element()
    }

    fn default_app(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let held = self.backend.read(cx).sms_role_held();
        let row = list_row(
            theme,
            None,
            "Default SMS app",
            Some(
                if held {
                    "Rovsti Messages sends and receives your SMS."
                } else {
                    "Not the default yet. Tap to make it the default."
                }
                .into(),
            ),
            Some(if held { "On" } else { "Off" }.into()),
        )
        .id("set-default")
        .on_click(cx.listener(|this, _, _, cx| {
            this.backend
                .update(cx, |backend, cx| backend.request_sms_role(cx));
        }))
        .into_any_element();
        section(theme, None, [row]).into_any_element()
    }

    fn notifications(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let (prefs, threads, sms_names) = {
            let backend = self.backend.read(cx);
            let names: HashMap<i64, String> = backend
                .threads()
                .iter()
                .filter_map(|t| {
                    let names: Vec<&str> = t
                        .addresses
                        .iter()
                        .filter_map(|a| backend.contacts().match_number(a))
                        .map(|c| c.display_name.as_str())
                        .collect();
                    (!names.is_empty()).then(|| (t.thread_id, names.join(", ")))
                })
                .collect();
            (backend.prefs().clone(), backend.threads().to_vec(), names)
        };
        let name_of = |thread: &ThreadId| match thread {
            ThreadId::Sms(id) => sms_names.get(id).cloned(),
            ThreadId::WhatsApp(jid) => self.wa_titles.get(jid).cloned(),
        };
        let mut rows = vec![
            Self::switch_row(
                theme,
                "set-notify",
                "Notifications",
                Some("Show new messages with a reply box."),
                prefs.notifications,
            )
            .on_click(cx.listener(|this, _, _, cx| {
                this.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| p.notifications = !p.notifications);
                });
            }))
            .into_any_element(),
            Self::switch_row(
                theme,
                "set-otp",
                "Copy code",
                Some("Offer a Copy button on passcode messages."),
                prefs.otp_copy,
            )
            .on_click(cx.listener(|this, _, _, cx| {
                this.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| p.otp_copy = !p.otp_copy);
                });
            }))
            .into_any_element(),
        ];
        for (index, tag) in prefs.muted.iter().enumerate() {
            let owned = tag.clone();
            rows.push(
                list_row(
                    theme,
                    None,
                    format!("Muted: {}", tag_label(tag, &threads, name_of)),
                    Some("Tap to unmute".into()),
                    None,
                )
                .id(("set-muted", index))
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.backend.update(cx, |backend, cx| {
                        backend.update_prefs(cx, |p| p.set_muted(&owned, false));
                    });
                    cx.emit(SettingsEvent::Undoable {
                        text: "Unmuted".into(),
                        undo: SettingsUndo::Mute(owned.clone()),
                    });
                }))
                .into_any_element(),
            );
        }
        section(theme, Some("Notifications".into()), rows).into_any_element()
    }

    fn spam(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let (rules, blocked) = {
            let backend = self.backend.read(cx);
            (
                backend.spam_rules().clone(),
                backend.blocked_rows().to_vec(),
            )
        };
        let mut rows: Vec<AnyElement> = vec![
            Self::switch_row(
                theme,
                "set-spam",
                "Spam filter",
                Some("Moves suspicious senders to Spam. Nothing is deleted."),
                rules.enabled,
            )
            .on_click(cx.listener(|this, _, _, cx| {
                this.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |p| p.spam.enabled = !p.spam.enabled);
                });
            }))
            .into_any_element(),
        ];
        for (index, rule) in rules.rules.iter().enumerate() {
            let rule_copy = rule.clone();
            rows.push(
                list_row(
                    theme,
                    None,
                    rule_text(rule),
                    Some("Tap to delete".into()),
                    None,
                )
                .id(("set-rule", index))
                .on_click(cx.listener(move |this, _, _, cx| {
                    let mut removed = false;
                    this.backend.update(cx, |backend, cx| {
                        backend.update_prefs(cx, |p| removed = p.spam.remove_rule(index));
                    });
                    if removed {
                        cx.emit(SettingsEvent::Undoable {
                            text: "Rule deleted".into(),
                            undo: SettingsUndo::Rule(index, rule_copy.clone()),
                        });
                    }
                }))
                .into_any_element(),
            );
        }
        let kind = self.rule_kind;
        let mut add = div().flex().items_center().gap(px(8.0)).child(
            glass_pill(theme, kind.label())
                .id("set-rule-kind")
                .on_click(cx.listener(|this, _, _, cx| {
                    this.rule_kind = this.rule_kind.next();
                    this.note = None;
                    cx.notify();
                })),
        );
        if kind.needs_text() {
            add = add.child(div().flex_1().min_w_0().child(field_box(
                theme,
                "set-rule-field",
                &self.rule_field,
                if kind == RuleKind::Sender {
                    "Name or number, * allowed"
                } else {
                    "Words to look for"
                },
                window,
                cx,
            )));
        }
        rows.push(Self::form_row(
            add.child(
                glass_pill(theme, "Add")
                    .id("set-rule-add")
                    .on_click(cx.listener(|this, _, _, cx| this.add_rule(cx))),
            ),
        ));

        rows.push(Self::form_row(
            div()
                .flex()
                .items_center()
                .gap(px(8.0))
                .w_full()
                .child(div().flex_1().min_w_0().child(field_box(
                    theme,
                    "set-allow-field",
                    &self.allow_field,
                    "Always allow a sender",
                    window,
                    cx,
                )))
                .child(
                    glass_pill(theme, "Allow")
                        .id("set-allow-add")
                        .on_click(cx.listener(|this, _, _, cx| this.allow_sender(cx))),
                ),
        ));
        for (index, sender) in rules.allow.iter().enumerate() {
            let sender_copy = sender.clone();
            rows.push(
                list_row(
                    theme,
                    None,
                    format!("Always allow: {sender}"),
                    Some("Tap to remove".into()),
                    None,
                )
                .id(("set-allow", index))
                .on_click(cx.listener(move |this, _, _, cx| {
                    let mut removed = false;
                    this.backend.update(cx, |backend, cx| {
                        backend.update_prefs(cx, |p| removed = p.remove_allowed(index));
                    });
                    if removed {
                        cx.emit(SettingsEvent::Undoable {
                            text: "Removed from always allowed".into(),
                            undo: SettingsUndo::Allow(sender_copy.clone()),
                        });
                    }
                }))
                .into_any_element(),
            );
        }
        for (index, row) in blocked.iter().enumerate() {
            let line = blocklist::numbers_line(row, None);
            let number = if row.number_raw.is_empty() {
                row.normalized.clone()
            } else {
                row.number_raw.clone()
            };
            rows.push(
                list_row(
                    theme,
                    None,
                    format!("Blocked: {}", line.title),
                    Some("Tap to unblock".into()),
                    None,
                )
                .id(("set-blocked", index))
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.change_block(number.clone(), false, cx);
                    cx.emit(SettingsEvent::Undoable {
                        text: "Unblocked".into(),
                        undo: SettingsUndo::Block(number.clone()),
                    });
                }))
                .into_any_element(),
            );
        }
        rows.push(Self::form_row(
            div()
                .flex()
                .items_center()
                .gap(px(8.0))
                .w_full()
                .child(div().flex_1().min_w_0().child(field_box(
                    theme,
                    "set-block-field",
                    &self.block_field,
                    "Block a number",
                    window,
                    cx,
                )))
                .child(
                    glass_pill(theme, "Block")
                        .id("set-block-add")
                        .on_click(cx.listener(|this, _, _, cx| this.block_number(cx))),
                ),
        ));
        if let Some(note) = self.note.clone() {
            rows.push(Self::text_line(theme, note, true));
        }
        section(theme, Some("Spam".into()), rows).into_any_element()
    }

    fn backup_section(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let mut rows: Vec<AnyElement> = Vec::new();
        let pill = |label: &'static str, id: &'static str| glass_pill(theme, label).id(id);
        match self.backup.clone() {
            BackupState::Idle => {
                rows.push(
                    list_row(
                        theme,
                        None,
                        "Export encrypted backup",
                        Some("Messages and spam rules, locked with a passphrase.".into()),
                        None,
                    )
                    .id("backup-export")
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.backup.begin_export();
                        cx.notify();
                    }))
                    .into_any_element(),
                );
                rows.push(
                    list_row(
                        theme,
                        None,
                        "Import backup",
                        Some("Restore from a Rovsti Messages backup file.".into()),
                        None,
                    )
                    .id("backup-import")
                    .on_click(cx.listener(|this, _, _, cx| this.start_import(cx)))
                    .into_any_element(),
                );
                rows.push(Self::text_line(
                    theme,
                    "WhatsApp chats stay on your gateway and are not part of this backup.",
                    false,
                ));
            }
            BackupState::Export { issue } => {
                rows.push(Self::form_row(div().flex_1().min_w_0().child(
                    secret_field_box(theme, "backup-pass", &self.pass, "Passphrase", window, cx),
                )));
                rows.push(Self::form_row(div().flex_1().min_w_0().child(
                    secret_field_box(
                        theme,
                        "backup-confirm",
                        &self.confirm,
                        "Type it again",
                        window,
                        cx,
                    ),
                )));
                if let Some(label) = backup_ui::strength_label(self.pass.read(cx).text()) {
                    rows.push(Self::text_line(theme, format!("Strength: {label}"), false));
                }
                if let Some(issue) = issue {
                    rows.push(Self::text_line(
                        theme,
                        issue.message(),
                        issue != Issue::Weak,
                    ));
                }
                rows.push(Self::form_row(
                    div()
                        .flex()
                        .gap(px(8.0))
                        .child(
                            pill("Export", "backup-go")
                                .on_click(cx.listener(|this, _, _, cx| this.export(cx))),
                        )
                        .child(
                            pill("Cancel", "backup-cancel")
                                .on_click(cx.listener(|this, _, _, cx| this.cancel_backup(cx))),
                        ),
                ));
            }
            BackupState::Busy(busy) => rows.push(Self::text_line(theme, busy.label(), false)),
            BackupState::ImportAsk { wrong } => {
                rows.push(Self::text_line(
                    theme,
                    "Type the passphrase this backup was made with.",
                    false,
                ));
                rows.push(Self::form_row(div().flex_1().min_w_0().child(
                    secret_field_box(
                        theme,
                        "backup-open-pass",
                        &self.open_pass,
                        "Passphrase",
                        window,
                        cx,
                    ),
                )));
                if wrong {
                    rows.push(Self::text_line(
                        theme,
                        backup_ui::describe(BackupError::WrongPassphrase),
                        true,
                    ));
                }
                rows.push(Self::form_row(
                    div()
                        .flex()
                        .gap(px(8.0))
                        .child(
                            pill("Open", "backup-open")
                                .on_click(cx.listener(|this, _, _, cx| this.open_import(cx))),
                        )
                        .child(
                            pill("Cancel", "backup-cancel")
                                .on_click(cx.listener(|this, _, _, cx| this.cancel_backup(cx))),
                        ),
                ));
            }
            BackupState::ImportPreview(preview) => {
                let mut line = format!(
                    "{} message{} will be added",
                    preview.insert,
                    if preview.insert == 1 { "" } else { "s" }
                );
                if preview.skipped > 0 {
                    line.push_str(&format!(", {} already on this phone", preview.skipped));
                }
                if preview.scheduled > 0 {
                    line.push_str(&format!(", {} scheduled to send", preview.scheduled));
                }
                rows.push(Self::text_line(theme, format!("{line}."), false));
                rows.push(Self::form_row(
                    div()
                        .flex()
                        .gap(px(8.0))
                        .child(
                            pill("Restore", "backup-restore")
                                .on_click(cx.listener(|this, _, _, cx| this.confirm_restore(cx))),
                        )
                        .child(
                            pill("Cancel", "backup-cancel")
                                .on_click(cx.listener(|this, _, _, cx| this.cancel_backup(cx))),
                        ),
                ));
            }
            BackupState::Done(text) | BackupState::Failed(text) => {
                let failed = matches!(self.backup, BackupState::Failed(_));
                rows.push(
                    div()
                        .px(px(16.0))
                        .py(px(10.0))
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(if failed { colors.red } else { colors.label })
                        .child(text)
                        .into_any_element(),
                );
                rows.push(Self::form_row(
                    pill("OK", "backup-ok")
                        .on_click(cx.listener(|this, _, _, cx| this.cancel_backup(cx))),
                ));
            }
        }
        section(theme, Some("Backup".into()), rows).into_any_element()
    }
}

impl Render for SettingsView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let whatsapp = section(
            theme,
            Some("WhatsApp".into()),
            [list_row(
                theme,
                None,
                "WhatsApp",
                Some("Link an account or connect with a key in the WhatsApp tab.".into()),
                None,
            )
            .id("set-whatsapp")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(SettingsEvent::OpenWhatsApp)))
            .into_any_element()],
        )
        .into_any_element();
        div()
            .flex()
            .flex_col()
            .gap(px(16.0))
            .pb(px(24.0))
            .child(self.default_app(theme, cx))
            .child(self.notifications(theme, cx))
            .child(self.spam(theme, window, cx))
            .child(self.backup_section(theme, window, cx))
            .child(whatsapp)
    }
}

// ---- file work, off the UI thread ---------------------------------------------

/// Seals `body` and writes it as `dir/cache/<name>`; returns the path.
fn seal_to_cache(
    dir: &Path,
    body: &BackupBody,
    passphrase: &str,
    name: &str,
) -> Result<PathBuf, String> {
    let mut salt = [0u8; msg_backup::SALT_LEN];
    let mut nonce = [0u8; msg_backup::NONCE_LEN];
    getrandom::fill(&mut salt).map_err(|e| format!("No secure random numbers: {e}"))?;
    getrandom::fill(&mut nonce).map_err(|e| format!("No secure random numbers: {e}"))?;
    let bytes = msg_backup::seal(body, passphrase, salt, nonce, msg_backup::ITERATIONS);
    let cache = dir.join(CACHE_DIR);
    // A file left by an export that never finished.
    let _ = fs::remove_dir_all(&cache);
    fs::create_dir_all(&cache).map_err(|e| format!("Couldn't write the backup: {e}"))?;
    let path = cache.join(name);
    fs::write(&path, bytes).map_err(|e| format!("Couldn't write the backup: {e}"))?;
    Ok(path)
}

/// Reads and decrypts the archive, then plans the restore against the phone.
fn open_archive(
    path: &Path,
    passphrase: &str,
    existing: &[rovsti_core::sms::SmsRow],
) -> Result<(BackupBody, RestorePlan), OpenFailure> {
    let meta =
        fs::metadata(path).map_err(|e| OpenFailure::Io(format!("Couldn't read the file: {e}")))?;
    if meta.len() > IMPORT_MAX_BYTES {
        return Err(OpenFailure::Io(
            "That file is too large for a backup.".to_owned(),
        ));
    }
    let bytes =
        fs::read(path).map_err(|e| OpenFailure::Io(format!("Couldn't read the file: {e}")))?;
    let body = msg_backup::open(&bytes, passphrase).map_err(OpenFailure::Backup)?;
    let plan = msg_backup::restore_plan(&body, existing);
    Ok((body, plan))
}

/// Removes a temporary file without making the UI wait for the disk.
fn remove_later<T: 'static>(path: &Path, cx: &mut Context<T>) {
    let path = path.to_path_buf();
    cx.background_executor()
        .spawn(async move {
            let _ = fs::remove_file(path);
        })
        .detach();
}

#[cfg(test)]
mod tests {
    use rovsti_core::sms::SmsRow;

    use super::*;

    #[test]
    fn rule_kinds_cycle_and_build() {
        let mut kind = RuleKind::Sender;
        let mut seen = vec![kind];
        for _ in 0..3 {
            kind = kind.next();
            seen.push(kind);
        }
        assert_eq!(kind.next(), RuleKind::Sender);
        assert_eq!(seen.len(), 4);
        assert!(RuleKind::Sender.needs_text() && !RuleKind::UnknownLinks.needs_text());
        assert_eq!(
            RuleKind::Keyword.build("win"),
            SpamRule::Keyword { text: "win".into() }
        );
        assert_eq!(
            RuleKind::ShortCodes.build(""),
            SpamRule::ShortCodeOnly(true)
        );
    }

    #[test]
    fn rules_read_naturally() {
        assert_eq!(
            rule_text(&SpamRule::Sender {
                pattern: "PROMO*".into()
            }),
            "Sender: PROMO*"
        );
        assert_eq!(
            rule_text(&SpamRule::UnknownWithLink),
            "Links from unknown senders"
        );
    }

    #[test]
    fn muted_tags_get_names() {
        let none = |_: &ThreadId| None;
        let threads = [SmsThreadRow {
            thread_id: 7,
            addresses: vec!["+905321234567".into()],
            ..SmsThreadRow::default()
        }];
        assert_eq!(tag_label("sms:7", &threads, none), "+905321234567");
        assert_eq!(tag_label("sms:8", &threads, none), "SMS conversation 8");
        assert_eq!(
            tag_label("wa:9053@s.whatsapp.net", &threads, none),
            "WhatsApp 9053"
        );
        assert_eq!(tag_label("???", &threads, none), "???");
        let named = |t: &ThreadId| match t {
            ThreadId::Sms(7) => Some("Ayşe".to_owned()),
            ThreadId::WhatsApp(_) => Some("  ".to_owned()),
            _ => None,
        };
        assert_eq!(tag_label("sms:7", &threads, named), "Ayşe");
        assert_eq!(tag_label("sms:8", &threads, named), "SMS conversation 8");
        assert_eq!(
            tag_label("wa:9053@s.whatsapp.net", &threads, named),
            "WhatsApp 9053"
        );
    }

    #[test]
    fn export_then_open_round_trips_through_a_file() {
        let dir =
            std::env::temp_dir().join(format!("rovsti-messages-settings-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let body = BackupBody {
            created_ms: 5,
            sms: vec![SmsRow {
                id: 1,
                address: "+1".into(),
                body: "hi".into(),
                date_ms: 9,
                ..SmsRow::default()
            }],
            ..BackupBody::default()
        };
        let path = seal_to_cache(&dir, &body, "correct horse battery", "m.rmb").expect("sealed");
        assert!(path.starts_with(dir.join(CACHE_DIR)));
        let (opened, plan) = open_archive(&path, "correct horse battery", &[])
            .ok()
            .expect("opened");
        assert_eq!(opened, body);
        assert_eq!(plan.insert.len(), 1);
        // A phone that already has the message skips it.
        let (_, plan) = open_archive(&path, "correct horse battery", &body.sms)
            .ok()
            .expect("opened");
        assert_eq!((plan.insert.len(), plan.skipped_duplicates), (0, 1));
        assert!(matches!(
            open_archive(&path, "wrong", &[]),
            Err(OpenFailure::Backup(BackupError::WrongPassphrase))
        ));
        assert!(matches!(
            open_archive(&dir.join("missing"), "x", &[]),
            Err(OpenFailure::Io(_))
        ));
        let _ = fs::remove_dir_all(&dir);
    }
}
