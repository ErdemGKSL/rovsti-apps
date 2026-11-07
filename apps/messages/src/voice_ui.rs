//! The voice note player shared by the WhatsApp and SMS pages: one note plays
//! at a time, the pages draw a [`player_row`] for each audio bubble.
//!
//! Playback is the platform `MediaPlayer` behind `rovsti_android::voice`; the
//! controller polls its state every 250 ms while a note plays. The pure rules
//! (speed cycle, scrub math, time labels, what a tap does) live in
//! `rovsti_core::voice_player`.

use std::{
    cell::Cell,
    path::{Path, PathBuf},
    rc::Rc,
    time::Duration,
};

use gpui::{
    AnyElement, App, Bounds, Context, Entity, EventEmitter, Hsla, InteractiveElement, IntoElement,
    ParentElement, Pixels, SharedString, StatefulInteractiveElement, Styled, TouchPhase, div, px,
    relative,
};
use rovsti_android::{Error as AndroidError, voice};
use rovsti_core::voice_player::{self, PlayerState, Speed, TogglePlan};
use rovsti_ui::{
    Icon,
    tokens::typography,
    touch::{Pointer, pointer_surface},
};
use rovsti_wa_client::Gateway;

use crate::{backend::Backend, conversation::cache_stem, files::ext_for_mime};

/// How often the player is asked where it is while a note plays.
const POLL: Duration = Duration::from_millis(250);

const UNSUPPORTED: &str = "That needs an Android phone.";
const PLAY_FAILED: &str = "Couldn't play this recording";

/// What the controller tells the page hosting the player.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum VoiceEvent {
    /// A pill message.
    Notice(String),
    /// Playback of the note with this key began.
    Started(String),
}

/// Where a note's bytes come from.
#[derive(Clone)]
pub(crate) enum VoiceSource {
    /// A file already on the phone (an MMS part).
    File(PathBuf),
    /// A WhatsApp message fetched from the gateway into `cache/voice`.
    Gateway {
        gateway: Gateway,
        id: String,
        mime: String,
    },
}

/// What a bubble needs to draw one note.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct NoteView {
    /// This note is the one the player holds (playing, paused or just ended).
    pub active: bool,
    pub playing: bool,
    pub loading: bool,
    /// 0..=1, following the finger while it scrubs.
    pub fraction: f32,
    pub position_ms: u64,
    pub duration_ms: u64,
}

pub(crate) struct VoiceController {
    backend: Entity<Backend>,
    state: PlayerState,
    /// The file of the note in `state`, to resume it after a pause.
    path: Option<PathBuf>,
    /// The note whose bytes are being fetched.
    loading: Option<String>,
    /// A finger is on the scrub bar of this note, at this fraction.
    scrub: Option<(String, f32)>,
    polling: bool,
}

impl EventEmitter<VoiceEvent> for VoiceController {}

impl VoiceController {
    pub(crate) fn new(backend: Entity<Backend>) -> Self {
        Self {
            backend,
            state: PlayerState::default(),
            path: None,
            loading: None,
            scrub: None,
            polling: false,
        }
    }

    fn speed(&self, cx: &App) -> Speed {
        self.backend.read(cx).prefs().voice_speed
    }

    fn notice(&self, text: &str, cx: &mut Context<Self>) {
        cx.emit(VoiceEvent::Notice(text.to_owned()));
    }

    /// How note `key` looks right now; `fallback_ms` is the length the message
    /// reports before the player knows it.
    pub(crate) fn view(&self, key: &str, fallback_ms: u64) -> NoteView {
        let active = self.state.key == key;
        let loading = self.loading.as_deref() == Some(key);
        if !active {
            return NoteView {
                active: false,
                playing: false,
                loading,
                fraction: 0.0,
                position_ms: 0,
                duration_ms: fallback_ms,
            };
        }
        let duration_ms = if self.state.duration_ms > 0 {
            self.state.duration_ms
        } else {
            fallback_ms
        };
        let scrubbing = self.scrub.as_ref().filter(|(k, _)| k == key);
        let (fraction, position_ms) = match scrubbing {
            Some((_, f)) => (*f, voice_player::seek_target(*f, duration_ms)),
            None if self.state.ended => (0.0, 0),
            None => (
                voice_player::progress(self.state.position_ms, duration_ms),
                self.state.position_ms,
            ),
        };
        NoteView {
            active,
            playing: self.state.playing,
            loading,
            fraction,
            position_ms,
            duration_ms,
        }
    }

