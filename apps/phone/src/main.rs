//! Rovsti Phone: the dialer.
//!
//! Desktop `cargo run -p rovsti_phone` renders the same UI for design work
//! (placing a call walks a fake one through the In-Call screen); real calls,
//! contacts and the call log need the Android host (see docs/android.md) and
//! the default-dialer role.

use futures::{StreamExt, channel::mpsc};
use gpui::{
    AnyElement, App, Bounds, Context, Entity, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Render, SharedString, Styled, Subscription, TouchPhase, Window, WindowBounds,
    WindowOptions, div, prelude::*, px, size,
};
mod actions_ui;
mod addcall;
mod aura;
mod backend;
mod backup_ui;
mod block_ui;
mod blocked_ui;
mod contacts;
#[cfg(not(target_os = "android"))]
mod devtools;
mod diagnostics;
mod field;
mod flight;
mod fx;
mod history_ui;
mod incall;
mod incoming;
mod keypad;
mod notice;
#[cfg(test)]
mod perf_harness;
#[cfg(any(feature = "perf", test))]
mod perf_run;
mod photos;
mod recents;
mod replies_ui;
mod route;
mod sample;
mod settings_ui;
mod setup_ui;
mod signature;
mod sim_ui;
mod speed_dial_ui;
mod wa_call;
mod wa_peer;
mod waiting_card;
mod zone;
mod zone_ui;

use backend::{Backend, XiaomiOs};
use contacts::{ContactSheet, ContactsView, FavoritesView};
use incall::InCall;
use incoming::Incoming;
use notice::Notice;
use photos::Photos;
use recents::RecentsView;
use rovsti_core::{
    dial,
    setup::{OpenedThisSession, SetupChecklist},
};
use rovsti_ui::{
    Icon, IconAssets,
    components::{backdrop, large_title},
    current_theme,
    moments::Fade,
    motion::{Entrance, Motion, Presses},
    motion_tokens,
    notice_pill::{NoticePill, PillEvent},
    sheet::Sheet,
    spring::Spring,
    tab_bar::{Tab, TabBar, TabBarView},
    theme,
    tokens::{Theme, metrics},
};

const TABS: [&str; 4] = ["Favorites", "Recents", "Contacts", "Keypad"];
const TAB_ICONS: [Icon; 4] = [
    Icon::LucideStar,
    Icon::LucideClock,
    Icon::LucideUsers,
    Icon::LucideGrid3x3,
];
const FAVORITES_TAB: usize = 0;
const RECENTS_TAB: usize = 1;
const CONTACTS_TAB: usize = 2;
const KEYPAD_TAB: usize = 3;

/// What the one bottom sheet shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum SheetKind {
    Setup,
    /// The info sheet of a Recents row (`RecentsView::number`).
    Number,
    /// A person's numbers (or the number chooser): `PhoneApp::contact_sheet`.
    Contact,
    /// The hidden Diagnostics page (story 14.0f).
    Diagnostics,
    /// The Add call sheet over the In-Call screen (story 15.5).
    AddCall,
    /// What an import would replace (story 16.6).
    ImportPreview,
    /// "Call with": the SIM chooser (story 16.4), drawn by `sim_overlay`.
    SimChooser,
    /// "Decline with message" over the ringing screen or a call (story 18.2), drawn by `reply_overlay`.
    Reply,
    /// "Reset quick replies?" (story 18.3).
    ResetReplies,
    /// "Delete calls with {who}?" / "Delete all call history?" (`history_ui`).
    ConfirmDelete,
    /// Every T9 match behind the keypad strip's "N more" (`keypad::t9_results_sheet`).
    T9Results,
}

/// The line under the number field.
enum Status {
    Text(SharedString),
    /// `CALL_PHONE` was refused: explain and offer "Grant access".
    NeedsPhone,
    /// The SIM has no voicemail number: explain and offer "Open call settings".
    NoVoicemail,
}

struct PhoneApp {
    tabs: TabBar,
    /// The Tab Bar's own cached view (it re-renders only when the bar changes).
    tab_view: Entity<TabBarView>,
    dialed: String,
    /// Speed-dial map the badges below were built from (rebuilt only on change).
    speed_map: std::collections::BTreeMap<u8, rovsti_core::settings::SpeedDialEntry>,
    /// Keypad badge per digit (index `d - 1`): shown name and avatar hue.
    speed_badges: [Option<(SharedString, Option<usize>)>; 9],
    /// Keypad screen-reader label per digit when it has a speed dial.
    speed_aria: [Option<SharedString>; 9],
    /// The keypad's "Allow Contacts" hint was used this session (not persisted).
    t9_hint_dismissed: bool,
    /// `dialed` split into the settled digits and the newest one, as text
    /// ready to draw; rebuilt only when `dialed` or the elision changes.
    shown_digits: (String, Option<usize>, SharedString, SharedString),
    kp_layout: rovsti_core::keypad_layout::KeypadLayout,
    status: Option<Status>,
    /// Whose number is typed (story 10.1); shown in the status line.
    dial_match: keypad::DialMatch,
    t9: keypad::T9Match,
    backend: Entity<Backend>,
    xiaomi: Option<XiaomiOs>,
    _subscriptions: Vec<Subscription>,

    motion: Motion,
    presses: Presses,
    /// Page entrance on tab switches (S5).
    page: Entrance,
    /// Direction of the last tab change: +1 came from the right, -1 from the
    /// left, 0 none (the page then lifts instead).
    tab_dir: f32,
    /// Status line fade-in.
    status_in: Entrance,
    /// Scale of the newest digit: kicked above 1, springs back.
    digit_pop: Spring,
    /// Horizontal offset of the number; kicked sideways on invalid input.
    shake: Spring,
    /// 0 = Delete hidden, 1 = shown.
    delete_shown: Spring,
    /// Where the moments find the real elements (keys, field, buttons, avatars).
    marks: fx::Marks,
    /// S1: ripple rings, digit copies dropping into the field, the Call bloom.
    keyfx: Entity<fx::KeyFx>,
    /// S2: the digit being deleted, shrinking out of the field.
    ghost: keypad::Ghost,
    /// The system asked for reduced motion (read each frame).
    reduced: bool,
    /// S3, S4, S8: buttons and avatars flying between screens.
    flights: flight::Flights,
    /// Reduced motion: the 120 ms cross-fades that replace S3/S4/S8 and S5.
    page_fade: Fade,
    call_fade: Fade,
    /// S2: the number field's font size; steps cross-fade on `morph`.
    font: Spring,
    /// Bumped to cancel a pending long-press timer.
    hold_gen: u64,

    /// The In-Call screen, drawn over the tabs while a call exists.
    incall: InCall,
    /// The Incoming Call screen, drawn over everything while a call rings.
    incoming: Incoming,

    /// S14: the setup shield's check pop and the wash brightening.
    celebrate: signature::Celebrate,
    /// The checklist last seen once Android's answers were in.
    setup_seen: Option<SetupChecklist>,
    /// Items that were done earlier in this run and are open again.
    setup_reopened: Vec<rovsti_core::setup::SetupItem>,
    /// The "Something changed" sheet title for `setup_reopened`, built once.
    setup_changed_title: Option<SharedString>,
    /// S15: the old theme's veil fading out over a theme change.
    melt: signature::Melt,

