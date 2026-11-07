//! The full-screen picture viewer for WhatsApp images (utilities M1, M2).
//!
//! An overlay above the open chat. It opens on the picture the bubble already
//! holds, then swaps in the full-size picture fetched and decoded on the
//! background executor. Pinch and double-tap zoom, drag to pan, swipe down to
//! close, a tap hides the chrome, and the bottom bar saves to the gallery or
//! shares. Modelled on the Phone app's photo viewer; the maths lives in
//! `rovsti_core::zoom`.

use std::{
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

use gpui::{
    AnyElement, Context, Image, InteractiveElement, IntoElement, KeyDownEvent, MouseButton,
    MouseDownEvent, MouseMoveEvent, MouseUpEvent, ObjectFit, ParentElement, PinchEvent,
    RenderImage, Role, SharedString, StatefulInteractiveElement, Styled, StyledImage, TouchPhase,
    Window, canvas, div, px,
};
use rovsti_core::zoom::{self, Point, Size, ZoomView};
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    motion_tokens,
    spring::Spring,
    theme,
    tokens::{Theme, shadow, typography},
    touch::{TAP_SLOP, TapGuard, drag_surface},
};
use rovsti_wa_client::{Gateway, Picture};

use crate::{MessagesApp, conversation::cache_stem, files::ext_for_mime, forward_ui::ForwardItem};
use rovsti_core::forward::ForwardBody;

/// Height of the strip at the top and bottom where the chrome sits; a finger
/// that lands there belongs to the buttons, not to the picture.
const CHROME_ZONE: f32 = 120.0;
/// A second tap within this long, and this close, is a double tap.
const DOUBLE_TAP: Duration = Duration::from_millis(300);
const DOUBLE_TAP_SLOP: f32 = 48.0;
/// A pause this long before the lift forgets the flick speed.
const STALE_SAMPLE: Duration = Duration::from_millis(80);
/// The longest side a viewed picture is decoded at.
const MAX_PX: u32 = 4096;
/// The longest side asked of the address book for a contact's photo.
const PHOTO_PX: u32 = 1024;

static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);

/// The rectangle `(x, y, w, h)` the image is drawn in, in viewport pixels: the
/// aspect-fitted size times the scale, centred, shifted by the view's offset.
pub(crate) fn image_rect(view: ZoomView, natural: Size, viewport: Size) -> (f32, f32, f32, f32) {
    let fitted = zoom::fit(natural, viewport);
    let (w, h) = (fitted.w * view.scale, fitted.h * view.scale);
    (
        (viewport.w - w) / 2.0 + view.offset.x,
        (viewport.h - h) / 2.0 + view.offset.y,
        w,
        h,
    )
}

/// A file name for the gallery: `WhatsApp-Ada-20261107-101500.jpg`. The time
/// is UTC (only there to keep names apart); the extension follows `mime`
/// (`jpg` when it is not a known type).
#[cfg(test)]
fn save_name(title: &str, mime: &str, at_ms: i64) -> String {
    branded_name("WhatsApp", title, mime, at_ms)
}

/// The gallery name of a profile photo: `Ada-Lovelace-photo.jpg`. The
/// extension follows `mime` (`jpg` when it is not a known type).
pub(crate) fn photo_name(title: &str, mime: &str) -> String {
    let ext = match ext_for_mime(mime) {
        "bin" => "jpg",
        ext => ext,
    };
    format!(
        "{}-photo.{ext}",
        rovsti_core::number_format::file_slug(title)
    )
}

/// [`save_name`] with the file name's first word chosen by where the picture came from.
fn branded_name(brand: &str, title: &str, mime: &str, at_ms: i64) -> String {
    let (y, m, d) = rovsti_wa_proto::presence::civil_from_days(at_ms.div_euclid(86_400_000));
    let secs = at_ms.rem_euclid(86_400_000) / 1000;
    let stamp = format!(
        "{y:04}{m:02}{d:02}-{:02}{:02}{:02}",
        secs / 3600,
        secs % 3600 / 60,
        secs % 60
    );
    let ext = match ext_for_mime(mime) {
        "bin" => "jpg",
        ext => ext,
    };
    match rovsti_core::number_format::file_slug_opt(title) {
        Some(stem) => format!("{brand}-{stem}-{stamp}.{ext}"),
        None => format!("{brand}-{stamp}.{ext}"),
    }
}

/// Where a viewed picture comes from.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum ViewerSource {
    /// A WhatsApp message's media, fetched through the gateway.
    WaMedia { message_id: String },
    /// A file on the phone (MMS pictures).
    File { path: PathBuf },
    /// A WhatsApp profile picture (full size), fetched through the gateway.
    WaPicture { jid: String },
    /// An address-book photo, read through Android's content providers.
    ContactPhoto { uri: String },
}

impl ViewerSource {
    /// A profile photo rather than a message's picture.
    pub(crate) fn is_profile(&self) -> bool {
        matches!(self, Self::WaPicture { .. } | Self::ContactPhoto { .. })
    }
}

/// Why a picture could not be shown.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum LoadError {
    Failed,
    /// The person has no profile photo (or hides it).
    NoPhoto,
}

/// One picture in the viewer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ViewerItem {
    pub key: String,
    /// Shown in the top bar and used in the saved file's name.
    pub title: SharedString,
    pub mime: String,
    pub source: ViewerSource,
    /// When the picture was sent (the saved file's name).
    pub at_ms: i64,
    /// The picture already on screen, shown while the full one loads.
    pub placeholder: Option<Arc<Image>>,
    /// An already decoded picture (an avatar) shown while the full one loads.
    pub avatar: Option<Arc<RenderImage>>,
}

/// How far the full picture has got.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Load {
    Loading,
    Failed,
}

/// What a finger lifting did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Release {
    Nothing,
    /// The finger barely moved: a tap.
    Tap,
    /// Swiped down far or fast enough: close.
    Close,
    /// Swiped to another picture: the caller loads it.
    Page,
}

/// Which way a drag has turned out to go.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Axis {
    Undecided,
    Horizontal,
    Vertical,
}

/// A decoded neighbour kept for the next swipe.
type Decoded = (String, Arc<RenderImage>, PathBuf);

struct Drag {
    start: Point,
    last: Point,
    at: Instant,
    velocity: f32,
    /// Horizontal flick speed (px/s), for paging.
    vx: f32,
    moved: f32,
    axis: Axis,
}

