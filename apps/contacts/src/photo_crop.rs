//! The full-screen crop page for a contact photo (utilities C13, C14).
//!
//! A picked or captured image does not become the photo at once: it opens
//! here first. The picture is read on the background executor, downscaled to
//! at most `backend::CROP_DISPLAY` px, and drawn behind a square frame with
//! the outside dimmed. Drag pans, pinch and double-tap zoom, Rotate turns it a
//! quarter. Use photo hands the chosen [`CropView`] to
//! `backend::change_photo_with`, which crops the original file.
//!
//! The maths is `rovsti_core::contact_photo::CropView`; [`frame_rect`] and
//! [`image_in_frame`] map it to screen space (through the shared
//! `photos::image_rect`), and [`photo_source_choices`] lists the photo menu.

use std::{
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
use rovsti_core::{
    contact_photo::{CropView, rotated_size},
    zoom::{Point, Size, ZoomView},
};
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    tab_bar::content_bottom_inset,
    theme,
    tokens::{Theme, shadow, typography},
    touch::{TAP_SLOP, TapGuard, drag_surface},
};

use crate::{
    ContactsApp, backend,
    photo_viewer::{point_of, viewport_of},
    photos::{image_rect, render_image},
};

/// A second tap within this long, and this close, is a double tap.
const DOUBLE_TAP: Duration = Duration::from_millis(300);
const DOUBLE_TAP_SLOP: f32 = 48.0;
/// Height of the strip at the bottom that belongs to the buttons.
const BAR_ZONE: f32 = 150.0;
/// Side margin of the frame, and the room kept for the bar and the title.
const FRAME_MARGIN: f32 = 24.0;
const FRAME_ROOM: f32 = 330.0;
/// Smallest frame, in px.
const FRAME_MIN: f32 = 120.0;

static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);

/// Who gets the cropped photo.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum CropTarget {
    /// A saved contact: it is written on Use photo.
    Contact(i64),
    /// A contact still in the editor: the choice waits for Save.
    NewContact,
}

/// One row of the photo menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum PhotoSource {
    TakePhoto,
    ChooseFromGallery,
    /// Crop the photo the contact already has (the viewer's full photo).
    CropCurrent,
    Remove,
}

impl PhotoSource {
    pub(crate) fn label(self) -> &'static str {
        match self {
            Self::TakePhoto => "Take photo",
            Self::ChooseFromGallery => "Choose from gallery",
            Self::CropCurrent => "Crop current photo",
            Self::Remove => "Remove photo",
        }
    }

    pub(crate) fn id(self) -> &'static str {
        match self {
            Self::TakePhoto => "photo-take",
            Self::ChooseFromGallery => "photo-choose",
            Self::CropCurrent => "photo-crop",
            Self::Remove => "photo-remove",
        }
    }

    pub(crate) fn icon(self) -> Icon {
        match self {
            Self::TakePhoto => Icon::LucideCamera,
            Self::ChooseFromGallery => Icon::LucideImagePlus,
            Self::CropCurrent => Icon::LucideCrop,
            Self::Remove => Icon::LucideTrash2,
        }
    }
}

/// The rows of the photo menu, top to bottom. Crop and Remove need a photo.
pub(crate) fn photo_source_choices(has_photo: bool) -> Vec<PhotoSource> {
    let mut choices = vec![PhotoSource::TakePhoto, PhotoSource::ChooseFromGallery];
    if has_photo {
        choices.push(PhotoSource::CropCurrent);
        choices.push(PhotoSource::Remove);
    }
    choices
}

/// What the pill says when the camera could not be opened (`words` is the
/// error of `backend::start_capture_photo`).
pub(crate) fn capture_notice(words: &str) -> String {
    if words == backend::NO_CAMERA {
        words.to_owned()
    } else {
        format!("Can't take a photo: {words}.")
    }
}

/// Side of the square frame in a `w` x `h` viewport.
pub(crate) fn frame_side(w: f32, h: f32) -> f32 {
    (w - 2.0 * FRAME_MARGIN).min(h - FRAME_ROOM).max(FRAME_MIN)
}