    /// The Setup sheet (an overlay: opening it never moves the page).
    sheet: Sheet,
    /// The Add call sheet's state (story 15.5).
    add: addcall::AddCall,
    /// The audio route list and its moments (S17, S18).
    route: route::RouteUi,
    /// Xiaomi pages opened, and pages that fell back to App info, this session.
    opened: OpenedThisSession,
    fell_back: OpenedThisSession,
    /// The role dialog did not show; offer Settings, Default apps too.
    role_declined: bool,
    setup_note: Option<SharedString>,
    sheet_kind: SheetKind,
    back_enabled: bool,
    /// The hidden Diagnostics page (story 14.0f).
    diag: diagnostics::Diagnostics,
    /// The Settings page behind the gear (Epic 16).
    settings: settings_ui::SettingsUi,
    backup: backup_ui::BackupUi,
    setting_failed_seen: u64,
    /// The SIM chooser and the chip flight (stories 16.4, 16.5).
    sim: sim_ui::SimUi,
    /// The Notice pill (S28): confirmations and reasons, never in the layout.
    pill: NoticePill,
    /// What the pill's trailing button does, with the token of the pill it belongs to.
    pill_action: Option<(u64, block_ui::PillAction)>,
    /// The Speed dial page and a digit waiting for a contact.
    speed_dial: speed_dial_ui::SpeedDialUi,
    /// The Recents row menu and S27 (Epic 17).
    row_menu: block_ui::RowMenu,
    /// The Blocked numbers page behind Settings (stories 17.5, 17.6).
    blocked: blocked_ui::BlockedUi,
    /// The reply sheet and S31 (stories 18.2, 18.7), and the Quick replies editor (18.3).
    reply: replies_ui::ReplyUi,
    replies_ed: replies_ui::ReplyEditor,
    /// The WhatsApp settings page (the shared screen), shown by `Sub::WhatsApp`.
    whatsapp: Entity<rovsti_wa_ui::WhatsAppSettings>,
    /// WhatsApp calls: the incoming card and the live call (AD-WA5).
    wa_call: Entity<wa_call::WaCallUi>,
    /// WhatsApp wake-ups already forwarded to `whatsapp`.
    wa_pushes_seen: u64,
    /// WhatsApp profile pictures and names of the people Phone shows.
    wa_avatars: Entity<rovsti_wa_ui::WaAvatars>,
    /// A WhatsApp picture is fading in (read once per frame for `tick`).
    wa_fading: bool,

    /// The Recents tab: row view-models, scroll and the seen-timer.
    recents: RecentsView,
    /// The Contacts tab (search field, rows) and the Favorites grid.
    contacts: ContactsView,
    favorites: FavoritesView,
    /// The open Contact sheet or number chooser.
    contact_sheet: Option<ContactSheet>,
    /// What the confirm-delete sheet will remove.
    confirm_delete: Option<history_ui::DeleteScope>,
    /// 0 = Tab Bar in place, 1 = slid away under the keyboard.
    tabs_away: Spring,
    /// The Return-to-call pill: 0 = away, 1 = shown; its text and 1 s timer.
    pill_in: Spring,
    pill_text: SharedString,
    pill_task: Option<gpui::Task<()>>,
    /// Outcome of the last call placed from a list (story 7.6 status line).
    call_note: Option<Notice>,
    /// The notice the slot under the title is showing or fading out.
    notice_shown: Option<Notice>,
    /// 0 = slot closed, 1 = open; the slot grows on a spring.
    notice_h: Spring,
    /// The number to retry after "Grant" on the Phone-access notice.
    last_dialed: SharedString,
    window_active: bool,
    /// Decoded contact photos (story 10.5).
    photos: Photos,
    /// The first tab was chosen (stored tab, `tel:` intent, or the fallback
    /// after 400 ms): until then only the backdrop is drawn, so the app never
    /// shows the Keypad for a moment and then jumps to the stored tab.
    restored: bool,
    _restore_timeout: Option<gpui::Task<()>>,
    /// Frame timings per scenario (`perf` builds only).
    #[cfg(any(feature = "perf", test))]
    recorder: rovsti_ui::perf::Recorder,
}

/// How long the first frame waits for the stored preferences.
const RESTORE_WAIT: std::time::Duration = std::time::Duration::from_millis(400);

