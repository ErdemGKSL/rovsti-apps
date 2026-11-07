//! Contact photos in the Contacts lists and on the contact card.
//!
//! The monogram always draws first. A photo is requested only for avatars on
//! screen, decoded on the background executor to at most [`SIZE`] px, kept as
//! an `Arc<RenderImage>` in a [`CAPACITY`]-entry LRU keyed by contact id, and
//! faded in over the monogram. Painting an avatar never decodes: it clones an
//! `Arc`. The same scheme as Rovsti Phone's `photos` module.

use std::{
    collections::HashSet,
    sync::Arc,
    time::{Duration, Instant},
};

use gpui::{
    App, Context, Div, Hsla, ObjectFit, ParentElement, RenderImage, SharedString, Styled,
    StyledImage, px,
};
use rovsti_core::lru::Lru;
use rovsti_ui::tokens::Theme;
use rovsti_ui::{Icon, components::avatar};

use crate::{ContactsApp, backend};

/// Longest side of a decoded thumbnail.
pub(crate) const SIZE: u32 = 192;
/// Decoded photos kept in memory.
const CAPACITY: usize = 64;
/// Decodes running at once; the rest wait for the next frame's request.
const MAX_PARALLEL: usize = 3;
/// A photo fades in over the monogram this long.
const FADE: Duration = Duration::from_millis(220);

struct Entry {
    image: Arc<RenderImage>,
    since: Instant,
}

pub(crate) struct Photos {
    cache: Lru<i64, Entry>,
    pending: HashSet<i64>,
    /// Contacts whose photo could not be read; retried after the next pull.
    missing: HashSet<i64>,
    last_arrival: Option<Instant>,
}

impl Photos {
    pub(crate) fn new() -> Self {
        Self {
            cache: Lru::new(CAPACITY),
            pending: HashSet::new(),
            missing: HashSet::new(),
            last_arrival: None,
        }
    }

    /// The contacts changed: photos that failed may exist now.
    pub(crate) fn forget_misses(&mut self) {
        self.missing.clear();
    }

    /// The photo of `id` changed: drop its decoded copy and its miss mark so
    /// the next request loads the new thumbnail.
    pub(crate) fn forget(&mut self, id: i64) {
        self.cache.remove(&id);
        self.missing.remove(&id);
        self.pending.remove(&id);
    }

    /// The photo of `id` and its opacity (0 to 1 over [`FADE`]), if decoded.
    pub(crate) fn get(&self, id: i64) -> Option<(&Arc<RenderImage>, f32)> {
        self.cache.peek(&id).map(|entry| {
            let t = entry.since.elapsed().as_secs_f32() / FADE.as_secs_f32();
            (&entry.image, ease(t))
        })
    }

    /// Marks the photo of `id` as used, and says whether a decode for it
    /// should start now (it is not held, not on its way, not known missing,
    /// and a decode slot is free). Lets callers skip building a request.
    pub(crate) fn needs(&mut self, id: i64) -> bool {
        self.cache.get(&id).is_none()
            && !self.pending.contains(&id)
            && !self.missing.contains(&id)
            && self.pending.len() < MAX_PARALLEL
    }

    /// Whether the WhatsApp picture stands in for contact `id`: it has no
    /// photo of its own (`has_photo` is false), or that photo can't be read.
    pub(crate) fn wa_stands_in(&self, id: i64, has_photo: bool) -> bool {
        !has_photo || self.missing.contains(&id)
    }

    /// A photo is still fading in (keep frames coming).
    pub(crate) fn fading(&self) -> bool {
        self.last_arrival.is_some_and(|at| at.elapsed() < FADE)
    }

    fn arrived(&mut self, id: i64, image: Option<Arc<RenderImage>>) {
        self.pending.remove(&id);
        match image {
            Some(image) => {
                let now = Instant::now();
                self.cache.insert(id, Entry { image, since: now });
                self.last_arrival = Some(now);
            }
            None => {
                self.missing.insert(id);
            }
        }
    }
}

fn ease(t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    1.0 - (1.0 - t) * (1.0 - t)
}

/// RGBA pixels as the GPU image gpui draws (BGRA).
pub(crate) fn render_image(mut pixels: image::RgbaImage) -> RenderImage {
    for pixel in pixels.as_chunks_mut::<4>().0 {
        pixel.swap(0, 2);
    }
    RenderImage::new(vec![image::Frame::new(pixels)])
}

/// An avatar: the monogram on its tint, with `photo` faded in over it.
pub(crate) fn photo_avatar(
    theme: Theme,
    monogram: impl Into<SharedString>,
    size: f32,
    tint: Hsla,
    photo: Option<(&Arc<RenderImage>, f32)>,
) -> Div {
    let monogram = monogram.into();
    let base = if monogram.is_empty() {
        // No letter to show: the neutral person glyph.
        avatar(theme, "", size, tint).child(
            gpui::svg()
                .path(Icon::LucideUser.path())
                .size(px(size * 0.5))
                .text_color(gpui::white()),
        )
    } else {
        avatar(theme, monogram, size, tint)
    };
    base.relative().children(photo.map(|(image, opacity)| {
        gpui::img(Arc::clone(image))
            .absolute()
            .top_0()
            .left_0()
            .size(px(size))
            .rounded_full()
            .object_fit(ObjectFit::Cover)
            .opacity(opacity)
    }))
}