pub(crate) struct MediaViewer {
    pub items: Vec<ViewerItem>,
    pub index: usize,
    /// The bubble's picture, drawn until the full one is decoded.
    placeholder: Option<Arc<Image>>,
    /// The avatar already on screen, drawn until the full photo is decoded.
    avatar: Option<Arc<RenderImage>>,
    /// The decoded full picture and the file it came from (what Save and
    /// Share send).
    pub full: Option<(Arc<RenderImage>, PathBuf)>,
    pub load: Load,
    /// Pixel size of the full picture (only its aspect matters).
    natural: Size,
    /// The gesture's own view; the springs below chase it.
    pub view: ZoomView,
    /// Decoded neighbours (the picture just left, the one ahead).
    cache: Vec<Decoded>,
    /// The neighbour being decoded in the background, so it is asked once.
    prefetching: Option<String>,
    /// Which way the last swipe went (1 forward), for choosing the neighbour to prefetch.
    heading: i8,
    /// Horizontal shift of the picture while paging.
    page: Spring,
    /// Where `page` was when the finger went down.
    page_base: f32,
    /// Identifies this opening and item, so a late load cannot land elsewhere.
    pub token: u64,
    drag: Option<Drag>,
    /// How far the finger pulled the picture down while not zoomed.
    dismiss_dy: f32,
    pinching: bool,
    chrome_on: bool,
    last_tap: Option<(Point, Instant)>,
    closing: bool,
    /// A raw touch has been seen, so mouse events are the synthesised taps.
    touch_seen: bool,
    guard: TapGuard,
    /// Click count of the mouse press being held (desktop path).
    pending_clicks: usize,
    /// The window as of the last frame, for closing from Back.
    env: (Size, bool),
    scale: Spring,
    ox: Spring,
    oy: Spring,
    dismiss: Spring,
    chrome: Spring,
    enter: Spring,
}

fn size_of(image: &RenderImage) -> Size {
    let px = image.size(0);
    Size {
        w: px.width.0 as f32,
        h: px.height.0 as f32,
    }
}

impl MediaViewer {
    /// Opens on `items[index]`; without a picture there is nothing to view.
    pub(crate) fn open(items: Vec<ViewerItem>, index: usize) -> Option<Self> {
        let current = items.get(index)?;
        let placeholder = current.placeholder.clone();
        let avatar = current.avatar.clone();
        let tween = |value| Spring::new(value, motion_tokens::appear()).with_epsilon(0.002);
        let pixels = |value| Spring::new(value, motion_tokens::appear()).with_epsilon(0.05);
        let mut enter = tween(0.0);
        enter.set_target(1.0);
        Some(Self {
            items,
            index,
            placeholder,
            avatar,
            full: None,
            load: Load::Loading,
            natural: Size { w: 1.0, h: 1.0 },
            view: ZoomView::IDENTITY,
            cache: Vec::new(),
            prefetching: None,
            heading: 1,
            page: Spring::new(0.0, motion_tokens::release()).with_epsilon(0.05),
            page_base: 0.0,
            token: NEXT_TOKEN.fetch_add(1, Ordering::Relaxed),
            drag: None,
            dismiss_dy: 0.0,
            pinching: false,
            chrome_on: true,
            last_tap: None,
            closing: false,
            touch_seen: false,
            guard: TapGuard::default(),
            pending_clicks: 1,
            env: (Size { w: 400.0, h: 800.0 }, false),
            scale: tween(1.0),
            ox: pixels(0.0),
            oy: pixels(0.0),
            dismiss: Spring::new(0.0, motion_tokens::release()).with_epsilon(0.05),
            chrome: tween(1.0),
            enter,
        })
    }

    pub(crate) fn current(&self) -> &ViewerItem {
        &self.items[self.index.min(self.items.len() - 1)]
    }

    /// The full picture arrived: same picture, more pixels.
    pub(crate) fn set_full(&mut self, image: Arc<RenderImage>, file: PathBuf) {
        let natural = size_of(&image);
        if natural.w > 0.0 && natural.h > 0.0 {
            self.natural = natural;
            self.full = Some((image, file));
        } else {
            self.load = Load::Failed;
        }
    }

    /// The current picture as something to forward, once its full file is
    /// loaded (so any source, profile photos included, is a local file).
    pub(crate) fn forward_item(&self) -> Option<ForwardItem> {
        let (_, path) = self.full.as_ref()?;
        let mut picture = self.current().clone();
        picture.source = ViewerSource::File { path: path.clone() };
        Some(ForwardItem {
            body: ForwardBody::Picture { caption: None },
            picture: Some(picture),
        })
    }

    /// "3 of 7" for the top bar; `None` for a lone picture.
    pub(crate) fn caption(&self) -> Option<String> {
        zoom::page_caption(self.index, self.items.len())
    }

    /// Horizontal shift of the picture and the placeholder (paging).
    pub(crate) fn page_shift(&self) -> f32 {
        self.page.value
    }

    /// Keeps a decoded neighbour. When it is the picture the viewer is
    /// waiting on, it is shown at once.
    pub(crate) fn cache_put(&mut self, key: &str, image: Arc<RenderImage>, file: PathBuf) {
        if self.full.is_none() && self.current().key == key {
            self.set_full(image, file);
            return;
        }
        self.cache.retain(|(k, ..)| k != key);
        self.cache.push((key.to_owned(), image, file));
        self.prune();
    }

    /// Drops decoded pictures that are no longer a neighbour.
    fn prune(&mut self) {
        let near = |key: &str| {
            let at = self.items.iter().position(|i| i.key == key);
            at.is_some_and(|at| at.abs_diff(self.index) == 1)
        };
        let keep: Vec<bool> = self.cache.iter().map(|(k, ..)| near(k)).collect();
        let mut keep = keep.into_iter();
        self.cache.retain(|_| keep.next().unwrap_or(false));
    }

    /// The neighbour to decode ahead of a swipe (the one in the direction of
    /// travel first), once.
    pub(crate) fn next_prefetch(&mut self) -> Option<ViewerItem> {
        if self.prefetching.is_some() {
            return None;
        }
        let ahead = self.index as i64 + i64::from(self.heading);
        let behind = self.index as i64 - i64::from(self.heading);
        let item = [ahead, behind]
            .into_iter()
            .filter_map(|at| usize::try_from(at).ok())
            .filter_map(|at| self.items.get(at))
            .find(|item| !self.cache.iter().any(|(k, ..)| *k == item.key))?
            .clone();
        self.prefetching = Some(item.key.clone());
        Some(item)
    }

    /// Moves to page `to`: the new picture slides in from the side the old one left to.
    fn turn(&mut self, to: usize, width: f32, reduced: bool) {
        let from = self.index;
        let shift = self.page.value + if to > from { width } else { -width };
        if let Some((image, file)) = self.full.take() {
            let key = self.current().key.clone();
            self.cache.retain(|(k, ..)| *k != key);
            self.cache.push((key, image, file));
        }
        self.heading = if to > from { 1 } else { -1 };
        self.index = to;
        self.prefetching = None;
        self.token = NEXT_TOKEN.fetch_add(1, Ordering::Relaxed);
        self.placeholder = self.current().placeholder.clone();
        self.avatar = self.current().avatar.clone();
        self.load = Load::Loading;
        self.natural = Size { w: 1.0, h: 1.0 };
        self.view = ZoomView::IDENTITY;
        self.snap_view();
        self.dismiss_dy = 0.0;
        self.last_tap = None;
        let key = self.current().key.clone();
        if let Some(at) = self.cache.iter().position(|(k, ..)| *k == key) {
            let (_, image, file) = self.cache.remove(at);
            self.set_full(image, file);
        }
        self.prune();
        self.page.snap(shift);
        if reduced {
            self.page.snap(0.0);
        } else {
            self.page.set_target(0.0);
        }
    }