impl PhoneApp {
    fn new(
        backend: Entity<Backend>,
        dial_urls: mpsc::UnboundedReceiver<String>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let mut tabs = TabBar::new(
            TABS.into_iter()
                .zip(TAB_ICONS)
                .map(|(label, icon)| Tab::new(icon, label)),
        );
        tabs.jump_to(KEYPAD_TAB);
        #[cfg_attr(target_os = "android", allow(unused_mut))]
        let mut restored = false;
        // Desktop design preview: start on another tab (ROVSTI_PREVIEW_TAB=1).
        #[cfg(not(target_os = "android"))]
        if let Some(tab) = std::env::var("ROVSTI_PREVIEW_TAB")
            .ok()
            .and_then(|v| v.parse::<usize>().ok())
            .filter(|t| *t < TABS.len())
        {
            tabs.jump_to(tab);
            restored = true;
        }
        #[cfg_attr(target_os = "android", allow(unused_mut))]
        let mut sheet = Sheet::new();
        // Desktop design preview: open the Setup sheet at start-up.
        #[cfg(not(target_os = "android"))]
        if std::env::var_os("ROVSTI_PREVIEW_SHEET").is_some() {
            sheet.open();
        }
        // Desktop design preview: start a fake call at start-up.
        #[cfg(not(target_os = "android"))]
        if std::env::var_os("ROVSTI_PREVIEW_CALL").is_some() {
            backend.update(cx, |backend, cx| {
                backend.place_call("+905551112233", cx);
            });
        }
        // Desktop design preview: a multi-call scene at start-up (ROVSTI_PREVIEW_SCENE=two, conf,
        // conf4, hidden, waiting, nomerge, three; see backend/preview_multi.rs).
        #[cfg(not(target_os = "android"))]
        if let Ok(scene) = std::env::var("ROVSTI_PREVIEW_SCENE") {
            backend.update(cx, |backend, cx| backend.preview_scene(&scene, cx));
        }
        // Desktop design preview: ring a fake call at start-up (see backend/preview.rs).
        #[cfg(not(target_os = "android"))]
        if let Some(value) = std::env::var_os("ROVSTI_PREVIEW_INCOMING") {
            let name = value
                .to_str()
                .filter(|n| !n.is_empty() && *n != "1")
                .map(str::to_owned);
            let locked = std::env::var_os("ROVSTI_PREVIEW_LOCKED").is_some();
            let give_up = std::env::var_os("ROVSTI_PREVIEW_MISSED").is_some();
            backend.update(cx, |backend, cx| {
                backend.preview_ring(name.as_deref(), locked, give_up, cx);
            });
        }
        let contacts_view = ContactsView::new(cx);
        let search_field = contacts_view.field.clone();
        let blocked_ui = blocked_ui::BlockedUi::new(cx);
        let blocked_field = blocked_ui.field.clone();
        let replies_ed = replies_ui::ReplyEditor::new(cx);
        let reply_fields = replies_ed.fields.clone();
        let whatsapp = cx
            .new(|cx| rovsti_wa_ui::WhatsAppSettings::new(rovsti_wa_ui::AppId::Phone, "phone", cx));
        let wa_avatars = cx.new(|cx| rovsti_wa_ui::WaAvatars::new(&whatsapp, cx));
        let wa_call = cx.new(|cx| wa_call::WaCallUi::new(whatsapp.clone(), wa_avatars.clone(), cx));
        // The glass In-Call and Incoming screens draw WhatsApp calls.
        wa_call.update(cx, |ui, _| ui.set_incall_mounted(true));
        let mut subscriptions = vec![
            cx.observe_in(&backend, window, |this, backend, window, cx| {
                let pushes = backend.read(cx).wa_pushes();
                if pushes != this.wa_pushes_seen {
                    this.wa_pushes_seen = pushes;
                    this.whatsapp.update(cx, |w, cx| w.wake(cx));
                    this.wa_call.update(cx, |ui, cx| ui.sync_calls(cx));
                }
                if let Some(number) = backend
                    .update(cx, |b, _| b.take_handoff())
                    .as_ref()
                    .and_then(wa_call::handoff_number)
                {
                    this.place_whatsapp(&number, cx);
                }
                let actions = backend.update(cx, |b, _| b.take_wa_call_actions());
                if !actions.is_empty() {
                    this.wa_call.update(cx, |ui, cx| {
                        for (call_id, action) in actions {
                            match action {
                                rovsti_android::events::WaCallAction::Answer => {
                                    ui.answer_id(&call_id, cx);
                                }
                                rovsti_android::events::WaCallAction::Decline => {
                                    ui.decline_id(&call_id, cx);
                                }
                            }
                        }
                    });
                }
                // The microphone was allowed during a call that has none yet.
                if backend.read(cx).permissions().microphone.is_granted()
                    && this.wa_call.read(cx).mic_off()
                {
                    this.wa_call.update(cx, |w, cx| w.retry_mic(cx));
                }
                this.sync_speed_badges(cx);
                this.sync_restore(cx);
                this.sync_calls(window, cx);
                this.sync_documents(window, cx);
                this.sync_sim_selection(cx);
                this.sync_settings_live(cx);
                this.sync_ui_request(window, cx);
                this.sync_recents(cx);
                this.sync_contacts(cx);
                this.update_back(window);
                cx.notify();
            }),
            // A WhatsApp call changed: the call screens follow it like a Telecom snapshot.
            cx.observe_in(&wa_call, window, |this, _, window, cx| {
                this.sync_calls(window, cx);
                this.sync_wa_history(cx);
                if let Some(what) = this.wa_call.update(cx, |ui, _| ui.take_error()) {
                    // A failed Answer or Decline gives the ringing screen its buttons back.
                    this.incoming_rearm(cx);
                    if what == wa_call::MIC_OFF {
                        // Over the call screens and the tabs alike: Allow asks again.
                        this.show_pill_action(what, "Allow", block_ui::PillAction::GrantMic, cx);
                    } else if this.incall.covers() || this.incoming.covers() {
                        // Over a call screen the pill says it; the tab notice would be hidden.
                        this.show_notice_pill(what, None, false, cx);
                    } else {
                        this.set_call_note(Some(notice::Notice::WhatsApp(what)), cx);
                    }
                }
            }),
            // A WhatsApp picture or name arrived: the call screens and Recents follow.
            cx.observe_in(&wa_avatars, window, |this, _, window, cx| {
                if this.wa_call.read(cx).has_call() {
                    this.sync_calls(window, cx);
                }
                this.sync_wa_names(cx);
                cx.notify();
            }),
            cx.observe_window_appearance(window, |_, _, cx| cx.notify()),
            // Typing in the add field of the Blocked numbers page clears its error.
            cx.observe_in(&blocked_field, window, |this, _, window, cx| {
                this.blocked_field_changed(window, cx);
            }),
            // Typing in the Contacts search field filters the list.
            cx.observe_in(&search_field, window, |this, _, window, cx| {
                this.contacts_field_changed(window, cx);
            }),
            // Opening the app again brings a live call back.
            cx.observe_window_activation(window, |this, window, cx| {
                let active = window.is_window_active();
                this.backend.update(cx, |b, _| b.set_ui_ready(active));
                if active {
                    this.app_activated(cx);
                } else {
                    // The process may be killed while in the background.
                    this.backend.read(cx).flush_prefs();
                }
                this.recents_window_active(active, cx);
            }),
        ];
        // Typing in a quick reply field autosaves it.
        for field in &reply_fields {
            subscriptions.push(cx.observe_in(field, window, |this, _, window, cx| {
                this.replies_field_changed(window, cx);
            }));
        }
        // `tel:` links arrive as `ACTION_VIEW` URLs (see MainActivity).
        let mut dial_urls = dial_urls;
        cx.spawn_in(window, async move |this, cx| {
            while let Some(url) = dial_urls.next().await {
                if this
                    .update_in(cx, |this, window, cx| this.open_dial(&url, window, cx))
                    .is_err()
                {
                    break;
                }
            }
        })
        .detach();
        let marks = fx::Marks::default();
        let weak = cx.weak_entity();
        let tab_view = {
            let on_select = weak.clone();
            let on_touch = weak;
            cx.new(|_| {
                TabBarView::new(
                    move |index, _, cx| {
                        on_select
                            .update(cx, |this, cx| this.select_tab(index, cx))
                            .ok();
                    },
                    move |phase, window, cx| {
                        on_touch
                            .update(cx, |this, cx| this.on_touch(phase, None, window, cx))
                            .ok();
                    },
                )
            })
        };
        let mut app = Self {
            tabs,
            tab_view,
            dialed: String::new(),
            speed_map: Default::default(),
            speed_badges: Default::default(),
            speed_aria: Default::default(),
            kp_layout: rovsti_core::keypad_layout::keypad_layout(&keypad::default_input()),
            shown_digits: (
                String::new(),
                None,
                SharedString::default(),
                SharedString::default(),
            ),
            status: None,
            t9_hint_dismissed: false,
            dial_match: keypad::DialMatch::default(),
            t9: keypad::T9Match::default(),
            _subscriptions: subscriptions,
            incall: InCall::new(cx),
            incoming: Incoming::new(cx, marks.clone()),
            backend,
            xiaomi: backend::xiaomi(),
            motion: Motion::default(),
            presses: Presses::default(),
            page: Entrance::default(),
            tab_dir: 0.0,
            status_in: Entrance::default(),
            celebrate: signature::Celebrate::default(),
            setup_seen: None,
            setup_reopened: Vec::new(),
            setup_changed_title: None,
            melt: signature::Melt::default(),
            digit_pop: Spring::new(1.0, motion_tokens::release()).with_epsilon(0.002),
            shake: Spring::new(0.0, motion_tokens::glow()).with_epsilon(0.1),
            delete_shown: Spring::new(0.0, motion_tokens::appear()).with_epsilon(0.002),
            marks,
            keyfx: cx.new(|_| fx::KeyFx::new()),
            ghost: keypad::Ghost::default(),
            reduced: false,
            flights: flight::Flights::default(),
            page_fade: Fade::default(),
            call_fade: Fade::default(),
            font: Spring::new(dial::font_step(0), motion_tokens::morph()).with_epsilon(0.01),
            hold_gen: 0,
            sheet,
            add: addcall::AddCall::new(cx),
            opened: OpenedThisSession::default(),
            fell_back: OpenedThisSession::default(),
            role_declined: false,
            setup_note: None,
            sheet_kind: SheetKind::Setup,
            route: route::RouteUi::default(),
            diag: diagnostics::Diagnostics::default(),
            settings: settings_ui::SettingsUi::default(),
            backup: backup_ui::BackupUi::default(),
            setting_failed_seen: 0,
            sim: sim_ui::SimUi::default(),
            pill: NoticePill::new(),
            pill_action: None,
            speed_dial: speed_dial_ui::SpeedDialUi::default(),
            row_menu: block_ui::RowMenu::new(),
            blocked: blocked_ui,
            reply: replies_ui::ReplyUi::default(),
            replies_ed,
            whatsapp,
            wa_call,
            wa_pushes_seen: 0,
            wa_avatars,
            wa_fading: false,
            back_enabled: false,
            recents: RecentsView::new(),
            contacts: contacts_view,
            favorites: FavoritesView::new(),
            contact_sheet: None,
            confirm_delete: None,
            tabs_away: Spring::new(0.0, motion_tokens::select()).with_epsilon(0.002),
            pill_in: Spring::new(0.0, motion_tokens::appear()).with_epsilon(0.002),
            pill_text: SharedString::default(),
            pill_task: None,
            call_note: None,
            notice_shown: None,
            notice_h: Spring::new(0.0, motion_tokens::appear()).with_epsilon(0.002),
            last_dialed: SharedString::default(),
            window_active: true,
            photos: Photos::new(),
            restored,
            _restore_timeout: None,
            #[cfg(any(feature = "perf", test))]
            recorder: rovsti_ui::perf::Recorder::default(),
        };
        #[cfg(feature = "perf")]
        app.start_perf_runner(window, cx);
        if !app.restored {
            // Preferences are normally read within a frame or two; if the
            // data directory never answers the app opens on the Keypad.
            app._restore_timeout = Some(cx.spawn(async move |this, cx| {
                cx.background_executor().timer(RESTORE_WAIT).await;
                this.update(cx, |this, cx| {
                    if !this.restored {
                        this.restored = true;
                        this.tab_dir = 0.0;
                        this.page.restart();
                        cx.notify();
                    }
                })
                .ok();
            }));
        }
        app.sync_speed_badges(cx);
        app.sync_restore(cx);
        app.sync_recents(cx);
        app.sync_contacts(cx);
        // Desktop design preview: leave the call screen after 3 s (the pill).
        #[cfg(not(target_os = "android"))]
        if std::env::var_os("ROVSTI_PREVIEW_PILL").is_some() {
            cx.spawn(async move |this, cx| {
                cx.background_executor()
                    .timer(std::time::Duration::from_secs(3))
                    .await;
                this.update(cx, |this, cx| {
                    this.incall_back(cx);
                })
                .ok();
            })
            .detach();
        }
        // Desktop design preview: scroll Contacts after start-up (ROVSTI_PREVIEW_SCROLL=400).
        #[cfg(not(target_os = "android"))]
        if let Some(y) = std::env::var("ROVSTI_PREVIEW_SCROLL")
            .ok()
            .and_then(|v| v.parse::<f32>().ok())
        {
            cx.spawn(async move |this, cx| {
                cx.background_executor()
                    .timer(std::time::Duration::from_millis(6000))
                    .await;
                this.update(cx, |this, cx| {
                    this.contacts.preview_scroll(y);
                    cx.notify();
                })
                .ok();
            })
            .detach();
        }
        // Desktop design preview: a missed call lands in the log after N seconds
        // (ROVSTI_PREVIEW_NEWCALL=3), to watch Recents refresh live.
        #[cfg(not(target_os = "android"))]
        if let Some(secs) = std::env::var("ROVSTI_PREVIEW_NEWCALL")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
        {
            cx.spawn(async move |this, cx| {
                cx.background_executor()
                    .timer(std::time::Duration::from_secs(secs))
                    .await;
                this.update(cx, |this, cx| {
                    this.backend.update(cx, |backend, cx| {
                        backend.preview_new_call(rovsti_core::wire::CallLogKind::Missed, cx);
                    });
                })
                .ok();
            })
            .detach();
        }
        // Desktop design preview: flip light/dark after N seconds (ROVSTI_PREVIEW_THEME_FLIP=4)
        // to watch the theme melt (S15); `...=4,dark` starts the flip towards dark.
        #[cfg(not(target_os = "android"))]
        if let Ok(spec) = std::env::var("ROVSTI_PREVIEW_THEME_FLIP") {
            let mut parts = spec.split(',');
            let secs = parts
                .next()
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(4);
            let to_dark = parts.next() != Some("light");
            cx.spawn_in(window, async move |this, cx| {
                cx.background_executor()
                    .timer(std::time::Duration::from_secs(secs))
                    .await;
                this.update(cx, |_, cx| {
                    rovsti_ui::set_theme_override(Some(if to_dark {
                        Theme::Dark
                    } else {
                        Theme::Light
                    }));
                    cx.notify();
                })
                .ok();
            })
            .detach();
        }
        // Desktop design preview: a typed number (ROVSTI_PREVIEW_DIAL=05551112233).
        #[cfg(not(target_os = "android"))]
        if let Ok(number) = std::env::var("ROVSTI_PREVIEW_DIAL") {
            app.dialed = number;
            app.delete_shown.snap(1.0);
        }
        #[cfg(not(target_os = "android"))]
        if let Ok(script) = std::env::var("ROVSTI_PREVIEW_KEYS") {
            app.start_preview_keys(script, window, cx);
        }
        #[cfg(not(target_os = "android"))]
        if std::env::var_os("ROVSTI_PREVIEW_SETTINGS").is_some() {
            app.settings.open(false);
            app.settings.enter_snap();
            app.sync_settings_rows(cx, true);
        }
        #[cfg(not(target_os = "android"))]
        if let Ok(query) = std::env::var("ROVSTI_PREVIEW_QUERY") {
            app.contacts.field.update(cx, |f, cx| f.set(&query, cx));
        }
        #[cfg(not(target_os = "android"))]
        if let Ok(spec) = std::env::var("ROVSTI_PREVIEW_CONTACT") {
            app.preview_contact_sheet(&spec, window, cx);
        }
        // A call that is already ringing (the full-screen intent started us cold, or the
        // preview) shows on the very first frame instead of the tabs.
        app.sync_calls(window, cx);
        app
    }

