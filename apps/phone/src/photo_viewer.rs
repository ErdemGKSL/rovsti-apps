//! The full-screen photo viewer (utility U1, U2).
//!
//! An overlay above the sheets (not a `SheetKind`: the sheet stays underneath
//! and comes back when the viewer closes). It opens on the picture the sheet
//! already holds, then swaps in the full-size photo read on the background
//! executor. Pinch and double-tap zoom, drag to pan, swipe down to close, a tap
//! hides the chrome, and the bottom bar saves to the gallery or shares.
//!
//! The maths lives in `rovsti_core::zoom`. [`PhotoViewer`] holds the gesture
//! state and the springs that animate it; [`image_rect`] is the one pure
//! function that maps a view to the rectangle the image is drawn in.

use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, KeyDownEvent, MouseButton,
    MouseDownEvent, MouseMoveEvent, MouseUpEvent, ObjectFit, ParentElement, PinchEvent,
    RenderImage, Role, SharedString, StatefulInteractiveElement, Styled, StyledImage, TouchPhase,
    Window, canvas, div, px,
};
use rovsti_core::zoom::{self, Point, Size, ZoomView};
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    motion::press_scale,
    motion_tokens::{self, scale},
    spring::Spring,
    theme,
    tokens::{Theme, shadow, typography},
    touch::{TAP_SLOP, TapGuard, drag_surface, touch_surface},
};

use crate::{PhoneApp, backend};

/// Height of the strip at the top and bottom where the chrome sits; a finger
/// that lands there belongs to the buttons, not to the photo.
const CHROME_ZONE: f32 = 120.0;
/// A second tap within this long, and this close, is a double tap.
const DOUBLE_TAP: Duration = Duration::from_millis(300);
const DOUBLE_TAP_SLOP: f32 = 48.0;
/// A pause this long before the lift forgets the flick speed.
const STALE_SAMPLE: Duration = Duration::from_millis(80);
/// A QR's white card extends this far past the code.
const QR_PAD: f32 = 16.0;
/// Space kept free around a QR card, on each side.
const QR_MARGIN: f32 = 32.0;

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

/// A file name for the gallery: the person's name, safe for a file system.
pub(crate) fn photo_file_name(title: &str) -> String {
    match rovsti_core::number_format::file_slug_opt(title) {
        Some(stem) => format!("{stem}-photo.png"),
        None => "photo.png".to_owned(),
    }
}

/// What the viewer shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ViewerKind {
    /// A contact's or caller's picture: zoom, pan, and Edit for a contact.
    Photo,
    /// A share QR code (U9): shown at fit on a white card, no zoom or pan.
    Qr,
}

impl ViewerKind {
    /// Pinch and double-tap zoom only apply to a photo.
    pub(crate) fn zooms(self) -> bool {
        self == Self::Photo
    }
}

/// The pill after a QR was built without some numbers, if any were left out.
pub(crate) fn dropped_pill(dropped: usize) -> Option<String> {
    match dropped {
        0 => None,
        1 => Some("1 number left out to fit".to_owned()),
        n => Some(format!("{n} numbers left out to fit")),
    }
}

/// What a finger lifting did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Release {
    Nothing,
    /// The finger barely moved: a tap.
    Tap,
    /// Swiped down far or fast enough: close.
    Close,
}

struct Drag {
    start: Point,
    last: Point,
    at: Instant,
    velocity: f32,
    moved: f32,
}

pub(crate) struct PhotoViewer {
    pub kind: ViewerKind,
    /// The contact whose photo this is (the Edit button needs one).
    pub contact_id: Option<i64>,
    /// The picture is the contact's own photo (not a WhatsApp picture).
    pub own_photo: bool,
    pub title: SharedString,
    /// The picture drawn: the thumbnail first, the full photo once it is read.
    pub image: Option<Arc<RenderImage>>,
    /// The PNG the full photo came from (what Save and Share send).
    pub file: Option<PathBuf>,
    /// Pixel size of `image` (only its aspect matters).
    pub natural: Size,
    /// The gesture's own view; the springs below chase it.
    pub view: ZoomView,
    /// Identifies this opening, so a late load cannot land in another viewer.
    pub token: u64,
    drag: Option<Drag>,
    /// How far the finger pulled the photo down while not zoomed.
    pub dismiss_dy: f32,
    pinching: bool,
    chrome_on: bool,
    last_tap: Option<(Point, Instant)>,
    closing: bool,
    /// A raw touch has been seen, so mouse events are the synthesised taps.
    touch_seen: bool,
    guard: TapGuard,
    /// Click count of the mouse press being held (desktop path).
    pending_clicks: usize,
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

impl PhotoViewer {
    /// Opens on `image`; without a picture there is nothing to view.
    pub(crate) fn open(title: SharedString, image: Option<Arc<RenderImage>>) -> Option<Self> {
        Self::open_kind(ViewerKind::Photo, title, image)
    }