/// Where the (rotated) `w` x `h` picture is drawn relative to the top-left of
/// a square frame `view_side` wide: `(x, y, width, height)`. The frame shows
/// the crop of `view`; the picture overflows it. `w`/`h` are the unrotated
/// pixel size, `view.turns` is applied here.
pub(crate) fn image_in_frame(
    view_side: f32,
    w: u32,
    h: u32,
    view: CropView,
) -> (f32, f32, f32, f32) {
    let (rw, rh) = rotated_size(w, h, view.turns);
    let (short, long) = (rw.min(rh) as f32, rw.max(rh) as f32);
    if short <= 0.0 || view_side <= 0.0 {
        return (0.0, 0.0, 0.0, 0.0);
    }
    let natural = Size {
        w: rw as f32,
        h: rh as f32,
    };
    let viewport = Size {
        w: view_side,
        h: view_side,
    };
    // `image_rect` fits the long side into the frame; the crop wants the
    // short side to fill it, times the zoom.
    let scale = view.zoom * long / short;
    let k = view_side * view.zoom / short;
    let (iw, ih) = (rw as f32 * k, rh as f32 * k);
    let offset = Point {
        x: iw * (0.5 - view.cx),
        y: ih * (0.5 - view.cy),
    };
    let (x, y, iw, ih) = image_rect(ZoomView { scale, offset }, natural, viewport);
    // Never show a gap inside the frame.
    let clamp = |v: f32, len: f32| v.clamp((view_side - len).min(0.0), 0.0);
    (clamp(x, iw), clamp(y, ih), iw, ih)
}

/// The crop frame in the coordinates of the drawn picture (top-left at 0,0 and
/// the short side times the zoom as wide as the frame): `(x, y, side)`, the
/// screen-space twin of `plan_for`'s `(x, y, side)` at the same turns.
#[cfg(test)]
pub(crate) fn frame_rect(view_side: f32, w: u32, h: u32, view: CropView) -> (f32, f32, f32) {
    let (x, y, _, _) = image_in_frame(view_side, w, h, view);
    (-x, -y, view_side)
}

/// Double-tap: zoomed in goes back to 1, otherwise to 2 (within the limits).
pub(crate) fn toggled_zoom(view: CropView, w: u32, h: u32) -> CropView {
    if view.zoom > 1.05 {
        view.pinch(1.0 / view.zoom, w, h)
    } else {
        view.pinch(2.0, w, h)
    }
}

/// `pixels` turned `turns` quarters clockwise.
fn turned(pixels: &image::RgbaImage, turns: u8) -> image::RgbaImage {
    match turns % 4 {
        1 => image::imageops::rotate90(pixels),
        2 => image::imageops::rotate180(pixels),
        3 => image::imageops::rotate270(pixels),
        _ => pixels.clone(),
    }
}

struct Gesture {
    last: Point,
    moved: f32,
    start: Point,
}

/// The open crop page.
pub(crate) struct CropPage {
    pub target: CropTarget,
    /// The file to crop: what `change_photo_with` is given.
    pub path: String,
    /// Identifies this opening, so a late load cannot land in another page.
    token: u64,
    /// The downscaled picture, unrotated, once it is read.
    pixels: Option<Arc<image::RgbaImage>>,
    /// `pixels` turned by `view.turns`, as the painter wants it.
    shown: Option<Arc<RenderImage>>,
    pub view: CropView,
    gesture: Option<Gesture>,
    last_tap: Option<(Point, Instant)>,
    touch_seen: bool,
    guard: TapGuard,
    pending_clicks: usize,
}

impl CropPage {
    fn new(target: CropTarget, path: String) -> Self {
        Self {
            target,
            path,
            token: NEXT_TOKEN.fetch_add(1, Ordering::Relaxed),
            pixels: None,
            shown: None,
            view: CropView::default(),
            gesture: None,
            last_tap: None,
            touch_seen: false,
            guard: TapGuard::default(),
            pending_clicks: 0,
        }
    }