    /// A `tel:` link or `ACTION_DIAL` arrived: show the Keypad with the number
    /// entered. Nothing is called.
    fn open_dial(&mut self, url: &str, window: &mut Window, cx: &mut Context<Self>) {
        // A dial intent forces the Keypad over the stored tab.
        self.restored = true;
        self.dialed = dial::from_tel_uri(url);
        self.status = None;
        self.delete_shown
            .set_target(f32::from(u8::from(!self.dialed.is_empty())));
        self.digit_pop.snap(1.1);
        self.digit_pop.set_target(1.0);
        if self.tabs.selected() != KEYPAD_TAB {
            self.tabs.jump_to(KEYPAD_TAB);
            self.tab_dir = 0.0;
            self.page.restart();
        }
        self.sheet.close();
        self.recents_tab_changed(cx);
        // Dialing again while a call is live leaves the call on its own.
        if self.incall.shown {
            self.incall_back(cx);
        }
        self.update_back(window);
        cx.notify();
    }

    fn checklist(&self, cx: &App) -> SetupChecklist {
        self.backend.read(cx).setup_checklist(self.xiaomi.is_some())
    }

    /// Closes the Setup sheet (scrim tap, Back, drag).
    fn close_sheet(&mut self, cx: &mut Context<Self>) {
        self.sheet.close();
        self.presses.release_all();
        cx.notify();
    }

