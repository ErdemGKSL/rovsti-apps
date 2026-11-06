//! Rovsti Stops: set a destination, fall asleep on the bus, get woken up
//! before your stop. Works offline: GPS fixes are compared on-device by
//! `rovsti_geo`, no map server or account involved.
//!
//! * `backend`: files, permission checks, starting and stopping the trip.
//! * `trip_ui`: the Trip tab, the live card and the alarm screen.
//! * `pick`: the pure picking state machine (A, B, pins, drag, hint, labels).
//! * `map`: the map surface and its gestures; your position arrives as a `stops_location`
//!   event from `rovsti_android::location` (a seam, no tracking).
//! * `picker`: the destination sheet and the shared-location confirmation.
//! * `saved_ui`: the Saved tab.
//! * `settings_ui`: the Settings list, History, and the arriving-soon message page.
//! * `headless` / `store`: the trip logic Kotlin calls with no UI running.

use std::{path::PathBuf, sync::Arc, time::Duration};

use futures::{StreamExt as _, channel::mpsc};
use gpui::{
    AnyElement, App, AppContext as _, Bounds, Context, Entity, InteractiveElement, IntoElement,
    ParentElement, Render, SharedString, Styled, Subscription, Task, Window, WindowBounds,
    WindowOptions, div, prelude::*, px, size,
};
use rovsti_android::{
    events::{self, RawEvent, kind},
    location::{self, LocationFix, parse_location_fix},
    trip::{AlarmAction, parse_trip_alarm},
};
use rovsti_core::wire::{DocumentOp, DocumentResult};
use rovsti_geo::{ActiveTrip, Coord, Feed, History, Place, SavedPlaces, StopsPrefs};
use rovsti_ui::{
    Icon, IconAssets,
    components::large_title,
    current_theme,
    field::TextField,
    motion::{Entrance, Motion},
    notice_pill::{NoticePill, PillEvent},
    sheet::Sheet,
    tab_bar::{Tab, TabBar, content_bottom_inset},
    tokens::{Theme, metrics},
};
use rovsti_wa_ui::{AppId, WhatsAppSettings};

mod backend;
mod headless;
#[cfg(test)]
mod kotlin_contract;
mod lists;
mod map;
mod pick;
mod picker;
mod saved_ui;
mod settings_ui;
mod store;
mod tiles;
mod transit;
mod trip_ui;

use backend::Preflight;
use picker::{Destination, SheetMode};
use settings_ui::{ChatList, SettingsPage};
use trip_ui::{AlarmView, Live};

const TABS: [&str; 3] = ["Trip", "Saved", "Settings"];
const TAB_ICONS: [Icon; 3] = [
    Icon::LucideNavigation,
    Icon::LucideBookmark,
    Icon::LucideSettings,
];

/// What a tab or page renders: a block inside the scrolling page, or a
/// virtualised list that scrolls itself and brings its own title.
pub(crate) enum Page {
    Flow(AnyElement),
    List(Box<gpui::List>),
}

/// What the Undo button on the notice pill does.
pub(crate) enum PillAction {
    UndoDelete(Place, usize),
    RestoreHistory(History),
}

pub(crate) struct StopsApp {
    tabs: TabBar,
    motion: Motion,
    page: Entrance,

    /// The app's data directory, once it is known.
    pub(crate) dir: Option<PathBuf>,
    /// The files have been read.
    pub(crate) loaded: bool,
    pub(crate) prefs: StopsPrefs,
    pub(crate) saved: SavedPlaces,
    pub(crate) history: History,
    /// The imported transit feed: searched by the stop picker, drawn on the map.
    pub(crate) gtfs: Option<Arc<Feed>>,
    pub(crate) transit: transit::Transit,
    pub(crate) map: map::MapState,
    /// Map tiles: the server, its disk cache and the downloads.
    pub(crate) map_tiles: tiles::MapTiles,
    /// The stop search result for the text in the picker field.
    pub(crate) stop_hits: picker::StopHits,
    /// The line chosen in the stop sheet.
    pub(crate) stop_pattern: Option<u32>,