    pub(crate) fn open_kind(
        kind: ViewerKind,
        title: SharedString,
        image: Option<Arc<RenderImage>>,
    ) -> Option<Self> {
        let image = image?;
        let natural = size_of(&image);
        if natural.w <= 0.0 || natural.h <= 0.0 {
            return None;
        }
        let tween = |value| Spring::new(value, motion_tokens::appear()).with_epsilon(0.002);
        let pixels = |value| Spring::new(value, motion_tokens::appear()).with_epsilon(0.05);
        let mut enter = tween(0.0);
        enter.set_target(1.0);
        Some(Self {
            kind,
            contact_id: None,
            own_photo: false,
            title,
            image: Some(image),
            file: None,
            natural,
            view: ZoomView::IDENTITY,
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
            scale: tween(1.0),
            ox: pixels(0.0),
            oy: pixels(0.0),
            dismiss: Spring::new(0.0, motion_tokens::release()).with_epsilon(0.05),
            chrome: tween(1.0),
            enter,
        })
    }

    /// The full-size photo arrived: same picture, more pixels.
    pub(crate) fn set_full(&mut self, image: Arc<RenderImage>, file: PathBuf) {
        let natural = size_of(&image);
        if natural.w > 0.0 && natural.h > 0.0 {
            self.natural = natural;
            self.image = Some(image);
        }
        self.file = Some(file);
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

    pub(crate) fn rect(&self, viewport: Size) -> (f32, f32, f32, f32) {
        let space = match self.kind {
            ViewerKind::Photo => viewport,
            // Room for the card around the code, same centre.
            ViewerKind::Qr => {
                let inset = 2.0 * (QR_MARGIN + QR_PAD);
                Size {
                    w: (viewport.w - inset).max(1.0),
                    h: (viewport.h - inset).max(1.0),
                }
            }
        };
        let (x, y, w, h) = image_rect(self.shown(), self.natural, space);
        (
            (x + (viewport.w - space.w) / 2.0),
            y + (viewport.h - space.h) / 2.0,
            w,
            h,
        )
    }

    pub(crate) fn chrome_visible(&self) -> bool {
        self.chrome.value > 0.01
    }

    pub(crate) fn chrome_alpha(&self) -> f32 {
        self.chrome.value.clamp(0.0, 1.0) * self.enter.value.clamp(0.0, 1.0)
    }

    /// Opacity of the whole overlay (the open and close fade).
    pub(crate) fn enter_alpha(&self) -> f32 {
        self.enter.value.clamp(0.0, 1.0)
    }

    /// How far the background has faded from a swipe down, 0 to 1.
    pub(crate) fn dismiss_fade(&self) -> f32 {
        zoom::dismiss_progress(self.dismiss.value)
    }

    pub(crate) fn dismiss_offset(&self) -> f32 {
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
        if !self.kind.zooms() {
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
        self.drag = Some(Drag {
            start: pos,
            last: pos,
            at: now,
            velocity: 0.0,
            moved: 0.0,
        });
    }

    /// The finger moved: pan when zoomed, else pull the photo down.
    pub(crate) fn drag(&mut self, pos: Point, viewport: Size, now: Instant) {
        let fitted = self.fitted(viewport);
        let zoomed = self.view.is_zoomed();
        let Some(d) = &mut self.drag else {
            return;
        };
        let (dx, dy) = (pos.x - d.last.x, pos.y - d.last.y);
        d.moved = (pos.x - d.start.x).hypot(pos.y - d.start.y);
        let elapsed = now.saturating_duration_since(d.at).as_secs_f32().max(0.004);
        d.velocity = d.velocity * 0.6 + (dy / elapsed) * 0.4;
        d.last = pos;
        d.at = now;
        if zoomed {
            self.view.pan(dx, dy, fitted, viewport);
            self.snap_view();
        } else {
            self.dismiss_dy = (self.dismiss_dy + dy).max(0.0);
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
        let velocity = if now.saturating_duration_since(d.at) > STALE_SAMPLE {
            0.0
        } else {
            d.velocity
        };
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
            if !self.kind.zooms() {
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

    /// Starts closing: the photo slides away and everything fades.
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

    /// Closes without animating (the photo it shows is gone or out of date).
    pub(crate) fn close_now(&mut self) {
        self.closing = true;
        self.drag = None;
        self.enter.snap(0.0);
        self.chrome.snap(0.0);
    }

    /// Whether the bottom bar offers Edit (a photo of a contact).
    pub(crate) fn can_edit(&self) -> bool {
        self.kind == ViewerKind::Photo && self.contact_id.is_some()
    }

    pub(crate) fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = false;
        for spring in [
            &mut self.scale,
            &mut self.ox,
            &mut self.oy,
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

/// The picture as a PNG file, for a photo that has no file of its own (a
/// WhatsApp picture, or a thumbnail still waiting for the full photo).
fn export_png(image: &RenderImage, token: u64) -> Option<PathBuf> {
    let (w, h) = (image.size(0).width.0, image.size(0).height.0);
    let mut bytes = image.as_bytes(0)?.to_vec();
    for pixel in bytes.as_chunks_mut::<4>().0 {
        pixel.swap(0, 2);
    }
    let pixels = image::RgbaImage::from_raw(u32::try_from(w).ok()?, u32::try_from(h).ok()?, bytes)?;
    let path = std::env::temp_dir().join(format!("rovsti-phone-viewer-{token}.png"));
    pixels
        .save_with_format(&path, image::ImageFormat::Png)
        .ok()?;
    Some(path)
}

impl PhoneApp {
    /// Opens the viewer on `thumb` (the picture the sheet shows), then loads
    /// the full photo behind it. Nothing opens without a picture.
    pub(crate) fn open_photo_viewer(
        &mut self,
        title: SharedString,
        thumb: Option<Arc<RenderImage>>,
        uri: Option<SharedString>,
        owner: Option<(i64, bool)>,
        cx: &mut Context<Self>,
    ) {
        let Some(mut viewer) = PhotoViewer::open(title, thumb) else {
            return;
        };
        if let Some((id, own)) = owner {
            viewer.contact_id = Some(id);
            viewer.own_photo = own;
        }
        let token = viewer.token;
        self.viewer = Some(viewer);
        self.presses.release_all();
        if let Some(uri) = uri {
            cx.spawn(async move |this, cx| {
                let loaded = cx
                    .background_executor()
                    .spawn(async move {
                        backend::full_photo(&uri).map(|(pixels, path)| {
                            (Arc::new(crate::photos::render_image(pixels)), path)
                        })
                    })
                    .await;
                this.update(cx, |this, cx| {
                    if let Some(viewer) = &mut this.viewer
                        && viewer.token == token
                        && let Some((image, path)) = loaded
                    {
                        viewer.set_full(image, path);
                    }
                    cx.notify();
                })
                .ok();
            })
            .detach();
        }
        cx.notify();
    }

    /// Opens a QR code with `name` and `numbers` (raw text and label) in the
    /// viewer (U9). Numbers that do not fit are left out and a pill says so.
    pub(crate) fn open_qr_viewer(
        &mut self,
        name: SharedString,
        numbers: Vec<(String, String)>,
        cx: &mut Context<Self>,
    ) {
        let home_cc = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => log.home_cc.clone(),
            _ => None,
        };
        let refs: Vec<(&str, Option<&str>)> = numbers
            .iter()
            .map(|(raw, label)| (raw.as_str(), Some(label.as_str())))
            .collect();
        let Ok(qr) = rovsti_core::number_format::contact_qr(&name, &refs, home_cc.as_deref())
        else {
            self.show_notice_pill("Too much to fit in a QR code", None, false, cx);
            return;
        };
        let dropped = qr.dropped;
        let payload = qr.payload;
        cx.spawn(async move |this, cx| {
            let made = cx
                .background_executor()
                .spawn(async move { backend::qr_image(&payload) })
                .await;
            this.update(cx, |this, cx| {
                let opened = made.and_then(|(image, path)| {
                    let mut viewer = PhotoViewer::open_kind(ViewerKind::Qr, name, Some(image))?;
                    viewer.file = Some(path);
                    Some(viewer)
                });
                match opened {
                    Some(viewer) => {
                        this.viewer = Some(viewer);
                        this.presses.release_all();
                        if let Some(text) = dropped_pill(dropped) {
                            this.show_notice_pill(text, None, false, cx);
                        }
                    }
                    None => this.show_notice_pill("Couldn't make the QR code", None, false, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The viewer's Edit button: the photo menu for its contact.
    fn viewer_edit(&mut self, cx: &mut Context<Self>) {
        let Some(viewer) = self.viewer.as_ref().filter(|v| v.can_edit()) else {
            return;
        };
        if let Some(id) = viewer.contact_id {
            let (name, own) = (viewer.title.clone(), viewer.own_photo);
            self.open_photo_menu(id, name, own, cx);
        }
    }

    pub(crate) fn close_photo_viewer(&mut self, window: &Window, cx: &mut Context<Self>) {
        let (viewport, reduced) = (viewport_of(window), window.prefers_reduced_motion());
        if let Some(viewer) = &mut self.viewer {
            viewer.close(viewport, reduced);
        }
        self.presses.release_all();
        cx.notify();
    }

    /// The file Save and Share send: the full photo's, else the picture on
    /// screen written out.
    fn viewer_file(&self) -> Option<PathBuf> {
        let viewer = self.viewer.as_ref()?;
        viewer
            .file
            .clone()
            .or_else(|| export_png(viewer.image.as_ref()?, viewer.token))
    }

    fn viewer_save(&mut self, cx: &mut Context<Self>) {
        let name = self
            .viewer
            .as_ref()
            .map(|v| match v.kind {
                ViewerKind::Photo => photo_file_name(&v.title),
                ViewerKind::Qr => rovsti_core::number_format::qr_file_name(&v.title),
            })
            .unwrap_or_default();
        let outcome = self
            .viewer_file()
            .map(|path| backend::save_to_gallery(&path, &name));
        match outcome {
            Some(backend::Outcome::Done) => {
                self.show_notice_pill("Saved to Photos", None, false, cx)
            }
            Some(other) => {
                self.show_notice_pill(other.why_not("Couldn't save the photo"), None, false, cx)
            }
            None => self.show_notice_pill("Couldn't save the photo", None, false, cx),
        }
    }

    fn viewer_share(&mut self, cx: &mut Context<Self>) {
        match self.viewer_file().map(|path| backend::share_image(&path)) {
            Some(backend::Outcome::Done) => {}
            Some(other) => {
                self.show_notice_pill(other.why_not("Couldn't share the photo"), None, false, cx)
            }
            None => self.show_notice_pill("Couldn't share the photo", None, false, cx),
        }
    }

    fn viewer_pinch(&mut self, event: &PinchEvent, window: &Window, cx: &mut Context<Self>) {
        let (viewport, reduced) = (viewport_of(window), window.prefers_reduced_motion());
        if let Some(viewer) = &mut self.viewer {
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
        let Some(viewer) = &mut self.viewer else {
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
                if viewer.release(viewport, now, reduced) == Release::Close {
                    viewer.close(viewport, reduced);
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
        let Some(viewer) = &mut self.viewer else {
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
        if let Some(viewer) = &mut self.viewer
            && !viewer.touch_seen
        {
            viewer.drag(point_of(event.position), viewport, Instant::now());
            cx.notify();
        }
    }

    fn viewer_mouse_up(&mut self, event: &MouseUpEvent, window: &Window, cx: &mut Context<Self>) {
        let (viewport, reduced) = (viewport_of(window), window.prefers_reduced_motion());
        let (pos, now) = (point_of(event.position), Instant::now());
        let Some(viewer) = &mut self.viewer else {
            return;
        };
        if viewer.touch_seen {
            return;
        }
        let clicks = std::mem::take(&mut viewer.pending_clicks);
        match viewer.release(viewport, now, reduced) {
            Release::Close => viewer.close(viewport, reduced),
            Release::Tap => viewer.tap(pos, clicks, now, viewport, reduced),
            Release::Nothing => {}
        }
        cx.notify();
    }

    /// A chrome button: pressed look now, the action on mouse down (on Android
    /// that is the tap that follows the finger lifting).
    fn viewer_button(
        &self,
        theme: Theme,
        id: &'static str,
        label: &'static str,
        icon: Icon,
        wide: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value(id);
        let s = press_scale(press, scale::PRESS_CARD);
        let mut face = div()
            .h(px(44.0 * s))
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
            face.w(px(44.0 * s))
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
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(id);
                    match id {
                        "pv-close" => this.close_photo_viewer(window, cx),
                        "pv-save" => this.viewer_save(cx),
                        "pv-edit" => this.viewer_edit(cx),
                        _ => this.viewer_share(cx),
                    }
                    cx.notify();
                }),
            )
            .child(face)
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
            .into_any_element()
    }

    /// The overlay, above the sheets and under the notice pill.
    pub(crate) fn photo_viewer_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let viewer = self.viewer.as_ref()?;
        let colors = theme::colors(theme);
        let viewport = viewport_of(window);
        let (x, y, w, h) = viewer.rect(viewport);
        let dy = viewer.dismiss_offset();
        let backdrop = gpui::Hsla {
            a: 0.96 * (1.0 - 0.8 * viewer.dismiss_fade()),
            ..gpui::black()
        };
        let entity = cx.entity();
        let touch = cx.entity();
        let surface = div()
            .id("pv-surface")
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
                        entity.update(cx, |this, cx| this.close_photo_viewer(window, cx));
                    }
                });
            },
        )
        .absolute()
        .size_full();

        let chrome_alpha = viewer.chrome_alpha();
        let chrome = viewer.chrome_visible().then(|| {
            let top = f32::from(window.insets().effective().top).max(20.0) + 8.0;
            let bottom = crate::safe_bottom(window) + 16.0;
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
                .child(self.viewer_button(theme, "pv-close", "Close", Icon::LucideX, false, cx))
                .child(
                    div()
                        .flex_1()
                        .truncate()
                        .pr(px(16.0))
                        .text_size(typography::HEADLINE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .child(viewer.title.clone()),
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
                        .children(viewer.can_edit().then(|| {
                            self.viewer_button(
                                theme,
                                "pv-edit",
                                "Edit",
                                Icon::LucidePencil,
                                true,
                                cx,
                            )
                        }))
                        .child(self.viewer_button(
                            theme,
                            "pv-save",
                            "Save",
                            Icon::LucideDownload,
                            true,
                            cx,
                        ))
                        .child(self.viewer_button(
                            theme,
                            "pv-share",
                            "Share",
                            Icon::LucideShare2,
                            true,
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

        let is_qr = viewer.kind == ViewerKind::Qr;
        let photo = (w > 0.0 && h > 0.0)
            .then(|| viewer.image.clone())
            .flatten()
            .map(|image| {
                let picture = gpui::img(image)
                    .absolute()
                    .left(px(x))
                    .top(px(y + dy))
                    .w(px(w))
                    .h(px(h))
                    .object_fit(ObjectFit::Fill);
                if is_qr {
                    // A QR sits on a white rounded card so it scans on a dark backdrop.
                    div()
                        .absolute()
                        .inset_0()
                        .child(
                            div()
                                .absolute()
                                .left(px(x - QR_PAD))
                                .top(px(y + dy - QR_PAD))
                                .w(px(w + 2.0 * QR_PAD))
                                .h(px(h + 2.0 * QR_PAD))
                                .rounded(px(28.0))
                                .bg(gpui::white()),
                        )
                        .child(picture)
                        .into_any_element()
                } else {
                    picture.into_any_element()
                }
            });
        Some(
            div()
                .id("photo-viewer")
                .role(Role::Dialog)
                .aria_label(SharedString::from(format!("Photo of {}", viewer.title)))
                .absolute()
                .inset_0()
                .occlude()
                .overflow_hidden()
                .opacity(viewer.enter_alpha())
                .bg(backdrop)
                .children(photo)
                .child(surface)
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

    fn picture(w: u32, h: u32) -> Arc<RenderImage> {
        Arc::new(RenderImage::new(vec![image::Frame::new(
            image::RgbaImage::new(w, h),
        )]))
    }

    fn viewer(w: u32, h: u32) -> PhotoViewer {
        PhotoViewer::open("Ada".into(), Some(picture(w, h))).expect("a viewer")
    }

    fn settle(v: &mut PhotoViewer) {
        for _ in 0..600 {
            v.tick(Duration::from_millis(16));
        }
    }

    #[test]
    fn it_opens_only_with_a_photo() {
        assert!(PhotoViewer::open("Ada".into(), None).is_none());
        assert!(PhotoViewer::open("Ada".into(), Some(picture(0, 0))).is_none());
        assert!(PhotoViewer::open("Ada".into(), Some(picture(8, 8))).is_some());
    }

    fn qr_viewer() -> PhotoViewer {
        PhotoViewer::open_kind(ViewerKind::Qr, "Ada".into(), Some(picture(64, 64)))
            .expect("a viewer")
    }

    #[test]
    fn a_qr_blocks_pinch_and_double_tap() {
        let now = Instant::now();
        let mut v = qr_viewer();
        assert!(!v.kind.zooms() && ViewerKind::Photo.zooms());
        v.pinch(1.0, pt(200.0, 400.0), VP);
        v.pinch_end(VP, true);
        assert_eq!(v.shown(), ZoomView::IDENTITY);
        // A first tap hides the chrome, the quick second one puts it back and
        // does not zoom, with the host's count at 1 or 2.
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
        v.tap(pt(200.0, 400.0), 2, now + Duration::from_secs(2), VP, true);
        assert_eq!(v.shown(), ZoomView::IDENTITY);
        assert!(!v.shown().is_zoomed());
    }

    #[test]
    fn a_qr_card_stays_inside_the_viewport_and_is_centred() {
        let v = qr_viewer();
        let (x, y, w, h) = v.rect(VP);
        assert!(x - QR_PAD >= 0.0 && x + w + QR_PAD <= VP.w, "{x} {w}");
        assert!(y - QR_PAD >= 0.0 && y + h + QR_PAD <= VP.h);
        assert!(((x + w / 2.0) - VP.w / 2.0).abs() < 0.001);
        assert!(((y + h / 2.0) - VP.h / 2.0).abs() < 0.001);
        assert_eq!(w, h);
    }

    #[test]
    fn edit_is_for_the_photo_of_a_contact_only() {
        let mut v = viewer(8, 8);
        assert!(!v.can_edit(), "a number without a contact");
        v.contact_id = Some(7);
        assert!(v.can_edit());
        let mut q = qr_viewer();
        q.contact_id = Some(7);
        assert!(!q.can_edit());
    }

    #[test]
    fn close_now_finishes_at_once() {
        let mut v = viewer(8, 8);
        v.close_now();
        assert!(v.is_closing() && v.finished());
    }

    #[test]
    fn the_dropped_numbers_pill() {
        assert_eq!(dropped_pill(0), None);
        assert_eq!(dropped_pill(1).as_deref(), Some("1 number left out to fit"));
        assert_eq!(
            dropped_pill(2).as_deref(),
            Some("2 numbers left out to fit")
        );
    }

    #[test]
    fn a_square_photo_fills_the_width_centred() {
        let (x, y, w, h) = image_rect(ZoomView::IDENTITY, Size { w: 128.0, h: 128.0 }, VP);
        assert_eq!((x, y, w, h), (0.0, 200.0, 400.0, 400.0));
    }

    #[test]
    fn a_wide_and_a_tall_photo_stay_inside_the_viewport_at_fit() {
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
    fn the_file_name_is_safe_and_never_empty() {
        assert_eq!(photo_file_name("Ada Lovelace"), "Ada-Lovelace-photo.png");
        assert_eq!(photo_file_name("  ../x/y: z "), "x-y-z-photo.png");
        assert_eq!(photo_file_name(""), "photo.png");
        assert_eq!(photo_file_name("***"), "photo.png");
    }

    #[test]
    fn the_full_photo_replaces_the_thumbnail_keeping_the_view() {
        let mut v = viewer(128, 128);
        v.view.scale = 2.0;
        v.set_full(picture(1080, 1080), PathBuf::from("/tmp/full.png"));
        assert_eq!(
            v.natural,
            Size {
                w: 1080.0,
                h: 1080.0
            }
        );
        assert_eq!(v.file, Some(PathBuf::from("/tmp/full.png")));
        assert_eq!(v.view.scale, 2.0);
    }

    #[test]
    fn a_long_swipe_down_closes_and_a_short_one_springs_back() {
        let now = Instant::now();
        let mut v = viewer(128, 128);
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
    fn a_zoomed_photo_pans_instead_of_closing() {
        let now = Instant::now();
        let mut v = viewer(128, 128);
        v.pinch(1.0, pt(200.0, 400.0), VP);
        v.pinch_end(VP, true);
        assert!(v.shown().scale > 1.9);
        v.press(pt(200.0, 300.0), now);
        v.drag(pt(180.0, 500.0), VP, now + Duration::from_millis(50));
        assert_eq!(v.dismiss_dy, 0.0);
        assert_eq!(
            v.release(VP, now + Duration::from_millis(60), true),
            Release::Nothing
        );
        let (x, y, w, h) = v.rect(VP);
        assert!(w > VP.w && h > 0.0);
        assert!(
            x <= 0.0 && x + w >= VP.w,
            "the photo still covers the width"
        );
        assert!(y <= 200.0);
    }

    #[test]
    fn pinching_out_below_fit_settles_back_to_fit() {
        let mut v = viewer(128, 128);
        v.pinch(-0.5, pt(200.0, 400.0), VP);
        assert!(v.shown().scale < 1.0);
        v.pinch_end(VP, true);
        assert_eq!(v.shown(), ZoomView::IDENTITY);
    }

    #[test]
    fn a_tap_toggles_the_chrome_and_a_double_tap_zooms_and_restores_it() {
        let now = Instant::now();
        let mut v = viewer(128, 128);
        assert!(v.chrome_visible());
        v.tap(pt(200.0, 400.0), 1, now, VP, true);
        assert!(!v.chrome_visible());
        // Quick second tap, close by, with the host's count still 1.
        v.tap(
            pt(210.0, 405.0),
            1,
            now + Duration::from_millis(120),
            VP,
            true,
        );
        assert!(v.chrome_visible(), "the double tap put the chrome back");
        assert_eq!(v.shown().scale, zoom::DOUBLE_TAP_SCALE);
        // Double tap again zooms out.
        v.tap(pt(200.0, 400.0), 2, now + Duration::from_secs(2), VP, true);
        assert_eq!(v.shown(), ZoomView::IDENTITY);
    }

    #[test]
    fn slow_taps_are_two_single_taps() {
        let now = Instant::now();
        let mut v = viewer(128, 128);
        v.tap(pt(200.0, 400.0), 1, now, VP, true);
        v.tap(pt(200.0, 400.0), 1, now + Duration::from_secs(1), VP, true);
        assert!(v.chrome_visible());
        assert_eq!(v.shown(), ZoomView::IDENTITY);
    }

    #[test]
    fn fingers_on_the_bars_belong_to_the_buttons() {
        let mut v = viewer(128, 128);
        assert!(v.in_chrome(pt(10.0, 40.0), VP));
        assert!(v.in_chrome(pt(10.0, 780.0), VP));
        assert!(!v.in_chrome(pt(10.0, 400.0), VP));
        v.toggle_chrome(true);
        assert!(!v.in_chrome(pt(10.0, 40.0), VP));
    }

    #[test]
    fn a_still_press_and_lift_is_a_tap() {
        let now = Instant::now();
        let mut v = viewer(128, 128);
        v.press(pt(200.0, 400.0), now);
        v.drag(pt(202.0, 401.0), VP, now + Duration::from_millis(30));
        assert_eq!(
            v.release(VP, now + Duration::from_millis(40), true),
            Release::Tap
        );
    }
}
