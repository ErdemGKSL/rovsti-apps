//! Epic 16 on the Backend: SIM accounts, the settings mirror, what only Kotlin
//! can read, and the backup documents. Views read `SimInfo` and the facts as
//! cheap clones and never see a `Result`.

use std::sync::Arc;

use gpui::Context;
use rovsti_android::{documents, settings as android_settings, telecom, window};
use rovsti_core::{
    dial,
    settings::{BACKUP_KIND, Settings},
    sim::SimInfo,
    wire::{DocumentResult, EmergencyList, PhoneAccountsResponse, SettingsSync},
};
use rovsti_ui::storage;
use serde::Deserialize;

use super::{APP_VERSION, Backend, Outcome, Placement, now_ms};

/// What only Kotlin can read (`systemFacts`). Until the first answer the
/// phone is assumed able, so no row flashes "Not supported".
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SystemFacts {
    pub flip_supported: bool,
    pub system_keypad_tones: bool,
    pub hearing_aid_compatible: Option<bool>,
    /// `EMERGENCY_ASSISTANCE` resolves on this phone; stays false until S10 (20.4) says so.
    pub emergency_info: bool,
}

impl Default for SystemFacts {
    fn default() -> Self {
        Self {
            flip_supported: true,
            system_keypad_tones: true,
            hearing_aid_compatible: None,
            emergency_info: false,
        }
    }
}

#[derive(Deserialize)]
struct FactsWire {
    #[serde(default = "yes")]
    flip_supported: bool,
    #[serde(default = "yes")]
    system_keypad_tones: bool,
}

const fn yes() -> bool {
    true
}

/// How long after a change the writer is asked whether it worked.
const SAVE_CHECK_MS: u64 = 700;

impl Backend {
    // ---- SIM accounts ---------------------------------------------------

    /// The SIMs, as one cheap clone. `visible()` is false below two SIMs.
    pub fn sims(&self) -> SimInfo {
        self.sims.clone()
    }