    /// The two points chosen, not started.
    pub(crate) pick: pick::Picking,
    /// Text the chat when arriving, for the next trip.
    pub(crate) notice_on: bool,
    /// The trip in progress.
    pub(crate) trip: Option<ActiveTrip>,
    pub(crate) live: Option<Live>,
    /// A start or stop is under way.
    pub(crate) busy: bool,
    /// A start waiting for the user to answer a permission prompt.
    pub(crate) pending_start: Option<(Destination, Destination)>,
    pub(crate) preflight: Preflight,
    /// Re-reads permissions when the user comes back from system settings.
    _activation: Subscription,
    pub(crate) alarm: Option<AlarmView>,
    /// Brings the alarm back after a snooze.
    pub(crate) rering: Option<Task<()>>,

    pub(crate) sheet: Sheet,
    pub(crate) sheet_mode: SheetMode,
    pub(crate) field: Entity<TextField>,
    /// The confirmation's Save toggle.
    pub(crate) save_place: bool,
    pub(crate) back_enabled: bool,
    /// The saved place whose actions are open.
    pub(crate) expanded: Option<u64>,
    /// The virtualised lists: the Saved tab, History, the chat picker and the
    /// destination search.
    pub(crate) saved_list: lists::KeyedList,
    pub(crate) history_list: lists::KeyedList,
    pub(crate) notice_list: lists::KeyedList,
    pub(crate) pick_list: lists::KeyedList,
    /// The destination search's rows, rebuilt with the sheet.
    pub(crate) pick_items: Vec<picker::PickItem>,

    pill: NoticePill,
    pill_action: Option<(u64, PillAction)>,
    pub(crate) whatsapp: Entity<WhatsAppSettings>,

    /// The page open in the Settings tab.
    pub(crate) settings_page: SettingsPage,
    /// The arriving-soon template being edited.
    pub(crate) template_field: Entity<TextField>,
    /// The tile server URL being edited.
    pub(crate) tile_field: Entity<TextField>,
    /// A tile server test is running.
    pub(crate) tile_testing: bool,
    /// The chats the message can go to.
    pub(crate) chats: ChatList,
    /// Clear history was tapped once.
    pub(crate) confirm_clear: bool,
}