    /// System Back, in the order of EXPERIENCE.md: sheet, in-call keypad,
    /// In-Call screen. On the other tabs it goes to the Keypad; on the Keypad
    /// the OS decides (Activity moves to back).
    fn on_back(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        // While the In-Call screen shows, what is open on the tabs under it (a menu,
        // a tab sheet, Settings, the keyboard) stays put: Back acts on the call.
        let on_call = self.incall.shown;
        if self.reply_is_open() {
            // Back puts the reply sheet away and returns to the ringing card.
            self.reply_close(window, cx);
        } else if self.incoming.covers() {
            // Back does nothing while a call rings: it must not look like a decline.
        } else if self.route.is_open() {
            // The route list closes first.
            self.route_close(window, cx);
        } else if self.sheet.is_open() && (!on_call || self.sheet_kind_over_call()) {
            // The Add call sheet's number chooser steps back to its list first.
            if !self.add_call_back(cx) {
                self.close_sheet(cx);
            }
        } else if on_call {
            // The keypad closes, else the screen goes; locked, the OS decides (no tab step).
            if self.incall.wants_back(self.incall.call_only) {
                self.incall_back(cx);
            }
        } else if self.row_menu.is_open() {
            self.row_menu_close(window, cx);
        } else if self.settings.sub_shown() {
            // The WhatsApp page steps back inside itself first.
            let stepped = self.settings.sub == Some(settings_ui::Sub::WhatsApp)
                && self
                    .whatsapp
                    .update(cx, |whatsapp, cx| whatsapp.go_back(cx));
            if !stepped {
                self.settings_close_sub(window, cx);
            }
        } else if self.settings.is_open() {
            self.settings_close(window, cx);
        } else if self.contacts.typing {
            // First Back puts the keyboard away; the next one is the OS's.
            window.blur();
        } else if self.tabs.selected() == CONTACTS_TAB
            && !self.contacts.field.read(cx).text().is_empty()
        {
            // A search is cleared before Back leaves Contacts.
            self.contacts.field.update(cx, |field, cx| field.clear(cx));
        } else if self.tabs.selected() != KEYPAD_TAB {
            // From any other tab Back goes home, to the Keypad; there the OS decides.
            self.select_tab(KEYPAD_TAB, cx);
        } else {
            self.incall_back(cx);
        }
        self.update_back(window);
    }

    /// The sheet is one drawn over the call screens (Add call, SIM chooser, reply).
    /// Any other sheet belongs to the tabs and is hidden while a call screen covers them.
    fn sheet_kind_over_call(&self) -> bool {
        matches!(
            self.sheet_kind,
            SheetKind::AddCall | SheetKind::SimChooser | SheetKind::Reply
        )
    }

    /// A visible sheet that owns the screen it sits on: a tab sheet only while no
    /// call screen covers it, a call sheet always.
    fn sheet_blocks(&self) -> bool {
        let call_covers = self.incall.covers() || self.incoming.covers();
        self.sheet.is_visible() && (!call_covers || self.sheet_kind_over_call())
    }

    /// Tells the host whether GPUI wants Back (only when something can close).
    fn update_back(&mut self, window: &mut Window) {
        let call_only = self.incall.call_only;
        // Over the In-Call screen only the call's own layers count (see `on_back`).
        let tabs_layer = !self.incall.shown
            && (self.row_menu.is_open()
                || self.sheet.is_open()
                || self.settings.is_open()
                || self.contacts.typing
                || self.tabs.selected() != KEYPAD_TAB);
        let want = self.incoming.wants_back()
            || self.reply_is_open()
            || self.route.is_open()
            || (self.sheet.is_open() && self.sheet_kind_over_call())
            || self.incall.wants_back(call_only)
            || tabs_layer;
        if want != self.back_enabled {
            self.back_enabled = want;
            window.set_back_enabled(want);
        }
    }

    /// Story 10.6: once the preferences are read, opens the tab the owner left
    /// on (the Keypad on first launch or for a value that makes no sense).
    fn sync_restore(&mut self, cx: &mut Context<Self>) {
        if self.restored || !self.backend.read(cx).prefs_ready() {
            return;
        }
        self.restored = true;
        self._restore_timeout = None;
        let tab = self
            .backend
            .read(cx)
            .prefs()
            .last_tab_or(TABS.len(), KEYPAD_TAB);
        self.tabs.jump_to(tab);
        self.tab_dir = 0.0;
        self.page.restart();
        self.recents_tab_changed(cx);
    }

    fn select_tab(&mut self, index: usize, cx: &mut Context<Self>) {
        if self.row_menu.is_open() {
            // A tap outside the menu only puts it away.
            self.row_menu.close();
            cx.notify();
            return;
        }
        if index != CONTACTS_TAB {
            self.speed_dial.pick = None;
        }
        self.tabs.press();
        let from = self.tabs.selected();
        if self.tabs.select(index) {
            // S5: the page enters from the direction of travel; a second tap
            // mid-flight retargets from where the page is.
            self.tab_dir = if index > from { 1.0 } else { -1.0 };
            self.page.restart_soft();
            if self.reduced {
                self.page_fade.restart();
            }
            self.recents_tab_changed(cx);
            // Remembered for the next launch (written off the UI thread).
            if let Ok(tab) = u8::try_from(index) {
                self.backend.update(cx, |backend, cx| {
                    backend.update_prefs(cx, |prefs| prefs.ui.last_tab = Some(tab));
                });
            }
        } else {
            // Tapping the selected tab scrolls its list to the top (Keypad: nothing).
            self.scroll_tab_to_top(index);
        }
        cx.notify();
    }