    /// The play button: pause, resume or start `key`.
    pub(crate) fn toggle(&mut self, key: String, source: VoiceSource, cx: &mut Context<Self>) {
        if self.loading.is_some() {
            return;
        }
        match voice_player::toggle_plan(&self.state, &key) {
            TogglePlan::Pause => {
                match voice::pause() {
                    Ok(_) => {}
                    Err(error) => self.fail(&error, cx),
                }
                self.refresh(cx);
            }
            TogglePlan::Resume { from_ms } => match self.path.clone() {
                Some(path) => self.start(key, path, from_ms, cx),
                None => self.begin(key, source, cx),
            },
            TogglePlan::Start => self.begin(key, source, cx),
        }
    }

    fn begin(&mut self, key: String, source: VoiceSource, cx: &mut Context<Self>) {
        // Desktop has no player: say so before downloading anything.
        if matches!(voice::state(), Err(AndroidError::Unsupported)) {
            self.notice(UNSUPPORTED, cx);
            return;
        }
        match source {
            VoiceSource::File(path) => self.start(key, path, 0, cx),
            VoiceSource::Gateway { gateway, id, mime } => {
                let Some(dir) = self
                    .backend
                    .read(cx)
                    .data_dir()
                    .map(|d| d.join("cache").join("voice"))
                else {
                    self.notice("The app's storage is not ready yet.", cx);
                    return;
                };
                let path = dir.join(format!("{}.{}", cache_stem(&id), ext_for_mime(&mime)));
                if path.is_file() {
                    self.start(key, path, 0, cx);
                    return;
                }
                self.loading = Some(key.clone());
                let target = path.clone();
                let task = cx.background_executor().spawn(async move {
                    let bytes = gateway.media(&id).map_err(|e| e.to_string())?;
                    write_cached(&target, &bytes).map_err(|e| e.to_string())
                });
                cx.spawn(async move |this, cx| {
                    let result = task.await;
                    this.update(cx, |this, cx| {
                        // Stopped or left while the bytes came.
                        if this.loading.as_deref() != Some(key.as_str()) {
                            return;
                        }
                        this.loading = None;
                        match result {
                            Ok(()) => this.start(key, path, 0, cx),
                            Err(error) => {
                                log::warn!("downloading a voice note failed: {error}");
                                this.notice("Couldn't load this recording", cx);
                            }
                        }
                        cx.notify();
                    })
                    .ok();
                })
                .detach();
            }
        }
        cx.notify();
    }

    fn start(&mut self, key: String, path: PathBuf, from_ms: u64, cx: &mut Context<Self>) {
        let speed = self.speed(cx).factor();
        match voice::play(&key, &path.to_string_lossy(), from_ms, speed) {
            Ok(true) => {
                self.path = Some(path);
                self.state = PlayerState {
                    key: key.clone(),
                    playing: true,
                    position_ms: from_ms,
                    ..PlayerState::default()
                };
                cx.emit(VoiceEvent::Started(key));
                self.poll(cx);
            }
            Ok(false) => self.notice(PLAY_FAILED, cx),
            Err(error) => self.fail(&error, cx),
        }
        cx.notify();
    }

    fn fail(&self, error: &AndroidError, cx: &mut Context<Self>) {
        let text = if matches!(error, AndroidError::Unsupported) {
            UNSUPPORTED
        } else {
            PLAY_FAILED
        };
        self.notice(text, cx);
    }