impl ContactsApp {
    /// The picture to draw for contact `id`: its own photo, else (when it has
    /// none or it can't be read) its WhatsApp picture, else `None` (the
    /// monogram). `wa_jid` is the contact's WhatsApp JID, when known. Cheap:
    /// clones an `Arc`.
    pub(crate) fn avatar_picture(
        &self,
        id: i64,
        has_photo: bool,
        wa_jid: Option<&str>,
        cx: &App,
    ) -> Option<(Arc<RenderImage>, f32)> {
        if has_photo && let Some((image, opacity)) = self.photos.get(id) {
            return Some((Arc::clone(image), opacity));
        }
        let jid = wa_jid.filter(|_| self.photos.wa_stands_in(id, has_photo))?;
        self.avatars.read(cx).picture(jid)
    }

    /// Asks for the WhatsApp picture of `jid` when it stands in for the
    /// photo of contact `id`. Cheap when nothing is due.
    pub(crate) fn want_wa_picture(
        &mut self,
        id: i64,
        has_photo: bool,
        jid: &str,
        cx: &mut Context<Self>,
    ) {
        if !self.photos.wa_stands_in(id, has_photo) {
            return;
        }
        self.avatars.update(cx, |avatars, cx| {
            let picture_id = avatars.profile(jid).and_then(|p| p.picture_id.clone());
            avatars.want(jid, picture_id.as_deref(), cx);
        });
    }

    /// Marks the avatars about to be drawn as used and starts decoding the
    /// ones that are missing. `wanted` is `(contact id, photo uri)`.
    pub(crate) fn want_photos<'a>(
        &mut self,
        wanted: impl IntoIterator<Item = (i64, &'a str)>,
        cx: &mut Context<Self>,
    ) {
        for (id, uri) in wanted {
            let photos = &mut self.photos;
            if !photos.needs(id) {
                continue;
            }
            photos.pending.insert(id);
            let uri = uri.to_owned();
            cx.spawn(async move |this, cx| {
                let decoded = cx
                    .background_executor()
                    .spawn(async move {
                        backend::contact_photo(&uri, SIZE)
                            .map(|pixels| Arc::new(render_image(pixels)))
                    })
                    .await;
                this.update(cx, |this, cx| {
                    this.photos.arrived(id, decoded);
                    cx.notify();
                })
                .ok();
            })
            .detach();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fade_runs_from_zero_to_one() {
        assert_eq!(ease(0.0), 0.0);
        assert_eq!(ease(1.0), 1.0);
        assert_eq!(ease(5.0), 1.0);
        assert!(ease(0.5) > 0.5, "ease-out starts fast");
    }

    #[test]
    fn channels_are_swapped_for_the_gpu() {
        let mut px = image::RgbaImage::new(1, 1);
        px.put_pixel(0, 0, image::Rgba([10, 20, 30, 255]));
        let image = render_image(px);
        assert_eq!(image.as_bytes(0), Some(&[30, 20, 10, 255][..]));
    }

    #[test]
    fn misses_are_not_retried_until_forgotten() {
        let mut photos = Photos::new();
        photos.pending.insert(7);
        photos.arrived(7, None);
        assert!(photos.missing.contains(&7) && photos.pending.is_empty());
        photos.forget_misses();
        assert!(photos.missing.is_empty());
    }

    #[test]
    fn forget_clears_the_cache_and_the_miss_of_that_id_only() {
        let mut photos = Photos::new();
        photos.arrived(1, Some(Arc::new(render_image(image::RgbaImage::new(1, 1)))));
        photos.arrived(2, Some(Arc::new(render_image(image::RgbaImage::new(1, 1)))));
        photos.arrived(3, None);
        photos.arrived(4, None);
        photos.forget(1);
        photos.forget(3);
        assert!(photos.get(1).is_none(), "the old thumbnail is gone");
        assert!(photos.get(2).is_some(), "others stay");
        assert!(!photos.missing.contains(&3), "the miss mark is gone");
        assert!(photos.missing.contains(&4));
    }

    #[test]
    fn the_cache_holds_sixty_four_photos() {
        let mut photos = Photos::new();
        for id in 0..70 {
            let pixels = image::RgbaImage::new(1, 1);
            photos.arrived(id, Some(Arc::new(render_image(pixels))));
        }
        assert_eq!(photos.cache.len(), CAPACITY);
        assert!(photos.get(0).is_none(), "the oldest was evicted");
        assert!(photos.get(69).is_some());
    }
}