    fn set_loaded(&mut self, pixels: image::RgbaImage) {
        self.pixels = Some(Arc::new(pixels));
        self.view = CropView::default();
        self.refresh();
    }

    pub(crate) fn ready(&self) -> bool {
        self.shown.is_some()
    }

    /// Size of the unrotated picture.
    fn dims(&self) -> (u32, u32) {
        self.pixels
            .as_ref()
            .map_or((0, 0), |p| (p.width(), p.height()))
    }

    /// Rebuilds the painted picture for the current turns.
    fn refresh(&mut self) {
        self.shown = self
            .pixels
            .as_ref()
            .map(|p| Arc::new(render_image(turned(p, self.view.turns))));
    }

    fn rotate(&mut self) {
        if self.ready() {
            self.view = self.view.rotate_right();
            self.refresh();
        }
    }

    fn reset(&mut self) {
        if self.ready() {
            let turned = self.view.turns != 0;
            self.view = CropView::default();
            if turned {
                self.refresh();
            }
        }
    }

    fn pan(&mut self, dx: f32, dy: f32, side: f32) {
        let (w, h) = self.dims();
        self.view = self.view.pan(dx, dy, side, w, h);
    }

    fn pinch(&mut self, delta: f32) {
        let (w, h) = self.dims();
        self.view = self.view.pinch(1.0 + delta, w, h);
    }

    fn toggle_zoom(&mut self) {
        let (w, h) = self.dims();
        self.view = toggled_zoom(self.view, w, h);
    }

    /// A tap landed; a second one close to the first zooms.
    fn tap(&mut self, pos: Point, click_count: usize, now: Instant) {
        let near = self.last_tap.is_some_and(|(p, at)| {
            now.saturating_duration_since(at) < DOUBLE_TAP
                && (p.x - pos.x).hypot(p.y - pos.y) < DOUBLE_TAP_SLOP
        });
        if click_count >= 2 || near {
            self.last_tap = None;
            self.toggle_zoom();
        } else {
            self.last_tap = Some((pos, now));
        }
    }

    fn press(&mut self, pos: Point) {
        self.gesture = Some(Gesture {
            last: pos,
            moved: 0.0,
            start: pos,
        });
    }

    /// The finger moved: the picture follows it.
    fn drag(&mut self, pos: Point, side: f32) {
        let Some(g) = &mut self.gesture else {
            return;
        };
        let (dx, dy) = (pos.x - g.last.x, pos.y - g.last.y);
        g.moved = (pos.x - g.start.x).hypot(pos.y - g.start.y);
        g.last = pos;
        self.pan(dx, dy, side);
    }

    /// The finger lifted; true when it was a tap.
    fn release(&mut self) -> bool {
        self.gesture.take().is_some_and(|g| g.moved <= TAP_SLOP)
    }
}