impl StopsApp {
    fn new(
        shares: mpsc::UnboundedReceiver<String>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let whatsapp = cx.new(|cx| WhatsAppSettings::new(AppId::Stops, "stops", cx));
        // Linking or unlinking decides whether the "text when arriving" row shows.
        cx.observe(&whatsapp, |_, _, cx| cx.notify()).detach();
        let field = cx.new(TextField::new);
        cx.observe(&field, |_, _, cx| cx.notify()).detach();
        let template_field = cx.new(TextField::new);
        let tile_field = cx.new(TextField::new);
        cx.observe(&tile_field, |_, _, cx| cx.notify()).detach();
        cx.observe(&template_field, |this, _, cx| this.on_template_edited(cx))
            .detach();

        // Coming back from a permission page or the lock screen: the service
        // may have changed the trip, and the grants may have changed.
        let activation = cx.observe_window_activation(window, |this, window, cx| {
            if window.is_window_active() {
                this.refresh_preflight(cx);
                this.refresh_trip(cx);
            }
        });

        // Kotlin events: the service's status, the alarm, permissions, files.
        let (mut events_rx, sink) = events::install_sink_owned();
        cx.spawn(async move |this, cx| {
            while let Some(event) = events_rx.next().await {
                if this
                    .update(cx, |this, cx| this.on_event(&event, cx))
                    .is_err()
                {
                    break;
                }
            }
            events::clear_sink_if(sink);
        })
        .detach();
        cx.background_executor()
            .spawn(async {
                let _ = rovsti_android::window::ui_attached(true);
            })
            .detach();

        // Links opened with Stops and text shared to it.
        let mut shares = shares;
        cx.spawn(async move |this, cx| {
            while let Some(text) = shares.next().await {
                if this
                    .update(cx, |this, cx| this.offer_text(&text, cx))
                    .is_err()
                {
                    break;
                }
            }
        })
        .detach();

        let data_dir = rovsti_ui::storage::data_dir("stops", cx);
        cx.spawn(async move |this, cx| {
            let dir = match data_dir.await {
                Ok(dir) => dir,
                Err(error) => {
                    log::warn!("no data directory for Stops: {error}");
                    return;
                }
            };
            let read_from = dir.clone();
            let data = cx
                .background_executor()
                .spawn(async move { backend::load(&read_from) })
                .await;
            this.update(cx, |this, cx| {
                this.dir = Some(dir.clone());
                this.loaded = true;
                this.prefs = data.prefs;
                let source = this.prefs.tile_source();
                this.map_tiles.attach(&dir, source, cx);
                this.saved = data.saved;
                this.history = data.history;
                this.set_feed(data.gtfs);
                this.set_saved_trip(data.trip, cx);
                this.resume_service(cx);
                this.start_simulation(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();

        let app = Self {
            tabs: TabBar::new(
                TABS.into_iter()
                    .zip(TAB_ICONS)
                    .map(|(label, icon)| Tab::new(icon, label)),
            ),
            motion: Motion::default(),
            page: Entrance::default(),
            dir: None,
            loaded: false,
            prefs: StopsPrefs::default(),
            saved: SavedPlaces::default(),
            history: History::default(),
            gtfs: None,
            transit: transit::Transit::default(),
            map: map::MapState::default(),
            map_tiles: tiles::MapTiles::default(),
            stop_hits: picker::StopHits::default(),
            stop_pattern: None,
            pick: pick::Picking::default(),
            notice_on: true,
            trip: None,
            live: None,
            busy: false,
            pending_start: None,
            preflight: Preflight::default(),
            _activation: activation,
            alarm: None,
            rering: None,
            sheet: Sheet::new(),
            sheet_mode: SheetMode::Pick,
            field,
            save_place: false,
            back_enabled: false,
            expanded: None,
            saved_list: lists::KeyedList::default(),
            history_list: lists::KeyedList::default(),
            notice_list: lists::KeyedList::default(),
            pick_list: lists::KeyedList::default(),
            pick_items: Vec::new(),
            pill: NoticePill::new(),
            pill_action: None,
            whatsapp,
            settings_page: SettingsPage::default(),
            template_field,
            tile_field,
            tile_testing: false,
            chats: ChatList::default(),
            confirm_clear: false,
        };
        app.refresh_preflight_later(cx);
        app.load_last_location(cx);
        app
    }

    /// Centres the idle map on the newest device fix when it is fresh. Silent when
    /// there is none, no permission, or on desktop.
    fn load_last_location(&self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let fix = cx
                .background_executor()
                .spawn(async { location::last_location() })
                .await;
            if let Ok(Some(fix)) = fix {
                this.update(cx, |this, cx| this.apply_fix(fix, true, cx))
                    .ok();
            }
        })
        .detach();
    }

    /// Puts a device fix on the map. No trip means the idle map follows it; a
    /// running trip keeps its own view.
    fn apply_fix(&mut self, fix: LocationFix, require_fresh: bool, cx: &mut Context<Self>) {
        if self.trip.is_some()
            || (require_fresh && !map::fresh_enough(fix.time_ms, store::now_ms()))
        {
            return;
        }
        self.map
            .set_device_fix(Coord::new(fix.lat, fix.lon), fix.accuracy_m);
        cx.notify();
    }

    /// `ROVSTI_STOPS_SIMULATE=1` on desktop: a synthetic fix every second,
    /// through the same handler the Android service calls.
    fn start_simulation(&mut self, cx: &mut Context<Self>) {
        if !backend::simulate_requested() {
            return;
        }
        log::info!("simulating location fixes");
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(Duration::from_secs(1)).await;
                let Ok(dir) = this.update(cx, |this, _| this.dir.clone()) else {
                    break;
                };
                let Some(dir) = dir else { continue };
                let reply = cx
                    .background_executor()
                    .spawn(async move { backend::simulate_tick(&dir) })
                    .await;
                if let Some(json) = reply
                    && this
                        .update(cx, |this, cx| this.on_status_json(&json, cx))
                        .is_err()
                {
                    break;
                }
            }
        })
        .detach();
    }