    /// Finger down/up on a pressable control (`id`), or on the tab bar (`None`).
    fn on_touch(
        &mut self,
        phase: TouchPhase,
        id: Option<SharedString>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match phase {
            TouchPhase::Started => {
                // Under the open sheet only the sheet's own buttons react, and
                // under the In-Call screen only its own controls.
                // The Settings page owns the screen: only its own controls (and sheets) react.
                // A call screen over Settings owns the screen instead (End must always work).
                let on_settings = id.as_deref().is_some_and(|i| self.settings.owns(i));
                let call_covers = self.incall.covers() || self.incoming.covers();
                if self.settings.covers() && !on_settings && !call_covers {
                    return;
                }
                let on_incoming = id.as_deref().is_some_and(|i| i.starts_with("in-"));
                // The reply sheet's rows work over the ringing screen and over a call.
                let reply_owned = id.as_deref().is_some_and(|i| self.reply_owns(i));
                let blocked_by_incoming = self.incoming.covers() != on_incoming && !reply_owned;
                // The Add call sheet's own keys, field and buttons work over the call.
                let owned = id.as_deref().is_some_and(|i| {
                    self.add_call_owns(i) || self.sim_owns(i) || self.reply_owns(i)
                });
                // A tab sheet left open under a call screen blocks nothing there.
                let sheet_blocks = self.sheet_blocks();
                let blocked_by_sheet = sheet_blocks
                    && !owned
                    && !id.as_deref().is_some_and(|i| i.starts_with("sheet-"));
                let is_call_id = |i: &str| {
                    i.starts_with("ic-")
                        || i.starts_with("ik-")
                        || i.starts_with("iw-")
                        || i.starts_with("ix-")
                };
                let blocked_by_call =
                    self.incall.covers() && !owned && !id.as_deref().is_some_and(is_call_id);
                // The open route list owns the screen: only End still works.
                let blocked_by_route = self.route.is_open()
                    && id
                        .as_deref()
                        .is_some_and(|i| is_call_id(i) && i != "ic-end");
                let blocked_by_tabs =
                    !self.incall.covers() && id.as_deref().is_some_and(is_call_id);
                // Over a sheet only the sheet's own controls react; the call controls under it do not.
                let call_under_sheet = sheet_blocks
                    && id
                        .as_deref()
                        .is_some_and(|i| is_call_id(i) && i != "ic-end");
                if blocked_by_incoming
                    || blocked_by_sheet
                    || blocked_by_call
                    || blocked_by_tabs
                    || blocked_by_route
                    || call_under_sheet
                {
                    return;
                }
                if let Some(id) = id.clone() {
                    self.presses.touch_down(id.clone());
                    if &*id == "gear" {
                        self.settings.gear_held = true;
                    }
                    if let Some(slot) = id
                        .strip_prefix("ix-dim-")
                        .and_then(|n| n.parse::<u8>().ok())
                    {
                        // A dimmed control: held for 450 ms it says why.
                        self.arm_hold(
                            incall::REASON_AFTER_MS,
                            keypad::HoldAction::Reason(slot),
                            window,
                            cx,
                        );
                    } else if Self::acts_on_down(&id) {
                        self.activate(&id, window, cx);
                    } else if let Some(n) = id
                        .strip_prefix("sheet-cn-")
                        .and_then(|n| n.parse::<u8>().ok())
                        .filter(|_| self.sim_has_default(cx))
                    {
                        self.arm_hold(
                            sim_ui::HOLD_MS,
                            keypad::HoldAction::SimNumber(n),
                            window,
                            cx,
                        );
                    } else if &*id == "call" && self.sim_long_press_ready(cx) {
                        self.arm_hold(sim_ui::HOLD_MS, keypad::HoldAction::SimChooser, window, cx);
                    } else if &*id == "set-title" {
                        self.arm_hold(
                            settings_ui::DIAG_AFTER_MS,
                            keypad::HoldAction::SettingsDiagnostics,
                            window,
                            cx,
                        );
                    } else if &*id == "sheet-title" {
                        self.arm_hold(
                            diagnostics::OPEN_AFTER_MS,
                            keypad::HoldAction::Diagnostics,
                            window,
                            cx,
                        );
                    }
                }
            }
            TouchPhase::Ended | TouchPhase::Cancelled => self.released(cx),
            TouchPhase::Moved => return,
        }
        if id.is_none() && !self.incall.covers() {
            self.tabs.touch(phase);
        }
        cx.notify();
    }

    /// Places a WhatsApp call to `number`; what stops it from starting shows as a notice.
    pub(crate) fn place_whatsapp(&mut self, number: &str, cx: &mut Context<Self>) {
        let placed = self.wa_call.update(cx, |ui, cx| ui.place(number, cx));
        self.set_call_note(placed.err().map(notice::Notice::WhatsApp), cx);
        cx.notify();
    }

