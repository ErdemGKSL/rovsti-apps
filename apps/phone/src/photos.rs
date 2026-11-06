//! Contact photos (story 10.5).
//!
//! The monogram always draws first. A photo is requested only for avatars on
//! screen, decoded on the background executor to at most 128 px, kept as an
//! `Arc<RenderImage>` in a 64-entry LRU keyed by contact id, and faded in over
//! the monogram. Painting an avatar never decodes: it clones an `Arc`, and the
//! GPU upload happens once per image (gpui keeps it in the atlas until the
//! `Arc` is dropped by an eviction).

use std::{
    collections::HashSet,
    sync::Arc,
    time::{Duration, Instant},
};

use gpui::{Context, RenderImage, SharedString};
use rovsti_core::lru::Lru;

use crate::{PhoneApp, backend};

/// Longest side of a decoded thumbnail.
pub const SIZE: u32 = 128;
/// Decoded photos kept in memory.
pub const CAPACITY: usize = 64;
/// Decodes running at once; the rest wait for the next frame's request.
const MAX_PARALLEL: usize = 3;
/// A photo fades in over the monogram this long.
pub const FADE: Duration = Duration::from_millis(220);

struct Entry {
    image: Arc<RenderImage>,
    since: Instant,
}

pub(crate) struct Photos {
    cache: Lru<i64, Entry>,
    pending: HashSet<i64>,
    /// Contacts whose photo could not be read; retried after the next
    /// contacts snapshot.
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

    /// Cache size (for tests).
    #[cfg(test)]
    pub(crate) fn len_for_tests(&self) -> usize {
        self.cache.len()
    }

    /// Decodes nothing: pushes `count` synthetic 1 px photos through the same
    /// path a decode lands on, reading some back on the way.
    #[cfg(test)]
    pub(crate) fn stress_for_tests(&mut self, count: i64) {
        for id in 0..count {
            self.arrived(
                id,
                Some(Arc::new(render_image(image::RgbaImage::new(1, 1)))),
            );
            let _ = self.get(id / 2);
            let _ = self.cache.get(&(id / 3));
        }
    }

    /// Drops what is known about the photo of `id`, so the next draw decodes
    /// it again (its photo was changed or removed).
    pub(crate) fn forget(&mut self, id: i64) {
        self.cache.remove(&id);
        self.missing.remove(&id);
    }

    /// The contacts changed: photos that failed may exist now.
    pub(crate) fn forget_misses(&mut self) {
        self.missing.clear();
    }

    /// The photo of `id` and its opacity (0 to 1 over [`FADE`]), if decoded.
    pub(crate) fn get(&self, id: i64) -> Option<(&Arc<RenderImage>, f32)> {
        self.cache.peek(&id).map(|entry| {
            let t = entry.since.elapsed().as_secs_f32() / FADE.as_secs_f32();
            (&entry.image, ease(t))
        })
    }

    /// The photo of `id` could not be read (until the next contacts snapshot).
    pub(crate) fn is_missing(&self, id: i64) -> bool {
        self.missing.contains(&id)
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

impl PhoneApp {
    /// Marks the avatars about to be drawn as used and starts decoding the
    /// ones that are missing. `wanted` is `(contact id, photo uri)`.
    pub(crate) fn want_photos<'a>(
        &mut self,
        wanted: impl IntoIterator<Item = (i64, &'a SharedString)>,
        cx: &mut Context<Self>,
    ) {
        for (id, uri) in wanted {
            let photos = &mut self.photos;
            if photos.cache.get(&id).is_some()
                || photos.pending.contains(&id)
                || photos.missing.contains(&id)
                || photos.pending.len() >= MAX_PARALLEL
            {
                continue;
            }
            photos.pending.insert(id);
            let uri = uri.clone();
            cx.spawn(async move |this, cx| {
                let decoded = cx
                    .background_executor()
                    .spawn(async move {
                        backend::contact_photo(&uri).map(|px| Arc::new(render_image(px)))
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