    fn refresh_preflight_later(&self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let pre = cx
                .background_executor()
                .spawn(async { Preflight::gather() })
                .await;
            this.update(cx, |this, cx| {
                this.preflight = pre;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn refresh_preflight(&mut self, cx: &mut Context<Self>) {
        self.refresh_preflight_later(cx);
    }

    /// One event from Kotlin.
    fn on_event(&mut self, event: &RawEvent, cx: &mut Context<Self>) {
        match event.kind.as_str() {
            kind::WA_PUSH => self.whatsapp.update(cx, |w, cx| w.wake(cx)),
            kind::TRIP_STATUS => self.on_status_json(&event.json, cx),
            kind::TRIP_ALARM => match parse_trip_alarm(&event.json) {
                Some(alarm) => self.on_alarm_event(alarm, cx),
                None => log::warn!("unreadable trip_alarm event"),
            },
            kind::STOPS_LOCATION => match parse_location_fix(&event.json) {
                Some(fix) => self.apply_fix(fix, false, cx),
                None => log::warn!("unreadable stops_location event"),
            },
            kind::PERMISSIONS_CHANGED => {
                self.refresh_preflight(cx);
                self.resume_pending_start(cx);
            }
            kind::DOCUMENT_RESULT => match serde_json::from_str::<DocumentResult>(&event.json) {
                Ok(result) if result.op == DocumentOp::ImportGtfs => {
                    self.on_feed_picked(&result, cx);
                }
                Ok(_) => {}
                Err(error) => log::warn!("bad document_result: {error}"),
            },
            _ => {}
        }
    }

    /// Whether System Back has a layer to close: a pin menu, the alarm, a sheet, a Settings page,
    /// an opened saved place, or any tab but the first (Trip). When it has not, Back leaves the app.
    pub(crate) fn can_go_back(&self) -> bool {
        self.pick.menu().is_some()
            || self.alarm.is_some()
            || self.sheet.is_open()
            || (self.tabs.selected() == 2 && self.settings_page != SettingsPage::Main)
            || self.expanded.is_some()
            || self.tabs.selected() != 0
    }

    /// System Back: one step out of the top-most layer (an open pin menu first).
    fn on_back(&mut self, cx: &mut Context<Self>) {
        if self.pick.menu().is_some() {
            self.pick.close_menu();
            cx.notify();
        } else if self.alarm.is_some() {
            self.alarm_action(AlarmAction::Snooze, cx);
        } else if self.sheet.is_open() {
            self.close_sheet(cx);
        } else if self.tabs.selected() == 2 && self.settings_page != SettingsPage::Main {
            // The WhatsApp page steps back inside itself first.
            let stepped = self.settings_page == SettingsPage::WhatsApp
                && self
                    .whatsapp
                    .update(cx, |whatsapp, cx| whatsapp.go_back(cx));
            if !stepped {
                self.open_settings(SettingsPage::Main, cx);
            }
        } else if self.expanded.is_some() {
            self.expanded = None;
            cx.notify();
        } else if self.tabs.select(0) {
            self.page.restart();
            cx.notify();
        }
    }

    // ---- files -------------------------------------------------------------------

    pub(crate) fn save_prefs(&self, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            return;
        };
        let prefs = self.prefs.clone();
        cx.background_executor()
            .spawn(async move { backend::save_prefs(&dir, &prefs) })
            .detach();
    }

    pub(crate) fn save_places(&self, cx: &mut Context<Self>) {
        let Some(dir) = self.dir.clone() else {
            return;
        };
        let saved = self.saved.clone();
        cx.background_executor()
            .spawn(async move { backend::save_places(&dir, &saved) })
            .detach();
    }

    // ---- notice pill ---------------------------------------------------------------

    pub(crate) fn show_pill(
        &mut self,
        text: impl Into<SharedString>,
        action: Option<PillAction>,
        cx: &mut Context<Self>,
    ) {
        let undo = action.is_some();
        let token = self
            .pill
            .show(text, undo.then(|| "Undo".into()), undo, false);
        self.pill_action = action.map(|a| (token, a));
        cx.notify();
    }

    fn pill_fired(&mut self, cx: &mut Context<Self>) {
        // Only the pill the button belongs to may act.
        let Some((token, action)) = self.pill_action.take() else {
            return;
        };
        if token != self.pill.token() {
            return;
        }
        match action {
            PillAction::UndoDelete(place, index) => self.undo_delete(place, index, cx),
            PillAction::RestoreHistory(history) => self.restore_history(history, cx),
        }
    }

    /// 12 px above the tab bar, or above the bottom edge over a sheet.
    fn pill_bottom(&self) -> f32 {
        const GAP: f32 = 12.0;
        if self.sheet.is_visible() {
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
            move |pointer, position, _, cx| {
                entity.update(cx, |this, cx| {
                    if this.pill.pointer(pointer, position) == PillEvent::Action {
                        this.pill_fired(cx);
                    }
                    cx.notify();
                });
            },
        )
    }
}

impl Render for StopsApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        self.pill.set_reduced(window.prefers_reduced_motion());
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame_timed(window, |dt, real| {
            self.tabs.tick(dt)
                | self.page.tick(dt)
                | self.sheet.tick(dt)
                | self.sheet.tick_real(real)
                | self.pill.tick(dt)
        });
        self.motion = motion;
        let tab = self.tabs.selected();
        let entity = cx.entity();
        let touch_entity = entity.clone();

        let body = match tab {
            0 => Page::Flow(self.trip_tab(theme, cx)),
            1 => self.saved_tab(theme, cx),
            _ => self.settings_tab(theme, window, cx),
        };
        // A Settings page brings its own back button and title.
        let titled = tab != 2 || self.settings_page == SettingsPage::Main;

        // The keyboard covers the bottom of the screen: leave room to scroll.
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
        let bottom = content_bottom_inset() + keyboard;
        let progress = self.page.progress();
        let offset = px(self.page.offset(24.0));
        let page = match body {
            Page::Flow(body) => div()
                .id("page")
                .size_full()
                .overflow_y_scroll()
                .pb(bottom)
                .opacity(progress)
                .child(
                    div()
                        .relative()
                        .top(offset)
                        .children(titled.then(|| large_title(theme, TABS[tab], None)))
                        .child(body),
                ),
            // Only the rows on screen are built; the list scrolls itself.
            Page::List(list) => div().id("page").size_full().opacity(progress).child(
                div()
                    .relative()
                    .top(offset)
                    .size_full()
                    .child((*list).size_full().pb(bottom)),
            ),
        };

        div()
            .size_full()
            .relative()
            .on_mouse_up(
                gpui::MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.tabs.release();
                    cx.notify();
                }),
            )
            .child(rovsti_ui::components::backdrop(
                theme,
                rovsti_ui::theme::colors(theme).orange,
            ))
            .child(page)
            .children(self.render_pill(theme, cx))
            .child(self.tabs.render(
                theme,
                move |index, _, cx| {
                    entity.update(cx, |this, cx| {
                        this.tabs.press();
                        if this.tabs.select(index) {
                            this.expanded = None;
                            this.page.restart();
                            this.saved_list.scroll_to_top();
                            if index == 2 {
                                this.refresh_preflight(cx);
                            }
                        }
                        cx.notify();
                    });
                },
                move |phase, _, cx| {
                    touch_entity.update(cx, |this, cx| {
                        this.tabs.touch(phase);
                        cx.notify();
                    });
                },
            ))
            .children(self.sheet_view(theme, window, cx))
            .children(self.alarm_screen(theme, cx))
    }
}