    /// Raw-touch listener for the control `id` (see `rovsti_ui::touch`).
    fn touch_listener(
        cx: &mut Context<Self>,
        id: Option<SharedString>,
    ) -> impl Fn(TouchPhase, &mut Window, &mut App) + 'static {
        let entity = cx.entity();
        move |phase, window, cx| {
            entity.update(cx, |this, cx| this.on_touch(phase, id.clone(), window, cx));
        }
    }

    fn tick(&mut self, dt: std::time::Duration, real: std::time::Duration) -> bool {
        let mut moving = self.tabs.tick(dt);
        moving |= self.settings.tick(dt, real);
        moving |= self.sim.tick(dt);
        moving |= self.page_fade.tick(real);
        moving |= self.call_fade.tick(real);
        moving |= self.presses.tick(dt);
        moving |= self.page.tick(dt);
        moving |= self.status_in.tick(dt);
        moving |= self.sheet.tick(dt);
        moving |= self.sheet.tick_real(real);
        moving |= self.celebrate.tick(dt);
        moving |= self.melt.tick(dt);
        if let Some(sheet) = &mut self.contact_sheet {
            moving |= sheet.tick_star(dt);
        }
        moving |= self.incall.tick(dt);
        moving |= self.add.tick(dt);
        moving |= self.pill.tick(dt);
        moving |= self.route.tick(dt);
        moving |= self.incoming.tick(dt);
        moving |= self.incoming.tick_real(real);
        moving |= self.recents.tick(dt);
        moving |= self.row_menu.tick(dt);
        moving |= self.blocked.tick(dt);
        moving |= self.reply.tick(dt);
        moving |= self.replies_ed.tick(dt);
        moving |= self.notice_h.tick(dt);
        moving |= self.tabs_away.tick(dt);
        moving |= self.pill_in.tick(dt);
        moving |= self.photos.fading();
        moving |= self.wa_fading;
        moving |= self.ghost.tick(dt);
        moving |= self.flights.tick(dt);
        for spring in [
            &mut self.digit_pop,
            &mut self.shake,
            &mut self.delete_shown,
            &mut self.font,
        ] {
            moving |= spring.tick(dt);
        }
        moving
    }

    /// Horizontal offset of the entering page: 28 px toward its resting place.
    fn page_slide(&self) -> f32 {
        self.tab_dir * self.page.offset(28.0)
    }

    /// Vertical lift for a page that appears without a direction (first show).
    fn page_lift(&self) -> f32 {
        if self.tab_dir == 0.0 {
            self.page.offset(24.0)
        } else {
            0.0
        }
    }

    /// The title slides 12 px further than the page (parallax).
    fn title_shift(&self) -> f32 {
        self.tab_dir * self.page.offset(12.0)
    }

    /// What the slow-frame log calls the screen the owner is on.
    fn scenario(&self) -> rovsti_ui::perf::Scenario {
        use rovsti_ui::perf::Scenario;
        if let Some(forced) = rovsti_ui::perf::forced() {
            forced
        } else if self.incoming.covers() {
            Scenario::Incoming
        } else if self.incall.covers() {
            Scenario::InCall
        } else if self.sheet.is_visible() {
            Scenario::Sheet
        } else {
            match self.tabs.selected() {
                FAVORITES_TAB => Scenario::Favorites,
                RECENTS_TAB => Scenario::Recents,
                CONTACTS_TAB => Scenario::Contacts,
                _ => Scenario::Keypad,
            }
        }
    }

    /// S14: the checklist just became complete in this session. Looks only
    /// once Android's answers are in, so a launch can never celebrate.
    fn sync_setup_celebration(&mut self, reduced: bool, cx: &mut Context<Self>) {
        let backend = self.backend.read(cx);
        if !(backend.permissions_known() && backend.prefs_ready()) {
            return;
        }
        let now = self.checklist(cx);
        if self.setup_seen != Some(now) {
            if let Some(seen) = self.setup_seen {
                let mut reopened = self.setup_reopened.clone();
                for item in now.reopened_since(&seen) {
                    if !reopened.contains(&item) {
                        reopened.push(item);
                    }
                }
                reopened = rovsti_core::setup::still_reopened(&reopened, &now);
                if reopened != self.setup_reopened {
                    self.setup_changed_title =
                        rovsti_core::setup::changed_title(&reopened).map(SharedString::from);
                    self.setup_reopened = reopened;
                }
                cx.notify();
            }
            self.setup_seen = Some(now);
        }
        if self.celebrate.observe(now.complete(), reduced) {
            cx.notify();
        }
    }

    /// S14: the page wash, brighter by up to 0.10 alpha for 600 ms.
    fn setup_wash(&self, theme: Theme) -> Option<AnyElement> {
        let extra = self.celebrate.wash();
        if extra <= 0.0 {
            return None;
        }
        let accent = theme::accent_glow(theme);
        Some(
            div()
                .absolute()
                .inset_0()
                .bg(gpui::linear_gradient(
                    160.0,
                    gpui::linear_color_stop(gpui::Hsla { a: extra, ..accent }, 0.0),
                    gpui::linear_color_stop(gpui::Hsla { a: 0.0, ..accent }, 0.55),
                ))
                .into_any_element(),
        )
    }

    /// S15: one quad in the old theme's background colour, fading out.
    fn theme_veil(&self) -> Option<AnyElement> {
        let (from, alpha) = self.melt.veil()?;
        Some(
            div()
                .absolute()
                .inset_0()
                .bg(gpui::Hsla {
                    a: alpha,
                    ..theme::colors(from).background
                })
                .into_any_element(),
        )
    }

    /// Shows the Notice pill (S28), replacing the one up. `action` is the
    /// trailing accent button's label; `undo` gives it the 5 s life.
    pub(crate) fn show_notice_pill(
        &mut self,
        text: impl Into<SharedString>,
        action: Option<SharedString>,
        undo: bool,
        cx: &mut Context<Self>,
    ) {
        self.pill.show(text, action, undo, false);
        cx.notify();
    }

    /// The pill's distance from the window bottom: 12 px above the Tab Bar,
    /// 12 px above the End button on the In-Call screen, 12 px above the
    /// bottom inset over a sheet.
    fn pill_bottom(&self) -> f32 {
        const GAP: f32 = 12.0;
        if self.incall.covers() {
            // pb(58) + the 64 px End button.
            58.0 + 64.0 + GAP
        } else if self.sheet.is_visible() || self.settings.covers() {
            GAP + 10.0
        } else {
            f32::from(metrics::TAB_BAR_MARGIN) + f32::from(metrics::TAB_BAR_HEIGHT) * 1.1 + GAP
        }
    }

    fn render_pill(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let entity = cx.entity();
        self.pill.render(
            theme,
            self.pill_bottom(),
            move |pointer, position, window, cx| {
                entity.update(cx, |this, cx| {
                    if this.pill.pointer(pointer, position) == PillEvent::Action {
                        this.pill_fired(window, cx);
                    }
                    cx.notify();
                });
            },
        )
    }

    fn page(&mut self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let tab = self.tabs.selected();
        let complete = self.checklist(cx).complete();
        let setup = self.setup_button(theme, complete, cx).into_any_element();
        let gear = self.settings_gear(theme, cx).into_any_element();
        if tab != KEYPAD_TAB {
            // Past 24 px of scroll the large title hands over to a compact one
            // (opacity and translate only: the list never moves).
            let scrolled = match tab {
                RECENTS_TAB => self.recents.scrolled(),
                CONTACTS_TAB => self.contacts.scrolled(),
                _ => self.favorites.scrolled(),
            };
            let collapse = (scrolled / 24.0).clamp(0.0, 1.0);
            let colors = theme::colors(theme);
            let title = div()
                .relative()
                .top(px(-10.0 * collapse))
                .left(px(self.title_shift()))
                .opacity(1.0 - collapse)
                .pr(px(56.0))
                .child(large_title(theme, TABS[tab], None));
            let compact = div()
                .absolute()
                .top(px(54.0))
                .left_0()
                .right_0()
                .h(px(44.0))
                .flex()
                .items_center()
                .justify_center()
                .opacity(collapse)
                .text_size(rovsti_ui::tokens::typography::HEADLINE.size)
                .font_weight(rovsti_ui::tokens::typography::HEADLINE.weight)
                .text_color(colors.label)
                .child(TABS[tab]);
            #[cfg(not(target_os = "android"))]
            let caption = Some(self.preview_caption(theme, 1.0 - collapse));
            #[cfg(target_os = "android")]
            let caption: Option<AnyElement> = None;
            // Virtualised lists scroll themselves: the page only stacks the
            // title and the list, so nothing above the list ever moves.
            let body = match tab {
                RECENTS_TAB => self.recents_body(theme, window, cx),
                CONTACTS_TAB => self.contacts_body(theme, window, cx),
                _ => self.favorites_body(theme, window, cx),
            };
            return div()
                .id("page")
                .size_full()
                .opacity(self.page.progress() * self.page_fade.value())
                .child(
                    div()
                        .relative()
                        .top(px(self.page_lift()))
                        .left(px(self.page_slide()))
                        .size_full()
                        .flex()
                        .flex_col()
                        .child(title)
                        .child(compact)
                        .children(caption)
                        .child(setup)
                        .child(gear)
                        .child(body),
                )
                .into_any_element();
        }
        let title = div()
            .relative()
            .left(px(self.title_shift()))
            .pr(px(56.0))
            .child(large_title(theme, TABS[tab], None));
        div()
            .id("page")
            .size_full()
            .opacity(self.page.progress() * self.page_fade.value())
            .child(
                div()
                    .relative()
                    .top(px(self.page_lift()))
                    .left(px(self.page_slide()))
                    .child(title)
                    .child(setup)
                    .child(gear)
                    .child(self.keypad(theme, cx)),
            )
            .into_any_element()
    }

    /// A sheet with `body` over the page: scrim, drag and dismiss wired up.
    fn modal_sheet(
        &self,
        theme: Theme,
        window: &Window,
        label: &'static str,
        body: AnyElement,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let entity = cx.entity();
        let drag_entity = entity.clone();
        self.sheet.render(
            theme,
            window,
            label,
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_sheet(cx)),
            move |phase, y, _, cx| {
                drag_entity.update(cx, |this, cx| {
                    this.sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }

    /// The bottom sheet for whatever it is showing.
    fn sheet_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        match self.sheet_kind {
            SheetKind::Setup => self.setup_sheet(theme, window, cx),
            SheetKind::Diagnostics => self.diag_sheet(theme, window, cx),
            // Drawn over the In-Call screen by `add_call_overlay`; the reply sheet by `reply_overlay`.
            SheetKind::AddCall | SheetKind::SimChooser | SheetKind::Reply => None,
            SheetKind::ResetReplies => self.replies_reset_sheet(theme, window, cx),
            SheetKind::ConfirmDelete => self.confirm_delete_sheet(theme, window, cx),
            SheetKind::T9Results => self.t9_results_sheet(theme, window, cx),
            SheetKind::ImportPreview => {
                if !self.sheet.is_visible() {
                    return None;
                }
                let body = self.import_sheet_body(theme, cx)?;
                self.modal_sheet(theme, window, "Import backup", body, cx)
            }
            SheetKind::Number => {
                if !self.sheet.is_visible() {
                    return None;
                }
                let body = self.number_sheet_body(theme, cx)?;
                self.modal_sheet(theme, window, "Number", body, cx)
            }
            SheetKind::Contact => {
                if !self.sheet.is_visible() {
                    return None;
                }
                let body = self.contact_sheet_body(theme, cx)?;
                let label = if self.contact_sheet.as_ref().is_some_and(|s| s.chooser()) {
                    "Choose a number"
                } else {
                    "Contact"
                };
                self.modal_sheet(theme, window, label, body, cx)
            }
        }
    }
}

impl Render for PhoneApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let reduced = window.prefers_reduced_motion();
        self.sheet.set_reduced(reduced);
        self.pill.set_reduced(reduced);
        self.route.set_reduced(reduced);
        // S15: a theme change melts (or snaps when the last frame was slow).
        self.melt
            .observe(theme, reduced, self.motion.last_frame_over_budget());
        self.sync_setup_celebration(reduced, cx);
        rovsti_ui::perf::set_scenario(self.scenario());
        self.contacts_sync_focus(window, cx);
        self.sync_notice(cx);
        self.sync_pill(cx);
        self.sync_row_menu(cx);
        self.sync_reply(window, cx);
        self.sync_add_call_closed(cx);
        self.sync_sim_dismissed(cx);
        self.sync_settings_frame(window, cx);
        self.sync_dial_match(cx);
        self.sync_t9_match(cx);
        self.sync_t9_rows(cx);
        self.wa_fading = self.wa_avatars.read(cx).fading();
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame_timed(window, |dt, real| self.tick(dt, real));
        self.flights_ready(cx);
        self.sim_flight_ready();
        self.motion = motion;
        #[cfg(any(feature = "perf", test))]
        self.perf_observe(window);

        self.sheet.set_viewport(window.viewport_size());
        self.update_back(window);
        self.reduced = window.prefers_reduced_motion();
        self.kp_layout = rovsti_core::keypad_layout::keypad_layout(&self.keypad_input(window));
        self.font.set_target(self.field_font());
        let keep = self.kp_layout.number_fit.keep;
        if self.shown_digits.0 != self.dialed || self.shown_digits.1 != keep {
            let (head, last) = keypad::elided_head(&self.dialed, keep);
            self.shown_digits = (self.dialed.clone(), keep, head.into(), last.into());
        }

        let tabs = &self.tabs;
        self.tab_view
            .update(cx, |view, cx| view.sync(tabs, theme, cx));
        let incall = self.render_incall(theme, window, cx);
        let add_call = self.add_call_overlay(theme, window, cx);
        let sim_sheet = self.sim_overlay(theme, window, cx);
        let incoming = self.render_incoming(theme, cx);
        let tabs_visible = self.restored
            && !self.incall.hides_tabs()
            && !self.incall.blank
            && !self.incoming.hides_tabs();
        let mut root = div()
            .size_full()
            .relative()
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.released(cx);
                    this.tabs.release();
                    cx.notify();
                }),
            )
            .child(backdrop(theme, theme::accent_glow(theme)))
            // Compiles the glass pipelines in the first frame, not on first touch.
            .child(rovsti_ui::glass::warm_up_layer())
            .children(self.setup_wash(theme));
        let away = self.tabs_away.value.clamp(0.0, 1.0);
        if tabs_visible {
            let sheet = self.sheet_overlay(theme, window, cx);
            root = root
                .child(self.page(theme, window, cx))
                .children(self.return_pill(theme, cx))
                .children(self.notice_slot(theme, cx))
                .children((away < 0.995 && self.settings.progress() < 0.995).then(|| {
                    // Under the keyboard the bar slides off the bottom; it is
                    // hidden, not dismissed. The bar is a cached view: frames in
                    // which it did not change replay its last frame.
                    div()
                        .id("tab-bar-slot")
                        .absolute()
                        .bottom(metrics::TAB_BAR_MARGIN - px(away * 140.0))
                        .left_0()
                        .right_0()
                        .h(metrics::TAB_BAR_HEIGHT * 1.1)
                        .opacity(self.settings_tab_fade())
                        .child(
                            self.tab_view
                                .clone()
                                .cached(gpui::StyleRefinement::default().size_full()),
                        )
                }))
                .child(self.keyfx.clone())
                .children(self.settings_overlay(theme, window, cx))
                .children(sheet)
                .children(self.row_menu_overlay(theme, window, cx));
        }
        #[cfg(not(target_os = "android"))]
        let root = root.child(self.dev_switcher(theme, cx));
        let pill = self.render_pill(theme, cx);
        let route = self.render_route(theme, window, cx);
        root.children(incall)
            .children(add_call)
            .children(sim_sheet)
            .children(route)
            .children(pill)
            .children(incoming)
            .child(self.wa_call.clone())
            .children(self.reply_overlay(theme, window, cx))
            .children(self.reply_bubble(theme))
            .children(self.flight_layer(theme))
            .children(self.sim_flight_layer(theme))
            .children(self.theme_veil())
    }
}