    /// Pinch and double-tap zoom need the full picture's real aspect.
    fn zooms(&self) -> bool {
        self.full.is_some()
    }

    /// The view being drawn this frame (the springs' values).
    pub(crate) fn shown(&self) -> ZoomView {
        ZoomView {
            scale: self.scale.value,
            offset: Point {
                x: self.ox.value,
                y: self.oy.value,
            },
        }
    }

    /// Where the picture is drawn: the whole viewport for the placeholder (it
    /// is contained inside it), the zoomed rectangle for the full picture.
    pub(crate) fn rect(&self, viewport: Size) -> (f32, f32, f32, f32) {
        if self.full.is_none() {
            return (0.0, 0.0, viewport.w, viewport.h);
        }
        image_rect(self.shown(), self.natural, viewport)
    }

    pub(crate) fn chrome_visible(&self) -> bool {
        self.chrome.value > 0.01
    }

    fn chrome_alpha(&self) -> f32 {
        self.chrome.value.clamp(0.0, 1.0) * self.enter.value.clamp(0.0, 1.0)
    }

    /// Opacity of the whole overlay (the open and close fade).
    fn enter_alpha(&self) -> f32 {
        self.enter.value.clamp(0.0, 1.0)
    }

    /// How far the background has faded from a swipe down, 0 to 1.
    fn dismiss_fade(&self) -> f32 {
        zoom::dismiss_progress(self.dismiss.value)
    }

    fn dismiss_offset(&self) -> f32 {
        self.dismiss.value
    }

    pub(crate) fn is_closing(&self) -> bool {
        self.closing
    }

    /// Whether `pos` is on a chrome bar (a finger there is a button press).
    pub(crate) fn in_chrome(&self, pos: Point, viewport: Size) -> bool {
        self.chrome_on && (pos.y < CHROME_ZONE || pos.y > viewport.h - CHROME_ZONE)
    }

    fn fitted(&self, viewport: Size) -> Size {
        zoom::fit(self.natural, viewport)
    }

    /// A gesture starts from what is on screen, even mid-animation.
    fn adopt(&mut self) {
        self.view = self.shown();
        self.dismiss_dy = self.dismiss.value;
    }

    fn snap_view(&mut self) {
        self.scale.snap(self.view.scale);
        self.ox.snap(self.view.offset.x);
        self.oy.snap(self.view.offset.y);
    }

    fn glide_to(&mut self, target: ZoomView, reduced: bool) {
        self.view = target;
        if reduced {
            self.snap_view();
        } else {
            self.scale.set_target(target.scale);
            self.ox.set_target(target.offset.x);
            self.oy.set_target(target.offset.y);
        }
    }

    pub(crate) fn pinch(&mut self, delta: f32, focus: Point, viewport: Size) {
        if !self.zooms() {
            return;
        }
        if !self.pinching {
            self.adopt();
            self.pinching = true;
            self.drag = None;
        }
        self.view.pinch(delta, focus, viewport);
        self.snap_view();
    }

    pub(crate) fn pinch_end(&mut self, viewport: Size, reduced: bool) {
        if self.pinching {
            self.pinching = false;
            let target = self.view.settled(self.fitted(viewport), viewport);
            self.glide_to(target, reduced);
        }
    }

    /// A finger (or the mouse) went down.
    pub(crate) fn press(&mut self, pos: Point, now: Instant) {
        if self.pinching || self.closing {
            return;
        }
        self.adopt();
        self.page_base = self.page.value;
        self.page.snap(self.page_base);
        self.drag = Some(Drag {
            start: pos,
            last: pos,
            at: now,
            velocity: 0.0,
            vx: 0.0,
            moved: 0.0,
            axis: Axis::Undecided,
        });
    }

    /// The finger moved: pan when zoomed; else page sideways or pull down.
    pub(crate) fn drag(&mut self, pos: Point, viewport: Size, now: Instant) {
        let fitted = self.fitted(viewport);
        let zoomed = self.view.is_zoomed();
        let pages = self.items.len() > 1;
        let Some(d) = &mut self.drag else {
            return;
        };
        let (dx, dy) = (pos.x - d.last.x, pos.y - d.last.y);
        d.moved = (pos.x - d.start.x).hypot(pos.y - d.start.y);
        let elapsed = now.saturating_duration_since(d.at).as_secs_f32().max(0.004);
        d.velocity = d.velocity * 0.6 + (dy / elapsed) * 0.4;
        d.vx = d.vx * 0.6 + (dx / elapsed) * 0.4;
        d.last = pos;
        d.at = now;
        let (total_x, total_y) = (pos.x - d.start.x, pos.y - d.start.y);
        let mut just_locked = false;
        if !zoomed && d.axis == Axis::Undecided && d.moved > TAP_SLOP {
            d.axis = if pages && total_x.abs() > total_y.abs() {
                Axis::Horizontal
            } else {
                Axis::Vertical
            };
            just_locked = true;
        }
        let axis = d.axis;
        if zoomed {
            self.view.pan(dx, dy, fitted, viewport);
            self.snap_view();
        } else if axis == Axis::Horizontal {
            let shown = zoom::page_drag(self.page_base + total_x, self.index, self.items.len());
            self.page.snap(shown);
        } else if axis == Axis::Vertical {
            let pull = if just_locked { total_y } else { dy };
            self.dismiss_dy = (self.dismiss_dy + pull).max(0.0);
            self.dismiss.snap(self.dismiss_dy);
        }
    }

    /// The finger lifted.
    pub(crate) fn release(&mut self, viewport: Size, now: Instant, reduced: bool) -> Release {
        let Some(d) = self.drag.take() else {
            return Release::Nothing;
        };
        if self.view.is_zoomed() {
            let target = self.view.settled(self.fitted(viewport), viewport);
            self.glide_to(target, reduced);
            return if d.moved <= TAP_SLOP {
                Release::Tap
            } else {
                Release::Nothing
            };
        }
        let fresh = now.saturating_duration_since(d.at) <= STALE_SAMPLE;
        if d.axis == Axis::Horizontal {
            let vx = if fresh { d.vx } else { 0.0 };
            let shown = self.page.value;
            let to = zoom::page_after_release(self.index, self.items.len(), shown, vx, viewport.w);
            if to == self.index {
                if reduced {
                    self.page.snap(0.0);
                } else {
                    self.page.set_target(0.0);
                }
                return Release::Nothing;
            }
            self.turn(to, viewport.w, reduced);
            return Release::Page;
        }
        let velocity = if fresh { d.velocity } else { 0.0 };
        if zoom::should_dismiss(self.dismiss_dy, velocity) {
            return Release::Close;
        }
        self.dismiss_dy = 0.0;
        if reduced {
            self.dismiss.snap(0.0);
        } else {
            self.dismiss.set_target(0.0);
        }
        if d.moved <= TAP_SLOP {
            Release::Tap
        } else {
            Release::Nothing
        }
    }