    /// Reads the player's state; an error from the player ends the note.
    fn refresh(&mut self, cx: &mut Context<Self>) {
        match voice::state() {
            Ok(json) => {
                let state = voice_player::parse_state(&json);
                if state.error.is_some() {
                    self.notice(PLAY_FAILED, cx);
                    self.state = PlayerState::default();
                    self.path = None;
                } else if !state.key.is_empty() {
                    self.state = state;
                }
            }
            Err(_) => {
                self.state = PlayerState::default();
                self.path = None;
            }
        }
        cx.notify();
    }

    fn poll(&mut self, cx: &mut Context<Self>) {
        if self.polling {
            return;
        }
        self.polling = true;
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor().timer(POLL).await;
                let again = this
                    .update(cx, |this, cx| {
                        this.refresh(cx);
                        let again = this.state.playing;
                        if !again {
                            this.polling = false;
                        }
                        again
                    })
                    .unwrap_or(false);
                if !again {
                    break;
                }
            }
        })
        .detach();
    }

    /// Moves the active note `key` to `fraction` of its length.
    pub(crate) fn seek_fraction(&mut self, key: &str, fraction: f32, cx: &mut Context<Self>) {
        if self.state.key != key {
            return;
        }
        let duration = self.state.duration_ms;
        if duration == 0 {
            return;
        }
        let ms = voice_player::seek_target(fraction, duration);
        match voice::seek(ms) {
            Ok(_) => {
                self.state.position_ms = ms;
                self.state.ended = false;
            }
            Err(error) => self.fail(&error, cx),
        }
        cx.notify();
    }

    /// A finger came down on (or moved along) the scrub bar of `key`.
    fn scrub_to(&mut self, key: &str, fraction: f32, cx: &mut Context<Self>) {
        self.scrub = Some((key.to_owned(), fraction.clamp(0.0, 1.0)));
        cx.notify();
    }

    /// The finger lifted: seek to where it was.
    fn scrub_end(&mut self, key: &str, cx: &mut Context<Self>) {
        if let Some((k, fraction)) = self.scrub.take()
            && k == key
        {
            self.seek_fraction(key, fraction, cx);
        }
        cx.notify();
    }

    /// The finger was taken over by something else (a scroll): forget it.
    fn scrub_cancel(&mut self, cx: &mut Context<Self>) {
        if self.scrub.take().is_some() {
            cx.notify();
        }
    }

    /// The speed capsule: next speed, remembered, applied to the playing note.
    pub(crate) fn cycle_speed(&mut self, cx: &mut Context<Self>) {
        let next = self.speed(cx).next();
        self.backend.update(cx, |backend, cx| {
            backend.update_prefs(cx, |p| p.voice_speed = next)
        });
        if !self.state.key.is_empty()
            && let Err(error) = voice::set_speed(next.factor())
        {
            self.fail(&error, cx);
        }
        cx.notify();
    }

    /// Stops and releases the player (page left, app paused).
    pub(crate) fn stop(&mut self, cx: &mut Context<Self>) {
        let busy = !self.state.key.is_empty() || self.loading.is_some();
        self.loading = None;
        self.scrub = None;
        if busy {
            voice::stop().ok();
            self.state = PlayerState::default();
            self.path = None;
            cx.notify();
        }
    }
}

fn write_cached(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    // Write beside, then rename: a half-written file is never played.
    let partial = path.with_extension("part");
    std::fs::write(&partial, bytes)?;
    std::fs::rename(partial, path)
}

/// The fraction of the bar's width a window x position is at.
fn fraction_at(bounds: Bounds<Pixels>, x: Pixels) -> Option<f32> {
    let width = f32::from(bounds.size.width);
    (width > 0.0).then(|| ((f32::from(x) - f32::from(bounds.origin.x)) / width).clamp(0.0, 1.0))
}