impl ContactsApp {
    /// Opens the crop page for the image at `path` and reads it behind it.
    pub(crate) fn open_crop(&mut self, target: CropTarget, path: String, cx: &mut Context<Self>) {
        let page = CropPage::new(target, path.clone());
        let token = page.token;
        self.crop = Some(page);
        self.clear_notice();
        cx.spawn(async move |this, cx| {
            let loaded = cx
                .background_executor()
                .spawn(async move { backend::load_crop_image(&path) })
                .await;
            this.update(cx, |this, cx| {
                if this.crop.as_ref().is_some_and(|c| c.token == token) {
                    match loaded {
                        Some(pixels) => {
                            if let Some(crop) = &mut this.crop {
                                crop.set_loaded(pixels);
                            }
                        }
                        None => {
                            this.crop = None;
                            this.notice =
                                Some("Can't change the photo: that image can't be read.".into());
                        }
                    }
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// "Crop current photo" in the viewer's menu: crops the full photo.
    pub(crate) fn crop_current_photo(&mut self, cx: &mut Context<Self>) {
        self.photo_sheet.close();
        let Some((id, file)) = self
            .viewer
            .as_ref()
            .filter(|v| v.can_edit())
            .and_then(|v| Some((v.contact_id, v.file.clone()?)))
        else {
            cx.notify();
            return;
        };
        // Android deletes the file it crops, and the viewer still needs this one
        // for Save and Share: crop a copy.
        let copy = std::env::temp_dir()
            .join("rovsti-contacts")
            .join(format!("crop-{id}.png"));
        let copied = copy
            .parent()
            .is_some_and(|dir| std::fs::create_dir_all(dir).is_ok())
            && std::fs::copy(&file, &copy).is_ok();
        if !copied {
            self.notice = Some("Can't change the photo: that image can't be read.".into());
            cx.notify();
            return;
        }
        self.open_crop(
            CropTarget::Contact(id),
            copy.to_string_lossy().into_owned(),
            cx,
        );
    }

    pub(crate) fn crop_cancel(&mut self, cx: &mut Context<Self>) {
        if self.crop.take().is_some() {
            cx.notify();
        }
    }

    fn crop_rotate(&mut self, cx: &mut Context<Self>) {
        if let Some(crop) = &mut self.crop {
            crop.rotate();
            cx.notify();
        }
    }

    fn crop_reset(&mut self, cx: &mut Context<Self>) {
        if let Some(crop) = &mut self.crop {
            crop.reset();
            cx.notify();
        }
    }

    /// Use photo: a saved contact gets it now, a new one at Save.
    fn crop_use(&mut self, cx: &mut Context<Self>) {
        if !self.crop.as_ref().is_some_and(CropPage::ready) {
            return;
        }
        let Some(crop) = self.crop.take() else {
            return;
        };
        match crop.target {
            CropTarget::Contact(id) => self.apply_photo_with(id, crop.path, crop.view, cx),
            CropTarget::NewContact => {
                if let Some(editor) = self.editor.as_mut() {
                    editor.pending_photo = Some((crop.path, crop.view));
                    self.notice = Some("The photo is saved with the contact.".into());
                }
            }
        }
        cx.notify();
    }

    fn crop_pinch(&mut self, event: &PinchEvent, cx: &mut Context<Self>) {
        if let Some(crop) = &mut self.crop {
            if matches!(event.phase, TouchPhase::Started | TouchPhase::Moved) {
                crop.pinch(event.delta);
            }
            cx.notify();
        }
    }

    fn crop_touch(
        &mut self,
        phase: TouchPhase,
        pos: gpui::Point<gpui::Pixels>,
        window: &Window,
        cx: &mut Context<Self>,
    ) {
        let viewport = viewport_of(window);
        let side = frame_side(viewport.w, viewport.h);
        let pos = point_of(pos);
        let Some(crop) = &mut self.crop else {
            return;
        };
        match phase {
            // Fingers on the bar belong to the buttons.
            TouchPhase::Started if pos.y > viewport.h - BAR_ZONE => return,
            TouchPhase::Started => {
                crop.touch_seen = true;
                crop.guard.begin(pos.x, pos.y, 0.0);
                crop.press(pos);
            }
            TouchPhase::Moved => {
                crop.guard.moved(pos.x, pos.y);
                crop.drag(pos, side);
            }
            TouchPhase::Ended | TouchPhase::Cancelled => {
                crop.guard.finish(pos.x, pos.y);
                // The tap itself arrives as the synthesised mouse press.
                crop.release();
            }
        }
        cx.notify();
    }

    fn crop_mouse_down(&mut self, event: &MouseDownEvent, cx: &mut Context<Self>) {
        let pos = point_of(event.position);
        let Some(crop) = &mut self.crop else {
            return;
        };
        if crop.touch_seen {
            if crop.guard.allow_click(0.0) {
                crop.tap(pos, event.click_count, Instant::now());
            }
        } else {
            crop.press(pos);
            crop.pending_clicks = event.click_count;
        }
        cx.notify();
    }

    fn crop_mouse_move(&mut self, event: &MouseMoveEvent, window: &Window, cx: &mut Context<Self>) {
        if event.pressed_button != Some(MouseButton::Left) {
            return;
        }
        let viewport = viewport_of(window);
        let side = frame_side(viewport.w, viewport.h);
        if let Some(crop) = &mut self.crop
            && !crop.touch_seen
        {
            crop.drag(point_of(event.position), side);
            cx.notify();
        }
    }

    fn crop_mouse_up(&mut self, event: &MouseUpEvent, cx: &mut Context<Self>) {
        let pos = point_of(event.position);
        let Some(crop) = &mut self.crop else {
            return;
        };
        if crop.touch_seen {
            return;
        }
        let clicks = std::mem::take(&mut crop.pending_clicks);
        if crop.release() {
            crop.tap(pos, clicks, Instant::now());
        }
        cx.notify();
    }

    /// A button of the bottom bar.
    fn crop_button(
        theme: Theme,
        id: &'static str,
        label: &'static str,
        icon: Option<Icon>,
        accent: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let tint = if accent { colors.accent } else { colors.label };
        let mut face = div()
            .h(px(44.0))
            .rounded_full()
            .flex()
            .items_center()
            .justify_center();
        face = match icon {
            Some(icon) => face.w(px(44.0)).child(
                gpui::svg()
                    .path(icon.path())
                    .size(px(22.0))
                    .text_color(tint),
            ),
            None => face.px(px(14.0)).child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(tint)
                    .child(label),
            ),
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
            .on_click(cx.listener(move |this, _, _, cx| {
                match id {
                    "pc-rotate" => this.crop_rotate(cx),
                    "pc-reset" => this.crop_reset(cx),
                    "pc-cancel" => this.crop_cancel(cx),
                    _ => this.crop_use(cx),
                }
                cx.notify();
            }))
            .child(face)
            .into_any_element()
    }

    /// The page, above the viewer and the editor and under the pill and sheets.
    pub(crate) fn photo_crop_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let crop = self.crop.as_ref()?;
        let colors = theme::colors(theme);
        let viewport = viewport_of(window);
        let side = frame_side(viewport.w, viewport.h);
        let fx = (viewport.w - side) / 2.0;
        let fy = ((viewport.h - side) / 2.0 - 24.0)
            .max(f32::from(window.insets().effective().top) + 64.0);
        let dim = gpui::Hsla {
            a: 0.62,
            ..gpui::black()
        };
        let entity = cx.entity();
        let touch = cx.entity();

        let (w, h) = crop.dims();
        let photo = crop.shown.clone().map(|image| {
            let (x, y, iw, ih) = image_in_frame(side, w, h, crop.view);
            gpui::img(image)
                .absolute()
                .left(px(fx + x))
                .top(px(fy + y))
                .w(px(iw))
                .h(px(ih))
                .object_fit(ObjectFit::Fill)
        });
        let shade = |l: f32, t: f32, w: f32, h: f32| {
            div()
                .absolute()
                .left(px(l))
                .top(px(t))
                .w(px(w.max(0.0)))
                .h(px(h.max(0.0)))
                .bg(dim)
        };
        let bottom_y = fy + side;
        let frame = div()
            .absolute()
            .left(px(fx))
            .top(px(fy))
            .size(px(side))
            .rounded(px(12.0))
            .border_2()
            .border_color(gpui::Hsla {
                a: 0.9,
                ..gpui::white()
            });

        let surface = div()
            .id("pc-surface")
            .absolute()
            .inset_0()
            .on_pinch(cx.listener(|this, event: &PinchEvent, _, cx| {
                this.crop_pinch(event, cx);
            }))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, event: &MouseDownEvent, _, cx| {
                    this.crop_mouse_down(event, cx);
                }),
            )
            .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, window, cx| {
                this.crop_mouse_move(event, window, cx);
            }))
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, event: &MouseUpEvent, _, cx| {
                    this.crop_mouse_up(event, cx);
                }),
            )
            .child(drag_surface(move |phase, pos, window, cx| {
                touch.update(cx, |this, cx| this.crop_touch(phase, pos, window, cx));
            }));
        // Escape cancels on a desktop keyboard.
        let escape = canvas(
            |_, _, _| (),
            move |_, (), window, _| {
                let entity = entity.clone();
                window.on_key_event(move |event: &KeyDownEvent, phase, _, cx| {
                    if phase.bubble() && event.keystroke.key == "escape" {
                        entity.update(cx, |this, cx| this.crop_cancel(cx));
                    }
                });
            },
        )
        .absolute()
        .size_full();