    /// A tap landed. `click_count` is the host's count; a second tap close to
    /// the first in time and place counts as a double tap whatever it says.
    pub(crate) fn tap(
        &mut self,
        pos: Point,
        click_count: usize,
        now: Instant,
        viewport: Size,
        reduced: bool,
    ) {
        let near = self.last_tap.is_some_and(|(p, at)| {
            now.saturating_duration_since(at) < DOUBLE_TAP
                && (p.x - pos.x).hypot(p.y - pos.y) < DOUBLE_TAP_SLOP
        });
        if click_count >= 2 || near {
            // The first tap hid or showed the chrome: the second puts it back.
            if self.last_tap.take().is_some() {
                self.toggle_chrome(reduced);
            }
            if !self.zooms() {
                return;
            }
            self.adopt();
            let target = self
                .view
                .double_tap(pos, viewport)
                .settled(self.fitted(viewport), viewport);
            self.glide_to(target, reduced);
        } else {
            self.last_tap = Some((pos, now));
            self.toggle_chrome(reduced);
        }
    }

    pub(crate) fn toggle_chrome(&mut self, reduced: bool) {
        self.chrome_on = !self.chrome_on;
        let target = f32::from(u8::from(self.chrome_on));
        if reduced {
            self.chrome.snap(target);
        } else {
            self.chrome.set_target(target);
        }
    }

    /// Starts closing: the picture slides away and everything fades.
    pub(crate) fn close(&mut self, viewport: Size, reduced: bool) {
        if self.closing {
            return;
        }
        self.closing = true;
        self.drag = None;
        if reduced {
            self.enter.snap(0.0);
            self.dismiss.snap(viewport.h * 0.25);
            self.chrome.snap(0.0);
        } else {
            self.enter.set_target(0.0);
            self.dismiss
                .retarget(viewport.h * 0.25, motion_tokens::appear());
            self.chrome.set_target(0.0);
        }
    }

    pub(crate) fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for spring in [
            &mut self.scale,
            &mut self.ox,
            &mut self.oy,
            &mut self.page,
            &mut self.dismiss,
            &mut self.chrome,
            &mut self.enter,
        ] {
            moving |= spring.tick(dt);
        }
        moving
    }

    /// Closed and faded out: drop it.
    pub(crate) fn finished(&self) -> bool {
        self.closing && self.enter.is_settled() && self.dismiss.is_settled()
    }
}

/// Reads or downloads the picture and decodes it. Blocking: run it on the
/// background executor. A WhatsApp picture is kept in `dir` (the file Save and
/// Share send).
fn load_blocking(
    item: &ViewerItem,
    gateway: Option<&Gateway>,
    dir: &Path,
) -> Result<(Arc<RenderImage>, PathBuf), LoadError> {
    let (bytes, path) = match &item.source {
        ViewerSource::WaMedia { message_id } => {
            let bytes = gateway
                .ok_or(LoadError::Failed)?
                .media(message_id)
                .map_err(|error| {
                    log::warn!("downloading a picture failed: {error}");
                    LoadError::Failed
                })?;
            let name = format!("{}.{}", cache_stem(message_id), ext_for_mime(&item.mime));
            let path = write_cached(dir, &name, &bytes)?;
            (bytes, path)
        }
        ViewerSource::File { path } => (
            std::fs::read(path).map_err(|_| LoadError::Failed)?,
            path.clone(),
        ),
        ViewerSource::WaPicture { jid } => {
            let picture = gateway
                .ok_or(LoadError::Failed)?
                .picture(jid, true, None)
                .map_err(|error| {
                    log::warn!("downloading a profile photo failed: {error}");
                    LoadError::Failed
                })?;
            let Picture::Image { id, bytes } = picture else {
                return Err(match picture {
                    Picture::None => LoadError::NoPhoto,
                    _ => LoadError::Failed,
                });
            };
            let name = format!(
                "photo-{}-{}.{}",
                cache_stem(jid),
                cache_stem(&id),
                ext_for_mime(&item.mime)
            );
            let path = write_cached(dir, &name, &bytes)?;
            (bytes, path)
        }
        ViewerSource::ContactPhoto { uri } => {
            let path = match rovsti_android::providers::contact_photo(uri, PHOTO_PX) {
                Ok(path) if path.is_empty() => return Err(LoadError::NoPhoto),
                Ok(path) => PathBuf::from(path),
                Err(error) => {
                    log::warn!("reading a contact photo failed: {error}");
                    return Err(LoadError::Failed);
                }
            };
            (std::fs::read(&path).map_err(|_| LoadError::Failed)?, path)
        }
    };
    let image = rovsti_wa_ui::decode_image(&bytes, MAX_PX).ok_or(LoadError::Failed)?;
    Ok((image, path))
}

/// Writes `bytes` to `dir/name` (creating `dir`) and returns the path.
fn write_cached(dir: &Path, name: &str, bytes: &[u8]) -> Result<PathBuf, LoadError> {
    std::fs::create_dir_all(dir).map_err(|_| LoadError::Failed)?;
    let path = dir.join(name);
    std::fs::write(&path, bytes).map_err(|_| LoadError::Failed)?;
    Ok(path)
}

fn point_of(p: gpui::Point<gpui::Pixels>) -> Point {
    Point {
        x: f32::from(p.x),
        y: f32::from(p.y),
    }
}

fn viewport_of(window: &Window) -> Size {
    let size = window.viewport_size();
    Size {
        w: f32::from(size.width),
        h: f32::from(size.height),
    }
}

impl MessagesApp {
    /// Opens the viewer on `items[index]` and loads its full picture.
    pub(crate) fn open_media_viewer(
        &mut self,
        items: Vec<ViewerItem>,
        index: usize,
        cx: &mut Context<Self>,
    ) {
        let Some(viewer) = MediaViewer::open(items, index) else {
            return;
        };
        self.media_viewer = Some(viewer);
        self.load_viewer_item(cx);
        cx.notify();
    }

    /// The gateway and the folder pictures are fetched with and kept in.
    fn viewer_loader(&self, cx: &Context<Self>) -> (Option<Gateway>, PathBuf) {
        let gateway = self.whatsapp.read(cx).gateway();
        let dir = self.backend.read(cx).data_dir().map_or_else(
            || std::env::temp_dir().join("rovsti-viewer"),
            |dir| dir.join("cache").join("viewer"),
        );
        (gateway, dir)
    }