// Exports the headless JNI entry the trip service and alarm call (no UI process needed).
rovsti_android::headless_entry!(headless::handle);

#[gpui_platform::main]
fn main() {
    // Keeps the JNI entry points in the final library (see `bridge::keep_symbols`).
    rovsti_android::bridge::keep_symbols();
    // Links opened with Stops and text shared to it, as raw strings; the
    // window turns them into a confirmation or a notice.
    let (share_tx, share_rx) = mpsc::unbounded::<String>();
    let app = gpui_platform::application().with_assets(IconAssets::new());
    let link_tx = share_tx.clone();
    app.on_open_urls(move |urls| {
        for url in urls {
            let _ = link_tx.unbounded_send(url);
        }
    });
    app.on_receive_share(move |share, _| match share {
        Ok(share) => {
            if let Some(text) = share.text {
                let _ = share_tx.unbounded_send(text);
            }
        }
        Err(error) => log::warn!("could not read the shared content: {error}"),
    });
    app.run(move |cx: &mut App| {
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
                let app = cx.new(|cx| StopsApp::new(share_rx, window, cx));
                // System Back: snooze the alarm, close the sheet.
                let weak = app.downgrade();
                window.on_system_back(cx, move |_, cx| {
                    weak.update(cx, |this, cx| this.on_back(cx)).ok();
                });
                app
            },
        )
        .expect("failed to open the Rovsti Stops window");
        cx.activate(true);
    });
}