    pub(super) fn refresh_sims(&mut self, cx: &mut Context<Self>) {
        self.sims_pull += 1;
        let pull = self.sims_pull;
        let preview = self.preview_sims;
        cx.spawn(async move |this, cx| {
            let info = cx
                .background_executor()
                .spawn(async move { load_sims(preview) })
                .await;
            this.update(cx, |this, cx| {
                if this.sims_pull == pull && this.sims != info {
                    this.sims = info;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Desktop preview: 1 shows a single-SIM phone, 2 a dual-SIM one (default
    /// "ask every time"), 3 a dual-SIM one with SIM 2 as the default.
    #[cfg(not(target_os = "android"))]
    pub fn preview_set_sims(&mut self, mode: u8, cx: &mut Context<Self>) {
        self.preview_sims = mode;
        self.refresh_sims(cx);
    }

    #[cfg(not(target_os = "android"))]
    pub fn preview_sims_mode(&self) -> u8 {
        self.preview_sims
    }

    // ---- placing on a SIM -------------------------------------------------

    /// The one call path with a chosen SIM. `account_key` is ignored for an
    /// emergency number (it never gets a key) and with fewer than two SIMs.
    pub fn place_call_on(
        &mut self,
        raw: &str,
        account_key: Option<&str>,
        cx: &mut Context<Self>,
    ) -> Placement {
        let key = account_key
            .filter(|_| self.sims.visible())
            .filter(|_| !dial::is_emergency(raw, &EmergencyList::default()))
            .map(str::to_owned);
        self.pending_account = key.clone();
        let placed = self.place_call(raw, cx);
        if placed != Placement::Requesting {
            self.pending_account = None;
        }
        placed
    }

    pub(super) fn place_with_account(&mut self, raw: &str, cx: &mut Context<Self>) -> Placement {
        let key = self.pending_account.take();
        let result = match &key {
            Some(key) => telecom::place_call_on(raw, key),
            None => telecom::place_call(raw),
        };
        match result {
            Ok(true) => Placement::Placed,
            Ok(false) => Placement::Failed,
            Err(rovsti_android::Error::Unsupported) => {
                #[cfg(not(target_os = "android"))]
                {
                    self.preview_account = key;
                    self.preview_start(raw, cx);
                }
                let _ = cx;
                Placement::Placed
            }
            Err(error) => {
                log::warn!("placing a call failed: {error}");
                Placement::Failed
            }
        }
    }

    /// Answers a system account picker raised by another app (the registry
    /// safety net, story 16.3).
    pub fn select_account(&self, call_id: i32, key: &str) -> Outcome {
        Outcome::from(telecom::select_account(call_id, key), "select account")
    }

    // ---- the Kotlin side ----------------------------------------------------

    /// Tells Kotlin whether Rust's session is live and the activity resumed.
    /// `true` also drains the one-shot mailbox.
    pub fn set_ui_ready(&mut self, ready: bool) {
        if self.ui_ready == ready {
            return;
        }
        self.ui_ready = ready;
        if let Err(error) = window::ui_attached(ready)
            && !matches!(error, rovsti_android::Error::Unsupported)
        {
            log::debug!("uiAttached failed: {error}");
        }
    }

    pub fn facts(&self) -> SystemFacts {
        self.facts
    }

    /// Pulls what only Kotlin can read, off the UI thread.
    pub(super) fn refresh_facts(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let (json, hearing) = cx
                .background_executor()
                .spawn(async {
                    (
                        android_settings::system_facts().ok(),
                        telecom::hearing_aid_compatible().ok(),
                    )
                })
                .await;
            this.update(cx, |this, cx| {
                let mut facts = this.facts;
                if let Some(wire) = json.and_then(|j| serde_json::from_str::<FactsWire>(&j).ok()) {
                    facts.flip_supported = wire.flip_supported;
                    facts.system_keypad_tones = wire.system_keypad_tones;
                }
                if hearing.is_some() {
                    facts.hearing_aid_compatible = hearing;
                }
                if facts != this.facts {
                    this.facts = facts;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Pushes the `SettingsSync` struct to Kotlin when it changed since the
    /// last push (attach, and after every change).
    pub(super) fn push_settings(&mut self, cx: &mut Context<Self>) {
        let sync: SettingsSync = self.prefs.sync();
        let Ok(json) = serde_json::to_string(&sync) else {
            return;
        };
        if json == self.last_sync {
            return;
        }
        self.last_sync.clone_from(&json);
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = android_settings::sync(&json)
                    && !matches!(error, rovsti_android::Error::Unsupported)
                {
                    log::debug!("settingsSync failed: {error}");
                }
            })
            .detach();
    }

    /// Whether dial-pad tones play now: the setting, else the system's.
    pub fn keypad_tones_on(&self) -> bool {
        rovsti_core::settings::effective_keypad_tones(
            self.settings.keypad_tones,
            self.facts.system_keypad_tones,
        )
    }

    // ---- a setting changes ------------------------------------------------

    /// Applies a settings change at once, saves it, mirrors it to Kotlin and
    /// publishes the new `Arc<Settings>`. If the save fails the value goes
    /// back and [`Backend::setting_failed_gen`] moves, so the UI can say
    /// "Couldn't save setting".
    pub fn change_setting(&mut self, cx: &mut Context<Self>, f: impl FnOnce(&mut Settings)) {
        let before = self.prefs.settings.clone();
        self.update_prefs(cx, |prefs| f(&mut prefs.settings));
        if self.prefs.settings == before {
            return;
        }
        self.change_gen += 1;
        let gen_now = self.change_gen;
        let good = before;
        cx.spawn(async move |this, cx| {
            cx.background_executor()
                .timer(std::time::Duration::from_millis(SAVE_CHECK_MS))
                .await;
            this.update(cx, |this, cx| {
                if this.change_gen != gen_now {
                    return;
                }
                if this.prefs_save_failed() {
                    this.update_prefs(cx, |prefs| prefs.settings = good);
                    this.setting_failed_gen += 1;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// Moves when a setting could not be saved and was put back.
    pub fn setting_failed_gen(&self) -> u64 {
        self.setting_failed_gen
    }

    /// Called whenever the settings Arc is replaced: the haptics filter and
    /// the Kotlin mirror follow.
    pub(super) fn settings_published(&mut self, cx: &mut Context<Self>) {
        rovsti_ui::haptics::set_level(self.settings.haptics);
        self.push_settings(cx);
    }

    // ---- documents ----------------------------------------------------------

    /// Starts "save as" for the backup. The answer is a `document_result`.
    pub fn export_backup(&mut self, cx: &mut Context<Self>) -> Outcome {
        let created = now_ms();
        let name = rovsti_core::settings::backup_file_name(created);
        let json = storage::backup_json(&self.prefs, created, APP_VERSION);
        let outcome = Outcome::from(documents::export(&name, &json), "export document");
        #[cfg(not(target_os = "android"))]
        if outcome == Outcome::NotAvailable {
            self.preview_document(
                rovsti_core::wire::DocumentOp::ExportBackup,
                Some((&name, &json)),
                cx,
            );
            return Outcome::Done;
        }
        let _ = cx;
        outcome
    }

    /// Starts the picker for a backup to import.
    pub fn import_backup(&mut self, cx: &mut Context<Self>) -> Outcome {
        let outcome = Outcome::from(documents::import(), "import document");
        #[cfg(not(target_os = "android"))]
        if outcome == Outcome::NotAvailable {
            self.preview_document(rovsti_core::wire::DocumentOp::ImportBackup, None, cx);
            return Outcome::Done;
        }
        let _ = cx;
        outcome
    }

    /// Desktop: the picker is the temp directory. Export writes the file and
    /// import reads the last one back, so both sheets can be driven.
    #[cfg(not(target_os = "android"))]
    fn preview_document(
        &mut self,
        op: rovsti_core::wire::DocumentOp,
        export: Option<(&str, &str)>,
        cx: &mut Context<Self>,
    ) {
        let path = std::env::temp_dir().join("rovsti-phone-backup-preview.json");
        let result = match export {
            Some((name, json)) => DocumentResult {
                op,
                ok: std::fs::write(&path, json).is_ok(),
                name: Some(name.to_owned()),
                ..DocumentResult::default()
            },
            None => {
                let text = std::env::var("ROVSTI_PREVIEW_IMPORT")
                    .ok()
                    .and_then(|p| std::fs::read_to_string(p).ok())
                    .or_else(|| std::fs::read_to_string(&path).ok());
                DocumentResult {
                    op,
                    ok: text.is_some(),
                    name: Some("rovsti-phone-backup-preview.json".into()),
                    text,
                    ..DocumentResult::default()
                }
            }
        };
        self.document = Some(result);
        self.document_gen += 1;
        cx.notify();
    }

    /// The newest unconsumed `document_result`.
    pub fn take_document(&mut self) -> Option<DocumentResult> {
        self.document.take()
    }

    pub fn document_gen(&self) -> u64 {
        self.document_gen
    }

    pub(super) fn on_document_result(&mut self, json: &str, cx: &mut Context<Self>) {
        match serde_json::from_str::<DocumentResult>(json) {
            Ok(result) => {
                self.document = Some(result);
                self.document_gen += 1;
                cx.notify();
            }
            Err(error) => log::warn!("bad document_result: {error}"),
        }
    }

    /// Applies a validated import (replace semantics) as one change.
    pub fn apply_import(
        &mut self,
        preview: rovsti_core::settings::ImportPreview,
        cx: &mut Context<Self>,
    ) {
        self.update_prefs(cx, |prefs| preview.apply_to(prefs));
        self.settings_changed_from_prefs(cx);
        self.change_gen += 1;
    }

    /// `update_prefs` already publishes the new Arc; this re-mirrors after a
    /// wholesale replacement such as an import.
    fn settings_changed_from_prefs(&mut self, cx: &mut Context<Self>) {
        self.settings = Arc::new(self.prefs.settings.clone());
        self.settings_published(cx);
    }

    /// The backup kind string (for tests of the preview path).
    pub const fn backup_kind() -> &'static str {
        BACKUP_KIND
    }
}

fn load_sims(preview: u8) -> SimInfo {
    match telecom::phone_accounts() {
        Ok(json) => serde_json::from_str::<PhoneAccountsResponse>(&json)
            .ok()
            .and_then(|e| e.into_result().ok())
            .map_or_else(SimInfo::default, |p| SimInfo::from_payload(&p)),
        Err(rovsti_android::Error::Unsupported) => preview_sims(preview),
        Err(error) => {
            log::debug!("phone accounts: {error}");
            SimInfo::default()
        }
    }
}

/// The fake phone's SIMs for the desktop preview.
fn preview_sims(mode: u8) -> SimInfo {
    use rovsti_core::wire::{PhoneAccountInfo, PhoneAccounts};
    if mode < 2 {
        return SimInfo::default();
    }
    let account = |key: &str, label: &str, slot: u8, color: u32| PhoneAccountInfo {
        key: key.into(),
        label: label.into(),
        slot_index: Some(slot),
        color_argb: color as i32,
        sim: true,
        enabled: true,
    };
    SimInfo::from_payload(&PhoneAccounts {
        accounts: vec![
            account("tel/pstn#1", "Turkcell", 0, 0xFF1E_88E5),
            account("tel/pstn#2", "Vodafone", 1, 0xFFFB_8C00),
        ],
        default_key: (mode == 3).then(|| "tel/pstn#2".to_owned()),
        ask: mode == 2,
    })
}