    /// Fetches and decodes the current item off the UI thread (nothing to do
    /// when a swipe already brought it decoded), then warms the neighbour.
    pub(crate) fn load_viewer_item(&mut self, cx: &mut Context<Self>) {
        let (gateway, dir) = self.viewer_loader(cx);
        let Some(viewer) = &mut self.media_viewer else {
            return;
        };
        if viewer.full.is_some() {
            self.prefetch_neighbour(cx);
            cx.notify();
            return;
        }
        viewer.load = Load::Loading;
        let (token, item) = (viewer.token, viewer.current().clone());
        let task = cx
            .background_executor()
            .spawn(async move { load_blocking(&item, gateway.as_ref(), &dir) });
        cx.spawn(async move |this, cx| {
            let loaded = task.await;
            this.update(cx, |this, cx| {
                if let Some(viewer) = &mut this.media_viewer
                    && viewer.token == token
                {
                    match loaded {
                        Ok((image, path)) => viewer.set_full(image, path),
                        Err(LoadError::NoPhoto) => {
                            this.media_viewer = None;
                            this.show_pill("No profile photo");
                            cx.notify();
                            return;
                        }
                        Err(LoadError::Failed) => viewer.load = Load::Failed,
                    }
                    this.prefetch_neighbour(cx);
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Decodes the picture a swipe is most likely to land on next.
    fn prefetch_neighbour(&mut self, cx: &mut Context<Self>) {
        let (gateway, dir) = self.viewer_loader(cx);
        let Some(item) = self
            .media_viewer
            .as_mut()
            .and_then(MediaViewer::next_prefetch)
        else {
            return;
        };
        let task = cx.background_executor().spawn({
            let item = item.clone();
            async move { load_blocking(&item, gateway.as_ref(), &dir) }
        });
        cx.spawn(async move |this, cx| {
            let loaded = task.await;
            this.update(cx, |this, cx| {
                if let (Some(viewer), Ok((image, path))) = (&mut this.media_viewer, loaded) {
                    viewer.cache_put(&item.key, image, path);
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn close_media_viewer(&mut self, window: &Window, cx: &mut Context<Self>) {
        let (viewport, reduced) = (viewport_of(window), window.prefers_reduced_motion());
        if let Some(viewer) = &mut self.media_viewer {
            viewer.close(viewport, reduced);
        }
        cx.notify();
    }

    /// System Back: closes the viewer. `false` when none is open.
    pub(crate) fn back_media_viewer(&mut self, cx: &mut Context<Self>) -> bool {
        let Some(viewer) = self.media_viewer.as_mut().filter(|v| !v.is_closing()) else {
            return false;
        };
        let (viewport, reduced) = viewer.env;
        viewer.close(viewport, reduced);
        cx.notify();
        true
    }

    /// The file and item Save and Share send, once the full picture exists.
    fn viewer_target(&self) -> Option<(PathBuf, ViewerItem)> {
        let viewer = self.media_viewer.as_ref()?;
        let (_, path) = viewer.full.as_ref()?;
        Some((path.clone(), viewer.current().clone()))
    }

    fn viewer_save(&mut self, cx: &mut Context<Self>) {
        let Some((path, item)) = self.viewer_target() else {
            return;
        };
        let name = match item.source {
            ViewerSource::WaMedia { .. } => {
                branded_name("WhatsApp", &item.title, &item.mime, item.at_ms)
            }
            ViewerSource::File { .. } => branded_name("MMS", &item.title, &item.mime, item.at_ms),
            ViewerSource::WaPicture { .. } | ViewerSource::ContactPhoto { .. } => {
                photo_name(&item.title, &item.mime)
            }
        };
        let task = cx.background_executor().spawn(async move {
            rovsti_android::media::save_image_to_gallery(&path.to_string_lossy(), &name, &item.mime)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.show_pill(match result {
                    Ok(true) => "Saved to Photos",
                    Err(rovsti_android::Error::Unsupported) => "Saving needs an Android phone.",
                    Ok(false) | Err(_) => "Couldn't save the picture",
                });
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Closes the viewer and opens the Forward chooser for the current picture.
    fn viewer_forward(&mut self, window: &Window, cx: &mut Context<Self>) {
        let Some(item) = self
            .media_viewer
            .as_ref()
            .and_then(MediaViewer::forward_item)
        else {
            return;
        };
        self.close_media_viewer(window, cx);
        self.open_forward(item, cx);
    }

    fn viewer_share(&mut self, cx: &mut Context<Self>) {
        let Some((path, item)) = self.viewer_target() else {
            return;
        };
        let task = cx.background_executor().spawn(async move {
            rovsti_android::media::share_image(&path.to_string_lossy(), &item.mime, &item.title)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(true) => {}
                    Err(rovsti_android::Error::Unsupported) => {
                        this.show_pill("Sharing needs an Android phone.");
                    }
                    Ok(false) | Err(_) => this.show_pill("Couldn't share the picture"),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn viewer_pinch(&mut self, event: &PinchEvent, window: &Window, cx: &mut Context<Self>) {
        let (viewport, reduced) = (viewport_of(window), window.prefers_reduced_motion());
        if let Some(viewer) = &mut self.media_viewer {
            match event.phase {
                TouchPhase::Ended | TouchPhase::Cancelled => viewer.pinch_end(viewport, reduced),
                _ => viewer.pinch(event.delta, point_of(event.position), viewport),
            }
            cx.notify();
        }
    }

    /// Raw touch from the drag surface: the finger's whole path.
    fn viewer_touch(
        &mut self,
        phase: TouchPhase,
        pos: gpui::Point<gpui::Pixels>,
        window: &Window,
        cx: &mut Context<Self>,
    ) {
        let (viewport, reduced) = (viewport_of(window), window.prefers_reduced_motion());
        let (pos, now) = (point_of(pos), Instant::now());
        let Some(viewer) = &mut self.media_viewer else {
            return;
        };
        match phase {
            TouchPhase::Started if viewer.in_chrome(pos, viewport) => return,
            TouchPhase::Started => {
                viewer.touch_seen = true;
                viewer.guard.begin(pos.x, pos.y, 0.0);
                viewer.press(pos, now);
            }
            TouchPhase::Moved => {
                viewer.guard.moved(pos.x, pos.y);
                viewer.drag(pos, viewport, now);
            }
            TouchPhase::Ended | TouchPhase::Cancelled => {
                viewer.guard.finish(pos.x, pos.y);
                // The tap itself arrives as the synthesised mouse press.
                match viewer.release(viewport, now, reduced) {
                    Release::Close => viewer.close(viewport, reduced),
                    Release::Page => self.load_viewer_item(cx),
                    Release::Tap | Release::Nothing => {}
                }
            }
        }
        cx.notify();
    }

    fn viewer_mouse_down(
        &mut self,
        event: &MouseDownEvent,
        window: &Window,
        cx: &mut Context<Self>,
    ) {
        let (viewport, reduced) = (viewport_of(window), window.prefers_reduced_motion());
        let (pos, now) = (point_of(event.position), Instant::now());
        let Some(viewer) = &mut self.media_viewer else {
            return;
        };
        if viewer.touch_seen {
            if viewer.guard.allow_click(0.0) {
                viewer.tap(pos, event.click_count, now, viewport, reduced);
            }
        } else {
            viewer.press(pos, now);
            viewer.pending_clicks = event.click_count;
        }
        cx.notify();
    }

    fn viewer_mouse_move(
        &mut self,
        event: &MouseMoveEvent,
        window: &Window,
        cx: &mut Context<Self>,
    ) {
        if event.pressed_button != Some(MouseButton::Left) {
            return;
        }
        let viewport = viewport_of(window);
        if let Some(viewer) = &mut self.media_viewer
            && !viewer.touch_seen
        {
            viewer.drag(point_of(event.position), viewport, Instant::now());
            cx.notify();
        }
    }

    fn viewer_mouse_up(&mut self, event: &MouseUpEvent, window: &Window, cx: &mut Context<Self>) {
        let (viewport, reduced) = (viewport_of(window), window.prefers_reduced_motion());
        let (pos, now) = (point_of(event.position), Instant::now());
        let Some(viewer) = &mut self.media_viewer else {
            return;
        };
        if viewer.touch_seen {
            return;
        }
        let clicks = std::mem::take(&mut viewer.pending_clicks);
        match viewer.release(viewport, now, reduced) {
            Release::Close => viewer.close(viewport, reduced),
            Release::Tap => viewer.tap(pos, clicks, now, viewport, reduced),
            Release::Page => self.load_viewer_item(cx),
            Release::Nothing => {}
        }
        cx.notify();
    }

    /// Remembers the window's size for closing from Back, and drops a viewer
    /// that finished closing. Called once per frame.
    pub(crate) fn sync_media_viewer(&mut self, window: &Window) {
        if self
            .media_viewer
            .as_ref()
            .is_some_and(MediaViewer::finished)
        {
            self.media_viewer = None;
        }
        if let Some(viewer) = &mut self.media_viewer {
            viewer.env = (viewport_of(window), window.prefers_reduced_motion());
        }
    }

    /// A chrome button. `enabled` false dims it and ignores taps.
    fn viewer_button(
        theme: Theme,
        id: &'static str,
        label: &'static str,
        icon: Icon,
        wide: bool,
        enabled: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let mut face = div()
            .h(px(44.0))
            .rounded_full()
            .flex()
            .items_center()
            .justify_center()
            .gap(px(8.0))
            .child(
                gpui::svg()
                    .path(icon.path())
                    .size(px(20.0))
                    .text_color(colors.label),
            );
        face = if wide {
            face.px(px(20.0)).child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.label)
                    .child(label),
            )
        } else {
            face.w(px(44.0))
        };
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .flex_none()
            .h(px(48.0))
            .flex()
            .items_center()
            .justify_center()
            .opacity(if enabled { 1.0 } else { 0.4 })
            .on_click(cx.listener(move |this, _, window, cx| {
                match id {
                    "mv-close" => this.close_media_viewer(window, cx),
                    "mv-save" => this.viewer_save(cx),
                    "mv-forward" => this.viewer_forward(window, cx),
                    "mv-retry" => this.load_viewer_item(cx),
                    _ => this.viewer_share(cx),
                }
                cx.notify();
            }))
            .child(face)
            .into_any_element()
    }

    /// The overlay, above the page and under the notice pill.
    pub(crate) fn media_viewer_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let viewer = self.media_viewer.as_ref()?;
        let colors = theme::colors(theme);
        let viewport = viewport_of(window);
        let (x, y, w, h) = viewer.rect(viewport);
        let dy = viewer.dismiss_offset();
        let page = viewer.page_shift();
        let backdrop = gpui::Hsla {
            a: 0.96 * (1.0 - 0.8 * viewer.dismiss_fade()),
            ..gpui::black()
        };
        let entity = cx.entity();
        let touch = cx.entity();
        let surface = div()
            .id("mv-surface")
            .absolute()
            .inset_0()
            .on_pinch(cx.listener(|this, event: &PinchEvent, window, cx| {
                this.viewer_pinch(event, window, cx);
            }))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, event: &MouseDownEvent, window, cx| {
                    this.viewer_mouse_down(event, window, cx);
                }),
            )
            .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, window, cx| {
                this.viewer_mouse_move(event, window, cx);
            }))
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, event: &MouseUpEvent, window, cx| {
                    this.viewer_mouse_up(event, window, cx);
                }),
            )
            .child(drag_surface(move |phase, pos, window, cx| {
                touch.update(cx, |this, cx| this.viewer_touch(phase, pos, window, cx));
            }));
        // Escape closes on a desktop keyboard.
        let escape = canvas(
            |_, _, _| (),
            move |_, (), window, _| {
                let entity = entity.clone();
                window.on_key_event(move |event: &KeyDownEvent, phase, window, cx| {
                    if phase.bubble() && event.keystroke.key == "escape" {
                        entity.update(cx, |this, cx| this.close_media_viewer(window, cx));
                    }
                });
            },
        )
        .absolute()
        .size_full();

        let ready = viewer.full.is_some();
        let chrome_alpha = viewer.chrome_alpha();
        let chrome = viewer.chrome_visible().then(|| {
            let top = f32::from(window.insets().effective().top).max(20.0) + 8.0;
            let bottom = f32::from(window.insets().effective().bottom) + 16.0;
            let top_bar = glass(GlassSpec::chrome(theme))
                .occlude()
                .absolute()
                .top(px(top))
                .left(px(16.0))
                .right(px(16.0))
                .h(px(56.0))
                .rounded_full()
                .shadow(shadow::glass(theme.is_dark()))
                .px(px(6.0))
                .flex()
                .items_center()
                .gap(px(8.0))
                .child(Self::viewer_button(
                    theme,
                    "mv-close",
                    "Close",
                    Icon::LucideX,
                    false,
                    true,
                    cx,
                ))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .pr(px(16.0))
                        .flex()
                        .flex_col()
                        .child(
                            div()
                                .truncate()
                                .text_size(typography::HEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child(viewer.current().title.clone()),
                        )
                        .children(viewer.caption().map(|caption| {
                            div()
                                .text_size(typography::CAPTION.size)
                                .text_color(colors.secondary_label)
                                .child(caption)
                        })),
                );
            let bottom_bar = div()
                .absolute()
                .bottom(px(bottom))
                .left_0()
                .right_0()
                .flex()
                .justify_center()
                .child(
                    glass(GlassSpec::chrome(theme))
                        .occlude()
                        .h(px(64.0))
                        .rounded_full()
                        .shadow(shadow::glass(theme.is_dark()))
                        .px(px(8.0))
                        .flex()
                        .items_center()
                        .gap(px(4.0))
                        .child(Self::viewer_button(
                            theme,
                            "mv-save",
                            "Save",
                            Icon::LucideDownload,
                            true,
                            ready,
                            cx,
                        ))
                        .child(Self::viewer_button(
                            theme,
                            "mv-share",
                            "Share",
                            Icon::LucideShare2,
                            true,
                            ready,
                            cx,
                        )),
                );
            div()
                .absolute()
                .inset_0()
                .opacity(chrome_alpha)
                .child(top_bar)
                .child(bottom_bar)
        });

        let picture = if let Some((image, _)) = &viewer.full {
            (w > 0.0 && h > 0.0).then(|| {
                gpui::img(image.clone())
                    .absolute()
                    .left(px(x + page))
                    .top(px(y + dy))
                    .w(px(w))
                    .h(px(h))
                    .object_fit(ObjectFit::Fill)
                    .into_any_element()
            })
        } else {
            let fit = |image: gpui::Img| {
                image
                    .absolute()
                    .left(px(x + page))
                    .top(px(y + dy))
                    .w(px(w))
                    .h(px(h))
                    .object_fit(ObjectFit::Contain)
                    .into_any_element()
            };
            viewer
                .placeholder
                .clone()
                .map(|image| fit(gpui::img(image)))
                .or_else(|| viewer.avatar.clone().map(|image| fit(gpui::img(image))))
        };
        // Status over a picture that has not arrived (or never will).
        let status = (!ready).then(|| {
            let failed = viewer.load == Load::Failed;
            let retry = failed.then(|| {
                glass(GlassSpec::chrome(theme))
                    .rounded_full()
                    .child(Self::viewer_button(
                        theme,
                        "mv-retry",
                        "Retry",
                        Icon::LucideRefreshCw,
                        true,
                        true,
                        cx,
                    ))
            });
            div()
                .absolute()
                .inset_0()
                .flex()
                .flex_col()
                .items_center()
                .justify_center()
                .gap(px(12.0))
                .child(
                    div()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.label)
                        .child(if failed {
                            "Couldn't load the picture"
                        } else {
                            "Loading…"
                        }),
                )
                .children(retry)
        });
        Some(
            div()
                .id("media-viewer")
                .role(Role::Dialog)
                .aria_label(SharedString::from(
                    if viewer.current().source.is_profile() {
                        format!("Profile photo of {}", viewer.current().title)
                    } else {
                        format!("Picture from {}", viewer.current().title)
                    },
                ))
                .absolute()
                .inset_0()
                .occlude()
                .overflow_hidden()
                .opacity(viewer.enter_alpha())
                .bg(backdrop)
                .children(picture)
                .child(surface)
                .children(status)
                .children(chrome)
                .child(escape)
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VP: Size = Size { w: 400.0, h: 800.0 };

    fn pt(x: f32, y: f32) -> Point {
        Point { x, y }
    }

    fn item() -> ViewerItem {
        ViewerItem {
            key: "m1".to_owned(),
            title: "Ada".into(),
            mime: "image/jpeg".to_owned(),
            source: ViewerSource::WaMedia {
                message_id: "m1".to_owned(),
            },
            at_ms: 0,
            placeholder: None,
            avatar: None,
        }
    }

    fn viewer() -> MediaViewer {
        MediaViewer::open(vec![item()], 0).expect("a viewer")
    }

    fn settle(v: &mut MediaViewer) {
        for _ in 0..600 {
            v.tick(Duration::from_millis(16));
        }
    }

    #[test]
    fn it_opens_only_with_an_item() {
        assert!(MediaViewer::open(Vec::new(), 0).is_none());
        assert!(MediaViewer::open(vec![item()], 1).is_none());
        assert!(MediaViewer::open(vec![item()], 0).is_some());
    }

    #[test]
    fn the_save_name_is_safe_stamped_and_typed() {
        // 2026-11-07 10:15:00 UTC.
        let at = 1_794_046_500_000;
        assert_eq!(
            save_name("Ada Lovelace", "image/jpeg", at),
            "WhatsApp-Ada-Lovelace-20261107-101500.jpg"
        );
        assert_eq!(
            save_name("  ../x/y: z ", "image/png", at),
            "WhatsApp-x-y-z-20261107-101500.png"
        );
        assert_eq!(
            save_name("", "image/webp", at),
            "WhatsApp-20261107-101500.webp"
        );
        assert_eq!(
            save_name("***", "application/x-weird", 0),
            "WhatsApp-19700101-000000.jpg"
        );
    }

    #[test]
    fn a_square_picture_fills_the_width_centred() {
        let (x, y, w, h) = image_rect(ZoomView::IDENTITY, Size { w: 128.0, h: 128.0 }, VP);
        assert_eq!((x, y, w, h), (0.0, 200.0, 400.0, 400.0));
    }

    #[test]
    fn a_wide_and_a_tall_picture_stay_inside_the_viewport_at_fit() {
        for natural in [Size { w: 300.0, h: 100.0 }, Size { w: 100.0, h: 900.0 }] {
            let (x, y, w, h) = image_rect(ZoomView::IDENTITY, natural, VP);
            assert!(x >= -0.001 && y >= -0.001, "{x} {y}");
            assert!(x + w <= VP.w + 0.001 && y + h <= VP.h + 0.001);
            assert!(((x + w / 2.0) - VP.w / 2.0).abs() < 0.001);
            assert!(((y + h / 2.0) - VP.h / 2.0).abs() < 0.001);
        }
    }

    #[test]
    fn zoom_scales_around_the_centre_and_the_offset_shifts_it() {
        let view = ZoomView {
            scale: 2.0,
            offset: pt(30.0, -10.0),
        };
        let (x, y, w, h) = image_rect(view, Size { w: 128.0, h: 128.0 }, VP);
        assert_eq!((w, h), (800.0, 800.0));
        assert_eq!((x, y), (-200.0 + 30.0, 0.0 - 10.0));
    }

    #[test]
    fn profile_photos_are_named_after_the_person() {
        assert_eq!(
            photo_name("Ada Lovelace", "image/jpeg"),
            "Ada-Lovelace-photo.jpg"
        );
        assert_eq!(photo_name("Ada", "image/png"), "Ada-photo.png");
        assert_eq!(photo_name("Ada", "application/x-weird"), "Ada-photo.jpg");
        assert_eq!(photo_name("???", "image/jpeg"), "contact-photo.jpg");
    }

    #[test]
    fn only_photo_sources_are_profiles() {
        assert!(
            ViewerSource::WaPicture {
                jid: "1@s.whatsapp.net".into()
            }
            .is_profile()
        );
        assert!(
            ViewerSource::ContactPhoto {
                uri: "content://x".into()
            }
            .is_profile()
        );
        assert!(
            !ViewerSource::File {
                path: PathBuf::new()
            }
            .is_profile()
        );
    }

    #[test]
    fn the_placeholder_fills_the_viewport_and_cannot_zoom() {
        let now = Instant::now();
        let mut v = viewer();
        assert_eq!(v.rect(VP), (0.0, 0.0, 400.0, 800.0));
        v.pinch(1.0, pt(200.0, 400.0), VP);
        v.pinch_end(VP, true);
        assert_eq!(v.shown(), ZoomView::IDENTITY);
        // A tap hides the chrome, the quick second one restores it, no zoom.
        v.tap(pt(200.0, 400.0), 1, now, VP, true);
        assert!(!v.chrome_visible());
        v.tap(
            pt(204.0, 402.0),
            1,
            now + Duration::from_millis(100),
            VP,
            true,
        );
        assert!(v.chrome_visible());
        assert_eq!(v.shown(), ZoomView::IDENTITY);
    }

    #[test]
    fn a_long_swipe_down_closes_and_a_short_one_springs_back() {
        let now = Instant::now();
        let mut v = viewer();
        v.press(pt(200.0, 300.0), now);
        v.drag(pt(200.0, 340.0), VP, now + Duration::from_millis(100));
        assert!(v.dismiss_fade() > 0.0);
        assert_eq!(
            v.release(VP, now + Duration::from_millis(400), false),
            Release::Nothing
        );
        settle(&mut v);
        assert_eq!(v.dismiss_offset(), 0.0);

        v.press(pt(200.0, 300.0), now);
        v.drag(pt(200.0, 460.0), VP, now + Duration::from_millis(100));
        assert_eq!(
            v.release(VP, now + Duration::from_millis(400), false),
            Release::Close
        );
        v.close(VP, false);
        assert!(v.is_closing() && !v.finished());
        settle(&mut v);
        assert!(v.finished());
    }

    #[test]
    fn fingers_on_the_bars_belong_to_the_buttons() {
        let mut v = viewer();
        assert!(v.in_chrome(pt(10.0, 40.0), VP));
        assert!(v.in_chrome(pt(10.0, 780.0), VP));
        assert!(!v.in_chrome(pt(10.0, 400.0), VP));
        v.toggle_chrome(true);
        assert!(!v.in_chrome(pt(10.0, 40.0), VP));
    }

    #[test]
    fn a_still_press_and_lift_is_a_tap() {
        let now = Instant::now();
        let mut v = viewer();
        v.press(pt(200.0, 400.0), now);
        v.drag(pt(202.0, 401.0), VP, now + Duration::from_millis(30));
        assert_eq!(
            v.release(VP, now + Duration::from_millis(40), true),
            Release::Tap
        );
    }

    fn keyed(key: &str) -> ViewerItem {
        ViewerItem {
            key: key.to_owned(),
            ..item()
        }
    }

    fn gallery(n: usize, at: usize) -> MediaViewer {
        let items = (0..n).map(|i| keyed(&format!("m{i}"))).collect();
        MediaViewer::open(items, at).expect("a viewer")
    }

    fn swipe(v: &mut MediaViewer, dx: f32, ms: u64) -> Release {
        let now = Instant::now();
        v.press(pt(200.0, 400.0), now);
        v.drag(pt(200.0 + dx, 405.0), VP, now + Duration::from_millis(ms));
        v.release(VP, now + Duration::from_millis(ms + 10), false)
    }

    #[test]
    fn a_long_sideways_swipe_turns_the_page() {
        let mut v = gallery(3, 1);
        assert_eq!(swipe(&mut v, -150.0, 300), Release::Page);
        assert_eq!(v.index, 2);
        assert_eq!(v.caption().as_deref(), Some("3 of 3"));
        // The new picture starts off to the right and slides in.
        assert!(v.page_shift() > 0.0);
        settle(&mut v);
        assert_eq!(v.page_shift(), 0.0);
        assert_eq!(v.shown(), ZoomView::IDENTITY);
        assert!(v.full.is_none());
        assert_eq!(swipe(&mut v, 150.0, 300), Release::Page);
        assert_eq!(v.index, 1);
        assert!(v.page_shift() < 0.0);
    }

    #[test]
    fn a_short_slow_swipe_springs_back() {
        let mut v = gallery(3, 1);
        assert_eq!(swipe(&mut v, -40.0, 600), Release::Nothing);
        assert_eq!(v.index, 1);
        settle(&mut v);
        assert_eq!(v.page_shift(), 0.0);
    }

    #[test]
    fn the_ends_rubber_band_and_stay() {
        let mut v = gallery(3, 0);
        let now = Instant::now();
        v.press(pt(200.0, 400.0), now);
        v.drag(pt(300.0, 402.0), VP, now + Duration::from_millis(50));
        assert!(v.page_shift() > 0.0 && v.page_shift() < 100.0);
        assert_eq!(
            v.release(VP, now + Duration::from_millis(500), false),
            Release::Nothing
        );
        assert_eq!(v.index, 0);
        let mut last = gallery(3, 2);
        assert_eq!(swipe(&mut last, -300.0, 300), Release::Nothing);
        assert_eq!(last.index, 2);
    }

    #[test]
    fn a_lone_picture_does_not_page_and_has_no_caption() {
        let mut v = viewer();
        assert_eq!(v.caption(), None);
        assert_ne!(swipe(&mut v, -300.0, 300), Release::Page);
        assert_eq!(v.index, 0);
        assert_eq!(v.page_shift(), 0.0);
    }

    #[test]
    fn a_mostly_vertical_drag_closes_instead_of_paging() {
        let mut v = gallery(3, 1);
        let now = Instant::now();
        v.press(pt(200.0, 300.0), now);
        v.drag(pt(230.0, 460.0), VP, now + Duration::from_millis(100));
        assert_eq!(v.page_shift(), 0.0);
        assert_eq!(
            v.release(VP, now + Duration::from_millis(400), false),
            Release::Close
        );
    }

    #[test]
    fn a_zoomed_picture_pans_instead_of_paging() {
        let mut v = gallery(3, 1);
        v.scale.snap(2.0);
        assert_ne!(swipe(&mut v, -300.0, 300), Release::Page);
        assert_eq!(v.index, 1);
    }

    #[test]
    fn the_picture_just_left_is_kept_for_swiping_back() {
        let mut v = gallery(3, 0);
        const PNG: &[u8] = &[
            0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48,
            0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00,
            0x00, 0x1f, 0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78,
            0x9c, 0x63, 0xf8, 0xcf, 0xc0, 0xf0, 0x1f, 0x00, 0x05, 0x00, 0x01, 0xff, 0x89, 0x99,
            0x3d, 0x1d, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
        ];
        let image = rovsti_wa_ui::decode_image(PNG, 64).expect("a picture");
        v.set_full(image, PathBuf::from("a.jpg"));
        assert_eq!(swipe(&mut v, -200.0, 300), Release::Page);
        assert!(v.full.is_none());
        settle(&mut v);
        assert_eq!(swipe(&mut v, 200.0, 300), Release::Page);
        // Back on m0, decoded again without a new load.
        assert!(v.full.is_some());
    }

    #[test]
    fn prefetch_asks_for_the_neighbour_ahead_once() {
        let mut v = gallery(3, 1);
        assert_eq!(v.next_prefetch().map(|i| i.key), Some("m2".to_owned()));
        assert!(v.next_prefetch().is_none());
        let mut first = gallery(2, 0);
        assert_eq!(first.next_prefetch().map(|i| i.key), Some("m1".to_owned()));
        let mut lone = viewer();
        assert!(lone.next_prefetch().is_none());
    }

    #[test]
    fn mms_pictures_are_named_mms() {
        assert_eq!(
            branded_name("MMS", "Ada", "image/png", 0),
            "MMS-Ada-19700101-000000.png"
        );
    }
}