/// The player row of one audio bubble: play/pause, scrub bar, time and (for
/// the active note) the speed capsule. `id` is unique per bubble.
#[allow(clippy::too_many_arguments)]
pub(crate) fn player_row(
    voice: &Entity<VoiceController>,
    id: &str,
    key: String,
    source: VoiceSource,
    fallback_ms: u64,
    speed: Speed,
    text: Hsla,
    quiet: Hsla,
    cx: &App,
) -> AnyElement {
    let view = voice.read(cx).view(&key, fallback_ms);
    let icon = if view.playing {
        Icon::LucidePause
    } else {
        Icon::LucidePlay
    };
    let play = {
        let voice = voice.clone();
        let key = key.clone();
        div()
            .id(SharedString::from(format!("voice-play-{id}")))
            .flex_none()
            .size(px(36.0))
            .rounded_full()
            .bg(text.opacity(0.14))
            .flex()
            .items_center()
            .justify_center()
            .child(if view.loading {
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(quiet)
                    .child("…")
                    .into_any_element()
            } else {
                gpui::svg()
                    .path(icon.path())
                    .size(px(18.0))
                    .text_color(text)
                    .into_any_element()
            })
            .on_click(move |_, _, cx| {
                cx.stop_propagation();
                voice.update(cx, |v, cx| v.toggle(key.clone(), source.clone(), cx));
            })
    };

    let bounds = Rc::new(Cell::new(Bounds::<Pixels>::default()));
    let mut bar = div()
        .relative()
        .flex_1()
        .min_w(px(80.0))
        .h(px(28.0))
        .flex()
        .items_center()
        .child(
            div()
                .w_full()
                .h(px(4.0))
                .rounded_full()
                .bg(text.opacity(0.2))
                .child(
                    div()
                        .h_full()
                        .w(relative(view.fraction))
                        .rounded_full()
                        .bg(text),
                ),
        );
    if view.active {
        let knob = (view.fraction.clamp(0.0, 1.0) * 100.0).round() / 100.0;
        bar = bar.child(
            div()
                .absolute()
                .left(relative(knob))
                .ml(px(-5.0))
                .size(px(10.0))
                .rounded_full()
                .bg(text),
        );
        let voice = voice.clone();
        let key = key.clone();
        let cell = Rc::clone(&bounds);
        // Only the note being played can be scrubbed.
        bar = bar.child(pointer_surface(bounds, move |pointer, position, _, cx| {
            let fraction = fraction_at(cell.get(), position.x);
            voice.update(cx, |v, cx| match (pointer, fraction) {
                (Pointer::Touch(TouchPhase::Started | TouchPhase::Moved), Some(f)) => {
                    // A move that began elsewhere (a list scroll) is not ours.
                    let ours = matches!(pointer, Pointer::Touch(TouchPhase::Started))
                        || v.scrub.as_ref().is_some_and(|(k, _)| *k == key);
                    if ours {
                        v.scrub_to(&key, f, cx);
                    }
                }
                (Pointer::Touch(TouchPhase::Ended), _) => v.scrub_end(&key, cx),
                (Pointer::Touch(TouchPhase::Cancelled), _) => v.scrub_cancel(cx),
                (Pointer::Click, Some(f)) => v.seek_fraction(&key, f, cx),
                _ => {}
            });
        }));
    }

    let label = voice_player::time_label(view.position_ms, view.duration_ms, view.playing);
    let mut row = div()
        .flex()
        .flex_row()
        .items_center()
        .gap(px(8.0))
        .w(px(250.0))
        .child(play)
        .child(bar)
        .child(
            div()
                .flex_none()
                .text_size(typography::CAPTION.size)
                .text_color(quiet)
                .child(label),
        );
    if view.active {
        let voice = voice.clone();
        row = row.child(
            div()
                .id(SharedString::from(format!("voice-speed-{id}")))
                .flex_none()
                .px(px(8.0))
                .py(px(2.0))
                .rounded_full()
                .bg(text.opacity(0.14))
                .text_size(typography::CAPTION.size)
                .text_color(text)
                .child(speed.label())
                .on_click(move |_, _, cx| {
                    cx.stop_propagation();
                    voice.update(cx, |v, cx| v.cycle_speed(cx));
                }),
        );
    }
    row.into_any_element()
}