#[gpui_platform::main]
fn main() {
    // Teal from the app icon (DESIGN.md): light, dark and glow.
    theme::set_accent(theme::AccentPair::from_hex(
        0x08_80_6E, 0x2D_D4_B4, 0x14_C8_A8,
    ));
    rovsti_ui::perf::install_sink(backend::perf_line);
    let (dial_tx, dial_rx) = mpsc::unbounded::<String>();
    let app = gpui_platform::application().with_assets(IconAssets::new());
    app.on_open_urls(move |urls| {
        for url in urls {
            let _ = dial_tx.unbounded_send(url);
        }
    });
    app.run(move |cx: &mut App| {
        let backend = Backend::start(cx);
        #[cfg(feature = "perf")]
        {
            rovsti_ui::perf::set_thermal(cx.thermal_state());
            cx.on_thermal_state_change(|cx| rovsti_ui::perf::set_thermal(cx.thermal_state()))
                .detach();
        }
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                    None,
                    size(px(402.0), px(874.0)),
                    cx,
                ))),
                ..Default::default()
            },
            |window, cx| {
                let app = cx.new(|cx| PhoneApp::new(backend, dial_rx, window, cx));
                // System Back: sheet, in-call keypad, In-Call screen.
                let weak = app.downgrade();
                window.on_system_back(cx, move |window, cx| {
                    weak.update(cx, |this, cx| this.on_back(window, cx)).ok();
                });
                app
            },
        )
        .expect("failed to open the Rovsti Phone window");
        cx.activate(true);
    });
}