        let top = f32::from(window.insets().effective().top).max(20.0) + 12.0;
        let title = div()
            .absolute()
            .top(px(top))
            .left_0()
            .right_0()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(2.0))
            .child(
                div()
                    .text_size(typography::HEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.label)
                    .child("Move and scale"),
            )
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(if crop.ready() {
                        "Drag, pinch or double-tap"
                    } else {
                        "Loading the photo…"
                    }),
            );

        let bottom = f32::from(content_bottom_inset()) + 16.0;
        let bar = div()
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
                    .gap(px(2.0))
                    .child(Self::crop_button(
                        theme,
                        "pc-rotate",
                        "Rotate",
                        Some(Icon::LucideRotateCw),
                        false,
                        cx,
                    ))
                    .child(Self::crop_button(
                        theme,
                        "pc-reset",
                        "Reset",
                        Some(Icon::LucideUndo2),
                        false,
                        cx,
                    ))
                    .child(Self::crop_button(
                        theme,
                        "pc-cancel",
                        "Cancel",
                        None,
                        false,
                        cx,
                    ))
                    .child(Self::crop_button(
                        theme,
                        "pc-use",
                        "Use photo",
                        None,
                        true,
                        cx,
                    )),
            );

        Some(
            div()
                .id("photo-crop")
                .role(Role::Dialog)
                .aria_label(SharedString::from("Crop photo"))
                .absolute()
                .inset_0()
                .occlude()
                .overflow_hidden()
                .bg(gpui::black())
                .children(photo)
                .child(shade(0.0, 0.0, viewport.w, fy))
                .child(shade(0.0, bottom_y, viewport.w, viewport.h - bottom_y))
                .child(shade(0.0, fy, fx, side))
                .child(shade(fx + side, fy, viewport.w - fx - side, side))
                .child(frame)
                .child(surface)
                .child(title)
                .child(bar)
                .child(escape)
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::contact_photo::plan_for;

    use super::*;

    const SIDE: f32 = 300.0;

    fn view(turns: u8, zoom: f32, cx: f32, cy: f32) -> CropView {
        CropView {
            turns,
            zoom,
            cx,
            cy,
        }
    }

    #[test]
    fn the_frame_matches_the_crop_plan_at_every_turn() {
        for (w, h) in [(4000, 3000), (3000, 4000), (1000, 1000), (1440, 810)] {
            for turns in 0..4 {
                for (zoom, px_, py_) in [(1.0, 0.0, 0.0), (2.0, 40.0, -25.0), (3.5, -90.0, 60.0)] {
                    let mut v = view(turns, 1.0, 0.5, 0.5);
                    v = v.pinch(zoom, w, h).pan(px_, py_, SIDE, w, h);
                    let plan = plan_for(w, h, v).expect("plan");
                    let (x, y, side) = frame_rect(SIDE, w, h, v);
                    assert!((side - SIDE).abs() < 1e-3);
                    // Drawn px per source px: the crop side fills the frame.
                    let short = rotated_size(w, h, turns);
                    let k = SIDE * v.zoom / short.0.min(short.1) as f32;
                    assert!(
                        (x / k - plan.x as f32).abs() <= 1.5,
                        "x {w}x{h} t{turns} z{zoom}: {} vs {}",
                        x / k,
                        plan.x
                    );
                    assert!(
                        (y / k - plan.y as f32).abs() <= 1.5,
                        "y {w}x{h} t{turns} z{zoom}: {} vs {}",
                        y / k,
                        plan.y
                    );
                }
            }
        }
    }

    #[test]
    fn at_rest_the_short_side_fills_the_frame_centred() {
        let (x, y, w, h) = image_in_frame(SIDE, 400, 200, CropView::default());
        assert_eq!((h, w), (SIDE, 2.0 * SIDE));
        assert!((x + SIDE / 2.0).abs() < 1e-3);
        assert!(y.abs() < 1e-3);
        // A quarter turn swaps the roles.
        let (x, y, w, h) = image_in_frame(SIDE, 400, 200, CropView::default().rotate_right());
        assert_eq!((w, h), (SIDE, 2.0 * SIDE));
        assert!(x.abs() < 1e-3);
        assert!((y + SIDE / 2.0).abs() < 1e-3);
    }

    #[test]
    fn the_picture_never_leaves_a_gap_in_the_frame() {
        for turns in 0..4 {
            let v = view(turns, 2.0, 0.0, 1.0);
            let (x, y, w, h) = image_in_frame(SIDE, 4000, 3000, v);
            assert!(x <= 0.0 && y <= 0.0);
            assert!(x + w >= SIDE - 1e-3 && y + h >= SIDE - 1e-3);
        }
    }

    #[test]
    fn an_empty_picture_draws_nothing() {
        assert_eq!(
            image_in_frame(SIDE, 0, 0, CropView::default()),
            (0.0, 0.0, 0.0, 0.0)
        );
    }

    #[test]
    fn double_tap_toggles_between_one_and_two() {
        let v = toggled_zoom(CropView::default(), 1000, 800);
        assert!((v.zoom - 2.0).abs() < 1e-4);
        let v = toggled_zoom(v, 1000, 800);
        assert!((v.zoom - 1.0).abs() < 1e-4);
    }

    #[test]
    fn the_menu_lists_crop_and_remove_only_with_a_photo() {
        use PhotoSource::*;
        assert_eq!(
            photo_source_choices(false),
            vec![TakePhoto, ChooseFromGallery]
        );
        assert_eq!(
            photo_source_choices(true),
            vec![TakePhoto, ChooseFromGallery, CropCurrent, Remove]
        );
        assert_eq!(Remove.label(), "Remove photo");
        assert_eq!(TakePhoto.label(), "Take photo");
    }

    #[test]
    fn camera_failures_are_worded() {
        assert_eq!(capture_notice(backend::NO_CAMERA), "No camera app found");
        assert_eq!(
            capture_notice("this needs an Android phone"),
            "Can't take a photo: this needs an Android phone."
        );
    }

    #[test]
    fn the_frame_fits_the_screen_with_a_minimum() {
        assert_eq!(frame_side(400.0, 900.0), 352.0);
        assert_eq!(frame_side(400.0, 500.0), 170.0);
        assert_eq!(frame_side(300.0, 300.0), FRAME_MIN);
    }

    #[test]
    fn turning_swaps_the_picture_size() {
        let p = image::RgbaImage::new(30, 10);
        assert_eq!(turned(&p, 0).dimensions(), (30, 10));
        assert_eq!(turned(&p, 1).dimensions(), (10, 30));
        assert_eq!(turned(&p, 2).dimensions(), (30, 10));
        assert_eq!(turned(&p, 3).dimensions(), (10, 30));
    }

    #[test]
    fn a_double_tap_zooms_and_a_slow_second_tap_does_not() {
        let mut page = CropPage::new(CropTarget::NewContact, "/c/p".into());
        page.set_loaded(image::RgbaImage::new(400, 300));
        let (p, t0) = (Point { x: 10.0, y: 10.0 }, Instant::now());
        page.tap(p, 1, t0);
        page.tap(p, 1, t0 + Duration::from_millis(100));
        assert!(page.view.zoom > 1.9);
        page.tap(p, 1, t0 + Duration::from_secs(5));
        page.tap(p, 1, t0 + Duration::from_secs(9));
        assert!(page.view.zoom > 1.9);
        page.reset();
        assert_eq!(page.view, CropView::default());
        page.rotate();
        assert_eq!(page.view.turns, 1);
        page.reset();
        assert_eq!(page.view.turns, 0);
    }
}
